# ADR-0005: Reliability and recovery — classification, bounded retry, timeout, fallback, rollback vs compensation, safe-stop, resume, MTTR

- **Status**: Accepted (2026-10-02, approved by the human candidate); amended 2026-10-02 by the
  requirements-quality gate resolutions (plan §Requirements-quality gate resolutions) — amendment
  re-accepted by the human candidate 2026-10-02
- **Date**: 2026-10-02
- **Deciders**: human candidate (approval authority); drafted by Claude Code
- **Plan**: rev. 3 §Reliability model, §Recovery incidents and MTTR · **Research**: R9, R16

## Context

Automated stages can fail, time out, or be interrupted by a restart. One stage, `TEST`, creates
real side effects (probe links) through the URL-shortener service. The system must recover in
bounded, observable ways, stop safely when it cannot, and report recovery metrics that a reviewer
can reproduce.

## Decision Drivers

- FR-REL-001..004, PVT-001/002: failure classification, transient-only bounded retry, and
  explicit timeouts for automated stages only.
- FR-REL-005: an explicit fallback that never masks a mandatory check.
- FR-REL-006: rollback only where an exact restore is possible, compensation otherwise.
- FR-REL-007..010: safe-stop triggers and preserved state; resume without re-running succeeded
  work; partial failure.
- FR-REL-011: controlled, labeled fault injection.
- FR-OBS-003..005, SC-010: recovery timestamps; MTTR over recovered failures only; demonstration
  labeling.

## Options Considered

1. **Bounded transient retry; per-stage timeout; `DOCS`-only fallback; attempt rollback +
   probe-link compensation; explicit safe-stop triggers; resume; incidents derived from audit
   events** (chosen).
2. Unlimited or exponential retry.
3. Fallback also for security validation.
4. Keeping probe links after completion as evidence.
5. A separate `recovery_record` table.
6. Real infrastructure fault simulation instead of injection.

## Decision

1. **Classification**: `TRANSIENT` or `PERMANENT`. A timeout counts as `TRANSIENT`; validation,
   policy and invariant failures are `PERMANENT`.
2. **Retry**: transient failures only. At most 3 attempts (2 retries), with backoff of 100 ms and
   then 200 ms. Every attempt is persisted. Exhaustion emits `RETRY_EXHAUSTED`.
3. **Timeout**: 5 s per automated node (`Future.get` plus cancel), configurable for tests. Human
   gates and the `IMPLEMENT` external action have no timeout.
4. **Fallback**: `DOCS` only. A minimal template that still meets the exit criteria, labeled
   `FALLBACK`. Never used for `SECURITY`, gates, `IMPLEMENT` or policy.
5. **Rollback = attempt/transaction rollback.**
   - Output and `SUCCEEDED` status commit together.
   - A failed, timed-out or interrupted attempt commits nothing, and the stage returns to
     `PENDING` with no output (`ATTEMPT_ROLLED_BACK`).
   - The same rollback is applied at startup to stages left `RUNNING`.
6. **Compensation = corrective action for side effects already committed elsewhere.**
   - `TEST` creates real probe links through `LinkService`. These commit in their own
     transactions, so attempt rollback cannot undo them.
   - On success, `TEST` deletes its own probe links and keeps the evidence in its output and in
     audit events. Probe data never outlives a successful run.
   - On a failed `TEST` attempt, on `TEST` invalidation (ADR-0004), or when the run ends `FAILED`
     or `SAFE_STOPPED`, an idempotent sweep deletes every link tagged with the run
     (`COMPENSATION_STARTED/COMPLETED`).
   - If the sweep fails, the run safe-stops as non-recoverable.
7. **Safe-stop triggers**:
   - mandatory policy `FAIL` or rejected policy exception: non-recoverable;
   - retries exhausted with no fallback: recoverable;
   - invalid or inconsistent state: non-recoverable;
   - compensation failure: non-recoverable;
   - restart interruption: recoverable, reason `INTERRUPTED`.

   State, prior outcomes, reason, history and the recoverable flag are all preserved.
8. **Permanent stage failure**: compensation sweep, then:
   - code `IMPLEMENTATION_DEFECT` (a `TEST`/`SECURITY` probe fails after implementation
     evidence was accepted) ⇒ `AWAITING_REWORK`, with `RECOVERY_STARTED(REWORK)`. The HUMAN
     reworks (invalidating the evidence; new evidence and validation are required) or terminates
     (CHK036);
   - any other permanent failure (invalid input, invariant violation) ⇒ `FAILED`.
9. **Resume** (HUMAN only, ADR-0004): only for recoverable `SAFE_STOPPED` runs. Only `PENDING`
   eligible nodes run. On a partial failure in the parallel group, the succeeded branches are kept
   and only the failed branch re-runs.
10. **Fault injection**: **disabled by default**; enabled only by the `demo` profile or test
    configuration, otherwise runs with faults are refused (`400 FAULT_INJECTION_DISABLED`).
    Optional per-run faults `TRANSIENT`, `PERMANENT`, `TIMEOUT`, `DELAY` and
    `COMPENSATION_FAILURE`, on automated nodes only. Each fires after the executor's work and
    before its completion commit, so one `TEST` fault exercises both rollback and compensation.
    Every effect is labeled `INJECTED`.
11. **Recovery incidents from audit events.** This realizes the spec's Recovery Record entity as a
    derived view, with no table:
    - `FAILURE_DETECTED` opens an incident; its `seq` is the incident id.
    - `RECOVERY_STARTED` records the mechanism: `RETRY`, `FALLBACK`, `COMPENSATION` or `RESUME`.
    - `RECOVERY_COMPLETED` closes it as recovered; `RECOVERY_FAILED` closes it as unrecovered.
    - **MTTR = Σ(recovered durations) / count(recovered)**. It includes human reaction time for
      `RESUME`/`REWORK` and is also reported per mechanism. Unrecovered and open incidents are
      reported separately.
    - Metrics can be filtered to injected or non-injected runs (`?faultInjected=`), so
      demonstration data stays separable (CHK038).
    - All metrics are labeled `DEMONSTRATION — local runs, not production statistics`.
12. **Idempotency**:
    - link creation via `Idempotency-Key`;
    - `FINAL_REPORT` regeneration;
    - the compensation sweep.

## Rationale

- Bounded, classified retries and explicit timeouts give predictable recovery that can be
  demonstrated.
- Keeping rollback (exact, transactional) separate from compensation (corrective, for committed
  side effects) matches what each mechanism can actually guarantee.
- One `DOCS` fallback demonstrates FR-REL-005 without risking a masked mandatory check.
- Deriving incidents from immutable events makes MTTR reproducible from a single source of truth.
- Rejected options:
  - 2 violates PVT-001;
  - 3 would mask a mandatory check;
  - 4 leaves test data in the domain table (rejected at architecture review);
  - 5 duplicates data the events already hold (rejected at architecture review);
  - 6 is non-deterministic.

## Consequences

- Every recovery behaviour can be demonstrated on demand, and the run record shows it.
- Rollback and compensation are visibly different mechanisms.
- A reviewer can recompute MTTR from the event history (SC-010).

## Risks

- The incident-derivation logic must be correct. Mitigation: `MetricsTest` on a known event
  sequence.
- Thread interruption on timeout depends on the executor cooperating. Mitigation: the attempt is
  treated as failed regardless, and its result is never committed.
- Compensation deletes by the `probe_run_id` tag. Mitigation: only links tagged with the run are
  touched, and client links never carry the tag.

## Reversibility

High. These are internal mechanisms behind the engine, and the retry, timeout and fallback
settings are configuration or code-local.

## Validation

`RecoveryTest` covers:
- retry with rollback and compensation;
- permanent failure;
- exhaustion leading to safe-stop and then resume;
- timeout;
- fallback;
- compensation failure;
- partial-failure resume.

`RestartPersistenceTest` covers an interrupted stage. `MetricsTest` covers incidents and MTTR.
