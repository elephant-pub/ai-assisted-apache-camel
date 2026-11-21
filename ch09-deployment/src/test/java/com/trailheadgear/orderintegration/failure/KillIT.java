package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.Mailboxes;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Partner;
import com.trailheadgear.orderintegration.spec.support.Peakmart;
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
 * T8 (design 005 §3.9; §3.1 P8, P9, I8, C6, D4, D7, M4, X1; evidence K1–K6; plan P-13): the process is killed at a step
 * and started again, inside {@code ./mvnw verify}. Replaces the earlier kill-test script, its delay hook and its test
 * main class (design 005 §3.9, fault hook).
 *
 * <p>Each kill point starts the packaged jar with the fault hook ({@code <node>:halt:1}: {@code Runtime.halt(137)}
 * exactly before that node, no timing), triggers the flow, waits for exit code 137, then starts the same instance again
 * without the hook ({@link IntegrationProcess#withoutFaults()}) and checks that no order is lost and no second Pick List
 * is delivered. Every kill point has its own test case id (own run directory and logs) and its own Order Number, and
 * always stops the process and cleans up in {@code finally}.
 *
 * <p>The halted JVM never leaves the Kafka consumer group, so the next consumer is assigned the partition only after
 * the dead member's session has timed out; waits that depend on the Peakmart topic allow for that
 * ({@link #topicWait()}). T8 of design 006 (§3.14) bounds that wait for the Peakmart kill points K1a and K1b: a new
 * event published after the restart is taken in within 15 s of the restarted process being ready (D10).
 */
@CitrusSupport
// Design 006 D13: after @CitrusSupport, so a failed check is reported with its message (Checks)
@ExtendWith(Checks.Reported.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KillIT {

    private static final Logger LOG = LoggerFactory.getLogger(KillIT.class);

    private static final int HALT_EXIT_CODE = 137;
    private static final String HOOK_ACTIVE = "Fault hook active points=";
    private static final String HOOK_FIRES = "Fault hook fires node=";

    private static final String PEAKMART_SAMPLE = "messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json";
    private static final String PEAKMART_INACTIVE_SKU = "messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json";
    private static final String PARTNER_SAMPLE = "inputs/partner-order-sample.json";

    private static final String RECEIVED = "Received";
    private static final String RECORDED = "Recorded";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";
    private static final String REJECTED = "Rejected";
    private static final String PENDING = "Pending";
    private static final String DELIVERED = "Delivered";

    private static final long POLL_MILLIS = 500L;
    /** T8 (design 006 §3.14): the restarted process takes in its first new event within this bound of "ready". */
    private static final Duration INTAKE_RESUME_LIMIT = Duration.ofSeconds(15);
    /** {@code partner.checking-expiry-seconds} (src/main/resources/application.properties). */
    private static final double CHECKING_EXPIRY_SECONDS = 30.0;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Peakmart peakmart;
    private static OrderDatabase db;
    private static WarehouseInbox inbox;

    @CitrusResource
    private TestCaseRunner runner;

    @BeforeAll
    static void environment() {
        SpecEnvironment env = SpecEnvironment.get();
        LOG.info("KillIT: images {}", env.imageReferences());
        peakmart = new Peakmart();
        db = new OrderDatabase();
        inbox = new WarehouseInbox();
    }

    @AfterAll
    static void closePeakmart() {
        if (peakmart != null) {
            peakmart.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // K1 (I8, X1, P-13)

    /** K1a: the receipt is written, the offset not committed; after the restart the event is a Duplicate. */
    @Test
    @Order(1)
    void k1aKilledBeforeOffsetCommit() throws Throwable {
        String order = "PM-91001";
        String file = pickListFile(order);
        String resumeOrder = "PM-91021";
        String node = "commit-kafka-offset";
        IntegrationProcess integration = new IntegrationProcess("TC-901").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            assertTopicDrained("K1a");
            inbox.remove(file);
            inbox.remove(pickListFile(resumeOrder));
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(db.cleanup(resumeOrder));
            long before = peakmart.endOffsets();
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order)));
            long end = before + 1;

            assertHalted(integration, node);
            assertEquals(1, db.receipts(order).size(), "K1a: the receipt of " + order + " was not written before the kill");
            assertTrue(peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP) < end,
                "K1a: the offset of " + order + " was committed before the kill");

            long readyAt = restartWithoutFaults(integration);
            assertIntakeResumes("K1a", readyAt, resumeOrder);
            await("K1a: " + order + " taken in again as a Duplicate", topicWait(), () ->
                log(integration).contains("Duplicate Peakmart order skipped orderNumber=" + order) ? null
                    : "no 'Duplicate Peakmart order skipped orderNumber=" + order + "' in " + integration.logFile());
            awaitDeliveredOnce(order);
            assertRecordedOnce(order, "Peakmart");
            assertEquals(1, inbox.count(file), "K1a: exactly one " + file + " on the Warehouse SFTP Server");
            await("K1a: the offset committed after the restart", topicWait(), () -> {
                long committed = peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP);
                return committed >= end ? null : "committed " + committed + " of " + end;
            });
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file, resumeOrder);
        }
    }

    /** K1b (P-13): killed before the receipt insert, nothing is written; after the restart the order is recorded once. */
    @Test
    @Order(2)
    void k1bKilledBeforeReceiptInsert() throws Throwable {
        String order = "PM-91011";
        String file = pickListFile(order);
        String resumeOrder = "PM-91031";
        String node = "to-receipt-insert";
        IntegrationProcess integration = new IntegrationProcess("TC-902").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            assertTopicDrained("K1b");
            inbox.remove(file);
            inbox.remove(pickListFile(resumeOrder));
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(db.cleanup(resumeOrder));
            long before = peakmart.endOffsets();
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order)));

            assertHalted(integration, node);
            assertTrue(db.receipts(order).isEmpty(), "K1b: a receipt of " + order + " was written before the kill");
            LOG.info("K1b: at the halt {} committed {} of {} records", Peakmart.INTEGRATION_GROUP,
                peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP), before + 1);

            long readyAt = restartWithoutFaults(integration);
            assertIntakeResumes("K1b", readyAt, resumeOrder);
            await("K1b: " + order + " recorded after the restart (not lost)", topicWait(), () -> {
                List<Map<String, Object>> receipts = db.receipts(order);
                return receipts.isEmpty() ? order + " has no receipt: the order is lost" : null;
            });
            awaitDeliveredOnce(order);
            assertRecordedOnce(order, "Peakmart");
            assertEquals(1, inbox.count(file), "K1b: exactly one " + file + " on the Warehouse SFTP Server");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file, resumeOrder);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // K2 (D4, D7)

    /** K2: uploaded, Delivered not recorded; the Warehouse moves the file to processed; no second file after restart. */
    @Test
    @Order(3)
    void k2KilledAfterUploadBeforeDelivered() throws Throwable {
        String order = "PM-91002";
        String file = pickListFile(order);
        String node = "to-pick-list-delivered";
        IntegrationProcess integration = new IntegrationProcess("TC-903").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            assertTopicDrained("K2");
            inbox.remove(file);
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order)));

            assertHalted(integration, node);
            assertEquals(PENDING, db.pickList(order).map(p -> p.get("delivery_state")).orElse(null),
                "K2: the Pick List of " + order + " before the restart");
            assertEquals(1, inbox.count(file), "K2: " + file + " was not uploaded before the kill");
            assertFalse(inbox.inProcessed(file), "K2: " + file + " is already in processed");
            // The Warehouse picks the file up
            inbox.moveToProcessed(file);
            assertTrue(inbox.inProcessed(file), "K2: " + file + " was not moved to processed");

            restartWithoutFaults(integration);
            awaitDeliveredOnce(order);
            assertEquals(1, inbox.count(file),
                "K2: exactly one " + file + " across inbound and processed (no second file)");
            assertTrue(inbox.inProcessed(file), "K2: " + file + " is no longer in processed");
            assertFalse(inbox.tmpExists(file), "K2: " + file + ".tmp is left on the server");
            assertTrue(log(integration).contains("Pick list already on the warehouse server, counted as delivered "
                + "orderNumber=" + order + " folder=processed"), "K2: 'already on the warehouse server' not logged");
            assertRecordedOnce(order, "Peakmart");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // K3 (M4)

    /** K3: the mail server accepted the email, the row is not marked sent; after the restart it is (OQ-081). */
    @Test
    @Order(4)
    void k3KilledAfterMailBeforeMarkedSent() throws Throwable {
        String order = "PM-91003";
        String file = pickListFile(order);
        String node = "log-order-desk-notified";
        IntegrationProcess integration = new IntegrationProcess("TC-904").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            assertTopicDrained("K3");
            inbox.remove(file);
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_INACTIVE_SKU, order)));

            assertHalted(integration, node);
            assertEquals(REJECTED, db.receiptState(order), "K3: Order State of " + order + " before the restart");
            assertEquals(Optional.empty(), db.notificationSentAt(order),
                "K3: the notification of " + order + " is marked sent before the restart");

            restartWithoutFaults(integration);
            await("K3: the notification of " + order + " marked sent", arrivalWait(), () ->
                db.notificationSentAt(order).isPresent() ? null : "not yet marked sent");
            // At least one email; a second one is accepted (OQ-081)
            SpecEnvironment env = SpecEnvironment.get();
            runner.run(env.mailboxes().receiveOne(Mailboxes.ORDER_DESK, Mailboxes.orderDeskSubject(order),
                env.millis("spec-test.arrival-timeout-ms")));
            assertEquals(1, db.notifications(order).size(), "K3: notification rows of " + order);
            assertTrue(db.pickList(order).isEmpty(), "K3: a pick_list row exists for the Rejected " + order);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // K4 (P9)

    /** K4: recorded, not answered; the resend is answered 200 accepted from the receipt; one Accepted Order. */
    @Test
    @Order(5)
    void k4KilledAfterRecordingBeforeAnswer() throws Throwable {
        String order = "TG-1901";
        String file = pickListFile(order);
        String node = "log-partner-order-recorded";
        IntegrationProcess integration = new IntegrationProcess("TC-905").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            inbox.remove(file);
            integration.start();
            runner.run(db.cleanup(order));
            String body = partnerOrder(order);

            assertConnectionEnds(integration, body, order);
            assertHalted(integration, node);
            assertEquals(RECORDED, db.receiptState(order), "K4: Order State of " + order + " before the restart");

            restartWithoutFaults(integration);
            HttpResponse<String> resend = postPartnerOrder(integration, body);
            assertAccepted(resend, order);
            String fromReceipt = "Partner order resend answered as before: accepted orderNumber=" + order;
            await("K4: the resend of " + order + " answered from its receipt", Duration.ofSeconds(10),
                () -> log(integration).contains(fromReceipt) ? null : "no line '" + fromReceipt + "'");
            assertRecordedOnce(order, "Partner");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // K5 (P8)

    /** K5: claimed, not checked; a resend within 30 s gets the 503 contract answer, after the expiry 200 once. */
    @Test
    @Order(6)
    void k5KilledAfterClaimBeforeCheck() throws Throwable {
        String order = "TG-1902";
        String file = pickListFile(order);
        String node = "to-partner-order-check";
        IntegrationProcess integration = new IntegrationProcess("TC-906").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            inbox.remove(file);
            integration.start();
            runner.run(db.cleanup(order));
            String body = partnerOrder(order);

            assertConnectionEnds(integration, body, order);
            assertHalted(integration, node);
            assertEquals(RECEIVED, db.receiptState(order), "K5: Order State of " + order + " before the restart");

            restartWithoutFaults(integration);
            double age = claimAgeSeconds(order);
            assertTrue(age < CHECKING_EXPIRY_SECONDS - 2,
                "K5: the restart took too long; the claim of " + order + " is " + age + " s old, so the resend "
                    + "within 30 s cannot be made");
            HttpResponse<String> early = postPartnerOrder(integration, body);
            assertEquals(503, early.statusCode(), "K5: status of the resend " + age + " s after the claim: "
                + early.body());
            assertTrue(early.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
                "K5: content type of the 503 answer");
            JsonNode problem = JSON.readTree(early.body());
            assertEquals("temporarily-unavailable", problem.path("result").asText(), "K5: result of the 503 answer");
            assertEquals(order, problem.path("orderNumber").asText(), "K5: orderNumber of the 503 answer");
            assertEquals(503, problem.path("status").asInt(), "K5: status member of the 503 answer");
            assertEquals(1, db.receipts(order).size(), "K5: receipts of " + order + " after the 503 answer");

            // Wait for the 30 s expiry of the claim (measured by the Order Database clock, as the claim is)
            await("K5: the claim of " + order + " expired", Duration.ofSeconds(60), () -> {
                double current = claimAgeSeconds(order);
                return current > CHECKING_EXPIRY_SECONDS + 1 ? null : "the claim is " + current + " s old";
            });
            HttpResponse<String> late = postPartnerOrder(integration, body);
            assertAccepted(late, order);
            assertRecordedOnce(order, "Partner");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // K6 (C6)

    /** K6: killed in the middle of a Peakmart check; checked again after the restart, recorded once, one Pick List. */
    @Test
    @Order(7)
    void k6KilledDuringCheck() throws Throwable {
        String order = "PM-91006";
        String file = pickListFile(order);
        String node = "to-product-lookup";
        IntegrationProcess integration = new IntegrationProcess("TC-907").withFaults(node + ":halt:1");
        Throwable primary = null;
        try {
            assertTopicDrained("K6");
            inbox.remove(file);
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order)));

            assertHalted(integration, node);
            assertEquals(RECEIVED, db.receiptState(order), "K6: Order State of " + order + " before the restart");
            assertTrue(db.customerOrder(order).isEmpty(), "K6: " + order + " was recorded before the restart");

            SpecEnvironment.get().catalog().clearRequests();
            restartWithoutFaults(integration);
            awaitDeliveredOnce(order);
            assertTrue(SpecEnvironment.get().catalog().requestedSkus().contains("TENT-2P"),
                "K6: " + order + " was not checked again after the restart (no Product Catalog lookup)");
            assertRecordedOnce(order, "Peakmart");
            assertEquals(1, inbox.count(file), "K6: exactly one " + file + " on the Warehouse SFTP Server");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanUp(primary, integration, order, file);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Kill and restart

    /** Exit code 137 (the halt), and the first log shows the hook active with this point and firing at this node. */
    private void assertHalted(IntegrationProcess integration, String node) throws IOException {
        Optional<Integer> exit = integration.waitForExit(topicWait());
        assertTrue(exit.isPresent(), "The integration did not exit at " + node + " within " + topicWait()
            + " (log " + integration.logFile() + ")");
        assertEquals(HALT_EXIT_CODE, exit.get(), "Exit code of the halt at " + node);
        String log = log(integration);
        assertTrue(log.contains(HOOK_ACTIVE + node + ":halt:1"), "The fault hook was not active in "
            + integration.logFile());
        assertTrue(log.contains(HOOK_FIRES + node + " kind=halt"), "The fault hook did not fire at " + node);
        assertFalse(integration.lastStopGraceful(), "A halt is never a graceful stop");
        LOG.info("{}: halted at {} with exit code {}", integration.logFile().getFileName(), node, exit.get());
    }

    /**
     * A normal start of the same instance (same run directory, port and settings), without the fault hook. Returns
     * {@link System#nanoTime()} when the restarted process was ready ({@link IntegrationProcess#start()} returned).
     */
    private static long restartWithoutFaults(IntegrationProcess integration) throws IOException {
        // stop() of the halted process checks its whole log for secrets (the key arrives with the first request)
        integration.stop();
        integration.withoutFaults().start();
        long readyAt = System.nanoTime();
        assertFalse(log(integration).contains(HOOK_ACTIVE), "The fault hook is active after the normal start ("
            + integration.logFile() + ")");
        return readyAt;
    }

    /**
     * T8 (design 006 §3.14, §3.1.5 K4, D10): one new event, published right after the restart, has its receipt written
     * within {@link #INTAKE_RESUME_LIMIT} of the restarted process being ready (measured from "ready", not from the
     * kill). Without static membership the new consumer first waits for the halted member's 45 s session.
     */
    private void assertIntakeResumes(String killPoint, long readyAt, String order) throws IOException {
        runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order)));
        await(killPoint + ": " + order + ", published after the restart, taken in", topicWait(), () ->
            db.receipts(order).isEmpty() ? "no receipt of " + order : null);
        double seconds = (System.nanoTime() - readyAt) / 1e9;
        String taken = String.format(Locale.ROOT, "%s: first event after restart taken in after %.1f s", killPoint,
            seconds);
        LOG.info(taken);
        assertTrue(seconds <= INTAKE_RESUME_LIMIT.toSeconds(),
            taken + ", expected ≤ " + INTAKE_RESUME_LIMIT.toSeconds() + " s");
    }

    /**
     * Precondition of every kill point: the integration's consumer group has committed every record on the topic, so
     * the halt can only land on this kill point's own record (a record left by an earlier, failed kill point would be
     * consumed first).
     */
    private static void assertTopicDrained(String killPoint) {
        long end = peakmart.endOffsets();
        long committed = peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP);
        assertEquals(end, committed, killPoint + ": precondition, every record on " + Peakmart.TOPIC
            + " is committed before the start");
    }

    /**
     * Stops the process, drains the topic when a record is left uncommitted (a normal start until every record is
     * committed), then removes the order and its Pick List; a later failure never hides an earlier one.
     */
    private static void cleanUp(Throwable primary, IntegrationProcess integration, String order, String file,
        String... moreOrders) throws Throwable {
        Throwable failure = primary;
        try {
            integration.stop();
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            long end = peakmart.endOffsets();
            if (peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP) < end) {
                LOG.warn("{}: records left uncommitted on {}; draining with a normal start", order, Peakmart.TOPIC);
                try {
                    integration.withoutFaults().start();
                    await(order + ": drain " + Peakmart.TOPIC, topicWait(), () -> {
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
        try {
            db.cleanupNow(order);
            assertTrue(db.receipts(order).isEmpty(), order + " is still in the Order Database");
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            inbox.remove(file);
            assertEquals(0, inbox.count(file), file + " is still on the Warehouse SFTP Server");
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        for (String more : moreOrders) {
            try {
                db.cleanupNow(more);
                assertTrue(db.receipts(more).isEmpty(), more + " is still in the Order Database");
            } catch (Throwable t) {
                failure = keep(failure, t);
            }
            try {
                inbox.remove(pickListFile(more));
                assertEquals(0, inbox.count(pickListFile(more)), pickListFile(more)
                    + " is still on the Warehouse SFTP Server");
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

    // ---------------------------------------------------------------------------------------------------------------
    // Assertions

    /** The Pick List of {@code order} is Delivered and the order Sent to Warehouse. */
    private static void awaitDeliveredOnce(String order) {
        await(order + " delivered to the warehouse", topicWait(), () -> {
            List<Map<String, Object>> receipts = db.receipts(order);
            Object pickList = db.pickList(order).map(p -> p.get("delivery_state")).orElse(null);
            String state = receipts.size() == 1 ? (String) receipts.get(0).get("order_state") : null;
            return DELIVERED.equals(pickList) && SENT_TO_WAREHOUSE.equals(state) ? null
                : "receipts " + receipts.size() + ", state " + state + ", pick list " + pickList;
        });
    }

    /** One receipt, one customer_order of the Channel; an Accepted Order. */
    private static void assertRecordedOnce(String order, String channel) {
        List<Map<String, Object>> receipts = db.receipts(order);
        assertEquals(1, receipts.size(), "order_receipt rows of " + order);
        assertEquals(channel, receipts.get(0).get("channel"), "Channel of " + order);
        String state = (String) receipts.get(0).get("order_state");
        assertTrue(RECORDED.equals(state) || SENT_TO_WAREHOUSE.equals(state),
            order + " is not an Accepted Order: " + state);
        assertTrue(db.customerOrder(order).isPresent(), "No customer_order " + order);
        assertEquals(1L, ((Number) db.rows("SELECT count(*) AS n FROM customer_order WHERE order_number = ?", order)
            .get(0).get("n")).longValue(), "customer_order rows of " + order);
    }

    /** The partner request ends without an answer: the process halts while it handles it. */
    private static void assertConnectionEnds(IntegrationProcess integration, String body, String order) {
        try {
            HttpResponse<String> response = postPartnerOrder(integration, body);
            fail("The partner got an answer for " + order + " instead of a dropped connection: "
                + response.statusCode() + " " + response.body());
        } catch (IOException e) {
            LOG.info("Partner connection for {} ended: {}", order, e.toString());
        }
    }

    private static void assertAccepted(HttpResponse<String> response, String order) throws IOException {
        assertEquals(200, response.statusCode(), "Status of the resend of " + order + ": " + response.body());
        JsonNode answer = JSON.readTree(response.body());
        assertEquals(order, answer.path("orderNumber").asText(), "orderNumber of the answer");
        assertEquals("accepted", answer.path("result").asText(), "result of the answer");
        assertNull(answer.get("rejectionReasons"), "rejectionReasons in an accepted answer");
    }

    /** Age of the partner receipt's being-checked mark, by the Order Database clock. */
    private static double claimAgeSeconds(String order) {
        List<Map<String, Object>> rows = db.rows("SELECT EXTRACT(EPOCH FROM (now() - checking_since)) AS age "
            + "FROM order_receipt WHERE order_number = ? AND channel = 'Partner'", order);
        assertEquals(1, rows.size(), "Partner receipts of " + order);
        Object age = rows.get(0).get("age");
        assertTrue(age instanceof Number, "The receipt of " + order + " has no being-checked mark");
        return ((Number) age).doubleValue();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Triggers

    /** A Peakmart Order Event from a docs example, with its own Order Number and a new event id, as a file. */
    private static Path peakmartEvent(String example, String order) throws IOException {
        ObjectNode event = (ObjectNode) JSON.readTree(docsFile(example).toFile());
        event.put("event_id", UUID.randomUUID().toString());
        event.put("order_ref", order);
        Path dir = SpecEnvironment.get().workDir().resolve("kill-it");
        Files.createDirectories(dir);
        Path file = dir.resolve(order + ".json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(event),
            StandardCharsets.UTF_8);
        return file;
    }

    /** The sample Partner Order with its own Order Number. */
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
            .timeout(arrivalWait())
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
        /** Null when met; otherwise what is still missing. */
        String missing() throws Exception;
    }

    /** Polls {@code condition} until it is met or {@code timeout} has passed. */
    private static void await(String what, Duration timeout, Condition condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String missing;
        while (true) {
            try {
                missing = condition.missing();
            } catch (Exception e) {
                missing = e.toString();
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
                throw new AssertionError(what + ": interrupted", e);
            }
        }
    }

    private static Duration arrivalWait() {
        return Duration.ofMillis(SpecEnvironment.get().millis("spec-test.arrival-timeout-ms"));
    }

    /** Arrival bound plus the Kafka session timeout of a halted consumer (45 s by default). */
    private static Duration topicWait() {
        return arrivalWait().plusSeconds(60);
    }

    private static String log(IntegrationProcess integration) throws IOException {
        Path file = integration.logFile();
        return file != null && Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
    }

    private static String pickListFile(String order) {
        return "PL-" + order + ".csv";
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
