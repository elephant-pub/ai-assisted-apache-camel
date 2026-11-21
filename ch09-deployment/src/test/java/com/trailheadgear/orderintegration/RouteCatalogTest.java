package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.EndpointValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Design 006 D11 / T7: every endpoint of the YAML routes (uri plus parameters) is known to the Camel 4.22.2 catalog:
 * the component, every option and its type, and the option fits the endpoint's role (a consumer option on a
 * {@code from}, a producer option elsewhere). A property placeholder ({@code {{…}}}) or a simple expression
 * ({@code ${…}}) counts as a valid value, because its type is only known at run time. Reads the catalog from the test
 * classpath: no network. The files are read with snakeyaml-engine (YAML 1.2), the parser of Camel's YAML DSL, so
 * {@code yes}/{@code no} and the like reach the check as the route gives them to Camel.
 * <p>
 * Not checked: options of a component that accepts unknown ("lenient") options, such as {@code http}, where an unknown
 * name is passed on as a query parameter of the call.
 */
class RouteCatalogTest {

    private static final Path ROUTES = Path.of("src/main/resources/camel");
    private static final String CAMEL_VERSION = "4.22.2";
    private static final Set<String> ENDPOINT_KEYS = Set.of("from", "to", "toD", "wireTap", "enrich", "pollEnrich");
    private static final CamelCatalog CATALOG = new DefaultCamelCatalog();

    record Endpoint(String stepId, String uri, boolean consumer) {
    }

    static Stream<Path> routeFiles() throws IOException {
        try (Stream<Path> files = Files.list(ROUTES)) {
            List<Path> list = files.filter(p -> p.getFileName().toString().endsWith(".camel.yaml")).sorted().toList();
            assertFalse(list.isEmpty(), "no *.camel.yaml under " + ROUTES);
            return list.stream();
        }
    }

    @Test
    void theCatalogIsCamel4222() {
        assertEquals(CAMEL_VERSION, CATALOG.getCatalogVersion(), "camel-catalog on the test classpath");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routeFiles")
    void everyEndpointIsKnownToTheCatalog(Path file) throws IOException {
        List<Endpoint> endpoints = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            collect(new Load(LoadSettings.builder().build()).loadFromReader(reader), "-", endpoints);
        }
        assertFalse(endpoints.isEmpty(), file + ": no endpoint found (the walker no longer matches the YAML DSL)");
        List<String> problems = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            EndpointValidationResult result = CATALOG.validateEndpointProperties(endpoint.uri(), true,
                    endpoint.consumer(), !endpoint.consumer());
            problems.addAll(problems(file, endpoint, result));
        }
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** Walks the YAML tree; an endpoint is a from/to/… key whose value is a map with "uri" (or a URI string). */
    @SuppressWarnings("unchecked")
    private static void collect(Object node, String stepId, List<Endpoint> out) {
        if (node instanceof Map<?, ?> map) {
            String id = map.get("id") instanceof String s ? s : stepId;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if ("parameters".equals(key)) {
                    continue; // endpoint options, never steps (smtp has an option named "from")
                }
                if (ENDPOINT_KEYS.contains(key) && value instanceof Map<?, ?> endpoint
                        && endpoint.get("uri") instanceof String uri) {
                    String endpointId = endpoint.get("id") instanceof String s ? s : id;
                    out.add(new Endpoint(endpointId, uri(uri, (Map<String, Object>) endpoint.get("parameters")),
                            "from".equals(key)));
                } else if (ENDPOINT_KEYS.contains(key) && value instanceof String uri) {
                    out.add(new Endpoint(id, uri, "from".equals(key)));
                }
                collect(value, id, out);
            }
        } else if (node instanceof List<?> list) {
            list.forEach(item -> collect(item, stepId, out));
        }
    }

    private static String uri(String base, Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return base;
        }
        String query = parameters.entrySet().stream()
                .map(p -> p.getKey() + "=" + URLEncoder.encode(String.valueOf(p.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return base + (base.contains("?") ? "&" : "?") + query;
    }

    private static List<String> problems(Path file, Endpoint endpoint, EndpointValidationResult result) {
        String where = file.getFileName() + " step " + endpoint.stepId() + ": ";
        List<String> problems = new ArrayList<>();
        if (result.getSyntaxError() != null) {
            problems.add(where + "URI not parsed: " + result.getSyntaxError());
        }
        if (result.getIncapable() != null) {
            problems.add(where + "URI not validated by the catalog: " + result.getIncapable());
        }
        if (result.getUnknownComponent() != null) {
            problems.add(where + "unknown component " + result.getUnknownComponent());
        }
        if (result.getUnknown() != null) {
            result.getUnknown().forEach(o -> problems.add(where + "option " + o + ": unknown to the " + CAMEL_VERSION
                    + " catalog" + suggestion(result, o)));
        }
        if (result.getNotConsumerOnly() != null) {
            result.getNotConsumerOnly().forEach(o -> problems.add(where + "option " + o
                    + ": a producer option on a consumer endpoint (from)"));
        }
        if (result.getNotProducerOnly() != null) {
            result.getNotProducerOnly().forEach(o -> problems.add(where + "option " + o
                    + ": a consumer option on a producer endpoint"));
        }
        if (result.getRequired() != null) {
            result.getRequired().forEach(o -> problems.add(where + "option " + o + ": required, not set"));
        }
        typeErrors(where, "not a boolean", result.getInvalidBoolean(), problems);
        typeErrors(where, "not an integer", result.getInvalidInteger(), problems);
        typeErrors(where, "not a number", result.getInvalidNumber(), problems);
        typeErrors(where, "not one of the allowed values", result.getInvalidEnum(), problems);
        return problems;
    }

    private static void typeErrors(String where, String reason, Map<String, String> errors, List<String> problems) {
        if (errors == null) {
            return;
        }
        errors.forEach((option, value) -> {
            if (!isPlaceholder(value)) {
                problems.add(where + "option " + option + ": value '" + value + "' is " + reason);
            }
        });
    }

    private static boolean isPlaceholder(String value) {
        return value != null && (value.contains("{{") || value.contains("${"));
    }

    private static String suggestion(EndpointValidationResult result, String option) {
        Map<String, String[]> suggestions = result.getUnknownSuggestions();
        String[] names = suggestions == null ? null : suggestions.get(option);
        return names == null || names.length == 0 ? "" : " (did you mean " + String.join(", ", names) + "?)";
    }
}
