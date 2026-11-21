package com.trailheadgear.orderintegration;

/**
 * Design 005 H1 (§3.2): a step's {@link Error} other than a {@code VirtualMachineError}, turned into an exception so that
 * the route's error handling gives it its end state. The {@code Error} is the cause; the message names its class only,
 * never its message, because a message can quote data (NFR-011).
 */
public class UnexpectedErrorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnexpectedErrorException(Error error) {
        super("Unexpected error " + error.getClass().getName(), error);
    }
}
