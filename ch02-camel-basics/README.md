# Chapter 2: Camel in an afternoon

Trailhead Gear's local order-integration skeleton, with no AI yet: Partner Orders arrive over HTTP and captured
Peakmart JSON events arrive through a local folder standing in for Kafka. Both become one `Order`, pass through plain
Java rules, and produce either rejection reasons, a set-aside event or a warehouse pick-list CSV.

The Spring Boot project skeleton was generated with Camel CLI 4.22.2 using `camel export --runtime=spring-boot`. A
temporary YAML route declared the file, HTTP, direct, JSON, CSV and YAML capabilities so the exporter could discover
the required Camel dependencies. The teaching routes, configuration, samples and tests were then added to that
scaffold. The exact discovery input is `bootstrap/ch02-bootstrap.camel.yaml`; it is not loaded by the application.

```bash
./mvnw verify                 # 21 executions across Java routes, YAML routes and rules
./mvnw spring-boot:run        # start the app on port 8080
mkdir -p work/peakmart-inbox
cp samples/peakmart-*.json work/peakmart-inbox/
curl -X POST localhost:8080/api/orders -H 'Content-Type: application/json' \
  --data @samples/partner-order-TG-1060.json
```

Output lands in `work/warehouse`, `work/rejected` and `work/set-aside`.

The one-file JBang example is in `jbang/Hello.java`: `camel run jbang/Hello.java`.

Tested with JDK 25, Apache Camel 4.22.2 LTS and Spring Boot 4.1.1.
