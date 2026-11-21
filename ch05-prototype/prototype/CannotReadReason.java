import org.apache.camel.component.jsonvalidator.JsonValidationException;

/**
 * Builds the reason stored with a Set-Aside Order Event from a JSON Schema validation failure.
 *
 * <p>The reason is only {@code <path>: <keyword>} of the first validation error, in the order the validator
 * returns them (for example {@code $.buyer.email: format}). The validator's message is never used, because it may
 * echo values from the event (design review item 4). Written against com.networknt:json-schema-validator 2.0.1,
 * the version managed by Camel 4.22.2 ({@code com.networknt.schema.Error#getInstanceLocation()} and
 * {@code #getKeyword()}).
 */
public class CannotReadReason {

    static final String UNREADABLE = "unreadable";
    private static final int MAX_LENGTH = 500;

    public CannotReadReason() {
    }

    public String of(Exception exception) {
        JsonValidationException validation = find(exception);
        if (validation == null || validation.getErrors() == null) {
            return UNREADABLE;
        }
        for (Object candidate : validation.getErrors()) {
            if (candidate instanceof com.networknt.schema.Error error) {
                if (error.getInstanceLocation() == null || isBlank(error.getKeyword())) {
                    return UNREADABLE;
                }
                String path = toPath(error.getInstanceLocation().toString());
                String keyword = error.getKeyword();
                String reason = path + ": " + keyword;
                return reason.length() > MAX_LENGTH ? reason.substring(0, MAX_LENGTH) : reason;
            }
            return UNREADABLE;
        }
        return UNREADABLE;
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

    /**
     * The 2.0.1 validator reports the instance location as a JSON Pointer ({@code /items/0/price_usd}, empty for the
     * root). Turn it into the {@code $.}-path form of the design ({@code $.items[0].price_usd}, {@code $} for the root).
     */
    static String toPath(String location) {
        if (location.startsWith("$")) {
            return location;
        }
        StringBuilder path = new StringBuilder("$");
        for (String token : location.split("/")) {
            if (token.isEmpty()) {
                continue;
            }
            String name = token.replace("~1", "/").replace("~0", "~");
            if (name.chars().allMatch(Character::isDigit)) {
                path.append('[').append(name).append(']');
            } else {
                path.append('.').append(name);
            }
        }
        return path.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
