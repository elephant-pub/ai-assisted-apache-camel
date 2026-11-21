package com.trailheadgear.orderintegration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The UC-003 Order Checks for an Order of either Channel (canonical Order JSON, docs/messages/order.schema.json).
 *
 * <p>{@link #structuralReasons(String, List)} runs the checks that need only the Order, in check order. {@link #evaluate}
 * adds the per-line SKU checks from the Product Catalog answers and decides the outcome. Reasons have the shape of
 * docs/messages/rejection-reason.schema.json ({@code orderCheck}, optional {@code lineNumber}, {@code text}) and
 * name no Customer, email address or Shipping Address (C-023).
 */
public class OrderChecks {

    public static final String PASSED = "PASSED";
    public static final String REJECTED = "REJECTED";
    public static final String CATALOG_OUTAGE = "CATALOG_OUTAGE";

    public static final String FOUND = "FOUND";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String OUTAGE = "OUTAGE";

    private static final Pattern PEAKMART_ORDER_NUMBER = Pattern.compile("^PM-\\d+$");
    private static final Pattern PARTNER_ORDER_NUMBER = Pattern.compile("^TG-\\d{4,}$");
    /** {@code TG-} followed by a digit string of any length: the range check applies (C-11). */
    private static final Pattern PARTNER_ORDER_DIGITS = Pattern.compile("^TG-(\\d+)$");
    private static final String PARTNER_CHANNEL = "partner";
    private static final int MIN_LINES = 1;
    private static final int MAX_LINES = 30;
    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 20;
    private static final int MAX_TEXT = 500;
    /** REJECTION_REASON.position allows 1 to 100. */
    private static final int MAX_REASONS = 100;
    private static final String[][] ADDRESS_PARTS = {
        {"recipientName", "recipient name"},
        {"streetLine", "street line"},
        {"city", "city"},
        {"postalCode", "postal code"},
        {"country", "country"}
    };

    private final ObjectMapper mapper = new ObjectMapper();

    public OrderChecks() {
    }

    /**
     * UC-003 steps 1-6: order-number-format (by Channel), the Partner-only order number reasons, number-of-order-lines,
     * quantity, shipping-address, destination-country, in check order (BR-010).
     *
     * @param orderNumberReasons the Partner-only reasons of {@link #partnerNumberReasons} (range, already used), put
     *                           right after the format reason; null for a Peakmart Order (UC-003 A1)
     */
    public List<Map<String, Object>> structuralReasons(String order, List<Map<String, Object>> orderNumberReasons)
        throws Exception {
        JsonNode root = mapper.readTree(order);
        List<Map<String, Object>> reasons = new ArrayList<>();

        String orderNumber = text(root.get("orderNumber"));
        if (isPartner(root)) {
            if (orderNumber == null || !PARTNER_ORDER_NUMBER.matcher(orderNumber).matches()) {
                reasons.add(reason("order-number-format", null, "The Order Number " + (orderNumber == null ? "" : orderNumber)
                    + " is not TG- followed by four or more digits. Use an order number from your Order Number Range."));
            }
        } else if (orderNumber == null || !PEAKMART_ORDER_NUMBER.matcher(orderNumber).matches()) {
            reasons.add(reason("order-number-format", null, "The Order Number " + (orderNumber == null ? "" : orderNumber)
                + " is not PM- followed by digits. Send the Peakmart order reference."));
        }
        if (orderNumberReasons != null) {
            reasons.addAll(orderNumberReasons);
        }

        JsonNode lines = root.get("lines");
        int lineCount = lines != null && lines.isArray() ? lines.size() : 0;
        if (lineCount < MIN_LINES || lineCount > MAX_LINES) {
            reasons.add(reason("number-of-order-lines", null, "The order has " + lineCount
                + " Order Lines; it must have 1 to 30. Split or complete the order."));
        }

        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) {
                JsonNode quantity = line.get("quantity");
                if (quantity == null || !quantity.canConvertToInt() || quantity.asInt() < MIN_QUANTITY
                    || quantity.asInt() > MAX_QUANTITY) {
                    int lineNumber = line.path("lineNumber").asInt();
                    reasons.add(reason("quantity", lineNumber, "Order Line " + lineNumber + ": the quantity "
                        + (quantity == null ? "" : quantity.asText()) + " is not between 1 and 20. Change the quantity."));
                }
            }
        }

        JsonNode address = root.path("shippingAddress");
        for (String[] part : ADDRESS_PARTS) {
            if (isBlank(text(address.get(part[0])))) {
                reasons.add(reason("shipping-address", null, "The Shipping Address has no " + part[1] + ". Add it."));
            }
        }

        String country = text(address.get("country"));
        if (!isBlank(country) && !"US".equals(country) && !"CA".equals(country)) {
            reasons.add(reason("destination-country", null, "The Shipping Address is in " + country
                + "; Trailhead Gear ships to US and CA only."));
        }
        return reasons;
    }

    /**
     * The Partner-only order number checks (UC-003 BR-003, BR-004), in check order.
     * <ul>
     *   <li>{@code order-number-range}: the Order Number is {@code TG-} followed by digits (any count) and the number is
     *       outside {@code [rangeFirst, rangeLast]}, both included; compared as {@link BigInteger}, so a long digit
     *       string cannot overflow. Without a digit string after {@code TG-} there is only the format reason (C-11).</li>
     *   <li>{@code order-number-already-used}: an Accepted Order with this Order Number exists and its content differs
     *       from this request's ({@code acceptedContentHash} not null and not equal to {@code contentHash}).</li>
     * </ul>
     */
    public List<Map<String, Object>> partnerNumberReasons(String order, Long rangeFirst, Long rangeLast,
        String acceptedContentHash, String contentHash) throws Exception {
        String orderNumber = text(mapper.readTree(order).get("orderNumber"));
        List<Map<String, Object>> reasons = new ArrayList<>();
        Matcher digits = orderNumber == null ? null : PARTNER_ORDER_DIGITS.matcher(orderNumber);
        if (digits != null && digits.matches() && rangeFirst != null && rangeLast != null) {
            BigInteger number = new BigInteger(digits.group(1));
            if (number.compareTo(BigInteger.valueOf(rangeFirst)) < 0 || number.compareTo(BigInteger.valueOf(rangeLast)) > 0) {
                reasons.add(reason("order-number-range", null, "The Order Number " + orderNumber
                    + " is outside your Order Number Range " + rangeFirst + " to " + rangeLast
                    + ". Use a number from your range."));
            }
        }
        if (acceptedContentHash != null && !acceptedContentHash.equals(contentHash)) {
            reasons.add(reason("order-number-already-used", null, "The Order Number " + orderNumber
                + " is already used by an accepted order with different content. Use a new order number."));
        }
        return reasons;
    }

    /**
     * UC-003 steps 7-9 and the outcome: any OUTAGE answer gives CATALOG_OUTAGE (BR-012); otherwise SKU reasons are
     * appended after the structural reasons; any reason gives REJECTED; otherwise PASSED with every line completed
     * from the Product Catalog (BR-011), keeping the Peakmart Price.
     *
     * @return {@code outcome}, {@code reasons} (JSON array text), {@code order} (JSON text), {@code reasonCount},
     *         {@code lineCount}
     */
    public Map<String, Object> evaluate(String order, List<Map<String, Object>> structuralReasons, Object catalogAnswers)
        throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(order);
        JsonNode linesNode = root.get("lines");
        ArrayNode lines = linesNode != null && linesNode.isArray() ? (ArrayNode) linesNode : mapper.createArrayNode();

        Map<Integer, Map<?, ?>> answers = new LinkedHashMap<>();
        boolean outage = false;
        // The split over no lines leaves the original body (the Order text); there are then no answers.
        if (catalogAnswers instanceof List<?> answerList) {
            for (Object candidate : answerList) {
                if (!(candidate instanceof Map<?, ?> answer)) {
                    outage = true;
                    continue;
                }
                if (OUTAGE.equals(answer.get("status"))) {
                    outage = true;
                }
                Object lineNumber = answer.get("lineNumber");
                if (lineNumber instanceof Number number) {
                    answers.put(number.intValue(), answer);
                }
            }
        }

        List<Map<String, Object>> reasons = new ArrayList<>(structuralReasons == null ? List.of() : structuralReasons);
        if (!outage) {
            for (JsonNode line : lines) {
                int lineNumber = line.path("lineNumber").asInt();
                String sku = text(line.get("sku"));
                Map<?, ?> answer = answers.get(lineNumber);
                if (answer == null) {
                    outage = true;
                    break;
                }
                if (UNKNOWN.equals(answer.get("status"))) {
                    reasons.add(reason("sku", lineNumber, "Order Line " + lineNumber + ": the SKU " + sku
                        + " is not in the Product Catalog. Replace it with a catalog SKU."));
                } else if (!Boolean.TRUE.equals(product(answer).get("active"))) {
                    reasons.add(reason("sku", lineNumber, "Order Line " + lineNumber + ": the SKU " + sku
                        + " is not an Active Product in the Product Catalog. Replace it with an active SKU."));
                }
            }
        }

        String outcome;
        if (outage) {
            outcome = CATALOG_OUTAGE;
            reasons = List.of();
        } else if (!reasons.isEmpty()) {
            outcome = REJECTED;
            if (reasons.size() > MAX_REASONS) {
                reasons = new ArrayList<>(reasons.subList(0, MAX_REASONS));
            }
        } else {
            outcome = PASSED;
            boolean partner = isPartner(root);
            for (JsonNode line : lines) {
                Map<?, ?> product = product(answers.get(line.path("lineNumber").asInt()));
                ObjectNode completed = (ObjectNode) line;
                if (partner) {
                    // The Catalog Price applies to a Partner Order (UC-001 BR-006, FR-019); Peakmart keeps its price (FR-020)
                    ObjectNode unitPrice = completed.putObject("unitPrice");
                    unitPrice.put("amount", new BigDecimal(String.valueOf(product.get("listPrice")))
                        .setScale(2, RoundingMode.HALF_UP).toPlainString());
                    unitPrice.put("currency", "USD");
                }
                completed.put("productName", String.valueOf(product.get("name")));
                completed.put("unitWeightGrams", ((Number) product.get("weightGrams")).intValue());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcome", outcome);
        result.put("reasons", mapper.writeValueAsString(reasons));
        result.put("order", mapper.writeValueAsString(root));
        result.put("reasonCount", reasons.size());
        result.put("lineCount", lines.size());
        return result;
    }

    /**
     * Request path of the Product Catalog lookup, with the SKU URL-encoded as one path segment. Dots are encoded too,
     * so a SKU of {@code .} or {@code ..} can never be normalised into another path.
     */
    public String productPath(String sku) {
        return "/products/" + URLEncoder.encode(sku == null ? "" : sku, StandardCharsets.UTF_8)
            .replace("+", "%20").replace(".", "%2E");
    }

    /** One per-line Product Catalog answer: FOUND (with the product), UNKNOWN or OUTAGE. */
    public Map<String, Object> answer(Integer lineNumber, String sku, String status, String productJson) throws Exception {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("lineNumber", lineNumber);
        answer.put("sku", sku);
        answer.put("status", status);
        if (FOUND.equals(status) && productJson != null) {
            answer.put("product", mapper.readValue(productJson, Map.class));
        }
        return answer;
    }

    private static Map<?, ?> product(Map<?, ?> answer) {
        Object product = answer == null ? null : answer.get("product");
        return product instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Object> reason(String orderCheck, Integer lineNumber, String text) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("orderCheck", orderCheck);
        if (lineNumber != null && lineNumber >= 1 && lineNumber <= MAX_LINES) {
            reason.put("lineNumber", lineNumber);
        }
        reason.put("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        return reason;
    }

    private static boolean isPartner(JsonNode root) {
        return PARTNER_CHANNEL.equals(text(root.get("channel")));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
