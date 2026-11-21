package com.trailheadgear.integration;

import java.util.List;

import org.apache.camel.Body;
import org.apache.camel.Header;
import org.springframework.stereotype.Component;

@Component
public class OrderAnswers {

    public record Answer(String orderNumber, String result, List<String> rejectionReasons) {
    }

    public Answer rejected(@Body Order order, @Header("problems") List<String> problems) {
        return new Answer(order.orderNumber(), "rejected", problems);
    }

    public Answer accepted(@Header("orderNumber") String orderNumber) {
        return new Answer(orderNumber, "accepted", List.of());
    }
}
