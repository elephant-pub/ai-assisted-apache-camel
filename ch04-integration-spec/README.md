# Chapter 4 · The order integration specification

The Trailhead Gear specification was drafted with Opus 5.5, AIUP and the chapter's `integration-spec`
plugin over 15 recorded sessions. The production baseline is Camel 4.22.2 LTS with Spring Boot and
JDK 25. The Chapter 3 workbench uses Camel Kit 0.4.1. SpecCheck is a standalone Java tool.

| Folder | What it holds |
|---|---|
| `docs/` | The specification, including team inputs, the agent drafts reviewed by people, and the approved use cases and test cases |
| `docs/messages/` | 14 JSON Schemas, 96 valid and invalid examples, and 6 field mappings |
| `docs/api/` | 9 contracts for HTTP, Kafka, SFTP and email |
| `docs/decisions/` | The ADR template and index for the Chapter 5 handoff |
| `integration-spec/` | The Claude Code plugin that drafts and reviews integration artifacts and exposes SpecCheck |
| `src/` | SpecCheck and its tests |

## Check the spec

Use Eclipse Temurin 25 from this project directory. The Maven build runs nine tests, including a check
of the published `docs/`. The JBang command runs that check directly.

```bash
java -version
./mvnw clean verify
jbang src/main/java/com/trailheadgear/speccheck/SpecCheck.java docs
```

Both use networknt JSON Schema Validator 3.0.8 and Swagger Parser 2.1.48. The Maven Wrapper and JBang
resolve Java dependencies when they are missing from the cache.

If you have AIUP's core plugin available, point `AIUP_CORE` to its root and invoke its lint separately
from this project directory. The second command adds a status summary and suggested next step.

```bash
AIUP_CORE="$HOME/src/aiup-marketplace/aiup-core"  # Use the path to your aiup-core directory.
python3 "$AIUP_CORE/skills/spec-review/scripts/spec_lint.py" --docs docs
python3 "$AIUP_CORE/skills/spec-review/scripts/spec_lint.py" --docs docs --next
```

## Open the Kaoto example

Open this folder as the VS Code workspace. Open `peakmart-mapping.camel.yaml` with Kaoto, select the
DataMapper step and choose **Configure**. This is the finished preview from Section 4.7.1. It maps
`orderNumber`, `eventId` and `shippingAddress.streetLine`.

Keep the route, `.kaoto` metadata and `kaoto-datamapper-c0a47830.xsl` together. The metadata points
to the source and target schemas already in `docs/messages/`. To follow the walkthrough from the
start, create a separate scratch route with a different filename.

