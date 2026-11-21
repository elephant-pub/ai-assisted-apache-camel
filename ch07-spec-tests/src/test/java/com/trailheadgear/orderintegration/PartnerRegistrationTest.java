package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC-007 checks of the Partner Configuration (design 003 Flow partner registration, C-1, C-5) without a database, and
 * the registry's authentication. Keys are test values; no assertion message prints one.
 */
class PartnerRegistrationTest {

    private static final String EMAIL_PATTERN = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$";

    @TempDir
    Path secrets;

    private PartnerRegistration registration;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(secrets.resolve("key-a"), "secret-a\n");
        Files.writeString(secrets.resolve("key-b"), "secret-b");
        Files.writeString(secrets.resolve("key-same"), "secret-a\n");
        Files.writeString(secrets.resolve("key-empty"), "\n");
        registration = new PartnerRegistration(null, new DirectorySecretStore(secrets), new PartnerRegistry(),
            "unused", EMAIL_PATTERN);
    }

    private static String partner(String id, long first, long last, String contact, String keyName) {
        return "{\"partnerId\":\"" + id + "\",\"shopName\":\"Shop " + id + "\",\"orderNumberRange\":{\"first\":" + first
            + ",\"last\":" + last + "},\"orderContact\":\"" + contact + "\",\"apiKeyName\":\"" + keyName + "\"}";
    }

    private static String configuration(String... partners) {
        return "{\"partners\":[" + String.join(",", partners) + "]}";
    }

    private List<String> problems(String... partners) {
        return registration.check(configuration(partners)).problems();
    }

    @Test
    void validConfiguration() {
        PartnerRegistration.Check check = registration.check(configuration(
            partner("a", 1000, 1999, "orders@a.example.com", "key-a"),
            partner("b", 2000, 2999, "orders@b.example.com", "key-b")));
        assertTrue(check.valid(), String.valueOf(check.problems()));
        assertEquals(2, check.partners().size());
        assertEquals(2, check.keysByPartnerId().size());
    }

    @Test
    void duplicateIdentifier() {
        List<String> problems = problems(partner("a", 1000, 1999, "o@a.example.com", "key-a"),
            partner("a", 2000, 2999, "o@a.example.com", "key-b"));
        assertEquals(List.of("the partner identifier a is used 2 times"), problems);
    }

    @Test
    void rangeEndsBeforeItStarts() {
        assertEquals(List.of("partner a: the Order Number Range 1999 to 1000 ends before it starts"),
            problems(partner("a", 1999, 1000, "o@a.example.com", "key-a")));
    }

    @Test
    void overlappingRangesNameBothPartners() {
        assertEquals(List.of("partners a and b have overlapping Order Number Ranges"),
            problems(partner("a", 1000, 1999, "o@a.example.com", "key-a"),
                partner("b", 1999, 2999, "o@b.example.com", "key-b")));
    }

    @Test
    void invalidOrderContactIsNotEchoed() {
        List<String> problems = problems(partner("a", 1000, 1999, "not-an-address", "key-a"));
        assertEquals(List.of("partner a: the Order Contact is not a valid email address"), problems);
    }

    @Test
    void unsafeKeyNameRejected() {
        List<String> problems = problems(partner("a", 1000, 1999, "o@a.example.com", "../x"));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("partner a: the API Key name is not a safe file name"));
        assertFalse(new DirectorySecretStore(secrets).read("../x").isPresent());
        assertFalse(DirectorySecretStore.isSafeName("a/b"));
        assertFalse(DirectorySecretStore.isSafeName(".."));
    }

    @Test
    void missingOrEmptyKeyFile() {
        assertEquals(List.of("partner a: the Secret Store has no API Key named key-missing",
                "partner b: the Secret Store has no API Key named key-empty"),
            problems(partner("a", 1000, 1999, "o@a.example.com", "key-missing"),
                partner("b", 2000, 2999, "o@b.example.com", "key-empty")));
    }

    @Test
    void sameKeyValueForTwoPartners() {
        List<String> problems = problems(partner("a", 1000, 1999, "o@a.example.com", "key-a"),
            partner("b", 2000, 2999, "o@b.example.com", "key-same"));
        assertEquals(List.of("partners a and b have the same API Key"), problems);
        assertFalse(problems.get(0).contains("secret-a"));
    }

    @Test
    void allProblemsCollectedInOneRun() {
        List<String> problems = problems(partner("a", 1000, 1999, "bad", "key-a"),
            partner("a", 1500, 1400, "o@a.example.com", "../x"),
            partner("c", 1900, 2100, "o@c.example.com", "key-missing"));
        // duplicate a, range of the second a, a/c overlap, contact of the first a, unsafe name, missing key of c
        assertEquals(6, problems.size(), String.valueOf(problems));
    }

    @Test
    void schemaViolationAndMissingFile() {
        assertFalse(registration.check("{\"partners\":[{\"partnerId\":\"a\"}]}").valid());
        assertEquals(List.of("the Partner Configuration is not JSON"), registration.check("{not json").problems());
        assertFalse(registration.check(secrets.resolve("absent.json")).valid());
    }

    @Test
    void registryAuthenticatesOnlyTheRightKey() {
        PartnerRegistry registry = new PartnerRegistry();
        PartnerRegistry.Partner a = new PartnerRegistry.Partner(1, "a", 1000, 1999, "o@a.example.com");
        PartnerRegistry.Partner b = new PartnerRegistry.Partner(2, "b", 2000, 2999, "o@b.example.com");
        registry.register(Map.of("secret-a", a, "secret-b", b));
        assertSame(a, registry.authenticate("secret-a"));
        assertSame(b, registry.authenticate("secret-b"));
        assertNull(registry.authenticate("unknown"));
        assertNull(registry.authenticate(""));
        assertNull(registry.authenticate(null));
    }
}
