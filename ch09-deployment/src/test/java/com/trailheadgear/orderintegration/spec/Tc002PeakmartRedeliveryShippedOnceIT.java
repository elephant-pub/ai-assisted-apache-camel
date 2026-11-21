package com.trailheadgear.orderintegration.spec;

import static org.citrusframework.actions.SleepAction.Builder.sleep;
import static org.citrusframework.container.Wait.Builder.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TC-002 Peakmart Redelivery Shipped Once ({@code docs/test_cases/TC-002-peakmart-redelivery-shipped-once.md},
 * design 004 §3.5).
 *
 * <p>Peakmart publishes Order {@code PM-50117} on the Peakmart order topic, the Product Catalog stub answers both SKUs,
 * and the Pick List reaches the Warehouse SFTP Server. Peakmart then publishes the same Order again under a new event
 * id; after the quiet period (A-10) the server still holds exactly one Pick List and the Order Database one Order. The
 * order lookup (UC-005) and the alerts (UC-006) are not built: the items that need them are declared not yet testable
 * and are never executed (A-15). Validation 3 is split into V3a (Order Desk mailbox, run) and V3b (Alert, UC-006).
 *
 * <p>Order States and the Channel are asserted with the names the Order Database stores (Flyway V1 check
 * constraints: {@code Received}, {@code Recorded}, {@code Sent to Warehouse}; Channel {@code Peakmart}); the delivered
 * Pick List state is {@code Delivered} (Flyway V2).
 */
@SpecTestCase("TC-002")
@CitrusSupport
class Tc002PeakmartRedeliveryShippedOnceIT {

    private static final Logger LOG = LoggerFactory.getLogger(Tc002PeakmartRedeliveryShippedOnceIT.class);

    private static final String TC_ID = "TC-002";
    private static final String DISPLAY_NAME = "TC-002 Peakmart Redelivery Shipped Once";
    private static final String ORDER_NUMBER = "PM-50117";
    private static final String PICK_LIST_FILE = "PL-PM-50117.csv";
    private static final String ORDER_EVENT_EXAMPLE =
        "messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json";
    private static final String REDELIVERED_EXAMPLE =
        "messages/examples/peakmart-order-placed/pm-50117-redelivered.json";
    private static final String PICK_LIST_EXAMPLE = "messages/examples/pick-list/pl-pm-50117.json";
    /** The event ids the document names for step 1 and step 5. */
    private static final String FIRST_EVENT_ID = "8d2f6c1e-4b7a-4f0e-9a51-3c2e7b9d0f14";
    private static final String REDELIVERY_EVENT_ID = "c3a8f1d2-7e4b-4a96-9d05-6f1b2e8c4a77";
    /** The test case's own limit for the Pick List (step 4), asserted next to the much shorter wait bound (A-9). */
    private static final Duration PICK_LIST_LIMIT = Duration.ofMinutes(15);
    /** Poll interval of the waits: longer than one SFTP check, so a check is never cut off. */
    private static final long WAIT_INTERVAL_MS = 2000L;
    private static final Set<String> ACCEPTED_STATES = Set.of("Recorded", "Sent to Warehouse");
    /** The two records the test publishes (steps 1 and 5). */
    private static final long RECORDS_PUBLISHED = 2L;

    public static final List<SpecRun.Item> ITEMS = List.of(
        SpecRun.run("C-setup", "Preconditions established by the test"),
        SpecRun.notYetTestable("C-helpdesk", "The Helpdesk Tool is set up to call the order lookup with its own key "
            + "from the test environment's Secret Store (OQ-066)", UseCase.UC_005),
        SpecRun.run("S1", "Publish peakmart order"),
        SpecRun.run("S2", "Check order"),
        SpecRun.run("S3", "Deliver pick list"),
        SpecRun.run("S4", "Verify pick list delivered"),
        SpecRun.run("S5", "Redeliver peakmart order"),
        SpecRun.run("S6", "Verify no second pick list"),
        SpecRun.notYetTestable("S7", "Look up order", UseCase.UC_005),
        SpecRun.notYetTestable("S8", "Verify single order", UseCase.UC_005),
        SpecRun.run("V1", "Shipped once"),
        SpecRun.notYetTestable("V2", "Recorded once", UseCase.UC_005),
        SpecRun.run("V3a", "Redelivery silent: the Order Desk mailbox received no Order Desk Notification for "
            + "PM-50117"),
        SpecRun.notYetTestable("V3b", "Redelivery silent: the Integration Team mailbox received no Alert naming "
            + "PM-50117 or either event id", UseCase.UC_006),
        SpecRun.run("P1", "One Accepted Order PM-50117 of the Channel peakmart is recorded in the Order Database"),
        SpecRun.run("P2", "The Order History of PM-50117 holds the Order States received, recorded and sent to "
            + "warehouse"),
        SpecRun.run("P3", "The file PL-PM-50117.csv lies on the Warehouse SFTP Server, in /inbound/picklists or "
            + "/inbound/picklists/processed"),
        SpecRun.run("P4", "Both Order Events stay on the Peakmart order topic of the test environment; the "
            + "integration sends nothing to Peakmart"),
        SpecRun.run("P-cleanup", "Cleanup order"));

    @CitrusResource
    private TestCaseRunner runner;

    private OrderDatabase db;
    private WarehouseInbox inbox;
    private Peakmart peakmart;
    private long offsetsBefore;
    private Instant publishedAt;

    @Test
    @DisplayName(DISPLAY_NAME)
    void peakmartRedeliveryShippedOnce(TestReporter testReporter) throws Throwable {
        SpecRun run = new SpecRun(TC_ID, getClass(), DISPLAY_NAME, ITEMS, testReporter);
        IntegrationProcess integration = new IntegrationProcess(TC_ID);
        Throwable primary = null;
        try {
            run.execute("C-setup", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                LOG.info("{}: images {}", TC_ID, env.imageReferences());
                db = new OrderDatabase();
                inbox = new WarehouseInbox();
                // The integration runs Flyway itself: the Order Database tables exist only once it has started. No
                // Order Event for PM-50117 is published before S1, so cleaning up after the start establishes the
                // precondition. The integration reads the Peakmart order topic (created by SpecEnvironment).
                integration.start();
                peakmart = new Peakmart();
                // No Order PM-50117 in the Order Database (same statements as P-cleanup)
                runner.run(db.cleanup(ORDER_NUMBER));
                assertTrue(db.receipts(ORDER_NUMBER).isEmpty(), "An order_receipt PM-50117 is left after the cleanup");
                assertTrue(db.customerOrder(ORDER_NUMBER).isEmpty(),
                    "A customer_order PM-50117 is left after the cleanup");
                // No PL-PM-50117.csv in /inbound/picklists or /inbound/picklists/processed
                inbox.remove(PICK_LIST_FILE);
                assertEquals(0, inbox.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the Warehouse SFTP Server");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is still on the server");
                // The Product Catalog answers the two Active Products; only this run's requests are counted
                env.catalog().clearRequests();
                // Snapshot of the topic for P4 ("both Order Events stay; the integration sends nothing to Peakmart")
                offsetsBefore = peakmart.endOffsets();
                LOG.info("{}: {} end offsets before step 1: {}", TC_ID, Peakmart.TOPIC, offsetsBefore);
            });

            run.execute("S1", () -> {
                JsonNode event = readJson(docsFile(ORDER_EVENT_EXAMPLE));
                assertEquals(FIRST_EVENT_ID, event.path("event_id").asText(), "event id of the step 1 example");
                assertEquals(ORDER_NUMBER, event.path("order_ref").asText(), "order_ref of the step 1 example");
                publishedAt = Instant.now();
                runner.run(peakmart.publish(docsFile(ORDER_EVENT_EXAMPLE)));
            });

            run.execute("S2", () -> {
                // The Order Event is consumed asynchronously: wait until the Order is checked and recorded
                awaitArrival("order-recorded", () -> {
                    List<Map<String, Object>> receipts = db.receipts(ORDER_NUMBER);
                    if (receipts.size() != 1
                        || !ACCEPTED_STATES.contains((String) receipts.get(0).get("order_state"))) {
                        throw new CitrusRuntimeException(ORDER_NUMBER + " not yet recorded: " + receipts);
                    }
                });
                // Exactly these two requests, each once; the lookups may run concurrently, so no order is asserted
                assertEquals(List.of("STAKE-AL8", "TENT-2P"),
                    SpecEnvironment.get().catalog().requestedSkus().stream().sorted().toList(),
                    "The Product Catalog is asked for exactly TENT-2P and STAKE-AL8, each once");
                assertPeakmartLines();
            });

            run.execute("S3", () -> {
                // The Accepted Order queues its Pick List; whether it is delivered is checked in S4
                awaitArrival("pick-list-queued", () -> {
                    if (db.pickList(ORDER_NUMBER).isEmpty()) {
                        throw new CitrusRuntimeException("No Pick List queued yet for " + ORDER_NUMBER);
                    }
                });
                Map<String, Object> pickList = db.pickList(ORDER_NUMBER).orElseThrow();
                assertEquals(PICK_LIST_FILE, pickList.get("file_name"));
            });

            run.execute("S4", () -> {
                awaitArrival("pick-list-on-warehouse-server", () -> {
                    if (inbox.count(PICK_LIST_FILE) == 0) {
                        throw new CitrusRuntimeException(PICK_LIST_FILE + " not yet on the Warehouse SFTP Server");
                    }
                });
                Duration elapsed = Duration.between(publishedAt, Instant.now());
                assertTrue(elapsed.compareTo(PICK_LIST_LIMIT) < 0,
                    PICK_LIST_FILE + " arrived " + elapsed + " after step 1, limit " + PICK_LIST_LIMIT);
                LOG.info("{}: {} on the Warehouse SFTP Server {} ms after step 1", TC_ID, PICK_LIST_FILE,
                    elapsed.toMillis());
                // Header and two rows as in the example: street line "2240 Pearl Street, Apt 4", total weight 2390
                inbox.assertEqualsExample(PICK_LIST_FILE, docsFile(PICK_LIST_EXAMPLE));
                List<List<String>> rows = WarehouseInbox.parse(inbox.read(PICK_LIST_FILE));
                assertEquals(3, rows.size(), PICK_LIST_FILE + ": a header and two rows");
                int street = WarehouseInbox.COLUMNS.indexOf("ship_street");
                int total = WarehouseInbox.COLUMNS.indexOf("total_weight_g");
                for (List<String> row : rows.subList(1, rows.size())) {
                    assertEquals("2240 Pearl Street, Apt 4", row.get(street), PICK_LIST_FILE + " street line");
                    assertEquals("2390", row.get(total), PICK_LIST_FILE + " Total Weight");
                }
            });

            run.execute("S5", () -> {
                // The same Order, the same content, under the new event id the document names
                JsonNode first = readJson(docsFile(ORDER_EVENT_EXAMPLE));
                JsonNode again = readJson(docsFile(REDELIVERED_EXAMPLE));
                assertEquals(REDELIVERY_EVENT_ID, again.path("event_id").asText(), "event id of the redelivery");
                assertNotEquals(first.path("event_id").asText(), again.path("event_id").asText(),
                    "The redelivery must carry a new event id");
                assertEquals(withoutEventId(first), withoutEventId(again),
                    "The redelivery must carry the same Order content as step 1");
                runner.run(peakmart.publish(docsFile(REDELIVERED_EXAMPLE)));
            });

            run.execute("S6", () -> {
                // First prove the integration has handled the redelivered event: its consumer group committed every
                // record on the topic (the intake route commits each record as its last step)
                long endAfterRedelivery = peakmart.endOffsets();
                awaitArrival("redelivery-consumed", () -> {
                    long committed = peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP);
                    if (committed < endAfterRedelivery) {
                        throw new CitrusRuntimeException(Peakmart.INTEGRATION_GROUP + " committed " + committed
                            + " of " + endAfterRedelivery + " records on " + Peakmart.TOPIC);
                    }
                });
                LOG.info("{}: {} committed all {} records of {} after step 5", TC_ID, Peakmart.INTEGRATION_GROUP,
                    endAfterRedelivery, Peakmart.TOPIC);
                // "15 minutes after step 5": several cycles of the 1 s pollers (A-10)
                runner.run(sleep().milliseconds(SpecEnvironment.get().millis("spec-test.quiet-period-ms")));
                assertEquals(1, inbox.count(PICK_LIST_FILE),
                    "Exactly one " + PICK_LIST_FILE + " across /inbound/picklists and /inbound/picklists/processed");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is on the server");
            });

            run.execute("V1", () -> {
                assertEquals(1, inbox.count(PICK_LIST_FILE),
                    "Exactly one " + PICK_LIST_FILE + " on the Warehouse SFTP Server");
                // A single pick_list row (OrderDatabase.pickList fails on more than one), delivered once
                Map<String, Object> pickList = db.pickList(ORDER_NUMBER)
                    .orElseThrow(() -> new AssertionError("No pick_list row for " + ORDER_NUMBER));
                assertEquals("Delivered", pickList.get("delivery_state"), "Delivery state of " + PICK_LIST_FILE);
                assertEquals(1L, ((Number) db.rows("SELECT count(*) AS n FROM pick_list WHERE file_name = ?",
                    PICK_LIST_FILE).get(0).get("n")).longValue(), "pick_list rows named " + PICK_LIST_FILE);
            });

            run.execute("V3a", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                runner.run(env.mailboxes().expectNone(Mailboxes.ORDER_DESK,
                    Mailboxes.orderDeskSubject(ORDER_NUMBER), env.millis("spec-test.quiet-period-ms")));
                // The mail check matches the exact subject; no notification of any kind is queued for PM-50117 either
                assertTrue(db.notifications(ORDER_NUMBER).isEmpty(), "A notification is queued for " + ORDER_NUMBER);
            });

            run.execute("P1", () -> {
                List<Map<String, Object>> receipts = db.receipts(ORDER_NUMBER);
                assertEquals(1, receipts.size(), "order_receipt rows of " + ORDER_NUMBER);
                assertEquals("Peakmart", receipts.get(0).get("channel"));
                assertTrue(ACCEPTED_STATES.contains(db.receiptState(ORDER_NUMBER)),
                    ORDER_NUMBER + " is not an Accepted Order: " + db.receiptState(ORDER_NUMBER));
                Map<String, Object> order = db.customerOrder(ORDER_NUMBER)
                    .orElseThrow(() -> new AssertionError("No customer_order " + ORDER_NUMBER));
                assertEquals("Peakmart", order.get("channel"));
                assertNull(order.get("partner_code"), ORDER_NUMBER + " is not a Partner Order");
                assertEquals("Jordan Reyes", order.get("recipient_name"));
                assertEquals("2240 Pearl Street, Apt 4", order.get("street_line"));
                assertEquals("Boulder", order.get("city"));
                assertEquals("CO", order.get("state_region"));
                assertEquals("80302", order.get("postal_code"));
                assertEquals("US", order.get("country"));
                // order_line has no currency column: the Peakmart Prices are stated in USD (price_usd)
                assertPeakmartLines();
            });

            run.execute("P2", () -> assertEquals(List.of("Received", "Recorded", "Sent to Warehouse"),
                db.stateHistory(ORDER_NUMBER),
                "Order History of " + ORDER_NUMBER + ": received, recorded, sent to warehouse, each once, in order"));

            run.execute("P3", () -> assertTrue(inbox.count(PICK_LIST_FILE) >= 1,
                PICK_LIST_FILE + " is in neither /inbound/picklists nor /inbound/picklists/processed"));

            run.execute("P4", () -> {
                long offsetsAfter = peakmart.endOffsets();
                assertEquals(offsetsBefore + RECORDS_PUBLISHED, offsetsAfter, Peakmart.TOPIC
                    + " must grow by exactly the 2 Order Events of the test (before " + offsetsBefore + ")");
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
                // Order History with (before) its Order, in one transaction
                database.cleanupNow(ORDER_NUMBER);
                warehouse.remove(PICK_LIST_FILE);
                assertTrue(database.receipts(ORDER_NUMBER).isEmpty(), ORDER_NUMBER + " is still in the Order Database");
                assertEquals(0, warehouse.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the server");
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

    /** The Order Lines keep the Peakmart Prices; name and weight are completed from the Product Catalog. */
    private void assertPeakmartLines() {
        List<Map<String, Object>> lines = db.orderLines(ORDER_NUMBER);
        assertEquals(2, lines.size(), "Order Lines of " + ORDER_NUMBER);
        assertLine(lines.get(0), 1, "TENT-2P", "Ridgeline 2-Person Tent", 1, "229.00", 2150);
        assertLine(lines.get(1), 2, "STAKE-AL8", "Aluminium Tent Stakes 8-Pack", 2, "12.50", 120);
    }

    private static void assertLine(Map<String, Object> line, int lineNumber, String sku, String productName,
                                   int quantity, String unitPrice, int unitWeightGrams) {
        String where = ORDER_NUMBER + " line " + lineNumber;
        assertEquals(lineNumber, ((Number) line.get("line_number")).intValue(), where + " line_number");
        assertEquals(sku, line.get("sku"), where + " sku");
        assertEquals(productName, line.get("product_name"), where + " product name");
        assertEquals(quantity, ((Number) line.get("quantity")).intValue(), where + " quantity");
        assertEquals(0, new BigDecimal(unitPrice).compareTo((BigDecimal) line.get("unit_price")),
            where + " Peakmart Price " + line.get("unit_price") + ", expected " + unitPrice);
        assertEquals(unitWeightGrams, ((Number) line.get("unit_weight_grams")).intValue(), where + " weight");
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

    private static JsonNode withoutEventId(JsonNode event) {
        ObjectNode copy = event.deepCopy();
        copy.remove("event_id");
        return copy;
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
