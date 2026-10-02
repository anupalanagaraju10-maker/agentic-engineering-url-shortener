# ADR-0004: Human governance, actor model, decision lineage, replanning and policy v1

- **Status**: Accepted (2026-10-02, approved by the human candidate); amended 2026-10-02 by the
  requirements-quality gate resolutions (plan §Requirements-quality gate resolutions) — amendment
  re-accepted by the human candidate 2026-10-02
- **Date**: 2026-10-02
- **Deciders**: human candidate (approval authority); drafted by Claude Code
- **Plan**: rev. 3 §Human approval model, §IMPLEMENT semantics, §Replanning model, §Policy model ·
  **Research**: R10, R11, R12

## Context

Humans must retain authority over material decisions, and rejected work must be correctable. Both
humans and an AI agent (Claude Code) take part in implementation, so the record must show who did
what, and an agent must never stand in for a human approval. Material upstream changes must
trigger dependency-aware replanning, and policy must use a small versioned model with
human-approved exceptions.

## Decision Drivers

- FR-HUM-001..007, NFR-009:
  - explicit human gates;
  - no silent, automatic or timeout approval;
  - rework or termination after rejection;
  - refusal of system or empty actors and of stale or misdirected decisions.
- FR-ORC-010: decision lineage. FR-ORC-013 / FR-POL-007: dependency-aware replanning with
  approval invalidation.
- FR-POL-001..006: a policy version, four result values, blocking failures, and human-approved
  exceptions with a full record.
- FR-SCN-003: the ambiguous scenario resumes only after a real human clarification.
- Constitution II: Claude proposes and never approves. VI: evidence integrity.

## Options Considered

1. **Typed actor model (`SYSTEM`/`HUMAN`/`AGENT`), HUMAN-only gates, structured evidence rules,
   one replan mechanism, policy v1 with five checks** (chosen).
2. Free-text actor names (plan revision 1).
3. Restarting the whole run on any change.
4. Seven policy checks, including SEC-02 and CHG-02.
5. A generic policy language or rules platform.

## Decision

1. **Actor model.** Every `decision` and `audit_event` records `actorType` (`SYSTEM` | `HUMAN` |
   `AGENT`) and `actorIdentity` (e.g. `workflow-engine`, a person's name, `claude-code`).

   | Action | Allowed actor types |
   |---|---|
   | `CLARIFICATION`, `DESIGN_APPROVAL`, `RELEASE_APPROVAL` (incl. residual-risk acceptance); policy-exception decisions; rework; termination; requirement change; resume | `HUMAN` only |
   | implementation evidence (`EXTERNAL_ACTION`); submitting a requirement | `HUMAN` or `AGENT` |
   | branch decisions, invalidations, stage events | `SYSTEM` (`workflow-engine`), engine-written; never accepted from the API |

   - **An `AGENT` can never satisfy a human gate.**
   - Blank or reserved identities are refused: `workflow-engine` and `system` for anyone, and
     `claude-code` as `HUMAN`.
2. **Decision validation.** Every command carries the actor, a reason or summary, and the
   `planVersion` acted on. A disallowed actor type, wrong gate or stale plan gets `400`/`409` and a
   recorded `DECISION_REFUSED`, and run state is unchanged. There is no default, automatic or
   timeout approval, and neither the gates nor the external action have a timer.
3. **Implementation evidence rules** for the `EXTERNAL_ACTION` (ADR-0003):
   - `summary` and `requirementIds` are always required.
   - Non-empty `changedArtifacts` ⇒ `revision` is **required**.
   - No code, config or schema change ⇒ empty `changedArtifacts` plus a **required**
     `noChangeJustification`. This is accepted only when the current `DESIGN` output has
     `implementationRequired = false`, which the human reviews at design approval.
   - Every `requirementId` must belong to the run's requirement-ID set (fixed by `DECOMPOSE`,
     carried in the approved `DESIGN`); otherwise `409 EVIDENCE_SCOPE_MISMATCH` and
     `DECISION_REFUSED` (CHK004).
   - The application validates structure only and never queries Git.
4. **Rejection and rework.**
   1. A rejection is recorded as a `REJECTION` decision and an `APPROVAL_REJECTED` event, and the
      run moves to `AWAITING_REWORK`.
   2. The HUMAN then either chooses `rework` from a node upstream of the gate (the requirement is
      unchanged and re-approval is required) or chooses `terminate`, which ends the run `FAILED`.
   3. An implementation defect found by `TEST`/`SECURITY` also leads to `AWAITING_REWORK`
      (ADR-0005 §8). Rework then starts from `IMPLEMENT` or any node upstream of it.
   4. `terminate` (HUMAN) is allowed from every waiting state and from recoverable
      `SAFE_STOPPED` (CHK002).
5. **Decision lineage.** A decision may `supersede` an earlier one. An approval or evidence record
   is valid only if it hasn't been superseded and matches the current plan version.
6. **Replanning**, triggered by a clarification, a requirement change or rework. Steps 2–5 commit
   in **one database transaction**. On failure the prior state is unchanged, and `REPLAN_ABORTED`
   is recorded separately (CHK007):
   1. Take the changed node plus its descendants. Everything else is preserved.
   2. Compensate side effects (ADR-0005).
   3. Reset the affected nodes, copying prior outputs into `STAGE_INVALIDATED` events.
   4. Record `DECISION_INVALIDATED` for approvals **and** implementation evidence on affected
      nodes.
   5. Set `planVersion+1` and record `PLAN_REPLANNED {old, new, reason, affected, preserved}`.

   Nothing is deleted. In SCN-C, whether implementation is needed is decided by the replanned
   `DESIGN` after the human's actual clarification, not in advance.
7. **Policy v1**: five mandatory checks, one per FR-POL-002 domain:

   | Check | Domain | Evaluated after |
   |---|---|---|
   | PRIV-01 | privacy | UNDERSTAND |
   | SEC-01 | security | DESIGN |
   | CHG-01 | change control | DESIGN |
   | DEP-01 | dependencies & licensing | DESIGN |
   | AUD-01 | audit evidence | RELEASE_READINESS |

   - Results are `PASS`, `FAIL`, `EXCEPTION_REQUESTED` or `NOT_APPLICABLE`, and every run records
     the policy version.
   - A `FAIL` leads to safe-stop.
   - `EXCEPTION_REQUESTED` makes the run wait (`AWAITING_APPROVAL`, `pendingAction =
     EXCEPTION:<checkId>`; the evaluated node has already succeeded, so the run rather than the node
     waits; wording clarified in Phase 3, no decision change) until a HUMAN decides:
     - approval records the policy id, reason, scope, approving actor, compensating control,
       timestamp and expiry/review condition;
     - rejection leads to safe-stop.
   - Readiness is blocked by any unresolved issue.
8. **Clarification vs approved requirements (CHK034).** A clarification that contradicts an
   approved requirement (the registry's approved behavior statements, with requirement IDs) is
   refused by `/clarify` with `409 CHANGE_CONTROL_REQUIRED`. It must go through
   `requirement-change` and replanning:
   - `DESIGN` lists `changesApprovedRequirements`;
   - CHG-01 requires the impact analysis to cover them;
   - design approval shows them explicitly;
   - the repository spec is amended through SpecKit change control before implementation.

   An approved requirement is never silently overridden.
9. **Destructive actions.** This prototype has none. If one were added, it would need its own
   HUMAN gate. Release approval records the residual risks the human accepts.

## Rationale

- Typed actors make "who did what" explicit and testable. They are the minimum needed to
  separate human authority from agent contribution.
- One replan mechanism serves all three causes (clarification, requirement change, rework). It
  preserves unaffected work, as FR-ORC-013 requires.
- Five checks cover each mandated policy domain once and demonstrate all four result values.
- Rejected options:
  - 2 cannot distinguish agent from human;
  - 3 discards preserved work and breaks FR-ORC-013;
  - 4 is redundant, because SEC-02 duplicates `SECURITY`'s exit criteria and CHG-02 duplicates the
    gate structure;
  - 5 is unjustified under Principle I.

## Consequences

- Every decision is attributable, versioned, and reconstructable from the database.
- The agent's role is bounded to submitting requirements and recording implementation evidence.
- Reworking `DOCS` preserves `IMPLEMENT`, `TEST` and `SECURITY`, which demonstrates selective
  invalidation.

## Risks

- Actor types are self-declared because there is no authentication (EXC-003). The HUMAN-only
  rule is explicit and testable but not tamper-proof. This is documented as a limitation, and
  production would bind actor types to authenticated identities.
- The `implementationRequired` rule is vocabulary-based. Mitigation: the human reviews it at
  design approval.

## Reversibility

High. Authentication can be added later by mapping identities onto the same fields. Policy
checks are isolated and versioned (`v1`), so a later version can add or retire checks.

## Validation

- `HumanGateTest`:
  - AGENT, SYSTEM, blank and reserved actors are refused at gates;
  - wrong gate and stale plan are refused;
  - rejection leads to rework or terminate;
  - the evidence rules hold.
- `ReplannerTest`: affected and preserved sets, approval and evidence invalidation.
- `PolicyTest`.
- `ScenarioCTest`.
