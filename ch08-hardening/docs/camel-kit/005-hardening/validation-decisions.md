# Decisions on the validation report of 005 (integration architect and developer, 2026-11-14)

1. **S-1 to S-4, carried over from 003 (fail).** Re-accepted for 005 with the reasons of 003 decisions 2 and 3 and
   004 decision 1. This pipeline changed nothing under `config/`, and nothing there is packaged.

2. **Q-1, and a worse case behind it (fail): H1 doesn't contain the `Error` that started this pipeline.** I checked
   Q-1 against the F8 fault run before deciding. In that run (the pin removed: every `json-validator:` step throws
   `NoSuchMethodError`), the `Error` is not turned into an `UnexpectedErrorException` at all:
   - **Partner path:** it reaches Spring's servlet as `Async processing failed: java.lang.NoSuchMethodError`. Spring
     logs it with its full message and stack trace. There is no `Unexpected error` line and no contract answer.
   - **Peakmart path (TC-002):** the exchange stays in flight at `peakmart-event-read` and nothing is logged.
     Shutdown waits 45 s for it. That is run B of the design lab, unchanged.

   The cause is in Camel 4.22.2's `AsyncProcessorConverterHelper$ProcessorToAsyncProcessorBridge`, which every `to:`
   of a `ProcessorEndpoint` (`json-validator` is one) goes through. It calls `callback.done(true)` in a `finally`
   block, then rethrows the `Error`. `ErrorContainment` sees a step that has already completed, with no exception on
   the exchange, and passes the `Error` on as "thrown by what ran on after this step". The tests can't see this: the
   fault hook throws *before* a node, in the interceptor chain, never from inside a component's producer.

   So the design's central claim, that an `Error` never stops a flow silently, doesn't hold for the `Error` that
   motivated it. **Fixed in this pipeline, before it is accepted** (session 07):
   - H1 contains an `Error` thrown by the wrapped step's own call even when Camel's adapter has already completed the
     callback. The "not this step's" case must stay correct for what really runs after a step.
   - A test in which the `Error` comes from inside a real component's producer reached through `to:` (a
     `json-validator` step, or a `ProcessorEndpoint` of the test's own), not from the fault hook. Its red run against
     today's code is recorded first. A unit test alone is not enough: one case runs against the packaged jar.
   - Q-1: `catch-partner-order-unreadable` no longer takes an `UnexpectedErrorException`; the H2 clause answers 503. A
     T7 case at `to-validate-partner-order`.
   - Q-2: the same for `try-read-as-json` (intake) and `catch-product-invalid` (check), so the reason a person reads
     names the unexpected error.
   - Afterwards I run F8 again: the partner gets the 503 contract answer, the Peakmart event is set aside as "cannot be
     taken in: unexpected error", and the log has the `Unexpected error` line and no stack trace with the message.

3. **Q-4, F9 (warn, fixed in session 07).** The test of "the intake waits for the database" must freeze it for longer
   than the intake's retries. A case that freezes the database for longer than the retries, with its red run against F9
   recorded.

4. **Q-3, Q-5, Q-6, Q-7, Q-8, Q-10 (warn): to the next pipeline that touches the failure tests**, in this order:
   - Q-8: failure ITs report the failed assertion. Cheap, and every later finding depends on reading failures quickly.
   - Q-6 and Q-3: measure the request's own duration and the Peakmart check, not only the partner answer, so the time
     limits behind the 9 s answer are tested.
   - Q-5: a counting catalog for the 20 calls a second.
   - Q-10, then Q-7.

5. **Q-9 (warn).** The completion-time check of H1 is accepted. Session 07 amends design §3.2 to say what H1 really
   does on Camel 4.22.2, including decision 2's case. The residual stack-trace paths stay a risk; F8's run after the
   fix checks the most likely one.

6. **Build time (developer check 3).** `./mvnw verify` takes 18 minutes; the 40 s Kafka session timeout per KillIT
   restart is most of the growth. To chapter 9, where CI runs the build.

Pipeline 005 is accepted once session 07's changes pass `./mvnw verify` and the F8 and F9 runs above.

## Acceptance after session 07 (2026-11-14)

- `./mvnw -B clean verify` on a clean copy, nothing running beforehand: exit 0 in 932 s (15:32; 1,075 s before
  session 07). `clean verify -DskipITs`: exit 0 in 16 s.
- **F8 again** (pin removed, full `verify`, 1,101 s; 2,107 s before, when stuck partner exchanges held the shutdown).
  In all 14 integration logs of the run:
  - every `json-validator` `Error` has its `Unexpected error routeId=… nodeId=… error=java.lang.NoSuchMethodError`
    line;
  - the partner is answered as temporarily unavailable (`Partner order temporarily unavailable after an unexpected
    error`, the 503 contract answer);
  - every Peakmart event is set aside as `cannot be taken in: unexpected error: NoSuchMethodError`, after the read was
    redelivered twice;
  - no stack trace, no `Async processing failed`, and no F8 message (`SchemaRegistry.getSchema`).
- **F9 again**: caught by `DatabaseFrozenIT` (1,052 s).

Pipeline 005 is accepted.
