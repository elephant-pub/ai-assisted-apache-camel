package com.trailheadgear.orderintegration;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.apache.kafka.common.KafkaException;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * Design 005 H4/H5/H6 (§3.5, §3.6, §3.7): classifies a failure by its cause chain, and describes it without message
 * text (§4 Security, P-7: at most 450 characters).
 *
 * <p>Every method walks the cause chain once per call, stopping at a cycle, and is null-safe. No method reads a
 * message: a message can quote order data (NFR-011, C-021).
 */
public class FailureKinds {

    /** P-7: the longest kind, so that a reason with its prefix fits a {@code varchar(500)} column. */
    static final int MAX_KIND_LENGTH = 450;

    /** SQLState classes of an unavailable database (§3.5): connection exception, insufficient resources, operator intervention. */
    private static final List<String> UNAVAILABLE_SQL_STATE_CLASSES = List.of("08", "53", "57");

    public FailureKinds() {
    }

    /** True when the Order Database is unavailable (connection, timeout, SQLState class 08, 53, 57). */
    public boolean databaseUnavailable(Throwable throwable) {
        for (Throwable cause : chain(throwable)) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLRecoverableException
                || cause instanceof CannotGetJdbcConnectionException || cause instanceof QueryTimeoutException
                || cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause instanceof SQLException sql && unavailableSqlState(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** True when the cause chain holds an {@code org.apache.kafka.common.KafkaException}. */
    public boolean kafka(Throwable throwable) {
        return chain(throwable).stream().anyMatch(KafkaException.class::isInstance);
    }

    /** True when the failure waits for a system to come back: database unavailable or Kafka. */
    public boolean waits(Throwable throwable) {
        return databaseUnavailable(throwable) || kafka(throwable);
    }

    /** True when a recording can never succeed: a non-transient data access failure other than database unavailable, or a JSON validation failure. */
    public boolean cannotBeRecorded(Throwable throwable) {
        List<Throwable> chain = chain(throwable);
        if (chain.stream().anyMatch(JsonValidationException.class::isInstance)) {
            return true;
        }
        return chain.stream().anyMatch(NonTransientDataAccessException.class::isInstance)
            && !databaseUnavailable(throwable);
    }

    /**
     * The root cause's simple class name plus its SQLState or server reply code; for a refused Warehouse SFTP step
     * {@code SftpException <status> <status text> (<step>)}; never a message; at most 450 characters.
     */
    public String describe(Throwable throwable) {
        List<Throwable> chain = chain(throwable);
        if (chain.isEmpty()) {
            return "unknown";
        }
        for (Throwable cause : chain) {
            if (cause instanceof UnexpectedErrorException) {
                Throwable error = cause.getCause();
                return truncate("unexpected error: " + (error == null ? "unknown" : name(error)));
            }
        }
        for (Throwable cause : chain) {
            if (cause instanceof WarehouseSftpStepException refused) {
                // Developer check 4 (W2-F4): the SFTP status (id and its constant text) and the step, no server text
                return truncate("SftpException " + refused.status() + " " + refused.statusText() + " ("
                    + refused.step() + ")");
            }
        }
        Throwable root = chain.get(chain.size() - 1);
        String code = code(root);
        return truncate(code == null || code.isBlank() ? name(root) : name(root) + " " + code);
    }

    /** The cause chain from the given throwable to its root, each throwable once (cycle-safe). */
    private static List<Throwable> chain(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = throwable; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private static boolean unavailableSqlState(String sqlState) {
        return sqlState != null && sqlState.length() >= 2
            && UNAVAILABLE_SQL_STATE_CLASSES.contains(sqlState.substring(0, 2));
    }

    /** The SQLState, the SMTP reply code or the SFTP step that timed out (design 006 P-9), read from a getter. */
    private static String code(Throwable root) {
        if (root instanceof WarehouseSftpTimeoutException timeout) {
            return timeout.step();
        }
        if (root instanceof SQLException sql) {
            return sql.getSQLState();
        }
        if (root instanceof SMTPAddressFailedException smtp) {
            return String.valueOf(smtp.getReturnCode());
        }
        if (root instanceof SMTPSenderFailedException smtp) {
            return String.valueOf(smtp.getReturnCode());
        }
        if (root instanceof SMTPSendFailedException smtp) {
            return String.valueOf(smtp.getReturnCode());
        }
        return null;
    }

    /** The simple class name; the full name for an anonymous class. */
    private static String name(Throwable throwable) {
        String simple = throwable.getClass().getSimpleName();
        return simple.isEmpty() ? throwable.getClass().getName() : simple;
    }

    private static String truncate(String kind) {
        return kind.length() <= MAX_KIND_LENGTH ? kind : kind.substring(0, MAX_KIND_LENGTH);
    }
}
