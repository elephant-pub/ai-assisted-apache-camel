package com.trailheadgear.orderintegration.failure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * T5 (design 005 §3.9): the notification text lists every Rejection Reason in check order, for both Channels. The
 * production statements run verbatim against PostgreSQL: order-reject.sql writes, notification-select.sql and
 * rejection-email-select.sql read.
 */
class NotificationTextIT {

    private static final String REASONS = """
        [{"orderCheck":"order-number-format","text":"The Order Number 12 is not valid."},
         {"orderCheck":"quantity","lineNumber":1,"text":"Order Line 1: the quantity 0 is not allowed."},
         {"orderCheck":"destination-country","text":"The Shipping Address is in MX."}]""";

    private static final String EXPECTED_LINES = String.join("\n",
        "- The Order Number 12 is not valid.",
        "- Order Line 1: the quantity 0 is not allowed.",
        "- The Shipping Address is in MX.");

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
    void peakmartAndPartnerNotificationsListEveryReasonInCheckOrder() {
        var jdbc = sql.jdbc();
        jdbc.update("""
            INSERT INTO partner (partner_code, name, api_key_name, order_number_from, order_number_to,
                                 order_contact_email, registered_at)
            VALUES ('summit', 'Summit', 'summit-key', 0, 999999, 'orders@summit.example.com', now())""");
        Long partnerId = jdbc.queryForObject("SELECT id FROM partner WHERE partner_code = 'summit'", Long.class);
        long peakmart = jdbc.queryForObject("""
            INSERT INTO order_receipt (order_number, channel, event_id, content_hash, correlation_id, received_at,
                                       order_state)
            VALUES ('PM-1', 'Peakmart', 'evt-1', 'hash-1', 'corr-peakmart', now(), 'Received') RETURNING id""",
            Long.class);
        long partner = jdbc.queryForObject("""
            INSERT INTO order_receipt (order_number, channel, partner_id, content_hash, correlation_id, received_at,
                                       order_state)
            VALUES ('12', 'Partner', ?, 'hash-2', 'corr-partner', now(), 'Received') RETURNING id""",
            Long.class, partnerId);

        var peakmartResult = sql.run("order-reject.sql", Map.of("receipt-id", peakmart, "reasons", REASONS,
            "notification-type", "Order Desk Notification", "recipient", "orderdesk@trailhead.example.com"));
        var partnerResult = sql.run("order-reject.sql", Map.of("receipt-id", partner, "reasons", REASONS,
            "notification-type", "Rejection Email", "recipient", "orders@summit.example.com"));
        assertThat(peakmartResult.updateCount()).as("order-reject.sql update count for PM-1").isEqualTo(1);
        assertThat(partnerResult.updateCount()).as("order-reject.sql update count for 12").isEqualTo(1);

        List<Map<String, Object>> orderDesk = sql.run("notification-select.sql", Map.of()).rows();
        assertThat(orderDesk).as("rows of notification-select.sql").hasSize(1);
        assertThat(orderDesk.get(0)).as("Order Desk Notification of PM-1")
            .containsEntry("recipient_email", "orderdesk@trailhead.example.com")
            .containsEntry("order_number", "PM-1")
            .containsKey("reason_lines");
        // Design 006 D13: the report names the check, the expected and actual text and the order number
        Checks.check("T5 reason_lines of the Order Desk Notification", EXPECTED_LINES,
            orderDesk.get(0).get("reason_lines"), "PM-1");

        List<Map<String, Object>> email = sql.run("rejection-email-select.sql", Map.of()).rows();
        assertThat(email).as("rows of rejection-email-select.sql").hasSize(1);
        assertThat(email.get(0)).as("Rejection Email of 12")
            .containsEntry("recipient_email", "orders@summit.example.com")
            .containsEntry("order_number", "12")
            .containsKey("reason_lines");
        Checks.check("T5 reason_lines of the Rejection Email", EXPECTED_LINES, email.get(0).get("reason_lines"), "12");
    }
}
