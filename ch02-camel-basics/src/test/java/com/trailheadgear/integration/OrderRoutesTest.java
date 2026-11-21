package com.trailheadgear.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.NotifyBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same behavior suite runs against the Java routes and the YAML routes. */
@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "trailhead.warehouse.outbox=mock:warehouse",
        "trailhead.orders.rejected=mock:rejected",
        "trailhead.orders.set-aside=mock:set-aside"})
@DirtiesContext
abstract class OrderRoutesTest {

    @TempDir
    static Path inbox;

    @DynamicPropertySource
    static void inbox(DynamicPropertyRegistry registry) {
        registry.add("trailhead.peakmart.inbox", () -> "file:" + inbox + "?delay=100");
    }

    @Autowired
    CamelContext camel;

    @Autowired
    ProducerTemplate producer;

    @LocalServerPort
    int port;

    MockEndpoint warehouse, rejected, setAside;

    @BeforeEach
    void mocks() {
        warehouse = MockEndpoint.resolve(camel, "mock:warehouse");
        rejected = MockEndpoint.resolve(camel, "mock:rejected");
        setAside = MockEndpoint.resolve(camel, "mock:set-aside");
        MockEndpoint.resetMocks(camel);
    }

    @Test
    void capturedPeakmartEventProducesWarehousePickList() throws Exception {
        warehouse.expectedMessageCount(1);
        warehouse.expectedHeaderReceived("orderNumber", "PM-50117");
        warehouse.expectedHeaderReceived("CamelFileName", "PL-PM-50117.csv");
        rejected.expectedMessageCount(0);
        setAside.expectedMessageCount(0);

        copySample("peakmart-order-placed-PM-50117.json");

        MockEndpoint.assertIsSatisfied(camel, 10, TimeUnit.SECONDS);
        String csv = warehouse.getExchanges().getFirst().getMessage().getBody(String.class);
        assertThat(csv)
                .contains("order_no,line_no,sku,qty,ship_name,ship_street,ship_city,ship_postcode,ship_country")
                .contains("PM-50117,1,TENT-2P,1,Jordan Reyes,\"2240 Pearl Street, Apt 4\",Boulder,80302,US")
                .contains("PM-50117,2,STAKE-AL8,2");
    }

    @Test
    void readablePeakmartEventThatFailsChecksIsRejected() throws Exception {
        rejected.expectedMessageCount(1);
        rejected.expectedHeaderReceived("orderNumber", "PM-50118");
        warehouse.expectedMessageCount(0);
        setAside.expectedMessageCount(0);

        copySample("peakmart-order-placed-PM-50118-rejected.json");

        MockEndpoint.assertIsSatisfied(camel, 10, TimeUnit.SECONDS);
        String json = rejected.getExchanges().getFirst().getMessage().getBody(String.class);
        assertThat(json).contains("\"result\":\"rejected\"")
                .contains("quantity must be at least 1");
    }

    @Test
    void unreadableOrUnclassifiablePeakmartEventIsSetAside() throws Exception {
        setAside.expectedMessageCount(2);
        warehouse.expectedMessageCount(0);
        rejected.expectedMessageCount(0);

        copySample("peakmart-broken.json");
        copySample("peakmart-missing-event-type.json");

        MockEndpoint.assertIsSatisfied(camel, 10, TimeUnit.SECONDS);
        assertThat(setAside.getExchanges())
                .extracting(exchange -> exchange.getMessage().getHeader("CamelFileName", String.class))
                .containsExactlyInAnyOrder(
                        "SET-ASIDE-peakmart-broken.json",
                        "SET-ASIDE-peakmart-missing-event-type.json");
        assertThat(setAside.getExchanges())
                .extracting(exchange -> exchange.getMessage().getBody(String.class))
                .anySatisfy(body -> assertThat(body).contains("\"event_id\":\"broken\""))
                .anySatisfy(body -> assertThat(body).contains("\"event_id\": \"missing-type\""));
    }

    @Test
    void readablePeakmartEventOfAnotherTypeIsSkipped() throws Exception {
        var processed = new NotifyBuilder(camel).fromRoute("peakmart-capture").whenDone(1).create();
        warehouse.expectedMessageCount(0);
        rejected.expectedMessageCount(0);
        setAside.expectedMessageCount(0);

        copySample("peakmart-other-event.json");

        assertThat(processed.matches(10, TimeUnit.SECONDS)).isTrue();
        MockEndpoint.assertIsSatisfied(camel);
    }

    @Test
    void sharedIntakeCanBeCalledWithCanonicalOrder() throws Exception {
        warehouse.expectedMessageCount(1);
        warehouse.expectedHeaderReceived("orderNumber", "TG-1061");

        producer.sendBody("direct:intake", canonicalOrder("TG-1061"));

        warehouse.assertIsSatisfied();
    }

    @Test
    void acceptedPartnerOrderReturns200AndProducesPickList() throws Exception {
        warehouse.expectedMessageCount(1);
        rejected.expectedMessageCount(0);

        HttpResponse<String> response = post(Files.readString(Path.of("samples/partner-order-TG-1060.json")));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"orderNumber\":\"TG-1060\"").contains("\"result\":\"accepted\"");
        MockEndpoint.assertIsSatisfied(camel);
    }

    @Test
    void rejectedPartnerOrderReturns422AndProducesNoPickList() throws Exception {
        rejected.expectedMessageCount(1);
        warehouse.expectedMessageCount(0);

        HttpResponse<String> response = post("""
                {"orderId":"TG-1062","partnerId":"summit-outfitters","orderedAt":"2026-11-02T14:05:12Z",
                 "shipTo":{"name":"Lee Martin","street":"18 Canyon Road","city":"Moab",
                           "postalCode":"84532","country":"US"},"lines":[]}""");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"result\":\"rejected\"").contains("order has no lines");
        MockEndpoint.assertIsSatisfied(camel);
    }

    private void copySample(String name) throws Exception {
        Files.copy(Path.of("samples", name), inbox.resolve(name));
    }

    private HttpResponse<String> post(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Order canonicalOrder(String orderNumber) {
        return new Order(orderNumber, "partner", "summit-outfitters", null,
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com",
                new Order.ShippingAddress("Lee Martin", "18 Canyon Road", "Moab", null, "84532", "US"),
                List.of(new Order.OrderLine(1, "TENT-2P", 1, new BigDecimal("249.00"))));
    }
}
