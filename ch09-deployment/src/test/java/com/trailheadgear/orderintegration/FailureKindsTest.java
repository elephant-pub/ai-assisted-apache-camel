package com.trailheadgear.orderintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.stream.Stream;

import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.kafka.common.KafkaException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * T2 (design 005 §3.9): the classification table of H4/H5 over the cause chain, and {@code describe()} (§3.5
 * {@code <kind>}, §3.7 examples, §4 Security, P-7). Every failure is a real exception class of the dependencies,
 * wrapped at least once as the routes see it. Message texts carry a name and an email address; they must never come
 * out of {@code describe()}.
 */
class FailureKindsTest {

    private static final String NAME = "Jane Doe";
    private static final String EMAIL = "jane.doe@example.com";
    private static final String PERSONAL = "for " + NAME + " <" + EMAIL + ">";

    private static CamelContext context;
    private static Exchange exchange;
    private static CamelExecutionException jsonValidationFailure;

    private final FailureKinds failureKinds = new FailureKinds();

    @BeforeAll
    static void startContext() throws Exception {
        context = new DefaultCamelContext();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:validate").routeId("failure-kinds-test-validate")
                    .to("json-validator:classpath:schemas/order.schema.json");
            }
        });
        context.start();
        exchange = new DefaultExchange(context);
        String invalidOrder = Files.readString(Path.of(System.getProperty("basedir", "."))
            .resolve("docs/messages/examples/order/invalid/no-lines.json"), StandardCharsets.UTF_8);
        Throwable thrown = catchThrowable(() -> context.createProducerTemplate()
            .requestBody("direct:validate", invalidOrder));
        assertThat(thrown).isInstanceOf(CamelExecutionException.class)
            .hasRootCauseInstanceOf(JsonValidationException.class);
        jsonValidationFailure = (CamelExecutionException) thrown;
    }

    @AfterAll
    static void stopContext() {
        context.stop();
    }

    // ---- the cases, each wrapped at least once ----

    private static CamelExecutionException camel(Throwable cause) {
        return new CamelExecutionException("Exception occurred during execution " + PERSONAL, exchange, cause);
    }

    private static UncategorizedSQLException jdbc(SQLException cause) {
        return new UncategorizedSQLException("insert order_receipt", "insert into order_receipt values (?)", cause);
    }

    private static PSQLException psql(String message, PSQLState state) {
        return new PSQLException(message + " " + PERSONAL, state);
    }

    private static DataIntegrityViolationException valueTooLong() {
        return new DataIntegrityViolationException("could not record " + PERSONAL,
            psql("ERROR: value too long for type character varying(100)", PSQLState.STRING_DATA_RIGHT_TRUNCATION));
    }

    private static DataIntegrityViolationException duplicateKey() {
        return new DataIntegrityViolationException("could not record " + PERSONAL,
            psql("ERROR: duplicate key value violates unique constraint", PSQLState.UNIQUE_VIOLATION));
    }

    private static UnexpectedErrorException unexpectedError() {
        return new UnexpectedErrorException(new NoSuchMethodError("'void com.example.Order.recipient(String)' " + PERSONAL));
    }

    static Stream<Arguments> databaseUnavailableCases() {
        return Stream.of(
            arguments("SQLTransientConnectionException", camel(new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 30000ms"))),
            arguments("SQLRecoverableException", jdbc(new SQLRecoverableException("I/O error " + PERSONAL))),
            arguments("CannotGetJdbcConnectionException",
                camel(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"))),
            arguments("Spring QueryTimeoutException",
                camel(new QueryTimeoutException("statement timed out " + PERSONAL))),
            arguments("SocketTimeoutException", camel(new RuntimeException("read failed",
                new SocketTimeoutException("Read timed out")))),
            arguments("SQLState 08006", jdbc(psql("An I/O error occurred while sending to the backend.",
                PSQLState.CONNECTION_FAILURE))),
            arguments("SQLState 53300", jdbc(new SQLException("FATAL: sorry, too many clients already", "53300"))),
            arguments("SQLState 57P01", jdbc(new SQLException(
                "FATAL: terminating connection due to administrator command", "57P01"))),
            arguments("DataAccessResourceFailureException with SQLState 08006",
                camel(new DataAccessResourceFailureException("connection lost",
                    psql("An I/O error occurred while sending to the backend.", PSQLState.CONNECTION_FAILURE)))));
    }

    static Stream<Arguments> kafkaCases() {
        return Stream.of(
            arguments("KafkaException", camel(new KafkaException("Failed to construct kafka consumer"))),
            arguments("Kafka TimeoutException (a KafkaException subclass)", camel(new RuntimeException("poll failed",
                new org.apache.kafka.common.errors.TimeoutException("Timeout expired while fetching topic metadata")))));
    }

    static Stream<Arguments> cannotBeRecordedCases() {
        return Stream.of(
            arguments("DataIntegrityViolationException 22001", camel(valueTooLong())),
            arguments("DataIntegrityViolationException 23505", camel(duplicateKey())),
            arguments("JsonValidationException", jsonValidationFailure));
    }

    static Stream<Arguments> otherCases() {
        return Stream.of(
            arguments("UnexpectedErrorException", camel(unexpectedError())),
            arguments("IllegalStateException", camel(new IllegalStateException("no partner " + PERSONAL))));
    }

    // ---- databaseUnavailable ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseUnavailableCases")
    void databaseUnavailableForEveryUnavailableCase(String label, Throwable failure) {
        assertThat(failureKinds.databaseUnavailable(failure)).as(label).isTrue();
    }

    @Test
    void databaseUnavailableFalseForConstraintViolations() {
        assertThat(failureKinds.databaseUnavailable(camel(duplicateKey()))).as("23505").isFalse();
        assertThat(failureKinds.databaseUnavailable(camel(valueTooLong()))).as("22001").isFalse();
        assertThat(failureKinds.databaseUnavailable(jdbc(new SQLException("ERROR: value too long", "22001"))))
            .as("plain SQLException 22001").isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"kafkaCases", "cannotBeRecordedCases", "otherCases"})
    void databaseUnavailableFalseForEveryOtherCase(String label, Throwable failure) {
        assertThat(failureKinds.databaseUnavailable(failure)).as(label).isFalse();
    }

    // ---- kafka ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("kafkaCases")
    void kafkaForAnyKafkaExceptionInTheChain(String label, Throwable failure) {
        assertThat(failureKinds.kafka(failure)).as(label).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"databaseUnavailableCases", "cannotBeRecordedCases", "otherCases"})
    void kafkaFalseWithoutKafkaException(String label, Throwable failure) {
        assertThat(failureKinds.kafka(failure)).as(label).isFalse();
    }

    // ---- waits ----

    @ParameterizedTest(name = "{0}")
    @MethodSource({"databaseUnavailableCases", "kafkaCases"})
    void waitsForDatabaseUnavailableAndKafka(String label, Throwable failure) {
        assertThat(failureKinds.waits(failure)).as(label).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"otherCases", "cannotBeRecordedCases"})
    void doesNotWaitForAnythingElse(String label, Throwable failure) {
        assertThat(failureKinds.waits(failure)).as(label).isFalse();
    }

    // ---- cannotBeRecorded ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("cannotBeRecordedCases")
    void cannotBeRecordedForNonTransientDataAccessAndJsonValidation(String label, Throwable failure) {
        assertThat(failureKinds.cannotBeRecorded(failure)).as(label).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"databaseUnavailableCases", "kafkaCases", "otherCases"})
    void canBeRecordedLaterForEverythingElse(String label, Throwable failure) {
        assertThat(failureKinds.cannotBeRecorded(failure)).as(label).isFalse();
    }

    @Test
    void cannotGetJdbcConnectionIsNonTransientYetDatabaseUnavailableSoNotParked() {
        CannotGetJdbcConnectionException failure = new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
            new SQLException("Connection to localhost:5432 refused.", "08001"));
        assertThat(failure).as("precondition: a non-transient data access exception")
            .isInstanceOf(NonTransientDataAccessException.class);

        assertThat(failureKinds.databaseUnavailable(camel(failure))).isTrue();
        assertThat(failureKinds.cannotBeRecorded(camel(failure))).isFalse();
    }

    // ---- describe ----

    @Test
    void describeNamesRootCauseAndSqlState() {
        assertThat(failureKinds.describe(camel(valueTooLong()))).isEqualTo("PSQLException 22001");
    }

    @Test
    void describeNamesRootCauseAndSmtpReplyCode() throws Exception {
        SMTPAddressFailedException rejected = new SMTPAddressFailedException(new InternetAddress(EMAIL),
            "RCPT TO:<" + EMAIL + ">", 550, "550 5.1.1 <" + EMAIL + ">: Recipient address rejected: User unknown " + NAME);
        assertThat(rejected.getReturnCode()).as("precondition").isEqualTo(550);
        Throwable failure = camel(new SendFailedException("Invalid Addresses " + PERSONAL, rejected));

        assertThat(failureKinds.describe(failure)).isEqualTo("SMTPAddressFailedException 550");
    }

    @Test
    void describeNamesTheErrorOfAnUnexpectedError() {
        assertThat(failureKinds.describe(camel(unexpectedError()))).isEqualTo("unexpected error: NoSuchMethodError");
    }

    @Test
    void describeNamesTheStepOfAWarehouseSftpTimeout() {
        // Design 006 D17, P-9: the step is the code, the message (with the limit) is never read
        assertThat(failureKinds.describe(new WarehouseSftpTimeoutException("channel-start", 10000)))
            .isEqualTo("WarehouseSftpTimeoutException channel-start");
        assertThat(failureKinds.describe(camel(new WarehouseSftpTimeoutException("stat", 10000))))
            .isEqualTo("WarehouseSftpTimeoutException stat");
    }

    @Test
    void describeNamesTheSftpStatusAndTheStepOfARefusedWarehouseSftpStep() {
        // Developer check 4 (W2-F4): status id and its constant text, the step; the server's message (a path) never
        com.jcraft.jsch.SftpException denied = new com.jcraft.jsch.SftpException(3,
            "Permission denied: inbound/picklists/PL-TG-1941.csv.tmp " + NAME);
        assertThat(failureKinds.describe(camel(new WarehouseSftpStepException("upload", denied))))
            .isEqualTo("SftpException 3 permission denied (upload)");
        assertThat(failureKinds.describe(new WarehouseSftpStepException("rename",
            new com.jcraft.jsch.SftpException(2, "No such file inbound/picklists/PL-TG-1941.csv.tmp"))))
            .isEqualTo("SftpException 2 no such file (rename)");
        assertThat(failureKinds.describe(new WarehouseSftpStepException("stat",
            new com.jcraft.jsch.SftpException(99, "odd"))))
            .isEqualTo("SftpException 99 unknown status (stat)");
    }

    @Test
    void describeNamesRootCauseOnlyWhenThereIsNoCode() {
        assertThat(failureKinds.describe(camel(new IllegalStateException("no partner " + PERSONAL))))
            .isEqualTo("IllegalStateException");
        assertThat(failureKinds.describe(jsonValidationFailure)).isEqualTo("JsonValidationException");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource({"databaseUnavailableCases", "kafkaCases", "cannotBeRecordedCases", "otherCases"})
    void describeNeverContainsMessageText(String label, Throwable failure) {
        String kind = failureKinds.describe(failure);

        assertThat(kind).as(label).isNotBlank().doesNotContain(EMAIL).doesNotContain(NAME).doesNotContain("jane")
            .hasSizeLessThanOrEqualTo(450);
    }

    @Test
    void describeIsAtMost450CharactersForAPathologicalChain() {
        Throwable failure = new ThisSqlExceptionClassNameIsPathologicallyLongSoThatItsSimpleNameTogetherWithAnEquallyPathologicalSqlStateWouldOverflowTheFiveHundredCharacterColumnUnlessDescribeTruncatesTheKind(
            "S".repeat(600));
        for (int depth = 0; depth < 200; depth++) {
            failure = new RuntimeException("level " + depth + " " + PERSONAL, failure);
        }

        String kind = failureKinds.describe(camel(failure));

        assertThat(kind).isNotBlank().hasSizeLessThanOrEqualTo(450).doesNotContain(EMAIL).doesNotContain(NAME)
            .startsWith("ThisSqlExceptionClassNameIsPathologicallyLong");
    }

    /** A root cause with a 177-character simple name and a 600-character SQLState (P-7). */
    static final class ThisSqlExceptionClassNameIsPathologicallyLongSoThatItsSimpleNameTogetherWithAnEquallyPathologicalSqlStateWouldOverflowTheFiveHundredCharacterColumnUnlessDescribeTruncatesTheKind
            extends SQLException {
        private static final long serialVersionUID = 1L;

        ThisSqlExceptionClassNameIsPathologicallyLongSoThatItsSimpleNameTogetherWithAnEquallyPathologicalSqlStateWouldOverflowTheFiveHundredCharacterColumnUnlessDescribeTruncatesTheKind(
                String sqlState) {
            super("pathological " + PERSONAL, sqlState);
        }
    }
}
