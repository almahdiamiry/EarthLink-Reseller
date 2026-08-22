# DOCUMENT_INVENTORY.md — REPOSITORY DOCUMENT CLASSIFICATION
## Master Classification Map for EarthLink Reseller V1

---

### 🧭 NAVIGATION & CONTEXT
* **Why You Are Here:** This document provides the definitive 4-tier classification of all repository files to prevent agent scope confusion.
* **What This Document Owns:** File Status & Classification Map.
* **Where To Go Next:**
  * For operational rules & safety invariants $\rightarrow$ [AGENTS.md](AGENTS.md)
  * For current state & milestone GPS $\rightarrow$ [PROJECT_ROADMAP.md](PROJECT_ROADMAP.md)

---

## 1. The 4-Tier Document Topology

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              THE 4-TIER DOCUMENT TOPOLOGY                              │
├─────────────────────────┬──────────────────────────────────────────────────────────────┤
│ TIER                    │ OPERATIONAL MEANING                                          │
├─────────────────────────┼──────────────────────────────────────────────────────────────┤
│ **1. OPERATIONAL & GPS**│ Active governing documents for daily development.            │
│ **2. STATIC STRATEGIC** │ Frozen business truth and technical boundaries.              │
│ **3. EVIDENCE & PROOFS**│ Machine contracts, sealed test receipts, and G8 tooling.     │
│ **4. HISTORICAL**       │ Path-locked forensic records (Immutable hash preservation).  │
└─────────────────────────┴──────────────────────────────────────────────────────────────┘
```

---

## 2. Master Document Inventory

| File / Directory | Tier | Authority Level | Lifecycle | Action / Rule |
|:---|:---|:---|:---|:---|
| **[`AGENTS.md`](AGENTS.md)** | Tier 1 (Operational) | **Sole Operational Authority** | Controlled | **V1 Operating Government:** Primary entry point for all rules and navigation. |
| **[`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md)** | Tier 1 (GPS) | **Dynamic Milestone GPS** | Dynamic | **Current State GPS:** Tracks active operating mode and authorized next step. |
| **[`FUTURE_WORK.md`](FUTURE_WORK.md)** | Tier 1 (Future Work) | **Future Improvements List** | Dynamic | **Future Work:** Tracks legitimate non-blocking improvements and conditional items. |
| **[`docs/authority/PLAN_STATUS.md`](docs/authority/PLAN_STATUS.md)** | Tier 1 (Plans) | **Plan Status Registry** | Dynamic | **Plan Registry:** Tracks formal multi-session implementation plan lifecycles. |
| **[`docs/authority/Target Product Contract v0.6.md`](docs/authority/Target%20Product%20Contract%20v0.6.md)** | Tier 2 (Strategic) | **Frozen Business Authority** | Frozen | **Highest Product Truth:** Defines domain rules and data integrity mandates. |
| **[`docs/authority/Final Independent Adjudication Memo.md`](docs/authority/Final%20Independent%20Adjudication%20Memo.md)** | Tier 2 (Strategic) | **Frozen Technical Authority** | Frozen | **Highest Architecture Truth:** Defines Direct Room and concurrency boundaries. |
| **[`docs/authority/G1-G8 Consolidated Architecture Summary.md`](docs/authority/G1-G8%20Consolidated%20Architecture%20Summary.md)** | Tier 2 (Strategic) | **Engineering Interpretation** | Frozen | **Architecture Models:** Cross-gate dependency and bounded recovery references. |
| **[`docs/authority/account_field_authority_classification.md`](docs/authority/account_field_authority_classification.md)** | Tier 2 (Strategic) | **Field Authority** | Controlled | **Entity Authority Map:** Room vs. Firestore vs. ISP field ownership. |
| **[`docs/authority/ledger_identity_inventory.md`](docs/authority/ledger_identity_inventory.md)** | Tier 2 (Strategic) | **ID Provenance Authority** | Controlled | **ID Inventory:** Canonical mapping of all 10 ledger creation paths. |
| **[`AI_DEVELOPMENT_GUIDE.md`](AI_DEVELOPMENT_GUIDE.md)** | Tier 2 (Supporting) | **Development Guidelines** | Controlled | **Engineering Patterns:** Kotlin, Jetpack Compose, and Room patterns. |
| **[`contract/`](contract/)** | Tier 3 (Evidence) | **Machine Contracts** | Managed | **Machine Contracts:** YAML invariant rules and test matrix coverage maps. |
| **[`evidence/`](evidence/)** | Tier 3 (Evidence) | **Sealed Machine Proofs** | Immutable | **Verification Receipts:** Sealed JSON bundles, probe outputs, APK hashes. |
| **[`scripts/`](scripts/)** | Tier 3 (Tooling) | **Verification Harness** | Managed | **G8 Verification Tools:** Independent verification runners. |
| **[`CHANGELOG.md`](CHANGELOG.md)** | Tier 4 (Historical) | **Audit Log** | Append-Only | **Historical Commit Ledger:** Chronological release history (Audit only). |
| **`PRODUCTION_INVARIANTS.md`** | Tier 4 (Historical) | **Path-Locked Record** | Immutable | **LEAVE UNTOUCHED:** Retained in root for cryptographic hash integrity. |
| **`ARCHITECTURE.md`** | Tier 4 (Historical) | **Path-Locked Record** | Immutable | **LEAVE UNTOUCHED:** Retained in root for historical evidence integrity. |
| **`PRODUCTION_CONTRACT_MATRIX.md`** | Tier 4 (Historical) | **Path-Locked Record** | Immutable | **LEAVE UNTOUCHED:** Retained in root for historical evidence integrity. |
| **`docs/authority/EARTHLINK_V1_HANDOVER.md`** | Tier 4 (Historical) | **Forensic Handover** | Immutable | **LEAVE UNTOUCHED:** Read-only transition context from ZIP 71 audit. |
| **[`docs/LESSONS_LEARNED/*`](docs/LESSONS_LEARNED/)** | Tier 4 (Historical) | **Lessons Learned Archive** | Controlled | **Engineering & Testing Lessons:** Distilled practical rules and testing techniques. |
