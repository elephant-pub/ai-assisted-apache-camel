# Chapter 6: the order integration on Spring Boot

The chapter 5 prototype exported with `camel export` and finished by two Camel Kit pipelines: `002-spring-boot-move`
(Flyway, profiles, SMTP, a startup check) and `003-partner-orders-pick-lists` (UC-001 partner orders over REST, UC-007
partners from a reviewed configuration, UC-004 pick lists over SFTP). Spring Boot 4.1.1, Camel 4.22.2.

| Folder | What it holds |
|---|---|
| `src/main/` | Routes (YAML), SQL, Flyway migrations V1 and V2, Java beans, `application.properties` and `application-default.properties` |
| `config/` | Development settings, read from the working directory and not packaged: `application-dev.properties`, the dev partner configuration, a dev API key, the warehouse host key |
| `src/test/` | 34 unit tests, the kill-test hook, and scripts for the scenarios and the kill test |
| `docs/` | The spec, and `camel-kit/001…003/`: design specs, reviews, plans, execution and validation reports, decisions |

## Build

```bash
./mvnw verify            # 34 tests; bundles the partner contract into the jar
```

## Run it in development

Each long-running command in its own terminal:

```bash
camel infra run kafka --background
camel infra run postgres --port 15432 --background
docker run -d --name trailhead-mailpit -p 127.0.0.1:1025:1025 -p 127.0.0.1:8025:8025 axllent/mailpit:v1.21

# The warehouse SFTP server, on this machine only
mkdir -p /tmp/warehouse/picklists/processed && chmod -R 777 /tmp/warehouse
docker run -d --name trailhead-warehouse-sftp -p 127.0.0.1:2222:22 \
  -v /tmp/warehouse:/home/admin/inbound atmoz/sftp:alpine admin:admin:1001
ssh-keyscan -p 2222 -t ed25519 127.0.0.1 > config/dev/warehouse_known_hosts

(cd ../ch05-prototype/prototype/stubs/product-catalog && ./run.sh)     # Product Catalog stub, 127.0.0.1:8091
java -jar target/order-integration-0.1.0.jar --spring.profiles.active=dev
```

Then:

```bash
src/test/scripts/partner-post.sh TG-1060          # a partner order with the dev API key; the answer is printed
ls /tmp/warehouse/picklists                        # PL-TG-1060.csv, about 30 s later
open http://localhost:8025                         # rejection emails and Order Desk notifications
src/test/scripts/kill-test.sh                      # SIGKILL between the receipt and the Kafka commit
```

The sessions used `camel infra run sftp` for the warehouse. Don't: it serves the whole file system of the machine with
the password `admin`, on every network interface. The container above is bound to 127.0.0.1 and sees one folder.

Without a profile, every host, port and secret comes from the environment; the application refuses to start and
names every missing variable (`RequiredVariablesCheck`).

