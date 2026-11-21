# Development Secret Store (stand-in)

This directory stands in for the Secret Store in development only (design 003, C-1). It holds one file per API Key,
named by the Partner's `apiKeyName` in the Partner Configuration; the file holds the key on one line.

- The key here (`partner-api-key-summit-outfitters`) is a randomly generated development key that no real Partner uses.
- **Never put a real API Key in this directory or anywhere in the repository.** Real keys live in the real Secret Store,
  which production mounts as a directory of the same shape (`SECRET_STORE_DIR`).
- This directory is under `config/`, outside the jar; it is never packaged.
