package com.trailheadgear.orderintegration;

import java.io.IOException;

/**
 * Design 006 D17 (F-1, plan P-9): a {@link WarehouseSftp} step did not end within {@code warehouse.sftp.timeout-ms}.
 *
 * <p>The step is a constant code ({@code session-connect}, {@code channel-open}, {@code channel-start}, {@code stat},
 * {@code upload}, {@code rename}); {@link FailureKinds} records it as the failure's code, so {@code last_failure} reads
 * e.g. {@code WarehouseSftpTimeoutException channel-start}. The message holds only the step and the limit, never a
 * path, a file name or a server reply (NFR-011, C-021).
 */
public class WarehouseSftpTimeoutException extends IOException {

    private static final long serialVersionUID = 1L;

    private final String step;

    public WarehouseSftpTimeoutException(String step, long timeoutMs) {
        super("SFTP " + step + " took longer than " + timeoutMs + " ms");
        this.step = step;
    }

    /** The step that took too long, a constant code. */
    public String step() {
        return step;
    }
}
