package com.trailheadgear.orderintegration.spec;

import static org.citrusframework.actions.SleepAction.Builder.sleep;
import static org.citrusframework.container.Wait.Builder.waitFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.trailheadgear.orderintegration.spec.support.FreePorts;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
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
import org.testcontainers.containers.GenericContainer;

/**
 * TC-005 Pick List Once After Restart ({@code docs/test_cases/TC-005-pick-list-once-after-restart.md}, design 004
 * §3.8).
 *
 * <p>The Warehouse SFTP Server is unavailable when the journey starts (A-12): the test picks a free port P on
 * 127.0.0.1, writes a known_hosts file for {@code [127.0.0.1]:P} with the run's host keys, and configures the
 * integration with P; nothing listens there, so every delivery attempt is refused and the Pick List is held (UC-004
 * A1). After the Order is recorded the integration is stopped gracefully (SIGTERM, A-13) and started again with the
 * same run directory, ports and settings; only then is this test's own {@code atmoz/sftp} container started bound to
 * {@code 127.0.0.1:P}, with the run's host keys and password. The same configured server becomes available, without a
 * proxy, a wrong address or a port change. The Maintenance Window is not involved: the test does not depend on the
 * clock (A-11).
 *
 * <p>Step 4 is split: its data part ({@code S4}) is run against the Order Database, its lookup part
 * ({@code S4-lookup}) needs UC-005 (A-15). Order States are asserted with the names the Order Database stores (Flyway
 * V1: {@code Received}, {@code Recorded}, {@code Sent to Warehouse}); the Pick List delivery states are those of
 * Flyway V2 ({@code Pending}, {@code Delivered}).
 */
@SpecTestCase("TC-005")
@CitrusSupport
class Tc005PickListOnceAfterRestartIT {

    private static final Logger LOG = LoggerFactory.getLogger(Tc005PickListOnceAfterRestartIT.class);

    private static final String TC_ID = "TC-005";
    private static final String DISPLAY_NAME = "TC-005 Pick List Once After Restart";
    private static final String ORDER_NUMBER = "TG-1060";
    private static final String PICK_LIST_FILE = "PL-TG-1060.csv";
    private static final String PARTNER_CODE = "summit-outfitters";
    private static final String PICK_LIST_EXAMPLE = "messages/examples/pick-list/pl-tg-1060.json";
    private static final String LOOPBACK = "127.0.0.1";
    private static final int SFTP_CONTAINER_PORT = 22;
    private static final int REFUSED_CHECK_TIMEOUT_MS = 2000;
    /** NFR-001 as the test case times it (step 7), asserted next to the much shorter wait bound (A-9). */
    private static final Duration PICK_LIST_LIMIT = Duration.ofMinutes(15);
    /** Poll interval of the waits: longer than one SFTP check, so a check is never cut off. */
    private static final long WAIT_INTERVAL_MS = 2000L;
    private static final Set<String> ACCEPTED_STATES = Set.of("Recorded", "Sent to Warehouse");
    private static final String PENDING = "Pending";
    private static final String DELIVERED = "Delivered";

    public static final List<SpecRun.Item> ITEMS = List.of(
        SpecRun.run("C-setup", "Preconditions established by the test"),
        SpecRun.notYetTestable("C-helpdesk", "The Helpdesk Tool is set up to call the order lookup with its own key "
            + "from the test environment's Secret Store (OQ-066)", UseCase.UC_005),
        SpecRun.run("S1", "Submit partner order"),
        SpecRun.run("S2", "Check order"),
        SpecRun.run("S3", "Verify order accepted"),
        SpecRun.run("S4", "Verify order recorded: the Order State is recorded, the Order History received, recorded, "
            + "and the Warehouse SFTP Server holds no PL-TG-1060.csv (Order Database and server side)"),
        SpecRun.notYetTestable("S4-lookup", "Verify order recorded: an Order Desk member looks up TG-1060",
            UseCase.UC_005),
        SpecRun.run("S5", "Restart integration"),
        SpecRun.run("S6", "Deliver pick list"),
        SpecRun.run("S7", "Verify pick list"),
        SpecRun.notYetTestable("S8", "Look up order", UseCase.UC_005),
        SpecRun.notYetTestable("S9", "Verify order history", UseCase.UC_005),
        SpecRun.run("V1", "Delivered exactly once"),
        SpecRun.notYetTestable("V2", "Recorded once", UseCase.UC_005),
        SpecRun.run("V3", "Not lost by the restart"),
        SpecRun.run("P1", "One Accepted Order TG-1060 of Partner summit-outfitters is recorded in the Order Database"),
        SpecRun.run("P2", "The Order History of TG-1060 holds the Order States received, recorded and sent to warehouse"),
        SpecRun.run("P3", "The file PL-TG-1060.csv lies on the Warehouse SFTP Server, in /inbound/picklists or "
            + "/inbound/picklists/processed"),
        SpecRun.run("P4", "The Warehouse SFTP Server of the test environment is left available"),
        SpecRun.run("P-cleanup", "Cleanup order"));

    @CitrusResource
    private TestCaseRunner runner;

    private OrderDatabase db;
    private Partner partner;
    /** The Warehouse SFTP port P the integration is configured with; nothing listens on it until S5. */
    private int sftpPort;
    private Path knownHostsFile;
    /** This test's own Warehouse SFTP Server on 127.0.0.1:P, started in S5; Testcontainers/Ryuk removes it. */
    private GenericContainer<?> warehouseServer;
    private WarehouseInbox inbox;
    private Instant submittedAt;
    private Instant serverAvailableAt;

    @Test
    @DisplayName(DISPLAY_NAME)
    void pickListOnceAfterRestart(TestReporter testReporter) throws Throwable {
        SpecRun run = new SpecRun(TC_ID, getClass(), DISPLAY_NAME, ITEMS, testReporter);
        IntegrationProcess integration = new IntegrationProcess(TC_ID);
        Throwable primary = null;
        try {
            run.execute("C-setup", () -> {
                SpecEnvironment env = SpecEnvironment.get();
                LOG.info("{}: images {}", TC_ID, env.imageReferences());
                db = new OrderDatabase();
                // The Warehouse SFTP Server is unavailable: nothing listens on the configured port P (A-12)
                sftpPort = FreePorts.next();
                assertTrue(sftpPort != env.sftpPort(), "P must not be the environment's SFTP port");
                assertRefused(sftpPort, "before the integration starts");
                // The run's host keys for [127.0.0.1]:P, in a file of its own (the environment's file names its port)
                knownHostsFile = env.workDir().resolve("sftp-tc-005").resolve("known_hosts").toAbsolutePath()
                    .normalize();
                env.writeKnownHosts(sftpPort, knownHostsFile);
                assertTrue(Files.isRegularFile(knownHostsFile), "known_hosts for P not written");
                inbox = new WarehouseInbox(LOOPBACK, sftpPort, knownHostsFile);
                // The integration runs Flyway itself; no Order TG-1060 is submitted before S1
                integration.withSftpPort(sftpPort, knownHostsFile).start();
                partner = new Partner(integration.serverPort());
                // No Order TG-1060 in the Order Database (same statements as P-cleanup; TC-001 cleans up too)
                runner.run(db.cleanup(ORDER_NUMBER));
                assertTrue(db.receipts(ORDER_NUMBER).isEmpty(), "An order_receipt TG-1060 is left after the cleanup");
                assertTrue(db.customerOrder(ORDER_NUMBER).isEmpty(), "A customer_order TG-1060 is left after the cleanup");
                // No PL-TG-1060.csv on the server: the server holds nothing because it is not there yet
                assertRefused(sftpPort, "after the integration started");
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
                // Recorded, history Received, Recorded, and the Pick List held after a refused attempt (UC-004 A1)
                awaitArrival("order-recorded-pick-list-held", () -> {
                    String state = db.receiptState(ORDER_NUMBER);
                    List<String> history = db.stateHistory(ORDER_NUMBER);
                    Map<String, Object> pickList = db.pickList(ORDER_NUMBER).orElse(null);
                    Object deliveryState = pickList == null ? null : pickList.get("delivery_state");
                    int attempts = pickList == null ? 0 : ((Number) pickList.get("attempts")).intValue();
                    if (!"Recorded".equals(state) || !List.of("Received", "Recorded").equals(history)
                        || !PENDING.equals(deliveryState) || attempts < 1) {
                        throw new CitrusRuntimeException(ORDER_NUMBER + " not yet recorded with a held Pick List: "
                            + "state " + state + ", history " + history + ", pick list " + deliveryState
                            + ", attempts " + attempts);
                    }
                });
                Map<String, Object> pickList = db.pickList(ORDER_NUMBER)
                    .orElseThrow(() -> new AssertionError("No Pick List queued for " + ORDER_NUMBER));
                assertEquals(PICK_LIST_FILE, pickList.get("file_name"));
                assertEquals("Recorded", db.receiptState(ORDER_NUMBER), "Order State of " + ORDER_NUMBER);
                assertEquals(List.of("Received", "Recorded"), db.stateHistory(ORDER_NUMBER),
                    "Order History of " + ORDER_NUMBER);
                assertEquals(PENDING, pickList.get("delivery_state"), "The Pick List of " + ORDER_NUMBER + " is held");
                assertTrue(((Number) pickList.get("attempts")).intValue() >= 1, "No delivery attempt was made");
                // The server holds no PL-TG-1060.csv: it is still unreachable (V1 settles the count)
                assertRefused(sftpPort, "while the Pick List is held");
                LOG.info("{}: {} held after {} attempt(s)", TC_ID, PICK_LIST_FILE, pickList.get("attempts"));
            });

            run.execute("S5", () -> {
                Path runDirBefore = integration.runDir();
                int serverPortBefore = integration.serverPort();
                Path firstLog = integration.logFile();
                // Graceful stop (SIGTERM) and start with the same run directory, ports and settings (A-13)
                integration.restart();
                assertTrue(integration.lastStopGraceful(),
                    "The integration did not stop gracefully on SIGTERM; it had to be killed (A-13)");
                assertTrue(integration.isRunning(), "The integration is not running after the restart");
                assertEquals(runDirBefore, integration.runDir(), "The run directory changed across the restart");
                assertEquals(serverPortBefore, integration.serverPort(), "server.port changed across the restart");
                assertEquals(TC_ID + "-2.log", integration.logFile().getFileName().toString(),
                    "Log file of the second start");
                assertTrue(Files.isRegularFile(firstLog), "The log of the first start is gone");
                // IntegrationProcess writes the run file in ISO-8859-1 (java.util.Properties encoding)
                assertTrue(Files.readString(integration.runDir().resolve("application-spec-test-run.properties"),
                        StandardCharsets.ISO_8859_1).contains("warehouse.sftp.port=" + sftpPort + "\n"),
                    "The integration is no longer configured with the Warehouse SFTP port P");
                // The Pick List is still held after the restart
                Map<String, Object> pickList = db.pickList(ORDER_NUMBER)
                    .orElseThrow(() -> new AssertionError("The Pick List of " + ORDER_NUMBER + " is gone"));
                assertEquals(PENDING, pickList.get("delivery_state"),
                    "The Pick List of " + ORDER_NUMBER + " is no longer held after the restart");
                assertRefused(sftpPort, "after the restart, before the server is made available");
                // Then the same configured server is made available: this test's container on 127.0.0.1:P
                warehouseServer = SpecEnvironment.get().startSftpOn(sftpPort);
                serverAvailableAt = Instant.now();
                assertBoundToLoopbackOnly(warehouseServer, sftpPort);
                LOG.info("{}: Warehouse SFTP Server available on {}:{} {} ms after step 1", TC_ID, LOOPBACK, sftpPort,
                    Duration.between(submittedAt, serverAvailableAt).toMillis());
            });

            run.execute("S6", () -> {
                // The restarted integration delivers the held Pick List and records it
                awaitArrival("pick-list-delivered-after-restart", () -> {
                    if (inbox.count(PICK_LIST_FILE) == 0) {
                        throw new CitrusRuntimeException(PICK_LIST_FILE + " not yet on the Warehouse SFTP Server");
                    }
                    Object state = db.pickList(ORDER_NUMBER).map(p -> p.get("delivery_state")).orElse(null);
                    if (!DELIVERED.equals(state)) {
                        throw new CitrusRuntimeException("The Pick List of " + ORDER_NUMBER + " is " + state);
                    }
                });
                LOG.info("{}: {} delivered {} ms after the server became available", TC_ID, PICK_LIST_FILE,
                    Duration.between(serverAvailableAt, Instant.now()).toMillis());
            });

            run.execute("S7", () -> {
                // NFR-001 from step 1; the unavailable server counts, nothing is left out (A-9, A-11)
                Duration elapsed = Duration.between(submittedAt, Instant.now());
                assertTrue(elapsed.compareTo(PICK_LIST_LIMIT) < 0,
                    PICK_LIST_FILE + " arrived " + elapsed + " after step 1, limit " + PICK_LIST_LIMIT);
                LOG.info("{}: {} on the Warehouse SFTP Server {} ms after step 1", TC_ID, PICK_LIST_FILE,
                    elapsed.toMillis());
                // Complete: header and two rows as in the example (total_weight_g 2340 on both rows)
                inbox.assertEqualsExample(PICK_LIST_FILE, docsFile(PICK_LIST_EXAMPLE));
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is left on the server");
            });

            run.execute("V1", () -> {
                // "15 minutes after step 7": several cycles of the 1 s pollers (A-10)
                runner.run(sleep().milliseconds(SpecEnvironment.get().millis("spec-test.quiet-period-ms")));
                assertEquals(1, inbox.count(PICK_LIST_FILE),
                    "Exactly one " + PICK_LIST_FILE + " across /inbound/picklists and /inbound/picklists/processed");
                assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is on the server");
            });

            run.execute("V3", () -> {
                // Delivered without Summit Outfitters resending the Order
                assertEquals(1, partner.requestsSent(), "The partner sent more than the one request of step 1");
                assertEquals(1, db.receipts(ORDER_NUMBER).size(), "order_receipt rows of " + ORDER_NUMBER);
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

            run.execute("P4", () -> {
                assertNotNull(warehouseServer, "The TC-005 Warehouse SFTP Server was never started");
                assertTrue(warehouseServer.isRunning(), "The TC-005 Warehouse SFTP Server is not running");
                assertBoundToLoopbackOnly(warehouseServer, sftpPort);
            });

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
                // Order History with (before) its Order, in one transaction
                database.cleanupNow(ORDER_NUMBER);
                assertTrue(database.receipts(ORDER_NUMBER).isEmpty(), ORDER_NUMBER + " is still in the Order Database");
                // The Pick List file is removed from the TC-005 server, when that server was made available
                if (warehouseServer != null && warehouseServer.isRunning() && inbox != null) {
                    inbox.remove(PICK_LIST_FILE);
                    assertEquals(0, inbox.count(PICK_LIST_FILE), PICK_LIST_FILE + " is still on the server");
                    assertFalse(inbox.tmpExists(PICK_LIST_FILE), PICK_LIST_FILE + ".tmp is still on the server");
                }
                // The Partner Configuration stays untouched (the Product Catalog data is read-only example files)
                assertEquals(1, database.rows("SELECT partner_code FROM partner WHERE partner_code = ?", PARTNER_CODE)
                    .size(), "Partner " + PARTNER_CODE + " was touched by the cleanup");
            });
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            // Both starts are stopped: restart() stopped the first; this stops the current one. Logs are kept.
            integration.stop();
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        try {
            run.finish();
        } catch (Throwable t) {
            failure = keep(failure, t);
        }
        // The TC-005 container is left to Testcontainers/Ryuk ("the Warehouse SFTP Server is left available")
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

    /** Nothing listens on {@code 127.0.0.1:port}: a TCP connect is refused (A-12). */
    private static void assertRefused(int port, String when) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), REFUSED_CHECK_TIMEOUT_MS);
            throw new AssertionError("Something listens on " + LOOPBACK + ":" + port + " " + when
                + "; the Warehouse SFTP Server must be unavailable");
        } catch (ConnectException e) {
            // refused: nothing listens
        } catch (IOException e) {
            throw new AssertionError("The connect to " + LOOPBACK + ":" + port + " " + when
                + " was not refused: " + e, e);
        }
    }

    /** The container's only host binding is {@code 127.0.0.1:port -> 22}. */
    private static void assertBoundToLoopbackOnly(GenericContainer<?> container, int port) {
        Map<ExposedPort, Ports.Binding[]> bindings =
            container.getContainerInfo().getNetworkSettings().getPorts().getBindings();
        Ports.Binding[] ssh = bindings.get(ExposedPort.tcp(SFTP_CONTAINER_PORT));
        assertNotNull(ssh, "The TC-005 Warehouse SFTP container has no binding for port " + SFTP_CONTAINER_PORT);
        assertEquals(1, ssh.length, "Bindings of the TC-005 Warehouse SFTP container");
        assertEquals(LOOPBACK, ssh[0].getHostIp(), "Host address of the TC-005 Warehouse SFTP container");
        assertEquals(String.valueOf(port), ssh[0].getHostPortSpec(), "Host port of the TC-005 Warehouse SFTP container");
        for (Map.Entry<ExposedPort, Ports.Binding[]> entry : bindings.entrySet()) {
            if (!entry.getKey().equals(ExposedPort.tcp(SFTP_CONTAINER_PORT)) && entry.getValue() != null) {
                throw new AssertionError("The TC-005 Warehouse SFTP container publishes " + entry.getKey());
            }
        }
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

    /** Polls {@code check} up to the arrival bound (A-9), as a Citrus {@code waitFor}. */
    private void awaitArrival(String name, Check check) {
        runner.run(waitFor().execution()
            .milliseconds(SpecEnvironment.get().millis("spec-test.arrival-timeout-ms"))
            .interval(WAIT_INTERVAL_MS)
            .action(check(name, check)));
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
