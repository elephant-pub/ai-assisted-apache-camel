package com.trailheadgear.orderintegration.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trailheadgear.orderintegration.spec.support.IntegrationProcess;
import com.trailheadgear.orderintegration.spec.support.SpecEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T6 (design 006 §3.14; §3.4 D3; §3.1.6 P1–P4): the packaged jar ({@link IntegrationProcess}, profile spec-test, the
 * shared {@link SpecEnvironment}) serves the actuator's health, and only that, on its management port. In the release
 * that port is 9090; here it is the harness's free port ({@link IntegrationProcess#managementPort()}, plan P-8).
 *
 * <ul>
 *   <li>{@code /actuator/health/liveness} and {@code /actuator/health/readiness} answer 200 {@code {"status":"UP"}};</li>
 *   <li>{@code /actuator/health} shows neither {@code components} nor {@code details};</li>
 *   <li>{@code /actuator/env} is 404 on the management port and on the partner port ({@code server.port});</li>
 *   <li>the partner path {@code /orders} is 404 on the management port;</li>
 *   <li>with PostgreSQL paused ({@code docker pause} through the Testcontainers Docker client), liveness and readiness
 *       stay 200 (three samples over 10 s) while {@code /actuator/health} is 503: an outside outage never takes the pod
 *       out of the Service nor restarts it (FR-030, lab L5).</li>
 * </ul>
 *
 * <p>Every request has a connect and a request timeout. The unpause is always done in a {@code finally}; the process
 * is stopped in {@link #stop()}.
 */
class ProbesIT {

    private static final Logger LOG = LoggerFactory.getLogger(ProbesIT.class);

    private static final String LIVENESS = "/actuator/health/liveness";
    private static final String READINESS = "/actuator/health/readiness";
    private static final String HEALTH = "/actuator/health";
    private static final String ENV = "/actuator/env";
    private static final String PARTNER_PATH = "/orders";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Longer than one database health check against a paused PostgreSQL (H3: about 7 s), so a 503 is seen, not a timeout. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    /** Step 2: three samples of each probe over 10 s. */
    private static final int OUTAGE_SAMPLES = 3;
    private static final Duration OUTAGE_SPAN = Duration.ofSeconds(10);
    /** Camel's consumer checks are DOWN until each poller has polled and the Kafka consumer has joined. */
    private static final Duration BASELINE_WAIT = Duration.ofSeconds(90);
    /** The availability states are set just after the harness's "ready" (Spring's started and ready events). */
    private static final Duration GROUP_WAIT = Duration.ofSeconds(10);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT).build();

    private static IntegrationProcess integration;

    @BeforeAll
    static void start() {
        LOG.info("ProbesIT: images {}", SpecEnvironment.get().imageReferences());
        integration = new IntegrationProcess("TC-971");
        integration.start();
        LOG.info("ProbesIT: server.port {}, management port {}", integration.serverPort(), integration.managementPort());
    }

    @AfterAll
    static void stop() {
        if (integration != null) {
            integration.stop();
        }
    }

    /** P1: health only, on its own port, groups UP, details hidden, nothing else exposed. */
    @Test
    void healthOnlyOnTheManagementPort() throws IOException {
        int management = integration.managementPort();

        // The harness is "ready" at Spring's "Started …" line, which comes before the liveness (ApplicationStartedEvent)
        // and readiness (ApplicationReadyEvent) states are set: wait for each group, briefly
        assertStatusUp(awaitStatus(management, LIVENESS, 200, GROUP_WAIT), "P1 liveness");
        assertStatusUp(awaitStatus(management, READINESS, 200, GROUP_WAIT), "P1 readiness");

        // UP or DOWN: right after the start Camel's consumer checks may still be DOWN ("not yet polled")
        Answer health = get(management, HEALTH);
        assertTrue(health.status == 200 || health.status == 503, "P1 " + HEALTH + " on the management port: status "
            + health.status + " (" + health.body + ")");
        JsonNode body = JSON.readTree(health.body);
        assertFalse(body.has("components"), "P1 " + HEALTH + " shows its components: " + health.body);
        assertFalse(body.has("details"), "P1 " + HEALTH + " shows details: " + health.body);

        Answer envOnManagement = get(management, ENV);
        assertEquals(404, envOnManagement.status, "P1 " + ENV + " on the management port: status");
        Answer envOnServer = get(integration.serverPort(), ENV);
        assertEquals(404, envOnServer.status, "P1 " + ENV + " on server.port: status");
        Answer partnerOnManagement = get(management, PARTNER_PATH);
        assertEquals(404, partnerOnManagement.status, "P1 the partner path " + PARTNER_PATH
            + " on the management port: status");
    }

    /** P3: PostgreSQL paused: both groups stay 200 at every sample while the whole health is 503. */
    @Test
    void groupsStayUpWhileTheDatabaseIsPaused() throws IOException {
        int management = integration.managementPort();
        SpecEnvironment env = SpecEnvironment.get();
        List<String> samples = new ArrayList<>();
        // Baseline: the whole health is UP before the pause, so its 503 below is the outage and not the start
        awaitHealthUp(management);
        Answer health;
        Throwable primary = null;
        try {
            // Inside the try: a pause command that fails after Docker paused the container is still undone
            env.pausePostgres();
            long interval = OUTAGE_SPAN.toMillis() / (OUTAGE_SAMPLES - 1);
            for (int sample = 1; sample <= OUTAGE_SAMPLES; sample++) {
                long started = System.nanoTime();
                Answer liveness = get(management, LIVENESS);
                Answer readiness = get(management, READINESS);
                samples.add("sample " + sample + ": liveness " + liveness.status + ", readiness " + readiness.status);
                assertEquals(200, liveness.status, "P3 liveness during the database outage, sample " + sample
                    + " of " + OUTAGE_SAMPLES + " (" + liveness.body + ")");
                assertEquals(200, readiness.status, "P3 readiness during the database outage, sample " + sample
                    + " of " + OUTAGE_SAMPLES + " (" + readiness.body + ")");
                long rest = interval - Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (sample < OUTAGE_SAMPLES && rest > 0) {
                    sleep(rest);
                }
            }
            health = get(management, HEALTH);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            try {
                env.unpausePostgres();
            } catch (RuntimeException e) {
                // Never hide the P3 failure behind an unpause failure
                if (primary == null) {
                    throw e;
                }
                primary.addSuppressed(e);
            }
        }
        LOG.info("ProbesIT: during the outage {}; {} {}", samples, HEALTH, health.status);
        assertEquals(503, health.status, "P3 " + HEALTH + " during the database outage: status (" + health.body + ")");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helpers

    private static void assertStatusUp(Answer answer, String what) throws IOException {
        assertEquals(200, answer.status, what + ": status (" + answer.body + ")");
        JsonNode body = JSON.readTree(answer.body);
        assertEquals(JSON.createObjectNode().put("status", "UP"), body, what + ": body");
    }

    /** Polls {@code /actuator/health} until it is 200, at most {@link #BASELINE_WAIT}. */
    private static void awaitHealthUp(int port) {
        Answer health = awaitStatus(port, HEALTH, 200, BASELINE_WAIT);
        if (health.status != 200) {
            fail("P3 precondition: " + HEALTH + " not 200 within " + BASELINE_WAIT + " before the pause (last "
                + health.status + " " + health.body + ")");
        }
    }

    /** Polls {@code path} until it answers {@code status} or {@code wait} has passed; returns the last answer. */
    private static Answer awaitStatus(int port, String path, int status, Duration wait) {
        long deadline = System.nanoTime() + wait.toNanos();
        Answer answer = get(port, path);
        while (answer.status != status && System.nanoTime() < deadline) {
            sleep(500);
            answer = get(port, path);
        }
        return answer;
    }

    /** A plain-HTTP answer: status and body. */
    private record Answer(int status, String body) {
    }

    /** {@code GET http://127.0.0.1:<port><path>} with the connect and request timeouts; a refused connection fails. */
    private static Answer get(int port, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(REQUEST_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Answer(response.statusCode(), response.body());
        } catch (IOException e) {
            return fail("GET " + path + " on port " + port + ": no answer (" + e.getClass().getSimpleName() + ": "
                + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail("GET " + path + " on port " + port + ": interrupted");
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
}
