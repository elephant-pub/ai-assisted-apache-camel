# Design review: 004-spec-tests

Integration architect and developer. Everything not listed is accepted as written, A-1 to A-20 included.

1. **Citrus from its catalog.** The design session couldn't reach the Citrus MCP server (my session settings, not the
   design's fault) and checked Citrus endpoints and actions against the local 5.0.1 cache. Planning and execution have
   the server: every Citrus endpoint, action and option the tests use is checked there, as Camel components are.
2. **Pinned images.** Every container image the tests start is pinned to an exact version (no `16-alpine`, no
   `latest`), recorded in the execution report: the tests must start the same systems next month.
3. **PostgreSQL 16 (A-18) is right**, and it shows that `dev` has run on 17 since chapter 5. Note it in the execution
   report; the dev setup changes in chapter 9, not here.

The design is approved with these changes.
