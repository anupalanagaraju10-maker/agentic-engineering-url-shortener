# Final assessment

- **Date**: 2026-10-02, after `/speckit.converge` reported *Converged* (no gaps) at commit `1d91773`.
- **Assessor**: Claude Code, at the candidate's request, reviewing work it largely implemented. It is
  therefore written adversarially: weaknesses are stated as plainly as strengths.
- **Basis**: the assignment's evaluation criteria (§6), the spec, the three live runs and their exports,
  `./mvnw verify` (277 tests, 0 failures; clean-clone verified), the traceability matrix and the human-gates
  log. No claim below relies on anything that was not executed or recorded.

## Verdict

**The prototype meets every core requirement of the assignment and every requirement of the approved
specification, with real evidence.** Its main weaknesses are the deliberately deterministic (non-LLM) stage
agents, self-reported implementation evidence, a single-instance deployment, and live parallelism that is
measurable only for the slower TEST stage. All of these are documented limitations rather than hidden gaps.

## By evaluation criterion

| Criterion (assignment §6) | Assessment | Evidence | Weaknesses |
|---|---|---|---|
| **Effectiveness of agentic orchestration** | Strong | explicit 14-node graph; gates; parallel group and join; retries, timeouts, fallback, rollback, compensation, safe-stop, resume; atomic replanning; policy guardrails; all three live scenarios COMPLETED | the in-app stage agents are deterministic rules, not an AI; the "agent" that writes code is Claude Code outside the application, and the workflow only sees its evidence |
| **Architecture / system design quality** | Strong | two planes with an enforced one-way dependency; one write path (`WorkflowStore`); explicit concurrency rules (thread ownership, short transactions, cancellation, run lock); append-only audit; five ADRs | `WorkflowStore` is large (≈ 790 lines); in-memory run locks limit the design to one instance |
| **Depth of decomposition and execution quality** | Strong | SpecKit lifecycle end to end (constitution → spec → clarify → plan → ADRs → checklist → tasks → analyze → reviews → implement → converge); 141 tasks, phase-by-phase, test-first, one commit per checkpoint | decomposition inside the runtime is capability-based (one task per capability), which is coarse for larger changes |
| **Realism / quality of outputs** | Strong | a working URL shortener (validation, 7-char codes, 302 no-store, analytics, idempotency, expiration 410, 503 on storage failure); OpenAPI contract executable; measured p95 ≈ 6 ms | DOCS output is generated from templates and the registry, so it is accurate but formulaic |
| **Validation and risk management rigor** | Strong | 277 tests incl. concurrency, crash-and-restart on a real database file, fault injection for every recovery path, determinism, contract; live probes of the running build; SC-003 checked on live data (16/16) | implementation evidence is self-reported and not verified against Git; SSRF via host names resolving to private addresses is not blocked (documented) |
| **Clarity and defensibility of decisions** | Strong | ADR-0001…0005, decisions recorded with actor and reason in every run, human-gates log, architecture overview mapping each component to a requirement | — |
| **Core engineering principles** | Good | modular planes; testable executors; reliable recovery; input validation and no stack traces; safe change management through gates, impact analysis and replanning | no authentication; scalability is a documented path, not implemented |
| **Engineering judgment** | Good | kept to one process and an embedded database; refused to fabricate approvals or evidence; reported measurements as measured (e.g. non-overlapping DOCS/SECURITY in the brownfield run) | see "process observations" below |

## Strengths worth highlighting

1. **Governance is real, not simulated.** Every gate in the live runs was crossed only after a HUMAN decision
   recorded by the candidate (16/16 crossings checked by audit sequence). The brownfield impact analysis was
   produced and approved before any expiration code existed (decision 14 at 17:55:21Z, code committed at
   18:20:27Z).
2. **Recovery is demonstrated, not described.** Each mechanism has an automated test driven by injected
   faults, plus a crash image taken with an online database backup while a stage was running.
3. **Replanning is atomic and selective.** A failure inside the replan leaves the run byte-identical
   (`ReplannerTest`); rework from DOCS keeps IMPLEMENT, TEST and SECURITY.
4. **Evidence integrity.** Runtime evidence is exported unedited; measurements and live intervals are reported
   as measured; two tests that passed on their first run are recorded as such, not as red-to-green.

## Weaknesses and risks

| # | Weakness / risk | Impact | Mitigation in place |
|---|---|---|---|
| 1 | Deterministic, vocabulary-based understanding: requirements outside the registry vocabulary are refused | narrow input language | documented; refusal instead of guessing; new capabilities extend the registry |
| 2 | Implementation evidence is self-reported; the app never reads Git | false evidence could be recorded | TEST/SECURITY probe the running build; release approval lists the risk |
| 3 | Live parallel overlap is only clearly visible for TEST (DOCS and SECURITY take ~1 ms) | SC-002 relies on instrumented tests | `ParallelValidationTest` (barrier on real executors) and the DELAY fault test prove concurrency |
| 4 | No authentication; actor types are self-declared | anyone with network access could act as HUMAN | accepted risk (EXC-003), local use only |
| 5 | Single instance (file-locked database, in-memory locks) | no horizontal scaling | ADR-0002 scaling path |
| 6 | The brownfield rule treats "add …" as requiring implementation even when the behavior already exists | a no-change brownfield run is not possible with that wording | documented in the quickstart; the human reviews `implementationRequired` at design approval |
| 7 | Metrics come from a handful of local runs | not statistically meaningful | labelled DEMONSTRATION everywhere |

## Process observations (honest)

- **Stale-build runs.** Two brownfield runs were first created on an outdated application process that had not
  been restarted, and a third was a duplicate submission. All three were detected from the data (missing data
  flows, run counts), terminated by the candidate with a stated reason and excluded from the evidence. A
  build-identity endpoint (commit id in `/actuator/info`) would have caught this immediately.
- **Two acceptance tests passed on their first run** (`ScenarioCTest`, `OpenApiContractTest`) because the
  behavior already existed; they are recorded as acceptance tests, not as test-first red-to-green.
- **Convergence found two real gaps** after all tasks were ticked (inconsistent run state ended FAILED instead
  of SAFE_STOPPED; a task-named class did not exist). Both were fixed or recorded, which shows the value of an
  independent convergence pass over completion claims.

## Recommendation

Ready for review as an assessment submission. Before any production use: authentication, an external
database with database-level run locking, verification of evidence revisions against the repository, and
host-name resolution checks for destination addresses.
