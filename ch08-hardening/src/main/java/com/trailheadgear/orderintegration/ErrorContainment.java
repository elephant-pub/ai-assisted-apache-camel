package com.trailheadgear.orderintegration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import org.apache.camel.AsyncCallback;
import org.apache.camel.AsyncProcessor;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.Ordered;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.spi.InterceptStrategy;
import org.apache.camel.spi.UnitOfWork;
import org.apache.camel.support.processor.DelegateAsyncProcessor;
import org.apache.camel.support.processor.DelegateSyncProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Design 005 H1 (§3.2, decision 1, A-5): wraps every processor; a step's {@code VirtualMachineError} logs one
 * {@code Fatal error} line and halts with 70, any other {@code Error} becomes an {@link UnexpectedErrorException} on the
 * exchange. Outermost intercept strategy (plan P-8: {@link Ordered#HIGHEST}).
 *
 * <p>A step's {@code Error} is seen in two ways: thrown synchronously by the step, or, because Camel's own adapters
 * (the bean processor, producers) catch it themselves, left on the exchange when the step completes, as the
 * {@code CamelExecutionException} Camel wraps a non-{@code Exception} in. A third way (validation of 005, decision 2):
 * Camel's adapter for a producer that is not async completes the callback synchronously and then rethrows the
 * {@code Error}; such a completion is held until the step's call has returned, so the {@code Error} is still the
 * step's. An application {@code Exception} is never replaced. The async behaviour of the wrapped EIPs is unchanged
 * (§3.2 Constraints): the step's callback is only decorated, and an asynchronous completion is passed on at once. Log lines name the class only, never a message, and carry no stack trace (NFR-011).
 */
public class ErrorContainment implements InterceptStrategy, Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorContainment.class);

    private final IntConsumer halt;

    /** @param halt stops the process ({@code Runtime.getRuntime()::halt} in production) */
    public ErrorContainment(IntConsumer halt) {
        this.halt = Objects.requireNonNull(halt, "halt");
    }

    @Override
    public Processor wrapProcessorInInterceptors(CamelContext context, NamedNode definition, Processor target,
            Processor nextTarget) throws Exception {
        String nodeId = definition == null ? null : definition.getId();
        return new ContainingProcessor(target, nodeId);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST;
    }

    /**
     * The {@code Error} a completed step left on the exchange: Camel's adapters catch a step's {@code Error} and set it
     * with {@code Exchange.setException(Throwable)}, which wraps it in a plain {@code CamelExecutionException}. Only
     * that exact wrapper with an {@code Error} as its direct cause counts; any other exception is the application's.
     */
    static Error errorLeftOn(Exchange exchange) {
        Exception exception = exchange.getException();
        if (exception != null && exception.getClass() == CamelExecutionException.class
            && exception.getCause() instanceof Error error) {
            return error;
        }
        return null;
    }

    /**
     * Delegates to {@code AsyncProcessorConverterHelper.convert(target)} (done by {@link DelegateAsyncProcessor}, which
     * also routes the sync {@code process(Exchange)} and {@code processAsync} paths through
     * {@link #process(Exchange, AsyncCallback)} and keeps the delegate's lifecycle and navigation).
     *
     * <p>A sync step (a {@code process} node: Camel 4.22.2 hands it over as a {@link DelegateSyncProcessor}; or any plain
     * {@link Processor}) is called directly instead of through that adapter: the adapter completes the callback even
     * when a {@code VirtualMachineError} passes through, so the fatal split of decision 1 would not hold (shown by T3,
     * see the red/green evidence). Calling the sync processor directly does what the adapter does for an
     * {@code Exception} (set it on the exchange, complete the callback synchronously). An async target is delegated to
     * with a decorated callback.
     */
    private final class ContainingProcessor extends DelegateAsyncProcessor {

        private final String nodeId;
        /** The sync processor behind the target, else {@code null}. */
        private final Processor syncTarget;

        ContainingProcessor(Processor target, String nodeId) {
            super(target);
            this.nodeId = nodeId;
            this.syncTarget = syncTarget(target);
        }

        @Override
        public boolean process(Exchange exchange, AsyncCallback callback) {
            Step step = new Step(exchange, callback);
            if (syncTarget != null) {
                try {
                    processSync(exchange);
                } catch (Error error) {
                    return step.failed(error, true);
                }
                return step.completed(true);
            }
            boolean sync;
            try {
                sync = processor.process(exchange, step::fromStep);
            } catch (Error error) {
                step.inCall = false;
                if (step.ended()) {
                    // the step completed asynchronously and the exchange has moved on: not ours to contain
                    throw error;
                }
                // thrown by the step's own call, also when Camel's adapter already completed (held) the callback
                return step.failed(error, true);
            }
            // outside the try: an Error of what runs on after this step is not this step's (§3.2)
            step.release();
            // a step that ended fatally is never completed, so it is not done synchronously either
            return sync && !step.fatal;
        }

        /** The adapter's handling of an {@code Exception}; an {@code Error} is thrown on to the caller. */
        private void processSync(Exchange exchange) {
            try {
                syncTarget.process(exchange);
            } catch (Exception e) {
                exchange.setException(e);
            }
        }

        /** One run of the step: completes the caller's callback at most once. */
        private final class Step {
            private final Exchange exchange;
            private final AsyncCallback callback;
            private final AtomicBoolean done = new AtomicBoolean();
            private final Thread caller = Thread.currentThread();
            private volatile boolean fatal;
            /** True while the step's own {@code process} call runs. */
            private volatile boolean inCall = true;
            /** A synchronous completion made during the call, passed on only once the call has returned. */
            private boolean held;

            Step(Exchange exchange, AsyncCallback callback) {
                this.exchange = exchange;
                this.callback = callback;
            }

            /**
             * The step's callback. Camel 4.22.2's {@code ProcessorToAsyncProcessorBridge} (every producer that is not
             * async, e.g. a {@code ProcessorEndpoint} such as {@code json-validator}) completes it with {@code true} in
             * a {@code finally} and then rethrows an {@code Error}. So a synchronous completion during the call is held
             * until the call has returned: an {@code Error} the call then throws is still the step's.
             */
            void fromStep(boolean doneSync) {
                if (doneSync && inCall && Thread.currentThread() == caller) {
                    held = true;
                    return;
                }
                completed(doneSync);
            }

            /** The call returned normally: pass on a held completion. */
            void release() {
                inCall = false;
                if (held) {
                    held = false;
                    completed(true);
                }
            }

            /** The step completed; an {@code Error} Camel left on the exchange is handled as if thrown. */
            boolean completed(boolean doneSync) {
                Error error = errorLeftOn(exchange);
                if (error != null) {
                    return failed(error, doneSync);
                }
                complete(doneSync);
                return doneSync;
            }

            /** Decision 1: halt on a {@code VirtualMachineError}, else contain. */
            boolean failed(Error error, boolean doneSync) {
                if (error instanceof VirtualMachineError) {
                    fatal = true;
                    try {
                        LOG.error("Fatal error routeId={} nodeId={} error={} — the process stops", routeId(exchange),
                            nodeId, error.getClass().getName());
                    } finally {
                        halt.accept(70); // EX_SOFTWARE (A-5)
                    }
                    // nothing in the JVM can be trusted: no callback, no error handler (decision 1)
                    return false;
                }
                exchange.setException(new UnexpectedErrorException(error));
                try {
                    LOG.error("Unexpected error routeId={} nodeId={} error={} orderNumber={} correlationId={}",
                        routeId(exchange), nodeId, error.getClass().getName(),
                        exchange.getProperty("order-number", String.class),
                        exchange.getProperty("correlation-id", String.class));
                } finally {
                    complete(doneSync);
                }
                return doneSync;
            }

            /** True once the step has completed its callback or ended fatally. */
            boolean ended() {
                return done.get() || fatal;
            }

            private void complete(boolean doneSync) {
                if (done.compareAndSet(false, true)) {
                    callback.done(doneSync);
                }
            }
        }

        private String routeId(Exchange exchange) {
            UnitOfWork unitOfWork = exchange.getUnitOfWork();
            Route route = unitOfWork == null ? null : unitOfWork.getRoute();
            return route != null ? route.getRouteId() : exchange.getFromRouteId();
        }

        @Override
        public String toString() {
            return "ErrorContainment[" + processor + "]";
        }
    }

    /**
     * The sync processor behind the target: the target itself when it is not an {@link AsyncProcessor}, or the processor
     * a {@link DelegateSyncProcessor} adapts (what the reifier of {@code process} builds), else {@code null}.
     */
    private static Processor syncTarget(Processor target) {
        if (target instanceof DelegateSyncProcessor delegateSync) {
            return delegateSync.getProcessor();
        }
        return target instanceof AsyncProcessor ? null : target;
    }
}
