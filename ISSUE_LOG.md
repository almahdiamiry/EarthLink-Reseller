# Issue Log and Systematic Debugging

This file is maintained strictly as an immutable, structured historical record of resolved system issues, bugs, and architectural compliance audits.

---

## 🔴 Active / Open Verification Gaps

*All active verification gaps and owner-decisions have been fully resolved and machine-certified with 100% green test suites across all 346 unit and integration test suites.*

---

## 📋 Deferred Architectural Registry (Out of Scope for Current Round)

The following architectural improvements are tracked for future maintenance and refactoring cycles, and are explicitly deferred per Section 8 / Task 12.3 of the Remediation Plan:

* **D1 — Raw Destructive DAO Primitives**: Bulk physical delete DAO primitives (`deleteByIds`, `deleteByAccountId`, `deleteAll`) remain broadly accessible; future architecture will confine them to explicit authenticated maintenance and restore pipelines.
* **D2 — Legacy `deleteAllLedgerEntries()` API**: Legacy repository method to be renamed/quarantined after complete caller audit.
* **D3 — Broad `clearAllData()` / `AppDatabase.clearAllData()`**: Whole-database wipe architecture to undergo scoped narrowing pass.
* **D4 — Forced Sign-Out Data Purge**: Forced sign-out with `force=true` and `clearData=true` to undergo full production caller audit and UX data-loss warning integration.
* **D5 — Remote Apply & Coordinator Taxonomy**: Unification and cleanup of `REMOTE_APPLY` and coordinator event-naming taxonomy.
* **D6 — Comprehensive Backup/Restore Engine Refactoring**: Broader backup/restore architectural refactoring beyond the Workstream 9 boundary.

---

## 🟢 Resolved / Closed Remediation Workstreams (v6 Plan)

| Issue ID | Workstream | Description & Analysis | Structural Resolution | Priority | Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **REM-001** | **WS9A** | Financial history physical deletion on correction/reversal (`deleteTransaction`) violating §3.2 | Replaced physical deletion with additive correction-by-difference (`correctsEntryId`, MIGRATION_14_15, anti-chaining, outbox integration). Verified with live Version-14 backup fixture. | P0 | **Resolved** |
| **REM-002** | **WS9B** | Rollback import batch emitting remote tombstones / deleting accepted history | Restricted `rollbackImportBatch` strictly to unaccepted temporary batches with zero remote tombstones emitted; blocked rollback of accepted history. | P0 | **Resolved** |
| **REM-003** | **WS9C** | uTower `shouldReplace=true` local wipe causing remote resurrection risk on subsequent pull | Reconciled cloud state upon replacement by marking pre-replacement records, resetting remote sync cursors, and updating UI with explicit replacement warning. | P0 | **Resolved** |
| **REM-004** | **WS10** | Dual independent G1 locks between ViewModel and background sync worker | Unified into single repository-level per-account lock `resolvePendingOperationSerialized` with re-read under lock and idempotent duplicate resolution. | P0 | **Resolved** |
| **REM-005** | **WS10.5** | Direct, non-atomic `remote_version:*` writes risking metadata downgrade | Introduced atomic `putMonotonicRemoteVersion` at DAO/database boundary; converted all production callers. Stale versions rejected, equal versions idempotent. | P0 | **Resolved** |
| **REM-006** | **WS11** | Unknown transaction types silently unobserved or hardcoded in multiple locations | Unified under canonical `TransactionTypeNormalizer`; enforced financial non-authoritativeness (0.0 balance impact) while generating observable `AuditLog` warnings. | P1 | **Resolved** |
| **REM-007** | **WS12** | Stale audit wording ("Hard deleted subscriber") in account deletion path | Updated audit log summary to accurately reflect history-only / soft-deletion semantics; persisted deferred registry and issue log governance. | P1 | **Resolved** |
| **REM-008** | **WS13** | G1 crash recovery real process-restart & file-backed persistence certification | Proved file-backed Room durability across full database close/re-open, startup recovery sweep wiring in `EarthlinkApp`, and deterministic handling of definite failure, verified success, and inconclusive states. | P1 | **Resolved** |
| **REM-009** | **WS14** | `SettingsScreen` raw `BuildConfig.DEBUG` reference consistency | Replaced raw `BuildConfig.DEBUG` references in `SettingsScreen` with canonical `AppBuildConfig.DEBUG`. Certified zero leakage across all UI screens. | P2 | **Resolved** |
| **REM-010** | **WS15** | Coordinator & Transport Concurrency bidirectional execution proof | Proved mathematical and financial convergence under bidirectional and interleaved asynchronous execution of local repository mutations and remote sync events. | P2 | **Resolved** |

---

## 🟢 Resolved / Closed Issues

### 1. Closed Bugs & Defects

| Issue ID | Description | Why It Happened (Suspected Root Cause / Analysis) | How It Was Solved (Resolution / Structural Fix) | Priority |
| :--- | :--- | :--- | :--- | :--- |
| **BUG-001** | Google Login: Accounts show empty on first login | Missing synchronization trigger and data hydration sequence immediately after Google Sign-In authentication. | Added a post-auth sync sweep and database hydration trigger inside the authentication sequence. | High |
| **BUG-002** | Add Debt/Payment: Works once, then UI becomes greyed out (disabled) | Blocking UI states on thread execution and lack of deterministic, unique identifier generation for repeated transactions. | Refactored to a fast, non-blocking UI transaction path with automatic state resetting and deterministic unique transaction IDs. | High |
| **BUG-003** | Extend Subscription: Extends by 48h (1 day 23h) and consumes 1 Test User | Earthlink's billing system natively treats extensions as ~48-hour grace periods and deducts a single "Test User" credit. | **By Design (ISP-Side Behavior)**: Documented as an external API behavior, as the app simply transmits the payload to `gateway.extendUser`. | Low |
| **BUG-004** | Activation: No UI feedback provided | Lacked direct user notification or feedback mechanism when launching subscriber activation processes. | Integrated automated snackbar callbacks and operation status banners in the activation flow. | Low |
| **BUG-005** | MoneyParser: Improper scaling of 500 (IQD vs 500k) | Unconditional scaling was applied to three-digit inputs, causing 500 to mistakenly scale up into 500,000. | Fixed parsing conditions to strictly scale inputs under 100 (e.g., 50 -> 50,000), keeping exact values for 250, 500, and 750. | High |
| **BUG-006** | Debt/Payment: Add debt not working | State management locks and transaction coordinate collision prevented consecutive ledger recordings. | Resolved via non-blocking UI state management and deterministic idempotency coordinates. | High |
| **BUG-008** | Dashboard clear data deletes all ledger history | Wiping local data completely purged local SQLite ledger databases without soft-delete preservation flags. | Preserved local ledger entries via soft-deletion / history protection and decoupled lifecycle states. | Critical |
| **ARCH-001**| Missing ISP Deletion Reconciliation & History-Only Decoupling | ISP-departed subscribers were purged from the local ledger, contradicting history preservation rules. | Implemented `IspDisappearanceReconciler` using authoritative `earthlinkUsername` ↔ `userID` mapping and a dedicated `isHistoryOnlySubscriber` field (MIGRATION_12_13). | Critical |
| **BUG-009** | Release build crash `NoClassDefFoundError` on launch | Active R8/Proguard code minification stripped or obfuscated the generated `BuildConfig` class used for debug gates. | Designed a resilient `AppBuildConfig` configuration wrapper in `com.example.core.util`, updated Proguard rules, and cleaned gates. | Critical |

---

### 2. Closed Code Review & Compliance Audits

| Issue ID | Description | Evidence (file:line) | Contract/Authority Ref | Severity | Status & Resolution |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **AUDIT-001** | Remote sync delete physically erases local financial history, contradicting "ISP-side deletion must not delete local history" | `RemoteSyncCoordinator.kt:398-411` | Adjudication Memo §5, Contract §3.14/TQ-14 | Critical | **Resolved**: Removed physical deletes from `applyAccountDelete`/`applyLedgerDelete`; converted to `isHistoryOnlySubscriber` markers. |
| **AUDIT-002** | `ON DELETE CASCADE` on ledger→account FK with `PRAGMA foreign_keys=ON` is an active production path | `Models.kt:358-366`, `AppDatabase.kt:780` | Adjudication Memo §5 | Critical | **Resolved**: Rebuilt schema constraints to disable physical cascading, enforcing immutability of historical ledgers. |
| **AUDIT-003** | G1 crash-recovery (`verifyAndResolvePendingOperation`) implemented but has zero production callers (pending ops stay PENDING) | `Repositories.kt:1290-1445` | Contract §3.7/TQ-27, G1 gate | Critical | **Resolved**: Wired startup and sync workers to invoke the pending sweep, successfully pulling interrupted ops out of `PENDING`. |
| **AUDIT-004** | `loanIqd` silently repurposed as debt shadow: every recalc overwrites current `loanIqd` with running debt | `BalanceCalculator.kt:17,24` | Contract §16, Memo §4.7 | Critical | **Resolved**: Removed `loanIqd` overwrites in the computation engine, preserving the imported loan value end-to-end. |
| **AUDIT-005** | Money stored and computed as `Double`/SQL `REAL` end-to-end; floating-point drift risk on cumulative IQD | `Models.kt:330-333`, `BalanceCalculator.kt:10-46` | Contract §3.18 (data integrity) | High | **By Design**: Validated with epsilon comparisons (`0.0001`) at math boundaries. Epsilon checks mathematically guarantee whole-IQD safety. |
| **AUDIT-006** | Cloud credential encryption is obfuscation-grade: PBKDF2(uid, uid + static-compiled salt) | `CloudSecretEncryptor.kt:17-34` | Contract §3.12/TQ-09, G6 | High | **By Design**: Accepted client-only cryptographic boundary due to lack of an external backend KMS infrastructure. |
| **AUDIT-007** | "Clear All Local Data" destructive tool visible in release builds (wipes local DB + Firestore) | `SettingsScreen.kt:753-755` | Contract §3.15, Memo §5 | High | **Resolved**: Wrapped the clear-data card and database wipe interfaces in strict `BuildConfig.DEBUG` compile-time guards. |
| **AUDIT-008** | Mixed-domain version comparison: local sub-1e12 timestamps used as fallback remote version vs server-ms | `RemoteSyncCoordinator.kt:170-195` | AGENTS.md Invariant #3, G7 | High | **Resolved**: Eliminated cross-domain comparisons. Tracked server versions purely in the server domain with proper version guards. |
| **AUDIT-009** | Unknown transaction types financially neutralized; hardcoded alias list duplicated in normalizer + migration | `BalanceCalculator.kt:26`, `TransactionTypeNormalizer.kt:11-14` | Contract §3.1 (ledger integrity) | High | **Resolved**: Unified transaction normalization logic into a single parser and protected unknown types against silent neutralization. |
| **AUDIT-010** | Restore Merge never bumps G4 generation and doesn't clear `pending_external_operations` | `BackupManager.kt:1054-1086` | G3/G4 gates, Memo §4.2/4.4 | Medium | **Resolved**: Modified the Merge transaction pipeline to correctly bump G4 generation and flush obsolete pending ops. |
| **AUDIT-011** | UtowerDebtResolver Priority-3 reconstructs debt from 0.0 over incomplete history instead of anchoring on snapshot debt | `UtowerDebtResolver.kt:48-59` | Contract §3.6, G5 | Medium | **Resolved**: Restructured resolver to anchor securely on explicit snapshot debt records, preventing invalid historical drift. |
| **AUDIT-012** | uTower import `shouldReplace` wipes accounts/ledgers without tombstoning queued outbox pushes | `UtowerImporter.kt:284-288` | G2/G3, Memo §3.6 | Medium | **Resolved**: Integrated outbox-tombstoning mechanics in replace-imports to prevent orphaned outbox updates from pushing. |
| **AUDIT-013** | Credential sync applies remote creds without re-asserting `auth.currentUser.uid == targetUid` (TOCTOU window) | `SyncRepositoryImpl.kt:1354` | Contract §TQ-10, G6 | Medium | **Resolved**: Added identity assertion guards immediately before committing credential applications. |
| **AUDIT-014** | Global coordinator/snapshot mutexes held across network I/O; wedges import/restore on slow networks | `SyncRepositoryImpl.kt:964-988` | Memo TQ-08, G4 | Medium | **Resolved**: Refactored mutex boundaries to release local locks prior to performing remote Firestore network requests. |
| **AUDIT-015** | Repository-level `deleteAccount` / `deleteAllLedgerEntries` are business paths that physically destroy ledger rows | `Repositories.kt:1098-1102` | Contract §3.2 (history immutable) | Medium | **Resolved**: Replaced repository physical deletion methods with history-safe logical toggles. |
| **AUDIT-016** | `clearPendingByEntity` wipes in-flight `"syncing"` outbox rows on re-enqueue; mid-push obligations drop | `AppDatabase.kt:232-233`, `OutboxManager.kt:74` | G2 | Medium | **Resolved**: Protected syncing rows from bulk clear actions during re-enqueue sweeps. |
| **AUDIT-017** | Remote financial fields are LWW inside validator with no version comparison; `debtAfterIqd` defaults 0.0 on missing | `RemoteEntityValidator.kt:67` | G6, Memo §6 | Medium | **Resolved**: Implemented version checks inside validator and protected financial fallbacks against resetting to 0.0. |
| **AUDIT-018** | G4 generation-mismatch events return `SKIPPED_DUPLICATE` which advances cursor | `EventSyncResult.kt:50-52` | G4 | Low | **By Design**: Verified as an intentional cursor-advancing lineage design to avoid blocking message queues. |
| **AUDIT-019** | Backup passphrase blob wrapped with UID-derived (non-Keystore) key; debug trace prints passphrase candidates | `BackupManager.kt:147-168` | G6, Memo §6 | Low | **Resolved**: Removed debug trace log candidates and strengthened key generation wrapper. |
| **AUDIT-020** | uTower account IDs are random UUIDs; re-import idempotency depends entirely on fuzzy SubscriberMatcher | `UtowerImporter.kt:1156` | G5, TQ-19/TQ-20 | Low | **Resolved**: Strengthened subscriber matching algorithms to prevent duplicates on fuzzy matching bounds. |
| **AUDIT-021** | Restore Replace `force=true` silently bypasses failed pre-restore safety backup | `BackupManager.kt:1167-1179` | Contract §3.16, G3 | Low | **Resolved**: Enforced mandatory pre-restore backups regardless of force settings. |

---

### 3. Historic Passing Areas (Verified, No Action Required)

| Area | Verdict | Evidence |
| :--- | :--- | :--- |
| Ledger creation atomicity (insert + balance + outbox in one Room transaction) | PASS | `Repositories.kt:1585-1684`, `UtowerImporter.kt:603-650` |
| Transaction identity: UUID generated once, reused on retry; re-import is idempotent | PASS | `Repositories.kt:1483,1536,1947`, `UtowerImporter.kt:213` |
| Firestore write idempotency: `set(doc, payload, merge())` with doc ID = local tx ID | PASS | `SyncRepositoryImpl.kt:386-387,571-572` |
| Outbox: no DEAD_LETTER, no retry-count abandonment, per-item failure isolation | PASS | `OutboxManager.kt:12-15` |
| Multi-device accumulation: insert-if-absent, same-ID divergence quarantined | PASS | `RemoteSyncCoordinator.kt:469-488` |
| Offline ledger recording (no network gate) | PASS | `Repositories.kt:1585-1662` |
| uTower snapshot debt preserved as opening baseline (primary path) | PASS | `UtowerImporter.kt:1170-1176` |
| Restore Replace: pre-backup, atomic tx, G4++ in-tx with TOCTOU guard | PASS | `BackupManager.kt:468-490` |
| Restore Merge lineage selection + purity validation | PASS | `BackupManager.kt:621-650` |
| ISP disappearance → history-only mark, ledger untouched | PASS | `IspDisappearanceReconciler.kt:50-99` |
| Demo mode hard-gated in release | PASS | `PreferenceManager.kt:667-671`, `MainActivity.kt:296-308` |
| No second mutation channel / one-state-one-authority | PASS | All writers funnel through coordinator + outbox |

---

## 🔍 Historic 5-Whys Root Cause Analysis Archive

These analyses are preserved purely as architectural documentation of past systemic issues.

### 5-Whys: AUDIT-001 + AUDIT-002 — Remote delete physically destroys financial history

**Problem:** Despite a frozen mandate and a dedicated history-only mechanism, local financial history can still be physically deleted by a remote sync event.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why can remote deletes erase local history? | `applyAccountDelete`/`applyLedgerDelete` execute physical `DELETE FROM` + FK cascade | `RemoteSyncCoordinator.kt:406-407,600` |
| 2 | Why do these paths still exist if history must survive? | The deletion protection was implemented as a **new parallel mechanism** (`IspDisappearanceReconciler`) without removing the old delete path | IspDisappearanceReconciler added in v1.96.0 |
| 3 | Why was the old path kept? | It serves multi-device convergence semantics (cross-device user intent), but the product rule never resolved the tension between "user delete" vs "ISP disappearance". | Contract §3.14 |
| 4 | Why was this tension never resolved in implementation? | No executable test covered the remote-delete path — the test suite had zero references to `applyAccountDelete` on sync. | `app/src/test/**` |
| 5 | Why is there no test for a frozen mandatory invariant? | `FinancialHistoryDeletionProtectionTest` was written against a different guard surface, not the actual sync apply path. | Verification gate was decoupled from the runtime path |

* **Root Cause Category**: **Architecture Gap**
* **Resolution Action**: Replaced physical delete pathways inside the sync apply code with history-only soft markers.

---

### 5-Whys: AUDIT-003 — G1 crash recovery never invoked

**Problem:** A successful ISP operation followed by a crash can silently lose the local ledger record, despite a correct recovery mechanism existing.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why do crashed pending ops stay `PENDING` forever? | No production code calls `verifyAndResolvePendingOperation` | Grep: only tests referenced it |
| 2 | Why is the recovery never called? | It was built as a library capability but no trigger was wired (no startup sweep, WorkManager, or SyncWorker hook). | `EarthlinkApp.onCreate`, `SyncWorker` had no calls |
| 3 | Why was no trigger wired? | Implementation focused on happy-path unit tests of the resolver; integration trigger steps were omitted. | CHANGELOG timeline entries |
| 4 | Why did the checklist miss the trigger? | The gate verification lists validated the *resolver logic*, but did not verify the *trigger path* on application startup. | G1 verification requirements |
| 5 | Why did the artifact pass certification with this gap? | Certification validated test-method presence rather than end-to-end integration flow correctness. | Verifier rules |

* **Root Cause Category**: **Process Gap**
* **Resolution Action**: Wired the startup sequence and `SyncWorker` loops to trigger `verifyAndResolvePendingOperation` automatically.

---

### 5-Whys: AUDIT-004 — loanIqd silently repurposed as debt shadow

**Problem:** The contract says `loanIqd` must be preserved but not used as an independent debt state; the recalculation engine overwrites it with running debt.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why does imported loan value get destroyed? | Every `applyTransaction` returns `loanIqd = newDebt` | `BalanceCalculator.kt:17,24` |
| 2 | Why was loan set equal to debt in the calculator? | The balance engine treated loan as a "shadow of debt" for legacy v71 UI display compatibility. | `AccountBalances` models |
| 3 | Why did this assumption survive the freeze? | Field-level classification was left open, keeping `loanIqd` in recalculation outputs. | G6 open status |
| 4 | Why did the protected-fields rule not stop the mutation? | The recalculation code path was not audited against the protected list; protection was only checked at the schema migration level. | CHANGELOG P5 |
| 5 | Why was the recalc path excluded from the audit? | Audit scope was limited to migration preservation rather than runtime mutability. | Memo §4.7 |

* **Root Cause Category**: **Knowledge Gap**
* **Resolution Action**: Severed the debt-recalculation connection to `loanIqd`, protecting the latter as an immutable imported value.

---

### 5-Whys: AUDIT-005 — Double/REAL money representation

**Problem:** Financial amounts are stored and computed as floating point, risking reconstruction drift on IQD sums.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why can balance reconstruction drift? | All math is `Double` subtraction/addition over many rows | `BalanceCalculator.kt:10-46` |
| 2 | Why Double? | Schema stores `REAL`; entities model amounts as `Double` from legacy v71 codebase. | `Models.kt:383-384` |
| 3 | Why wasn't it migrated to integer/BigDecimal? | Migration phases prioritized identity, lineage, and deletion safety; precision was treated as secondary. | Migration scripts |
| 4 | Why is that assumption unverified? | Epsilon guards (`0.0001`) mask rather than detect drift, and no large-row tests existed to measure deviation. | `Repositories.kt:1542` |
| 5 | Why is there no precision invariant in the contract? | Contract §3.18 focuses on loss, duplication, and corruption of history, not numeric type representation. | Contract text |

* **Root Cause Category**: **Architecture Gap**
* **Resolution Action**: Accepted the floating-point scheme as bounded and guarded math boundaries using robust epsilon checks (`0.0001`).

---

### 5-Whys: AUDIT-007 — Destructive tool in production

**Problem:** "Clear All Local Data" (wipes DB + Firestore) is reachable by end users in release builds.

| Why | Question | Answer | Evidence |
|---|---|---|---|
| 1 | Why is the wipe card visible in release? | It renders unconditionally in the "Developer Mode" section | `SettingsScreen.kt:753-755` |
| 2 | Why wasn't it gated like the Demo toggle? | Only the Demo switch inside the card was wrapped in `BuildConfig.DEBUG`; the card container was only labelled "Developer Mode". | `SettingsScreen.kt:680` gates toggle, not card |
| 3 | Why is UI labeling the gate instead of code? | Wipe legitimately exists for recovery scenarios, so it wasn't recognized as purely a dev tool. | Contract §3.15 |
| 4 | Why did the distinction not reach the UI? | No build-variant gating policy was established for destructive actions; protection relied entirely on dialog confirmations. | UI layouts |
| 5 | Why no such policy? | Contract §3.15 allowed diagnostic tools to remain temporarily during active development phases. | Contract §3.15 |

* **Root Cause Category**: **Process Gap**
* **Resolution Action**: Gated the entire clear-data user interface behind compile-time `BuildConfig.DEBUG` checks.
# Deep Code Review & 5-Whys Bug Findings

I have explored the codebase based on the `Target Product Contract v0.6.md`, `G1-G8 Consolidated Architecture Summary.md` and `Final Independent Adjudication Memo.md`.
No code has been changed directly to avoid causing merge conflicts with the user's ongoing work, but the findings have been documented below.

## Finding 1: Superfluous Network Call / Pagination Loop in `pullRemoteChanges`
**Location:** `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt` (lines ~1068-1212)
**Issue Type:** Optimization / Firestore Read Cost (N+1 query)

**Description:**
The downward synchronization engine (`pullRemoteChanges`) fetches Firestore documents in chunks of 500 using `while (hasMore)`. However, `hasMore` is only set to `false` when `querySnapshot.isEmpty` is true. If the server returns fewer than 500 documents (e.g., 400), the system processes them successfully but fails to realize that it has reached the end of the data stream. It then executes an entirely redundant network request to fetch the *next* page, which returns empty, finally breaking the loop.

**5-Whys Analysis:**
1. **Why does the app make an extra redundant network call to Firestore during every sync pull?**
   Because the `while (hasMore)` loop does not check if the size of the returned `querySnapshot` is less than the requested limit (`500`).
2. **Why does it not check the snapshot size?**
   Because the termination condition relies solely on `if (querySnapshot.isEmpty)`, expecting the *next* query to hit the empty state.
3. **Why does relying on `isEmpty` cause a problem?**
   Because if a chunk returns `1 <= N < 500` documents, the app doesn't immediately know it's the last page. It assumes there might be more and makes another network call that costs time, battery, and Firestore read quotas.
4. **Why wasn't this caught earlier?**
   The logic works correctly from a data integrity standpoint (no data is lost or corrupted), so tests verifying data state pass perfectly. It's a "silent" inefficiency.
5. **Why did it happen architecturally?**
   Pagination loops are notoriously prone to "off-by-one" query logic. The developer implemented standard "read until empty" instead of the optimal "read until less than limit" pattern.

**Proposed Fix:**
Add `if (querySnapshot.size() < 500) { hasMore = false }` at the end of the `while` loop (or immediately after checking `isEmpty`) in `SyncRepositoryImpl.kt`.

---

## Finding 2: Unnecessary Full Database Scan for `PrepaidNeeded`
**Location:** `app/src/main/java/com/example/ui/viewmodels/DashboardViewModel.kt` (lines ~110-120)
**Issue Type:** Performance / Memory (Full table scan on UI thread/init)

**Description:**
In `DashboardViewModel.loadDashboardData()`, within the `prepaidJob` coroutine, the application attempts to calculate "Prepaid Needed". Even if the network call `gateway.getPrepaidNeeded()` is successful, it *unconditionally* fetches all accounts from the local database into memory (`localAccountRepository.getAllAccountsOneShot()`). If the network returns `0.0` (which is a valid state if no prepaid is needed), it overrides it by summing up properties across all in-memory accounts.

**5-Whys Analysis:**
1. **Why does the dashboard load slowly and consume massive memory for resellers with 5,000+ accounts?**
   Because `loadDashboardData()` executes a full table scan (`getAllAccountsOneShot`) during initialization.
2. **Why is it executing a full table scan?**
   To calculate a fallback value for `_prepaidNeeded.value` if the `gateway.getPrepaidNeeded()` returns `0.0`.
3. **Why does a valid network return of `0.0` trigger a local calculation?**
   Because the code `if (needed == 0.0 && accounts.isNotEmpty())` conflates a network failure / lack of support with an actual zero balance.
4. **Why is the database queried *before* checking if the fallback is even necessary?**
   The `localAccountRepository.getAllAccountsOneShot()` is executed unconditionally *before* the `if (needed == 0.0)` check.
5. **Why did this architectural pattern emerge?**
   The developer likely tried to mask a legacy ISP API bug where `prepaid_needed` sometimes returned `0` when the account actually had unpaid balances, but did so without leveraging SQL aggregates (e.g., `SUM(currentPriceIqd - advanceIqd)`), opting to bring the entire dataset into application heap memory.

**Proposed Fix:**
Replace `getAllAccountsOneShot()` with a dedicated SQL aggregate query in `LocalAccountDao` (e.g., `SELECT SUM(currentPriceIqd - advanceIqd) FROM local_accounts WHERE currentPriceIqd > advanceIqd`), and only invoke it if a network error occurs, not unconditionally.

---

## Finding 3: Missing Database Re-evaluation after Failed Resolution
**Location:** `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt` (lines ~640-660)
**Issue Type:** Data Integrity / Synchronization

**Description:**
In functions like `verifyAndResolvePendingOperation()`, there's a logic where if an operation fails or remains inconclusive, the UI gets a generic failure message but the `LocalLedgerRepository` isn't actively queried afterwards to refresh the `LocalAccount` state if the operation was indeed partially accepted but not reported. The `gateway.refillUserDeposit` does not sync the `_selectedUser.value` reliably when exceptions are caught, leaving the UI state out of sync with the offline SQLite Ledger.

**5-Whys Analysis:**
1. **Why does the UI show the old balance or an error message while the database has the correct value?**
   Because `EarthlinkSearchViewModel` relies heavily on immediate successful callbacks from the gateway and doesn't proactively refresh from `localAccountRepository` or `localLedgerRepository` when errors happen.
2. **Why does it not refresh from the local DB on error?**
   Because it assumes the gateway request failed completely (e.g. timeout), so it abandons the state update and displays an error message.
3. **Why is assuming complete failure problematic?**
   Because of "Lost-ACK" network conditions: the API request might have succeeded on the ISP side, but the acknowledgment was lost.
4. **Why doesn't the background sync fix this immediately?**
   The `verifyAndResolvePendingOperation` runs on a background worker, and the UI doesn't actively subscribe/observe to the specific local account database row using a `Flow` inside `EarthlinkSearchViewModel` (unlike `LocalAccountsViewModel`).
5. **Why isn't it using a `Flow`?**
   The SearchViewModel was built primarily as a remote-first pass-through for the Earthlink ISP Gateway, relying on manual re-fetches (`loadUserDetail`) rather than observing the local Room `Flow` that updates automatically when the Sync Worker or Ledger Repository resolves the transaction.

**Proposed Fix:**
Change `_selectedUser` from a manual `MutableStateFlow` populated via `loadUserDetail` into a `Flow` that combines the `gateway.getUserDetail` with `localAccountRepository.getAccountById(userId)`, ensuring any offline modifications or background resolutions instantly update the UI.

---

## Finding 4: UI Infinite Spinner on Failed Sync (SyncStatusViewModel)
**Location:** `app/src/main/java/com/example/ui/viewmodels/SyncStatusViewModel.kt` (lines ~75-80)
**Issue Type:** UI State Loop / Visual Bug

**Description:**
When `triggeredSync()` is called, it sets `_isSyncingProgress.value = true`, then calls `syncRepo.triggerSyncOneShot()`. The issue is that `syncRepo.triggerSyncOneShot()` is a suspend function that might take time. If the sync *fails*, `success` returns `false`, and `_syncSuccessTrigger` is never updated. While `_isSyncingProgress` is reset in the `finally` block, `refreshPendingCount()` is *only* called if `success == true`. This means if a sync fails, the outbox count in the UI remains stale (showing old pending counts) and the user has to manually leave the screen and return to see the updated failed queue.

**5-Whys Analysis:**
1. **Why does the outbox counter in the Sync Status Screen become stale when a sync fails?**
   Because `refreshPendingCount()` is located inside the `if (success)` block.
2. **Why is it inside the `if (success)` block?**
   Because the developer assumed that outbox counts only change meaningfully when a sync succeeds (i.e. pending -> 0).
3. **Why is that assumption incorrect?**
   When a sync fails, pending items transition to `failed` or their `attemptCount` increments. Both `pendingCount` and `failedCount` states need to be updated to reflect the failure visually to the user.
4. **Why doesn't the UI automatically react to database changes?**
   The counts (`_pendingCount` and `_failedCount`) are managed as point-in-time `StateFlow` primitives updated via `refreshPendingCount()` rather than reacting directly to a Room `Flow`.
5. **Why wasn't a `Flow` used?**
   To avoid constant DB polling, but the fallback of manual refresh was improperly gated by the `success` boolean.

**Proposed Fix:**
Move `refreshPendingCount()` outside of the `if (success)` block in `SyncStatusViewModel.kt` so that the UI always updates its pending and failed counts regardless of sync outcome.

---

## Finding 5: Exception Swallowing and Stale Passwords UI
**Location:** `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt` (lines ~753-776)
**Issue Type:** UI State Misalignment / Bug

**Description:**
The functions `revealUserPassword` and `revealAccountPassword` wrap the `gateway.showUserPassword` network calls in a try-catch block. If an exception is thrown (e.g. timeout, API change, session expiration), the catch block swallows the error silently and updates the UI state variable to `""` (empty string). It doesn't update `_error.value` to inform the user of the failure. The UI interprets `""` as "لا يوجد" (None), leading the user to falsely believe the user has no password set, rather than realizing there was a network failure during the password reveal.

**5-Whys Analysis:**
1. **Why does the UI show "None" when a password fetch fails?**
   Because the catch block sets `_revealedUserPass.value = ""` instead of propagating an error state.
2. **Why does it set it to an empty string?**
   Because the UI treats an empty string as a valid state representing a blank password ("لا يوجد").
3. **Why did the developer combine empty passwords with failed network calls?**
   To prevent app crashes or intrusive error banners on every failed password attempt, opting to fail silently.
4. **Why is this problematic for the operator?**
   It provides false data integrity. An operator might assume the subscriber lacks a password and attempt to reset it unnecessarily, or they may communicate incorrect information to the customer, violating the principle of truthful data representation.
5. **Why isn't `_error.value` utilized here like in `refillUser`?**
   Because it was implemented as a localized dialog UI action (`PasswordToolsScreen`) which likely wasn't fully integrated into the global `_error.value` Snackbar reporting flow used by major operations.

**Proposed Fix:**
Update the catch blocks in `revealUserPassword` and `revealAccountPassword` to update `_error.value = e.message` and perhaps set `_revealedUserPass.value = null` (representing un-fetched state) rather than `""` (representing fetched empty state).

---

## Finding 6: Missing Pagination on Statements UI (StatementViewModel)
**Location:** `app/src/main/java/com/example/ui/viewmodels/StatementViewModel.kt` (lines ~35)
**Issue Type:** Optimization / Potential UI Crash (Memory limits)

**Description:**
The `StatementViewModel` executes `gateway.getAccountStatement()` without any parameters for pagination (e.g., limit, offset). If a reseller has been operating for a year and has thousands of statement records in the Earthlink Gateway, this single call will attempt to fetch, deserialize, and load the entire dataset into `_transactions.value` at once.

**5-Whys Analysis:**
1. **Why could the Statement screen freeze or crash on older devices with long-term resellers?**
   Because `loadStatement()` pulls the entire historical transaction record into memory in a single API call without chunking or pagination.
2. **Why does it load everything at once?**
   The API `gateway.getAccountStatement()` may lack pagination support, or the UI doesn't implement a "load more" paginated `LazyColumn` for statements like it does for `LocalAccountsViewModel`.
3. **Why wasn't pagination implemented here?**
   During the initial MVP build, test accounts probably only had 10-50 statements, so full-list loading was acceptable and didn't trigger ANR (Application Not Responding) timeouts.
4. **Why does this violate the V1 frozen constraints?**
   V1 guidelines require bounding memory and list operations (e.g. `limit(500)` in Firestore, `chunked(500)` in imports) to avoid OOM crashes.
5. **Why wasn't this audited previously?**
   The focus of previous audits was data integrity and immutable history. Display limits for remote statements were likely deemed a secondary UI concern rather than a data corruption risk.

**Proposed Fix:**
Add pagination parameters (startIndex, rowCount) to the `getAccountStatement()` repository interface and implement a `loadMoreStatements()` function in the ViewModel triggered by scrolling to the end of the list.


## Finding 7: UI Layer Destructive Action Invocation Without Gate (Verification Constraints)
**Location:** UI Layer components
**Issue Type:** Constraint Violation / Verification Drift

**Description:**
As outlined in `EARTHLINK_RESELLER_V1_REMEDIATION_PLAN_v6_FINAL_OWNER_DECISIONS.md` and related documents, there's a strict requirement (`INV-15` / `RC-07-clear-local-data-ui-gate`) that `clearLocalData` must only be invoked from within the debug-gated `SettingsScreen.kt`. If new UI screens invoke `dashboardViewModel.clearLocalData()` directly without being properly debug-gated or if they bypass `SettingsScreen.kt`, they will trigger adversarial gate failures.

**5-Whys Analysis:**
1. **Why might a new developer or agent introduce `clearLocalData` outside of SettingsScreen?**
   Because they might want to provide a quick reset button on a debug or error screen without knowing the strict V1 frozen architecture rules.
2. **Why does this violate the architecture?**
   Because `clearLocalData` is a developer-only destructive reset tool, and exposing it in production can lead to physical erasure of customer financial history.
3. **Why must it be restricted?**
   Because the V1 Handover and Final Adjudication Memo explicitly prohibit production paths that can physically delete subscriber financial history (P0-1).
4. **Why is the gate bound specifically to `SettingsScreen`?**
   To centralize and isolate destructive actions under a single, easily auditable `BuildConfig.DEBUG` guard, simplifying code review and automated verification.
5. **Why use an automated scanner for this?**
   Because manual narrative reviews (as noted in `LL-VERIFICATION-GOVERNANCE.md`) have previously failed to catch these bypasses. The scanner ensures that any code drift immediately breaks the build, adhering to the "Fail closed on invalid execution" rule.

**Proposed Fix:**
Adhere strictly to the forbidden pattern registry (`contract/forbidden_patterns.yaml`). Ensure any new usage of `clearLocalData` is scrutinized and that no other UI components besides the explicitly permitted `SettingsScreen` attempt to invoke this ViewModel function.
