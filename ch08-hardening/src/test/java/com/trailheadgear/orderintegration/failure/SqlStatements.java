package com.trailheadgear.orderintegration.failure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapperResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB-only helper of the failure tests (design 005 §3.9, P-12): one PostgreSQL container from the pinned image of
 * {@code spec-test/spec-test.properties}, the Flyway migrations of {@code classpath:db/migration}, and the production
 * statements of {@code src/main/resources/sql} executed with Spring's named-parameter parsing. Per-class lifecycle:
 * start it in {@code @BeforeAll}, {@link #close()} it in {@code @AfterAll}.
 *
 * <p>The only change to a statement is the translation of Camel's parameter syntax to Spring's: {@code :#name} becomes
 * {@code :name}, and {@code :#${exchangeProperty.some-name}} (or {@code header.}/{@code property.}) becomes
 * {@code :some_name} (a hyphen would end a Spring parameter name). Callers pass the parameters under their Camel
 * names ({@code reasons}, {@code receipt-id}); the keys are normalised the same way. No other rewriting is done.
 */
public final class SqlStatements implements AutoCloseable {

    private static final String PROPERTIES = "spec-test/spec-test.properties";
    private static final String IMAGE_KEY = "spec-test.image.postgres";
    private static final Path SQL_DIR = Path.of("src/main/resources/sql");

    private static final Pattern CAMEL_EXPRESSION =
        Pattern.compile(":#\\$\\{(?:exchangeProperty|header|property)\\.([A-Za-z0-9_.-]+)\\}");
    private static final Pattern CAMEL_NAME = Pattern.compile(":#([A-Za-z_][A-Za-z0-9_]*)");

    /** The outcome of one statement: {@code rows} for a statement that returns rows, else {@code updateCount}. */
    public record Result(List<Map<String, Object>> rows, int updateCount) {
        public boolean hasRows() {
            return rows != null;
        }
    }

    private final PostgreSQLContainer postgres;
    private final JdbcTemplate jdbc;

    private SqlStatements(PostgreSQLContainer postgres, JdbcTemplate jdbc) {
        this.postgres = postgres;
        this.jdbc = jdbc;
    }

    /** Starts the container and migrates it. */
    public static SqlStatements start() {
        String image = pinnedImage();
        PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"));
        postgres.start();
        try {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            return new SqlStatements(postgres, new JdbcTemplate(dataSource));
        } catch (RuntimeException e) {
            postgres.stop();
            throw e;
        }
    }

    /** For fixtures and for reading state back. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** Runs {@code src/main/resources/sql/<sqlFile>} verbatim (apart from the parameter syntax). */
    public Result run(String sqlFile, Map<String, Object> params) {
        try {
            return runText(Files.readString(SQL_DIR.resolve(sqlFile), StandardCharsets.UTF_8), params);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + sqlFile, e);
        }
    }

    /** Runs statement text (a README block, Task 16) the same way as {@link #run(String, Map)}. */
    public Result runText(String sqlText, Map<String, Object> params) {
        String sql = translate(sqlText);
        Map<String, Object> normalised = new HashMap<>();
        params.forEach((key, value) -> normalised.put(normalise(key), value));
        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
        MapSqlParameterSource source = new MapSqlParameterSource(normalised);
        String jdbcSql = NamedParameterUtils.substituteNamedParameters(parsed, source);
        Object[] args = NamedParameterUtils.buildValueArray(parsed, source, null);
        return jdbc.execute(jdbcSql, (java.sql.PreparedStatement ps) -> {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            if (ps.execute()) {
                try (var rs = ps.getResultSet()) {
                    return new Result(new RowMapperResultSetExtractor<>(new ColumnMapRowMapper()).extractData(rs), -1);
                }
            }
            return new Result(null, ps.getUpdateCount());
        });
    }

    @Override
    public void close() {
        postgres.stop();
    }

    static String translate(String sqlText) {
        Matcher expression = CAMEL_EXPRESSION.matcher(sqlText);
        String step = expression.replaceAll(m -> Matcher.quoteReplacement(":" + normalise(m.group(1))));
        return CAMEL_NAME.matcher(step).replaceAll(m -> Matcher.quoteReplacement(":" + m.group(1)));
    }

    private static String normalise(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String pinnedImage() {
        Properties properties = new Properties();
        try (InputStream in = SqlStatements.class.getClassLoader().getResourceAsStream(PROPERTIES)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource " + PROPERTIES + " not found");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String reference = properties.getProperty(IMAGE_KEY, "").trim();
        Matcher m = Pattern.compile("^([a-z0-9][a-z0-9._/:-]*?)(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?@sha256:([0-9a-f]{64})$")
            .matcher(reference);
        if (!m.matches()) {
            throw new IllegalStateException(IMAGE_KEY + " is not pinned by digest: " + reference);
        }
        // by digest alone, as SpecEnvironment does: the digest names the image
        return m.group(1) + "@sha256:" + m.group(2);
    }
}
