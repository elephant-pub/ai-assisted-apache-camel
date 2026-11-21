package com.trailheadgear.integration;

import java.util.ArrayList;
import java.util.List;

import org.apache.camel.Body;
import org.springframework.stereotype.Component;

/** Builds the Chapter 2 subset of the warehouse CSV; catalog fields arrive in Chapter 6. */
@Component
public class PickListRows {

    public List<List<Object>> fromOrder(@Body Order order) {
        Order.ShippingAddress address = order.shippingAddress();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("order_no", "line_no", "sku", "qty", "ship_name", "ship_street", "ship_city",
                "ship_postcode", "ship_country"));
        for (Order.OrderLine line : order.lines()) {
            rows.add(List.of(order.orderNumber(), line.lineNumber(), line.sku(), line.quantity(),
                    address.recipientName(), address.streetLine(), address.city(), address.postalCode(),
                    address.country()));
        }
        return rows;
    }
}
