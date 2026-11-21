package com.trailheadgear.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

/** Translates channel-specific messages into the canonical Order used by the shared routes. */
@Component
public class OrderMappers {

    public Order fromPartner(PartnerOrder source) {
        PartnerOrder.ShipTo address = source.shipTo();
        List<Order.OrderLine> lines = source.lines() == null ? List.of()
                : IntStream.range(0, source.lines().size())
                        .mapToObj(i -> {
                            PartnerOrder.Line line = source.lines().get(i);
                            return new Order.OrderLine(i + 1, line.sku(), line.quantity(), null);
                        })
                        .toList();
        return new Order(source.orderId(), "partner", source.partnerId(), null, parseTime(source.orderedAt()),
                source.customerEmail(), address == null ? null : new Order.ShippingAddress(
                        address.name(), address.street(), address.city(), null, address.postalCode(), address.country()),
                lines);
    }

    public Order fromPeakmart(PeakmartOrderEvent source) {
        PeakmartOrderEvent.ShipTo address = source.shipTo();
        String street = address == null ? null : joinAddress(address.address1(), address.address2());
        List<Order.OrderLine> lines = source.items() == null ? List.of()
                : IntStream.range(0, source.items().size())
                        .mapToObj(i -> {
                            PeakmartOrderEvent.Item item = source.items().get(i);
                            return new Order.OrderLine(i + 1, item.sku(), item.qty(), item.priceUsd());
                        })
                        .toList();
        return new Order(source.orderRef(), "peakmart", null, source.eventId(), parseTime(source.occurredAt()),
                source.buyer() == null ? null : source.buyer().email(),
                address == null ? null : new Order.ShippingAddress(address.fullName(), street, address.city(),
                        address.state(), address.zip(), address.countryCode()),
                lines);
    }

    private String joinAddress(String first, String second) {
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + ", " + second;
    }

    private OffsetDateTime parseTime(String value) {
        return value == null ? null : OffsetDateTime.parse(value);
    }
}
