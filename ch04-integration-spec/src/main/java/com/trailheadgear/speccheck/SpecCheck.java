///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//DEPS com.networknt:json-schema-validator:3.0.8
//DEPS io.swagger.parser.v3:swagger-parser:2.1.48
//DEPS org.slf4j:slf4j-nop:2.0.17
package com.trailheadgear.speccheck;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.resource.InputStreamSource;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Checks the integration spec under docs/: message schemas and their examples, field mappings between messages,
 * and the OpenAPI and AsyncAPI contracts. Everything it checks is mechanical; people review the meaning.
 */
public class SpecCheck {

    public record Finding(String code, String file, String detail) {
        @Override
        public String toString() {
            return "%s %s: %s".formatted(code, file, detail);
        }
    }

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ObjectMapper YAML = YAMLMapper.builder().build();
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)#]+)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    private final Path docs;
    private final List<Finding> findings = new ArrayList<>();
    private final SchemaRegistry registry;

    public SpecCheck(Path docs) {
        this.docs = docs.toAbsolutePath().normalize();
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                b -> b.resourceLoaders(loaders -> loaders.add(this::load)));
    }

    /** The JSON Schema loader accepts file paths that normalize under docs/. */
    private InputStreamSource load(AbsoluteIri iri) {
        if (!iri.toString().startsWith("file:")) {
            return null;
        }
        Path file = Path.of(URI.create(iri.toString())).normalize();
        return file.startsWith(docs) && Files.isRegularFile(file) ? () -> Files.newInputStream(file) : null;
    }

    public List<Finding> run() {
        Path messages = docs.resolve("messages");
        files(messages, 1, ".schema.json").forEach(this::checkSchema);
        checkExamples(messages.resolve("examples"));
        files(messages.resolve("mappings"), 1, ".md").forEach(this::checkMapping);
        files(docs.resolve("api"), 2, ".openapi.yaml").forEach(this::checkOpenApi);
        files(docs.resolve("api"), 2, ".asyncapi.yaml").forEach(this::checkAsyncApi);
        return findings;
    }

    // --- message schemas and examples

    private void checkSchema(Path file) {
        JsonNode node = readJson(file);
        if (node == null) {
            return;
        }
        if (!node.path("$schema").asString("").contains("2020-12")) {
            add("SCHEMA_DIALECT", file, "declare \"$schema\": \"https://json-schema.org/draft/2020-12/schema\"");
        }
        Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        for (Error e : meta.validate(node)) {
            add("SCHEMA_INVALID", file, e.toString());
        }
        try {
            schema(file).initializeValidators();
        } catch (RuntimeException e) {
            add("SCHEMA_REF", file, e.getMessage());
        }
    }

    private void checkExamples(Path examples) {
        for (Path dir : dirs(examples)) {
            Path schemaFile = docs.resolve("messages").resolve(dir.getFileName() + ".schema.json");
            if (!Files.exists(schemaFile)) {
                add("EXAMPLE_NO_SCHEMA", dir, "no messages/" + schemaFile.getFileName());
                continue;
            }
            Schema schema = schema(schemaFile);
            for (Path example : files(dir, 1, ".json")) {
                List<Error> errors = validate(schema, example);
                if (errors != null && !errors.isEmpty()) {
                    add("EXAMPLE_INVALID", example, errors.getFirst().toString()
                            + (errors.size() > 1 ? " (and %d more)".formatted(errors.size() - 1) : ""));
                }
            }
            for (Path example : files(dir.resolve("invalid"), 1, ".json")) {
                List<Error> errors = validate(schema, example);
                if (errors != null && errors.isEmpty()) {
                    add("EXAMPLE_ACCEPTED", example, "an invalid example passes the schema");
                }
            }
        }
    }

    private Schema schema(Path file) {
        return registry.getSchema(SchemaLocation.of(file.toAbsolutePath().normalize().toUri().toString()));
    }

    private List<Error> validate(Schema schema, Path example) {
        JsonNode node = readJson(example);
        return node == null ? null : schema.validate(node.toString(), InputFormat.JSON,
                ctx -> ctx.executionConfig(c -> c.formatAssertionsEnabled(true)));
    }

    // --- mappings

    /** Field paths of a schema, such as "shipTo.postalCode" or "lines[].sku", with whether each is required. */
    Map<String, Boolean> fields(Path schemaFile) {
        Map<String, Boolean> fields = new LinkedHashMap<>();
        JsonNode root = readJson(schemaFile);
        if (root != null) {
            collect(schemaFile, root, root, "", true, fields, 0);
        }
        return fields;
    }

    private void collect(Path file, JsonNode root, JsonNode node, String prefix, boolean required,
            Map<String, Boolean> fields, int depth) {
        if (depth > 20) {
            return;
        }
        if (node.has("$ref")) {
            String ref = node.get("$ref").asString();
            int hash = ref.indexOf('#');
            Path target = hash == 0 ? file : file.resolveSibling(hash < 0 ? ref : ref.substring(0, hash));
            JsonNode targetRoot = target.equals(file) ? root : readJson(target);
            if (targetRoot == null) {
                return;
            }
            JsonNode resolved = hash < 0 ? targetRoot : targetRoot.at(ref.substring(hash + 1));
            if (!resolved.isMissingNode()) {
                collect(target, targetRoot, resolved, prefix, required, fields, depth + 1);
            }
            return;
        }
        for (String combiner : List.of("allOf", "oneOf", "anyOf")) {
            for (JsonNode part : node.path(combiner)) {
                collect(file, root, part, prefix, required && combiner.equals("allOf"), fields, depth + 1);
            }
        }
        if (node.has("items")) {
            collect(file, root, node.get("items"), prefix + "[]", required, fields, depth + 1);
        }
        Set<String> requiredNames = new LinkedHashSet<>();
        node.path("required").forEach(n -> requiredNames.add(n.asString()));
        for (Map.Entry<String, JsonNode> p : node.path("properties").properties()) {
            String path = prefix.isEmpty() ? p.getKey() : prefix + "." + p.getKey();
            boolean req = required && requiredNames.contains(p.getKey());
            fields.merge(path, req, Boolean::logicalOr);
            collect(file, root, p.getValue(), path, req, fields, depth + 1);
        }
    }

    private void checkMapping(Path file) {
        List<String> lines = readLines(file);
        Path source = linkedSchema(file, lines, "**Source:**");
        Path target = linkedSchema(file, lines, "**Target:**");
        if (target == null) {
            add("MAPPING_HEADER", file, "needs a **Target:** line that links a schema in messages/");
            return;
        }
        Map<String, Boolean> sourceFields = source == null ? Map.of() : fields(source);
        Map<String, Boolean> targetFields = fields(target);
        Set<String> mapped = new LinkedHashSet<>();
        int sourceCol = -1, targetCol = -1;
        for (String line : lines) {
            if (!line.trim().startsWith("|")) {
                sourceCol = targetCol = -1;
                continue;
            }
            String[] cells = line.trim().split("\\|", -1);
            if (sourceCol < 0) {
                for (int i = 0; i < cells.length; i++) {
                    String c = cells[i].trim();
                    if (c.equalsIgnoreCase("Source")) sourceCol = i;
                    if (c.equalsIgnoreCase("Target")) targetCol = i;
                }
                continue;
            }
            if (targetCol < 0 || cells.length <= Math.max(sourceCol, targetCol) || cells[targetCol].contains("---")) {
                continue;
            }
            for (String path : codes(cells[targetCol])) {
                if (!targetFields.containsKey(path)) {
                    add("MAPPING_TARGET_UNKNOWN", file, "`%s` is not a field of %s".formatted(path, target.getFileName()));
                }
                mapped.add(path);
            }
            if (source != null) {
                for (String path : codes(cells[sourceCol])) {
                    if (!sourceFields.containsKey(path)) {
                        add("MAPPING_SOURCE_UNKNOWN", file, "`%s` is not a field of %s".formatted(path, source.getFileName()));
                    }
                }
            }
        }
        for (Map.Entry<String, Boolean> f : targetFields.entrySet()) {
            boolean covered = mapped.stream().anyMatch(m -> f.getKey().equals(m) || f.getKey().startsWith(m + ".")
                    || f.getKey().startsWith(m + "[]"));
            boolean hasChildren = targetFields.keySet().stream().anyMatch(k -> k.startsWith(f.getKey() + ".")
                    || k.startsWith(f.getKey() + "[]."));
            if (f.getValue() && !covered && !hasChildren) {
                add("MAPPING_UNMAPPED", file, "required field `%s` of %s has no row".formatted(f.getKey(), target.getFileName()));
            }
        }
    }

    private Path linkedSchema(Path file, List<String> lines, String label) {
        for (String line : lines) {
            if (line.startsWith(label)) {
                Matcher m = LINK.matcher(line);
                if (m.find()) {
                    Path schema = file.resolveSibling(m.group(1)).normalize();
                    if (Files.exists(schema)) {
                        return schema;
                    }
                    add("MAPPING_HEADER", file, label + " links a missing file " + m.group(1));
                }
            }
        }
        return null;
    }

    private static List<String> codes(String cell) {
        List<String> codes = new ArrayList<>();
        Matcher m = CODE.matcher(cell);
        while (m.find()) {
            codes.add(m.group(1).trim());
        }
        return codes;
    }

    // --- contracts

    private void checkOpenApi(Path file) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(file.toAbsolutePath().toString(), null, options);
        if (result.getMessages() != null) {
            result.getMessages().forEach(m -> add("OPENAPI_ERROR", file, m));
        }
        OpenAPI api = result.getOpenAPI();
        if (api == null || api.getPaths() == null) {
            return;
        }
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            String where = method + " " + path;
            if (op.getOperationId() == null) {
                add("OPENAPI_NO_OPERATION_ID", file, where);
            }
            if (!rejects(op)) {
                add("OPENAPI_NO_REJECTION", file, where + " documents no 4xx response");
            }
        }));
    }

    private static boolean rejects(Operation op) {
        return op.getResponses() != null && op.getResponses().keySet().stream()
                .anyMatch(code -> code.startsWith("4") || code.equals("default"));
    }

    private void checkAsyncApi(Path file) {
        JsonNode doc = readYaml(file);
        if (doc == null) {
            return;
        }
        if (!doc.path("asyncapi").asString("").startsWith("3.")) {
            add("ASYNCAPI_VERSION", file, "write AsyncAPI 3.x (asyncapi: 3.0.0)");
        }
        checkRefs(file, doc, doc);
        for (Map.Entry<String, JsonNode> op : doc.path("operations").properties()) {
            String action = op.getValue().path("action").asString("");
            if (!action.equals("send") && !action.equals("receive")) {
                add("ASYNCAPI_OPERATION", file, op.getKey() + " needs action send or receive");
            }
            if (!op.getValue().path("channel").has("$ref")) {
                add("ASYNCAPI_OPERATION", file, op.getKey() + " needs a channel $ref");
            }
        }
        List<Map.Entry<String, JsonNode>> messages = new ArrayList<>(doc.path("components").path("messages").properties());
        for (Map.Entry<String, JsonNode> channel : doc.path("channels").properties()) {
            messages.addAll(channel.getValue().path("messages").properties());
        }
        Set<JsonNode> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Map.Entry<String, JsonNode> msg : messages) {
            JsonNode resolved = resolve(file, doc, msg.getValue());
            if (resolved != null && seen.add(resolved) && !resolved.has("payload")) {
                add("ASYNCAPI_MESSAGE", file, msg.getKey() + " has no payload");
            }
        }
    }

    private void checkRefs(Path file, JsonNode doc, JsonNode node) {
        if (node.isObject()) {
            if (node.has("$ref") && resolve(file, doc, node) == null) {
                add("ASYNCAPI_REF", file, "unresolved $ref " + node.get("$ref").asString());
            }
            node.properties().forEach(e -> checkRefs(file, doc, e.getValue()));
        } else if (node.isArray()) {
            node.forEach(n -> checkRefs(file, doc, n));
        }
    }

    private JsonNode resolve(Path file, JsonNode doc, JsonNode node) {
        if (!node.has("$ref")) {
            return node;
        }
        String ref = node.get("$ref").asString();
        int hash = ref.indexOf('#');
        JsonNode target = doc;
        if (hash != 0) {
            Path other = file.resolveSibling(hash < 0 ? ref : ref.substring(0, hash)).normalize();
            if (!Files.exists(other)) {
                return null;
            }
            target = other.toString().endsWith(".json") ? readJson(other) : readYaml(other);
            if (target == null) {
                return null;
            }
        }
        JsonNode resolved = hash < 0 ? target : target.at(ref.substring(hash + 1));
        return resolved.isMissingNode() ? null : resolved;
    }

    // --- files

    private JsonNode readJson(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (RuntimeException e) {
            add("JSON_ERROR", file, e.getMessage().lines().findFirst().orElse(""));
            return null;
        }
    }

    private JsonNode readYaml(Path file) {
        try {
            return YAML.readTree(file.toFile());
        } catch (RuntimeException e) {
            add("YAML_ERROR", file, e.getMessage().lines().findFirst().orElse(""));
            return null;
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> files(Path dir, int depth, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(dir, depth)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> dirs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void add(String code, Path file, String detail) {
        findings.add(new Finding(code, docs.relativize(file.toAbsolutePath().normalize()).toString(), detail));
    }

    public static void main(String[] args) {
        Path docs = Path.of(args.length > 0 ? args[0] : "docs");
        List<Finding> findings = new SpecCheck(docs).run();
        findings.forEach(System.out::println);
        System.out.printf("Spec check: %d finding(s)%n", findings.size());
        System.exit(findings.isEmpty() ? 0 : 1);
    }
}
