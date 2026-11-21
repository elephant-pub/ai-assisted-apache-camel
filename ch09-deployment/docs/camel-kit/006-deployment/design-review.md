# Design review: 006-deployment

Integration architect and developer, 2026-11-16. Everything not listed is accepted as written: the failure-mode
tables, D1 to D15, the tests T1 to T12 and the after-runs A1 to A10, the CI gate of §3.15 and its build-time rule, A-1
to A-7, and the answers taken for OQ-078, OQ-060, OQ-087 to OQ-089, OQ-091, OQ-092 and OQ-095. One instance, a
StatefulSet, is right for the reason the lab gives (152 of 200 emails sent twice with two pods). The base image digest
of A-3 is the multi-architecture index digest (`docker buildx imagetools inspect`), so it holds on the amd64 runner.

1. **F-2 is fixed in this pipeline (OQ-093 changes).** The release is the first time the integration meets a Warehouse
   SFTP server whose login directory nobody has confirmed, and if it isn't `/`, every existence check misses and a
   retry delivers a second pick list: NFR-006, the guarantee this integration is built around. A runbook line can't
   hold that. The upload and the "already delivered?" check resolve the configured folders the same way, whatever the
   login directory. A failure test with an SFTP server whose login directory is not `/` delivers each pick list once
   after a retry; its red run against today's code is recorded first. OQ-093 stays open only for the Warehouse lead's
   confirmation of the folder names.
2. **F-1 is fixed in this pipeline too (OQ-094 changes).** Every SFTP call, the channel start included, has the time
   limit of `warehouse.sftp.timeout-ms`. A test with a server that accepts the login and never starts the SFTP
   subsystem: the delivery fails within the limit, is counted with its `last_failure`, and the next poll tries again.
   Red first.
3. **C-003 is amended to Java 25 (OQ-090).** As the spec's owner I amend C-003 in `docs/constitution.md`: Java 25,
   Eclipse Temurin, with this review as the reference. The design's T1 and T2 check it.
4. **Branch protection is not available on the CI repository.** The repository is a private one on a free GitHub
   account; protected branches and rulesets on private repositories need a paid plan (GitHub docs). So the gate can't
   be made required for a merge. What still holds: the release job runs only after the gate's jobs pass in the same
   run (`needs:`), so no release artifact exists without a green gate. §3.16's CI row says this, and keeps the branch
   protection and CODEOWNERS as what a team repository sets.
5. **The developer machine is not free of credentials (§3.16).** The new deny rules of D15 are in `.claude/settings.json`, added by the developer, with one more: no session may edit `.claude/`, so an agent can't change its own permissions. D15 is not a task for execute. But §3.16 is right
   that settings can't stop a script an agent writes, and the sandbox can't either: Claude Code's sandbox docs say
   `docker` is incompatible with it, and this build needs Docker. This machine holds a credential for a real cluster of
   another project in the default kubeconfig. No session has read it, and the deny rules block the agent's own tools,
   but a script could. §3.16 states this as a gap of this machine, not as a property of the design: on a machine where
   agents build with Docker, production credentials must live elsewhere, and the developer keeps them so.
6. **"Production" for this pipeline is a second local kind cluster, `trailhead-prod`.** The developer creates it and
   deploys the release artifact of a green `main` run with `deploy/README.md`, step by step, as a person would. No
   session ever gets its kubeconfig.
7. **Execute runs one wave per session.** The plan groups its tasks in waves that each end in a state that builds and
   tests, and each wave's session starts fresh from the plan and the execution report. The report says, at the end of
   each wave, what the next one needs. One long session cost most of 005's budget in re-reading its own context.
8. **A gate that turns red with no code change is expected.** The vulnerability database changes daily. The job
   summary says which finding is new since the last green run, and the fix or the expiring exception is a reviewed
   change as §3.3 says.

The design is approved with these changes.
