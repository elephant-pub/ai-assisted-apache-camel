# Deploy lab (design 006)

Lab scaffolding for the runs behind `docs/camel-kit/006-deployment/evidence/deploy-lab-runs.md`. It is **not** the
release: the release manifests, the Dockerfile and the CI workflow are written by the plan of 006. Everything here runs
only against the kind cluster `trailhead-lab`, through `deploy/local/kube.sh`.

| File | What it does |
|---|---|
| `inspect-image.sh <image>` | what an image holds: base, user, Java, tools, writable folders |
| `scan-summary.sh image <tar> \| jar` | Trivy findings counted per severity and fixable |
| `base-probe.Dockerfile` | the requested Temurin 25 JRE base alone, to read its digest and findings |
| `secrets.sh` | lab-only certificates, keys and passwords under `target/lab/secrets/` (never committed) |
| `k8s/systems.yaml` | namespace `trailhead-lab`: PostgreSQL 16 (TLS), Kafka, SFTP, Mailpit (STARTTLS), Product Catalog stub, a client pod |
| `k8s/today.yaml` | the image of `src/main/docker/Dockerfile` deployed with what the jar needs and nothing else: no probes, no resources, no security context, default grace period |
| `lab.sh` | up / down / app / events / psql / mail / post helpers used by the runs |
