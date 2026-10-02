# ADR-0002: Persistence — file-backed H2, Flyway migrations, Spring Data JPA

- **Status**: Accepted (2026-10-02, approved by the human candidate)
- **Date**: 2026-10-02
- **Deciders**: human candidate (approval authority); drafted by Claude Code
- **Plan**: rev. 3 §Persistence and restart · **Data model**:
  [data-model.md](../../specs/001-agentic-sdlc-url-shortener/data-model.md) · **Research**: R2, R16

## Context

Workflow state, decisions, policy results and audit history must outlive the process. Runs must
be able to wait across application restarts, for example while an implementation is performed
externally (ADR-0003). The audit trail must be immutable. The brownfield scenario (SCN-B) adds
link expiration as a real schema change, so schema evolution has to be explicit.

## Decision Drivers

- FR-ORC-006, NFR-004, SC-005: run state survives restart unchanged.
- FR-ORC-009: stage outputs persist and are available downstream.
- FR-OBS-001/002/003: an append-only audit trail with recovery timestamps.
- FR-URL-010/012: analytics with no lost updates under concurrency.
- FR-SCN-002: the brownfield change touches data and must be reviewable.
- ASM-003 and the constitution: no external services, and minimal tables (Principle I).

## Options Considered

1. **H2 file mode + Flyway + Spring Data JPA, seven tables, incidents derived from events**
   (chosen).
2. In-memory H2.
3. PostgreSQL or another server database.
4. `JdbcClient` without JPA.
5. Hibernate `ddl-auto=update`, so no migrations.
6. Additional tables: separate analytics and `recovery_record`.

## Decision

- **H2 in file mode** (`./data/shortener`, git-ignored) is the single durable store. Tests use
  in-memory H2, except the restart test, which uses a temp-directory file database.
- **Flyway** owns the schema (`src/main/resources/db/migration`). Hibernate runs with
  `ddl-auto=validate` and never alters the schema. Migrations are additive.
- **Spring Data JPA** handles entity access. Redirect counting is one atomic
  `UPDATE … SET redirect_count = redirect_count + 1` statement.
- **Seven tables**:
  - `link`: realizes the spec entities Short Link and Link Analytics, with analytics as columns on
    the row;
  - `idempotency_record`;
  - `workflow_run`;
  - `workflow_stage`;
  - `decision`;
  - `policy_evaluation`;
  - `audit_event`.
- **`audit_event` is append-only**: the repository has insert and read methods only, and no API
  path updates or deletes events.
- **No recovery table.** The spec's Recovery Record entity is a view derived from audit events
  (ADR-0005).
- A stage's output and its `SUCCEEDED` status are written in **one completion transaction**,
  which is the basis of attempt rollback (ADR-0005).
- `decision` and `audit_event` rows store `actor_type` and `actor_identity`. Their semantics are
  defined in ADR-0004.

## Rationale

- File-mode H2 gives durability with zero installation.
- Flyway makes every schema change, including SCN-B's, a versioned and reviewable artifact.
- JPA is the most familiar access style for reviewers.
- Deriving recovery data from immutable events keeps one source of truth.
- Rejected options:
  - in-memory H2 fails the restart requirement;
  - a server database violates the single-process constraint;
  - `JdbcClient` is viable but needs more SQL and mapping, so it stays the fallback;
  - `ddl-auto=update` makes schema changes implicit and unreviewable;
  - extra tables are not needed by any requirement.

## Consequences

- Restart-safe state, with versioned and inspectable schema history.
- Long-lived runs rely on additive migrations, and the workflow graph is frozen after delivery
  slice 2.
- H2 dialect quirks may appear; integration tests catch them.

## Risks

- An incompatible workflow-schema change could arrive while a live demonstration run is waiting.
  Mitigation: the plan's stop condition. Stop and decide with the human, and never rewrite
  history.
- The atomic counter depends on correct use of an update query. Mitigation: the concurrency test.

## Prototype trade-off and production path

*Recorded 2026-10-02 at the human candidate's direction (Phase 5 checkpoint). This clarifies the
decision above; it does not change it.* The embedded configuration stays as it is (no
`AUTO_SERVER` mode, no external database).

- **Sufficient for the assessment**: a 2–3 day prototype must run end to end on a reviewer's
  machine with only a JDK. Embedded H2 needs no installation, no credentials and no extra process.
- **Durable**: file mode keeps workflow runs, decisions, audit events and links across restarts. A
  live run can wait for human decisions or external implementation across application restarts.
- **Schema evolution**: Flyway owns every schema change as a versioned, reviewable SQL migration
  (V1 workflow, V2 links, V3 for SCN-B); Hibernate only validates.
- **Persistence isolated**: all access goes through Spring Data repositories and `WorkflowStore`,
  so the storage engine is not visible to the domain or workflow logic.
- **Current limitation**: single process, local deployment. The database file is locked by the
  running application, and the per-run workflow lock (`RunLocks`) is in memory. So only one
  application instance may serve a given data directory.
- **Production scaling path**: an external relational database (e.g. PostgreSQL) behind the same
  migrations and repositories. The in-memory per-run lock would be replaced by database locking
  (row lock or advisory lock on the run) or a distributed lock, so several application instances
  can share the workflow state safely.

## Reversibility

High. JPA plus Flyway ports to PostgreSQL with a driver and dialect change. Moving from JPA to
`JdbcClient` would be local to the repositories.

## Validation

- `RestartPersistenceTest` runs two application contexts over one H2 file.
- `AuditTest` checks that the audit trail is append-only.
- `LinkConcurrencyTest` checks the atomic counter (PVT-008).
- Flyway validates the schema at startup.
