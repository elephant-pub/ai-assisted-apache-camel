package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.citrusframework.TestCaseRunner;
import org.citrusframework.annotations.CitrusResource;
import org.citrusframework.junit.jupiter.CitrusSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T9 (design 005 §3.9; §3.1 P3, I3; §3.3 H3; §3.4 H2; run D): the Order Database is frozen ({@code docker pause} of
 * the PostgreSQL container through the Testcontainers Docker client, {@link SpecEnvironment#pausePostgres()}) while the
 * packaged jar runs normally, without the fault hook.
 *
 * <p>While it is frozen, a partner gets the 503 contract answer within NFR-002's 10 s (run D: 30.9 s with Spring's
 * generic body), and two Peakmart events are published (one that is recorded, one that is rejected). After the unpause
 * the first is recorded and its pick list delivered, the second is rejected and its Order Desk Notification sent, and a
 * new partner order is accepted and its pick list delivered: intake and every poller (check, pick list, notification)
 * resumed. The freeze lasts longer than the intake's retries (validation of 005, decision 3), so the event published
 * during it is recorded only if the intake waits for the database instead of handling the failure (F9).
 *
 * <p>The unpause is always done in a {@code finally}, before anything else touches the database; the process is
 * always stopped and the rows and files removed in the outer {@code finally}. Every wait is bounded.
 */
@CitrusSupport
class DatabaseFrozenIT {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseFrozenIT.class);

    private static final String PEAKMART_SAMPLE = "messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json";
    private static final String PEAKMART_INACTIVE_SKU = "messages/examples/peakmart-order-placed/pm-50118-inactive-sku.json";
    private static final String PARTNER_SAMPLE = "messages/examples/partner-order/partner-order-sample.json";
    private static final String ORDER_ANSWER_SCHEMA = "messages/order-answer.schema.json";
    private static final String ANSWER_VALID_EXAMPLE = "messages/examples/order-answer/temporarily-unavailable.json";
    private static final String ANSWER_INVALID_EXAMPLE = "messages/examples/order-answer/invalid/result-unknown.json";
    /** The Order Answer members of the contract's OrderProblem (Problem Details plus these). */
    private static final List<String> ORDER_ANSWER_MEMBERS = List.of("orderNumber", "result", "rejectionReasons");

    private static final String RECORDED = "Recorded";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";
    private static final String REJECTED = "Rejected";
    private static final String DELIVERED = "Delivered";

    /** NFR-002: every partner request is answered within 10 s. */
    private static final Duration PARTNER_ANSWER_LIMIT = Duration.ofSeconds(10);
    /** Longer than Spring's 30 s async timeout, so a red run shows the time and the body of today's answer (run D). */
    private static final Duration PARTNER_CLIENT_TIMEOUT = Duration.ofSeconds(40);
    /**
     * How long the intake keeps retrying a database failure before it gives up on the attempt (on-intake-waits): 1 + 3
     * redeliveries, each bounded at about 7 s by H3 (2 s for a connection, 5 s for the read), plus the 1, 2 and 4 s
     * back-off: about 35 s.
     */
    private static final Duration INTAKE_RETRY_SPAN = Duration.ofSeconds(4 * 7 + 1 + 2 + 4);
    /**
     * At least this long frozen (validation of 005, decision 3, Q-4): longer than {@link #INTAKE_RETRY_SPAN}, so that the
     * event published during the freeze is still not written when the retries run out. Only then does the test see
     * whether the intake waits (not handled, the event consumed again) or skips the event (F9). 15 s did not.
     */
    private static final Duration MINIMUM_FREEZE = Duration.ofSeconds(60);
    private static final long POLL_MILLIS = 500L;
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
        LOG.info("DatabaseFrozenIT: images {}", env.imageReferences());
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

    /** P3, I3: 503 contract answer in ≤ 10 s while frozen; after the unpause the event is recorded, every poller resumes. */
    @Test
    void frozenDatabaseAnswersInTimeAndEverythingResumesAfterTheUnpause() throws Throwable {
        String frozenOrder = "TG-1921";
        String afterOrder = "TG-1922";
        String eventOrder = "PM-91201";
        String rejectedOrder = "PM-91202";
        SpecEnvironment env = SpecEnvironment.get();
        IntegrationProcess integration = new IntegrationProcess("TC-921");
        Cleanup cleanup = new Cleanup(integration).orders(frozenOrder, afterOrder, eventOrder, rejectedOrder);
        Throwable primary = null;
        try {
            assertTopicDrained("frozen database");
            for (String file : cleanup.files) {
                inbox.remove(file);
            }
            integration.start();
            for (String order : cleanup.orders) {
                runner.run(db.cleanup(order));
            }

            Answer frozen;
            Duration frozenFor;
            env.pausePostgres();
            long pausedAt = System.nanoTime();
            try {
                frozen = postPartnerOrder(integration, partnerOrder(frozenOrder));
                runner.run(peakmart.publish(peakmartEvent(PEAKMART_SAMPLE, eventOrder)));
                runner.run(peakmart.publish(peakmartEvent(PEAKMART_INACTIVE_SKU, rejectedOrder)));
                long remaining = MINIMUM_FREEZE.toNanos() - (System.nanoTime() - pausedAt);
                if (remaining > 0) {
                    sleep(remaining / 1_000_000L);
                }
            } finally {
                env.unpausePostgres();
                frozenFor = Duration.ofNanos(System.nanoTime() - pausedAt);
            }
            LOG.info("DatabaseFrozenIT: PostgreSQL was frozen for {} ms; the partner answer took {} ms",
                frozenFor.toMillis(), frozen.elapsed.toMillis());

            // P3 (H2 + H3): the answer given while frozen
            assertContractAnswer503(frozen, frozenOrder);

            // I3 (decision 3): the freeze outlasted the intake's retries, so only waiting (not handled) gets the event in
            assertTrue(frozenFor.compareTo(INTAKE_RETRY_SPAN) > 0, "I3: frozen for " + frozenFor.toMillis() + " ms, not "
                + "longer than the intake's retries (" + INTAKE_RETRY_SPAN.toMillis() + " ms)");
            // I3 and the pollers: the events published during the freeze, then a new partner order. No restart before
            // this: an event the intake skipped would only come in again after one (the cleanup's drain)
            awaitDeliveredOnce(eventOrder, topicWait());
            assertRecordedOnce(eventOrder, "Peakmart");
            await("I3: " + rejectedOrder + " rejected after the unpause", topicWait(),
                () -> stateOf(rejectedOrder, REJECTED));
            await("the Order Desk Notification of " + rejectedOrder + " sent after the unpause", arrivalWait(), () ->
                db.notificationSentAt(rejectedOrder).isPresent() ? null : "not yet marked sent");
            runner.run(env.mailboxes().receiveOne(Mailboxes.ORDER_DESK, Mailboxes.orderDeskSubject(rejectedOrder),
                env.millis("spec-test.arrival-timeout-ms")));

            Answer after = postPartnerOrder(integration, partnerOrder(afterOrder));
            assertAccepted(after, afterOrder);
            awaitDeliveredOnce(afterOrder, arrivalWait());
            assertRecordedOnce(afterOrder, "Partner");
            assertEquals(1, inbox.count(pickListFile(afterOrder)), "exactly one " + pickListFile(afterOrder)
                + " on the Warehouse SFTP Server");
            assertEquals(1, inbox.count(pickListFile(eventOrder)), "exactly one " + pickListFile(eventOrder)
                + " on the Warehouse SFTP Server");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            Throwable failure = primary;
            try {
                env.unpausePostgres();
            } catch (Throwable t) {
                failure = keep(failure, t);
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
    // Assertions

    /** 503, problem+json, result temporarily-unavailable, Order Answer members valid, within 10 s. */
    private static void assertContractAnswer503(Answer answer, String order) throws IOException {
        String what = "P3: the answer to " + order + " while the Order Database is frozen";
        if (answer.response == null) {
            fail(what + ": no answer within " + PARTNER_CLIENT_TIMEOUT + " (" + answer.failure + ")");
        }
        HttpResponse<String> response = answer.response;
        assertTrue(answer.elapsed.compareTo(PARTNER_ANSWER_LIMIT) <= 0, what + " took " + answer.elapsed.toMillis()
            + " ms (limit " + PARTNER_ANSWER_LIMIT.toMillis() + " ms): " + response.statusCode() + " " + response.body());
        assertEquals(503, response.statusCode(), what + ": status (" + response.body() + ")");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
            what + ": content type " + response.headers().firstValue("Content-Type").orElse("none") + " ("
                + response.body() + ")");
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
            // The 9 s last-resort answer may leave the order number out (order-answer.schema.json requires only result)
            assertEquals(order, problem.path("orderNumber").asText(), what + ": orderNumber");
        }
    }

    private static void assertAccepted(Answer answer, String order) throws IOException {
        if (answer.response == null) {
            fail("The answer to " + order + " after the unpause: none within " + PARTNER_CLIENT_TIMEOUT + " ("
                + answer.failure + ")");
        }
        HttpResponse<String> response = answer.response;
        assertEquals(200, response.statusCode(), "Status of " + order + " after the unpause: " + response.body());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(order, body.path("orderNumber").asText(), "orderNumber of the answer");
        assertEquals("accepted", body.path("result").asText(), "result of the answer");
        assertNull(body.get("rejectionReasons"), "rejectionReasons in an accepted answer");
    }

    /** The Pick List of {@code order} is Delivered and the order Sent to Warehouse. */
    private static void awaitDeliveredOnce(String order, Duration timeout) {
        await(order + " delivered to the warehouse after the unpause", timeout, () -> {
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

    private static String stateOf(String order, String expected) {
        List<Map<String, Object>> receipts = db.receipts(order);
        if (receipts.size() == 1 && expected.equals(receipts.get(0).get("order_state"))) {
            return null;
        }
        return receipts.isEmpty() ? "no receipt" : receipts.size() + " receipt(s), state "
            + receipts.get(0).get("order_state") + ", waiting_for " + receipts.get(0).get("waiting_for");
    }

    /** Precondition: every record on the topic is committed, so the events of this case are the only new ones. */
    private static void assertTopicDrained(String testCase) {
        assertEquals(peakmart.endOffsets(), peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP),
            testCase + ": precondition, every record on " + Peakmart.TOPIC + " is committed before the start");
    }

    /** The Order Answer members validated with the contract schema; null when valid, else the reason. */
    private static String orderAnswerViolation(String json) {
        try {
            validator.requestBody("json-validator:file:" + docsFile(ORDER_ANSWER_SCHEMA), json);
            return null;
        } catch (RuntimeException e) {
            return rootMessage(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cleanup

    /** Stops the process, drains the topic, removes the orders and pick-list files of the case. */
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

    /** A Peakmart Order Event from a docs example, with its own Order Number and a new event id, as a file. */
    private static Path peakmartEvent(String example, String order) throws IOException {
        ObjectNode event = (ObjectNode) JSON.readTree(docsFile(example).toFile());
        event.put("event_id", UUID.randomUUID().toString());
        event.put("order_ref", order);
        Path dir = SpecEnvironment.get().workDir().resolve("database-frozen-it");
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

    /** Arrival bound plus the intake's redeliveries and Kafka's re-poll after the freeze. */
    private static Duration topicWait() {
        return arrivalWait().plusSeconds(60);
    }

    private static String pickListFile(String order) {
        return "PL-" + order + ".csv";
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
