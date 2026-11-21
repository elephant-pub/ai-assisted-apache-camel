package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Carried item 3: every missing variable named in one failure, sorted; a default or an empty value is no failure. */
class RequiredVariablesCheckTest {

    private static final String CONFIG_SOURCE =
        "Config resource 'class path resource [application-default.properties]' via location 'optional:classpath:/'";

    private static StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        environment.getPropertySources().addLast(new MapPropertySource(CONFIG_SOURCE,
            Map.of("a", "${Y}", "b", "x-${X}", "c", "${Z:d}")));
        return environment;
    }

    @Test
    void namesEveryMissingVariableSortedAndSkipsDefaults() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RequiredVariablesCheck.check(environment(new HashMap<>())));
        assertEquals("Missing environment variables: X, Y", failure.getMessage());
    }

    @Test
    void passesWhenEverythingIsSetEvenIfEmpty() {
        assertDoesNotThrow(() -> RequiredVariablesCheck.check(environment(Map.of("X", "", "Y", "value"))));
    }

    @Test
    void namesOnlyTheOneMissingVariable() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RequiredVariablesCheck.check(environment(Map.of("X", "1"))));
        assertEquals("Missing environment variables: Y", failure.getMessage());
    }

    @Test
    void ignoresSourcesThatAreNotConfigFiles() {
        StandardEnvironment environment = environment(Map.of("X", "1", "Y", "2"));
        environment.getPropertySources().addLast(new MapPropertySource("systemEnvironment-like", Map.of("q", "${NOPE}")));
        assertDoesNotThrow(() -> RequiredVariablesCheck.check(environment));
    }
}
