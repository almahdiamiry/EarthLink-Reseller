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
│ CURRENT VERIFIED TEST BASELINE: 579 / 579 TESTS PASSING (100% GREEN)                   │
│ CURRENT CHECKPOINT:             1a8c8c9                                                │
│ GOVERNING PLAYBOOK:             OPERATIONAL TESTING PLAYBOOK (AGENTS.md §9)            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

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

