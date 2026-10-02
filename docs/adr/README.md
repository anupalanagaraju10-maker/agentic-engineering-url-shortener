# Architecture Decision Records

Material decisions for the Agentic SDLC URL Shortener. Each ADR implements the approved plan
([specs/001-agentic-sdlc-url-shortener/plan.md](../../specs/001-agentic-sdlc-url-shortener/plan.md),
revision 3, approved 2026-10-02).

Status values: **Proposed** (drafted, not yet binding) → **Accepted** (set only by the human
candidate) → **Superseded** (replaced by a later ADR; never deleted).

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-single-process-modular-monolith.md) | Single-process modular monolith on Java 21 / Spring Boot 3.5 / Maven; application-plane vs control-plane boundary | Accepted (2026-10-02) |
| [0002](0002-persistence-h2-flyway-jpa.md) | File-backed H2 + Flyway + JPA; 7 tables; append-only audit | Accepted (2026-10-02) |
| [0003](0003-in-process-dag-engine.md) | In-process DAG engine; wave scheduling; deterministic stages; `IMPLEMENT` as `EXTERNAL_ACTION` | Accepted (2026-10-02) |
| [0004](0004-human-governance-actor-model-replanning.md) | HUMAN-only gates; actor model; evidence rules; decision lineage; replanning; policy v1 | Accepted (2026-10-02); amendment re-accepted 2026-10-02 |
| [0005](0005-reliability-and-recovery.md) | Classification, bounded retry, timeout, fallback, rollback vs compensation, safe-stop, resume, MTTR from events | Accepted (2026-10-02); amendment re-accepted 2026-10-02 |
