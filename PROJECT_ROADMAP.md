# PROJECT_ROADMAP.md — DYNAMIC PROJECT GPS
## Current Milestone & Active Workstream Router

---

### 🧭 NAVIGATION & CONTEXT
* **Why You Are Here:** This document is the dynamic project GPS summarizing the current operating state and authorized active tasks.
* **What This Document Owns:** Current Operating Mode, Active Workstream, and Milestone Completion Records.
* **Where To Go Next:**
  * For operational rules & safety invariants $\rightarrow$ [AGENTS.md](AGENTS.md)
  * For product requirements & business truth $\rightarrow$ [Target Product Contract v0.6](docs/authority/Target%20Product%20Contract%20v0.6.md)
  * For architectural rulings $\rightarrow$ [Final Adjudication Memo](docs/authority/Final%20Independent%20Adjudication%20Memo.md)

---

## 1. Current Operating Mode

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               CURRENT OPERATING STATE                                  │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ OPERATING MODE:        POST-V1 / STABLE MAINTENANCE                                    │
│ ACTIVE WORKSTREAM:     NONE — SYSTEM IDLE (READY FOR MAINTENANCE TASKS)                │
│ V1 RELEASE BASELINE:   FROZEN FOR V1 RELEASE (Certified commit: 6d91dbd)               │
│ POST-V1 EVOLUTION:     CONTROLLED VIA PROPORTIONAL VERIFICATION MODEL                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Completed Milestone Record

All foundational remediation and verification phases are **formally complete and closed**:

| Phase / Gate | Scope | Status | Reference / Evidence |
|:---|:---|:---|:---|
| **PHASE-00** | Authority & Architecture Freeze | **CLOSED** | [Target Product Contract v0.6](docs/authority/Target%20Product%20Contract%20v0.6.md), [Final Adjudication Memo](docs/authority/Final%20Independent%20Adjudication%20Memo.md) |
| **PHASE-01** | G1 Durable Intent & Restart Durability | **CLOSED** | [Wave 1 Final Spec](EarthLink-Reseller_Wave1_Step1-3_Final.md), `Workstream13ProcessRestartDurabilityRealDbTest` |
| **PHASE-02** | Candidate Scope & Wave-1 Reconciliation | **CLOSED** | [Wave 1 Report v3](EarthLink-Reseller_Wave1_Report_v3.md), `accountStatement` 4-tuple correlation |
| **PHASE-03** | G4 Lineage, G5 Identity & G8 Certification | **CLOSED** | [Evidence Directory](evidence/), 391 unit tests, 79 G8 adversarial probes, sealed release APK |
| **PHASE-04** | V1 Closure & Governance Consolidation | **CLOSED** | [AGENTS.md](AGENTS.md) (V1 Operating Government), Master Design v1.2, Simplification Baseline v1.2 |

---

## 3. Scope Rules for Future Work

1. **No Backlog in Completed Gates:** G1 through G8 and Wave 1 are completed historical milestones. Do not treat historical summaries as an open implementation backlog.
2. **Proportional Verification:** Verify changes according to risk as defined in [AGENTS.md](AGENTS.md) (Level 1 doc diff to Level 5 release build).
3. **Lean Planning:** Minor bug fixes and UI adjustments do not require formal implementation plans; major architectural changes require a concise working plan.

---

## 4. Static Strategic Authority Directory

* **Business & Product Authority:** [Target Product Contract v0.6](docs/authority/Target%20Product%20Contract%20v0.6.md)
* **Architectural Boundaries:** [Final Independent Adjudication Memo](docs/authority/Final%20Independent%20Adjudication%20Memo.md)
* **Engineering Architecture Models:** [G1-G8 Architecture Summary](docs/authority/G1-G8%20Consolidated%20Architecture%20Summary.md)
* **Field Authority Classification:** [Account Field Authority](docs/authority/account_field_authority_classification.md)
* **Ledger Identity Inventory:** [Ledger Identity Inventory](docs/authority/ledger_identity_inventory.md)

