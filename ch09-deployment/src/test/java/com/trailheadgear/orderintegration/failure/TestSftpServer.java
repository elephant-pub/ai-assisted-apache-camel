package com.trailheadgear.orderintegration.failure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import com.trailheadgear.orderintegration.spec.support.FreePorts;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A Warehouse SFTP Server of its own for design 006 T13 and T14 (§3.1.8 W1, W2; §3.14): the image of
 * {@code spec-test.image.sftp} ({@code spec-test/spec-test.properties}, pinned by digest), configured only by files
 * copied in with Testcontainers ({@code sshd_config}, the user spec, a startup script and, for {@link #subsystemHang()},
 * a subsystem script). No new image, no Docker CLI.
 *
 * <p>Same user ({@code admin}), the same per-run random password ({@link SpecEnvironment#sftpPassword()}, never
 * logged, never in argv or a container environment: it is copied in a file the startup script deletes) and the same
 * per-run host keys as the environment's server, so {@code IntegrationProcess.withSftpPort(port(), knownHostsFile())}
 * is the only change the integration needs. The container is bound to {@code 127.0.0.1:<free port>} only.
 *
 * <ul>
 *   <li>{@link #loginDirectory(String)} (W1, T13): chroot {@code %h} and {@code internal-sftp -d <dir>}, so the login
 *       directory is {@code <dir>}, not {@code /}. The folders {@code <dir>/inbound/picklists/processed} are owned by
 *       {@code admin}; an absolute {@code /inbound/picklists} does not exist.</li>
 *   <li>{@link #subsystemHang()} (W2, T14): no chroot, login directory {@code /home/admin} with
 *       {@code inbound/picklists/processed}. The sftp subsystem is a script that runs the image's {@code sftp-server}
 *       unless the flag file {@value #HANG_FLAG} exists; then it sleeps without reading its input: the login and the
 *       channel are accepted, {@code SSH_FXP_INIT} is never answered. {@link #hang(boolean)} switches it for every
 *       <b>new</b> connection; a connection whose subsystem already runs is not affected.</li>
 * </ul>
 *
 * <p>Close it after the test class ({@link #close()}); Ryuk removes it otherwise.
 */
final class TestSftpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TestSftpServer.class);

    private static final String IMAGE_KEY = "spec-test.image.sftp";
    private static final Pattern PINNED = Pattern.compile("^[a-z0-9][a-z0-9._/:-]*@sha256:[0-9a-f]{64}$");
    private static final Pattern LOGIN_DIRECTORY = Pattern.compile("^/[a-z][a-z0-9_-]{0,30}$");
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$");
    private static final String STARTUP_TIMEOUT_KEY = "spec-test.startup-timeout-ms";

    private static final String LOOPBACK = "127.0.0.1";
    private static final int CONTAINER_PORT = 22;
    private static final String USERNAME = "admin";
    private static final int UID = 1001;
    private static final String HOME = "/home/" + USERNAME;
    private static final String INBOUND = "inbound/picklists";
    private static final String PROCESSED = "inbound/picklists/processed";

    private static final String SSHD_CONFIG = "/etc/ssh/sshd_config";
    private static final String HOST_KEY_DIR = "/etc/ssh/";
    private static final String USERS_CONF = "/etc/sftp/users.conf";
    private static final String PASSWORD_FILE = "/etc/sftp/test-sftp-admin.chpasswd";
    private static final String STARTUP_SCRIPT = "/etc/sftp.d/10-test-sftp-server";
    private static final String SUBSYSTEM_SCRIPT = "/usr/local/bin/sftp-or-hang";
    static final String HANG_FLAG = "/tmp/hang";

    private static final int MODE_OWNER_RW = 0100600;
    private static final int MODE_OWNER_RWX = 0100700;
    private static final int MODE_ALL_RX = 0100755;
    private static final int SFTP_TIMEOUT_MS = 10_000;

    private final boolean hangMode;
    /** In the container: the folder that is the SFTP login directory ({@code <chroot>/<dir>} or the home). */
    private final String loginRoot;
    private final int port;
    private final Path knownHostsFile;
    private final GenericContainer<?> container;

    /** W1: a server whose SFTP login directory is {@code dir} (e.g. {@code /work}), chrooted to the user's home. */
    static TestSftpServer loginDirectory(String dir) {
        if (dir == null || !LOGIN_DIRECTORY.matcher(dir).matches()) {
            throw new IllegalArgumentException("Not a login directory (/<name>): " + dir);
        }
        return new TestSftpServer(false, dir);
    }

    /** W2: a server that accepts the login and the channel and, while {@link #hang(boolean) hanging}, never starts SFTP. */
    static TestSftpServer subsystemHang() {
        return new TestSftpServer(true, null);
    }

    private TestSftpServer(boolean hangMode, String loginDirectory) {
        SpecEnvironment env = SpecEnvironment.get();
        String image = env.settings().getProperty(IMAGE_KEY, "").trim();
        if (!PINNED.matcher(image).matches()) {
            throw new IllegalStateException(IMAGE_KEY + " is not pinned by digest: " + image);
        }
        this.hangMode = hangMode;
        String relativeLogin = hangMode ? "" : loginDirectory.substring(1) + "/";
        this.loginRoot = hangMode ? HOME : HOME + loginDirectory;
        this.port = FreePorts.next();

        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse(image));
        try {
            // The user spec carries no password (the image's entrypoint prints it); the startup script sets it
            c.withCopyToContainer(Transferable.of(USERNAME + "::" + UID + "::" + relativeLogin + INBOUND + ","
                + relativeLogin + PROCESSED + "\n"), USERS_CONF);
            c.withCopyToContainer(Transferable.of(USERNAME + ":" + env.sftpPassword() + "\n", MODE_OWNER_RW),
                PASSWORD_FILE);
            c.withCopyToContainer(Transferable.of(startupScript(env), MODE_OWNER_RWX), STARTUP_SCRIPT);
            c.withCopyToContainer(Transferable.of(sshdConfig(hangMode, loginDirectory), MODE_OWNER_RW), SSHD_CONFIG);
            if (hangMode) {
                c.withCopyToContainer(Transferable.of(subsystemScript(), MODE_ALL_RX), SUBSYSTEM_SCRIPT);
            }
            for (Path key : env.hostKeys().privateKeys()) {
                // sshd refuses a host key readable by group or others
                c.withCopyToContainer(Transferable.of(Files.readAllBytes(key), MODE_OWNER_RW),
                    HOST_KEY_DIR + key.getFileName());
            }
            Path dir = env.workDir().resolve("test-sftp").resolve((hangMode ? "hang-" : "login-") + port);
            this.knownHostsFile = env.writeKnownHosts(port, dir.resolve("known_hosts"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare the test SFTP server", e);
        }
        // The only binding is 127.0.0.1:<port> -> 22, as for the environment's server
        c.withCreateContainerCmdModifier(cmd -> {
            ExposedPort ssh = ExposedPort.tcp(CONTAINER_PORT);
            HostConfig hostConfig = cmd.getHostConfig() != null ? cmd.getHostConfig() : HostConfig.newHostConfig();
            hostConfig.withPortBindings(new PortBinding(Ports.Binding.bindIpAndPort(LOOPBACK, port), ssh));
            cmd.withExposedPorts(ssh).withHostConfig(hostConfig);
        });
        c.waitingFor(Wait.forLogMessage(".*Server listening on .*port " + CONTAINER_PORT + "\\..*", 1)
            .withStartupTimeout(Duration.ofMillis(env.millis(STARTUP_TIMEOUT_KEY))));
        c.start();
        this.container = c;
        LOG.info("Test SFTP server ({}) on {}:{}, login directory {}", hangMode ? "subsystem hang" : "login directory",
            LOOPBACK, port, hangMode ? HOME : loginDirectory);
    }

    int port() {
        return port;
    }

    /** known_hosts for {@code [127.0.0.1]:<port()>} with the run's host keys. */
    Path knownHostsFile() {
        return knownHostsFile;
    }

    /** {@link #subsystemHang()} only: from now on, a new connection's subsystem hangs ({@code true}) or runs. */
    void hang(boolean hang) {
        if (!hangMode) {
            throw new IllegalStateException("hang() needs a subsystemHang() server");
        }
        if (hang) {
            exec("touch", HANG_FLAG);
        } else {
            exec("rm", "-f", HANG_FLAG);
        }
        LOG.info("Test SFTP server on port {}: subsystem {} for new connections", port, hang ? "hangs" : "runs");
    }

    /** Every regular file under the login directory, as a path relative to it (sorted). Read in the container. */
    List<String> files() {
        String out = exec("find", loginRoot, "-type", "f").getStdout();
        List<String> files = new ArrayList<>();
        String prefix = loginRoot + "/";
        for (String line : out.split("\n")) {
            String path = line.strip();
            if (path.startsWith(prefix)) {
                files.add(path.substring(prefix.length()));
            }
        }
        files.sort(null);
        return files;
    }

    /** How many regular files under the login directory are named {@code name}, in any folder. */
    int count(String name) {
        if (name == null || name.isBlank() || name.contains("/")) {
            throw new IllegalArgumentException("Not a file name: " + name);
        }
        return (int) files().stream().filter(p -> p.equals(name) || p.endsWith("/" + name)).count();
    }

    /**
     * Moves {@code from} to {@code to} (paths relative to the login directory) with an SFTP rename as the user, as the
     * Warehouse does with a picked-up file. A server that {@link #hang(boolean) hangs} cannot do it.
     */
    void move(String from, String to) {
        requireRelative(from);
        requireRelative(to);
        SpecEnvironment env = SpecEnvironment.get();
        Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(knownHostsFile.toString());
            session = jsch.getSession(USERNAME, LOOPBACK, port);
            session.setPassword(env.sftpPassword());
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "password");
            session.setTimeout(SFTP_TIMEOUT_MS);
            session.connect(SFTP_TIMEOUT_MS);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(SFTP_TIMEOUT_MS);
            channel.rename(from, to);
            LOG.info("Test SFTP server on port {}: moved {} to {}", port, from, to);
        } catch (JSchException | SftpException e) {
            // JSch messages name the path and the reply, never the password
            throw new IllegalStateException("Cannot move " + from + " to " + to + " on the test SFTP server: " + e, e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    @Override
    public void close() {
        container.stop();
        LOG.info("Test SFTP server on port {} stopped", port);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private Container.ExecResult exec(String... command) {
        Container.ExecResult result;
        try {
            result = container.execInContainer(command);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot run " + command[0] + " in the test SFTP server", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + command[0] + " in the test SFTP server", e);
        }
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " in the test SFTP server exited with "
                + result.getExitCode() + ": " + result.getStderr().strip());
        }
        return result;
    }

    private static void requireRelative(String path) {
        if (path == null || !RELATIVE_PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException("Not a relative path: " + path);
        }
    }

    /** Password from the copied file (then deleted); only this run's host keys; a login shell for the subsystem. */
    private String startupScript(SpecEnvironment env) {
        StringBuilder script = new StringBuilder()
            .append("#!/bin/bash\n")
            .append("set -eu\n")
            .append("chpasswd < ").append(PASSWORD_FILE).append('\n')
            .append("rm -f ").append(PASSWORD_FILE).append('\n')
            .append("rm -f ").append(HANG_FLAG).append('\n');
        if (!env.hostKeys().hasEd25519()) {
            script.append("rm -f ").append(HOST_KEY_DIR).append("ssh_host_ed25519_key ").append(HOST_KEY_DIR)
                .append("ssh_host_ed25519_key.pub\n");
        }
        if (hangMode) {
            // sshd starts a subsystem through the user's shell
            script.append("usermod -s /bin/sh ").append(USERNAME).append('\n');
        }
        return script.toString();
    }

    private static String sshdConfig(boolean hangMode, String loginDirectory) {
        StringBuilder config = new StringBuilder()
            .append("HostKey /etc/ssh/ssh_host_ed25519_key\n")
            .append("HostKey /etc/ssh/ssh_host_rsa_key\n")
            .append("UseDNS no\n")
            .append("PermitRootLogin no\n")
            .append("PasswordAuthentication yes\n")
            .append("X11Forwarding no\n")
            .append("AllowTcpForwarding no\n");
        if (hangMode) {
            config.append("Subsystem sftp ").append(SUBSYSTEM_SCRIPT).append('\n');
        } else {
            config.append("Subsystem sftp internal-sftp -d ").append(loginDirectory).append('\n')
                .append("ForceCommand internal-sftp -d ").append(loginDirectory).append('\n')
                .append("ChrootDirectory %h\n");
        }
        return config.toString();
    }

    /** Runs the image's sftp-server, or, while the flag file exists, sleeps without reading (never answers INIT). */
    private static String subsystemScript() {
        return "#!/bin/sh\n"
            + "if [ -e " + HANG_FLAG + " ]; then\n"
            + "  exec sleep 3600 < /dev/null\n"
            + "fi\n"
            + "for server in /usr/lib/ssh/sftp-server /usr/lib/openssh/sftp-server /usr/libexec/sftp-server; do\n"
            + "  if [ -x \"$server\" ]; then\n"
            + "    exec \"$server\"\n"
            + "  fi\n"
            + "done\n"
            + "echo 'no sftp-server in the image' >&2\n"
            + "exit 1\n";
    }
}
