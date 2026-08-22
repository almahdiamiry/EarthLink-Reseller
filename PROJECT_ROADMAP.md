# EARTHLINK RESELLER V1 — MASTER DEVELOPMENT ROADMAP TO RELEASE

## 1. Product Objective
EarthLink Reseller is a local-first, offline-capable Android application designed to allow authorized resellers to manage local ledger balances, issue Earthlink deposits, and synchronize financial operations durably, even over highly constrained or unavailable networks. The core objective is secure, atomic, and idempotent financial dispatch.

## 2. Simplification Principle
**Simplification is a first-class product objective.** We are not trying to finish every historical architecture item. The goal is to finish the smallest release that is justified by the business need, the current product contract, explicit Owner Decisions, required safety, and release evidence. We use the minimum sufficient architecture and avoid unnecessary future complexity.

## 3. Current State
- **Step 1 (G1 Transport Intent):** CLOSED
- **Step 2 (Outcome Resolution):** CLOSED
- **Step 3 (Durable Atomic SQLite Claim):** CLOSED
- **Step 3 Adversarial Certification:** GO
- **G1 / Wave 1 Closure:** CLOSED
- **C06 / C12 Constraints:** CLOSED

**Production architecture is FROZEN unless a concrete current contradiction is established.** Do not reopen completed work.

## 4. Minimum Release Scope
The following items are strictly required before launch, based on current authority and Wave 1 Owner Decisions:

- **Wave 1 Completion (Steps 4 & 5)**
  - *Requirement:* Remove transient RAM-based `inflightAccountLocks` and relocate financial orchestration from ViewModels to a thin `FinancialOperationService`. ViewModels must have zero knowledge of the G1 lifecycle.
  - *Why it is needed:* Step 3 established durable atomic dispatch, making RAM locks obsolete and dangerous to UX lifecycle.
  - *Authority:* Wave 1 Final Report / Owner Decisions.
  - *Exit condition:* Full backup/restore and concurrent transaction regressions pass without memory locks.
- **G2 Transport Hardening**
  - *Requirement:* Verify and harden existing outbox processing, orphan handling, and real Firestore lost-ACK idempotency.
  - *Why it is needed:* Prevents duplicate API charges on poor networks.
  - *Authority:* Target Product Contract / G1-G8 Architecture.
  - *Exit condition:* Idempotency tests pass across disrupted network simulated boundaries.
- **G4 Concurrency & Lineage Protection**
  - *Requirement:* Implement local generation validation, reject stale sync results under the same Room transaction, and ensure secure lineage invalidation on sign-out/clear.
  - *Why it is needed:* Prevents data corruption from interleaved async sync responses.
  - *Authority:* G1-G8 Architecture.
  - *Exit condition:* Lineage violation tests reject stale payloads.
- **G3 Restore Merge & Safety**
  - *Requirement:* Implement complete-lineage baseline conflict resolution *before* the final Room transaction during database restore.
  - *Why it is needed:* Prevents backup restorations from overwriting canonical server truth destructively.
  - *Authority:* Final Independent Adjudication Memo.
  - *Exit condition:* Restore conflict matrix tests pass.
- **G5 Deterministic Identity**
  - *Requirement:* Replace collision-prone missing-source-key deduplication with deterministic source-row identity.
  - *Why it is needed:* Ensures identical historical rows remain distinct and are not erroneously merged.
  - *Authority:* Final Independent Adjudication Memo.
  - *Exit condition:* Ledger identity uniqueness tests pass.
- **G8 Final Certification & Security Audit**
  - *Requirement:* Execute external machine-verifiable certification engine, verify release artifacts.
  - *Why it is needed:* Contractual production gate.
  - *Authority:* Target Product Contract.
  - *Exit condition:* All adversarial test matrices pass.

## 5. Current Release Gates
Only gates actually justified by current authority are included.
- **GATE A — Product / Business Contract:** Target Product Contract v0.6 requirements met.
- **GATE B — Wave 1 / G1 Safety:** Canonical Financial Lifecycle and atomic dispatch verified (CLOSED).
- **GATE C — Transport / Durability:** G2/G4 outbox, idempotency, and concurrency hardened.
- **GATE D — Restore / Data Integrity:** G3 restore merge and G5 deterministic identity verified.
- **GATE E — Final Certification:** G8 adversarial test matrix pass.

## 6. Post-Launch / Future Hardening
Work that is explicitly deferred, not required for Minimum Release Scope, or belongs to future G-area dependencies that can wait safely.
- **General Settings Synchronization:** Deferred. Local settings only for MVP.
- **Web Admin Audit Log API POC:** Deferred. ASP.NET integration is post-launch.
- **Autonomous Earthlink Reconciliation Engine:** Deferred. Server-side reconciliation loop is out of scope for the mobile client MVP.
- **G6/G7 Extensive Semantic Migrations:** Safely deferred unless specific schema modernization (like Money representation) blocks MVP ledger safety.
- *Reason:* None of these violate current product/business contracts or endanger the atomic local ledger.

## 7. G2–G8 Disposition
- **G2 (Transport Hardening):** MINIMUM RELEASE SCOPE. Requires hardening/verification of existing outbox and idempotency. Does not require a complete rewrite, just verification and gap closures.
- **G3 (Restore & Import):** MINIMUM RELEASE SCOPE. Requires deterministic conflict resolution during backup/restore.
- **G4 (Concurrency & Lineage):** MINIMUM RELEASE SCOPE. Requires lineage invalidation and stale sync rejection.
- **G5 (Identity Management):** MINIMUM RELEASE SCOPE. Deterministic identity is required to prevent financial deduplication errors.
- **G6 (Field Ownership Semantics):** CONDITIONAL RELEASE GATE / POST-LAUNCH. Legacy semantics (`isLegacy`, `loanIqd`) are largely stable. Extensive remapping is deferred unless broken. Critical fix required: Ensure ISP-side subscriber deletion does not physically erase local financial history.
- **G7 (State Migration):** POST-LAUNCH / DEFERRED. Schema modernization and FK migrations (e.g., removing ON DELETE CASCADE) only applied when strictly necessary.
- **G8 (Production Certification):** MINIMUM RELEASE SCOPE. Required for final release validation.

## 8. Wave 2 Disposition
- **Demo Mode Removal:** Explicitly classified as a Wave 2 task. Required before final G8 certification but separated from Wave 1 ledger safety tasks.

## 9. Data Modernization Disposition
- **Money Representation (Double → Long):** CONDITIONAL GATE. Modernization of IQD to `Long` is identified but depends heavily on G7 migration capability. It must not be forced if it introduces immediate structural regression risk. If it blocks Minimum Release Scope safety, it executes prior to launch; otherwise, deferred to Data Modernization epoch.

## 10. Historical P0/P1/P2 Disposition
**P0/P1/P2 labels are historical prioritization context.** Current release priority is determined by business need, current authority, Owner Decisions, release dependency, and current evidence. Historical matrix items do not automatically become backlog tasks.
- Items resolved by Wave 1 atomic dispatch: **CLOSED IN WAVE 1**
- Core Idempotency / Restore safety items: **MINIMUM RELEASE SCOPE**
- Settings / Sync extensions: **DEFERRED / OPTIONAL**
- Generic Abstractions: **NOT REQUIRED / RETIRED**

## 11. Explicitly NOT BUILDING
- Generic distributed synchronization state machine.
- Staging database / complex dataset publishing.
- Unnecessary architectural abstractions that lack current implementation justification.

## 12. Release Readiness Path
```text
BUSINESS NEED
      ↓
PRODUCT CONTRACT
      ↓
WAVE 1 / G1
      ✅ CLOSED
      ↓
MINIMUM RELEASE SCOPE
      ↓
CURRENT RELEASE GATES (A-D)
      ↓
TARGETED IMPLEMENTATION / VERIFICATION
      ↓
RELEASE CERTIFICATION (GATE E / G8)
      ↓
LAUNCH
```

## 13. Document Navigation Map
- **Business/Product truth:** `docs/authority/Target Product Contract v0.6.md`
- **Final architectural judgment:** `docs/authority/Final Independent Adjudication Memo.md`
- **Frozen architecture:** `docs/authority/G1-G8 Consolidated Architecture Summary.md`
- **Current Wave 1 scope:** `Earthlink-Reseller_Wave1_Report_v3.md`, `EarthLink-Reseller_Wave1_Step1-3_Final.md`
- **Certification:** `EarthLink_Reseller_Step3_Adversarial_Certification_Basis_v6.md`, `EARTHLINK_RESELLER_STEP3_ADVERSARIAL_CERTIFICATION_FINAL.md`
- **Current machine contracts:** `contract/`
- **Current machine evidence:** `evidence/`
- **Transition context:** `docs/authority/EARTHLINK_V1_HANDOVER.md`, `docs/authority/EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
- **Current route:** `PROJECT_ROADMAP.md` (This document)

## 14. Current vs Historical Documentation
- **CURRENT AUTHORITY:** `Target Product Contract v0.6.md`, `Final Independent Adjudication Memo.md`, `G1-G8 Consolidated Architecture Summary.md`
- **CURRENT SCOPE:** `PROJECT_ROADMAP.md`
- **CURRENT IMPLEMENTATION / EVIDENCE:** `contract/`, `evidence/`
- **TRANSITION CONTEXT:** `EARTHLINK_V1_HANDOVER.md`, `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
- **HISTORICAL / REFERENCE:** P0/P1/P2 Matrices, older Lessons Learned, legacy roadmaps (READ FOR HISTORY ONLY, DO NOT USE AS CURRENT SCOPE).

## 15. Agent Navigation Rules
1. Read `PROJECT_ROADMAP.md` first.
2. Determine current state before proposing work.
3. Use current Owner Decisions for current scope.
4. Use Product Contract for business/product requirements.
5. Use G1-G8 only as architecture/dependency constraints.
6. Never convert historical P0/P1/P2 into current backlog automatically.
7. Never implement a G-area merely because it exists.
8. Verify whether the work is Minimum Release Scope or Future Hardening.
9. Do not reopen CLOSED Wave 1 work without concrete contradiction.
10. Do not create architecture merely to satisfy historical documents.

