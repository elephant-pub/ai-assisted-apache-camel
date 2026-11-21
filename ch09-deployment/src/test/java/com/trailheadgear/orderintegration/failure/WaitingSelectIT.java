package com.trailheadgear.orderintegration.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T6 (design 005 §3.9, §3.6 H5 "Order of the poll"): waiting-select.sql lists the receipts waiting for Check with the
 * never-checked ones first (by received_at, id), then the checked ones by last_check_at (oldest check first); a receipt
 * waiting for Recording is never listed. The production statement runs verbatim against PostgreSQL.
 */
class WaitingSelectIT {

    private static SqlStatements sql;

    @BeforeAll
    static void start() {
        sql = SqlStatements.start();
    }

    @AfterAll
    static void stop() {
        if (sql != null) {
            sql.close();
        }
    }

    @Test
    void neverCheckedReceiptsComeFirstThenTheLeastRecentlyChecked() {
        var jdbc = sql.jdbc();
        try {
            // received_at: A < B < C (A is the oldest receipt); times relative to now(). C is inserted before B, so
            // its id is lower: only received_at puts B before C.
            long a = insert("WS-A", "Check", "now() - interval '60 minutes'", "now() - interval '10 minutes'",
                "catalog outage");
            long c = insert("WS-C", "Check", "now() - interval '40 minutes'", null, null);
            long b = insert("WS-B", "Check", "now() - interval '50 minutes'", null, null);
            long d = insert("WS-D", "Check", "now() - interval '55 minutes'", "now() - interval '1 minute'",
                "catalog outage");
            long e = insert("WS-E", "Recording", "now() - interval '70 minutes'", null, "The Order Database was unavailable");
            List<Long> fixture = List.of(a, b, c, d, e);

            List<Map<String, Object>> rows = sql.run("waiting-select.sql", Map.of()).rows();
            List<Long> listed = rows.stream().map(row -> ((Number) row.get("id")).longValue())
                .filter(fixture::contains).toList();

            // never checked first (B, C by received_at, id), then by last_check_at (A 10 min ago, D 1 min ago); never E
            assertThat(listed).as("waiting-select.sql order (ids of A, B, C, D, E = %s)", fixture)
                .containsExactly(b, c, a, d);
        } finally {
            jdbc.update("DELETE FROM order_receipt WHERE order_number LIKE 'WS-%'");
        }
    }

    private long insert(String orderNumber, String waitingFor, String receivedAt, String lastCheckAt, String reason) {
        String lastCheckColumn = lastCheckAt == null ? "" : ", last_check_at";
        String lastCheckValue = lastCheckAt == null ? "" : ", " + lastCheckAt;
        String reasonColumn = reason == null ? "" : ", waiting_reason";
        String reasonValue = reason == null ? "" : ", '" + reason + "'";
        return sql.jdbc().queryForObject("""
            INSERT INTO order_receipt (order_number, channel, event_id, content_hash, correlation_id, received_at,
                                       order_state, waiting_for, waiting_order%s%s)
            VALUES (?, 'Peakmart', ?, 'hash', ?, %s, 'Received', ?, '{}'%s%s) RETURNING id"""
                .formatted(lastCheckColumn, reasonColumn, receivedAt, lastCheckValue, reasonValue),
            Long.class, orderNumber, "evt-" + orderNumber, "corr-" + orderNumber, waitingFor);
    }
}
