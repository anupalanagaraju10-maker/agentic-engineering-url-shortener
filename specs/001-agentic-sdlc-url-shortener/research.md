# Research: Agentic Software Engineering System — URL Shortener

**Phase**: 0 (`/speckit.plan`) | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

Each entry records **Decision / Rationale / Alternatives considered**. All decisions here are
*proposed* and become binding only after human architecture review and acceptance of the
corresponding ADR (constitution, Technology & Scope Constraints).

Environment facts verified on the candidate's machine (2026-10-01): JDK 21.0.1, Maven 3.9.6;
local Maven cache already holds Spring Boot 3.5.16, H2 2.3.232, Flyway 11.7.2.

---

## R1. Runtime, language and build

- **Decision**: Java 21, Spring Boot 3.5.x (pinned to the latest 3.5 patch at scaffolding;
  3.5.16 is cached locally), Maven with the Maven Wrapper (`mvnw`) committed.
- **Rationale**: Matches the constitution's preferred planning inputs; the candidate's toolchain
  is already installed; 3.5.x is the most widely understood Spring Boot line and works offline
  from the local cache. The wrapper makes clean-clone verification reproducible.
- **Alternatives**: Spring Boot 4.0 (newer, but modular starters and Jackson 3 add migration
  risk with no requirement benefit); plain Java without Spring (more hand-written HTTP,
  persistence and validation code to review); Gradle (no advantage; Maven is preferred).

## R2. Persistence

- **Decision**: H2 in **file mode** (`./data/shortener`), schema owned by **Flyway** migrations,
  accessed through **Spring Data JPA** with `ddl-auto=validate`. Tests use in-memory H2 except
  the restart test, which uses a file database in a temp directory.
- **Rationale**: File-mode H2 gives durability across restarts (FR-ORC-006) with no external
  server. Flyway makes schema evolution explicit and reviewable — the brownfield change (SCN-B)
  becomes a real, versioned migration. JPA is the most familiar access style for reviewers;
  atomic counters use a single `UPDATE … SET count = count + 1` query.
- **Alternatives**: In-memory H2 (fails FR-ORC-006); PostgreSQL (external server, violates
  single-process constraint); `JdbcClient` without JPA (slightly less code magic but more SQL
  and row mapping to write; acceptable fallback if JPA becomes awkward); Hibernate
  `ddl-auto=update` (schema changes would be implicit and unreviewable).

## R3. Workflow engine model

- **Decision**: An in-process engine over a **static, explicit DAG** defined in code
  (`WorkflowGraph`): 14 nodes, each with declared dependencies, a kind (`AUTOMATED`,
  `HUMAN_GATE`, `EXTERNAL_ACTION`, plus a `conditional` flag), entry/exit conditions, timeout, retry eligibility and optional
  fallback. Scheduling is **wave-based**: compute every eligible node, execute all eligible
  automated nodes concurrently on a fixed thread pool, wait for the wave, persist each result,
  recompute. The engine advances **synchronously** inside each operator command (create,
  approve, clarify, record implementation, resume…) until no automatic progress is possible
  (human gate, external action, terminal state, or safe-stop). A per-run in-JVM lock plus optimistic locking (`version` column) serializes
  commands on the same run.
- **Rationale**: Waves give real parallelism with a trivially explainable join: a node is
  eligible only when all dependencies are `SUCCEEDED` or `SKIPPED`. Synchronous advancement
  keeps tests deterministic (a command returns when the run is at a gate or terminal) and avoids
  a background scheduler, queue or polling. Single process ⇒ an in-JVM lock is sufficient
  (constitution prohibits distributed locking).
- **Alternatives**: Asynchronous background runner with polling (more moving parts, flaky
  tests); a workflow library/engine such as Temporal, Camunda, Spring State Machine (prohibited or
  unjustified); event-driven "start each node as soon as its last dependency finishes" (marginally
  faster, harder to reason about; no requirement needs it).
- **Limitation**: An HTTP command blocks while automated stages run (seconds; bounded by
  timeouts × retries).

## R4. What a stage does (AMB-001 → deterministic executors + external action)

- **Decision**: Three node kinds: `AUTOMATED` (one deterministic `StageExecutor` each, over the
  run's persisted context), `HUMAN_GATE` (`CLARIFICATION`, `DESIGN_APPROVAL`,
  `RELEASE_APPROVAL`; satisfied only by `HUMAN` actors) and `EXTERNAL_ACTION` (`IMPLEMENT`
  only). After `DESIGN_APPROVAL` the run waits in `AWAITING_IMPLEMENTATION` while the
  implementation is performed **outside the application** by the engineer and/or Claude Code
  (under SpecKit tasks). The performer — `HUMAN` or `AGENT` — then records **implementation
  evidence** through the API:
  - implementation summary;
  - requirement IDs addressed (e.g. `FR-URL-008`);
  - changed artifacts (repository-relative paths), with the **required** revision/commit id
    whenever artifacts changed;
  - or, if no code/config/schema change was needed, a **required** `noChangeJustification`. This
    is accepted only when `DESIGN` determined `implementationRequired = false`, meaning every
    requested capability is already `IMPLEMENTED` in the registry, the requirement does not ask
    to change one, and it introduces no behavior detail outside the capability's recorded
    behavior. The human reviews this flag at design approval;
  - actor type/identity and the plan version implemented.
  Only then does `IMPLEMENT` succeed, which makes `TEST`, `DOCS` and `SECURITY` eligible. Those
  stages run real checks against the **running build** — typically after the application has
  been restarted on the new code, which the persisted run survives.
- **Provenance labels** on every stage output: `ACTUAL` (computed or checked by the running
  system), `EXTERNAL` (evidence of an external action supplied by a human or agent; recorded,
  structurally validated, not verified against Git), `FALLBACK` (fallback path). Injected failures are labeled `INJECTED` in all records.
- **What the application does not do**: it never writes, generates or modifies source code, and
  it does not independently verify that a commit id exists; downstream `TEST`/`SECURITY` probes
  are the behavioral verification.
- **Rationale**: Honest separation between governance (the system) and engineering work (the
  human + assistant). The workflow governs real implementation instead of describing a simulated
  one. No AI service in the running system (EXC-008).
- **Alternatives**: `IMPLEMENT` as a simulated change plan (rejected at architecture review);
  LLM code generation in-app (rejected at AMB-001).

## R5. Ambiguity detection (FR-ORC-016)

- **Decision**: A small, documented rule set evaluated on the normalized requirement using a
  fixed vocabulary of URL-shortener capabilities:
  - **AMB-R1 Unclear outcome** — no recognizable capability *and* no observable outcome phrase.
  - **AMB-R2 Missing parameter** — a capability that needs a condition/trigger/duration/threshold
    (e.g., expiration) is requested without one, and without stating that the client supplies it
    (e.g., "optional expiration" per link).
  - **AMB-R3 Conflict** — mutually exclusive statements (e.g., "never expire" with "expire").
  - **AMB-R4 Multiple interpretations** — a modifying requirement whose scope is unqualified
    (e.g., "links" without *new* / *existing* / *all*).
  Each finding records rule id, matched text and explanation. Missing edge-case detail never
  triggers a rule.
- **Expected results**: SCN-A → no findings. SCN-B → no findings ("optional" ⇒ client-supplied
  parameter; "existing links" ⇒ qualified scope). SCN-C "Make links expire." → AMB-R2 (no
  duration or trigger) and AMB-R4 (which links).
- **Rationale**: Deterministic and explainable; the limitation (keyword rules, not language
  understanding) is documented.
- **Alternatives**: Operator-flagged ambiguity only (rejected at clarification); scoring
  heuristics (opaque).

## R6. Greenfield vs brownfield branch and the capability registry

- **Decision**: A static **capability registry** lists the URL-shortener capabilities the
  workflow understands (vocabulary, design template, acceptance probes) and, for each, its
  **status**: `IMPLEMENTED` (with components, endpoints, migrations, tests) or `PLANNED`. A
  requirement is **brownfield** when it modifies a capability whose status is `IMPLEMENTED`
  (change verbs such as *add … to existing*, *change*, *modify*, or explicit *existing*);
  otherwise greenfield. `IMPACT_ANALYSIS` builds its report (all ten areas of FR-SCN-002) from
  the registry. A unit test verifies that every `IMPLEMENTED` entry references real classes and
  test files, so the registry cannot silently drift from the code. The registry entry for a
  capability moves from `PLANNED` to `IMPLEMENTED` in the same change that implements it.
- **Rationale**: A credible, checkable impact analysis without code parsing; the greenfield /
  brownfield distinction reflects the actual state of the codebase.
- **Alternatives**: Static code analysis (heavy); free-text impact analysis (not verifiable).

## R7. Parallel group and join (FR-ORC-004/005)

- **Decision**: After `IMPLEMENT`, the independent nodes **`TEST`**, **`DOCS`** and
  **`SECURITY`** form the parallel group; **`RELEASE_READINESS`** depends on all three and is the
  join.
- **Rationale**: The three are genuinely independent (each reads the design and implementation
  evidence and writes only its own output).
- **Overlap evidence**: start/end timestamps and thread names are persisted per attempt. The
  overlap test uses an injected `DELAY` fault to make overlap deterministic; this is labeled
  `INJECTED` (FR-REL-011).

## R8. State names (FR-ORC-011)

- **Decision**:
  - Run status: `RUNNING`; waiting: `AWAITING_CLARIFICATION`, `AWAITING_APPROVAL`,
    `AWAITING_IMPLEMENTATION`, `AWAITING_REWORK`; halted: `SAFE_STOPPED` (with `recoverable`
    flag); terminal: `COMPLETED`, `FAILED`.
  - Stage status: `PENDING`, `RUNNING`, `BLOCKED` (waiting for a human gate decision,
    external-action evidence or a policy-exception decision), `SUCCEEDED`, `FAILED`, `SKIPPED`.
  - A rejection is a `REJECTION` decision plus an `APPROVAL_REJECTED` event; the run then waits
    in `AWAITING_REWORK` for explicit rework or termination (FR-HUM-005).
  - Retry, rollback, compensation, fallback, replan, policy results, invalidations and recovery
    incidents are **audit events**, not statuses.

## R9. Reliability mechanisms

- **Decision**:
  - **Classification**: executors return `TRANSIENT` or `PERMANENT`; timeouts are `TRANSIENT`;
    validation/policy/invariant failures are `PERMANENT`.
  - **Retry** (PVT-001): at most 2 retries (3 attempts), backoff 100 ms then 200 ms, transient
    only; every attempt persisted.
  - **Timeout** (PVT-002): 5 s default per automated node via `Future.get(timeout)` then
    cancel; configurable property (tests use shorter values). Human gates and the
    `IMPLEMENT` external action have no timeout.
  - **Fallback** (FR-REL-005): `DOCS` only — if the detailed generator still fails after
    retries, a minimal template that still contains every required section is produced and
    labeled `FALLBACK`. Never applied to `SECURITY`, gates, `IMPLEMENT` or policy checks.
  - **Rollback = attempt (transaction) rollback**: a stage writes its output and `SUCCEEDED`
    status in a single completion transaction. When an attempt fails, times out or is
    interrupted, nothing of it is committed, and the stage row returns to `PENDING` with no output
    (`ATTEMPT_ROLLED_BACK`). This is an exact restore. At startup, the same rollback is applied to
    stages found `RUNNING` after a crash.
  - **Compensation = corrective action for side effects already committed elsewhere**: `TEST`
    creates real probe links through `LinkService` (committed in their own transactions, tagged
    `probe_run_id`). On success, `TEST` deletes its probe links before completing, as normal
    cleanup; the validation evidence lives in the stage output and audit events. If an attempt
    fails after creating probes, the engine runs **compensation**: it deletes all links tagged
    with the run (`COMPENSATION_STARTED/COMPLETED`). The same sweep runs when `TEST` is
    invalidated by replan or the run ends `FAILED`/`SAFE_STOPPED`. The sweep is idempotent.
    Compensation failure ⇒ safe-stop (non-recoverable).
  - **Fault timing**: injected faults fire after the executor's main work and before its
    completion commit, so a fault in `TEST` exercises both attempt rollback and compensation.
  - **Safe-stop** triggers: mandatory policy `FAIL`; retry exhaustion without fallback
    (recoverable); invalid/inconsistent state (non-recoverable); compensation failure
    (non-recoverable); process restart during a run (recoverable, reason `INTERRUPTED`).
  - **Permanent failure** of an automated node ⇒ compensation sweep ⇒ run `FAILED`, except
    `IMPLEMENTATION_DEFECT` (a TEST/SECURITY probe fails after evidence was accepted) ⇒
    `AWAITING_REWORK` (see R19, CHK036).
  - **Resume**: allowed only for recoverable `SAFE_STOPPED` runs; reloads state, keeps
    `SUCCEEDED` nodes, re-executes only failed/pending eligible nodes (partial failure in the
    parallel group re-runs only the failed branch).
  - **Fault injection** (FR-REL-011): optional `faults` on run creation —
    `{stage, type: TRANSIENT|PERMANENT|TIMEOUT|DELAY|COMPENSATION_FAILURE, times, delayMs}` for
    automated nodes only; **disabled by default**, enabled only by the `demo` profile or test
    configuration; every injected effect is labeled and injected runs are separable in metrics
    (R19, CHK038).
  - **Idempotent final report**: `FINAL_REPORT` regenerates the same report from persisted
    records, so it is safe to retry or re-run.

## R10. Replanning, rework and invalidation (FR-ORC-013, FR-HUM-005)

- **Decision**: One mechanism, `replan(fromNode, reason, cause)`. Steps 2–5 commit in **one
  database transaction**; on failure the prior state is unchanged (R19, CHK007):
  1. affected = `fromNode` ∪ all DAG descendants; preserved = everything else;
  2. run the compensation sweep if `TEST` is affected;
  3. reset affected nodes to `PENDING` — prior outputs are kept in the `STAGE_INVALIDATED` audit
     event payload (nothing deleted);
  4. invalidate human decisions attached to affected nodes — gate approvals **and** recorded
     implementation evidence — with `DECISION_INVALIDATED` decisions that supersede them;
  5. increment `planVersion`; record `PLAN_REPLANNED` with old/new version, reason, affected and
     preserved node lists;
  6. advance the engine.
  Causes: **clarification** (from `UNDERSTAND`; the `CLARIFICATION` gate is kept `SUCCEEDED`
  holding the decision), **requirement change** (from `UNDERSTAND`), **rework after rejection**
  (from an operator-chosen node upstream of the rejected gate, e.g. `DESIGN` or `DOCS`).
- **Rationale**: Plan version = revision of decomposition/design (spec terminology), so rework
  also bumps it. Reworking `DOCS` after a rejected release approval preserves `IMPLEMENT`,
  `TEST` and `SECURITY`, demonstrating selective invalidation; a requirement change after design
  approval invalidates the approval and any implementation evidence.

## R11. Policy model v1 (FR-POL)

- **Decision**: `PolicyCatalog` version **`v1`** in code — **five checks, one per spec domain**
  (FR-POL-002), each bound to the node after which it is evaluated; results stored append-only.

  | Check | Domain | Evaluated after | Rule | Demonstrates |
  |---|---|---|---|---|
  | PRIV-01 | privacy | UNDERSTAND | requirement asks to capture personal data (e.g. visitor IP, email, location) ⇒ `EXCEPTION_REQUESTED`; else `PASS` | human-approved exception |
  | SEC-01 | security | DESIGN | requirement/design weakens URL safety (other schemes, private hosts, disabled validation) ⇒ `FAIL`; else `PASS` | blocking `FAIL` → safe-stop |
  | CHG-01 | change control | DESIGN | brownfield ⇒ impact analysis present (`PASS`/`FAIL`); greenfield ⇒ `NOT_APPLICABLE` | `NOT_APPLICABLE` |
  | DEP-01 | dependencies & licensing | DESIGN | dependencies the design adds are on the approved list (with license) ⇒ `PASS`, otherwise `FAIL`; none added ⇒ `NOT_APPLICABLE` | licensing gate |
  | AUD-01 | audit evidence | RELEASE_READINESS | every executed node, gate decision and implementation record has audit events; retention assumption ASM-009 stated | auditability |

  All mandatory. Mandatory `FAIL` ⇒ safe-stop. `EXCEPTION_REQUESTED` ⇒ node `BLOCKED`, run
  `AWAITING_APPROVAL` until a human approves (with all FR-POL-005 fields) or rejects the
  exception (⇒ safe-stop). Readiness fails on any unresolved mandatory failure or unapproved
  exception.
- **Removed at architecture review (YAGNI)**: `SEC-02` — the real validator probes remain, as
  the `SECURITY` stage's exit criteria (a failing probe is a `PERMANENT` failure), so they need
  no separate policy record. `CHG-02` (valid design approval) is guaranteed structurally, because
  `IMPLEMENT` cannot become eligible without it. Bounded retries are also structural (PVT-001).
- **Rationale**: Each domain in FR-POL-002 is covered once. All four result values are
  demonstrable. Every check is a few lines of deterministic code. There is no policy engine.
- **Limitation**: DEP-01 checks the design's declared dependencies against the approved list; it
  is not a license scanner of the build.

## R12. Decisions, actors and identity

- **Decision — actor model**: every decision and audit event carries `actorType` (`SYSTEM`,
  `HUMAN`, `AGENT`) and `actorIdentity` (e.g. `workflow-engine`, a human name, `claude-code`).
  - `SYSTEM`: engine-written only; the API never accepts it.
  - `HUMAN` is **required** for the human gates, clarification, policy-exception decisions,
    rework, termination, requirement change and resume. An **`AGENT` can never satisfy a human
    gate**.
  - `AGENT` is allowed to submit requirements and to record implementation evidence (the
    `EXTERNAL_ACTION`).
- **Validation**: every command also needs `reason`/summary and the `planVersion` acted on. It is
  refused (`409`/`400` plus a recorded `DECISION_REFUSED`) if:
  - the actor type is not allowed for that action;
  - the identity is blank or reserved (`workflow-engine`, `system` for anyone; `claude-code` for
    `HUMAN`);
  - the run is not waiting for that action;
  - `planVersion` is stale.

  Actor types are self-declared: there is no authentication (EXC-003, documented). The rule makes
  the approval model explicit and testable but cannot stop a caller from lying.
- **Destructive/irreversible actions**: this prototype has **none** — no stage deletes client
  data or performs an action that cannot be undone (probe-link cleanup only removes the run's own
  probe data; the final report is idempotent). FR-HUM-001's irreversible-action gate is therefore
  satisfied vacuously and documented: any future destructive action would need its own explicit
  human gate node before it. Release approval records the residual risks the human accepts
  (FR-HUM-001/002).

## R13. HTTP semantics (FR-URL-006/007/008/013/015)

- **Decision**: Redirect `GET /r/{code}` ⇒ `302 Found` with `Location` and
  `Cache-Control: no-store` (every visit reaches the server and is counted). Unknown ⇒ `404`,
  expired ⇒ `410 Gone`, storage unavailable ⇒ `503`. Errors use RFC 9457 Problem Details
  (`application/problem+json`) with an added `category` field and no stack traces.
- **Alternatives**: `301` (cached by browsers ⇒ uncounted visits); `307` (also fine; `302` is the
  most widely understood).

## R14. Short codes and idempotency

- **Decision**: 7-character Base62 codes from `SecureRandom` (PVT-006); database unique
  constraint; on violation regenerate, max 5 attempts, then `503`-class explicit error
  `CODE_SPACE_EXHAUSTED`. Generator is an injectable interface so collisions are testable.
  `Idempotency-Key` header (≤ 100 chars): request fingerprint = SHA-256 of canonical
  `(url, expiresAt)`; same key + same fingerprint ⇒ `200` with the original response; different
  fingerprint ⇒ `409`; the key row and the link are written in one transaction, and a
  unique-key race is resolved by re-reading.
- **Alternatives**: Hash-of-URL codes (forces de-duplication by URL, rejected at AMB-002);
  sequence-based codes (enumerable).

## R15. Destination address checks (FR-URL-002/003/016)

- **Decision**: Parse with `java.net.URI`; require absolute `http`/`https`, non-empty host,
  length ≤ 2,048 (PVT-007). Reject `localhost` (and `*.localhost`), IPv4 literals in
  `0.0.0.0/8`, `127/8`, `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, IPv6 literals `::`, `::1`,
  `fc00::/7`, `fe80::/10`, IPv4-mapped forms, and non-canonical numeric hosts (e.g. `2130706433`).
  IP literals are parsed without name resolution; host names are never resolved.
- **Documented limitation**: a host name that resolves to a private address is not blocked
  (EXC-009). The service never fetches destination URLs itself, so server-side request forgery
  exposure is limited to visitors' browsers being redirected.

## R16. Metrics and recovery incidents (FR-OBS-003/004/005)

- **Decision**: **No recovery table.** Recovery incidents come from append-only audit events:
  - `FAILURE_DETECTED` opens an incident (node, failure class, injected flag; the incident id is
    this event's `seq`);
  - `RECOVERY_STARTED` (incident id, mechanism: `RETRY`, `FALLBACK`, `COMPENSATION`, `RESUME`);
  - `RECOVERY_COMPLETED` (incident id) closes it as recovered;
  - `RECOVERY_FAILED` (incident id, reason) closes it as unrecovered.

  Detection time, recovery start, recovery completion, mechanism and recovered status are
  therefore all in the event history (FR-OBS-003). `GET /api/metrics/workflows` computes from
  runs, stages and events on request:
  - run counts and rates by final status;
  - retry count and frequency;
  - rollback count (`ATTEMPT_ROLLED_BACK`) and compensation count (`COMPENSATION_COMPLETED`);
  - recovery durations (completed − detected);
  - unrecovered and still-open incidents;
  - end-to-end duration, wall clock and excluding human wait;
  - **MTTR = Σ(duration of recovered incidents) / count(recovered incidents)**.

  The response carries `"label": "DEMONSTRATION — local runs, not production statistics"`.
- **Rationale**: Every required field derives cleanly from immutable history, which removes a
  mutable table and keeps one source of truth.
- **Alternatives**: separate `recovery_record` table (removed at architecture review);
  Micrometer/Prometheus (infrastructure without a requirement).

## R17. Testing approach

- **Decision**: JUnit 5 + AssertJ; plain unit tests for validator, code generator, ambiguity
  rules, graph, policy, replan; `@SpringBootTest` + MockMvc for API and scenario tests; a
  concurrency test with an executor (PVT-008); a restart test that closes and reopens the Spring
  context over the same H2 file; a measurement test tagged `measurement` (excluded from the
  default build) for PVT-003..005. Red-green-refactor per constitution Principle III.
- **Alternatives**: Testcontainers (no external DB); Cucumber (extra tooling).

## R18. Logging and health

- **Decision**: SLF4J logs with `runId` in MDC; Actuator exposing only `health` (DB indicator
  reports `DOWN` when storage is unavailable, FR-URL-014).

## R19. Requirements-quality gate resolutions (2026-10-02)

These are the decisions behind the plan's §Requirements-quality gate resolutions (checklist items
CHK002–CHK038). They were requested by the human candidate after `/speckit.checklist`.
- **Rationale**: smallest amendments that make the requirements unambiguous and testable without
  changing the architecture.
- **Alternatives rejected**:
  - accepting out-of-scope evidence IDs as warnings: weakens traceability;
  - non-atomic replanning: partial invalidation is possible;
  - accepting conflicting clarifications: silent override of approved requirements;
  - ending a run `FAILED` on an implementation defect: discards correctable work;
  - fault injection on by default: demonstration data mixes with real runs.

## Open items

None. No `NEEDS CLARIFICATION` remains in the Technical Context.
