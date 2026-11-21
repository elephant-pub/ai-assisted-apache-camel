package com.trailhead.catalogcheck;

import com.networknt.schema.Error;
import java.io.IOException;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.EndpointValidationResult;
import org.apache.camel.dsl.yaml.validator.YamlValidator;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Checks YAML routes against the Camel catalog on the classpath: the YAML DSL schema and the component names,
 * endpoint options and declared values in the endpoint shapes collected by {@link EndpointUris}.
 */
public class RouteCatalogCheck {

    private final CamelCatalog catalog = new DefaultCamelCatalog(true);
    private final YamlValidator schema = new YamlValidator();

    public RouteCatalogCheck() throws Exception {
        schema.init();
    }

    public String camelVersion() {
        return catalog.getCatalogVersion();
    }

    public List<Problem> check(Path file) throws Exception {
        List<Problem> problems = new ArrayList<>();
        String name = file.getFileName().toString();

        for (Error e : schema.validate(file.toFile())) {
            problems.add(new Problem(name, "schema", String.valueOf(e.getInstanceLocation()), e.getMessage()));
        }
        for (String uri : EndpointUris.find(parse(file))) {
            problems.addAll(checkEndpoint(name, uri));
        }
        return problems;
    }

    List<Problem> checkEndpoint(String file, String uri) {
        List<Problem> problems = new ArrayList<>();
        try {
            uri = withPath(uri);
        } catch (URISyntaxException e) {
            problems.add(new Problem(file, "syntax", uri, e.getMessage()));
            return problems;
        } catch (IllegalArgumentException e) {
            // a URI that is only a placeholder, such as {{catalog.url}}, names no component the catalog can check
            problems.add(new Problem(file, "unknown-component", uri, e.getMessage()));
            return problems;
        }
        EndpointValidationResult r = catalog.validateEndpointProperties(uri);
        if (r.getUnknownComponent() != null) {
            problems.add(new Problem(file, "unknown-component", uri, r.getUnknownComponent()));
            return problems;
        }
        if (r.getSyntaxError() != null) {
            problems.add(new Problem(file, "syntax", uri, r.getSyntaxError()));
        }
        for (Object option : r.getUnknown() == null ? List.of() : r.getUnknown()) {
            Object hints = r.getUnknownSuggestions() == null ? null : r.getUnknownSuggestions().get(option);
            problems.add(new Problem(file, "unknown-option", uri,
                    option + (hints instanceof String[] s && s.length > 0 ? " (did you mean " + String.join(", ", s) + "?)" : "")));
        }
        addAll(problems, file, "invalid-enum", uri, r.getInvalidEnum());
        addAll(problems, file, "invalid-boolean", uri, r.getInvalidBoolean());
        addAll(problems, file, "invalid-integer", uri, r.getInvalidInteger());
        addAll(problems, file, "invalid-number", uri, r.getInvalidNumber());
        addAll(problems, file, "invalid-duration", uri, r.getInvalidDuration());
        return problems;
    }

    /**
     * YAML routes may name only the component in {@code uri} and put every option, path ones included, under
     * {@code parameters}: {@code uri: platform-http} with {@code path: /api/orders}. The catalog builds the full URI.
     */
    private String withPath(String uri) throws URISyntaxException {
        String[] parts = uri.split("\\?", 2);
        if (parts[0].contains(":") || parts.length == 1) {
            return uri;
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String pair : parts[1].split("&")) {
            String[] kv = pair.split("=", 2);
            options.put(kv[0], kv.length > 1 ? kv[1] : "");
        }
        return catalog.asEndpointUri(parts[0], options, false);
    }

    private static void addAll(List<Problem> problems, String file, String kind, String uri, Map<?, ?> invalid) {
        if (invalid != null) {
            invalid.forEach((option, value) -> problems.add(new Problem(file, kind, uri, option + "=" + value)));
        }
    }

    private static Object parse(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return new Load(LoadSettings.builder().build()).loadFromReader(reader);
        }
    }

    public static void main(String[] args) throws Exception {
        RouteCatalogCheck check = new RouteCatalogCheck();
        int total = 0;
        for (String arg : args) {
            List<Problem> problems = check.check(Path.of(arg));
            problems.forEach(System.out::println);
            total += problems.size();
        }
        System.out.printf("Camel %s catalog: %d problem(s) in %d file(s)%n", check.camelVersion(), total, args.length);
        System.exit(total == 0 ? 0 : 1);
    }
}
