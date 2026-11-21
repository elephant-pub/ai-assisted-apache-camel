package com.trailheadgear.orderintegration.spec.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.citrusframework.http.actions.HttpClientActionBuilder;
import org.citrusframework.http.actions.HttpClientRequestActionBuilder;
import org.citrusframework.http.actions.HttpClientResponseActionBuilder;
import org.citrusframework.http.client.HttpClient;
import org.citrusframework.http.client.HttpClientBuilder;
import org.citrusframework.message.MessageType;
import org.citrusframework.validation.context.json.JsonMessageValidationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Partner Summit Outfitters (design 004 §3.3, Task 10): a Citrus {@code http-client} on
 * {@code http://127.0.0.1:<server.port>} that submits the sample Partner Order and checks the Order Answer.
 *
 * <p><b>Request.</b> {@link #submitOrder()} is {@code POST /orders} with {@code Content-Type: application/json}, the
 * header {@value #API_KEY_HEADER} read from the run's Secret Store file, and the body
 * {@code docs/inputs/partner-order-sample.json} unchanged ({@code orderId} TG-1060). The file must decode as UTF-8
 * and must hold no Citrus expression ({@code ${…}}, {@code citrus:}), so Citrus' dynamic-content replacement cannot
 * change a character; Spring's string converter writes {@code application/json} as UTF-8, so the bytes on the wire are
 * the file's bytes.
 *
 * <p><b>Answer.</b> {@link #expectAccepted(String)} receives status 200 and validates the body against
 * {@code docs/messages/examples/order-answer/accepted.json}. That example has only {@code orderNumber} (the Partner's
 * {@code orderId}, set to the argument) and {@code result} ({@code accepted}); neither is run-dependent, so no field is
 * {@code @ignore@}. The validation is JSON and explicitly strict: the answer must have exactly these members, which
 * also proves {@code rejectionReasons} is absent (the schema forbids it unless {@code result} is {@code rejected}).
 *
 * <p><b>Counting.</b> {@link #requestsSent()} counts the HTTP requests this client executed: the client's only
 * interceptor increments the count when the RestTemplate executes a request, so a built but never-run action is not
 * counted, and a retried or repeated action is counted each time.
 *
 * <p><b>The key is never logged.</b> Citrus installs a {@code LoggingClientInterceptor} in every http-client; it hands
 * the raw request, headers included, to the message listeners ({@code LoggingReporter}), which print it whenever the
 * {@code Message_OUT} logger is at DEBUG or {@code citrus.logger.print.message.content} is set. This client's
 * {@code interceptors} option replaces that list with the counting interceptor only, so the request with its
 * {@value #API_KEY_HEADER} header never reaches a listener. {@code HttpClient} itself logs only the body at DEBUG, and
 * the root level is INFO ({@code SpecEnvironment}).
 */
public final class Partner {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String ORDERS_PATH = "/orders";

    private static final String CONTENT_TYPE = "application/json";
    private static final String ORDER_SAMPLE = "inputs/partner-order-sample.json";
    private static final String ACCEPTED_EXAMPLE = "messages/examples/order-answer/accepted.json";
    private static final String ARRIVAL_TIMEOUT_KEY = "spec-test.arrival-timeout-ms";
    private static final Pattern ORDER_NUMBER = Pattern.compile("^[A-Z]{2}-[0-9]+$");
    /** URL-safe Base64 as SpecEnvironment generates it; nothing Citrus could take for an expression. */
    private static final Pattern API_KEY = Pattern.compile("^[A-Za-z0-9_-]{16,256}$");

    private final AtomicInteger requestsSent = new AtomicInteger();
    private final HttpClient client;
    private final long timeoutMs;
    private final String orderBody;
    private final JsonNode acceptedExample;

    public Partner(int serverPort) {
        if (serverPort < 1 || serverPort > 65535) {
            throw new IllegalArgumentException("Not a port: " + serverPort);
        }
        SpecEnvironment env = SpecEnvironment.get();
        this.timeoutMs = env.millis(ARRIVAL_TIMEOUT_KEY);
        this.orderBody = readExample(docsFile(ORDER_SAMPLE));
        try {
            this.acceptedExample = new ObjectMapper().readTree(readExample(docsFile(ACCEPTED_EXAMPLE)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse " + ACCEPTED_EXAMPLE, e);
        }
        if (!acceptedExample.isObject()) {
            throw new IllegalStateException(ACCEPTED_EXAMPLE + " is not a JSON object");
        }

        ClientHttpRequestInterceptor counting = (request, body, execution) -> {
            requestsSent.incrementAndGet();
            return execution.execute(request, body);
        };
        this.client = new HttpClientBuilder()
            .requestUrl("http://127.0.0.1:" + serverPort)
            .requestMethod(RequestMethod.POST)
            .contentType(CONTENT_TYPE)
            .timeout(timeoutMs)
            // Replaces Citrus' default LoggingClientInterceptor, which would pass the key header to the log listeners
            .interceptors(List.of(counting))
            .build();
        client.setName("partner-summit-outfitters");
    }

    /** {@code POST /orders} with the API Key header and {@code partner-order-sample.json} as is. */
    public HttpClientRequestActionBuilder submitOrder() {
        HttpClientRequestActionBuilder send = new HttpClientActionBuilder(client).send().post(ORDERS_PATH);
        send.message()
            .contentType(CONTENT_TYPE)
            .header(API_KEY_HEADER, apiKey())
            .body(orderBody);
        return send;
    }

    /**
     * Status 200 and a body equal to {@code accepted.json} for {@code orderNumber}, strict: no member besides
     * {@code orderNumber} and {@code result}, hence no {@code rejectionReasons}.
     */
    public HttpClientResponseActionBuilder expectAccepted(String orderNumber) {
        if (orderNumber == null || !ORDER_NUMBER.matcher(orderNumber).matches()) {
            throw new IllegalArgumentException("Not an Order Number: " + orderNumber);
        }
        ObjectNode expected = acceptedExample.deepCopy();
        expected.put("orderNumber", orderNumber);

        HttpClientResponseActionBuilder receive = new HttpClientActionBuilder(client).receive().response(HttpStatus.OK);
        receive.message()
            .type(MessageType.JSON)
            .body(expected.toString());
        receive.validate(JsonMessageValidationContext.Builder.json().strict(true));
        receive.timeout(timeoutMs);
        return receive;
    }

    /** The number of HTTP requests this client has executed. */
    public int requestsSent() {
        return requestsSent.get();
    }

    public HttpClient client() {
        return client;
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** Read when the action is built; the value is never logged. */
    private static String apiKey() {
        Path file = SpecEnvironment.get().partnerApiKeyFile();
        String key;
        try {
            key = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the partner API Key file " + file, e);
        }
        if (!API_KEY.matcher(key).matches()) {
            throw new IllegalStateException("The partner API Key file " + file + " does not hold a usable key");
        }
        return key;
    }

    static Path docsFile(String relative) {
        return Path.of(System.getProperty("spec-test.docs-dir", "docs")).resolve(relative).toAbsolutePath().normalize();
    }

    /**
     * The file as text, refused when it is not valid UTF-8 or holds something Citrus would replace, so the text sent is
     * byte for byte the file.
     */
    static String readExample(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read example " + file, e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Example " + file + " is not valid UTF-8", e);
        }
        if (text.contains("${") || text.contains("citrus:")) {
            throw new IllegalStateException("Example " + file + " holds a Citrus expression and would not be sent as is");
        }
        return text;
    }
}
