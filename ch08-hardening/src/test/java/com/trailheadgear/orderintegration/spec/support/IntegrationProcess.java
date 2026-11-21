package com.trailheadgear.orderintegration.spec.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The integration under test (design 004 §3.1, plan P-7, Task 9): the packaged jar {@code ${spec-test.jar}} as a child
 * process with profile {@code spec-test}, reached only through its external interfaces.
 *
 * <p><b>Launch.</b> The same {@code java} that runs the test JVM ({@code ProcessHandle.current().info().command()})
 * runs {@code -jar <jar> --spring.profiles.active=spec-test
 * --spring.config.additional-location=file:<run dir>/,file:<run dir>/application-spec-test-run.properties}. The
 * working directory is the run directory {@code <work dir>/run/<TC-id>/}, so the project's {@code ./config/} (the dev
 * profile and its secrets) is never read. The directory location makes Spring Boot load the profile-specific
 * {@code application-spec-test.properties} (copied from the test classpath) for the active profile; the explicit file
 * location loads the generated run file whatever the profile. An explicit {@code file:} prefix is used so neither
 * location can be taken for a classpath resource; neither is {@code optional:}, so a missing file fails the start.
 *
 * <p><b>No secret in argv or environment.</b> The command line holds only the jar path, the profile and the two
 * locations. The child environment is reduced to a small allowlist ({@link #INHERITED_ENVIRONMENT}: PATH, HOME, locale,
 * time zone, temp directory, and the Windows system variables); every other inherited variable is removed and none is
 * added. Spring Boot's relaxed binding gives environment variables precedence over config files, so an inherited
 * {@code SPRING_*}, {@code SERVER_PORT}, {@code KAFKA_BROKERS}, {@code SMTP_HOST} … would silently override the run file,
 * and {@code JAVA_TOOL_OPTIONS}/{@code JDK_JAVA_OPTIONS} would add JVM options; all of them are dropped.
 *
 * <p><b>Readiness.</b> stdout and stderr go to {@code <work dir>/logs/<TC-id>.log} ({@code <TC-id>-2.log},
 * {@code -3} … for later starts). The process is ready when, within {@code spec-test.startup-timeout-ms}, the log holds
 * Spring Boot's line {@value #READY_MARKER}… ({@code StartupInfoLogger}: "Started CamelApplication in n seconds") and a
 * TCP connect to {@code 127.0.0.1:server.port} succeeds. If the process exits or the time runs out it is stopped and
 * the start fails with the last 50 log lines (secrets redacted). After a start the log is checked to hold neither the
 * partner API Key nor the database or SFTP password (NFR-011).
 *
 * <p><b>Stop.</b> {@link Process#destroy()} (SIGTERM, Spring Boot's graceful shutdown), up to 30 s wait, then
 * {@link Process#destroyForcibly()}. A JVM shutdown hook does the same, so a crashed test never leaves the child
 * running. Log files are kept.
 */
public final class IntegrationProcess {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationProcess.class);

    /** Spring Boot's startup line for the main class {@code com.trailheadgear.orderintegration.CamelApplication}. */
    static final String READY_MARKER = "Started CamelApplication in ";

    private static final String PROFILE = "spec-test";
    private static final String JAR_PROPERTY = "spec-test.jar";
    private static final String FAULT_PROFILE = "fault-injection";
    private static final String FAULT_HOOK_JAR_PROPERTY = "spec-test.fault-hook-jar";
    private static final String LOADER_PATH_KEY = "loader.path";
    private static final String PROPERTIES_LAUNCHER = "org.springframework.boot.loader.launch.PropertiesLauncher";
    private static final String STARTUP_TIMEOUT_KEY = "spec-test.startup-timeout-ms";
    private static final String STATIC_SETTINGS_RESOURCE = "spec-test/application-spec-test.properties";
    private static final String STATIC_SETTINGS_FILE = "application-spec-test.properties";
    private static final String RUN_SETTINGS_FILE = "application-spec-test-run.properties";
    private static final String LOOPBACK = "127.0.0.1";

    private static final Pattern TEST_CASE_ID = Pattern.compile("^TC-[0-9]{3}$");
    private static final Pattern PROPERTY_KEY = Pattern.compile("^[a-z0-9][a-z0-9.-]{0,199}$");
    private static final long STOP_GRACE_SECONDS = 30;

    private volatile boolean lastStopGraceful;
    private static final long KILL_WAIT_SECONDS = 10;
    private static final long POLL_MILLIS = 250;
    private static final int CONNECT_TIMEOUT_MILLIS = 1000;
    private static final int FAILURE_LOG_LINES = 50;
    private static final String REDACTED = "****";

    /** The only inherited environment variables (compared case-insensitively); everything else is removed. */
    private static final Set<String> INHERITED_ENVIRONMENT = Set.of(
        "PATH", "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TMPDIR", "TMP", "TEMP",
        "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT");

    private static final Set<PosixFilePermission> OWNER_RW =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_RWX =
        EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final String testCaseId;
    /** Chosen once per instance and kept across {@link #restart()}. */
    private final int serverPort;

    private Integer sftpPortOverride;
    private Path knownHostsOverride;
    /** Design 005: null for the spec ITs; the {@code fault.points} value for the failure ITs. */
    private String faultPoints;
    /** Design 005 T11: extra run-file settings, written after (and overriding) the standard ones. */
    private final Map<String, String> extraProperties = new LinkedHashMap<>();

    private Path runDir;
    private Path logFile;
    /** Volatile: read by the shutdown hook without the lock. */
    private volatile Process process;
    private int starts;
    private Thread shutdownHook;

    public IntegrationProcess(String testCaseId) {
        if (testCaseId == null || !TEST_CASE_ID.matcher(testCaseId).matches()) {
            throw new IllegalArgumentException("Not a test case id (TC-nnn): " + testCaseId);
        }
        this.testCaseId = testCaseId;
        this.serverPort = FreePorts.next();
    }

    /**
     * TC-005: the integration uses this Warehouse SFTP port and known_hosts file instead of the environment's. Only
     * before the first {@link #start()}; kept across {@link #restart()}.
     */
    public synchronized IntegrationProcess withSftpPort(int port, Path knownHostsFile) {
        if (starts > 0) {
            throw new IllegalStateException("The SFTP settings must be set before the first start");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Not a port: " + port);
        }
        if (knownHostsFile == null) {
            throw new IllegalArgumentException("knownHostsFile is required");
        }
        this.sftpPortOverride = port;
        this.knownHostsOverride = knownHostsFile.toAbsolutePath().normalize();
        return this;
    }

    /**
     * Design 005 §3.9: the integration starts with the fault hook ({@code PropertiesLauncher}, the fault-hook jar on
     * {@code loader.path}, profiles {@code spec-test,fault-injection}); {@code points} goes into the run properties
     * file as {@code fault.points}, never into argv. Only before the first {@link #start()}; kept across
     * {@link #restart()}.
     */
    public synchronized IntegrationProcess withFaults(String points) {
        if (starts > 0) {
            throw new IllegalStateException("The fault points must be set before the first start");
        }
        if (points == null || points.isBlank()) {
            throw new IllegalArgumentException("fault points are required");
        }
        this.faultPoints = points.strip();
        return this;
    }

    /**
     * Design 005 §3.9 T11: {@code key=value} goes into the run properties file (never into argv), after the standard
     * keys, so it overrides one of them (e.g. {@code smtp.port} pointed at a {@code RejectingSmtpServer}). Only before
     * the first {@link #start()}; kept across {@link #restart()}. {@code fault.points} is set with {@link #withFaults}.
     */
    public synchronized IntegrationProcess withProperty(String key, String value) {
        if (starts > 0) {
            throw new IllegalStateException("The run properties must be set before the first start");
        }
        if (key == null || !PROPERTY_KEY.matcher(key).matches() || "fault.points".equals(key)) {
            throw new IllegalArgumentException("Not a settable property key: " + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("A value is required for " + key);
        }
        extraProperties.put(key, value);
        return this;
    }

    /**
     * Design 005 §3.9 (KillIT): the next {@link #start()} is a normal start, without the fault hook, with the same run
     * directory, port and settings (the start after a halt). Only while the integration is not running.
     */
    public synchronized IntegrationProcess withoutFaults() {
        if (isRunning()) {
            throw new IllegalStateException(testCaseId + ": the fault points can only be dropped while not running");
        }
        this.faultPoints = null;
        return this;
    }

    /**
     * The exit code of a process that ends by itself (e.g. {@code Runtime.halt}), waiting up to {@code timeout}; empty
     * when it is still running or was never started. A process that ended by itself is never reported graceful.
     */
    public Optional<Integer> waitForExit(Duration timeout) {
        Process current = process;
        if (current == null) {
            return Optional.empty();
        }
        try {
            if (current.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                lastStopGraceful = false;
                return Optional.of(current.exitValue());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /** Writes the run configuration, starts the jar and waits until it is ready; fails with the last log lines. */
    public synchronized void start() {
        if (isRunning()) {
            throw new IllegalStateException(testCaseId + ": the integration is already running");
        }
        SpecEnvironment env = SpecEnvironment.get();
        Path jar = jar();
        try {
            this.runDir = env.workDir().resolve("run").resolve(testCaseId).toAbsolutePath().normalize();
            createOwnerOnlyDirectory(runDir);
            writeRunSettings(env, runDir.resolve(RUN_SETTINGS_FILE));
            copyStaticSettings(runDir.resolve(STATIC_SETTINGS_FILE));

            Path logDir = env.workDir().resolve("logs");
            Files.createDirectories(logDir);
            starts++;
            this.logFile = logDir.resolve(starts == 1 ? testCaseId + ".log" : testCaseId + "-" + starts + ".log");
        } catch (IOException e) {
            throw new UncheckedIOException(testCaseId + ": cannot prepare the run directory " + runDir, e);
        }

        ProcessBuilder builder = new ProcessBuilder(command(jar))
            .directory(runDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        reduceEnvironment(builder.environment());

        registerShutdownHook();
        try {
            this.process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException(testCaseId + ": cannot start the integration", e);
        }
        LOG.info("{}: integration started (pid {}, server.port {}, run dir {}, log {})", testCaseId, process.pid(),
            serverPort, runDir, logFile);

        awaitReady(env.millis(STARTUP_TIMEOUT_KEY));
        assertLogHasNoSecrets(env);
        LOG.info("{}: integration ready on {}:{}", testCaseId, LOOPBACK, serverPort);
    }

    /** SIGTERM, up to 30 s for the graceful shutdown, then a forced kill. Does nothing when not running. */
    public synchronized void stop() {
        Process current = process;
        if (current == null) {
            return;
        }
        try {
            terminate(current, STOP_GRACE_SECONDS);
            LOG.info("{}: integration stopped (exit code {})", testCaseId,
                current.isAlive() ? "none, still alive" : String.valueOf(current.exitValue()));
        } finally {
            process = null;
        }
        // The partner key reaches the integration only with the first request: check the whole log once more (§3.1)
        assertLogHasNoSecrets(SpecEnvironment.get());
    }

    /** {@link #stop()} and {@link #start()} with the same run directory, ports and settings; the next log file. */
    public synchronized void restart() {
        stop();
        start();
    }

    /**
     * True when the last stop ended within the grace period after SIGTERM (a graceful shutdown, A-13); false when the
     * process had to be killed.
     */
    public boolean lastStopGraceful() {
        return lastStopGraceful;
    }

    public int serverPort() {
        return serverPort;
    }

    /** {@code <work dir>/run/<TC-id>/}, or null before the first start. */
    public synchronized Path runDir() {
        return runDir;
    }

    /** The log file of the current (or last) start, or null before the first start. */
    public synchronized Path logFile() {
        return logFile;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Configuration

    private static Path faultHookJar() {
        String value = System.getProperty(FAULT_HOOK_JAR_PROPERTY);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property " + FAULT_HOOK_JAR_PROPERTY + " is not set (failsafe sets it)");
        }
        Path hook = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(hook)) {
            throw new IllegalStateException("The fault-hook jar is missing: " + hook + " (run ./mvnw package)");
        }
        return hook;
    }

    private static Path jar() {
        String value = System.getProperty(JAR_PROPERTY);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property " + JAR_PROPERTY + " is not set (failsafe sets it)");
        }
        Path jar = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("The packaged integration is missing: " + jar + " (run ./mvnw verify)");
        }
        return jar;
    }

    private List<String> command(Path jar) {
        String java = ProcessHandle.current().info().command()
            .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
        String dir = runDir.toString();
        if (dir.contains(",")) {
            // spring.config.additional-location is a comma-separated list
            throw new IllegalStateException("The run directory path must not contain a comma: " + dir);
        }
        String locations = "file:" + dir + "/,file:" + runDir.resolve(RUN_SETTINGS_FILE);
        List<String> command = new ArrayList<>();
        command.add(java);
        if (faultPoints == null) {
            command.add("-jar");
            command.add(jar.toString());
            command.add("--spring.profiles.active=" + PROFILE);
        } else {
            // Design 005 §3.9: the same packaged jar, the fault-hook jar on the PropertiesLauncher's loader.path
            command.add("-cp");
            command.add(jar.toString());
            command.add("-D" + LOADER_PATH_KEY + "=" + faultHookJar());
            command.add(PROPERTIES_LAUNCHER);
            command.add("--spring.profiles.active=" + PROFILE + "," + FAULT_PROFILE);
        }
        command.add("--spring.config.additional-location=" + locations);
        return command;
    }

    /** The P-7 keys: every key {@code config/application-dev.properties} sets that the static file does not. */
    private void writeRunSettings(SpecEnvironment env, Path file) throws IOException {
        int sftpPort = sftpPortOverride != null ? sftpPortOverride : env.sftpPort();
        Path knownHosts = knownHostsOverride != null ? knownHostsOverride
            : env.knownHostsFile().toAbsolutePath().normalize();

        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("kafka.brokers", env.kafkaBootstrapServers());
        settings.put("spring.datasource.url", env.jdbcUrl());
        settings.put("spring.datasource.username", env.dbUsername());
        settings.put("spring.datasource.password", env.dbPassword());
        settings.put("catalog.host", LOOPBACK);
        settings.put("catalog.port", String.valueOf(requirePort(env.catalog().port(), "catalog")));
        // The mail server listens on IPv4 loopback only (Mailboxes); "localhost" could resolve to ::1
        settings.put("smtp.host", LOOPBACK);
        settings.put("smtp.port", String.valueOf(requirePort(env.mailboxes().port(), "mail server")));
        settings.put("server.port", String.valueOf(serverPort));
        settings.put("partner.configuration", env.partnerConfigurationFile().toAbsolutePath().normalize().toString());
        settings.put("secret-store.directory", env.secretStoreDir().toAbsolutePath().normalize().toString());
        settings.put("warehouse.sftp.host", env.sftpHost());
        settings.put("warehouse.sftp.port", String.valueOf(sftpPort));
        settings.put("warehouse.sftp.password", env.sftpPassword());
        settings.put("warehouse.sftp.known-hosts", knownHosts.toString());
        if (faultPoints != null) {
            settings.put("fault.points", faultPoints);
        }
        settings.putAll(extraProperties);

        StringBuilder content = new StringBuilder()
            .append("# Generated by IntegrationProcess for ").append(testCaseId)
            .append(" (design 004 §3.1, plan P-7). Holds per-run credentials: never commit, never print.\n");
        settings.forEach((key, value) -> content.append(key).append('=').append(escape(value)).append('\n'));

        Files.deleteIfExists(file);
        if (posix()) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_RW));
        } else {
            Files.createFile(file);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.ISO_8859_1,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.write(content.toString());
        }
    }

    private static int requirePort(int port, String role) {
        if (port < 1) {
            throw new IllegalStateException("The " + role + " is not started");
        }
        return port;
    }

    private static void copyStaticSettings(Path target) throws IOException {
        try (InputStream in = IntegrationProcess.class.getClassLoader().getResourceAsStream(STATIC_SETTINGS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + STATIC_SETTINGS_RESOURCE + " not found");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** {@code java.util.Properties} escaping of a value: backslash, line breaks, a leading blank, non-Latin-1. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                case ' ' -> out.append(i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void createOwnerOnlyDirectory(Path dir) throws IOException {
        Files.createDirectories(dir);
        if (posix()) {
            Files.setPosixFilePermissions(dir, OWNER_RWX);
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void reduceEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> !INHERITED_ENVIRONMENT.contains(name.toUpperCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Readiness, secrets, stop

    private void awaitReady(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean started = false;
        while (true) {
            if (!process.isAlive()) {
                failStart("the integration exited with code " + process.exitValue() + " before it was ready");
            }
            if (!started) {
                started = readLog().contains(READY_MARKER);
            }
            if (started && accepts(serverPort)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                failStart("the integration was not ready within " + timeoutMs + " ms (" + READY_MARKER.trim()
                    + (started ? " logged, but " + LOOPBACK + ":" + serverPort + " refuses connections)"
                        : " not logged)"));
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failStart("interrupted while waiting for the integration");
            }
        }
    }

    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void failStart(String reason) {
        Process failed = process;
        process = null;
        if (failed != null) {
            terminate(failed, STOP_GRACE_SECONDS);
        }
        SpecEnvironment env = SpecEnvironment.get();
        String tail = redact(lastLines(readLog(), FAILURE_LOG_LINES), env);
        throw new IllegalStateException(testCaseId + ": " + reason + ". Log " + logFile + ", last "
            + FAILURE_LOG_LINES + " lines:\n" + tail);
    }

    /** NFR-011: the integration's log holds neither the partner API Key nor a password of this run. */
    private void assertLogHasNoSecrets(SpecEnvironment env) {
        String log = readLog();
        List<String> found = new ArrayList<>();
        if (contains(log, env.partnerApiKey())) {
            found.add("the partner API Key");
        }
        if (contains(log, env.dbPassword())) {
            found.add("the database password");
        }
        if (contains(log, env.sftpPassword())) {
            found.add("the SFTP password");
        }
        if (!found.isEmpty()) {
            Process leaking = process;
            process = null;
            if (leaking != null) {
                terminate(leaking, STOP_GRACE_SECONDS);
            }
            throw new AssertionError(testCaseId + ": the integration log " + logFile + " contains "
                + String.join(", ", found) + " (NFR-011); the values are not shown");
        }
    }

    private static boolean contains(String text, String secret) {
        return secret != null && !secret.isEmpty() && text.contains(secret);
    }

    private static String redact(String text, SpecEnvironment env) {
        String result = text;
        for (String secret : new String[] {env.partnerApiKey(), env.dbPassword(), env.sftpPassword()}) {
            if (secret != null && !secret.isEmpty()) {
                result = result.replace(secret, REDACTED);
            }
        }
        return result;
    }

    private String readLog() {
        if (logFile == null || !Files.exists(logFile)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String lastLines(String text, int count) {
        String[] lines = text.split("\\R");
        int from = Math.max(0, lines.length - count);
        return String.join("\n", java.util.Arrays.asList(lines).subList(from, lines.length));
    }

    private void terminate(Process target, long graceSeconds) {
        if (target == null) {
            return;
        }
        // Pipeline 005: a process that already ended by itself (e.g. a halt) is never reported graceful
        lastStopGraceful = false;
        if (!target.isAlive()) {
            return;
        }
        target.destroy();
        boolean interrupted = false;
        try {
            if (target.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                lastStopGraceful = true;
            } else {
                LOG.warn("{}: the integration did not stop within {} s; killing it", testCaseId, graceSeconds);
                target.destroyForcibly();
                target.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            interrupted = true;
            target.destroyForcibly();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = new Thread(() -> {
            // No lock: a start that is waiting for readiness holds it
            Process current = process;
            if (current != null && current.isAlive()) {
                current.destroy();
                try {
                    if (!current.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS)) {
                        current.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    current.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }, "integration-process-shutdown-" + testCaseId);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
}
