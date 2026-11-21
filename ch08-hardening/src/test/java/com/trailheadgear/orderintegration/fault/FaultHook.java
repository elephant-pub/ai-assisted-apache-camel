package com.trailheadgear.orderintegration.fault;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.DelegateProcessor;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Ordered;
import org.apache.camel.Processor;
import org.apache.camel.component.jsonvalidator.DefaultJsonUriSchemaLoader;
import org.apache.camel.component.jsonvalidator.JsonUriSchemaLoader;
import org.apache.camel.component.jsonvalidator.JsonValidatorEndpoint;
import org.apache.camel.processor.SendProcessor;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.support.AsyncProcessorConverterHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fault hook (design 005 §3.9, plan P-8): test-only, profile {@code fault-injection}. It is packaged into its own jar
 * ({@code order-integration-0.1.0-fault-hook.jar}, only this package) and put on the packaged integration's
 * {@code loader.path}; the production jar does not contain it.
 *
 * <p>{@code fault.points} is a comma list of {@code <nodeId>:<error|oom|halt>:<times>}. Before the node with that id
 * runs, while the point's remaining count is above zero: {@code error} throws a {@link NoSuchMethodError} (an
 * {@code Error}, as F8 did), {@code oom} throws an {@link OutOfMemoryError}, {@code halt} calls
 * {@code Runtime.halt(137)} (the process dies like a SIGKILL). Every other processor is returned unchanged.
 * {@code producer-error} throws nothing itself: on a {@code to:} of a {@code json-validator} endpoint it makes the
 * endpoint's own producer throw a {@link NoSuchMethodError} (see {@code plantInJsonValidator}).
 */
@Configuration
@Profile("fault-injection")
public class FaultHook {

    private static final Logger LOG = LoggerFactory.getLogger(FaultHook.class);

    @Bean
    public InterceptStrategy faultInjectionInterceptStrategy(@Value("${fault.points:}") String points) {
        LOG.info("Fault hook active points={}", points);
        return new Strategy(points);
    }

    /** The kinds of fault. */
    enum Kind {
        ERROR, OOM, HALT, PRODUCER_ERROR
    }

    /** One listed point: the kind and the remaining count (atomic decrement). */
    private static final class Point {
        final Kind kind;
        final AtomicInteger remaining;

        Point(Kind kind, int times) {
            this.kind = kind;
            this.remaining = new AtomicInteger(times);
        }

        /** True when this call uses one of the remaining times. */
        boolean take() {
            while (true) {
                int current = remaining.get();
                if (current <= 0) {
                    return false;
                }
                if (remaining.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }
    }

    /** Wraps only the processors of the listed nodes; innermost ({@link Ordered#LOWEST}). */
    public static final class Strategy implements InterceptStrategy, Ordered {

        private final Map<String, Point> points = new LinkedHashMap<>();

        public Strategy(String points) {
            if (points == null || points.isBlank()) {
                return;
            }
            for (String entry : points.split(",", -1)) {
                parse(entry.trim());
            }
        }

        private void parse(String entry) {
            String[] parts = entry.split(":", -1);
            if (parts.length != 3 || parts[0].isBlank()) {
                throw new IllegalArgumentException("Malformed fault point '" + entry
                    + "', expected <nodeId>:<error|oom|halt>:<times>");
            }
            Kind kind;
            switch (parts[1].trim()) {
                case "error" -> kind = Kind.ERROR;
                case "oom" -> kind = Kind.OOM;
                case "halt" -> kind = Kind.HALT;
                case "producer-error" -> kind = Kind.PRODUCER_ERROR;
                default -> throw new IllegalArgumentException("Malformed fault point '" + entry
                    + "': unknown kind '" + parts[1] + "', expected error, oom, halt or producer-error");
            }
            int times;
            try {
                times = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Malformed fault point '" + entry + "': times is not a number", e);
            }
            if (times < 1) {
                throw new IllegalArgumentException("Malformed fault point '" + entry + "': times must be at least 1");
            }
            String nodeId = parts[0].trim();
            if (points.putIfAbsent(nodeId, new Point(kind, times)) != null) {
                throw new IllegalArgumentException("Malformed fault point '" + entry + "': node '" + nodeId
                    + "' is listed twice");
            }
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST;
        }

        @Override
        public Processor wrapProcessorInInterceptors(CamelContext context, NamedNode definition, Processor target,
                Processor nextTarget) throws Exception {
            if (definition == null || definition.getId() == null) {
                return target;
            }
            Point point = points.get(definition.getId());
            if (point == null) {
                return target;
            }
            if (point.kind == Kind.PRODUCER_ERROR) {
                plantInJsonValidator(definition.getId(), point, target);
                return target; // not wrapped: the Error is thrown inside the producer, not here
            }
            AsyncProcessor delegate = AsyncProcessorConverterHelper.convert(target);
            return new FaultingProcessor(definition.getId(), point, delegate);
        }

        /**
         * {@code producer-error} (validation of 005, decision 2): the node must be a {@code to:} of a
         * {@code json-validator} endpoint. Its {@code uriSchemaLoader} (an endpoint option of camel-json-validator
         * 4.22.2) is replaced by one that throws {@link NoSuchMethodError} while the count lasts, then loads the schema
         * as before. The loader is called from {@code JsonValidatorEndpoint.onExchange}, inside the endpoint's producer,
         * which is where F8's {@code NoSuchMethodError} was thrown ({@code DefaultJsonUriSchemaLoader.createSchema}).
         */
        private static void plantInJsonValidator(String nodeId, Point point, Processor target) {
            Processor processor = target;
            while (!(processor instanceof SendProcessor) && processor instanceof DelegateProcessor delegating) {
                processor = delegating.getProcessor();
            }
            if (!(processor instanceof SendProcessor send)
                || !(send.getDestination() instanceof JsonValidatorEndpoint endpoint)) {
                throw new IllegalArgumentException("Fault point '" + nodeId + ":producer-error' is not a to: of a "
                    + "json-validator endpoint (" + target + ")");
            }
            JsonUriSchemaLoader before = endpoint.getUriSchemaLoader() != null ? endpoint.getUriSchemaLoader()
                : new DefaultJsonUriSchemaLoader();
            endpoint.setUriSchemaLoader((camelContext, uri) -> {
                if (point.take()) {
                    LOG.warn("Fault hook plants node={} kind=producer-error in {}", nodeId, uri);
                    // the message of F8's Error, so that a test can see whether it reaches the log
                    throw new NoSuchMethodError(F8_MESSAGE);
                }
                return before.createSchema(camelContext, uri);
            });
            endpoint.clearContentCache(); // the schema is created on the next exchange, through the loader
        }
    }

    /** The message of the {@code NoSuchMethodError} of F8 (json-schema-validator 3.0.4 with camel-json-validator 4.22.2). */
    public static final String F8_MESSAGE = "'com.networknt.schema.Schema com.networknt.schema.SchemaRegistry.getSchema("
        + "com.networknt.schema.SchemaLocation, com.fasterxml.jackson.databind.JsonNode)'";

    /** Throws (or halts) synchronously before delegating, so async EIPs keep their behaviour. */
    private static final class FaultingProcessor implements AsyncProcessor {

        private final String nodeId;
        private final Point point;
        private final AsyncProcessor delegate;

        FaultingProcessor(String nodeId, Point point, AsyncProcessor delegate) {
            this.nodeId = nodeId;
            this.point = point;
            this.delegate = delegate;
        }

        @Override
        public boolean process(Exchange exchange, AsyncCallback callback) {
            fire();
            return delegate.process(exchange, callback);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Exchange> processAsync(Exchange exchange) {
            fire();
            return delegate.processAsync(exchange);
        }

        @Override
        public void process(Exchange exchange) throws Exception {
            fire();
            delegate.process(exchange);
        }

        private void fire() {
            if (!point.take()) {
                return;
            }
            LOG.warn("Fault hook fires node={} kind={}", nodeId, point.kind.name().toLowerCase(java.util.Locale.ROOT));
            switch (point.kind) {
                case ERROR -> throw new NoSuchMethodError("fault-injection");
                case OOM -> throw new OutOfMemoryError("fault-injection");
                case HALT -> Runtime.getRuntime().halt(137);
            }
        }
    }
}
