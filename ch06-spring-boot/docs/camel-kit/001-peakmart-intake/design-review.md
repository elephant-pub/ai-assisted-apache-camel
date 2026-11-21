# Design review: 001-peakmart-intake

Integration architect, with the developer who will run the prototype. Everything not listed here is accepted as
written, including assumptions A-1, A-2, A-3, A-5, A-7, A-9 to A-14.

1. **Stub port.** 8081 is also in use on this machine. Run the Product Catalog stub on 8091.

2. **Kafka offsets.** Don't rely on auto-commit. Its guarantee depends on when the Kafka client commits on its own,
   on a timer during poll and again when the consumer closes, and nobody reading the route can see that. Set
   `autoCommitEnable=false` and `allowManualCommit=true`, and commit the record with the `KafkaManualCommit` from the
   exchange as the last step of `peakmart-order-intake`, after the receipt insert, the set-aside insert or the skip.
   Keep `breakOnFirstError=true`. Keep the kill-and-restart test: it is how we know.

3. **Records that aren't JSON (A-6).** Set them aside under `kafka-<partition>-<offset>` instead of skipping them. We
   can't tell that such a record isn't a broken `order.placed`, and UC-002 says an order that was read is never lost.
   The Integration Team decides what it was.

4. **Set-aside reason.** Store only the schema path and keyword of the first validation error (for example
   `$.buyer.email: format`), never the validator's message. Don't wait for implementation to find out whether the
   message echoes values; assume it does.

5. **Tests.** No Citrus tests in this pipeline; they come with the test cases TC-002 and TC-003 in the next step of the
   project. Instead, `prototype/scenarios.sh` publishes the example events to Kafka and prints what the database holds
   afterwards, for scenarios 1 to 6, and the execution report records the result of scenarios 1 to 10, run by hand
   where they need the stub stopped or the integration restarted. Drop `src/test/resources` from the structure.

6. **PostgreSQL** from `camel infra` is 17.10, not 16.

The design is approved with these changes.
