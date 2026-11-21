# AI-Assisted Apache Camel — code

The code for the book **AI-Assisted Apache Camel: Delivering enterprise integrations
in the era of agents** by Dhrubo Kayal, published by Elephant Publication on Leanpub:
<https://leanpub.com/ai-assisted-apache-camel>

The book follows one integration, Trailhead Gear's order integration, from its specification to a Kubernetes
deployment. Each chapter from 2 onwards has its own project here, and each project builds on its own.

| Folder | Chapter |
|---|---|
| [`ch02-camel-basics`](ch02-camel-basics) | 2. Camel in an Afternoon |
| [`ch03-workbench`](ch03-workbench) | 3. The AI Workbench |
| [`ch04-integration-spec`](ch04-integration-spec) | 4. Integration Specs with AIUP |
| [`ch05-prototype`](ch05-prototype) | 5. Prototype with JBang |
| [`ch06-spring-boot`](ch06-spring-boot) | 6. From Prototype to Spring Boot Project |
| [`ch07-spec-tests`](ch07-spec-tests) | 7. Testing Against the Spec |
| [`ch08-hardening`](ch08-hardening) | 8. Hardening |
| [`ch09-deployment`](ch09-deployment) | 9. Deploying to Production |

Chapters 10–14 are added here as they are published.

## Running a project

You need JDK 25 (the book uses Eclipse Temurin 25) and, from Chapter 3 on, Docker or Podman for the tests that start
Kafka, PostgreSQL, SFTP and mail servers in containers. Maven comes with each project as the wrapper:

```bash
cd ch06-spring-boot
./mvnw verify               # build and run the tests
```

Each folder's README says how to run that chapter's integration and what else it needs.

## License

The code is licensed under the [Apache License 2.0](LICENSE). The book's text is not part of this repository.
