package com.trailheadgear.orderintegration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Ordered;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jsonvalidator.DefaultJsonUriSchemaLoader;
import org.apache.camel.component.jsonvalidator.JsonUriSchemaLoader;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.support.AsyncProcessorConverterHelper;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.ProcessorEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.trailheadgear.orderintegration.fault.FaultHook;

/**
 * T3 (design 005 §3.2 H1, §3.9, decision 1, A-5, P-8): a step's {@code Error} reaches the route's
 * {@code onException(java.lang.Exception)} as an {@link UnexpectedErrorException}; a {@code VirtualMachineError} calls
 * the halt argument once with 70 and reaches no handler. A plain {@code CamelContext}, no Docker; the halt is recorded,
 * never real. Log lines are captured on the logger of {@link ErrorContainment} and checked for class names only.
 */
class ErrorContainmentTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String ORDER_NUMBER = "PM-90417";
    private static final String CORRELATION_ID = "c0ffee00-0000-4000-8000-000000000417";
    /** Error messages quote data; they must never reach a log line. */
    private static final String ERROR_MESSAGE = "recipient Jane Doe <jane.doe@example.com>";

    private final List<Integer> halts = new CopyOnWriteArrayList<>();
    private final AtomicReference<Supplier<Throwable>> stepThrows = new AtomicReference<>();
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private Logger logger;
    private CamelContext context;

    @BeforeEach
    void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(ErrorContainment.class);
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void stop() {
        logger.detachAppender(log);
        log.stop();
        if (context != null) {
            context.stop();
        }
    }

    // ---- contained: any Error that is not a VirtualMachineError ----

    @Test
    void noSuchMethodErrorReachesOnExceptionAsUnexpectedError() throws Exception {
        assertContained("direct:step", "contained-route", "throwing-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 1);
    }

    @Test
    void assertionErrorReachesOnExceptionAsUnexpectedError() throws Exception {
        assertContained("direct:step", "contained-route", "throwing-step",
            () -> new AssertionError(ERROR_MESSAGE), AssertionError.class, 1);
    }

    @Test
    void noSuchMethodErrorInParallelSplitReachesOnException() throws Exception {
        assertContained("direct:split", "split-route", "split-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 2);
    }

    @Test
    void assertionErrorInParallelSplitReachesOnException() throws Exception {
        assertContained("direct:split", "split-route", "split-step",
            () -> new AssertionError(ERROR_MESSAGE), AssertionError.class, 2);
    }

    // ---- fatal: a VirtualMachineError stops the process ----

    @Test
    void stackOverflowErrorHaltsWith70() throws Exception {
        assertFatal("throwing-step", () -> new StackOverflowError(), StackOverflowError.class);
    }

    @Test
    void outOfMemoryErrorHaltsWith70() throws Exception {
        assertFatal("throwing-step", () -> new OutOfMemoryError("Java heap space " + ERROR_MESSAGE),
            OutOfMemoryError.class);
    }

    // ---- P-8: the fault hook wraps inside errorContainment ----

    @Test
    void errorContainmentIsOutermostAndFaultHookInnermost() {
        assertThat(new ErrorContainment(halts::add).getOrder()).isEqualTo(Ordered.HIGHEST);
        assertThat(new FaultHook.Strategy("").getOrder()).isEqualTo(Ordered.LOWEST);
    }

    @Test
    void faultHookErrorReachesOnExceptionAsUnexpectedError() throws Exception {
        // registered first, so only the Ordered sort can put errorContainment outside it
        start(new FaultHook.Strategy("quiet-step:error:1"), new ErrorContainment(halts::add));
        MockEndpoint handled = mock("mock:handled");
        handled.expectedMessageCount(1);
        MockEndpoint after = mock("mock:after");
        after.expectedMessageCount(0);

        Exchange result = send("direct:step").get(WAIT.toSeconds(), TimeUnit.SECONDS);

        handled.assertIsSatisfied(WAIT.toMillis());
        assertUnexpectedError(handled.getReceivedExchanges().get(0), NoSuchMethodError.class);
        assertUnexpectedError(result, NoSuchMethodError.class);
        after.assertIsSatisfied();
        assertNothingInFlight();
        assertThat(halts).isEmpty();
        assertThat(errorLines()).containsExactly(
            unexpectedLine("contained-route", "quiet-step", NoSuchMethodError.class));
    }

    @Test
    void faultHookOutOfMemoryErrorHaltsWith70() throws Exception {
        start(new FaultHook.Strategy("quiet-step:oom:1"), new ErrorContainment(halts::add));
        MockEndpoint handled = mock("mock:handled");

        Future<Exchange> result = send("direct:step");

        assertHaltedOnce(result, handled);
        assertThat(errorLines()).containsExactly(fatalLine("contained-route", "quiet-step", OutOfMemoryError.class));
    }

    // ---- review follow-up: bean steps, producers, application exceptions ----

    @Test
    void noSuchMethodErrorInBeanStepReachesOnExceptionAsUnexpectedError() throws Exception {
        assertContained("direct:bean", "bean-route", "bean-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 1);
    }

    @Test
    void outOfMemoryErrorInBeanStepHaltsWith70() throws Exception {
        assertFatal("direct:bean", "bean-route", "bean-step",
            () -> new OutOfMemoryError("Java heap space " + ERROR_MESSAGE), OutOfMemoryError.class);
    }

    @Test
    void noSuchMethodErrorInProducerStepReachesOnExceptionAsUnexpectedError() throws Exception {
        assertContained("direct:producer", "producer-route", "producer-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 1);
    }

    @Test
    void outOfMemoryErrorInProducerStepHaltsWith70() throws Exception {
        assertFatal("direct:producer", "producer-route", "producer-step",
            () -> new OutOfMemoryError("Java heap space " + ERROR_MESSAGE), OutOfMemoryError.class);
    }

    // ---- decision 2 of the 005 validation: an Error from inside a component's producer, reached through to: ----
    // Camel 4.22.2: SendProcessor -> AsyncProcessorConverterHelper$ProducerToAsyncProducerBridge, which calls
    // callback.done(true) in a finally and then rethrows the Error; SendProcessor catches only Exception.

    @Test
    void noSuchMethodErrorInProcessorEndpointProducerReachesOnExceptionAsUnexpectedError() throws Exception {
        assertContained("direct:endpoint", "endpoint-route", "endpoint-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 1);
    }

    @Test
    void noSuchMethodErrorInJsonValidatorProducerReachesOnExceptionAsUnexpectedError() throws Exception {
        // F8's throw site: JsonValidatorEndpoint.onExchange -> uriSchemaLoader.createSchema
        assertContained("direct:json-validator", "json-validator-route", "json-validator-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 1);
    }

    @Test
    void noSuchMethodErrorInParallelSplitProcessorEndpointProducerReachesOnException() throws Exception {
        assertContained("direct:split-endpoint", "split-endpoint-route", "split-endpoint-step",
            () -> new NoSuchMethodError(ERROR_MESSAGE), NoSuchMethodError.class, 2);
    }

    @Test
    void outOfMemoryErrorInProcessorEndpointProducerHaltsWith70() throws Exception {
        assertFatal("direct:endpoint", "endpoint-route", "endpoint-step",
            () -> new OutOfMemoryError("Java heap space " + ERROR_MESSAGE), OutOfMemoryError.class);
    }

    /**
     * The "not this step's" case: the step completes its callback synchronously and returns normally; what runs on
     * after it (here the caller's callback itself) throws. That Error is not the step's and is not contained by it.
     */
    @Test
    void errorThrownByWhatRunsAfterTheStepIsNotContainedByTheStep() throws Exception {
        context = new DefaultCamelContext();
        context.start();
        AsyncProcessor step = (AsyncProcessor) new ErrorContainment(halts::add).wrapProcessorInInterceptors(context,
            null, AsyncProcessorConverterHelper.convert(exchange -> exchange.getMessage().setBody("done")), null);
        Exchange exchange = new DefaultExchange(context);
        List<Boolean> calls = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> step.process(exchange, doneSync -> {
            calls.add(doneSync);
            throw new NoSuchMethodError(ERROR_MESSAGE);
        })).isExactlyInstanceOf(NoSuchMethodError.class);

        assertThat(calls).as("the callback is completed once, synchronously").containsExactly(true);
        assertThat(exchange.getException()).as("not set on the exchange as this step's").isNull();
        assertThat(exchange.getMessage().getBody()).isEqualTo("done");
        assertThat(halts).isEmpty();
        assertThat(errorLines()).as("no ERROR line from ErrorContainment").isEmpty();
    }

    @Test
    void runtimeExceptionReachesOnExceptionUnchanged() throws Exception {
        stepThrows.set(() -> new IllegalArgumentException(ERROR_MESSAGE));
        start(new ErrorContainment(halts::add));
        MockEndpoint handled = mock("mock:handled");
        handled.expectedMessageCount(1);
        MockEndpoint after = mock("mock:after");
        after.expectedMessageCount(0);

        Exchange result = send("direct:step").get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(result).isNotNull();
        handled.assertIsSatisfied(WAIT.toMillis());
        assertThat(handled.getReceivedExchanges().get(0).getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class))
            .as("an application exception is not replaced").isExactlyInstanceOf(IllegalArgumentException.class)
            .hasNoCause();
        after.assertIsSatisfied();
        assertNothingInFlight();
        assertThat(halts).isEmpty();
        assertThat(errorLines()).as("no ERROR line from ErrorContainment").isEmpty();
    }

    // ---- helpers ----

    private void assertContained(String uri, String routeId, String nodeId, Supplier<Throwable> error,
            Class<? extends Error> errorClass, int steps) throws Exception {
        stepThrows.set(error);
        start(new ErrorContainment(halts::add));
        MockEndpoint handled = mock("mock:handled");
        handled.expectedMessageCount(steps);
        MockEndpoint after = mock("mock:after");
        after.expectedMessageCount(0);

        Exchange result = send(uri).get(WAIT.toSeconds(), TimeUnit.SECONDS);

        assertThat(result).as("the exchange completes within 5 s").isNotNull();
        handled.assertIsSatisfied(WAIT.toMillis());
        for (Exchange exchange : handled.getReceivedExchanges()) {
            assertUnexpectedError(exchange, errorClass);
        }
        after.assertIsSatisfied();
        assertNothingInFlight();
        assertThat(halts).as("a contained Error does not halt").isEmpty();
        String line = unexpectedLine(routeId, nodeId, errorClass);
        assertThat(errorLines()).as("one ERROR line per failed step").hasSize(steps).allMatch(line::equals);
        assertNoMessageNorStackTrace();
    }

    private void assertFatal(String nodeId, Supplier<Throwable> error, Class<? extends Error> errorClass)
            throws Exception {
        assertFatal("direct:step", "contained-route", nodeId, error, errorClass);
    }

    private void assertFatal(String uri, String routeId, String nodeId, Supplier<Throwable> error,
            Class<? extends Error> errorClass) throws Exception {
        stepThrows.set(error);
        start(new ErrorContainment(halts::add));
        MockEndpoint handled = mock("mock:handled");

        Future<Exchange> result = send(uri);

        assertHaltedOnce(result, handled);
        assertThat(errorLines()).containsExactly(fatalLine(routeId, nodeId, errorClass));
        assertNoMessageNorStackTrace();
    }

    private void assertHaltedOnce(Future<Exchange> result, MockEndpoint handled) {
        await().atMost(WAIT).until(() -> !halts.isEmpty());
        assertThat(halts).as("halt called exactly once with 70 (EX_SOFTWARE, A-5)").containsExactly(70);
        assertThat(result.isDone()).as("the callback is not completed").isFalse();
        assertThat(handled.getReceivedCounter()).as("no error handler runs").isZero();
        assertThat(mock("mock:after").getReceivedCounter()).isZero();
    }

    private static void assertUnexpectedError(Exchange exchange, Class<? extends Error> errorClass) {
        Throwable caught = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
        assertThat(caught).isInstanceOf(UnexpectedErrorException.class).hasCauseExactlyInstanceOf(errorClass);
        assertThat(caught.getMessage()).isEqualTo("Unexpected error " + errorClass.getName());
    }

    private void assertNothingInFlight() {
        await().atMost(WAIT).until(() -> context.getInflightRepository().size() == 0);
    }

    private void assertNoMessageNorStackTrace() {
        assertThat(log.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("jane.doe@example.com").doesNotContain("Jane Doe");
            assertThat(event.getThrowableProxy()).as("no stack trace: it would carry the Error message").isNull();
        });
    }

    private List<String> errorLines() {
        return log.list.stream().filter(event -> event.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String unexpectedLine(String routeId, String nodeId, Class<? extends Error> errorClass) {
        return "Unexpected error routeId=" + routeId + " nodeId=" + nodeId + " error=" + errorClass.getName()
            + " orderNumber=" + ORDER_NUMBER + " correlationId=" + CORRELATION_ID;
    }

    private static String fatalLine(String routeId, String nodeId, Class<? extends Error> errorClass) {
        return "Fatal error routeId=" + routeId + " nodeId=" + nodeId + " error=" + errorClass.getName()
            + " — the process stops";
    }

    private Future<Exchange> send(String uri) {
        ProducerTemplate template = context.createProducerTemplate();
        return template.asyncSend(uri, exchange -> {
            exchange.setProperty("order-number", ORDER_NUMBER);
            exchange.setProperty("correlation-id", CORRELATION_ID);
            exchange.getMessage().setBody(List.of("line-1", "line-2"));
        });
    }

    private MockEndpoint mock(String uri) {
        return context.getEndpoint(uri, MockEndpoint.class);
    }

    /**
     * Starts the routes with the given strategies. Without H1 (red), the strategy fails when the routes are built, so
     * the start fails and the test fails fast.
     */
    private void start(InterceptStrategy... strategies) throws Exception {
        context = new DefaultCamelContext();
        context.getShutdownStrategy().setTimeout(1);
        context.getShutdownStrategy().setShutdownNowOnTimeout(true);
        for (InterceptStrategy strategy : strategies) {
            context.getCamelContextExtension().addInterceptStrategy(strategy);
        }
        context.getRegistry().bind("stepBean", new StepBean(this::throwStepError));
        // a component's own producer: ProcessorEndpoint's producer (DefaultProducer) calls this processor
        context.addEndpoint("step-endpoint:producer",
            new ProcessorEndpoint("step-endpoint:producer", context, exchange -> throwStepError()));
        // the json-validator's schema loader, where F8's NoSuchMethodError was thrown
        context.getRegistry().bind("stepSchemaLoader", (JsonUriSchemaLoader) (camelContext, uri) -> {
            throwStepError();
            return new DefaultJsonUriSchemaLoader().createSchema(camelContext, uri);
        });
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                onException(Exception.class).handled(true).to("mock:handled");

                from("direct:step").routeId("contained-route")
                    .process(exchange -> exchange.getMessage().setHeader("checked", true)).id("quiet-step")
                    .process(exchange -> throwStepError()).id("throwing-step")
                    .to("mock:after").id("to-after");

                from("direct:split").routeId("split-route")
                    .split(body()).parallelProcessing()
                        .process(exchange -> throwStepError()).id("split-step")
                        .to("mock:after").id("split-to-after")
                    .end();

                // a bean step: Camel's bean processor invokes the method
                from("direct:bean").routeId("bean-route")
                    .bean("stepBean", "run").id("bean-step")
                    .to("mock:after").id("bean-to-after");

                // a producer step: the bean component's producer invokes the method
                from("direct:producer").routeId("producer-route")
                    .to("bean:stepBean?method=run").id("producer-step")
                    .to("mock:after").id("producer-to-after");

                // decision 2: the Error comes from inside a component's producer reached through to:
                from("direct:endpoint").routeId("endpoint-route")
                    .to("step-endpoint:producer").id("endpoint-step")
                    .to("mock:after").id("endpoint-to-after");

                from("direct:json-validator").routeId("json-validator-route")
                    .to("json-validator:classpath:schemas/order.schema.json?uriSchemaLoader=#stepSchemaLoader")
                        .id("json-validator-step")
                    .to("mock:after").id("json-validator-to-after");

                from("direct:split-endpoint").routeId("split-endpoint-route")
                    .split(body()).parallelProcessing()
                        .to("step-endpoint:producer").id("split-endpoint-step")
                        .to("mock:after").id("split-endpoint-to-after")
                    .end();
            }
        });
        context.start();
    }

    private void throwStepError() {
        Supplier<Throwable> error = stepThrows.get();
        if (error == null) {
            return;
        }
        Throwable throwable = error.get();
        if (throwable instanceof Error e) {
            throw e;
        }
        if (throwable instanceof RuntimeException e) {
            throw e;
        }
        throw new IllegalStateException("test setup: neither an Error nor a RuntimeException");
    }

    /** The bean of the bean and producer steps; public so that Camel's bean binding can call it. */
    public static final class StepBean {
        private final Runnable step;

        StepBean(Runnable step) {
            this.step = step;
        }

        public void run() {
            step.run();
        }
    }
}
