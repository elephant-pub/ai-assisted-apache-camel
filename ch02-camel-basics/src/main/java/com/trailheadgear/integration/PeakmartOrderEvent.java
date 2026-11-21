package com.trailheadgear.integration;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A captured Peakmart order.placed event; Chapter 5 reads this same JSON from Kafka. */
public record PeakmartOrderEvent(
        @JsonProperty("event_type") String eventType,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("order_ref") String orderRef,
        Buyer buyer,
        @JsonProperty("ship_to") ShipTo shipTo,
        List<Item> items) {

    public record Buyer(String email) {
    }

    public record ShipTo(
            @JsonProperty("full_name") String fullName,
            String address1,
            String address2,
            String city,
            String state,
            String zip,
            @JsonProperty("country_code") String countryCode) {
    }

    public record Item(String sku, int qty, @JsonProperty("price_usd") BigDecimal priceUsd) {
    }
}
