---

description: "Task list for Agentic Software Engineering System — URL Shortener"
---

# Tasks: Agentic Software Engineering System — URL Shortener

**Input**: Design documents from `/specs/001-agentic-sdlc-url-shortener/`

**Prerequisites**:
- [plan.md](./plan.md) — revision 3, approved 2026-10-02
- [spec.md](./spec.md) — approved
- [research.md](./research.md)
- [data-model.md](./data-model.md)
- [contracts/openapi.yaml](./contracts/openapi.yaml)
- [quickstart.md](./quickstart.md)
- ADRs 0001–0005 — accepted

**Tests**: **Mandatory.** Constitution Principle III (test-first, non-negotiable) applies:
1. For every behavior-bearing task group, the test tasks come first.
2. Run them and observe the expected failure.
3. Then implement the smallest compliant change, rerun the tests, refactor, and run regression.
4. Record the observed failure and the passing run in the task-group checkpoint.

**Organization**: Tasks are grouped by user story (spec.md US1–US6). **Exception, approved in the plan
§Delivery slices (live SCN-A sequencing):** US1 is split into Phase 3 (workflow up to the
implementation wait) and Phase 5 (validation and release). US2, which builds the URL shortener, sits
between them. This ordering ensures the greenfield implementation happens *after* human design
approval.

**Human checkpoints**:
- Tasks marked **HUMAN** are performed or approved by the human candidate. The assistant prepares
  them but never performs the decision.
- Every phase ends at a checkpoint: STOP for review and a commit (constitution, Development Workflow).

## Checkpoint protocol (constitution §Development Workflow — applies to every phase)

At every **Checkpoint**, STOP and:
1. **Report**:
   - tasks completed;
   - requirements addressed;
   - ADRs followed;
   - files changed;
   - tests written first and their observed failures;
   - validation commands actually executed and their real results;
   - deviations, risks and assumptions;
   - the recommended commit boundary and message.
2. **Pre-commit review** of the uncommitted changes only:
   - requirement mapping;
   - ADR compliance;
   - API/schema/persistence/orchestration/security/reliability impact;
   - relevant tests pass;
   - documentation and traceability updated;
   - no unrelated files.

   Do not recommend a commit while relevant tests fail or required validation has not run.
3. **HUMAN** approves the commit, or directs changes. Nothing is pushed or tagged without explicit
   instruction.

## Human-operated actions rule (pre-implementation review H5)

There is no authentication, so `actorType` is self-declared. To keep human gates meaningful in this
agentic setup:
- **Every `HUMAN`-typed request is executed by the human candidate**, never by the assistant. That
  covers approve, reject, clarify, rework, terminate, resume, requirement change and policy-exception
  decisions. Run them e.g. as `! curl …` in the Claude Code session, so the transcript shows who
  issued them.
- The assistant may **prepare** these commands but MUST NOT send them.
- The assistant sends only `AGENT`-typed requests (`actorIdentity: claude-code`): implementation
  evidence, and submitting a requirement, and only when the human asks.
- Automated tests use `HUMAN` actors as **labeled test fixtures**. They are not live decisions.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: US1–US6 from spec.md; no label for Setup, Foundational and Polish
- Paths: `src/main/java/com/agentic/shortener/...` (abbreviated **`main/`**) and
  `src/test/java/com/agentic/shortener/...` (abbreviated **`test/`**); resources under
  `src/main/resources/` and `src/test/resources/`

## Path Conventions

Single Maven module at repository root (plan §Project Structure, ADR-0001).

---

## Phase 1: Setup (Shared Infrastructure) — plan slice 1

**Purpose**: walking skeleton that builds, starts, and reports health. The first build downloads
uncached artifacts such as `spring-boot-starter-data-jpa` and Hibernate; later builds run offline.

- [X] T001 Create `pom.xml`:
  - coordinates `com.agentic:url-shortener`; Java 21;
  - parent `spring-boot-starter-parent` **3.5.16** (pinned; verified present in the local Maven cache
    on 2026-10-02);
  - dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
    `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `flyway-core`, `com.h2database:h2`
    (runtime), and `spring-boot-starter-test` (test). No other dependencies (ADR-0001, DEP-01 list);
  - Surefire excludes JUnit tag `measurement` by default.
- [X] T002 Add the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`)
  pinned to Maven 3.9.x.
- [X] T003 [P] Create `.gitignore` with `target/`, `data/`, `*.mv.db`, `*.trace.db` and IDE folders
  (`.idea/`, `.vscode/`, `*.iml`).
- [X] T004 [P] Create `.gitattributes` normalizing text files (`* text=auto eol=lf`; `*.cmd text eol=crlf`), to
  stop the CRLF warnings seen in earlier commits.
- [X] T005 Write the failing test `test/ApplicationSmokeTest.java`: the context loads, and
  `GET /actuator/health` returns 200 `{"status":"UP"}`. Run it and record the failure (no
  application class yet).
- [X] T006 Create `main/ShortenerApplication.java` (a plain `@SpringBootApplication`
  bootstrap; no orchestration logic).
- [X] T007 Create `src/main/resources/application.yml` with:
  - datasource `jdbc:h2:file:./data/shortener`;
  - `spring.jpa.hibernate.ddl-auto=validate`; Flyway enabled;
  - `management.endpoints.web.exposure.include=health`;
  - `server.error.include-stacktrace=never`;
  - `workflow.fault-injection.enabled=false`, `workflow.stage-timeout=5s`,
    `workflow.retry.max-attempts=3`, `workflow.retry.backoff=100ms,200ms`.
- [X] T008 [P] Create `src/main/resources/application-demo.yml` (only
  `workflow.fault-injection.enabled=true`) and `src/test/resources/application-test.yml`
  (in-memory H2 `jdbc:h2:mem:…;DB_CLOSE_DELAY=-1`, `workflow.stage-timeout=300ms`).
- [X] T009 Run `./mvnw verify`. `ApplicationSmokeTest` passes. Then rerun with `./mvnw -o verify` to
  confirm offline builds work once the cache is populated.
- [X] T010 Create `docs/traceability/matrix.md` with its column header (Requirement · Scenario · ADR · Task · Code · Test · Executed command/result · Evidence) and the Phase 1 rows (FR-URL-014 health via `ApplicationSmokeTest`, executed tests only). Constitution §Development Workflow.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `chore: add Spring Boot walking skeleton`.

---

## Phase 2: Foundational (Blocking Prerequisites) — plan slice 2, part 1

**Purpose**: the orchestration persistence, graph, engine, audit and actor model that every story
needs. ⚠️ No user-story work starts before this phase is green.

### Tests first

- [X] T011 [P] Write `test/common/ProblemDetailsTest.java`. Error responses must be
  `application/problem+json` with `type`, `title`, `status` and `category`, and no stack trace. The
  categories are the OpenAPI enum `VALIDATION, NOT_FOUND, EXPIRED, CONFLICT, INVALID_STATE,
  STALE_PLAN_VERSION, EVIDENCE_SCOPE_MISMATCH, CHANGE_CONTROL_REQUIRED, FAULT_INJECTION_DISABLED,
  REPLAN_FAILED, STORAGE_UNAVAILABLE, CODE_SPACE_EXHAUSTED, INTERNAL`.
  Traceability: FR-URL-015, NFR-001.
- [X] T012 [P] Write `test/PlaneBoundaryTest.java`: no class under `com.agentic.shortener.link`
  imports `com.agentic.shortener.workflow` (NFR-003, ADR-0001).
- [X] T013 [P] Write `test/workflow/engine/WorkflowGraphTest.java`. The graph has exactly the 14 nodes
  `INTAKE, UNDERSTAND, CLARIFICATION, DECOMPOSE, IMPACT_ANALYSIS, DESIGN, DESIGN_APPROVAL,
  IMPLEMENT, TEST, DOCS, SECURITY, RELEASE_READINESS, RELEASE_APPROVAL, FINAL_REPORT` with these
  properties:
  - the dependencies are as plan §Workflow DAG;
  - it is acyclic;
  - kinds: `CLARIFICATION`/`DESIGN_APPROVAL`/`RELEASE_APPROVAL` = `HUMAN_GATE`,
    `IMPLEMENT` = `EXTERNAL_ACTION`, the rest `AUTOMATED`;
  - `CLARIFICATION` and `IMPACT_ANALYSIS` are conditional;
  - `TEST`/`DOCS`/`SECURITY` depend only on `IMPLEMENT`;
  - `RELEASE_READINESS` depends on all three.

  (FR-ORC-001/002/004/005)
- [X] T014 [P] Write `test/workflow/audit/AuditServiceTest.java`:
  - events get a per-run increasing `seq`;
  - each carries `run_id`, `correlation_id`, `actor_type`, `actor_identity`, `plan_version`,
    `policy_version` and `injected`;
  - append-only means the application has **only an append write path**:
    - `AuditService` is the single writer and only inserts new events;
    - `AuditEventRepository` (by reflection, including inherited methods) offers no `delete*`
      method and no update query;
    - the `AuditEvent` entity is immutable (Hibernate `@Immutable`, columns `updatable = false`,
      no setters), so an existing record cannot be changed through JPA;
    - the only HTTP route over events is `GET /api/workflows/{id}/events`;
  - **concurrency (H1)**: 3 threads appending events to the same run at once produce unique,
    gap-free `seq` values (no `UNIQUE(run_id, seq)` violation).

  (FR-OBS-001/002)
- [X] T015 [P] Write `test/workflow/engine/ActorValidatorTest.java`:
  - `SYSTEM` is never accepted from the API;
  - human gates, clarification, rework, termination, requirement change, policy exceptions and
    resume accept only `HUMAN`;
  - `AGENT` is accepted only for submitting a requirement and for implementation evidence;
  - blank identities are refused;
  - `workflow-engine`/`system` are refused for HUMAN/AGENT;
  - `claude-code` is refused as HUMAN.

  (ADR-0004 §1, FR-HUM-006)
- [X] T016 [P] Write `test/workflow/engine/WorkflowEngineTest.java` using stub executors:
  - sequential order;
  - a node starts only when every dependency is `SUCCEEDED`/`SKIPPED`;
  - conditional nodes are `SKIPPED`, with a `BRANCH` decision and a `BRANCH_TAKEN` event;
  - entry/exit conditions are enforced;
  - stub executors that sleep 200 ms in `TEST`/`DOCS`/`SECURITY` produce overlapping persisted
    intervals on distinct threads;
  - the join waits for all three;
  - the engine stops at a `HUMAN_GATE` (`BLOCKED`, run `AWAITING_APPROVAL`) and at `IMPLEMENT`
    (`BLOCKED`, run `AWAITING_IMPLEMENTATION`);
  - a conditional `PENDING→RUNNING` update prevents double execution under concurrent advance calls;
  - **thread ownership (H1)**: stub executors running on pool threads perform no database or audit
    writes; all stage-row and event writes happen on the coordinating thread, so a parallel wave
    causes no optimistic-lock conflict on `workflow_run`;
  - **transaction boundaries (H2)**: while a stub executor is still running, its stage is visible
    as `RUNNING` from a separate connection (the claim committed before execution);
  - **busy run (H4)**: a second advance or command on a run whose lock is held returns
    immediately with `409 CONFLICT` ("run busy"), without waiting.

  (FR-ORC-003..008, SC-002, ADR-0003)
- [X] T017 [P] Write `test/workflow/rules/CapabilityRegistryTest.java`:
  - every `IMPLEMENTED` entry references existing classes and test files;
  - every entry lists vocabulary, requirement IDs and recorded behavior statements B1..Bn exactly
    as the research R6 table;
  - initially all capabilities are `PLANNED` (CREATE_LINK, REDIRECT, ANALYTICS, IDEMPOTENCY,
    EXPIRATION).

  (Research R6)
- [X] T018 Run `./mvnw test` for T011–T017 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 2 checkpoint.

### Implementation

- [X] T019 [P] Create `main/common/ErrorCategory.java` (the enum from T011) and
  `main/common/GlobalExceptionHandler.java`, which maps exceptions to `ProblemDetail` with
  `category` and no stack traces (FR-URL-015).
- [X] T020 Create `src/main/resources/db/migration/V1__workflow_tables.sql` with the five control-plane
  tables exactly as in data-model.md:
  - `workflow_run`:
    - `id` UUID PK; `correlation_id` VARCHAR(64);
    - `original_requirement` VARCHAR(4000) NOT NULL; `current_requirement` VARCHAR(8000);
      `normalized_json` CLOB;
    - `change_type` VARCHAR(16); `status` VARCHAR(32); `pending_action` VARCHAR(64);
    - `plan_version` INT; `policy_version` VARCHAR(16); `recoverable` BOOLEAN;
      `stop_reason` VARCHAR(1000); `fault_plan_json` CLOB;
    - `created_at`/`updated_at`/`ended_at` TIMESTAMP WITH TIME ZONE; `version` BIGINT.
  - `workflow_stage`:
    - PK (`run_id`, `node`); `node` VARCHAR(32); `status` VARCHAR(16); `attempts` INT;
    - `started_at`/`ended_at`; `thread_name` VARCHAR(64); `output_json` CLOB;
      `provenance` VARCHAR(16);
    - `failure_class` VARCHAR(16); `failure_code` VARCHAR(32); `failure_reason` VARCHAR(1000);
      `plan_version` INT.
  - `decision`:
    - `id` identity; `run_id` UUID FK; `type` VARCHAR(32); `gate` VARCHAR(48);
    - `actor_type` VARCHAR(8) NOT NULL; `actor_identity` VARCHAR(100) NOT NULL;
      `reason` VARCHAR(2000) NOT NULL;
    - `plan_version` INT; `supersedes_id` BIGINT FK; `payload_json` CLOB; `created_at`.
  - `policy_evaluation`:
    - `id` identity; `run_id` UUID FK; `policy_version` VARCHAR(16); `check_id` VARCHAR(16);
    - `domain` VARCHAR(32); `node` VARCHAR(32); `mandatory` BOOLEAN; `result` VARCHAR(24);
    - `reason` VARCHAR(2000); `plan_version` INT; `resolution_decision_id` BIGINT FK; `created_at`.
  - `audit_event`:
    - `id` identity; `run_id` UUID FK; `correlation_id` VARCHAR(64); `seq` INT,
      UNIQUE(`run_id`,`seq`);
    - `type` VARCHAR(40); `node` VARCHAR(32);
    - `actor_type` VARCHAR(8); `actor_identity` VARCHAR(100);
    - `plan_version` INT; `policy_version` VARCHAR(16); `injected` BOOLEAN; `payload_json` CLOB;
      `created_at`.
  Traceability: FR-ORC-006, ADR-0002.
- [X] T021 [P] Create the enums under `main/workflow/engine/`:
  - `Node` (14 values);
  - `NodeKind` (`AUTOMATED`, `HUMAN_GATE`, `EXTERNAL_ACTION`);
  - `RunStatus` (`RUNNING`, `AWAITING_CLARIFICATION`, `AWAITING_APPROVAL`,
    `AWAITING_IMPLEMENTATION`, `AWAITING_REWORK`, `SAFE_STOPPED`, `COMPLETED`, `FAILED`);
  - `StageStatus` (`PENDING`, `RUNNING`, `BLOCKED`, `SUCCEEDED`, `FAILED`, `SKIPPED`);
  - `FailureClass` (`TRANSIENT`, `PERMANENT`);
  - `Provenance` (`ACTUAL`, `EXTERNAL`, `FALLBACK`);
  - `ActorType` (`SYSTEM`, `HUMAN`, `AGENT`);
  - `DecisionType` (the data-model list);
  - `AuditEventType` (the data-model event catalog, including `REPLAN_ABORTED`).

  (FR-ORC-011)
- [X] T022 [P] Create the JPA entities and repositories under `main/workflow/persistence/`:
  `WorkflowRun` (with `@Version`), `WorkflowStage`, `Decision`, `PolicyEvaluation`, `AuditEvent`. For
  the append-only audit trail (ADR-0002), use the simplest approach:
  - `AuditEvent` is annotated `@org.hibernate.annotations.Immutable`, with all columns
    `updatable = false` and no setters;
  - `AuditEventRepository` extends the marker interface `org.springframework.data.repository.Repository<AuditEvent, Long>`
    (not `JpaRepository`/`CrudRepository`), so it inherits no delete or update methods;
  - it declares only `AuditEvent save(AuditEvent newEvent)`, used solely by `AuditService` for new
    events, plus finder methods.
- [X] T023 Implement `main/workflow/audit/AuditService.java`: append-only writes with per-run `seq`
  and copied correlation/plan/policy version; `SYSTEM`/`workflow-engine` for engine events. `seq`
  allocation is serialized per run (an in-JVM per-run lock around read-max-plus-one and insert, in its
  own short transaction) as a safety net, even though writes normally come only from the
  coordinating thread (H1).
  Traceability: per T014; FR-OBS-001/002.
- [X] T024 [P] Implement `main/workflow/engine/Actor.java` (record `actorType`, `actorIdentity`) and
  `main/workflow/engine/ActorValidator.java` per T015.
- [X] T025 Implement `main/workflow/engine/WorkflowGraph.java` per T013: a static node definition
  with dependencies, kind, conditional flag and entry/exit-condition hooks.
- [X] T026 Implement `main/workflow/engine/StageExecutor.java` (interface `Node node(); StageResult
  execute(StageContext ctx)`), `StageContext.java` (run, persisted upstream outputs, and the
  attempt's cancellation token; H3) and
  `StageResult.java` (success output + provenance | failure class/code/reason).
  Traceability: per T016; FR-ORC-009, FR-ORC-014.
- [X] T027 Implement `main/workflow/engine/WorkflowEngine.java`:
  - wave scheduling on a fixed thread pool;
  - eligibility = `PENDING` and all dependencies `SUCCEEDED`/`SKIPPED`;
  - per-run `ReentrantLock` taken with **`tryLock()` (no wait)**: if it is held ⇒ `409 CONFLICT`
    "run busy" (H4); plus the optimistic `@Version` and the conditional `PENDING→RUNNING` update;
  - **thread ownership (H1)**: pool threads only execute stage logic and return a `StageResult`,
    with start/end timestamps and thread name captured by the worker. Every database and audit
    write — stage claim, completion, failure, retry events, run-status changes — happens on the
    coordinating thread that holds the run lock;
  - **transaction boundaries (H2)**: commands and advancement are **not** wrapped in one surrounding
    transaction (no `@Transactional` on controllers or the advance loop). Each step is its own short
    transaction:
    - (a) claim `PENDING→RUNNING` and `STAGE_STARTED`, committed before the stage is submitted;
    - (b) on success, output + `SUCCEEDED` + events in one completion transaction;
    - (c) on failure, attempt rollback + events.

    The replan transaction (T103) is the only multi-step transaction;
  - start/end time and thread name recorded, with `STAGE_*` events;
  - stops at `HUMAN_GATE` (`BLOCKED`, `AWAITING_*`) and at `EXTERNAL_ACTION` (`BLOCKED`,
    `AWAITING_IMPLEMENTATION`, `IMPLEMENTATION_REQUESTED`);
  - `pending_action` set (FR-ORC-003..009, ADR-0003).
- [X] T028 Implement `main/workflow/rules/CapabilityRegistry.java` per T017: five capabilities, all
  `PLANNED`, each with the R5 vocabulary and acceptance-probe identifiers, and with requirement IDs
  and recorded behavior statements B1..Bn **exactly as the research R6 table**:
  - CREATE_LINK → FR-URL-001..005, 013, 016;
  - REDIRECT → FR-URL-006, 007, 013, 017;
  - ANALYTICS → FR-URL-010, 012;
  - IDEMPOTENCY → FR-URL-011;
  - EXPIRATION → FR-URL-008, 009.
- [X] T029 Run `./mvnw verify`. T011–T017 pass (record red → green).
- [X] T030 Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 2 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `feat: add workflow persistence, DAG and engine core`.

---

## Phase 3: User Story 1 (part A) — Govern a clear requirement up to the implementation wait (P1) — plan slice 2, part 2

**Goal**: submit a requirement and drive it through intake, understanding, branching, decomposition,
design, policy and human design approval, until it waits for external implementation.

**Independent test**: `POST /api/workflows` with the SCN-A text ⇒ `GREENFIELD`, both conditional
nodes `SKIPPED`, `AWAITING_APPROVAL`; HUMAN approve ⇒ `AWAITING_IMPLEMENTATION`; an `AGENT`
approval is refused.

### Tests first

- [ ] T031 [P] [US1] Write `test/workflow/rules/AmbiguityRulesTest.java`:
  - SCN-A text ⇒ no findings;
  - SCN-B text ⇒ no findings;
  - "Make links expire." ⇒ AMB-R2 and AMB-R4;
  - a "never expire … expire" conflict ⇒ AMB-R3;
  - a requirement missing only edge-case detail ⇒ no findings;
  - each finding records rule id, matched text and explanation.

  (FR-ORC-016)
- [ ] T032 [P] [US1] Write `test/workflow/stages/UnderstandAndDecomposeTest.java`:
  - normalization;
  - capability mapping;
  - change type exactly per research R6:
    - `BROWNFIELD` if the normalized requirement contains `existing`, or a behavior change verb
      (`add`, `change`, `modify`, `replace`, `remove`, `extend`, `introduce`, `increase`,
      `decrease`, `convert`, `migrate`) together with ≥ 1 `IMPLEMENTED` matched capability;
    - otherwise `GREENFIELD`;
    - fixtures: SCN-A ⇒ `GREENFIELD` (capabilities `PLANNED`); SCN-B ⇒ `BROWNFIELD`;
      clarified SCN-C ⇒ `BROWNFIELD`;
  - DECOMPOSE produces ≥ 1 task per capability, each with an acceptance check and requirement IDs;
  - an unknown capability ⇒ `PERMANENT` failure (`INVALID_INPUT`).
- [ ] T033 [P] [US1] Write `test/workflow/stages/ImpactAnalysisAndDesignTest.java`:
  - the impact report populates all 10 FR-SCN-002 areas;
  - DESIGN lists components, interface/data changes, the test plan, dependencies, a
    security-sensitivity flag, the run's `requirementIds`, `implementationRequired` and
    `changesApprovedRequirements`.
- [ ] T034 [P] [US1] Write `test/workflow/policy/PolicyV1Test.java`:
  - PRIV-01 `PASS` / `EXCEPTION_REQUESTED` (visitor IP, email, location);
  - SEC-01 `FAIL` for "allow javascript: URLs" ⇒ run `SAFE_STOPPED`, non-recoverable;
  - CHG-01 `NOT_APPLICABLE` (greenfield) / `PASS` / `FAIL` (brownfield);
  - DEP-01 `NOT_APPLICABLE` / `PASS` / `FAIL`;
  - every evaluation records policy version `v1`, check id, node, mandatory, result and reason.

  (FR-POL-001..004)
- [ ] T035 [P] [US1] Write `test/workflow/api/HumanGateTest.java`:
  - no progress past a gate without a decision;
  - approval at `DESIGN_APPROVAL` by `HUMAN` succeeds;
  - `AGENT`, `SYSTEM`, blank and reserved actors get `400`/`409` plus `DECISION_REFUSED`;
  - wrong gate or stale `planVersion` ⇒ `409` + `DECISION_REFUSED`, with state unchanged;
  - reject ⇒ `REJECTION` decision, `APPROVAL_REJECTED` event, `AWAITING_REWORK`;
  - terminate (HUMAN) from every `AWAITING_*` state ⇒ `FAILED` with a `TERMINATION` decision;
  - a duplicate concurrent approval ⇒ the second gets `409` (CHK035);
  - a command sent while the run is busy advancing ⇒ immediate `409 CONFLICT` "run busy", not a
    blocked request (H4).

  (FR-HUM-001..007, SC-003, CHK002, NFR-009)
- [ ] T036 [P] [US1] Write `test/workflow/api/ImplementationEvidenceTest.java`:
  - accepted from `HUMAN` or `AGENT` only while `AWAITING_IMPLEMENTATION` at the current plan
    version;
  - non-empty `changedArtifacts` without `revision` ⇒ `400`;
  - empty `changedArtifacts` without `noChangeJustification` ⇒ `400`;
  - `noChangeJustification` while `implementationRequired = true` ⇒ `400`;
  - a `requirementId` outside the run's set ⇒ `409 EVIDENCE_SCOPE_MISMATCH` + `DECISION_REFUSED`;
  - on success, `IMPLEMENT` is `SUCCEEDED` with provenance `EXTERNAL` (FR-ORC-014), and coverage of the
    designed components is recorded;
  - evidence before `DESIGN_APPROVAL` ⇒ refused;
  - the stored evidence `created_at` is later than the valid approval decision's `created_at`.

  (ADR-0004 §3, CHK004, CHK024)
- [ ] T037 [P] [US1] Write `test/workflow/api/WorkflowApiTest.java` (MockMvc against
  contracts/openapi.yaml):
  - `POST /api/workflows` ⇒ 201 `Run`, with `X-Correlation-Id` honored;
  - `GET /api/workflows/{id}` returns status, `pendingAction`, plan/policy version and every node
    with kind, `dependsOn`, conditional and status;
  - `GET …/events` and `GET …/decisions` are ordered;
  - unknown id ⇒ 404 problem. (NFR-005)
- [ ] T038 [US1] Run `./mvnw test` for T031–T037 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 3 checkpoint.

### Implementation

- [ ] T039 [P] [US1] Implement `main/workflow/rules/AmbiguityRules.java` using **exactly** the
  normalization, capability terms, outcome verbs, R2 parameter patterns, R3 conflict pairs and R4
  change verbs/qualifiers listed in research R5. Nothing beyond those lists (CHK011).
- [ ] T040 [P] [US1] Implement `main/workflow/stages/IntakeExecutor.java` (non-blank, ≤ 4000 chars)
  and `UnderstandExecutor.java` (normalize, capabilities, findings, change type (exact research R6 rule), `REQUIREMENT_NORMALIZED`,
  `AMBIGUITY_DETECTED`, branch decision for `CLARIFICATION`).
- [ ] T041 [P] [US1] Implement `main/workflow/stages/DecomposeExecutor.java` (tasks with acceptance
  checks and requirement IDs; fixes the run's requirement-ID set) and `ImpactAnalysisExecutor.java`
  (10-area report from the registry; branch decision).
  Traceability: per T032/T033; FR-ORC-002, FR-SCN-002.
- [ ] T042 [US1] Implement `main/workflow/stages/DesignExecutor.java` per T033. Set
  `implementationRequired` exactly per research R6: `false` only if all matched capabilities are
  `IMPLEMENTED`, there is no R6 behavior change verb, and none of the R6 out-of-record detail
  patterns is present; otherwise `true`.
- [ ] T043 [US1] Implement `main/workflow/policy/PolicyCatalog.java` (version `v1`: PRIV-01, SEC-01,
  CHG-01, DEP-01, AUD-01, each with domain and mandatory flag; the PRIV-01 term list and the DEP-01
  approved dependency list with licenses **exactly** as in research R11 (CHK018)) and
  `PolicyEvaluator.java`:
  - checks are evaluated after their bound node and persisted;
  - mandatory `FAIL` ⇒ safe-stop;
  - `EXCEPTION_REQUESTED` ⇒ node `BLOCKED`, run `AWAITING_APPROVAL`,
    `pendingAction = EXCEPTION:<checkId>`.
- [ ] T044 [US1] Implement `main/workflow/engine/DecisionService.java`:
  - approve, reject and terminate with actor validation, gate/plan-version checks,
    `DECISION_REFUSED` recording and decision lineage;
  - terminate from any `AWAITING_*` (compensation hook is a no-op until Phase 6).

  (FR-ORC-010)
- [ ] T045 [US1] Implement `main/workflow/engine/ImplementationEvidenceService.java` per T036.
- [ ] T046 [US1] Implement `main/workflow/api/WorkflowController.java` and request/response records for
  `POST /api/workflows`, `GET /api/workflows/{id}`, `GET …/events`, `GET …/decisions`,
  `POST …/approve`, `POST …/reject`, `POST …/terminate` and `POST …/implementation`, exactly as in
  contracts/openapi.yaml (FR-ORC-015).
- [ ] T047 [US1] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T048 [US1] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 3 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.
- [ ] T049 [US1] **HUMAN** (records in `docs/scenarios/README.md`): start the app with the default profile on the local demonstration data directory `./data` (not the `demo` profile), and submit the SCN-A requirement
  ("Create a short link for a valid HTTP/HTTPS address, redirect to the original address, and record
  redirect count and last redirect time.") as `HUMAN`/`candidate`. Review the design, then
  **approve `DESIGN_APPROVAL`** (plan version 1). The run now waits in `AWAITING_IMPLEMENTATION`.
  Note the run id in `docs/scenarios/README.md` (create the file with only this fact).

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `feat: add governed workflow up to external implementation
wait`.

---

## Phase 4: User Story 2 — Shorten, resolve and measure links (P1) = SCN-A implementation — plan slice 3

**Goal**: the core URL shortener **without expiration**, implemented only after the SCN-A design
approval in T049.

**Independent test**: create ⇒ 201 with a 7-char code; redirect ⇒ 302 `no-store` and the count
increments; unknown ⇒ 404; unsafe URL ⇒ 400; idempotency 201/200/409; 50 + 50 concurrency.

### Tests first

- [ ] T050 [P] [US2] Write `test/link/UrlValidatorTest.java` (parameterized):
  - accepts absolute `http`/`https` with a host;
  - rejects `javascript:`, `file:`, `data:`, other schemes, missing host, and length > 2,048;
  - rejects `localhost`/`*.localhost` and literals in `0.0.0.0/8`, `127/8`, `10/8`, `172.16/12`,
    `192.168/16`, `169.254/16`, `::`, `::1`, `fc00::/7`, `fe80::/10` and IPv4-mapped forms;
  - rejects non-canonical numeric hosts (e.g. `2130706433`);
  - a hostname is never resolved (no DNS).

  (FR-URL-002/003/016, PVT-007, SC-008, NFR-001)
- [ ] T051 [P] [US2] Write `test/link/ShortCodeGeneratorTest.java` (7 Base62 characters) and
  `test/link/LinkServiceCollisionTest.java`. Using a stub generator: a collision regenerates; 5
  collisions ⇒ `CODE_SPACE_EXHAUSTED` and no link (FR-URL-004/005, PVT-006).
- [ ] T052 [P] [US2] Write `test/link/LinkApiTest.java` (MockMvc):
  - `POST /api/links` ⇒ 201 `Link`;
  - `GET /r/{code}` ⇒ 302 `Location` + `Cache-Control: no-store`, with `redirectCount` +1 and
    `lastRedirectAt` set;
  - unknown ⇒ 404 `NOT_FOUND`;
  - `GET /api/links/{code}` returns analytics;
  - `Idempotency-Key`: same body ⇒ 200 with the same code; different body ⇒ 409 `CONFLICT`; no key
    ⇒ new link.

  (FR-URL-001, 006, 007, 010, 011, 015)
- [ ] T053 [P] [US2] Write `test/link/LinkStorageFailureTest.java` (mocked repository):
  - create and redirect ⇒ 503 `STORAGE_UNAVAILABLE`, never 404/410;
  - an analytics-update failure still redirects 302 and is logged;
  - health is `DOWN` when the datasource fails.

  (FR-URL-013, 014, 017)
- [ ] T054 [P] [US2] Write `test/link/LinkConcurrencyTest.java`: 50 concurrent creates ⇒ 50 distinct
  codes; 50 concurrent redirects of one link ⇒ `redirectCount = 50` (FR-URL-012, PVT-008, SC-009).
- [ ] T055 [US2] Run `./mvnw test` for T050–T054 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 4 checkpoint.

### Implementation

- [ ] T056 [US2] Create `src/main/resources/db/migration/V2__links.sql`:
  - `link`:
    - `id` BIGINT identity PK;
    - `code` VARCHAR(16) NOT NULL UNIQUE;
    - `original_url` VARCHAR(2048) NOT NULL;
    - `created_at` TIMESTAMP WITH TIME ZONE NOT NULL;
    - `redirect_count` BIGINT NOT NULL DEFAULT 0;
    - `last_redirect_at` TIMESTAMP WITH TIME ZONE NULL;
    - `probe_run_id` UUID NULL;
    - **no `expires_at`**.
  - `idempotency_record`:
    - `idem_key` VARCHAR(100) PK;
    - `request_fingerprint` CHAR(64);
    - `link_id` BIGINT FK → `link.id`;
    - `created_at` NOT NULL.
  Traceability: FR-URL-001/004/010/011, ADR-0002.
- [ ] T057 [P] [US2] Create `main/link/Link.java`, `IdempotencyRecord.java`, `LinkRepository.java`
  (atomic `@Modifying` query `UPDATE link SET redirect_count = redirect_count + 1, last_redirect_at
  = :now WHERE id = :id`; `deleteByProbeRunId`) and `IdempotencyRepository.java`.
  Traceability: per T052/T054; FR-URL-010/011/012.
- [ ] T058 [P] [US2] Implement `main/link/UrlValidator.java` per T050, using `java.net.URI` and
  literal-only IP parsing.
- [ ] T059 [P] [US2] Implement `main/link/ShortCodeGenerator.java` (interface) and
  `SecureRandomShortCodeGenerator.java` (7-char Base62).
  Traceability: per T051; FR-URL-004, PVT-006.
- [ ] T060 [US2] Implement `main/link/LinkService.java`:
  - create with idempotency (SHA-256 fingerprint of the canonical `url`; key row + link in one
    transaction; unique-key race resolved by re-read);
  - collision retry ≤ 5;
  - resolve (active / not found);
  - record redirect (analytics failure logged, redirect still allowed);
  - `createProbeLink(url, runId)`, `deleteProbeLinks(runId)`.
  Traceability: per T050–T054; FR-URL-001..013, FR-URL-017.
- [ ] T061 [US2] Implement `main/link/LinkController.java` (`POST /api/links`,
  `GET /api/links/{code}`) and `main/link/RedirectController.java` (`GET /r/{code}` ⇒ 302,
  `Cache-Control: no-store`). Map data-access failures to 503 in `GlobalExceptionHandler`.
  Traceability: per T052/T053; FR-URL-006/007/013/015.
- [ ] T062 [US2] Update `main/workflow/rules/CapabilityRegistry.java`: CREATE_LINK, REDIRECT,
  ANALYTICS and IDEMPOTENCY become `IMPLEMENTED`, with real component classes, endpoints, migration
  `V2__links.sql`, test classes and acceptance probes. EXPIRATION stays `PLANNED`.
  `CapabilityRegistryTest` passes.
  Traceability: per T017; research R6.
- [ ] T063 [US2] Run `./mvnw verify`. All tests green, including the boundary test (record red → green).
- [ ] T064 [US2] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 4 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `feat: implement core URL shortener (SCN-A implementation)`.
Record the commit id. The live SCN-A run is still waiting.

---

## Phase 5: User Story 1 (part B) — Validation, release and completion (P1) — plan slice 4

**Goal**: implementation evidence unblocks the parallel `TEST` ‖ `DOCS` ‖ `SECURITY` group, followed
by the join, release approval and final report. SCN-A completes.

**Independent test**: `ScenarioATest` end to end. Live SCN-A reaches `COMPLETED`, with overlapping
parallel intervals and no remaining probe links.

### Tests first

- [ ] T065 [P] [US1] Write `test/workflow/stages/TestStageExecutorTest.java`:
  - probes for each requested capability, through `LinkService`, with probe links tagged
    `probe_run_id`;
  - on success the probe links are deleted and the evidence stays in the output (provenance
    `ACTUAL`, FR-ORC-014);
  - a failing probe ⇒ `PERMANENT` with `failure_code = IMPLEMENTATION_DEFECT`.
- [ ] T066 [P] [US1] Write `test/workflow/stages/SecurityAndDocsExecutorTest.java`:
  - SECURITY probes the validator with the unsafe set from T050; any acceptance ⇒
    `IMPLEMENTATION_DEFECT`;
  - DOCS produces the required sections from design, registry and evidence (provenance `ACTUAL`).
  Traceability: FR-URL-003/016, NFR-001, FR-ORC-004.
- [ ] T067 [P] [US1] Write `test/workflow/stages/ReleaseReadinessAndReportTest.java`:
  - readiness fails on an unresolved mandatory policy issue or an unapproved exception;
  - AUD-01 `PASS`/`FAIL`;
  - every task needs a passing check;
  - residual risks include designed components missing from the evidence;
  - readiness fails if any `link` row is still tagged with the run's `probe_run_id` (H3);
  - FINAL_REPORT is idempotent (same report on re-run), cites audit `seq` numbers, and the run is
    `COMPLETED`.

  (FR-POL-006, FR-OBS-006, NFR-006)
- [ ] T068 [P] [US1] Write `test/workflow/ImplementationDefectRoutingTest.java`: a `TEST`/`SECURITY`
  `IMPLEMENTATION_DEFECT` after accepted evidence ⇒ run `AWAITING_REWORK`, with
  `pendingAction = REWORK_OR_TERMINATE`; terminate ⇒ `FAILED` (CHK036; rework is covered in Phase 7).
- [ ] T069 [P] [US1] Write `test/scenario/ScenarioATest.java` (MockMvc, test-fixture evidence
  labeled as fixture):
  - full SCN-A path, with both branches `SKIPPED`;
  - HUMAN design approval, then evidence (changed artifacts + revision);
  - `TEST`/`DOCS`/`SECURITY` scheduled in the same wave on distinct threads, then the join, then HUMAN
    release approval with `acceptedRisks`. Interval overlap itself is proven in T016 (stub
    instrumentation) and T079 (injected `DELAY`, CHK027);
  - `COMPLETED`, report available;
  - no `link` rows with `probe_run_id` remain.

  (FR-SCN-001, SC-001, SC-002)
- [ ] T070 [US1] Run `./mvnw test` for T065–T069 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 5 checkpoint.

### Implementation

- [ ] T071 [P] [US1] Implement `main/workflow/stages/TestStageExecutor.java` per T065. Before each
  probe-link creation, check the attempt's cancellation token (from `StageContext`), and stop
  without further side effects if it is revoked (H3).
- [ ] T072 [P] [US1] Implement `main/workflow/stages/SecurityExecutor.java` and `DocsExecutor.java`
  per T066 (no fallback yet).
- [ ] T073 [P] [US1] Implement `main/workflow/stages/ReleaseReadinessExecutor.java` (with AUD-01) and
  `FinalReportExecutor.java` per T067.
- [ ] T074 [US1] Add `IMPLEMENTATION_DEFECT` routing to `AWAITING_REWORK` in `WorkflowEngine.java`
  per T068.
- [ ] T075 [US1] Add `GET /api/workflows/{id}/report` to `WorkflowController.java` (409 until
  `FINAL_REPORT` has succeeded).
  Traceability: FR-OBS-006, NFR-006, FR-ORC-015.
- [ ] T076 [US1] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T077 [US1] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 5 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.
- [ ] T078 [US1] **HUMAN** (records in `docs/scenarios/README.md`): restart the app on the new code (the SCN-A run is still waiting). Record
  SCN-A implementation evidence via `POST …/implementation`:
  - changed artifacts from Phase 4;
  - the Phase 4 commit id as `revision`;
  - requirement IDs within the run's set.

  Inspect the parallel overlap, then **approve `RELEASE_APPROVAL`** with `acceptedRisks`. The run
  reaches `COMPLETED`.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `feat: add parallel validation, release readiness and
final report`.

---

## Phase 6: User Story 3 — Recover safely from failures (P2) — plan slice 5

**Goal**: bounded retry, timeout, DOCS fallback, attempt rollback vs compensation, safe-stop,
resume, startup recovery, incident events and gated fault injection.

**Independent test**: with fault injection enabled by test configuration, every row of the
quickstart recovery table behaves as specified.

### Tests first

- [ ] T079 [P] [US3] Write `test/workflow/engine/FaultInjectionConfigTest.java`:
  - default config ⇒ a run with `faults` gets `400 FAULT_INJECTION_DISABLED`;
  - with `workflow.fault-injection.enabled=true` it is accepted;
  - injected effects carry `injected=true`;
  - faults are allowed on automated nodes only;
  - for a run created with the **SCN-A requirement text**, with `DELAY` injected on the real `TEST`,
    `DOCS` and `SECURITY` executors, their persisted intervals overlap (max start < min end) on
    distinct threads.

  (CHK038, FR-REL-011, SC-002/CHK027)
- [ ] T080 [P] [US3] Write `test/workflow/engine/RetryTimeoutTest.java`:
  - TRANSIENT ×1 ⇒ 2 attempts and success, with `RETRY_SCHEDULED`;
  - PERMANENT ⇒ no retry;
  - TRANSIENT ×3 ⇒ `RETRY_EXHAUSTED` ⇒ `SAFE_STOPPED` (recoverable);
  - TIMEOUT ⇒ `STAGE_TIMED_OUT`, treated as transient;
  - gates and `IMPLEMENT` never time out;
  - backoff is 100 ms then 200 ms.

  (FR-REL-001..004, PVT-001/002, SC-004, NFR-002)
- [ ] T081 [P] [US3] Write `test/workflow/engine/FallbackTest.java`: DOCS TRANSIENT ×3 ⇒
  `FALLBACK_USED`, provenance `FALLBACK`, all required sections present; SECURITY never falls back
  (FR-REL-005, FR-ORC-014).
- [ ] T082 [P] [US3] Write `test/workflow/engine/RollbackCompensationTest.java`:
  - a TEST fault after probe creation ⇒ `ATTEMPT_ROLLED_BACK` (no output committed) and
    `COMPENSATION_STARTED/COMPLETED` (probe links deleted), then retry;
  - `COMPENSATION_FAILURE` ⇒ `COMPENSATION_FAILED` ⇒ `SAFE_STOPPED`, non-recoverable;
  - permanent non-defect failure ⇒ compensation ⇒ `FAILED`;
  - terminate runs the compensation sweep;
  - **timed-out attempt keeps working (H3)**: a TEST attempt whose probe creation is slowed past the
    timeout is cancelled. Its token is revoked, so it creates no further probe links; the sweep runs
    before the next attempt; and after the run completes, **zero** links tagged with the run remain.

  (FR-REL-006, ADR-0005 §5/6)
- [ ] T083 [P] [US3] Write `test/workflow/engine/SafeStopResumeTest.java`:
  - resume is HUMAN-only, and refused for non-recoverable or completed runs;
  - succeeded nodes are never re-executed;
  - with a SECURITY failure in the parallel group, resume re-runs only SECURITY;
  - state, reason, history and the recoverable flag are preserved.

  (FR-REL-007..010, SC-006)
- [ ] T084 [P] [US3] Write `test/workflow/RestartPersistenceTest.java` (two Spring contexts over one
  temp H2 file):
  - runs waiting at a gate and at `AWAITING_IMPLEMENTATION` are identical after restart and continue
    normally;
  - a stage left `RUNNING` ⇒ `ATTEMPT_ROLLED_BACK`, compensation sweep, run `SAFE_STOPPED`
    (`INTERRUPTED`, recoverable);
  - a run left `RUNNING` with **no** stage `RUNNING` ⇒ compensation sweep, run `SAFE_STOPPED`
    (`INTERRUPTED`, recoverable);
  - no stage is re-executed until HUMAN `resume`;
  - the `RUNNING` stage found at restart was committed by its own claim transaction, not lost in a
    surrounding transaction (H2).

  (FR-ORC-006, NFR-004, SC-005, CHK033)
- [ ] T085 [P] [US3] Write `test/workflow/engine/IncidentEventsTest.java`:
  - first failure ⇒ `FAILURE_DETECTED`;
  - `RECOVERY_STARTED` with mechanism `RETRY`/`FALLBACK`/`COMPENSATION`/`RESUME`/`REWORK`;
  - `RECOVERY_COMPLETED` on later success;
  - `RECOVERY_FAILED` when the run ends `FAILED` or non-recoverable `SAFE_STOPPED`.

  (FR-OBS-003)
- [ ] T086 [P] [US3] Write `test/workflow/DeterminismTest.java`:
  - run the same requirement, HUMAN decisions and fault plan twice;
  - assert an identical semantic stage path per research R20: nodes executed/skipped, branch
    outcomes, gate locations, failure class and code, requirement/capability decisions, and the
    final outcome;
  - ignore timestamps, run/correlation IDs, thread names, short codes, probe IDs and the order of
    events inside one parallel wave.

  It fails until fault injection exists (Phase 6). (FR-ORC-012, CHK015)
- [ ] T087 [US3] Run `./mvnw test` for T079–T086 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 6 checkpoint.

### Implementation

- [ ] T088 [P] [US3] Implement `main/workflow/engine/FaultInjector.java` (reads `fault_plan_json`;
  types `TRANSIENT|PERMANENT|TIMEOUT|DELAY|COMPENSATION_FAILURE`; fires after executor work and
  before the completion commit) and gate run creation on `workflow.fault-injection.enabled`.
  Traceability: per T079; FR-REL-011.
- [ ] T089 [US3] Implement `main/workflow/engine/RetryTimeoutRunner.java` (`Future.get(timeout)` plus
  cancel; transient-only retry; 3 attempts; 100/200 ms backoff; events) and wire it into
  `WorkflowEngine.java`. The runner lives on the **coordinating side** (H1): it submits each attempt
  to the pool, waits with the timeout, and writes retry/timeout events itself. Each attempt gets a
  fresh **cancellation token** that is revoked on timeout; a late result from a revoked attempt is
  always discarded (H3).
  Traceability: per T080; FR-REL-001..004, PVT-001/002.
- [ ] T090 [US3] Add the DOCS fallback template to `main/workflow/stages/DocsExecutor.java` per T081.
- [ ] T091 [US3] Implement `main/workflow/engine/CompensationService.java`: an idempotent sweep via
  `LinkService.deleteProbeLinks(runId)`, with events, and safe-stop on failure. Wire it to:
  - **before every TEST attempt**, including the first, so leftovers from a timed-out attempt are
    removed (H3);
  - a failed TEST attempt;
  - a permanent failure;
  - terminate;
  - `FAILED`/`SAFE_STOPPED`.
  Traceability: per T082; FR-REL-006.
- [ ] T092 [US3] Implement the safe-stop triggers and the recoverable flag in `WorkflowEngine.java`,
  plus `POST /api/workflows/{id}/resume` (HUMAN) in `WorkflowController.java`.
  Traceability: per T083; FR-REL-007..010.
- [ ] T093 [US3] Implement `main/workflow/engine/StartupRecovery.java`
  (`ApplicationReadyEvent`: inspect every non-terminal run. For a `RUNNING` run: roll back any
  `RUNNING` stage, run the idempotent compensation sweep, then `SAFE_STOPPED` (recoverable,
  `INTERRUPTED`). Leave `AWAITING_*` runs untouched. No automatic re-execution; CHK033).
- [ ] T094 [US3] Emit incident events in `main/workflow/engine/WorkflowEngine.java`,
  `CompensationService.java` and `DecisionService.java` (`FAILURE_DETECTED`, `RECOVERY_*`) from the engine, the
  compensation service and resume per T085.
- [ ] T095 [US3] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T096 [US3] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 6 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `feat: add bounded recovery, compensation, safe-stop and
resume`.

---

## Phase 7: Replanning (US5 core), policy exceptions and metrics (US6) — plan slice 6

**Goal**: atomic dependency-aware replanning (clarification, requirement change, rework), the
conflict-with-approved-requirement rule, policy exceptions, and demonstration metrics.

**Independent test**: replan and policy/metrics tests are green; the quickstart rework, invalidation
and policy-exception rows behave as specified.

### Tests first

- [ ] T097 [P] [US5] Write `test/workflow/engine/ReplannerTest.java`:
  - affected = from-node + descendants, everything else preserved;
  - `STAGE_INVALIDATED` keeps prior outputs;
  - `DECISION_INVALIDATED` for approvals and implementation evidence;
  - plan +1 and `PLAN_REPLANNED {old,new,reason,affected,preserved}`;
  - **atomicity**: an injected failure inside the replan transaction leaves the run, stages,
    decisions and links unchanged, records `REPLAN_ABORTED`, and the command returns
    `REPLAN_FAILED`.

  (FR-ORC-013, FR-POL-007, CHK007, SC-007)
- [ ] T098 [P] [US5] Write `test/workflow/api/ClarificationTest.java`:
  - `POST …/clarify` (HUMAN) while `AWAITING_CLARIFICATION` ⇒ `CLARIFICATION_RECEIVED`, replan from
    `UNDERSTAND`, `CLARIFICATION` kept `SUCCEEDED`;
  - a still-ambiguous clarification ⇒ another round (plan +1 each);
  - a clarification contradicting an approved behavior statement (e.g. "all links expire after 30
    days" vs FR-URL-008) ⇒ `409 CHANGE_CONTROL_REQUIRED` + `DECISION_REFUSED`;
  - stale plan ⇒ 409.

  (FR-SCN-003, CHK010, CHK034)
- [ ] T099 [P] [US5] Write `test/workflow/api/RequirementChangeAndReworkTest.java`:
  - requirement change after design approval ⇒ plan +1, approval invalidated, re-approval
    required; `changesApprovedRequirements` listed when applicable;
  - rework from `DOCS` after a release rejection ⇒ only `DOCS`/`RELEASE_READINESS`/`RELEASE_APPROVAL`
    re-run, with `IMPLEMENT`/`TEST`/`SECURITY` preserved;
  - rework from `IMPLEMENT` after `IMPLEMENTATION_DEFECT` ⇒ evidence invalidated, new evidence
    required, downstream re-validated;
  - rework node must be at or upstream of the rejected/failed node;
  - HUMAN only.

  (FR-HUM-005, FR-ORC-013, CHK036, NFR-008)
- [ ] T100 [P] [US6] Write `test/workflow/policy/PolicyExceptionTest.java`:
  - PRIV-01 `EXCEPTION_REQUESTED` blocks;
  - HUMAN approval requires `scope`, `compensatingControl` and `expiresOrReview`, and records
    `EXCEPTION_APPROVED` with all FR-POL-005 fields;
  - rejection ⇒ `SAFE_STOPPED`;
  - a dated expiry that has passed before `RELEASE_READINESS` ⇒ treated as unapproved, readiness
    fails;
  - AGENT is refused.

  (FR-POL-004/005/006, CHK006)
- [ ] T101 [P] [US6] Write `test/workflow/metrics/MetricsTest.java` over a known event sequence:
  - success rate = COMPLETED / (COMPLETED + FAILED + non-recoverable SAFE_STOPPED);
  - failure rate defined likewise; in-progress runs excluded;
  - retry frequency = retries / automated attempts;
  - rollbacks = `ATTEMPT_ROLLED_BACK`; compensations = `COMPENSATION_COMPLETED`;
  - MTTR = Σ recovered / count recovered; `mttrMsByMechanism`;
  - open incidents excluded from MTTR and from unrecovered;
  - `automatedActiveAvg` excludes all waiting time;
  - `?faultInjected=true|false` filters; `label` = `DEMONSTRATION — local runs, not production
    statistics`.

  (FR-OBS-004/005, CHK016/017/030/038, SC-010)
- [ ] T102 [US5] Run `./mvnw test` for T097–T101 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 7 checkpoint.

### Implementation

- [ ] T103 [US5] Implement `main/workflow/engine/Replanner.java`: one `@Transactional` unit covering
  stage resets, invalidation events, `DECISION_INVALIDATED`, the probe-link sweep, plan +1,
  `PLAN_REPLANNED` and the status change. On exception it rolls back, writes `REPLAN_ABORTED` in a
  new transaction and throws `REPLAN_FAILED`. Engine advancement happens only after commit.
  Traceability: per T097; FR-ORC-013, FR-POL-007.
- [ ] T104 [US5] Add the approved-behavior conflict rule to `main/workflow/rules/AmbiguityRules.java`
  (or a sibling `ApprovedRequirementConflictRule.java`) using exactly the research R6
  contradiction patterns (EXPIRATION B4, CREATE_LINK B2/B3, IDEMPOTENCY B4).
- [ ] T105 [US5] Add `POST …/clarify`, `POST …/requirement-change` and `POST …/rework` to
  `WorkflowController.java`, implemented in `DecisionService.java` via `Replanner`, per T098/T099.
- [ ] T106 [US6] Add `POST …/policy-exceptions/{checkId}` (HUMAN; APPROVE/REJECT with the required
  fields) to `WorkflowController.java`, the exception resolution in `PolicyEvaluator.java`, and the
  expiry evaluation at readiness in `ReleaseReadinessExecutor.java`.
  Traceability: per T100; FR-POL-004/005.
- [ ] T107 [US6] Implement `main/workflow/metrics/MetricsService.java` (derived from runs, stages and
  events only) and `main/workflow/api/MetricsController.java` (`GET /api/metrics/workflows`,
  `faultInjected` filter) per T101.
- [ ] T108 [US5] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T109 [US5] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 7 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Suggested commit: `feat: add atomic replanning, policy exceptions and
metrics`.

---

## Phase 8: User Story 4 — Change existing behavior safely: SCN-B real brownfield (P2) — plan slice 7, part 1

**Goal**: add optional expiration as a **real brownfield change**, governed by a live SCN-B run.
Impact analysis and HUMAN design approval come **before** any expiration code.

**Independent test**: `ScenarioBTest`. Live SCN-B reaches `COMPLETED`, and TEST probes the real 410
behavior.

- [ ] T110 [US4] **HUMAN** (records in `docs/scenarios/README.md`): submit the live SCN-B requirement ("Add optional expiration to existing
  links; expired links return an expired result distinct from not-found."). Check `BROWNFIELD`,
  `IMPACT_ANALYSIS` with all 10 areas, and `CHG-01 PASS`. Review the impact analysis and design, then
  **approve `DESIGN_APPROVAL`**. The run waits in `AWAITING_IMPLEMENTATION`. **No expiration code may
  exist before this approval.** Record the run id in `docs/scenarios/README.md`.

### Tests first (after T110)

- [ ] T111 [P] [US4] Write `test/link/LinkExpirationTest.java`:
  - `expiresAt` in the future accepted and echoed;
  - not in the future ⇒ 400 `VALIDATION`;
  - after expiry `GET /r/{code}` ⇒ 410 `EXPIRED`, no redirect, count unchanged;
  - `expiresAt` null never expires (injectable `Clock`);
  - the idempotency fingerprint includes `expiresAt` (same key, different `expiresAt` ⇒ 409).

  (FR-URL-001, 008, 009, 011)
- [ ] T112 [P] [US4] Extend `test/workflow/rules/CapabilityRegistryTest.java`: EXPIRATION
  `IMPLEMENTED`, with approved statements citing FR-URL-008/009 and an expiration acceptance probe.
- [ ] T113 [P] [US4] Write `test/scenario/ScenarioBTest.java` (MockMvc, fixture evidence):
  - brownfield branch taken, impact report complete;
  - `IMPLEMENT` not eligible before design approval;
  - evidence, then parallel validation including the expiration probe, then release;
  - `COMPLETED`.

  (FR-SCN-002)
- [ ] T114 [US4] Run `./mvnw test` for T111–T113 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 8 checkpoint.

### Implementation

- [ ] T115 [US4] Create `src/main/resources/db/migration/V3__link_expiration.sql`: `ALTER TABLE link
  ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE NULL` ("NULL = never expires").
  Traceability: FR-URL-008, FR-SCN-002.
- [ ] T116 [US4] Update `main/link/Link.java`, `LinkService.java` (future-only validation;
  resolve ⇒ ACTIVE/NOT_FOUND/EXPIRED via an injected `java.time.Clock`; fingerprint includes
  `expiresAt`), `LinkController.java` (`expiresAt` field) and `RedirectController.java` (410, no count
  increment).
  Traceability: per T111; FR-URL-008/009/011.
- [ ] T117 [US4] Update `main/workflow/rules/CapabilityRegistry.java`: EXPIRATION `PLANNED →
  IMPLEMENTED`, with components, migration V3, tests, approved behavior statements and the expiration
  probe used by `TestStageExecutor`.
  Traceability: per T112; research R6.
- [ ] T118 [US4] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T119 [US4] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 8 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.
- [ ] T120 [US4] **HUMAN** (records in `docs/scenarios/README.md`): commit (e.g. `feat: add optional link expiration (SCN-B brownfield)`),
  restart the app, record the SCN-B evidence (changed artifacts + commit id + requirement IDs), and
  **approve `RELEASE_APPROVAL`**. The run reaches `COMPLETED`.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval).

---

## Phase 9: User Story 5 — Resolve an ambiguous requirement: SCN-C (P2) — plan slice 7, part 2

**Goal**: genuine suspension, real human clarification, replan, and an implementation decision made
only after the clarification.

**Independent test**: `ScenarioCTest`. Live SCN-C reaches `COMPLETED`.

- [ ] T121 [P] [US5] Write `test/scenario/ScenarioCTest.java` (MockMvc) covering two paths:
  - (a) clarification consistent with the implemented behavior ⇒ plan 2, `implementationRequired =
    false`, evidence with `noChangeJustification` accepted, then `COMPLETED`;
  - (b) clarification that requires new behavior ⇒ `implementationRequired = true`, and
    `noChangeJustification` refused.

  Also: no node after `CLARIFICATION` runs before the clarification; plan-1 decisions are refused
  after the replan. (FR-SCN-003, SC-007)
- [ ] T122 [US5] Run `./mvnw test` for `test/scenario/ScenarioCTest.java` (red, if any gap remains), fix any gap within already-approved behavior,
  then run `./mvnw verify` (green).
- [ ] T123 [US5] Update `docs/traceability/matrix.md` with rows for the requirements addressed in Phase 9 (requirement → task → code → test, listing only tests actually executed, with the command and real result). Update any documentation affected by this phase (e.g. `specs/001-agentic-sdlc-url-shortener/quickstart.md`, `README.md` once it exists). Constitution §Development Workflow.
- [ ] T124 [US5] **HUMAN** (records in `docs/scenarios/README.md`): submit live "Make links expire." ⇒ `AWAITING_CLARIFICATION` (AMB-R2,
  AMB-R4). Provide the **real** clarification. Inspect the replanned `DESIGN` (`implementationRequired`,
  `changesApprovedRequirements`), then:
  - if the clarification conflicts with an approved requirement, it is refused (`409`). Decide
    whether to submit a requirement change, which requires a SpecKit spec amendment first;
  - if `implementationRequired = true`, STOP. New tasks are added through change control
    (`/speckit.converge`) before any code is written;
  - otherwise, approve the design, record evidence with `noChangeJustification`, and approve release.

  Record the outcome in `docs/scenarios/README.md`.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval).

---

## Phase 10: Polish & Cross-Cutting Concerns — plan slice 8

- [ ] T125 [P] Write `src/test/java/com/agentic/shortener/PerformanceMeasurementTest.java`
  (`@Tag("measurement")`): measure PVT-003 (p95 create/redirect), PVT-004 (SCN-A automated-active
  duration) and PVT-005 (startup to healthy). Run `./mvnw test -Dgroups=measurement -Dtest.excluded.groups=none` (pom excludes the tag by default) and record the
  actual results, labeled demonstration and non-blocking, in `docs/assessment/measurements.md`
  (NFR-010, SC-011).
- [ ] T126 [P] Create `scripts/export-run.sh`. It uses `curl` to fetch `GET /api/workflows/{id}`,
  `/events`, `/decisions`, `/report` and `GET /api/metrics/workflows?faultInjected=false|true` into
  `docs/scenarios/<scenario>/`.
  Traceability: FR-OBS-001, NFR-006 (runtime evidence only).
- [ ] T127 **HUMAN**: run `scripts/export-run.sh` for the live SCN-A, SCN-B and SCN-C runs and commit
  the exported **runtime** evidence under `docs/scenarios/scn-a/`, `scn-b/` and `scn-c/`. Never edit
  it by hand.
- [ ] T128 [P] Consolidate and verify `docs/traceability/matrix.md` (built incrementally by each
  phase's traceability task): requirement → scenario → ADR → task → code → test → evidence. List only
  tests that have actually been executed; no orphan requirements, tasks, code or tests (NFR-007). Include:
  - **SC-003 population** (CHK028): every HUMAN_GATE crossing attempt in the gate/scenario tests
    and the three live runs, each with its preceding valid HUMAN decision; expected 100%;
  - **approval-before-evidence** (CHK024): for each live run, the `DESIGN_APPROVAL` decision time,
    the evidence time and the cited revision's commit date;
  - FR-HUM-001's irreversible-action gate, satisfied by the documented absence of destructive
    actions (research R12).
- [ ] T129 [P] Write a concise `README.md`:
  - objective;
  - prerequisites;
  - build/test/run commands (including the `demo` profile);
  - links to the spec, plan, ADRs, quickstart, scenarios, traceability and measurements;
  - **limitations**:
    - no authentication, and `actorType` is self-declared (accepted risk, owner: candidate);
    - host names resolving to private addresses are not blocked;
    - the app never writes code (`IMPLEMENT` evidence is `EXTERNAL`);
    - rules are vocabulary-based (research R5/R6/R11);
    - metrics are demonstration data;
    - synchronous commands have a 90 s worst-case safety bound (research R20);
    - the runtime cannot observe when external coding started (CHK024);
    - transitive Hibernate is LGPL-2.1+ (research R11);
    - no destructive or irreversible action exists; one would need its own HUMAN gate
      (FR-HUM-001, research R12);
    - human-operated actions rule: every HUMAN-typed request in the live demonstrations was issued
      by the candidate, not the assistant (pre-implementation review H5);
    - back up `./data` before risky steps, because live evidence lives there until exported (T127).
- [ ] T130 Run quickstart.md validation end to end on a fresh `./data`, and fix any documentation
  mismatch (documentation only; any behavior change goes back through change control).
- [ ] T131 Clean-clone verification: `git clone` into a temp directory, `./mvnw verify`, start
  the app, `GET /actuator/health` returns `UP`. Run `./mvnw verify` normally (a populated cache
  makes it offline). Record the actual commands and results in
  `docs/assessment/measurements.md`.
  Traceability: NFR-007.

**Checkpoint** (protocol above: report → pre-commit review → HUMAN commit approval). Then the remaining lifecycle stages follow: full validation,
`/speckit.converge`, the independent final assessment, and the final engineering summary.

---

## Dependencies & Execution Order

### Phase dependencies (critical path, plan §Delivery slices)

```text
Phase 1 Setup → Phase 2 Foundational → Phase 3 US1-A ──(T049 HUMAN design approval)──► Phase 4 US2
→ Phase 5 US1-B ──(T078 HUMAN evidence + release)──► Phase 6 US3 → Phase 7 US5-core/US6
→ Phase 8 US4 (T110 HUMAN approval before code) → Phase 9 US5 → Phase 10 Polish
```

- Phase 4 must not start before T049: the greenfield implementation follows design approval.
- T111–T117 must not start before T110: the brownfield change follows impact analysis and design
  approval (FR-SCN-002).
- The workflow graph (T025) is frozen after Phase 3. Migrations after V1 are additive only (ADR-0002).

### User story dependencies

- **US1** needs the Foundational phase. Part B needs US2, because the probes call `LinkService`.
- **US2** needs Setup and Foundational (error handling, boundary test). It is independent of the
  workflow.
- **US3** needs US1-B (it recovers the full path).
- **US5-core / US6** need US3 (compensation inside replan, incident events for metrics).
- **US4** needs US5-core (requirement change and rework available) and US2.
- **US5 (live SCN-C)** needs US4 (expiration implemented).

### Within each phase

Tests (T0xx "Write …") come first, then the recorded failing run, then models/migrations, services,
controllers, the green run, and a HUMAN checkpoint.

## Parallel Opportunities

- Phase 1: T003, T004 and T008 run in parallel after T001.
- Phase 2: test tasks T011–T017 all [P]; implementation T019, T021, T022 and T024 [P].
- Phase 3: tests T031–T037 [P]; T039–T041 [P].
- Phase 4: tests T050–T054 [P]; T057–T059 [P].
- Phase 5: tests T065–T069 [P]; T071–T073 [P].
- Phase 6: tests T079–T086 [P].
- Phase 7: tests T097–T101 [P].
- Phase 8: tests T111–T113 [P].
- Phase 10: T125, T126, T128 and T129 [P].

### Parallel example — Phase 4 (US2)

```text
Task: "T050 UrlValidatorTest"        Task: "T051 ShortCodeGeneratorTest + collision"
Task: "T052 LinkApiTest"             Task: "T053 LinkStorageFailureTest"
Task: "T054 LinkConcurrencyTest"
then: T057 entities/repos ‖ T058 UrlValidator ‖ T059 ShortCodeGenerator → T060 LinkService → T061 controllers
```

## Implementation Strategy

- **MVP** = Phases 1–5. SCN-A is governed end to end on a working URL shortener: design approval
  comes before implementation, then a parallel validation join and release approval. Stop and
  validate here before adding reliability.
- **Incremental delivery** follows the plan slices. Each phase ends green, with a HUMAN checkpoint
  and one coherent commit.
- **Stop conditions** (plan): a red test at a phase end; any needed deviation from spec/plan/ADRs
  (constitution VII → upstream SpecKit stage); an incompatible workflow-schema change while a live
  run waits.
- **Not in tasks** (deferred per plan): async execution, a second fallback, authentication.

## Notes

- `[P]` = different files, no dependency on incomplete tasks.
- HUMAN tasks are decisions or actions the assistant must not perform or simulate.
- Test fixtures used for automated scenario tests are labeled as fixtures. Live-run evidence comes
  only from the running system (T127).
- `/speckit-implement` reads `checklists/orchestration.md` checkbox state as a gate (42/42 checked
  since 2026-10-02).
