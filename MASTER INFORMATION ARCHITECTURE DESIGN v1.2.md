# MASTER INFORMATION ARCHITECTURE DESIGN v1.2
## EarthLink Reseller V1 — Linked Information Topology

---

## 1. Executive Design & Core Philosophy

### 1.1 Scope and Boundary of this Document
This document is the **Master Information Architecture Design (v1.2)** for EarthLink Reseller V1.

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               MASTER DESIGN BOUNDARY                                   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ MASTER INFORMATION ARCHITECTURE                                                        │
│     = Master of TOPOLOGY, CONNECTIVITY, and INFORMATION OWNERSHIP                     │
│                                                                                        │
│ NOT:                                                                                   │
│     = Master of all project truth, or a duplicate of domain contracts                 │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

The Master Design establishes **how documents connect, who owns what, and how any AI agent or human maintainer navigates without guessing or duplicating state.** It does not replace or restate domain truth.

---

### 1.2 Core Architectural Principles
1. **One Obvious Entry Point:** Every session begins in [`AGENTS.md`](AGENTS.md) (The Front Door Router).
2. **The "Owner $\ne$ Router" Law:** A router points to truth, but pointing to truth does **not** make the router an owner or duplicate of that truth.
3. **One Authoritative Home per Fact:** Every fact (business rule, active milestone, plan status, invariant) has exactly one canonical owner.
4. **Linked Navigation (Obsidian-Style Topology):** Hub and authority files connect directly to downstream operational files using repository-relative Markdown links.
5. **Zero Duplication of Mutable State:** Routers and static authorities never duplicate changing project states (current phase, test counts, commit hashes).
6. **Strict Zero-Touch on Cryptographic Records:** Path-locked historical files and evidence bundles (`PRODUCTION_INVARIANTS.md`, `ARCHITECTURE.md`, `evidence/*.json`) are preserved 100% untouched to protect SHA-256 verification receipts.
7. **Lightweight Hub Convention:** Hub navigation blocks apply only where traversal is materially improved; no mandatory rigid status headers on ordinary files.

---

## 2. Information Ownership vs. Routing Model

### 2.1 The "Owner $\ne$ Router" Law
To prevent future drift and stop multiple files from maintaining competing copies of project status, the architecture explicitly separates **Ownership** from **Routing**:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              THE "OWNER ≠ ROUTER" PRINCIPLE                             │
├────────────────────────────────┬───────────────────────────────────────────────────────┤
│ ROLE                           │ RESPONSIBILITY & BOUNDARY                             │
├────────────────────────────────┼───────────────────────────────────────────────────────┤
│ **OWNER (Single Source)**      │ • The authoritative creator and maintainer of a fact.│
│                                │ • Modifies the fact when reality changes.             │
│                                │ • Example: Product Contract owns business truth.      │
├────────────────────────────────┼───────────────────────────────────────────────────────┤
│ **ROUTER (Navigation Pointer)**│ • Directs the reader to the Owner.                    │
│                                │ • Contains NO local copy of the mutable fact.         │
│                                │ • Example: AGENTS.md points to Product Contract.      │
└────────────────────────────────┴───────────────────────────────────────────────────────┘
```

### 2.2 Canonical Source-of-Truth & Routing Map

| Fact / Question Type | Canonical Owner (Single Truth) | Front Door (`AGENTS.md`) Role | Roadmap (`PROJECT_ROADMAP.md`) Role |
|:---|:---|:---|:---|
| **Agent Operational Rules & Invariants** | [`AGENTS.md`](AGENTS.md) | **OWNS DIRECTLY** | Pointers to rules |
| **Product Purpose & Business Rules** | [`docs/authority/Target Product Contract v0.6.md`](docs/authority/Target%20Product%20Contract%20v0.6.md) | **ROUTER** (Points to contract) | **ROUTER** (Points to contract) |
| **Architectural Rulings & Boundaries** | [`docs/authority/Final Independent Adjudication Memo.md`](docs/authority/Final%20Independent%20Adjudication%20Memo.md) | **ROUTER** (Points to memo) | **ROUTER** (Points to memo) |
| **Current Project Milestone & Active Gate** | [`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md) | **ROUTER** (Points to roadmap) | **OWNS DIRECTLY** |
| **Implementation Plan Execution Status** | [`docs/authority/PLAN_STATUS.md`](docs/authority/PLAN_STATUS.md) | **ROUTER** (Points to plan status) | **ROUTER** (Points to plan status) |
| **Database Field Authority (Room vs Cloud)** | [`docs/authority/account_field_authority_classification.md`](docs/authority/account_field_authority_classification.md) | **ROUTER** (Points to classification) | No mention |
| **Ledger Creation Paths (10 Paths)** | [`docs/authority/ledger_identity_inventory.md`](docs/authority/ledger_identity_inventory.md) | **ROUTER** (Points to inventory) | No mention |
| **Machine Invariant Contracts** | [`contract/invariant_contract.yaml`](contract/invariant_contract.yaml) | **ROUTER** (Points to contract/) | No mention |
| **Certification Truth & Machine Proofs** | [`evidence/`](evidence/) | **ROUTER** (Points to evidence/) | **ROUTER** (Points to evidence/) |
| **Historical Commit & Change Ledger** | [`CHANGELOG.md`](CHANGELOG.md) | **ROUTER** (Audit reference only) | No mention |

---

## 3. The Front Door Model (`AGENTS.md`)

### 3.1 Role
`AGENTS.md` is the **Canonical Front Door, Behavioral Authority, and Top-Level Navigation Router**.

### 3.2 Lifecycle
**STABLE / CONTROLLED OPERATIONAL AUTHORITY**
* Updated only when operational rules, invariants, or top-level navigation routes change.
* Contains **zero mutable project state** (no phase names, no test counts, no commit hashes, no plan lists).

### 3.3 What the Front Door OWNS:
1. **Stable Orientation & Summary Identity:** Concise summary of what the application is (offline-capable Android reseller account book) and what it protects. *(It provides orientation, but defers business truth to the Product Contract).*
2. **Mandatory Operational Invariants:**
   * No deleting, weakening, or skipping tests.
   * Direct Atomic Room (Local database is business authority; no staging DB).
   * One State, One Authority.
   * Fail-Closed Security.
   * Plan execution strictly requires explicit `ACTIVE` status in `PLAN_STATUS.md`.
3. **The Master Navigation Router:** Canonical routing table pointing to the single owner of each fact.
4. **The Scope Shield & Quarantine Rules:**
   * G1–G8 are completed/frozen release-boundary work areas; G8 has independent machine certification.
   * Historical plan file existence $\ne$ permission to execute.
   * Path-locked root records are preserved for cryptographic evidence verification; do not use for current planning.

---

## 4. Master Navigation Graph (Linked Topology)

```
                               ┌──────────────────────────────────────────────┐
                               │           FRONT DOOR: AGENTS.md              │
                               │  (Operational Rules & Top-Level Router)      │
                               └──────────────────────┬───────────────────────┘
                                                      │
         ┌────────────────────────────────────────────┼────────────────────────────────────────────┐
         │                                            │                                            │
         ▼                                            ▼                                            ▼
┌──────────────────────────────┐       ┌──────────────────────────────┐       ┌──────────────────────────────┐
│       WHERE ARE WE?          │       │      CAN I EXECUTE WORK?     │       │      WHAT IS THE TRUTH?      │
│      PROJECT_ROADMAP.md      │       │ docs/authority/PLAN_STATUS.md│       │       docs/authority/*       │
├──────────────────────────────┤       ├──────────────────────────────┤       ├──────────────────────────────┤
│ Dynamic Project GPS:         │       │ Plan Execution Gate:         │       │ Frozen Domain Authorities:   │
│ • Current Milestone / Gate   │       │ • ACTIVE (Executable)        │       │ • Target Product Contract    │
│ • Completed Phase Record     │       │ • CLOSED (Frozen)            │       │ • Final Adjudication Memo    │
│ • Single Authorized Next Step│       │ • SUPERSEDED (Historical)    │       │ • Field & ID Inventories     │
└──────────────┬───────────────┘       └──────────────┬───────────────┘       └──────────────┬───────────────┘
               │                                      │                                      │
               └──────────────────────────────────────┼──────────────────────────────────────┘
                                                      │
                                                      ▼
                                       ┌──────────────────────────────┐
                                       │    VERIFICATION & EVIDENCE   │
                                       │     contract/ & evidence/    │
                                       ├──────────────────────────────┤
                                       │ • Machine YAML Contracts     │
                                       │ • Sealed Evidence Bundles    │
                                       │ • Zero-Trust Test Proofs     │
                                       └──────────────────────────────┘
```

### Agent Startup Traversal:
1. **Step 1:** Read [`AGENTS.md`](AGENTS.md) $\rightarrow$ Ingest behavioral rules, core financial invariants, and the Scope Shield.
2. **Step 2:** Read [`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md) $\rightarrow$ Identify current project milestone, active gate, and authorized next step.
3. **Step 3:** If the task involves an implementation plan, check [`docs/authority/PLAN_STATUS.md`](docs/authority/PLAN_STATUS.md) $\rightarrow$ Confirm plan status is `ACTIVE`.
4. **Step 4:** Consult relevant domain authority in [`docs/authority/`](docs/authority/) $\rightarrow$ Proceed with exact authorized scope.

---

## 5. Lightweight Hub Navigation Convention

To ensure deterministic traversal across the repository without cluttering ordinary files with rigid status metadata, a **3-Point Hub Convention** is established.

### Applicability:
Applies **ONLY to major Hub and Authority documents**:
* `AGENTS.md`
* `PROJECT_ROADMAP.md`
* `docs/authority/PLAN_STATUS.md`
* `docs/authority/Target Product Contract v0.6.md`
* `docs/authority/Final Independent Adjudication Memo.md`
* `docs/authority/G1-G8 Consolidated Architecture Summary.md`
* `DOCUMENT_INVENTORY.md`

*(Normal reports, historical records, test files, scripts, and temporary data do NOT use this convention).*

### The Hub Block Structure (No Mutable Status Field):
```markdown
---
### 🧭 NAVIGATION & CONTEXT
* **Why You Are Here:** [1-sentence explanation of this document's purpose]
* **What This Document Owns:** [Specific fact types for which this file is canonical]
* **Where To Go Next:**
  * For current project state $\rightarrow$ [PROJECT_ROADMAP.md](PROJECT_ROADMAP.md)
  * For plan execution status $\rightarrow$ [PLAN_STATUS.md](docs/authority/PLAN_STATUS.md)
  * For behavioral rules & router $\rightarrow$ [AGENTS.md](AGENTS.md)
---
```

---

## 6. Static / Dynamic / Historical / Evidence Classification

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. STATIC / FROZEN AUTHORITIES                                                         │
│    (Immutable domain truth — Never edited during normal development)                   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • docs/authority/Target Product Contract v0.6.md                                       │
│ • docs/authority/Final Independent Adjudication Memo.md                                │
│ • docs/authority/G1-G8 Consolidated Architecture Summary.md                            │
│ • docs/authority/account_field_authority_classification.md                             │
│ • docs/authority/ledger_identity_inventory.md                                          │
└────────────────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. DYNAMIC / MANAGED DOCUMENTS                                                         │
│    (Mutable operational state — Updated strictly upon milestone or plan transitions)   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • PROJECT_ROADMAP.md (Updated ONLY when a project gate opens or closes)                │
│ • docs/authority/PLAN_STATUS.md (Updated ONLY when a plan is created, started, closed) │
│ • AGENTS.md (Updated ONLY when operational rules or top-level routes change)           │
│ • CHANGELOG.md (Append-only record of completed release milestones)                    │
│ • DOCUMENT_INVENTORY.md (Updated ONLY when files are added or reclassified)            │
└────────────────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 3. CERTIFICATION & MACHINE EVIDENCE                                                    │
│    (Sealed, hash-bound, source/artifact-bound receipts — Machine evaluated)            │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • contract/*.yaml (Machine invariant and coverage contracts)                           │
│ • evidence/* (Sealed test execution bundles, probe outputs, and verification receipts) │
│ • scripts/*.py & scripts/*.sh (Zero-trust certification and verification harness)      │
└────────────────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 4. HISTORICAL & FORENSIC RECORDS                                                       │
│    (Path-locked artifacts — Preserved for evidence integrity; Not current backlog)     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ • PRODUCTION_INVARIANTS.md, ARCHITECTURE.md, PRODUCTION_CONTRACT_MATRIX.md             │
│ • EarthLink-Reseller_Wave1_Report_v3.md, EarthLink-Reseller_Wave1_Step1-3_Final.md     │
│ • docs/authority/EARTHLINK_V1_HANDOVER.md & APPENDIX                                   │
│ • docs/LESSONS_LEARNED/*                                                               │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Roadmap Role (`PROJECT_ROADMAP.md`)

### Responsibilities:
* **Current State Summary:** Summarizes overall milestone progress.
* **Milestone / Gate GPS:** Identifies which phase gate is currently active (e.g., Phase-04) and which are `CLOSED`.
* **Authorized Scope Gate:** Clearly specifies the single authorized next step.

### Boundaries (What it is NOT):
* It is **NOT** the root certification authority (`evidence/` holds certification truth).
* It is **NOT** an implementation plan (detailed task checklists belong in plan files).
* It is **NOT** an architectural debate forum (historical G2–G7 candidate essays are replaced with link pointers).

---

## 8. PLAN_STATUS Role (`docs/authority/PLAN_STATUS.md`)

### Conceptual Flow:
```text
PROJECT GATE (Roadmap authorizes work)
       ↓
CREATE IMPLEMENTATION PLAN (Markdown plan file)
       ↓
REGISTER IN PLAN_STATUS (Marked ACTIVE)
       ↓
EXECUTE & VERIFY WORK (G8 tools / tests)
       ↓
RECORD EVIDENCE & SATISFY CLOSURE CRITERIA
       ↓
CLOSE IN PLAN_STATUS (Marked CLOSED with evidence reference)
```

### Key Principles:
* `PLAN_STATUS.md` is the **sole execution authority** for implementation plans.
* **Plan file existence $\ne$ permission to execute.** An agent must never execute a plan unless its status in `PLAN_STATUS.md` is `ACTIVE`.
* `PLAN_STATUS.md` records execution status based on evidence; it does **not** produce certification truth.

---

## 9. Certification & Evidence Role

### 9.1 Evidentiary Standard
Documentation uses precise, verified evidentiary terms:
* **SEALED:** Preserved in immutable files representing completed verification runs.
* **HASH-BOUND:** Bound to specific source, contract, and manifest hashes.
* **SOURCE/ARTIFACT-BOUND:** Explicitly tied to the exact Git commit, source tree, and release APK binary.
* **INDEPENDENTLY VERIFIED:** Evaluated by zero-trust scripts without relying on narrative producer claims.

### 9.2 G8 Certification Boundary Rule
```text
PHASE CLOSURE / VERIFICATION STEP
               ↓
CERTIFICATION BOUNDARY CHECK
  ├─ Has any production source code, contract, or invariant changed?
  │
  ├─ If NO (Documentation / Information Architecture refinement only):
  │     → Retain existing valid certification evidence (commit 6d91dbd).
  │     → Do NOT rerun G8 certification unnecessarily.
  │
  └─ If YES (Production code or contracts modified):
        → Identify exact changed boundary.
        → Execute required verification and update closure bundle.
```

---

## 10. Simplification Decision Funnel

Future simplification work after V1 launch must follow an objective, requirement-driven decision funnel:

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        SIMPLIFICATION DECISION FUNNEL                                  │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 1. WHY DOES THIS MECHANISM EXIST?                                                      │
│    (Trace original problem from forensic baseline or defect history)                   │
│         ↓                                                                              │
│ 2. WHICH PRODUCT REQUIREMENT DOES IT SERVE?                                            │
│    (Map to Target Product Contract v0.6 or Core Safety Invariant)                      │
│         ↓                                                                              │
│ 3. IS THAT REQUIREMENT STILL CURRENT?                                                  │
│    • NO  → PROPOSE REMOVAL (e.g., Demo Mode)                                           │
│    • YES → PROCEED TO STEP 4                                                           │
│         ↓                                                                              │
│ 4. WHAT EXACT GUARANTEE DOES IT PROVIDE?                                               │
│    (Identify mathematical, data-integrity, or concurrency invariant)                   │
│         ↓                                                                              │
│ 5. CAN A SIMPLER MECHANISM PRESERVE THE SAME EXACT GUARANTEE?                          │
│    (e.g., SQLite hardware claim instead of 3 in-memory mutex layers)                   │
│         ↓                                                                              │
│ 6. WHAT ARE THE RISKS AND TRADE-OFFS?                                                  │
│    (Evaluate schema migration cost, test breakages, and regression risk)               │
│         ↓                                                                              │
│ 7. VERDICT:                                                                            │
│    [ KEEP / SIMPLIFY IN WAVE-2 / DEFER POST-LAUNCH / REMOVE / UNKNOWN ]                │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 11. G1–G8 Scope Protection

1. **Explicit Status Semantics:**
   * **G1–G8 are completed/frozen release-boundary work areas.**
   * **G8 has independent machine certification** (79 adversarial probes, 391 automated tests, sealed release APK).
2. **The Backlog Defense Rule:**
   * An open architectural idea in an older document (e.g., Staging DB, Identity Registry, WebForms scraping) is **NOT** an implementation backlog.
   * Those concepts were explicitly descoped or rejected by the Target Product Contract.
3. **The Freeze Invariant:**
   * Production architecture is **FROZEN**. It may only be reopened if an executable test proves that a product requirement or safety invariant cannot be satisfied within the frozen architecture.

---

## 12. Illustrative Phase-04 Execution Shape (Conceptual Only)

*Note: This is an illustrative shape outlining the conceptual steps. An approved, executable Implementation Plan will be created separately following approval of this Master Design.*

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                     ILLUSTRATIVE PHASE-04 EXECUTION SHAPE                              │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ STEP 1: FRONT DOOR & ROUTER REFINEMENT                                                 │
│ • Update AGENTS.md: Embed stable orientation, Scope Shield, and Navigation Router.     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ STEP 2: DYNAMIC GPS & INVENTORY ALIGNMENT                                              │
│ • Streamline PROJECT_ROADMAP.md: Focus on current milestone & active gate; add links.  │
│ • Update DOCUMENT_INVENTORY.md: Align file classifications with Master Design.         │
│ • Reconcile duplicate plan references in docs/authority/PLAN_STATUS.md.                │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ STEP 3: V1 RELEASE CLOSURE & SEALING                                                   │
│ • Perform Certification Boundary Check (Confirm 0 production source changes).          │
│ • Mark Phase-04 CLOSED in PROJECT_ROADMAP.md.                                          │
│ • Tag repository state as EarthLink Reseller V1.0.0-RELEASE.                           │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 13. Risks & Trade-Offs

| Decision / Choice | Advantages | Risks | Mitigations |
|:---|:---|:---|:---|
| **Using `AGENTS.md` as Front Door** | Zero new files; immediate startup orientation; automatically ingested by agent tooling. | `AGENTS.md` is an instruction file; human developers might look for `README.md`. | Standard `README.md` includes a clean 3-line pointer to `AGENTS.md` and `PROJECT_ROADMAP.md`. |
| **Preserving Path-Locked Files Untouched** | 100% guarantees existing hash-bound evidence manifests and verification scripts never break. | Historical files remain in root and could tempt untrained agents. | Front Door explicitly lists path-locked filenames under the Scope Shield / Quarantine rule. |
| **No Automatic G8 Recertification** | Saves execution time; respects existing certified baseline (`6d91dbd`). | Risk of undocumented drift if source code was altered. | Git diff confirms zero production source changes during documentation phases. |
| **Lightweight Hub Conventions** | Eliminates boilerplate on ordinary reports while providing clear traversal on major nodes. | Inconsistent header styles across minor files. | Minor files are terminal leaf nodes; agents navigate to them via hub links. |

---

## 14. Final Recommended Design

> ### **SUMMARY VERDICT: APPROVE MASTER DESIGN v1.2**
>
> 1. **Front Door:** `AGENTS.md` is the stable operational entry and Navigation Router (Orientation only; defers business truth to Product Contract).
> 2. **Owner $\ne$ Router:** Strict separation between information ownership and navigation routing.
> 3. **Dynamic GPS:** `PROJECT_ROADMAP.md` is the dynamic milestone summary (Current State & Gate).
> 4. **Plan Authority:** `docs/authority/PLAN_STATUS.md` is the sole execution gatekeeper for plans.
> 5. **Domain Truth:** `docs/authority/*` holds frozen product contracts and architectural rulings.
> 6. **Evidence Truth:** `evidence/` holds sealed, hash-bound verification receipts.
> 7. **Zero Churn:** Zero new root files, zero touch on path-locked historical files, and zero automatic G8 recertification.

---

```
============================================================
MASTER INFORMATION ARCHITECTURE DESIGN v1.2 COMPLETE.
APPROVED CANDIDATE BASELINE ESTABLISHED.
AWAITING INSTRUCTION TO CREATE IMPLEMENTATION PLAN.
============================================================
```