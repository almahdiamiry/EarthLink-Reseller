# EARTHLINK RESELLER V1 — FINAL STEP 1–3 IMPLEMENTATION SPECIFICATION
## Authority-Locked, Owner-Decision-Locked, Repository-Evidence-Based

**Target Release:** EarthLink Reseller V1  
**Baseline Authority:** `EarthLink-Reseller_Wave1_Report_v3.md` (Frozen Wave 1 v3 Authority)  
**Implementation Base:** Current Repository Source Tree (`reconstruct_step3_state_model`)  
**Audit Date:** 2026-08-21  
**Status:** IMPLEMENTATION SPECIFICATION — READY FOR EXECUTION PHASING

---

## 1. EXECUTIVE SUMMARY & VERDICT

### 1.1 Executive Verdict & Baseline vs. Target Demarcation
**The Step 1–3 specification is implementation-ready and safe, with a strict boundary between existing code and target design:**

* **Current Repository Baseline (Verified & Existing in Codebase Now):**
  - Table `pending_external_operations` currently contains only: `businessTransactionId`, `operationIntentId`, `accountId`, `operationType`, `amountIqd`, `payloadJson`, `status`, `createdAt`, `updatedAt`, `lastError`, `verificationEvidence`.
  - The highest active database migration registered in `AppDatabase.kt` is `MIGRATION_15_16`.
  - `dispatchClaimCount`, `claimDispatch()`, `getOrphanedInFlightOperations()`, `resetOrphanedInFlightToPending()`, and `getUnresolvedClaimedOperations()` do **NOT exist in the current codebase**. They are the **target design** to be added in Step 3 via `MIGRATION_16_17`.
  - ViewModels currently rely on transient RAM locks (`inflightAccountLocks`) and direct Gateway calls with silent catch blocks.
* **Target Specification (Designed & Validated, Ready for Phased Execution):**
  - SQLite hardware-level atomic claim (`status = 'PENDING' AND dispatchClaimCount = 0`).
  - Separation of 2 Financial Mutations (`createUserUsingDeposit`, `refillUser`) from 2 Non-Financial Lifecycle Operations (`createTestUser`, `extendUser`).
  - Elimination of all silent fallbacks (including `40000.0` catch fallbacks and `G1-F` state-only renewal fallback).
  - Cold-start recovery reader for orphaned `DISPATCHING` / `RESOLVING` records, bounded by the process-start snapshot.
  - Existing ViewModel in-flight lock retained only as same-action UI coalescing; SQLite remains the durable claim authority.
  - Non-financial recovery is fail-closed: current state can prove non-execution, but not historical success by itself.

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                         CORE ARCHITECTURAL UPGRADES                              │
├────────────────────────────────────────┬─────────────────────────────────────────┤
│ CURRENT REPOSITORY BASELINE (Now)      │ TARGET FROZEN SPECIFICATION (To Build)  │
├────────────────────────────────────────┼─────────────────────────────────────────┤
│ In-memory ConcurrentHashMap Mutex      │ SQLite Hardware-Level Atomic Claim      │
│ (inflightAccountLocks)                 │ (status='PENDING' & dispatchClaimCount=0)│
├────────────────────────────────────────┼─────────────────────────────────────────┤
│ 7 Uncoordinated COMPLETED Writers in   │ 1 Canonical Verified-Success Materializer│
│ Repositories.kt                        │ (resolvePendingOperationVerifiedSuccess)│
├────────────────────────────────────────┼─────────────────────────────────────────┤
│ Silent 40000.0 & Catch-to-FAILED Falls │ Fail-Closed NOT-DISPATCHED / INCONCLUSIVE│
│ (Swallows errors into guess amounts)   │ (Strict validation before dispatch claim)│
├────────────────────────────────────────┼─────────────────────────────────────────┤
│ State-Only Renewal Fallback (G1-F Bug) │ 4-Tuple accountStatement Correlation    │
│ (detail != null -> COMPLETED)          │ (userID, operation, amount, ±90s window)│
├────────────────────────────────────────┼─────────────────────────────────────────┤
│ Orphaned DISPATCHING on Process Death  │ Cold-Start Only Recovery Reader         │
│ (No recovery after hard crash)         │ (getOrphanedInFlightOperations)      │
└────────────────────────────────────────┴─────────────────────────────────────────┘
```

---

## 2. FROZEN OWNER & BUSINESS DECISIONS

The following decisions are product-level invariants and govern all implementation steps:

* **2.1 Product Scope (`VERIFIED`):** Demo Mode is not required and will not exist in the intended product. Its removal is an independent Wave 2 simplification task. No new production abstractions will be built around Demo Mode in Steps 1–3.
* **2.2 Money Representation (`VERIFIED`):** Iraqi Dinar (IQD) transactions are whole numbers (minimum denomination is 250 IQD). Direction is standardization on `Long`. Database `Double → Long` migration is deferred to post-Wave 1 data cleanup.
* **2.3 Backup Architecture Baseline (`VERIFIED`):** Pre-Wave-1 portable backup (optional password, AES-256-GCM, zero Firebase UID / Keystore binding) is frozen baseline. No changes to backup encryption in Steps 1–3.
* **2.4 Non-Banking System Semantics (`VERIFIED`):** Local-first system. Explicit API rejection results in zero ledger materialization. Transport uncertainty (timeouts, socket disconnects, process termination) is strictly an unknown outcome requiring verification before financial ledger materialization. Blind redispatch is prohibited.
* **2.5 Activation Recovery Protocol (`VERIFIED`):** Recovered activations are never deleted based on elapsed time.
  - If subscriber status is `SUSPENDED` on recovery $\rightarrow$ treated as the approved V1 operational non-execution signal $\rightarrow$ transitions to `FAILED` with zero ledger mutation.
  - If subscriber status is `ACTIVE` $\rightarrow$ `ACTIVE` alone is not proof of historical execution $\rightarrow$ proceeds to `accountStatement` 4-tuple compound correlation.
* **2.6 Approved V1 Verification Heuristic (`VERIFIED`):** `GET /affiliate/deposit/accountStatement` with 4-tuple compound correlation (`userID`, `operation`, `exact amount`, `±90s window` around operation-intent `createdAt`).
  - Unique strong match $\rightarrow$ `VERIFIED_SUCCESS`.
  - Ambiguous or missing $\rightarrow$ `INCONCLUSIVE` (remains `PENDING`, zero ledger mutation).
* **2.7 Anti-Repeat API Signal (`VERIFIED`):** Server rejection of repeated renewal within $<1$ minute is an experimental signal only. Never treat "rate-limit passed" as proof of device execution.
* **2.8 Web Admin Audit Log (`VERIFIED`):** ASP.NET WebForms audit log discovery is a separate future POC outside Steps 1–3.
* **2.9 Recovery Identity Discriminator (`VERIFIED`):** `dispatchClaimCount: Int NOT NULL DEFAULT 0` added to `pending_external_operations`. `0` = fresh/unclaimed, `1` = claimed/recovery-blocked, `>1` = forbidden.
* **2.10 Automated Runtime Sweep Isolation (`VERIFIED`):** Runtime background sweeps (`SyncWorker`) must not query active `DISPATCHING` records to eliminate in-flight race conditions.
* **2.11 `G1-F` Prerequisite (`VERIFIED`):** The `baselineExpirationDate == null` success fallback in `verifyAndResolvePendingOperation` is invalid. Replacing it with `accountStatement` compound correlation is a mandatory Step 2 prerequisite before enabling automated recovery.
* **2.12 Operation Type Classification & Owner Decisions (`VERIFIED`):**
  - **Financial Mutations (2 operations):**
    - `createUserUsingDeposit` $\rightarrow$ Persisted `operationType = "ACTIVATION"`. Paid activation, deducts deposit, materializes ledger debt. Requires strict positive `amountIqd > 0L`.
    - `refillUser` $\rightarrow$ Persisted `operationType = "REFILL"`. Subscription refill, deducts deposit, materializes ledger renewal. Requires strict positive `amountIqd > 0L`.
  - **Non-Financial Lifecycle Operations (2 operations):**
    - `createTestUser` $\rightarrow$ Persisted `operationType = "TEST_USER"` (replaces legacy `"ACTIVATION"`). Free temporary test account creation (`amountIqd = 0L`, zero financial ledger mutation).
    - `extendUser` $\rightarrow$ Persisted `operationType = "EXTEND"` (replaces legacy `"RENEWAL"`). **OWNER BUSINESS DECISION:** `extendUser` is classified as an administrative ~24h/48h grace-period extension in V1 that deducts a test user credit on EarthLink's server without reseller deposit charge (`amountIqd = 0L`, zero financial ledger mutation).
  - **Strict `operationType` Discrimination Invariant:** The canonical materializer discriminates non-financial vs financial branches **strictly by `operationType`** (`TEST_USER` / `EXTEND`), and **NEVER by `amountIqd == 0L` alone**, preventing any corrupted financial record from bypassing ledger accounting.
* **2.13 Elimination of Silent Amount Fallbacks (`G1-Amount-Fallback`) (`VERIFIED`):**
  - In `createUserUsingDeposit`: If `gateway.getAccountCost(pkgIndex)` throws or fails, the operation MUST fail closed immediately as **NOT-DISPATCHED** (no pending intent, no claim, no dispatch). Defaulting to `40000.0` is strictly prohibited.
  - In `refillUser`: If package `price` is null or $\le 0$, the operation MUST fail closed immediately as **NOT-DISPATCHED**. Defaulting to `40000.0` is strictly prohibited.
  - **Pre-Dispatch Price Failure Isolation Invariant:** Aborting on price fetch failure happens before `recordPendingOperation` or `claimDispatch`. Any pre-existing historical `PendingExternalOperation` in the database remains completely untouched and is never mutated, dispatched, or corrupted by a failed price resolution.
* **2.14 SQLite Claim Atomicity (`VERIFIED`):**
  - Dispatch authorization atomicity is enforced strictly by SQLite (`WHERE status = 'PENDING' AND dispatchClaimCount = 0`). `DataOperationCoordinator` is reserved for coarse maintenance exclusion and must not be overloaded as an unnecessary 4th concurrency layer around local single-row claims.
* **2.15 Cold-Start Snapshot Boundary (`P0`, `VERIFIED` target rule):**
  - Cold-start orphan recovery considers only `DISPATCHING` / `RESOLVING` rows with `updatedAt < processStartMs`, where `processStartMs` is captured once when the current Android process starts. Current-process claims are never classified as previous-process orphans merely because startup recovery is asynchronous.
  - Runtime `SyncWorker` never inspects in-flight `DISPATCHING` / `RESOLVING`; it verifies only recovery-blocked `PENDING(count=1)` rows.
* **2.16 Same-Process Double-Tap Coalescing (`P0`, `VERIFIED` target rule):**
  - The existing ViewModel `inflightAccountLocks` remains solely a same-process gesture coalescer for the same logical `(account, operationType)` action. It is not a durability authority and never replaces the SQLite claim.
  - The coalescing lock is acquired before a new `operationIntentId` is generated; a concurrent second tap is rejected without creating a second intent. Release occurs in `finally` after terminal/inconclusive completion.
* **2.17 Non-Financial Recovery Evidence (`P0`, `VERIFIED` target rule):**
  - For `TEST_USER` and `EXTEND`, current `ACTIVE`/existing state alone is never historical proof of the interrupted operation. Current state may provide negative evidence (`username still available`, or `EXTEND` still suspended/expired), but positive recovery requires operation-specific historical evidence; otherwise remain `INCONCLUSIVE` with zero financial ledger mutation.
  - Direct HTTP success from the original claimed dispatch remains definitive and may complete the non-financial lifecycle operation.
* **2.18 Financial Amount Normalization & Price Authority (`P0`, `VERIFIED` target rule):**
  - Before `Double -> Long`, financial cost/price must be finite, strictly positive, a whole IQD value, and a valid 250-IQD denomination. Invalid/fractional values abort as `NOT-DISPATCHED`; no truncation or rounding.
  - `refillUser` price must trace to the authoritative production pricing source, not a presentation/cache-only value. If authority cannot be proven, abort as `NOT-DISPATCHED`.
* **2.19 Data-Safety Freeze (`OWNER DECISION`):**
  - The current pre-production/data-free window authorizes the P0 integrity work. After adversarial certification, subsequent simplifications may not weaken durable claim, recovery, financial materialization, or verification invariants.

---

## 3. STEP 1 — FINAL VERIFIED BOUNDARY & CALL-SITE INVENTORY

### 3.1 Authoritative Lifecycle Matrix

| Lifecycle Role | Current Production Owner | Target Frozen Authority | Classification |
| :--- | :--- | :--- | :--- |
| **Pending Record Creation** | `EarthlinkSearchViewModel.kt` (4 sites) | `LocalLedgerRepository.recordPendingOperation` | `CURRENT` |
| **`operationIntentId` Generation** | `EarthlinkSearchViewModel.kt` (`UUID.randomUUID()`) | Client Intent Boundary (`UUID.randomUUID()`) | `CURRENT` |
| **`businessTransactionId` Derivation** | `EarthlinkSearchViewModel.kt` (`"tx_" + intentId`) | Deterministic Intent Mapping (`"tx_" + intentId`) | `CURRENT` |
| **Dispatch Claim Authority** | Non-existent (in-memory `inflightAccountLocks` only) | `pendingDao.claimDispatch` (SQLite atomic claim) | `REQUIRED CHANGE` |
| **External Financial Mutation** | `EarthlinkSearchViewModel.kt` directly calls Gateway | Exclusive Claim Winner only | `REQUIRED CHANGE` |
| **Outcome Classification** | Viewmodel `catch` blocks (collapses to `FAILED`) | Canonical Outcome Classifier (`Step 2`) | `REQUIRED CHANGE` |
| **Verification Invocation** | Uncoordinated (`EarthlinkApp`, `SyncWorker`, VM) | `LocalLedgerRepository.verifyAndResolvePendingOperation` | `CURRENT` |
| **Verified-Success Materializer** | 7 uncoordinated write lines in `Repositories.kt` | 1 Canonical Materializer (`resolvePendingOperationVerifiedSuccess`) | `REQUIRED CHANGE` |
| **Failure Transition Authority** | Multiple ad-hoc writers (`markPendingOperationFailed`) | 1 Canonical Failure Authority (`resolvePendingOperationVerifiedFailure`) | `REQUIRED CHANGE` |
| **Manual Verification Entry** | `submitManualVerificationEvidence` (`Repositories.kt:1458`) | `submitManualVerificationEvidence` (routes to canonical materializer) | `CURRENT` |
| **Writer of `PENDING`** | VM constructors & `resolvePendingOperationInconclusive` | `recordPendingOperation` & `resolvePendingOperationInconclusive` | `CURRENT` |
| **Writer of `DISPATCHING`** | None | `pendingDao.claimDispatch` | `REQUIRED CHANGE` |
| **Writer of `RESOLVING`** | None (only read in DAO) | Active multi-step reconciliation transition | `REQUIRED CHANGE` |
| **Writer of `COMPLETED`** | 7 physical code lines in `Repositories.kt` | Exactly 1 (`resolvePendingOperationVerifiedSuccess`) | `REQUIRED CHANGE` |
| **Writer of `FAILED`** | 2 physical code lines in `Repositories.kt` | Exactly 1 (`resolvePendingOperationVerifiedFailure`) | `REQUIRED CHANGE` |

---

### 3.2 Production Call-Site Inventory (2 Financial + 2 Non-Financial Lifecycle)

```text
CALL-SITE 1: createTestUser [NON-FINANCIAL LIFECYCLE OPERATION]
Caller: CreateTestUserScreen.kt:84 -> EarthlinkSearchViewModel.kt:322
Current Path:
  VM generates intentId & businessTxId ("tx_" + intentId)
  -> Acquires VM-level inflightAccountLocks.tryLock() [RAM ONLY]
  -> Calls localLedgerRepository.recordPendingOperation (status='PENDING', amount=0)
  -> Calls gateway.createTestUser(username, phone, fullName, pkgIndex) [NO DURABLE CLAIM]
  -> If generatedPassword != null -> completePendingOperation (writes COMPLETED)
  -> If null -> markPendingOperationFailed (writes FAILED)
  -> On catch (e: Exception) -> markPendingOperationFailed (COLLAPSES TIMEOUT TO FAILED)
Target Path:
  VM generates intentId & businessTxId
  -> Calls recordPendingOperation (status='PENDING', amountIqd=0L, dispatchClaimCount=0)
  -> Calls claimDispatch(businessTxId) -> checks rowsAffected == 1
  -> If claimed: executes gateway.createTestUser
     -> Success: routes to canonical resolvePendingOperationVerifiedSuccess (Non-Financial Branch: zero ledger entry)
     -> Explicit Failure: routes to canonical resolvePendingOperationVerifiedFailure
     -> Timeout/Exception: routes to resolvePendingOperationInconclusive (status=PENDING, dispatchClaimCount=1)
  -> If claim rejected: returns inflight/duplicate status to UI without calling Gateway.
Classification: REQUIRED CHANGE (Step 2 + Step 3)
```

```text
CALL-SITE 2: createUserUsingDeposit [FINANCIAL MUTATION]
Caller: CreateUsingDepositScreen.kt:92 -> EarthlinkSearchViewModel.kt:377
Current Path:
  VM generates intentId & businessTxId
  -> Acquires VM-level inflightAccountLocks.tryLock() [RAM ONLY]
  -> Resolves customerId & calls gateway.getAccountCost(pkgIndex) [SILENT CATCH FALLBACK TO 40000.0!]
  -> Calls localLedgerRepository.recordPendingOperation (status='PENDING', amount=cost)
  -> Calls gateway.createUserUsingDeposit(...) [NO DURABLE CLAIM]
  -> If password != null -> completePendingOperation (writes COMPLETED)
  -> If null -> markPendingOperationFailed (writes FAILED)
  -> On catch (e: Exception) -> markPendingOperationFailed (COLLAPSES TIMEOUT TO FAILED)
Target Path:
  VM generates intentId & businessTxId
  -> Resolves customerId & calls gateway.getAccountCost(pkgIndex)
     -> If cost <= 0 or throws Exception: ABORT IMMEDIATELY as NOT-DISPATCHED (no intent recorded, no claim).
  -> Calls recordPendingOperation (status='PENDING', amountIqd=cost.toLong(), dispatchClaimCount=0)
  -> Calls claimDispatch(businessTxId) -> checks rowsAffected == 1
  -> If claimed: executes gateway.createUserUsingDeposit
     -> Success: routes to canonical resolvePendingOperationVerifiedSuccess (Financial Branch: materializes ledger debt)
     -> Explicit Failure: routes to canonical resolvePendingOperationVerifiedFailure
     -> Timeout/Exception: routes to resolvePendingOperationInconclusive (status=PENDING, dispatchClaimCount=1)
  -> If claim rejected: returns inflight/duplicate status to UI without calling Gateway.
Classification: REQUIRED CHANGE (Step 2 + Step 3)
```

```text
CALL-SITE 3: refillUser [FINANCIAL MUTATION]
Caller: UserDetailScreen.kt:91 & UserDetailScreenV2.kt:557 -> EarthlinkSearchViewModel.kt:433
Current Path:
  VM generates intentId & businessTxId
  -> Acquires VM-level inflightAccountLocks.tryLock() [RAM ONLY]
  -> Evaluates finalPrice = price ?: 40000.0 [SILENT FALLBACK TO 40000.0!]
  -> Calls localLedgerRepository.recordPendingOperation (status='PENDING', amount=finalPrice)
  -> Calls gateway.refillUserDeposit(userId, depositPass) [NO DURABLE CLAIM]
  -> If true -> calls recordAccountRenewal (writes COMPLETED at Repositories.kt:1780)
  -> If false -> markPendingOperationFailed (writes FAILED)
  -> On catch (e: Exception) -> markPendingOperationFailed (COLLAPSES TIMEOUT TO FAILED)
Target Path:
  VM generates intentId & businessTxId
  -> Validates package price
     -> If price == null or price <= 0.0: ABORT IMMEDIATELY as NOT-DISPATCHED (no intent recorded, no claim).
  -> Calls recordPendingOperation (status='PENDING', amountIqd=finalPrice.toLong(), dispatchClaimCount=0)
  -> Calls claimDispatch(businessTxId) -> checks rowsAffected == 1
  -> If claimed: executes gateway.refillUserDeposit
     -> Success: routes to canonical resolvePendingOperationVerifiedSuccess (Financial Branch: materializes ledger renewal)
     -> Explicit Failure: routes to canonical resolvePendingOperationVerifiedFailure
     -> Timeout/Exception: routes to resolvePendingOperationInconclusive (status=PENDING, dispatchClaimCount=1)
  -> If claim rejected: returns inflight/duplicate status to UI without calling Gateway.
Classification: REQUIRED CHANGE (Step 2 + Step 3)
```

```text
CALL-SITE 4: extendUser [NON-FINANCIAL LIFECYCLE OPERATION]
Caller: UserDetailScreen.kt:104 & UserDetailScreenV2.kt:941 -> EarthlinkSearchViewModel.kt:563
Current Path:
  VM generates intentId & businessTxId
  -> Acquires VM-level inflightAccountLocks.tryLock() [RAM ONLY]
  -> Calls localLedgerRepository.recordPendingOperation (status='PENDING', amount=0)
  -> Calls gateway.extendUser(userIndex) [NO DURABLE CLAIM]
  -> If true -> completePendingOperation (writes COMPLETED)
  -> If false -> markPendingOperationFailed (writes FAILED)
  -> On catch (e: Exception) -> markPendingOperationFailed (COLLAPSES TIMEOUT TO FAILED)
Target Path:
  VM generates intentId & businessTxId
  -> Calls recordPendingOperation (status='PENDING', amountIqd=0L, dispatchClaimCount=0)
  -> Calls claimDispatch(businessTxId) -> checks rowsAffected == 1
  -> If claimed: executes gateway.extendUser (administrative ~24h/48h extension, 0 IQD)
     -> Success: routes to canonical resolvePendingOperationVerifiedSuccess (Non-Financial Branch: zero ledger entry)
     -> Explicit Failure: routes to canonical resolvePendingOperationVerifiedFailure
     -> Timeout/Exception: routes to resolvePendingOperationInconclusive (status=PENDING, dispatchClaimCount=1)
  -> If claim rejected: returns inflight/duplicate status to UI without calling Gateway.
Classification: REQUIRED CHANGE (Step 2 + Step 3)
```

---

### 3.3 Semantic Call-Graph Writer Analysis

```text
┌────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 COMPLETED STATUS WRITER INVENTORY                                      │
├────┬───────────────────────┬────────────────────────────────────────┬──────────────────────────────────┤
│ #  │ File & Line           │ Method Name                            │ Semantic Classification          │
├────┼───────────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
│ 1  │ Repositories.kt:1206  │ completePendingOperation               │ Direct Pending Writer (Deprecate)│
│ 2  │ Repositories.kt:1250  │ resolvePendingOperationVerifiedSuccess │ Canonical Materializer (Replay)  │
│ 3  │ Repositories.kt:1266  │ resolvePendingOperationVerifiedSuccess │ Canonical Materializer (Standard)│
│ 4  │ Repositories.kt:1272  │ resolvePendingOperationVerifiedSuccess │ Canonical Materializer (Non-Fin) │
│ 5  │ Repositories.kt:1780  │ recordRenewalTransaction               │ Manual/Offline Ledger Path       │
│ 6  │ Repositories.kt:1799  │ recordAccountPayment                   │ Manual/Offline Ledger Path       │
│ 7  │ Repositories.kt:1818  │ recordAccountDebt                      │ Manual/Offline Ledger Path       │
└────┴───────────────────────┴────────────────────────────────────────┴──────────────────────────────────┘
```

```text
┌────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  FAILED STATUS WRITER INVENTORY                                        │
├────┬───────────────────────┬────────────────────────────────────────┬──────────────────────────────────┤
│ #  │ File & Line           │ Method Name                            │ Semantic Classification          │
├────┼───────────────────────┼────────────────────────────────────────┼──────────────────────────────────┤
│ 1  │ Repositories.kt:1198  │ markPendingOperationFailed             │ Direct Pending Writer (Deprecate)│
│ 2  │ Repositories.kt:1289  │ resolvePendingOperationVerifiedFailure │ Canonical Failure Authority      │
└────┴───────────────────────┴────────────────────────────────────────┴──────────────────────────────────┘
```

#### Canonical Completion Authority in `Repositories.kt:1219–1277`
To eliminate the conflict where `amountIqd <= 0L` threw `MISSING_PERSISTED_FINANCIAL_AMOUNT` for non-financial operations, the canonical materializer `resolvePendingOperationVerifiedSuccess` is structured with an **explicit non-financial branch before the financial amount validation**:

```kotlin
// Canonical Materializer Structure (Repositories.kt:1219–1277)
override suspend fun resolvePendingOperationVerifiedSuccess(
    businessTransactionId: String,
    chargeNote: String?
): LocalLedgerEntry? = database.withTransaction {
    val op = pendingDao.getByBusinessTransactionId(businessTransactionId) ?: return@withTransaction null
    if (op.status == "COMPLETED") {
        return@withTransaction ledgerDao.getByIdOneShot(businessTransactionId)
    }

    // 1. CANONICAL NON-FINANCIAL LIFECYCLE BRANCH (Strictly by operationType: TEST_USER or EXTEND)
    if (op.operationType.equals("TEST_USER", ignoreCase = true) ||
        op.operationType.equals("EXTEND", ignoreCase = true)
    ) {
        pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)
        return@withTransaction null // Zero financial ledger entry created
    }

    // 2. CANONICAL FINANCIAL MUTATION BRANCH (ACTIVATION, REFILL, RENEWAL with amountIqd > 0L)
    if (op.amountIqd <= 0L) {
        throw IllegalStateException("MISSING_PERSISTED_FINANCIAL_AMOUNT: Operation ${op.businessTransactionId} missing exact persisted charge amount")
    }

    val localAcc = accountDao.getByIdOneShot(op.accountId)
        ?: accountDao.findAccountByUsernameOrIdOneShot(op.accountId)
        ?: throw IllegalStateException("MISSING_LOCAL_FINANCIAL_TARGET: Cannot materialize financial position for missing local account ${op.accountId}")

    val operationPrice = op.amountIqd.toDouble()
    val existing = ledgerDao.getByIdOneShot(businessTransactionId)
    if (existing != null) {
        if (existing.accountId == localAcc.id && existing.typeRaw == "took" && kotlin.math.abs(existing.amountIqd - operationPrice) < 0.0001) {
            pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)
            return@withTransaction existing
        } else {
            throw DivergentPayloadConflictException("Divergent payload conflict for $businessTransactionId")
        }
    }

    val defaultNote = if (op.operationType.equals("ACTIVATION", ignoreCase = true)) "[VERIFIED ACTIVATION]" else "[VERIFIED RENEW]"
    val finalNote = if (!chargeNote.isNullOrBlank()) chargeNote else defaultNote
    val savedAcc = saveAccountInternal(localAcc.copy(currentPriceIqd = operationPrice))
    val chargeEntry = addDebtInternal(savedAcc.id, operationPrice, finalNote, businessTransactionId)
    pendingDao.updateStatus(businessTransactionId, "COMPLETED", System.currentTimeMillis(), null)
    chargeEntry
}
```

---

## 4. STEP 2 — FINAL FINANCIAL OUTCOME SEMANTICS

Step 2 formalizes four strict execution outcome categories:

```text
┌───────────────────────────┬───────────────────────────┬───────────────────────────┬───────────────────────────┐
│     A. NOT-DISPATCHED     │    B. BUSINESS-FAILURE    │        C. SUCCESS         │ D. UNKNOWN-AFTER-DISPATCH │
├───────────────────────────┼───────────────────────────┼───────────────────────────┼───────────────────────────┤
│ Claim rejected            │ Explicit API rejection    │ Confirmed HTTP 200        │ Socket timeout,           │
│ (rowsAffected == 0)       │ (e.g. invalid password,   │ or approved               │ disconnect, HTTP 5xx,     │
│ or pre-dispatch           │ business rule rejection). │ accountStatement          │ or process death.         │
│ validation error.         │                           │ compound correlation.     │                           │
├───────────────────────────┼───────────────────────────┼───────────────────────────┼───────────────────────────┤
│ DB Status: PENDING / none │ DB Status: FAILED         │ DB Status: COMPLETED      │ DB Status: PENDING        │
│ dispatchClaimCount: 0     │ (Terminal)                │ (Terminal)                │ dispatchClaimCount: 1     │
├───────────────────────────┼───────────────────────────┼───────────────────────────┼───────────────────────────┤
│ Ledger Mutation: NONE     │ Ledger Mutation: NONE     │ Ledger Mutation: ATOMIC   │ Ledger Mutation: NONE     │
├───────────────────────────┼───────────────────────────┼───────────────────────────┼───────────────────────────┤
│ Action: Propagate UI info │ Action: Finished          │ Action: Finished          │ Action: Await correlation │
└───────────────────────────┴───────────────────────────┴───────────────────────────┴───────────────────────────┘
```

### 4.2 Typed Gateway Outcome Exception Architecture
**String matching on `Exception.message` is strictly prohibited.**

To guarantee deterministic, type-safe outcome classification without modifying existing `EarthlinkGateway` method signatures, the Gateway / `safeApiCall` layer (`Repositories.kt:113–170`) is structured to emit typed domain exceptions:

```kotlin
// Core Domain Exception Hierarchy (com.example.core.network)
sealed class EarthlinkGatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Transport Uncertainty: Outcome is unknown. Gateway call may or may not have reached the ISP.
 * Handled as: UNKNOWN / INCONCLUSIVE -> PENDING (dispatchClaimCount = 1). Zero ledger mutation.
 */
class EarthlinkTransportException(
    message: String,
    cause: Throwable? = null
) : EarthlinkGatewayException(message, cause)

/**
 * Definitive Business Rejection: ISP explicitly processed and rejected the request.
 * Handled as: EXPLICIT_FAILURE -> FAILED. Zero ledger mutation.
 */
class EarthlinkBusinessException(
    val statusCode: Int? = null,
    val errorMessage: String,
    cause: Throwable? = null
) : EarthlinkGatewayException(errorMessage, cause)

/**
 * Authentication / Session Failure: Session expired or invalid credentials.
 * Handled as: EXPLICIT_FAILURE -> FAILED. Token cleared. Zero ledger mutation.
 */
class EarthlinkAuthException(
    message: String,
    cause: Throwable? = null
) : EarthlinkGatewayException(message, cause)
```

### 4.3 REQUIRED CONCRETE DIFF FOR LEGACY RAW-EXCEPTION MUTATION PATHS
**This is an implementation requirement, not descriptive prose. The following two Gateway mutation paths MUST be changed so their mutation API calls pass through the typed `safeApiCall` path defined above. Do not rely on `Exception.message` matching.**

#### `Repositories.kt` — `createTestUser` (current implementation around the existing direct API call)
Replace the current direct `try/catch` around `apiService.createTestUser(...)` with:

```kotlin
val result = safeApiCall {
    apiService.createTestUser(
        mobile = phone,
        accountIndex = accountIndex,
        userId = username,
        displayName = fullName,
        affiliateIndex = affiliateIndex,
        userPass = userPass
    )
}
```

The remaining response validation must preserve the existing typed outcome contract:
- valid successful payload -> return `userPass`;
- successful response without the required `userIndex` -> typed business failure;
- explicit API rejection -> `EarthlinkBusinessException`;
- transport / timeout / 5xx / decode uncertainty -> `EarthlinkTransportException`;
- 401 -> `EarthlinkAuthException`;
- `CancellationException` -> rethrow unchanged.

#### `Repositories.kt` — `createUserUsingDeposit` (current implementation around the existing direct API call)
Replace the current direct `try/catch` around `apiService.createUserUsingDeposit(...)` with:

```kotlin
val result = safeApiCall {
    apiService.createUserUsingDeposit(
        mobile = phone,
        accountIndex = accountIndex,
        userId = username,
        displayName = fullName,
        affiliateIndex = affiliateIndex,
        userPass = userPass,
        depositPass = depositPassword,
        customerId = customerId
    )
}
```

The remaining response validation must preserve the same typed outcome contract:
- valid successful payload -> return `userPass`;
- successful response without the required `userIndex` -> typed business failure;
- explicit API rejection -> `EarthlinkBusinessException`;
- transport / timeout / 5xx / decode uncertainty -> `EarthlinkTransportException`;
- 401 -> `EarthlinkAuthException`;
- `CancellationException` -> rethrow unchanged.

**Acceptance condition:** a rejection from either mutation path MUST reach the typed `EarthlinkBusinessException` catch at the ViewModel call-site; it MUST NOT fall through to the generic `catch (Exception)` merely because the repository wrapped it as a raw `Exception`.

#### Deterministic Classification Mapping in `safeApiCall`:

| Source Error / Response Condition | Emitted Typed Exception | Target Outcome Classification |
| :--- | :--- | :--- |
| `java.io.IOException`, `SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `SSLHandshakeException` | `EarthlinkTransportException` | **`UNKNOWN_AFTER_DISPATCH`** (`PENDING`, `count=1`) |
| Retrofit `HttpException` with HTTP Status `500..599` | `EarthlinkTransportException` | **`UNKNOWN_AFTER_DISPATCH`** (`PENDING`, `count=1`) |
| Moshi `JsonDataException` on mutation response payload | `EarthlinkTransportException` | **`UNKNOWN_AFTER_DISPATCH`** (`PENDING`, `count=1`) |
| Retrofit `HttpException` with HTTP Status `400..499` (except 401) | `EarthlinkBusinessException(code, msg)` | **`BUSINESS_FAILURE`** (`FAILED`) |
| HTTP 200 with `ApiEnvelope.isSuccessful == false` or `result.isSuccessful == false` | `EarthlinkBusinessException(200, errorMsg)` | **`BUSINESS_FAILURE`** (`FAILED`) |
| HTTP 401 or `isSuccessful == false` with "Unauthorized" / "expired" | `EarthlinkAuthException(msg)` | **`BUSINESS_FAILURE`** (`FAILED` + Clear Token) |
| `kotlinx.coroutines.CancellationException` | Rethrown directly (`throw e`) | **`UNKNOWN_AFTER_DISPATCH`** (Preserves `count=1`) |

---

## 5. STEP 2 — `G1-F` CORRECTION & COMPOUND CORRELATION

### 5.1 Deletion of Offending Fallback (`Repositories.kt:1405–1415`)
* **Offending Code:**
  ```kotlin
  // Repositories.kt lines 1405-1415 -- STRICTLY DELETED PER G1-F
  } else {
      val ledger = resolvePendingOperationVerifiedSuccess(businessTransactionId, "[VERIFIED RENEW]")
      ...
  }
  ```
* **Correction Rule:**
  - Fallback is deleted entirely.
  - When `baselineExpirationDate` is null/blank, the resolver must execute the approved 4-tuple `accountStatement` compound correlation.

### 5.2 The 4-Tuple Compound Correlation Specification
```kotlin
suspend fun verifyRenewalViaStatement(
    op: PendingExternalOperation,
    gateway: EarthlinkGateway
): UnknownOutcomeResolutionResult {
    // Window: ±90 seconds centered at the immutable operation-intent createdAt timestamp
    val windowStart = op.createdAt - 90_000L
    val windowEnd = op.createdAt + 90_000L
    
    val statements = gateway.getAccountStatement(startIndex = 0, rowCount = 50, query = op.accountId)
    
    val matchingCandidates = statements.filter { item ->
        val itemTime = parseStatementTimestamp(item.occurredAt)
        item.operation.equals("Withdraw", ignoreCase = true) &&
        item.userID.equals(op.accountId, ignoreCase = true) &&
        Math.abs(item.withdrawalAmount - op.amountIqd.toDouble()) < 0.001 &&
        itemTime in windowStart..windowEnd
    }
    
    return when (matchingCandidates.size) {
        1 -> UnknownOutcomeResolutionResult.VERIFIED_SUCCESS
        0 -> UnknownOutcomeResolutionResult.INCONCLUSIVE
        else -> UnknownOutcomeResolutionResult.INCONCLUSIVE // Ambiguous multiple candidates
    }
}
```

---

## 6. STEP 2 — ACTIVATION RECOVERY SEMANTICS

* **SUSPENDED Subscriber Semantics:**  
  When activation recovery checks authoritative ISP subscriber state and finds status `SUSPENDED` (or username available):
  - This is treated as the **approved V1 operational non-execution signal for activation recovery**.
  - Transitions to `FAILED` via `resolvePendingOperationVerifiedFailure`.
  - Zero financial ledger materialization occurs.
* **ACTIVE Subscriber Semantics:**  
  Subscriber status `ACTIVE` alone is **never** proof of the specific historical operation.
  - The system executes `accountStatement` 4-tuple compound correlation.
  - Unique match $\rightarrow$ `VERIFIED_SUCCESS` $\rightarrow$ `COMPLETED`.
  - Ambiguous / Missing $\rightarrow$ `INCONCLUSIVE` (remains `PENDING`, zero ledger mutation).

### 6.2 Non-Financial Lifecycle Recovery: `verifyNonFinancialLifecycle`
Recovery of `TEST_USER` / `EXTEND` must remain fail-closed because present-day entity state is not historical proof of the interrupted operation:

```kotlin
suspend fun verifyNonFinancialLifecycle(
    op: PendingExternalOperation,
    gateway: EarthlinkGateway
): UnknownOutcomeResolutionResult {
    return try {
        when (op.operationType.uppercase()) {
            "TEST_USER" -> {
                // Negative evidence only: username still available proves the create did not execute.
                if (gateway.checkUsernameAvailable(op.accountId)) {
                    UnknownOutcomeResolutionResult.VERIFIED_FAILURE
                } else {
                    // Existing/taken/ACTIVE state may pre-date the interrupted operation.
                    UnknownOutcomeResolutionResult.INCONCLUSIVE
                }
            }
            "EXTEND" -> {
                val userIndex = op.accountId.toIntOrNull() ?: run {
                    val json = org.json.JSONObject(op.payloadJson)
                    json.optInt("userIndex", -1)
                }
                if (userIndex <= 0) return UnknownOutcomeResolutionResult.INCONCLUSIVE

                val detail = gateway.getUserDetail(userIndex)
                // Negative evidence only: still suspended/expired means this extend did not take effect.
                if (detail.accountStatus.equals("Suspended", ignoreCase = true) || detail.activeDaysLeft <= 0.0) {
                    UnknownOutcomeResolutionResult.VERIFIED_FAILURE
                } else {
                    // ACTIVE with remaining days may pre-date this interrupted EXTEND.
                    UnknownOutcomeResolutionResult.INCONCLUSIVE
                }
            }
            else -> UnknownOutcomeResolutionResult.INCONCLUSIVE
        }
    } catch (e: EarthlinkTransportException) {
        UnknownOutcomeResolutionResult.INCONCLUSIVE
    } catch (e: EarthlinkAuthException) {
        UnknownOutcomeResolutionResult.INCONCLUSIVE
    } catch (e: EarthlinkBusinessException) {
        if (e.statusCode == 404) UnknownOutcomeResolutionResult.VERIFIED_FAILURE
        else UnknownOutcomeResolutionResult.INCONCLUSIVE
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        UnknownOutcomeResolutionResult.INCONCLUSIVE
    }
}
```

**Direct-dispatch rule:** a definitive success response from the original `createTestUser` / `extendUser` HTTP call remains a valid success proof. The conservative rule above applies only to later recovery of an uncertain/lost outcome.

---

## 7. STEP 3 — FINAL STATE / CLAIM MODEL

```text
┌─────────────────┬────────────────────┬────────────────────────────────────────────────────────┐
│ Status          │ dispatchClaimCount │ Semantic Meaning & Allowed Actions                     │
├─────────────────┼────────────────────┼────────────────────────────────────────────────────────┤
│ PENDING         │ 0                  │ Fresh Intent: Eligible for atomic claimDispatch.       │
├─────────────────┼────────────────────┼────────────────────────────────────────────────────────┤
│ DISPATCHING     │ 1                  │ In-flight HTTP dispatch by the current local actor.   │
├─────────────────┼────────────────────┼────────────────────────────────────────────────────────┤
│ PENDING         │ 1                  │ Recovered / Inconclusive: BLOCKED from redispatch.     │
│ (Recovered)     │                    │ Eligible only for verification / resolution.           │
├─────────────────┼────────────────────┼────────────────────────────────────────────────────────┤
│ RESOLVING       │ 1                  │ In-flight verification; cold-start may recover it.    │
├─────────────────┼────────────────────┼────────────────────────────────────────────────────────┤
│ COMPLETED       │ 1                  │ Terminal: Verified success & materialized ledger.      │
├─────────────────┼────────────────────┼────────────────────────────────────────────────────────┤
│ FAILED          │ 0 or 1             │ Terminal: Verified failure; zero ledger mutations.     │
└─────────────────┴────────────────────┴────────────────────────────────────────────────────────┘
```

---

## 8. STEP 3 — SCHEMA, DAO & MIGRATION

### 8.1 Room Entity (`Models.kt:509–521`)
```kotlin
@Entity(
    tableName = "pending_external_operations",
    indices = [
        Index(value = ["operationIntentId"], unique = true),
        Index(value = ["businessTransactionId"], unique = true),
        Index(value = ["accountId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
@JsonClass(generateAdapter = true)
data class PendingExternalOperation(
    @PrimaryKey val businessTransactionId: String = java.util.UUID.randomUUID().toString(),
    val operationIntentId: String = java.util.UUID.randomUUID().toString(),
    val accountId: String,
    val operationType: String,
    val amountIqd: Long = 0L,
    val payloadJson: String = "{}",
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val verificationEvidence: String? = null,
    @ColumnInfo(defaultValue = "0") val dispatchClaimCount: Int = 0
)
```

### 8.2 Room Migration Recipe (Version 16 → 17)
Complete recipe for Room database schema upgrade:

1. **Database Version Bump (`AppDatabase.kt`):**
   ```kotlin
   @Database(
       entities = [
           LocalAccount::class,
           LocalLedgerEntry::class,
           PendingExternalOperation::class,
           // ...
       ],
       version = 17,
       exportSchema = true
   )
   ```

2. **Migration Definition (`AppDatabase.kt`):**
   ```kotlin
   val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
       override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
           db.execSQL("ALTER TABLE `pending_external_operations` ADD COLUMN `dispatchClaimCount` INTEGER NOT NULL DEFAULT 0")
        // Fail-closed policy for any rows that pre-date this migration.
        // This experimental window has no production users, but unresolved historical rows must never
        // become fresh-dispatchable by default when the new discriminator is introduced.
        db.execSQL("""
            UPDATE `pending_external_operations`
            SET `dispatchClaimCount` = 1
            WHERE `status` IN ('PENDING', 'DISPATCHING', 'RESOLVING')
        """)
       }
   }
   ```

3. **Migration Registration in Database Builder (`AppDatabase.kt` / Database Factory):**
   ```kotlin
   Room.databaseBuilder(context, AppDatabase::class.java, "earthlink_app.db")
       .addMigrations(
           // ... previous migrations ...
           MIGRATION_15_16,
           MIGRATION_16_17
       )
       .build()
   ```

### 8.3 DAO Claim, Transition & Recovery Queries (`AppDatabase.kt:368–422`)
```kotlin
@Dao
interface PendingExternalOperationDao {
    // ... existing queries ...

    /**
     * Atomic SQLite Dispatch Claim:
     * Claims first-dispatch ownership exclusively for a fresh intent (count = 0).
     */
    @Query("""
        UPDATE pending_external_operations
        SET status = 'DISPATCHING',
            dispatchClaimCount = dispatchClaimCount + 1,
            updatedAt = :now
        WHERE businessTransactionId = :businessTransactionId
          AND status = 'PENDING'
          AND dispatchClaimCount = 0
    """)
    suspend fun claimDispatch(businessTransactionId: String, now: Long = System.currentTimeMillis()): Int

    /**
     * Exact RESOLVING State Writer:
     * Claims a recovery-blocked PENDING operation (count = 1) for active multi-step verification.
     * Cold-start DISPATCHING/RESOLVING rows are reset to PENDING first.
     */
    @Query("""
        UPDATE pending_external_operations
        SET status = 'RESOLVING',
            updatedAt = :now
        WHERE businessTransactionId = :businessTransactionId
          AND status = 'PENDING'
          AND dispatchClaimCount = 1
    """)
    suspend fun transitionToResolving(businessTransactionId: String, now: Long = System.currentTimeMillis()): Int

    /**
     * Cold-Start Only Query: Discovers orphaned DISPATCHING records left by a previous dead process.
     */
    @Query("""
        SELECT * FROM pending_external_operations
        WHERE status IN ('DISPATCHING', 'RESOLVING')
          AND dispatchClaimCount = 1
          AND updatedAt < :processStartMs
        ORDER BY createdAt ASC
    """)
    suspend fun getOrphanedInFlightOperations(processStartMs: Long): List<PendingExternalOperation>

    @Query("""
        UPDATE pending_external_operations
        SET status = 'PENDING',
            updatedAt = :now
        WHERE businessTransactionId = :businessTransactionId
          AND status IN ('DISPATCHING', 'RESOLVING')
          AND dispatchClaimCount = 1
    """)
    suspend fun resetOrphanedInFlightToPending(businessTransactionId: String, now: Long = System.currentTimeMillis()): Int

    /**
     * Runtime Verification Sweep Query: Inspects only operations that have already been claimed
     * and preserved in PENDING/RESOLVING awaiting verification. Fresh intents (count=0) are excluded.
     */
    @Query("""
        SELECT * FROM pending_external_operations
        WHERE status = 'PENDING'
          AND dispatchClaimCount = 1
        ORDER BY createdAt ASC
    """)
    suspend fun getUnresolvedClaimedOperations(): List<PendingExternalOperation>
}
```

---

## 9. STEP 3 — PRODUCTION CALL-SITE GATES (ALL 4 SITES)

### 9.1 Canonical Dispatch Claim Repository Primitive
`claimDispatchAuthorization` operates directly on SQLite's ACID guarantees via the atomic conditional query:

```kotlin
// Repositories.kt
override suspend fun claimDispatchAuthorization(businessTransactionId: String): Boolean {
    val rowsAffected = pendingDao.claimDispatch(businessTransactionId, System.currentTimeMillis())
    return rowsAffected == 1
}
```

### 9.2 Same-Process UI Gesture Coalescing (Existing Lock Only)
The existing ViewModel `inflightAccountLocks` is retained solely to coalesce simultaneous taps for the same logical `(account, operationType)` action. Acquire it before generating a new intent ID, reject a second tap without creating another intent, and release it in `finally`. This is not the durable correctness boundary; SQLite `claimDispatch` remains authoritative across restart/process death.

```kotlin
val operationKey = "${username}:ACTIVATION"
val lock = inflightAccountLocks.getOrPut(operationKey) { kotlinx.coroutines.sync.Mutex() }
if (!lock.tryLock()) {
    _error.value = "Operation already in progress or awaiting verification."
    return@launch
}
try {
    // Generate the new operationIntentId only after the same-action coalescing gate.
    val opIntentId = java.util.UUID.randomUUID().toString()
    val businessTxId = "tx_" + opIntentId
    // Existing call-site body continues here.
} finally {
    lock.unlock()
}
```

### 9.3 CALL-SITE 1: `createUserUsingDeposit` [FINANCIAL MUTATION]
> **Required gate:** execute this call-site inside the existing same-process UI coalescing boundary defined in §9.2; do not generate a new `operationIntentId` until that gate is acquired.
```kotlin
// EarthlinkSearchViewModel.kt (createUserUsingDeposit)
val opIntentId = java.util.UUID.randomUUID().toString()
val businessTxId = "tx_" + opIntentId

// 1. Pre-dispatch Package Cost Validation (Fail-Closed)
val cost = try { gateway.getAccountCost(pkgIndex) } catch (e: Exception) { 0.0 }
val invalidCost = !cost.isFinite() || cost <= 0.0 || cost % 1.0 != 0.0 || cost % 250.0 != 0.0
if (invalidCost) {
    _error.value = "Failed to determine a valid IQD package cost. Operation aborted."
    return@launch // NOT-DISPATCHED: No intent recorded, zero claim
}
val exactAmountIqd = cost.toLong()

// 2. Durably record pending intent (idempotent insert)
localLedgerRepository.recordPendingOperation(
    PendingExternalOperation(
        businessTransactionId = businessTxId,
        operationIntentId = opIntentId,
        accountId = username,
        operationType = "ACTIVATION",
        amountIqd = exactAmountIqd,
        payloadJson = "{\"username\":\"$username\",\"phone\":\"$phone\",\"fullName\":\"$fullName\",\"pkgIndex\":$pkgIndex}",
        status = "PENDING",
        dispatchClaimCount = 0
    )
)

// 3. Atomic SQLite dispatch claim
val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
if (!claimGranted) {
    _error.value = "Operation is already processing or awaiting verification."
    return@launch
}

// 4. Authorized external HTTP dispatch
try {
    val generatedPass = gateway.createUserUsingDeposit(username, phone, fullName, pkgIndex, depositPassword)
    if (generatedPass != null) {
        localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[ACTIVATION SUCCESS]")
        _actionSuccess.value = "Subscriber $username created successfully.\nPassword: $generatedPass"
        audit.logAction("CREATE_USER_DEPOSIT", "USER", username, "Created subscriber successfully")
    } else {
        localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Creation returned null")
        _error.value = "Failed to create subscriber."
    }
} catch (e: EarthlinkBusinessException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
    _error.value = e.errorMessage
} catch (e: EarthlinkAuthException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
    _error.value = "Session expired. Please log in again."
} catch (e: EarthlinkTransportException) {
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
    _error.value = "Network uncertain. Operation stored for verification."
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
    _error.value = "Operation pending verification."
}
```

### 9.4 CALL-SITE 2: `refillUser` [FINANCIAL MUTATION]
> **Required gate:** execute this call-site inside the existing same-process UI coalescing boundary defined in §9.2; do not generate a new `operationIntentId` until that gate is acquired.
```kotlin
// EarthlinkSearchViewModel.kt (refillUser)
val opIntentId = java.util.UUID.randomUUID().toString()
val businessTxId = "tx_" + opIntentId

// 1. Pre-dispatch Price Validation (Fail-Closed)
// `price` is valid here only after Step 1 proves it comes from the authoritative
// production package-pricing path; presentation/cache-only price values are forbidden.
val authoritativePrice = price
if (authoritativePrice == null || !authoritativePrice.isFinite() || authoritativePrice <= 0.0 ||
    authoritativePrice % 1.0 != 0.0 || authoritativePrice % 250.0 != 0.0) {
    _error.value = "Invalid, non-authoritative, or missing package price. Operation aborted."
    return@launch // NOT-DISPATCHED: No intent recorded, zero claim
}
val exactAmountIqd = authoritativePrice.toLong()

// 2. Durably record pending intent (idempotent insert)
localLedgerRepository.recordPendingOperation(
    PendingExternalOperation(
        businessTransactionId = businessTxId,
        operationIntentId = opIntentId,
        accountId = userId,
        operationType = "REFILL",
        amountIqd = exactAmountIqd,
        payloadJson = "{\"userId\":\"$userId\",\"price\":$price,\"note\":\"$finalNote\"}",
        status = "PENDING",
        dispatchClaimCount = 0
    )
)

// 3. Atomic SQLite dispatch claim
val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
if (!claimGranted) {
    _error.value = "Operation is already processing or awaiting verification."
    return@launch
}

// 4. Authorized external HTTP dispatch
try {
    val success = gateway.refillUserDeposit(userId, depositPass)
    if (success) {
        localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[REFILL SUCCESS]")
        _actionSuccess.value = "Subscriber $userId renewed successfully."
        audit.logAction("REFILL_USER", "USER", userId, "Renewed subscription at price $exactAmountIqd")
    } else {
        localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Refill rejected by server")
        _error.value = "Renewal rejected by server."
    }
} catch (e: EarthlinkBusinessException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
    _error.value = e.errorMessage
} catch (e: EarthlinkAuthException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
    _error.value = "Session expired. Please log in again."
} catch (e: EarthlinkTransportException) {
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
    _error.value = "Network uncertain. Operation stored for verification."
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
    _error.value = "Operation pending verification."
}
```

### 9.5 CALL-SITE 3: `createTestUser` [NON-FINANCIAL LIFECYCLE OPERATION]
> **Required gate:** execute this call-site inside the existing same-process UI coalescing boundary defined in §9.2; do not generate a new `operationIntentId` until that gate is acquired.
```kotlin
// EarthlinkSearchViewModel.kt (createTestUser)
val opIntentId = java.util.UUID.randomUUID().toString()
val businessTxId = "tx_" + opIntentId

// 1. Durably record non-financial intent (operationType = "TEST_USER", amountIqd = 0L)
localLedgerRepository.recordPendingOperation(
    PendingExternalOperation(
        businessTransactionId = businessTxId,
        operationIntentId = opIntentId,
        accountId = username,
        operationType = "TEST_USER",
        amountIqd = 0L,
        payloadJson = "{\"username\":\"$username\",\"phone\":\"$phone\",\"fullName\":\"$fullName\",\"pkgIndex\":$pkgIndex,\"isTest\":true}",
        status = "PENDING",
        dispatchClaimCount = 0
    )
)

// 2. Atomic SQLite dispatch claim
val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
if (!claimGranted) {
    _error.value = "Operation is already processing or awaiting verification."
    return@launch
}

// 3. Authorized external HTTP dispatch
try {
    val generatedPass = gateway.createTestUser(username, phone, fullName, pkgIndex)
    if (generatedPass != null) {
        localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[TEST_USER SUCCESS]")
        _actionSuccess.value = "Test subscriber $username created successfully.\nPassword: $generatedPass"
        audit.logAction("CREATE_TEST_USER", "USER", username, "Created test user successfully")
    } else {
        localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Test user creation returned null")
        _error.value = "Test user creation failed."
    }
} catch (e: EarthlinkBusinessException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
    _error.value = e.errorMessage
} catch (e: EarthlinkAuthException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
    _error.value = "Session expired. Please log in again."
} catch (e: EarthlinkTransportException) {
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
    _error.value = "Network uncertain. Operation stored for verification."
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
    _error.value = "Operation pending verification."
}
```

### 9.6 CALL-SITE 4: `extendUser` [NON-FINANCIAL LIFECYCLE OPERATION]
> **Required gate:** execute this call-site inside the existing same-process UI coalescing boundary defined in §9.2; do not generate a new `operationIntentId` until that gate is acquired.
```kotlin
// EarthlinkSearchViewModel.kt (extendUser)
val opIntentId = java.util.UUID.randomUUID().toString()
val businessTxId = "tx_" + opIntentId

// 1. Durably record non-financial intent (operationType = "EXTEND", amountIqd = 0L)
localLedgerRepository.recordPendingOperation(
    PendingExternalOperation(
        businessTransactionId = businessTxId,
        operationIntentId = opIntentId,
        accountId = userIndex.toString(),
        operationType = "EXTEND",
        amountIqd = 0L,
        payloadJson = "{\"userIndex\":$userIndex}",
        status = "PENDING",
        dispatchClaimCount = 0
    )
)

// 2. Atomic SQLite dispatch claim
val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
if (!claimGranted) {
    _error.value = "Operation is already processing or awaiting verification."
    return@launch
}

// 3. Authorized external HTTP dispatch
try {
    val extended = gateway.extendUser(userIndex)
    if (extended) {
        localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[EXTEND SUCCESS]")
        _actionSuccess.value = "Subscriber extended successfully."
        audit.logAction("EXTEND_USER", "USER", userIndex.toString(), "Extended subscriber successfully")
    } else {
        localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Extension returned false")
        _error.value = "Extension rejected by server."
    }
} catch (e: EarthlinkBusinessException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
    _error.value = e.errorMessage
} catch (e: EarthlinkAuthException) {
    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
    _error.value = "Session expired. Please log in again."
} catch (e: EarthlinkTransportException) {
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
    _error.value = "Network uncertain. Operation stored for verification."
} catch (e: Exception) {
    if (e is kotlinx.coroutines.CancellationException) throw e
    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
    _error.value = "Operation pending verification."
}
```

---

## 10. STEP 3 — COLD-START & RUNTIME SWEEP SPECIFICATION

### 10.1 Recovery Execution Flow (`EarthlinkApp.kt:116–123`)
On cold start, capture `processStartMs` once and use it as the previous-process boundary. Startup recovery remains asynchronous, but it can only classify rows last updated before this process started:

```kotlin
// EarthlinkApp.kt
val processStartMs = System.currentTimeMillis()
CoroutineScope(Dispatchers.IO).launch {
    try {
        localLedgerRepository.recoverColdStartOrphanedOperations(earthlinkGateway, processStartMs)
        localLedgerRepository.sweepAndResolvePendingOperations(earthlinkGateway)
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        android.util.Log.e("EarthlinkApp", "Cold-start recovery sweep failed", e)
    }
}
```

```kotlin
// Repositories.kt - Cold-Start In-Flight Discovery
override suspend fun recoverColdStartOrphanedOperations(
    gateway: EarthlinkGateway,
    processStartMs: Long
) {
    val orphaned = pendingDao.getOrphanedInFlightOperations(processStartMs)
    if (orphaned.isEmpty()) return

    for (op in orphaned) {
        try {
            // Previous-process in-flight state is reset to recovery-blocked PENDING(count=1).
            // The external mutation is NEVER redispatched.
            val reset = pendingDao.resetOrphanedInFlightToPending(
                op.businessTransactionId,
                System.currentTimeMillis()
            )
            if (reset != 1) continue

            val resolution = verifyAndResolvePendingOperation(
                businessTransactionId = op.businessTransactionId,
                gateway = gateway,
                baselineExpirationDate = null
            )
            android.util.Log.i("ColdRecovery", "Orphaned op ${op.businessTransactionId} resolved: ${resolution.result}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            resolvePendingOperationInconclusive(
                businessTransactionId = op.businessTransactionId,
                diagnostic = "Cold-start recovery verification interrupted: ${e.message}"
            )
        }
    }
}

// Runtime sweep: only recovery-blocked PENDING(count=1) is eligible.
override suspend fun getUnresolvedPendingOperations(): List<PendingExternalOperation> {
    return pendingDao.getUnresolvedClaimedOperations()
}
```

### 10.2 Exact `RESOLVING` State Transition Rules & Execution Primitive
* **Definitive HTTP Success:** Transitions directly `DISPATCHING → COMPLETED` (inside `resolvePendingOperationVerifiedSuccess`). Never writes `RESOLVING`.
* **Definitive HTTP Rejection:** Transitions directly `DISPATCHING → FAILED` (inside `resolvePendingOperationVerifiedFailure`). Never writes `RESOLVING`.
* **Unknown Outcome / Drop:** Transitions `DISPATCHING → PENDING` (`count=1`) (inside `resolvePendingOperationInconclusive`). Never writes `RESOLVING`.
* **Active Multi-Step Verification / Reconciliation:** Written **exclusively** when `verifyAndResolvePendingOperation` starts active verification of an unresolved operation (`PENDING / DISPATCHING (count=1) → RESOLVING`):

```kotlin
// Repositories.kt - verifyAndResolvePendingOperation execution primitive
override suspend fun verifyAndResolvePendingOperation(
    businessTransactionId: String,
    gateway: EarthlinkGateway,
    baselineExpirationDate: String?
): PendingOperationResolution {
    // 1. Guarded transition: claims only recovery-blocked PENDING(count=1) -> RESOLVING. Cold-start orphans are reset first.
    val rows = pendingDao.transitionToResolving(businessTransactionId, System.currentTimeMillis())
    if (rows == 0) {
        // Already resolving, completed, or failed by another worker
        val current = pendingDao.getByBusinessTransactionId(businessTransactionId)
        return PendingOperationResolution(current?.status ?: "UNKNOWN", "Concurrent resolution in progress")
    }

    val op = pendingDao.getByBusinessTransactionId(businessTransactionId)
        ?: return PendingOperationResolution("NOT_FOUND", "Missing record")

    // 2. Perform authoritative verification check
    val resolution = when (op.operationType) {
        "ACTIVATION" -> verifyActivation(op, gateway)
        "REFILL", "RENEWAL" -> verifyRenewalViaStatement(op, gateway)
        "TEST_USER", "EXTEND" -> verifyNonFinancialLifecycle(op, gateway)
        else -> UnknownOutcomeResolutionResult.INCONCLUSIVE
    }

    // 3. Resolve from RESOLVING to terminal or revert to PENDING (count=1)
    return when (resolution) {
        UnknownOutcomeResolutionResult.VERIFIED_SUCCESS -> {
            resolvePendingOperationVerifiedSuccess(businessTransactionId, "[VERIFIED ${op.operationType}]")
            PendingOperationResolution("COMPLETED", "Verified successfully on server")
        }
        UnknownOutcomeResolutionResult.VERIFIED_FAILURE -> {
            resolvePendingOperationVerifiedFailure(businessTransactionId, "Verified operation non-execution on server")
            PendingOperationResolution("FAILED", "Verified non-execution")
        }
        UnknownOutcomeResolutionResult.INCONCLUSIVE -> {
            resolvePendingOperationInconclusive(businessTransactionId, "Verification inconclusive: preserved for future sweep")
            PendingOperationResolution("PENDING", "Verification inconclusive")
        }
    }
}
```

---

## 11. TIMESTAMP PROVENANCE & CORRELATION ANCHOR

### Execution Order & Timestamp Deltas
```text
t0: User initiates action -> Intent created (opIntentId)
t1: recordPendingOperation() -> INSERT (createdAt = t1, updatedAt = t1, dispatchClaimCount = 0)
t2: claimDispatch()          -> UPDATE (updatedAt = t2, dispatchClaimCount = 1, status = 'DISPATCHING')
t3: gateway.execute()        -> HTTP Socket transmission
t4: Response / Timeout / Interruption
t5: Recovery verification executed (minutes or hours later)
```

* **Correlation Anchor Property:** `createdAt` ($t_1$) is the immutable, durable operation-intent correlation anchor recorded in SQLite at the moment of intent creation.
* **Authoritative Anchor Semantics:** `createdAt` is the approved V1 correlation anchor, used as a reliable baseline proxy for dispatch initiation because `recordPendingOperation → claimDispatch → gateway call` executes synchronously in the same foreground flow. It is not a literal network-transmission timestamp, but provides a deterministic immutable anchor. The $\pm90\text{s}$ window is the frozen V1 authority correlation heuristic.
* **Why `createdAt` is Used:** `createdAt` ($t_1$) is immutable in SQLite. If recovery runs at $t_5$ (e.g. 15 minutes later), `updatedAt` will have drifted, but `createdAt` permanently records the exact initiation timestamp.
* **Window Formula:** $\text{Window} = \left[\text{createdAt} - 90\,000\text{ ms},\; \text{createdAt} + 90\,000\text{ ms}\right]$.

---

## 12. MATHEMATICAL PROOF OF ANTI-REDISPATCH

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                    MATHEMATICAL PROOF OF ANTI-REDISPATCH                     │
├──────────────────────────────────────────────────────────────────────────────┤
│ 1. Initial State: dispatchClaimCount = 0.                                    │
│ 2. claimDispatch() requires dispatchClaimCount = 0 and executes atomic:      │
│    dispatchClaimCount = dispatchClaimCount + 1 (transitions to 1).          │
│ 3. On failure, timeout, crash, or inconclusive outcome:                      │
│    resolvePendingOperationInconclusive() updates status to 'PENDING',        │
│    leaving dispatchClaimCount = 1 strictly unchanged.                        │
│ 4. No DAO method contains logic to decrement or reset dispatchClaimCount.    │
│ 5. Any subsequent claimDispatch() on the same intent executes:               │
│    WHERE status = 'PENDING' AND dispatchClaimCount = 0                       │
│    which evaluates to FALSE (dispatchClaimCount is 1), returning             │
│    rowsAffected = 0.                                                         │
│ 6. CONCLUSION: Blind redispatch of the same operationIntentId is impossible. │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 13. COMPREHENSIVE SCENARIO VALIDATION

* **SCENARIO A — Activate $\rightarrow$ Internet Dies $\rightarrow$ App Reopened 15m Later (`VERIFIED`):**
  Intent recorded ($t_0$) $\rightarrow$ `claimDispatch` succeeds (`count=1`) $\rightarrow$ HTTP lost $\rightarrow$ Inconclusive sets `status=PENDING` (`count=1`) $\rightarrow$ App reopened 15m later $\rightarrow$ Cold recovery checks subscriber:
  - If `SUSPENDED` $\rightarrow$ Approved V1 operational non-execution signal $\rightarrow$ `resolvePendingOperationVerifiedFailure` $\rightarrow$ `FAILED` (zero ledger charge).
  - If `ACTIVE` $\rightarrow$ Runs `accountStatement` compound correlation centered at $t_0 \pm 90\text{s}$. If matched $\rightarrow$ `COMPLETED` (ledger charged once); if missing/ambiguous $\rightarrow$ remains `PENDING` (no ledger charge).
* **SCENARIO B — Explicit API Rejection (`VERIFIED`):**
  `claimDispatch` succeeds $\rightarrow$ API returns error code $\rightarrow$ `resolvePendingOperationVerifiedFailure` $\rightarrow$ `FAILED` (zero ledger mutation, no verification loops).
* **SCENARIO C — Definitive HTTP Success (`VERIFIED`):**
  `claimDispatch` succeeds $\rightarrow$ API returns 200 OK $\rightarrow$ Directly invokes `resolvePendingOperationVerifiedSuccess` $\rightarrow$ `COMPLETED` (single atomic transaction, no intermediate `RESOLVING` churn).
* **SCENARIO D — Two Concurrent UI Taps (`VERIFIED`):**
  Existing ViewModel `inflightAccountLocks` is acquired before UUID generation. Tap A creates the single logical intent and proceeds to SQLite `claimDispatch`; Tap B is rejected before creating a second intent. The UI lock is only gesture coalescing; SQLite remains the durable correctness boundary.
* **SCENARIO E — Coroutine Cancellation After Claim (`VERIFIED`):**
  `claimDispatch` succeeds (`count=1`) $\rightarrow$ Coroutine cancelled $\rightarrow$ `CancellationException` rethrown without calling `markPendingOperationFailed` $\rightarrow$ Record remains `count=1` $\rightarrow$ Zero false `FAILED`, zero blind redispatch.
* **SCENARIO F — Unrelated Later Operation Protection (`VERIFIED`):**
  User performs unrelated refill 10 minutes later $\rightarrow$ Later transaction has timestamp $t_0 + 600\text{s}$ $\rightarrow$ Compound correlation for old intent checks $[t_0 - 90\text{s}, t_0 + 90\text{s}]$ $\rightarrow$ Later transaction timestamp falls outside window $\rightarrow$ Zero false attribution.
* **SCENARIO G — Current-Process Startup Race (`VERIFIED`):**
  Process starts at `processStartMs` $\rightarrow$ a current-process claim updates `updatedAt >= processStartMs` $\rightarrow$ asynchronous orphan query excludes it $\rightarrow$ no false recovery or verification.
* **SCENARIO H — Non-Financial Recovery Without Historical Evidence (`VERIFIED`):**
  `TEST_USER`/`EXTEND` crashes after dispatch $\rightarrow$ current entity is ACTIVE/existing $\rightarrow$ recovery stays `INCONCLUSIVE` unless operation-specific historical evidence exists; no financial ledger entry and no blind redispatch.

---

## 14. G1 CLOSURE BACKLOG (EMBEDDED LIVE TRACKING)

```text
[CLOSED] G1-B: Response model mismatch for newuserdeposit -> Verified in Pre-Wave-1
[CLOSED] G1-C: Missing persisted amount for activation -> Verified in Pre-Wave-1
[CLOSED] G1-G: Missing userIndex preservation -> Verified in Pre-Wave-1
[CLOSED] G1-H: Presentation cache crossing into verification -> Verified in Pre-Wave-1
[CLOSED] G1-K: Verified success without financial target -> Verified in Pre-Wave-1
[CLOSED] G1-L: Response model mismatch for newtestuser -> Verified in Pre-Wave-1
[CLOSED] G1-M: Cached subscriber data in recovery -> Verified in Pre-Wave-1

[ACTIVE - STEP 2] G1-A: Network timeout collapsed to FAILED -> Fixed via Inconclusive Classification
[ACTIVE - STEP 2] G1-D: State-only proof inference -> Fixed via G1-F Fallback Deletion
[ACTIVE - STEP 2] G1-F: Renewal state-only fallback -> Fixed via accountStatement 4-Tuple Heuristic
[ACTIVE - STEP 2] G1-I: Non-deterministic statement correlation -> Fixed via 4-Tuple Window Match
[ACTIVE - STEP 2] G1-Amount-Fallback: Silent 40000.0 catch fallbacks -> Fixed via Fail-Closed NOT-DISPATCHED
[ACTIVE - STEP 2] G1-Typed-Outcome: Generic Exception wrapping in safeApiCall -> Fixed via Typed Gateway Exceptions Hierarchy

[ACTIVE - STEP 3] G1-E: Terminal success outside financial materialization -> Fixed via Single Materializer
[ACTIVE - STEP 3] G1-J: RESOLVING state runtime divergence -> Fixed via Explicit Transition
[ACTIVE - STEP 3] G1-Crash: Orphaned DISPATCHING discovery -> Fixed via Cold-Start Recovery Reader
[ACTIVE - STEP 3] G1-Startup-Race: Cold-start orphan discovery excludes rows updated in the current process via `processStartMs`.
[ACTIVE - STEP 3] G1-DoubleTap: Same-process concurrent taps cannot create separate intents for the same logical operation; existing ViewModel lock is UI-only coalescing.
[ACTIVE - STEP 2/3] G1-NonFinancial-Recovery: Current ACTIVE state is not historical proof for TEST_USER/EXTEND; positive recovery requires operation-specific evidence or remains INCONCLUSIVE.
[ACTIVE - STEP 2/3] G1-IQD-Amount: Reject non-finite/fractional/non-250 denomination financial amounts before persistence/claim.

[DEFERRED] 5A.8 Manual-Review Queue UI -> Deferred to Post-Step 3 UI phase
[DEFERRED] ViewModel Architecture Extraction -> Deferred to Step 5
[DEFERRED] Long/Double Financial Migration -> Deferred to Data Modernization phase
[DEFERRED] Demo Mode Removal -> Deferred to Wave 2
```

### 14.1 WAVE 1 P0 DATA-SAFETY SCOPE CLOSURE

| Area / Gate | V1 Data-Affecting? | Covered by Step 1–3? | Status | Required Action |
| :--- | :---: | :---: | :--- | :--- |
| Durable dispatch claim / anti-redispatch | YES / P0 | YES | CLOSED IN SPEC | Implement + TEST-01/02/03 |
| Hard-crash `DISPATCHING` / `RESOLVING` recovery | YES / P0 | YES | CLOSED IN SPEC | `processStartMs` + TEST-13/18/19 |
| Financial vs non-financial separation | YES / P0 | YES | CLOSED IN SPEC | Strict `operationType` branches |
| G1-F state-only renewal fallback | YES / P0 | YES | CLOSED IN SPEC | Remove fallback + TEST-07 |
| AccountStatement 4-tuple correlation | YES / P0 | YES | CLOSED IN SPEC | Unique match only |
| Typed outcome classification | YES / P0 | YES | CLOSED IN SPEC | Domain exceptions + TEST-14/15 |
| Amount fallback / IQD integrity | YES / P0 | YES | CLOSED IN SPEC | Fail-closed + TEST-16/22 |
| Same-process duplicate tap | YES / P0 | YES | CLOSED IN SPEC | Existing UI coalescer + TEST-20 |
| Non-financial recovery historical proof | YES / P0 | YES | CLOSED IN SPEC | Positive evidence required + TEST-21 |
| Backup / FK history protection | YES / P0 | BASELINE | CLOSED | Preserve existing baseline |

> **Scope invariant:** Items deferred by the broader Wave 1 authority remain deferred only where they do not block the current single-device V1 Data-Safety Freeze. Final operational guarantees still require implementation evidence and adversarial certification.

---

## 15. 5-WHYS ROOT CAUSE ANALYSES

### 15.1 Blocker A: Sweep vs. In-Flight Dispatch Race
1. *Why did sweeps race active dispatches?* Background `SyncWorker` and foreground ViewModels ran concurrently without shared locking.
2. *Why were locks not shared?* ViewModel used `inflightAccountLocks` (RAM) while Worker used `repositoryAccountLocks` (RAM).
3. *Why were locks divided?* Concurrency was added ad-hoc per class rather than unified at the data layer.
4. *Why was data layer not the lock owner?* SQLite single-writer atomic claim was never built for `pending_external_operations`.
5. *Root Cause Fix:* Implement SQLite hardware-atomic claim (`claimDispatch`) and restrict in-flight recovery to the cold-start snapshot boundary.

### 15.2 Blocker B: Fresh vs. Recovered `PENDING` Identity Gap
1. *Why could recovered operations be blindly redispatched?* SQLite held `status = 'PENDING'` for both fresh and recovered records.
2. *Why did recovered operations revert to PENDING?* To keep them in the verification sweep without inventing a separate permanent outcome state.
3. *Why could SQLite not distinguish them?* Schema had no durable claim counter or attempt marker.
4. *Why was no counter added?* Initial design relied on transient ViewModel locks to prevent repeated clicks.
5. *Root Cause Fix:* Add `dispatchClaimCount: Int NOT NULL DEFAULT 0` to SQLite and make `claimDispatch` require `count=0`.

### 15.3 Blocker C: `G1-F` State-Only Renewal Fallback Defect
1. *Why did sweeps falsely charge accounts on renewal?* `verifyAndResolvePendingOperation` had a fallback when `baselineExpirationDate == null`.
2. *Why was the fallback added?* Sweeps did not have access to the pre-operation expiration date.
3. *Why did fallback infer success?* It assumed that if the subscriber exists on ISP, the renewal must have worked.
4. *Why was this assumption flawed?* Subscriber existence is not proof of this specific historical renewal.
5. *Root Cause Fix:* Delete the fallback and require the approved `accountStatement` 4-tuple correlation.

### 15.4 Blocker D: Cold-Start Recovery Race
1. *Why could startup recovery misclassify a live claim?* Recovery is asynchronous from `Application.onCreate`.
2. *Why is that unsafe?* A current-process UI claim can become `DISPATCHING` / `RESOLVING` before the orphan query runs.
3. *Root Cause Fix:* Capture `processStartMs` once and recover only rows with `updatedAt < processStartMs`; runtime workers never inspect in-flight states.

### 15.5 Blocker E: Same-Process Double-Tap Intent Duplication
1. *Why could two taps dispatch twice?* Each tap can create a new UUID before SQLite compares the second intent to the first.
2. *Why is SQLite alone insufficient?* The atomic claim is per intent, not per user gesture.
3. *Root Cause Fix:* Retain the existing ViewModel lock only as same-action UI coalescing before intent creation; SQLite remains the durable correctness boundary.

### 15.6 Blocker F: Non-Financial Current-State False Success
1. *Why could recovery mark `TEST_USER`/`EXTEND` successful?* Current ACTIVE/existing state was treated as historical proof.
2. *Why is that invalid?* The entity may have existed before the interrupted operation.
3. *Root Cause Fix:* Positive recovery requires operation-specific historical evidence; current state alone yields `INCONCLUSIVE`. Negative evidence may still yield `VERIFIED_FAILURE`.

### 15.7 Blocker G: Invalid IQD Amount Normalization
1. *Why can `Double.toLong()` be unsafe?* It silently truncates fractional values and accepts invalid denominations.
2. *Why does this matter?* The persisted charge can differ from the authoritative package price.
3. *Root Cause Fix:* Validate finite, whole IQD and 250-denomination before converting to `Long`; otherwise abort pre-dispatch.

## 16. MASTER TEST MATRIX

| Test ID | Test Method Name | Invariant Tested |
| :--- | :--- | :--- |
| **`TEST-01`** | `testFreshIntentClaimSuccess` | `claimDispatch` on fresh intent (`count=0`) returns `1` and sets `count=1`. |
| **`TEST-02`** | `testConcurrentClaimsMutualExclusion` | 2 concurrent coroutines on same ID $\rightarrow$ exactly one gets `1`, second gets `0`. |
| **`TEST-03`** | `testRecoveredIntentClaimBlocked` | Inconclusive intent (`count=1`) returns `0` on `claimDispatch`; Gateway is not called. |
| **`TEST-04`** | `testRealRoomRestartDurability` | Room DB instance closed and reopened; `count=1` and `DISPATCHING` status intact. |
| **`TEST-05`** | `testCancellationAfterClaimSafety` | Coroutine cancelled mid-flight does not write `FAILED` and preserves `count=1`. |
| **`TEST-06`** | `testDefinitiveSuccessBypassesResolving` | Gateway 200 OK transitions directly `DISPATCHING → COMPLETED`. |
| **`TEST-07`** | `testG1FFallbackDeleted` | Null baseline expiration does NOT auto-complete; routes to statement check. |
| **`TEST-08`** | `testActivationSuspendedResolvesFailure` | Suspended subscriber status on recovery transitions directly to `FAILED` with zero ledger. |
| **`TEST-09`** | `testAccountStatementExactMatchSuccess` | Matching 4-tuple within $\pm90\text{s}$ resolves `VERIFIED_SUCCESS` + ledger entry. |
| **`TEST-10`** | `testAccountStatementAmbiguousRemainsInconclusive` | 2 matching statement rows resolve `INCONCLUSIVE` + zero ledger entry. |
| **`TEST-11`** | `testUnrelatedLaterOperationWindowRejection` | Transaction at $+600\text{s}$ is rejected by the $\pm90\text{s}$ window filter. |
| **`TEST-12`** | `testBackupRestoreSmokeRegression` | Full backup/restore smoke run passes 100% green without lock deadlock. |
| **`TEST-13`** | `testColdStartOrphanedDispatchingRecovery` | Proves persistence/recovery boundary with fresh Room instance across DB closure/reopening. |
| **`TEST-14`** | `testTypedBusinessRejectionResolvesFailed` | Server rejection throws EarthlinkBusinessException -> transitions directly to FAILED (zero ledger). |
| **`TEST-15`** | `testTypedTransportUncertaintyResolvesInconclusive` | Socket timeout / 5xx throws EarthlinkTransportException -> transitions to PENDING (count=1, zero ledger). |
| **`TEST-16`** | `testPriceFetchFailureAbortsAsNotDispatched` | Price fetch failure aborts before record/claim; zero Gateway call; existing records uncorrupted. |
| **`TEST-17`** | `testMigration16To17LegacyUnresolvedRecordsClassifiedAsClaimed` | Proves that pre-existing PENDING/DISPATCHING/RESOLVING rows receive dispatchClaimCount = 1 during Room migration. |
| **`TEST-18`** | `testColdStartOrphanedResolvingRecovery` | RESOLVING row orphaned by process death is discovered, reset to PENDING(count=1), then verified without redispatch. |
| **`TEST-19`** | `testColdStartRecoveryIgnoresCurrentProcessClaims` | A DISPATCHING/RESOLVING row updated after `processStartMs` is not treated as a previous-process orphan. |
| **`TEST-20`** | `testConcurrentUiTapCoalescingCreatesSingleIntent` | Same logical `(account, operationType)` double tap creates one intent; second tap is rejected before UUID/record creation. |
| **`TEST-21`** | `testNonFinancialRecoveryCurrentStateAloneRemainsInconclusive` | Existing ACTIVE state without operation-specific historical evidence never auto-completes TEST_USER/EXTEND. |
| **`TEST-22`** | `testFinancialAmountValidationRejectsInvalidIqd` | Fractional, non-250-denomination, non-finite, or non-positive cost/price aborts before record/claim/dispatch. |

---

## 17. REGRESSION & SAFETY GATES

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                      MANDATORY STEP COMPLETION GATES                        │
├─────────────────┬───────────────────────────────────────────────────────────┤
│ Financial Gate  │ Zero Gateway calls without rowsAffected == 1.             │
│                 │ Zero ledger materializations on explicit failure.         │
│                 │ Zero blind redispatches under the same operationIntentId; zero same-action duplicate intents due to concurrent UI taps.     │
├─────────────────┼───────────────────────────────────────────────────────────┤
│ Durability Gate │ Fresh Room instance reopening proves claim durability.    │
├─────────────────┼───────────────────────────────────────────────────────────┤
│ Verification    │ G1-F fallback deleted; zero state-only ledger charges.    │
├─────────────────┼───────────────────────────────────────────────────────────┤
│ Shared Sync     │ Backup / Restore / Sync regression tests 100% green.      │
└─────────────────┴───────────────────────────────────────────────────────────┘
```

---

## 18. DEFINITION OF DONE (DoD)

* **Step 1 DoD:** Call-Site Inventory verified against all 4 financial paths and offline ledger writers; zero production code modified in Step 1.
* **Step 2 DoD:** Exception-to-`FAILED` collapse removed; `G1-F` renewal fallback deleted; `accountStatement` 4-tuple correlation implemented; `SUSPENDED`/`ACTIVE` recovery semantics proven.
* **Step 3 DoD:** `dispatchClaimCount` added via `MIGRATION_16_17` with fail-closed legacy classification; `claimDispatch` integrated; all 4 production call-sites gated; cold-start recovery uses `processStartMs`; same-process double-tap coalescing is retained only as a UI guard; tests prove durable anti-redispatch and recovery isolation; backup smoke tests 100% green.

---

## 19. FINAL EXECUTION ORDER

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│                         STRICT THREE-STEP EXECUTION                         │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 1: Inventory & Boundaries                                              │
│ - Finalize boundary documentation.                                          │
│ - Zero production modifications.                                            │
│ - STOP for user approval.                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 2: Financial Outcome Semantics & G1-F Correction                       │
│ - Remove exception-to-FAILED collapse in ViewModels.                        │
│ - Delete G1-F renewal fallback in Repositories.kt:1405-1415.                │
│ - Implement accountStatement 4-tuple compound correlation.                  │
│ - Execute focused outcome tests (TEST-06 through TEST-11).                  │
│ - STOP for user approval.                                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ STEP 3: Durable First-Dispatch Authorization                                │
│ - Apply Room MIGRATION_16_17 (dispatchClaimCount).                          │
│ - Implement claimDispatch and cold-start recovery DAO queries.              │
│ - Gate all 4 production call-sites (2 financial + 2 non-financial).         │
│ - Retain existing UI coalescing lock only for same-action suppression.       │
│ - Integrate cold-start recovery in EarthlinkApp.onCreate.                   │
│ - Execute full Step 3 test matrix (TEST-01 through TEST-22) + Backup smoke. │
│ - STOP for adversarial certification.                                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---
## 20. FINAL SELF-REVIEW / CONSISTENCY GATE

This correction pass intentionally preserves the previously validated architecture and closes only the identified gaps:

- Startup race is closed by `processStartMs` / `updatedAt < processStartMs` orphan classification.
- `DISPATCHING` / `RESOLVING` orphans reset to `PENDING(count=1)` before verification; runtime workers never sweep in-flight states.
- Same-process double taps are coalesced before creating a second intent, while SQLite remains the durable claim authority.
- Non-financial recovery cannot infer historical success from current ACTIVE/existing state alone.
- Financial amount conversion is fail-closed for non-finite, fractional, non-250 denomination, or non-positive values.
- Refill price must trace to the authoritative pricing source; otherwise dispatch is aborted.
- Existing G1-F, typed-outcome, accountStatement, canonical materializer, backup, and data-free-window decisions remain unchanged; migration now also explicitly classifies legacy unresolved rows as recovery-blocked.

**Final status:** IMPLEMENTATION-READY FOR STAGED EXECUTION. Each step still requires its defined tests and the mandatory review stop before the next step; final operational claims require adversarial certification.

---
**END OF SPECIFICATION**
