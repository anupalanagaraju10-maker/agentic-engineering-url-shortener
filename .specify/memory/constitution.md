# Agentic Engineering URL Shortener Constitution

## Core Principles

### I. Simplicity First (YAGNI)

- Every abstraction, class, service, database table, library, workflow state, workflow node,
  document, and ADR MUST trace to an approved requirement. If no approved requirement needs it,
  it MUST NOT be added.
- The system MUST be a single deployable application running in one process. Logical separation
  (URL-shortener application plane vs. orchestration control plane) MUST be achieved with
  packages/modules, not separate services.
- State models MUST stay small: status enums describe states, not events. Events such as retry,
  replan, policy evaluation, and compensation are recorded as audit events, not new statuses.
- Existing working code MUST be corrected incrementally (KEEP / SIMPLIFY / MODIFY / REMOVE)
  rather than rewritten, unless a rewrite passes the plan/ADR gate.

Rationale: this is a 2–3 day assessment judged on clarity and engineering judgment;
production-scale infrastructure adds risk and review cost without proving any requirement.

### II. SpecKit-Governed Lifecycle & Human Authority

- SpecKit is the ONLY lifecycle, specification, planning, task, and governance framework.
  No competing methodology, roadmap, task numbering, or project-state files may be introduced.
- The lifecycle stages (constitution → specify → clarify → plan + ADRs → checklist → tasks →
  analyze → implement → scenarios → validation → converge → final review) MUST be followed in
  order. A stage MUST NOT be skipped because implementation already exists.
- The human candidate is the final decision maker. The AI assistant MAY propose artifacts,
  ADRs, and decisions but MUST NOT mark anything approved, accepted, or ratified, and MUST NOT
  treat silence as approval.
- Each human gate (constitution, requirements, clarifications, architecture/ADRs,
  task-group checkpoints, commits, release) MUST be explicitly resolved before work proceeds
  past it.

Rationale: the assessment evaluates governed, human-controlled agentic engineering; a single
authoritative lifecycle makes every decision attributable and reviewable.

### III. Test-First Development (NON-NEGOTIABLE)

- Every behavior-bearing task MUST follow red-green-refactor: write the test, run it, observe
  the expected failure, implement the smallest compliant change, rerun, refactor, run regression.
- Implementation-first work MUST NOT be described as TDD, and Git history MUST NOT be
  manufactured to appear test-first.
- Tests MUST validate claimed behavior: domain (validation, short-code uniqueness/collision,
  redirect, expiration, analytics, idempotency), orchestration (dependencies, parallel branches,
  join, branching, gates, approval/rejection, retry/exhaustion, timeout, fallback,
  compensation, safe-stop, resume, replanning, policy failure, audit), and the greenfield,
  brownfield, and ambiguous-requirement scenarios.
- Meaningful coverage takes precedence over test count; trivial tests written to inflate
  counts are prohibited.

Rationale: tests are the primary evidence that each claim in the submission is true.

### IV. Governed, Stateful Orchestration

The agentic SDLC workflow MUST be a genuine orchestration, not a linear chain:

- It MUST be an explicit dependency graph with stage entry/exit conditions, conditional
  branching, real parallel execution of independent stages, and a synchronization (join)
  point that blocks downstream stages until all required branches succeed.
- Workflow state, stage outcomes, decisions, and plan/policy versions MUST be persisted and
  MUST survive application restart. In-memory state MUST NOT be authoritative.
- Mandatory human gates (clarification of ambiguity, design approval, release approval) MUST
  NOT be bypassable, auto-approved, or fabricated. Every decision records actor, timestamp,
  reason, workflow, and plan version.
- Failures MUST be classified (at minimum TRANSIENT vs. PERMANENT). Only transient failures
  are retried; retries MUST be bounded and every attempt recorded. Timeouts MUST be explicit.
- Fallback MUST be explicit and observable and MUST NOT mask a mandatory failure. Rollback
  is used only where prior state can truly be restored; otherwise compensation is used; if
  neither yields a safe state, the workflow MUST safe-stop with state and history preserved.
- Recoverable workflows MUST be resumable without re-running succeeded stages. Material
  requirement changes MUST trigger versioned replanning that invalidates only affected stages
  and approvals.
- Mandatory policy failure MUST block downstream execution; policy exceptions MUST NOT be
  auto-approved. Every run identifies its policy version.

Rationale: these properties are the core subject of the assessment; anything less is a
sequential script, not governed agentic execution.

### V. Secure-by-Default URL Handling

- Only `http` and `https` URLs MAY be shortened. Unsafe schemes (e.g., `javascript`, `file`,
  `data`) and malformed URLs MUST be rejected with a clear error.
- Treatment of local/private/internal addresses MUST be documented. Protections that are not
  implemented (e.g., full SSRF prevention) MUST NOT be claimed; assessment limitations MUST be
  distinguished from recommended production hardening.
- Inputs at API boundaries MUST be validated; errors MUST NOT leak internal stack traces.
- API contracts (e.g., OpenAPI) MUST be explicit and MUST NOT change silently; contract changes
  report consumers, compatibility, tests, and documentation impact.

Rationale: a URL shortener is an open redirector by nature; a minimal, honest security
baseline is required and over-claiming is itself a defect.

### VI. Evidence Integrity, Auditability & Traceability

- Test results, command output, logs, metrics, approvals, retries, audit events, failure
  simulations, workflow histories, and Git history MUST NOT be fabricated.
- Audit events MUST be persisted, append-only runtime evidence produced by the system, never
  hand-authored files presented as runtime output.
- All evidence MUST be labeled as one of: actual execution, simulated input, injected/mock
  failure, demonstration metric, assumption, or proposed future enhancement. Demonstration
  metrics MUST NOT be presented as production statistics; MTTR MUST use only recovered
  failures in its denominator.
- Traceability MUST be maintained Requirement → Scenario → Design/ADR → Task → Code → Test →
  Validation → Documentation. No orphan requirements, tasks, code, or tests. A traceability
  entry MUST NOT reference a test that has not actually been executed.
- Documentation MUST match actual behavior; documents exist only when they serve a purpose.

Compliance and change control (governance rules, not a separate framework or service):

- Every orchestration run MUST record the policy version it was evaluated against.
- The versioned policy model covers only the applicable assessment domains: security;
  privacy / sensitive-data handling; audit evidence / retention assumptions; approved
  dependencies and software licensing; and change control.
- Each applicable policy check MUST produce exactly one result: `PASS`, `FAIL`,
  `EXCEPTION_REQUESTED`, or `NOT_APPLICABLE`.
- A mandatory policy `FAIL` MUST block downstream progression.
- A policy exception MUST NOT proceed without explicit human approval. An approved exception
  records: policy identifier, reason, scope, approving actor, compensating control, approval
  timestamp, and expiry or review condition.
- Release readiness MUST be blocked by any unresolved mandatory policy failure or
  unapproved exception.
- Material changes to approved requirements, architecture, APIs/schemas, workflow states,
  security controls, or release criteria MUST have an impact analysis and appropriate human
  approval before implementation.

Rationale: the reviewer must be able to verify every claim from the repository alone, and
every policy outcome and exception must be attributable to a recorded human decision.

### VII. No Silent Architecture Drift

- If implementation reveals that an approved spec, plan, ADR, or task is impractical, the
  affected work MUST stop; the problem, options, and a recommended simplest option MUST be
  presented; and the change MUST go back through the appropriate upstream SpecKit stage and
  human approval before implementation resumes.
- Brownfield changes MUST be preceded by a documented impact analysis (current vs. requested
  behavior, modules, data/schema, API contracts, tests, docs, security, reliability,
  migration, rollback/compensation, tasks needing replan) and human review when material.

Rationale: undocumented divergence destroys traceability and makes governance claims false.

## Technology & Scope Constraints

- Java 21, Spring Boot, Maven, file-backed H2, one JVM, REST, Actuator, JUnit/MockMvc, and an
  in-process workflow engine are currently preferred planning inputs, not ratified
  implementation decisions. Final technology selection occurs during `/speckit.plan` and
  requires accepted ADR(s). Feature specifications produced by `/speckit.specify` MUST remain
  implementation-independent and MUST NOT include language, framework, or database details
  merely because they are listed here.
- PROHIBITED unless an approved requirement makes them strictly necessary: message brokers
  (Kafka, RabbitMQ), Redis, external workflow engines (Temporal, Camunda), agent frameworks
  (LangGraph, CrewAI, AutoGen), LLM/vector-database infrastructure, multiple microservices,
  separate workflow/agent servers, Kubernetes or container orchestration, distributed
  locking, complex authentication systems, frontend applications, and cloud deployment.
- The URL-shortener domain stays intentionally small: create link, redirect (404 unknown,
  410 expired), optional expiration, basic analytics (count, last redirect), defined
  duplicate/idempotency behavior, and health.
- ADRs are written only for material decisions; tightly related decisions MAY share one ADR.
  ADR status is set to Accepted only by the human candidate.

## Development Workflow & Quality Gates

- Implementation proceeds one coherent, dependency-safe task group at a time. Each group
  includes its tests, documentation update, and traceability update, then STOPS at a
  checkpoint reporting: tasks completed, requirements addressed, ADRs followed, files changed,
  tests written first and their observed failures, validation commands actually executed and
  their real results, deviations, risks, assumptions, and the recommended commit boundary.
- Before any important commit, a pre-commit review of only the uncommitted changes MUST
  confirm requirement mapping, ADR compliance, API/schema/persistence/orchestration/security/
  reliability impact, passing tests, documentation, traceability, and absence of unrelated
  changes. A commit MUST NOT be recommended if relevant tests fail or required validation has
  not run.
- Commits are small, coherent, honest, and use conventional prefixes (`chore:`, `docs:`,
  `test:`, `feat:`, `fix:`, `refactor:`). History is never rewritten to improve appearance.
- `/speckit.analyze` MUST pass (no unresolved CRITICAL findings) before `/speckit.implement`.
  `/speckit.converge` MUST conclude READY, READY WITH ACCEPTED LIMITATIONS, or NOT READY;
  READY MUST NOT be declared while a mandatory requirement is incomplete.
- External reference repositories MAY be studied for patterns only; their code, documentation,
  and test counts MUST NOT be copied.

## Governance

- This constitution supersedes all other practices in this repository. Every spec, plan,
  ADR, task list, analysis, convergence report, and review MUST check compliance with it;
  `/speckit.plan` records this in its Constitution Check.
- Any added complexity that conflicts with Principle I MUST be justified in the plan's
  Complexity Tracking section and approved by the human candidate.
- Amendments: proposed by the assistant or the candidate, documented with a Sync Impact
  Report, ratified only by the human candidate, and followed by a review of dependent
  artifacts for consistency.
- Versioning: MAJOR for removing or redefining a principle or governance rule; MINOR for a
  new principle/section or materially expanded guidance; PATCH for clarifications and wording.
- Constitutional exceptions: any exception to this constitution MUST identify the principle
  affected, state the reason and scope, identify the risk and compensating control, receive
  explicit human approval, and be recorded before the affected work proceeds.
- Non-waivable rules: the following MUST NOT be silently waived — human approval authority;
  evidence integrity (no fabricated evidence); mandatory security or policy blocking behavior;
  truthful test and execution reporting; no silent architecture drift. If an assessment
  requirement conflicts with one of these, work MUST stop and the conflict MUST be surfaced
  for human resolution.
- Conflict resolution: when principles appear to conflict, (1) official assignment
  requirements take precedence; (2) safety/security and evidence integrity MUST NOT be
  weakened silently; (3) the simplest compliant solution is chosen; (4) material ambiguity is
  escalated to the human candidate.
- Release blocking: unresolved constitutional non-compliance blocks a READY release status
  unless it is explicitly handled through an approved exception where exceptions are allowed.
- Status: **RATIFIED** by the human candidate on 2026-10-01. It governs all subsequent
  work in this repository.

**Version**: 1.0.0 | **Ratified**: 2026-10-01 | **Last Amended**: 2026-10-01
