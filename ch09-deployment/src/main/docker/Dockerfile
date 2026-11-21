####
# Design 006 D1 (the image) and D6 (JVM options). Checked by deploy/ci/image-check.sh (T2) and deploy/ci/scan.sh (T3).
#
#   ./mvnw -q -DskipTests package
#   docker build -f src/main/docker/Dockerfile -t trailhead/order-integration:006 .
#
# - Base: Eclipse Temurin 25 JRE, pinned by its multi-architecture index digest (amd64 in CI, arm64 in the lab).
# - The Spring Boot layers (dependencies first, application last) under /app, owned by root and not writable by anyone:
#   the process cannot rewrite its own jar or libraries. The only writable path is /tmp, an emptyDir in the pod (D8).
# - Numeric non-root user 10001, so that runAsNonRoot can verify it.
# - No secret, no setting and no config/ folder in the image: everything comes from the pod (D9).
# - Exec-form entrypoint: the JVM is PID 1 and receives SIGTERM. Heap at most 50 % of the memory limit; any
#   OutOfMemoryError ends the process (exit 3) and the kubelet restarts it.
###

# Stage 1: extract the Spring Boot layers; read-only before they are copied, so the runtime stage needs no extra layer
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c AS builder
WORKDIR /builder
COPY target/order-integration-0.1.0.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted \
 && chmod -R a-w extracted

# Stage 2: the runtime image
FROM eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c

# COPY without --chown: owned by root:root; the modes of stage 1 (no write bit) are kept
COPY --from=builder /builder/extracted/dependencies/ /app/
COPY --from=builder /builder/extracted/spring-boot-loader/ /app/
COPY --from=builder /builder/extracted/snapshot-dependencies/ /app/
COPY --from=builder /builder/extracted/application/ /app/
RUN chmod a-w /app

WORKDIR /app
USER 10001:10001

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/application.jar"]
