package com.trailheadgear.orderintegration.spec.support;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.citrusframework.base.endpoint.adapter.StaticEndpointAdapter;
import org.citrusframework.http.message.HttpMessage;
import org.citrusframework.http.server.HttpServer;
import org.citrusframework.http.server.HttpServerBuilder;
import org.citrusframework.message.Message;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * The Product Catalog for the executable spec tests (design 004 §2, §3.3): a Citrus {@code http-server} in the test
 * JVM that answers {@code GET /products/{sku}} as {@code docs/api/external/product-catalog.openapi.yaml} states.
 *
 * <p>TENT-2P, LAMP-HD200, STAKE-AL8 and TENT-1P (inactive) are answered 200 with {@code application/json} and the
 * exact bytes of {@code docs/messages/examples/product/<file>.json}, read from
 * {@code System.getProperty("spec-test.docs-dir", "docs")}. Every other SKU, and every other request, is answered 404
 * without a body (the contract's 404 has no content). Every SKU asked for is recorded, in order.
 *
 * <p>The server binds to 127.0.0.1 only: Citrus' {@code connector} option is given a Jetty {@link ServerConnector}
 * with host 127.0.0.1; {@code HttpServer.startup()} then uses that connector's {@link Server} instead of
 * {@code new Server(port)}, which would bind all interfaces. {@code SpecEnvironment} owns the lifecycle.
 */
public final class ProductCatalogStub {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String PATH_PREFIX = "/products/";
    /** The content type of the contract's 200 answer. */
    private static final String CONTENT_TYPE = "application/json";

    /** SKU to example file under {@code messages/examples/product/}. */
    private static final Map<String, String> EXAMPLES;

    static {
        Map<String, String> examples = new LinkedHashMap<>();
        examples.put("TENT-2P", "tent-2p.json");
        examples.put("LAMP-HD200", "lamp-hd200.json");
        examples.put("STAKE-AL8", "stake-al8.json");
        examples.put("TENT-1P", "tent-1p-inactive.json");
        EXAMPLES = Map.copyOf(examples);
    }

    private final List<String> requestedSkus = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private int port = -1;

    public ProductCatalogStub() {
    }

    /** Reads the example answers and starts the server on {@code 127.0.0.1:<port>}. */
    public synchronized void start(int port) throws IOException {
        if (server != null) {
            throw new IllegalStateException("Product Catalog stub already started on port " + this.port);
        }
        Map<String, String> answers = loadAnswers();

        ServerConnector connector = new ServerConnector(new Server());
        connector.setHost(LOOPBACK);
        connector.setPort(port);

        HttpServer httpServer = new HttpServerBuilder()
            .port(port)
            .autoStart(false)
            .connector(connector)
            .endpointAdapter(new CatalogAdapter(answers, requestedSkus))
            .build();
        httpServer.setName("product-catalog-stub");
        httpServer.initialize();
        httpServer.start();
        this.server = httpServer;
        this.port = port;
    }

    /** The port the server listens on, or -1 before {@link #start(int)}. */
    public synchronized int port() {
        return port;
    }

    /** Stops the server; a stopped stub can be started again. */
    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } finally {
                server = null;
                port = -1;
            }
        }
    }

    /** Every SKU requested since start or the last {@link #clearRequests()}, in arrival order. */
    public List<String> requestedSkus() {
        return List.copyOf(requestedSkus);
    }

    public void clearRequests() {
        requestedSkus.clear();
    }

    /** The example file contents, byte for byte; decoded strictly as UTF-8 only because Citrus sends a String body. */
    private static Map<String, String> loadAnswers() throws IOException {
        Path dir = Path.of(System.getProperty("spec-test.docs-dir", "docs"))
            .resolve("messages").resolve("examples").resolve("product");
        Map<String, String> answers = new LinkedHashMap<>();
        for (Map.Entry<String, String> example : EXAMPLES.entrySet()) {
            Path file = dir.resolve(example.getValue());
            byte[] bytes = Files.readAllBytes(file);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            } catch (CharacterCodingException e) {
                throw new IOException(file + " is not UTF-8; it cannot be served byte for byte", e);
            }
            if (!Arrays.equals(text.getBytes(StandardCharsets.UTF_8), bytes)) {
                throw new IOException(file + " does not round-trip through UTF-8");
            }
            answers.put(example.getKey(), text);
        }
        return Map.copyOf(answers);
    }

    /**
     * Answers each request synchronously; no test action is involved. Spring's string converter writes an
     * {@code application/json} body as UTF-8, so the bytes on the wire are those of the example file.
     */
    private static final class CatalogAdapter extends StaticEndpointAdapter {

        private final Map<String, String> answers;
        private final List<String> requestedSkus;

        CatalogAdapter(Map<String, String> answers, List<String> requestedSkus) {
            this.answers = answers;
            this.requestedSkus = requestedSkus;
        }

        @Override
        protected Message handleMessageInternal(Message message) {
            HttpMessage request = message instanceof HttpMessage http ? http : new HttpMessage(message);
            String path = request.getPath() != null ? request.getPath() : request.getUri();
            if (path == null || !path.startsWith(PATH_PREFIX)) {
                return notFound();
            }
            // The path is already URL-decoded by Spring's UrlPathHelper (HttpMessageController)
            String sku = path.substring(PATH_PREFIX.length());
            if (sku.isEmpty() || sku.contains("/")) {
                return notFound();
            }
            requestedSkus.add(sku);
            String answer = answers.get(sku);
            if (request.getRequestMethod() != RequestMethod.GET || answer == null) {
                return notFound();
            }
            return new HttpMessage(answer)
                .status(HttpStatus.OK)
                .contentType(CONTENT_TYPE);
        }

        private static Message notFound() {
            // An empty (not null) payload: a null payload would make Citrus look for a fallback adapter
            return new HttpMessage("").status(HttpStatus.NOT_FOUND);
        }
    }
}
