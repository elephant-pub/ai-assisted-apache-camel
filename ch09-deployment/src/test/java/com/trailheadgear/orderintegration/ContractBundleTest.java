package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.swagger.v3.core.util.Json31;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The bundled contract must say what the source says: the first bundler tried dropped constraints silently. */
class ContractBundleTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void theBundledRequestSchemaIsThePartnerOrderSchema() throws Exception {
        var api = ContractBundle.bundle(Path.of("docs/api/partner-orders.openapi.yaml"));
        var bundled = api.getPaths().get("/orders").getPost().getRequestBody().getContent().get("application/json")
                .getSchema();
        Object fromBundle = JSON.readValue(Json31.pretty(bundled), Object.class);
        Object fromDocs = JSON.readValue(Path.of("docs/messages/partner-order.schema.json").toFile(), Object.class);
        assertEquals(normalized(fromDocs), normalized(fromBundle));
    }

    /** Drops $schema and $id, and sorts "required" lists, whose order means nothing. */
    @SuppressWarnings("unchecked")
    private static Object normalized(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> {
                if (!k.equals("$schema") && !k.equals("$id")) {
                    copy.put(k, k.equals("required") ? sorted((List<Object>) v) : normalized(v));
                }
            });
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(ContractBundleTest::normalized).toList();
        }
        return node;
    }

    private static List<Object> sorted(List<Object> list) {
        List<Object> copy = new ArrayList<>(list);
        copy.sort((a, b) -> a.toString().compareTo(b.toString()));
        return copy;
    }
}
