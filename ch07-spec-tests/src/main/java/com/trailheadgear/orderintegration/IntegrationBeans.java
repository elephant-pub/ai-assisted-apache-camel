package com.trailheadgear.orderintegration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * The Java beans the YAML routes call by name (design 002 E4). They replace the prototype's
 * {@code camel.beans.*=#class:} entries and keep their bean names. The datasource is Spring Boot's own
 * {@code dataSource} (E3), so it is not declared here.
 */
@Configuration
public class IntegrationBeans {

    /** UC-003 Order Checks, used by the peakmart-order-check routes. */
    @Bean("orderChecks")
    public OrderChecks orderChecks() {
        return new OrderChecks();
    }

    /** Builds the set-aside reason of an Order Event that cannot be read, used by peakmart-order-intake. */
    @Bean("cannotReadReason")
    public CannotReadReason cannotReadReason() {
        return new CannotReadReason();
    }

    /**
     * Threads of the parallel Product Catalog lookups in order-check (design 003, C-13). Every lookup still passes the
     * one shared throttle (BR-014). Spring shuts the pool down with the context; Camel does not own it.
     */
    @Bean(name = "catalogLookupPool", destroyMethod = "shutdown")
    public ExecutorService catalogLookupPool(@Value("${catalog.lookup-threads}") int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "catalog-lookup-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The Secret Store stand-in: one file per API Key in {@code secret-store.directory} (design 003, C-1). */
    @Bean("secretStore")
    public SecretStore secretStore(@Value("${secret-store.directory}") String directory) {
        return new DirectorySecretStore(Path.of(directory));
    }

    /** The registered Partners by API Key, used by partner-order-submit to authenticate a request. */
    @Bean("partnerRegistry")
    public PartnerRegistry partnerRegistry() {
        return new PartnerRegistry();
    }

    /**
     * UC-007 at startup, after Flyway (bean {@code flywayInitializer} of Spring Boot 4.1.1) and before the Camel routes
     * start.
     */
    @Bean("partnerRegistration")
    @DependsOn("flywayInitializer")
    public PartnerRegistration partnerRegistration(DataSource dataSource, SecretStore secretStore,
        PartnerRegistry partnerRegistry, @Value("${partner.configuration}") String configurationFile,
        @Value("${peakmart.email-pattern}") String emailPattern) {
        return new PartnerRegistration(dataSource, secretStore, partnerRegistry, configurationFile, emailPattern);
    }

    /** The partner request rules that are not Order Checks: unreadable requests and the content hash. */
    @Bean("partnerOrders")
    public PartnerOrders partnerOrders(@Value("${peakmart.email-pattern}") String emailPattern) {
        return new PartnerOrders(emailPattern);
    }

    /** The Order Answer and Problem bodies of the partner endpoint. */
    @Bean("orderAnswers")
    public OrderAnswers orderAnswers() {
        return new OrderAnswers();
    }

    /**
     * The "already delivered?" check of pick-list-delivery (SFTP stat, inbound before processed), with the same
     * {@code warehouse.sftp.*} settings as the sftp endpoint, including the known-hosts file.
     */
    @Bean("warehouseSftp")
    public WarehouseSftp warehouseSftp(@Value("${warehouse.sftp.host}") String host,
        @Value("${warehouse.sftp.port}") int port, @Value("${warehouse.sftp.username}") String username,
        @Value("${warehouse.sftp.password}") String password,
        @Value("${warehouse.sftp.known-hosts}") String knownHostsFile,
        @Value("${warehouse.sftp.timeout-ms}") int timeoutMs,
        @Value("${warehouse.sftp.inbound-directory}") String inboundDirectory,
        @Value("${warehouse.sftp.processed-directory}") String processedDirectory) {
        return new WarehouseSftp(host, port, username, password, knownHostsFile, timeoutMs, inboundDirectory,
            processedDirectory);
    }
}
