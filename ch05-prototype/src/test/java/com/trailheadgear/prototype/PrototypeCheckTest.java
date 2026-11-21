package com.trailheadgear.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.trailhead.catalogcheck.Problem;
import com.trailhead.catalogcheck.RouteCatalogCheck;

class PrototypeCheckTest {

    private static final Path PROTOTYPE = Path.of("prototype");

    private static List<Path> files(String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(PROTOTYPE)) {
            return files.filter(f -> f.toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void everyRoutePassesTheCatalogCheck() throws Exception {
        RouteCatalogCheck check = new RouteCatalogCheck();
        List<Path> routes = files(".camel.yaml");
        List<Problem> problems = new ArrayList<>();
        for (Path route : routes) {
            problems.addAll(check.check(route));
        }
        assertEquals(5, routes.size(), routes::toString);
        assertEquals(List.of(), problems);
    }

    @Test
    void everyScriptRunsTheProjectsCamelVersion() throws IOException {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(".camel-kit/config.properties"))) {
            config.load(reader);
        }
        String version = config.getProperty("project.camelVersion");
        Pattern jbang = Pattern.compile("jbang(.*?)camel@apache/camel");
        int commands = 0;
        for (Path script : files(".sh")) {
            Matcher m = jbang.matcher(Files.readString(script));
            while (m.find()) {
                commands++;
                assertEquals(" -Dcamel.jbang.version=" + version + " ", m.group(1), script + ": " + m.group());
            }
        }
        assertEquals(3, commands);
    }

    @Test
    void noRouteNamesItsComponentOnlyThroughAPlaceholder() throws IOException {
        for (Path route : files(".camel.yaml")) {
            assertFalse(Pattern.compile("uri: \"?\\{\\{").matcher(Files.readString(route)).find(), route.toString());
        }
    }
}
