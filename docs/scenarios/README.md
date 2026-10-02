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
