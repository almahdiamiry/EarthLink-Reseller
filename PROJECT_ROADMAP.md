# PROJECT_ROADMAP.md — DYNAMIC PROJECT GPS
## Current Milestone & Active Workstream Router

---

### 🧭 NAVIGATION & CONTEXT
* **Why You Are Here:** This document is the dynamic project GPS tracking the current operating state, active workstream, and authorized post-V1 improvements.
* **What This Document Owns:** Current Operating Mode, Active Workstream State, and Authorized Post-V1 Work items.
* **Governing Rules:** All development, testing depth, and architectural boundaries are strictly governed by [`AGENTS.md`](AGENTS.md).

---

## 1. Current Operating Mode

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               CURRENT OPERATING STATE                                  │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ OPERATING MODE:        POST-V1 / STABLE MAINTENANCE                                    │
│ ACTIVE WORKSTREAM:     NONE — SYSTEM IDLE (READY FOR MAINTENANCE TASKS)                │
│ CERTIFIED BASELINE:    535 / 535 TESTS PASSING (100% GREEN)                            │
│                        (Certified production code baseline: 6d91dbd)                   │
│ GOVERNING PLAYBOOK:    OPERATIONAL TESTING PLAYBOOK (AGENTS.md §9)                     │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Completed Milestone Summary

All foundational remediation and verification phases are **formally complete and closed**:

| Phase / Gate | Scope | Status | Primary Authority / Evidence |
|:---|:---|:---|:---|
| **PHASE-00** | Authority & Architecture Freeze | **CLOSED** | [`Target Product Contract v0.6`](docs/authority/Target%20Product%20Contract%20v0.6.md), [`Final Adjudication Memo`](docs/authority/Final%20Independent%20Adjudication%20Memo.md) |
| **PHASE-01** | G1 Durable Intent & Process Durability | **CLOSED** | G1 Spec, 4-tuple correlation, recovery sweep |
| **PHASE-02** | Wave 1 Reconciliation & Semantics | **CLOSED** | Non-destructive soft-delete, balance math |
| **PHASE-03** | G4 Lineage, G5 Identity & G8 Certification | **CLOSED** | 79 G8 adversarial checks, sealed release APK, [`evidence/`](evidence/) |
| **PHASE-04** | V1 Closure & Governance Consolidation | **CLOSED** | [`AGENTS.md`](AGENTS.md) (V1 Operating Government) |

---

## 3. Authorized Post-V1 Improvements

These non-blocking engineering improvements are authorized for future maintenance cycles. They must adhere to the Minimum-Change Rule and the Testing Playbook in [`AGENTS.md`](AGENTS.md):

### FW-01 — ViewModel Architectural Thinning
* **Description:** Decompose `EarthlinkSearchViewModel` and eliminate residual in-memory `inflightAccountLocks` by moving orchestration entirely into repository and use-case layers.
* **Why Useful:** Improves presentation-layer testability and architectural separation of concerns.
* **Constraint:** SQLite single-claim authority (`claimDispatch`) already guarantees durable hardware-level execution safety; this is an internal presentation-layer cleanup.

### FW-02 — API DTO Typing & Modernization
* **Description:** Replace residual untyped JSON and generic `Map` response parsing in non-financial API call sites with strongly typed Kotlin data classes.
* **Why Useful:** Increases compile-time type safety and code clarity across secondary network interactions.
* **Constraint:** Applies strictly to non-financial endpoints; all financial and mutation endpoints are already typed and contract-verified.

> ⚠️ **Accepted Technical Debt Notice:** Database schema types (`Double`), sequential Room migrations (1–16), large working files (`Repositories.kt`, `UserDetailScreenV2.kt`), and gated demo mode are **officially accepted V1 technical debt** ([`AGENTS.md` §5](AGENTS.md)). They are NOT backlog items and must not be refactored without an explicit user requirement.
