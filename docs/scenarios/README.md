# Scenario runs

Live runs on the local demonstration data directory `./data`. Every HUMAN-typed request below was
issued by the candidate (pre-implementation review H5). Runtime exports follow in T127.

## SCN-A — greenfield (FR-SCN-001)

- Run id: `d76be1a7-7e1d-4338-8fd4-c47535786b1e`
- T049: `DESIGN_APPROVAL` granted by HUMAN/candidate (decision 3, 2026-10-02T12:53:17Z), plan version 1.
- Implementation: commit `8c1eeffeb258512cdec36a0257c24251c1211510` (Phase 4), made after the design
  approval. Validation stages: commit `d587830bc9637320513b73c0edb8b573a27fe323` (Phase 5), the build
  running when the evidence was recorded.
- T078: implementation evidence recorded by HUMAN/candidate (decision 4, 2026-10-02T14:47:44Z), 13
  changed artifacts, all 7 designed components cited, 12 requirement IDs (the run's full scope).
- Parallel validation (persisted intervals, 2026-10-02T14:47Z):
  - TEST, stage-worker-1, 44.944850 → 44.986085 (5/5 acceptance probes passed; 4 probe links created,
    0 remaining);
  - DOCS, stage-worker-2, 44.945849 → 44.948389 (8/8 required sections);
  - SECURITY, stage-worker-3, 44.948389 → 44.949402 (20/20 unsafe inputs rejected; safe control
    accepted);
  - TEST overlaps DOCS and SECURITY; DOCS and SECURITY only touch at one instant. Concurrency of the
    real executors is proven by `ParallelValidationTest`.
  - RELEASE_READINESS started at 45.029230, after all three ended; ready, AUD-01 PASS.
- T078: `RELEASE_APPROVAL` granted by HUMAN/candidate (decision 5, 2026-10-02T14:49:49Z) with four
  accepted risks: self-reported evidence; host names resolving to private addresses not blocked
  (EXC-009); no authentication (EXC-003); DOCS/SECURITY intervals only touching.
- Outcome: `COMPLETED`. 39 audit events, gap-free. Final report: `GET /api/workflows/{id}/report`.

## SCN-B — brownfield (FR-SCN-002)

- Run id: `b4ff60fc-d24e-4833-8fd7-54625c1ccd48`, submitted by HUMAN/candidate on 2026-10-02T16:45:46Z
  on build `d157765` (Phase 7). BROWNFIELD, impact analysis with ten areas and data flows, CHG-01 PASS,
  waiting at `DESIGN_APPROVAL` (plan 1). Pre-code impact analysis: [scn-b-impact-analysis.md](scn-b-impact-analysis.md).
- T110: `DESIGN_APPROVAL` granted by HUMAN/candidate (decision 14, 2026-10-02T17:55:21Z), plan version 1,
  after review of the impact analysis. No expiration code existed then (last commit `d157765`,
  2026-10-02T16:39:14Z); the expiration tests and code were written afterwards (Phase 8).
- Implementation: commit `b3d0b8d57cbfb9443cddf257f03a930c9049e38b` (2026-10-02T18:20:27Z, Phase 8), the build
  running when the evidence was recorded.
- T120: implementation evidence recorded by HUMAN/candidate (decision 18, 2026-10-02T18:23:07Z): 7 changed
  artifacts, requirement IDs FR-URL-008/009; designed component `RedirectController` not cited (unchanged by
  design, listed as a residual risk).
- Validation (persisted intervals, 2026-10-02T18:23:07Z):
  - TEST, stage-worker-2, .118300 → .557703: `probe.expired-link` passed ("active link redirected; redirect
    after expiry refused with EXPIRED; redirect count stayed 1"); 0 probe links remaining;
  - DOCS, stage-worker-3, .122831 → .123823: 8/8 sections;
  - SECURITY, stage-worker-4, .125827 → .135823: 20/20 unsafe inputs rejected;
  - TEST overlaps DOCS and SECURITY; DOCS and SECURITY do **not** overlap each other (final report
    `intervalsOverlapPairwise: false`). Concurrency of the real executors is proven by `ParallelValidationTest`.
  - RELEASE_READINESS started at .599134, after all three ended; ready, AUD-01 PASS, CHG-01 PASS.
- T120: `RELEASE_APPROVAL` granted by HUMAN/candidate (decision 19, 2026-10-02T18:25:30Z) with four accepted
  risks: RedirectController not cited; self-reported evidence; no authentication (EXC-003); DOCS/SECURITY
  intervals not overlapping.
- Outcome: `COMPLETED`. 40 audit events, gap-free. Final report outcome `RELEASE_APPROVED`.
- Not used as evidence: runs `5e73e337-db63-4d1e-8b2c-d0f0fba93ce7` and
  `942efe8b-eb66-4027-9236-96b592042a75` were created by mistake on a stale Phase 5 process that was still
  serving port 8080, so their impact analyses lack data flows. Both were terminated by HUMAN/candidate
  (status FAILED) with that reason.
  Run `f660508d-68f0-4f78-af41-ecf444983b66` was a duplicate SCN-B submission made by mistake after the
  design approval of `b4ff60fc`; it was terminated by HUMAN/candidate (status FAILED) with that reason.
