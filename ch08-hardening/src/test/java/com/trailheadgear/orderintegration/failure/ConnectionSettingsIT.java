package com.trailheadgear.orderintegration.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.zaxxer.hikari.HikariDataSource;

/**
 * T12 (design 005 §3.3 H3 and "Flyway's connection", decision 3; plan P-9): the connection settings of the shared
 * {@code application.properties} reach both data paths. A pool connection has a socket (network) timeout of 5000 ms
 * and the pool a connection timeout of 2000 ms; Flyway's own connection, which the migration may hold for long, has
 * no network timeout (0). Only the DataSource, Hikari and Flyway auto-configurations start (no Camel, no Kafka), on a
 * PostgreSQL container of the pinned image.
 */
class ConnectionSettingsIT {

    private static final String PROPERTIES = "spec-test/spec-test.properties";
    private static final String IMAGE_KEY = "spec-test.image.postgres";
    private static final Pattern PINNED = Pattern.compile(
        "^([a-z0-9][a-z0-9._/:-]*?)(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?@sha256:([0-9a-f]{64})$");

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void start() {
        postgres = new PostgreSQLContainer(DockerImageName.parse(pinnedImage()).asCompatibleSubstituteFor("postgres"));
        postgres.start();
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void poolAndFlywayConnectionsCarryTheirOwnTimeouts() throws Exception {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
            .withUserConfiguration(RecordingCallbackConfiguration.class)
            .withPropertyValues(
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword())
            .run(context -> {
                assertThat(context).hasNotFailed();
                HikariDataSource pool = context.getBean(HikariDataSource.class);
                int poolNetworkTimeout;
                try (Connection connection = pool.getConnection()) {
                    poolNetworkTimeout = connection.getNetworkTimeout();
                }
                RecordingCallback callback = context.getBean(RecordingCallback.class);

                assertThat(callback.migrations.get()).as("Flyway ran beforeMigrate once").isEqualTo(1);
                // all three are measured and reported together
                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(poolNetworkTimeout).as("pool connection getNetworkTimeout() (ms)")
                        .isEqualTo(5000);
                    softly.assertThat(pool.getConnectionTimeout()).as("HikariDataSource.getConnectionTimeout() (ms)")
                        .isEqualTo(2000);
                    softly.assertThat(callback.networkTimeout).as("Flyway connection getNetworkTimeout() (ms)")
                        .isEqualTo(0);
                });
            });
    }

    /** Flyway callback bean: records the network timeout of the connection Flyway migrates with. */
    static final class RecordingCallback implements Callback {

        final AtomicInteger migrations = new AtomicInteger();
        volatile int networkTimeout = -1;

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            try {
                networkTimeout = context.getConnection().getNetworkTimeout();
                migrations.incrementAndGet();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String getCallbackName() {
            return "recording-network-timeout";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingCallbackConfiguration {

        @Bean
        RecordingCallback recordingCallback() {
            return new RecordingCallback();
        }
    }

    private static String pinnedImage() {
        Properties properties = new Properties();
        try (InputStream in = ConnectionSettingsIT.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + PROPERTIES + " not found");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var m = PINNED.matcher(properties.getProperty(IMAGE_KEY, "").trim());
        if (!m.matches()) {
            throw new IllegalStateException(IMAGE_KEY + " is not pinned by digest");
        }
        return m.group(1) + "@sha256:" + m.group(2);
    }
}
