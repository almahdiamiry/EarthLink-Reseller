# EARTHLINK RESELLER V1 — MASTER DEVELOPMENT ROADMAP TO RELEASE

## 1. Product Objective
EarthLink Reseller is a local-first, offline-capable Android application designed to allow authorized resellers to manage local ledger balances, issue Earthlink deposits, and synchronize financial operations durably, even over highly constrained or unavailable networks. The core objective is secure, atomic, and idempotent financial dispatch.

## 2. Simplification Principle
**Simplification is a first-class product objective.** We are not trying to finish every historical architecture item. The goal is to finish the smallest release that is justified by the business need, the current product contract, explicit Owner Decisions, required safety, and release evidence. We use the minimum sufficient architecture and avoid unnecessary future complexity.

## 3. Current State

**Last Updated:** 2026-08-22

### Completed
- Step 1 — CLOSED
- Step 2 — CLOSED
- Step 3 — CLOSED
- Step 3 Adversarial Certification — GO
- G1 / Wave 1 Closure — CLOSED
- C06 / C12 Evidence Closure — CLOSED

### Current Gate
- Post-Wave-1 Scope Assessment — NOT STARTED

### Next Authorized Gate
- TBD by current authority after Scope Assessment

**Production architecture is FROZEN unless a concrete current contradiction is established.** Do not reopen completed work.

## 4. Authority-Derived Disposition (The "Not a Backlog" Rule)
The roadmap does **NOT** assume that every open G-area is release-required. No G-area is release-required merely because its architecture remains open.

Each candidate must be classified as:
- `RELEASE-BLOCKING`
- `RELEASE-SUPPORTING`
- `CONDITIONAL`
- `POST-LAUNCH`
- `DEFERRED`
- `NOT REQUIRED`

Before any implementation begins, the Agent must prove from current authoritative release/scope material that the item is required for launch (e.g., prevents silent loss, deletion, duplication, or corruption of account/ledger history).

Possible authority sources include:
- Target Product Contract
- Explicit current Owner Decision
- Applicable certification/release contract
- Explicit release gate

## 5. Disposition of Specific Candidates & Gates

### A. Business Data Integrity (Product Contract Mandates)
- **ISP-Side Deletion Must Not Erase Local History:** `RELEASE-BLOCKING`
  - *Authority:* Target Product Contract.
  - *Context:* This is a critical business-data integrity rule, not just a "G6 semantic modernization." If current source/evidence demonstrates that an ISP-side deletion can erase required local financial history, this is RELEASE-BLOCKING.

### B. Steps 4 & 5 (Remove RAM Locks & Thin ViewModels)
- **Status:** `UX OPTIMIZATION / CONDITIONAL`
  - *Authority:* Wave 1 Final Specification.
  - *Context:* Step 3 proved SQLite is the durable correctness boundary. The `inflightAccountLocks` remains a same-process gesture coalescer. Removing it is an optimization, NOT automatically a minimum release requirement. Do not implement as blocking unless a newer Owner Decision mandates its removal.

### C. G2, G3, G4, G5 (Architectural Candidates)
These are **NOT** linear sequential phases (i.e., not `G2 -> G3 -> G4 -> G5`). They have complex interdependencies (`G5 -> G2, G3`, `G3 <-> G4`, `G5 -> G7`, `G2/G3/G4 -> G8`). 

**A CONDITIONAL RELEASE CANDIDATE is not implementation scope.**
It must first pass a dedicated scope assessment that identifies:
- the exact current product requirement;
- the minimum proof obligation;
- existing implementation coverage;
- the exact remaining gap;
- whether the gap actually blocks launch.

Only the resulting minimum gap may become implementation scope.

The roadmap does not pre-authorize implementation of any G-area. A dedicated scope assessment must precede any implementation plan.

- **G5 (Identity Management):** `CONDITIONAL RELEASE CANDIDATE`
  - *Dependency:* Impacts G2 and G3.
  - *Disposition:* Only the strictly necessary deterministic identity fixes required to prevent financial deduplication errors are release-blocking.
- **G3 (Restore & Import) & G4 (Concurrency & Lineage):** `CONDITIONAL RELEASE CANDIDATES`
  - *Dependency:* Mutual dependency (`G3 <-> G4`).
  - *Disposition:* Only the logic strictly required to prevent restorations from destructively overwriting canonical server truth, or interleaving async responses from corrupting data, is release-blocking.
- **G2 (Transport Hardening):** `CONDITIONAL RELEASE CANDIDATE`
  - *Disposition:* Existing outbox and idempotency must be verified. New implementation is only authorized if a proven gap causes duplicate API charges or data loss.

### D. Wave 2 / Pre-Release Cleanup
- **Demo Mode Removal:** `CONDITIONAL PRE-RELEASE CLEANUP`
  - *Authority:* Target Product Contract states Demo Mode is out of scope for the final product.
  - *Disposition:* Required before final release *only* if the current release authority strictly requires its removal before certification. It is not an immediate release gate on its own.

### E. Data Modernization (G7)
- **Money Representation (Double → Long):** `FUTURE DATA-MODERNIZATION / CONDITIONAL`
  - *Authority:* Wave 1 Owner Decisions.
  - *Disposition:* The direction is `Long`, but the migration is deferred to post-Wave 1 data cleanup. Do not treat as a standalone release blocker unless it introduces immediate structural regression risk. Depends heavily on G7 migration capabilities.

### F. G8 — Final Machine-Verifiable Production Certification
- **Status:** `RELEASE-BLOCKING`
  - *Authority:* Target Product Contract.
  - *Context:* This is not a generic "security audit". It is an external evidence infrastructure requiring source identity, test corpus, execution results, and release artifact SHA-256 hashing.

## 6. Post-Launch & Deferred Work (Out of Scope for V1)
- **General Settings Synchronization:** Deferred. Local settings only.
- **Web Admin Audit Log API POC:** Deferred. ASP.NET integration is post-launch.
- **Generic distributed synchronization state machine:** Deferred.
- **Autonomous Earthlink reconciliation engine:** Deferred.
- **Extensive G6/G7 semantic modernization:** Deferred.
  - *Note:* A separate current product-safety requirement remains independently release-blocking if the current implementation can erase required local financial history (e.g., ISP-side deletion). Do not classify that safety requirement as "G6 modernization" merely because the eventual fix may touch G6/G7-related schema.

## 7. Historical P0/P1/P2 Disposition
**P0/P1/P2 labels are historical prioritization context.** Current release priority is determined by business need, current authority, Owner Decisions, release dependency, and current evidence. Historical matrix items do **not** automatically become backlog tasks.

## 8. Explicitly NOT BUILDING
- Generic distributed synchronization state machine.
- Staging database / complex dataset publishing.
- Unnecessary architectural abstractions that lack current implementation justification.

## 9. Release Readiness Path
```text
BUSINESS NEED & PRODUCT CONTRACT
      ↓
WAVE 1 / G1 (CLOSED)
      ↓
CANDIDATE SCOPE ASSESSMENT (G2/G3/G4/G5)
(Extract minimal proof obligations based on dependencies)
      ↓
MINIMAL REQUIRED IMPLEMENTATION & VERIFICATION
      ↓
G8 — FINAL MACHINE-VERIFIABLE PRODUCTION CERTIFICATION
      ↓
LAUNCH
```

## 10. Document Navigation Map
- **Business/Product truth:** `docs/authority/Target Product Contract v0.6.md`
- **Final architectural judgment:** `docs/authority/Final Independent Adjudication Memo.md`
- **Frozen architecture:** `docs/authority/G1-G8 Consolidated Architecture Summary.md`
- **Current Wave 1 scope:** `Earthlink-Reseller_Wave1_Report_v3.md`, `EarthLink-Reseller_Wave1_Step1-3_Final.md`
- **Certification:** `EarthLink_Reseller_Step3_Adversarial_Certification_Basis_v6.md`, `EARTHLINK_RESELLER_STEP3_ADVERSARIAL_CERTIFICATION_FINAL.md`
- **Current machine contracts:** `contract/`
- **Current machine evidence:** `evidence/`
- **Transition context:** `docs/authority/EARTHLINK_V1_HANDOVER.md`, `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
- **Current route:** `PROJECT_ROADMAP.md` (This document)
- **Document Classification:** `DOCUMENT_INVENTORY.md` (Active vs historical status map)

## 11. Current vs Historical Documentation
- **CURRENT AUTHORITY:** `Target Product Contract v0.6.md`, `Final Independent Adjudication Memo.md`, `G1-G8 Consolidated Architecture Summary.md`
- **CURRENT SCOPE / NAVIGATION:** `PROJECT_ROADMAP.md`
  - *Current scope is derived from: current Owner Decisions, Target Product Contract, applicable authority, and current Wave 1 decisions.*
- **DOCUMENT CLASSIFICATION:** `DOCUMENT_INVENTORY.md`
- **CURRENT IMPLEMENTATION / EVIDENCE:** `contract/`, `evidence/`
- **TRANSITION CONTEXT:** `EARTHLINK_V1_HANDOVER.md`, `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
- **HISTORICAL / REFERENCE:** P0/P1/P2 Matrices, older Lessons Learned, legacy roadmaps (READ FOR HISTORY ONLY, DO NOT USE AS CURRENT SCOPE).

## 12. Agent Navigation Rules
1. Read `PROJECT_ROADMAP.md` first as the primary navigation document. It is NOT a replacement for authoritative product/scope documents.
2. Determine current state before proposing work.
3. Use current Owner Decisions for current scope.
4. Use Product Contract for business/product requirements.
5. Use G1-G8 only as architecture/dependency constraints, not as a sequential backlog.
6. Never convert historical P0/P1/P2 into current backlog automatically.
7. Never implement a G-area merely because it exists.
8. Verify whether the work is `RELEASE-BLOCKING` or `POST-LAUNCH`.
9. Do not reopen CLOSED Wave 1 work without concrete contradiction.
10. Do not create architecture merely to satisfy historical documents.
11. Update the `Current State` section only when a defined milestone, scope gate, verification gate, or release gate is formally CLOSED.
12. Keep the roadmap status concise:
    - Completed
    - Current Gate
    - Next Authorized Gate
13. Do not use PROJECT_ROADMAP.md as an execution log. Detailed evidence, test results, commits, and implementation history belong in their dedicated records.
14. Every milestone closure must update the roadmap's Current State so that a new Agent can determine the repository's current position without reconstructing history from commits.
15. Update `Current Gate` only when the corresponding scope/verification activity is formally opened or closed by the authorized work record. Do not infer gate status from partial work, uncommitted local changes, or agent claims alone.
