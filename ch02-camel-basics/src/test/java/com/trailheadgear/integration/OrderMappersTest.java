package com.trailheadgear.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class OrderMappersTest {

    private final OrderMappers mappers = new OrderMappers();

    @Test
    void pricesFollowTheRuleForEachChannel() {
        PartnerOrder partner = new PartnerOrder("TG-1060", "summit-outfitters", "2026-11-02T14:05:12Z",
                "lee@example.com", new PartnerOrder.ShipTo("Lee Martin", "18 Canyon Road", "Moab", "84532", "US"),
                List.of(new PartnerOrder.Line("TENT-2P", 1, new BigDecimal("249.00"))));

        Order partnerOrder = mappers.fromPartner(partner);
        assertThat(partnerOrder.lines().getFirst().unitPrice()).isNull();

        PeakmartOrderEvent peakmart = new PeakmartOrderEvent("order.placed", "event-1",
                "2026-11-02T09:14:31Z", "PM-50117", new PeakmartOrderEvent.Buyer("jordan@example.com"),
                new PeakmartOrderEvent.ShipTo("Jordan Reyes", "2240 Pearl Street", "Apt 4", "Boulder", "CO",
                        "80302", "US"),
                List.of(new PeakmartOrderEvent.Item("TENT-2P", 1, new BigDecimal("229.00"))));

        Order peakmartOrder = mappers.fromPeakmart(peakmart);
        assertThat(peakmartOrder.lines().getFirst().unitPrice()).isEqualByComparingTo("229.00");
    }
}
