package com.trailheadgear.orderintegration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dataformat.csv.CsvDataFormat;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pick-list CSV (design 003, pick-list-delivery step 3, C-25): the same csv options as the route, checked byte for
 * byte against docs/messages/examples/pick-list/pl-tg-1060.json, RFC 4180 minimal quoting and CRLF line endings.
 */
class PickListCsvTest {

    static final String HEADER = "order_no,line_no,sku,description,qty,unit_weight_g,ship_name,ship_street,ship_city,"
        + "ship_postcode,ship_country,total_weight_g";
    private static final Path ROUTE = Path.of("src/main/resources/camel/pick-list-delivery.camel.yaml");

    private CamelContext context;

    @BeforeEach
    void startContext() throws Exception {
        context = new DefaultCamelContext();
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                // The options of marshal-pick-list-csv in pick-list-delivery.camel.yaml
                CsvDataFormat csv = new CsvDataFormat();
                csv.setHeader(HEADER.split(","));
                csv.setDelimiter(',');
                from("direct:csv").marshal(csv);
            }
        });
        context.start();
    }

    @AfterEach
    void stopContext() {
        context.stop();
    }

    private byte[] marshal(List<Map<String, Object>> rows) {
        String csv = context.createProducerTemplate().requestBody("direct:csv", rows, String.class);
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void exampleByteForByte() throws Exception {
        List<Map<String, Object>> rows = new ObjectMapper().readValue(
            Path.of("docs/messages/examples/pick-list/pl-tg-1060.json").toFile(),
            new TypeReference<List<Map<String, Object>>>() { });
        // The example's values hold no comma, quote or line break, so each row is its values joined by commas
        StringBuilder expected = new StringBuilder(HEADER).append("\r\n");
        for (Map<String, Object> row : rows) {
            List<String> values = new java.util.ArrayList<>();
            for (String column : HEADER.split(",")) {
                values.add(String.valueOf(row.get(column)));
            }
            expected.append(String.join(",", values)).append("\r\n");
        }
        assertEquals(2, rows.size());
        byte[] actual = marshal(rows);
        assertArrayEquals(expected.toString().getBytes(StandardCharsets.UTF_8), actual,
            new String(actual, StandardCharsets.UTF_8));
        assertFalse(actual.length >= 3 && actual[0] == (byte) 0xEF, "no byte order mark");
    }

    @Test
    void commaAndQuoteAreQuotedMinimally() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_no", "TG-1061");
        row.put("line_no", 1);
        row.put("sku", "STAKE-AL8");
        row.put("description", "Aluminium Stakes");
        row.put("qty", 1);
        row.put("unit_weight_g", 120);
        row.put("ship_name", "Lee Martin");
        row.put("ship_street", "18 Canyon Road, Unit \"B\"");
        row.put("ship_city", "Moab");
        row.put("ship_postcode", "84532");
        row.put("ship_country", "US");
        row.put("total_weight_g", 120);
        String csv = new String(marshal(List.of(row)), StandardCharsets.UTF_8);
        assertEquals(HEADER + "\r\n"
            + "TG-1061,1,STAKE-AL8,Aluminium Stakes,1,120,Lee Martin,\"18 Canyon Road, Unit \"\"B\"\"\",Moab,84532,US,120\r\n",
            csv);
    }

    @Test
    void routeKeepsTheSameCsvOptions() throws Exception {
        String route = Files.readString(ROUTE, StandardCharsets.UTF_8);
        assertTrue(route.contains("header: \"" + HEADER + "\""), "the route's csv header changed");
        assertTrue(route.contains("delimiter: \",\""), "the route's csv delimiter changed");
        assertFalse(route.contains("recordSeparator"), "the route must keep the default CRLF record separator");
        assertFalse(route.contains("quoteMode"), "the route must keep the default minimal quoting");
    }
}
