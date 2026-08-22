# AGENTS.md
## The Canonical Front Door, Behavioral Authority, and Top-Level Navigation Router

---
### 🧭 NAVIGATION & CONTEXT
* **Why You Are Here:** This is the project's front door and operational rulebook. Every session must start here.
* **What This Document Owns:** Mandatory behavioral rules, operational invariants, the canonical navigation router, and the scope shield.
* **Where To Go Next:**
  * For current project state $\rightarrow$ [PROJECT_ROADMAP.md](PROJECT_ROADMAP.md)
  * For plan execution status $\rightarrow$ [PLAN_STATUS.md](docs/authority/PLAN_STATUS.md)
---

## 1. Stable Orientation
**EarthLink Reseller V1** is an offline-capable Android account book whose primary purpose is to protect subscriber financial history. 
This document provides behavioral orientation, but **defers all business truth to the Product Contract**.

## 2. Canonical Navigation Router (The "Owner ≠ Router" Rule)
A router points to truth but does not become the owner of mutable state. Do not duplicate facts.

| Fact / Question Type | Canonical Owner (Single Truth) |
|:---|:---|
| **Agent Operational Rules & Invariants** | [`AGENTS.md`](AGENTS.md) |
| **Product Purpose & Business Rules** | [`docs/authority/Target Product Contract v0.6.md`](docs/authority/Target%20Product%20Contract%20v0.6.md) |
| **Architectural Rulings & Boundaries** | [`docs/authority/Final Independent Adjudication Memo.md`](docs/authority/Final%20Independent%20Adjudication%20Memo.md) |
| **Current Project Milestone & Active Gate** | [`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md) |
| **Implementation Plan Execution Status** | [`docs/authority/PLAN_STATUS.md`](docs/authority/PLAN_STATUS.md) |
| **Database Field Authority (Room vs Cloud)** | [`docs/authority/account_field_authority_classification.md`](docs/authority/account_field_authority_classification.md) |
| **Ledger Creation Paths (10 Paths)** | [`docs/authority/ledger_identity_inventory.md`](docs/authority/ledger_identity_inventory.md) |
| **Machine Invariant Contracts** | [`contract/invariant_contract.yaml`](contract/invariant_contract.yaml) |
| **Certification Truth & Machine Proofs** | [`evidence/`](evidence/) |
| **Historical Commit & Change Ledger** | [`CHANGELOG.md`](CHANGELOG.md) |

## 3. Scope Shield & Quarantine Rules
* **G1–G8 Work Areas:** G1–G8 are completed/frozen release-boundary work areas. G8 has independent machine certification. Do NOT treat closed G-areas as an active backlog.
* **Plan File Existence ≠ Permission:** The existence of a historical plan file does NOT grant permission to execute it. Execution strictly requires an `ACTIVE` state in `PLAN_STATUS.md`.
* **Quarantine Rule for Path-Locked Files:** Root historical files (such as `PRODUCTION_INVARIANTS.md`, `ARCHITECTURE.md`, `PRODUCTION_CONTRACT_MATRIX.md`) are frozen historical records preserved strictly for cryptographic evidence verification. They must **never** be edited and must not be used for current planning.

---

## 4. Mandatory Non-Negotiable Invariants

### 1. No Deleting, Weakening, or Skipping Tests
- NEVER delete or skip tests.
- NEVER modify or weaken assertions/expected results in existing certification tests (`FinalTestMatrixCertificationTest`, `ProductionCertificationPipelineTest`, `ProductionExecutableInvariantsTest`, `DeepCrossLayerInvariantsTest`, and existing golden/regression tests).
- A failing test is proof of a production defect until the production code is proven otherwise. Never make a test pass by changing the test.

### 2. One State, One Authority
Every synchronized business state must have:
- one authoritative meaning;
- one version domain;
- one mutation policy;
- one synchronization path.

Do not introduce a second mechanism that independently writes the same state to Firebase.

### 3. Remote Version Semantics
Server version MUST be represented explicitly and compared only against the same semantic version domain.
Do not use `createdAt`, `occurredAt`, device clock, or local business timestamps as substitutes for server version.

### 4. Mutation Channel Rule
Any code capable of mutating synchronized application state or performing destructive database operations MUST pass through the canonical synchronization / mutation architecture defined in the frozen architecture bundle.

### 5. Snapshot & History Rule
Historical data, Snapshot state, and Runtime state are different concepts:
- Historical records (e.g., uTower import archives) must remain immutable.
- Snapshot state is the authoritative starting baseline.
- Runtime calculations must not re-apply historical snapshot records.

### 6. Fail-Closed Security
- **In Release Build:** missing or invalid production signing credentials => build failure. No fallback to debug signing, placeholder keys, or unsigned artifacts.
- **Android Keystore Failure:** fail closed immediately.
- **Existing Encrypted DB with Unrecoverable Key:** stop safely; never generate a replacement key over an existing database.

### 7. Certification Freeze
Existing certification tests are immutable during implementation and certification runs.

---

## API Documentation Reference

When working with network requests and API integrations, consult:
1. `/docs/earthlink_reseller_app_api_documentation_v0_7_0.md`: Primary API specification.
2. `/docs/earthlink_app_api_poc_v0_6_48.py`: Python reference implementation for payload unwrapping and edge cases.

---

## Mandatory Workflow

For every development session:

1. Follow the **ACTIVE IMPLEMENTATION ENTRY POINT** above.
2. Implement ONLY the minimal required scope as explicitly authorized through a dedicated candidate scope assessment mapped by `PROJECT_ROADMAP.md`.
3. Do not modify unrelated files.
4. Verify the implementation with tests and verified runners.
5. Update `CHANGELOG.md` and active phase tracking/roadmap milestones.
6. Stop and wait for user approval.

---

## Permanent Verification & Compliance Rules

1. **Requirement-by-Requirement Compliance Reviews:** Every phase or task closure MUST prove every single blocking requirement in `contract/phase_requirements.yaml` through explicit machine evidence.
2. **No Phase Closure from Narrative Reports:** Narrative claims are strictly ignored. Only machine-verified compliance matrix status (`ALL BLOCKING ROWS PASS`) authorizes closure.
3. **No NO-SOURCE Pass:** Any test task or compilation step returning `NO-SOURCE` MUST fail closed with exit code 2.
4. **No Unbounded Verification Commands:** All verification commands MUST execute through `run_verified_command.py` enforcing strict timeouts, process-tree termination, and heartbeat emission.
5. **No Deleting, Skipping, or Weakening Tests:** Tests MUST NEVER be deleted, skipped, or weakened.

---

## Implementation Plan Lifecycle

Before executing any implementation plan:

1. Read `docs/authority/PLAN_STATUS.md`.
2. Locate the exact plan path.
3. Execute the plan only if its status is `ACTIVE`.
4. Do not execute plans marked `CLOSED`, `SUPERSEDED`, `NOT-YET-EXECUTED`, or `STATUS-UNKNOWN`.

### On Plan Completion

When the implementation plan's authorized Definition of Done / closure criteria are actually satisfied:

1. Update the exact plan row in `docs/authority/PLAN_STATUS.md`: `ACTIVE` → `CLOSED`.
2. Add concise evidence to the plan-status note, such as the completion commit, verification result, or certification identifier.
3. Update `CHANGELOG.md` only when the completed work represents a meaningful repository milestone and is not already recorded.
4. Do not change the status of any other plan.
5. Do not resolve `STATUS-UNKNOWN` plans as part of completing another plan.
6. Do not rename, move, delete, or rewrite historical plans merely because the plan has closed.

### Completion Rule

A plan is CLOSED only when its actual authorized closure criteria are satisfied by repository evidence.

Do not mark a plan CLOSED because:
- only part of the plan was implemented;
- some tests passed;
- a subtask appears complete;
- the agent believes the work is finished.

### Failure / Blocked Rule

If the plan cannot be completed:

- keep the plan `ACTIVE` unless repository authority explicitly requires another status;
- record the blocking condition in the final report;
- do not silently mark the plan `CLOSED`.

### Status Integrity

`PLAN_STATUS.md` is the tracking authority for implementation-plan execution status only. It does not override stronger product, architecture, contract, or historical authority.

If the plan, `PLAN_STATUS.md`, and repository evidence disagree:

STOP and report the conflict. Do not guess.
