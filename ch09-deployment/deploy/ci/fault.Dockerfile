# Design 006 §3.15 job 4, plan P-12: the fault image. CI and the lab only: it is NEVER published, pushed or released.
# It is the release image plus the test-only fault hook (design 005 §3.9), started with the profile fault-injection,
# so that the smoke test (deploy/ci/smoke.sh, T12) can make H1 stop the process with exit code 70.
#
#   ./mvnw -q -DskipTests package
#   docker build -f deploy/ci/fault.Dockerfile --build-arg RELEASE_IMAGE=trailhead/order-integration:006 \
#     -t trailhead/order-integration:006-fault .
#
# - FROM the release image itself: the same base, user 10001, /app layers and JVM options; only the hook is added.
# - The release image's /app/application.jar is the extracted application jar (its manifest Class-Path names lib/*),
#   not the Spring Boot fat jar, so it has no PropertiesLauncher and no loader.path. The hook jar is therefore put on
#   the class path next to it, and the main class is started directly; Spring finds FaultHook (@Profile
#   "fault-injection") by component scan, as the failure ITs do through loader.path.
# - `default` is named next to `fault-injection` so that application-default.properties (every setting from the
#   environment) is still read (P-12).
# - fault.points comes from the environment variable FAULT_POINTS (Spring relaxed binding), set by smoke.sh.
ARG RELEASE_IMAGE
FROM ${RELEASE_IMAGE}

# Root-owned and read-only, as everything under /app is (D1)
USER 0:0
COPY --chmod=0444 target/order-integration-0.1.0-fault-hook.jar /fault/order-integration-0.1.0-fault-hook.jar
RUN chmod 0555 /fault
USER 10001:10001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", \
  "-cp", "/app/application.jar:/fault/order-integration-0.1.0-fault-hook.jar", \
  "com.trailheadgear.orderintegration.CamelApplication", "--spring.profiles.active=default,fault-injection"]
