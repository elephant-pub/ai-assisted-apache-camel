package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T10 (design 005 §3.9; §3.1 P4, C3; §3.4 H2; §3.6 H5; run D): an order that passes every check but does not fit the
 * Order Database, against the packaged jar (normal start, no fault hook).
 *
 * <p>The unstorable value is a <b>150-character recipient name</b>: no schema sets a {@code maxLength} for it
 * ({@code peakmart-order-placed.schema.json} {@code ship_to.full_name}, {@code partner-order.schema.json}
 * {@code shipTo.name}, {@code order.schema.json} {@code recipientName} only {@code minLength: 1}), {@code OrderChecks}
 * checks only that it is present, and {@code customer_order.recipient_name} is {@code varchar(100)} (V1). Run D used the
 * same value. The name is generated (no personal data) and never put into an assertion message.
 *
 * <ul>
 *   <li>Peakmart (C3, H5): the order is parked, {@code waiting_for = 'Recording'} with
 *       {@code waiting_reason = 'cannot be recorded: <kind>'} and one ERROR {@code kind=unstorable orderNumber=…}; it is
 *       neither rejected nor polled again, and an order published after it is recorded.</li>
 *   <li>Partner (P4, H2): the 503 contract answer, an ERROR {@code kind=unstorable orderNumber=…}, nothing recorded.</li>
 * </ul>
 */
@CitrusSupport
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UnstorableOrderIT {

    private static final Logger LOG = LoggerFactory.getLogger(UnstorableOrderIT.class);

    private static final String PEAKMART_SAMPLE = "messages/examples/peakmart-order-placed/peakmart-order-placed-sample.json";
    private static final String PARTNER_SAMPLE = "messages/examples/partner-order/partner-order-sample.json";
    private static final String ORDER_ANSWER_SCHEMA = "messages/order-answer.schema.json";
    private static final String ANSWER_VALID_EXAMPLE = "messages/examples/order-answer/temporarily-unavailable.json";
    private static final String ANSWER_INVALID_EXAMPLE = "messages/examples/order-answer/invalid/result-unknown.json";
    private static final List<String> ORDER_ANSWER_MEMBERS = List.of("orderNumber", "result", "rejectionReasons");

    /** {@code customer_order.recipient_name varchar(100)}; the name is half as long again. */
    private static final int RECIPIENT_NAME_LENGTH = 150;
    private static final String RECIPIENT_NAME = generatedName(RECIPIENT_NAME_LENGTH);

    private static final String UNSTORABLE = "kind=unstorable";
    private static final String CANNOT_BE_RECORDED = "cannot be recorded: ";
    private static final String RECEIVED = "Received";
    private static final String RECORDED = "Recorded";
    private static final String SENT_TO_WAREHOUSE = "Sent to Warehouse";
    private static final String RECORDING = "Recording";

    private static final Duration PARTNER_ANSWER_LIMIT = Duration.ofSeconds(10);
    private static final Duration PARTNER_CLIENT_TIMEOUT = Duration.ofSeconds(12);
    /** Check poller every 1 s in spec-test: the park and the next order fit easily. */
    private static final Duration FLOW_WAIT = Duration.ofSeconds(45);
    /** More than 5 check polls: a parked order that were polled again would log again. */
    private static final Duration NO_REPOLL_WATCH = Duration.ofSeconds(6);
    private static final Duration LOG_WAIT = Duration.ofSeconds(5);
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
        LOG.info("UnstorableOrderIT: images {}", env.imageReferences());
        assertEquals(RECIPIENT_NAME_LENGTH, RECIPIENT_NAME.length(), "length of the generated recipient name");
        peakmart = new Peakmart();
        db = new OrderDatabase();
        inbox = new WarehouseInbox();
        validation = new DefaultCamelContext();
        validation.start();
        validator = validation.createProducerTemplate();
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
    // C3 (H5)

    /** Parked as waiting for Recording with its reason and one ERROR; not rejected, not re-polled; the next order moves. */
    @Test
    @Order(1)
    void peakmartOrderThatDoesNotFitIsParkedAndTheNextOrderIsRecorded() throws Throwable {
        String unstorable = "PM-91301";
        String next = "PM-91302";
        IntegrationProcess integration = new IntegrationProcess("TC-931");
        Cleanup cleanup = new Cleanup(integration).orders(unstorable, next);
        Throwable primary = null;
        try {
            assertTopicDrained("unstorable Peakmart order");
            integration.start();
            runner.run(db.cleanup(unstorable));
            runner.run(db.cleanup(next));
            runner.run(peakmart.publish(peakmartEvent(unstorable, true)));
            runner.run(peakmart.publish(peakmartEvent(next, false)));

            // Behaviour first: the newer order is not held up
            await("C3: " + next + ", published after the unstorable order, recorded", FLOW_WAIT,
                () -> recorded(next) ? null : next + " not recorded (" + receiptSummary(next) + ")");
            assertRecordedOnce(next, "Peakmart");

            await("C3: " + unstorable + " parked as waiting for " + RECORDING, FLOW_WAIT, () -> {
                Map<String, Object> receipt = singleReceipt(unstorable);
                return receipt != null && RECORDING.equals(receipt.get("waiting_for")) ? null
                    : unstorable + ": " + receiptSummary(unstorable);
            });
            List<Map<String, Object>> parked = db.rows("SELECT order_state, waiting_for, waiting_reason "
                + "FROM order_receipt WHERE order_number = ? AND channel = 'Peakmart'", unstorable);
            assertEquals(1, parked.size(), "C3: receipts of " + unstorable);
            assertEquals(RECEIVED, parked.get(0).get("order_state"), "C3: Order State of " + unstorable
                + " (parked, not rejected)");
            String reason = (String) parked.get(0).get("waiting_reason");
            assertNotNull(reason, "C3: waiting_reason of " + unstorable);
            assertTrue(reason.startsWith(CANNOT_BE_RECORDED), "C3: waiting_reason of " + unstorable + " starts '"
                + CANNOT_BE_RECORDED + "': " + reason);
            assertTrue(reason.length() > CANNOT_BE_RECORDED.length(), "C3: waiting_reason names a kind: " + reason);
            assertFalse(reason.contains(RECIPIENT_NAME), "C3: waiting_reason of " + unstorable + " holds the value "
                + "itself (a kind holds no message text, NFR-011)");
            assertTrue(db.customerOrder(unstorable).isEmpty(), "C3: " + unstorable + " was recorded");
            assertTrue(db.rejectionReasons(unstorable).isEmpty(), "C3: " + unstorable + " has Rejection Reasons "
                + "(no invented rejection)");
            assertTrue(db.notifications(unstorable).isEmpty(), "C3: " + unstorable + " has a notification");

            await("C3: ERROR " + UNSTORABLE + " for " + unstorable, LOG_WAIT,
                () -> unstorableErrors(integration, unstorable) > 0 ? null
                    : "no ERROR line with '" + UNSTORABLE + "' and 'orderNumber=" + unstorable + "' in "
                        + integration.logFile());
            // Never polled again: no further check of the parked order logs another unstorable ERROR
            long errors = unstorableErrors(integration, unstorable);
            sleep(NO_REPOLL_WATCH.toMillis());
            assertEquals(errors, unstorableErrors(integration, unstorable), "C3: " + unstorable + " was checked "
                + "again after it was parked (more ERROR " + UNSTORABLE + " lines within " + NO_REPOLL_WATCH + ")");
            assertEquals(RECORDING, singleReceipt(unstorable).get("waiting_for"), "C3: waiting_for of " + unstorable
                + " after " + NO_REPOLL_WATCH);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // P4 (H2)

    /** The partner gets the 503 contract answer, ERROR kind=unstorable is logged, nothing is recorded. */
    @Test
    @Order(2)
    void partnerOrderThatDoesNotFitIsAnswered503WithAnError() throws Throwable {
        String order = "TG-1931";
        IntegrationProcess integration = new IntegrationProcess("TC-932");
        Cleanup cleanup = new Cleanup(integration).orders(order);
        Throwable primary = null;
        try {
            integration.start();
            runner.run(db.cleanup(order));

            Answer answer = postPartnerOrder(integration, partnerOrder(order));
            assertContractAnswer503(answer, order);
            await("P4: ERROR " + UNSTORABLE + " for " + order, LOG_WAIT,
                () -> unstorableErrors(integration, order) > 0 ? null
                    : "no ERROR line with '" + UNSTORABLE + "' and 'orderNumber=" + order + "' in "
                        + integration.logFile());
            assertTrue(db.customerOrder(order).isEmpty(), "P4: " + order + " was recorded");
            List<Map<String, Object>> receipts = db.receipts(order);
            assertTrue(receipts.size() <= 1, "P4: receipts of " + order + ": " + receipts.size());
            for (Map<String, Object> receipt : receipts) {
                assertEquals(RECEIVED, receipt.get("order_state"), "P4: Order State of " + order + " (no invented "
                    + "rejection, not recorded)");
            }
            assertTrue(db.rejectionReasons(order).isEmpty(), "P4: " + order + " has Rejection Reasons");
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            cleanup.run(primary);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Assertions

    private static void assertContractAnswer503(Answer answer, String order) throws IOException {
        String what = "P4: the answer to " + order;
        if (answer.response == null) {
            fail(what + ": no answer within " + PARTNER_CLIENT_TIMEOUT + " (" + answer.failure + ")");
        }
        HttpResponse<String> response = answer.response;
        assertTrue(answer.elapsed.compareTo(PARTNER_ANSWER_LIMIT) <= 0, what + " took " + answer.elapsed.toMillis()
            + " ms (limit " + PARTNER_ANSWER_LIMIT.toMillis() + " ms): " + response.statusCode());
        assertEquals(503, response.statusCode(), what + ": status");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"),
            what + ": content type " + response.headers().firstValue("Content-Type").orElse("none"));
        JsonNode problem = JSON.readTree(response.body());
        assertEquals("temporarily-unavailable", problem.path("result").asText(), what + ": result");
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

    /** One receipt, one customer_order of the Channel; an Accepted Order. */
    private static void assertRecordedOnce(String order, String channel) {
        List<Map<String, Object>> receipts = db.receipts(order);
        assertEquals(1, receipts.size(), "order_receipt rows of " + order);
        assertEquals(channel, receipts.get(0).get("channel"), "Channel of " + order);
        String state = (String) receipts.get(0).get("order_state");
        assertTrue(RECORDED.equals(state) || SENT_TO_WAREHOUSE.equals(state),
            order + " is not an Accepted Order: " + state);
        assertTrue(db.customerOrder(order).isPresent(), "No customer_order " + order);
    }

    /** ERROR lines of the current log naming {@code kind=unstorable} and {@code orderNumber=<order>}. */
    private static long unstorableErrors(IntegrationProcess integration, String order) throws IOException {
        String number = "orderNumber=" + order;
        return log(integration).lines()
            .filter(l -> l.contains("ERROR") && l.contains(UNSTORABLE) && containsToken(l, number))
            .count();
    }

    /** {@code token} not followed by a further digit (PM-9130 must not match PM-91301). */
    private static boolean containsToken(String line, String token) {
        for (int i = line.indexOf(token); i >= 0; i = line.indexOf(token, i + 1)) {
            int end = i + token.length();
            if (end == line.length() || !Character.isDigit(line.charAt(end))) {
                return true;
            }
        }
        return false;
    }

    private static void assertTopicDrained(String testCase) {
        assertEquals(peakmart.endOffsets(), peakmart.committedOffsets(Peakmart.INTEGRATION_GROUP),
            testCase + ": precondition, every record on " + Peakmart.TOPIC + " is committed before the start");
    }

    private static Map<String, Object> singleReceipt(String order) {
        List<Map<String, Object>> receipts = db.receipts(order);
        return receipts.size() == 1 ? receipts.get(0) : null;
    }

    private static boolean recorded(String order) {
        Map<String, Object> receipt = singleReceipt(order);
        if (receipt == null) {
            return false;
        }
        Object state = receipt.get("order_state");
        return (RECORDED.equals(state) || SENT_TO_WAREHOUSE.equals(state)) && db.customerOrder(order).isPresent();
    }

    /** State and waiting_for only: no order content (personal data) in a message. */
    private static String receiptSummary(String order) {
        List<Map<String, Object>> receipts = db.receipts(order);
        if (receipts.isEmpty()) {
            return "no receipt";
        }
        Map<String, Object> r = receipts.get(0);
        return receipts.size() + " receipt(s), state " + r.get("order_state") + ", waiting_for " + r.get("waiting_for");
    }

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
                files.add("PL-" + number + ".csv");
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

    /** {@code length} letters and blanks; generated, not a person's name. */
    private static String generatedName(int length) {
        StringBuilder name = new StringBuilder("Unstorable Recipient ");
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int i = 0;
        while (name.length() < length) {
            name.append(name.length() % 11 == 10 ? ' ' : letters.charAt(i++ % letters.length()));
        }
        // A trailing blank could be trimmed somewhere; end on a letter
        if (name.charAt(length - 1) == ' ') {
            name.setCharAt(length - 1, 'Z');
        }
        return name.toString();
    }

    /** The Peakmart sample with its own Order Number and event id; {@code unstorable} sets the 150-character name. */
    private static Path peakmartEvent(String order, boolean unstorable) throws IOException {
        ObjectNode event = (ObjectNode) JSON.readTree(docsFile(PEAKMART_SAMPLE).toFile());
        event.put("event_id", UUID.randomUUID().toString());
        event.put("order_ref", order);
        if (unstorable) {
            ((ObjectNode) event.path("ship_to")).put("full_name", RECIPIENT_NAME);
        }
        Path dir = SpecEnvironment.get().workDir().resolve("unstorable-order-it");
        Files.createDirectories(dir);
        Path file = dir.resolve(order + ".json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(event),
            StandardCharsets.UTF_8);
        return file;
    }

    /** The partner sample with its own Order Number and the 150-character recipient name. */
    private static String partnerOrder(String order) throws IOException {
        ObjectNode partnerOrder = (ObjectNode) JSON.readTree(docsFile(PARTNER_SAMPLE).toFile());
        partnerOrder.put("orderId", order);
        ((ObjectNode) partnerOrder.path("shipTo")).put("name", RECIPIENT_NAME);
        return JSON.writeValueAsString(partnerOrder);
    }

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

    private static String log(IntegrationProcess integration) throws IOException {
        Path file = integration.logFile();
        return file != null && Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
    }

    private static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }
}
