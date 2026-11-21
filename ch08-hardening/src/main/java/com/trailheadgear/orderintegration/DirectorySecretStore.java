package com.trailheadgear.orderintegration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The Secret Store stand-in (design 003, C-1): a directory with one file per API Key, named by {@code apiKeyName}. This
 * is the shape Vault Agent, the Secrets Store CSI driver and Kubernetes/Docker secrets produce, so production mounts the
 * real store at {@code secret-store.directory} without a code change.
 */
public class DirectorySecretStore implements SecretStore {

    /** A safe file name: no separator, no path escape (C-1). */
    static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");

    private final Path directory;

    public DirectorySecretStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** True when {@code apiKeyName} can name a file inside the Secret Store directory and nothing else. */
    public static boolean isSafeName(String apiKeyName) {
        return apiKeyName != null && SAFE_NAME.matcher(apiKeyName).matches() && !apiKeyName.contains("..");
    }

    @Override
    public Optional<String> read(String apiKeyName) {
        if (!isSafeName(apiKeyName)) {
            return Optional.empty();
        }
        Path file = directory.resolve(apiKeyName).normalize();
        if (!file.getParent().equals(directory) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String key = Files.readString(file, StandardCharsets.UTF_8);
            // One key on one line: the trailing line break is not part of the key
            key = key.replaceAll("[\\r\\n]+$", "");
            return key.isEmpty() ? Optional.empty() : Optional.of(key);
        } catch (IOException e) {
            // An unreadable entry counts as missing: it is reported, and the application keeps running (C-3)
            return Optional.empty();
        }
    }
}
