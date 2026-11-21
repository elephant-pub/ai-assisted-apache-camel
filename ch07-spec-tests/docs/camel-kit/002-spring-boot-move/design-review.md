# Design review: 002-spring-boot-move

Integration architect, with the developer. Everything not listed here is accepted as written, including the export
defects E1 to E10, D4 (the attempts cap) and assumptions B-1 to B-6 and B-8 to B-12.

1. **Keep the export's parent POM.** Camel Kit's guide says not to use `spring-boot-starter-parent`, because a parent
   can fight the Camel Spring Boot BOM over versions. Here it can't: Camel Spring Boot 4.22.2 is built on Spring Boot
   4.1.1, the parent's version. The pom stays what `camel export` wrote, with the changes E10 lists. Reviewers keep this
   exception to the guide.

2. **E7 gets a test.** The export changed a regular expression without a word, and nothing failed. Add a unit test that
   loads the packaged `application.properties` the way Spring Boot does and checks that `peakmart.email-pattern`
   accepts the buyer email of `peakmart-order-placed-sample.json` and rejects the one in
   `invalid/pm-50119-buyer-email-not-an-email.json`. It runs in `./mvnw verify`.

3. **The production mail server (B-7).** Nothing in the spec says how it is reached. Keep the assumption (STARTTLS
   required, a login), and add it to `docs/open_questions.md` as an open, non-blocking question so operations answers it
   before chapter 9 deploys anything.

4. **The prototype is retired.** The Spring Boot project uses the same consumer group (B-11), so the two must never
   consume at the same time. `prototype/` stays as the record of pipeline 001; add one line at the top of
   `prototype/run.sh` that refuses to start unless `PROTOTYPE_ANYWAY=1` is set, saying why.

The design is approved with these changes.
