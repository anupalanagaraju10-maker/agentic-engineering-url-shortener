# Final engineering summary

**Agentic Software Engineering System — URL Shortener** · 2026-10-02 · status: submitted for the candidate's
approval (T139). All figures below come from executed commands or recorded runs.

## 1. What was built

A runnable prototype (one Spring Boot process, Java 21, embedded H2) with two parts:

1. **A URL shortener**: create short links for validated http/https addresses (7-character Base62 codes,
   bounded collision retry), 302 redirects with `Cache-Control: no-store`, redirect count and last-redirect
   time, idempotency keys, optional expiration (410 after expiry), 503 when storage is unavailable, and a
   consistent Problem Details error format.
2. **A governed agentic workflow** that takes a requirement through an explicit 14-node dependency graph:
   intake, understanding and ambiguity detection, clarification (conditional), decomposition, impact analysis
   (conditional, brownfield), design, **design approval (human)**, implementation (external, by an engineer
   or Claude Code, recorded as evidence), **TEST ‖ DOCS ‖ SECURITY in parallel** against the running build,
   release readiness (join), **release approval (human)** and a final report. It includes policy guardrails,
   bounded retry, timeouts, fallback, rollback, compensation, safe-stop and resume, restart recovery, atomic
   replanning, an append-only audit trail and demonstration metrics.

Size: 93 production classes (7,203 lines), 48 test classes (5,782 lines), 25 commits.

## 2. Plan and rationale

| Decision | Rationale |
|---|---|
| **SpecKit lifecycle** (constitution → spec → clarify → plan → ADRs → checklist → tasks → analyze → reviews → implement → converge) | requirements, decisions and approvals are versioned artifacts; every implementation task traces to a requirement |
| **One process, two planes** (ADR-0001) | everything the assignment asks for fits in one JVM; the link plane never depends on the workflow plane |
| **Embedded H2 + Flyway + JPA** (ADR-0002) | durable, inspectable state with zero installation; versioned schema changes; documented path to an external database |
| **In-process DAG engine with waves** (ADR-0003) | explicit, testable orchestration without an external workflow product |
| **Typed actors, HUMAN-only gates, one replan mechanism** (ADR-0004) | controlled autonomy: agents execute, humans decide |
| **Bounded recovery** (ADR-0005) | transient-only retry, DOCS-only fallback, rollback where exact, compensation where side effects exist |
| **Deterministic stage logic** | reproducible runs (tested by `DeterminismTest`); no AI service in the runtime |
| **Test-first, phase checkpoints, human commit approval** | every behavior has a failing test before its implementation; each phase ends green and reviewed |

## 3. The three scenarios (live, decisions by the candidate)

| Scenario | Run | Path | Outcome |
|---|---|---|---|
| **Greenfield**: "Create a short link …, redirect …, record redirect count and last redirect time." | `d76be1a7-7e1d-4338-8fd4-c47535786b1e` | design approval (decision 3) → implementation commit `8c1eeff` → evidence (4) → TEST 5/5 probes, SECURITY 20/20, DOCS 8/8 → release approval (5) | COMPLETED, 39 events |
| **Brownfield**: "Add optional expiration to existing links; expired links return an expired result distinct from not-found." | `b4ff60fc-d24e-4833-8fd7-54625c1ccd48` | impact analysis (ten areas + data flows) **before code** → design approval (14) → expiration code `b3d0b8d` → evidence (18) → live probe 302 then 410, not counted → release approval (19) | COMPLETED, 40 events |
| **Ambiguous**: "Make links expire." | `dbbf2627-5e6d-499e-8a57-44ccda16d54e` | suspended (AMB-R2, AMB-R4), nothing ran past the gate → clarification (21) → replan plan 1 → 2 → design approval (23) → no-change evidence (24, design said no new code) → release approval (25) | COMPLETED, 60 events |

Runtime evidence is exported unedited under `docs/scenarios/greenfield/`, `brownfield/`, `ambiguous/`; every
human decision is listed in `docs/governance/human-gates-log.md`. In all three runs, 16/16 gate crossings
happened only after the corresponding human decision.

## 4. Artifacts

| Kind | Where |
|---|---|
| Specification and plan | `.specify/memory/constitution.md`, `specs/001-agentic-sdlc-url-shortener/` (spec, plan, research, data model, OpenAPI contract, quickstart, tasks, checklists) |
| Decisions | `docs/adr/0001…0005` |
| Architecture | `docs/architecture/overview.md` |
| Code | `src/main/java/com/agentic/shortener/` (`link`, `workflow.*`, `common`); migrations V1–V3 |
| Tests | `src/test/java/…` (277 in the default build + 3 measurements) |
| Evidence | `docs/scenarios/` (log, impact analysis, exports), `docs/governance/human-gates-log.md`, `docs/traceability/matrix.md`, `docs/assessment/measurements.md` |
| Reviews | `docs/assessment/pre-implementation-review.md`, `docs/assessment/final-assessment.md` |
| Tooling | `scripts/export-run.sh`, Maven wrapper |

## 5. Testing approach and results

- **Test-first**: each phase wrote its tests, ran them red, implemented, and ended with a green `./mvnw verify`;
  red and green runs are recorded per phase in the traceability matrix.
- **Coverage of behavior**: URL validation (57 cases), API, concurrency (50 parallel creates and redirects),
  storage failure, engine ordering and parallelism, human gates and actor rules, policies and exceptions, every
  recovery path via injected faults, crash-and-restart on a real database file, replanning atomicity,
  determinism, metrics, the OpenAPI contract, and the three scenarios.
- **Results**: `./mvnw verify` → **277 tests, 0 failures, 0 errors**; also verified from a clean clone
  (`51d988b`: 276 tests at that commit, app started on a new database, health UP). Quickstart walked against
  fresh data: 34 checks passed.
- **Measurements** (demonstration, in-process): create p95 6.04 ms, redirect p95 5.13 ms (n = 500); greenfield
  automated-active duration 1.09 s; cold start 4.4 s (application log).
- **Convergence**: the final `/speckit.converge` reported no gaps after two real findings were fixed or
  recorded (T140, T141).

## 6. Risks, trade-offs and validation

| Risk / trade-off | Choice made | How it is validated or mitigated |
|---|---|---|
| Simplicity vs scale | one process, embedded database | documented scaling path (ADR-0002); clean-clone check |
| Deterministic rules vs flexible understanding | rule- and vocabulary-based | `AmbiguityRulesTest`, `DeterminismTest`; out-of-vocabulary requirements refused, not guessed |
| Self-reported implementation evidence | structure, scope and ordering validated; Git not read | TEST and SECURITY probe the running build; residual risk accepted at release |
| Parallelism visibility | real thread-pool concurrency | `ParallelValidationTest`, DELAY-fault test; live intervals reported as measured |
| Recovery complexity | bounded mechanisms only | fault-injection tests for each mechanism; restart crash image |
| Security | URL validation, no stack traces, HUMAN-only gates | `UrlValidatorTest`, SECURITY stage probes; host-name SSRF limit documented |

## 7. Assumptions

- The official assessment brief stays outside the repository and outranks it (ASM-001).
- A single operator role, the human candidate; actor identity is self-declared (ASM-002, EXC-003).
- Local, single, self-contained application with its own durable storage (ASM-003); programmatic interaction,
  no UI (ASM-004, EXC-001).
- Redirect availability is preferred over analytics completeness (ASM-006).
- Demonstration failures come from controlled injection (ASM-007); audit evidence is retained for the life of
  the local data store (ASM-009); no personal data beyond the original address and timestamps (ASM-010).

## 8. Limitations

- No authentication or authorization (EXC-003).
- Host names that resolve to private addresses are not blocked; only IP literals are (EXC-009).
- The application never writes code and never reads Git; evidence is self-reported.
- Requirement understanding is limited to the registry vocabulary.
- Single instance per data directory.
- Metrics are local demonstration figures.
- Worst-case command latency under repeated injected failures is about 90 s; normal commands take
  milliseconds.

## 9. What would change for production

1. Authentication and authorization; actor identity from the identity provider, not the request body.
2. An external relational database behind the same migrations, with database-level run locking and several
   application instances; stage execution on a durable queue.
3. Verify evidence revisions against the repository (commit exists, touches the cited files, CI green).
4. Resolve destination host names and block private ranges; rate limiting and abuse detection.
5. Expose the build identity (commit id) in `/actuator/info` so operators can see which build a run used.
6. Real observability: metrics export (Prometheus/OpenTelemetry), traces per run, alerting on safe-stops.
7. Optionally, an LLM-assisted understanding stage behind the same deterministic contract and human gates.
