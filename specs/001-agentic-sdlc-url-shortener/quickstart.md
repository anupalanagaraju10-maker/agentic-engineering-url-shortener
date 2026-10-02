# Quickstart & Validation Guide

**Phase**: 1 (`/speckit.plan`) | **Date**: 2026-10-01 (amended at architecture review) |
Planned, not yet runnable — commands become valid as implementation slices land. Contracts:
[contracts/openapi.yaml](./contracts/openapi.yaml). Data model: [data-model.md](./data-model.md).

## Prerequisites

- JDK 21 (`java -version`), no other services. Maven is provided by the wrapper.
- Port 8080 free. Data is stored in `./data/` (git-ignored); delete it for a clean start.

## Build, test, run

```bash
./mvnw verify                              # unit + integration + scenario tests
./mvnw test -Dgroups=measurement           # PVT-003..005 measurements (demonstration, non-blocking)
./mvnw spring-boot:run                     # start on http://localhost:8080 (fault injection OFF)
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # demo profile: fault injection ON
curl -s localhost:8080/actuator/health     # {"status":"UP"}
```

## URL shortener checks

| Check | Command | Expected |
|---|---|---|
| Create | `curl -si -X POST localhost:8080/api/links -H 'Content-Type: application/json' -d '{"url":"https://example.com"}'` | 201, 7-char `code` |
| Redirect | `curl -si localhost:8080/r/<code>` | 302, `Location: https://example.com`, `Cache-Control: no-store` |
| Analytics | `curl -s localhost:8080/api/links/<code>` | `redirectCount: 1`, `lastRedirectAt` set |
| Unknown | `curl -si localhost:8080/r/zzzzzzz` | 404 problem+json |
| Unsafe | POST with `"url":"javascript:alert(1)"` / `"http://127.0.0.1"` / `"http://localhost"` | 400, `category: VALIDATION` |
| Idempotent | POST twice with `Idempotency-Key: k1` same body; then different body | 201, 200 (same code), 409 |
| Expired (after SCN-B) | POST with `expiresAt` 2 s ahead, wait, GET `/r/<code>` | 410, count unchanged |

## Scenario demonstrations (live runs on the demo database)

Use `"actorType":"HUMAN","actorIdentity":"candidate"` for decisions; implementation evidence
may instead use `"actorType":"AGENT","actorIdentity":"claude-code"`. After each command inspect
`GET /api/workflows/{id}` (status, pending action, every node's kind/dependencies/state),
`/events`, `/decisions`. Every decision carries the `planVersion` shown by the run. At the end,
export `/events`, `/decisions` and `/report` for each run into `docs/scenarios/` as runtime
evidence.

### SCN-A — Greenfield (FR-SCN-001)

1. `POST /api/workflows` with "Create a short link for a valid HTTP/HTTPS address, redirect to
   the original address, and record redirect count and last redirect time." → `GREENFIELD` (the
   capabilities are `PLANNED` in the registry); `CLARIFICATION` and `IMPACT_ANALYSIS` are
   `SKIPPED` with `BRANCH_TAKEN`; run `AWAITING_APPROVAL` (`DESIGN_APPROVAL`).
2. `POST /approve` (`DESIGN_APPROVAL`, plan 1) → run `AWAITING_IMPLEMENTATION`.
3. The engineer implements the core URL shortener under SpecKit tasks (test-first), commits, and
   restarts the app. The run is unchanged after the restart.
4. `POST /implementation` (HUMAN or AGENT) with summary, changed files, requirement IDs
   (`FR-URL-001`…) and the commit id (required because files changed) → `IMPLEMENT` succeeds (`EXTERNAL`). `TEST`, `DOCS` and `SECURITY` run with
   overlapping intervals; `RELEASE_READINESS` starts after all three → `AWAITING_APPROVAL`
   (`RELEASE_APPROVAL`).
5. `POST /approve` (`RELEASE_APPROVAL`, `acceptedRisks`) → `COMPLETED`; `GET /report`. No probe
   links remain (`GET /api/links/<probe code>` → 404).

### SCN-B — Brownfield (FR-SCN-002)

1. `POST /api/workflows` with "Add optional expiration to existing links; expired links return
   an expired result distinct from not-found." → `BROWNFIELD`; `IMPACT_ANALYSIS` `SUCCEEDED`
   (all ten areas); `CHG-01 PASS`; `DESIGN` done; run `AWAITING_APPROVAL` (`DESIGN_APPROVAL`).
   No expiration code exists yet.
2. Review the impact analysis and design; `POST /approve` → `AWAITING_IMPLEMENTATION`.
3. Only now: implement expiration (Flyway migration adding `expires_at`, 410 behavior, registry
   entry `PLANNED → IMPLEMENTED`, tests first), commit, restart.
4. `POST /implementation` with evidence → `TEST` probes the real expiration behavior; release
   approval → `COMPLETED`.

### SCN-C — Ambiguous (FR-SCN-003)

1. `POST /api/workflows` with "Make links expire." → `AWAITING_CLARIFICATION`; findings
   `AMB-R2` (no duration/trigger) and `AMB-R4` (which links); no node after `CLARIFICATION` ran.
2. `POST /clarify` (plan 1) with e.g. "Expiration is optional per link: the client may supply an
   absolute expiration time when creating a link; after that time the link returns the expired
   result; links without an expiration never expire; existing links are unaffected." →
   `CLARIFICATION_RECEIVED`, `PLAN_REPLANNED` (1 → 2, affected/preserved lists); `UNDERSTAND`
   re-runs with no findings; brownfield branch; waits at `DESIGN_APPROVAL` (plan 2). A decision
   sent with `planVersion: 1` → 409 + `DECISION_REFUSED`.
3. Inspect the replanned `DESIGN` output: `implementationRequired` reflects the actual
   clarification against the current registry. Approve design (plan 2) as `HUMAN`; an `AGENT`
   approval is refused.
4. If `implementationRequired = true`: implement, commit, restart, then record evidence with
   changed artifacts + revision. If `false`: record evidence with an empty `changedArtifacts` and
   a `noChangeJustification`. Either way `TEST`/`SECURITY` validate the running build. Approve
   release → `COMPLETED`.

## Governance and recovery demonstrations (automated tests and optional live runs)

Fault-injection rows need the `demo` profile; without it, runs with `faults` get `400
FAULT_INJECTION_DISABLED`. Compare `GET /api/metrics/workflows?faultInjected=false` with `=true`.

| Demo | How | Expected evidence |
|---|---|---|
| Implementation defect | record evidence for code that fails a `TEST` probe | `IMPLEMENTATION_DEFECT`, compensation, `AWAITING_REWORK`; `POST /rework` `{"fromNode":"IMPLEMENT"}` → evidence invalidated, plan+1, new evidence required |
| Rejection + rework | reject `RELEASE_APPROVAL`, then `POST /rework` `{"fromNode":"DOCS"}` | `APPROVAL_REJECTED`, `AWAITING_REWORK` → plan+1; only `DOCS`, `RELEASE_READINESS`, `RELEASE_APPROVAL` re-run; `IMPLEMENT`/`TEST`/`SECURITY` preserved |
| Terminate | reject, then `POST /terminate` | `FAILED`, `TERMINATION` decision |
| Invalidation | after design approval (or after evidence), `POST /requirement-change` | `DECISION_INVALIDATED` for approval and evidence; both required again at new plan |
| Retry + rollback + compensation | `faults:[{"stage":"TEST","type":"TRANSIENT","times":1}]` | attempt 1: probes created, fault, `ATTEMPT_ROLLED_BACK`, `COMPENSATION_COMPLETED`; attempt 2 succeeds; incident `RETRY` recovered |
| Permanent failure | `{"stage":"TEST","type":"PERMANENT"}` | no retry; compensation; run `FAILED`; incident `RECOVERY_FAILED` |
| Retry exhaustion + resume | `{"stage":"SECURITY","type":"TRANSIENT","times":3}` | `RETRY_EXHAUSTED`, `SAFE_STOPPED` (recoverable); `/resume` re-runs only `SECURITY` |
| Timeout | `{"stage":"DOCS","type":"TIMEOUT","times":1}` | `STAGE_TIMED_OUT` (transient), success on retry |
| Fallback | `{"stage":"DOCS","type":"TRANSIENT","times":3}` | `FALLBACK_USED`, `DOCS` provenance `FALLBACK` |
| Compensation failure | `{"stage":"TEST","type":"COMPENSATION_FAILURE"}` | `COMPENSATION_FAILED`, `SAFE_STOPPED` (non-recoverable) |
| Policy FAIL | requirement "… and allow javascript: URLs" | `SEC-01 FAIL`, `SAFE_STOPPED` before design approval |
| Policy exception | requirement "… and record each visitor's IP address" | `PRIV-01 EXCEPTION_REQUESTED`; approve via `/policy-exceptions/PRIV-01` with all fields |
| Restart | run waiting at any gate or at `AWAITING_IMPLEMENTATION`; stop and start app | identical status, stages, events |

Metrics: `GET /api/metrics/workflows` → counts, rates, rollbacks, compensations, recovery
durations, MTTR, labeled `DEMONSTRATION`.

## Known limitations (to be stated in README)

- Operator identity is not authenticated.
- Host names that resolve to private addresses are not blocked.
- The application never writes code: `IMPLEMENT` is an external action whose evidence is supplied by a human or agent (`EXTERNAL`),
  and its truth is checked only behaviorally by `TEST`/`SECURITY`.
- Ambiguity detection is rule-based.
- Metrics are local demonstration figures.
