package com.trailheadgear.orderintegration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.springframework.beans.factory.DisposableBean;

/**
 * Delivers a Pick List to the Warehouse SFTP Server and tells whether it is already there (design 003,
 * pick-list-delivery, C-18; design 006 A-9).
 *
 * <p><b>Already delivered?</b> ({@link #deliveredFolder(String)}) The Warehouse moves picked-up files to the processed
 * folder. This bean runs SFTP {@code stat} on {@code <inbound>/<file>} <b>first</b> and then on
 * {@code <processed>/<file>}: a server-side rename is atomic, so a file that is not in inbound at the first check either
 * was never delivered or is found in processed by the second. No folder is listed.
 *
 * <p><b>Upload</b> ({@link #upload(String, String)}, design 006 A-9: no sftp endpoint option bounds the channel start,
 * plan P-3, so the upload moved here from Camel's sftp producer). It keeps that endpoint's guards: {@code stat} of the
 * final name, which fails the attempt when the file is there (the old {@code fileExist: Fail}); the CSV written as
 * {@code <file>.tmp} (a leftover {@code .tmp} is overwritten, as Camel's temp-file handling does) and then renamed to
 * the final name, so the Warehouse, which ignores {@code .tmp} files, never sees a partial file (C-014); no folder is
 * created (the old {@code autoCreate: false}); UTF-8 bytes. JSch's rename is the call Camel's producer made, so the
 * rename semantics are unchanged (P-4).
 *
 * <p><b>Folders</b> (design 006 D16, F-2): both are taken relative to the SFTP login directory, with any leading
 * {@code /} removed ({@link #remoteFolder(String)}), as Camel's sftp producer took its endpoint path, so the check and
 * the upload always mean the same folder.
 *
 * <p>The connection uses the {@code warehouse.sftp.*} settings: strict host-key checking against the known-hosts file
 * of {@code warehouse.sftp.known-hosts} only, password login, connect and socket timeouts. One session is reused by
 * the check and the upload and reconnected when broken; the single poller calls it, and the methods are synchronised.
 * Any failure closes the session. The password is never logged.
 *
 * <p><b>Time limit (design 006 D17, F-1).</b> Each public call has one deadline of {@code warehouse.sftp.timeout-ms},
 * covering session connect, channel open, channel start ({@code ChannelSftp.connect}), each {@code stat}, the write
 * and the rename. JSch's channel start reads the server's {@code SSH_FXP_VERSION} with no limit of its own (a server
 * that accepts the login but never starts the SFTP subsystem holds it for ever, W2). Before each step a watchdog is
 * scheduled at the time left; when it fires it disconnects the session, which ends the blocked read, and the step fails
 * with a {@link WarehouseSftpTimeoutException} naming the step. The delivery attempt then fails as any SFTP failure
 * does: counted, and tried again on the next poll.
 */
public class WarehouseSftp implements DisposableBean {

    public static final String INBOUND = "inbound";
    public static final String PROCESSED = "processed";
    /** A file name built from an Order Number that passed the order schema (path safety). */
    private static final Pattern FILE_NAME = Pattern.compile("^PL-(TG-\\d{4,}|PM-\\d+)\\.csv$");
    /** C-014: the Warehouse ignores files with this suffix. */
    static final String TMP_SUFFIX = ".tmp";

    static final String SESSION_CONNECT = "session-connect";
    static final String CHANNEL_OPEN = "channel-open";
    static final String CHANNEL_START = "channel-start";
    static final String STAT = "stat";
    static final String UPLOAD = "upload";
    static final String RENAME = "rename";

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String knownHostsFile;
    private final int timeoutMs;
    private final String inboundDirectory;
    private final String processedDirectory;
    /** D17: one daemon thread that disconnects a session whose step passed the deadline; shut down in destroy(). */
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "warehouse-sftp-deadline");
        thread.setDaemon(true);
        return thread;
    });

    /** Watchdog states of one step. */
    private static final int ARMED = 0;
    private static final int FIRED = 1;
    private static final int DONE = 2;

    private Session session;
    private ChannelSftp channel;

    public WarehouseSftp(String host, int port, String username, String password, String knownHostsFile, int timeoutMs,
        String inboundDirectory, String processedDirectory) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.knownHostsFile = knownHostsFile;
        this.timeoutMs = timeoutMs;
        // A cancelled watchdog leaves the queue at once instead of holding its session until its time
        this.deadlines.setRemoveOnCancelPolicy(true);
        // D16: the same rule as the sftp producer, for both folders, so every stat uses the converted path
        this.inboundDirectory = remoteFolder(inboundDirectory);
        this.processedDirectory = remoteFolder(processedDirectory);
    }

    /**
     * The folder ({@value #INBOUND} or {@value #PROCESSED}) where the Pick List file already is, or null when it is in
     * neither. "No such file" is a miss; every other failure is thrown, so the delivery attempt fails. Ends within
     * {@code warehouse.sftp.timeout-ms} (D17).
     */
    public synchronized String deliveredFolder(String fileName) throws JSchException, SftpException, IOException {
        requireFileName(fileName);
        return withFiles(files -> deliveredFolder(fileName, files));
    }

    /**
     * Writes the Pick List {@code csv} (UTF-8) to the inbound folder as {@code fileName}: temporary name, then rename
     * (C-014); fails when the final file is already there. Ends within {@code warehouse.sftp.timeout-ms} (D17).
     */
    public synchronized void upload(String fileName, String csv) throws JSchException, SftpException, IOException {
        requireFileName(fileName);
        if (csv == null) {
            throw new IllegalArgumentException("A Pick List CSV is required");
        }
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        withFiles(files -> {
            upload(fileName, bytes, files);
            return null;
        });
    }

    /** The inbound-then-processed lookup, on a seam so that it is testable without an SFTP server. */
    String deliveredFolder(String fileName, RemoteFiles files) throws JSchException, SftpException, IOException {
        requireFileName(fileName);
        if (files.exists(inboundDirectory + "/" + fileName)) {
            return INBOUND;
        }
        if (files.exists(processedDirectory + "/" + fileName)) {
            return PROCESSED;
        }
        return null;
    }

    /** The upload's order of operations (P-4), on the seam: stat of the final name, write the .tmp, rename. */
    void upload(String fileName, byte[] bytes, RemoteFiles files) throws JSchException, SftpException, IOException {
        requireFileName(fileName);
        String target = inboundDirectory + "/" + fileName;
        if (files.exists(target)) {
            // The old fileExist: Fail; no path in the exception (it is never logged with a message, C-021)
            throw new FileAlreadyExistsException("final pick list file");
        }
        String tmp = target + TMP_SUFFIX;
        files.put(tmp, bytes);
        files.rename(tmp, target);
    }

    /**
     * Design 006 D16 (F-2): a configured folder as the SFTP server sees it, relative to the login directory, with every
     * leading {@code /} removed. This is what Camel's sftp producer does with the endpoint path ("SftpComponent doesn't
     * support absolute paths, "/inbound/picklists" will be converted to "inbound/picklists""), so the upload and this
     * bean's existence check resolve the same folder whatever the login directory. On a chrooted server whose login
     * directory is {@code /} the paths are unchanged.
     */
    static String remoteFolder(String folder) {
        int start = 0;
        while (start < folder.length() && folder.charAt(start) == '/') {
            start++;
        }
        return folder.substring(start);
    }

    /**
     * The SFTP operations the lookup and the upload use. "No such file" is false for {@link #exists}; every other
     * failure is thrown (in the bean: also a {@link WarehouseSftpTimeoutException}, or a {@link JSchException} of a
     * broken session).
     */
    interface RemoteFiles {
        boolean exists(String path) throws JSchException, SftpException, IOException;

        /** Writes {@code bytes} to {@code tmpPath}, overwriting a leftover file of that name. */
        void put(String tmpPath, byte[] bytes) throws JSchException, SftpException, IOException;

        /** Renames {@code from} to {@code to} on the server. */
        void rename(String from, String to) throws JSchException, SftpException, IOException;
    }

    /** The bean's {@link RemoteFiles}: each operation on the connected channel, under the call's deadline. */
    private final class ChannelFiles implements RemoteFiles {
        private final ChannelSftp sftp;
        private final Session target;
        private final long deadline;

        ChannelFiles(ChannelSftp sftp, Session target, long deadline) {
            this.sftp = sftp;
            this.target = target;
            this.deadline = deadline;
        }

        @Override
        public boolean exists(String path) throws JSchException, SftpException, IOException {
            return step(STAT, deadline, target, () -> {
                try {
                    sftp.stat(path);
                    return true;
                } catch (SftpException e) {
                    if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                        return false;
                    }
                    throw e;
                }
            });
        }

        @Override
        public void put(String tmpPath, byte[] bytes) throws JSchException, SftpException, IOException {
            step(UPLOAD, deadline, target, () -> {
                sftp.put(new ByteArrayInputStream(bytes), tmpPath, ChannelSftp.OVERWRITE);
                return null;
            });
        }

        @Override
        public void rename(String from, String to) throws JSchException, SftpException, IOException {
            step(RENAME, deadline, target, () -> {
                sftp.rename(from, to);
                return null;
            });
        }
    }

    @FunctionalInterface
    private interface FilesWork<T> {
        T apply(RemoteFiles files) throws JSchException, SftpException, IOException;
    }

    /** One public call: one deadline, the (re)used connection, and the session closed on any failure. */
    private <T> T withFiles(FilesWork<T> work) throws JSchException, SftpException, IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            ChannelSftp sftp = connect(deadline);
            return work.apply(new ChannelFiles(sftp, session, deadline));
        } catch (JSchException | SftpException | IOException | RuntimeException | Error e) {
            // A session left in the middle of an SFTP exchange is never reused
            close();
            throw e;
        }
    }

    private static void requireFileName(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Not a Pick List file name");
        }
    }

    private ChannelSftp connect(long deadline) throws JSchException, SftpException, IOException {
        if (session != null && session.isConnected() && channel != null && channel.isConnected()) {
            return channel;
        }
        close();
        JSch jsch = new JSch();
        jsch.setKnownHosts(knownHostsFile);
        Session newSession = jsch.getSession(username, host, port);
        newSession.setPassword(password);
        newSession.setConfig("StrictHostKeyChecking", "yes");
        newSession.setConfig("PreferredAuthentications", "password");
        newSession.setTimeout(timeoutMs);
        try {
            step(SESSION_CONNECT, deadline, newSession, () -> {
                newSession.connect(timeoutMs);
                return null;
            });
            ChannelSftp newChannel = step(CHANNEL_OPEN, deadline, newSession,
                () -> (ChannelSftp) newSession.openChannel("sftp"));
            step(CHANNEL_START, deadline, newSession, () -> {
                newChannel.connect(timeoutMs);
                return null;
            });
            session = newSession;
            channel = newChannel;
            return channel;
        } catch (JSchException | SftpException | IOException | RuntimeException | Error e) {
            newSession.disconnect();
            throw e;
        }
    }

    /** One SFTP step, run under what is left of the call's deadline. */
    @FunctionalInterface
    private interface Step<T> {
        T run() throws JSchException, SftpException, IOException;
    }

    /**
     * Runs {@code action} with a watchdog at the time left before {@code deadline} that disconnects {@code target}.
     * When the watchdog fired, the step's own outcome is replaced by a {@link WarehouseSftpTimeoutException} (the
     * original failure is kept as suppressed, not as the cause, so the recorded kind names the timeout and its step).
     * A step the server refused in time ({@link SftpException}) fails as a {@link WarehouseSftpStepException} with the
     * step and the SFTP status (developer check 4, W2-F4). The watchdog is cancelled when the step ends in time.
     */
    private <T> T step(String step, long deadline, Session target, Step<T> action)
        throws JSchException, SftpException, IOException {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
            throw new WarehouseSftpTimeoutException(step, timeoutMs);
        }
        // ARMED -> FIRED (watchdog) or ARMED -> DONE (step ended): exactly one side wins, so a watchdog that is already
        // running can never disconnect a session whose step was reported as ended in time
        AtomicInteger state = new AtomicInteger(ARMED);
        ScheduledFuture<?> watchdog = deadlines.schedule(() -> {
            if (state.compareAndSet(ARMED, FIRED)) {
                target.disconnect();
            }
        }, left, TimeUnit.NANOSECONDS);
        boolean inTime = false;
        try {
            T result = action.run();
            inTime = state.compareAndSet(ARMED, DONE);
            if (!inTime) {
                // Ended at the deadline, but the watchdog has already disconnected the session
                throw new WarehouseSftpTimeoutException(step, timeoutMs);
            }
            return result;
        } catch (JSchException | SftpException | IOException | RuntimeException e) {
            if (!inTime && !state.compareAndSet(ARMED, DONE)) {
                if (e instanceof WarehouseSftpTimeoutException) {
                    throw e;
                }
                WarehouseSftpTimeoutException timeout = new WarehouseSftpTimeoutException(step, timeoutMs);
                timeout.addSuppressed(e);
                throw timeout;
            }
            if (e instanceof SftpException refused) {
                // Developer check 4 (W2-F4): the recorded kind names the SFTP status and this step
                throw new WarehouseSftpStepException(step, refused);
            }
            throw e;
        } finally {
            // Also on an Error: the watchdog never outlives its step
            state.compareAndSet(ARMED, DONE);
            watchdog.cancel(false);
        }
    }

    private void close() {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }

    @Override
    public synchronized void destroy() {
        close();
        deadlines.shutdownNow();
    }
}
