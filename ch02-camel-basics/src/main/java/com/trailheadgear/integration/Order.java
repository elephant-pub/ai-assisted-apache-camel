package com.trailheadgear.integration;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** One order, whichever channel it came from. */
public record Order(
        String orderNumber,
        String channel,
        String partnerId,
        String eventId,
        OffsetDateTime orderedAt,
        String customerEmail,
        ShippingAddress shippingAddress,
        List<OrderLine> lines) {

    public Order {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public record ShippingAddress(
            String recipientName,
            String streetLine,
            String city,
            String stateRegion,
            String postalCode,
            String country) {
    }

    public record OrderLine(int lineNumber, String sku, int quantity, BigDecimal unitPrice) {
    }
}
