package com.trailheadgear.orderintegration.spec.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Design 005 §3.9 T11 (§3.1 M2, §3.7 H6): a minimal in-JVM SMTP server on 127.0.0.1 only, written with plain sockets
 * (no third-party library, test classpath only). Nothing is relayed: no real mail is ever sent.
 *
 * <p>It speaks just enough SMTP for JavaMail without AUTH and without STARTTLS (the spec-test profile sets
 * {@code smtp.starttls=false} and no SMTP login): greeting {@code 220}, {@code EHLO}/{@code HELO}, {@code MAIL FROM},
 * {@code RCPT TO}, {@code DATA}, {@code RSET}, {@code NOOP}, {@code QUIT}; anything else gets {@code 502}.
 *
 * <p>The switch: {@link #reject550()} answers every {@code RCPT TO} with
 * {@code 550 5.1.1 Recipient refused} (counted in {@link #rejectedRecipients()}); {@link #accept()} answers {@code 250}
 * and keeps each message's raw text, dot-unstuffed, in {@link #messages()}. A new server rejects.
 */
public final class RejectingSmtpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RejectingSmtpServer.class);
    private static final String LOOPBACK = "127.0.0.1";
    private static final int READ_TIMEOUT_MS = 30_000;

    private volatile boolean rejecting = true;
    private final AtomicInteger rejected = new AtomicInteger();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

    private ServerSocket server;
    private Thread acceptor;

    /** Binds {@code 127.0.0.1:port} (use {@link FreePorts#next()}) and starts answering. */
    public synchronized RejectingSmtpServer start(int port) {
        if (server != null) {
            throw new IllegalStateException("The SMTP server is already started");
        }
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port), 10);
            this.server = socket;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot bind the SMTP server to " + LOOPBACK + ":" + port, e);
        }
        acceptor = new Thread(this::acceptLoop, "rejecting-smtp-" + port);
        acceptor.setDaemon(true);
        acceptor.start();
        LOG.info("Rejecting SMTP server listening on {}:{} (rejecting)", LOOPBACK, port);
        return this;
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("The SMTP server is not started");
        }
        return server.getLocalPort();
    }

    /** Every {@code RCPT TO} is answered {@code 550} from now on. */
    public void reject550() {
        rejecting = true;
        LOG.info("Rejecting SMTP server: RCPT TO answered 550");
    }

    /** Every {@code RCPT TO} is answered {@code 250} and messages are kept from now on. */
    public void accept() {
        rejecting = false;
        LOG.info("Rejecting SMTP server: accepting messages");
    }

    /** How many {@code RCPT TO} were answered 550. */
    public int rejectedRecipients() {
        return rejected.get();
    }

    /** The raw text (headers and body) of every accepted message, in arrival order. */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** How many accepted messages contain {@code text} (e.g. {@code "Subject: Peakmart order PM-1 rejected"}). */
    public long count(String text) {
        return messages.stream().filter(m -> m.contains(text)).count();
    }

    @Override
    public synchronized void close() {
        ServerSocket current = server;
        server = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                LOG.warn("Closing the SMTP server socket failed: {}", e.toString());
            }
        }
        for (Socket client : clients) {
            closeQuietly(client);
        }
        clients.clear();
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void acceptLoop() {
        ServerSocket listening = server;
        while (listening != null && !listening.isClosed()) {
            try {
                Socket client = listening.accept();
                clients.add(client);
                Thread session = new Thread(() -> serve(client), "rejecting-smtp-session");
                session.setDaemon(true);
                session.start();
            } catch (IOException e) {
                if (!listening.isClosed()) {
                    LOG.warn("SMTP accept failed: {}", e.toString());
                }
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            client.setSoTimeout(READ_TIMEOUT_MS);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(),
                StandardCharsets.ISO_8859_1));
            OutputStream out = client.getOutputStream();
            reply(out, "220 127.0.0.1 ESMTP rejecting-smtp-server");
            boolean recipientAccepted = false;
            String line;
            while ((line = in.readLine()) != null) {
                String verb = (line.length() >= 4 ? line.substring(0, 4) : line).toUpperCase(Locale.ROOT);
                switch (verb) {
                    case "EHLO", "HELO" -> {
                        recipientAccepted = false;
                        reply(out, "250 127.0.0.1");
                    }
                    case "MAIL" -> {
                        recipientAccepted = false;
                        reply(out, "250 2.1.0 OK");
                    }
                    case "RCPT" -> {
                        if (rejecting) {
                            rejected.incrementAndGet();
                            reply(out, "550 5.1.1 Recipient refused");
                        } else {
                            recipientAccepted = true;
                            reply(out, "250 2.1.5 OK");
                        }
                    }
                    case "DATA" -> {
                        if (!recipientAccepted) {
                            reply(out, "554 5.5.1 No valid recipients");
                            break;
                        }
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        String message = readData(in);
                        if (message == null) {
                            return;
                        }
                        messages.add(message);
                        recipientAccepted = false;
                        reply(out, "250 2.0.0 Accepted");
                    }
                    case "RSET" -> {
                        recipientAccepted = false;
                        reply(out, "250 2.0.0 OK");
                    }
                    case "NOOP" -> reply(out, "250 2.0.0 OK");
                    case "QUIT" -> {
                        reply(out, "221 2.0.0 Bye");
                        return;
                    }
                    default -> reply(out, "502 5.5.2 Command not implemented");
                }
            }
        } catch (SocketException e) {
            // closed by close() or by the client
        } catch (IOException e) {
            LOG.warn("SMTP session failed: {}", e.toString());
        } finally {
            clients.remove(client);
        }
    }

    /** The lines up to the lone {@code .}, dot-unstuffed, joined with CRLF; null when the connection ends first. */
    private static String readData(BufferedReader in) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(".")) {
                return String.join("\r\n", lines);
            }
            lines.add(line.startsWith(".") ? line.substring(1) : line);
        }
        return null;
    }

    private static void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing to do
        }
    }
}
