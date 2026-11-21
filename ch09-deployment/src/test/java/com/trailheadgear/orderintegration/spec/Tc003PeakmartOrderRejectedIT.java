package com.trailheadgear.orderintegration.spec;

import static org.citrusframework.actions.SleepAction.Builder.sleep;
import static org.citrusframework.container.Wait.Builder.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.xml.transform.stream.StreamSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.Mailboxes;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Peakmart;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import com.trailheadgear.orderintegration.spec.support.SpecRun;
import com.trailheadgear.orderintegration.spec.support.SpecRun.UseCase;
import com.trailheadgear.orderintegration.spec.support.WarehouseInbox;
import org.citrusframework.TestActionBuilder;
import org.citrusframework.TestCaseRunner;
import org.citrusframework.actions.AbstractTestAction;
import org.citrusframework.annotations.CitrusResource;
import org.citrusframework.context.TestContext;
import org.citrusframework.exceptions.CitrusRuntimeException;
import org.citrusframework.junit.jupiter.CitrusSupport;
import org.citrusframework.mail.message.CitrusMailMessageHeaders;
import org.citrusframework.mail.message.MailMessage;
import org.citrusframework.mail.model.BodyPart;
import org.citrusframework.mail.model.MailMarshaller;
import org.citrusframework.mail.model.MailRequest;
import org.citrusframework.message.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TC-003 Peakmart Order Rejected ({@code docs/test_cases/TC-003-peakmart-order-rejected.md}, design 004 §3.6).
 *
 * <p>Peakmart publishes Order {@code PM-50118} with one Order Line {@code TENT-1P}; the Product Catalog stub answers
 * {@code tent-1p-inactive.json}, so the Order fails the Order Checks. The Order Desk mailbox receives one plain-text
 * Order Desk Notification naming the Order Number and the Rejection Reason, without the buyer's personal data, and no
 * Pick List reaches the Warehouse SFTP Server. The order lookup (UC-005) is not built: the steps and Validations that
 * need it are declared not yet testable and are never executed or faked (A-15).
 *
 * <p>Names as the Order Database stores them (Flyway V1 check constraints): Order States {@code Received},
 * {@code Rejected}; Channel {@code Peakmart}; the Order Check the document writes {@code sku} is stored as {@code SKU}.
 * The Rejection Reason text is asserted verbatim against the example (A-16).
 */
@SpecTestCase("TC-003")
@CitrusSupport
class Tc003PeakmartOrderRejectedIT {

    private static final Logger LOG = LoggerFactory.getLogger(Tc003PeakmartOrderRejectedIT.class);

    private static final String TC_ID = "TC-003";
    private static final String DISPLAY_NAME = "TC-003 Peakmart Order Rejected";
    private static final String ORDER_NUMBER = "PM-50118";
    private static final String PICK_LIST_FILE = "PL-PM-50118.csv";
    private static final String ORDER_EVENT_EXAMPLE =
        "messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json";
    private static final String NOTIFICATION_EXAMPLE = "messages/examples/order-desk-notification/pm-50118.json";
    /** The event id the document names for step 1. */
    private static final String EVENT_ID = "5b7e2d94-8c1a-4e3f-b6d0-2a9c4f7e1b38";
    private static final String INACTIVE_SKU = "TENT-1P";
    /** The Order Check {@code sku} of the document, as Flyway V1 stores it. */
    private static final String SKU_CHECK = "SKU";
    /** The test case's own limit for the Order Desk Notification (step 3), next to the shorter wait bound (A-9). */
    private static final Duration NOTIFICATION_LIMIT = Duration.ofMinutes(5);
    private static final long WAIT_INTERVAL_MS = 1000L;

    public static final List<SpecRun.Item> ITEMS = List.of(
        SpecRun.run("C-setup", "Preconditions established by the test"),
        SpecRun.notYetTestable("C-helpdesk", "The Helpdesk Tool is set up to call the order lookup with its own key "
            + "from the test environment's Secret Store (OQ-066)", UseCase.UC_005),
        SpecRun.run("S1", "Publish peakmart order"),
        SpecRun.run("S2", "Check order"),
        SpecRun.run("S3", "Verify order desk notification"),
        SpecRun.notYetTestable("S4", "Look up order", UseCase.UC_005),
        SpecRun.notYetTestable("S5", "Verify rejection reason shown", UseCase.UC_005),
        SpecRun.run("V1", "No Pick List"),
        SpecRun.notYetTestable("V2", "Same reasons everywhere", UseCase.UC_005),
        SpecRun.run("V3", "One notification"),
        SpecRun.notYetTestable("V4", "Never recorded", UseCase.UC_005),
        SpecRun.run("P1", "One Rejected Order PM-50118 of the Channel peakmart with one Rejection Reason (sku, Order "
            + "Line 1) exists in the Order History with the Order States received and rejected; it is not recorded in "
            + "the Order Database as an Accepted Order"),
        SpecRun.run("P2", "One Order Desk Notification for PM-50118 lies in the Order Desk mailbox of the test "
            + "environment, and the integration keeps when it was sent"),
        SpecRun.run("P-cleanup", "Cleanup order"));

    @CitrusResource
    private TestCaseRunner runner;

    private OrderDatabase db;
    private WarehouseInbox inbox;
    private Peakmart peakmart;
    private Instant publishedAt;
    private String expectedReasonText;
    private MailRequest notificationMail;
    private boolean consumed;

    @Test
    @DisplayName(DISPLAY_NAME)
    void peakmartOrderRejected(TestReporter testReporter) throws Throwable {
        SpecRun run = new SpecRun(TC_ID, getClass(), DISPLAY_NAME, ITEMS, testReporter);
        IntegrationProcess integration = new IntegrationProcess(TC_ID);
        Throwable primary = null;
        try {
            run.execute("C-setup", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                LOG.info("{}: images {}", TC_ID, env.imageReferences());
                db = new OrderDatabase();
                inbox = new WarehouseInbox();
                // The integration runs Flyway itself: the tables exist only once it has started. Nothing for PM-50118
                // is published before S1, so cleaning up after the start establishes the precondition.
                integration.start();
                peakmart = new Peakmart();
                // No Order PM-50118 in the Order Database (same statements as P-cleanup)
                runner.run(db.cleanup(ORDER_NUMBER));
                assertTrue(db.receipts(ORDER_NUMBER).isEmpty(), "An order_receipt PM-50118 is left after the cleanup");
                assertTrue(db.customerOrder(ORDER_NUMBER).isEmpty(),
                    "A customer_order PM-50118 is left after the cleanup");
                // The Order Desk mailbox holds no Order Desk Notification for PM-50118 (zero-wait selector check)
                runner.run(env.mailboxes().expectNoneWaiting(Mailboxes.ORDER_DESK,
                    Mailboxes.orderDeskSubject(ORDER_NUMBER)));
                // No PL-PM-50118.csv (or .tmp) on the Warehouse SFTP Server
                inbox.remove(PICK_LIST_FILE);
                assertEquals(0, inbox.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the Warehouse SFTP Server");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is still on the server");
                // The Product Catalog serves tent-1p-inactive.json; only this run's requests are counted
                env.catalog().clearRequests();
            });

            run.execute("S1", () -> {
                JsonNode event = readJson(docsFile(ORDER_EVENT_EXAMPLE));
                assertEquals("order.placed", event.path("event_type").asText(), "event type of the step 1 example");
                assertEquals(EVENT_ID, event.path("event_id").asText(), "event id of the step 1 example");
                assertEquals(ORDER_NUMBER, event.path("order_ref").asText(), "order_ref of the step 1 example");
                assertEquals(1, event.path("items").size(), "one Order Line in the step 1 example");
                assertEquals(INACTIVE_SKU, event.path("items").get(0).path("sku").asText());
                assertEquals(1, event.path("items").get(0).path("qty").asInt());
                publishedAt = Instant.now();
                runner.run(peakmart.publish(docsFile(ORDER_EVENT_EXAMPLE)));
            });

            run.execute("S2", () -> {
                // The Order Event is consumed asynchronously: wait until the Order has failed the Order Checks
                awaitArrival("order-rejected", () -> {
                    List<Map<String, Object>> receipts = db.receipts(ORDER_NUMBER);
                    if (receipts.size() != 1 || !"Rejected".equals(receipts.get(0).get("order_state"))
                        || db.rejectionReasons(ORDER_NUMBER).isEmpty()) {
                        throw new CitrusRuntimeException(ORDER_NUMBER + " not yet rejected: " + receipts);
                    }
                });
                assertEquals(Set.of(INACTIVE_SKU), Set.copyOf(SpecEnvironment.get().catalog().requestedSkus()),
                    "The Product Catalog is asked for TENT-1P and nothing else");
                expectedReasonText = exampleReasonText();
                assertSingleSkuReason();
            });

            run.execute("S3", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                AtomicReference<Message> received = new AtomicReference<>();
                runner.run(env.mailboxes()
                    .receiveOne(Mailboxes.ORDER_DESK, Mailboxes.orderDeskSubject(ORDER_NUMBER),
                        env.millis("spec-test.arrival-timeout-ms"))
                    .validate((message, context) -> received.set(message)));
                Duration elapsed = Duration.between(publishedAt, Instant.now());
                Message message = received.get();
                assertNotNull(message, "No Order Desk Notification was handed to the validation");
                notificationMail = mailRequest(message);
                LOG.info("{}: Order Desk Notification received {} ms after step 1: {}", TC_ID, elapsed.toMillis(),
                    message.getPayload(String.class));
                assertTrue(elapsed.compareTo(NOTIFICATION_LIMIT) < 0,
                    "The Order Desk Notification arrived " + elapsed + " after step 1, limit " + NOTIFICATION_LIMIT);

                assertEquals(Mailboxes.ORDER_DESK, notificationMail.getTo(), "recipient of the notification");
                assertEquals(Mailboxes.orderDeskSubject(ORDER_NUMBER), notificationMail.getSubject(), "subject");
                // Plain text: a single text/plain part, no multipart, no attachment
                Object contentType = message.getHeader(CitrusMailMessageHeaders.MAIL_CONTENT_TYPE);
                assertNotNull(contentType, "Content type of the notification");
                assertTrue(contentType.toString().toLowerCase().startsWith("text/plain"),
                    "The notification is not plain text: " + contentType);
                BodyPart body = notificationMail.getBody();
                assertNotNull(body, "The notification has no body");
                assertTrue(body.getContentType().toLowerCase().startsWith("text/plain"),
                    "The notification body is not plain text: " + body.getContentType());
                assertFalse(body.hasAttachments(), "The notification carries attachments");

                String text = body.getContent();
                assertTrue(text.contains(ORDER_NUMBER), "The notification does not name " + ORDER_NUMBER + ":\n" + text);
                // A-16: the reason text of the example, verbatim, naming Order Line 1
                assertTrue(expectedReasonText.startsWith("Order Line 1:"), "example reason names Order Line 1");
                assertTrue(text.contains(expectedReasonText),
                    "The notification does not hold the Rejection Reason of the example verbatim:\n" + text);
                assertNoPersonalData(text);
            });

            run.execute("V1", () -> {
                awaitConsumed();
                // "15 minutes after step 1": several cycles of the 1 s pollers (A-10)
                runner.run(sleep().milliseconds(SpecEnvironment.get().millis("spec-test.quiet-period-ms")));
                assertEquals(0, inbox.count(PICK_LIST_FILE),
                    PICK_LIST_FILE + " is in /inbound/picklists or /inbound/picklists/processed");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is on the server");
                assertTrue(db.pickList(ORDER_NUMBER).isEmpty(), "A pick_list row exists for " + ORDER_NUMBER);
            });

            run.execute("V3", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                awaitConsumed();
                // Exactly one = the S3 receive plus no second one over the quiet period
                runner.run(env.mailboxes().expectNone(Mailboxes.ORDER_DESK, Mailboxes.orderDeskSubject(ORDER_NUMBER),
                    env.millis("spec-test.quiet-period-ms")));
                List<Map<String, Object>> notifications = db.notifications(ORDER_NUMBER);
                assertEquals(1, notifications.size(), "notification rows of " + ORDER_NUMBER + ": " + notifications);
                assertEquals("Order Desk Notification", notifications.get(0).get("notification_type"));
                assertEquals(Mailboxes.ORDER_DESK, notifications.get(0).get("recipient_email"));
            });

            run.execute("P1", () -> {
                List<Map<String, Object>> receipts = db.receipts(ORDER_NUMBER);
                assertEquals(1, receipts.size(), "order_receipt rows of " + ORDER_NUMBER);
                assertEquals("Peakmart", receipts.get(0).get("channel"), "Channel of " + ORDER_NUMBER);
                assertEquals(EVENT_ID, String.valueOf(receipts.get(0).get("event_id")), "event id of the receipt");
                assertEquals("Rejected", db.receiptState(ORDER_NUMBER), "Order State of " + ORDER_NUMBER);
                assertEquals(List.of("Received", "Rejected"), db.stateHistory(ORDER_NUMBER),
                    "Order History of " + ORDER_NUMBER + ": received, rejected only");
                assertSingleSkuReason();
                assertTrue(db.customerOrder(ORDER_NUMBER).isEmpty(),
                    ORDER_NUMBER + " is recorded in the Order Database as an Accepted Order");
                assertTrue(db.orderLines(ORDER_NUMBER).isEmpty(), "order_line rows exist for " + ORDER_NUMBER);
            });

            run.execute("P2", () -> {
                assertTrue(db.notificationSentAt(ORDER_NUMBER).isPresent(),
                    "notification.sent_at of " + ORDER_NUMBER + " is not set");
                assertNotNull(notificationMail, "The Order Desk Notification of S3 was not received");
                assertEquals(Mailboxes.orderDeskSubject(ORDER_NUMBER), notificationMail.getSubject());
            });

            run.bodyCompleted();
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            primary = cleanUp(run, integration, primary);
        }
    }

    /** P-cleanup, the integration stop, the Peakmart producer and the result file; a later failure never hides an earlier one. */
    private Throwable cleanUp(SpecRun run, IntegrationProcess integration, Throwable primary) throws Throwable {
        Throwable failure = primary;
        try {
            run.executeAlways("P-cleanup", () -> {
                OrderDatabase database = db != null ? db : new OrderDatabase();
                WarehouseInbox warehouse = inbox != null ? inbox : new WarehouseInbox();
                // notification and rejection_reason with (before) the Order History and the receipt, in one transaction
                database.cleanupNow(ORDER_NUMBER);
                warehouse.remove(PICK_LIST_FILE);
                assertTrue(database.receipts(ORDER_NUMBER).isEmpty(), ORDER_NUMBER + " is still in the Order Database");
                assertTrue(database.rows("SELECT count(*) AS n FROM notification n JOIN order_receipt r "
                    + "ON r.id = n.order_receipt_id WHERE r.order_number = ?", ORDER_NUMBER).stream()
                    .allMatch(row -> ((Number) row.get("n")).longValue() == 0L), "notification rows are left");
                assertEquals(0, warehouse.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the server");
                // The email was consumed by the S3 receive: none is left for PM-50118 in the Order Desk mailbox
                runner.run(SpecEnvironment.get().mailboxes().expectNoneWaiting(Mailboxes.ORDER_DESK,
                    Mailboxes.orderDeskSubject(ORDER_NUMBER)));
                // The Product Catalog data are read-only example files and stay untouched
            });
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            integration.stop();
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            if (peakmart != null) {
                peakmart.close();
            }
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            run.finish();
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        if (failure != null && failure != primary) {
            throw failure;
        }
        return failure;
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

    /** Exactly one Rejection Reason: Order Check sku, Order Line 1, the text of the example. */
    private void assertSingleSkuReason() {
        List<Map<String, Object>> reasons = db.rejectionReasons(ORDER_NUMBER);
        assertEquals(1, reasons.size(), "Rejection Reasons of " + ORDER_NUMBER + ": " + reasons);
        Map<String, Object> reason = reasons.get(0);
        assertEquals(SKU_CHECK, reason.get("order_check"), "Order Check of the Rejection Reason");
        assertNotNull(reason.get("line_number"), "The Rejection Reason names no Order Line");
        assertEquals(1, ((Number) reason.get("line_number")).intValue(), "Order Line of the Rejection Reason");
        assertEquals(expectedReasonText, reason.get("reason_text"), "Rejection Reason text (A-16)");
    }

    /** The single Rejection Reason text of {@code order-desk-notification/pm-50118.json} (sku, Order Line 1). */
    private static String exampleReasonText() throws IOException {
        JsonNode example = readJson(docsFile(NOTIFICATION_EXAMPLE));
        assertEquals(ORDER_NUMBER, example.path("orderNumber").asText(), "Order Number of the notification example");
        JsonNode reasons = example.path("rejectionReasons");
        assertEquals(1, reasons.size(), "Rejection Reasons of the notification example");
        assertEquals("sku", reasons.get(0).path("orderCheck").asText());
        assertEquals(1, reasons.get(0).path("lineNumber").asInt());
        String text = reasons.get(0).path("text").asText();
        assertFalse(text.isBlank(), "The notification example has no reason text");
        return text;
    }

    /**
     * None of the buyer's name, email address or shipping address field values of the event file. Values of three
     * characters or fewer (state and country codes) are matched as whole words, longer ones as substrings.
     */
    private static void assertNoPersonalData(String text) throws IOException {
        JsonNode event = readJson(docsFile(ORDER_EVENT_EXAMPLE));
        Map<String, String> personal = new LinkedHashMap<>();
        personal.put("buyer.email", event.path("buyer").path("email").asText());
        event.path("ship_to").fields().forEachRemaining(f -> personal.put("ship_to." + f.getKey(), f.getValue().asText()));
        assertTrue(personal.containsKey("ship_to.full_name") && personal.containsKey("ship_to.address1"),
            "The event example has no buyer name or shipping address: " + personal.keySet());
        for (Map.Entry<String, String> field : personal.entrySet()) {
            String value = field.getValue();
            assertFalse(value.isBlank(), field.getKey() + " of the event example is blank");
            boolean found = value.length() <= 3
                ? Pattern.compile("\\b" + Pattern.quote(value) + "\\b").matcher(text).find()
                : text.contains(value);
            assertFalse(found, "The Order Desk Notification holds " + field.getKey() + " of the event:\n" + text);
        }
        // The name and the email address also not in parts (last name, local part of the address)
        for (String part : personal.get("ship_to.full_name").split("\\s+")) {
            assertFalse(text.contains(part), "The Order Desk Notification holds part of the buyer's name: " + part);
        }
        String email = personal.get("buyer.email");
        assertFalse(text.contains(email.substring(0, email.indexOf('@'))),
            "The Order Desk Notification holds the local part of the buyer's email address");
    }

    /** The received mail as the Citrus mail model (the server hands over a {@code MailMessage}, else its XML/JSON form). */
    private static MailRequest mailRequest(Message message) {
        if (message instanceof MailMessage mail) {
            MailRequest request = mail.getPayload(MailRequest.class);
            if (request != null) {
                return request;
            }
        }
        Object payload = message.getPayload();
        if (payload instanceof MailRequest request) {
            return request;
        }
        Object model = new MailMarshaller().unmarshal(new StreamSource(new StringReader(message.getPayload(String.class))));
        if (model instanceof MailRequest request) {
            return request;
        }
        throw new AssertionError("The received message is not a mail: " + model);
    }

    /** The integration's consumer group committed every record on the Peakmart order topic (the event was handled). */
    private void awaitConsumed() {
        if (consumed) {
            return;
        }
        long end = peakmart.endOffsets();
        awaitArrival("order-event-consumed", () -> {
            long committed = peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP);
            if (committed < end) {
                throw new CitrusRuntimeException(Peakmart.INTEGRATION_GROUP + " committed " + committed + " of " + end
                    + " records on " + Peakmart.TOPIC);
            }
        });
        LOG.info("{}: {} committed all {} records of {}", TC_ID, Peakmart.INTEGRATION_GROUP, end, Peakmart.TOPIC);
        consumed = true;
    }

    /** Polls {@code check} up to the arrival bound (A-9), as a Citrus {@code waitFor}. */
    private void awaitArrival(String name, Check check) {
        runner.run(waitFor().execution()
            .milliseconds(SpecEnvironment.get().millis("spec-test.arrival-timeout-ms"))
            .interval(WAIT_INTERVAL_MS)
            .action(check(name, check)));
    }

    private static JsonNode readJson(Path file) throws IOException {
        return new ObjectMapper().readTree(file.toFile());
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    /** A Citrus test action around a check; used as the condition action of {@code waitFor().execution()}. */
    private static TestActionBuilder<AbstractTestAction> check(String name, Check check) {
        AbstractTestAction action = new AbstractTestAction() {
            @Override
            public void doExecute(TestContext context) {
                try {
                    check.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CitrusRuntimeException(e);
                }
            }
        };
        action.setName(name);
        return () -> action;
    }
}
