package com.trailheadgear.speccheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.trailheadgear.speccheck.SpecCheck.Finding;

class SpecCheckTest {

    private static final Path SPECS = Path.of("src/test/resources/specs");

    private static List<Finding> check(String spec) {
        return new SpecCheck(SPECS.resolve(spec)).run();
    }

    private static List<String> codes(List<Finding> findings, String file) {
        return findings.stream().filter(f -> f.file().equals(file)).map(Finding::code).toList();
    }

    @Test
    void aConsistentSpecHasNoFindings() {
        assertEquals(List.of(), check("good"));
    }

    @Test
    void listsTheFieldsOfASchemaWithTheirRequiredFlag() {
        Map<String, Boolean> fields = new SpecCheck(SPECS.resolve("good"))
                .fields(SPECS.resolve("good/messages/order.schema.json"));
        assertEquals(Map.of("orderId", true, "note", false, "shipTo", true, "shipTo.name", true,
                "shipTo.country", true, "lines", true, "lines[].sku", true, "lines[].quantity", true), fields);
    }

    @Test
    void findsSchemasThatAreNotJsonSchema() {
        assertTrue(codes(check("broken"), "messages/source.schema.json").contains("SCHEMA_INVALID"));
    }

    @Test
    void checksExamplesBothWays() {
        List<Finding> findings = check("broken");
        assertEquals(List.of("EXAMPLE_INVALID"), codes(findings, "messages/examples/order/mexico.json"));
        assertEquals(List.of("EXAMPLE_ACCEPTED"), codes(findings, "messages/examples/order/invalid/actually-fine.json"));
        assertEquals(List.of("EXAMPLE_NO_SCHEMA"), codes(findings, "messages/examples/invoice"));
    }

    @Test
    void findsMappingsToFieldsThatDoNotExistAndRequiredFieldsWithoutOne() {
        List<Finding> findings = check("broken");
        assertEquals(List.of("MAPPING_TARGET_UNKNOWN", "MAPPING_SOURCE_UNKNOWN", "MAPPING_UNMAPPED", "MAPPING_UNMAPPED"),
                codes(findings, "messages/mappings/source-to-order.md"));
        assertTrue(findings.stream().anyMatch(f -> f.detail().contains("`shipTo.country`")));
        assertTrue(findings.stream().anyMatch(f -> f.detail().contains("`lines[].quantity`")));
    }

    @Test
    void wantsAnOperationIdAndARejectionOnEveryOperation() {
        assertEquals(List.of("OPENAPI_NO_OPERATION_ID", "OPENAPI_NO_REJECTION"),
                codes(check("broken"), "api/orders.openapi.yaml"));
    }

    @Test
    void checksAsyncApiVersionReferencesAndPayloads() {
        assertEquals(List.of("ASYNCAPI_VERSION", "ASYNCAPI_REF", "ASYNCAPI_MESSAGE"),
                codes(check("broken"), "api/feed.asyncapi.yaml"));
    }

    @Test
    void theOrderIntegrationSpecPasses() {
        assertEquals(List.of(), new SpecCheck(Path.of("docs")).run());
    }

    @Test
    void thePluginShipsThisChecker() throws IOException {
        assertEquals(Files.readString(Path.of("src/main/java/com/trailheadgear/speccheck/SpecCheck.java")),
                Files.readString(Path.of("integration-spec/skills/spec-check/scripts/SpecCheck.java")));
    }
}
