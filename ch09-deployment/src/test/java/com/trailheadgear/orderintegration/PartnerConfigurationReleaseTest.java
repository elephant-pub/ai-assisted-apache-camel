package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * T10 (design 006 §3.14; §3.9 D9; UC-007): the released Partner Configuration {@code deploy/k8s/base/partner-configuration.json}
 * passes UC-007's own checks (schema {@code partner-configuration.schema.json}, Order Number Ranges, overlaps, Order
 * Contacts, safe API Key names) without the API Keys and without a database, through
 * {@link PartnerRegistration#problemsWithoutKeys(String, String)}. The email rule is the shipped
 * {@code peakmart.email-pattern} of {@code application.properties}, so the test holds no rule of its own.
 */
class PartnerConfigurationReleaseTest {

    private static final Path RELEASED = Path.of("deploy", "k8s", "base", "partner-configuration.json");
    private static final Path INVALID_EXAMPLE =
        Path.of("docs", "messages", "examples", "partner-configuration", "invalid", "api-key-in-configuration.json");

    @Test
    void theReleasedPartnerConfigurationPassesUc007sChecks() throws IOException {
        Path file = baseDir().resolve(RELEASED);
        assertTrue(Files.isRegularFile(file), "T10: " + RELEASED + " does not exist (" + file + ")");
        List<String> problems = PartnerRegistration.problemsWithoutKeys(
            Files.readString(file, StandardCharsets.UTF_8), shippedEmailPattern());
        assertEquals(List.of(), problems, "T10: problems of " + RELEASED);
    }

    /** Proves the checks run: the contract's invalid example (an API Key in the file) is refused. */
    @Test
    void anInvalidPartnerConfigurationHasAProblem() throws IOException {
        List<String> problems = PartnerRegistration.problemsWithoutKeys(
            Files.readString(baseDir().resolve(INVALID_EXAMPLE), StandardCharsets.UTF_8), shippedEmailPattern());
        assertFalse(problems.isEmpty(), "T10: no problem found in " + INVALID_EXAMPLE);
    }

    private static Path baseDir() {
        return Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    }

    /** {@code peakmart.email-pattern} as Spring reads it from the shipped {@code application.properties}. */
    private static String shippedEmailPattern() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = PartnerConfigurationReleaseTest.class.getClassLoader()
            .getResourceAsStream("application.properties")) {
            assertTrue(in != null, "application.properties is not on the classpath");
            properties.load(in);
        }
        String pattern = properties.getProperty("peakmart.email-pattern");
        assertTrue(pattern != null && !pattern.isBlank(), "peakmart.email-pattern is not set in application.properties");
        return pattern;
    }
}
