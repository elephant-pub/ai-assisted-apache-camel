package com.trailheadgear.orderintegration;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The bodies of the partner endpoint's responses (design 003, partner-order-answer; docs/api/partner-orders.openapi.yaml,
 * docs/messages/order-answer.schema.json), written with Jackson so no value is concatenated into JSON by hand.
 * <ul>
 *   <li>200: the Order Answer {@code {"orderNumber", "result": "accepted"}}, {@code application/json};</li>
 *   <li>400, 422, 503: the contract's {@code OrderProblem}: Problem Details plus {@code orderNumber} (when known),
 *       {@code result} and {@code rejectionReasons} (only for {@code rejected}), {@code application/problem+json};</li>
 *   <li>401, 403: a plain Problem without Order Answer members.</li>
 * </ul>
 * The {@code detail} texts are fixed and name no personal data; there is no {@code instance} and no correlation id
 * (C-23).
 */
public class OrderAnswers {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The Content-Type of the response with this status. */
    public String contentType(int status) {
        return status == 200 ? "application/json" : "application/problem+json";
    }

    /** One reason without an Order Check, as the JSON array of an unreadable request's answer (UC-001 A3). */
    public String unreadableReasons(String text) throws IOException {
        ArrayNode reasons = mapper.createArrayNode();
        reasons.addObject().put("text", text);
        return mapper.writeValueAsString(reasons);
    }

    /**
     * The response body.
     *
     * @param reasonsJson the JSON array of Rejection Reasons, used only for 400 and 422
     */
    public String body(int status, String orderNumber, String reasonsJson) throws IOException {
        if (status == 200) {
            ObjectNode answer = mapper.createObjectNode();
            if (orderNumber != null) {
                answer.put("orderNumber", orderNumber);
            }
            answer.put("result", "accepted");
            return mapper.writeValueAsString(answer);
        }
        ObjectNode problem = mapper.createObjectNode();
        problem.put("type", "about:blank");
        problem.put("title", title(status));
        problem.put("status", status);
        problem.put("detail", detail(status));
        if (status == 400 || status == 422 || status == 503) {
            if (orderNumber != null && !orderNumber.isEmpty()) {
                problem.put("orderNumber", orderNumber);
            }
            if (status == 503) {
                problem.put("result", "temporarily-unavailable");
            } else {
                problem.put("result", "rejected");
                JsonNode reasons = reasonsJson == null ? null : mapper.readTree(reasonsJson);
                problem.set("rejectionReasons", reasons != null && reasons.isArray() ? reasons : mapper.createArrayNode());
            }
        }
        return mapper.writeValueAsString(problem);
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 422 -> "Unprocessable Content";
            case 503 -> "Service Unavailable";
            default -> throw new IllegalArgumentException("No Order Answer for status " + status);
        };
    }

    private static String detail(int status) {
        return switch (status) {
            case 400 -> "The request cannot be read as a Partner Order.";
            case 401 -> "The request has no API Key, or one that belongs to no registered Partner.";
            case 403 -> "The Partner Order names a Partner other than the one the API Key belongs to.";
            case 422 -> "The Order fails at least one Order Check.";
            case 503 -> "The Order cannot be checked or recorded now. Send the request again later.";
            default -> throw new IllegalArgumentException("No Order Answer for status " + status);
        };
    }
}
