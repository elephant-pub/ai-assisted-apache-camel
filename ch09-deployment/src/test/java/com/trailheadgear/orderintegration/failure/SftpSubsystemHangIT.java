package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Partner;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T14 (design 006 §3.14, §3.1.8 W2, §3.18 D17, review decision 2, plan P-3): a Warehouse SFTP Server that accepts the
 * login and the channel and never starts the SFTP subsystem ({@link TestSftpServer#subsystemHang()}).
 *
 * <p>{@code warehouse.sftp.timeout-ms} is {@value #TIMEOUT_MS} ms, the pick-list poll delay {@value #POLL_DELAY_MS} ms
 * (spec-test profile), the stated margin {@value #MARGIN_MS} ms. Each wait for an attempt to end has the deadline
 * {@code timeout-ms + margin + 2 poll delays}.
 *
 * <ul>
 *   <li><b>check</b>: the subsystem hangs before the integration starts, so the "already delivered?" check of
 *       {@code WarehouseSftp} meets it on its first connection. The attempt must fail within the deadline (attempts
 *       &ge; 1, {@code last_failure} set, Pending) and the next poll must try again (a second attempt). Once the server
 *       answers, the Pick List is delivered once.</li>
 *   <li><b>upload</b>: the check's connection is healthy and only the upload's connection meets the hang. A warm-up
 *       order whose file the test placed in the inbound folder beforehand is counted as already delivered: the check's
 *       session exists, the upload has never connected. Then the subsystem hangs for new connections and a second
 *       order is submitted. Within the deadline its attempt must have ended (Delivered, or attempts &ge; 1 with
 *       {@code last_failure}). Once the server answers, it is delivered once.</li>
 * </ul>
 *
 * <p>Without D17 (and, for the upload, A-9) the poller waits with no limit: the test's own deadline expires. The
 * integration is then stopped by {@link IntegrationProcess#stop()} (SIGTERM, then a forced kill after its grace).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SftpSubsystemHangIT {

    private static final Logger LOG = LoggerFactory.getLogger(SftpSubsystemHangIT.class);

    private static final String PARTNER_SAMPLE = "messages/examples/partner-order/partner-order-sample.json";
    private static final String INBOUND = "inbound/picklists";

    private static final long TIMEOUT_MS = 3000;
    private static final long POLL_DELAY_MS = 1000;
    private static final long MARGIN_MS = 5000;
    private static final Duration ATTEMPT_DEADLINE = Duration.ofMillis(TIMEOUT_MS + MARGIN_MS + 2 * POLL_DELAY_MS);
    private static final Duration FLOW_WAIT = Duration.ofSeconds(60);

    private static final String PENDING = "Pending";
    private static final String DELIVERED = "Delivered";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";

    private static final long POLL_MILLIS = 200L;
    private static final int SFTP_TIMEOUT_MS = 10_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static OrderDatabase db;
    private static TestSftpServer server;

    @BeforeAll
    static void environment() {
        LOG.info("SftpSubsystemHangIT: images {}", SpecEnvironment.get().imageReferences());
        db = new OrderDatabase();
        server = TestSftpServer.subsystemHang();
    }

    @AfterAll
    static void close() {
        if (server != null) {
            server.close();
        }
    }

    /** Case check: the check's channel start hangs; bounded failure, counted, retried; delivered once afterwards. */
    @Test
    @Order(1)
    void checkConnectionThatNeverStartsSftpFailsWithinTheLimitAndIsRetried() throws Throwable {
        String order = "TG-1962";
        String file = pickListFile(order);
        IntegrationProcess integration = integration("TC-962");
        Throwable primary = null;
        try {
            server.hang(true);
            integration.start();
            db.cleanupNow(order);
            assertAccepted(postPartnerOrder(integration, partnerOrder(order)), order);

            await("T14 check: the first attempt for " + order + " ended (attempts >= 1, last_failure set) while the "
                + "subsystem hangs", ATTEMPT_DEADLINE, () -> failedAttempts(order, 1));
            Map<String, Object> failed = pickList(order);
            assertEquals(PENDING, failed.get("delivery_state"), "T14 check: state of the pick list of " + order
                + " after the bounded failure: expected " + PENDING + ", actual " + failed.get("delivery_state"));
            LOG.info("T14 check: last_failure of {}: {}", order, failed.get("last_failure"));
            await("T14 check: the next poll tried " + order + " again (attempts >= 2)", ATTEMPT_DEADLINE,
                () -> failedAttempts(order, 2));
            assertEquals(0, server.count(file), "T14 check: " + file + " of " + order + " on the server while the "
                + "subsystem hangs: expected 0, actual " + server.files());

            server.hang(false);
            awaitDelivered(order);
            assertEquals(1, server.count(file), "T14 check: " + file + " of " + order + " on the server after the "
                + "recovery: expected exactly 1, actual " + server.files());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order);
        }
    }

    /** Case upload: the check's connection is healthy, the upload's connection hangs; the attempt ends in time. */
    @Test
    @Order(2)
    void uploadConnectionThatNeverStartsSftpEndsWithinTheLimit() throws Throwable {
        String warmUp = "TG-1963";
        String order = "TG-1964";
        String file = pickListFile(order);
        IntegrationProcess integration = integration("TC-963");
        Throwable primary = null;
        try {
            server.hang(false);
            integration.start();
            db.cleanupNow(warmUp);
            db.cleanupNow(order);

            // Warm-up: the check connects and finds the file; the upload never connects
            place(INBOUND + "/" + pickListFile(warmUp));
            assertAccepted(postPartnerOrder(integration, partnerOrder(warmUp)), warmUp);
            awaitDelivered(warmUp);
            String alreadyDelivered = "Pick list already on the warehouse server, counted as delivered orderNumber="
                + warmUp + " folder=inbound";
            assertTrue(log(integration).contains(alreadyDelivered), "T14 upload: precondition, the warm-up " + warmUp
                + " was counted as already delivered by the check (log line '" + alreadyDelivered + "')");

            server.hang(true);
            assertAccepted(postPartnerOrder(integration, partnerOrder(order)), order);
            await("T14 upload: the attempt for " + order + " ended (Delivered, or attempts >= 1 with last_failure) "
                + "while new connections hang", ATTEMPT_DEADLINE, () -> {
                    Map<String, Object> row = pickList(order);
                    if (row == null) {
                        return "no pick_list row";
                    }
                    if (DELIVERED.equals(row.get("delivery_state"))) {
                        return null;
                    }
                    return failedAttempts(order, 1);
                });
            LOG.info("T14 upload: pick list of {} after the hang: {}", order, pickList(order));

            server.hang(false);
            awaitDelivered(order);
            assertEquals(1, server.count(file), "T14 upload: " + file + " of " + order + " on the server after the "
                + "recovery: expected exactly 1, actual " + server.files());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, warmUp, order);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static IntegrationProcess integration(String testCaseId) {
        return new IntegrationProcess(testCaseId)
            .withSftpPort(server.port(), server.knownHostsFile())
            .withProperty("warehouse.sftp.timeout-ms", String.valueOf(TIMEOUT_MS))
            .withProperty("pick-list.poll-delay-ms", String.valueOf(POLL_DELAY_MS));
    }

    /** Null when the pick list of {@code order} is Pending with at least {@code attempts} and a last_failure. */
    private static String failedAttempts(String order, int attempts) {
        Map<String, Object> row = pickList(order);
        if (row == null) {
            return "no pick_list row";
        }
        int actual = ((Number) row.get("attempts")).intValue();
        return PENDING.equals(row.get("delivery_state")) && actual >= attempts && row.get("last_failure") != null
            ? null : "pick list " + row.get("delivery_state") + ", attempts " + actual + " (expected >= " + attempts
                + "), last_failure " + row.get("last_failure");
    }

    private static void awaitDelivered(String order) {
        await("T14: the pick list of " + order + " Delivered once the server answers", FLOW_WAIT, () -> {
            Map<String, Object> row = pickList(order);
            List<Map<String, Object>> receipts = db.receipts(order);
            Object state = receipts.size() == 1 ? receipts.get(0).get("order_state") : null;
            return row != null && DELIVERED.equals(row.get("delivery_state")) && SENT_TO_WAREHOUSE.equals(state) ? null
                : "pick list " + (row == null ? "missing" : row.get("delivery_state") + ", attempts "
                    + row.get("attempts")) + ", order state " + state;
        });
    }

    /** Puts a file on the server as the user (the server must answer). */
    private static void place(String path) throws Exception {
        Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = new JSch();
            jsch.setKnownHosts(server.knownHostsFile().toString());
            session = jsch.getSession(SpecEnvironment.get().sftpUsername(), "127.0.0.1", server.port());
            session.setPassword(SpecEnvironment.get().sftpPassword());
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "password");
            session.setTimeout(SFTP_TIMEOUT_MS);
            session.connect(SFTP_TIMEOUT_MS);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(SFTP_TIMEOUT_MS);
            channel.put(new ByteArrayInputStream("warm-up\r\n".getBytes(StandardCharsets.UTF_8)), path);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    /** The server answers again, the process is always stopped, the orders removed; nothing hides the first failure. */
    private static void cleanUp(Throwable primary, IntegrationProcess integration, String... orders) throws Throwable {
        Throwable failure = primary;
        List<Runnable> steps = new ArrayList<>();
        steps.add(() -> server.hang(false));
        steps.add(integration::stop);
        for (String order : Arrays.asList(orders)) {
            steps.add(() -> db.cleanupNow(order));
        }
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (Throwable t) {
                failure = keep(failure, t);
            }
        }
        if (failure != null && failure != primary) {
            throw failure;
        }
    }

    private static Throwable keep(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static Map<String, Object> pickList(String order) {
        List<Map<String, Object>> rows = db.rows("SELECT p.delivery_state, p.attempts, p.last_failure FROM pick_list p "
            + "JOIN customer_order c ON c.id = p.customer_order_id WHERE c.order_number = ?", order);
        if (rows.size() > 1) {
            throw new AssertionError("T14: pick_list rows of " + order + ": expected at most 1, actual " + rows.size());
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String log(IntegrationProcess integration) throws IOException {
        return Files.readString(integration.logFile(), StandardCharsets.UTF_8);
    }

    private static void assertAccepted(HttpResponse<String> response, String order) throws IOException {
        assertEquals(200, response.statusCode(), "Status of " + order + ": " + response.body());
        JsonNode answer = JSON.readTree(response.body());
        assertEquals(order, answer.path("orderNumber").asText(), "orderNumber of the answer");
        assertEquals("accepted", answer.path("result").asText(), "result of the answer");
    }

    private static String partnerOrder(String order) throws IOException {
        ObjectNode partnerOrder = (ObjectNode) JSON.readTree(docsFile(PARTNER_SAMPLE).toFile());
        partnerOrder.put("orderId", order);
        return JSON.writeValueAsString(partnerOrder);
    }

    /** {@code POST /orders} with the run's API Key (never logged). */
    private static HttpResponse<String> postPartnerOrder(IntegrationProcess integration, String body)
        throws IOException {
        String key = Files.readString(SpecEnvironment.get().partnerApiKeyFile(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + integration.serverPort() + Partner.ORDERS_PATH))
            .timeout(Duration.ofSeconds(12))
            .header("Content-Type", "application/json")
            .header(Partner.API_KEY_HEADER, key)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    @FunctionalInterface
    private interface Condition {
        String missing() throws Exception;
    }

    /** Polls {@code condition} every {@value #POLL_MILLIS} ms until it holds or {@code timeout} has passed. */
    private static void await(String what, Duration timeout, Condition condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String missing;
        while (true) {
            try {
                missing = condition.missing();
            } catch (Exception e) {
                missing = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst()
                    .orElse("");
            }
            if (missing == null) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + ": not within " + timeout + " (" + missing + ")");
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
    }

    private static String pickListFile(String order) {
        return "PL-" + order + ".csv";
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
