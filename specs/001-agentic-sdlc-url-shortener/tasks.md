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

- [ ] T001 Create `pom.xml`:
  - coordinates `com.agentic:url-shortener`; Java 21;
  - parent `spring-boot-starter-parent` **3.5.16** (pinned; verified present in the local Maven cache
    on 2026-10-02);
  - dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
    `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `flyway-core`, `com.h2database:h2`
    (runtime), and `spring-boot-starter-test` (test). No other dependencies (ADR-0001, DEP-01 list);
  - Surefire excludes JUnit tag `measurement` by default.
- [ ] T002 Add the Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`)
  pinned to Maven 3.9.x.
- [ ] T003 [P] Create `.gitignore` with `target/`, `data/`, `*.mv.db`, `*.trace.db` and IDE folders
  (`.idea/`, `.vscode/`, `*.iml`).
- [ ] T004 [P] Create `.gitattributes` normalizing text files (`* text=auto eol=lf`; `*.cmd text eol=crlf`), to
  stop the CRLF warnings seen in earlier commits.
- [ ] T005 Write the failing test `test/ApplicationSmokeTest.java`: the context loads, and
  `GET /actuator/health` returns 200 `{"status":"UP"}`. Run it and record the failure (no
  application class yet).
- [ ] T006 Create `main/ShortenerApplication.java` (a plain `@SpringBootApplication`
  bootstrap; no orchestration logic).
- [ ] T007 Create `src/main/resources/application.yml` with:
  - datasource `jdbc:h2:file:./data/shortener`;
  - `spring.jpa.hibernate.ddl-auto=validate`; Flyway enabled;
  - `management.endpoints.web.exposure.include=health`;
  - `server.error.include-stacktrace=never`;
  - `workflow.fault-injection.enabled=false`, `workflow.stage-timeout=5s`,
    `workflow.retry.max-attempts=3`, `workflow.retry.backoff=100ms,200ms`.
- [ ] T008 [P] Create `src/main/resources/application-demo.yml` (only
  `workflow.fault-injection.enabled=true`) and `src/test/resources/application-test.yml`
  (in-memory H2 `jdbc:h2:mem:…;DB_CLOSE_DELAY=-1`, `workflow.stage-timeout=300ms`).
- [ ] T009 Run `./mvnw verify`. `ApplicationSmokeTest` passes. Then rerun with `./mvnw -o verify` to
  confirm offline builds work once the cache is populated.

**Checkpoint (HUMAN review + commit)**: e.g. `chore: add Spring Boot walking skeleton`.

---

## Phase 2: Foundational (Blocking Prerequisites) — plan slice 2, part 1

**Purpose**: the orchestration persistence, graph, engine, audit and actor model that every story
needs. ⚠️ No user-story work starts before this phase is green.

### Tests first

- [ ] T010 [P] Write `test/common/ProblemDetailsTest.java`. Error responses must be
  `application/problem+json` with `type`, `title`, `status` and `category`, and no stack trace. The
  categories are the OpenAPI enum `VALIDATION, NOT_FOUND, EXPIRED, CONFLICT, INVALID_STATE,
  STALE_PLAN_VERSION, EVIDENCE_SCOPE_MISMATCH, CHANGE_CONTROL_REQUIRED, FAULT_INJECTION_DISABLED,
  REPLAN_FAILED, STORAGE_UNAVAILABLE, CODE_SPACE_EXHAUSTED, INTERNAL`.
- [ ] T011 [P] Write `test/PlaneBoundaryTest.java`: no class under `com.agentic.shortener.link`
  imports `com.agentic.shortener.workflow` (NFR-003, ADR-0001).
- [ ] T012 [P] Write `test/workflow/engine/WorkflowGraphTest.java`. The graph has exactly the 14 nodes
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
- [ ] T013 [P] Write `test/workflow/audit/AuditServiceTest.java`:
  - events get a per-run increasing `seq`;
  - each carries `run_id`, `correlation_id`, `actor_type`, `actor_identity`, `plan_version`,
    `policy_version` and `injected`;
  - append-only means the application has **only an append write path**:
    - `AuditService` is the single writer and only inserts new events;
    - `AuditEventRepository` (by reflection, including inherited methods) offers no `delete*`
      method and no update query;
    - the `AuditEvent` entity is immutable (Hibernate `@Immutable`, columns `updatable = false`,
      no setters), so an existing record cannot be changed through JPA;
    - the only HTTP route over events is `GET /api/workflows/{id}/events`.

  (FR-OBS-001/002)
- [ ] T014 [P] Write `test/workflow/engine/ActorValidatorTest.java`:
  - `SYSTEM` is never accepted from the API;
  - human gates, clarification, rework, termination, requirement change, policy exceptions and
    resume accept only `HUMAN`;
  - `AGENT` is accepted only for submitting a requirement and for implementation evidence;
  - blank identities are refused;
  - `workflow-engine`/`system` are refused for HUMAN/AGENT;
  - `claude-code` is refused as HUMAN.

  (ADR-0004 §1, FR-HUM-006)
- [ ] T015 [P] Write `test/workflow/engine/WorkflowEngineTest.java` using stub executors:
  - sequential order;
  - a node starts only when every dependency is `SUCCEEDED`/`SKIPPED`;
  - conditional nodes are `SKIPPED`, with a `BRANCH` decision and a `BRANCH_TAKEN` event;
  - entry/exit conditions are enforced;
  - stub executors that sleep 200 ms in `TEST`/`DOCS`/`SECURITY` produce overlapping persisted
    intervals on distinct threads;
  - the join waits for all three;
  - the engine stops at a `HUMAN_GATE` (`BLOCKED`, run `AWAITING_APPROVAL`) and at `IMPLEMENT`
    (`BLOCKED`, run `AWAITING_IMPLEMENTATION`);
  - a conditional `PENDING→RUNNING` update prevents double execution under concurrent advance calls.

  (FR-ORC-003..008, SC-002, ADR-0003)
- [ ] T016 [P] Write `test/workflow/rules/CapabilityRegistryTest.java`:
  - every `IMPLEMENTED` entry references existing classes and test files;
  - every entry lists vocabulary, requirement IDs and approved behavior statements;
  - initially all capabilities are `PLANNED` (CREATE_LINK, REDIRECT, ANALYTICS, IDEMPOTENCY,
    EXPIRATION).

  (Research R6)
- [ ] T017 Run `./mvnw test` for T010–T016 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 2 checkpoint.

### Implementation

- [ ] T018 [P] Create `main/common/ErrorCategory.java` (the enum from T010) and
  `main/common/GlobalExceptionHandler.java`, which maps exceptions to `ProblemDetail` with
  `category` and no stack traces (FR-URL-015).
- [ ] T019 Create `src/main/resources/db/migration/V1__workflow_tables.sql` with the five control-plane
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
- [ ] T020 [P] Create the enums under `main/workflow/engine/`:
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
- [ ] T021 [P] Create the JPA entities and repositories under `main/workflow/persistence/`:
  `WorkflowRun` (with `@Version`), `WorkflowStage`, `Decision`, `PolicyEvaluation`, `AuditEvent`. For
  the append-only audit trail (ADR-0002), use the simplest approach:
  - `AuditEvent` is annotated `@org.hibernate.annotations.Immutable`, with all columns
    `updatable = false` and no setters;
  - `AuditEventRepository` extends the marker interface `org.springframework.data.repository.Repository<AuditEvent, Long>`
    (not `JpaRepository`/`CrudRepository`), so it inherits no delete or update methods;
  - it declares only `AuditEvent save(AuditEvent newEvent)`, used solely by `AuditService` for new
    events, plus finder methods.
- [ ] T022 Implement `main/workflow/audit/AuditService.java`: append-only writes with per-run `seq`
  and copied correlation/plan/policy version; `SYSTEM`/`workflow-engine` for engine events.
- [ ] T023 [P] Implement `main/workflow/engine/Actor.java` (record `actorType`, `actorIdentity`) and
  `main/workflow/engine/ActorValidator.java` per T014.
- [ ] T024 Implement `main/workflow/engine/WorkflowGraph.java` per T012: a static node definition
  with dependencies, kind, conditional flag and entry/exit-condition hooks.
- [ ] T025 Implement `main/workflow/engine/StageExecutor.java` (interface `Node node(); StageResult
  execute(StageContext ctx)`), `StageContext.java` (run, persisted upstream outputs) and
  `StageResult.java` (success output + provenance | failure class/code/reason).
- [ ] T026 Implement `main/workflow/engine/WorkflowEngine.java`:
  - wave scheduling on a fixed thread pool;
  - eligibility = `PENDING` and all dependencies `SUCCEEDED`/`SKIPPED`;
  - per-run `ReentrantLock`, the optimistic `@Version`, and the conditional `PENDING→RUNNING`
    update;
  - output + `SUCCEEDED` committed in one completion transaction;
  - start/end time and thread name recorded, with `STAGE_*` events;
  - stops at `HUMAN_GATE` (`BLOCKED`, `AWAITING_*`) and at `EXTERNAL_ACTION` (`BLOCKED`,
    `AWAITING_IMPLEMENTATION`, `IMPLEMENTATION_REQUESTED`);
  - `pending_action` set (FR-ORC-003..009, ADR-0003).
- [ ] T027 Implement `main/workflow/rules/CapabilityRegistry.java` per T016: five capabilities, all
  `PLANNED`, each with vocabulary, requirement IDs (CREATE_LINK → FR-URL-001..005, 011, 016;
  REDIRECT → FR-URL-006, 007; ANALYTICS → FR-URL-010; IDEMPOTENCY → FR-URL-011; EXPIRATION →
  FR-URL-008, 009), approved behavior statements, and acceptance-probe identifiers.
- [ ] T028 Run `./mvnw verify`. T010–T016 pass (record red → green).

**Checkpoint (HUMAN review + commit)**: e.g. `feat: add workflow persistence, DAG and engine core`.

---

## Phase 3: User Story 1 (part A) — Govern a clear requirement up to the implementation wait (P1) — plan slice 2, part 2

**Goal**: submit a requirement and drive it through intake, understanding, branching, decomposition,
design, policy and human design approval, until it waits for external implementation.

**Independent test**: `POST /api/workflows` with the SCN-A text ⇒ `GREENFIELD`, both conditional
nodes `SKIPPED`, `AWAITING_APPROVAL`; HUMAN approve ⇒ `AWAITING_IMPLEMENTATION`; an `AGENT`
approval is refused.

### Tests first

- [ ] T029 [P] [US1] Write `test/workflow/rules/AmbiguityRulesTest.java`:
  - SCN-A text ⇒ no findings;
  - SCN-B text ⇒ no findings;
  - "Make links expire." ⇒ AMB-R2 and AMB-R4;
  - a "never expire … expire" conflict ⇒ AMB-R3;
  - a requirement missing only edge-case detail ⇒ no findings;
  - each finding records rule id, matched text and explanation.

  (FR-ORC-016)
- [ ] T030 [P] [US1] Write `test/workflow/stages/UnderstandAndDecomposeTest.java`:
  - normalization;
  - capability mapping;
  - `GREENFIELD` when all requested capabilities are `PLANNED`, `BROWNFIELD` when it modifies an
    `IMPLEMENTED` capability;
  - DECOMPOSE produces ≥ 1 task per capability, each with an acceptance check and requirement IDs;
  - an unknown capability ⇒ `PERMANENT` failure (`INVALID_INPUT`).
- [ ] T031 [P] [US1] Write `test/workflow/stages/ImpactAnalysisAndDesignTest.java`:
  - the impact report populates all 10 FR-SCN-002 areas;
  - DESIGN lists components, interface/data changes, the test plan, dependencies, a
    security-sensitivity flag, the run's `requirementIds`, `implementationRequired` and
    `changesApprovedRequirements`.
- [ ] T032 [P] [US1] Write `test/workflow/policy/PolicyV1Test.java`:
  - PRIV-01 `PASS` / `EXCEPTION_REQUESTED` (visitor IP, email, location);
  - SEC-01 `FAIL` for "allow javascript: URLs" ⇒ run `SAFE_STOPPED`, non-recoverable;
  - CHG-01 `NOT_APPLICABLE` (greenfield) / `PASS` / `FAIL` (brownfield);
  - DEP-01 `NOT_APPLICABLE` / `PASS` / `FAIL`;
  - every evaluation records policy version `v1`, check id, node, mandatory, result and reason.

  (FR-POL-001..004)
- [ ] T033 [P] [US1] Write `test/workflow/api/HumanGateTest.java`:
  - no progress past a gate without a decision;
  - approval at `DESIGN_APPROVAL` by `HUMAN` succeeds;
  - `AGENT`, `SYSTEM`, blank and reserved actors get `400`/`409` plus `DECISION_REFUSED`;
  - wrong gate or stale `planVersion` ⇒ `409` + `DECISION_REFUSED`, with state unchanged;
  - reject ⇒ `REJECTION` decision, `APPROVAL_REJECTED` event, `AWAITING_REWORK`;
  - terminate (HUMAN) from every `AWAITING_*` state ⇒ `FAILED` with a `TERMINATION` decision;
  - a duplicate concurrent approval ⇒ the second gets `409` (CHK035).

  (FR-HUM-001..007, SC-003, CHK002)
- [ ] T034 [P] [US1] Write `test/workflow/api/ImplementationEvidenceTest.java`:
  - accepted from `HUMAN` or `AGENT` only while `AWAITING_IMPLEMENTATION` at the current plan
    version;
  - non-empty `changedArtifacts` without `revision` ⇒ `400`;
  - empty `changedArtifacts` without `noChangeJustification` ⇒ `400`;
  - `noChangeJustification` while `implementationRequired = true` ⇒ `400`;
  - a `requirementId` outside the run's set ⇒ `409 EVIDENCE_SCOPE_MISMATCH` + `DECISION_REFUSED`;
  - on success, `IMPLEMENT` is `SUCCEEDED` with provenance `EXTERNAL`, and coverage of the
    designed components is recorded;
  - evidence before `DESIGN_APPROVAL` ⇒ refused;
  - the stored evidence `created_at` is later than the valid approval decision's `created_at`.

  (ADR-0004 §3, CHK004, CHK024)
- [ ] T035 [P] [US1] Write `test/workflow/api/WorkflowApiTest.java` (MockMvc against
  contracts/openapi.yaml):
  - `POST /api/workflows` ⇒ 201 `Run`, with `X-Correlation-Id` honored;
  - `GET /api/workflows/{id}` returns status, `pendingAction`, plan/policy version and every node
    with kind, `dependsOn`, conditional and status;
  - `GET …/events` and `GET …/decisions` are ordered;
  - unknown id ⇒ 404 problem.
- [ ] T036 [US1] Run `./mvnw test` for T029–T035 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 3 checkpoint.

### Implementation

- [ ] T037 [P] [US1] Implement `main/workflow/rules/AmbiguityRules.java` using **exactly** the
  normalization, capability terms, outcome verbs, R2 parameter patterns, R3 conflict pairs and R4
  change verbs/qualifiers listed in research R5. Nothing beyond those lists (CHK011).
- [ ] T038 [P] [US1] Implement `main/workflow/stages/IntakeExecutor.java` (non-blank, ≤ 4000 chars)
  and `UnderstandExecutor.java` (normalize, capabilities, findings, change type, `REQUIREMENT_NORMALIZED`,
  `AMBIGUITY_DETECTED`, branch decision for `CLARIFICATION`).
- [ ] T039 [P] [US1] Implement `main/workflow/stages/DecomposeExecutor.java` (tasks with acceptance
  checks and requirement IDs; fixes the run's requirement-ID set) and `ImpactAnalysisExecutor.java`
  (10-area report from the registry; branch decision).
- [ ] T040 [US1] Implement `main/workflow/stages/DesignExecutor.java` per T031. Set
  `implementationRequired = false` only when every requested capability is `IMPLEMENTED`, none is
  asked to change, and no behavior detail falls outside the recorded behavior.
- [ ] T041 [US1] Implement `main/workflow/policy/PolicyCatalog.java` (version `v1`: PRIV-01, SEC-01,
  CHG-01, DEP-01, AUD-01, each with domain and mandatory flag; the PRIV-01 term list and the DEP-01
  approved dependency list with licenses **exactly** as in research R11 (CHK018)) and
  `PolicyEvaluator.java`:
  - checks are evaluated after their bound node and persisted;
  - mandatory `FAIL` ⇒ safe-stop;
  - `EXCEPTION_REQUESTED` ⇒ node `BLOCKED`, run `AWAITING_APPROVAL`,
    `pendingAction = EXCEPTION:<checkId>`.
- [ ] T042 [US1] Implement `main/workflow/engine/DecisionService.java`:
  - approve, reject and terminate with actor validation, gate/plan-version checks,
    `DECISION_REFUSED` recording and decision lineage;
  - terminate from any `AWAITING_*` (compensation hook is a no-op until Phase 6).
- [ ] T043 [US1] Implement `main/workflow/engine/ImplementationEvidenceService.java` per T034.
- [ ] T044 [US1] Implement `main/workflow/api/WorkflowController.java` and request/response records for
  `POST /api/workflows`, `GET /api/workflows/{id}`, `GET …/events`, `GET …/decisions`,
  `POST …/approve`, `POST …/reject`, `POST …/terminate` and `POST …/implementation`, exactly as in
  contracts/openapi.yaml.
- [ ] T045 [US1] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T046 [US1] **HUMAN** (records in `docs/scenarios/README.md`): start the app on the demo database and submit the SCN-A requirement
  ("Create a short link for a valid HTTP/HTTPS address, redirect to the original address, and record
  redirect count and last redirect time.") as `HUMAN`/`candidate`. Review the design, then
  **approve `DESIGN_APPROVAL`** (plan version 1). The run now waits in `AWAITING_IMPLEMENTATION`.
  Note the run id in `docs/scenarios/README.md` (create the file with only this fact).

**Checkpoint (HUMAN review + commit)**: e.g. `feat: add governed workflow up to external implementation
wait`.

---

## Phase 4: User Story 2 — Shorten, resolve and measure links (P1) = SCN-A implementation — plan slice 3

**Goal**: the core URL shortener **without expiration**, implemented only after the SCN-A design
approval in T046.

**Independent test**: create ⇒ 201 with a 7-char code; redirect ⇒ 302 `no-store` and the count
increments; unknown ⇒ 404; unsafe URL ⇒ 400; idempotency 201/200/409; 50 + 50 concurrency.

### Tests first

- [ ] T047 [P] [US2] Write `test/link/UrlValidatorTest.java` (parameterized):
  - accepts absolute `http`/`https` with a host;
  - rejects `javascript:`, `file:`, `data:`, other schemes, missing host, and length > 2,048;
  - rejects `localhost`/`*.localhost` and literals in `0.0.0.0/8`, `127/8`, `10/8`, `172.16/12`,
    `192.168/16`, `169.254/16`, `::`, `::1`, `fc00::/7`, `fe80::/10` and IPv4-mapped forms;
  - rejects non-canonical numeric hosts (e.g. `2130706433`);
  - a hostname is never resolved (no DNS).

  (FR-URL-002/003/016, PVT-007, SC-008)
- [ ] T048 [P] [US2] Write `test/link/ShortCodeGeneratorTest.java` (7 Base62 characters) and
  `test/link/LinkServiceCollisionTest.java`. Using a stub generator: a collision regenerates; 5
  collisions ⇒ `CODE_SPACE_EXHAUSTED` and no link (FR-URL-004/005, PVT-006).
- [ ] T049 [P] [US2] Write `test/link/LinkApiTest.java` (MockMvc):
  - `POST /api/links` ⇒ 201 `Link`;
  - `GET /r/{code}` ⇒ 302 `Location` + `Cache-Control: no-store`, with `redirectCount` +1 and
    `lastRedirectAt` set;
  - unknown ⇒ 404 `NOT_FOUND`;
  - `GET /api/links/{code}` returns analytics;
  - `Idempotency-Key`: same body ⇒ 200 with the same code; different body ⇒ 409 `CONFLICT`; no key
    ⇒ new link.

  (FR-URL-001, 006, 007, 010, 011, 015)
- [ ] T050 [P] [US2] Write `test/link/LinkStorageFailureTest.java` (mocked repository):
  - create and redirect ⇒ 503 `STORAGE_UNAVAILABLE`, never 404/410;
  - an analytics-update failure still redirects 302 and is logged;
  - health is `DOWN` when the datasource fails.

  (FR-URL-013, 014, 017)
- [ ] T051 [P] [US2] Write `test/link/LinkConcurrencyTest.java`: 50 concurrent creates ⇒ 50 distinct
  codes; 50 concurrent redirects of one link ⇒ `redirectCount = 50` (FR-URL-012, PVT-008, SC-009).
- [ ] T052 [US2] Run `./mvnw test` for T047–T051 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 4 checkpoint.

### Implementation

- [ ] T053 [US2] Create `src/main/resources/db/migration/V2__links.sql`:
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
- [ ] T054 [P] [US2] Create `main/link/Link.java`, `IdempotencyRecord.java`, `LinkRepository.java`
  (atomic `@Modifying` query `UPDATE link SET redirect_count = redirect_count + 1, last_redirect_at
  = :now WHERE id = :id`; `deleteByProbeRunId`) and `IdempotencyRepository.java`.
- [ ] T055 [P] [US2] Implement `main/link/UrlValidator.java` per T047, using `java.net.URI` and
  literal-only IP parsing.
- [ ] T056 [P] [US2] Implement `main/link/ShortCodeGenerator.java` (interface) and
  `SecureRandomShortCodeGenerator.java` (7-char Base62).
- [ ] T057 [US2] Implement `main/link/LinkService.java`:
  - create with idempotency (SHA-256 fingerprint of the canonical `url`; key row + link in one
    transaction; unique-key race resolved by re-read);
  - collision retry ≤ 5;
  - resolve (active / not found);
  - record redirect (analytics failure logged, redirect still allowed);
  - `createProbeLink(url, runId)`, `deleteProbeLinks(runId)`.
- [ ] T058 [US2] Implement `main/link/LinkController.java` (`POST /api/links`,
  `GET /api/links/{code}`) and `main/link/RedirectController.java` (`GET /r/{code}` ⇒ 302,
  `Cache-Control: no-store`). Map data-access failures to 503 in `GlobalExceptionHandler`.
- [ ] T059 [US2] Update `main/workflow/rules/CapabilityRegistry.java`: CREATE_LINK, REDIRECT,
  ANALYTICS and IDEMPOTENCY become `IMPLEMENTED`, with real component classes, endpoints, migration
  `V2__links.sql`, test classes and acceptance probes. EXPIRATION stays `PLANNED`.
  `CapabilityRegistryTest` passes.
- [ ] T060 [US2] Run `./mvnw verify`. All tests green, including the boundary test (record red → green).

**Checkpoint (HUMAN review + commit)**: e.g. `feat: implement core URL shortener (SCN-A implementation)`.
Record the commit id. The live SCN-A run is still waiting.

---

## Phase 5: User Story 1 (part B) — Validation, release and completion (P1) — plan slice 4

**Goal**: implementation evidence unblocks the parallel `TEST` ‖ `DOCS` ‖ `SECURITY` group, followed
by the join, release approval and final report. SCN-A completes.

**Independent test**: `ScenarioATest` end to end. Live SCN-A reaches `COMPLETED`, with overlapping
parallel intervals and no remaining probe links.

### Tests first

- [ ] T061 [P] [US1] Write `test/workflow/stages/TestStageExecutorTest.java`:
  - probes for each requested capability, through `LinkService`, with probe links tagged
    `probe_run_id`;
  - on success the probe links are deleted and the evidence stays in the output;
  - a failing probe ⇒ `PERMANENT` with `failure_code = IMPLEMENTATION_DEFECT`.
- [ ] T062 [P] [US1] Write `test/workflow/stages/SecurityAndDocsExecutorTest.java`:
  - SECURITY probes the validator with the unsafe set from T047; any acceptance ⇒
    `IMPLEMENTATION_DEFECT`;
  - DOCS produces the required sections from design, registry and evidence (provenance `ACTUAL`).
- [ ] T063 [P] [US1] Write `test/workflow/stages/ReleaseReadinessAndReportTest.java`:
  - readiness fails on an unresolved mandatory policy issue or an unapproved exception;
  - AUD-01 `PASS`/`FAIL`;
  - every task needs a passing check;
  - residual risks include designed components missing from the evidence;
  - FINAL_REPORT is idempotent (same report on re-run), cites audit `seq` numbers, and the run is
    `COMPLETED`.

  (FR-POL-006, FR-OBS-006, NFR-006)
- [ ] T064 [P] [US1] Write `test/workflow/ImplementationDefectRoutingTest.java`: a `TEST`/`SECURITY`
  `IMPLEMENTATION_DEFECT` after accepted evidence ⇒ run `AWAITING_REWORK`, with
  `pendingAction = REWORK_OR_TERMINATE`; terminate ⇒ `FAILED` (CHK036; rework is covered in Phase 7).
- [ ] T065 [P] [US1] Write `test/scenario/ScenarioATest.java` (MockMvc, test-fixture evidence
  labeled as fixture):
  - full SCN-A path, with both branches `SKIPPED`;
  - HUMAN design approval, then evidence (changed artifacts + revision);
  - `TEST`/`DOCS`/`SECURITY` scheduled in the same wave on distinct threads, then the join, then HUMAN
    release approval with `acceptedRisks`. Interval overlap itself is proven in T015 (stub
    instrumentation) and T074 (injected `DELAY`, CHK027);
  - `COMPLETED`, report available;
  - no `link` rows with `probe_run_id` remain.

  (FR-SCN-001, SC-001, SC-002)
- [ ] T066 [US1] Run `./mvnw test` for T061–T065 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 5 checkpoint.

### Implementation

- [ ] T067 [P] [US1] Implement `main/workflow/stages/TestStageExecutor.java` per T061.
- [ ] T068 [P] [US1] Implement `main/workflow/stages/SecurityExecutor.java` and `DocsExecutor.java`
  per T062 (no fallback yet).
- [ ] T069 [P] [US1] Implement `main/workflow/stages/ReleaseReadinessExecutor.java` (with AUD-01) and
  `FinalReportExecutor.java` per T063.
- [ ] T070 [US1] Add `IMPLEMENTATION_DEFECT` routing to `AWAITING_REWORK` in `WorkflowEngine.java`
  per T064.
- [ ] T071 [US1] Add `GET /api/workflows/{id}/report` to `WorkflowController.java` (409 until
  `FINAL_REPORT` has succeeded).
- [ ] T072 [US1] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T073 [US1] **HUMAN** (records in `docs/scenarios/README.md`): restart the app on the new code (the SCN-A run is still waiting). Record
  SCN-A implementation evidence via `POST …/implementation`:
  - changed artifacts from Phase 4;
  - the Phase 4 commit id as `revision`;
  - requirement IDs within the run's set.

  Inspect the parallel overlap, then **approve `RELEASE_APPROVAL`** with `acceptedRisks`. The run
  reaches `COMPLETED`.

**Checkpoint (HUMAN review + commit)**: e.g. `feat: add parallel validation, release readiness and
final report`.

---

## Phase 6: User Story 3 — Recover safely from failures (P2) — plan slice 5

**Goal**: bounded retry, timeout, DOCS fallback, attempt rollback vs compensation, safe-stop,
resume, startup recovery, incident events and gated fault injection.

**Independent test**: with fault injection enabled by test configuration, every row of the
quickstart recovery table behaves as specified.

### Tests first

- [ ] T074 [P] [US3] Write `test/workflow/engine/FaultInjectionConfigTest.java`:
  - default config ⇒ a run with `faults` gets `400 FAULT_INJECTION_DISABLED`;
  - with `workflow.fault-injection.enabled=true` it is accepted;
  - injected effects carry `injected=true`;
  - faults are allowed on automated nodes only;
  - with `DELAY` injected on the real `TEST`, `DOCS` and `SECURITY` executors, their persisted
    intervals overlap (max start < min end) on distinct threads.

  (CHK038, FR-REL-011, SC-002/CHK027)
- [ ] T075 [P] [US3] Write `test/workflow/engine/RetryTimeoutTest.java`:
  - TRANSIENT ×1 ⇒ 2 attempts and success, with `RETRY_SCHEDULED`;
  - PERMANENT ⇒ no retry;
  - TRANSIENT ×3 ⇒ `RETRY_EXHAUSTED` ⇒ `SAFE_STOPPED` (recoverable);
  - TIMEOUT ⇒ `STAGE_TIMED_OUT`, treated as transient;
  - gates and `IMPLEMENT` never time out;
  - backoff is 100 ms then 200 ms.

  (FR-REL-001..004, PVT-001/002, SC-004)
- [ ] T076 [P] [US3] Write `test/workflow/engine/FallbackTest.java`: DOCS TRANSIENT ×3 ⇒
  `FALLBACK_USED`, provenance `FALLBACK`, all required sections present; SECURITY never falls back
  (FR-REL-005).
- [ ] T077 [P] [US3] Write `test/workflow/engine/RollbackCompensationTest.java`:
  - a TEST fault after probe creation ⇒ `ATTEMPT_ROLLED_BACK` (no output committed) and
    `COMPENSATION_STARTED/COMPLETED` (probe links deleted), then retry;
  - `COMPENSATION_FAILURE` ⇒ `COMPENSATION_FAILED` ⇒ `SAFE_STOPPED`, non-recoverable;
  - permanent non-defect failure ⇒ compensation ⇒ `FAILED`;
  - terminate runs the compensation sweep.

  (FR-REL-006, ADR-0005 §5/6)
- [ ] T078 [P] [US3] Write `test/workflow/engine/SafeStopResumeTest.java`:
  - resume is HUMAN-only, and refused for non-recoverable or completed runs;
  - succeeded nodes are never re-executed;
  - with a SECURITY failure in the parallel group, resume re-runs only SECURITY;
  - state, reason, history and the recoverable flag are preserved.

  (FR-REL-007..010, SC-006)
- [ ] T079 [P] [US3] Write `test/workflow/RestartPersistenceTest.java` (two Spring contexts over one
  temp H2 file):
  - runs waiting at a gate and at `AWAITING_IMPLEMENTATION` are identical after restart and continue
    normally;
  - a stage left `RUNNING` ⇒ `ATTEMPT_ROLLED_BACK`, compensation sweep, run `SAFE_STOPPED`
    (`INTERRUPTED`, recoverable);
  - a run left `RUNNING` with **no** stage `RUNNING` ⇒ compensation sweep, run `SAFE_STOPPED`
    (`INTERRUPTED`, recoverable);
  - no stage is re-executed until HUMAN `resume`.

  (FR-ORC-006, NFR-004, SC-005, CHK033)
- [ ] T080 [P] [US3] Write `test/workflow/engine/IncidentEventsTest.java`:
  - first failure ⇒ `FAILURE_DETECTED`;
  - `RECOVERY_STARTED` with mechanism `RETRY`/`FALLBACK`/`COMPENSATION`/`RESUME`/`REWORK`;
  - `RECOVERY_COMPLETED` on later success;
  - `RECOVERY_FAILED` when the run ends `FAILED` or non-recoverable `SAFE_STOPPED`.

  (FR-OBS-003)
- [ ] T081 [US3] Run `./mvnw test` for T074–T080 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 6 checkpoint.

### Implementation

- [ ] T082 [P] [US3] Implement `main/workflow/engine/FaultInjector.java` (reads `fault_plan_json`;
  types `TRANSIENT|PERMANENT|TIMEOUT|DELAY|COMPENSATION_FAILURE`; fires after executor work and
  before the completion commit) and gate run creation on `workflow.fault-injection.enabled`.
- [ ] T083 [US3] Implement `main/workflow/engine/RetryTimeoutRunner.java` (`Future.get(timeout)` plus
  cancel; transient-only retry; 3 attempts; 100/200 ms backoff; events) and wire it into
  `WorkflowEngine.java`.
- [ ] T084 [US3] Add the DOCS fallback template to `main/workflow/stages/DocsExecutor.java` per T076.
- [ ] T085 [US3] Implement `main/workflow/engine/CompensationService.java`: an idempotent sweep via
  `LinkService.deleteProbeLinks(runId)`, with events, and safe-stop on failure. Wire it to:
  - a failed TEST attempt;
  - a permanent failure;
  - terminate;
  - `FAILED`/`SAFE_STOPPED`.
- [ ] T086 [US3] Implement the safe-stop triggers and the recoverable flag in `WorkflowEngine.java`,
  plus `POST /api/workflows/{id}/resume` (HUMAN) in `WorkflowController.java`.
- [ ] T087 [US3] Implement `main/workflow/engine/StartupRecovery.java`
  (`ApplicationReadyEvent`: inspect every non-terminal run. For a `RUNNING` run: roll back any
  `RUNNING` stage, run the idempotent compensation sweep, then `SAFE_STOPPED` (recoverable,
  `INTERRUPTED`). Leave `AWAITING_*` runs untouched. No automatic re-execution; CHK033).
- [ ] T088 [US3] Emit incident events in `main/workflow/engine/WorkflowEngine.java`,
  `CompensationService.java` and `DecisionService.java` (`FAILURE_DETECTED`, `RECOVERY_*`) from the engine, the
  compensation service and resume per T080.
- [ ] T089 [US3] Run `./mvnw verify`. All tests green (record red → green).

**Checkpoint (HUMAN review + commit)**: e.g. `feat: add bounded recovery, compensation, safe-stop and
resume`.

---

## Phase 7: Replanning (US5 core), policy exceptions and metrics (US6) — plan slice 6

**Goal**: atomic dependency-aware replanning (clarification, requirement change, rework), the
conflict-with-approved-requirement rule, policy exceptions, and demonstration metrics.

**Independent test**: replan and policy/metrics tests are green; the quickstart rework, invalidation
and policy-exception rows behave as specified.

### Tests first

- [ ] T090 [P] [US5] Write `test/workflow/engine/ReplannerTest.java`:
  - affected = from-node + descendants, everything else preserved;
  - `STAGE_INVALIDATED` keeps prior outputs;
  - `DECISION_INVALIDATED` for approvals and implementation evidence;
  - plan +1 and `PLAN_REPLANNED {old,new,reason,affected,preserved}`;
  - **atomicity**: an injected failure inside the replan transaction leaves the run, stages,
    decisions and links unchanged, records `REPLAN_ABORTED`, and the command returns
    `REPLAN_FAILED`.

  (FR-ORC-013, CHK007, SC-007)
- [ ] T091 [P] [US5] Write `test/workflow/api/ClarificationTest.java`:
  - `POST …/clarify` (HUMAN) while `AWAITING_CLARIFICATION` ⇒ `CLARIFICATION_RECEIVED`, replan from
    `UNDERSTAND`, `CLARIFICATION` kept `SUCCEEDED`;
  - a still-ambiguous clarification ⇒ another round (plan +1 each);
  - a clarification contradicting an approved behavior statement (e.g. "all links expire after 30
    days" vs FR-URL-008) ⇒ `409 CHANGE_CONTROL_REQUIRED` + `DECISION_REFUSED`;
  - stale plan ⇒ 409.

  (FR-SCN-003, CHK010, CHK034)
- [ ] T092 [P] [US5] Write `test/workflow/api/RequirementChangeAndReworkTest.java`:
  - requirement change after design approval ⇒ plan +1, approval invalidated, re-approval
    required; `changesApprovedRequirements` listed when applicable;
  - rework from `DOCS` after a release rejection ⇒ only `DOCS`/`RELEASE_READINESS`/`RELEASE_APPROVAL`
    re-run, with `IMPLEMENT`/`TEST`/`SECURITY` preserved;
  - rework from `IMPLEMENT` after `IMPLEMENTATION_DEFECT` ⇒ evidence invalidated, new evidence
    required, downstream re-validated;
  - rework node must be at or upstream of the rejected/failed node;
  - HUMAN only.

  (FR-HUM-005, FR-ORC-013, CHK036)
- [ ] T093 [P] [US6] Write `test/workflow/policy/PolicyExceptionTest.java`:
  - PRIV-01 `EXCEPTION_REQUESTED` blocks;
  - HUMAN approval requires `scope`, `compensatingControl` and `expiresOrReview`, and records
    `EXCEPTION_APPROVED` with all FR-POL-005 fields;
  - rejection ⇒ `SAFE_STOPPED`;
  - a dated expiry that has passed before `RELEASE_READINESS` ⇒ treated as unapproved, readiness
    fails;
  - AGENT is refused.

  (FR-POL-004/005/006, CHK006)
- [ ] T094 [P] [US6] Write `test/workflow/metrics/MetricsTest.java` over a known event sequence:
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
- [ ] T095 [US5] Run `./mvnw test` for T090–T094 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 7 checkpoint.

### Implementation

- [ ] T096 [US5] Implement `main/workflow/engine/Replanner.java`: one `@Transactional` unit covering
  stage resets, invalidation events, `DECISION_INVALIDATED`, the probe-link sweep, plan +1,
  `PLAN_REPLANNED` and the status change. On exception it rolls back, writes `REPLAN_ABORTED` in a
  new transaction and throws `REPLAN_FAILED`. Engine advancement happens only after commit.
- [ ] T097 [US5] Add the approved-behavior conflict rule to `main/workflow/rules/AmbiguityRules.java`
  (or a sibling `ApprovedRequirementConflictRule.java`) using the registry's approved statements.
- [ ] T098 [US5] Add `POST …/clarify`, `POST …/requirement-change` and `POST …/rework` to
  `WorkflowController.java`, implemented in `DecisionService.java` via `Replanner`, per T091/T092.
- [ ] T099 [US6] Add `POST …/policy-exceptions/{checkId}` (HUMAN; APPROVE/REJECT with the required
  fields) to `WorkflowController.java`, the exception resolution in `PolicyEvaluator.java`, and the
  expiry evaluation at readiness in `ReleaseReadinessExecutor.java`.
- [ ] T100 [US6] Implement `main/workflow/metrics/MetricsService.java` (derived from runs, stages and
  events only) and `main/workflow/api/MetricsController.java` (`GET /api/metrics/workflows`,
  `faultInjected` filter) per T094.
- [ ] T101 [US5] Run `./mvnw verify`. All tests green (record red → green).

**Checkpoint (HUMAN review + commit)**: e.g. `feat: add atomic replanning, policy exceptions and
metrics`.

---

## Phase 8: User Story 4 — Change existing behavior safely: SCN-B real brownfield (P2) — plan slice 7, part 1

**Goal**: add optional expiration as a **real brownfield change**, governed by a live SCN-B run.
Impact analysis and HUMAN design approval come **before** any expiration code.

**Independent test**: `ScenarioBTest`. Live SCN-B reaches `COMPLETED`, and TEST probes the real 410
behavior.

- [ ] T102 [US4] **HUMAN** (records in `docs/scenarios/README.md`): submit the live SCN-B requirement ("Add optional expiration to existing
  links; expired links return an expired result distinct from not-found."). Check `BROWNFIELD`,
  `IMPACT_ANALYSIS` with all 10 areas, and `CHG-01 PASS`. Review the impact analysis and design, then
  **approve `DESIGN_APPROVAL`**. The run waits in `AWAITING_IMPLEMENTATION`. **No expiration code may
  exist before this approval.** Record the run id in `docs/scenarios/README.md`.

### Tests first (after T102)

- [ ] T103 [P] [US4] Write `test/link/LinkExpirationTest.java`:
  - `expiresAt` in the future accepted and echoed;
  - not in the future ⇒ 400 `VALIDATION`;
  - after expiry `GET /r/{code}` ⇒ 410 `EXPIRED`, no redirect, count unchanged;
  - `expiresAt` null never expires (injectable `Clock`);
  - the idempotency fingerprint includes `expiresAt` (same key, different `expiresAt` ⇒ 409).

  (FR-URL-001, 008, 009, 011)
- [ ] T104 [P] [US4] Extend `test/workflow/rules/CapabilityRegistryTest.java`: EXPIRATION
  `IMPLEMENTED`, with approved statements citing FR-URL-008/009 and an expiration acceptance probe.
- [ ] T105 [P] [US4] Write `test/scenario/ScenarioBTest.java` (MockMvc, fixture evidence):
  - brownfield branch taken, impact report complete;
  - `IMPLEMENT` not eligible before design approval;
  - evidence, then parallel validation including the expiration probe, then release;
  - `COMPLETED`.

  (FR-SCN-002)
- [ ] T106 [US4] Run `./mvnw test` for T103–T105 (under `src/test/java/com/agentic/shortener/`) and record the expected failures in the Phase 8 checkpoint.

### Implementation

- [ ] T107 [US4] Create `src/main/resources/db/migration/V3__link_expiration.sql`: `ALTER TABLE link
  ADD COLUMN expires_at TIMESTAMP WITH TIME ZONE NULL` ("NULL = never expires").
- [ ] T108 [US4] Update `main/link/Link.java`, `LinkService.java` (future-only validation;
  resolve ⇒ ACTIVE/NOT_FOUND/EXPIRED via an injected `java.time.Clock`; fingerprint includes
  `expiresAt`), `LinkController.java` (`expiresAt` field) and `RedirectController.java` (410, no count
  increment).
- [ ] T109 [US4] Update `main/workflow/rules/CapabilityRegistry.java`: EXPIRATION `PLANNED →
  IMPLEMENTED`, with components, migration V3, tests, approved behavior statements and the expiration
  probe used by `TestStageExecutor`.
- [ ] T110 [US4] Run `./mvnw verify`. All tests green (record red → green).
- [ ] T111 [US4] **HUMAN** (records in `docs/scenarios/README.md`): commit (e.g. `feat: add optional link expiration (SCN-B brownfield)`),
  restart the app, record the SCN-B evidence (changed artifacts + commit id + requirement IDs), and
  **approve `RELEASE_APPROVAL`**. The run reaches `COMPLETED`.

**Checkpoint (HUMAN review)**.

---

## Phase 9: User Story 5 — Resolve an ambiguous requirement: SCN-C (P2) — plan slice 7, part 2

**Goal**: genuine suspension, real human clarification, replan, and an implementation decision made
only after the clarification.

**Independent test**: `ScenarioCTest`. Live SCN-C reaches `COMPLETED`.

- [ ] T112 [P] [US5] Write `test/scenario/ScenarioCTest.java` (MockMvc) covering two paths:
  - (a) clarification consistent with the implemented behavior ⇒ plan 2, `implementationRequired =
    false`, evidence with `noChangeJustification` accepted, then `COMPLETED`;
  - (b) clarification that requires new behavior ⇒ `implementationRequired = true`, and
    `noChangeJustification` refused.

  Also: no node after `CLARIFICATION` runs before the clarification; plan-1 decisions are refused
  after the replan. (FR-SCN-003, SC-007)
- [ ] T113 [US5] Run `./mvnw test` for `test/scenario/ScenarioCTest.java` (red, if any gap remains), fix any gap within already-approved behavior,
  then run `./mvnw verify` (green).
- [ ] T114 [US5] **HUMAN** (records in `docs/scenarios/README.md`): submit live "Make links expire." ⇒ `AWAITING_CLARIFICATION` (AMB-R2,
  AMB-R4). Provide the **real** clarification. Inspect the replanned `DESIGN` (`implementationRequired`,
  `changesApprovedRequirements`), then:
  - if the clarification conflicts with an approved requirement, it is refused (`409`). Decide
    whether to submit a requirement change, which requires a SpecKit spec amendment first;
  - if `implementationRequired = true`, STOP. New tasks are added through change control
    (`/speckit.converge`) before any code is written;
  - otherwise, approve the design, record evidence with `noChangeJustification`, and approve release.

  Record the outcome in `docs/scenarios/README.md`.

**Checkpoint (HUMAN review + commit)**.

---

## Phase 10: Polish & Cross-Cutting Concerns — plan slice 8

- [ ] T115 [P] Write `src/test/java/com/agentic/shortener/PerformanceMeasurementTest.java`
  (`@Tag("measurement")`): measure PVT-003 (p95 create/redirect), PVT-004 (SCN-A automated-active
  duration) and PVT-005 (startup to healthy). Run `./mvnw test -Dgroups=measurement` and record the
  actual results, labeled demonstration and non-blocking, in `docs/assessment/measurements.md`.
- [ ] T116 [P] Create `scripts/export-run.sh`. It uses `curl` to fetch `GET /api/workflows/{id}`,
  `/events`, `/decisions`, `/report` and `GET /api/metrics/workflows?faultInjected=false|true` into
  `docs/scenarios/<scenario>/`.
- [ ] T117 **HUMAN**: run `scripts/export-run.sh` for the live SCN-A, SCN-B and SCN-C runs and commit
  the exported **runtime** evidence under `docs/scenarios/scn-a/`, `scn-b/` and `scn-c/`. Never edit
  it by hand.
- [ ] T118 [P] Write `docs/traceability/matrix.md`: requirement → scenario → ADR → task → code → test →
  evidence. List only tests that have actually been executed; no orphans. Include:
  - **SC-003 population** (CHK028): every HUMAN_GATE crossing attempt in the gate/scenario tests
    and the three live runs, each with its preceding valid HUMAN decision; expected 100%;
  - **approval-before-evidence** (CHK024): for each live run, the `DESIGN_APPROVAL` decision time,
    the evidence time and the cited revision's commit date.
- [ ] T119 [P] Write a concise `README.md`:
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
    - transitive Hibernate is LGPL-2.1+ (research R11).
- [ ] T120 Run quickstart.md validation end to end on a fresh `./data`, and fix any documentation
  mismatch (documentation only; any behavior change goes back through change control).
- [ ] T121 Clean-clone verification: `git clone` into a temp directory, `./mvnw verify`, start
  the app, `GET /actuator/health` returns `UP`. Run `./mvnw verify` normally (a populated cache
  makes it offline). Record the actual commands and results in
  `docs/assessment/measurements.md`.

**Checkpoint (HUMAN review + commit)**. Then the remaining lifecycle stages follow: full validation,
`/speckit.converge`, the independent final assessment, and the final engineering summary.

---

## Dependencies & Execution Order

### Phase dependencies (critical path, plan §Delivery slices)

```text
Phase 1 Setup → Phase 2 Foundational → Phase 3 US1-A ──(T046 HUMAN design approval)──► Phase 4 US2
→ Phase 5 US1-B ──(T073 HUMAN evidence + release)──► Phase 6 US3 → Phase 7 US5-core/US6
→ Phase 8 US4 (T102 HUMAN approval before code) → Phase 9 US5 → Phase 10 Polish
```

- Phase 4 must not start before T046: the greenfield implementation follows design approval.
- T103–T109 must not start before T102: the brownfield change follows impact analysis and design
  approval (FR-SCN-002).
- The workflow graph (T024) is frozen after Phase 3. Migrations after V1 are additive only (ADR-0002).

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
- Phase 2: test tasks T010–T016 all [P]; implementation T018, T020, T021 and T023 [P].
- Phase 3: tests T029–T035 [P]; T037–T039 [P].
- Phase 4: tests T047–T051 [P]; T054–T056 [P].
- Phase 5: tests T061–T065 [P]; T067–T069 [P].
- Phase 6: tests T074–T080 [P].
- Phase 7: tests T090–T094 [P].
- Phase 8: tests T103–T105 [P].
- Phase 10: T115, T116, T118 and T119 [P].

### Parallel example — Phase 4 (US2)

```text
Task: "T047 UrlValidatorTest"        Task: "T048 ShortCodeGeneratorTest + collision"
Task: "T049 LinkApiTest"             Task: "T050 LinkStorageFailureTest"
Task: "T051 LinkConcurrencyTest"
then: T054 entities/repos ‖ T055 UrlValidator ‖ T056 ShortCodeGenerator → T057 LinkService → T058 controllers
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
  only from the running system (T117).
- `/speckit-implement` reads `checklists/orchestration.md` checkbox state as a gate. 42 items are
  currently unchecked, pending reviewer evaluation.
