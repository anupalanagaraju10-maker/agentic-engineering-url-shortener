# Architecture overview

An agentic software-engineering workflow that governs changes to a URL shortener: it takes a
requirement, understands and decomposes it, designs the change, waits for human approval, records the
implementation done outside the application, validates the running build in parallel, checks release
readiness and waits for release approval. Decisions behind this design: [ADR-0001…0005](../adr/README.md).

## 1. System context

```text
 candidate (HUMAN)            Claude Code (AGENT)                 visitors / clients
   approves, rejects,           writes code in the repo,            create links,
   clarifies, reworks,          records implementation              follow /r/{code}
   resumes, terminates          evidence (no gate power)                  │
        │   REST                     │   REST                             │ REST
        ▼                            ▼                                    ▼
 ┌───────────────────────────────── one Spring Boot process ──────────────────────────────────┐
 │  workflow plane (control)                         link plane (application)                 │
 │  API → DecisionService / Replanner → WorkflowEngine  ──calls──►  LinkService, UrlValidator │
 │        policy, rules, stages, audit, metrics          (one-way)   Link, idempotency         │
 │                         │                                              │                    │
 │                         └──────────── H2 file database (Flyway) ───────┘                    │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

The workflow plane may call the link plane (probes, validator); the link plane never depends on the
workflow plane (enforced by `PlaneBoundaryTest`, ADR-0001).

## 2. Components

| Package | Responsibility |
|---|---|
| `link` | the URL shortener: create, redirect (302 `no-store`), analytics, idempotency, optional expiration (410) |
| `workflow.engine` | the DAG engine: waves, gates, retry/timeout/fallback, rollback, compensation, safe-stop, resume, replanning, startup recovery, fault injection |
| `workflow.stages` | one executor per automated node (INTAKE … FINAL_REPORT) |
| `workflow.rules` | deterministic ambiguity rules (AMB-R1…R4), recorded-behavior rules, capability registry |
| `workflow.policy` | policy v1: PRIV-01, SEC-01, CHG-01, DEP-01, AUD-01 |
| `workflow.persistence`, `workflow.audit` | runs, stages, decisions, policy results; append-only audit trail |
| `workflow.metrics` | demonstration metrics derived from runs and audit events |
| `workflow.api` | REST API ([contracts/openapi.yaml](../../specs/001-agentic-sdlc-url-shortener/contracts/openapi.yaml), validated by `OpenApiContractTest`) |

## 3. Orchestration model: the explicit dependency graph

```text
INTAKE → UNDERSTAND → CLARIFICATION◇ → DECOMPOSE → IMPACT_ANALYSIS◇ → DESIGN → DESIGN_APPROVAL▣
       → IMPLEMENT⧉ → ┌ TEST ┐
                      ├ DOCS ┤ → RELEASE_READINESS (join) → RELEASE_APPROVAL▣ → FINAL_REPORT
                      └ SECURITY ┘
◇ conditional (CLARIFICATION if ambiguous, IMPACT_ANALYSIS if brownfield)   ▣ human gate   ⧉ external action
```

- **Entry/exit gates**: every node declares dependencies, an entry condition and an exit condition; a
  node runs only when all dependencies are SUCCEEDED or SKIPPED.
- **Sequential and parallel paths with synchronization**: eligible automated nodes run as one *wave*
  on a 4-thread pool; TEST, DOCS and SECURITY form the parallel group; RELEASE_READINESS is the join.
- **Human gates** (CLARIFICATION, DESIGN_APPROVAL, RELEASE_APPROVAL) and the external IMPLEMENT action
  stop the run until a decision or evidence arrives; there is no timeout, default or automatic approval.
- **Context and decision lineage**: every stage output is persisted and passed downstream; every
  decision (branch, approval, rejection, clarification, evidence, invalidation, resume…) is a row with
  its actor, reason and plan version.

## 4. Control flow of one command

1. A REST command (e.g. approve) takes the run's lock (a busy run gets `409`, never waits).
2. The command is validated: actor type, required fields, waiting state, plan version. A refused
   command is itself recorded (`DECISION_REFUSED`).
3. The engine advances synchronously: each wave is claimed in its own short transaction, executed on
   pool threads (logic only), then persisted on the coordinating thread (output + status + audit event
   in one transaction), followed by post-stage policy evaluation.
4. Advancement stops at a gate, the external action, a safe-stop or a final state; the response shows
   the run.

## 5. Autonomy boundary (who does what)

| Actor | May | May not |
|---|---|---|
| **SYSTEM** (`workflow-engine`) | run automated stages, take branches, record events, invalidate decisions on replan | be named by an API caller |
| **AGENT** (`claude-code`) | submit requirements; record implementation evidence | approve, reject, clarify, rework, resume, terminate, decide policy exceptions |
| **HUMAN** (the candidate) | every gate decision, clarification, rework, requirement change, resume, terminate, policy exception | — |

The stage "agents" inside the application are **deterministic** executors: reproducible rules and the
capability registry, not a language model (AMB-001; FR-ORC-012 determinism is tested). The coding agent
is Claude Code, working outside the application; its work enters the workflow only as structured
evidence at IMPLEMENT, and TEST/SECURITY then verify the running build independently.

## 6. Governance and guardrails

- **Policy v1**, evaluated after its bound node: PRIV-01 (privacy, after UNDERSTAND); SEC-01 (URL
  safety), CHG-01 (impact analysis for brownfield), DEP-01 (dependencies) after DESIGN; AUD-01 (audit
  completeness) at RELEASE_READINESS. FAIL ⇒ safe-stop; EXCEPTION_REQUESTED ⇒ the run waits for a HUMAN
  exception decision (scope, compensating control, expiry or review condition recorded).
- **Change control**: a clarification that contradicts an approved requirement is refused (`409
  CHANGE_CONTROL_REQUIRED`) and must go through a requirement change.
- **Evidence rules**: changed artifacts need a revision; "no change" needs a justification and is accepted
  only when the design says no implementation is required; requirement IDs must be in the run's scope;
  evidence must follow a valid design approval.

## 7. Reliability

| Mechanism | Behavior |
|---|---|
| Retry | transient failures only; 3 attempts; backoff 100 ms, 200 ms |
| Timeout | per automated stage (5 s); the attempt's cancellation token is revoked; late results are discarded |
| Fallback | DOCS only: a minimal template with every required section, labelled FALLBACK |
| Rollback | a failed attempt commits nothing; the stage returns to PENDING |
| Compensation | probe links already committed by TEST are deleted by an idempotent sweep |
| Safe-stop / resume | recoverable stops (retries exhausted, restart) resume on HUMAN request and re-run only failed/pending nodes; non-recoverable stops (policy FAIL, compensation failure, blocked readiness) are final |
| Startup recovery | runs interrupted by a restart are rolled back, swept and safe-stopped as `INTERRUPTED` |
| Fault injection | demonstration only, off by default (`demo` profile), every effect labelled `injected` |

Recovery incidents and metrics (success rate, retry/rollback frequency, MTTR, end-to-end and
automated-active latency) are derived from the audit events — nothing is tracked alongside them.

## 8. Replanning when upstream outputs change

Clarification, requirement change and rework all use one mechanism, in **one database transaction**:
the changed node and all its descendants are reset (prior outputs kept in `STAGE_INVALIDATED` events),
approvals and implementation evidence on them are invalidated, the plan version increases, and
`PLAN_REPLANNED` records old/new version, reason, affected and preserved nodes. Unaffected work is kept
(e.g. reworking DOCS after a rejected release keeps IMPLEMENT, TEST and SECURITY). If anything fails, the
run is unchanged and `REPLAN_ABORTED` is recorded.

## 9. Why each part exists (assignment §4.4 → component)

| Assignment requirement | Component |
|---|---|
| explicit dependency graph with entry/exit gates | `WorkflowGraph`, `StageExecutor` entry/exit conditions |
| sequential and parallel paths with synchronization | wave scheduling, parallel group, RELEASE_READINESS join |
| preserve cross-stage context and decision lineage | persisted stage outputs, `decision` table |
| human approval checkpoints | three gates + HUMAN-only actor rules |
| bounded retries, fallback, rollback, safe-stop | `WorkflowEngine`, `CompensationService`, `StartupRecovery` |
| policy guardrails for security, compliance, change control | policy v1, change-control refusal |
| audit-grade observability and traceability | append-only audit trail, final report citing audit `seq` |
| reliability metrics (success, retry/rollback, MTTR, latency) | `MetricsCalculator` |
| dynamic re-plan when upstream outputs change | `Replanner` |

## 10. Key decisions and trade-offs

| Decision | Chosen | Rejected because |
|---|---|---|
| Deployment | one Spring Boot process, two planes (ADR-0001) | microservices or a workflow product add infrastructure without adding governance |
| Persistence | embedded H2 file + Flyway + Spring Data (ADR-0002) | an external database adds setup for a 2–3 day runnable prototype |
| Engine | in-process DAG with waves (ADR-0003) | Temporal/Camunda/Kafka: out of scope and harder to inspect |
| Governance | typed actors, HUMAN-only gates, one replan mechanism (ADR-0004) | free-text actors; restarting runs on every change |
| Reliability | bounded retry, DOCS-only fallback, rollback + compensation (ADR-0005) | unlimited retry; fallback for security checks |
| Stage logic | deterministic rules | an LLM in the runtime makes runs unrepeatable and is not required |

**Deliberately not built**: an LLM runtime, message broker, external workflow engine, microservices,
authentication, a policy language, automatic (non-human) replanning.

**Scaling path** (ADR-0002): an external relational database behind the same migrations and
repositories, with database or distributed locking replacing the in-memory per-run lock, would allow
several application instances.
