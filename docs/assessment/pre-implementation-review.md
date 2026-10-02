# Pre-implementation engineering review (lifecycle step 13)

- **Date**: 2026-10-02
- **Reviewer**: Claude Code (assistant), acting as independent Senior Software engineer at the human
  candidate's request. It reviewed artifacts it had itself drafted, adversarially.
- **Approval**: the human candidate approved the verdict and directed that conditions H1–H5 be
  applied (2026-10-02).
- **Baseline reviewed**: commit `cb4eb16`. The documents in scope:
  - spec (approved);
  - plan revision 3;
  - ADR-0001..0005 (accepted);
  - research, data model and OpenAPI;
  - `tasks.md` (131 tasks).
- **Preceding gates**:
  - orchestration checklist 42/42;
  - `/speckit.analyze` rerun: 0 CRITICAL.

## Verdict

**GO, with conditions.** No plan or ADR change is needed. Five implementation-level gaps (H1–H5)
were closed with wording amendments to `tasks.md` in the same commit as this record.

## Review questions

| # | Question | Finding |
|---|---|---|
| 1 | Genuine orchestration or linear chain? | Genuine but modest. Explicit DAG with eligibility rules, 2 conditional branches, 1 three-way parallel group + join, 3 HUMAN gates, 1 external action. The core path is largely sequential; do not over-claim. |
| 2 | Where is parallel execution? | `TEST` ‖ `DOCS` ‖ `SECURITY` on a thread pool. These are independent: `SECURITY`/`DOCS` are pure computation, and `TEST` writes only run-tagged probe links. |
| 3 | Where is synchronization? | `RELEASE_READINESS` waits for all three. |
| 4 | Is workflow state persistent? | Yes: seven tables in file-backed H2, plus a restart test. Correctness depends on thread ownership and transaction boundaries (H1, H2). |
| 5 | Can workflows resume? | Yes, for recoverable safe-stops; only non-succeeded eligible nodes run. Startup recovery covers `RUNNING` runs with or without a running stage. |
| 6 | Is context preserved? | Stage outputs are persisted and passed downstream; replaced outputs are kept in `STAGE_INVALIDATED` events. |
| 7 | Is decision lineage visible? | Yes. Each decision records actor type and identity, plan version, and the decision it supersedes. |
| 8 | Which actions require humans? | Three gates, policy exceptions, rework, terminate, requirement change, resume (HUMAN only). Implementation evidence: HUMAN or AGENT. |
| 9 | Can approval be bypassed? | Not within the API's rules. There is no authentication, so the assistant could impersonate a HUMAN: H5. |
| 10 | Are retries bounded? | Yes, 3 attempts. Where the retry loop runs was unspecified: H1. |
| 11 | Is timeout behavior explicit? | 5 s per attempt, but cancellation does not stop a thread blocked in database work: H3. |
| 12 | Is rollback feasible where claimed? | Yes, as attempt rollback (output committed only on success), provided no surrounding transaction spans the command: H2. |
| 13 | Where is compensation used? | Probe-link deletion for `TEST`. Real but modest (the system cleaning up its own side effect); state this honestly. |
| 14 | What triggers safe-stop? | Five defined triggers, each mapped to recoverable or not (spec FR-REL-008). |
| 15 | How does replanning work? | One atomic transaction: descendants reset, approvals and evidence invalidated, plan +1. Concurrent commands are handled by H4. |
| 16 | Are requirements independently testable? | Yes. All 88 IDs are cited by tasks with test tasks; live demos depend on HUMAN steps by design. |
| 17 | Do ADRs agree with tasks? | Yes (`/speckit.analyze` 0 CRITICAL). |
| 18 | Are tasks dependency-correct? | Yes: SCN-A design approval comes before the shortener is built, and SCN-B design approval before expiration. Risk: the live SCN-A run waits across Phases 4–5, so later workflow schema changes must be additive (existing stop condition). |
| 19 | Is the brownfield reasoning credible? | Partly. The in-app impact analysis is registry/template-based: accurate but shallow. Its credibility comes from HUMAN review at the design gate plus a real migration and code change. Do not present it as code analysis. |
| 20 | Does the ambiguous scenario genuinely suspend? | Yes: `AWAITING_CLARIFICATION`, no downstream node runs, state persisted. The rules are exact but narrow (documented). |
| 21 | Can every major claim be validated? | Yes, with careful wording: parallelism via instrumented tests; gate integrity within the API's rules (not tamper-proof); live evidence only from the running system. |

## Conditions (applied to `tasks.md`)

| ID | Gap | Resolution | Tasks |
|---|---|---|---|
| H1 | Which thread writes stage rows and audit events, and where retries run, was unspecified. Risks: `seq` collisions and optimistic-lock conflicts in the parallel wave. | Workers only execute stage logic. All database/audit writes and the retry/timeout runner live on the coordinating thread holding the run lock. `AuditService` serializes `seq` per run as a safety net. | T014, T016, T023, T027, T089 |
| H2 | Transaction boundaries were unspecified. One command-wide transaction would hide `RUNNING` claims from restart recovery and blur rollback and compensation. | No surrounding transaction. Claim, completion and failure are separate short transactions; replanning is the only multi-step transaction. Tests check that the claim is visible from another connection. | T016, T027, T084 |
| H3 | A timed-out attempt may keep running and create probe links after the cleanup sweep. | Per-attempt cancellation token checked before each side effect; late results discarded; sweep before every `TEST` attempt; readiness fails if any tagged link remains; test with a slowed probe. | T026, T067, T071, T082, T089, T091 |
| H4 | Concurrent commands on a busy run would block for up to 90 s. | `tryLock()` with no wait ⇒ immediate `409 CONFLICT` "run busy" (existing contract category). | T016, T027, T035 |
| H5 | No authentication, and the assistant has shell access, so it could issue HUMAN-typed requests. | Human-operated actions rule: every HUMAN request is issued by the candidate (e.g. `! curl …`). The assistant prepares but never sends them; it sends only AGENT-typed evidence or requirements, when asked. The rule is stated in the README limitations. | top-level rule, T129 |

Also clarified: T049 uses the default profile on `./data`, not the `demo` profile.

## Other observations (no action required now)

- **Scope versus time**: 131 tasks is ambitious for 2–3 days.
  - **MVP**: Phases 1–5.
  - **First optional items to drop if time runs short**: the `COMPENSATION_FAILURE` fault type,
    per-mechanism MTTR, policy-exception expiry handling, and the measurement test. Any drop is
    recorded as an accepted limitation at convergence, never silently.
- **Live evidence lives in git-ignored `./data`** until exported (T127). Back it up before risky
  steps.
- **H2 web console** is off by default and must stay off.
