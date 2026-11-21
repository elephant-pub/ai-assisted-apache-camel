# Decisions on the validation report of 003 (integration architect, 2026-11-13)

1. **Bundled contract not equivalent (warn, and the most important finding).** The developer's first bundle, with
   openapi-generator's `openapi-yaml` generator, normalized the OpenAPI 3.1 schemas: `additionalProperties: false`
   became `{}`, property types were dropped, `Rejection_Reason` appeared twice. Replaced: `ContractBundle` (swagger-parser
   2.1.48, `Yaml31`) bundles the contract at `prepare-package`, and `ContractBundleTest` fails the build when the bundled
   request schema differs from `docs/messages/partner-order.schema.json` (only the order of `required` lists may
   differ). The start logs no ERROR line; TG-1777 and its resend were answered `accepted`, and one pick list was
   delivered.
2. **Development passwords and the dev partner key in git (fail).** Accepted. They belong to throwaway local services
   (`camel infra` PostgreSQL and SFTP) and to a partner that exists only in `config/dev/`; committing them lets the
   project run from a clone. Nothing under `config/` is packaged, and without a profile every secret comes from the
   environment, checked at startup.
3. **Kafka without TLS, Product Catalog over plain HTTP (fail).** Re-accepted for 003 as in 002 (OQ-038, OQ-039).
4. **The design spec isn't amended** for the bundle and the kill test: this file and `developer-checks.md` are the
   record; the next amendment of the design takes them in.
5. **Warnings for later chapters:** a non-database failure in `direct:order-check` may answer a partner 500 instead of
   503, the parallel catalog lookups have no overall timeout, and the partner endpoint has no request-size limit at the
   server: chapter 8 (hardening). Outbox rows that always fail have no alert: UC-006. Whether
   `server.ssl.enabled-protocols` applies with an SSL bundle: chapter 9 (deployment).

Pipeline 003 is accepted.
