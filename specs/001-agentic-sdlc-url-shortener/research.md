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
  is already installed; 3.5.x is the most widely understood Spring Boot line and, once its artifacts are cached, works offline
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

## R5. Ambiguity detection (FR-ORC-016) — exact rule definitions

- **Decision**: four deterministic rules evaluated on the **normalized** current requirement (the
  original text plus any recorded clarifications).
  - **Normalization**: lowercase; replace `-` and `_` with a space; collapse whitespace; keep
    digits; strip other punctuation. Matching is whole-word (or whole-phrase) on the normalized
    text.
  - **No other interpretation is applied**: no synonyms beyond the lists below, no stemming beyond
    the listed forms, no AI/NLP. Each finding records the rule id, the matched (or missing) terms
    and a fixed explanation.

  **Capability terms** (also used by UNDERSTAND):

  | Capability | Terms |
  |---|---|
  | CREATE_LINK | `short link`, `short links`, `short url`, `shorten`, `create a link`, `create link` |
  | REDIRECT | `redirect`, `redirects`, `redirected` |
  | ANALYTICS | `redirect count`, `last redirect`, `analytics`, `click count` |
  | IDEMPOTENCY | `idempotency`, `idempotent`, `duplicate request`, `duplicate requests` |
  | EXPIRATION | `expire`, `expires`, `expired`, `expiration`, `expiry` |

  **Rules**:
  - **AMB-R1 Unclear outcome** fires when the requirement contains **no** capability term **and
    no** outcome verb from: `return`, `returns`, `respond`, `responds`, `redirect`, `redirects`,
    `record`, `records`, `reject`, `rejects`, `refuse`, `refuses`, `create`, `creates`, `store`,
    `stores`, `show`, `shows`, `log`, `logs`, `block`, `blocks`, `allow`, `allows`. If an outcome
    verb is present but no capability term is, R1 does not fire; `DECOMPOSE` then fails
    `PERMANENT` (`INVALID_INPUT`, out-of-vocabulary) instead of guessing (CHK005).
  - **AMB-R2 Missing parameter** applies only to EXPIRATION, the only parameterized capability.
    It fires when an EXPIRATION term is present and **none** of the following is present:
    - a client-supplied marker: `optional`, `per link`, `client may supply`, `client supplies`,
      `supplied by the client`;
    - a duration: regex `\b\d+ (second|minute|hour|day|week|month|year)s?\b`;
    - an absolute time: `expiration time`, `expiry time`, `expires at`, `expiresat`,
      `absolute time`, `date`;
    - a trigger: regex `\bafter \d+ (click|redirect|visit)s?\b`.
  - **AMB-R3 Conflict** fires when **both** members of a pair are present:
    - pair E: universal expiry `(all|every|each) links? (must |will |should )?expire` **and**
      universal non-expiry `(all|every|each|no) links? (must |will |should )?(never|not) expire`;
    - pair D: `allow duplicates` / `always create a new link` **and** `never create duplicates` /
      `deduplicate`;
    - pair R: `temporary redirect` **and** `permanent redirect`.
  - **AMB-R4 Multiple interpretations** fires when a change verb is present — `make`, `add`,
    `change`, `modify`, `update`, `apply`, `enable`, `require` — together with the bare plural
    `links`, and **none** of these scope qualifiers appears anywhere in the requirement: `new`,
    `newly created`, `existing`, `all`, `every`, `each`, `per link`, `optional`, `specific`,
    `selected`, `links created after`, `links created before`.
- **Expected results**:
  - SCN-A ⇒ none.
  - SCN-B ⇒ none: `optional` satisfies R2, and `existing`/`optional` satisfy R4.
  - SCN-C "Make links expire." ⇒ R2 (no parameter) and R4 (`make` + `links`, no qualifier).
  - The quickstart clarification ("optional per link … absolute expiration time … existing links
    are unaffected") ⇒ none.
  - A requirement missing only edge-case detail ⇒ none.
- **Rationale**: Bounded lists make classification reproducible by any two reviewers (CHK011).
  Their limits are the documented limitation.
- **Alternatives**: Operator-flagged ambiguity only (rejected at clarification); scoring
  heuristics (opaque); NLP/AI interpretation (rejected at AMB-001).

## R6. Greenfield vs brownfield branch and the capability registry

- **Decision**: A static **capability registry** lists the URL-shortener capabilities the
  workflow understands (vocabulary, design template, acceptance probes) and, for each, its
  **status**: `IMPLEMENTED` (with components, endpoints, migrations, tests) or `PLANNED`. A
  requirement is **brownfield** when it modifies existing behavior. Exact rule (clarified
  2026-10-02 with the R5 normalization; behavior unchanged):
  - the normalized requirement contains the word `existing`; **or**
  - it contains an R6 behavior change verb (`add`, `change`, `modify`, `replace`, `remove`,
    `extend`, `introduce`, `increase`, `decrease`, `convert`, `migrate`) **and** at least one
    matched capability is `IMPLEMENTED`.

  Otherwise it is greenfield. Results: SCN-A greenfield, SCN-B brownfield, and the SCN-C
  quickstart clarification brownfield. `IMPACT_ANALYSIS` builds its report (all ten areas of FR-SCN-002) from
  the registry. A unit test verifies that every `IMPLEMENTED` entry references real classes and
  test files, so the registry cannot silently drift from the code. The registry entry for a
  capability moves from `PLANNED` to `IMPLEMENTED` in the same change that implements it.
- **Recorded behavior (CHK012)**: the approved behavior statements below, held in the registry
  with their requirement IDs, are the only "recorded behavior". Each is taken from approved spec
  requirements; none adds behavior.

  | Capability | Recorded behavior statements | Requirement IDs |
  |---|---|---|
  | CREATE_LINK | B1 a client can create a short link for an absolute `http`/`https` address with a non-empty host · B2 other schemes (incl. `javascript`, `file`, `data`) are refused · B3 `localhost` and literal loopback/private hosts are refused without name resolution · B4 each code is unique, URL-safe, 7 characters, regenerated at most 5 times on collision · B5 addresses up to 2,048 characters · B6 if storage is unavailable, creation fails with service-unavailable and no code | FR-URL-001..005, 013, 016; PVT-006/007 |
  | REDIRECT | B1 an active link redirects to its original address · B2 an unknown code returns not-found · B3 if storage is unavailable, a redirect returns service-unavailable · B4 an analytics-recording failure does not prevent the redirect | FR-URL-006, 007, 013, 017 |
  | ANALYTICS | B1 each successful redirect increments the link's count and sets its last-redirect time, readable by clients · B2 not-found and expired attempts are not counted · B3 no count is lost under concurrent redirects | FR-URL-010, 012 |
  | IDEMPOTENCY | B1 a create request may carry an idempotency key · B2 same key + same content returns the first result · B3 same key + different content is a conflict · B4 no key means a new link | FR-URL-011 |
  | EXPIRATION | B1 a client may optionally supply an absolute expiration time per link · B2 it must be in the future · B3 after it, the link returns an expired result distinct from not-found, with no redirect and no count · B4 a link without an expiration never expires (existing links are unaffected) | FR-URL-008, 009 |

  **`implementationRequired = false`** only if all three of these hold:
  1. every requested capability is `IMPLEMENTED`;
  2. the requirement contains no change verb aimed at behavior — `add`, `change`, `modify`,
     `replace`, `remove`, `extend`, `introduce`, `increase`, `decrease`, `convert`, `migrate`;
  3. it contains none of the out-of-record detail patterns — the R2 duration and trigger
     regexes, `default`, `maximum`, `max`, `minimum`, `min`, `renew`, `notify`, `delete`,
     `purge`, `archive`.

  Otherwise it is `true`. A human reviews the result at design approval.
- **Approved-requirement conflict rule (CHK034)**: `/clarify` returns `409 CHANGE_CONTROL_REQUIRED`
  when the clarification matches a contradiction pattern of a recorded statement:
  - EXPIRATION B4: `default expir`, `(all|every|each) links? (must |will |should )?expire`,
    `links without (an )?expiration (must |will |should )?expire`;
  - CREATE_LINK B2/B3: `allow (javascript|file|data)`, `allow localhost`,
    `allow private (ip|address)`;
  - IDEMPOTENCY B4: `deduplicate`, `same url returns the same link`.
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

  All mandatory. Mandatory `FAIL` ⇒ safe-stop. `EXCEPTION_REQUESTED` ⇒ the run waits in
  `AWAITING_APPROVAL` with `pendingAction = EXCEPTION:<checkId>` (the evaluated node has already
  succeeded, so the run, not the node, waits and no downstream node starts; clarified in Phase 3) until a human approves (with all FR-POL-005 fields) or rejects the
  exception (⇒ safe-stop). Readiness fails on any unresolved mandatory failure or unapproved
  exception.
- **Removed at architecture review (YAGNI)**: `SEC-02` — the real validator probes remain, as
  the `SECURITY` stage's exit criteria (a failing probe is a `PERMANENT` failure), so they need
  no separate policy record. `CHG-02` (valid design approval) is guaranteed structurally, because
  `IMPLEMENT` cannot become eligible without it. Bounded retries are also structural (PVT-001).
- **Rationale**: Each domain in FR-POL-002 is covered once. All four result values are
  demonstrable. Every check is a few lines of deterministic code. There is no policy engine.
- **PRIV-01 vocabulary (CHK018)**: `EXCEPTION_REQUESTED` when the normalized requirement contains
  any of: `ip address`, `visitor ip`, `client ip`, `user agent`, `email`, `e mail`, `phone`,
  `location`, `geolocation`, `geo location`, `device id`, `cookie`, `browser fingerprint`,
  `visitor name`, `user identity`, `personal data`, `pii`, `track users`, `user tracking`.
  Otherwise `PASS`.
- **DEP-01 approved dependency list (CHK018)**: these are the direct dependencies from plan and
  tasks. Each licence was **verified on 2026-10-02 from POM metadata**: the local Maven repository,
  or Maven Central where the artifact is not cached.

  | Dependency (version) | Scope | License | Verified from |
  |---|---|---|---|
  | `org.springframework.boot:spring-boot-starter-parent` 3.5.16 | build parent | Apache License 2.0 | local POM |
  | `org.springframework.boot:spring-boot-starter-web` 3.5.16 | compile | Apache License 2.0 | local POM |
  | `org.springframework.boot:spring-boot-starter-data-jpa` 3.5.16 | compile | Apache License 2.0 | Maven Central POM (not in local cache) |
  | `org.springframework.boot:spring-boot-starter-validation` 3.5.16 | compile | Apache License 2.0 | local POM |
  | `org.springframework.boot:spring-boot-starter-actuator` 3.5.16 | compile | Apache License 2.0 | local POM |
  | `org.flywaydb:flyway-core` 11.7.2 (Boot-managed) | compile | Apache License 2.0 (declared in parent `flyway-parent` 11.7.2) | local POM |
  | `com.h2database:h2` 2.3.232 (Boot-managed) | runtime | MPL 2.0 or EPL 1.0 | local POM |
  | `org.springframework.boot:spring-boot-starter-test` 3.5.16 | test | Apache License 2.0 | local POM |

  DEP-01 passes a design dependency only if it is on this list. A design that adds none gets
  `NOT_APPLICABLE`.
- **Noted transitive license (for human awareness, not a DEP-01 rule; acknowledged and accepted by
  the human candidate 2026-10-02)**:
  `org.hibernate.orm:hibernate-core` 6.6.53.Final, which is pulled in by `spring-boot-starter-data-jpa`
  and Boot-managed, is **GNU LGPL v2.1 or later** (Maven Central POM, verified 2026-10-02).
- **SEC-01 exact patterns (added in Phase 3; R11 previously gave only the intent)**: `FAIL` when the
  normalized requirement matches any of `allow (javascript|file|data)`, `allow localhost`,
  `allow private (ip|address)` (the R6 CREATE_LINK B2/B3 contradiction patterns), or
  `(disable|skip) validation`. Otherwise `PASS`.
- **How a design "adds" dependencies for DEP-01 (added in Phase 3)**: the dependencies a design adds
  are the technology mentions found in the normalized requirement.
  - Approved mentions: `h2`, `flyway`, `jpa`, `spring web`, `bean validation`, `actuator`, mapping to
    the approved artifacts above.
  - Unapproved mentions: `redis`, `kafka`, `rabbitmq`, `mongodb`, `postgresql`, `postgres`, `mysql`,
    `elasticsearch`, `temporal`, `camunda` (each FAILs).
  - No mention means `NOT_APPLICABLE`, which keeps the plan's "SCN-A: DEP-01 N/A": capability
    templates add no dependencies because the codebase already has every approved one.
- **Limitation**: DEP-01 checks the design's declared direct dependencies against the approved
  list. It is not a license scanner of the full transitive build.

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

## R20. Checklist gate clarifications — round 2 (2026-10-02)

Clarifications only; no architectural decision changes.

- **CHK008 External repository changes**: replanning or rework invalidates recorded
  implementation evidence. It cannot and does not undo Git changes. Reverting or adjusting
  repository changes is an external engineering responsibility, done in Git under SpecKit tasks.
  Downstream validation runs only after new evidence with a new revision is recorded.
- **FR-POL-007 recorded impact analysis**: the impact analysis recorded before replanning is the
  `PLAN_REPLANNED` payload, written in the same transaction before any affected stage re-executes.
  It holds the reason, affected nodes, preserved nodes and invalidated decisions. For brownfield
  requirements, the re-run `IMPACT_ANALYSIS` stage adds the ten-area report.
- **CHK013 Material requirement change**: a change is material when it changes any of:
  - approved observable behavior;
  - acceptance criteria;
  - capability scope;
  - the API or data contract;
  - security or privacy constraints;
  - a policy outcome;
  - other approved requirement semantics.

  A pure wording or documentation clarification that alters none of these is non-material. A
  HUMAN initiates a material change through `requirement-change`, and the runtime treats every
  such call as material. The runtime attempts no open-ended semantic inference about materiality.
- **CHK015 Determinism (FR-ORC-012)**: "same stage path" means the same semantic outcome:
  - nodes executed or skipped;
  - branch outcomes;
  - gate locations;
  - stage success/failure classification (class and code);
  - requirement and capability decisions (findings, change type, `implementationRequired`,
    policy results).

  It does not require identical timestamps, run or correlation IDs, thread names, generated
  short codes, probe identifiers, or ordering of independent events emitted concurrently inside
  one parallel wave.
- **CHK024 Approval-before-implementation**:
  - `IMPLEMENT` evidence is refused unless the run is `AWAITING_IMPLEMENTATION` after a valid
    `DESIGN_APPROVAL` at the current plan version.
  - The evidence record's timestamp must be later than that approval decision's timestamp.
  - Live scenario traceability shows approval before evidence, with decision timestamps and the
    commit date of the cited revision.
  - **Limitation**: the runtime cannot observe when an engineer began editing files outside the
    application, so it does not claim to prove that external coding did not start early.
- **CHK027 Observable parallelism (SC-002)**: overlap is proven with controlled instrumentation:
  - sleeping stub executors in `WorkflowEngineTest`;
  - the injected `DELAY` fault on the real `TEST`/`DOCS`/`SECURITY` executors.

  The delay does not simulate parallelism; it only makes the real scheduler's concurrent execution
  measurable. Normal execution uses the same scheduler without delay. Live runs record intervals
  and thread names, and claim overlap only where the recorded intervals show it.
- **CHK028 SC-003 population**:
  - the denominator is every observed attempt to cross a `HUMAN_GATE`, both in the automated gate
    and scenario acceptance tests and in the three recorded live scenario demonstrations;
  - for each attempt, progression beyond the gate must be preceded by a valid HUMAN decision for
    that gate at the current `planVersion`;
  - expected result: 100%.
- **CHK033 Startup recovery**: at startup the engine inspects every non-terminal run.
  - A run that is `RUNNING` with a stage `RUNNING`: attempt rollback, then compensation, then
    `SAFE_STOPPED` (recoverable, `INTERRUPTED`).
  - A run that is `RUNNING` with no stage `RUNNING` (an interrupted orchestration boundary): the
    idempotent compensation sweep, then `SAFE_STOPPED` (recoverable, `INTERRUPTED`).
  - Waiting runs (`AWAITING_*`) are untouched.
  - Successful stages are never re-executed automatically; continuing requires HUMAN `resume`.
- **CHK039 Whole-command safety bound**: one HTTP command advances through at most **5 automated
  waves** before reaching a gate, the external action or a terminal state. The largest case is
  run creation (INTAKE → UNDERSTAND → DECOMPOSE → IMPACT_ANALYSIS → DESIGN); resume and
  requirement change are no larger.
  - Worst case per wave = 3 attempts × 5 s timeout + 0.1 s + 0.2 s backoff = 15.3 s, so 5 waves ≈
    76.5 s.
  - The DOCS fallback adds at most one more 5 s attempt, but only in the 2-wave evidence command
    (≈ 35.6 s).
  - **Stated safety bound: 90 s per command**, including margin for compensation and replan
    transactions.

  This is a worst-case safety bound under repeated injected failures, **not** a performance
  target. PVT-004 (normal SCN-A automated-active duration < 60 s, measured and reported) is
  unchanged.
- **Build note (factual correction)**: `spring-boot-starter-data-jpa` 3.5.16 and Hibernate are
  not in the local Maven cache. The first build downloads them from Maven Central; later builds
  run offline.

## Open items

None. No `NEEDS CLARIFICATION` remains in the Technical Context.
