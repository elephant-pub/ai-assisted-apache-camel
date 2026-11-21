package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Design 003: channel-aware order number format, the Partner order number reasons and the Catalog Price. */
class OrderChecksPartnerTest {

    private final OrderChecks checks = new OrderChecks();
    private final ObjectMapper mapper = new ObjectMapper();

    private static String partnerOrder(String orderNumber) {
        return "{\"orderNumber\":\"" + orderNumber + "\",\"channel\":\"partner\",\"partnerId\":\"summit-outfitters\","
            + "\"shippingAddress\":{\"recipientName\":\"R\",\"streetLine\":\"S\",\"city\":\"C\",\"postalCode\":\"P\","
            + "\"country\":\"US\"},\"lines\":[{\"lineNumber\":1,\"sku\":\"TENT-2P\",\"quantity\":1},"
            + "{\"lineNumber\":2,\"sku\":\"LAMP-HD200\",\"quantity\":2}]}";
    }

    private static String peakmartOrder(String orderNumber) {
        return "{\"orderNumber\":\"" + orderNumber + "\",\"channel\":\"peakmart\",\"eventId\":\"e\","
            + "\"shippingAddress\":{\"recipientName\":\"R\",\"streetLine\":\"S\",\"city\":\"C\",\"postalCode\":\"P\","
            + "\"country\":\"US\"},\"lines\":[{\"lineNumber\":1,\"sku\":\"TENT-2P\",\"quantity\":1,"
            + "\"unitPrice\":{\"amount\":\"199.99\",\"currency\":\"USD\"}}]}";
    }

    private static List<String> checksOf(List<Map<String, Object>> reasons) {
        List<String> names = new ArrayList<>();
        reasons.forEach(r -> names.add((String) r.get("orderCheck")));
        return names;
    }

    private List<Map<String, Object>> partnerReasons(String orderNumber, String acceptedHash, String hash) throws Exception {
        String order = partnerOrder(orderNumber);
        return checks.structuralReasons(order, checks.partnerNumberReasons(order, 1000L, 1999L, acceptedHash, hash));
    }

    @Test
    void partnerFormatAcceptedInRange() throws Exception {
        assertEquals(List.of(), partnerReasons("TG-1060", null, "h"));
    }

    @Test
    void partnerFormatRejectedWithPartnerText() throws Exception {
        List<Map<String, Object>> reasons = partnerReasons("PM-1060", null, "h");
        assertEquals(List.of("order-number-format"), checksOf(reasons));
        assertEquals("The Order Number PM-1060 is not TG- followed by four or more digits. Use an order number from "
            + "your Order Number Range.", reasons.get(0).get("text"));
    }

    @Test
    void shortNumberGetsFormatAndRange() throws Exception {
        assertEquals(List.of("order-number-format", "order-number-range"), checksOf(partnerReasons("TG-12", null, "h")));
    }

    @Test
    void numberOutsideRange() throws Exception {
        List<Map<String, Object>> reasons = partnerReasons("TG-2500", null, "h");
        assertEquals(List.of("order-number-range"), checksOf(reasons));
        assertEquals("The Order Number TG-2500 is outside your Order Number Range 1000 to 1999. Use a number from your "
            + "range.", reasons.get(0).get("text"));
    }

    @Test
    void rangeBoundsIncluded() throws Exception {
        assertEquals(List.of(), partnerReasons("TG-1000", null, "h"));
        assertEquals(List.of(), partnerReasons("TG-1999", null, "h"));
    }

    @Test
    void noDigitsGivesOnlyFormat() throws Exception {
        assertEquals(List.of("order-number-format"), checksOf(partnerReasons("TG-", null, "h")));
        assertEquals(List.of("order-number-format"), checksOf(partnerReasons("TG-12A4", null, "h")));
    }

    @Test
    void longDigitStringDoesNotOverflow() throws Exception {
        assertEquals(List.of("order-number-range"),
            checksOf(partnerReasons("TG-" + "9".repeat(40), null, "h")));
    }

    @Test
    void alreadyUsedOnlyWhenContentDiffers() throws Exception {
        assertEquals(List.of(), partnerReasons("TG-1060", "same", "same"));
        List<Map<String, Object>> reasons = partnerReasons("TG-1060", "accepted", "other");
        assertEquals(List.of("order-number-already-used"), checksOf(reasons));
        assertEquals("The Order Number TG-1060 is already used by an accepted order with different content. Use a new "
            + "order number.", reasons.get(0).get("text"));
    }

    @Test
    void reasonsInCheckOrder() throws Exception {
        String order = partnerOrder("TG-12").replace("\"quantity\":2", "\"quantity\":25").replace("\"US\"", "\"DE\"");
        List<Map<String, Object>> reasons =
            checks.structuralReasons(order, checks.partnerNumberReasons(order, 1000L, 1999L, "a", "b"));
        assertEquals(List.of("order-number-format", "order-number-range", "order-number-already-used", "quantity",
            "destination-country"), checksOf(reasons));
    }

    @Test
    void peakmartFormatTextUnchanged() throws Exception {
        List<Map<String, Object>> reasons = checks.structuralReasons(peakmartOrder("TG-1060"), null);
        assertEquals("The Order Number TG-1060 is not PM- followed by digits. Send the Peakmart order reference.",
            reasons.get(0).get("text"));
        assertEquals(List.of(), checks.structuralReasons(peakmartOrder("PM-50117"), null));
    }

    private static Map<String, Object> found(int lineNumber, Object listPrice) {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", "Product " + lineNumber);
        product.put("listPrice", listPrice);
        product.put("weightGrams", 100);
        product.put("active", true);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("lineNumber", lineNumber);
        answer.put("status", OrderChecks.FOUND);
        answer.put("product", product);
        return answer;
    }

    @Test
    void partnerGetsCatalogPrice() throws Exception {
        Map<String, Object> result = checks.evaluate(partnerOrder("TG-1060"), List.of(),
            List.of(found(2, 39.95), found(1, 249)));
        assertEquals(OrderChecks.PASSED, result.get("outcome"));
        JsonNode lines = mapper.readTree((String) result.get("order")).get("lines");
        assertEquals("249.00", lines.get(0).at("/unitPrice/amount").asText());
        assertEquals("USD", lines.get(0).at("/unitPrice/currency").asText());
        assertEquals("39.95", lines.get(1).at("/unitPrice/amount").asText());
    }

    @Test
    void catalogPriceRoundsHalfUp() throws Exception {
        Map<String, Object> result = checks.evaluate(partnerOrder("TG-1060"), List.of(),
            List.of(found(1, 10.005), found(2, 1)));
        JsonNode lines = mapper.readTree((String) result.get("order")).get("lines");
        assertEquals("10.01", lines.get(0).at("/unitPrice/amount").asText());
        assertEquals("1.00", lines.get(1).at("/unitPrice/amount").asText());
    }

    @Test
    void peakmartKeepsItsPrice() throws Exception {
        Map<String, Object> result = checks.evaluate(peakmartOrder("PM-50117"), List.of(), List.of(found(1, 249)));
        assertEquals(OrderChecks.PASSED, result.get("outcome"));
        JsonNode line = mapper.readTree((String) result.get("order")).get("lines").get(0);
        assertEquals("199.99", line.at("/unitPrice/amount").asText());
        assertTrue(line.has("productName"));
    }
}
