package com.trailheadgear.orderintegration;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * One startup check that names every missing environment variable at once (design 003, carried item 3).
 *
 * <p>Runs right after the configuration files are loaded. It reads the raw values of every active
 * {@code application*.properties} source (classpath and {@code ./config/}), collects each {@code ${NAME}} placeholder
 * that has no default, and fails with one message listing every name the environment does not contain, sorted. The
 * list comes from the property files themselves, so a variable added later is covered without a second list. An empty
 * value counts as set. Values are never printed.
 *
 * <p>Registered in {@code META-INF/spring.factories} under {@code org.springframework.boot.EnvironmentPostProcessor}
 * (the Spring Boot 4.1.1 key). Throwing here stops startup before any datasource, Flyway, partner registration or route.
 */
public class RequiredVariablesCheck implements EnvironmentPostProcessor, Ordered {

    /** Spring Boot names config-file sources "Config resource '... [application....properties]' via location '...'". */
    static final String CONFIG_SOURCE_PREFIX = "Config resource";
    /** A placeholder without a default: no ':' inside the braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)}");

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        check(environment);
    }

    /** Throws one {@link IllegalStateException} naming every missing variable, sorted; does nothing when none is missing. */
    static void check(ConfigurableEnvironment environment) {
        Set<String> missing = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!isConfigFile(source) || !(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String key : enumerable.getPropertyNames()) {
                Object raw = enumerable.getProperty(key);
                if (raw == null) {
                    continue;
                }
                Matcher matcher = PLACEHOLDER.matcher(raw.toString());
                while (matcher.find()) {
                    String name = matcher.group(1).trim();
                    if (!environment.containsProperty(name)) {
                        missing.add(name);
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing environment variables: " + String.join(", ", missing));
        }
    }

    private static boolean isConfigFile(PropertySource<?> source) {
        String name = source.getName();
        return name.startsWith(CONFIG_SOURCE_PREFIX) && name.contains("application");
    }
}
