# PHASE-04 IMPLEMENTATION PLAN (v1.2 — FINAL REVISION)
## Based on Master Information Architecture Design v1.2

---

## 1. Executive Objective

Transition the EarthLink Reseller V1 repository to the approved **Master Information Architecture Design v1.2** with the smallest possible documentation-only changeset.

This work establishes:
1. **One Obvious Entry Point:** [`AGENTS.md`](AGENTS.md) as the stable Front Door and Top-Level Navigation Router.
2. **One Authoritative Home per Fact:** Zero duplicated mutable states across files; strict adherence to the **"Owner $\ne$ Router"** principle.
3. **Linked Navigation (Obsidian-Style Topology):** Hub documents connect directly to authoritative domain sources using repository-relative Markdown links.
4. **Clean Categorization:** Explicit separation of Frozen Authority vs. Controlled Authority vs. Dynamic State vs. Historical Forensics vs. Certification Evidence.
5. **G1–G8 Scope Shield:** Explicit protection against treating closed G-areas or historical plan files as an active backlog.
6. **Zero Weakening of Evidence:** Zero modifications to path-locked historical files or sealed evidence bundles.

---

## 2. Scope & Boundaries

### In-Scope (Documentation & Governance Surfaces Only):
* [`AGENTS.md`](AGENTS.md): Refine into Front Door Router, embed stable orientation, Scope Shield, and invariant rules.
* [`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md): Streamline into Dynamic GPS (Current Milestone, Active Gate, Authorized Next Step), add Hub Navigation block, preserve historical traceability via structured reference sections, and use repository-relative links.
* [`docs/authority/PLAN_STATUS.md`](docs/authority/PLAN_STATUS.md): Execute governed plan state transitions (`ACTIVE` on authorization $\rightarrow$ `CLOSED` on verified completion).
* [`DOCUMENT_INVENTORY.md`](DOCUMENT_INVENTORY.md): Align document classifications and topology mapping with Master Design v1.2.
* Minimal hub navigation blocks on major authority documents where traversal is materially improved.

### Non-Goals / Forbidden Surfaces (Strictly Out of Scope):
* **`app/src/main/`:** Zero changes to production code, Room entities, DAOs, migrations, ViewModels, or Network layer.
* **`app/src/test/`:** Zero changes to unit, integration, or Robolectric test suites.
* **`scripts/`:** Strictly out of scope. Zero modifications to Python or Bash verification tooling.
* **`contract/`:** Zero modifications to machine invariant contracts (`contract/*.yaml`).
* **`evidence/`:** Zero modifications to sealed certification evidence, test reports, or JSON bundles.
* **Path-Locked Historical Records:** Zero renaming, moving, or editing of `PRODUCTION_INVARIANTS.md`, `ARCHITECTURE.md`, or `PRODUCTION_CONTRACT_MATRIX.md`.

---

## 3. Plan Authorization & Governed Lifecycle Transitions

Modifications to `docs/authority/PLAN_STATUS.md` are **governed plan state transitions**, not casual text edits:

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        GOVERNED PLAN STATE TRANSITIONS                                 │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 1. NOT REGISTERED                                                                      │
│    Plan exists only as a design proposal. Zero execution authorized.                  │
│         ↓                                                                              │
│    [TRIGGER: Explicit Human Authorization to proceed]                                  │
│         ↓                                                                              │
│ 2. ACTIVE                                                                              │
│    Plan is recorded as ACTIVE in docs/authority/PLAN_STATUS.md.                         │
│    Execution begins on Gate 1.                                                         │
│         ↓                                                                              │
│    [TRIGGER: Verified completion of all Gate 1–3 closure criteria]                     │
│         ↓                                                                              │
│ 3. CLOSED                                                                              │
│    Plan is recorded as CLOSED in docs/authority/PLAN_STATUS.md with evidence note.     │
│    Phase-04 is formally closed in PROJECT_ROADMAP.md.                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

> **CRITICAL RULE:** The agent must NEVER self-authorize execution. Transition from `NOT REGISTERED` to `ACTIVE` occurs ONLY upon explicit human authorization.

---

## 4. Execution Gates (Minimal 3-Gate Shape)

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                          PHASE-04 EXECUTION GATES                                      │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ GATE 1: FRONT DOOR & OPERATIONAL ROUTER (AGENTS.md)                                    │
│ • Embed concise stable orientation (defers business truth to Product Contract).        │
│ • Insert Canonical Navigation Router table (Owner ≠ Router).                           │
│ • Codify Scope Shield (G1–G8 closed; plan existence ≠ permission to execute).         │
│ • Codify Path-Locked File Quarantine rule.                                             │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ GATE 2: DYNAMIC GPS & GOVERNANCE ALIGNMENT (ROADMAP, INVENTORY, HUBS)                  │
│ • Streamline PROJECT_ROADMAP.md into Dynamic GPS summary.                              │
│ • Preserve historical milestone traceability via dedicated historical summary/links.   │
│ • Update DOCUMENT_INVENTORY.md to reflect Master Design v1.2 topology.                 │
│ • Add lightweight Hub Navigation blocks to key authority files.                        │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ GATE 3: CERTIFICATION BOUNDARY REVIEW & PHASE-04 CLOSURE                               │
│ • Perform Comprehensive Certification Boundary Check across physical paths.           │
│ • Validate all repository-relative Markdown links and navigation traversal.            │
│ • Verify all Phase-04 closure criteria are satisfied by machine proof.                 │
│ • Transition plan in PLAN_STATUS.md to CLOSED; record PHASE-04 CLOSED in ROADMAP.      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Detailed Gate Change Intent & File Specifications

### Gate 1: Front Door & Operational Router (`AGENTS.md`)
* **File Target:** [`AGENTS.md`](AGENTS.md)
* **Lifecycle:** `STABLE / CONTROLLED OPERATIONAL AUTHORITY`
* **Change Intent:**
  1. **Stable Orientation:** Add a concise summary defining EarthLink Reseller V1 as an offline-capable Android account book whose primary purpose is to protect subscriber financial history. State explicitly that domain business truth lives in the Target Product Contract.
  2. **Canonical Navigation Router:** Embed the single-source-of-truth router table mapping every fact type to its authoritative owner.
  3. **The "Owner $\ne$ Router" Rule:** Explicitly codify that routers point to truth but do not become owners of mutable state.
  4. **The Scope Shield:** Codify that G1–G8 are completed/frozen release-boundary work areas (G8 independently certified), and plan file existence $\ne$ permission to execute.
  5. **Quarantine Rule for Path-Locked Files:** State that root files like `ARCHITECTURE.md` and `PRODUCTION_INVARIANTS.md` are frozen historical records preserved strictly for cryptographic evidence verification and must not be used for current planning.

### Gate 2: Dynamic GPS & Governance Alignment
* **File Targets:**
  * [`PROJECT_ROADMAP.md`](PROJECT_ROADMAP.md) (`DYNAMIC / MANAGED`)
  * [`DOCUMENT_INVENTORY.md`](DOCUMENT_INVENTORY.md) (`DYNAMIC / MANAGED`)
  * Hub blocks on [`docs/authority/Target Product Contract v0.6.md`](docs/authority/Target%20Product%20Contract%20v0.6.md), [`docs/authority/Final Independent Adjudication Memo.md`](docs/authority/Final%20Independent%20Adjudication%20Memo.md), and [`docs/authority/G1-G8 Consolidated Architecture Summary.md`](docs/authority/G1-G8%20Consolidated%20Architecture%20Summary.md).
* **Change Intent:**
  1. **Update `PROJECT_ROADMAP.md`:**
     - Position it as the **Dynamic Project GPS** owning Current Milestone, Active Gate, and Authorized Next Step.
     - **Preserve Historical Traceability:** Retain a structured historical milestone record for completed phases (Phase-00 through Phase-03) with repository-relative links to `G1-G8 Consolidated Architecture Summary.md` and `EarthLink-Reseller_Wave1_Report_v3.md`.
     - Insert the 3-point Hub Navigation block (`WHY YOU ARE HERE`, `WHAT THIS OWNS`, `WHERE TO GO NEXT`).
     - Convert all internal references to repository-relative Markdown links.
  2. **Update `DOCUMENT_INVENTORY.md`:**
     - Align file status table with the 4-tier model (Static Authorities, Dynamic Managed, Certification Evidence, Historical Forensics).
  3. **Add Hub Navigation Blocks:** Insert the lightweight 3-field navigation block into major hub files.

### Gate 3: Comprehensive Certification Boundary Review & Phase-04 Closure
* **File Target:** Verification & Milestone Recording
* **Change Intent:**
  1. **Comprehensive Boundary Check:** Verify zero modifications across physical repository paths.
  2. **Link Parity Check:** Verify all Markdown links resolve to existing files.
  3. **Governed Closure:** Transition `docs/authority/PLAN_STATUS.md` to `CLOSED` with evidence reference. Update `PROJECT_ROADMAP.md` to record Phase-04 `CLOSED`.

---

## 6. Comprehensive Certification Boundary Check (Physical Paths)

The boundary check confirms that the implementation was strictly documentation-only and did not compromise any verified software or evidence:

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                   COMPREHENSIVE CERTIFICATION BOUNDARY REVIEW                          │
├────────────────────────────────┬───────────────────────┬───────────────────────────────┤
│ PHYSICAL REPOSITORY SURFACE    │ ALLOWED CHANGE        │ VERIFICATION COMMAND          │
├────────────────────────────────┼───────────────────────┼───────────────────────────────┤
│ 1. app/src/main/ (Code/Schema) │ ZERO (0 lines)        │ git diff --stat HEAD -- app/src/main/ │
├────────────────────────────────┼───────────────────────┼───────────────────────────────┤
│ 2. app/src/test/ (All Tests)   │ ZERO (0 lines)        │ git diff --stat HEAD -- app/src/test/ │
├────────────────────────────────┼───────────────────────┼───────────────────────────────┤
│ 3. contract/ (Machine YAML)    │ ZERO (0 lines)        │ git diff --stat HEAD -- contract/ │
├────────────────────────────────┼───────────────────────┼───────────────────────────────┤
│ 4. scripts/ (Tooling)          │ ZERO (0 lines)        │ git diff --stat HEAD -- scripts/ │
├────────────────────────────────┼───────────────────────┼───────────────────────────────┤
│ 5. evidence/ (Sealed Proofs)   │ ZERO (0 lines)        │ git diff --stat HEAD -- evidence/ │
├────────────────────────────────┼───────────────────────┼───────────────────────────────┤
│ 6. Path-Locked Root Records    │ ZERO (0 lines)        │ git status --porcelain PRODUCTION_INVARIANTS.md ARCHITECTURE.md │
└────────────────────────────────┴───────────────────────┴───────────────────────────────┘
```

### Boundary Logic:
$$\text{Documentation-only change} + \text{Zero boundary inputs changed} \implies \text{Existing G8 evidence remains applicable (commit 6d91dbd)}$$

* If all 6 physical path checks return **ZERO changes**:
  $\rightarrow$ **NO RECERTIFICATION REQUIRED.** Existing certification evidence remains 100% applicable.
* If any check returns **non-zero**:
  $\rightarrow$ **HALT IMMEDIATELY.** Report the unexpected modification.

---

## 7. Canonical Plan Path

* **Canonical Plan Path:** `PHASE_04_INFORMATION_ARCHITECTURE_IMPLEMENTATION_PLAN.md` (Repository Root)
* **Registration in `PLAN_STATUS.md` upon authorization:**
  ```markdown
  | PHASE_04_INFORMATION_ARCHITECTURE_IMPLEMENTATION_PLAN.md | ACTIVE | Approved execution of Master Information Architecture Design v1.2 |
  ```

---

## 8. Definition of Done & Phase-04 Closure Criteria

Phase-04 is formally complete and eligible for `CLOSED` status only when all of the following machine and review criteria are satisfied:

1. [ ] **Explicit Human Authorization Received:** Human owner explicitly authorized execution.
2. [ ] **Plan Registered as ACTIVE:** `PLAN_STATUS.md` recorded the plan as `ACTIVE` prior to execution.
3. [ ] **Front Door Router Active:** `AGENTS.md` contains the stable orientation, Navigation Router table, Scope Shield, and behavioral rules.
4. [ ] **Roadmap Streamlined with History Preserved:** `PROJECT_ROADMAP.md` is a clean dynamic GPS with preserved historical traceability and 100% repository-relative links.
5. [ ] **Inventory Aligned:** `DOCUMENT_INVENTORY.md` reflects the 4-tier taxonomy of Master Design v1.2.
6. [ ] **Comprehensive Boundary Check PASS:** Zero changes across `app/src/main/`, `app/src/test/`, `contract/`, `scripts/`, `evidence/`, and path-locked root files.
7. [ ] **Link Parity PASS:** All Markdown navigation links across modified hub documents resolve to existing files.
8. [ ] **Plan Formally CLOSED:** `docs/authority/PLAN_STATUS.md` records this plan as `CLOSED` with evidence reference.
9. [ ] **Roadmap Formally CLOSED:** `PROJECT_ROADMAP.md` records Phase-04 as `CLOSED`.

---

```
============================================================
FINAL REVISED PLAN v1.2 READY.
STRICT NO-IMPLEMENTATION RULE OBSERVED.
AWAITING EXPLICIT HUMAN AUTHORIZATION TO PROCEED.
============================================================
```
