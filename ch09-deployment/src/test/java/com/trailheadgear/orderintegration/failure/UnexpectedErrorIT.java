package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trailheadgear.orderintegration.fault.FaultHook;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.Mailboxes;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Partner;
import com.trailheadgear.orderintegration.spec.support.Peakmart;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import com.trailheadgear.orderintegration.spec.support.WarehouseInbox;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T7 (design 005 §3.9; §3.1 P7, I6, I7, C5, D6, M3, X2, X6; §3.2–§3.8; runs A, B, BP, B2): an {@code Error} thrown by
 * a step, injected with the fault hook ({@code <node>:error:<n>} throws {@code NoSuchMethodError} before that node,
 * {@code <node>:oom:<n>} an {@code OutOfMemoryError}), against the packaged jar.
 *
 * <p><b>Process split.</b> One process per concern, each with only the fault points its case needs, because the six
 * nodes are shared: every Peakmart event passes {@code to-validate-order-placed}, every check (partner and Peakmart)
 * passes {@code to-validate-product}. One process with all six points would set aside every Peakmart event and fault
 * the first Peakmart check instead of the partner order. The counts fit the case:
 * <ul>
 *   <li>partner (TC-911): {@code to-validate-product:error:1,to-validate-partner-completed-order:error:1}, two partner
 *       orders, the first faults in a catalog-lookup thread, the second in the request thread;</li>
 *   <li>intake (TC-912): {@code to-validate-order-placed:error:3}, every attempt of the first event (1 + the 2
 *       redeliveries of {@code on-intake-cannot-take-in}, {@code maximumRedeliveries: 2}), so the next event passes the node;</li>
 *   <li>check (TC-913), pick list (TC-914), notification (TC-915): {@code <node>:error:2} each;</li>
 *   <li>fatal (TC-916): {@code to-validate-order-placed:oom:1}, then a normal start;</li>
 *   <li>validation of 005, decision 2 and Q-1 (TC-917): {@code to-validate-partner-order:producer-error:1}, the
 *       {@code Error} thrown inside the json-validator producer (its {@code uriSchemaLoader}), not by the hook;</li>
 *   <li>Q-2 intake (TC-918): {@code set-json-object:error:3}, inside {@code try-read-as-json};</li>
 *   <li>Q-2 check (TC-919): {@code to-validate-product:error:2} in a Peakmart check, inside
 *       {@code try-validate-product}.</li>
 * </ul>
 *
 * <p>Each case checks the end state first (answer time, the next event, recorded, Delivered, sent) and then what it
 * saw on the way (the new columns {@code waiting_reason}, {@code last_failure}); transient states are sampled every
 * {@value #FAST_POLL_MILLIS} ms while the end state is awaited. Every wait is bounded; the process is always stopped
 * and the rows and files removed in {@code finally}.
 */
@CitrusSupport
// Design 006 D13: after @CitrusSupport, so a failed check is reported with its message (Checks)
@ExtendWith(Checks.Reported.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnexpectedErrorIT {

    private static final Logger LOG = LoggerFactory.getLogger(UnexpectedErrorIT.class);

    private static final String HOOK_FIRES = "Fault hook fires node=";
    private static final String UNEXPECTED_ERROR = "Unexpected error routeId=";
    private static final String FATAL_ERROR = "Fatal error routeId=";
    private static final String ERROR_CLASS = "NoSuchMethodError";
    /** {@code failureKinds.describe(UnexpectedErrorException)} (design 005 §3.5–§3.7). */
    private static final String UNEXPECTED_KIND = "unexpected error: NoSuchMethodError";
    private static final String CANNOT_BE_TAKEN_IN = "cannot be taken in: ";

    private static final String PEAKMART_SAMPLE = "messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json";
    private static final String PEAKMART_INACTIVE_SKU = "messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json";
    private static final String PARTNER_SAMPLE = "inputs/partner-order-sample.json";
    private static final String ORDER_ANSWER_SCHEMA = "messages/order-answer.schema.json";
    private static final String ANSWER_VALID_EXAMPLE = "messages/examples/order-answer/temporarily-unavailable.json";
    private static final String ANSWER_INVALID_EXAMPLE = "messages/examples/order-answer/invalid/result-unknown.json";
    /** The Order Answer members of the contract's OrderProblem (Problem Details plus these, partner-orders.openapi.yaml). */
    private static final List<String> ORDER_ANSWER_MEMBERS = List.of("orderNumber", "result", "rejectionReasons");

    private static final String RECEIVED = "Received";
    private static final String RECORDED = "Recorded";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";
    private static final String REJECTED = "Rejected";
    private static final String PENDING = "Pending";
    private static final String DELIVERED = "Delivered";
    private static final String CHECK = "Check";

    /** NFR-002: every partner request is answered within 10 s. */
    private static final Duration PARTNER_ANSWER_LIMIT = Duration.ofSeconds(10);
    /** The client gives up a little after the limit, so a red run does not wait for Spring's 30 s answer. */
    private static final Duration PARTNER_CLIENT_TIMEOUT = Duration.ofSeconds(12);
    /** Design 005 §3.9 decision 1: the process stops within 5 s of the VirtualMachineError. */
    private static final Duration FATAL_EXIT_LIMIT = Duration.ofSeconds(5);
    /** Pollers run every 1 s in spec-test; two faulted attempts and the success fit easily. */
    private static final Duration FLOW_WAIT = Duration.ofSeconds(45);
    private static final Duration MAIL_QUIET = Duration.ofSeconds(3);
    private static final long POLL_MILLIS = 500L;
    private static final long FAST_POLL_MILLIS = 100L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Peakmart peakmart;
    private static OrderDatabase db;
    private static WarehouseInbox inbox;
    private static CamelContext validation;
    private static ProducerTemplate validator;

    @CitrusResource
    private TestCaseRunner runner;

    @BeforeAll
    static void environment() throws Exception {
        SpecEnvironment env = SpecEnvironment.get();
        LOG.info("UnexpectedErrorIT: images {}", env.imageReferences());
        peakmart = new Peakmart();
        db = new OrderDatabase();
        inbox = new WarehouseInbox();
        validation = new DefaultCamelContext();
        validation.start();
        validator = validation.createProducerTemplate();
        // Self-check of the Order Answer validation: the contract's example passes, an invalid one does not
        assertNull(orderAnswerViolation(Files.readString(docsFile(ANSWER_VALID_EXAMPLE), StandardCharsets.UTF_8)),
            "The validation of " + ORDER_ANSWER_SCHEMA + " refuses its own example " + ANSWER_VALID_EXAMPLE);
        assertNotNull(orderAnswerViolation(Files.readString(docsFile(ANSWER_INVALID_EXAMPLE), StandardCharsets.UTF_8)),
            "The validation of " + ORDER_ANSWER_SCHEMA + " accepts " + ANSWER_INVALID_EXAMPLE);
    }

    @AfterAll
    static void close() {
        if (validation != null) {
            validation.stop();
        }
        if (peakmart != null) {
            peakmart.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // P7 (H1 + H2): the partner path

    /** Both partner faults: each order gets the 503 contract answer in under 10 s; ERROR logged; graceful stop. */
    @Test
    @Order(1)
    void partnerOrdersAreAnsweredTemporarilyUnavailable() throws Throwable {
        String lookupOrder = "TG-1911";
        String requestOrder = "TG-1912";
        IntegrationProcess integration = new IntegrationProcess("TC-911")
            .withFaults("to-validate-product:error:1,to-validate-partner-completed-order:error:1");
        Cleanup cleanup = new Cleanup(integration).orders(lookupOrder, requestOrder);
        Throwable primary = null;
        try {
            assertTopicDrained("partner");
            integration.start();
            runner.run(db.cleanup(lookupOrder));
            runner.run(db.cleanup(requestOrder));

            // First order: the Error is thrown in a catalog-lookup thread (to-validate-product, product-lookup)
            Answer lookup = postPartnerOrder(integration, partnerOrder(lookupOrder));
            // Second order: the lookups pass (count used up), the Error is thrown in the request thread
            Answer request = postPartnerOrder(integration, partnerOrder(requestOrder));
            assertAll("P7: partner answers under an Error",
                () -> assertContractAnswer503(lookup, lookupOrder, "to-validate-product"),
                () -> assertContractAnswer503(request, requestOrder, "to-validate-partner-completed-order"));

            assertAll("P7: what is logged and stored",
                () -> assertUnexpectedErrorLogged(integration, "to-validate-product"),
                () -> assertUnexpectedErrorLogged(integration, "to-validate-partner-completed-order"),
                () -> assertEquals(RECEIVED, db.receiptState(lookupOrder), "Order State of " + lookupOrder),
                () -> assertEquals(RECEIVED, db.receiptState(requestOrder), "Order State of " + requestOrder));
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // I6, I7 (H1 + H4): intake

    /** An Error on every attempt: the event is set aside as "cannot be taken in", and the next event is recorded. */
    @Test
    @Order(2)
    void intakeErrorSetsTheEventAsideAndTheNextEventGoesOn() throws Throwable {
        String setAsideOrder = "PM-91101";
        String nextOrder = "PM-91102";
        String node = "to-validate-order-placed";
        // 1 attempt + 2 redeliveries (on-intake-cannot-take-in): every attempt of the first event faults, the next one passes
        IntegrationProcess integration = new IntegrationProcess("TC-912").withFaults(node + ":error:3");
        Cleanup cleanup = new Cleanup(integration).orders(setAsideOrder, nextOrder);
        Throwable primary = null;
        try {
            assertTopicDrained("intake");
            cleanup.files(pickListFile(setAsideOrder), pickListFile(nextOrder));
            integration.start();
            runner.run(db.cleanup(setAsideOrder));
            runner.run(db.cleanup(nextOrder));
            String setAsideEvent = UUID.randomUUID().toString();
            cleanup.setAside(setAsideEvent);
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, setAsideOrder, setAsideEvent)));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, nextOrder, UUID.randomUUID().toString())));

            // Behaviour first (runs B, BP: the consumer thread blocks, no later record is taken in)
            await("I6: the next event " + nextOrder + " recorded after the Error", FLOW_WAIT,
                () -> recorded(nextOrder) ? null : nextOrder + " is not recorded (" + receiptSummary(nextOrder)
                    + "); the topic is not read on after " + setAsideOrder);
            assertRecordedOnce(nextOrder, "Peakmart");

            Optional<Map<String, Object>> setAside = db.setAsideEvent(setAsideEvent);
            assertTrue(setAside.isPresent(), "I7: no set_aside_order_event for " + setAsideOrder);
            String reason = (String) setAside.get().get("cannot_read_reason");
            assertNotNull(reason, "I7: cannot_read_reason of " + setAsideOrder);
            assertTrue(reason.startsWith(CANNOT_BE_TAKEN_IN), "I7: cannot_read_reason starts '" + CANNOT_BE_TAKEN_IN
                + "': " + reason);
            assertTrue(reason.contains(UNEXPECTED_KIND), "I7: cannot_read_reason names '" + UNEXPECTED_KIND + "': "
                + reason);
            assertEquals(setAsideOrder, setAside.get().get("order_number"), "I7: order_number of the set-aside event");
            assertTrue(db.receipts(setAsideOrder).isEmpty(), "I7: " + setAsideOrder + " has a receipt although it was "
                + "set aside (an attempt passed " + node + ": more attempts than 1 + 2 redeliveries?)");
            assertEquals(3, count(log(integration), HOOK_FIRES + node + " kind=error"),
                "I7: attempts of " + setAsideOrder + " at " + node + " (1 + 2 redeliveries)");
            assertTrue(log(integration).contains("Order event set aside eventId=" + setAsideEvent),
                "I7: 'Order event set aside' not logged for " + setAsideEvent);
            assertUnexpectedErrorLogged(integration, node);
            assertOffsetsCommitted("I7");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // C5 (H1 + H5): check

    /** An Error twice while recording: the order waits for Check with its reason, then it is recorded once. */
    @Test
    @Order(3)
    void checkErrorLeavesTheOrderWaitingThenItIsRecorded() throws Throwable {
        String order = "PM-91103";
        String node = "to-validate-order";
        IntegrationProcess integration = new IntegrationProcess("TC-913").withFaults(node + ":error:2");
        Cleanup cleanup = new Cleanup(integration).orders(order).files(pickListFile(order));
        Throwable primary = null;
        try {
            assertTopicDrained("check");
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order, UUID.randomUUID().toString())));

            Watch watch = watch(FLOW_WAIT,
                () -> recorded(order) ? null : order + " not recorded (" + receiptSummary(order) + ")",
                () -> {
                    List<Map<String, Object>> rows = db.rows("SELECT order_state, waiting_for, waiting_reason "
                        + "FROM order_receipt WHERE order_number = ? AND channel = 'Peakmart'", order);
                    if (rows.size() != 1) {
                        return null;
                    }
                    Map<String, Object> row = rows.get(0);
                    return RECEIVED.equals(row.get("order_state")) && CHECK.equals(row.get("waiting_for"))
                        ? (String) row.get("waiting_reason") : null;
                });
            // Behaviour first (run B2: the check poller stops, the order is never recorded)
            assertNull(watch.endMissing, "C5: " + order + " recorded after two Errors: not within " + FLOW_WAIT + " ("
                + watch.endMissing + ")");
            assertRecordedOnce(order, "Peakmart");
            assertUnexpectedErrorLogged(integration, node);
            assertEquals(UNEXPECTED_KIND, watch.observed, "C5: waiting_reason of " + order + " while it waited for "
                + "Check (last query problem: " + watch.probeProblem + ")");
            List<Map<String, Object>> after = db.rows("SELECT waiting_for, waiting_reason FROM order_receipt "
                + "WHERE order_number = ?", order);
            assertNull(after.get(0).get("waiting_reason"), "C5: waiting_reason of " + order + " after recording");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D6 (H1 + H6): pick list

    /** An Error twice while building the CSV: the pick list stays Pending with last_failure, then Delivered once. */
    @Test
    @Order(4)
    void pickListErrorIsRetriedThenDeliveredOnce() throws Throwable {
        String order = "PM-91104";
        String file = pickListFile(order);
        String node = "marshal-pick-list-csv";
        IntegrationProcess integration = new IntegrationProcess("TC-914").withFaults(node + ":error:2");
        Cleanup cleanup = new Cleanup(integration).orders(order).files(file);
        Throwable primary = null;
        try {
            assertTopicDrained("pick list");
            inbox.remove(file);
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order, UUID.randomUUID().toString())));

            Watch watch = watch(FLOW_WAIT,
                () -> {
                    Object state = db.pickList(order).map(p -> p.get("delivery_state")).orElse(null);
                    return DELIVERED.equals(state) ? null : "pick list of " + order + " is "
                        + db.pickList(order).map(p -> p.get("delivery_state") + ", attempts " + p.get("attempts"))
                            .orElse("missing") + "; " + receiptSummary(order);
                },
                () -> {
                    List<Map<String, Object>> rows = db.rows("SELECT p.delivery_state, p.last_failure FROM pick_list p "
                        + "JOIN customer_order c ON c.id = p.customer_order_id WHERE c.order_number = ?", order);
                    return rows.size() == 1 && PENDING.equals(rows.get(0).get("delivery_state"))
                        ? (String) rows.get(0).get("last_failure") : null;
                });
            // Behaviour first (D6, mechanism of run B2: the pick-list poller stops)
            assertNull(watch.endMissing, "D6: pick list of " + order + " Delivered after two Errors: not within "
                + FLOW_WAIT + " (" + watch.endMissing + ")");
            assertEquals(1, inbox.count(file), "D6: exactly one " + file + " on the Warehouse SFTP Server");
            assertRecordedOnce(order, "Peakmart");
            assertUnexpectedErrorLogged(integration, node);
            assertEquals(UNEXPECTED_KIND, watch.observed, "D6: pick_list.last_failure of " + order + " while Pending "
                + "(last query problem: " + watch.probeProblem + ")");
            assertNull(db.rows("SELECT p.last_failure FROM pick_list p JOIN customer_order c "
                + "ON c.id = p.customer_order_id WHERE c.order_number = ?", order).get(0).get("last_failure"),
                "D6: last_failure of " + order + " after the delivery");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // M3 (H1 + H6): Order Desk Notification

    /** An Error twice at the mail step: the notification stays unsent with last_failure, then it is sent once. */
    @Test
    @Order(5)
    void notificationErrorIsRetriedThenSentOnce() throws Throwable {
        String order = "PM-91105";
        String node = "to-order-desk-mailbox";
        IntegrationProcess integration = new IntegrationProcess("TC-915").withFaults(node + ":error:2");
        Cleanup cleanup = new Cleanup(integration).orders(order).files(pickListFile(order));
        Throwable primary = null;
        try {
            assertTopicDrained("notification");
            integration.start();
            runner.run(db.cleanup(order));
            // The Order Desk Notification is queued for a Rejected Peakmart order (inactive SKU)
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_INACTIVE_SKU, order, UUID.randomUUID().toString())));

            Watch watch = watch(FLOW_WAIT,
                () -> {
                    List<Map<String, Object>> rows = db.notifications(order);
                    return rows.size() == 1 && rows.get(0).get("sent_at") != null ? null
                        : "notifications of " + order + ": " + rows.size()
                            + (rows.size() == 1 ? ", unsent, attempts " + rows.get(0).get("attempts") : "")
                            + "; " + receiptSummary(order);
                },
                () -> {
                    List<Map<String, Object>> rows = db.rows("SELECT n.sent_at, n.last_failure FROM notification n "
                        + "JOIN order_receipt r ON r.id = n.order_receipt_id WHERE r.order_number = ?", order);
                    return rows.size() == 1 && rows.get(0).get("sent_at") == null
                        ? (String) rows.get(0).get("last_failure") : null;
                });
            // Behaviour first (M3, mechanism of run B2: the notification poller stops)
            assertNull(watch.endMissing, "M3: the notification of " + order + " sent after two Errors: not within "
                + FLOW_WAIT + " (" + watch.endMissing + ")");
            SpecEnvironment env = SpecEnvironment.get();
            runner.run(env.mailboxes().receiveOne(Mailboxes.ORDER_DESK, Mailboxes.orderDeskSubject(order),
                env.millis("spec-test.arrival-timeout-ms")));
            runner.run(env.mailboxes().expectNone(Mailboxes.ORDER_DESK, Mailboxes.orderDeskSubject(order),
                MAIL_QUIET.toMillis()));
            assertEquals(1, db.notifications(order).size(), "M3: notification rows of " + order);
            assertEquals(REJECTED, db.receiptState(order), "M3: Order State of " + order);
            assertUnexpectedErrorLogged(integration, node);
            assertNotNull(watch.observed, "M3: notification.last_failure of " + order + " while unsent "
                + "(last query problem: " + watch.probeProblem + ")");
            assertTrue(watch.observed.contains(UNEXPECTED_KIND), "M3: notification.last_failure names '"
                + UNEXPECTED_KIND + "': " + watch.observed);
            assertNull(db.rows("SELECT n.last_failure FROM notification n JOIN order_receipt r "
                + "ON r.id = n.order_receipt_id WHERE r.order_number = ?", order).get(0).get("last_failure"),
                "M3: last_failure of " + order + " after it was sent");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // X6 (H1, design review decision 1): a VirtualMachineError stops the process

    /** An OutOfMemoryError at intake: the process exits non-zero within 5 s; after a normal start the event is recorded. */
    @Test
    @Order(6)
    void fatalErrorStopsTheProcessAndTheEventIsRecordedAfterTheRestart() throws Throwable {
        String order = "PM-91106";
        String node = "to-validate-order-placed";
        IntegrationProcess integration = new IntegrationProcess("TC-916").withFaults(node + ":oom:1");
        Cleanup cleanup = new Cleanup(integration).orders(order).files(pickListFile(order));
        Throwable primary = null;
        try {
            assertTopicDrained("fatal");
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order, UUID.randomUUID().toString())));

            // The 5 s are counted from the fault (the consumer may still be joining the group when the event is sent)
            String fires = HOOK_FIRES + node + " kind=oom";
            await("X6: the fault hook fires at " + node, topicWait(),
                () -> log(integration).contains(fires) ? null : "no '" + fires + "' in " + integration.logFile());
            long fired = System.nanoTime();
            Optional<Integer> exit = integration.waitForExit(FATAL_EXIT_LIMIT);
            assertTrue(exit.isPresent(), "X6: the process did not exit within " + FATAL_EXIT_LIMIT + " of the "
                + "OutOfMemoryError at " + node + " (still running after "
                + Duration.ofNanos(System.nanoTime() - fired).toMillis() + " ms; log " + integration.logFile() + ")");
            assertNotEquals(0, exit.get(), "X6: exit code after the OutOfMemoryError");
            assertTrue(lines(log(integration)).stream().anyMatch(l -> l.contains(FATAL_ERROR)
                && l.contains("nodeId=" + node)), "X6: no '" + FATAL_ERROR + "… nodeId=" + node + "' line in "
                + integration.logFile());
            assertTrue(db.receipts(order).isEmpty(), "X6: " + order + " has a receipt before the restart");

            // A normal start of the same instance: afterwards it is a kill (X1, K1–K6)
            integration.stop();
            integration.withoutFaults().start();
            assertFalse(log(integration).contains("Fault hook active points="), "X6: the fault hook is active after "
                + "the normal start (" + integration.logFile() + ")");
            await("X6: " + order + " recorded after the restart", topicWait(),
                () -> recorded(order) ? null : order + " not recorded (" + receiptSummary(order) + ")");
            assertRecordedOnce(order, "Peakmart");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Validation of 005, decision 2 and Q-1: F8's Error, thrown inside the json-validator producer at
    // to-validate-partner-order (FaultHook producer-error: the endpoint's uriSchemaLoader throws, the hook throws nothing)

    /** The 503 contract answer, the Unexpected error line, and neither the Error's message nor Spring's async failure. */
    @Test
    @Order(7)
    void partnerProducerErrorAtValidationIsAnsweredTemporarilyUnavailable() throws Throwable {
        String order = "TG-1917";
        String node = "to-validate-partner-order";
        IntegrationProcess integration = new IntegrationProcess("TC-917").withFaults(node + ":producer-error:1");
        Cleanup cleanup = new Cleanup(integration).orders(order);
        Throwable primary = null;
        try {
            assertTopicDrained("partner producer error");
            integration.start();
            runner.run(db.cleanup(order));

            Answer answer = postPartnerOrder(integration, partnerOrder(order));
            assertContractAnswer503(answer, order, node);

            String log = log(integration);
            assertTrue(log.contains("Fault hook plants node=" + node + " kind=producer-error"), "Q-1: the Error was not "
                + "thrown inside the json-validator producer at " + node + " (" + integration.logFile() + ")");
            assertUnexpectedErrorLogged(integration, node);
            assertFalse(log.contains("Async processing failed") || log.contains("threw exception"), "decision 2: the "
                + "Error reached Spring's servlet (" + integration.logFile() + ")");
            assertFalse(log.contains(FaultHook.F8_MESSAGE), "decision 2: the Error's message is in the log, so a "
                + "stack trace or message was logged (" + integration.logFile() + ")");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Validation of 005, Q-2: the catch-alls of intake and check give an Error its own reason

    /** An Error at set-json-object (inside try-read-as-json): set aside as "cannot be taken in: unexpected error". */
    @Test
    @Order(8)
    void intakeErrorWhileReadingAsJsonSetsTheEventAsideAsUnexpected() throws Throwable {
        String setAsideOrder = "PM-91107";
        String nextOrder = "PM-91108";
        String node = "set-json-object";
        // 1 attempt + 2 redeliveries (on-intake-cannot-take-in): every attempt of the first event faults
        IntegrationProcess integration = new IntegrationProcess("TC-918").withFaults(node + ":error:3");
        Cleanup cleanup = new Cleanup(integration).orders(setAsideOrder, nextOrder);
        Throwable primary = null;
        try {
            assertTopicDrained("intake read");
            cleanup.files(pickListFile(setAsideOrder), pickListFile(nextOrder));
            integration.start();
            runner.run(db.cleanup(setAsideOrder));
            runner.run(db.cleanup(nextOrder));
            String setAsideEvent = UUID.randomUUID().toString();
            String nextEvent = UUID.randomUUID().toString();
            // json-object is never set, so the set-aside key is the Kafka position: found by its content
            cleanup.setAsideContaining(setAsideEvent).setAsideContaining(nextEvent);
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, setAsideOrder, setAsideEvent)));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, nextOrder, nextEvent)));

            await("Q-2: " + setAsideOrder + " set aside", FLOW_WAIT,
                () -> setAsideRowsContaining(setAsideEvent).isEmpty() ? "no set_aside_order_event" : null);
            List<Map<String, Object>> setAside = setAsideRowsContaining(setAsideEvent);
            assertEquals(1, setAside.size(), "Q-2: set_aside_order_event rows of " + setAsideOrder);
            String reason = (String) setAside.get(0).get("cannot_read_reason");
            assertNotNull(reason, "Q-2: cannot_read_reason of " + setAsideOrder);
            assertTrue(reason.startsWith(CANNOT_BE_TAKEN_IN) && reason.contains(UNEXPECTED_KIND), "Q-2: "
                + "cannot_read_reason names '" + CANNOT_BE_TAKEN_IN + UNEXPECTED_KIND + "': " + reason);
            await("Q-2: the next event " + nextOrder + " recorded", FLOW_WAIT,
                () -> recorded(nextOrder) ? null : nextOrder + " is not recorded (" + receiptSummary(nextOrder) + ")");
            assertRecordedOnce(nextOrder, "Peakmart");
            assertUnexpectedErrorLogged(integration, node);
            assertOffsetsCommitted("Q-2");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    /** An Error at to-validate-product (inside try-validate-product) of a Peakmart check: waits as "unexpected error". */
    @Test
    @Order(9)
    void checkErrorAtProductValidationLeavesTheOrderWaitingAsUnexpected() throws Throwable {
        String order = "PM-91109";
        String node = "to-validate-product";
        IntegrationProcess integration = new IntegrationProcess("TC-919").withFaults(node + ":error:2");
        Cleanup cleanup = new Cleanup(integration).orders(order).files(pickListFile(order));
        Throwable primary = null;
        try {
            assertTopicDrained("check product");
            integration.start();
            runner.run(db.cleanup(order));
            runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, order, UUID.randomUUID().toString())));

            Watch watch = watch(FLOW_WAIT,
                () -> recorded(order) ? null : order + " not recorded (" + receiptSummary(order) + ")",
                () -> {
                    List<Map<String, Object>> rows = db.rows("SELECT order_state, waiting_for, waiting_reason "
                        + "FROM order_receipt WHERE order_number = ? AND channel = 'Peakmart'", order);
                    if (rows.size() != 1) {
                        return null;
                    }
                    Map<String, Object> row = rows.get(0);
                    return RECEIVED.equals(row.get("order_state")) && CHECK.equals(row.get("waiting_for"))
                        ? (String) row.get("waiting_reason") : null;
                });
            assertNull(watch.endMissing, "Q-2: " + order + " recorded after two Errors: not within " + FLOW_WAIT
                + " (" + watch.endMissing + ")");
            assertRecordedOnce(order, "Peakmart");
            assertUnexpectedErrorLogged(integration, node);
            assertEquals(UNEXPECTED_KIND, watch.observed, "Q-2: waiting_reason of " + order + " while it waited for "
                + "Check (last query problem: " + watch.probeProblem + ")");
            assertGracefulStop(integration);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Assertions

    /** A partner answer: 503, problem+json, result temporarily-unavailable, Order Answer members valid, under 10 s. */
    private static void assertContractAnswer503(Answer answer, String order, String node) throws IOException {
        String what = "P7 (" + node + "): the answer to " + order;
        if (answer.response == null) {
            fail(what + ": no answer within " + PARTNER_CLIENT_TIMEOUT + " (" + answer.failure + ")");
        }
        HttpResponse<String> response = answer.response;
        assertTrue(answer.elapsed.compareTo(PARTNER_ANSWER_LIMIT) < 0, what + " took " + answer.elapsed.toMillis()
            + " ms (limit " + PARTNER_ANSWER_LIMIT.toMillis() + " ms): " + response.statusCode() + " " + response.body());
        assertEquals(503, response.statusCode(), what + ": status (" + response.body() + ")");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
            what + ": content type " + response.headers().firstValue("Content-Type").orElse("none"));
        JsonNode problem = JSON.readTree(response.body());
        assertEquals("temporarily-unavailable", problem.path("result").asText(), what + ": result (" + response.body()
            + ")");
        assertEquals(503, problem.path("status").asInt(), what + ": status member");
        ObjectNode orderAnswer = JSON.createObjectNode();
        for (String member : ORDER_ANSWER_MEMBERS) {
            if (problem.has(member)) {
                orderAnswer.set(member, problem.get(member));
            }
        }
        assertNull(orderAnswerViolation(JSON.writeValueAsString(orderAnswer)), what + ": its Order Answer members "
            + orderAnswer + " are not valid against " + ORDER_ANSWER_SCHEMA);
        if (problem.has("orderNumber")) {
            assertEquals(order, problem.path("orderNumber").asText(), what + ": orderNumber");
        }
    }

    /** One {@code Unexpected error routeId=… nodeId=<node> error=…NoSuchMethodError…} line (§3.2, §3.8). */
    private static void assertUnexpectedErrorLogged(IntegrationProcess integration, String node) throws IOException {
        List<String> lines = lines(log(integration));
        assertTrue(lines.stream().anyMatch(l -> l.contains(UNEXPECTED_ERROR) && l.contains("nodeId=" + node)
            && l.contains(ERROR_CLASS)), "No '" + UNEXPECTED_ERROR + "… nodeId=" + node + " error=…" + ERROR_CLASS
            + "' line in " + integration.logFile());
    }

    /** X2: SIGTERM ends the process within the grace period, nothing is left in flight. */
    private static void assertGracefulStop(IntegrationProcess integration) {
        long start = System.nanoTime();
        integration.stop();
        assertTrue(integration.lastStopGraceful(), "X2: the SIGTERM stop was not graceful (forced after "
            + Duration.ofNanos(System.nanoTime() - start).toMillis() + " ms; log " + integration.logFile() + ")");
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

    private static void assertOffsetsCommitted(String what) {
        long end = peakmart.endOffsets();
        await(what + ": every record on " + Peakmart.TOPIC + " committed", FLOW_WAIT, () -> {
            long committed = peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP);
            return committed >= end ? null : "committed " + committed + " of " + end;
        });
    }

    /** The {@code set_aside_order_event} rows whose stored event content holds {@code text} (an event id). */
    private static List<Map<String, Object>> setAsideRowsContaining(String text) {
        return db.rows("SELECT event_id, order_number, cannot_read_reason FROM set_aside_order_event "
            + "WHERE event_content LIKE ?", "%" + text + "%");
    }

    /** Precondition: every record on the topic is committed, so a fault can only land on this case's own event. */
    private static void assertTopicDrained(String testCase) {
        assertEquals(peakmart.endOffsets(), peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP),
            testCase + ": precondition, every record on " + Peakmart.TOPIC + " is committed before the start");
    }

    private static boolean recorded(String order) {
        List<Map<String, Object>> receipts = db.receipts(order);
        if (receipts.size() != 1) {
            return false;
        }
        Object state = receipts.get(0).get("order_state");
        return (RECORDED.equals(state) || SENT_TO_WAREHOUSE.equals(state)) && db.customerOrder(order).isPresent();
    }

    private static String receiptSummary(String order) {
        List<Map<String, Object>> receipts = db.receipts(order);
        if (receipts.isEmpty()) {
            return "no receipt";
        }
        Map<String, Object> r = receipts.get(0);
        return receipts.size() + " receipt(s), state " + r.get("order_state") + ", waiting_for " + r.get("waiting_for");
    }

    /** The Order Answer members validated with the contract schema; null when valid, else the reason. */
    private static String orderAnswerViolation(String json) {
        try {
            validator.requestBody("json-validator:file:" + docsFile(ORDER_ANSWER_SCHEMA), json);
            return null;
        } catch (RuntimeException e) {
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cleanup

    /** Stops the process, drains the topic, removes the orders, set-aside events and pick-list files of one case. */
    private static final class Cleanup {
        private final IntegrationProcess integration;
        private final List<String> orders = new ArrayList<>();
        private final List<String> files = new ArrayList<>();
        private final List<String> setAsideEvents = new ArrayList<>();
        private final List<String> setAsideContents = new ArrayList<>();

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

        Cleanup files(String... names) {
            for (String name : names) {
                if (!files.contains(name)) {
                    files.add(name);
                }
            }
            return this;
        }

        Cleanup setAside(String eventId) {
            setAsideEvents.add(eventId);
            return this;
        }

        /** Set-aside rows whose content holds {@code text}, whatever key they got (an unread event: kafka-…). */
        Cleanup setAsideContaining(String text) {
            setAsideContents.add(text);
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
                        integration.withoutFaults().start();
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
            for (String text : setAsideContents) {
                try {
                    // the key of an unread event is its Kafka position, which OrderDatabase does not accept as an id
                    new JdbcTemplate(db.dataSource()).update(
                        "DELETE FROM set_aside_order_event WHERE event_content LIKE ?", "%" + text + "%");
                    assertTrue(setAsideRowsContaining(text).isEmpty(), "a set_aside_order_event holding " + text
                        + " is still in the Order Database");
                } catch (Throwable t) {
                    failure = keep(failure, t);
                }
            }
            for (String eventId : setAsideEvents) {
                try {
                    db.cleanupSetAsideNow(eventId);
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

    /** A Peakmart Order Event from a docs example, with its own Order Number and event id, as a file. */
    private static Path peakmartEvent(String example, String order, String eventId) throws IOException {
        ObjectNode event = (ObjectNode) JSON.readTree(docsFile(example).toFile());
        event.put("event_id", eventId);
        event.put("order_ref", order);
        Path dir = SpecEnvironment.get().workDir().resolve("unexpected-error-it");
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

    /** A partner answer, or why there is none, and how long it took. */
    private record Answer(HttpResponse<String> response, String failure, Duration elapsed) {
    }

    /** {@code POST /orders} with the run's API Key (never logged), bounded by {@link #PARTNER_CLIENT_TIMEOUT}. */
    private static Answer postPartnerOrder(IntegrationProcess integration, String body) throws IOException {
        String key = Files.readString(SpecEnvironment.get().partnerApiKeyFile(), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + integration.serverPort() + Partner.ORDERS_PATH))
            .timeout(PARTNER_CLIENT_TIMEOUT)
            .header("Content-Type", "application/json")
            .header(Partner.API_KEY_HEADER, key)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Answer(response, null, Duration.ofNanos(System.nanoTime() - start));
        } catch (HttpTimeoutException e) {
            return new Answer(null, "timed out: " + e.getClass().getSimpleName(),
                Duration.ofNanos(System.nanoTime() - start));
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

    @FunctionalInterface
    private interface Probe {
        /** The transient value when it is seen now, else null. */
        String observe() throws Exception;
    }

    /** The outcome of {@link #watch}: what was still missing (null when met), the first observed value. */
    private static final class Watch {
        String endMissing;
        String observed;
        String probeProblem;
    }

    /** Samples {@code probe} every {@value #FAST_POLL_MILLIS} ms until {@code end} is met or {@code timeout} passed. */
    private static Watch watch(Duration timeout, Condition end, Probe probe) {
        Watch watch = new Watch();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (watch.observed == null) {
                try {
                    watch.observed = probe.observe();
                } catch (Exception e) {
                    watch.probeProblem = rootMessage(e);
                }
            }
            try {
                watch.endMissing = end.missing();
            } catch (Exception e) {
                watch.endMissing = rootMessage(e);
            }
            if (watch.endMissing == null || System.nanoTime() > deadline) {
                return watch;
            }
            sleep(FAST_POLL_MILLIS);
        }
    }

    /** Polls {@code condition} until it is met or {@code timeout} has passed. */
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

    private static List<String> lines(String text) {
        return text.lines().toList();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
            n++;
        }
        return n;
    }

    private static String pickListFile(String order) {
        return "PL-" + order + ".csv";
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
