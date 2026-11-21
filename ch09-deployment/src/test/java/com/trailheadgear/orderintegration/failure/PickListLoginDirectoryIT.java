package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Partner;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T13 (design 006 §3.14, §3.1.8 W1, §3.17 D16, review decision 1): a Warehouse SFTP Server whose login directory is
 * not {@code /}.
 *
 * <p>{@link TestSftpServer#loginDirectory(String)} with {@code /work}: chrooted, the login directory is {@code /work},
 * an absolute {@code /inbound/picklists} does not exist. The integration runs with the production folder settings
 * (absolute {@code /inbound/picklists}, {@code /inbound/picklists/processed}) and the fault point
 * {@code to-pick-list-delivered:error:1}: the first attempt uploads the Pick List, then recording it fails. The test
 * moves the file to the processed folder, as the Warehouse does, before the next poll. The retry must find it there and
 * count it as delivered: one file on the server, in {@code inbound/picklists/processed} relative to the login
 * directory, no {@code .tmp}. Without D16 the check stats the absolute path, misses, and the retry uploads a second
 * file (W1).
 *
 * <p>The pick-list poll delay is 5 s here (spec-test: 1 s), so the move reliably happens between the failed attempt and
 * the next poll; the test checks that it did.
 */
class PickListLoginDirectoryIT {

    private static final Logger LOG = LoggerFactory.getLogger(PickListLoginDirectoryIT.class);

    private static final String PARTNER_SAMPLE = "messages/examples/partner-order/partner-order-sample.json";
    private static final String ORDER = "TG-1961";
    private static final String FILE = "PL-" + ORDER + ".csv";
    private static final String INBOUND = "inbound/picklists";
    private static final String PROCESSED = "inbound/picklists/processed";
    private static final String FAULT = "to-pick-list-delivered:error:1";
    private static final String UNEXPECTED_KIND = "unexpected error: NoSuchMethodError";
    private static final String POLL_DELAY_MS = "5000";

    private static final String PENDING = "Pending";
    private static final String DELIVERED = "Delivered";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";

    private static final Duration FLOW_WAIT = Duration.ofSeconds(60);
    private static final long POLL_MILLIS = 200L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static OrderDatabase db;
    private static TestSftpServer server;

    @BeforeAll
    static void environment() {
        LOG.info("PickListLoginDirectoryIT: images {}", SpecEnvironment.get().imageReferences());
        db = new OrderDatabase();
        server = TestSftpServer.loginDirectory("/work");
    }

    @AfterAll
    static void close() {
        if (server != null) {
            server.close();
        }
    }

    /** Upload, recording fails, the Warehouse moves the file, the retry finds it: one file, in processed. */
    @Test
    void retryAfterTheWarehouseMovedTheFileFindsItWithALoginDirectoryNotRoot() throws Throwable {
        IntegrationProcess integration = new IntegrationProcess("TC-961")
            .withFaults(FAULT)
            .withSftpPort(server.port(), server.knownHostsFile())
            .withProperty("warehouse.sftp.inbound-directory", "/" + INBOUND)
            .withProperty("warehouse.sftp.processed-directory", "/" + PROCESSED)
            .withProperty("pick-list.poll-delay-ms", POLL_DELAY_MS);
        Throwable primary = null;
        try {
            integration.start();
            // After the start: the integration creates the schema
            db.cleanupNow(ORDER);
            assertAccepted(postPartnerOrder(integration, partnerOrder(ORDER)), ORDER);

            await("T13: " + ORDER + " uploaded to " + INBOUND + " and the attempt recorded as failed (fault " + FAULT
                + ")", FLOW_WAIT, () -> {
                    Map<String, Object> row = pickList(ORDER);
                    if (row == null) {
                        return "no pick_list row";
                    }
                    int attempts = ((Number) row.get("attempts")).intValue();
                    return attempts >= 1 && row.get("last_failure") != null ? null
                        : "pick list " + row.get("delivery_state") + ", attempts " + attempts + ", last_failure "
                            + row.get("last_failure") + ", files " + server.files();
                });
            Map<String, Object> failed = pickList(ORDER);
            assertEquals(PENDING, failed.get("delivery_state"), "T13: state of the pick list of " + ORDER
                + " after the failed recording: expected " + PENDING + ", actual " + failed.get("delivery_state"));
            assertEquals(1, ((Number) failed.get("attempts")).intValue(), "T13: attempts of the pick list of " + ORDER
                + " after the failed recording");
            assertEquals(UNEXPECTED_KIND, failed.get("last_failure"), "T13: last_failure of the pick list of " + ORDER);
            assertEquals(List.of(INBOUND + "/" + FILE), server.files(), "T13: files on the server after the upload of "
                + ORDER + " (relative to the login directory /work)");

            // The Warehouse picks the file up
            server.move(INBOUND + "/" + FILE, PROCESSED + "/" + FILE);
            int attemptsAtMove = ((Number) pickList(ORDER).get("attempts")).intValue();
            assertEquals(1, attemptsAtMove, "T13: precondition, the file of " + ORDER + " was moved before the next "
                + "poll (attempts at the move)");

            await("T13: the pick list of " + ORDER + " Delivered after the retry", FLOW_WAIT, () -> {
                Map<String, Object> row = pickList(ORDER);
                List<Map<String, Object>> receipts = db.receipts(ORDER);
                Object state = receipts.size() == 1 ? receipts.get(0).get("order_state") : null;
                return row != null && DELIVERED.equals(row.get("delivery_state")) && SENT_TO_WAREHOUSE.equals(state)
                    ? null : "pick list " + (row == null ? "missing" : row.get("delivery_state") + ", attempts "
                        + row.get("attempts")) + ", order state " + state + ", files " + server.files();
            });

            List<String> files = server.files();
            LOG.info("T13: files on the server after the retry of {}: {}", ORDER, files);
            assertEquals(1, server.count(FILE), "T13: " + FILE + " of " + ORDER + " on the server: expected exactly 1, "
                + "actual " + server.count(FILE) + " " + files);
            assertEquals(List.of(PROCESSED + "/" + FILE), files, "T13: files on the server for " + ORDER
                + ": expected only " + PROCESSED + "/" + FILE + " (relative to the login directory)");
            assertTrue(files.stream().noneMatch(f -> f.endsWith(".tmp")), "T13: a .tmp file is left for " + ORDER
                + ": " + files);
            String expectedLog = "Pick list already on the warehouse server, counted as delivered orderNumber=" + ORDER
                + " folder=processed";
            assertTrue(log(integration).contains(expectedLog), "T13: log line for " + ORDER + ": expected '"
                + expectedLog + "', not found in " + integration.logFile());
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The process is always stopped and the order removed; a later failure never hides an earlier one. */
    private static void cleanUp(Throwable primary, IntegrationProcess integration) throws Throwable {
        Throwable failure = primary;
        try {
            integration.stop();
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            db.cleanupNow(ORDER);
        } catch (Throwable t) {
            failure = keep(failure, t);
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
            throw new AssertionError("T13: pick_list rows of " + order + ": expected at most 1, actual " + rows.size());
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

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
