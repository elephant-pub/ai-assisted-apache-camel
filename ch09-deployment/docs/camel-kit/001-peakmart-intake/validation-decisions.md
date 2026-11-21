# Decisions on the first validation report (integration architect, 2026-11-13)

1. **Kill test (fail):** replaced. The intake route has a test-only delay before the offset commit
   (`peakmart.test.commit-delay-ms`, 0 by default, synchronous); `kill-test.sh` kills in that gap and checks its own
   numbers. Results in `prototype/kill-test-results.md`.
2. **TLS to Kafka and PostgreSQL (security rule 2, fail):** accepted for the prototype only. It runs against local
   `camel infra` services that have no TLS. Encrypted connections to the Order Database are a requirement (C-024) for
   the Spring Boot project and its deployment, and Peakmart's broker settings are still open (OQ-038); the prototype is
   never deployed.
3. **Stub authentication (security rule 5, fail):** the stub now binds to 127.0.0.1 only. No authentication: it is a
   test double, and the Product Catalog it stands in for takes none either: its contract has `security: []`
   (`docs/api/external/product-catalog.openapi.yaml`).
4. **Warnings:** accepted; none blocks a local prototype.

## Second validation (2026-11-13)

5. **TLS:** stays failed and accepted, as decision 2.
6. **Kill test warnings:** fixed in the final script (start check, output kept); one run with it passes.
7. **Test hook not in the design:** accepted for the prototype; the Spring Boot project replaces it with a test that
   doesn't need a hook in the route (chapter 6 decides how).
8. **Execution report open points:** ACR F2 (orders that pass the checks but don't fit the database) is a business
   question and goes to the spec as OQ-075. The skip log stays at DEBUG with the Kafka position, as §3 says; §4 is
   corrected by the next amendment.

The prototype is accepted.

## Developer review after acceptance (2026-11-13)

9. **Catalog endpoint:** `uri: "{{catalog.base-url}}"` hid the `http` component from every static check (the chapter 3
   catalog check could not read it) and from a reader. It is now `http://{{catalog.host}}` with
   `catalog.host=localhost:8091`.
10. **Order Desk Notification:** each reason line read `- [Order Line 1] Order Line 1: the SKU ...`, because the reason
    texts already name their Order Line. A line is now `- <text>`; design §3 said otherwise and is corrected by the
    next amendment. Checked with PM-60003 (two reasons, in check order).
