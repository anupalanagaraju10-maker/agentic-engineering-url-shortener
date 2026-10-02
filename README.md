# Agentic Software Engineering System — URL Shortener

A working prototype that turns a requirement into a reviewable engineering outcome under controlled
autonomy. A governed, stateful workflow (explicit dependency graph, human gates, parallel validation,
bounded recovery, policy guardrails, audit trail, metrics, replanning) drives changes to a real URL
shortener (create, redirect, analytics, idempotency, optional expiration). The human candidate approves;
Claude Code implements; the workflow verifies the running build.

- **Architecture**: [docs/architecture/overview.md](docs/architecture/overview.md) ·
  decisions [docs/adr/](docs/adr/README.md)
- **Specification (SpecKit)**: [spec](specs/001-agentic-sdlc-url-shortener/spec.md) ·
  [plan](specs/001-agentic-sdlc-url-shortener/plan.md) · [tasks](specs/001-agentic-sdlc-url-shortener/tasks.md) ·
  [API contract](specs/001-agentic-sdlc-url-shortener/contracts/openapi.yaml) ·
  [quickstart](specs/001-agentic-sdlc-url-shortener/quickstart.md)
- **Evidence**: [three live scenarios](docs/scenarios/README.md) · [traceability matrix](docs/traceability/matrix.md) ·
  [measurements](docs/assessment/measurements.md) · [reviewer guide](docs/assessment/reviewer-navigation.md)
- **Conclusions**: [final engineering summary](docs/assessment/final-engineering-summary.md) ·
  [final assessment](docs/assessment/final-assessment.md)

## Prerequisites

- JDK 21 (`JAVA_HOME` must point to the JDK folder, not its `bin` folder).
- Nothing else: Maven comes through the wrapper, the database is embedded (H2, file `./data/shortener`).
- `curl` and Git Bash (or any bash) only for `scripts/export-run.sh`.

## Build, test, run

```bash
./mvnw verify                      # Windows: .\mvnw.cmd verify — builds and runs the automated tests
./mvnw spring-boot:run             # starts on http://localhost:8080 (fault injection OFF)
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo   # demo profile: fault injection ON (recovery demos)
./mvnw test -Dgroups=measurement -Dtest.excluded.groups=none   # demonstration performance measurements
```

Health: `GET /actuator/health`. Tests use an in-memory database and never touch `./data`.

## Try it

```bash
# the URL shortener
curl -s -X POST localhost:8080/api/links -H 'Content-Type: application/json' -d '{"url":"https://example.com/docs"}'
curl -si localhost:8080/r/<code>                 # 302 with Location and Cache-Control: no-store
curl -s localhost:8080/api/links/<code>          # redirectCount, lastRedirectAt

# a governed change
curl -s -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' \
  -d '{"requirement":"Make links expire.","actorType":"HUMAN","actorIdentity":"candidate"}'
# → AWAITING_CLARIFICATION; continue with /clarify, /approve, /implementation, /approve (see quickstart)
curl -s localhost:8080/api/workflows/<id>          # stages, policy results, status, pending action
curl -s localhost:8080/api/workflows/<id>/events   # append-only audit trail
curl -s localhost:8080/api/metrics/workflows       # demonstration metrics
```

The quickstart walks through the three scenarios and every recovery demonstration step by step.

## The three scenarios (run live, decisions by the candidate)

| Scenario | Requirement | Shows | Run |
|---|---|---|---|
| Greenfield | "Create a short link …, redirect …, record redirect count and last redirect time." | design approval → implementation → parallel validation → release | `d76be1a7…` COMPLETED |
| Brownfield | "Add optional expiration to existing links; expired links return an expired result distinct from not-found." | impact analysis before code → approval → code → live 410 probe → release | `b4ff60fc…` COMPLETED |
| Ambiguous | "Make links expire." | suspension for clarification → human answer → replan (plan 2) → no new code needed → release | `dbbf2627…` COMPLETED |

## Testing approach

- **Test-first** for every behavior task: tests were written and run red before implementation, and each
  phase ended with a full green `./mvnw verify` (results per phase in the traceability matrix).
- **277 automated tests** (JUnit 5, MockMvc, Spring Boot test): URL rules and API, concurrency (50
  parallel creates/redirects), storage failure, engine ordering and parallelism, human gates and actor
  rules, policies, every recovery path through injected faults, crash-and-restart recovery on a real
  database file, replanning atomicity, determinism, metrics, the OpenAPI contract, and the three scenarios.
- **Live scenario runs** on the real application, with exported runtime evidence (`scripts/export-run.sh`).

## Limitations and accepted risks

- No authentication; `actorType` is self-declared (accepted risk, owner: the candidate).
- Host names that resolve to private addresses are not blocked; only IP literals are checked.
- The application never writes code: IMPLEMENT evidence is external and self-reported; it does not query
  Git. TEST and SECURITY verify the behavior of the running build, not the revision.
- Requirement understanding is rule- and vocabulary-based (deterministic, documented lists), not a
  language model; requirements outside the vocabulary are refused rather than guessed.
- Metrics are demonstration data from local runs.
- One HTTP command may take up to about 90 s in the worst case (repeated injected failures); normal
  commands take milliseconds.
- The runtime cannot observe when external coding started; approval-before-code is shown by decision and
  commit timestamps.
- Transitive Hibernate is LGPL-2.1+ (dependency licenses: research R11).
- No destructive or irreversible action exists; one would need its own human gate.
- Human-operated actions rule: every HUMAN-typed request in the live demonstrations was issued by the
  candidate, not by the assistant.
- Single process, local deployment: the embedded database and in-memory run locks allow one instance per
  data directory (scaling path: [ADR-0002](docs/adr/0002-persistence-h2-flyway-jpa.md)).
- Back up `./data` before risky steps: live evidence lives there until exported.

## Trade-offs

Simplicity over infrastructure: one process, an embedded database and an in-process engine keep the
prototype runnable on a laptop with only a JDK, while every orchestration behavior the assignment asks
for is implemented and tested. The architecture overview maps each component to the requirement it
serves and lists what was deliberately not built.
