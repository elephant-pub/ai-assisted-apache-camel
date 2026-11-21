package com.trailheadgear.orderintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guard of the json-schema-validator 2.0.1 pin (design 005 T1, X5; decision 2 of 004): camel-json-validator 4.22.2 cannot
 * run with the 3.0.4 that citrus-validation-json brings (NoSuchMethodError on every json-validator step). With the pin
 * removed, this test fails on the first validation. No Docker, no network: a plain Camel context.
 */
class JsonValidatorPinTest {

    private static final String SCHEMA = "json-validator:classpath:schemas/order.schema.json";

    private CamelContext context;
    private ProducerTemplate template;

    @BeforeEach
    void start() throws Exception {
        context = new DefaultCamelContext();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:validate").routeId("json-validator-pin-test").to(SCHEMA);
            }
        });
        context.start();
        template = context.createProducerTemplate();
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    @Test
    void validOrderPassesUnchanged() throws Exception {
        String order = read("docs/messages/examples/order/from-peakmart-order.json");

        Object result = template.requestBody("direct:validate", order);

        assertThat(result).isEqualTo(order);
    }

    @Test
    void orderWithoutLinesIsRejected() throws Exception {
        String order = read("docs/messages/examples/order/invalid/no-lines.json");

        assertThatThrownBy(() -> template.requestBody("direct:validate", order))
            .hasRootCauseInstanceOf(JsonValidationException.class);
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(Path.of(System.getProperty("basedir", ".")).resolve(relativePath), StandardCharsets.UTF_8);
    }
}
