# SCN-B — impact analysis before any code (T134)

Brownfield change, reviewed **before** design approval and before any expiration code exists
(FR-SCN-002, reference-alignment review F3). Every statement below is taken from the live run's
persisted stage outputs and audit trail; nothing here was written by hand except the arrangement and
the notes marked **Reviewer note**.

- **Run**: `b4ff60fc-d24e-4833-8fd7-54625c1ccd48` (plan version 1, policy v1), created
  2026-10-02T16:45:46Z by HUMAN/candidate (audit seq 1) on build `d157765`.
- **Requirement**: "Add optional expiration to existing links; expired links return an expired result
  distinct from not-found."
- **Sources**: `GET /api/workflows/{id}` — `UNDERSTAND`, `DECOMPOSE`, `IMPACT_ANALYSIS`, `DESIGN`
  outputs; `GET /api/workflows/{id}/events` (seq 1–20); `/decisions` (10, 11).

## 1. Classification

| Item | Value | Evidence |
|---|---|---|
| Normalized | `add optional expiration to existing links expired links return an expired result distinct from not found` | UNDERSTAND output; `REQUIREMENT_NORMALIZED` seq 6 |
| Capabilities | `EXPIRATION` | UNDERSTAND output |
| Ambiguity findings | none (`optional` and `existing` satisfy AMB-R2/R4) | UNDERSTAND output; branch decision 10 "not taken", seq 8–9 |
| Change type | `BROWNFIELD` (the word `existing`) | UNDERSTAND output; branch decision 11 "taken", seq 12 |
| Requirement scope | `FR-URL-008`, `FR-URL-009` (TASK-1, acceptance check `probe.expired-link`) | DECOMPOSE output, seq 11 |
| Policies | PRIV-01 PASS (seq 7); SEC-01 PASS, CHG-01 PASS "impact analysis covers all ten areas", DEP-01 NOT_APPLICABLE (seq 17–19) | run `policyEvaluations` |

## 2. Change-impact summary (the ten areas, IMPACT_ANALYSIS seq 14)

| Area | Recorded content |
|---|---|
| Current behavior | 17 recorded statements of the implemented capabilities: CREATE_LINK B1–B6, REDIRECT B1–B4, ANALYTICS B1–B3, IDEMPOTENCY B1–B4 (e.g. "an active link redirects to its original address", "not-found and expired attempts are not counted") |
| Requested behavior | the requirement text above |
| Affected components | `Link`, `LinkService`, `LinkController`, `RedirectController` |
| Interfaces | `POST /api/links (expiresAt)`, `GET /r/{code} (410 Gone)` |
| Data | `link.expires_at (V3__link_expiration.sql)` |
| Tests | `LinkExpirationTest` (new) plus the existing `UrlValidatorTest`, `ShortCodeGeneratorTest`, `LinkServiceCollisionTest`, `LinkApiTest`, `LinkStorageFailureTest`, `LinkConcurrencyTest` |
| Documentation | `contracts/openapi.yaml`, `quickstart.md`, `README.md` |
| Regression risks | recorded behavior of CREATE_LINK (B1..B6), REDIRECT (B1..B4), ANALYTICS (B1..B3) and IDEMPOTENCY (B1..B4) must still hold |
| Security / reliability | "URL validation and/or redirect resolution are touched: re-run the SECURITY probes; storage-failure behavior (FR-URL-013) and the analytics-failure rule (FR-URL-017) must be preserved" |
| Rollback / compensation | "Schema changes are additive Flyway migrations: roll back by reverting the code and adding a corrective migration; applied migrations are never edited" |

## 3. Dependency map (data flows, IMPACT_ANALYSIS `dataFlows`)

```text
EXPIRATION: POST /api/links (expiresAt) → LinkController, RedirectController → LinkService → Link → link.expires_at (V3__link_expiration.sql)
EXPIRATION: GET /r/{code} (410 Gone)     → LinkController, RedirectController → LinkService → Link → link.expires_at (V3__link_expiration.sql)
```

**Reviewer note**: the flows are generated per capability, so each line lists both controllers of
the capability. Read precisely: create goes through `LinkController`, redirect through
`RedirectController`; both reach `LinkService`, the `Link` entity and the new `expires_at` column.

## 4. Approved design (DESIGN seq 16)

- Components: `Link`, `LinkService`, `LinkController`, `RedirectController` (all mapped to TASK-1).
- Interface changes and data change: as in §2.
- Test plan: `LinkExpirationTest`, `probe.expired-link`.
- Dependencies: none (DEP-01 NOT_APPLICABLE). `changesApprovedRequirements`: none.
- `implementationRequired`: **true** (EXPIRATION is `PLANNED` in the registry).
- `securitySensitive`: false. **Reviewer note**: the flag follows the requirement's capabilities
  (only EXPIRATION). SECURITY still runs on every run, and the impact report asks for its probes to be
  re-run because redirect resolution is touched.

## 5. Test-first plan (tasks T111–T113, written before any expiration code)

| Test | What it must show | Requirement |
|---|---|---|
| `LinkExpirationTest` (T111) | a future `expiresAt` is accepted and echoed; a past or present one ⇒ 400 `VALIDATION`; after expiry `GET /r/{code}` ⇒ 410 `EXPIRED`, no redirect, count unchanged; `expiresAt` absent ⇒ never expires (injectable `Clock`); the idempotency fingerprint includes `expiresAt` | FR-URL-001, 008, 009, 011 |
| `CapabilityRegistryTest` (T112) | EXPIRATION `IMPLEMENTED`, statements citing FR-URL-008/009, expiration acceptance probe | research R6 |
| `ScenarioBTest` (T113) | brownfield branch, complete impact report, `IMPLEMENT` not eligible before design approval, evidence, parallel validation including the expiration probe, release, `COMPLETED` | FR-SCN-002 |
| existing suites | all existing link tests stay green (regression risks in §2) | FR-URL-001..017 |

## 6. Regression-risk matrix

| Recorded behavior at risk | Why it is touched | Guarding test(s) |
|---|---|---|
| REDIRECT B1/B2: active ⇒ redirect; unknown ⇒ 404 | redirect resolution gains an EXPIRED outcome | `LinkApiTest`, `LinkExpirationTest`, TEST probes `probe.redirect`, `probe.unknown-code` |
| ANALYTICS B1/B2: count successful redirects only | an expired attempt must not increment the count | `LinkExpirationTest`, `probe.redirect-count` |
| REDIRECT B3, CREATE_LINK B6: storage failure ⇒ 503, never 404/410 | the new 410 path must not mask storage failures | `LinkStorageFailureTest` |
| IDEMPOTENCY B2/B3: replay vs conflict by fingerprint | the request gains `expiresAt` | `LinkApiTest`, `LinkExpirationTest` |
| CREATE_LINK B1–B5: validation and codes | the create request changes shape | `UrlValidatorTest`, `ShortCodeGeneratorTest`, `LinkServiceCollisionTest`, SECURITY probes |
| ANALYTICS B3: no lost count under concurrency | redirect path changes | `LinkConcurrencyTest` |
| EXPIRATION B4 (approved, FR-URL-008): links without an expiration never expire; existing links unaffected | the migration adds a nullable column | `LinkExpirationTest` (absent ⇒ never expires); migration `V3` is additive (`NULL` default) |

## 7. Decision requested

The candidate reviews this analysis and the design, then decides `DESIGN_APPROVAL` for plan version 1
(T110). No expiration code is written before that decision.
