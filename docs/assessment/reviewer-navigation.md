# Reviewer navigation

Where to find the evidence for each part of the assignment (T138).

## 10-minute reading order

1. [README](../../README.md): what it is, how to run it, limitations and trade-offs.
2. [Architecture overview](../architecture/overview.md): components, the graph, control flow, autonomy boundary,
   and a table mapping each assignment requirement to a component.
3. [Scenario log](../scenarios/README.md): the three live runs with decision ids, times and measured intervals.
4. [Human gates log](../governance/human-gates-log.md): every human decision and its evidence; SC-003 16/16.
5. [Traceability matrix](../traceability/matrix.md): requirement → task → code → test → executed result.

## Assignment requirement → evidence

| Assignment | Where it is shown |
|---|---|
| §1 requirement → reviewable outcome, controlled autonomy | [overview §5 autonomy boundary](../architecture/overview.md); final report `GET /api/workflows/{id}/report` |
| §3 greenfield, brownfield, ambiguous; tests and docs | [scenario log](../scenarios/README.md); `ScenarioATest`, `ScenarioBTest`, `ScenarioCTest` |
| §4.1 requirement understanding, ambiguity, normalization | UNDERSTAND stage; rules AMB-R1…R4 ([research R5](../../specs/001-agentic-sdlc-url-shortener/research.md)); `AmbiguityRulesTest`; ambiguous-scenario suspension |
| §4.2 decomposition with dependencies and sequencing | DECOMPOSE output (tasks, requirement IDs, acceptance checks); the explicit graph ([overview §3](../architecture/overview.md)) |
| §4.3 codebase reasoning: modules, services, APIs, data flows | IMPACT_ANALYSIS (ten areas + data flows); [brownfield impact analysis](../scenarios/brownfield-impact-analysis.md) |
| §4.4 graph, entry/exit gates, sequential + parallel + join | `WorkflowGraph`; `WorkflowEngineTest`, `ParallelValidationTest` |
| §4.4 context and decision lineage | persisted stage outputs; `GET …/decisions`; replan invalidations |
| §4.4 human approval checkpoints | `HumanGateTest`; [human gates log](../governance/human-gates-log.md) |
| §4.4 bounded retries, fallback, rollback, safe-stop | `RetryTimeoutTest`, `FallbackTest`, `RollbackCompensationTest`, `SafeStopResumeTest`, `RestartPersistenceTest` |
| §4.4 policy guardrails (security, compliance, change control) | policy v1 (`PolicyV1Test`, `PolicyExceptionTest`); change-control refusal (`ClarificationTest`) |
| §4.4 audit-grade observability and traceability | `GET …/events` (append-only, gap-free), AUD-01, final report citing audit `seq`; `AuditServiceTest` |
| §4.4 metrics: success rate, retry/rollback, MTTR, latency | `GET /api/metrics/workflows`; `MetricsTest`; [measurements](measurements.md) |
| §4.4 dynamic re-plan when upstream outputs change | `Replanner`; `ReplannerTest`, `RequirementChangeAndReworkTest`; ambiguous scenario plan 1 → 2 |
| §4.5 code, API/schema, tests, docs | `src/`; [OpenAPI](../../specs/001-agentic-sdlc-url-shortener/contracts/openapi.yaml) validated by `OpenApiContractTest`; Flyway migrations V1–V3; 276 automated tests |
| §4.6 risks, failure scenarios, guardrails | README limitations; residual risks in each release approval; [ADR-0005](../adr/0005-reliability-and-recovery.md) |
| §4.7 agents execute, humans approve | actor model ([ADR-0004](../adr/0004-human-governance-actor-model-replanning.md)); AGENT refused at gates in tests |
| §4.8 final engineering summary | [final-engineering-summary.md](final-engineering-summary.md) (written after convergence) |
| §5 runnable prototype, setup, testing approach | README; [quickstart](../../specs/001-agentic-sdlc-url-shortener/quickstart.md) |

## Process evidence (SpecKit lifecycle)

[constitution](../../.specify/memory/constitution.md) → [spec](../../specs/001-agentic-sdlc-url-shortener/spec.md) →
[plan](../../specs/001-agentic-sdlc-url-shortener/plan.md) → [ADRs](../adr/README.md) →
[checklists](../../specs/001-agentic-sdlc-url-shortener/checklists/) →
[tasks](../../specs/001-agentic-sdlc-url-shortener/tasks.md) →
[pre-implementation review](pre-implementation-review.md) →
phase commits (`git log`), each test-first with the red and green runs recorded in the traceability matrix.

## Live evidence exports

`docs/scenarios/greenfield/`, `brownfield/`, `ambiguous/`: run, events, decisions, report and metrics exported from the running
application by `scripts/export-run.sh` (never edited by hand).
