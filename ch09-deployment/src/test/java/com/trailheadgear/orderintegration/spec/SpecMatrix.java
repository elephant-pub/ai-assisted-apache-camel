package com.trailheadgear.orderintegration.spec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trailheadgear.orderintegration.spec.SpecTraceabilityTest.ItClass;
import com.trailheadgear.orderintegration.spec.TestCaseDocument.Step;
import com.trailheadgear.orderintegration.spec.TestCaseDocument.UseCaseDocument;
import com.trailheadgear.orderintegration.spec.support.SpecRun;

/**
 * Completeness check and traceability matrix (design 004 §3.9, A-8). A test-classpath main, run by
 * {@code exec-maven-plugin} (execution {@code spec-traceability-matrix}) at {@code post-integration-test}: after
 * {@code failsafe:integration-test} and before {@code failsafe:verify}.
 *
 * <p>Arguments: {@code docsDir workDir skipITs skipTests}; the ITs count as skipped when either flag is set. It reads the test case and use case documents, the static
 * {@code ITEMS} tables of the {@code *IT} classes (discovered as {@link SpecTraceabilityTest} does) and
 * {@code <workDir>/results/*.json}, and writes {@code <workDir>/traceability-matrix.md} and {@code .json}.
 *
 * <p>It fails (by throwing, so that the in-process {@code exec:java} fails the build; it never calls
 * {@code System.exit}) when:
 * <ul>
 *   <li>an item of a test case (each step, validation, postcondition, the cleanup bullet, and the precondition markers
 *       {@code C-setup}, {@code C-helpdesk} for OQ-066, {@code C-resubmit} for OQ-067) is not in its class's
 *       {@code ITEMS}, by itself or by split children ({@code <id>a}, {@code <id>-<suffix>});</li>
 *   <li>an {@code ITEMS} entry is no item of the document (neither an item, a split child, nor a marker);</li>
 *   <li>no Approved test case document exists (no vacuous pass);</li>
 *   <li>the ITs ran and a result file is missing for an Approved test case;</li>
 *   <li>a result file disagrees with {@code ITEMS} (test case, item IDs, order, kinds, outcome allowed for the kind).</li>
 * </ul>
 * With {@code itsSkipped} the result files are ignored (they may be stale), every run item is {@code not run}, and the
 * matrix is written; the completeness rule still applies.
 */
public final class SpecMatrix {

    private static final String APPROVED = "Approved";
    private static final String PASSED = "passed";
    private static final String FAILED = "failed";
    private static final String NOT_RUN = "not run";
    private static final String NOT_YET_TESTABLE = "not yet testable";
    private static final Pattern UC_ID = Pattern.compile("UC-\\d+");

    /** Precondition markers: the marker ID and the text that makes a document need it ({@code null}: always). */
    private static final Map<String, String> MARKERS = markers();

    private static Map<String, String> markers() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("C-setup", null);
        m.put("C-helpdesk", "(OQ-066)");
        m.put("C-resubmit", "(OQ-067)");
        m.put("C-alert", "The Integration Team mailbox of the test environment is reachable and holds no Alert");
        return m;
    }

    /** One item of one test case with its final status. */
    record Row(String id, String name, SpecRun.Kind kind, String status, String useCase, String failure) {
    }

    /** One test case: document, class (nullable) and rows. */
    record Case(TestCaseDocument document, ItClass itClass, List<Row> rows) {
    }

    private SpecMatrix() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: SpecMatrix <docsDir> <workDir> <skipITs true|false> <skipTests true|false>");
        }
        Path docsDir = Path.of(args[0]);
        Path workDir = Path.of(args[1]);
        // Either flag skips failsafe: -DskipITs or -DskipTests
        boolean itsSkipped = parseSkipped(args[2]) | parseSkipped(args[3]);

        List<TestCaseDocument> documents = TestCaseDocument.loadAll(docsDir);
        List<UseCaseDocument> useCases = UseCaseDocument.loadUseCases(docsDir);
        List<ItClass> classes = SpecTraceabilityTest.discover();

        List<String> violations = new ArrayList<>();
        List<Case> cases = build(documents, classes, workDir.resolve("results"), itsSkipped, violations);

        Files.createDirectories(workDir);
        Path md = workDir.resolve("traceability-matrix.md");
        Path json = workDir.resolve("traceability-matrix.json");
        Files.writeString(md, markdown(cases, useCases, itsSkipped, violations));
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(json.toFile(), json(cases, useCases, itsSkipped, violations));
        System.out.println("[SpecMatrix] wrote " + md + " and " + json);
        for (Case c : cases) {
            System.out.println("[SpecMatrix] " + summary(c));
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Spec traceability matrix: " + violations.size() + " violation(s):\n - "
                    + String.join("\n - ", violations));
        }
    }

    private static boolean parseSkipped(String arg) {
        String v = arg.strip();
        if (v.isEmpty() || v.equalsIgnoreCase("true")) {
            return true; // -DskipITs without a value is "true"
        }
        if (v.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("a skip flag must be true or false but is '" + arg + "'");
    }

    // ---------------------------------------------------------------- the check

    static List<Case> build(List<TestCaseDocument> documents, List<ItClass> classes, Path resultsDir,
            boolean itsSkipped, List<String> violations) throws IOException {
        Map<String, ItClass> byId = new LinkedHashMap<>();
        for (ItClass c : classes) {
            if (c.specTestCaseId() != null) {
                byId.putIfAbsent(c.specTestCaseId(), c);
            }
        }
        if (documents.stream().noneMatch(d -> APPROVED.equals(d.status()))) {
            violations.add("No Approved test case document was found, so nothing is traced");
        }
        List<Case> cases = new ArrayList<>();
        for (TestCaseDocument d : documents) {
            ItClass c = byId.get(d.id());
            if (c == null || c.items() == null) {
                if (APPROVED.equals(d.status())) {
                    violations.add(d.id() + " (" + d.file() + ") is Approved but has no *IT class with an ITEMS table");
                }
                cases.add(new Case(d, c, List.of()));
                continue;
            }
            checkCompleteness(d, c, violations);
            JsonNode result = null;
            if (!itsSkipped) {
                Path file = resultsDir.resolve(d.id() + ".json");
                if (Files.isRegularFile(file)) {
                    result = new ObjectMapper().readTree(file.toFile());
                    checkResult(d, c, result, file, violations);
                } else if (APPROVED.equals(d.status())) {
                    violations.add(d.id() + ": the ITs ran but the result file " + file + " is missing");
                }
            }
            cases.add(new Case(d, c, rows(c, result)));
        }
        return cases;
    }

    /** The items a document requires: S/V/P items, P-cleanup and the precondition markers it needs. */
    static List<String> requiredItems(TestCaseDocument d) {
        List<String> ids = new ArrayList<>(d.itemIds());
        for (Map.Entry<String, String> m : MARKERS.entrySet()) {
            if (m.getValue() == null || d.preconditions().stream().anyMatch(p -> p.contains(m.getValue()))) {
                ids.add(m.getKey());
            }
        }
        return ids;
    }

    /** True when {@code itemId} is {@code required} itself or one of its split children. */
    static boolean covers(String required, String itemId) {
        return itemId.equals(required) || itemId.matches(Pattern.quote(required) + "[a-z]")
                || itemId.startsWith(required + "-") && itemId.length() > required.length() + 1;
    }

    /**
     * Design 005 §3.10 row 3: true when {@code itemId} is {@code required} itself, or a split child of it (as
     * {@link #covers(String, String)}) that {@code src/test/resources/spec-test/split-items.properties} lists for the
     * test case {@code tcId}. Any other split child is not covered.
     */
    static boolean covers(String tcId, String required, String itemId) {
        if (itemId.equals(required)) {
            return true;
        }
        return covers(required, itemId) && splitItems().getOrDefault(tcId, Set.of()).contains(itemId);
    }

    private static final String SPLIT_ITEMS = "spec-test/split-items.properties";
    private static volatile Map<String, Set<String>> splitItems;

    /** Test case ID to the split item IDs listed for it, read once from the test classpath. */
    private static Map<String, Set<String>> splitItems() {
        Map<String, Set<String>> loaded = splitItems;
        if (loaded == null) {
            Properties properties = new Properties();
            try (InputStream in = SpecMatrix.class.getClassLoader().getResourceAsStream(SPLIT_ITEMS)) {
                if (in == null) {
                    throw new IllegalStateException("Classpath resource " + SPLIT_ITEMS + " not found");
                }
                properties.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + SPLIT_ITEMS, e);
            }
            loaded = new LinkedHashMap<>();
            for (String tc : properties.stringPropertyNames()) {
                Set<String> ids = new LinkedHashSet<>();
                for (String id : properties.getProperty(tc).split(",")) {
                    if (!id.isBlank()) {
                        ids.add(id.strip());
                    }
                }
                loaded.put(tc.strip(), ids);
            }
            splitItems = loaded;
        }
        return loaded;
    }

    private static void checkCompleteness(TestCaseDocument d, ItClass c, List<String> violations) {
        List<String> required = requiredItems(d);
        for (String r : required) {
            if (c.items().stream().noneMatch(i -> covers(d.id(), r, i.id()))) {
                violations.add(d.id() + " item " + r + " (" + d.file() + ") is neither a run item nor a not-yet-testable"
                        + " item in " + c.className() + ".ITEMS");
            }
        }
        for (SpecRun.Item i : c.items()) {
            if (required.stream().noneMatch(r -> covers(d.id(), r, i.id()))) {
                violations.add(c.className() + ".ITEMS has " + i.id() + ", which is no item of " + d.id() + " ("
                        + d.file() + ") and no split item listed for " + d.id() + " in " + SPLIT_ITEMS);
            }
        }
    }

    private static void checkResult(TestCaseDocument d, ItClass c, JsonNode result, Path file,
            List<String> violations) {
        if (!d.id().equals(result.path("testCaseId").asText())) {
            violations.add(file + ": testCaseId is '" + result.path("testCaseId").asText() + "', expected " + d.id());
        }
        if (!c.className().equals(result.path("testClass").asText())) {
            violations.add(file + ": testClass is '" + result.path("testClass").asText() + "', expected "
                    + c.className());
        }
        JsonNode items = result.path("items");
        List<String> resultIds = new ArrayList<>();
        for (JsonNode n : items) {
            resultIds.add(n.path("id").asText());
        }
        List<String> declared = c.items().stream().map(SpecRun.Item::id).toList();
        if (!resultIds.equals(declared)) {
            violations.add(file + ": items " + resultIds + " differ from " + c.className() + ".ITEMS " + declared);
            return;
        }
        for (int i = 0; i < declared.size(); i++) {
            SpecRun.Item item = c.items().get(i);
            JsonNode n = items.get(i);
            String kind = n.path("kind").asText();
            String outcome = n.path("outcome").asText();
            if (!item.kind().name().equals(kind)) {
                violations.add(file + ": item " + item.id() + " has kind " + kind + " but ITEMS declares "
                        + item.kind());
            }
            boolean nyt = item.kind() == SpecRun.Kind.NOT_YET_TESTABLE;
            boolean allowed = nyt ? outcome.equals(SpecRun.Outcome.NOT_YET_TESTABLE.name())
                    : Set.of("PASSED", "FAILED", "NOT_RUN").contains(outcome);
            if (!allowed) {
                violations.add(file + ": item " + item.id() + " (" + item.kind() + ") has outcome '" + outcome + "'");
            }
            if (nyt && !item.useCase().id().equals(n.path("useCase").asText())) {
                violations.add(file + ": item " + item.id() + " names use case " + n.path("useCase").asText()
                        + " but ITEMS declares " + item.useCase().id());
            }
        }
    }

    private static List<Row> rows(ItClass c, JsonNode result) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        if (result != null) {
            for (JsonNode n : result.path("items")) {
                byId.put(n.path("id").asText(), n);
            }
        }
        List<Row> rows = new ArrayList<>();
        for (SpecRun.Item i : c.items()) {
            if (i.kind() == SpecRun.Kind.NOT_YET_TESTABLE) {
                rows.add(new Row(i.id(), i.name(), i.kind(), NOT_YET_TESTABLE, i.useCase().id(), null));
                continue;
            }
            JsonNode n = byId.get(i.id());
            String status = NOT_RUN;
            String failure = null;
            if (n != null) {
                switch (n.path("outcome").asText()) {
                    case "PASSED" -> status = PASSED;
                    case "FAILED" -> {
                        status = FAILED;
                        failure = n.hasNonNull("failure") ? n.get("failure").asText() : null;
                    }
                    default -> status = NOT_RUN;
                }
            }
            rows.add(new Row(i.id(), i.name(), i.kind(), status, null, failure));
        }
        return rows;
    }

    // ---------------------------------------------------------------- summary

    /** {@code TC-001: 12 run (12 passed), 5 not yet testable (UC-005 ×4, UC-006 ×1)}. */
    static String summary(Case c) {
        String id = c.document().id();
        if (c.itClass() == null || c.itClass().items() == null) {
            return id + ": no test class (status " + c.document().status() + ")";
        }
        int run = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        byStatus.put(PASSED, 0);
        byStatus.put(FAILED, 0);
        byStatus.put(NOT_RUN, 0);
        Map<String, Integer> nyt = new TreeMap<>();
        for (Row r : c.rows()) {
            if (r.kind() == SpecRun.Kind.RUN) {
                run++;
                byStatus.merge(r.status(), 1, Integer::sum);
            } else {
                nyt.merge(r.useCase(), 1, Integer::sum);
            }
        }
        List<String> parts = new ArrayList<>();
        byStatus.forEach((k, v) -> {
            if (v > 0) {
                parts.add(v + " " + k);
            }
        });
        int nytTotal = nyt.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder sb = new StringBuilder(id).append(": ").append(run).append(" run");
        if (!parts.isEmpty()) {
            sb.append(" (").append(String.join(", ", parts)).append(')');
        }
        sb.append(", ").append(nytTotal).append(" not yet testable");
        if (nytTotal > 0) {
            List<String> ucs = new ArrayList<>();
            nyt.forEach((k, v) -> ucs.add(k + " ×" + v));
            sb.append(" (").append(String.join(", ", ucs)).append(')');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- use case view

    /** One line of the use case section: a test case step (or a not-yet-testable item) for a use case. */
    record UseCaseEntry(String testCase, String step, String item, String itemName, String testClass, String status,
            String via) {
    }

    static List<UseCaseEntry> useCaseEntries(String ucId, List<Case> cases) {
        List<UseCaseEntry> out = new ArrayList<>();
        for (Case c : cases) {
            if (c.itClass() == null) {
                continue;
            }
            Set<String> listed = new LinkedHashSet<>();
            for (Step s : c.document().steps()) {
                if (!ucId.equals(s.useCaseId())) {
                    continue;
                }
                String stepId = "S" + s.number();
                for (Row r : c.rows()) {
                    if (covers(c.document().id(), stepId, r.id())) {
                        out.add(new UseCaseEntry(c.document().id(), stepId + " " + s.name(), r.id(), r.name(),
                                c.itClass().className(), status(r), "flow step"));
                        listed.add(r.id());
                    }
                }
            }
            for (Row r : c.rows()) {
                if (ucId.equals(r.useCase()) && !listed.contains(r.id())) {
                    out.add(new UseCaseEntry(c.document().id(), "-", r.id(), r.name(), c.itClass().className(),
                            status(r), "declared not yet testable for " + ucId));
                }
            }
        }
        return out;
    }

    /**
     * Test cases whose Preconditions name {@code ucId}, with the C-setup outcome (the test establishes them). Listed
     * only for a use case that no flow step names (UC-007 through the Partner Configuration).
     */
    static List<UseCaseEntry> preconditionEntries(String ucId, List<Case> cases) {
        List<UseCaseEntry> out = new ArrayList<>();
        for (Case c : cases) {
            if (c.itClass() == null) {
                continue;
            }
            for (String p : c.document().preconditions()) {
                Matcher m = UC_ID.matcher(p);
                boolean names = false;
                while (m.find()) {
                    names |= m.group().equals(ucId);
                }
                if (!names) {
                    continue;
                }
                Row setup = c.rows().stream().filter(r -> r.id().equals("C-setup")).findFirst().orElse(null);
                String what = p.contains("Partner Configuration") ? "precondition: the Partner Configuration"
                        : "precondition";
                out.add(new UseCaseEntry(c.document().id(), "Preconditions", "C-setup",
                        setup == null ? "-" : setup.name(), c.itClass().className(),
                        setup == null ? NOT_RUN : status(setup), what));
                break;
            }
        }
        return out;
    }

    private static String status(Row r) {
        return r.kind() == SpecRun.Kind.NOT_YET_TESTABLE ? NOT_YET_TESTABLE + " (" + r.useCase() + ")" : r.status();
    }

    private static List<String[]> useCaseList(List<UseCaseDocument> useCases) {
        Map<String, String> titles = new TreeMap<>();
        for (SpecRun.UseCase uc : SpecRun.UseCase.values()) {
            titles.put(uc.id(), uc.title());
        }
        for (UseCaseDocument u : useCases) {
            titles.put(u.id(), u.title());
        }
        List<String[]> out = new ArrayList<>();
        titles.forEach((k, v) -> out.add(new String[] {k, v}));
        return out;
    }

    // ---------------------------------------------------------------- Markdown

    static String markdown(List<Case> cases, List<UseCaseDocument> useCases, boolean itsSkipped,
            List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Spec test traceability matrix\n\n");
        sb.append("Generated by `SpecMatrix` (design 004 §3.9). Build output, not committed (A-8).\n\n");
        sb.append(itsSkipped
                ? "Integration tests were skipped (`-DskipITs`): every run item is `not run`.\n\n"
                : "Integration tests ran: outcomes from `results/*.json`.\n\n");
        if (!violations.isEmpty()) {
            sb.append("**Completeness check failed:**\n\n");
            for (String v : violations) {
                sb.append("- ").append(escape(v)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## 1. Use case → test case step → test\n\n");
        for (String[] uc : useCaseList(useCases)) {
            sb.append("### ").append(uc[0]).append(' ').append(uc[1]).append("\n\n");
            List<UseCaseEntry> entries = useCaseEntries(uc[0], cases);
            List<UseCaseEntry> pre = entries.isEmpty() ? preconditionEntries(uc[0], cases) : List.of();
            if (entries.isEmpty() && pre.isEmpty()) {
                sb.append("No test case.\n\n");
                continue;
            }
            if (entries.isEmpty()) {
                sb.append("Exercised only as a precondition (no test case step names it).\n\n");
            }
            sb.append("| Test case | Step | Item | Test class | Outcome | Via |\n|---|---|---|---|---|---|\n");
            for (UseCaseEntry e : entries) {
                appendEntry(sb, e);
            }
            for (UseCaseEntry e : pre) {
                appendEntry(sb, e);
            }
            sb.append('\n');
        }

        sb.append("## 2. Per test case\n\n");
        for (Case c : cases) {
            sb.append("### ").append(c.document().id()).append(' ').append(c.document().title()).append("\n\n");
            sb.append(summary(c)).append("\n\n");
            if (c.itClass() == null) {
                continue;
            }
            sb.append("Test class: `").append(c.itClass().className()).append("`\n\n");
            sb.append("| Item | Name | Kind | Status | Failure |\n|---|---|---|---|---|\n");
            for (Row r : c.rows()) {
                sb.append("| ").append(r.id()).append(" | ").append(escape(r.name())).append(" | ")
                        .append(r.kind() == SpecRun.Kind.RUN ? "run" : NOT_YET_TESTABLE).append(" | ")
                        .append(status(r)).append(" | ").append(r.failure() == null ? "" : escape(r.failure()))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 3. Not yet testable, by use case\n\n");
        Map<String, List<String[]>> ready = readyList(cases);
        if (ready.isEmpty()) {
            sb.append("None.\n");
        }
        Map<String, String> titles = new LinkedHashMap<>();
        for (String[] uc : useCaseList(useCases)) {
            titles.put(uc[0], uc[1]);
        }
        ready.forEach((uc, items) -> {
            sb.append("### ").append(uc).append(' ').append(titles.getOrDefault(uc, "")).append(" (")
                    .append(items.size()).append(" items)\n\n");
            sb.append("| Test case | Item | Name | Test class |\n|---|---|---|---|\n");
            for (String[] i : items) {
                sb.append("| ").append(i[0]).append(" | ").append(i[1]).append(" | ").append(escape(i[2]))
                        .append(" | `").append(i[3]).append("` |\n");
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    private static void appendEntry(StringBuilder sb, UseCaseEntry e) {
        sb.append("| ").append(e.testCase()).append(" | ").append(escape(e.step())).append(" | ").append(e.item())
                .append(" | `").append(simpleName(e.testClass())).append("` | ").append(e.status()).append(" | ")
                .append(escape(e.via())).append(" |\n");
    }

    /** Use case → [test case, item, name, class] of the not-yet-testable items, use cases in ID order. */
    private static Map<String, List<String[]>> readyList(List<Case> cases) {
        Map<String, List<String[]>> out = new TreeMap<>();
        for (Case c : cases) {
            for (Row r : c.rows()) {
                if (r.kind() == SpecRun.Kind.NOT_YET_TESTABLE) {
                    out.computeIfAbsent(r.useCase(), k -> new ArrayList<>())
                            .add(new String[] {c.document().id(), r.id(), r.name(), c.itClass().className()});
                }
            }
        }
        return out;
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static String escape(String text) {
        return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    // ---------------------------------------------------------------- JSON

    static Map<String, Object> json(List<Case> cases, List<UseCaseDocument> useCases, boolean itsSkipped,
            List<String> violations) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("itsSkipped", itsSkipped);
        root.put("violations", violations);

        List<Map<String, Object>> ucs = new ArrayList<>();
        for (String[] uc : useCaseList(useCases)) {
            List<UseCaseEntry> entries = useCaseEntries(uc[0], cases);
            List<UseCaseEntry> pre = entries.isEmpty() ? preconditionEntries(uc[0], cases) : List.of();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", uc[0]);
            m.put("title", uc[1]);
            m.put("coverage", entries.isEmpty() ? (pre.isEmpty() ? "no test case" : "precondition only")
                    : "test case steps");
            List<Map<String, Object>> list = new ArrayList<>();
            for (UseCaseEntry e : entries) {
                list.add(entryJson(e));
            }
            for (UseCaseEntry e : pre) {
                list.add(entryJson(e));
            }
            m.put("entries", list);
            ucs.add(m);
        }
        root.put("useCases", ucs);

        List<Map<String, Object>> tcs = new ArrayList<>();
        for (Case c : cases) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.document().id());
            m.put("title", c.document().title());
            m.put("status", c.document().status());
            m.put("testClass", c.itClass() == null ? null : c.itClass().className());
            m.put("summary", summary(c));
            List<Map<String, Object>> items = new ArrayList<>();
            for (Row r : c.rows()) {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("id", r.id());
                i.put("name", r.name());
                i.put("kind", r.kind() == SpecRun.Kind.RUN ? "run" : NOT_YET_TESTABLE);
                i.put("status", r.kind() == SpecRun.Kind.RUN ? r.status() : NOT_YET_TESTABLE);
                i.put("useCase", r.useCase());
                i.put("failure", r.failure());
                items.add(i);
            }
            m.put("items", items);
            tcs.add(m);
        }
        root.put("testCases", tcs);

        Map<String, Object> ready = new LinkedHashMap<>();
        readyList(cases).forEach((uc, items) -> {
            List<Map<String, Object>> list = new ArrayList<>();
            for (String[] i : items) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("testCase", i[0]);
                m.put("item", i[1]);
                m.put("name", i[2]);
                m.put("testClass", i[3]);
                list.add(m);
            }
            ready.put(uc, list);
        });
        root.put("notYetTestableByUseCase", ready);
        return root;
    }

    private static Map<String, Object> entryJson(UseCaseEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("testCase", e.testCase());
        m.put("step", e.step());
        m.put("item", e.item());
        m.put("itemName", e.itemName());
        m.put("testClass", e.testClass());
        m.put("outcome", e.status());
        m.put("via", e.via());
        return m;
    }
}
