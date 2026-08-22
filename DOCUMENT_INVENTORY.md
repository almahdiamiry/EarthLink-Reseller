# EARTHLINK RESELLER V1 — MASTER DOCUMENT INVENTORY

**Last Updated:** Post-Wave 1 Closure
**Status:** Classification Draft (Cleanup Execution: PAUSED / PENDING SAFETY CHECK)
**Purpose:** Classify all non-source repository files (documentation, plans, scripts, contracts, evidence) to establish a clean Information Architecture and prevent Agent scope confusion.

---

## 1. Master Document Inventory

| File / Directory | Current Role | Authority Level | Status | Action | Reason |
| --- | --- | --- | --- | --- | --- |
| **`PROJECT_ROADMAP.md`** | Master Navigation / GPS | Navigation Synthesis | Current | **KEEP** | Single source of truth for repository position and authorized next steps. |
| **`AGENTS.md`** | Operational Rules | Behavioral Guardrails | Current | **KEEP** | Mandatory operational rules, behavior guidelines, and process guardrails for AI Agents. |
| **`DOCUMENT_INVENTORY.md`** | Document Classification | Navigation / Governance | Current | **KEEP** | Classification map indicating which documents matter and their active status. |
| **`AI_DEVELOPMENT_GUIDE.md`** | Dev Guidelines | Governance / Reference | Current | **KEEP** | Engineering patterns (Kotlin, Jetpack, Clean Architecture). |
| **`docs/authority/Target Product Contract v0.6.md`** | Product Constraints | Frozen Authority | Current | **KEEP** | The ultimate source of business/product truth. |
| **`docs/authority/Final Independent Adjudication Memo.md`** | Architecture Bound | Frozen Authority | Current | **KEEP** | The final ruling on technical conflicts and boundaries. |
| **`docs/authority/G1-G8 Consolidated Architecture Summary.md`** | Eng Interpretation | Frozen Authority | Current | **KEEP** | Architectural constraints and dependency map (Not a backlog or scope generator). |
| **`docs/authority/EARTHLINK_V1_HANDOVER.md`** | Transition Context | Subordinate Authority | Current | **KEEP** | Required for understanding the transition to the new baseline. |
| **`docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`** | Implementation Context | Subordinate Authority | Current | **KEEP** | Granular transition context. |
| **`EarthLink-Reseller_Wave1_Report_v3.md`** | Wave 1 Rationale | Scope Authority | Current Ref | **KEEP** | Explains Owner Decisions and Simplifications made in Wave 1. |
| **`EarthLink-Reseller_Wave1_Step1-3_Final.md`** | Wave 1 Scope | Scope Authority | Current Ref | **KEEP** | Detailed Step 1-3 final spec. |
| **`EARTHLINK_RESELLER_STEP3_ADVERSARIAL_CERTIFICATION_FINAL.md`** | Certification Record | Evidence | Current Ref | **KEEP** | Final adversarial certification output. |
| **`EarthLink_Reseller_Step3_Adversarial_Certification_Basis_v6.md`** | Cert Contract | Contract | Current Ref | **KEEP** | Definitions for the Step 3 certification bounds. |
| **`docs/earthlink_reseller_app_api_documentation_v0_7_0.md`** | API Specs | Technical Reference | Current | **KEEP** | Primary API interaction contract. |
| **`docs/earthlink_app_api_poc_v0_6_48.py`** | API POC | Technical Reference | Current | **KEEP** | Edge cases and payload unwrapping reference. |
| **`docs/LESSONS_LEARNED/*`** | Governance History | Governance Context | Historical | **KEEP HISTORICAL** | Crucial guardrails preventing repeated mistakes. |
| **`contract/*`** (YAML/JSON) | Machine Contracts | Validation Rules | Current & Hist | **KEEP** | Contains current machine contracts + historical certification contracts. |
| **`evidence/*`** | Sealed Evidence | Provenance | Current & Hist | **KEEP** | Existing source-bound evidence records must not be deleted or rewritten. New evidence may be added through the established evidence workflow. |
| **`scripts/*`** | Verification Tooling | CI/Execution | Current | **KEEP** | Standard verified command runners and matrix generators. |
| **`docs/authority/EARTHLINK_RESELLER_V1_FINAL_APPROVED_FINDINGS_MATRIX.md`** | Old Findings | Context | Historical | **KEEP HISTORICAL** | Superseded by actual closure, but useful reference. |
| **`ARCHITECTURE.md`** | Generic Arch | Superseded | Historical | **KEEP HISTORICAL** | Path-locked: referenced in historical certification scope and manifests. Must remain in place to preserve closure evidence path integrity. |
| **`PRODUCTION_CONTRACT_MATRIX.md`** | Old Matrix | Superseded | Historical | **KEEP HISTORICAL** | Path-locked: referenced in historical evidence and closure bundles. Must remain in place. |
| **`PRODUCTION_INVARIANTS.md`** | Invariant Source | Verification Dependency | Current / Hist | **KEEP (VERIFICATION DEPENDENCY)** | **BLOCKED FROM ARCHIVING:** Actively verified and hashed by `scripts/verify_closure_evidence.py` and referenced across immutable evidence bundles. |
| **`ISSUE_LOG.md`** | Old Issue Tracker | Superseded | Historical | **KEEP HISTORICAL** | Path-locked: referenced in historical changelogs and provenance. |
| **`g8_adversarial_checks_FINAL.yaml`** (root) | Duplicate Contract | Duplicate | Obsolete | **SAFE TO DELETE** | Verified zero repo references outside inventory. Root duplicate of `contract/g8_adversarial_checks_FINAL.yaml`. |
| **`fix_braces.py`, `fix_repo.py`, `patch_mock.py`, etc.** (root python scripts) | Forensic Utilities | Ad-hoc Tooling | Obsolete | **SAFE TO DELETE** | Verified zero repo references in code, scripts, or workflows. One-off ad-hoc repair scripts. |

---

## 2. Cleanup Safety Check Results & Protocol

> **CRITICAL RULE:** Cleanup execution is currently **PAUSED**. While the safety check empirical audit is complete, actual file removal or movement requires explicit future authorization.
>
> **HISTORICAL DECISION & KNOWLEDGE PRESERVATION RULE:**
> Before deleting or archiving any document that contains a historical decision, requirement, rationale, or failure analysis, verify that its unique knowledge is preserved in either:
> - current authority;
> - current roadmap/scope rationale;
> - lessons learned;
> - historical/reference record.

### A. DELETE CANDIDATES SAFETY AUDIT RESULTS
- `g8_adversarial_checks_FINAL.yaml` *(Root duplicate)* -> **SAFE TO DELETE (Zero references found)**
- `fix_braces.py`, `fix_manual_test.py`, `fix_mocks.py`, `fix_repo.py`, `fix_repo_again.py`, `fix_test.py`, `patch_mock.py`, `revert_repo.py` -> **SAFE TO DELETE (Zero references in scripts, workflows, or code)**

### B. ARCHIVE CANDIDATES SAFETY AUDIT RESULTS (RECLASSIFIED)
- `PRODUCTION_INVARIANTS.md` -> **BLOCKED — REFERENCE FOUND / VERIFICATION DEPENDENCY**. It is actively hashed and checked by Python verification scripts (`verify_closure_evidence.py`, `execute_phase2_closure_gate.py`) and referenced in sealed evidence bundles (`closure_bundle.json`). **Must be kept in root.**
- `ARCHITECTURE.md`, `PRODUCTION_CONTRACT_MATRIX.md`, `ISSUE_LOG.md` -> **KEEP HISTORICAL (PATH-LOCKED)**. Moving these files to `docs/archive/` would break path references in historical evidence manifests and closure reports. They are retained in place to guarantee 100% provenance integrity.

### C. KEEP HISTORICAL (Retain in current location)
- `docs/LESSONS_LEARNED/LL-VERIFICATION-COMPLIANCE-COLLAPSE.md`
- `docs/LESSONS_LEARNED/LL-VERIFICATION-GOVERNANCE.md`
- `docs/authority/EARTHLINK_RESELLER_V1_FINAL_APPROVED_FINDINGS_MATRIX.md`
- `evidence/*` *(Immutable provenance. Never touch or delete)*

### D. KEEP ACTIVE & REFERENCE
- All `docs/authority/` core files
- `PROJECT_ROADMAP.md`
- `AGENTS.md`
- `DOCUMENT_INVENTORY.md`
- `AI_DEVELOPMENT_GUIDE.md`
- All Wave 1 Final Reports & Certification Records in root

---

## 3. Information Architecture & Navigation Flow

When an Agent enters the repository, they MUST navigate using this strict Information Architecture:

```text
1. BEHAVIOR & RULES
   AGENTS.md
   (Mandatory operational rules, guardrails, and forbidden actions - HOW TO ACT)

2. POSITION & NAVIGATION (The GPS)
   PROJECT_ROADMAP.md
   (Single source of truth for current state and next authorized gates - WHERE WE ARE)

3. DOCUMENT CLASSIFICATION
   DOCUMENT_INVENTORY.md
   (Identifies active vs historical documents and cleanup status - WHICH DOCUMENTS MATTER)

4. FROZEN AUTHORITY
   docs/authority/Target Product Contract v0.6.md
   docs/authority/Final Independent Adjudication Memo.md
   docs/authority/G1-G8 Consolidated Architecture Summary.md
   (Defines business/product rules and technical boundaries - WHAT IS ALLOWED/REQUIRED)

5. CURRENT SCOPE & RATIONALE
   EarthLink-Reseller_Wave1_Report_v3.md (and current Candidate Scope Assessments)
   (Explains why choices were made and defines task scope - WHAT TO DO NOW)

6. EVIDENCE & VERIFICATION
   contract/ & evidence/
   (Machine contracts and source-bound evidence records - WHAT IS PROVEN)
```

**WHAT THE AGENT MUST IGNORE (Unless Explicitly Requested):**
- Do NOT read `ARCHITECTURE.md` or old `PRODUCTION_*.md` matrices for current scope.
- Do NOT read `docs/LESSONS_LEARNED/` for implementation tasks (only for historical warnings).
- Do NOT read `evidence/` directories to figure out what to build next (only use them to verify past closure).
