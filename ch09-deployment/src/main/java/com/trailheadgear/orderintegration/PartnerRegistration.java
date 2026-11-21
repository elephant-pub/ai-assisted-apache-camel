package com.trailheadgear.orderintegration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * UC-007 at startup (design 003, Flow partner registration): register the Partners of the released Partner
 * Configuration with their API Keys from the Secret Store.
 *
 * <p>The bean runs while the Spring context refreshes, after Flyway ({@code @DependsOn("flywayInitializer")}); the Camel
 * routes start only after the refresh, so no partner request arrives before the registry is ready. Every problem of the
 * configuration is collected and logged at ERROR; a configuration with a problem changes no row, and the Partners
 * registered before stay registered with their keys re-read (BR-005, OQ-059 reading a, C-3). The application keeps
 * running. Log lines name Partners and key names, never a key or an Order Contact.
 */
public class PartnerRegistration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(PartnerRegistration.class);
    private static final String SCHEMA = "schemas/partner-configuration.schema.json";

    /** One Partner as the Partner Configuration names it. */
    record Configured(String partnerId, String shopName, long first, long last, String orderContact, String apiKeyName) {
    }

    /** The outcome of checking one Partner Configuration: every problem, and the Partners with their keys when valid. */
    record Check(List<String> problems, List<Configured> partners, Map<String, String> keysByPartnerId) {
        boolean valid() {
            return problems.isEmpty();
        }

        /** Never prints the keys. */
        @Override
        public String toString() {
            return "Check[problems=" + problems + ", partners=" + partners.size() + "]";
        }
    }

    private final DataSource dataSource;
    private final SecretStore secretStore;
    private final PartnerRegistry registry;
    private final String configurationFile;
    private final Pattern emailPattern;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public PartnerRegistration(DataSource dataSource, SecretStore secretStore, PartnerRegistry registry,
        String configurationFile, String emailPattern) {
        this.dataSource = dataSource;
        this.secretStore = secretStore;
        this.registry = registry;
        this.configurationFile = configurationFile;
        this.emailPattern = Pattern.compile(emailPattern);
    }

    @Override
    public void afterPropertiesSet() {
        register();
    }

    /** UC-007 steps 5-8: check, then register the file (valid) or keep the Partners registered before (invalid). */
    void register() {
        Check check = check(Path.of(configurationFile));
        List<String> problems = new ArrayList<>(check.problems());
        Map<String, PartnerRegistry.Partner> byKey = null;
        if (check.valid()) {
            try {
                byKey = store(check);
            } catch (RuntimeException e) {
                problems.add("the Partner Configuration cannot be stored (" + e.getClass().getName() + ")");
            }
        }
        if (byKey != null) {
            registry.register(byKey);
            LOG.info("Partner Configuration registered partners={}", byKey.size());
            return;
        }
        problems.forEach(problem -> LOG.error("Partner Configuration not registered: {}", problem));
        registry.register(previouslyRegistered());
        LOG.warn("Partner Configuration not registered; the partners registered before stay registered partners={}",
            registry.size());
    }

    /** Reads and checks one Partner Configuration file; a missing or unreadable file is a problem like any other. */
    Check check(Path file) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return new Check(List.of("the Partner Configuration file " + file + " cannot be read"), List.of(), Map.of());
        }
        return check(json);
    }

    /** UC-007 steps 5-7 and C-1, C-5: collects every problem; it does not stop at the first. */
    Check check(String json) {
        return checkConfiguration(json, emailPattern, secretStore);
    }

    /**
     * Design 006 T10 (D9): UC-007's checks of a Partner Configuration (schema, Order Number Ranges, overlaps, Order
     * Contacts, safe API Key names) without the Secret Store and without a database; every problem, in the order
     * {@link #check(String)} reports them. For the build's check of the released file.
     */
    static List<String> problemsWithoutKeys(String json, String emailPattern) {
        return checkConfiguration(json, Pattern.compile(emailPattern), null).problems();
    }

    /** The checks of {@link #check(String)}; with no {@code secretStore} the API Keys are not read (T10). */
    private static Check checkConfiguration(String json, Pattern emailPattern, SecretStore secretStore) {
        List<String> problems = new ArrayList<>();
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            return new Check(List.of("the Partner Configuration is not JSON"), List.of(), Map.of());
        }
        for (com.networknt.schema.Error error : schema().validate(json, InputFormat.JSON)) {
            String location = error.getInstanceLocation() == null ? "$"
                : CannotReadReason.toPath(error.getInstanceLocation().toString());
            problems.add("the Partner Configuration does not match its schema at " + location + " ("
                + error.getKeyword() + ")");
        }
        if (!problems.isEmpty()) {
            return new Check(problems, List.of(), Map.of());
        }

        List<Configured> partners = new ArrayList<>();
        for (JsonNode node : root.get("partners")) {
            partners.add(new Configured(node.get("partnerId").asText(), node.get("shopName").asText(),
                node.at("/orderNumberRange/first").asLong(), node.at("/orderNumberRange/last").asLong(),
                node.get("orderContact").asText(), node.get("apiKeyName").asText()));
        }

        Map<String, Integer> idCount = new LinkedHashMap<>();
        partners.forEach(p -> idCount.merge(p.partnerId(), 1, Integer::sum));
        idCount.forEach((id, count) -> {
            if (count > 1) {
                problems.add("the partner identifier " + id + " is used " + count + " times");
            }
        });

        for (Configured p : partners) {
            if (p.last() < p.first()) {
                problems.add("partner " + p.partnerId() + ": the Order Number Range " + p.first() + " to " + p.last()
                    + " ends before it starts");
            }
        }
        for (int i = 0; i < partners.size(); i++) {
            for (int j = i + 1; j < partners.size(); j++) {
                Configured a = partners.get(i);
                Configured b = partners.get(j);
                if (a.first() <= a.last() && b.first() <= b.last() && a.first() <= b.last() && b.first() <= a.last()) {
                    problems.add("partners " + a.partnerId() + " and " + b.partnerId()
                        + " have overlapping Order Number Ranges");
                }
            }
        }

        Map<String, String> keysByPartnerId = new LinkedHashMap<>();
        Map<String, String> partnerByKeyHash = new LinkedHashMap<>();
        for (Configured p : partners) {
            if (!emailPattern.matcher(p.orderContact()).matches()) {
                problems.add("partner " + p.partnerId() + ": the Order Contact is not a valid email address");
            }
            if (!DirectorySecretStore.isSafeName(p.apiKeyName())) {
                problems.add("partner " + p.partnerId() + ": the API Key name is not a safe file name "
                    + "(letters, digits, '.', '_', '-', at most 100, no '..')");
                continue;
            }
            if (secretStore == null) {
                // T10: the release is checked without the keys
                continue;
            }
            Optional<String> secret = secretStore.read(p.apiKeyName());
            if (secret.isEmpty()) {
                problems.add("partner " + p.partnerId() + ": the Secret Store has no API Key named " + p.apiKeyName());
                continue;
            }
            String hash = HexFormat.of().formatHex(PartnerRegistry.sha256(secret.get()));
            String other = partnerByKeyHash.putIfAbsent(hash, p.partnerId());
            if (other != null) {
                problems.add("partners " + other + " and " + p.partnerId() + " have the same API Key");
            }
            keysByPartnerId.put(p.partnerId(), secret.get());
        }
        return new Check(problems, partners, keysByPartnerId);
    }

    /** Valid configuration: upsert every Partner and deregister the missing ones, in one transaction (UC-007 step 8). */
    private Map<String, PartnerRegistry.Partner> store(Check check) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return transaction.execute(status -> {
            Map<String, PartnerRegistry.Partner> byKey = new LinkedHashMap<>();
            List<String> codes = new ArrayList<>();
            for (Configured p : check.partners()) {
                Long id = jdbc.queryForObject("""
                    INSERT INTO partner (partner_code, name, api_key_name, order_number_from, order_number_to,
                                         order_contact_email, registered_at)
                    VALUES (?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT (partner_code) DO UPDATE
                    SET name = EXCLUDED.name, api_key_name = EXCLUDED.api_key_name,
                        order_number_from = EXCLUDED.order_number_from, order_number_to = EXCLUDED.order_number_to,
                        order_contact_email = EXCLUDED.order_contact_email,
                        registered_at = CASE
                          WHEN partner.deregistered_at IS NOT NULL
                            OR (partner.name, partner.api_key_name, partner.order_number_from, partner.order_number_to,
                                partner.order_contact_email)
                               IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.api_key_name, EXCLUDED.order_number_from,
                                                 EXCLUDED.order_number_to, EXCLUDED.order_contact_email)
                          THEN now() ELSE partner.registered_at END,
                        deregistered_at = NULL
                    RETURNING id""", Long.class, p.partnerId(), p.shopName(), p.apiKeyName(), p.first(), p.last(),
                    p.orderContact());
                codes.add(p.partnerId());
                byKey.put(check.keysByPartnerId().get(p.partnerId()),
                    new PartnerRegistry.Partner(id, p.partnerId(), p.first(), p.last(), p.orderContact()));
            }
            for (String code : jdbc.queryForList("SELECT partner_code FROM partner WHERE deregistered_at IS NULL",
                String.class)) {
                if (!codes.contains(code)) {
                    jdbc.update("UPDATE partner SET deregistered_at = now() WHERE partner_code = ?", code);
                    LOG.info("Partner deregistered, no longer in the Partner Configuration partnerId={}", code);
                }
            }
            return byKey;
        });
    }

    /** Invalid configuration: the Partners registered before, with their keys re-read; one without a key is left out. */
    private Map<String, PartnerRegistry.Partner> previouslyRegistered() {
        Map<String, PartnerRegistry.Partner> byKey = new LinkedHashMap<>();
        List<String> sharedKeys = new ArrayList<>();
        new JdbcTemplate(dataSource).query("""
            SELECT id, partner_code, api_key_name, order_number_from, order_number_to, order_contact_email
            FROM partner WHERE deregistered_at IS NULL ORDER BY id""", row -> {
            String code = row.getString("partner_code");
            String name = row.getString("api_key_name");
            Optional<String> secret = secretStore.read(name);
            if (secret.isEmpty()) {
                LOG.error("Partner Configuration: registered partner left out, the Secret Store has no API Key named {} "
                    + "partnerId={}", name, code);
                return;
            }
            PartnerRegistry.Partner other = byKey.put(secret.get(), new PartnerRegistry.Partner(row.getLong("id"), code,
                row.getLong("order_number_from"), row.getLong("order_number_to"),
                row.getString("order_contact_email")));
            if (other != null) {
                // A key must identify one partner (C-5): neither of the two keeps it
                LOG.error("Partner Configuration: registered partners left out, they have the same API Key "
                    + "partnerId={} partnerId={}", other.getPartnerId(), code);
                sharedKeys.add(secret.get());
            }
        });
        sharedKeys.forEach(byKey::remove);
        return byKey;
    }

    private static Schema schema() {
        try (InputStream in = PartnerRegistration.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("classpath:" + SCHEMA + " is missing");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("classpath:" + SCHEMA + " cannot be read", e);
        }
    }
}
