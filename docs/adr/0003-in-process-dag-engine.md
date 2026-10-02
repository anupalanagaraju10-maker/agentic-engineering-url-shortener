# ADR-0003: In-process DAG engine with deterministic stages and an external implementation action

- **Status**: Accepted (2026-10-02, approved by the human candidate)
- **Date**: 2026-10-02
- **Deciders**: human candidate (approval authority); drafted by Claude Code
- **Plan**: rev. 3 §Workflow DAG and stage contracts, §IMPLEMENT semantics, §State model ·
  **Research**: R3, R4, R5, R6, R7, R8

## Context

The orchestration is the core subject of the assessment and must not be a linear chain. The
running system must be deterministic, must not depend on an AI service, and must never claim to
write source code. Implementation is real engineering work performed outside the application by
the engineer and/or Claude Code.

## Decision Drivers

- FR-ORC-001..005, 007, 008: an explicit inspectable graph, sequential paths, real parallelism
  with a join, conditional branches, and entry/exit conditions.
- FR-ORC-011/012: a small state model and deterministic final outcomes.
- FR-ORC-014 / AMB-001 / EXC-008: deterministic stage logic, provenance labels, and no AI
  service.
- FR-ORC-016: rule-based detection of material ambiguity only.
- FR-SCN-001..003: the three scenarios, including brownfield impact analysis.
- Architecture review: `IMPLEMENT` is an `EXTERNAL_ACTION`.

## Options Considered

1. **Static in-code graph, wave scheduling, synchronous advancement, deterministic executors,
   `IMPLEMENT` as `EXTERNAL_ACTION`** (chosen).
2. External workflow engine (Temporal, Camunda) or Spring State Machine.
3. Asynchronous background runner with polling.
4. Event-driven per-node start.
5. `IMPLEMENT` as an in-app simulated change plan.
6. LLM-backed stages.

## Decision

1. **Static graph** (`WorkflowGraph`) with 14 nodes:
   - `INTAKE`, `UNDERSTAND`, `CLARIFICATION`*, `DECOMPOSE`, `IMPACT_ANALYSIS`*, `DESIGN`
   - `DESIGN_APPROVAL`, `IMPLEMENT`
   - `TEST`, `DOCS`, `SECURITY`
   - `RELEASE_READINESS`, `RELEASE_APPROVAL`, `FINAL_REPORT`

   `*` marks a conditional node. When its branch isn't taken it is `SKIPPED`, with a recorded
   branch decision.
2. **Node kinds**:
   - `AUTOMATED`: one deterministic `StageExecutor` each.
   - `HUMAN_GATE`: `CLARIFICATION`, `DESIGN_APPROVAL`, `RELEASE_APPROVAL`. Satisfied only by
     `HUMAN` actors (ADR-0004).
   - `EXTERNAL_ACTION`: `IMPLEMENT`.
3. **Eligibility**: a node starts only if it is `PENDING` and every dependency is `SUCCEEDED` or
   `SKIPPED`.
4. **Wave scheduling**:
   1. Compute all eligible automated nodes.
   2. Run them concurrently on a fixed thread pool.
   3. Wait for the wave, persist each result, then recompute.
5. **Parallel group and join**: `TEST`, `DOCS` and `SECURITY` depend only on `IMPLEMENT`; the join
   `RELEASE_READINESS` depends on all three. Overlap evidence:
   - the stage row records the latest attempt's start and end times and thread name;
   - per-attempt `STAGE_STARTED` and end events give the full history.
6. **Synchronous advancement**: each command (create, decide, record evidence, resume) advances
   the run until it reaches a human gate, the external action, a safe-stop or a terminal state.
   There is no background scheduler.
7. **Concurrency control**:
   - a per-run in-JVM lock;
   - an optimistic `version` on the run;
   - a conditional `PENDING→RUNNING` update, which prevents duplicate execution.
8. **`IMPLEMENT` as `EXTERNAL_ACTION`**:
   - After design approval the run waits in `AWAITING_IMPLEMENTATION`.
   - The engineer and/or Claude Code implement the change in the repository, then a `HUMAN` or
     `AGENT` records structured evidence (rules in ADR-0004).
   - Only then do `TEST`, `DOCS` and `SECURITY` run real checks against the running build.
   - **The application never writes, generates or modifies source code, and never queries Git.**
9. **Provenance on every stage output**:
   - `ACTUAL`: computed or checked by the system.
   - `EXTERNAL`: evidence supplied by a human or agent.
   - `FALLBACK`: produced by the fallback path.

   Injected faults are labeled `INJECTED`. FR-ORC-014 names "actual execution, simulated, or
   injected failure". No output is simulated in this design, so `SIMULATED` is not used, and
   `EXTERNAL`/`FALLBACK` make the remaining distinctions explicit.
10. **Deterministic rules**:
    - Ambiguity rules AMB-R1..R4 fire only on material ambiguity and record the rule that
      triggered.
    - A capability registry (`PLANNED`/`IMPLEMENTED`) drives the greenfield/brownfield decision,
      the impact analysis, and `implementationRequired`. `CapabilityRegistryTest` checks that
      every `IMPLEMENTED` entry references real code.
11. **State model**:
    - Run: `RUNNING`, `AWAITING_CLARIFICATION`, `AWAITING_APPROVAL`,
      `AWAITING_IMPLEMENTATION`, `AWAITING_REWORK`, `SAFE_STOPPED`, `COMPLETED`, `FAILED`.
    - Stage: `PENDING`, `RUNNING`, `BLOCKED`, `SUCCEEDED`, `FAILED`, `SKIPPED`.

    Retries, replans, compensation and similar happenings are audit events, not states.

## Rationale

- Waves give real parallelism with a join that is easy to explain: a node waits for all its
  dependencies.
- Synchronous advancement keeps tests deterministic and avoids a scheduler or queue.
- A single process makes an in-JVM lock sufficient; the constitution prohibits distributed
  locking.
- Making implementation an external action means the workflow governs real changes honestly.
- Rejected options:
  - 2 adds prohibited or unjustified infrastructure;
  - 3 means more moving parts and flaky tests;
  - 4 adds complexity without any requirement for it;
  - 5 was rejected at architecture review;
  - 6 was rejected at AMB-001.

## Consequences

- The same requirement, decisions and injected faults give the same stage path and outcome.
- An HTTP command blocks while automated stages run, bounded by about 3 × 5 s per wave.
- Runs can wait for a long time, across restarts and code changes, so the graph must stay stable
  (ADR-0002).
- Ambiguity and brownfield detection are vocabulary rules over a known capability set.

## Risks

- Implementation evidence is self-reported. Mitigations: structural validation, HUMAN review at
  design and release, and behavioural `TEST`/`SECURITY` probes.
- The registry could drift from the code. Mitigation: `CapabilityRegistryTest`.
- Keyword rules can misclassify. Mitigation: the human reviews findings and `implementationRequired`
  at the gates.

## Reversibility

Medium. The engine is internal, but moving to asynchronous execution would change the API's
timing semantics, and changing the graph affects waiting runs.

## Validation

- `WorkflowGraphTest`.
- `WorkflowEngineTest`: order, branch skip, overlap via an injected `DELAY`, and the join.
- `AmbiguityRulesTest` and `CapabilityRegistryTest`.
- `HumanGateTest`: external-action evidence rules.
- `ScenarioATest`, `ScenarioBTest`, `ScenarioCTest`.
