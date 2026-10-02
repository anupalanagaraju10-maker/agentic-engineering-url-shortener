# Traceability Matrix

Built incrementally by each phase's traceability task and consolidated in T128.

**Rules (constitution Principle VI)**:
- List only tests that have **actually been executed**, with the command and its real result.
- No orphan requirements, tasks, code or tests.
- Evidence is runtime output, never hand-written.

| Requirement | Scenario | ADR | Task | Code | Test | Executed command / result | Evidence |
|---|---|---|---|---|---|---|---|
| FR-URL-014 (partial: health reported UP; the storage-unavailable ⇒ DOWN case is covered in Phase 4, T053) | — | ADR-0001 | T005, T006, T007, T009 | `src/main/java/com/agentic/shortener/ShortenerApplication.java`, `src/main/resources/application.yml` | `src/test/java/com/agentic/shortener/ApplicationSmokeTest.java` | 2026-10-02: `./mvnw -B test` before T006 ⇒ **1 error**, `Unable to find a @SpringBootConfiguration` (expected red). After T006–T008: `./mvnw -B verify` ⇒ **Tests run: 1, Failures: 0, Errors: 0**, BUILD SUCCESS. `./mvnw -B -o clean verify` ⇒ Tests run: 1, Failures 0, Errors 0, BUILD SUCCESS | Local start: `GET /actuator/health` ⇒ HTTP 200 `{"status":"UP"}`; `GET /actuator/env` ⇒ 404 (only health exposed) |
