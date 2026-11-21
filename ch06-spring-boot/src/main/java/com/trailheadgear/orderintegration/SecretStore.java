package com.trailheadgear.orderintegration;

import java.util.Optional;

/**
 * The Secret Store that holds the Partners' API Keys (UC-007 BR-003). This interface is the seam a real Secret Store
 * client replaces (design 003, C-1); the integration only reads a key by the name the Partner Configuration gives.
 */
public interface SecretStore {

    /**
     * The API Key stored under {@code apiKeyName}, or empty when there is none, it is empty, or the name is not a safe
     * name. The key is never logged by an implementation.
     */
    Optional<String> read(String apiKeyName);
}
