package com.trailheadgear.orderintegration.spec.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Free TCP ports on 127.0.0.1 for the integration, the catalog stub, the mail server and the TC-005 SFTP server
 * (design 004 §2, §3.3).
 *
 * <p>A port is picked by binding {@code ServerSocket(0)} to 127.0.0.1 only and closing it again. A port listed in
 * {@code spec-test.excluded-ports} (classpath {@code spec-test/spec-test.properties}) is never returned, and neither is a
 * port this JVM has already handed out. 8080, 8081 and 5432 are excluded even if the property were edited (§4
 * Constraints).
 */
public final class FreePorts {

    private static final String PROPERTIES = "spec-test/spec-test.properties";
    private static final String EXCLUDED_PORTS_KEY = "spec-test.excluded-ports";
    private static final String LOOPBACK = "127.0.0.1";
    private static final Set<Integer> ALWAYS_EXCLUDED = Set.of(8080, 8081, 5432);
    private static final int MAX_ATTEMPTS = 200;

    /** Ports handed out by this JVM; guarded by the class lock. */
    private static final Set<Integer> HANDED_OUT = new HashSet<>();

    private FreePorts() {
    }

    /** A free port on 127.0.0.1 that is neither excluded nor already handed out by this JVM. */
    public static synchronized int next() {
        Set<Integer> excluded = excludedPorts();
        InetAddress loopback;
        try {
            loopback = InetAddress.getByName(LOOPBACK);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve " + LOOPBACK, e);
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, loopback)) {
                port = socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot bind a free port on " + LOOPBACK, e);
            }
            if (!excluded.contains(port) && HANDED_OUT.add(port)) {
                return port;
            }
        }
        throw new IllegalStateException("No free port on " + LOOPBACK + " after " + MAX_ATTEMPTS + " attempts");
    }

    /** The ports never returned: {@code spec-test.excluded-ports} plus 8080, 8081 and 5432. */
    public static Set<Integer> excludedPorts() {
        return Holder.EXCLUDED;
    }

    private static final class Holder {
        static final Set<Integer> EXCLUDED = load();

        private static Set<Integer> load() {
            Properties properties = new Properties();
            try (InputStream in = FreePorts.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
                if (in == null) {
                    throw new IllegalStateException("Classpath resource " + PROPERTIES + " is missing");
                }
                properties.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + PROPERTIES, e);
            }
            String value = properties.getProperty(EXCLUDED_PORTS_KEY);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(EXCLUDED_PORTS_KEY + " is not set in " + PROPERTIES);
            }
            Set<Integer> ports = new LinkedHashSet<>();
            for (String token : value.split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    ports.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(EXCLUDED_PORTS_KEY + " has a value that is not a port: " + trimmed, e);
                }
            }
            ports.addAll(ALWAYS_EXCLUDED);
            return Collections.unmodifiableSet(ports);
        }
    }
}
