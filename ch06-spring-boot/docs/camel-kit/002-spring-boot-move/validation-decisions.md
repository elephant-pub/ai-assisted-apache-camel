# Decisions on the validation report of 002 (integration architect, 2026-11-13)

1. **Scenario 9 (fail):** run by the developer against the jar on the `dev` profile, together with scenario 10. PM-69009
   (quantity 21, country DE) waited with the stub stopped, still waited after a restart of the application, and was
   rejected once the stub was back, with both reasons in check order. Mailpit received one email, from
   `order-integration@trailhead.example` to `order-desk@trailhead.example`, subject `Peakmart order PM-69009 rejected`,
   the two reasons as the body. Log lines in `evidence/scenario-9-10.log`. Passed.
2. **Scenario 8 (warn):** run with the stub in `slow` mode (3 s). PM-68008 kept waiting; each poll took about 4 s more
   than the 5 s interval, two lines at the 2 s timeout. Without the timeout the 3 s answers would have recorded it.
   `evidence/scenario-8.log`. Passed.
3. **Missing-variable test (fail):** open; it moves to pipeline 003, which adds a check at startup that names every
   missing variable.
4. **`camel.main.run-controller` (fail):** a false positive. The option is in Camel Spring Boot 4.22.2's
   `spring-configuration-metadata.json` ("Whether to use the main run controller to ensure the Spring-Boot application
   keeps running"), and the application stays up with it.
5. **Kafka without TLS (fail):** accepted until Peakmart names its broker settings (OQ-038).
6. **Product Catalog over plain HTTP (fail):** accepted; the catalog's contract has no TLS or authentication, and the
   question goes with OQ-039 (where the catalog runs).
7. **Development credentials in the jar (fail):** moves to pipeline 003: `application-dev.properties` leaves the jar
   and is read from `config/` in development.

Pipeline 002 is accepted with items 3 and 7 carried into pipeline 003.
