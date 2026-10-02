# Data Model: Agentic Software Engineering System — URL Shortener

**Phase**: 1 (`/speckit.plan`) | **Date**: 2026-10-01 (amended at architecture review) |
**Spec**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

Seven tables in one H2 file database, created only by Flyway migrations. Two belong to the
URL-shortener plane (`link`, `idempotency_record`); five to the orchestration plane. The only
cross-plane reference is `link.probe_run_id`, a nullable tag on links created by the workflow's
`TEST` stage so they can be cleaned up or compensated. JSON columns hold structured stage
artifacts as text. Recovery incidents and metrics are derived from `audit_event` (no recovery
table).

---

## URL-shortener plane

### `link` — Short Link + Link Analytics (FR-URL-001..012, 017)

| Column | Type | Rules |
|---|---|---|
| `id` | BIGINT identity | PK |
| `code` | VARCHAR(16) | NOT NULL, **UNIQUE**; Base62, length 7 (PVT-006) |
| `original_url` | VARCHAR(2048) | NOT NULL; http/https, validated (FR-URL-002/003/016, PVT-007) |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |
| `redirect_count` | BIGINT | NOT NULL DEFAULT 0; incremented atomically per successful redirect |
| `last_redirect_at` | TIMESTAMP WITH TIME ZONE | NULL until first redirect |
| `probe_run_id` | UUID | NULL for client links; workflow run id for transient TEST probe links |
| `expires_at` | TIMESTAMP WITH TIME ZONE | NULL = never expires; must be in the future at creation (FR-URL-009). **Added only by the SCN-B brownfield migration, after design approval** |

Resolution outcome (computed, not stored): active → 302; unknown code → 404; after SCN-B,
`expires_at <= now` → 410 (no count increment). Probe links never outlive their `TEST` stage on
success; on failure/invalidation they are removed by compensation.

### `idempotency_record` (FR-URL-011)

| Column | Type | Rules |
|---|---|---|
| `idem_key` | VARCHAR(100) | PK |
| `request_fingerprint` | CHAR(64) | SHA-256 of canonical request (`url`, plus `expiresAt` once it exists) |
| `link_id` | BIGINT | FK → `link.id` |
| `created_at` | TIMESTAMP WITH TIME ZONE | NOT NULL |

Same key + same fingerprint → replay original result; same key + different fingerprint → 409.

---

## Orchestration plane

### `workflow_run` — Workflow Run (FR-ORC-006, FR-POL-001)

| Column | Type | Rules |
|---|---|---|
| `id` | UUID | PK |
| `correlation_id` | VARCHAR(64) | client-supplied `X-Correlation-Id` at creation, else the run id; copied onto every audit event |
| `original_requirement` | VARCHAR(4000) | NOT NULL, immutable |
| `current_requirement` | VARCHAR(8000) | requirement after clarifications/changes |
| `normalized_json` | CLOB | latest normalized requirement + capabilities + ambiguity findings |
| `change_type` | VARCHAR(16) | `GREENFIELD` / `BROWNFIELD` (set by UNDERSTAND; may change on replan) |
| `status` | VARCHAR(32) | run status (see state machine) |
| `pending_action` | VARCHAR(64) | what must happen next (human decision or external action): `CLARIFY`, `APPROVE:DESIGN_APPROVAL`, `RECORD_IMPLEMENTATION`, `EXCEPTION:PRIV-01`, `APPROVE:RELEASE_APPROVAL`, `REWORK_OR_TERMINATE`, `RESUME` |
| `plan_version` | INT | starts at 1; +1 per replan/rework |
| `policy_version` | VARCHAR(16) | `v1` |
| `recoverable` | BOOLEAN | meaningful when `SAFE_STOPPED` |
| `stop_reason` | VARCHAR(1000) | safe-stop / failure reason |
| `fault_plan_json` | CLOB | injected faults for this run (FR-REL-011), NULL if none; non-NULL marks an *injected run* for metrics separation. Accepted only when fault injection is enabled |
| `created_at`, `updated_at`, `ended_at` | TIMESTAMP WITH TIME ZONE | `ended_at` set on final outcome |
| `version` | BIGINT | optimistic lock |

### `workflow_stage` — Stage Execution, current state (FR-ORC-006/008/009, FR-REL-001)

PK (`run_id`, `node`). One row per DAG node, created with the run.

| Column | Type | Rules |
|---|---|---|
| `run_id` | UUID | FK → `workflow_run.id` |
| `node` | VARCHAR(32) | one of the 14 node ids |
| `status` | VARCHAR(16) | stage status |
| `attempts` | INT | attempts in the current plan version |
| `started_at`, `ended_at` | TIMESTAMP WITH TIME ZONE | of the latest attempt |
| `thread_name` | VARCHAR(64) | executing thread (parallelism evidence) |
| `output_json` | CLOB | structured artifact; NULL unless `SUCCEEDED` |
| `provenance` | VARCHAR(16) | `ACTUAL` / `EXTERNAL` / `FALLBACK` |
| `failure_class` | VARCHAR(16) | `TRANSIENT` / `PERMANENT` / NULL |
| `failure_code` | VARCHAR(32) | e.g. `IMPLEMENTATION_DEFECT`, `POLICY_FAIL`, `INVALID_INPUT`, `INVARIANT`, `TIMEOUT`; `IMPLEMENTATION_DEFECT` routes the run to `AWAITING_REWORK` |
| `failure_reason` | VARCHAR(1000) | |
| `plan_version` | INT | plan version that produced the current state |

Output and `SUCCEEDED` status are written in one completion transaction, so a failed attempt
leaves nothing behind (attempt rollback). Before a replan resets a row, its prior output is
written into a `STAGE_INVALIDATED` audit event.

### `decision` — Decision lineage (FR-ORC-010, FR-HUM-004..007, FR-POL-005)

| Column | Type | Rules |
|---|---|---|
| `id` | BIGINT identity | PK |
| `run_id` | UUID | FK |
| `type` | VARCHAR(32) | `BRANCH`, `APPROVAL`, `REJECTION`, `CLARIFICATION`, `IMPLEMENTATION_EVIDENCE`, `EXCEPTION_APPROVED`, `EXCEPTION_REJECTED`, `REWORK`, `TERMINATION`, `REQUIREMENT_CHANGE`, `RESUME`, `DECISION_INVALIDATED`, `DECISION_REFUSED` |
| `gate` | VARCHAR(48) | node or `EXCEPTION:<checkId>`; NULL for non-gate decisions |
| `actor_type` | VARCHAR(8) | NOT NULL; `SYSTEM` / `HUMAN` / `AGENT` |
| `actor_identity` | VARCHAR(100) | NOT NULL; `workflow-engine` for `BRANCH`/`DECISION_INVALIDATED` |
| `reason` | VARCHAR(2000) | NOT NULL |
| `plan_version` | INT | plan version of the subject decided on |
| `supersedes_id` | BIGINT | FK → `decision.id`, nullable |
| `payload_json` | CLOB | clarification text; implementation evidence; exception fields; accepted risks |
| `created_at` | TIMESTAMP WITH TIME ZONE | |

`IMPLEMENTATION_EVIDENCE` payload (actor `HUMAN` or `AGENT`):
- `summary` (required);
- `requirementIds` (≥ 1, pattern `FR-…`/`NFR-…`), each one in the run's requirement-ID set fixed by
  `DECOMPOSE` and carried in the approved `DESIGN` output; otherwise `409 EVIDENCE_SCOPE_MISMATCH`;
- `changedArtifacts` (repository-relative paths);
- if `changedArtifacts` is non-empty, `revision` (7–40 hex chars) is **required**;
- if there is no code/config/schema change (`changedArtifacts` empty), `noChangeJustification` is
  **required** and accepted only when the current `DESIGN` output says
  `implementationRequired = false`.

The application checks structure only and never queries Git. The evidence record's `created_at` must be
later than the valid `DESIGN_APPROVAL` decision it follows (CHK024).

An approval or implementation record is **valid** only if it has not been superseded by
`DECISION_INVALIDATED` and its `plan_version` equals the run's.

**Actor model** (both `decision` and `audit_event`): `actor_type` ∈ `SYSTEM` | `HUMAN` |
`AGENT`; `actor_identity` e.g. `workflow-engine`, a human name, `claude-code`. Rules:
- `SYSTEM` is written only by the engine; the API never accepts it.
- Human gates (`CLARIFICATION`, `DESIGN_APPROVAL`, `RELEASE_APPROVAL`), policy-exception
  decisions, rework, termination, requirement change and resume require `HUMAN`. An `AGENT` can
  never satisfy them.
- `AGENT` may submit requirements and record `IMPLEMENTATION_EVIDENCE` (the `EXTERNAL_ACTION`).
- Blank identities are refused. Reserved identities (`workflow-engine`, `system`) are refused for
  `HUMAN` and `AGENT`, and `claude-code` is refused for `HUMAN`.
- Types are self-declared (no authentication, EXC-003).

### `policy_evaluation` (FR-POL-001..006)

| Column | Type | Rules |
|---|---|---|
| `id` | BIGINT identity | PK |
| `run_id` | UUID | FK |
| `policy_version` | VARCHAR(16) | `v1` |
| `check_id` | VARCHAR(16) | `PRIV-01`, `SEC-01`, `CHG-01`, `DEP-01`, `AUD-01` |
| `domain` | VARCHAR(32) | privacy / security / change-control / dependencies / audit |
| `node` | VARCHAR(32) | node after which it was evaluated |
| `mandatory` | BOOLEAN | all `true` in v1 |
| `result` | VARCHAR(24) | `PASS`, `FAIL`, `EXCEPTION_REQUESTED`, `NOT_APPLICABLE` (spec spelling `EXCEPTION-REQUESTED`/`NOT-APPLICABLE`; underscores used as identifiers) |
| `reason` | VARCHAR(2000) | |
| `plan_version` | INT | |
| `resolution_decision_id` | BIGINT | FK → `decision.id` (exception approved/rejected), nullable |
| `created_at` | TIMESTAMP WITH TIME ZONE | |

Unresolved = mandatory `FAIL`, or `EXCEPTION_REQUESTED` without an `EXCEPTION_APPROVED`
resolution, for the current plan version.

### `audit_event` — append-only (FR-OBS-001/002/003)

| Column | Type | Rules |
|---|---|---|
| `id` | BIGINT identity | PK |
| `run_id` | UUID | FK |
| `correlation_id` | VARCHAR(64) | copied from the run |
| `seq` | INT | per-run sequence, UNIQUE(`run_id`,`seq`) |
| `type` | VARCHAR(40) | see event catalog |
| `node` | VARCHAR(32) | nullable |
| `actor_type` | VARCHAR(8) | `SYSTEM` / `HUMAN` / `AGENT` |
| `actor_identity` | VARCHAR(100) | e.g. `workflow-engine`, human name, `claude-code` |
| `plan_version` | INT | |
| `policy_version` | VARCHAR(16) | |
| `injected` | BOOLEAN | true when caused by fault injection |
| `payload_json` | CLOB | result, reason, attempt, incident id, mechanism, invalidated output, metadata |
| `created_at` | TIMESTAMP WITH TIME ZONE | |

Append-only is enforced by design: the repository exposes insert and read methods only, no API
updates or deletes events, and a test asserts both.

**Event catalog**:
- **Run lifecycle**: `RUN_CREATED`, `RUN_RESUMED`, `RUN_COMPLETED`, `RUN_FAILED`, `SAFE_STOPPED`.
- **Understanding and branching**: `REQUIREMENT_NORMALIZED`, `AMBIGUITY_DETECTED`, `BRANCH_TAKEN`.
- **Stages**: `STAGE_STARTED`, `STAGE_SUCCEEDED`, `STAGE_FAILED`, `STAGE_TIMED_OUT`,
  `STAGE_SKIPPED`, `STAGE_INVALIDATED`.
- **Human interaction**: `CLARIFICATION_REQUESTED`, `CLARIFICATION_RECEIVED`,
  `APPROVAL_REQUESTED`, `APPROVAL_GRANTED`, `APPROVAL_REJECTED`, `IMPLEMENTATION_REQUESTED`,
  `IMPLEMENTATION_RECORDED`, `DECISION_INVALIDATED`, `DECISION_REFUSED`.
- **Recovery**: `RETRY_SCHEDULED`, `RETRY_EXHAUSTED`, `FALLBACK_USED`, `ATTEMPT_ROLLED_BACK`,
  `COMPENSATION_STARTED`, `COMPENSATION_COMPLETED`, `COMPENSATION_FAILED`, `FAILURE_DETECTED`,
  `RECOVERY_STARTED`, `RECOVERY_COMPLETED`, `RECOVERY_FAILED`.
- **Policy**: `POLICY_EVALUATED`, `EXCEPTION_REQUESTED`, `EXCEPTION_APPROVED`,
  `EXCEPTION_REJECTED`.
- **Replanning**: `PLAN_REPLANNED`, `REPLAN_ABORTED` (written after a rolled-back replan
  transaction).

**Recovery incidents (derived)**:
- `FAILURE_DETECTED` is emitted on the first failed attempt of a node within a plan version, and
  its `seq` is the incident id.
- `RECOVERY_STARTED` records the mechanism (`RETRY`, `FALLBACK`, `COMPENSATION`, `RESUME`,
  `REWORK`).
- `RECOVERY_COMPLETED` is emitted when the node later succeeds.
- `RECOVERY_FAILED` is emitted when the run ends `FAILED`, or `SAFE_STOPPED` non-recoverable,
  with the incident open.
- Recovery duration = `RECOVERY_COMPLETED.created_at − FAILURE_DETECTED.created_at`. MTTR
  averages recovered incidents only.

---

## Workflow graph (static, in code)

```text
INTAKE ─► UNDERSTAND ─► CLARIFICATION* ─► DECOMPOSE ─► IMPACT_ANALYSIS* ─► DESIGN ─► DESIGN_APPROVAL†
          (UNDERSTAND also ─► DECOMPOSE)   (DECOMPOSE also ─► DESIGN)
DESIGN_APPROVAL† ─► IMPLEMENT‡ ─┬─► TEST ─────┐
                                ├─► DOCS ─────┼─► RELEASE_READINESS (join) ─► RELEASE_APPROVAL† ─► FINAL_REPORT
                                └─► SECURITY ─┘
*  conditional: CLARIFICATION runs only if ambiguity found; IMPACT_ANALYSIS only if BROWNFIELD
†  human approval gate (HUMAN only)     ‡  EXTERNAL_ACTION: implementation done outside the app; evidence by HUMAN or AGENT
```

| Node | Kind | Depends on | Entry condition (beyond deps) | Exit condition | Output (provenance) |
|---|---|---|---|---|---|
| INTAKE | automated | — | requirement non-blank, ≤ 4000 chars | original requirement stored | intake record (ACTUAL) |
| UNDERSTAND | automated | INTAKE | — | normalized requirement + capabilities + findings + change type; PRIV-01 evaluated | (ACTUAL) |
| CLARIFICATION | gate, conditional | UNDERSTAND | findings non-empty, else SKIPPED (or SUCCEEDED if resolved by an earlier clarification) | valid CLARIFICATION decision at current plan | decision ref |
| DECOMPOSE | automated | UNDERSTAND, CLARIFICATION | no open ambiguity | ≥ 1 task per capability, each with acceptance check | task list (ACTUAL) |
| IMPACT_ANALYSIS | automated, conditional | DECOMPOSE | change type BROWNFIELD, else SKIPPED | all 10 FR-SCN-002 areas populated | impact report (ACTUAL) |
| DESIGN | automated | DECOMPOSE, IMPACT_ANALYSIS | — | components, interface/data changes, test plan, dependency list, security-sensitivity flag, `implementationRequired`; SEC-01/CHG-01/DEP-01 evaluated | design (ACTUAL) |
| DESIGN_APPROVAL | gate | DESIGN | no unresolved policy issue | valid APPROVAL at current plan | decision ref |
| IMPLEMENT | **external action** | DESIGN_APPROVAL | valid design approval | valid IMPLEMENTATION_EVIDENCE at current plan (rules above) | evidence + coverage of designed components (EXTERNAL) |
| TEST | automated, parallel | IMPLEMENT | — | every capability probe executed and passed; probe links cleaned up | probe results (ACTUAL) |
| DOCS | automated, parallel, **fallback** | IMPLEMENT | — | doc has required sections | docs (ACTUAL or FALLBACK) |
| SECURITY | automated, parallel | IMPLEMENT | — | all validator probes rejected the unsafe inputs | probe results (ACTUAL) |
| RELEASE_READINESS | automated, **join** | TEST, DOCS, SECURITY | — | no unresolved policy issue; AUD-01 PASS; every task has a passing check; residual risks listed (incl. designed components missing from evidence) | readiness report (ACTUAL) |
| RELEASE_APPROVAL | gate | RELEASE_READINESS | — | valid APPROVAL at current plan (accepted risks recorded) | decision ref |
| FINAL_REPORT | automated, idempotent | RELEASE_APPROVAL | — | report generated from persisted records | final report (ACTUAL) → run `COMPLETED` |

No node performs a destructive or irreversible action (research R12).

## State machines

**Run**

```text
RUNNING ──gate reached──► AWAITING_CLARIFICATION | AWAITING_APPROVAL ──decision──► RUNNING
RUNNING ──design approved──► AWAITING_IMPLEMENTATION ──evidence recorded──► RUNNING
AWAITING_APPROVAL ──rejection──► AWAITING_REWORK ──rework──► RUNNING (plan+1)
RUNNING ──IMPLEMENTATION_DEFECT──► AWAITING_REWORK          └─terminate─► FAILED
any AWAITING_* or recoverable SAFE_STOPPED ──terminate (HUMAN)──► FAILED
RUNNING ──mandatory policy FAIL / retry exhausted / invalid state / restart──► SAFE_STOPPED
SAFE_STOPPED (recoverable) ──resume──► RUNNING
any non-final ──requirement change──► RUNNING (plan+1)
RUNNING ──other permanent stage failure──► FAILED      RUNNING ──FINAL_REPORT done──► COMPLETED
```

**Stage**:
- `PENDING → RUNNING → SUCCEEDED | FAILED`.
- `RUNNING → PENDING` on attempt rollback, before a retry or after an interruption.
- `PENDING → SKIPPED` when the branch is not taken.
- `PENDING → BLOCKED`, while waiting for a human gate decision, external-action evidence or an exception
  decision, then `→ SUCCEEDED` (approved or recorded) or `→ FAILED` (rejected).
- Any status `→ PENDING` on replan (invalidated).

## Traceability within a run (FR-OBS-006)

The chain runs from `original_requirement` to the final report:
1. `normalized_json` records capabilities and findings.
2. DECOMPOSE tasks each reference a capability.
3. DESIGN components reference task ids.
4. IMPLEMENTATION_EVIDENCE gives requirement IDs, changed artifacts and the revision.
5. TEST/SECURITY probe results reference task acceptance checks.
6. RELEASE_READINESS verifies every task has a passing check.
7. FINAL_REPORT cites audit `seq` numbers.
