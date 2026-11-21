package com.trailheadgear.orderintegration.spec;

import static org.citrusframework.actions.SleepAction.Builder.sleep;
import static org.citrusframework.container.Wait.Builder.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.Mailboxes;
import com.trailheadgear.orderintegration.spec.support.OrderDatabase;
import com.trailheadgear.orderintegration.spec.support.Partner;
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
 * TC-001 Partner Order to Warehouse ({@code docs/test_cases/TC-001-partner-order-to-warehouse.md}, design 004 §3.4).
 *
 * <p>Summit Outfitters submits {@code TG-1060} over HTTP, the Product Catalog stub answers both SKUs, and the Pick
 * List must reach the Warehouse SFTP Server once. The order lookup (UC-005) and the alerts (UC-006) are not built: the
 * items that need them are declared not yet testable and are never executed (A-15). The Order Database
 * Postconditions are checked with SQL.
 *
 * <p>Order States: the document writes them in running text ("received", "recorded", "sent to warehouse"); the
 * Order Database stores the entity-model names {@code Received}, {@code Recorded}, {@code Sent to Warehouse}
 * (Flyway V1 check constraint). P2 asserts the stored names, which are the document's terms with capitalised words.
 */
@SpecTestCase("TC-001")
@CitrusSupport
class Tc001PartnerOrderToWarehouseIT {

    private static final Logger LOG = LoggerFactory.getLogger(Tc001PartnerOrderToWarehouseIT.class);

    private static final String TC_ID = "TC-001";
    private static final String DISPLAY_NAME = "TC-001 Partner Order to Warehouse";
    private static final String ORDER_NUMBER = "TG-1060";
    private static final String PICK_LIST_FILE = "PL-TG-1060.csv";
    private static final String PARTNER_CODE = "summit-outfitters";
    private static final String PICK_LIST_EXAMPLE = "messages/examples/pick-list/pl-tg-1060.json";
    /** The test case's own limit for the Pick List (step 5), asserted next to the much shorter wait bound (A-9). */
    private static final Duration PICK_LIST_LIMIT = Duration.ofMinutes(15);
    /** Poll interval of the pick-list wait: longer than one SFTP check, so a check is never cut off. */
    private static final long WAIT_INTERVAL_MS = 2000L;
    private static final Set<String> ACCEPTED_STATES = Set.of("Recorded", "Sent to Warehouse");

    public static final List<SpecRun.Item> ITEMS = List.of(
        SpecRun.run("C-setup", "Preconditions established by the test"),
        SpecRun.notYetTestable("C-helpdesk", "The Helpdesk Tool is set up to call the order lookup with its own key "
            + "from the test environment's Secret Store (OQ-066)", UseCase.UC_005),
        SpecRun.run("S1", "Submit partner order"),
        SpecRun.run("S2", "Check order"),
        SpecRun.run("S3", "Verify order accepted"),
        SpecRun.run("S4", "Deliver pick list"),
        SpecRun.run("S5", "Verify pick list"),
        SpecRun.notYetTestable("S6", "Look up order", UseCase.UC_005),
        SpecRun.notYetTestable("S7", "Verify order history", UseCase.UC_005),
        SpecRun.run("V1", "One Pick List"),
        SpecRun.notYetTestable("V2", "Final Order State", UseCase.UC_005),
        SpecRun.run("V3", "No rejection"),
        SpecRun.notYetTestable("V4", "No Alert", UseCase.UC_006),
        SpecRun.run("P1", "One Accepted Order TG-1060 of Partner summit-outfitters is recorded in the Order Database"),
        SpecRun.run("P2", "The Order History of TG-1060 holds the Order States received, recorded and sent to warehouse"),
        SpecRun.run("P3", "The file PL-TG-1060.csv lies on the Warehouse SFTP Server, in /inbound/picklists or "
            + "/inbound/picklists/processed"),
        SpecRun.run("P-cleanup", "Cleanup order"));

    @CitrusResource
    private TestCaseRunner runner;

    private OrderDatabase db;
    private WarehouseInbox inbox;
    private Partner partner;
    private Instant submittedAt;

    @Test
    @DisplayName(DISPLAY_NAME)
    void partnerOrderToWarehouse(TestReporter testReporter) throws Throwable {
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
                // Order TG-1060 is submitted before S1, so cleaning up after the start establishes the precondition.
                integration.start();
                partner = new Partner(integration.serverPort());
                // No Order TG-1060 in the Order Database (same statements as P-cleanup), then no file left behind
                runner.run(db.cleanup(ORDER_NUMBER));
                assertTrue(db.receipts(ORDER_NUMBER).isEmpty(), "An order_receipt TG-1060 is left after the cleanup");
                assertTrue(db.customerOrder(ORDER_NUMBER).isEmpty(), "A customer_order TG-1060 is left after the cleanup");
                // No PL-TG-1060.csv in /inbound/picklists or /inbound/picklists/processed
                inbox.remove(PICK_LIST_FILE);
                assertEquals(0, inbox.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the Warehouse SFTP Server");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is still on the server");
                // Partner Configuration released and its API Key in the Secret Store
                assertTrue(Files.isRegularFile(env.partnerConfigurationFile()), "Partner Configuration missing");
                assertTrue(Files.isRegularFile(env.partnerApiKeyFile()), "Secret Store holds no partner API Key");
                // The Product Catalog answers the two Active Products; only this run's requests are counted
                env.catalog().clearRequests();
                // The released Partner Configuration is registered (UC-007, done by the integration at start)
                assertEquals(1, db.rows("SELECT partner_code FROM partner WHERE partner_code = ? "
                    + "AND deregistered_at IS NULL", PARTNER_CODE).size(), "Partner summit-outfitters not registered");
            });

            run.execute("S1", () -> {
                submittedAt = Instant.now();
                runner.run(partner.submitOrder());
                assertEquals(1, partner.requestsSent(), "Exactly one partner request is sent");
            });

            run.execute("S2", () -> {
                // Exactly these two requests, each once; the document states no lookup order
                assertEquals(List.of("LAMP-HD200", "TENT-2P"),
                    SpecEnvironment.get().catalog().requestedSkus().stream().sorted().toList(),
                    "The Product Catalog is asked for exactly TENT-2P and LAMP-HD200, each once");
                List<Map<String, Object>> lines = db.orderLines(ORDER_NUMBER);
                assertEquals(2, lines.size(), "Order Lines of " + ORDER_NUMBER);
                assertLine(lines.get(0), 1, "TENT-2P", "Ridgeline 2-Person Tent", 1, "249.00", 2150);
                assertLine(lines.get(1), 2, "LAMP-HD200", "HD200 Headlamp", 2, "39.95", 95);
            });

            run.execute("S3", () -> runner.run(partner.expectAccepted(ORDER_NUMBER)));

            run.execute("S4", () -> {
                // The Accepted Order queued its Pick List; whether it is delivered is checked in S5
                Map<String, Object> pickList = db.pickList(ORDER_NUMBER)
                    .orElseThrow(() -> new AssertionError("No Pick List queued for " + ORDER_NUMBER));
                assertEquals(PICK_LIST_FILE, pickList.get("file_name"));
            });

            run.execute("S5", () -> {
                long arrivalMs = SpecEnvironment.get().millis("spec-test.arrival-timeout-ms");
                runner.run(waitFor().execution()
                    .milliseconds(arrivalMs)
                    .interval(WAIT_INTERVAL_MS)
                    .action(check("pick-list-on-warehouse-server", () -> {
                        if (inbox.count(PICK_LIST_FILE) == 0) {
                            throw new CitrusRuntimeException(PICK_LIST_FILE + " not yet on the Warehouse SFTP Server");
                        }
                    })));
                Duration elapsed = Duration.between(submittedAt, Instant.now());
                assertTrue(elapsed.compareTo(PICK_LIST_LIMIT) < 0,
                    PICK_LIST_FILE + " arrived " + elapsed + " after step 1, limit " + PICK_LIST_LIMIT);
                LOG.info("{}: {} on the Warehouse SFTP Server {} ms after step 1", TC_ID, PICK_LIST_FILE,
                    elapsed.toMillis());
                // Header and two rows as in the example (total_weight_g 2340 on both rows)
                inbox.assertEqualsExample(PICK_LIST_FILE, docsFile(PICK_LIST_EXAMPLE));
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is left on the server");
            });

            run.execute("V1", () -> {
                // "Nothing more happens": several cycles of the 1 s pollers (A-10)
                runner.run(sleep().milliseconds(SpecEnvironment.get().millis("spec-test.quiet-period-ms")));
                assertEquals(1, inbox.count(PICK_LIST_FILE),
                    "Exactly one " + PICK_LIST_FILE + " across /inbound/picklists and /inbound/picklists/processed");
            });

            run.execute("V3", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                runner.run(env.mailboxes().expectNone(Mailboxes.PARTNER_CONTACT,
                    Mailboxes.rejectionEmailSubject(ORDER_NUMBER), env.millis("spec-test.quiet-period-ms")));
                assertTrue(db.notifications(ORDER_NUMBER).isEmpty(), "A notification is queued for " + ORDER_NUMBER);
            });

            run.execute("P1", () -> {
                List<Map<String, Object>> receipts = db.receipts(ORDER_NUMBER);
                assertEquals(1, receipts.size(), "order_receipt rows of " + ORDER_NUMBER);
                assertEquals("Partner", receipts.get(0).get("channel"));
                assertTrue(ACCEPTED_STATES.contains(db.receiptState(ORDER_NUMBER)),
                    ORDER_NUMBER + " is not an Accepted Order: " + db.receiptState(ORDER_NUMBER));
                Map<String, Object> order = db.customerOrder(ORDER_NUMBER)
                    .orElseThrow(() -> new AssertionError("No customer_order " + ORDER_NUMBER));
                assertEquals(PARTNER_CODE, order.get("partner_code"));
                assertEquals("Lee Martin", order.get("recipient_name"));
                assertEquals("18 Canyon Road", order.get("street_line"));
                assertEquals("Moab", order.get("city"));
                assertEquals("84532", order.get("postal_code"));
                assertEquals("US", order.get("country"));
                // order_line has no currency column: the prices are the Catalog Prices, which the catalog states in USD
                List<Map<String, Object>> lines = db.orderLines(ORDER_NUMBER);
                assertEquals(2, lines.size(), "Order Lines of " + ORDER_NUMBER);
                assertLine(lines.get(0), 1, "TENT-2P", "Ridgeline 2-Person Tent", 1, "249.00", 2150);
                assertLine(lines.get(1), 2, "LAMP-HD200", "HD200 Headlamp", 2, "39.95", 95);
            });

            run.execute("P2", () -> assertEquals(List.of("Received", "Recorded", "Sent to Warehouse"),
                db.stateHistory(ORDER_NUMBER),
                "Order History of " + ORDER_NUMBER + ": received, recorded, sent to warehouse, each once, in order"));

            run.execute("P3", () -> assertTrue(inbox.count(PICK_LIST_FILE) >= 1,
                PICK_LIST_FILE + " is in neither /inbound/picklists nor /inbound/picklists/processed"));

            run.bodyCompleted();
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            primary = cleanUp(run, integration, primary);
        }
    }

    /** P-cleanup, the integration stop and the result file; a later failure never hides an earlier one. */
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
                // The Partner Configuration stays untouched (the Product Catalog data is read-only example files)
                assertEquals(1, database.rows("SELECT partner_code FROM partner WHERE partner_code = ?", PARTNER_CODE)
                    .size(), "Partner " + PARTNER_CODE + " was touched by the cleanup");
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

    private static void assertLine(Map<String, Object> line, int lineNumber, String sku, String productName,
                                   int quantity, String unitPrice, int unitWeightGrams) {
        String where = ORDER_NUMBER + " line " + lineNumber;
        assertEquals(lineNumber, ((Number) line.get("line_number")).intValue(), where + " line_number");
        assertEquals(sku, line.get("sku"), where + " sku");
        assertEquals(productName, line.get("product_name"), where + " product name");
        assertEquals(quantity, ((Number) line.get("quantity")).intValue(), where + " quantity");
        assertEquals(0, new BigDecimal(unitPrice).compareTo((BigDecimal) line.get("unit_price")),
            where + " Catalog Price " + line.get("unit_price") + ", expected " + unitPrice);
        assertEquals(unitWeightGrams, ((Number) line.get("unit_weight_grams")).intValue(), where + " weight");
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
