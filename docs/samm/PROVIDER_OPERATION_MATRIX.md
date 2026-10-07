# PROVIDER_OPERATION_MATRIX.md — Earthlink Reseller V1 & SAMM Parity Matrix

**Document Version:** 1.0.0  
**Date:** 2026-10-06  
**Status:** Canonical Authority for Task 0 Baseline & Provider Seam  
**Target Architecture:** SAS Seam (`SasGatewayRouter` -> `EarthlinkGateway` / `SammGateway`)

---

## 1. Executive Summary & Repository Baseline

This document establishes the repository call graph, consumer inventory, pending-operation producers, recovery entry points, serialization pathways, and credential lifecycles prior to the Alamiry/SAMM integration.

### 1.1 Verified Repository Baseline (Step 0.1)

| Parameter | Measured Reality | Source / Command |
|---|---|---|
| **Git Branch** | `feat/alamiry-samm-integration` | `git branch --show-current` |
| **Commit SHA (HEAD)** | `f0514e5a7f17e70abda6867fe4498fae1c4e77d3` | `git rev-parse HEAD` |
| **Working Tree State** | Clean (untracked: `docs/samm/`, `docs/superpowers/plans/...`) | `git status --short` |
| **Test Suite Command** | `./gradlew :app:testDebugUnitTest --rerun-tasks` | Gradle task runner |
| **Total Test Count** | **811** | JUnit XML aggregated (`app/build/test-results/testDebugUnitTest/`) |
| **Passed Tests** | **811** | 0 failures, 0 errors |
| **Failed Tests** | **0** | Authoritative run |
| **Skipped Tests** | **0** | Authoritative run |
| **Test Suite Count** | **115 XML suites** | All passing |
| **Build Variant** | `debug` | Android Gradle Plugin 8.2+ |
| **Connected Device / ADB** | None (Headless environment, Robolectric + JVM execution) | `adb devices` |

---

## 2. EarthlinkGateway Consumer Inventory (Step 0.2)

`EarthlinkGateway` is defined in `app/src/main/java/com/example/domain/repository/Interfaces.kt` and implemented by `EarthlinkGatewayImpl` in `app/src/main/java/com/example/data/repository/Repositories.kt`.

### 2.1 Complete Call-Site Mapping

| Caller Component | Method / Operation | Provider-Specific? | Target Architectural Seam |
|---|---|---|---|
| `AuthViewModel.login` | `gateway.login(user, pass)` | **Yes** (EarthLink token endpoint) | Auth Seam (`SasAuthAdapter`) |
| `DashboardViewModel.loadDashboardData` | `gateway.getBalance()` | **Yes** (Prepaid reseller wallet balance) | Telemetry / Wallet Seam (`SasGateway.getBalance`) |
| `DashboardViewModel.loadDashboardData` | `gateway.searchUsers("", 0, 5000)` | **No** (Subscriber directory list for disappearance reconciliation) | Directory Seam (`SasGateway.searchUsers`) |
| `DashboardViewModel.loadDashboardData` | `gateway.getPrepaidNeeded(days)` | **Yes** (EarthLink forecast algorithm) | Financial Advisory Seam |
| `DashboardViewModel.loadDashboardData` | `gateway.getTestUsersCount()` | **Yes** (EarthLink test user quota) | EarthLink-Only Feature Seam |
| `DashboardViewModel.setPrepaidNeededDays` | `gateway.getPrepaidNeeded(days)` | **Yes** (EarthLink forecast algorithm) | Financial Advisory Seam |
| `EarthlinkSearchViewModel.loadData` | `gateway.getBalance()` | **Yes** (Reseller balance indicator) | Telemetry / Wallet Seam |
| `EarthlinkSearchViewModel.loadData` | `gateway.getAccountCost(accountIndex)` | **No** (Plan pricing check) | Catalog Seam (`SasGateway.getPackageCost`) |
| `EarthlinkSearchViewModel.searchSubscribers` | `gateway.searchUsers(query, 0, 200)` | **No** (Live subscriber query) | Directory Seam (`SasGateway.searchUsers`) |
| `EarthlinkSearchViewModel.selectUser` | `gateway.getUserDetail(userIndex)` | **No** (Live subscriber detail) | Subscriber Detail Seam (`SasGateway.getUserDetail`) |
| `EarthlinkSearchViewModel.loadPackages` | `gateway.getPackages()` | **No** (Available plan catalog) | Catalog Seam (`SasGateway.getPackages`) |
| `EarthlinkSearchViewModel.loadPackages` | `gateway.getAccountCost(pkgIndex)` | **No** (Default package cost) | Catalog Seam (`SasGateway.getPackageCost`) |
| `EarthlinkSearchViewModel.checkAvailability` | `gateway.checkUsernameAvailable(userId)` | **No** (Username collision check) | Provisioning Seam (`SasGateway.checkUsernameAvailable`) |
| `EarthlinkSearchViewModel.createTestUser` | `gateway.checkUsernameAvailable(userId)` | **No** (Pre-flight availability) | Provisioning Seam |
| `EarthlinkSearchViewModel.createTestUser` | `gateway.createTestUser(...)` | **Yes** (EarthLink test user) | EarthLink-Only Feature Seam |
| `EarthlinkSearchViewModel.createSubscriberUsingDeposit` | `gateway.checkUsernameAvailable(userId)` | **No** (Pre-flight availability) | Provisioning Seam |
| `EarthlinkSearchViewModel.createSubscriberUsingDeposit` | `gateway.getAccountCost(pkgIndex)` | **No** (Authoritative price check) | Catalog Seam |
| `EarthlinkSearchViewModel.createSubscriberUsingDeposit` | `gateway.createUserUsingDeposit(...)` | **No** (Durable subscriber activation) | Durable Activation Seam (`SasGateway.activateSubscriber`) |
| `EarthlinkSearchViewModel.refillUser` | `gateway.refillUserDeposit(userId, pass)` | **No** (Durable subscriber renewal/refill) | Durable Renewal Seam (`SasGateway.renewSubscriber`) |
| `EarthlinkSearchViewModel.refillUser` | `gateway.getBalance()` | **Yes** (Post-refill balance refresh) | Telemetry / Wallet Seam |
| `EarthlinkSearchViewModel.extendUser` | `gateway.extendUser(userIndex)` | **Yes** (EarthLink grace extension) | Lifecycle Seam (`SasGateway.extendSubscriber`) |
| `EarthlinkSearchViewModel.toggleUserActive` | `gateway.toggleUserActive(userIndex, active)` | **No** (Enable / Disable subscriber) | Lifecycle Seam (`SasGateway.toggleUserActive`) |
| `EarthlinkSearchViewModel.changeAccountType` | `gateway.changeAccountType(userIndex, userId, pkgIndex)` | **No** (Plan / Profile migration) | Catalog Seam (`SasGateway.changeAccountType`) |
| `EarthlinkSearchViewModel.showUserPassword` | `gateway.showUserPassword(userIndex, userId)` | **No** (Show PPP password) | Credential Retrieval Seam |
| `EarthlinkSearchViewModel.showAccountPassword` | `gateway.showAccountPassword(userIndex, userId)` | **Yes** (EarthLink portal password) | EarthLink-Only Feature Seam |
| `EarthlinkSearchViewModel.changeUserPassword` | `gateway.changeUserPassword(userIndex, userId, newPass)` | **No** (Change PPP password) | Credential Management Seam |
| `EarthlinkSearchViewModel.changeAccountPassword` | `gateway.changeAccountPassword(userIndex, userId, newPass)` | **Yes** (EarthLink portal password) | EarthLink-Only Feature Seam |
| `EarthlinkSearchViewModel.updateUserDisplayName` | `gateway.updateUserDisplayName(userIndex, newName)` | **No** (Update customer name) | Profile Seam (`SasGateway.updateDisplayName`) |
| `StatementViewModel.loadStatement` | `gateway.getAccountStatement()` | **Yes** (EarthLink deposit ledger statement) | Statement / History Seam |
| `EarthlinkApp.onCreate` | `localLedgerRepo.recoverColdStartOrphanedOperations(gateway, startMs)` | **No** (Pending operation recovery) | Recovery Coordinator (`SasRecoveryCoordinator`) |
| `EarthlinkApp.onCreate` | `localLedgerRepo.sweepAndResolvePendingOperations(gateway)` | **No** (Startup pending sweep) | Recovery Coordinator (`SasRecoveryCoordinator`) |
| `SyncWorker.doWork` | `localLedgerRepo.sweepAndResolvePendingOperations(gateway)` | **No** (Background pending sweep) | Recovery Coordinator (`SasRecoveryCoordinator`) |
| `LocalLedgerRepositoryImpl.verifyRenewalViaStatement` | `gateway.getAccountStatement(0, 50, op.accountId)` | **Yes** (4-tuple statement correlation ±90s) | Verification Seam (EarthLink-specific correlation) |
| `LocalLedgerRepositoryImpl.verifyNonFinancialLifecycle` | `gateway.checkUsernameAvailable(op.accountId)` | **No** (Negative evidence check for TEST_USER) | Verification Seam |
| `LocalLedgerRepositoryImpl.verifyNonFinancialLifecycle` | `gateway.getUserDetail(userIndex)` | **No** (Negative evidence check for EXTEND) | Verification Seam |
| `LocalLedgerRepositoryImpl.verifyAndResolvePendingOperation` | `gateway.checkUsernameAvailable(op.accountId)` | **No** (ACTIVATION non-execution check) | Verification Seam |
| `LocalLedgerRepositoryImpl.verifyAndResolvePendingOperation` | `gateway.searchUsers(op.accountId, 0, 10)` | **No** (RENEWAL subscriber location) | Verification Seam |
| `LocalLedgerRepositoryImpl.verifyAndResolvePendingOperation` | `gateway.getUserDetail(userIndex)` | **No** (RENEWAL expiration advancement check) | Verification Seam |

---

## 3. Pending-Operation Producers (Step 0.3)

All production external mutations that interact with ISP backends are governed by **RED Invariant 3 (Atomic & Idempotent Dispatch)** and **RED Invariant 4 (Canonical Financial Materialization)**.

### 3.1 Producer Inventory Table

| Operation Type | Producer File & Method | Mutex Key | Intent ID & Business Tx ID | Financial Impact | Dispatch Seam & Verification Rule |
|---|---|---|---|---|---|
| **`ACTIVATION`** | `EarthlinkSearchViewModel.createSubscriberUsingDeposit` (lines 535–645) | `"${username}:ACTIVATION"` | `opIntentId = UUID`<br>`businessTxId = "act_" + opIntentId` | **Yes** (`exactAmountIqd` > 0, 250-IQD multiple) | Claim 1-writer hardware lock -> `gateway.createUserUsingDeposit(...)` -> If success, `resolvePendingOperationVerifiedSuccess` materializes debt in `local_ledger_entries`. Supported on both EarthLink and SAMM (`POST /customers`). |
| **`RENEWAL`** | Subscription duration renewal. Triggered via `EarthlinkSearchViewModel.refillUser` (or UI renewal action). | `"${userId}:REFILL"` | `opIntentId = intentId ?: UUID`<br>`businessTxId = "charge_" + opIntentId` | **Yes** (`exactAmountIqd` > 0, 250-IQD multiple) | Claim 1-writer hardware lock -> routes to provider renewal (`POST /api/v1/customers/{id}/renew` on SAMM, `refilluserdeposit` on EarthLink) -> `resolvePendingOperationVerifiedSuccess` + `recordAccountRenewal`. Full parity. |
| **`REFILL`** | Deposit wallet refill (`refillUserDeposit`). | `"${userId}:REFILL"` | `opIntentId = intentId ?: UUID`<br>`businessTxId = "charge_" + opIntentId` | **Yes** (`exactAmountIqd` > 0) | **EarthLink-Only; STRICTLY UNSUPPORTED on SAMM.** SAMM does not maintain a reseller deposit box. Any `REFILL` operation targeting `ALAMIRY` must fail closed as `UnsupportedProviderOperationException` and never route to `/renew` or billing. |
| **`EXTEND`** | `EarthlinkSearchViewModel.extendUser` (lines 1002–1065) | `"${userId}:EXTEND"` | `opIntentId = UUID`<br>`businessTxId = "ext_" + opIntentId` | **No** (`amountIqd = 0L`) | Claim 1-writer hardware lock -> `gateway.extendUser(...)` -> `resolvePendingOperationVerifiedSuccess` records 0 ledger debt (non-financial). |
| **`TEST_USER`** | `EarthlinkSearchViewModel.createTestUser` (lines 457–520) | `"${username}:TEST_USER"` | `opIntentId = UUID`<br>`businessTxId = "test_" + opIntentId` | **No** (`amountIqd = 0L`) | Claim 1-writer hardware lock -> `gateway.createTestUser(...)` -> `resolvePendingOperationVerifiedSuccess` records 0 ledger debt (non-financial). EarthLink-only. |

### 3.2 Producer Architecture & Guarantees
1. **Single Producer File:** `EarthlinkSearchViewModel.kt` is the **only** file in `app/src/main` that calls `localLedgerRepository.recordPendingOperation`.
2. **In-Flight Mutex Guard:** Every producer acquires an in-memory `ConcurrentHashMap<String, Mutex>` lock (`getAccountLock`) before entering.
3. **Idempotency Gate:** Checks `getPendingOperationByIntentId(opIntentId)`. If `COMPLETED`, returns cached success immediately without dispatch.
4. **Hardware Single-Writer Claim:** Queries SQLite Room `claimDispatchAuthorization(businessTxId)`:
   ```sql
   UPDATE pending_external_operations
   SET status = 'DISPATCHING', dispatchClaimCount = dispatchClaimCount + 1, updatedAt = :now
   WHERE businessTransactionId = :id AND status = 'PENDING' AND dispatchClaimCount = 0
   ```
   If returned row count is 0, dispatch is strictly blocked.
5. **Canonical Materialization (RED Invariant 4):** Ledger mutations are NEVER created optimistically before dispatch. Debt is only materialized upon verified success via `resolvePendingOperationVerifiedSuccess`.

---

## 4. Recovery Entry Points (Step 0.4)

### 4.1 Recovery Pipeline & Call-Graph

```text
Application Cold Start (EarthlinkApp.onCreate)
       │
       ├─► 1. recoverColdStartOrphanedOperations(gateway, processStartMs)
       │       │
       │       ├─► pendingDao.getOrphanedInFlightOperations(processStartMs)
       │       ├─► pendingDao.resetOrphanedInFlightToPending(...) [DISPATCHING/RESOLVING -> PENDING]
       │       └─► resolvePendingOperationSerialized(businessTxId, gateway)
       │
       └─► 2. sweepAndResolvePendingOperations(gateway, graceWindowMs = 5000L)
               │
               └─► resolvePendingOperationSerialized(...)

Background WorkManager (SyncWorker.doWork)
       │
       └─► sweepAndResolvePendingOperations(gateway, graceWindowMs = 5000L)
```

### 4.2 Recovery Entry Points Detail

| Entry Point | Trigger & Lifecycle | Concurrency Lock | Target Operations | Verification Strategy |
|---|---|---|---|---|
| **Cold-Start Orphan Recovery** | `EarthlinkApp.onCreate()` -> `recoverColdStartOrphanedOperations` | Coroutine `Dispatchers.IO`<br>Per-op mutex `pendingOpMutexes` | Operations in `DISPATCHING` or `RESOLVING` left behind by a crash/kill before current process startup | Resets status to `PENDING` without re-dispatching. Verifies via 4-tuple statement correlation or server query. |
| **Cold-Start Pending Sweep** | `EarthlinkApp.onCreate()` -> `sweepAndResolvePendingOperations` | Coroutine `Dispatchers.IO`<br>Per-op mutex `pendingOpMutexes` | Unresolved operations in `PENDING` | Evaluates operations older than grace window (5000ms). |
| **Periodic Background Sweep** | `SyncWorker.doWork()` -> `sweepAndResolvePendingOperations` | Android WorkManager periodic worker (`Dispatchers.Default` -> Repo IO) | All unresolved `PENDING` operations | Evaluates operations older than grace window (5000ms). Aborts early if `token.isNullOrEmpty()`. |
| **Manual / Direct Verification** | `LocalLedgerRepositoryImpl.verifyAndResolvePendingOperation` | Serialized by `pendingOpMutexes[txId]` | Single `businessTransactionId` | Direct invocation by UI or operator action. |

### 4.3 Provider Requirement for Recovery
Currently, all recovery entry points accept a single `gateway: EarthlinkGateway`. In the SAS architecture:
- Each pending operation must record `operationProvider: String` (e.g. `"EARTHLINK"` or `"ALAMIRY"`).
- The recovery coordinator must route verification to the specific provider adapter recorded in the pending operation.
- Cross-provider fallback during recovery is strictly prohibited (Plan §2.4).

---

## 5. LocalAccount Serialization Audit (Step 0.5)

### 5.1 Current Entity State & Data Pipeline
- `LocalAccount` is annotated with `@JsonClass(generateAdapter = true)` (Moshi).
- In Room, table `local_accounts` contains 32 columns.
- `operationProvider` does **NOT** currently exist in `LocalAccount`.

### 5.2 Serialization Pathway Trace

| Stage | Mechanism | Behavior |
|---|---|---|
| **Room Database (SQLite)** | Room Entity (`local_accounts`) | Reads/writes via `LocalAccountDao`. Direct SQLite schema mapping. |
| **Local Backup** | `BackupManager.createLocalBackupZip` | Clones SQLite table `local_accounts` directly row-by-row into an unencrypted SQLite clone inside the zip. **Preserves 100% of SQLite columns**. |
| **Local Restore** | `BackupManager.restoreFromZip` | Copies table rows directly from decrypted backup SQLite into live SQLite within an atomic Room transaction. |
| **Sync Outbox** | `OutboxManager.upsertWithOutbox` | Serializes `LocalAccount` to JSON via Moshi `adapter.toJson(account)` and writes to `sync_outbox`. |
| **Firestore Cloud Sync (Outbound)** | `SyncRepositoryImpl.processOutboxBatch` | Sends the Moshi JSON map to Firestore document `users/{uid}/local_accounts/{id}`. |
| **Firestore Cloud Sync (Inbound)** | `RemoteSyncCoordinator.processEvent` | Deserializes Firestore document via `accountAdapter.fromJson(payload)` and calls `accountDao.upsert(account)` after conflict resolution. |
| **uTower CSV/JSON Import** | `UtowerImporter.kt` | Maps external import fields into `LocalAccount` instances. |
| **In-Memory Copy / Update** | `LocalAccountRepositoryImpl.saveAccount` | Merges incoming fields with `existing.copy(...)` (lines 1207–1222). |

### 5.3 Explicit Architectural Answers (Step 0.5 Requirements)

#### Q1: Is `operationProvider` synchronized?
**YES, as business routing metadata.**  
When `operationProvider` is added to `LocalAccount`:
1. It represents which ISP backend (`EARTHLINK` vs `ALAMIRY`) manages this subscriber.
2. It is synchronized to Firebase Firestore as an attribute of `local_accounts/{id}` so multi-device setups know which provider handles each subscriber.
3. **Sensitive credentials (SAMM API tokens, passwords, Base URLs) are NEVER synchronized to Firestore.** Only the provider discriminator string (`"EARTHLINK"` or `"ALAMIRY"`) is synchronized.

#### Q2: How is it protected from stale remote overwrite?
1. **Local Mutation Protection:** In `RemoteSyncCoordinator.kt` (lines 297, 354), `hasConflictingLocalMutation(event.entityId, "local_accounts", ...)` checks `sync_outbox`. If a local change is pending, remote overwrite is **rejected** (`ConflictDecision.REJECT_REMOTE`).
2. **Timestamp Monotonicity:** `SyncConflictResolver.resolveIncomingChange` evaluates `remoteVersion` vs `localTimestamp`. Stale remote writes are rejected.
3. **Safe Defaulting on Deserialization:** If an older remote document or legacy backup without `operationProvider` is deserialized, Moshi defaults to `"EARTHLINK"` (or preserves existing local provider if already present in Room).
4. **Copy-Preservation in Repositories:** In `LocalAccountRepositoryImpl.saveAccount` (lines 1207–1222) and `bindIspIdentity` (lines 1181–1185), `existing.operationProvider` must be explicitly preserved across `.copy()` calls.

#### Q3: How is local provider state preserved across restore / offline use?
1. **Local SQLite Backup/Restore:** `BackupManager.kt` copies the SQLite database table-by-table. Because `operationProvider` resides in `local_accounts` SQLite schema, it is completely preserved across all offline backup and restore operations.
2. **Cloud Restore on New Device:** When a new device signs in to Firebase and downloads `local_accounts`, the Moshi adapter reads `operationProvider` from Firestore and persists it into the local Room database.
3. **Database Migration:** Adding `operationProvider` to Room requires a standard Room migration (Migration 18 -> 19) with `ALTER TABLE local_accounts ADD COLUMN operationProvider TEXT NOT NULL DEFAULT 'EARTHLINK'`.

---

## 6. Credential Lifecycle Audit (Step 0.6)

### 6.1 Current Credential Storage in `PreferenceManager`
- All operator credentials live in encrypted preferences (`PreferenceManager.kt`).
- Keys:
  - `KEY_TOKEN` (`"enc_ref_token"`): Session token (Earthlink bearer token or Google OAuth session `google_oauth_session_$uid`).
  - `KEY_USERNAME` / `KEY_PASSWORD`: Operator login credentials.
  - `KEY_DEPOSIT_PASSWORD`: Reseller deposit box PIN for financial mutations.
  - `KEY_ISP_ADMIN_USERNAME` / `KEY_ISP_ADMIN_PASSWORD`: ISP Admin API credentials.
  - `KEY_EARTHLINK_API_TOKEN` (`"enc_earthlink_api_token"`): Cached live EarthLink bearer token.
  - `KEY_ISP_CREDENTIAL_OWNER` (`"isp_credential_owner"`): Session token under which resident ISP credentials were saved.

### 6.2 Credential Invalidation Triggers

| Lifecycle Event | Current EarthLink Behavior | Required SAMM Behavior |
|---|---|---|
| **User Logout (`AuthViewModel.logout`)** | Calls `prefs.clearAuthToken()`. Preserves username if "Remember Me" active. | Invalidate active SAMM session token. Clear cached SAMM bearer token. |
| **Firebase Sign Out (`SyncRepositoryImpl.signOut`)** | Wipes `KEY_EARTHLINK_API_TOKEN`, `KEY_TOKEN`, `KEY_ISP_ADMIN_*`, and clears credentials. | Clear SAMM API token, SAMM base URL cache, and SAMM credentials. |
| **Clear Credentials (`PreferenceManager.clearCredentials`)** | Removes `KEY_TOKEN`, `KEY_DEPOSIT_PASSWORD`, `KEY_ISP_ADMIN_*`, `KEY_EARTHLINK_API_TOKEN`. | Remove `KEY_SAMM_API_TOKEN` and `KEY_SAMM_ADMIN_*`. |
| **Account Switch (`AuthViewModel.signInWithGoogle`)** | Compares `prefs.getIspCredentialOwner() != newToken`. If mismatched, calls `prefs.clearIspAdminCredentials()` (TQ-18 Item 9). | Must clear SAMM admin credentials and token when `isp_credential_owner` mismatches. |
| **HTTP 401 Unauthorized** | In `EarthlinkGatewayImpl`: If Google session, calls `prefs.saveEarthlinkApiToken(null)`; if direct login, calls `prefs.clearAuthToken()`. | In SAMM adapter: Invalidate cached SAMM bearer token (`samm_...`). Do not log user out of app if signed in via Google. |
| **Settings Server URL Change** | N/A (EarthLink URL is hardcoded: `rapi.earthlink.iq`). | When user edits SAMM Server URL in Settings, **immediately purge** cached SAMM API token. |
| **Settings Credentials Change** | Calls `prefs.saveIspAdminPassword(...)`, triggers settings sync. | When user edits SAMM API token or credentials, immediately invalidate previous client instance. |
| **Database Reset (`clearAllData`)** | Increments generation counter, wipes Room data. | Flush cached credentials and tokens. |

---

## 7. Comprehensive ISP Provider Operation Matrix (Step 0.7)

Comparison between Earthlink Gateway and SAMM REST API (version 5.1.15, OpenAPI 3.1.0).

| # | Operation Name | EarthLink Gateway Mechanism | SAMM API Equivalent | Parity Classification | Architectural & Product Decision |
|---|---|---|---|---|---|
| 1 | **Reseller Login / Auth** | Form-POST `/api/reseller/token` with username, password, loginType=1, grant_type=password | HTTP Bearer Token (`samm_...`) configured in settings, verified via `GET /api/v1/me` | **Mapped** | EarthLink logs in via credentials to get temporary token. SAMM uses persistent API bearer token configured in Settings. |
| 2 | **Reseller Wallet Balance** | `GET /api/reseller/balance` -> Double (reseller wallet in IQD) | No reseller deposit wallet in SAMM. System stats via `GET /api/v1/stats/overview` or `GET /api/v1/invoices` | **Not Supported on SAMM / Mapped** | SAMM does not use reseller deposit debiting. When SAMM is active, reseller balance displays `0.0` or N/A (non-applicable). |
| 3 | **Package / Plan Catalog** | `GET /api/reseller/packages` -> List of `{accountIndex, accountName}` | `GET /api/v1/plans` -> List of `{id, name, price, ...}` | **Full Parity** | `SasGateway.getPackages()` maps SAMM plans to `AccountPackage`. |
| 4 | **Package Cost Inspection** | `GET /api/reseller/accountcost?accountIndex={id}` -> Double | Available directly in `GET /api/v1/plans/{id}` payload (`price`) | **Full Parity** | `SasGateway.getPackageCost()` extracts plan price directly from plan details. |
| 5 | **Search Subscribers** | `GET /api/reseller/searchusers?query={q}&startIndex={s}&rowCount={r}` | `GET /api/v1/customers?search={q}&page={p}&per_page={r}` | **Full Parity** | `SasGateway.searchUsers()` adapts query and paginates results into standard `UserListResponse`. |
| 6 | **Subscriber Detail** | `GET /api/reseller/userdetail?userIndex={id}` -> `UserDetail` | `GET /api/v1/customers/{id}` -> Customer object with plan, status, usage | **Full Parity** | `SasGateway.getUserDetail()` maps customer fields to `UserDetail`. |
| 7 | **Username Availability** | `GET /api/reseller/checkusername?userId={u}` -> Boolean | `GET /api/v1/customers?search={u}` (empty result = available) | **Full Parity** | `SasGateway.checkUsernameAvailable()` checks if query returns zero matching customers. |
| 8 | **Customer Pre-creation** | `POST /api/reseller/createcustomer` with name, phone | Unified with `POST /api/v1/customers` | **Mapped / Simplified** | EarthLink requires 2 steps (customer, then user). SAMM creates customer + username in a single atomic call. |
| 9 | **Durable Activation** | `POST /api/reseller/createuserusingdeposit` with username, phone, accountIndex, depositPassword | `POST /api/v1/customers` with username, name, phone, plan_id, password, `generate_invoice=false` | **Full Parity (Durable)** | Governed by `PendingExternalOperation(operationType="ACTIVATION")`. Materializes debt upon verified success. |
| 10a | **Durable Renewal (`RENEWAL`)** | `POST /api/reseller/refilluserdeposit` with userId, depositPassword | `POST /api/v1/customers/{id}/renew` with `generate_invoice=false` | **Full Parity (Durable)** | Governed by `PendingExternalOperation(operationType="RENEWAL")`. Materializes debt upon verified success. Full functional and financial parity on SAMM. |
| 10b | **Deposit Refill (`REFILL`)** | `POST /api/reseller/refilluserdeposit` with userId, depositPassword | Not supported on SAMM (no reseller deposit mechanism) | **UNSUPPORTED on SAMM (EarthLink-Only)** | SAMM does not have reseller deposit balance or deposit refill debiting. Any `REFILL` operation targeting `ALAMIRY` must fail closed as `UnsupportedProviderOperationException` and never route to `/renew` or billing. |
| 11 | **Grace Extension** | `POST /api/reseller/extenduser?userIndex={id}` | `POST /api/v1/customers/{id}/reset-limit` or update via `PATCH` | **Mapped / EarthLink-Preferred** | EarthLink extends subscription grace. For SAMM, map to `/reset-limit` or mark provider-unsupported. |
| 12 | **Toggle User Active** | `POST /api/reseller/toggleuseractive?userIndex={id}&active={b}` | `POST /api/v1/customers/{id}/activate`<br>`POST /api/v1/customers/{id}/suspend` | **Full Parity** | Calls `activate` if true, `suspend` if false. |
| 13 | **Change Plan / Package** | `POST /api/reseller/changeaccounttype` with userIndex, accountIndex | `POST /api/v1/customers/{id}/assign-plan` with `plan_id` | **Full Parity** | Maps plan index/ID and dispatches to SAMM assign-plan endpoint. |
| 14 | **Show PPP Password** | `GET /api/reseller/showuserpassword` | Customer object from `GET /api/v1/customers/{id}` contains password | **Full Parity** | Retrieved from customer detail endpoint. |
| 15 | **Change PPP Password** | `POST /api/reseller/changeuserpassword` with userIndex, newPass | `PATCH /api/v1/customers/{id}` with `password` field | **Full Parity** | Updates customer password field in SAMM. |
| 16 | **Show Account Password** | `GET /api/reseller/showaccountpassword` (EarthLink portal password) | Not applicable in SAMM (single customer password) | **EarthLink-Only** | Only active when provider is `EARTHLINK`. Hidden/disabled for `ALAMIRY`. |
| 17 | **Change Account Password** | `POST /api/reseller/changeaccountpassword` | Not applicable in SAMM | **EarthLink-Only** | Only active when provider is `EARTHLINK`. |
| 18 | **Update Display Name** | `POST /api/reseller/updateuserdisplayname` with userIndex, newName | `PATCH /api/v1/customers/{id}` with `name` field | **Full Parity** | Updates customer name field in SAMM. |
| 19 | **Create Test User** | `POST /api/reseller/createtestuser` with username, phone, accountIndex | Not supported natively in SAMM | **EarthLink-Only** | Test user creation is EarthLink-specific. Disabled/hidden for `ALAMIRY`. |
| 20 | **Test Users Count** | `GET /api/reseller/testuserscount` | Not supported natively in SAMM | **EarthLink-Only** | Returns 0 for `ALAMIRY`. |
| 21 | **Prepaid Needed Forecast** | `GET /api/reseller/prepaidneeded?days={d}` | Calculated from local accounts if server lacks endpoint | **Local Calculation Fallback** | Falls back to local accounts debt calculation when provider is `ALAMIRY`. |
| 22 | **Account Statement** | `GET /api/reseller/accountstatement` -> List of ledger entries | `GET /api/v1/invoices` or `GET /api/v1/customers/{id}/timeline` | **Mapped / Local History** | Local ledger is canonical financial truth. SAMM invoices must NOT duplicate local ledger. |
| 23 | **Disconnect Active Session** | Not exposed in EarthLink UI | `POST /api/v1/customers/{id}/disconnect` or `/sessions/disconnect` | **SAMM Bonus / Future** | Available in SAMM API; deferred to future task. |
| 24 | **Customer Usage Stats** | Not exposed in EarthLink UI | `GET /api/v1/customers/{id}/usage` | **SAMM Bonus / Future** | Available in SAMM API; deferred to future task. |

---

## 8. Architectural Seam Design Requirements

To ensure zero regression against existing EarthLink functionality:

1. **`SasGatewayRouter` Boundary:**
   The router inspects the target subscriber's `LocalAccount.operationProvider` (defaulting to `"EARTHLINK"`):
   - If `"EARTHLINK"` -> delegates to `EarthlinkGatewayImpl`.
   - If `"ALAMIRY"` -> delegates to `SammGatewayImpl`.
2. **Pending Operation Provider Immutability:**
   When a pending operation is created, `operationProvider` is recorded directly inside `PendingExternalOperation`.
   During recovery or background sweep, the operation's own recorded provider is used to route verification.
3. **Strict Fallback Prohibition:**
   If a SAMM call fails, times out, or returns inconclusive, the application **must NEVER** fall back to EarthLink.
4. **Local Ledger Exclusivity:**
   SAMM operations must use `generate_invoice = false`. Local financial ledger entries (`local_ledger_entries`) remain the sole financial bookkeeping authority.
5. **Unsupported Refill on SAMM:**
   REFILL operations targeting ALAMIRY must fail closed as `UnsupportedProviderOperationException` and never route to `/renew` or billing.

---

## 9. Gate Verification Checklist

- [x] Baseline test suite captured: **811/811 passing** (0 failures, 0 errors, 0 skipped).
- [x] All 27 `EarthlinkGateway` consumers across the codebase enumerated into caller table.
- [x] All pending-operation producers (`ACTIVATION`, `REFILL`, `RENEWAL`, `EXTEND`, `TEST_USER`) identified.
- [x] All recovery entry points (startup recovery, periodic sync sweep, direct serialized verification) audited.
- [x] `LocalAccount` serialization pathways traced and protection mechanisms defined.
- [x] Credential lifecycle audited and SAMM token invalidation conditions established.
- [x] Comprehensive Provider Operation Matrix completed with Earthlink vs SAMM parity classifications.
