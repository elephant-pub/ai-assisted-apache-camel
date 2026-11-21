package com.trailhead.catalogcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/** Finds the endpoint URI shapes supported by this check, with their parameters joined on as a query string. */
final class EndpointUris {

    /** YAML DSL keys whose value is an endpoint. */
    private static final Set<String> ENDPOINT_KEYS =
            Set.of("from", "to", "toD", "wireTap", "enrich", "pollEnrich", "poll", "deadLetterChannel");

    private EndpointUris() {
    }

    static List<String> find(Object yaml) {
        List<String> uris = new ArrayList<>();
        walk(yaml, uris);
        return uris;
    }

    private static void walk(Object node, List<String> uris) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("parameters".equals(key)) {
                    return; // endpoint options, such as the mail component's "from", are not endpoints
                }
                if (ENDPOINT_KEYS.contains(String.valueOf(key))) {
                    String uri = uriOf(value);
                    if (uri != null) {
                        uris.add(uri);
                    }
                }
                walk(value, uris);
            });
        } else if (node instanceof List<?> list) {
            list.forEach(item -> walk(item, uris));
        }
    }

    /** {@code to: "log:x"} or {@code to: {uri: ..., parameters: {...}}}; deadLetterChannel uses deadLetterUri. */
    private static String uriOf(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Map<?, ?> map) {
            Object uri = map.containsKey("uri") ? map.get("uri") : map.get("deadLetterUri");
            if (uri == null) {
                return null;
            }
            return withParameters(String.valueOf(uri), map.get("parameters"));
        }
        return null;
    }

    private static String withParameters(String uri, Object parameters) {
        if (!(parameters instanceof Map<?, ?> params) || params.isEmpty()) {
            return uri;
        }
        StringJoiner query = new StringJoiner("&");
        params.forEach((k, v) -> query.add(k + "=" + v));
        return uri + (uri.contains("?") ? "&" : "?") + query;
    }
}
