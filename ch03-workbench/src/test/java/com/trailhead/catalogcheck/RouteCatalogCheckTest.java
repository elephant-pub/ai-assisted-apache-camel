package com.trailhead.catalogcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RouteCatalogCheckTest {

    static RouteCatalogCheck check;

    @BeforeAll
    static void loadCatalog() throws Exception {
        check = new RouteCatalogCheck();
    }

    static Path route(String name) {
        return Path.of("src/test/resources/routes", name);
    }

    static List<String> kinds(List<Problem> problems) {
        return problems.stream().map(p -> p.kind() + " " + p.detail()).toList();
    }

    @Test
    void usesTheCatalogOfThePinnedRelease() {
        assertEquals("4.22.2", check.camelVersion());
    }

    @Test
    void aCorrectRouteHasNoProblems() throws Exception {
        assertEquals(List.of(), check.check(route("good.camel.yaml")));
    }

    @Test
    void findsInventedOptionsAndBadValues() throws Exception {
        List<String> found = kinds(check.check(route("invented.camel.yaml")));

        assertTrue(found.stream().anyMatch(k -> k.startsWith("unknown-option consumerGroup")), found::toString);
        assertTrue(found.stream().anyMatch(k -> k.startsWith("unknown-option moveDone")), found::toString);
        assertTrue(found.contains("invalid-enum fileExist=Overwrite"), found::toString);
        assertTrue(found.contains("unknown-component kafka-stream"), found::toString);
        assertEquals(4, found.size(), found::toString);
    }

    @Test
    void cannotCheckValuesTheCatalogDoesNotList() {
        // autoOffsetReset is a plain string in the 4.22 catalog, so a wrong value passes
        assertEquals(List.of(), check.checkEndpoint("x", "kafka:orders?autoOffsetReset=first"));
    }

    @Test
    void findsBadBooleans() {
        List<String> found = kinds(check.checkEndpoint("x", "file:in?noop=yes"));

        assertEquals(List.of("invalid-boolean noop=yes"), found);
    }

    @Test
    void reportsAUriWhoseComponentIsAPlaceholder() {
        List<String> found = kinds(check.checkEndpoint("x", "{{catalog.url}}?throwExceptionOnFailure=false"));

        assertEquals(1, found.size(), found::toString);
        assertTrue(found.get(0).startsWith("unknown-component"), found::toString);
    }

    @Test
    void findsStructureErrorsWithTheYamlSchema() throws Exception {
        List<Problem> problems = check.check(route("bad-structure.camel.yaml"));

        assertTrue(problems.stream().anyMatch(p -> p.kind().equals("schema")), problems::toString);
    }

    @Test
    void readsUrisFromEveryEndpointShape() {
        Object yaml = List.of(java.util.Map.of("route", java.util.Map.of("from", java.util.Map.of(
                "uri", "timer:tick", "parameters", java.util.Map.of("period", 1000),
                "steps", List.of(java.util.Map.of("to", "log:a"), java.util.Map.of("toD", java.util.Map.of("uri", "log:${header.x}")))))));

        List<String> uris = EndpointUris.find(yaml);

        assertTrue(uris.containsAll(List.of("timer:tick?period=1000", "log:a", "log:${header.x}")), uris::toString);
    }

    @Test
    void optionsNamedLikeEndpointsAreNotEndpoints() {
        Object yaml = java.util.Map.of("to", java.util.Map.of(
                "uri", "smtp:mail.example", "parameters", java.util.Map.of("from", "orders@example.com")));

        assertEquals(List.of("smtp:mail.example?from=orders@example.com"), EndpointUris.find(yaml));
    }

    @Test
    void cannotCheckOptionsBehindPassThroughPrefixes() {
        // httpClient.* is handed to the HTTP client builder as-is, so the catalog accepts any name after it
        assertEquals(List.of(), check.checkEndpoint("x", "https://example.com?httpClient.noSuchOption=1"));
    }

    @Test
    void acceptsTheComponentNameWithEveryOptionUnderParameters() {
        // uri: platform-http, parameters: {path: /api/orders, ...} runs fine in Camel 4.22
        assertEquals(List.of(), check.checkEndpoint("x", "platform-http?path=/api/orders&httpMethodRestrict=POST"));
        assertEquals(List.of(), check.checkEndpoint("x", "direct?name=intake"));
    }

    @Test
    void stillFindsInventedOptionsInThatForm() {
        List<String> found = kinds(check.checkEndpoint("x", "direct?name=intake&timeoutSeconds=5"));

        assertEquals(List.of("unknown-option timeoutSeconds"), found);
    }
}
