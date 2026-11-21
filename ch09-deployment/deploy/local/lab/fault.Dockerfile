# Deploy lab only: the packaged jar plus the test-only fault hook (design 005 §3.9), started as the failure ITs start it
# (PropertiesLauncher, the fault-hook jar on loader.path, profile fault-injection). Never a release image.
# docker build -f deploy/local/lab/fault.Dockerfile -t trailhead/order-integration:fault-today --build-arg BASE=... .
ARG BASE=trailhead/order-integration:today
FROM ${BASE}
COPY target/order-integration-0.1.0.jar /lab/app.jar
COPY target/order-integration-0.1.0-fault-hook.jar /lab/fault-hook.jar
ENTRYPOINT ["java", "-Dloader.path=/lab/fault-hook.jar", "-cp", "/lab/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher", "--spring.profiles.active=default,fault-injection"]
