package com.trailheadgear.orderintegration.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T13 (design 005 §3.9, §3.8 "Getting a parked order out", design review decision 4): every statement of the README
 * section for the Integration Team is read from {@code README.md} and run against PostgreSQL with rows in each end
 * state, so the statements are checked, not only written. No statement is copied into this test.
 *
 * <h2>The README contract (Task 35 writes the section to it)</h2>
 * <ul>
 *   <li><b>Section:</b> the line {@code ## Finding and moving stuck orders}, exactly once in {@code README.md} (project
 *       base directory). The section ends at the next line starting with {@code # } or {@code ## }, or at the end of the
 *       file.</li>
 *   <li><b>Blocks:</b> every statement stands in its own fenced block opened by the line {@code ```sql} and closed by
 *       the line {@code ```}. Its first line is exactly {@code -- end-state: <name>} (regex
 *       {@code ^-- end-state: ([a-z-]+)$}). Every {@code ```sql} block of the section carries the marker; a name occurs
 *       once. The section holds exactly these seven names, no fewer and no more: {@code set-aside},
 *       {@code waiting-for-check}, {@code waiting-for-recording}, {@code release-recording},
 *       {@code pick-list-not-delivered}, {@code notification-not-sent}, {@code partner-unstorable}. Other fences
 *       ({@code ```bash}, {@code ```text}) are allowed and ignored.</li>
 *   <li><b>One statement per block:</b> the lines after the marker; one trailing {@code ;} is allowed, no other
 *       {@code ;}. Further {@code --} comment lines are allowed.</li>
 *   <li><b>Parameters</b> (Spring/psql-style named parameters): none, except {@code :id} (the {@code order_receipt.id})
 *       in {@code release-recording} and {@code :order_number} (the order number of the ERROR {@code kind=unstorable}
 *       line) in {@code partner-unstorable}.</li>
 *   <li><b>Returned rows:</b> each select returns one row per stuck item and a column {@code id}, the {@code id} of the
 *       table it finds the item in; and the columns a person needs:
 *       <ul>
 *         <li>{@code set-aside}: {@code set_aside_order_event} rows in {@code Set Aside}, newest first
 *             ({@code arrived_at} descending), with {@code cannot_read_reason};</li>
 *         <li>{@code waiting-for-check}: {@code order_receipt} with {@code waiting_for = 'Check'}, with
 *             {@code waiting_reason} and {@code last_check_at};</li>
 *         <li>{@code waiting-for-recording}: {@code order_receipt} with {@code waiting_for = 'Recording'}, with
 *             {@code waiting_reason};</li>
 *         <li>{@code pick-list-not-delivered}: {@code pick_list} {@code Pending}, with {@code attempts},
 *             {@code last_attempt_at}, {@code last_failure};</li>
 *         <li>{@code notification-not-sent}: {@code notification} with {@code sent_at IS NULL}, with
 *             {@code attempts}, {@code last_attempt_at}, {@code last_failure};</li>
 *         <li>{@code partner-unstorable}: the Partner receipts ({@code order_receipt}, {@code channel = 'Partner'})
 *             in {@code Received} with {@code order_number = :order_number}.</li>
 *       </ul></li>
 *   <li><b>{@code release-recording}</b> is the statement of design §3.8: it moves the receipt {@code :id} from
 *       {@code Recording} to {@code Check} with {@code waiting_reason} and {@code last_check_at} NULL, and changes no
 *       row of a receipt that is not {@code Received} and waiting for {@code Recording}.</li>
 * </ul>
 *
 * <p>The fixture rows carry their own order numbers ({@code ESQ-…}), event IDs and partner code, so the assertions
 * consider only fixture rows: a query returns its row(s) and none of the other fixture rows (the healthy rows and the
 * rows in the other end states).
 */
class EndStateQueriesIT {

    static final String SECTION = "## Finding and moving stuck orders";
    static final Pattern MARKER = Pattern.compile("^-- end-state: ([a-z-]+)$");
    static final Set<String> NAMES = Set.of("set-aside", "waiting-for-check", "waiting-for-recording",
        "release-recording", "pick-list-not-delivered", "notification-not-sent", "partner-unstorable");

    private static final Path README = Path.of("README.md");
    private static final String PARTNER_CODE = "esq-partner";
    private static final String UNSTORABLE_NUMBER = "ESQ-PU";

    private static Map<String, String> blocks;
    private static SqlStatements sql;

    /** Fixture IDs per table, filled by {@link #insertFixture()}. */
    private final Map<String, Long> ids = new LinkedHashMap<>();

    @BeforeAll
    static void start() {
        // The README first: without the section the test fails before any container is started
        blocks = endStateBlocks(README);
        sql = SqlStatements.start();
    }

    @AfterAll
    static void stop() {
        if (sql != null) {
            sql.close();
        }
    }

    @BeforeEach
    void insertFixture() {
        var jdbc = sql.jdbc();
        long partner = jdbc.queryForObject("""
            INSERT INTO partner (partner_code, name, api_key_name, order_number_from, order_number_to,
                                 order_contact_email, registered_at)
            VALUES (?, 'End State Partner', 'esq-partner-key', 1, 999999, 'orders@esq-partner.example', now())
            RETURNING id""", Long.class, PARTNER_CODE);
        ids.put("partner", partner);

        // set_aside_order_event: two set aside (older, newer), one resubmitted (healthy)
        ids.put("setAsideOlder", setAside("esq-evt-older", "now() - interval '2 hours'", "Set Aside"));
        ids.put("setAsideNewer", setAside("esq-evt-newer", "now() - interval '1 hour'", "Set Aside"));
        ids.put("setAsideHealthy", setAside("esq-evt-resubmitted", "now() - interval '30 minutes'", "Resubmitted"));

        // order_receipt: waiting for Check, waiting for Recording, partner unstorable, and healthy ones
        ids.put("check", receipt("ESQ-CHECK", "Peakmart", null, "Received", "'Check'", "'catalog outage'",
            "now() - interval '5 minutes'"));
        ids.put("recording", receipt("ESQ-REC", "Peakmart", null, "Received", "'Recording'",
            "'cannot be recorded: DataIntegrityViolationException'", null));
        ids.put("partnerUnstorable", receipt(UNSTORABLE_NUMBER, "Partner", partner, "Received", null, null, null));
        // the same order number, resubmitted by the partner with other content and recorded: not stuck
        ids.put("partnerRecorded", receipt(UNSTORABLE_NUMBER, "Partner", partner, "Recorded", null, null, null));
        // another partner order still Received (e.g. being checked): a query without the order number would list it
        ids.put("partnerOtherReceived", receipt("ESQ-PO", "Partner", partner, "Received", null, null, null));
        ids.put("pickListPendingReceipt", receipt("ESQ-PL-P", "Peakmart", null, "Recorded", null, null, null));
        ids.put("pickListDeliveredReceipt", receipt("ESQ-PL-D", "Peakmart", null, "Recorded", null, null, null));
        ids.put("notificationUnsentReceipt", receipt("ESQ-NT-U", "Peakmart", null, "Rejected", null, null, null));
        ids.put("notificationSentReceipt", receipt("ESQ-NT-S", "Peakmart", null, "Rejected", null, null, null));

        // pick_list: one not delivered (with its failure), one delivered (healthy)
        ids.put("pickListPending", pickList("ESQ-PL-P", ids.get("pickListPendingReceipt"), false));
        ids.put("pickListDelivered", pickList("ESQ-PL-D", ids.get("pickListDeliveredReceipt"), true));

        // notification: one not sent (with its failure), one sent (healthy)
        ids.put("notificationUnsent", notification(ids.get("notificationUnsentReceipt"), false));
        ids.put("notificationSent", notification(ids.get("notificationSentReceipt"), true));
    }

    @AfterEach
    void deleteFixture() {
        var jdbc = sql.jdbc();
        jdbc.update("DELETE FROM notification WHERE order_receipt_id IN "
            + "(SELECT id FROM order_receipt WHERE order_number LIKE 'ESQ-%')");
        jdbc.update("DELETE FROM pick_list WHERE customer_order_id IN "
            + "(SELECT id FROM customer_order WHERE order_number LIKE 'ESQ-%')");
        jdbc.update("DELETE FROM customer_order WHERE order_number LIKE 'ESQ-%'");
        jdbc.update("DELETE FROM order_receipt WHERE order_number LIKE 'ESQ-%'");
        jdbc.update("DELETE FROM set_aside_order_event WHERE event_id LIKE 'esq-evt-%'");
        jdbc.update("DELETE FROM partner WHERE partner_code = ?", PARTNER_CODE);
        ids.clear();
    }

    @Test
    void eachEndStateQueryFindsItsRowsAndNoOtherFixtureRow() {
        assertFinds("set-aside", Map.of(), "set_aside_order_event", List.of("setAsideNewer", "setAsideOlder"),
            "cannot_read_reason");
        assertFinds("waiting-for-check", Map.of(), "order_receipt", List.of("check"), "waiting_reason",
            "last_check_at");
        assertFinds("waiting-for-recording", Map.of(), "order_receipt", List.of("recording"), "waiting_reason");
        assertFinds("pick-list-not-delivered", Map.of(), "pick_list", List.of("pickListPending"), "attempts",
            "last_attempt_at", "last_failure");
        assertFinds("notification-not-sent", Map.of(), "notification", List.of("notificationUnsent"), "attempts",
            "last_attempt_at", "last_failure");
        assertFinds("partner-unstorable", Map.of("order_number", UNSTORABLE_NUMBER), "order_receipt",
            List.of("partnerUnstorable"));
    }

    @Test
    void releaseRecordingMovesOnlyARecordingOrderBackToCheckFirstInThePoll() {
        String release = blocks.get("release-recording");

        // not waiting for Recording: nothing changes
        assertThat(sql.runText(release, Map.of("id", ids.get("check"))).updateCount())
            .as("release-recording for a receipt waiting for Check").isZero();
        assertThat(sql.runText(release, Map.of("id", ids.get("pickListPendingReceipt"))).updateCount())
            .as("release-recording for a Recorded receipt").isZero();
        assertThat(receiptState(ids.get("check")))
            .containsEntry("waiting_for", "Check").containsEntry("waiting_reason", "catalog outage");
        assertThat(receiptState(ids.get("pickListPendingReceipt"))).containsEntry("waiting_for", null)
            .containsEntry("order_state", "Recorded");

        // the Recording order: back to Check, no reason, never checked
        SqlStatements.Result released = sql.runText(release, Map.of("id", ids.get("recording")));
        assertThat(released.hasRows()).as("release-recording returns no rows").isFalse();
        assertThat(released.updateCount()).as("release-recording for the Recording receipt").isEqualTo(1);
        Map<String, Object> state = receiptState(ids.get("recording"));
        assertThat(state).containsEntry("waiting_for", "Check").containsEntry("waiting_reason", null)
            .containsEntry("last_check_at", null).containsEntry("order_state", "Received");

        // and the next poll checks it first
        List<Map<String, Object>> polled = sql.run("waiting-select.sql", Map.of()).rows();
        assertThat(polled).as("waiting-select.sql after release-recording").isNotEmpty();
        assertThat(((Number) polled.get(0).get("id")).longValue()).as("first receipt of waiting-select.sql")
            .isEqualTo(ids.get("recording"));
    }

    // ---------------------------------------------------------------------------------------------- assertions

    private void assertFinds(String name, Map<String, Object> params, String table, List<String> expected,
            String... columns) {
        SqlStatements.Result result = sql.runText(blocks.get(name), params);
        assertThat(result.hasRows()).as("%s returns rows", name).isTrue();
        List<Long> fixtureIds = fixtureIdsOf(table);
        for (Map<String, Object> row : result.rows()) {
            assertThat(row).as("%s row", name).containsKey("id");
            for (String column : columns) {
                assertThat(row).as("%s row", name).containsKey(column);
            }
        }
        List<Long> found = result.rows().stream().map(row -> ((Number) row.get("id")).longValue())
            .filter(fixtureIds::contains).toList();
        List<Long> wanted = expected.stream().map(ids::get).toList();
        assertThat(found).as("%s: fixture %s ids found (fixture ids of %s: %s)", name, table, table, ids)
            .containsExactlyElementsOf(wanted);
    }

    private List<Long> fixtureIdsOf(String table) {
        List<String> keys = switch (table) {
            case "set_aside_order_event" -> List.of("setAsideOlder", "setAsideNewer", "setAsideHealthy");
            case "order_receipt" -> List.of("check", "recording", "partnerUnstorable", "partnerRecorded",
                "partnerOtherReceived", "pickListPendingReceipt", "pickListDeliveredReceipt", "notificationUnsentReceipt",
                "notificationSentReceipt");
            case "pick_list" -> List.of("pickListPending", "pickListDelivered");
            case "notification" -> List.of("notificationUnsent", "notificationSent");
            default -> throw new IllegalArgumentException("No fixture table " + table);
        };
        return keys.stream().map(ids::get).toList();
    }

    private Map<String, Object> receiptState(long id) {
        return sql.jdbc().queryForMap(
            "SELECT order_state, waiting_for, waiting_reason, last_check_at FROM order_receipt WHERE id = ?", id);
    }

    // ---------------------------------------------------------------------------------------------- fixture

    private long setAside(String eventId, String arrivedAt, String state) {
        return sql.jdbc().queryForObject("""
            INSERT INTO set_aside_order_event (event_id, order_number, event_content, cannot_read_reason, arrived_at,
                                               set_aside_state)
            VALUES (?, NULL, '{not json', 'unreadable: JsonParseException', %s, ?) RETURNING id"""
                .formatted(arrivedAt), Long.class, eventId, state);
    }

    /** {@code waitingFor}, {@code reason}, {@code lastCheckAt} are SQL literals or null. */
    private long receipt(String orderNumber, String channel, Long partnerId, String orderState, String waitingFor,
            String reason, String lastCheckAt) {
        boolean peakmart = "Peakmart".equals(channel);
        String key = orderNumber + "-" + orderState;
        return sql.jdbc().queryForObject("""
            INSERT INTO order_receipt (order_number, channel, partner_id, event_id, content_hash, correlation_id,
                                       received_at, order_state, waiting_for, waiting_order, waiting_reason,
                                       last_check_at)
            VALUES (?, ?, ?, ?, ?, ?, now() - interval '3 hours', ?, %s, %s, %s, %s) RETURNING id"""
                .formatted(waitingFor == null ? "NULL" : waitingFor, waitingFor == null ? "NULL" : "'{}'",
                    reason == null ? "NULL" : reason, lastCheckAt == null ? "NULL" : lastCheckAt),
            Long.class, orderNumber, channel, partnerId, peakmart ? "esq-" + key : null, "hash-" + key,
            "corr-" + key, orderState);
    }

    private long pickList(String orderNumber, long receiptId, boolean delivered) {
        var jdbc = sql.jdbc();
        long customerOrder = jdbc.queryForObject("""
            INSERT INTO customer_order (order_receipt_id, order_number, ordered_at, recipient_name, street_line, city,
                                        state_region, postal_code, country, recorded_at)
            VALUES (?, ?, now() - interval '3 hours', 'Dana Whitfield', '12 Pine Ridge Rd', 'Bend', 'OR', '97701',
                    'US', now() - interval '3 hours') RETURNING id""", Long.class, receiptId, orderNumber);
        return delivered
            ? jdbc.queryForObject("""
                INSERT INTO pick_list (customer_order_id, file_name, total_weight_g, delivery_state, created_at,
                                       last_attempt_at, attempts, delivered_at)
                VALUES (?, ?, 1200, 'Delivered', now() - interval '3 hours', now() - interval '2 hours', 1,
                        now() - interval '2 hours') RETURNING id""", Long.class, customerOrder,
                "PL-" + orderNumber + ".csv")
            : jdbc.queryForObject("""
                INSERT INTO pick_list (customer_order_id, file_name, total_weight_g, delivery_state, created_at,
                                       last_attempt_at, attempts, last_failure)
                VALUES (?, ?, 1200, 'Pending', now() - interval '3 hours', now() - interval '1 minute', 17,
                        'GenericFileOperationFailedException') RETURNING id""", Long.class, customerOrder,
                "PL-" + orderNumber + ".csv");
    }

    private long notification(long receiptId, boolean sent) {
        return sent
            ? sql.jdbc().queryForObject("""
                INSERT INTO notification (order_receipt_id, notification_type, recipient_email, created_at, sent_at,
                                          attempts, last_attempt_at)
                VALUES (?, 'Order Desk Notification', 'order-desk@trailheadgear.example', now() - interval '3 hours',
                        now() - interval '3 hours', 1, now() - interval '3 hours') RETURNING id""", Long.class,
                receiptId)
            : sql.jdbc().queryForObject("""
                INSERT INTO notification (order_receipt_id, notification_type, recipient_email, created_at, attempts,
                                          last_attempt_at, last_failure)
                VALUES (?, 'Order Desk Notification', 'order-desk@trailheadgear.example', now() - interval '3 hours',
                        9, now() - interval '1 minute', 'SMTPAddressFailedException 550') RETURNING id""",
                Long.class, receiptId);
    }

    // ---------------------------------------------------------------------------------------------- README parser

    /**
     * The {@code -- end-state:} blocks of the README section, by name, statement text without the marker line and
     * without a trailing {@code ;}. Fails when the section is missing or twice, when a {@code ```sql} block of the
     * section has no marker, a name repeats, a fence is not closed, a block holds no or more than one statement, or the
     * names are not exactly {@link #NAMES}.
     */
    static Map<String, String> endStateBlocks(Path readme) {
        List<String> lines;
        try {
            lines = Files.readAllLines(readme, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + readme.toAbsolutePath(), e);
        }
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(SECTION)) {
                if (start >= 0) {
                    throw new AssertionError(readme + ": the section '" + SECTION + "' occurs twice (lines "
                        + (start + 1) + " and " + (i + 1) + ")");
                }
                start = i;
            }
        }
        if (start < 0) {
            throw new AssertionError(readme + ": section '" + SECTION + "' not found");
        }

        Map<String, String> found = new LinkedHashMap<>();
        int i = start + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.startsWith("# ") || line.startsWith("## ")) {
                break; // the next section
            }
            if (!line.startsWith("```")) {
                i++;
                continue;
            }
            String info = line.substring(3).strip();
            int open = i;
            List<String> body = new ArrayList<>();
            i++;
            while (i < lines.size() && !lines.get(i).equals("```")) {
                body.add(lines.get(i));
                i++;
            }
            if (i >= lines.size()) {
                throw new AssertionError(readme + " line " + (open + 1) + ": the fence is never closed");
            }
            i++; // past the closing fence
            if (!info.equals("sql")) {
                continue;
            }
            Matcher marker = MARKER.matcher(body.isEmpty() ? "" : body.get(0));
            if (!marker.matches()) {
                throw new AssertionError(readme + " line " + (open + 2) + ": a sql block of '" + SECTION
                    + "' must start with '-- end-state: <name>' but starts with '"
                    + (body.isEmpty() ? "" : body.get(0)) + "'");
            }
            String name = marker.group(1);
            String statement = String.join("\n", body.subList(1, body.size())).strip();
            if (statement.endsWith(";")) {
                statement = statement.substring(0, statement.length() - 1).strip();
            }
            if (statement.isEmpty() || statement.contains(";")) {
                throw new AssertionError(readme + " line " + (open + 1) + ": block '" + name
                    + "' must hold exactly one statement");
            }
            if (found.put(name, statement) != null) {
                throw new AssertionError(readme + " line " + (open + 1) + ": block '" + name + "' occurs twice");
            }
        }
        List<String> missing = NAMES.stream().filter(n -> !found.containsKey(n)).sorted().toList();
        List<String> unknown = found.keySet().stream().filter(n -> !NAMES.contains(n)).sorted().toList();
        if (!missing.isEmpty() || !unknown.isEmpty()) {
            throw new AssertionError(readme + ": section '" + SECTION + "' lacks the end-state blocks " + missing
                + " and has the unknown ones " + unknown);
        }
        return found;
    }
}
