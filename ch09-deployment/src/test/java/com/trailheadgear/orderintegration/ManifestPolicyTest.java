package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * T4 (design 006 §3.14; D3-D9 §3.4-§3.9; plan P-11): the deployment's manifests keep the rules the process relies on.
 * A plain reading of the YAML, no Kubernetes client and no cluster.
 *
 * <p>Input: every document of the {@code *.yaml}/{@code *.yml} files under the directory of the system property
 * {@code manifest-policy.dir} (a single file is accepted too). Without the property the input is
 * {@code deploy/k8s/base} plus {@code deploy/k8s/release/kustomization.yaml} (the image the release deploys), and the
 * Secret rule searches all of {@code deploy/k8s}. An explicit input is taken as a whole deployment, for example
 * {@code -Dmanifest-policy.dir=deploy/local/lab/k8s/today.yaml} (today's deployment, the red run).
 *
 * <p>Rendered input (validation 006, decisions 2 and 3): {@code -Dmanifest-policy.rendered=<file>} is the output of
 * {@code kubectl kustomize deploy/k8s/release}, so a field set by an overlay, a patch or a generator of any
 * {@code kustomization.yaml} is checked as it is deployed. Every rule runs on it; the generator rule then checks the
 * rendered ConfigMap's hashed name, and the overlay rule (raw files only) does not apply.
 * {@code -Dmanifest-policy.rendered-also=<file>[,<file>]} adds other renders (the CI overlay, which deploys a variant
 * of the release with its own image tag, JVM options and systems) to the two secret rules only. The gate's
 * {@code manifests} job renders both with {@code kubectl kustomize}; locally:
 * <pre>
 *   deploy/local/kube.sh kustomize deploy/k8s/release &gt; target/k8s/release.yaml
 *   deploy/local/kube.sh kustomize deploy/k8s/ci &gt; target/k8s/ci.yaml
 *   ./mvnw -B -DskipITs test -Dtest=ManifestPolicyTest -Dmanifest-policy.rendered=target/k8s/release.yaml \
 *       -Dmanifest-policy.rendered-also=target/k8s/ci.yaml
 * </pre>
 *
 * <p>The management port and Camel's shutdown timeout are read from the shipped
 * {@code src/main/resources/application.properties}, so the manifests and the process cannot drift apart. Each rule
 * reports every violation with the file, the resource and the field.
 */
class ManifestPolicyTest {

    private static final String DIR_PROPERTY = "manifest-policy.dir";
    private static final String RENDERED_PROPERTY = "manifest-policy.rendered";
    private static final String RENDERED_ALSO_PROPERTY = "manifest-policy.rendered-also";
    private static final Path DEFAULT_DIR = Path.of("deploy", "k8s", "base");
    private static final Path RELEASE_KUSTOMIZATION = Path.of("deploy", "k8s", "release", "kustomization.yaml");
    private static final Path ALL_MANIFESTS = Path.of("deploy", "k8s");
    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    private static final Set<String> WORKLOAD_KINDS = Set.of("Pod", "Deployment", "StatefulSet", "DaemonSet",
        "ReplicaSet", "ReplicationController", "Job", "CronJob");
    private static final Set<String> FORBIDDEN_KINDS = Set.of("Deployment", "HorizontalPodAutoscaler",
        "PodDisruptionBudget");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Set<String> RELEASE_KEYS = Set.of("apiVersion", "kind", "resources", "images");
    /** A name that looks like a secret's (validation 006, decision 2): its value never comes from a manifest. */
    private static final Pattern SECRET_NAME = Pattern.compile("PASSWORD|SECRET|TOKEN|KEY|CREDENTIAL",
        Pattern.CASE_INSENSITIVE);
    private static final String PARTNERS_CONFIGMAP = "order-integration-partners";
    /** kustomize's content hash suffix of a generated name. */
    private static final Pattern NAME_HASH = Pattern.compile("-[a-z0-9]{10}");

    private static final int PARTNER_PORT = 8443;
    private static final int SPRING_WEB_SHUTDOWN_SECONDS = 30;
    private static final String SECRET_STORE_DIR = "/etc/trailhead/secret-store";
    private static final String PARTNERS_DIR = "/etc/trailhead/partners";
    private static final String PARTNER_CONFIGURATION = PARTNERS_DIR + "/partner-configuration.json";
    private static final String FILES_DIR = "/etc/trailhead/files";

    private static Path base;
    private static boolean defaultInput;
    private static boolean rendered;
    private static String input;
    private static List<Doc> resources;
    private static List<Doc> kustomizations;
    private static List<Doc> secretScope;
    private static Properties properties;

    /** One YAML document and the file it came from (relative to the project). */
    private record Doc(String file, Map<?, ?> node) {
        String kind() {
            return Objects.toString(node.get("kind"), "");
        }

        String name() {
            return text(get(node, "metadata", "name"));
        }

        String where() {
            return file + " " + kind() + "/" + name();
        }
    }

    @BeforeAll
    static void load() throws IOException {
        base = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        String dir = System.getProperty(DIR_PROPERTY, "");
        String render = System.getProperty(RENDERED_PROPERTY, "");
        String renderAlso = System.getProperty(RENDERED_ALSO_PROPERTY, "");
        rendered = !render.isBlank();
        assertTrue(rendered || renderAlso.isBlank(), "T4: " + RENDERED_ALSO_PROPERTY + " needs " + RENDERED_PROPERTY);
        assertTrue(!rendered || dir.isBlank(), "T4: " + DIR_PROPERTY + " and " + RENDERED_PROPERTY + " exclude each other");
        defaultInput = dir.isBlank() && !rendered;
        Path in = rendered ? base.resolve(render).normalize()
            : defaultInput ? base.resolve(DEFAULT_DIR) : base.resolve(dir).normalize();
        assertTrue(rendered ? Files.isRegularFile(in) : Files.exists(in), "T4: manifest input " + in + " does not exist");
        input = base.relativize(in).toString();

        List<Doc> docs = read(yamlFiles(in));
        if (defaultInput && Files.isRegularFile(base.resolve(RELEASE_KUSTOMIZATION))) {
            docs.addAll(read(List.of(base.resolve(RELEASE_KUSTOMIZATION))));
        }
        kustomizations = docs.stream().filter(ManifestPolicyTest::isKustomization).toList();
        resources = docs.stream().filter(d -> !isKustomization(d)).toList();
        assertTrue(!rendered || kustomizations.isEmpty(), "T4: " + input + " is not kustomize output (it holds a "
            + "Kustomization); render it with kubectl kustomize");
        if (rendered) {
            secretScope = new ArrayList<>(docs);
            for (String also : renderAlso.split(",")) {
                if (!also.isBlank()) {
                    Path f = base.resolve(also.trim()).normalize();
                    assertTrue(Files.isRegularFile(f), "T4: rendered input " + f + " does not exist");
                    secretScope.addAll(read(List.of(f)));
                }
            }
        } else {
            secretScope = defaultInput ? read(yamlFiles(base.resolve(ALL_MANIFESTS))) : docs;
        }

        properties = new Properties();
        try (InputStream stream = Files.newInputStream(base.resolve(APPLICATION_PROPERTIES))) {
            properties.load(stream);
        }
    }

    // --- D4: one instance -------------------------------------------------------------------------------------------

    @Test
    void oneStatefulSetWithOneReplicaReplacedStopThenStart() {
        Violations v = new Violations("one instance (D4)");
        List<Doc> workloads = workloads();
        if (workloads.size() != 1) {
            v.add(input, "workloads", "exactly one, a StatefulSet",
                workloads.isEmpty() ? "none" : workloads.stream().map(Doc::where).toList());
        }
        for (Doc w : workloads) {
            v.expect(w, "kind", "StatefulSet", w.kind());
            v.expect(w, "spec.replicas", 1, get(w.node(), "spec", "replicas"));
            v.expect(w, "spec.podManagementPolicy", "OrderedReady", get(w.node(), "spec", "podManagementPolicy"));
            v.expect(w, "spec.updateStrategy.type", "RollingUpdate", get(w.node(), "spec", "updateStrategy", "type"));
        }
        for (Doc d : resources) {
            if (FORBIDDEN_KINDS.contains(d.kind())) {
                v.add(d.where(), "kind", "no Deployment, HorizontalPodAutoscaler or PodDisruptionBudget", d.kind());
            }
        }
        v.assertNone();
    }

    // --- D3: probes on the management port --------------------------------------------------------------------------

    @Test
    void probesUseTheHealthGroupsOnTheManagementPort() {
        Violations v = new Violations("probes (D3)");
        Integer port = intProperty(v, "management.server.port");
        Doc w = workload(v);
        if (w != null && port != null) {
            Map<?, ?> c = container(w);
            probe(v, w, c, "startupProbe", port, "/actuator/health/liveness",
                Map.of("periodSeconds", 5, "failureThreshold", 60));
            probe(v, w, c, "livenessProbe", port, "/actuator/health/liveness",
                Map.of("periodSeconds", 10, "timeoutSeconds", 2, "failureThreshold", 3));
            probe(v, w, c, "readinessProbe", port, "/actuator/health/readiness",
                Map.of("periodSeconds", 5, "failureThreshold", 2));
        }
        v.assertNone();
    }

    // --- D5: stopping -----------------------------------------------------------------------------------------------

    @Test
    void gracePeriodCoversSpringsWebPhaseAndCamelsShutdownTimeout() {
        Violations v = new Violations("grace period (D5)");
        Integer camel = intProperty(v, "camel.main.shutdown-timeout");
        Integer web = webShutdownSeconds(v);
        Doc w = workload(v);
        if (w != null && camel != null && web != null) {
            int needed = web + camel;
            Object grace = get(podSpec(w), "terminationGracePeriodSeconds");
            if (!(grace instanceof Number n) || n.intValue() < needed) {
                v.add(w.where(), "terminationGracePeriodSeconds",
                    ">= " + needed + " (" + web + " s web + camel.main.shutdown-timeout " + camel + " s)",
                    grace == null ? "(absent: 30 by default)" : grace);
            }
        }
        v.assertNone();
    }

    // --- D6: memory and CPU -----------------------------------------------------------------------------------------

    @Test
    void memoryRequestEqualsLimitAndNoCpuLimit() {
        Violations v = new Violations("resources (D6)");
        Doc w = workload(v);
        if (w != null) {
            Map<?, ?> c = container(w);
            v.expect(w, "resources.requests.memory", "1Gi", get(c, "resources", "requests", "memory"));
            v.expect(w, "resources.limits.memory", "1Gi", get(c, "resources", "limits", "memory"));
            v.expect(w, "resources.requests.cpu", "500m", get(c, "resources", "requests", "cpu"));
            v.expect(w, "resources.limits.cpu", null, get(c, "resources", "limits", "cpu"));
        }
        v.assertNone();
    }

    // --- D8: security context ---------------------------------------------------------------------------------------

    @Test
    void podAndContainerRunRestricted() {
        Violations v = new Violations("security context (D8)");
        Doc w = workload(v);
        if (w != null) {
            Map<?, ?> pod = podSpec(w);
            Map<?, ?> c = container(w);
            v.expect(w, "securityContext.runAsNonRoot", true, get(pod, "securityContext", "runAsNonRoot"));
            v.expect(w, "securityContext.runAsUser", 10001, get(pod, "securityContext", "runAsUser"));
            v.expect(w, "securityContext.runAsGroup", 10001, get(pod, "securityContext", "runAsGroup"));
            v.expect(w, "securityContext.seccompProfile.type", "RuntimeDefault",
                get(pod, "securityContext", "seccompProfile", "type"));
            v.expect(w, "automountServiceAccountToken", false, get(pod, "automountServiceAccountToken"));
            String account = text(get(pod, "serviceAccountName"));
            boolean own = account != null && !account.equals("default")
                && resources.stream().anyMatch(d -> d.kind().equals("ServiceAccount") && account.equals(d.name()));
            if (!own) {
                v.add(w.where(), "serviceAccountName", "its own ServiceAccount (not default), defined in " + input,
                    account);
            }
            v.expect(w, "container securityContext.allowPrivilegeEscalation", false,
                get(c, "securityContext", "allowPrivilegeEscalation"));
            v.expect(w, "container securityContext.readOnlyRootFilesystem", true,
                get(c, "securityContext", "readOnlyRootFilesystem"));
            v.expect(w, "container securityContext.capabilities.drop", List.of("ALL"),
                get(c, "securityContext", "capabilities", "drop"));
            Map<?, ?> tmp = volumeAt(pod, c, "/tmp");
            v.expect(w, "volume at /tmp emptyDir.sizeLimit", "256Mi", tmp == null ? null : get(tmp, "emptyDir", "sizeLimit"));
        }
        v.assertNone();
    }

    // --- D9: no dev settings ----------------------------------------------------------------------------------------

    @Test
    void noProfileNoToolOptionsAndNothingMountedUnderApp() {
        Violations v = new Violations("no profile, no JVM tool options, nothing under /app (D9, C3)");
        Set<String> forbidden = Set.of("SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS");
        Doc w = workload(v);
        if (w != null) {
            Map<?, ?> c = container(w);
            for (Object e : list(c.get("env"))) {
                String name = Objects.toString(get(e, "name"), "");
                if (forbidden.contains(name)) {
                    v.add(w.where(), "env " + name, "not set", "set");
                }
            }
            for (Object m : list(c.get("volumeMounts"))) {
                String path = text(get(m, "mountPath"));
                if (path != null && (path.equals("/app") || path.startsWith("/app/"))) {
                    v.add(w.where(), "volumeMounts " + get(m, "name"), "no mount under /app", path);
                }
            }
        }
        for (Doc d : resources) {
            if (d.kind().equals("ConfigMap") && get(d.node(), "data") instanceof Map<?, ?> data) {
                for (Object key : data.keySet()) {
                    if (forbidden.contains(Objects.toString(key, ""))) {
                        v.add(d.where(), "data." + key, "not set", "set");
                    }
                }
            }
        }
        for (Doc k : kustomizations) {
            for (Object g : list(k.node().get("configMapGenerator"))) {
                for (Object literal : list(get(g, "literals"))) {
                    String name = Objects.toString(literal, "").split("=", 2)[0];
                    if (forbidden.contains(name)) {
                        v.add(k.file() + " configMapGenerator/" + get(g, "name"), "literals", "no " + name, "set");
                    }
                }
            }
        }
        v.assertNone();
    }

    // --- D9: where settings, secrets, the Secret Store and the Partner Configuration come from ----------------------

    @Test
    void settingsSecretsSecretStoreAndPartnerConfigurationAreMountedWhereTheProcessReadsThem() {
        Violations v = new Violations("mounts and envFrom (D9)");
        Doc w = workload(v);
        if (w != null) {
            Map<?, ?> pod = podSpec(w);
            Map<?, ?> c = container(w);
            v.expect(w, "volume at " + SECRET_STORE_DIR + " secret.secretName", "order-integration-partner-api-keys",
                secretName(volumeAt(pod, c, SECRET_STORE_DIR)));
            v.expect(w, "env SECRET_STORE_DIR", SECRET_STORE_DIR, env(c, "SECRET_STORE_DIR"));
            Map<?, ?> partners = volumeAt(pod, c, PARTNERS_DIR);
            v.expect(w, "volume at " + PARTNERS_DIR + " kind", "configMap",
                partners == null ? null : partners.containsKey("configMap") ? "configMap" : partners.keySet());
            v.expect(w, "env PARTNER_CONFIGURATION", PARTNER_CONFIGURATION, env(c, "PARTNER_CONFIGURATION"));
            v.expect(w, "volume at " + FILES_DIR + " secret.secretName", "order-integration-files",
                secretName(volumeAt(pod, c, FILES_DIR)));
            List<String> envFrom = list(c.get("envFrom")).stream()
                .map(e -> get(e, "configMapRef", "name") != null ? "configMap " + get(e, "configMapRef", "name")
                    : "secret " + get(e, "secretRef", "name"))
                .toList();
            for (String needed : List.of("configMap order-integration-settings", "secret order-integration-secrets")) {
                if (!envFrom.contains(needed)) {
                    v.add(w.where(), "envFrom", needed, envFrom);
                }
            }
        }
        v.assertNone();
    }

    @Test
    void partnerConfigurationConfigMapIsGeneratedWithAContentHash() {
        Violations v = new Violations("Partner Configuration from a configMapGenerator (D9, C4)");
        Doc w = workload(v);
        if (w != null) {
            Map<?, ?> partners = volumeAt(podSpec(w), container(w), PARTNERS_DIR);
            String name = partners == null ? null : text(get(partners, "configMap", "name"));
            if (rendered) {
                // kustomize output holds no generator: the generated ConfigMap carries the content hash in its name
                Doc map = resources.stream().filter(d -> d.kind().equals("ConfigMap") && d.name() != null
                    && d.name().equals(name)).findFirst().orElse(null);
                if (name == null || !(name.startsWith(PARTNERS_CONFIGMAP)
                    && NAME_HASH.matcher(name.substring(PARTNERS_CONFIGMAP.length())).matches())) {
                    v.add(w.where(), "volume at " + PARTNERS_DIR + " configMap.name", PARTNERS_CONFIGMAP
                        + "-<content hash> (the hash makes a changed file a new pod template)", name);
                } else if (map == null || !(get(map.node(), "data") instanceof Map<?, ?> data)
                    || !data.containsKey("partner-configuration.json")) {
                    v.add(input, "ConfigMap/" + name, "data partner-configuration.json",
                        map == null ? "(absent)" : get(map.node(), "data") instanceof Map<?, ?> m ? m.keySet() : null);
                }
                v.assertNone();
                return;
            }
            Object generator = null;
            Doc owner = null;
            for (Doc k : kustomizations) {
                for (Object g : list(k.node().get("configMapGenerator"))) {
                    if (name != null && name.equals(text(get(g, "name")))) {
                        generator = g;
                        owner = k;
                    }
                }
            }
            if (generator == null) {
                v.add(w.where(), "volume at " + PARTNERS_DIR + " configMap.name",
                    "a configMapGenerator of a kustomization in " + input, name);
            } else {
                boolean fromFile = list(get(generator, "files")).stream()
                    .anyMatch(f -> text(f).endsWith("partner-configuration.json"));
                if (!fromFile) {
                    v.add(owner.file() + " configMapGenerator/" + name, "files", "partner-configuration.json",
                        get(generator, "files"));
                }
                if (Boolean.TRUE.equals(get(generator, "options", "disableNameSuffixHash"))
                    || Boolean.TRUE.equals(get(owner.node(), "generatorOptions", "disableNameSuffixHash"))) {
                    v.add(owner.file() + " configMapGenerator/" + name, "disableNameSuffixHash", "not true (the hash "
                        + "makes a changed file a new pod template)", true);
                }
            }
        }
        v.assertNone();
    }

    @Test
    void noSecretWithDataInTheManifests() {
        Violations v = new Violations("no Secret with data (D9, constitution 6)");
        for (Doc d : secretScope) {
            if (isKustomization(d)) {
                if (!list(d.node().get("secretGenerator")).isEmpty()) {
                    v.add(d.file(), "secretGenerator", "none (Secrets are created by a person or per CI run)",
                        list(d.node().get("secretGenerator")).stream().map(g -> get(g, "name")).toList());
                }
            } else if (d.kind().equals("Secret")) {
                for (String field : List.of("data", "stringData")) {
                    if (d.node().get(field) instanceof Map<?, ?> m && !m.isEmpty()) {
                        v.add(d.where(), field, "no Secret data in the repository", "keys " + m.keySet());
                    }
                }
            }
        }
        v.assertNone();
    }

    /**
     * Validation 006, decision 2 (D10): no secret value in a manifest. An {@code env} entry whose name looks like a
     * secret's ({@link #SECRET_NAME}) takes its value from a Secret ({@code valueFrom}, {@code envFrom}); a literal
     * {@code value} is allowed only as the path of a file in a volume mounted from a Secret (the secret is the file,
     * not the text). Such a key with a value is refused in a ConfigMap, in a {@code configMapGenerator}'s literals and in
     * the {@code .env} and other files it reads. On rendered input the generators are ConfigMaps already. The value
     * itself is never printed, only its length.
     */
    @Test
    void noSecretValueInTheManifests() throws IOException {
        Violations v = new Violations("no secret value in a manifest (validation 006 decision 2, D10, constitution 6)");
        for (Doc d : secretScope) {
            if (isKustomization(d)) {
                generatorInputs(v, d);
            } else if (WORKLOAD_KINDS.contains(d.kind())) {
                Map<?, ?> pod = podSpec(d);
                for (String field : List.of("initContainers", "containers")) {
                    for (Object c : list(pod.get(field))) {
                        for (Object e : list(get(c, "env"))) {
                            String name = Objects.toString(get(e, "name"), "");
                            String value = text(get(e, "value"));
                            if (SECRET_NAME.matcher(name).find() && value != null && !value.isEmpty()
                                && !inSecretVolume(pod, c instanceof Map<?, ?> cm ? cm : Map.of(), value)) {
                                v.add(d.where() + " " + field + "/" + get(c, "name"), "env " + name,
                                    "valueFrom.secretKeyRef or envFrom.secretRef, or the path of a file in a mounted "
                                        + "Secret", "a literal value (" + value.length() + " characters)");
                            }
                        }
                    }
                }
            } else if (d.kind().equals("ConfigMap")) {
                for (String field : List.of("data", "binaryData")) {
                    if (d.node().get(field) instanceof Map<?, ?> data) {
                        data.forEach((key, value) -> secretKey(v, d.where(), field, Objects.toString(key, ""),
                            Objects.toString(value, "")));
                    }
                }
            }
        }
        v.assertNone();
    }

    /** A raw kustomization's {@code configMapGenerator}: literals, {@code envs} files and {@code files}. */
    private static void generatorInputs(Violations v, Doc k) throws IOException {
        Path dir = base.resolve(k.file()).getParent();
        for (Object g : list(k.node().get("configMapGenerator"))) {
            String where = k.file() + " configMapGenerator/" + get(g, "name");
            for (Object literal : list(get(g, "literals"))) {
                String[] pair = Objects.toString(literal, "").split("=", 2);
                secretKey(v, where, "literals", pair[0], pair.length > 1 ? pair[1] : "");
            }
            List<Object> envs = new ArrayList<>(list(get(g, "envs")));
            if (get(g, "env") != null) {
                envs.add(get(g, "env"));
            }
            for (Object env : envs) {
                Path f = dir.resolve(text(env)).normalize();
                if (!Files.isRegularFile(f)) {
                    v.add(where, "envs " + env, "a file next to the kustomization", "(absent)");
                    continue;
                }
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        String[] pair = trimmed.split("=", 2);
                        secretKey(v, base.relativize(f).toString(), "(read by " + where + ")", pair[0].strip(),
                            pair.length > 1 ? pair[1] : "");
                    }
                }
            }
            for (Object file : list(get(g, "files"))) {
                String[] pair = Objects.toString(file, "").split("=", 2);
                String path = pair.length > 1 ? pair[1] : pair[0];
                String key = pair.length > 1 ? pair[0] : Path.of(path).getFileName().toString();
                Path f = dir.resolve(path).normalize();
                secretKey(v, where, "files", key, Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : "");
            }
        }
    }

    private static void secretKey(Violations v, String where, String field, String key, String value) {
        if (SECRET_NAME.matcher(key).find() && !value.isBlank()) {
            v.add(where, field + " " + key, "no secret-named key with a value (a Secret holds it)",
                "a value (" + value.length() + " characters)");
        }
    }

    /**
     * Whether a literal env value is the path of a file in (or the mount point of) a volume mounted from a Secret, with
     * an optional {@code file:} prefix (Spring's resource form).
     */
    private static boolean inSecretVolume(Map<?, ?> pod, Map<?, ?> c, String value) {
        String raw = value.startsWith("file:") ? value.substring("file:".length()) : value;
        if (!raw.startsWith("/") || raw.contains("..")) {
            return false;
        }
        Path path = Path.of(raw).normalize();
        for (Object m : list(c.get("volumeMounts"))) {
            String mount = text(get(m, "mountPath"));
            String volume = text(get(m, "name"));
            boolean secret = list(pod.get("volumes")).stream().anyMatch(vol -> volume != null
                && volume.equals(text(get(vol, "name"))) && get(vol, "secret") != null);
            if (secret && mount != null && path.startsWith(Path.of(mount).normalize())) {
                return true;
            }
        }
        return false;
    }

    // --- D8: Services and NetworkPolicy -----------------------------------------------------------------------------

    @Test
    void onlyThePartnerPortIsInAServiceAndTheManagementPortOnlyFromTheNamespace() {
        Violations v = new Violations("Services and NetworkPolicy (D3, D8)");
        Integer management = intProperty(v, "management.server.port");
        Doc w = workload(v);
        if (w != null && management != null) {
            Map<?, ?> c = container(w);
            List<Doc> services = resources.stream().filter(d -> d.kind().equals("Service")).toList();
            Doc partner = services.stream().filter(s -> "order-integration".equals(s.name())).findFirst().orElse(null);
            if (partner == null) {
                v.add(input, "Service/order-integration", "a Service exposing port " + PARTNER_PORT, "none");
            } else {
                List<Object> ports = list(get(partner.node(), "spec", "ports")).stream()
                    .map(p -> get(p, "port")).collect(Collectors.toList());
                v.expect(partner, "spec.ports[].port", List.of(PARTNER_PORT), ports);
            }
            for (Doc s : services) {
                for (Object p : list(get(s.node(), "spec", "ports"))) {
                    Integer target = resolvePort(c, get(p, "targetPort") != null ? get(p, "targetPort") : get(p, "port"));
                    if (Objects.equals(target, management) || Objects.equals(get(p, "port"), management)) {
                        v.add(s.where(), "spec.ports " + get(p, "name"), "the management port " + management
                            + " in no Service", get(p, "port") + " -> " + get(p, "targetPort"));
                    }
                }
            }
            networkPolicy(v, w, c, management);
        }
        v.assertNone();
    }

    // --- P-11, D14: the release names its image by digest -----------------------------------------------------------

    @Test
    void theReleasedImageIsPinnedByDigest() {
        Violations v = new Violations("image by digest (B6, D14, P-11)");
        Doc w = workload(v);
        if (defaultInput) {
            // The release is the base plus the image, nothing else: a patch, a replica count or generator options in
            // the overlay would change what every other rule checked in the base.
            String release = RELEASE_KUSTOMIZATION.toString();
            Doc overlay = kustomizations.stream().filter(k -> k.file().equals(release)).findFirst().orElse(null);
            if (overlay == null) {
                v.add(release, "file", "the release overlay", "(absent)");
            } else {
                for (Object key : overlay.node().keySet()) {
                    if (!RELEASE_KEYS.contains(Objects.toString(key, ""))) {
                        v.add(release, String.valueOf(key), "only " + RELEASE_KEYS + " (the base plus the image)",
                            "present");
                    }
                }
                v.expect(overlay, "resources", List.of("../base"), overlay.node().get("resources"));
            }
        }
        List<Object> images = new ArrayList<>();
        for (Doc k : kustomizations) {
            for (Object i : list(k.node().get("images"))) {
                images.add(i);
                if (get(i, "newTag") != null) {
                    v.add(k.file() + " images/" + get(i, "name"), "newTag", "no tag, a digest", get(i, "newTag"));
                }
                if (!DIGEST.matcher(String.valueOf(get(i, "digest"))).matches()) {
                    v.add(k.file() + " images/" + get(i, "name"), "digest", "sha256:<64 hex>", get(i, "digest"));
                }
            }
        }
        if (w != null) {
            String image = text(container(w).get("image"));
            if (image == null) {
                v.add(w.where(), "image", "an image", null);
            } else if (image.contains("@")) {
                if (!DIGEST.matcher(image.substring(image.indexOf('@') + 1)).matches()) {
                    v.add(w.where(), "image", "<name>@sha256:<64 hex>", image);
                }
            } else {
                boolean pinnedByKustomize = images.stream().anyMatch(i -> image.equals(text(get(i, "name")))
                    && DIGEST.matcher(String.valueOf(get(i, "digest"))).matches());
                if (!pinnedByKustomize) {
                    v.add(w.where(), "image", "a digest (in the image or by the release kustomization's images)",
                        image);
                }
            }
        }
        v.assertNone();
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private static void probe(Violations v, Doc w, Map<?, ?> c, String probe, int port, String path,
                              Map<String, Integer> settings) {
        Object p = c.get(probe);
        if (p == null) {
            v.add(w.where(), probe, "httpGet " + path + " on port " + port, "(absent)");
            return;
        }
        v.expect(w, probe + ".httpGet.path", path, get(p, "httpGet", "path"));
        v.expect(w, probe + ".httpGet.port", port, resolvePort(c, get(p, "httpGet", "port")));
        settings.forEach((key, value) -> v.expect(w, probe + "." + key, value, get(p, key)));
    }

    private static void networkPolicy(Violations v, Doc w, Map<?, ?> c, int management) {
        Map<?, ?> labels = get(w.node(), "spec", "template", "metadata", "labels") instanceof Map<?, ?> m ? m : Map.of();
        List<Doc> policies = resources.stream().filter(d -> d.kind().equals("NetworkPolicy"))
            .filter(d -> selects(get(d.node(), "spec", "podSelector", "matchLabels"), labels)).toList();
        if (policies.isEmpty()) {
            v.add(input, "NetworkPolicy", "one selecting the pod " + labels, "none");
            return;
        }
        boolean partnerFromAnywhere = false;
        boolean managementAdmitted = false;
        for (Doc policy : policies) {
            String namespace = text(get(policy.node(), "metadata", "namespace"));
            for (Object rule : list(get(policy.node(), "spec", "ingress"))) {
                List<Object> ports = list(get(rule, "ports"));
                List<Object> from = list(get(rule, "from"));
                boolean admitsPartner = admits(c, ports, PARTNER_PORT);
                boolean admitsManagement = admits(c, ports, management);
                partnerFromAnywhere |= admitsPartner && from.isEmpty();
                if (admitsManagement) {
                    managementAdmitted = true;
                    boolean namespaceOnly = !from.isEmpty() && from.stream().allMatch(peer -> sameNamespace(peer, namespace));
                    if (!namespaceOnly) {
                        v.add(policy.where(), "spec.ingress (port " + management + ")",
                            "from the namespace only (podSelector, or namespaceSelector of its own name)",
                            from.isEmpty() ? "from anywhere" : from);
                    }
                }
            }
        }
        if (!partnerFromAnywhere) {
            v.add(input, "NetworkPolicy ingress", "port " + PARTNER_PORT + " from anywhere", "no such rule");
        }
        if (!managementAdmitted) {
            v.add(input, "NetworkPolicy ingress", "port " + management + " from the namespace", "no such rule");
        }
    }

    /**
     * Whether an ingress rule's {@code ports} admit {@code port}: no list or an entry without {@code port} admits every
     * port; {@code endPort} makes a range.
     */
    private static boolean admits(Map<?, ?> c, List<Object> ports, int port) {
        if (ports.isEmpty()) {
            return true;
        }
        for (Object p : ports) {
            if (get(p, "port") == null) {
                return true;
            }
            Integer first = resolvePort(c, get(p, "port"));
            Object end = get(p, "endPort");
            if (first != null && (end instanceof Number last ? first <= port && port <= last.intValue() : first == port)) {
                return true;
            }
        }
        return false;
    }

    /** A peer inside the policy's namespace: pods of the namespace, or the namespace selected by its own name. */
    private static boolean sameNamespace(Object peer, String namespace) {
        if (!(peer instanceof Map<?, ?> p) || p.containsKey("ipBlock")) {
            return false;
        }
        Object selector = p.get("namespaceSelector");
        if (selector == null) {
            return p.containsKey("podSelector");
        }
        Object name = get(selector, "matchLabels", "kubernetes.io/metadata.name");
        return namespace != null && namespace.equals(text(name))
            && get(selector, "matchExpressions") == null && ((Map<?, ?>) get(selector, "matchLabels")).size() == 1;
    }

    private static boolean selects(Object matchLabels, Map<?, ?> labels) {
        if (!(matchLabels instanceof Map<?, ?> m)) {
            return true;
        }
        return m.entrySet().stream().allMatch(e -> Objects.equals(text(labels.get(e.getKey())), text(e.getValue())));
    }

    /** A container port given as a number or by the name of one of the container's ports. */
    private static Integer resolvePort(Map<?, ?> c, Object port) {
        if (port instanceof Number n) {
            return n.intValue();
        }
        String name = text(port);
        if (name == null) {
            return null;
        }
        if (name.matches("\\d+")) {
            return Integer.valueOf(name);
        }
        return list(c.get("ports")).stream().filter(p -> name.equals(text(get(p, "name"))))
            .map(p -> get(p, "containerPort")).filter(Number.class::isInstance).map(n -> ((Number) n).intValue())
            .findFirst().orElse(null);
    }

    private static Integer intProperty(Violations v, String key) {
        String value = properties.getProperty(key);
        try {
            return Integer.valueOf(value.trim());
        } catch (RuntimeException e) {
            v.add(APPLICATION_PROPERTIES.toString(), key, "a number", value);
            return null;
        }
    }

    /**
     * Spring's graceful web phase, {@code spring.lifecycle.timeout-per-shutdown-phase} (default 30 s), in whole seconds
     * rounded up. Spring's simple duration format: a number with {@code ms}, {@code s}, {@code m} or {@code h}; a bare
     * number is milliseconds.
     */
    private static Integer webShutdownSeconds(Violations v) {
        String key = "spring.lifecycle.timeout-per-shutdown-phase";
        String value = properties.getProperty(key);
        if (value == null) {
            return SPRING_WEB_SHUTDOWN_SECONDS;
        }
        java.util.regex.Matcher m = Pattern.compile("(\\d+)(ms|s|m|h)?").matcher(value.trim());
        if (!m.matches()) {
            v.add(APPLICATION_PROPERTIES.toString(), key, "a duration such as 30s", value);
            return null;
        }
        long millis = Long.parseLong(m.group(1)) * switch (m.group(2) == null ? "ms" : m.group(2)) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            default -> 1L;
        };
        return (int) ((millis + 999) / 1000);
    }

    private static List<Doc> workloads() {
        return resources.stream().filter(d -> WORKLOAD_KINDS.contains(d.kind())).toList();
    }

    /** The workload the other rules look at (the StatefulSet if several); a violation when there is none. */
    private static Doc workload(Violations v) {
        List<Doc> workloads = workloads();
        if (workloads.isEmpty()) {
            v.add(input, "workload", "the order-integration StatefulSet", "none");
            return null;
        }
        return workloads.stream().filter(d -> d.kind().equals("StatefulSet")).findFirst().orElse(workloads.get(0));
    }

    private static Map<?, ?> podSpec(Doc w) {
        Object spec = switch (w.kind()) {
            case "Pod" -> get(w.node(), "spec");
            case "CronJob" -> get(w.node(), "spec", "jobTemplate", "spec", "template", "spec");
            default -> get(w.node(), "spec", "template", "spec");
        };
        return spec instanceof Map<?, ?> m ? m : Map.of();
    }

    /** The container {@code order-integration}, or the first one. */
    private static Map<?, ?> container(Doc w) {
        List<Object> containers = list(podSpec(w).get("containers"));
        return containers.stream().filter(c -> "order-integration".equals(text(get(c, "name"))))
            .findFirst().or(() -> containers.stream().findFirst())
            .map(c -> c instanceof Map<?, ?> m ? m : Map.of()).orElse(Map.of());
    }

    /** The pod volume mounted at {@code path} in the container, or null. */
    private static Map<?, ?> volumeAt(Map<?, ?> pod, Map<?, ?> c, String path) {
        String volume = list(c.get("volumeMounts")).stream().filter(m -> path.equals(text(get(m, "mountPath"))))
            .map(m -> text(get(m, "name"))).findFirst().orElse(null);
        return list(pod.get("volumes")).stream().filter(vol -> volume != null && volume.equals(text(get(vol, "name"))))
            .map(vol -> (Map<?, ?>) vol).findFirst().orElse(null);
    }

    private static String secretName(Map<?, ?> volume) {
        return volume == null ? null : text(get(volume, "secret", "secretName"));
    }

    private static String env(Map<?, ?> c, String name) {
        return list(c.get("env")).stream().filter(e -> name.equals(text(get(e, "name"))))
            .map(e -> text(get(e, "value"))).findFirst().orElse(null);
    }

    private static boolean isKustomization(Doc d) {
        String file = Path.of(d.file()).getFileName().toString();
        return file.equals("kustomization.yaml") || file.equals("kustomization.yml") || "Kustomization".equals(d.kind());
    }

    private static List<Path> yamlFiles(Path in) throws IOException {
        if (Files.isRegularFile(in)) {
            return List.of(in);
        }
        try (Stream<Path> files = Files.walk(in)) {
            return files.filter(Files::isRegularFile)
                .filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml")).sorted().toList();
        }
    }

    private static List<Doc> read(List<Path> files) throws IOException {
        List<Doc> docs = new ArrayList<>();
        Load load = new Load(LoadSettings.builder().build());
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            for (Object doc : load.loadAllFromString(text)) {
                if (doc instanceof Map<?, ?> m) {
                    docs.add(new Doc(base.relativize(f).toString(), m));
                }
            }
        }
        return docs;
    }

    private static Object get(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(key);
        }
        return current;
    }

    private static List<Object> list(Object node) {
        return node instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** The violations of one rule; the assertion message lists them all. */
    private static final class Violations {
        private final String rule;
        private final List<String> items = new ArrayList<>();

        Violations(String rule) {
            this.rule = rule;
        }

        void add(String where, String field, Object expected, Object got) {
            items.add(where + " " + field + ": expected " + expected + ", got " + (got == null ? "(absent)" : got));
        }

        void expect(Doc d, String field, Object expected, Object got) {
            if (!Objects.equals(text(expected), text(got))) {
                add(d.where(), field, expected == null ? "(absent)" : expected, got);
            }
        }

        void assertNone() {
            assertTrue(items.isEmpty(), () -> "T4 " + rule + ": " + items.size() + " violation(s)\n  "
                + String.join("\n  ", items));
        }
    }
}
