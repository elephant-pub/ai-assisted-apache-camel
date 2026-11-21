package com.trailheadgear.orderintegration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.component.jsonvalidator.JsonValidationException;

/**
 * The partner request rules that are not Order Checks (design 003, partner-order-submit steps 5 and 7): what makes a
 * request unreadable (UC-001 A3, C-10) and what counts as the same content (C-6). A reason names the field and the
 * problem, never a value from the request.
 */
public class PartnerOrders {

    static final String PREFIX = "The request cannot be read as a Partner Order: ";
    private static final int MAX_ORDER_ID = 30;
    /** A field name from the request is named in a reason only when it looks like a field name. */
    private static final Pattern FIELD_NAME = Pattern.compile("^[A-Za-z0-9_]{1,50}$");

    private final Pattern emailPattern;
    private final ObjectMapper mapper = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    public PartnerOrders(String emailPattern) {
        this.emailPattern = Pattern.compile(emailPattern);
    }

    /** The reason of a request larger than {@code maxChars} characters. */
    public String tooLargeReason(int maxChars) {
        return PREFIX + "the request is larger than " + maxChars + " characters.";
    }

    /**
     * The reason of a request that fails partner-order.schema.json, from the first validation error: the field path and
     * what is wrong with it. Anything that is not a schema failure (the body is not JSON) gives "the request is not JSON".
     */
    public String cannotReadReason(Exception exception) {
        JsonValidationException validation = find(exception);
        if (validation == null || validation.getErrors() == null || validation.getErrors().isEmpty()) {
            return PREFIX + "the request is not JSON.";
        }
        Object first = validation.getErrors().iterator().next();
        if (!(first instanceof com.networknt.schema.Error error)) {
            return PREFIX + "the request does not have the form of a Partner Order.";
        }
        String location = error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString();
        String path = CannotReadReason.toPath(location);
        String field = path.equals("$") ? "" : path.substring(2);
        String keyword = error.getKeyword() == null ? "" : error.getKeyword();
        String property = error.getProperty();
        boolean namedProperty = property != null && FIELD_NAME.matcher(property).matches();
        String child = namedProperty ? (field.isEmpty() ? property : field + "." + property) : null;
        String reason = switch (keyword) {
            case "required" -> child != null ? "the field " + child + " is missing." : "a required field is missing.";
            case "additionalProperties" -> child != null ? "the field " + child + " is not part of the Partner Order."
                : "it holds a field that is not part of the Partner Order.";
            case "type" -> field.isEmpty() ? "the request is not a JSON object." : "the field " + field + " has the wrong type.";
            case "minLength" -> "the field " + field + " is empty.";
            default -> field.isEmpty() ? "the request does not have the form of a Partner Order (" + keyword + ")."
                : "the field " + field + " is not valid (" + keyword + ").";
        };
        return PREFIX + reason;
    }

    /**
     * The guards JSON Schema cannot enforce, because {@code format} is not asserted (C-10): {@code orderedAt} is an
     * ISO-8601 date-time with offset; {@code customerEmail}, when present, matches the shared email pattern;
     * {@code orderId} has at most 30 characters and no control characters. Null when the request passes them.
     */
    public String readableReason(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        String orderId = root.path("orderId").asText();
        if (orderId.length() > MAX_ORDER_ID) {
            return PREFIX + "the field orderId is longer than " + MAX_ORDER_ID + " characters.";
        }
        if (hasControl(orderId)) {
            return PREFIX + "the field orderId holds a control character.";
        }
        try {
            OffsetDateTime.parse(root.path("orderedAt").asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException e) {
            return PREFIX + "the field orderedAt is not a date and time with an offset.";
        }
        JsonNode email = root.get("customerEmail");
        if (email != null && !emailPattern.matcher(email.asText()).matches()) {
            return PREFIX + "the field customerEmail is not an email address.";
        }
        return null;
    }

    /**
     * The Order Number to put into the answer of an unreadable request: the {@code orderId} when the body is JSON and
     * it is a non-empty string of at most 30 characters without control characters, otherwise null.
     */
    public String orderNumberOf(String body) {
        try {
            JsonNode orderId = mapper.readTree(body).get("orderId");
            if (orderId == null || !orderId.isTextual()) {
                return null;
            }
            String value = orderId.asText();
            return value.isEmpty() || value.length() > MAX_ORDER_ID || hasControl(value) ? null : value;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * SHA-256 hex of the canonical JSON value of the request (C-6): object keys sorted, no whitespace, numbers by value.
     * A resend that only reformats the JSON has the same hash.
     */
    public String contentHash(String body) throws IOException {
        StringBuilder canonical = new StringBuilder();
        write(mapper.readTree(body), canonical);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(JsonNode node, StringBuilder out) throws IOException {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(null);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(mapper.writeValueAsString(names.get(i))).append(':');
                write(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            Iterator<JsonNode> items = node.elements();
            boolean firstItem = true;
            while (items.hasNext()) {
                if (!firstItem) {
                    out.append(',');
                }
                firstItem = false;
                write(items.next(), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            BigDecimal value = node.decimalValue();
            out.append(value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString());
        } else {
            out.append(mapper.writeValueAsString(node));
        }
    }

    /** C0 and C1 control characters (U+0000-U+001F, U+007F-U+009F). */
    private static boolean hasControl(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static JsonValidationException find(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof JsonValidationException validation) {
                return validation;
            }
            current = current.getCause();
        }
        return null;
    }
}
