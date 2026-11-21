package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trailheadgear.orderintegration.spec.support.FreePorts;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.Mailboxes;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Partner;
import com.trailheadgear.orderintegration.spec.support.Peakmart;
import com.trailheadgear.orderintegration.spec.support.RejectingSmtpServer;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import com.trailheadgear.orderintegration.spec.support.WarehouseInbox;
import org.citrusframework.TestCaseRunner;
import org.citrusframework.annotations.CitrusResource;
import org.citrusframework.junit.jupiter.CitrusSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T11 (design 005 §3.9; §3.1 D1, D2, M1, M2; §3.7 H6): both outboxes keep trying and say why, against the packaged jar
 * (normal start, no fault hook).
 *
 * <ul>
 *   <li><b>Pick list (D2).</b> The inbound folder of the Warehouse SFTP container is made read-only
 *       ({@code chmod a-w} as root through {@link SpecEnvironment#execInSftp}; the SFTP user owns the folder but, not
 *       being root, cannot write into it without the w bit). An accepted partner order's pick list stays Pending with
 *       {@code attempts > 0} and {@code last_failure} set to a kind only (no file name, no path). Once the folder's
 *       original mode is restored (always, in {@code finally}) it is Delivered once: one file, {@code last_failure}
 *       cleared.</li>
 *   <li><b>Mail (M2).</b> The process is started with {@code smtp.host}/{@code smtp.port} pointed at a
 *       {@link RejectingSmtpServer} on 127.0.0.1 that answers {@code 550} at {@code RCPT TO}. The Order Desk
 *       Notification of a rejected Peakmart order stays unsent ({@code sent_at} NULL) with {@code last_failure} holding
 *       {@code 550} and {@code last_attempt_at} set. Once the server accepts, it is sent once: one message,
 *       {@code last_failure} cleared.</li>
 * </ul>
 */
@CitrusSupport
// Design 006 D13: after @CitrusSupport, so a failed check is reported with its message (Checks)
@ExtendWith(Checks.Reported.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeliveryFailureIT {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryFailureIT.class);

    private static final String PEAKMART_INACTIVE_SKU = "messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json";
    private static final String PARTNER_SAMPLE = "messages/examples/partner-order/partner-order-sample.json";

    private static final String PENDING = "Pending";
    private static final String DELIVERED = "Delivered";
    private static final String REJECTED = "Rejected";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";
    /** A kind starts with a class's simple name (or {@code unexpected error}) (design 005 §3.5, §3.7). */
    private static final Pattern KIND_START = Pattern.compile("^[A-Za-z][A-Za-z0-9_$]*");
    /** W2-F4: SFTP status 3 (SSH_FX_PERMISSION_DENIED) at the step that writes {@code <file>.tmp}. */
    private static final String READ_ONLY_FAILURE = "SftpException 3 permission denied (upload)";

    /** Every poller runs every 1 s in spec-test: several failed attempts fit easily. */
    private static final Duration FLOW_WAIT = Duration.ofSeconds(45);
    private static final Duration MAIL_QUIET = Duration.ofSeconds(3);
    private static final long POLL_MILLIS = 500L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Peakmart peakmart;
    private static OrderDatabase db;
    private static WarehouseInbox inbox;

    @CitrusResource
    private TestCaseRunner runner;

    @BeforeAll
    static void environment() {
        SpecEnvironment env = SpecEnvironment.get();
        LOG.info("DeliveryFailureIT: images {}", env.imageReferences());
        peakmart = new Peakmart();
        db = new OrderDatabase();
        inbox = new WarehouseInbox();
    }

    @AfterAll
    static void close() {
        if (peakmart != null) {
            peakmart.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D1, D2 (H6): pick list

    /** Inbound read-only: Pending, attempts, last_failure (a kind); writable again: Delivered once, one file. */
    @Test
    @Order(1)
    void pickListToAReadOnlyFolderSaysWhyThenIsDeliveredOnce() throws Throwable {
        String order = "TG-1941";
        String file = pickListFile(order);
        SpecEnvironment env = SpecEnvironment.get();
        // atmoz/sftp: the folders of users.conf are created under the user's home, owned by the user
        String inboundDir = "/home/" + env.sftpUsername() + "/" + WarehouseInbox.INBOUND;
        IntegrationProcess integration = new IntegrationProcess("TC-941");
        Cleanup cleanup = new Cleanup(integration).orders(order);
        String originalMode = null;
        Throwable primary = null;
        try {
            inbox.remove(file);
            env.execInSftp("test", "-d", inboundDir);
            originalMode = env.execInSftp("stat", "-c", "%a", inboundDir).getStdout().strip();
            assertTrue(originalMode.matches("^[0-7]{3,4}$"), "mode of " + inboundDir + ": " + originalMode);
            integration.start();
            runner.run(db.cleanup(order));

            env.execInSftp("chmod", "a-w", inboundDir);
            HttpResponse<String> answer = postPartnerOrder(integration, partnerOrder(order));
            assertAccepted(answer, order);

            await("D2: the pick list of " + order + " Pending with attempts and last_failure while " + inboundDir
                + " is read-only", FLOW_WAIT, () -> {
                    List<Map<String, Object>> rows = pickListRow(order);
                    if (rows.size() != 1) {
                        return "pick_list rows of " + order + ": " + rows.size();
                    }
                    Map<String, Object> row = rows.get(0);
                    return PENDING.equals(row.get("delivery_state")) && ((Number) row.get("attempts")).intValue() > 0
                        && row.get("last_failure") != null && row.get("last_attempt_at") != null ? null
                        : "pick list " + row.get("delivery_state") + ", attempts " + row.get("attempts")
                            + ", last_failure " + row.get("last_failure");
                });
            String lastFailure = (String) pickListRow(order).get(0).get("last_failure");
            assertTrue(KIND_START.matcher(lastFailure).find(), "D2: pick_list.last_failure of " + order + " starts "
                + "with a kind (a class name or 'unexpected error'): " + lastFailure);
            assertFalse(lastFailure.contains(file) || lastFailure.contains(order) || lastFailure.contains("/")
                || lastFailure.contains("picklists"), "D2: pick_list.last_failure of " + order + " names the file or "
                + "its path: " + lastFailure);
            assertTrue(lastFailure.length() <= 500, "D2: last_failure fits varchar(500)");
            // Design 006 developer check 4 (W2-F4): last_failure names the SFTP status (id and its text) and the step,
            // as the runbook's "fix what last_failure names" needs; the write of <file>.tmp is refused
            Checks.check("D2: pick_list.last_failure while " + inboundDir + " is read-only", READ_ONLY_FAILURE,
                lastFailure, order);
            assertEquals(0, inbox.count(file), "D2: " + file + " reached the read-only folder");
            LOG.info("D2: pick_list.last_failure of {} while read-only: {}", order, lastFailure);

            restoreMode(env, inboundDir, originalMode);
            originalMode = null;
            await("D2: the pick list of " + order + " Delivered once " + inboundDir + " is writable again", FLOW_WAIT,
                () -> {
                    List<Map<String, Object>> rows = pickListRow(order);
                    List<Map<String, Object>> receipts = db.receipts(order);
                    Object state = receipts.size() == 1 ? receipts.get(0).get("order_state") : null;
                    return rows.size() == 1 && DELIVERED.equals(rows.get(0).get("delivery_state"))
                        && SENT_TO_WAREHOUSE.equals(state) ? null
                        : "pick list " + (rows.isEmpty() ? "missing" : rows.get(0).get("delivery_state"))
                            + ", " + receiptSummary(order);
                });
            assertEquals(1, inbox.count(file), "D2: exactly one " + file + " on the Warehouse SFTP Server");
            assertFalse(inbox.tmpExists(file), "D2: " + file + ".tmp is left on the server");
            assertNull(pickListRow(order).get(0).get("last_failure"), "D2: last_failure of " + order
                + " after the delivery");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            Throwable failure = primary;
            if (originalMode != null) {
                try {
                    restoreMode(env, inboundDir, originalMode);
                } catch (Throwable t) {
                    failure = keep(failure, t);
                }
            }
            try {
                cleanup.run(failure);
            } catch (Throwable t) {
                failure = keep(failure, t);
            }
            if (failure != null && failure != primary) {
                throw failure;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // M1, M2 (H6): Order Desk Notification

    /** 550 at RCPT: unsent with last_failure holding 550 and last_attempt_at; accepted again: sent once, one message. */
    @Test
    @Order(2)
    void notificationRefused550SaysWhyThenIsSentOnce() throws Throwable {
        // Stopped whatever happens below, the IntegrationProcess construction included
        try (RejectingSmtpServer smtp = new RejectingSmtpServer().start(FreePorts.next())) {
            refusedThenAccepted(smtp);
        }
    }

    private void refusedThenAccepted(RejectingSmtpServer smtp) throws Throwable {
        String order = "PM-91402";
        String subject = "Subject: " + Mailboxes.orderDeskSubject(order);
        IntegrationProcess integration = new IntegrationProcess("TC-942")
            .withProperty("smtp.host", "127.0.0.1")
            .withProperty("smtp.port", String.valueOf(smtp.port()));
        Cleanup cleanup = new Cleanup(integration).orders(order);
        Throwable primary = null;
        try {
            assertTopicDrained("notification 550");
            smtp.reject550();
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_INACTIVE_SKU, order)));

            await("M2: the notification of " + order + " unsent with last_failure and last_attempt_at after a 550",
                FLOW_WAIT, () -> {
                    List<Map<String, Object>> rows = notificationRow(order);
                    if (rows.size() != 1) {
                        return "notification rows of " + order + ": " + rows.size() + " (" + receiptSummary(order)
                            + ")";
                    }
                    Map<String, Object> row = rows.get(0);
                    return row.get("sent_at") == null && row.get("last_failure") != null
                        && row.get("last_attempt_at") != null && ((Number) row.get("attempts")).intValue() > 0 ? null
                        : "sent_at " + row.get("sent_at") + ", attempts " + row.get("attempts") + ", last_failure "
                            + row.get("last_failure") + ", last_attempt_at " + row.get("last_attempt_at");
                });
            Map<String, Object> refused = notificationRow(order).get(0);
            String lastFailure = (String) refused.get("last_failure");
            assertTrue(lastFailure.contains("550"), "M2: notification.last_failure of " + order + " holds 550: "
                + lastFailure);
            assertFalse(lastFailure.contains("@"), "M2: notification.last_failure of " + order + " names an address");
            assertNull(refused.get("sent_at"), "M2: sent_at of " + order + " after a 550");
            assertTrue(smtp.rejectedRecipients() > 0, "M2: the SMTP server refused no recipient");
            assertEquals(0, smtp.messages().size(), "M2: a message was accepted while refusing");
            assertEquals(REJECTED, db.receiptState(order), "M2: Order State of " + order);
            LOG.info("M2: notification.last_failure of {} after 550: {}", order, lastFailure);

            smtp.accept();
            await("M1: the notification of " + order + " sent once the server accepts", FLOW_WAIT, () -> {
                List<Map<String, Object>> rows = notificationRow(order);
                return rows.size() == 1 && rows.get(0).get("sent_at") != null ? null
                    : "notification of " + order + " not yet marked sent";
            });
            sleep(MAIL_QUIET.toMillis());
            assertEquals(1L, smtp.count(subject), "M1: messages for " + order + " accepted by the SMTP server");
            assertEquals(1, notificationRow(order).size(), "M1: notification rows of " + order);
            assertNull(notificationRow(order).get(0).get("last_failure"), "M1: last_failure of " + order
                + " after it was sent");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            Throwable failure = primary;
            try {
                cleanup.run(failure);
            } catch (Throwable t) {
                failure = keep(failure, t);
            }
            if (failure != null && failure != primary) {
                throw failure;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Queries and assertions

    private static List<Map<String, Object>> pickListRow(String order) {
        return db.rows("SELECT p.delivery_state, p.attempts, p.last_attempt_at, p.last_failure FROM pick_list p "
            + "JOIN customer_order c ON c.id = p.customer_order_id WHERE c.order_number = ?", order);
    }

    private static List<Map<String, Object>> notificationRow(String order) {
        return db.rows("SELECT n.sent_at, n.attempts, n.last_attempt_at, n.last_failure FROM notification n "
            + "JOIN order_receipt r ON r.id = n.order_receipt_id WHERE r.order_number = ?", order);
    }

    private static void restoreMode(SpecEnvironment env, String dir, String mode) {
        env.execInSftp("chmod", mode, dir);
        LOG.info("Write rights of {} restored (mode {})", dir, mode);
    }

    private static void assertAccepted(HttpResponse<String> response, String order) throws IOException {
        assertEquals(200, response.statusCode(), "Status of " + order + ": " + response.body());
        JsonNode answer = JSON.readTree(response.body());
        assertEquals(order, answer.path("orderNumber").asText(), "orderNumber of the answer");
        assertEquals("accepted", answer.path("result").asText(), "result of the answer");
    }

    private static void assertTopicDrained(String testCase) {
        assertEquals(peakmart.endOffsets(), peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP),
            testCase + ": precondition, every record on " + Peakmart.TOPIC + " is committed before the start");
    }

    private static String receiptSummary(String order) {
        List<Map<String, Object>> receipts = db.receipts(order);
        if (receipts.isEmpty()) {
            return "no receipt";
        }
        Map<String, Object> r = receipts.get(0);
        return receipts.size() + " receipt(s), state " + r.get("order_state") + ", waiting_for " + r.get("waiting_for");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cleanup

    private static final class Cleanup {
        private final IntegrationProcess integration;
        private final List<String> orders = new ArrayList<>();
        private final List<String> files = new ArrayList<>();

        Cleanup(IntegrationProcess integration) {
            this.integration = integration;
        }

        Cleanup orders(String... numbers) {
            orders.addAll(Arrays.asList(numbers));
            for (String number : numbers) {
                files.add(pickListFile(number));
            }
            return this;
        }

        /** A later failure never hides an earlier one. */
        void run(Throwable primary) throws Throwable {
            Throwable failure = primary;
            try {
                integration.stop();
            } catch (Throwable t) {
                failure = keep(failure, t);
            }
            try {
                long end = peakmart.endOffsets();
                if (peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP) < end) {
                    LOG.warn("{}: records left uncommitted on {}; draining with a normal start", orders, Peakmart.TOPIC);
                    try {
                        integration.start();
                        await(orders + ": drain " + Peakmart.TOPIC, topicWait(), () -> {
                            long committed = peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP);
                            return committed >= end ? null : "committed " + committed + " of " + end;
                        });
                    } finally {
                        integration.stop();
                    }
                }
            } catch (Throwable t) {
                failure = keep(failure, t);
            }
            for (String order : orders) {
                try {
                    db.cleanupNow(order);
                    assertTrue(db.receipts(order).isEmpty(), order + " is still in the Order Database");
                } catch (Throwable t) {
                    failure = keep(failure, t);
                }
            }
            for (String file : files) {
                try {
                    inbox.remove(file);
                    assertEquals(0, inbox.count(file), file + " is still on the Warehouse SFTP Server");
                } catch (Throwable t) {
                    failure = keep(failure, t);
                }
            }
            if (failure != null && failure != primary) {
                throw failure;
            }
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

    // ---------------------------------------------------------------------------------------------------------------
    // Triggers

    private static Path peakmartEvent(String example, String order) throws IOException {
        ObjectNode event = (ObjectNode) JSON.readTree(docsFile(example).toFile());
        event.put("event_id", UUID.randomUUID().toString());
        event.put("order_ref", order);
        Path dir = SpecEnvironment.get().workDir().resolve("delivery-failure-it");
        Files.createDirectories(dir);
        Path file = dir.resolve(order + ".json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(event),
            StandardCharsets.UTF_8);
        return file;
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

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

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
                missing = rootMessage(e);
            }
            if (missing == null) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + ": not within " + timeout + " (" + missing + ")");
            }
            sleep(POLL_MILLIS);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()).lines().findFirst()
            .orElse("");
    }

    private static Duration topicWait() {
        return Duration.ofMillis(SpecEnvironment.get().millis("spec-test.arrival-timeout-ms")).plusSeconds(60);
    }

    private static String pickListFile(String order) {
        return "PL-" + order + ".csv";
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
