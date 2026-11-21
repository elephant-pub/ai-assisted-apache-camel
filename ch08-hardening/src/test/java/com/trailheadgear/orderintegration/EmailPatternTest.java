package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * E7 regression test (design 002 R2): {@code peakmart.email-pattern} must keep its doubled backslashes in
 * {@code application.properties}. Without them {@code java.util.Properties} reads {@code \s} as {@code s}, the
 * pattern becomes {@code ^[^@s]+@...}, and every valid address with an {@code s} is set aside.
 *
 * <p>The pattern is loaded the way Spring Boot loads it, and the emails come from the message examples, so neither
 * is repeated here. No Spring or Camel context is started. Failure messages name the file, never the address.
 */
class EmailPatternTest {

    private static final String PROPERTY = "peakmart.email-pattern";
    private static final Path EXAMPLES = Path.of("docs", "messages", "examples", "peakmart-order-placed");
    private static final Path VALID_SAMPLE = EXAMPLES.resolve("peakmart-order-placed-sample.json");
    private static final Path INVALID_SAMPLE = EXAMPLES.resolve("invalid/pm-50119-buyer-email-not-an-email.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Pattern emailPattern;

    @BeforeAll
    static void loadPatternAsSpringBootDoes() throws IOException {
        List<PropertySource<?>> sources = new PropertiesPropertySourceLoader()
                .load("application", new ClassPathResource("application.properties"));
        Object value = sources.isEmpty() ? null : sources.get(0).getProperty(PROPERTY);
        assertNotNull(value, PROPERTY + " is missing from the packaged application.properties");
        emailPattern = Pattern.compile(value.toString());
    }

    @Test
    void acceptsTheBuyerEmailOfTheValidSample() throws IOException {
        String email = buyerEmail(VALID_SAMPLE);
        assertTrue(email.contains("s"),
                "the buyer email of " + VALID_SAMPLE + " must contain an 's' to guard against ^[^@s]+@");
        assertTrue(emailPattern.matcher(email).matches(),
                PROPERTY + " rejects the buyer email of " + VALID_SAMPLE + " (backslashes lost?)");
    }

    @Test
    void rejectsTheBuyerEmailOfPm50119() throws IOException {
        String email = buyerEmail(INVALID_SAMPLE);
        assertFalse(emailPattern.matcher(email).matches(),
                PROPERTY + " accepts the buyer email of " + INVALID_SAMPLE + ", which is not an email address");
    }

    private static String buyerEmail(Path file) throws IOException {
        JsonNode email = MAPPER.readTree(file.toFile()).path("buyer").path("email");
        assertTrue(email.isTextual(), "buyer.email is missing in " + file);
        return email.asText();
    }
}
