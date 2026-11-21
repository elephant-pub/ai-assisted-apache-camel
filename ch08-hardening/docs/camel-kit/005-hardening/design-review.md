# Design review: 005-hardening

Integration architect and developer, 2026-11-13. Everything not listed is accepted as written, A-1 to A-4 and the
answers taken for OQ-075 (b) and OQ-079 to OQ-086 included. Not adding a circuit breaker is right, for the reason the
design gives.

1. **Not every `Error` is contained (OQ-084 changes).** H1 contains `LinkageError` and the other `Error`s a step can
   throw for a reason of its own (`NoSuchMethodError`, `NoClassDefFoundError`, `ExceptionInInitializerError`,
   `AssertionError`). A `VirtualMachineError` (`OutOfMemoryError`, `StackOverflowError`, `InternalError`) is not: after
   one, nothing in the JVM can be trusted, including the error handling that would run next. For those, H1 logs one
   ERROR line (`Fatal error … the process stops`) and stops the process with a non-zero exit code, without a graceful
   shutdown. That is safe for the reason the design itself gives: every state is durable, and the kill runs prove a
   process that dies at any step loses no order and delivers no second pick list. Restarting it is deployment's job
   (chapter 9). T7 gains a case: an `OutOfMemoryError` thrown by the fault hook at intake ends the process with a
   non-zero code within 5 s, and after a normal start the event is recorded once.
2. **Red before green.** "Every change has a test that fails without it" is checked, not claimed. The plan orders
   each test before its change, and execute runs it against the code without the change and records the failure
   (test, assertion, message) in the execution report, then again after the change. A test that never failed proves
   nothing about its change.
3. **Flyway and the socket timeout (H3).** The plan resolves the constraint the design names: Flyway runs with its own
   connection settings, without the 5 s socket timeout, and a test shows which settings each one uses.
4. **Getting a parked order out (H5).** `waiting_for = 'Recording'` is only an end state if a person knows what to do
   with it. The README gains a short section for the Integration Team: for each end state of §3.8, the query that finds
   it and the step that gets it moving again (the statement that sets `waiting_for` back to `Check`, how a set-aside
   event waits for UC-008). The statements are checked by `WaitingSelectIT` or a test next to it, not only written.
5. **Build time is measured.** The execution report gives the measured duration of `./mvnw verify` and of
   `-DskipITs`, before and after, and the time of each new IT. If verify takes more than 15 minutes, say which ITs cost
   the most; don't move them out of verify.

The design is approved with these changes.
