package com.trailheadgear.orderintegration;

import java.util.regex.Pattern;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.springframework.beans.factory.DisposableBean;

/**
 * Tells whether a Pick List is already on the Warehouse SFTP Server (design 003, pick-list-delivery step 2, C-18).
 *
 * <p>Camel's sftp producer can guard only its own folder ({@code fileExist: Fail}); the Warehouse moves picked-up files
 * to the processed folder. This bean runs SFTP {@code stat} on {@code <inbound>/<file>} <b>first</b> and then on
 * {@code <processed>/<file>}: a server-side rename is atomic, so a file that is not in inbound at the first check either
 * was never delivered or is found in processed by the second. No folder is listed.
 *
 * <p>The connection uses the same {@code warehouse.sftp.*} settings as the sftp endpoint: strict host-key checking
 * against the known-hosts file of {@code warehouse.sftp.known-hosts} only, password login, connect and socket timeouts. One session is reused and reconnected
 * when broken; the single poller calls it, and the method is synchronised. The password is never logged.
 */
public class WarehouseSftp implements DisposableBean {

    public static final String INBOUND = "inbound";
    public static final String PROCESSED = "processed";
    /** A file name built from an Order Number that passed the order schema (path safety). */
    private static final Pattern FILE_NAME = Pattern.compile("^PL-(TG-\\d{4,}|PM-\\d+)\\.csv$");

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String knownHostsFile;
    private final int timeoutMs;
    private final String inboundDirectory;
    private final String processedDirectory;

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
        this.inboundDirectory = inboundDirectory;
        this.processedDirectory = processedDirectory;
    }

    /**
     * The folder ({@value #INBOUND} or {@value #PROCESSED}) where the Pick List file already is, or null when it is in
     * neither. "No such file" is a miss; every other failure is thrown, so the delivery attempt fails.
     */
    public synchronized String deliveredFolder(String fileName) throws JSchException, SftpException {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException("Not a Pick List file name");
        }
        ChannelSftp sftp = connect();
        try {
            if (exists(sftp, inboundDirectory + "/" + fileName)) {
                return INBOUND;
            }
            if (exists(sftp, processedDirectory + "/" + fileName)) {
                return PROCESSED;
            }
            return null;
        } catch (SftpException e) {
            close();
            throw e;
        }
    }

    private static boolean exists(ChannelSftp sftp, String path) throws SftpException {
        try {
            sftp.stat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    private ChannelSftp connect() throws JSchException {
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
            newSession.connect(timeoutMs);
            ChannelSftp newChannel = (ChannelSftp) newSession.openChannel("sftp");
            newChannel.connect(timeoutMs);
            session = newSession;
            channel = newChannel;
            return channel;
        } catch (JSchException e) {
            newSession.disconnect();
            throw e;
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
    }
}
