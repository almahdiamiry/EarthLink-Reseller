# PROJECT_ROADMAP.md: DYNAMIC PROJECT GPS
## Current Milestone and Active Workstream Router

---

### Navigation and Context
* **Purpose:** Dynamic project GPS tracking the current operating state, active workstream, and deferred post-V1/V2 candidates.
* **Truth Ownership:** Current Operating Mode, Active Workstream State, and Workstream Boundaries.
* **Governing Rules:** All development, testing depth, and architectural boundaries are strictly governed by [`AGENTS.md`](AGENTS.md).

---

## 1. Current Operating Mode

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               CURRENT OPERATING STATE                                  │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ OPERATING MODE:                 POST-V1 / STABLE MAINTENANCE                           │
│ ACTIVE WORKSTREAM:              NONE — SYSTEM IDLE                                     │
│ CURRENT VERIFIED TEST BASELINE: 808 / 808 TESTS PASSING (100% GREEN)                   │
│ CURRENT CHECKPOINT:             c85bdec                                                │
│ GOVERNING PLAYBOOK:             OPERATIONAL TESTING PLAYBOOK (AGENTS.md §9)            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

> **Baseline changed from 798 to 797 at Round 13, and the count is not a regression.** The Round-13
> repair plan sanctioned exactly one deletion — `Phase1FirestoreDocumentIdentityTest.testScenarioJ_counterfactualRawPayloadContainsRawJson`,
> a tautological control for a control — so 798 − 1 = **797** is arithmetically forced and was
> **measured**, not remembered (113 JUnit XML, 797 `testcase` elements, 0 failures / 0 errors,
> `:app:testDebugUnitTest --rerun-tasks`, log `r13-t7-baseline.log`). The checkpoint `bbc4cb1` is the
> last repair commit — it repairs `oracle_noteTransaction_zeroFinancialImpact`, the **fourteenth** of
> the fourteen findings, which no task in the original repair plan covered — and was proven to exist
> via `git cat-file -e "bbc4cb1"` with `$LASTEXITCODE` = 0, per `AGENTS.md`
> §12.1. **No gate script asserts a test count** — `collect_closure_evidence.py:214` needs only
> `total_tests > 0` — so the release gate is unaffected.
>
> **Reading this number correctly:** 797 green means 797 assertions pass. It does **not** mean 797
> behaviours are guarded. Round 13 found 14 tests that proved nothing about what they named (13
> repaired, 1 deleted) and left **41 cohort members unadjudicated** and **~86 of 113 test files never
> audited** — see §6 of the results document before citing this baseline as coverage.

---

## 2. Completed Milestone Summary

All foundational remediation, verification, and maintainability phases are **formally complete and closed**:

| Phase / Milestone | Scope | Status | Primary Authority / Evidence |
|:---|:---|:---|:---|
| **PHASE-00** | Authority and Architecture Freeze | **CLOSED** | [`Target Product Contract v0.6`](docs/authority/Target%20Product%20Contract%20v0.6.md), [`Final Adjudication Memo`](docs/authority/Final%20Independent%20Adjudication%20Memo.md) |
| **PHASE-01** | G1 Durable Intent and Process Durability | **CLOSED** | G1 Spec, 4-tuple correlation, recovery sweep |
| **PHASE-02** | Wave 1 Reconciliation and Semantics | **CLOSED** | Non-destructive soft-delete, balance math |
| **PHASE-03** | G4 Lineage, G5 Identity and G8 Certification | **CLOSED** | 79 G8 adversarial checks, sealed release APK, [`evidence/`](evidence/) |
| **PHASE-04** | V1 Closure and Governance Consolidation | **CLOSED** | [`AGENTS.md`](AGENTS.md) (V1 Operating Government) |
| **MNT-PASS** | V1 Maintainability and Seam Cleanup | **CLOSED** | Dead code removal, boundary cleanup, string normalization |
| **SAFETY-RECON** | V1 Safety Fixes & Concurrency Hardening | **CLOSED** | WS1–WS9 verified (25 new tests, 607/607 green), commits `b68916f`, `ff574cc` |
| **BUG-HUNT-R4-12** | Adversarial Bug Hunt, Rounds 4–12 | **CLOSED** | 12 confirmed defects fixed, 0 false positives shipped. See [`LL-ROUND-4-12-RESULTS`](docs/LESSONS_LEARNED/LL-ROUND-4-12-RESULTS.md) and the method in [`LL-BUG-HUNT-METHODOLOGY`](docs/LESSONS_LEARNED/LL-BUG-HUNT-METHODOLOGY.md). Baseline grew 607 → 798. Per-file audit coverage 61/61 in [`coverage-tracker.tsv`](docs/LESSONS_LEARNED/coverage-tracker.tsv). |
| **BUG-HUNT-R13** | Test-Suite Evidence Audit (the tests, not the product) | **CLOSED** | 14 confirmed-fake tests found; **13 repaired, 1 deleted**; 0 product defects asserted. Method: [`LL-BUG-HUNT-METHODOLOGY`](docs/LESSONS_LEARNED/LL-BUG-HUNT-METHODOLOGY.md) §7.2. Results, per-repair evidence, and the unproven surface: [`LL-ROUND-13-TEST-EVIDENCE-AUDIT`](docs/LESSONS_LEARNED/LL-ROUND-13-TEST-EVIDENCE-AUDIT.md). **Baseline 798 → 797 because one dead counterfactual (`testScenarioJ_counterfactualRawPayloadContainsRawJson`) was deleted** — the only sanctioned deletion in the round, guarded by a stop-gate that confirmed Scenario I was the real guard. Commits `6a53357`, `ff5b485`, `2d9ab99`, `b2026e2`, `d728902`, `0b14755`, `bbc4cb1` — **seven** repair commits; the last repaired the one finding no task in the plan covered. **Not a coverage closure:** 41 cohort members unadjudicated, ~86 of 113 files never audited, and the P1–P3 screen measured at 50% misprediction in both directions — see §6 of the results document. |

---

## 3. Deferred Post-V1 / V2 Candidates

These non-blocking engineering items are deferred candidates for post-V1 / V2 consideration. They are NOT active backlog items and are retained for future reference only:

### FW-01: ViewModel Architectural Thinning
* **Description:** Decompose `EarthlinkSearchViewModel` and eliminate residual in-memory `inflightAccountLocks` by moving orchestration entirely into repository and use-case layers.
* **Why Useful:** Improves presentation-layer testability and architectural separation of concerns.
* **Constraint:** SQLite single-claim authority (`claimDispatch`) already guarantees durable hardware-level execution safety; this is an internal presentation-layer cleanup.

### FW-02: API DTO Typing and Modernization
* **Description:** Replace residual untyped JSON and generic `Map` response parsing in non-financial API call sites with strongly typed Kotlin data classes.
* **Why Useful:** Increases compile-time type safety and code clarity across secondary network interactions.
* **Constraint:** Applies strictly to non-financial endpoints; all financial and mutation endpoints are already typed and contract-verified.

> ⚠️ **Accepted Technical Debt Notice:** Database schema types (`Double`), sequential Room migrations (1–16), large working files (`Repositories.kt`, `UserDetailScreenV2.kt`), and gated demo mode are **officially accepted V1 technical debt** ([`AGENTS.md` §5](AGENTS.md)). They are NOT backlog items and must not be refactored without an explicit user requirement.

