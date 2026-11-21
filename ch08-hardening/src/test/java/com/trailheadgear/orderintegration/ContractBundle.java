package com.trailheadgear.orderintegration;

import java.nio.file.Files;
import java.nio.file.Path;

import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

/**
 * Bundles an OpenAPI 3.1 contract into one file: its relative $refs to docs/messages/*.schema.json become inline
 * schemas, so the contract is complete on the classpath, where the parser has no base location to resolve them.
 * Runs at prepare-package (pom.xml); docs/ stays the single source.
 */
public final class ContractBundle {

    static OpenAPI bundle(Path contract) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);
        if (result.getOpenAPI() == null || !result.getMessages().isEmpty()) {
            throw new IllegalStateException("Cannot bundle " + contract + ": " + result.getMessages());
        }
        return result.getOpenAPI();
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[1]);
        Files.createDirectories(out.getParent());
        Files.writeString(out, Yaml31.pretty(bundle(Path.of(args[0]))));
    }
}
