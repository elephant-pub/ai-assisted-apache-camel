package com.trailheadgear.integration;

import java.math.BigDecimal;
import java.util.List;

/** The JSON shape partners post to Trailhead Gear's HTTP API. */
public record PartnerOrder(
        String orderId,
        String partnerId,
        String orderedAt,
        String customerEmail,
        ShipTo shipTo,
        List<Line> lines) {

    public record ShipTo(String name, String street, String city, String postalCode, String country) {
    }

    public record Line(String sku, int quantity, BigDecimal unitPrice) {
    }
}
