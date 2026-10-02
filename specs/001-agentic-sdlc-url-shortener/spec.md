# Feature Specification: Agentic Software Engineering System — URL Shortener

**Feature Branch**: `001-agentic-sdlc-url-shortener` (spec directory; no git branch created)

**Created**: 2026-10-01

**Status**: Approved by the human candidate on 2026-10-01 (requirements review and clarification
session complete)

**Input**: Candidate's `/speckit.specify` description (2026-10-01): "Create the feature
specification for Agentic Software Engineering System — URL Shortener … a governed, stateful,
non-linear agentic SDLC orchestration system … The URL shortener is the demonstration domain."
Governing document: `.specify/memory/constitution.md` v1.0.0 (ratified 2026-10-01).

## How to Read This Specification

**Requirement source tags** (every requirement carries exactly one):

- **[C] Confirmed** — stated explicitly in the candidate's specify input or the ratified
  constitution.
- **[D] Derived** — not stated explicitly, but necessary for a confirmed requirement to be
  testable or coherent. Derived requirements are proposals for human review.

**Requirement ID scheme**: `FR-URL` (URL shortener), `FR-ORC` (orchestration), `FR-HUM`
(human governance), `FR-REL` (reliability), `FR-POL` (policy & change control), `FR-OBS`
(observability & evidence), `FR-SCN` (scenarios), `NFR` (non-functional), `SC` (success
criteria), `PVT` (validation targets), `ASM` (assumptions), `CON` (constraints),
`EXC` (exclusions), `AMB` (ambiguities, all resolved). IDs are stable; retired IDs are never reused.

**Terminology**:

- *Operator* — the human candidate (or reviewer) interacting with the system.
- *Workflow run* — one execution of the governed SDLC workflow for one requirement.
- *Stage* — one node (activity) in the workflow's dependency graph.
- *Gate* — a point where the workflow cannot progress without a recorded human decision.
- *Plan version* — a numbered revision of a run's decomposition/design that changes only via
  replanning.

## Clarifications

### Session 2026-10-01

- Q: How should the system decide that a submitted requirement is ambiguous and must stop for
  clarification? → A: Option A — deterministic rule-based detection; stop only when a material
  ambiguity prevents safe or testable implementation (unclear observable outcome; missing
  condition, trigger, duration or threshold; conflicting parts; multiple materially different
  interpretations). Record the triggered rule and why. Missing edge-case detail alone is not
  ambiguity (FR-ORC-016).
- Q: Which requirement texts should the three demonstration scenarios use? → A: Option A —
  SCN-A: create a short link for a valid HTTP/HTTPS address, redirect to the original address,
  record redirect count and last redirect time; SCN-B: add optional expiration to existing
  links, expired links return an expired result distinct from not-found; SCN-C: "Make links
  expire." (detect, stop for clarification, then replan and resume). No extra functionality
  (FR-SCN-001..003).
- Q: Which proposed numeric targets (PVT-001 to PVT-008) should be approved now? → A: Option A —
  approve PVT-001, PVT-002, PVT-006, PVT-007, PVT-008 as proposed; PVT-003, PVT-004, PVT-005 are
  measured and reported as demonstration figures but do not block release.

### Session 2026-10-02 (orchestration checklist gate)

- Q: Which safe-stop conditions are recoverable? → A: Copied from the approved plan and ADR-0005;
  no new behavior. Recoverable: retries exhausted without fallback; restart interruption.
  Not recoverable: mandatory policy FAIL; rejected policy exception; invalid state; compensation
  failure (FR-REL-008, CHK009).
- Q: Should the official assessment brief be copied into the repository? → A: No. ASM-001 is kept
  as an explicitly accepted assumption; the brief remains the external authoritative source above
  repository artifacts (CHK042).

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Govern a clear requirement through the workflow (Priority: P1)

The operator submits a clear, complete, testable requirement. The system understands and
normalizes it, decomposes it into tasks, produces a design, stops for design approval, then
performs implementation, runs independent activities in parallel, synchronizes at a join,
checks release readiness, and stops for release approval before completing. Every
step is persisted and auditable.

**Why this priority**: This is the core subject of the assessment: governed, stateful,
non-linear orchestration with human control.

**Independent Test**: Submit the SCN-A requirement, approve at each gate, and verify the run
reaches *Completed* with the expected stage path, parallel overlap, join, gates and audit trail.

**Acceptance Scenarios**:

1. **Given** a clear requirement, **When** the operator submits it, **Then** a workflow run is
   created with a unique identifier, policy version and plan version 1, and proceeds through
   understanding without entering clarification.
2. **Given** the run reaches design approval, **When** no human decision is recorded, **Then**
   the run remains awaiting approval indefinitely and performs no downstream work.
3. **Given** a group of independent activities becomes eligible at the same time, **When** the
   system continues, **Then** their executions overlap in time.
4. **Given** one activity in a parallel group has not yet succeeded, **When** the others
   succeed, **Then** work that depends on the join does not start.
5. **Given** release approval is granted, **When** the run finishes, **Then** its final outcome
   is *Completed* and the audit trail contains every stage transition and decision in order.

---

### User Story 2 — Shorten, resolve and measure links (Priority: P1)

A client submits a long web address and receives a short code and short link. Visiting the short
link sends the visitor to the original address. Basic usage counts are recorded. Invalid or
unsafe addresses are refused.

**Why this priority**: The demonstration domain must work correctly for the workflow's
implementation and validation stages to have something real to act on.

**Independent Test**: Create links, follow them, inspect analytics and exercise invalid,
unknown, expired, duplicate and concurrent cases without using the workflow.

**Acceptance Scenarios**:

1. **Given** a valid `https` address, **When** a client requests a short link, **Then** a unique
   short code and short link are returned.
2. **Given** an address using the `javascript`, `file` or `data` scheme, **When** a client
   requests a short link, **Then** the request is refused with a validation error and no link is
   created.
3. **Given** an existing active short code, **When** a visitor follows it, **Then** the visitor is
   redirected to the original address and the redirect count increases by one.
4. **Given** a code that was never issued, **When** a visitor follows it, **Then** a not-found
   outcome is returned.
5. **Given** a link whose expiration time has passed, **When** a visitor follows it, **Then** an
   expired ("gone") outcome is returned that is distinguishable from not-found, and no redirect
   occurs.

---

### User Story 3 — Recover safely from failures (Priority: P2)

When a stage fails, the system classifies the failure, retries only transient failures within a
bound, uses an explicit fallback where one is defined, rolls back or compensates where needed,
and safe-stops when it cannot reach a safe state. A safe-stopped or interrupted run can be
resumed without repeating successful work.

**Why this priority**: Recovery behavior is a required assessment capability and the source of
reliability metrics.

**Independent Test**: Inject a transient failure, a permanent failure, a timeout and a
non-recoverable failure into specific stages and verify retries, exhaustion, fallback,
compensation, safe-stop and resume.

**Acceptance Scenarios**:

1. **Given** an injected transient failure that clears on the second attempt, **When** the stage
   runs, **Then** it is retried, succeeds, and both attempts are recorded.
2. **Given** an injected permanent failure, **When** the stage runs, **Then** it is not retried.
3. **Given** a transient failure that persists, **When** the retry limit is reached, **Then**
   retries stop, exhaustion is recorded and the run follows its defined recovery path.
4. **Given** a safe-stopped recoverable run, **When** the operator resumes it, **Then** previously
   succeeded stages are not re-executed and execution continues from the first incomplete stage.
5. **Given** the system restarts while a run is waiting at a gate, **When** it comes back,
   **Then** the run's state, outputs and history are unchanged and the gate is still pending.

---

### User Story 4 — Change existing behavior safely (Priority: P2)

The operator submits a change to existing URL-shortener behavior. The workflow takes the
brownfield branch and performs an impact analysis before design approval and before any
implementation work.

**Why this priority**: Required scenario (SCN-B); demonstrates conditional branching and change
safety.

**Independent Test**: Submit the SCN-B requirement and verify the impact-analysis stage runs,
its output covers all required areas, and implementation cannot start before approval.

**Acceptance Scenarios**:

1. **Given** a requirement that modifies existing behavior, **When** the run is decomposed,
   **Then** the impact-analysis stage is scheduled; for a greenfield requirement it is skipped.
2. **Given** impact analysis is incomplete, **When** anything attempts to start implementation,
   **Then** implementation is not started.

---

### User Story 5 — Resolve an ambiguous requirement (Priority: P2)

The operator submits a vague requirement. The workflow detects the ambiguity, explains it, stops
and waits for human clarification. After the human answers, the system updates the requirement,
analyzes downstream impact, replans and resumes from the correct stage.

**Why this priority**: Required scenario (SCN-C); demonstrates suspension, human input,
replanning and resume.

**Independent Test**: Submit the SCN-C requirement, verify the run is awaiting clarification
with no downstream work, provide a clarification, and verify replan, plan-version increment and
resume.

**Acceptance Scenarios**:

1. **Given** an ambiguous requirement, **When** it is understood, **Then** the run enters
   *awaiting clarification*, records what is ambiguous and why it is unsafe to proceed, and runs
   no design or implementation work.
2. **Given** a run awaiting clarification, **When** the operator supplies a clarification,
   **Then** the decision is recorded with actor, time and text, the requirement is updated, the
   plan version is incremented and the run resumes.
3. **Given** no clarification is supplied, **When** time passes, **Then** the system never
   chooses an interpretation on its own.

---

### User Story 6 — Enforce policy and produce verifiable evidence (Priority: P3)

Each run is evaluated against a versioned policy. Mandatory failures block progress; exceptions
require human approval. The operator can inspect a run's full history, decisions, policy results
and reliability metrics.

**Why this priority**: Required for change control and reviewer verification; builds on all
other stories.

**Independent Test**: Trigger a mandatory policy failure and an exception request; inspect the
audit trail and metrics for a set of demonstration runs.

**Acceptance Scenarios**:

1. **Given** a mandatory policy check returns FAIL, **When** the workflow evaluates progression,
   **Then** downstream stages do not start and release readiness is blocked.
2. **Given** a policy exception is requested, **When** no human has approved it, **Then** the
   affected work does not proceed.
3. **Given** completed, failed and recovered demonstration runs, **When** the operator requests
   metrics, **Then** counts, rates, recovery durations and MTTR are reported and labeled as
   demonstration data.

---

### Edge Cases

- A short link is requested for an address with no host, an unsupported scheme, or exceeding
  the maximum length (PVT-007).
- A short link is requested with an expiration time already in the past.
- A generated short code collides with an existing one (FR-URL-005).
- Many clients create links, or follow the same link, at the same moment (FR-URL-012).
- Two requests reuse the same idempotency key with different content (FR-URL-011).
- Storage is unavailable during create, redirect or analytics recording (FR-URL-013).
- A human decision is submitted for a run that is not waiting for that decision, or is
  submitted twice (FR-HUM-007).
- A rejection is recorded at a gate (FR-HUM-005).
- One parallel activity fails while the others in its group succeed (FR-REL-010).
- A stage exceeds its time limit (FR-REL-004).
- The system restarts mid-run or while waiting at a gate (FR-ORC-006).
- A replan changes a subject that was already approved (FR-ORC-013).
- A resume is requested for a completed or non-recoverable run (FR-REL-009).
- A clear requirement omits minor edge-case detail that does not change the requested behavior;
  it proceeds without clarification (FR-ORC-016).

## Requirements *(mandatory)*

### Functional Requirements — URL Shortener (`FR-URL`)

- **FR-URL-001** [C]: Clients MUST be able to request a short link for a long address,
  optionally supplying an expiration time. A successful response MUST include the short code,
  the full short link, the original address and the expiration time if one was set.
- **FR-URL-002** [C]: The system MUST accept only syntactically valid absolute addresses with
  the `http` or `https` scheme and a non-empty host.
- **FR-URL-003** [C]: The system MUST refuse addresses using any other scheme, explicitly
  including `javascript`, `file` and `data`, with a validation error that states the reason. No
  link is created.
- **FR-URL-004** [C]: Each issued short code MUST be unique across all links ever issued, MUST
  use only URL-safe characters and MUST be short relative to typical original addresses. The
  exact length is set by the approved validation target PVT-006.
- **FR-URL-005** [C]: If a generated code collides with an existing one, the system MUST
  generate a new code, up to a bounded number of attempts. If the bound is exceeded, the request
  MUST fail with an explicit error and no link is created. The exact bound is set by the
  approved validation target PVT-006.
- **FR-URL-006** [C]: Following an active short link MUST redirect the visitor to the original
  address, and every redirect the system performs MUST be counted (FR-URL-010). Exact redirect
  and caching semantics are decided in planning/API design.
- **FR-URL-007** [C]: Following a code that was never issued MUST return a not-found outcome.
- **FR-URL-008** [C]: Following a link whose expiration time has passed MUST return an expired
  ("gone") outcome, distinguishable from not-found, without redirecting. A link without an
  expiration time never expires.
- **FR-URL-009** [D]: A requested expiration time that is not in the future MUST be refused with
  a validation error.
- **FR-URL-010** [C]: For each link the system MUST record the total number of successful
  redirects and the time of the most recent redirect, and MUST let clients read them.
  Not-found and expired attempts MUST NOT increase the redirect count.
- **FR-URL-011** [C]: A create request MAY carry an idempotency key. A repeated request with the
  same key and the same content MUST return the same result as the first, without creating a
  new link; a request with a previously used key and different content MUST be refused as a
  conflict; a request without a key is always treated as a new create request (AMB-002).
- **FR-URL-012** [C]: Under concurrent create requests every successful response MUST carry a
  distinct code, and under concurrent redirects of one link no redirect MUST be lost from the
  count (PVT-008).
- **FR-URL-013** [C]: If storage is unavailable:
  - a create request MUST fail with a service-unavailable error and MUST NOT return a code;
  - a redirect MUST return a service-unavailable error, not a not-found or expired outcome;
  - a failure to record analytics MUST be logged; whether the redirect still proceeds is
    governed by FR-URL-017.
- **FR-URL-014** [C]: The system MUST expose an operational health indication that reports
  unhealthy when storage is unavailable.
- **FR-URL-015** [C]: All error responses MUST use a consistent structured format containing an
  error category and a human-readable message, and MUST NOT expose internal stack traces.
- **FR-URL-016** [C]: The system MUST refuse addresses whose host is `localhost` or a literal
  loopback or private network address, with a validation error. The system MUST NOT resolve
  host names to perform this check, and documentation MUST state the limitation that a host name
  resolving to a private address is not blocked (AMB-003).
- **FR-URL-017** [D]: If recording analytics fails for an otherwise valid redirect, the redirect
  MAY still proceed, and the analytics failure MUST be recorded/logged. See ASM-006.

### Functional Requirements — Orchestration (`FR-ORC`)

- **FR-ORC-001** [C]: The workflow MUST be defined as an explicit dependency graph of stages in
  which each stage declares the stages it depends on. The graph MUST be inspectable by the
  operator for any run.
- **FR-ORC-002** [C]: The graph MUST contain at least these activities: requirement intake;
  understanding (normalization and ambiguity detection); clarification (conditional);
  decomposition; impact analysis (conditional); design; design approval (gate); implementation;
  testing; documentation; security/risk validation; release readiness; release approval (gate);
  and final engineering report.
- **FR-ORC-003** [C]: The graph MUST include sequential paths: a stage MUST NOT start until every
  stage it depends on has succeeded or been explicitly skipped by a recorded branch decision.
- **FR-ORC-004** [C]: Activities that are independent of each other and eligible at the same
  time MUST be executed concurrently, such that their recorded execution intervals overlap. The
  graph MUST contain at least one such group of parallel activities. Which activities form the
  parallel group is decided in `/speckit.plan`.
- **FR-ORC-005** [C]: Every group of parallel activities MUST be followed by a synchronization
  (join) point: dependent downstream work MUST NOT start until all required activities in the
  group have succeeded.
- **FR-ORC-006** [C]: Run state — status, each stage's status, attempt count, timestamps,
  outputs, failure reasons, decisions, plan version and policy version — MUST be persisted at
  every transition and MUST survive a restart of the system unchanged.
- **FR-ORC-007** [C]: The graph MUST branch conditionally at least: (a) to clarification when
  understanding detects ambiguity, otherwise directly to decomposition; (b) to impact analysis
  when the requirement changes existing behavior, otherwise skipping it. Every branch decision
  MUST be recorded with its reason.
- **FR-ORC-008** [C]: Each stage MUST have documented entry conditions and exit conditions. A
  stage whose entry conditions are not met MUST NOT start; a stage whose exit conditions are not
  met MUST NOT be marked succeeded.
- **FR-ORC-009** [C]: A stage's outputs MUST be persisted and available as inputs to every
  downstream stage (cross-stage context preservation), including after a restart.
- **FR-ORC-010** [C]: Every decision (branch, approval, rejection, clarification, retry,
  fallback, rollback, compensation, safe-stop, resume, replan, policy result) MUST be recorded
  with who or what made it, when, why, the plan version in force, and a reference to any earlier
  decision it supersedes (decision lineage).
- **FR-ORC-011** [C]: Runs and stages MUST each have a small, explicit, fixed set of status
  values that describe states (not events). The set MUST distinguish at least: work in progress,
  waiting for a human decision, halted by safe-stop, and each final outcome. Exact state names
  are defined in `/speckit.plan`.
- **FR-ORC-012** [C]: Every run MUST end in exactly one final outcome — *Completed*, *Failed* or
  *Safe-stopped* — and given the same requirement, the same human decisions and the same injected
  failures, the system MUST follow the same stage path and reach the same outcome.
- **FR-ORC-013** [C]: When an upstream requirement changes materially (including via
  clarification), the system MUST: record the change; identify affected downstream stages;
  increment the plan version; reset only affected stages for re-execution; preserve unaffected
  successful stage outputs; invalidate any approval whose subject changed; require new approval;
  and resume from the earliest affected stage. The replan MUST be recorded with old and new plan
  versions and the reason.
- **FR-ORC-014** [C]: Workflow stages MUST use deterministic system logic to produce structured,
  reviewable artifacts and MUST execute real checks where practical. The running system MUST NOT
  require an AI or language-model service (AMB-001). Every stage output MUST be labeled with how
  it was produced (actual execution, simulated, or injected failure).
- **FR-ORC-016** [C]: Ambiguity detection MUST be deterministic and rule-based. A requirement
  MUST be routed to clarification only when a material ambiguity prevents safe or testable
  implementation, at least when: (a) the intended observable outcome is unclear; (b) a required
  condition, trigger, duration or threshold is missing; (c) two parts of the requirement
  conflict; or (d) multiple materially different interpretations are possible. Each detection
  MUST record which rule triggered and why. A requirement MUST NOT be classified as ambiguous
  merely because not every edge case is written out; such details are handled as derived
  requirements or during planning when they do not materially change the requested behavior.
- **FR-ORC-015** [C]: The operator MUST be able to: submit a requirement to create a run;
  inspect a run (status, graph, stages, outputs, decisions, policy results, audit trail);
  approve; reject; clarify; resume; and submit a requirement change for an existing run.

### Functional Requirements — Human Governance (`FR-HUM`)

- **FR-HUM-001** [C]: The workflow MUST stop and wait for a recorded human decision at:
  unresolved ambiguity (clarification); design approval; release approval (which also covers
  final quality acceptance); any policy exception request; any risk acceptance; and before any
  stage action classified as destructive or irreversible.
- **FR-HUM-002** [C]: Security-sensitive decisions and material risk acceptance MUST require
  explicit human approval. The policy-exception process (FR-POL-004, FR-POL-005) applies only
  when an actual policy exception is requested.
- **FR-HUM-003** [C]: Absence of a decision MUST never be treated as approval. There MUST be no
  automatic approval, timeout-based approval or default approval.
- **FR-HUM-004** [C]: Each human decision MUST record the decision type, the actor identity
  supplied by the operator, the timestamp, a note or reason, the affected run, the gate, and the
  plan version of the subject decided on.
- **FR-HUM-005** [C]: A rejection MUST block progression past the gate and record the reason.
  The run MUST then either (a) undergo explicit human-directed correction or rework of the
  rejected subject, after which the applicable approval MUST be obtained again before
  progression, or (b) be terminated by explicit human decision. Rework does not require changing
  the underlying requirement (e.g., a rejected design can be revised and resubmitted).
- **FR-HUM-006** [D]: The system MUST refuse any approval, rejection or clarification whose actor
  is the system itself or is empty.
- **FR-HUM-007** [D]: A decision submitted for a gate the run is not currently waiting at, or for
  a plan version that is no longer current, MUST be refused without changing run state, and the
  refusal MUST be recorded.

### Functional Requirements — Reliability (`FR-REL`)

- **FR-REL-001** [C]: Every stage failure MUST be classified as *transient* or *permanent*, and
  the classification and reason MUST be recorded.
- **FR-REL-002** [C]: Only transient failures MAY be retried. Permanent failures MUST NOT be
  retried.
- **FR-REL-003** [C]: Retries MUST be bounded (PVT-001), MAY use a bounded delay, and every
  attempt MUST be recorded. When the bound is reached the system MUST record retry exhaustion and
  stop retrying.
- **FR-REL-004** [C]: Every automated executable stage for which timeout behavior is applicable
  MUST have an explicit time limit (value: PVT-002, approved). Exceeding it MUST be recorded
  as a timeout and treated as a transient failure. Human approval and clarification gates MUST
  NOT expire automatically and MUST NOT be treated as timed-out stages.
- **FR-REL-005** [C]: A stage MAY define a fallback only where the fallback still satisfies the
  stage's exit conditions. Using a fallback MUST be recorded, the stage output MUST be marked as
  fallback-produced, and a fallback MUST NOT be used to bypass a mandatory policy check, gate or
  security validation. At least one stage MUST define a demonstrable fallback.
- **FR-REL-006** [C]: The system MUST use *rollback* only where a stage's prior state can be
  restored exactly, and *compensation* (a corrective action) where a side effect has already
  taken effect. Which stages use which approach MUST be documented, and every rollback or
  compensation MUST be recorded with its start, completion and result.
- **FR-REL-007** [C]: The run MUST safe-stop when: a mandatory policy check fails without an
  approved exception; retries are exhausted and no fallback or recovery path makes continuing
  safe; run state is found invalid or inconsistent; or rollback/compensation fails to restore a
  safe state.
- **FR-REL-008** [C]: A safe-stop MUST preserve run state, prior stage outcomes, the failure
  reason, the audit history, the run identifier and whether the run is recoverable. The
  recoverable flag is set per safe-stop trigger (clarified 2026-10-02; the semantics come from the
  approved plan and ADR-0005):
  - `recoverable = true`: retries exhausted with no fallback; process restart interruption
    (`INTERRUPTED`).
  - `recoverable = false`: mandatory policy `FAIL`; rejected policy exception; invalid or
    inconsistent run state; compensation failure.
- **FR-REL-009** [C]: Resuming a recoverable run MUST reload persisted state, MUST NOT re-execute
  succeeded stages, MUST respect recorded human decisions and MUST continue only eligible work.
  Resuming a completed or non-recoverable run MUST be refused.
- **FR-REL-010** [C]: When some parallel branches succeed and another fails (partial failure),
  the succeeded branches' outputs MUST be preserved, the join MUST NOT be passed, and recovery or
  resume MUST re-execute only the failed branch.
- **FR-REL-011** [D]: The system MUST provide a controlled way to inject transient, permanent and
  timeout failures into named stages for demonstration and testing; injected failures MUST be
  labeled as injected in all records.

### Functional Requirements — Policy & Change Control (`FR-POL`)

- **FR-POL-001** [C]: Every run MUST record the policy version it is evaluated against.
- **FR-POL-002** [C]: The policy model MUST cover only these domains: security; privacy /
  sensitive-data handling; audit evidence / retention assumptions; approved dependencies and
  software licensing; change control.
- **FR-POL-003** [C]: Each applicable policy check MUST produce exactly one result: PASS, FAIL,
  EXCEPTION-REQUESTED or NOT-APPLICABLE, recorded with the check identifier, stage and reason.
- **FR-POL-004** [C]: A mandatory FAIL MUST block downstream progression. An
  EXCEPTION-REQUESTED result MUST block the affected work until a human approves or rejects the
  exception.
- **FR-POL-005** [C]: An approved exception MUST record: policy identifier, reason, scope,
  approving actor, compensating control, approval timestamp, and expiry or review condition.
- **FR-POL-006** [C]: Release readiness MUST fail while any mandatory policy failure is
  unresolved or any exception is unapproved.
- **FR-POL-007** [C]: A material upstream change MUST trigger a recorded impact analysis before
  affected downstream work is replanned (see FR-ORC-013).

### Functional Requirements — Observability & Evidence (`FR-OBS`)

- **FR-OBS-001** [C]: The system MUST record an append-only audit trail per run capturing: run
  creation; stage transitions; branch decisions; approvals and rejections; clarifications;
  retries; failures and their classification; timeouts; fallbacks; rollbacks and compensations;
  safe-stops; resumes; replans; policy results; and the final outcome. Each record MUST carry the
  run identifier, timestamp, actor, plan version and policy version.
- **FR-OBS-002** [C]: Audit records MUST NOT be modifiable or deletable through any operation
  the system offers.
- **FR-OBS-003** [C]: For each failure the system MUST record: detection time; recovery start;
  recovery completion; recovery mechanism; and whether it was recovered.
- **FR-OBS-004** [C]: The system MUST report, across runs: success and failure counts and rates;
  retry count and frequency; rollback and compensation counts; recovery durations; unrecovered
  failure count; end-to-end run duration; and MTTR, computed as total recovery duration of
  recovered failures divided by the number of recovered failures (unrecovered failures are
  excluded from the denominator and reported separately).
- **FR-OBS-005** [C]: All metrics MUST be labeled as demonstration measurements from local runs,
  not production statistics.
- **FR-OBS-006** [C]: Each run MUST maintain traceability from the original requirement to the
  normalized requirement, decomposed tasks, design, stage outputs and validation results, so a
  reviewer can follow any final outcome back to its requirement.

### Functional Requirements — Scenarios (`FR-SCN`)

- **FR-SCN-001 (SCN-A Greenfield)** [C]: The requirement "Create a short link for a valid
  HTTP/HTTPS address, redirect to the original address, and record redirect count and last redirect
  time." MUST complete the workflow without entering clarification and without impact analysis,
  producing evidence of understanding, normalization, decomposition, design, design approval,
  implementation, parallel validation, join, release readiness, release approval, documentation,
  traceability and a *Completed* outcome.
- **FR-SCN-002 (SCN-B Brownfield)** [C]: The requirement "Add optional expiration to existing
  links; expired links return an expired result distinct from not-found." MUST take the
  impact-analysis branch. The impact analysis MUST cover current behavior, requested behavior,
  affected components, interfaces, data, tests, documentation, regression risks, security and
  reliability impact, and rollback/compensation considerations, and MUST be part of the subject
  presented for design approval. Implementation MUST NOT start before that approval.
- **FR-SCN-003 (SCN-C Ambiguous)** [C]: The requirement "Make links expire." MUST: be detected as
  ambiguous with the ambiguity classified and explained; enter *awaiting clarification* with no
  design or implementation performed; record the human clarification; update the requirement;
  perform downstream impact analysis; increment the plan version; replan affected work; resume from
  the correct stage; and preserve the full audit trail. The system MUST NOT choose an
  interpretation on its own.

### Non-Functional Requirements (`NFR`)

- **NFR-001 Security** [C]: All operator and client inputs MUST be validated at the system
  boundary; unsafe address schemes MUST be refused (FR-URL-003); errors MUST NOT leak internal
  details (FR-URL-015); and documentation MUST state which protections are implemented and which
  are not (including server-side request forgery limits).
- **NFR-002 Reliability** [C]: No retry loop is unbounded; every failure ends in recovery,
  safe-stop or a recorded *Failed* outcome; no run is left without a defined status.
- **NFR-003 Maintainability** [C]: The URL-shortener capability and the orchestration capability
  MUST be separable concerns, each testable on its own.
- **NFR-004 Recoverability** [C]: Any run interrupted by a restart or safe-stop MUST be
  inspectable afterwards and, if recoverable, resumable (FR-REL-009).
- **NFR-005 Observability** [C]: The operator MUST be able to determine, for any run, its current
  status, why it is in that status, and what is required to progress.
- **NFR-006 Auditability** [C]: Every claim made in the final engineering report about a run MUST
  be verifiable from that run's persisted audit trail.
- **NFR-007 Testability** [C]: Every functional requirement MUST be verifiable by an automated
  test or a documented, repeatable demonstration step.
- **NFR-008 Change safety** [C]: No change to approved requirements, design, interfaces, data
  shapes, workflow states, security controls or release criteria proceeds without impact
  analysis and human approval.
- **NFR-009 Controlled autonomy** [C]: The system MAY progress automatically between gates but
  MUST NOT pass any gate, approve any exception or resolve any ambiguity on its own.
- **NFR-010 Local performance** [C]: The system MUST run on a single developer machine. Its
  performance MUST be measured and reported against PVT-003 to PVT-005 as demonstration figures;
  these targets do not block release.

### Key Entities *(include if feature involves data)*

- **Short Link**: original address, short code, creation time, optional expiration time.
- **Link Analytics**: per short link, redirect count and last redirect time.
- **Idempotency Record**: a client-supplied idempotency key, the content of the first request
  made with it, and the result returned (FR-URL-011).
- **Workflow Run**: identifier, scenario type, original requirement, normalized requirement,
  status, plan version, policy version, timestamps, final outcome, recoverable flag.
- **Stage Execution**: run, stage, status, attempt count, start/end times, inputs consumed,
  outputs produced (with provenance label), failure classification and reason.
- **Decision**: run, type (branch, approval, rejection, clarification, exception, replan,
  resume…), actor, time, reason, plan version, superseded decision.
- **Policy Evaluation**: run, policy version, check identifier, domain, stage, result, reason;
  plus exception details when approved (FR-POL-005).
- **Audit Event**: append-only record of anything that happened in a run (FR-OBS-001).
- **Recovery Record**: failure, detection/recovery-start/recovery-complete times, mechanism,
  recovered or not.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All three scenarios (SCN-A, SCN-B, SCN-C) can be run end to end by a reviewer
  following documented steps and each reaches its expected final outcome.
- **SC-002**: In SCN-A, the recorded execution intervals of the activities in the parallel group
  overlap, and the work after the join starts only after all of them have ended successfully.
- **SC-003**: In 100% of attempts, no stage beyond a gate starts before the corresponding human
  decision is recorded.
- **SC-004**: In 100% of injected permanent failures, zero retries occur; in 100% of injected
  persistent transient failures, attempts stop at the bound in PVT-001.
- **SC-005**: After a restart, 100% of persisted runs show identical status, stage outcomes and
  audit trail to before the restart.
- **SC-006**: On resume, zero previously succeeded stages are re-executed.
- **SC-007**: In SCN-C, the plan version increases by exactly one per material clarification,
  and only affected stages are re-executed.
- **SC-008**: 100% of `javascript`, `file` and `data` addresses in the test set are refused.
- **SC-009**: Under the concurrency level in PVT-008, all created codes are distinct and the
  final redirect count equals the number of successful redirects.
- **SC-010**: A reviewer can reproduce every reported metric (including MTTR) from the persisted
  audit and recovery records.
- **SC-011**: Performance against PVT-003 to PVT-005 is measured on a developer machine and
  reported, labeled as demonstration figures (not release-blocking).

## Validation Targets (`PVT`)

The candidate's input does not supply numbers; these values were proposed by the assistant.

**Approval status (clarification session 2026-10-01)**:

- *Approved* by the human candidate, binding for tests: PVT-001, PVT-002, PVT-006, PVT-007,
  PVT-008.
- *Demonstration targets*, measured and reported but not release-blocking: PVT-003, PVT-004,
  PVT-005.

- **PVT-001** *(approved)*: Maximum 2 retries per stage attempt sequence (3 attempts in total),
  with a bounded delay of at most 1 second between attempts.
- **PVT-002** *(approved)*: Default stage time limit of 5 seconds for automated stages
  (demonstration scale); gates have no time limit.
- **PVT-003** *(demonstration, non-blocking)*: Creating a short link and following a short link
  each complete in under 1 second for 95% of requests on a developer machine.
- **PVT-004** *(demonstration, non-blocking)*: A full SCN-A run, excluding human wait time,
  completes in under 60 seconds.
- **PVT-005** *(demonstration, non-blocking)*: The system starts and reports healthy in under 30
  seconds on a developer machine.
- **PVT-006** *(approved)*: Short codes are 7 URL-safe alphanumeric characters; at most 5
  generation attempts on collision.
- **PVT-007** *(approved)*: Maximum accepted original-address length of 2,048 characters.
- **PVT-008** *(approved)*: Concurrency check of 50 simultaneous create requests and 50
  simultaneous redirects of one link.

## Assumptions (`ASM`)

- **ASM-001** *(explicitly accepted by the human candidate, 2026-10-02)*: The official assessment
  brief is **not** copied into this repository. It remains an external authoritative source that
  ranks **above** all repository artifacts (constitution, Governance: conflict resolution). The
  candidate's `/speckit.specify` input is treated as an accurate statement of it. Requirements
  tagged [C] trace to that input or the constitution.
- **ASM-002**: There is a single operator role (the human candidate/reviewer). Actor identity on
  decisions is supplied by the operator and is not authenticated (see EXC-003); this is a
  documented assessment limitation.
- **ASM-003**: The system runs locally as a single, self-contained application with its own
  durable local storage; no external services are required to run it or its tests.
- **ASM-004**: Interaction with the system (links and workflow control) is programmatic; there is
  no graphical interface.
- **ASM-005**: *(Retired 2026-10-01 at human review: redirect/caching semantics moved to
  planning/API design; see FR-URL-006.)*
- **ASM-006**: Availability of redirects is preferred over analytics completeness: if recording
  a redirect fails, the redirect may still proceed and the failure is recorded/logged
  (FR-URL-017, Derived; approved by the human candidate 2026-10-01). The assignment requires
  appropriate failure behavior but does not dictate this trade-off.
- **ASM-007**: Demonstration failures are produced by controlled injection (FR-REL-011), not by
  real infrastructure faults.
- **ASM-008**: *(Retired 2026-10-01 at clarification: scenario texts confirmed by the human
  candidate and moved into FR-SCN-001..003.)* Consequence retained: expiration
  (FR-URL-008/009) is introduced through the brownfield scenario rather than the greenfield one.
- **ASM-009**: Audit evidence is retained for the lifetime of the local data store; no deletion
  or archival is in scope.
- **ASM-010**: No personal or sensitive data is collected beyond the original address and
  operator-supplied actor names; original addresses are not treated as sensitive.

## Constraints (`CON`)

- **CON-001**: The specification is implementation-independent; technology choices are made in
  `/speckit.plan` with accepted ADR(s) (constitution, Technology & Scope Constraints).
- **CON-002**: Single deployable application, single process; no distributed infrastructure,
  message brokers, external workflow engines or agent frameworks unless an approved requirement
  makes them strictly necessary (constitution Principle I).
- **CON-003**: Scope sized for a 2–3 day assessment.
- **CON-004**: Reference repositories may inform evidence presentation only; no code,
  requirements, test counts or architecture are copied.
- **CON-005**: Human gates, evidence integrity, mandatory security/policy blocking, truthful
  reporting and no silent drift are non-waivable (constitution Governance).

## Exclusions (`EXC`)

- **EXC-001**: Graphical user interface, dashboards and frontend applications.
- **EXC-002**: User accounts, link ownership, custom/vanity codes, link editing or deletion by
  clients, QR codes, bulk creation.
- **EXC-003**: Authentication and authorization of clients or operators.
- **EXC-004**: Advanced analytics (geography, referrer, device, time series).
- **EXC-005**: Cloud deployment, containers/orchestration platforms, horizontal scaling,
  multi-region.
- **EXC-006**: Rate limiting and abuse/malware detection of destination addresses (documented as
  production hardening).
- **EXC-007**: A general-purpose policy engine or enterprise compliance framework.
- **EXC-008**: Use of large-language-model or other AI services by the running system
  (AMB-001).
- **EXC-009**: Blocking of host names that resolve to private addresses (name-resolution-based
  checks); documented as a limitation and production hardening (AMB-003).

## Ambiguities (`AMB`)

All three were resolved by the human candidate at specification review on 2026-10-01.

- **AMB-001** — *Resolved (option A)*: What does a workflow stage actually do? **Decision**:
  stages use deterministic system logic to produce structured, reviewable artifacts and execute
  real checks where practical; the running application does not require an AI/LLM service.
  Rejected: stages calling an AI model (B); stages generating/modifying source code (C).
  Encoded in FR-ORC-014, EXC-008.
- **AMB-002** — *Resolved (option A)*: What counts as a duplicate create request? **Decision**:
  idempotency key — same key + same request returns the same result; same key + different
  request is refused as a conflict; requests without a key are normal new create requests.
  Rejected: de-duplication by original address (B); both (C). Encoded in FR-URL-011.
- **AMB-003** — *Resolved (option B)*: How are local/private/internal destinations treated?
  **Decision**: refuse `localhost` and literal loopback/private IP addresses; no
  name-resolution-based blocking; document that a host name resolving to a private address is
  not fully protected. Rejected: accept all (A); name-resolution blocking (C). Encoded in
  FR-URL-016, EXC-009.
