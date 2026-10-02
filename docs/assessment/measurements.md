# Measurements (demonstration, non-blocking)

These figures are **demonstration data from one developer machine**, not production performance claims
(NFR-010, SC-011). PVT-003, PVT-004 and PVT-005 are reported, not enforced (spec clarification session
2026-10-01).

## Environment

Windows 11, 12 logical CPUs, Java 21.0.1, Spring Boot 3.5.16, H2 in-memory database for the measurement run.

## Results

Command: `./mvnw test -Dgroups=measurement -Dtest.excluded.groups=none -Dtest=PerformanceMeasurementTest`
(2026-10-02T18:41:26Z) — Tests run: 3, Failures: 0. Raw output: `target/measurements.txt` (regenerated on
each run, not committed).

| Target | Measured | Notes |
|---|---|---|
| PVT-003 create p95 < 1 s | **p50 3.14 ms, p95 6.04 ms**, max 14.63 ms (n = 500) | in-process through MockMvc after 50 warm-up requests; HTTP network time not included |
| PVT-003 redirect p95 < 1 s | **p50 2.72 ms, p95 5.13 ms**, max 36.32 ms (n = 500) | same conditions; every response was 302 |
| PVT-004 greenfield scenario automated-active < 60 s | **1,086 ms** | sum of the four greenfield scenario commands (create, design approval, evidence, release approval) with fixture decisions; human wait excluded by construction |
| PVT-005 startup to healthy < 30 s | **878 ms** in the test; **4.435 s** for the real application | the test starts a second application context inside an already-warm JVM, so it understates a cold start. The cold start is taken from the candidate's own application log: `Started ShortenerApplication in 4.435 seconds` (2026-10-02, default profile, file database) |

## Live scenario timings (from the persisted runs)

Measured by the workflow itself; see `docs/scenarios/README.md` for the full records.

| Run | Parallel group (TEST, DOCS, SECURITY) | Join (RELEASE_READINESS) |
|---|---|---|
| Greenfield `d76be1a7…` | TEST 41 ms; DOCS 2.5 ms; SECURITY 1.0 ms | 15.8 ms |
| Brownfield `b4ff60fc…` | TEST 439 ms (includes the 300 ms expiry wait of the expiration probe); DOCS 1.0 ms; SECURITY 10.0 ms | 27.0 ms |
| Ambiguous `dbbf2627…` | TEST 364 ms (includes the expiry wait); DOCS 1.0 ms; SECURITY 1.0 ms | 7.0 ms |

## Limitations

- In-process measurements exclude HTTP and network time; a load test against the running server was not
  performed.
- One machine, one run; no statistical repetition beyond the 500 samples per operation.
- The live timings are single runs and include JVM warm-up effects.

## Clean-clone verification (T131)

2026-10-02, commit `51d988b13ecc7a4572d5b9d5824e89c79b068b25`:

| Step | Command | Result |
|---|---|---|
| Clone | `git clone <repo> clean-clone` (temporary directory) | HEAD `51d988b`; no untracked files; no `data/` or `target/` |
| Build and test | `./mvnw -q verify` (JDK 21, populated local Maven cache) | exit 0 in 67 s; **276 tests, 0 failures, 0 errors, 0 skipped** (45 test classes, from the surefire reports) |
| Start | `java -jar target/url-shortener-0.1.0-SNAPSHOT.jar --server.port=8082` (default profile) | Flyway "Successfully applied 3 migrations" to a new `data/shortener.mv.db`; "Started ShortenerApplication in 7.451 seconds" |
| Health | `GET /actuator/health` | `{"status":"UP"}` |

The `ERROR` lines in the build log come from tests that simulate storage failure and an aborted replan on
purpose; they are expected output, not failures.
