# EARTHLINK RESELLER V1 — MASTER DEVELOPMENT ROADMAP TO PRE-LAUNCH

## 1. PURPOSE AND AUTHORITY
This is the single authoritative master roadmap for the remainder of the EarthLink Reseller V1 project, superseding all previous historical plans, intermediate reports, and fragmented Wave 1/Wave 2 documents.

**Frozen Architecture Authorities:**
- `Target Product Contract v0.6`
- `G1-G8 Consolidated Architecture Summary`
- `Final Independent Adjudication Memo`

**Current Execution Baseline:**
- Wave 1 (Steps 1–3) is **CLOSED and CERTIFIED** (Durable Dispatch Claim, Canonical Financial Lifecycle, Outcome Semantics).

---

## 2. COMPLETED PHASES (ARCHIVED)
- **Phase 0:** Repository, Documentation, and Governance Alignment [CLOSED]
- **Wave 1 (Steps 1–3):** Canonical Financial Lifecycle, Outcome Semantics, and Durable Dispatch Claim (Atomic SQLite Claim) [CLOSED & CERTIFIED]

---

## 3. REMAINING DEVELOPMENT ROADMAP (EXECUTION SEQUENCE)

### PHASE 1: WAVE 1 COMPLETION (STEPS 4 & 5)
- **Step 4 (Remove Transient Locks):** Remove RAM-based `inflightAccountLocks` now that the durable SQLite atomic claim (Step 3) is proven. Run full backup/restore regressions.
- **Step 5 (Thin ViewModel):** Relocate financial orchestration from ViewModels to a `FinancialOperationService` or `UseCase` layer. ViewModels must have zero knowledge of the G1 lifecycle.

### PHASE 2: WAVE 2 SIMPLIFICATION & FEATURE REMOVAL
- **Demo Mode Removal:** Completely remove Demo Mode configuration, caches, and conditional UI. Replace tests relying on Demo Mode with explicit test doubles.
- **Financial Normalization (Double to Long):** Formalize IQD money representation strictly as `Long` across the local ledger and models, with a controlled schema migration.

### PHASE 3: G2 & G4 — CLOUD DURABILITY AND CONCURRENCY
- **G2 Transport Hardening:** Ensure per-item outbox processing, orphan handling, and real Firestore lost-ACK idempotency.
- **G4 Concurrency & Lineage Protection:** Implement local generation validation, reject stale sync results under the same Room transaction, and ensure sign-out/full-clear correctly invalidates lineage.

### PHASE 4: G3 & G5 — RESTORE, IMPORT, AND IDENTITY
- **G3 Restore Merge:** Implement complete-lineage baseline conflict resolution *before* the final Room transaction.
- **G5 Identity Management:** Replace collision-prone missing-source-key deduplication with deterministic source-row identity. Ensure identical historical rows remain distinct.

### PHASE 5: G6 & G7 — SEMANTICS & SAFE MIGRATION
- **G6 Field Ownership & Semantics:** Finalize credential/session isolation, and preserve legacy semantic fields (`loanIqd`, `isLegacy`, etc.). **Critical:** Ensure ISP-side subscriber deletion does not physically erase local financial history.
- **G7 Migration:** Implement business-data-preserving migrations (e.g., non-destructive FK migration removing `ON DELETE CASCADE`).

### PHASE 6: G8 PRODUCTION CERTIFICATION & RELEASE
- **Certification Engine:** Implement external machine-verifiable certification.
- **Final Security & QA:** Independent zero-trust audit, final release artifact hashing, and production-ready authorization.

---

## 4. DEFERRED / OUT-OF-SCOPE FOR V1
- General Settings Synchronization.
- Web Admin Audit Log API POC (ASP.NET WebForms integration).
- Generic distributed synchronization state machine / generic reconciliation engine.
- Staging database / complex dataset publishing.
- Autonomous Earthlink reconciliation engine.
