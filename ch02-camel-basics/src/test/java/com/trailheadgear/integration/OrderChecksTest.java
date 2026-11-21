package com.trailheadgear.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class OrderChecksTest {

    private final OrderChecks checks = new OrderChecks();

    @Test
    void aGoodOrderHasNoProblems() {
        assertThat(checks.problems(order("TG-1060", "TENT-2P", 1))).isEmpty();
    }

    @Test
    void reportsEveryProblemNotJustTheFirst() {
        Order broken = new Order("1060", "partner", "summit-outfitters", null,
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com", null,
                List.of(new Order.OrderLine(1, "", 0, new BigDecimal("249.00"))));

        assertThat(checks.problems(broken)).containsExactly(
                "order number missing or malformed: 1060",
                "delivery address missing",
                "line 1: SKU missing",
                "line 1: quantity must be at least 1");
    }

    @Test
    void anOrderNeedsLines() {
        Order noLines = new Order("TG-1060", "partner", "summit-outfitters", null,
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com",
                new Order.ShippingAddress("Lee Martin", "18 Canyon Road", "Moab", null, "84532", "US"),
                null);

        assertThat(noLines.lines()).isEmpty();
        assertThat(checks.problems(noLines)).containsExactly("order has no lines");
    }

    @Test
    void anOrderNeedsACompleteDeliveryAddress() {
        Order incomplete = new Order("TG-1060", "partner", "summit-outfitters", null,
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com",
                new Order.ShippingAddress("Lee Martin", "", "Moab", null, "", "US"),
                List.of(new Order.OrderLine(1, "TENT-2P", 1, new BigDecimal("249.00"))));

        assertThat(checks.problems(incomplete)).containsExactly(
                "delivery street missing", "delivery postal code missing");
    }

    @Test
    void reportsEveryChannelAndMetadataProblem() {
        Order partner = new Order("TG-1060", "partner", "", null, null, "lee@example.com",
                new Order.ShippingAddress("", "18 Canyon Road", "", null, "84532", ""),
                List.of(new Order.OrderLine(1, "TENT-2P", 1, new BigDecimal("249.00"))));
        assertThat(checks.problems(partner)).containsExactly(
                "partner ID missing", "order time missing", "recipient name missing",
                "delivery city missing", "delivery country missing");

        Order peakmart = new Order("PM-50117", "peakmart", null, "",
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com",
                new Order.ShippingAddress("Lee Martin", "18 Canyon Road", "Moab", null, "84532", "US"),
                List.of(new Order.OrderLine(1, "TENT-2P", 1, new BigDecimal("249.00"))));
        assertThat(checks.problems(peakmart)).containsExactly("event ID missing");

        Order unknown = new Order("TG-1060", "unknown", null, null,
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com",
                new Order.ShippingAddress("Lee Martin", "18 Canyon Road", "Moab", null, "84532", "US"),
                List.of(new Order.OrderLine(1, "TENT-2P", 1, new BigDecimal("249.00"))));
        assertThat(checks.problems(unknown)).containsExactly("channel missing or unknown: unknown");
    }

    private Order order(String number, String sku, int quantity) {
        return new Order(number, "partner", "summit-outfitters", null,
                OffsetDateTime.parse("2026-11-02T14:05:12Z"), "lee@example.com",
                new Order.ShippingAddress("Lee Martin", "18 Canyon Road", "Moab", null, "84532", "US"),
                List.of(new Order.OrderLine(1, sku, quantity, new BigDecimal("249.00"))));
    }
}
