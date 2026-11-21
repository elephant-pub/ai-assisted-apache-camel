package com.trailheadgear.orderintegration.spec.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.citrusframework.sql.actions.ExecuteSQLAction;
import org.citrusframework.sql.actions.ExecuteSQLQueryAction;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The Order Database side of the Postconditions and the cleanup (design 004 §3.3, A-14, A-15, Task 11), on the run's
 * PostgreSQL container ({@link SpecEnvironment#dataSource()}). Table and column names are those of Flyway V1 and V2.
 *
 * <p><b>Two ways in.</b> Citrus {@code sql} builders ({@link #query(String, String...)}, {@link #cleanup(String)},
 * {@link #cleanupSetAside(String)}) for checks run as Citrus actions, and plain-JDBC readers (with
 * {@link PreparedStatement} parameters) where an IT asserts in Java.
 *
 * <p><b>SQL safety.</b> Citrus {@code sql} statements are strings, so they cannot carry JDBC parameters. Their
 * {@code ?} markers are filled only with values that pass a strict pattern: an Order Number
 * ({@code ^[A-Z]{2}-[0-9]+$}) or an event id (a UUID). Such a value holds no quote, backslash, {@code $} or blank, so it
 * can neither end the SQL string literal nor be taken for a Citrus expression; anything else is refused with an
 * {@link IllegalArgumentException}. The statement templates are constants of this class or of the calling IT, never
 * data. The JDBC readers and {@link #cleanupNow(String)} bind every value as a parameter.
 *
 * <p><b>Cleanup order</b> (test cases' "Cleanup order", §3.3): {@code order_state_change}, {@code rejection_reason},
 * {@code notification}, {@code pick_list}, {@code order_line}, then {@code customer_order}, then {@code order_receipt},
 * every row found through the receipts of the Order Number, in one transaction. The {@code partner} table and the
 * Partner Configuration are never touched.
 */
public final class OrderDatabase {

    private static final Pattern ORDER_NUMBER = Pattern.compile("^[A-Z]{2}-[0-9]+$");
    private static final Pattern EVENT_ID =
        Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final String RECEIPTS_OF = "SELECT r.id FROM order_receipt r WHERE r.order_number = ?";
    private static final String ORDERS_OF = "SELECT c.id FROM customer_order c WHERE c.order_number = ? "
        + "OR c.order_receipt_id IN (" + RECEIPTS_OF + ")";

    /** Each statement takes the Order Number for every {@code ?}. */
    private static final List<String> CLEANUP_STATEMENTS = List.of(
        "DELETE FROM order_state_change WHERE order_receipt_id IN (" + RECEIPTS_OF + ")",
        "DELETE FROM rejection_reason WHERE order_receipt_id IN (" + RECEIPTS_OF + ")",
        "DELETE FROM notification WHERE order_receipt_id IN (" + RECEIPTS_OF + ")",
        "DELETE FROM pick_list WHERE customer_order_id IN (" + ORDERS_OF + ")",
        "DELETE FROM order_line WHERE customer_order_id IN (" + ORDERS_OF + ")",
        "DELETE FROM customer_order WHERE order_number = ? OR order_receipt_id IN (" + RECEIPTS_OF + ")",
        "DELETE FROM order_receipt WHERE order_number = ?");

    private static final String CLEANUP_SET_ASIDE = "DELETE FROM set_aside_order_event WHERE event_id = ?";

    private final DataSource dataSource;

    public OrderDatabase() {
        this.dataSource = SpecEnvironment.get().dataSource();
    }

    public DataSource dataSource() {
        return dataSource;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Citrus sql builders

    /**
     * A Citrus {@code sql} query on the Order Database; the caller adds {@code validate}/{@code extract}. Each
     * {@code ?} of {@code sqlTemplate} is replaced, in order, by one of {@code values} as a SQL string literal; every
     * value must be an Order Number or an event id.
     */
    public ExecuteSQLQueryAction.Builder query(String sqlTemplate, String... values) {
        return ExecuteSQLQueryAction.Builder.query(dataSource).statement(bind(sqlTemplate, values));
    }

    /** The Citrus {@code sql} action deleting everything of {@code orderNumber}, in the cleanup order, in one transaction. */
    public ExecuteSQLAction.Builder cleanup(String orderNumber) {
        requireOrderNumber(orderNumber);
        List<String> statements = new ArrayList<>();
        for (String statement : CLEANUP_STATEMENTS) {
            statements.add(bind(statement, repeat(orderNumber, statement)));
        }
        return ExecuteSQLAction.Builder.sql(dataSource)
            .transactionManager(new DataSourceTransactionManager(dataSource))
            .statements(statements);
    }

    /** The Citrus {@code sql} action deleting the set-aside Order Event {@code eventId} (TC-004). */
    public ExecuteSQLAction.Builder cleanupSetAside(String eventId) {
        requireEventId(eventId);
        return ExecuteSQLAction.Builder.sql(dataSource).statement(bind(CLEANUP_SET_ASIDE, eventId));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Plain JDBC (bound parameters)

    /** The same deletes as {@link #cleanup(String)}, run directly (for a {@code finally} block), in one transaction. */
    public void cleanupNow(String orderNumber) {
        requireOrderNumber(orderNumber);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                for (String statement : CLEANUP_STATEMENTS) {
                    try (PreparedStatement ps = connection.prepareStatement(statement)) {
                        int markers = markers(statement);
                        for (int i = 1; i <= markers; i++) {
                            ps.setString(i, orderNumber);
                        }
                        ps.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cleanup of " + orderNumber + " failed", e);
        }
    }

    /** {@link #cleanupSetAside(String)} run directly. */
    public void cleanupSetAsideNow(String eventId) {
        requireEventId(eventId);
        update(CLEANUP_SET_ASIDE, eventId);
    }

    /** Rows of a query with bound parameters; column labels in lower case, values as JDBC returns them. */
    public List<Map<String, Object>> rows(String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int c = 1; c <= meta.getColumnCount(); c++) {
                        row.put(meta.getColumnLabel(c).toLowerCase(Locale.ROOT), rs.getObject(c));
                    }
                    rows.add(Collections.unmodifiableMap(row));
                }
                return Collections.unmodifiableList(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    /** Every {@code order_receipt} of {@code orderNumber}, oldest first. */
    public List<Map<String, Object>> receipts(String orderNumber) {
        return rows("SELECT id, order_number, channel, partner_id, event_id, order_state, received_at, waiting_for "
            + "FROM order_receipt WHERE order_number = ? ORDER BY id", requireOrderNumber(orderNumber));
    }

    /** The Order State of the single receipt of {@code orderNumber}; fails unless there is exactly one. */
    public String receiptState(String orderNumber) {
        List<Map<String, Object>> receipts = receipts(orderNumber);
        if (receipts.size() != 1) {
            throw new AssertionError("Expected exactly one order_receipt for " + orderNumber + ", found "
                + receipts.size());
        }
        return (String) receipts.get(0).get("order_state");
    }

    /** The Order History of {@code orderNumber}: {@code order_state_change.order_state} by {@code changed_at}, id. */
    public List<String> stateHistory(String orderNumber) {
        List<String> states = new ArrayList<>();
        for (Map<String, Object> row : rows("SELECT s.order_state FROM order_state_change s "
            + "JOIN order_receipt r ON r.id = s.order_receipt_id WHERE r.order_number = ? "
            + "ORDER BY s.changed_at, s.id", requireOrderNumber(orderNumber))) {
            states.add((String) row.get("order_state"));
        }
        return states;
    }

    /** The {@code customer_order} of {@code orderNumber}, if recorded. */
    public Optional<Map<String, Object>> customerOrder(String orderNumber) {
        return single(rows("SELECT c.id, c.order_number, r.channel, p.partner_code, c.ordered_at, c.customer_email, "
            + "c.recipient_name, c.street_line, c.city, c.state_region, c.postal_code, c.country, c.recorded_at "
            + "FROM customer_order c JOIN order_receipt r ON r.id = c.order_receipt_id "
            + "LEFT JOIN partner p ON p.id = r.partner_id WHERE c.order_number = ?",
            requireOrderNumber(orderNumber)), "customer_order", orderNumber);
    }

    /** The Order Lines of {@code orderNumber} in line order. */
    public List<Map<String, Object>> orderLines(String orderNumber) {
        return rows("SELECT l.line_number, l.sku, l.product_name, l.quantity, l.unit_price, l.unit_weight_grams "
            + "FROM order_line l JOIN customer_order c ON c.id = l.customer_order_id WHERE c.order_number = ? "
            + "ORDER BY l.line_number", requireOrderNumber(orderNumber));
    }

    /** The {@code pick_list} row of {@code orderNumber} (delivery state, attempts, …), if one exists. */
    public Optional<Map<String, Object>> pickList(String orderNumber) {
        return single(rows("SELECT p.id, p.file_name, p.total_weight_g, p.delivery_state, p.attempts, "
            + "p.last_attempt_at, p.delivered_at FROM pick_list p JOIN customer_order c ON c.id = p.customer_order_id "
            + "WHERE c.order_number = ?", requireOrderNumber(orderNumber)), "pick_list", orderNumber);
    }

    /** The Rejection Reasons of {@code orderNumber}, by receipt and position. */
    public List<Map<String, Object>> rejectionReasons(String orderNumber) {
        return rows("SELECT x.order_check, x.line_number, x.reason_text, x.position FROM rejection_reason x "
            + "JOIN order_receipt r ON r.id = x.order_receipt_id WHERE r.order_number = ? ORDER BY r.id, x.position",
            requireOrderNumber(orderNumber));
    }

    /** The {@code notification} rows of {@code orderNumber}. */
    public List<Map<String, Object>> notifications(String orderNumber) {
        return rows("SELECT n.notification_type, n.recipient_email, n.created_at, n.sent_at, n.attempts "
            + "FROM notification n JOIN order_receipt r ON r.id = n.order_receipt_id WHERE r.order_number = ? "
            + "ORDER BY n.id", requireOrderNumber(orderNumber));
    }

    /** {@code notification.sent_at} of the single notification of {@code orderNumber} (empty when not yet sent). */
    public Optional<Object> notificationSentAt(String orderNumber) {
        List<Map<String, Object>> notifications = notifications(orderNumber);
        if (notifications.size() != 1) {
            throw new AssertionError("Expected exactly one notification for " + orderNumber + ", found "
                + notifications.size());
        }
        return Optional.ofNullable(notifications.get(0).get("sent_at"));
    }

    /** The {@code set_aside_order_event} with {@code eventId}, if any (A-14). */
    public Optional<Map<String, Object>> setAsideEvent(String eventId) {
        return single(rows("SELECT event_id, order_number, cannot_read_reason, arrived_at, set_aside_state "
            + "FROM set_aside_order_event WHERE event_id = ?", requireEventId(eventId)), "set_aside_order_event",
            eventId);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private void update(String sql, String value) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Update failed: " + sql, e);
        }
    }

    private static Optional<Map<String, Object>> single(List<Map<String, Object>> rows, String table, String key) {
        if (rows.size() > 1) {
            throw new AssertionError("Expected at most one " + table + " for " + key + ", found " + rows.size());
        }
        return rows.stream().findFirst();
    }

    /** Fills the {@code ?} markers with validated values as SQL string literals (see the class comment). */
    static String bind(String sqlTemplate, String... values) {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new IllegalArgumentException("No SQL statement");
        }
        if (markers(sqlTemplate) != values.length) {
            throw new IllegalArgumentException("The statement has " + markers(sqlTemplate) + " markers but "
                + values.length + " values were given");
        }
        StringBuilder sql = new StringBuilder(sqlTemplate.length() + 16 * values.length);
        int next = 0;
        for (int i = 0; i < sqlTemplate.length(); i++) {
            char c = sqlTemplate.charAt(i);
            if (c == '?') {
                sql.append('\'').append(requireLiteral(values[next++])).append('\'');
            } else {
                sql.append(c);
            }
        }
        return sql.toString();
    }

    private static int markers(String sql) {
        return (int) sql.chars().filter(c -> c == '?').count();
    }

    private static String[] repeat(String value, String statement) {
        String[] values = new String[markers(statement)];
        java.util.Arrays.fill(values, value);
        return values;
    }

    private static String requireLiteral(String value) {
        if (value != null && (ORDER_NUMBER.matcher(value).matches() || EVENT_ID.matcher(value).matches())) {
            return value;
        }
        throw new IllegalArgumentException("Only an Order Number or an event id may be put into a Citrus SQL "
            + "statement: " + value);
    }

    private static String requireOrderNumber(String orderNumber) {
        if (orderNumber == null || !ORDER_NUMBER.matcher(orderNumber).matches()) {
            throw new IllegalArgumentException("Not an Order Number: " + orderNumber);
        }
        return orderNumber;
    }

    private static String requireEventId(String eventId) {
        if (eventId == null || !EVENT_ID.matcher(eventId).matches()) {
            throw new IllegalArgumentException("Not an event id: " + eventId);
        }
        return eventId;
    }
}
