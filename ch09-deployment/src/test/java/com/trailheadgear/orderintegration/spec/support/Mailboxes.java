package com.trailheadgear.orderintegration.spec.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.citrusframework.actions.ReceiveMessageAction;
import org.citrusframework.actions.ReceiveTimeoutAction;
import org.citrusframework.mail.message.CitrusMailMessageHeaders;
import org.citrusframework.mail.server.MailServer;
import org.citrusframework.mail.server.MailServerBuilder;

/**
 * The Order Desk and Order Contact mailboxes for the executable spec tests (design 004 §2, §3.3, A-20): one Citrus
 * {@code mail-server} (SMTP, no STARTTLS, no login) in the test JVM. Mailboxes are told apart by recipient address.
 *
 * <p>There is no Integration Team mailbox: its address is not configured anywhere yet (UC-006 is not built), so the
 * alert checks are not-yet-testable instead of "no mail arrived".
 *
 * <p><b>Selection.</b> Every check selects on {@code citrus_mail_to} AND {@code citrus_mail_subject}, both exact
 * matches (Citrus message selector: a key-value map, the entries joined with AND, see {@code actions-receive.adoc}
 * "Message selectors"). Mail of other tests stays in the server queue and is never consumed. A subject "contains"
 * match is not used: whether a selector value may be a validation-matcher expression cannot be confirmed from the
 * Citrus 5.0.1 sources at hand (the selector implementation lives in citrus-api), so the exact subject the
 * integration sends is used instead, as the routes build it and the mail contracts state it:
 * {@link #orderDeskSubject(String)} ({@code docs/api/external/order-desk-mail.md}) and
 * {@link #rejectionEmailSubject(String)} ({@code docs/api/external/partner-mail.md}).
 *
 * <p><b>Recipient header.</b> Citrus fills {@code citrus_mail_to} with the To addresses joined by "," as
 * {@code InternetAddress.toString()} renders them ({@code MailMessageConverter.createMessageHeaders}). The integration
 * sets {@code To} to the bare address ({@code orderdesk.mailbox}, the Partner's {@code orderContact}) with one
 * recipient and no display name, so the header value is exactly the address. A mail with a display name or a second
 * recipient does not match, and the check fails rather than accepting it.
 *
 * <p><b>Binding.</b> The mail-server endpoint has no bind-address option. Its {@code smtp} option (the GreenMail
 * instance) is given a GreenMail {@link ServerSetup} with bind address 127.0.0.1, so the SMTP server listens on
 * loopback only; without it Citrus creates {@code new ServerSetup(port, null, "smtp")} (no bind address, i.e. not
 * restricted to loopback). The integration must therefore use {@code smtp.host=127.0.0.1}.
 *
 * <p>{@code SpecEnvironment} owns the lifecycle.
 */
public final class Mailboxes {

    /** The Order Desk mailbox ({@code orderdesk.mailbox} of the spec-test profile). */
    public static final String ORDER_DESK = "order-desk@trailhead.example";
    /** The Order Contact of Summit Outfitters ({@code docs/messages/examples/partner-configuration/summit-outfitters.json}). */
    public static final String PARTNER_CONTACT = "orders@summit-outfitters.example.com";

    private static final String LOOPBACK = "127.0.0.1";

    private MailServer server;
    private int port = -1;

    public Mailboxes() {
    }

    /** Subject of the Order Desk Notification: {@code Peakmart order <Order Number> rejected}. */
    public static String orderDeskSubject(String orderNumber) {
        return "Peakmart order " + Objects.requireNonNull(orderNumber, "orderNumber") + " rejected";
    }

    /** Subject of the Rejection Email to the Partner: {@code Order <Order Number> rejected}. */
    public static String rejectionEmailSubject(String orderNumber) {
        return "Order " + Objects.requireNonNull(orderNumber, "orderNumber") + " rejected";
    }

    /** Starts the SMTP server on {@code 127.0.0.1:<port>}. */
    public synchronized void start(int port) {
        if (server != null) {
            throw new IllegalStateException("Mail server already started on port " + this.port);
        }
        GreenMail smtp = new GreenMail(new ServerSetup(port, LOOPBACK, "smtp"));
        MailServer mailServer = new MailServerBuilder()
            .port(port)
            .autoAccept(true)
            .splitMultipart(true)
            .authRequired(false)
            .autoStart(false)
            .smtp(smtp)
            .build();
        mailServer.setName("mailboxes");
        mailServer.initialize();
        mailServer.start();
        this.server = mailServer;
        this.port = port;
    }

    /** The port the server listens on, or -1 before {@link #start(int)}. */
    public synchronized int port() {
        return port;
    }

    /** The running server, for receive actions built by the caller. */
    public synchronized MailServer server() {
        if (server == null) {
            throw new IllegalStateException("Mail server not started");
        }
        return server;
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } finally {
                server = null;
                port = -1;
            }
        }
    }

    /**
     * A {@code receive} of one mail to {@code recipient} with exactly {@code subject}, waiting up to
     * {@code timeoutMs}. The caller adds body validation or extraction. "Exactly one" is this receive followed by
     * {@link #expectNone(String, String, long)} for the quiet period.
     */
    public ReceiveMessageAction.Builder receiveOne(String recipient, String subject, long timeoutMs) {
        return ReceiveMessageAction.Builder.receive(server())
            .selector(selector(recipient, subject))
            .timeout(timeoutMs);
    }

    /**
     * An {@code expectTimeout}: no mail to {@code recipient} with exactly {@code subject} arrives within
     * {@code quietMs}. The subject is matched exactly, not by "contains" (see the class comment); pass the subject the
     * integration sends ({@link #orderDeskSubject(String)}, {@link #rejectionEmailSubject(String)}).
     */
    public ReceiveTimeoutAction.Builder expectNone(String recipient, String subject, long quietMs) {
        return ReceiveTimeoutAction.Builder.expectTimeout(server())
            .selector(new LinkedHashMap<>(selector(recipient, subject)))
            .timeout(quietMs);
    }

    /** A precondition check: no such mail is already waiting (a short {@code expectTimeout}). */
    public ReceiveTimeoutAction.Builder expectNoneWaiting(String recipient, String subject) {
        return expectNone(recipient, subject, 100L);
    }

    private static Map<String, String> selector(String recipient, String subject) {
        Map<String, String> selector = new LinkedHashMap<>();
        selector.put(CitrusMailMessageHeaders.MAIL_TO, requireSelectorValue(recipient, "recipient"));
        selector.put(CitrusMailMessageHeaders.MAIL_SUBJECT, requireSelectorValue(subject, "subject"));
        return selector;
    }

    /**
     * Citrus turns the map into the string {@code key = 'value' AND key = 'value'} and parses it again; a value with a
     * quote, an equals sign or " AND " could be split wrongly, so such values are refused instead of selecting
     * something else.
     */
    private static String requireSelectorValue(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.contains("'") || value.contains("=") || value.toUpperCase().contains(" AND ")) {
            throw new IllegalArgumentException("Unsupported " + name + " for a message selector: " + value);
        }
        return value;
    }
}
