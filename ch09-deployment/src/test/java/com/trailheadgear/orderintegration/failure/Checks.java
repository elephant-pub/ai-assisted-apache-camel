package com.trailheadgear.orderintegration.failure;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import org.citrusframework.exceptions.TestCaseFailedException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

/**
 * T9 (design 006 §3.13 D13, §3.14): a failure IT reports the check that failed, not Citrus's wrapper.
 *
 * <p><b>Why.</b> Citrus 5.0.1's {@code CitrusExtension} runs a {@code @CitrusSupport} test method inside its own test
 * case: a {@link RuntimeException} leaves it unchanged, but any other throwable (every {@link AssertionError}) becomes
 * {@code CitrusRuntimeException("Test failed", e)}, and the failure JUnit reports is
 * {@code CitrusJUnitException(<that message>)}, without the cause. So a failed {@code assertEquals} reads
 * {@code Test failed} in {@code failsafe-reports/*.xml} (005 developer check 7e).
 *
 * <p><b>How.</b> {@link Reported}, registered <em>after</em> {@code @CitrusSupport} (so it runs inside Citrus's
 * interceptor), rethrows the test method's failure as a {@link CheckFailed} ({@code RuntimeException}) whose message is
 * that of the first {@link AssertionError} in the cause chain ({@link #unwrap}); Citrus then reports that message. A
 * failed {@code runner.run(...)} leaves the method as Citrus's {@link TestCaseFailedException} and goes through the same
 * interceptor, which unwraps it, so no {@code runner.run} call needs a helper of its own. {@link #check} raises an {@link AssertionError} whose message names the check, the
 * expected and the actual value and the order number. Every existing assertion stays as it is.
 */
final class Checks {

    private Checks() {
    }

    /**
     * Passes when {@code expected} equals {@code actual}; otherwise throws
     * {@code AssertionError("<name>: expected <x>, got <y> (order <n>)")}.
     */
    static void check(String name, Object expected, Object actual, String orderNumber) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual + " (order " + orderNumber + ")");
        }
    }

    /**
     * The failure to report for {@code failure}: the first {@link AssertionError} in its cause chain; when there is
     * none, the first throwable that is not Citrus's {@link TestCaseFailedException} wrapper; else {@code failure}
     * itself. Cycle-safe.
     */
    static Throwable unwrap(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable firstReal = null;
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof AssertionError) {
                return current;
            }
            if (firstReal == null && !(current instanceof TestCaseFailedException)) {
                firstReal = current;
            }
        }
        return firstReal != null ? firstReal : failure;
    }

    /**
     * {@code failure} as Citrus reports it with its message: a {@link RuntimeException} that is already the real failure
     * unchanged; otherwise a {@link CheckFailed} with the message of {@link #unwrap} and {@code failure} as cause.
     */
    static RuntimeException reportable(Throwable failure) {
        Throwable real = unwrap(failure);
        if (real == failure && failure instanceof RuntimeException runtime && !(failure instanceof TestCaseFailedException)) {
            return runtime;
        }
        String message = real.getMessage();
        if (message == null || message.isBlank()) {
            message = real.getClass().getName();
        } else if (!(real instanceof AssertionError)) {
            message = real.getClass().getSimpleName() + ": " + message;
        }
        return new CheckFailed(message, failure);
    }

    /** A failed check, as an unchecked exception so that Citrus keeps its message (see the class comment). */
    static final class CheckFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CheckFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * JUnit interceptor for the failure ITs: {@code @ExtendWith(Checks.Reported.class)} after {@code @CitrusSupport}.
     * An {@link Error} that is not an {@link AssertionError} (e.g. {@link OutOfMemoryError}) is rethrown unchanged.
     */
    static final class Reported implements InvocationInterceptor {
        @Override
        public void interceptTestMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> invocationContext, ExtensionContext extensionContext) throws Throwable {
            try {
                invocation.proceed();
            } catch (Error e) {
                if (!(unwrap(e) instanceof AssertionError)) {
                    throw e;
                }
                throw reportable(e);
            } catch (Throwable t) {
                throw reportable(t);
            }
        }
    }
}
