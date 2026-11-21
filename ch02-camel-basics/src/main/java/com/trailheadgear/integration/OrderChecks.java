package com.trailheadgear.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Plain Java rules every order must pass, whichever channel it came from. */
@Component
public class OrderChecks {

    private static final Pattern ORDER_NUMBER = Pattern.compile("(TG-\\d{4,}|PM-\\d+)");

    public List<String> problems(Order order) {
        List<String> problems = new ArrayList<>();

        checkIdentity(order, problems);
        checkOrderTime(order, problems);
        checkDeliveryAddress(order, problems);
        checkLines(order, problems);

        return List.copyOf(problems);
    }

    private void checkIdentity(Order order, List<String> problems) {
        if (order.orderNumber() == null || !ORDER_NUMBER.matcher(order.orderNumber()).matches()) {
            problems.add("order number missing or malformed: " + order.orderNumber());
        }
        if (!"partner".equals(order.channel()) && !"peakmart".equals(order.channel())) {
            problems.add("channel missing or unknown: " + order.channel());
        } else if ("partner".equals(order.channel()) && blank(order.partnerId())) {
            problems.add("partner ID missing");
        } else if ("peakmart".equals(order.channel()) && blank(order.eventId())) {
            problems.add("event ID missing");
        }
    }

    private void checkOrderTime(Order order, List<String> problems) {
        if (order.orderedAt() == null) {
            problems.add("order time missing");
        }
    }

    private void checkDeliveryAddress(Order order, List<String> problems) {
        if (order.shippingAddress() == null) {
            problems.add("delivery address missing");
        } else {
            require(problems, order.shippingAddress().recipientName(), "recipient name missing");
            require(problems, order.shippingAddress().streetLine(), "delivery street missing");
            require(problems, order.shippingAddress().city(), "delivery city missing");
            require(problems, order.shippingAddress().postalCode(), "delivery postal code missing");
            require(problems, order.shippingAddress().country(), "delivery country missing");
        }
    }

    private void checkLines(Order order, List<String> problems) {
        if (order.lines().isEmpty()) {
            problems.add("order has no lines");
            return;
        }

        for (Order.OrderLine line : order.lines()) {
            if (blank(line.sku())) {
                problems.add("line " + line.lineNumber() + ": SKU missing");
            }
            if (line.quantity() < 1) {
                problems.add("line " + line.lineNumber() + ": quantity must be at least 1");
            }
        }
    }

    private void require(List<String> problems, String value, String message) {
        if (blank(value)) {
            problems.add(message);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
