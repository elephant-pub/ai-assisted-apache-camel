package com.trailheadgear.integration;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "trailhead.routes", havingValue = "java", matchIfMissing = true)
public class OrderRoutes extends RouteBuilder {

    @Override
    public void configure() {
        // 1. A local file stands in for the Peakmart Kafka topic until Chapter 5.
        from("{{trailhead.peakmart.inbox}}").routeId("peakmart-capture")
            .convertBodyTo(String.class)
            .setProperty("capturedEvent", body())
            .doTry()
                .unmarshal().json(PeakmartOrderEvent.class)
                .setHeader("eventType", simple("${body.eventType}"))
                .setHeader("eventId", simple("${body.eventId}"))
                .validate(header("eventType").isNotNull())
                .filter(header("eventType").isEqualTo("order.placed"))
                    .bean(OrderMappers.class, "fromPeakmart")
                .endDoTry()
            .doCatch(Exception.class)
                .setBody(exchangeProperty("capturedEvent"))
                .setHeader(Exchange.FILE_NAME, simple("SET-ASIDE-${header.CamelFileNameOnly}"))
                .log(LoggingLevel.WARN, "Set aside captured event ${header.CamelFileNameOnly}")
                .to("{{trailhead.orders.set-aside}}")
                .stop()
            .end()
            .choice()
                .when(header("eventType").isNotEqualTo("order.placed"))
                    .log("Skipped Peakmart event ${header.eventId} of type ${header.eventType}")
                    .stop()
            .end()
            .to("direct:intake");

        // 2. Orders posted by partners over HTTP
        from("platform-http:/api/orders?httpMethodRestrict=POST").routeId("order-api")
            .unmarshal().json(PartnerOrder.class)
            .bean(OrderMappers.class, "fromPartner")
            .to("direct:intake")
            .choice()
                .when(header("decision").isEqualTo("rejected"))
                    .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(422))
                .otherwise()
                    .bean(OrderAnswers.class, "accepted")
                    .marshal().json()
            .end()
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"));

        // 3. One intake for both channels: check and choose an outcome.
        from("direct:intake").routeId("order-intake")
            .setHeader("orderNumber", simple("${body.orderNumber}"))
            .setHeader("problems", method(OrderChecks.class, "problems"))
            .choice()
                .when(simple("${header.problems.size} > 0"))
                    .setHeader("decision", constant("rejected"))
                    .setHeader(Exchange.FILE_NAME, simple("REJECTED-${header.orderNumber}.json"))
                    .log(LoggingLevel.WARN, "Rejected ${header.orderNumber}: ${header.problems}")
                    .bean(OrderAnswers.class, "rejected")
                    .marshal().json()
                    .to("{{trailhead.orders.rejected}}")
                .otherwise()
                    .setHeader("decision", constant("accepted"))
                    .log("Accepted ${header.orderNumber}")
                    .to("direct:pick-list")
            .end();

        // 4. A local folder stands in for the warehouse SFTP server until Chapter 6.
        from("direct:pick-list").routeId("warehouse-pick-list")
            .setHeader(Exchange.FILE_NAME, simple("PL-${header.orderNumber}.csv"))
            .bean(PickListRows.class, "fromOrder")
            .marshal().csv()
            .to("{{trailhead.warehouse.outbox}}");
    }
}
