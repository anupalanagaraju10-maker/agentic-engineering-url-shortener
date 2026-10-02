# Human gates log

Every human decision of this project, with the evidence that records it (reference-alignment review F7,
T137). **Rule**: no row without evidence. Lifecycle approvals were given by the candidate in the working
session; their durable record is the approval statement in the artifact and the commit that captured it.
Runtime decisions are rows in each run's decision lineage (`GET /api/workflows/{id}/decisions`), made by
`HUMAN/candidate` and sent by the candidate (pre-implementation review condition H5).

## SpecKit lifecycle gates

| # | Gate | Decision | Evidence |
|---|---|---|---|
| 1 | Constitution | ratified v1.0.0, 2026-10-01 | `.specify/memory/constitution.md` "Status: RATIFIED by the human candidate on 2026-10-01"; commit `9229abe` |
| 2 | Specification | approved after review and clarification, 2026-10-01 | `spec.md` "Status: Approved by the human candidate on 2026-10-01"; commit `781a2b0` |
| 3 | Architecture (plan revision 3) | approved, 2026-10-02 | `plan.md` "Status: APPROVED (revision 3) — architecture approved by the human candidate on 2026-10-02"; commit `e4eb0d5` |
| 4 | ADR-0001…0005 | accepted, 2026-10-02 | each ADR "Status: Accepted (2026-10-02, approved by the human candidate)"; commit `bc9064d` |
| 5 | Requirements-quality gate | orchestration checklist 42/42 and round-2 clarifications approved | `checklists/orchestration.md`; commits `5077bac`, `accd597` |
| 6 | `/speckit.analyze` remediation | remediation approved and applied | commit `cb4eb16` |
| 7 | Pre-implementation review | verdict "GO, with conditions" approved; conditions H1–H5 applied | `docs/assessment/pre-implementation-review.md` "Approval" line; commit `8a88936` |
| 8 | Reference-alignment review | findings F1–F7 approved as task amendments | commit `297ff0b` (the review document was later removed from the working tree at the candidate's request; it remains in that commit) |
| 9 | Persistence trade-off | keep the embedded database; document the prototype trade-off | ADR-0002 "Prototype trade-off and production path" ("recorded at the human candidate's direction"); commit `d587830` |
| 10 | Phase checkpoint commits | each implementation phase committed only after the candidate's commit approval | commits `56912d5`, `5df553d`, `dd105d7`, `8c1eeff`, `d587830`, `6b0de77`, `ddafe28`, `d157765`, `b3d0b8d`, `503903f`, `b0c01d9` |

## Runtime gates (live runs)

| Run | Decision | Gate / action | Time (UTC) | Plan |
|---|---|---|---|---|
| Greenfield `d76be1a7-7e1d-4338-8fd4-c47535786b1e` | 3 APPROVAL | DESIGN_APPROVAL | 2026-10-02T12:53:17Z | 1 |
| | 4 IMPLEMENTATION_EVIDENCE | IMPLEMENT (revision `8c1eeff`) | 2026-10-02T14:47:44Z | 1 |
| | 5 APPROVAL | RELEASE_APPROVAL (4 accepted risks) | 2026-10-02T14:49:49Z | 1 |
| Brownfield `b4ff60fc-d24e-4833-8fd7-54625c1ccd48` | 14 APPROVAL | DESIGN_APPROVAL, after the pre-code impact analysis | 2026-10-02T17:55:21Z | 1 |
| | 18 IMPLEMENTATION_EVIDENCE | IMPLEMENT (revision `b3d0b8d`) | 2026-10-02T18:23:07Z | 1 |
| | 19 APPROVAL | RELEASE_APPROVAL (4 accepted risks) | 2026-10-02T18:25:30Z | 1 |
| Ambiguous `dbbf2627-5e6d-499e-8a57-44ccda16d54e` | 21 CLARIFICATION | CLARIFICATION (answers AMB-R2 and AMB-R4) | 2026-10-02T18:33:48Z | 2 |
| | 23 APPROVAL | DESIGN_APPROVAL | 2026-10-02T18:35:39Z | 2 |
| | 24 IMPLEMENTATION_EVIDENCE | IMPLEMENT (no change, with justification) | 2026-10-02T18:36:13Z | 2 |
| | 25 APPROVAL | RELEASE_APPROVAL (4 accepted risks) | 2026-10-02T18:37:21Z | 2 |
| `5e73e337-db63-4d1e-8b2c-d0f0fba93ce7` | 12 TERMINATION | terminate: created on a stale build | 2026-10-02T17:55:21Z | 1 |
| `942efe8b-eb66-4027-9236-96b592042a75` | 13 TERMINATION | terminate: created on a stale build | 2026-10-02T17:55:21Z | 1 |
| `f660508d-68f0-4f78-af41-ecf444983b66` | 17 TERMINATION | terminate: duplicate brownfield scenario submission | 2026-10-02T18:17:19Z | 1 |

## SC-003 check

Checked on 2026-10-02 against the live audit trails: for every HUMAN decision (design approval,
implementation evidence, clarification, release approval), the first activity of each node after that gate
at the same plan version has a higher audit `seq` than the event recording the decision.

| Run | Gate crossings checked | Preceded by the HUMAN decision |
|---|---|---|
| Greenfield | 5 (IMPLEMENT; TEST, DOCS, SECURITY; FINAL_REPORT) | 5 |
| Brownfield | 5 | 5 |
| Ambiguous | 6 (DECOMPOSE after the clarification, then as greenfield scenario) | 6 |
| **Total** | **16** | **16 (100%)** |

The automated tests assert the same rule (`HumanGateTest`, `ScenarioATest`, `ScenarioBTest`,
`ScenarioCTest`).
