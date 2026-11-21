package com.trailheadgear.orderintegration.spec.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.TestcontainersConfiguration;

/**
 * The test environment of the executable spec tests (design 004 §2, §3.3): the Kafka, PostgreSQL and Warehouse SFTP
 * containers, the in-JVM Product Catalog and mail servers, the per-run Secret Store and the per-run SFTP host keys and
 * password. Started once per JVM by {@link #get()} and shared by all five test classes.
 *
 * <p>The containers are started with the Testcontainers Java API (A-5), not with Citrus {@code testcontainers-start-*}
 * actions, and are removed by Ryuk; there is no explicit stop. The two in-JVM servers are stopped by a JVM shutdown hook.
 *
 * <p>Every image comes from a {@code spec-test.image.*} property and must carry an {@code @sha256:} digest (A-21). A
 * reference without one fails {@link #get()} before any container, Ryuk included, is started. The container is pulled
 * and created by {@code <repository>@sha256:<digest>}: the digest alone names the image, so a re-pushed tag cannot
 * change what runs. The configured tag stays in {@link #imageReferences()} for the execution report.
 *
 * <p>Secrets (the database password, the SFTP password, the partner API Key) are random per run, are never logged,
 * and never appear in a container command or in a container's log output.
 */
public final class SpecEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(SpecEnvironment.class);

    /*
     * The failsafe JVM has no logging configuration (Spring Boot runs only in the child process), so Logback would log
     * at DEBUG: docker-java and the Docker HTTP transport would then print container environments (the PostgreSQL
     * password), the copied SFTP password file and host keys, and Citrus the partner key header. Every container of
     * the run is started from this class, so the root level is raised to INFO here, before the first Docker call
     * (design 004 §4 Security). To debug, lower it here deliberately.
     */
    static {
        if (LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context) {
            ch.qos.logback.classic.Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            if (!root.getEffectiveLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO)) {
                root.setLevel(ch.qos.logback.classic.Level.INFO);
            }
        }
    }

    private static final String PROPERTIES = "spec-test/spec-test.properties";
    private static final String IMAGE_KAFKA_KEY = "spec-test.image.kafka";
    private static final String IMAGE_POSTGRES_KEY = "spec-test.image.postgres";
    private static final String IMAGE_SFTP_KEY = "spec-test.image.sftp";
    private static final String STARTUP_TIMEOUT_KEY = "spec-test.startup-timeout-ms";

    /** {@code <name>[:<tag>]@sha256:<64 hex>}; the name may carry a registry, the tag is optional. */
    private static final Pattern PINNED_REFERENCE =
        Pattern.compile("^([a-z0-9][a-z0-9._/:-]*?)(?::([A-Za-z0-9_][A-Za-z0-9_.-]{0,127}))?@sha256:([0-9a-f]{64})$");

    private static final String KAFKA_TOPIC = "peakmart.orders.v2";

    private static final String LOOPBACK = "127.0.0.1";
    private static final int SFTP_CONTAINER_PORT = 22;
    private static final String SFTP_USERNAME = "admin";
    private static final int SFTP_UID = 1001;
    private static final String SFTP_INBOUND = "inbound/picklists";
    private static final String SFTP_PROCESSED = "inbound/picklists/processed";
    /** Read by the image's entrypoint; holds the user spec without password. */
    private static final String SFTP_USERS_CONF = "/etc/sftp/users.conf";
    /** Input of the startup script below; deleted by the script before sshd starts. */
    private static final String SFTP_PASSWORD_FILE = "/etc/sftp/spec-test-admin.chpasswd";
    /** Run by the image's entrypoint after the users are created and before sshd starts (design 004 §7). */
    private static final String SFTP_STARTUP_SCRIPT = "/etc/sftp.d/10-spec-test-admin-password";
    private static final String SFTP_HOST_KEY_DIR = "/etc/ssh/";

    private static final String PARTNER_ID = "summit-outfitters";
    private static final String PARTNER_CONFIGURATION = "messages/examples/partner-configuration/summit-outfitters.json";
    private static final Pattern SAFE_SECRET_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    private static final int FILE_MODE_OWNER_RW = 0100600;
    private static final int FILE_MODE_OWNER_RWX = 0100700;
    private static final Set<PosixFilePermission> OWNER_RW =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_RWX =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Object LOCK = new Object();
    private static volatile SpecEnvironment instance;
    private static RuntimeException startFailure;

    private final Properties settings;
    private final Path workDir;
    private final List<String> imageReferences;
    private final DockerImageName sftpImage;

    private final KafkaContainer kafka;
    private final PostgreSQLContainer postgres;
    private final String dbUsername;
    private final String dbPassword;
    private final PGSimpleDataSource dataSource;

    private final WarehouseHostKeys hostKeys;
    private final String sftpPassword;
    private final int sftpPort;
    private final Path knownHostsFile;
    @SuppressWarnings("unused") // kept reachable for the run; Ryuk removes it
    private final GenericContainer<?> sftp;

    private final ProductCatalogStub catalog;
    private final Mailboxes mailboxes;

    private final Path secretStoreDir;
    private final Path partnerApiKeyFile;
    private final String partnerApiKey;
    private final Path partnerConfigurationFile;

    /**
     * The environment of this JVM, started on the first call. A failed start is remembered: later calls fail at once
     * with the same cause instead of starting containers again.
     */
    public static SpecEnvironment get() {
        SpecEnvironment env = instance;
        if (env != null) {
            return env;
        }
        synchronized (LOCK) {
            if (instance != null) {
                return instance;
            }
            if (startFailure != null) {
                throw new IllegalStateException("The spec test environment failed to start earlier in this JVM",
                    startFailure);
            }
            try {
                instance = new SpecEnvironment();
                return instance;
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                startFailure = e instanceof RuntimeException re ? re
                    : new IllegalStateException("The spec test environment failed to start", e);
                throw startFailure;
            }
        }
    }

    /**
     * Deletes {@code <spec-test.work-dir>/results/*.json} once per JVM, at the start of the one-time start and before
     * any container. Each verify run must produce its own result files, so that the traceability matrix (SpecMatrix,
     * design 004 §3.9) never reports an outcome of an earlier run.
     */
    private static void deleteEarlierResults() throws IOException {
        Path results = SpecRun.resultsDir();
        if (!Files.isDirectory(results)) {
            return;
        }
        try (var files = Files.newDirectoryStream(results, "*.json")) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        }
    }

    private SpecEnvironment() throws Exception {
        this.settings = loadSettings();
        this.workDir = SpecRun.workDir();
        Files.createDirectories(workDir);
        deleteEarlierResults();

        // Image guard (§2, A-21): every reference, Ryuk's included, is checked before anything is started
        String kafkaRef = configuredImage(IMAGE_KAFKA_KEY);
        String postgresRef = configuredImage(IMAGE_POSTGRES_KEY);
        String sftpRef = configuredImage(IMAGE_SFTP_KEY);
        @SuppressWarnings("deprecation")
        String ryukRef = TestcontainersConfiguration.getInstance().getRyukImage();
        PinnedImage kafkaPin = pin("kafka", kafkaRef);
        PinnedImage postgresPin = pin("postgres", postgresRef);
        PinnedImage sftpPin = pin("sftp", sftpRef);
        pin("ryuk.container.image", ryukRef);
        if (postgresPin.tag() == null || !postgresPin.tag().startsWith("16.")) {
            throw new IllegalStateException(IMAGE_POSTGRES_KEY + " must be an exact PostgreSQL 16 tag plus digest "
                + "(C-004, A-18), not " + postgresRef);
        }
        DockerImageName kafkaImage = kafkaPin.dockerImageName().asCompatibleSubstituteFor("apache/kafka");
        DockerImageName postgresImage = postgresPin.dockerImageName().asCompatibleSubstituteFor("postgres");
        this.sftpImage = sftpPin.dockerImageName();
        this.imageReferences = List.of(kafkaRef, postgresRef, sftpRef, ryukRef);

        // Per-run secrets and files first: nothing below depends on a container
        this.partnerConfigurationFile = Path.of(System.getProperty("spec-test.docs-dir", "docs"))
            .resolve(PARTNER_CONFIGURATION).toAbsolutePath().normalize();
        this.secretStoreDir = workDir.resolve("secret-store");
        this.partnerApiKey = randomSecret(32);
        this.partnerApiKeyFile = writeSecretStore(secretStoreDir, partnerApiKeyName(partnerConfigurationFile),
            partnerApiKey);

        Path sftpDir = workDir.resolve("sftp");
        this.hostKeys = WarehouseHostKeys.generate(sftpDir);
        this.sftpPassword = randomSecret(24);
        this.knownHostsFile = sftpDir.resolve("known_hosts");

        // Kafka (default random mapping, A-6), topic created before the first integration start
        this.kafka = new KafkaContainer(kafkaImage);
        kafka.start();
        logStarted("Kafka", kafkaRef, kafka.getDockerImageName());
        createTopic(kafka.getBootstrapServers(), KAFKA_TOPIC);

        // PostgreSQL 16 (random mapping); the integration runs Flyway itself
        String dbSuffix = HexFormat.of().formatHex(randomBytes(6));
        this.dbUsername = "spec_user_" + dbSuffix;
        this.dbPassword = randomSecret(24);
        this.postgres = new PostgreSQLContainer(postgresImage)
            .withDatabaseName("spec_orders_" + dbSuffix)
            .withUsername(dbUsername)
            .withPassword(dbPassword);
        postgres.start();
        logStarted("PostgreSQL", postgresRef, postgres.getDockerImageName());
        this.dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(dbUsername);
        dataSource.setPassword(dbPassword);
        checkPostgres16(dataSource);

        // Warehouse SFTP Server on 127.0.0.1 only
        this.sftpPort = FreePorts.next();
        this.sftp = startSftpOn(sftpPort);
        logStarted("Warehouse SFTP", sftpRef, sftp.getDockerImageName());
        hostKeys.writeKnownHosts(sftpPort, knownHostsFile);

        // In-JVM servers on 127.0.0.1
        this.catalog = new ProductCatalogStub();
        catalog.start(FreePorts.next());
        this.mailboxes = new Mailboxes();
        try {
            mailboxes.start(FreePorts.next());
        } catch (RuntimeException e) {
            catalog.stop();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopInJvmServers, "spec-environment-shutdown"));

        LOG.info("Spec test environment ready: Kafka {} (topic {}), PostgreSQL {}, SFTP {}:{}, catalog {}:{}, "
                + "SMTP {}:{}, Secret Store {}, Ryuk {}", kafka.getBootstrapServers(), KAFKA_TOPIC,
            postgres.getJdbcUrl(), LOOPBACK, sftpPort, LOOPBACK, catalog.port(), LOOPBACK, mailboxes.port(),
            secretStoreDir, ryukRef);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Public API

    /** A copy of the test-side settings ({@code spec-test/spec-test.properties}). */
    public Properties settings() {
        Properties copy = new Properties();
        copy.putAll(settings);
        return copy;
    }

    /** A {@code spec-test.*} duration in milliseconds; a missing or non-numeric value fails. */
    public long millis(String key) {
        return parseMillis(settings, key);
    }

    public String kafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    public String kafkaTopic() {
        return KAFKA_TOPIC;
    }

    public String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    public String dbUsername() {
        return dbUsername;
    }

    public String dbPassword() {
        return dbPassword;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public String sftpHost() {
        return LOOPBACK;
    }

    public int sftpPort() {
        return sftpPort;
    }

    public String sftpUsername() {
        return SFTP_USERNAME;
    }

    public String sftpPassword() {
        return sftpPassword;
    }

    /** {@code <workDir>/sftp/known_hosts} for {@code [127.0.0.1]:<sftpPort()>}. */
    public Path knownHostsFile() {
        return knownHostsFile;
    }

    public WarehouseHostKeys hostKeys() {
        return hostKeys;
    }

    public ProductCatalogStub catalog() {
        return catalog;
    }

    public Mailboxes mailboxes() {
        return mailboxes;
    }

    public Path workDir() {
        return workDir;
    }

    /** Every image reference this run uses, exactly as configured (tag and digest): Kafka, PostgreSQL, SFTP, Ryuk. */
    public List<String> imageReferences() {
        return imageReferences;
    }

    public Path secretStoreDir() {
        return secretStoreDir;
    }

    public Path partnerApiKeyFile() {
        return partnerApiKeyFile;
    }

    /** The Summit Outfitters API Key of this run; for the {@code Partner} helper only, never to be logged. */
    public String partnerApiKey() {
        return partnerApiKey;
    }

    /** The released Partner Configuration ({@code docs/messages/examples/partner-configuration/summit-outfitters.json}). */
    public Path partnerConfigurationFile() {
        return partnerConfigurationFile;
    }

    /**
     * Starts another Warehouse SFTP Server (same image, user, password and host keys) bound to {@code 127.0.0.1:port}
     * only, and returns it once sshd listens. The caller may stop it; Ryuk removes it otherwise. TC-005 uses this.
     */
    public GenericContainer<?> startSftpOn(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a port: " + port);
        }
        GenericContainer<?> container = new GenericContainer<>(sftpImage);
        try {
            // The user spec carries no password: the image's user script prints the spec it parses to the container
            // log, which Testcontainers shows on a failed start. The password is set by the startup script instead.
            container.withCopyToContainer(Transferable.of(SFTP_USERNAME + "::" + SFTP_UID + "::" + SFTP_INBOUND + ","
                + SFTP_PROCESSED + "\n"), SFTP_USERS_CONF);
            container.withCopyToContainer(Transferable.of(SFTP_USERNAME + ":" + sftpPassword + "\n",
                FILE_MODE_OWNER_RW), SFTP_PASSWORD_FILE);
            container.withCopyToContainer(Transferable.of(startupScript(), FILE_MODE_OWNER_RWX), SFTP_STARTUP_SCRIPT);
            for (Path key : hostKeys.privateKeys()) {
                // sshd refuses a host key that is readable by group or others
                container.withCopyToContainer(Transferable.of(Files.readAllBytes(key), FILE_MODE_OWNER_RW),
                    SFTP_HOST_KEY_DIR + key.getFileName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the Warehouse SFTP host keys", e);
        }
        // No exposed-port mapping by Testcontainers: the only binding is 127.0.0.1:<port> -> 22 (README.md, A-6)
        container.withCreateContainerCmdModifier(cmd -> {
            ExposedPort ssh = ExposedPort.tcp(SFTP_CONTAINER_PORT);
            HostConfig hostConfig = cmd.getHostConfig() != null ? cmd.getHostConfig() : HostConfig.newHostConfig();
            hostConfig.withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort(LOOPBACK, port), ssh));
            cmd.withExposedPorts(ssh).withHostConfig(hostConfig);
        });
        // The default wait checks Testcontainers-mapped ports, of which there are none here; sshd's own line is used
        container.waitingFor(Wait.forLogMessage(".*Server listening on .*port " + SFTP_CONTAINER_PORT + "\\..*", 1)
            .withStartupTimeout(Duration.ofMillis(millis(STARTUP_TIMEOUT_KEY))));
        container.start();
        LOG.info("Warehouse SFTP Server started on {}:{} (image {})", LOOPBACK, port, container.getDockerImageName());
        return container;
    }

    /** Writes a known_hosts file for {@code [127.0.0.1]:<port>} with this run's host keys and returns {@code file}. */
    public Path writeKnownHosts(int port, Path file) throws IOException {
        hostKeys.writeKnownHosts(port, file);
        return file;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Internals

    private record PinnedImage(String tag, DockerImageName dockerImageName) {
    }

    /** The image guard: a reference without a 64-hex {@code @sha256:} digest throws. */
    private static PinnedImage pin(String role, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException("No image configured for " + role);
        }
        Matcher m = PINNED_REFERENCE.matcher(reference.trim());
        if (!m.matches()) {
            throw new IllegalStateException("Image for " + role + " is not pinned by digest (expected "
                + "<name>[:<tag>]@sha256:<64 hex>): " + reference);
        }
        String name = m.group(1);
        String tag = m.group(2);
        // Testcontainers keeps a tag in front of '@sha256:' as part of the repository name; pull and create by
        // digest alone so the reference Docker receives is exactly <repository>@sha256:<digest>
        DockerImageName image = DockerImageName.parse(name + "@sha256:" + m.group(3));
        image.assertValid();
        return new PinnedImage(tag, image);
    }

    private String configuredImage(String key) {
        String value = settings.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not set in " + PROPERTIES);
        }
        return value.trim();
    }

    private static void logStarted(String role, String configured, String used) {
        LOG.info("Started {} from image {} (pulled and created as {})", role, configured, used);
    }

    private static Properties loadSettings() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = SpecEnvironment.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + PROPERTIES + " not found");
            }
            properties.load(in);
        }
        return properties;
    }

    private static long parseMillis(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is not set in " + PROPERTIES);
        }
        try {
            long millis = Long.parseLong(value.trim());
            if (millis < 0) {
                throw new IllegalArgumentException(key + " is negative: " + value);
            }
            return millis;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " is not a number of milliseconds: " + value, e);
        }
    }

    private void createTopic(String bootstrapServers, String topic) throws InterruptedException {
        long timeoutMs = millis(STARTUP_TIMEOUT_KEY);
        Map<String, Object> config = Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(timeoutMs, Integer.MAX_VALUE),
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) Math.min(timeoutMs, Integer.MAX_VALUE));
        try (Admin admin = Admin.create(config)) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(timeoutMs, TimeUnit.MILLISECONDS);
            admin.describeTopics(List.of(topic)).allTopicNames().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot create Kafka topic " + topic, e);
        }
        LOG.info("Kafka topic {} created", topic);
    }

    /** The pin says 16; the running server must say so too. */
    private static void checkPostgres16(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SHOW server_version_num")) {
            rs.next();
            int version = Integer.parseInt(rs.getString(1).trim());
            if (version < 160000 || version >= 170000) {
                throw new IllegalStateException("The PostgreSQL container is not PostgreSQL 16: " + version);
            }
            LOG.info("PostgreSQL server version {}", version);
        }
    }

    /** The {@code apiKeyName} the Partner Configuration names for Summit Outfitters (read by PartnerRegistration). */
    private static String partnerApiKeyName(Path partnerConfiguration) throws IOException {
        if (!Files.isRegularFile(partnerConfiguration)) {
            throw new IllegalStateException("Partner Configuration not found: " + partnerConfiguration);
        }
        JsonNode root = new ObjectMapper().readTree(partnerConfiguration.toFile());
        for (JsonNode partner : root.path("partners")) {
            if (PARTNER_ID.equals(partner.path("partnerId").asText())) {
                String name = partner.path("apiKeyName").asText("");
                if (!SAFE_SECRET_NAME.matcher(name).matches() || name.contains("..")) {
                    throw new IllegalStateException("Partner " + PARTNER_ID + " has no usable apiKeyName in "
                        + partnerConfiguration);
                }
                return name;
            }
        }
        throw new IllegalStateException("Partner " + PARTNER_ID + " not found in " + partnerConfiguration);
    }

    /** Creates the Secret Store directory (owner only) with one owner-only file holding {@code key}, no line break. */
    private static Path writeSecretStore(Path dir, String apiKeyName, String key) throws IOException {
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Files.createDirectories(dir);
        if (posix) {
            Files.setPosixFilePermissions(dir, OWNER_RWX);
        }
        Path file = dir.resolve(apiKeyName);
        Files.deleteIfExists(file);
        if (posix) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_RW));
        } else {
            Files.createFile(file);
        }
        Files.writeString(file, key, StandardCharsets.UTF_8);
        LOG.info("Secret Store {} holds the API Key named {} (value not logged)", dir, apiKeyName);
        return file;
    }

    /**
     * Sets the admin password from the copied file and deletes it; runs as root before sshd. Without an Ed25519 key
     * of this run, the key the entrypoint generated is removed so sshd offers only the RSA key in known_hosts.
     */
    private String startupScript() {
        StringBuilder script = new StringBuilder()
            .append("#!/bin/bash\n")
            .append("set -eu\n")
            .append("chpasswd < ").append(SFTP_PASSWORD_FILE).append('\n')
            .append("rm -f ").append(SFTP_PASSWORD_FILE).append('\n');
        if (!hostKeys.hasEd25519()) {
            script.append("rm -f ").append(SFTP_HOST_KEY_DIR).append(WarehouseHostKeys.ED25519_KEY_FILE)
                .append(' ').append(SFTP_HOST_KEY_DIR).append(WarehouseHostKeys.ED25519_KEY_FILE).append(".pub\n");
        }
        return script.toString();
    }

    private void stopInJvmServers() {
        try {
            catalog.stop();
        } catch (RuntimeException e) {
            LOG.warn("Stopping the Product Catalog stub failed: {}", e.toString());
        }
        try {
            mailboxes.stop();
        } catch (RuntimeException e) {
            LOG.warn("Stopping the mail server failed: {}", e.toString());
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** {@code length} random bytes as URL-safe Base64 without padding (no ':' so it fits a chpasswd line). */
    private static String randomSecret(int length) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(length));
    }
}
