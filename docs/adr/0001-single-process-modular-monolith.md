# ADR-0001: Single-process modular monolith on Java 21 / Spring Boot / Maven

- **Status**: Accepted (2026-10-02, approved by the human candidate)
- **Date**: 2026-10-02
- **Deciders**: human candidate (approval authority); drafted by Claude Code
- **Plan**: [plan.md](../../specs/001-agentic-sdlc-url-shortener/plan.md) rev. 3 §Architecture Overview,
  §Technical Context, §Project Structure · **Research**: R1, R17, R18

## Context

The assessment demonstrates governed, stateful SDLC orchestration around a deliberately small URL
shortener. It must run locally, be reviewable within a 2–3 day assessment, and avoid unjustified
infrastructure. The candidate's machine has JDK 21.0.1 and Maven 3.9.6; Spring Boot 3.5.16 is in
the local Maven cache.

## Decision Drivers

- Constitution Principle I (simplicity, single deployable, single process) and Technology & Scope
  Constraints (preferred planning inputs; prohibited infrastructure).
- NFR-003: URL-shortener and orchestration capabilities are separable concerns, each testable on
  its own.
- ASM-003: no external services needed to run the system or its tests.
- NFR-010: runs on one developer machine.

## Options Considered

1. **Spring Boot 3.5.x monolith, one Maven module, package-level planes** (chosen).
2. Spring Boot 4.0 monolith.
3. Plain Java without a framework.
4. Separate Maven modules or services per plane.
5. Gradle instead of Maven.

## Decision

- Build **one deployable Spring Boot 3.5.x application** in **one JVM**, using Java 21 and Maven
  with the Maven Wrapper committed. Pin the latest 3.5 patch at scaffolding.
- Use **one Maven module** with three packages:
  - `com.agentic.shortener.link`: the **application plane**, the URL shortener;
  - `com.agentic.shortener.workflow`: the **control plane**, the governed SDLC workflow;
  - `common`: error handling.
- Make the dependency **one-way**: `workflow` may call `link`'s public service API
  (`LinkService`, `UrlValidator`) for validation probes, and `link` never imports `workflow`.
  `PlaneBoundaryTest` enforces this.
- Give each plane its own tables. The only cross-reference is the nullable `link.probe_run_id`
  tag on transient probe links (ADR-0005).
- Limit runtime dependencies to the Spring Boot starters `web`, `data-jpa`, `validation` and
  `actuator`, plus Flyway and H2 (ADR-0002). Expose only the Actuator `health` endpoint.
- Use the Maven coordinates `com.agentic:url-shortener`.

## Rationale

- Option 1 meets every driver with the least code and infrastructure. It also uses the toolchain
  already installed, and builds offline from the local cache.
- Option 2 (Boot 4.0) adds migration risk, because of modular starters and Jackson 3, and no
  requirement benefits from it.
- Option 3 (plain Java) means more hand-written HTTP, validation and persistence code to review.
- Option 4 (modules or services) is unnecessary because one test proves the boundary, and
  services are prohibited without a hard requirement.
- Option 5 (Gradle) has no advantage, and Maven is the preferred planning input.

## Consequences

- Simple to run (`./mvnw spring-boot:run`) and to verify from a clean clone (`./mvnw verify`).
- The plane boundary is a tested convention, not a deployment boundary.
- The application has a single build, one dependency list, and a single configuration file.

## Risks

- The package-level separation could erode over time. Mitigation: `PlaneBoundaryTest` fails the
  build.
- Spring Boot 3.5's support horizon is shorter than 4.x's. This is acceptable for an assessment.

## Reversibility

High. The planes can be split into Maven modules or services later because the dependency is
already one-way. Migrating to Spring Boot 4.x is a contained upgrade.

## Validation

- `./mvnw verify` builds offline from the local cache.
- `PlaneBoundaryTest` passes.
- The application starts and `GET /actuator/health` reports `UP`.
