# Design review: 003-partner-orders-pick-lists

Integration architect, with the developer and, for item 2, the order desk lead as product owner. Everything not listed
here is accepted as written, including C-1 to C-3, C-6, C-8 to C-13, C-15 to C-24, C-26 and C-27.

1. **The spec follows the design (C-4, C-5).** A receipt unique per partner, order number and content, and a
   `deregistered_at` on the partner, are right. The entity model is part of the approved spec, so it changes with the
   code: update `docs/entity_model.md` in this pipeline (the `PARTNER` and `ORDER_RECEIPT` entities and their rules),
   and say in its change note that pipeline 003 made the change.

2. **OQ-074 is answered (C-7).** The product owner accepts reading (b): the "being checked" mark expires after 30
   seconds. Record it in `docs/open_questions.md` as Answered, with "Answered by: order desk lead, design review of
   pipeline 003".

3. **Pick list line endings (C-25).** Neither the warehouse's format nor the SFTP contract says. Keep CRLF, and add a
   non-blocking open question for the warehouse, so it is confirmed before the first real pick list.

4. **Header stripping (risk, scenario P-11).** Keep it, and in the execution report also say whether Camel 4.22.2's
   `platform-http` already filters incoming `Camel*` headers. If it does, the strip is a second guard; if it doesn't,
   the report says so plainly. Either way P-11 must send a `CamelSqlQuery` header and show it changed nothing.

5. **Dev port.** 8082 is free on this machine; use it.

The design is approved with these changes.
