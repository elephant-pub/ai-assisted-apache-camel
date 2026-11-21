package com.trailheadgear.orderintegration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * The registered Partners by API Key, used by the partner-order-submit route to authenticate a request (UC-001 BR-001,
 * NFR-009). Filled once at startup by {@link PartnerRegistration}. The API Keys exist here only as SHA-256 hashes; a key
 * is never logged, stored or returned (UC-007 BR-003).
 */
public class PartnerRegistry {

    /** A registered Partner as the routes see it; it holds no API Key. */
    public static final class Partner {
        private final long id;
        private final String partnerId;
        private final long rangeFirst;
        private final long rangeLast;
        private final String orderContact;

        public Partner(long id, String partnerId, long rangeFirst, long rangeLast, String orderContact) {
            this.id = id;
            this.partnerId = partnerId;
            this.rangeFirst = rangeFirst;
            this.rangeLast = rangeLast;
            this.orderContact = orderContact;
        }

        /** The database id ({@code partner.id}). */
        public long getId() {
            return id;
        }

        /** The identifier the Partner names in every order ({@code partner_code}). */
        public String getPartnerId() {
            return partnerId;
        }

        public long getRangeFirst() {
            return rangeFirst;
        }

        public long getRangeLast() {
            return rangeLast;
        }

        public String getOrderContact() {
            return orderContact;
        }

        @Override
        public String toString() {
            return "Partner[" + partnerId + "]";
        }
    }

    private record Entry(byte[] keyHash, Partner partner) {
    }

    private volatile List<Entry> entries = List.of();

    /** Replaces the registry with these Partners, keyed by their API Keys. Called once at startup. */
    public void register(Map<String, Partner> partnersByKey) {
        entries = partnersByKey.entrySet().stream().map(e -> new Entry(sha256(e.getKey()), e.getValue())).toList();
    }

    /** Number of registered Partners. */
    public int size() {
        return entries.size();
    }

    /**
     * The Partner whose API Key this is, or null for a missing, blank or unknown key. The given key is hashed and
     * compared in constant time with every registered hash; the loop never stops early.
     */
    public Partner authenticate(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        byte[] hash = sha256(key);
        Partner found = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(hash, entry.keyHash()) && found == null) {
                found = entry.partner();
            }
        }
        return found;
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
