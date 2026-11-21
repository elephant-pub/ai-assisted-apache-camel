---
name: spec-check
description: >
  Checks message schemas, example expectations, mapping paths and required target leaves, and selected OpenAPI
  and AsyncAPI structure. Use after changing docs/messages/ or docs/api/, before a review, or in CI.
---

# Spec Check

## Instructions

Keep the project root as the working directory. Resolve the full path to this skill's bundled
`scripts/SpecCheck.java` and use it as the first argument. For example, in the Chapter 4 project:

```bash
jbang ./integration-spec/skills/spec-check/scripts/SpecCheck.java docs
```

It needs JBang and JDK 25 and exits 1 when it reports a finding. The JSON Schema loader accepts file paths
that normalize under `docs/`. Swagger Parser and the AsyncAPI checker resolve contract references separately. It prints one line per finding, `CODE file: detail`, then `Spec check: N finding(s)`.

Report the findings grouped by file, with what each code means and the fix, from the table below. Don't fix
anything: this skill only reads. Name the skill that owns the file (`/message-model` for `docs/messages/`,
`/integration-contracts` for `docs/api/`).

| Code                      | Means                                                         | Fix |
|---------------------------|---------------------------------------------------------------|-----|
| `JSON_ERROR`, `YAML_ERROR` | The file doesn't parse                                       | Correct the syntax |
| `SCHEMA_DIALECT`          | The schema doesn't declare JSON Schema 2020-12                 | Add the `$schema` line |
| `SCHEMA_INVALID`          | The schema breaks the JSON Schema meta-schema                  | Correct the keyword or value |
| `SCHEMA_REF`              | A `$ref` doesn't resolve                                       | Point it at a file in `docs/messages/` |
| `EXAMPLE_NO_SCHEMA`       | An examples folder has no schema of the same name              | Rename the folder or write the schema |
| `EXAMPLE_INVALID`         | A valid example fails its schema                               | Decide which one is wrong; never change a sample from `docs/inputs/` |
| `EXAMPLE_ACCEPTED`        | An invalid example passes its schema                           | The schema misses the rule the example breaks |
| `MAPPING_HEADER`          | A mapping has no `**Target:**` link, or links a missing file   | Add the header lines |
| `MAPPING_TARGET_UNKNOWN`  | A target path isn't a field of the target schema               | Correct the path or the schema |
| `MAPPING_SOURCE_UNKNOWN`  | A source path isn't a field of the source schema               | Correct the path or the schema |
| `MAPPING_UNMAPPED`        | A required target field has no row                             | Add a row, or record an open question |
| `OPENAPI_ERROR`           | The OpenAPI parser reports a problem                           | As the message says |
| `OPENAPI_NO_OPERATION_ID` | An operation has no `operationId`                              | Add one; use cases link to it |
| `OPENAPI_NO_REJECTION`    | An operation has neither a 4xx nor a default response         | Document the response of each rejecting flow |
| `ASYNCAPI_VERSION`        | The document isn't AsyncAPI 3.x                                | Write `asyncapi: 3.0.0` |
| `ASYNCAPI_REF`            | A `$ref` doesn't resolve                                       | Correct the reference |
| `ASYNCAPI_OPERATION`      | An operation lacks `action` or a channel `$ref`                | Add them |
| `ASYNCAPI_MESSAGE`        | A message has no payload                                       | `$ref` its schema in `docs/messages/` |

The check covers structure only. Whether a mapping is right, a contract is complete or a rule belongs in the schema
is the job of people, and of `/integration-review` and `/spec-review`.
