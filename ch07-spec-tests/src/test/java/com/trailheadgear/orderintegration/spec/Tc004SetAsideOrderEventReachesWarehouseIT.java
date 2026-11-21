package com.trailheadgear.orderintegration.spec;

import static org.citrusframework.actions.SleepAction.Builder.sleep;
import static org.citrusframework.container.Wait.Builder.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
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
 * TC-004 Set-Aside Order Event Reaches Warehouse ({@code docs/test_cases/TC-004-set-aside-order-event-reaches-warehouse.md},
 * design 004 §3.7).
 *
 * <p>Peakmart publishes the Order Event {@code 1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f} for {@code PM-50119} whose
 * Customer email is not an email address; the System sets it aside. The Alert (UC-006) and the resubmission (UC-008)
 * are not built: every step, Validation and Postcondition that needs them is declared not yet testable and is never
 * executed or faked. What runs: the set-aside outcome of step 1, read from {@code set_aside_order_event} because no
 * outside view of set-aside events exists yet (A-14); the "No Pick List {@code PL-PM-50119.csv} exists" sentence of
 * step 3 after the quiet period ({@code S3}; the Alert part of step 3 is the separate item {@code S3-alert}); and
 * Validation 4 from a Peakmart topic offset snapshot.
 *
 * <p>Names as the Order Database stores them (Flyway V1 {@code set_aside_order_event}): the event id is
 * {@code event_id}, the Order Number {@code order_number}, the reason the event cannot be read
 * {@code cannot_read_reason}, the time it arrived {@code arrived_at}, and the set-aside state {@code set_aside_state}
 * with the check-constraint values {@code 'Set Aside'} / {@code 'Resubmitted'}. The reason is the JSON pointer of the
 * failing field and the schema keyword, {@code $.buyer.email: format}, asserted exactly.
 *
 * <p>Precondition "the Integration Team mailbox … holds no Alert": the Integration Team address is not configured
 * anywhere until UC-006 (design §3.3 {@code Mailboxes}), so there is no mailbox to look into; nothing is checked or
 * faked for it. Precondition "a corrected resubmission is allowed (OQ-062)" is an assumption about UC-008 and needs no
 * action of the test.
 */
@SpecTestCase("TC-004")
@CitrusSupport
class Tc004SetAsideOrderEventReachesWarehouseIT {

    private static final Logger LOG = LoggerFactory.getLogger(Tc004SetAsideOrderEventReachesWarehouseIT.class);

    private static final String TC_ID = "TC-004";
    private static final String DISPLAY_NAME = "TC-004 Set-Aside Order Event Reaches Warehouse";
    private static final String ORDER_NUMBER = "PM-50119";
    private static final String PICK_LIST_FILE = "PL-PM-50119.csv";
    private static final String ORDER_EVENT_EXAMPLE =
        "messages/examples/peakmart-order-placed/invalid/pm-50119-buyer-email-not-an-email.json";
    /** The event id the document names for step 1. */
    private static final String EVENT_ID = "1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f";
    /** The Customer email of step 1 that is not an email address. */
    private static final String INVALID_EMAIL = "sam.okafor-at-example.com";
    /** The reason the Order Event cannot be read, as stored in {@code cannot_read_reason} (design §3.7). */
    private static final String CANNOT_READ_REASON = "$.buyer.email: format";
    /** {@code set_aside_state} of a kept Set-Aside Order Event (Flyway V1 check constraint). */
    private static final String SET_ASIDE_STATE = "Set Aside";
    /** Validation 4: the topic grows by the one Order Event of the test and nothing else. */
    private static final long RECORDS_PUBLISHED = 1L;
    private static final long WAIT_INTERVAL_MS = 1000L;

    public static final List<SpecRun.Item> ITEMS = List.of(
        SpecRun.run("C-setup", "Preconditions established by the test"),
        SpecRun.notYetTestable("C-resubmit", "An Integration Team member is authorised to resubmit Set-Aside Order "
            + "Events, with a bearer token issued by the test environment (OQ-067)", UseCase.UC_008),
        SpecRun.run("S1", "Publish unreadable event"),
        SpecRun.notYetTestable("S2", "Alert integration team", UseCase.UC_006),
        SpecRun.run("S3", "Verify set-aside alert: No Pick List PL-PM-50119.csv exists"),
        SpecRun.notYetTestable("S3-alert", "Verify set-aside alert: the Integration Team mailbox receives one Alert "
            + "of kind order-event-set-aside", UseCase.UC_006),
        SpecRun.notYetTestable("S4", "Resubmit corrected event", UseCase.UC_008),
        SpecRun.notYetTestable("S5", "Verify event taken in", UseCase.UC_008),
        SpecRun.notYetTestable("S6", "Check order", UseCase.UC_008),
        SpecRun.notYetTestable("S7", "Deliver pick list", UseCase.UC_008),
        SpecRun.notYetTestable("S8", "Verify pick list", UseCase.UC_008),
        SpecRun.notYetTestable("V1", "One Pick List", UseCase.UC_008),
        SpecRun.notYetTestable("V2", "No longer set aside", UseCase.UC_008),
        SpecRun.notYetTestable("V3", "One Alert", UseCase.UC_006),
        SpecRun.run("V4", "Nothing to Peakmart"),
        SpecRun.notYetTestable("P1", "One Accepted Order PM-50119 of the Channel peakmart is recorded in the Order "
            + "Database, with the Order States received, recorded and sent to warehouse in its Order History",
            UseCase.UC_008),
        SpecRun.notYetTestable("P2", "The Order Event 1f4e9a7c-2b3d-4c5e-8f60-7a8b9c0d1e2f is no longer a Set-Aside "
            + "Order Event; the integration keeps who resubmitted it and when", UseCase.UC_008),
        SpecRun.notYetTestable("P3", "One Alert of kind order-event-set-aside for that event id is kept and lies in "
            + "the Integration Team mailbox of the test environment", UseCase.UC_006),
        SpecRun.notYetTestable("P4", "The file PL-PM-50119.csv lies on the Warehouse SFTP Server, in "
            + "/inbound/picklists or /inbound/picklists/processed", UseCase.UC_008),
        SpecRun.run("P-cleanup", "Cleanup order"));

    @CitrusResource
    private TestCaseRunner runner;

    private OrderDatabase db;
    private WarehouseInbox inbox;
    private Peakmart peakmart;
    private long offsetsBefore;
    private boolean consumed;

    @Test
    @DisplayName(DISPLAY_NAME)
    void setAsideOrderEventReachesWarehouse(TestReporter testReporter) throws Throwable {
        SpecRun run = new SpecRun(TC_ID, getClass(), DISPLAY_NAME, ITEMS, testReporter);
        IntegrationProcess integration = new IntegrationProcess(TC_ID);
        Throwable primary = null;
        try {
            run.execute("C-setup", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                LOG.info("{}: images {}", TC_ID, env.imageReferences());
                db = new OrderDatabase();
                inbox = new WarehouseInbox();
                // The integration runs Flyway itself: the tables exist only once it has started. Nothing for PM-50119
                // is published before S1, so cleaning up after the start establishes the precondition. The
                // integration reads the Peakmart order topic (created by SpecEnvironment).
                integration.start();
                peakmart = new Peakmart();
                // No Set-Aside Order Event 1f4e9a7c-… is kept, and no Order PM-50119 has been received
                runner.run(db.cleanupSetAside(EVENT_ID));
                runner.run(db.cleanup(ORDER_NUMBER));
                assertTrue(db.setAsideEvent(EVENT_ID).isEmpty(),
                    "A set_aside_order_event " + EVENT_ID + " is left after the cleanup");
                assertTrue(db.receipts(ORDER_NUMBER).isEmpty(), "An order_receipt PM-50119 is left after the cleanup");
                assertTrue(db.customerOrder(ORDER_NUMBER).isEmpty(),
                    "A customer_order PM-50119 is left after the cleanup");
                // The Warehouse SFTP Server holds no PL-PM-50119.csv (or .tmp) in either folder
                inbox.remove(PICK_LIST_FILE);
                assertEquals(0, inbox.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the Warehouse SFTP Server");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is still on the server");
                // The Product Catalog answers tent-2p.json (stub default); only this run's requests are counted
                env.catalog().clearRequests();
                // OQ-062 (a corrected resubmission is allowed) is an assumption about UC-008: nothing to set up
                // Snapshot of the topic for V4 ("the integration published nothing on the Peakmart order topic")
                offsetsBefore = peakmart.endOffsets();
                LOG.info("{}: {} end offsets before step 1: {}", TC_ID, Peakmart.TOPIC, offsetsBefore);
            });

            run.execute("S1", () -> {
                JsonNode event = readJson(docsFile(ORDER_EVENT_EXAMPLE));
                assertEquals("order.placed", event.path("event_type").asText(), "event type of the step 1 example");
                assertEquals(EVENT_ID, event.path("event_id").asText(), "event id of the step 1 example");
                assertEquals(ORDER_NUMBER, event.path("order_ref").asText(), "order_ref of the step 1 example");
                assertEquals(INVALID_EMAIL, event.path("buyer").path("email").asText(),
                    "Customer email of the step 1 example");
                // The example file unchanged, byte for byte
                runner.run(peakmart.publish(docsFile(ORDER_EVENT_EXAMPLE)));

                // "The System sets it aside" (A-14): the Order Event is consumed asynchronously
                AtomicReference<Map<String, Object>> setAside = new AtomicReference<>();
                awaitArrival("order-event-set-aside", () -> {
                    Optional<Map<String, Object>> row = db.setAsideEvent(EVENT_ID);
                    if (row.isEmpty()) {
                        throw new CitrusRuntimeException("Order Event " + EVENT_ID + " not yet set aside");
                    }
                    setAside.set(row.get());
                });
                Map<String, Object> row = setAside.get();
                LOG.info("{}: set_aside_order_event {}", TC_ID, row);
                List<Map<String, Object>> rows = db.rows(
                    "SELECT count(*) AS n FROM set_aside_order_event WHERE event_id = ?", EVENT_ID);
                assertEquals(1L, ((Number) rows.get(0).get("n")).longValue(),
                    "set_aside_order_event rows with event id " + EVENT_ID);
                assertEquals(EVENT_ID, row.get("event_id"), "event id of the Set-Aside Order Event");
                assertEquals(ORDER_NUMBER, row.get("order_number"), "Order Number of the Set-Aside Order Event");
                assertEquals(CANNOT_READ_REASON, row.get("cannot_read_reason"),
                    "reason the Order Event cannot be read");
                assertEquals(SET_ASIDE_STATE, row.get("set_aside_state"), "state of the Set-Aside Order Event");
                assertNotNull(row.get("arrived_at"), "The Set-Aside Order Event keeps no arrival time");
                // Set aside, not taken in: no order_receipt for PM-50119
                var receipts = db.receipts(ORDER_NUMBER);
                assertTrue(receipts.isEmpty(), () -> "An order_receipt exists for " + ORDER_NUMBER + ": " + receipts);
            });

            run.execute("S3", () -> {
                awaitConsumed();
                // "No Pick List exists": several cycles of the 1 s pollers (A-10)
                runner.run(sleep().milliseconds(SpecEnvironment.get().millis("spec-test.quiet-period-ms")));
                assertEquals(0, inbox.count(PICK_LIST_FILE),
                    PICK_LIST_FILE + " is in /inbound/picklists or /inbound/picklists/processed");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is on the server");
            });

            run.execute("V4", () -> {
                awaitConsumed();
                long offsetsAfter = peakmart.endOffsets();
                assertEquals(offsetsBefore + RECORDS_PUBLISHED, offsetsAfter, Peakmart.TOPIC
                    + " must grow by exactly the 1 Order Event of the test (before " + offsetsBefore + ")");
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
                // There are no resubmission or Alert records yet (UC-006, UC-008 not built): the Set-Aside Order
                // Event record is the first to go. PM-50119 is cleaned up too, for safety (Order History with or
                // before its Order, in one transaction).
                database.cleanupSetAsideNow(EVENT_ID);
                database.cleanupNow(ORDER_NUMBER);
                warehouse.remove(PICK_LIST_FILE);
                assertTrue(database.setAsideEvent(EVENT_ID).isEmpty(),
                    "The Set-Aside Order Event " + EVENT_ID + " is still kept");
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
