package com.trailheadgear.orderintegration;

import java.io.IOException;

import com.jcraft.jsch.SftpException;

/**
 * Design 006 developer check 4 (W2-F4): an SFTP server refused a {@link WarehouseSftp} step. Carries the step
 * ({@code stat}, {@code upload}, {@code rename}) and the SFTP status id of the {@link SftpException}, which stays the
 * cause. {@link FailureKinds} records it as {@code SftpException <id> <status text> (<step>)}, e.g.
 * {@code SftpException 3 permission denied (upload)}, so {@code last_failure} names what to fix, as the runbook asks.
 *
 * <p>The status text comes from a constant table of the SFTP version 3 status codes, never from the server's message
 * or the exception message (which can hold a path or a file name, NFR-011, C-021). The message holds only the step and
 * the id.
 */
public class WarehouseSftpStepException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String step;
    private final int status;

    public WarehouseSftpStepException(String step, SftpException cause) {
        super("SFTP " + step + " failed with status " + cause.id, cause);
        this.step = step;
        this.status = cause.id;
    }

    /** The step the server refused, a constant code. */
    public String step() {
        return step;
    }

    /** The SFTP status id ({@code SftpException.id}). */
    public int status() {
        return status;
    }

    /** The SFTP version 3 name of {@link #status()} in words, or {@code unknown status} (constants only). */
    public String statusText() {
        return switch (status) {
            case 0 -> "ok";
            case 1 -> "end of file";
            case 2 -> "no such file";
            case 3 -> "permission denied";
            case 4 -> "failure";
            case 5 -> "bad message";
            case 6 -> "no connection";
            case 7 -> "connection lost";
            case 8 -> "operation unsupported";
            default -> "unknown status";
        };
    }
}
