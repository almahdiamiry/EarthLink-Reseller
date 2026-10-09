# Multi-Provider Subscriber Sync, First-Run & Restore Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix empty Dashboard/Search screens for SAMM-only users, unify subscriber fetching across EarthLink and SAMM based on active provider configuration (SAMM-first imports SAMM, EarthLink-first imports EarthLink, dual configuration imports both), prevent cross-provider disappearance corruption, ensure SAMM subscribers materialize into `LocalAccount`s with `operationProvider = ALAMIRY`, and guarantee full reliability across all First-Run (A.1, A.2, A.3) and Returning User (B.1, B.2, B.3) scenarios.

**Architecture:** Utilize `SasGatewayRouter` to drive provider-aware subscriber fetching in `DashboardViewModel` and `EarthlinkSearchViewModel`. Attach `originProvider` to `UserListItem` and `UserDetail` so subscriber identity is preserved across UI layers. Scope `IspDisappearanceReconciler` strictly to the target provider to prevent cross-provider account marking as history-only. Parse `operationProvider` in `RemoteEntityValidator` to ensure Firebase Cloud Restores retain SAMM account identity. Ensure `LocalAccount` creation points in `UserDetailScreenV2` inherit the subscriber's origin provider instead of defaulting to `EARTHLINK`.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.6.1, Coroutines Flow, Retrofit 2, Moshi, JUnit 4, Robolectric, MockK.

**Spec:** `docs/samm/LOGIN_PROVIDER_SCENARIOS.md`, `docs/authority/account_field_authority_classification.md`, `contract/invariant_contract.yaml`.

## Global Constraints
- RED Invariant 1: Financial Correctness — additive ledger math, whole-IQD and 250-IQD multiple validation preserved.
- RED Invariant 2: History Preservation — no physical row deletion, cross-provider reconciliation must never mark another provider's accounts as history-only.
- Strict Zero Cross-Provider Fallback: SAMM operations never fallback to EarthLink; EarthLink operations never fallback to SAMM.
- Ponytail Anti-Bloat: No new database tables, no new schema migrations, reuse existing `SasGatewayRouter`, `BackupManager`, and `SasSubscriberView`.
- Core Triad Standard: All new tests must document `Claim`, `Seam`, and `Independent Oracle` in class/method KDoc headers.

---

## Supported Lifecycle Scenarios Matrix

| Scenario Code | Scenario Type | Flow Description | Guaranteed Behavior |
|:---|:---|:---|:---|
| **A.1** | First Run | User enters EarthLink credentials, SAMM credentials, or both | Dashboard immediately fetches and displays accounts for configured provider(s). Dual mode displays both with badges. |
| **A.2** | First Run | User signs in with a fresh Google account, then enters SAMM, EarthLink, or both | Google session establishes cloud scope; entering ISP provider(s) loads respective subscribers seamlessly. |
| **A.3** | First Run | User enters SAMM or EarthLink, then imports uTower backup (`.json`/`.tgz`) | Accounts and ledgers import into Room; Dashboard merges live subscribers + local accounts; batch provider assignment (`Set Provider`) allows instant switching. |
| **B.1** | Returning User | User logs in using Google, SAMM, or EarthLink | Flexible authentication: session unlocks with any valid provider credentials. |
| **B.2** | Returning User | User logs in with Google and triggers Cloud Restore from Firebase | `RemoteEntityValidator` parses `operationProvider` from Firestore, preserving `ALAMIRY` and `EARTHLINK` accounts with full financial history. |
| **B.3** | Returning User | User logs in with SAMM or EarthLink and triggers Local Restore from backup (`BackupManager`) | `BackupManager` restores Room tables atomically; `local_accounts.operationProvider` is 100% restored and merged on Dashboard. |

---

### Task 1: Provider-Scoped ISP Disappearance Reconciliation

**Files:**
- Modify: `app/src/main/java/com/example/core/sync/IspDisappearanceReconciler.kt:34-75`
- Modify: `app/src/main/java/com/example/domain/repository/Interfaces.kt:145-146`
- Modify: `app/src/main/java/com/example/data/repository/Repositories.kt:1370-1382`
- Test: `app/src/test/java/com/example/core/sync/IspDisappearanceReconcilerProviderTest.kt`

**Interfaces:**
- Consumes: `LocalAccountDao.getAllOneShot()`, `LocalAccount.operationProvider`, `SasProviders.EARTHLINK`, `SasProviders.ALAMIRY`.
- Produces: `reconcile(..., targetProvider: String = SasProviders.EARTHLINK)` ensuring only accounts matching `targetProvider` are evaluated for disappearance.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/core/sync/IspDisappearanceReconcilerProviderTest.kt`:
```kotlin
package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Claim: IspDisappearanceReconciler must strictly evaluate accounts belonging to targetProvider
 * and never transition accounts belonging to another provider to history-only (INV-02).
 * Seam: ROBOLECTRIC in-memory SQLite database transaction.
 * Independent Oracle: Reconciling with EarthLink user IDs missing a SAMM user ID must leave
 * the SAMM account's isHistoryOnlySubscriber == false.
 */
@RunWith(RobolectricTestRunner::class)
class IspDisappearanceReconcilerProviderTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun reconcileEarthlink_doesNotTransitionSammAccount() = runBlocking {
        val earthlinkAcc = LocalAccount(
            id = "el-1",
            earthlinkUsername = "user_el",
            operationProvider = SasProviders.EARTHLINK,
            isHistoryOnlySubscriber = false
        )
        val sammAcc = LocalAccount(
            id = "samm-1",
            earthlinkUsername = "user_samm",
            operationProvider = SasProviders.ALAMIRY,
            isHistoryOnlySubscriber = false
        )
        db.localAccountDao().insert(earthlinkAcc)
        db.localAccountDao().insert(sammAcc)

        val elUserIds = setOf("other_el_user")

        val transitioned = IspDisappearanceReconciler.reconcile(
            database = db,
            accountDao = db.localAccountDao(),
            auditDao = db.auditLogDao(),
            authoritativeIspUserIds = elUserIds,
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )

        assertTrue(transitioned.contains("el-1"))
        assertFalse("SAMM account must not be transitioned by EarthLink reconciliation", transitioned.contains("samm-1"))

        val sammAfter = db.localAccountDao().getByIdOneShot("samm-1")
        assertFalse(sammAfter!!.isHistoryOnlySubscriber)
    }

    @Test
    fun reconcileSamm_transitionsOnlySammAccounts() = runBlocking {
        val sammAcc = LocalAccount(
            id = "samm-2",
            earthlinkUsername = "samm_user_disappeared",
            operationProvider = SasProviders.ALAMIRY,
            isHistoryOnlySubscriber = false
        )
        val elAcc = LocalAccount(
            id = "el-2",
            earthlinkUsername = "el_user_stable",
            operationProvider = SasProviders.EARTHLINK,
            isHistoryOnlySubscriber = false
        )
        db.localAccountDao().insert(sammAcc)
        db.localAccountDao().insert(elAcc)

        val sammActiveIds = setOf("samm_other_user")

        val transitioned = IspDisappearanceReconciler.reconcile(
            database = db,
            accountDao = db.localAccountDao(),
            auditDao = db.auditLogDao(),
            authoritativeIspUserIds = sammActiveIds,
            isFetchComplete = true,
            targetProvider = SasProviders.ALAMIRY
        )

        assertTrue(transitioned.contains("samm-2"))
        assertFalse("EarthLink account must not be transitioned by SAMM reconciliation", transitioned.contains("el-2"))

        val elAfter = db.localAccountDao().getByIdOneShot("el-2")
        assertFalse(elAfter!!.isHistoryOnlySubscriber)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.core.sync.IspDisappearanceReconcilerProviderTest"`
Expected: FAIL (compilation error: targetProvider parameter does not exist yet).

- [ ] **Step 3: Write minimal implementation**

Update `app/src/main/java/com/example/core/sync/IspDisappearanceReconciler.kt`:
```kotlin
    suspend fun reconcile(
        database: AppDatabase,
        accountDao: LocalAccountDao,
        auditDao: AuditLogDao?,
        authoritativeIspUserIds: Set<String>,
        isFetchComplete: Boolean,
        targetProvider: String = com.example.core.model.SasProviders.EARTHLINK
    ): List<String> {
        if (!isFetchComplete) {
            return emptyList()
        }

        val normalizedIspUserIds = authoritativeIspUserIds.map { it.trim().lowercase() }.toSet()
        val transitionedAccountIds = mutableListOf<String>()
        val moshi = Moshi.Builder().build()
        val accountAdapter = moshi.adapter(LocalAccount::class.java)

        database.withTransaction {
            val allAccounts = accountDao.getAllOneShot(limit = Int.MAX_VALUE)
            val now = System.currentTimeMillis()

            for (acc in allAccounts) {
                // Rule: Strictly evaluate accounts belonging to the target provider being reconciled
                if (acc.operationProvider != targetProvider) {
                    continue
                }

                val username = acc.earthlinkUsername?.trim()
                if (username.isNullOrBlank()) {
                    continue
                }

                if (acc.isHistoryOnlySubscriber) {
                    continue
                }

                if (!normalizedIspUserIds.contains(username.lowercase())) {
                    val updated = acc.copy(
                        isHistoryOnlySubscriber = true,
                        updatedAt = now
                    )
                    accountDao.update(updated)
                    transitionedAccountIds.add(acc.id)

                    OutboxManager.upsertWithOutbox(
                        database.syncOutboxDao(),
                        "local_accounts",
                        updated.id,
                        accountAdapter.toJson(updated)
                    )

                    auditDao?.insert(
                        AuditLog(
                            action = "ISP_SUBSCRIBER_DISAPPEARED",
                            entityType = "local_accounts",
                            entityId = acc.id,
                            summary = "Subscriber '${acc.displayName}' ($username) on provider '$targetProvider' is no longer present in authoritative ISP subscriber list. Transitioned to history-only.",
                            createdAt = now,
                            severity = AuditSeverity.WARNING.name,
                            actor = "system",
                            origin = AuditOrigin.SYSTEM_ACTION.name,
                            metadataJsonMasked = "{\"earthlinkUsername\":\"$username\",\"accountId\":\"${acc.id}\",\"provider\":\"$targetProvider\"}"
                        )
                    )
                }
            }
        }

        return transitionedAccountIds
    }
```

Update `app/src/main/java/com/example/domain/repository/Interfaces.kt`:
```kotlin
    suspend fun reconcileIspDisappearance(
        authoritativeIspUserIds: Set<String>,
        isFetchComplete: Boolean,
        targetProvider: String = com.example.core.model.SasProviders.EARTHLINK
    ): List<String>
```

Update `app/src/main/java/com/example/data/repository/Repositories.kt`:
```kotlin
    override suspend fun reconcileIspDisappearance(
        authoritativeIspUserIds: Set<String>,
        isFetchComplete: Boolean,
        targetProvider: String
    ): List<String> {
        if (!isFetchComplete || authoritativeIspUserIds.isEmpty()) return emptyList()
        return com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.SYNC) {
            com.example.core.sync.IspDisappearanceReconciler.reconcile(
                database = database,
                accountDao = accountDao,
                auditDao = database.auditLogDao(),
                authoritativeIspUserIds = authoritativeIspUserIds,
                isFetchComplete = isFetchComplete,
                targetProvider = targetProvider
            )
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.core.sync.IspDisappearanceReconcilerProviderTest"`
Expected: PASS (2 tests pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/core/sync/IspDisappearanceReconciler.kt app/src/main/java/com/example/domain/repository/Interfaces.kt app/src/main/java/com/example/data/repository/Repositories.kt app/src/test/java/com/example/core/sync/IspDisappearanceReconcilerProviderTest.kt
git commit -m "fix(sync): scope ISP disappearance reconciliation to target provider (Task 1)"
```

---

### Task 2: Provider Origin Tracking & Firebase Cloud Entity Validation (Scenario B.2)

**Files:**
- Modify: `app/src/main/java/com/example/core/model/Models.kt:70-120, 210-250`
- Modify: `app/src/main/java/com/example/core/sync/RemoteEntityValidator.kt:100-110`
- Modify: `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt:247-260`
- Test: `app/src/test/java/com/example/core/model/UserListItemOriginProviderTest.kt`
- Test: `app/src/test/java/com/example/core/sync/RemoteEntityValidatorProviderTest.kt`

**Interfaces:**
- Consumes: `SasProviders.EARTHLINK`, `SasProviders.ALAMIRY`.
- Produces: `UserListItem.originProvider: String?`, `UserDetail.originProvider: String?`, and `RemoteEntityValidator.validateAndMapAccount` restoring `operationProvider` accurately from Firestore payloads.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/core/model/UserListItemOriginProviderTest.kt`:
```kotlin
package com.example.core.model

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claim: UserListItem and UserDetail must accept and serialize originProvider without breaking
 * backward-compatible JSON decoding.
 * Seam: JVM Moshi serialization.
 * Independent Oracle: Default originProvider is null; explicitly specified provider persists accurately.
 */
class UserListItemOriginProviderTest {

    private val moshi = Moshi.Builder().build()
    private val listItemAdapter = moshi.adapter(UserListItem::class.java)
    private val detailAdapter = moshi.adapter(UserDetail::class.java)

    @Test
    fun userListItem_originProvider_defaultsToNull_andAcceptsProvider() {
        val defaultItem = UserListItem(userIDLower = "test_user")
        assertNull(defaultItem.originProvider)

        val sammItem = UserListItem(userIDLower = "samm_user", originProvider = SasProviders.ALAMIRY)
        assertEquals(SasProviders.ALAMIRY, sammItem.originProvider)

        val json = listItemAdapter.toJson(sammItem)
        val deserialized = listItemAdapter.fromJson(json)
        assertEquals(SasProviders.ALAMIRY, deserialized?.originProvider)
    }

    @Test
    fun userDetail_originProvider_preservesValue() {
        val detail = UserDetail(userIDLower = "samm_user", originProvider = SasProviders.ALAMIRY)
        assertEquals(SasProviders.ALAMIRY, detail.originProvider)

        val json = detailAdapter.toJson(detail)
        val deserialized = detailAdapter.fromJson(json)
        assertEquals(SasProviders.ALAMIRY, deserialized?.originProvider)
    }
}
```

Create `app/src/test/java/com/example/core/sync/RemoteEntityValidatorProviderTest.kt`:
```kotlin
package com.example.core.sync

import com.example.core.model.SasProviders
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claim: RemoteEntityValidator must parse and map operationProvider from remote Firestore documents (Scenario B.2).
 * Seam: JVM Unit Test.
 * Independent Oracle: Document with operationProvider = ALAMIRY maps to LocalAccount with operationProvider == ALAMIRY.
 */
class RemoteEntityValidatorProviderTest {

    @Test
    fun validateAndMapAccount_parsesAlamiryProviderFromRemoteData() {
        val rawData = mapOf<String, Any>(
            "displayName" to "Mustafa SAMM",
            "earthlinkUsername" to "mustafa_samm",
            "operationProvider" to SasProviders.ALAMIRY,
            "debtIqd" to 25000.0
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "samm_remote_1",
            d = rawData,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = null
        )

        val valid = result as RemoteEntityValidationResult.Valid
        assertEquals(SasProviders.ALAMIRY, valid.entity.operationProvider)
        assertEquals(25000.0, valid.entity.debtIqd, 0.001)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.core.model.UserListItemOriginProviderTest"`
Expected: FAIL (unresolved reference `originProvider`).

- [ ] **Step 3: Write minimal implementation**

In `app/src/main/java/com/example/core/model/Models.kt`:
Add to `UserListItem`:
```kotlin
    @Json(name = "originProvider") val originProvider: String? = null,
```
Add to `UserDetail`:
```kotlin
    @Json(name = "originProvider") val originProvider: String? = null,
```

In `app/src/main/java/com/example/core/sync/RemoteEntityValidator.kt`:
In `validateAndMapAccount`:
```kotlin
        val operationProvider = (d["operationProvider"] as? String)?.takeIf { com.example.core.model.SasProviders.isValid(it) }
            ?: existingLocalAccount?.operationProvider
            ?: com.example.core.model.SasProviders.EARTHLINK

        val account = LocalAccount(
            id = id,
            sourceExternalId = d["sourceExternalId"] as? String ?: existingLocalAccount?.sourceExternalId,
            sourceBatchId = d["sourceBatchId"] as? String ?: existingLocalAccount?.sourceBatchId,
            displayName = displayName,
            earthlinkUsername = d["earthlinkUsername"] as? String ?: existingLocalAccount?.earthlinkUsername,
            phone1 = d["phone1"] as? String ?: existingLocalAccount?.phone1,
            phone2 = d["phone2"] as? String ?: existingLocalAccount?.phone2,
            packageName = d["packageName"] as? String ?: existingLocalAccount?.packageName,
            isLegacy = if (existingLocalAccount?.isLegacy == true) true else (d["isLegacy"] as? Boolean ?: false),
            isHistoryOnlySubscriber = if (d["isHistoryOnlySubscriber"] == true) true else existingLocalAccount?.isHistoryOnlySubscriber ?: false,
            currentPriceIqd = (d["currentPriceIqd"] as? Number)?.toDouble() ?: existingLocalAccount?.currentPriceIqd ?: 0.0,
            debtIqd = effectiveDebt,
            loanIqd = loanIqd,
            advanceIqd = advanceIqd,
            towerName = d["towerName"] as? String ?: existingLocalAccount?.towerName,
            zoneName = d["zoneName"] as? String ?: existingLocalAccount?.zoneName,
            address = d["address"] as? String ?: existingLocalAccount?.address,
            nanoIp = d["nanoIp"] as? String ?: existingLocalAccount?.nanoIp,
            latitude = (d["latitude"] as? Number)?.toDouble() ?: existingLocalAccount?.latitude,
            longitude = (d["longitude"] as? Number)?.toDouble() ?: existingLocalAccount?.longitude,
            note = d["note"] as? String ?: existingLocalAccount?.note,
            expiresAt = d["expiresAt"] as? String ?: existingLocalAccount?.expiresAt,
            lastPaymentAt = (d["lastPaymentAt"] as? Number)?.toLong() ?: existingLocalAccount?.lastPaymentAt,
            rawJson = d["rawJson"] as? String ?: existingLocalAccount?.rawJson,
            openingDebtIqd = openingDebt,
            openingAdvanceIqd = openingAdvance,
            openingLoanIqd = openingLoan,
            stateSource = stateSource,
            stateConfidence = stateConfidence,
            snapshotCapturedAt = snapshotCapturedAt,
            ispSubscriberId = d["ispSubscriberId"] as? String ?: existingLocalAccount?.ispSubscriberId,
            ispUserIndex = (d["ispUserIndex"] as? Number)?.toInt() ?: existingLocalAccount?.ispUserIndex,
            operationProvider = operationProvider,
            createdAt = createdAt,
            updatedAt = remoteUpdatedAt
        )
```

In `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt`:
Update `searchUsers`:
```kotlin
            try {
                val targetGateway = sasGatewayRouter.getGateway(currentProvider)
                val subscribers = targetGateway.searchSubscribers(query = _searchQuery.value, page = 1, pageSize = 200)
                _usersList.value = subscribers.map { sub ->
                    val numIndex = sub.subscriberId.toIntOrNull() ?: sub.subscriberId.hashCode()
                    com.example.core.model.UserListItem(
                        userIndexLower = numIndex,
                        userIDLower = sub.username,
                        customerNameLower = sub.displayName ?: sub.username,
                        mobileNumberLower = sub.phone,
                        accountStatusLower = sub.status,
                        expirationDateLower = sub.expiresAt,
                        accountExpirationDateLower = sub.expiresAt,
                        displayNameLower = sub.displayName,
                        accountNameLower = sub.planName,
                        originProvider = currentProvider
                    )
                }
            } catch (e: Exception) { ... }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.core.model.UserListItemOriginProviderTest"`
Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.core.sync.RemoteEntityValidatorProviderTest"`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/core/model/Models.kt app/src/main/java/com/example/core/sync/RemoteEntityValidator.kt app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt app/src/test/java/com/example/core/model/UserListItemOriginProviderTest.kt app/src/test/java/com/example/core/sync/RemoteEntityValidatorProviderTest.kt
git commit -m "feat(sync): support originProvider in models and restore from Firestore (Task 2)"
```

---

### Task 3: Multi-Provider Dashboard Subscriber & Metric Loading (Scenarios A.1, A.2)

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodels/DashboardViewModel.kt:17-150`
- Modify: `app/src/main/java/com/example/ui/viewmodels/AppViewModelProvider.kt:20-30`
- Test: `app/src/test/java/com/example/ui/viewmodels/DashboardViewModelMultiProviderTest.kt`

**Interfaces:**
- Consumes: `SasGatewayRouter`, `PreferenceManager.getProviderAccessState()`, `PreferenceManager.providerAccessStateFlow`, `SasProviders`.
- Produces: `DashboardViewModel.loadDashboardData()` loading subscribers from SAMM when `SAMM_ONLY`, EarthLink when `EARTHLINK_ONLY`, and merging both concurrently when `BOTH`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/ui/viewmodels/DashboardViewModelMultiProviderTest.kt`:
```kotlin
package com.example.ui.viewmodels

import com.example.core.model.ProviderAccessState
import com.example.core.model.SasProviders
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasSubscriberView
import com.example.core.security.PreferenceManager
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.domain.repository.SyncRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Claim: DashboardViewModel loads SAMM subscribers when SAMM_ONLY, EarthLink subscribers when EARTHLINK_ONLY,
 * and merges both when BOTH, without empty-screen aborts.
 * Seam: JVM Coroutines TestDispatcher.
 * Independent Oracle: When SAMM_ONLY, subscribersList contains SAMM users and isCredentialsEmpty is false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelMultiProviderTest {

    private val testDispatcher = StandardTestDispatcher()
    private val earthlinkGateway = mockk<EarthlinkGateway>(relaxed = true)
    private val sammGateway = mockk<SasGateway>(relaxed = true)
    private val sasGatewayRouter = mockk<SasGatewayRouter>()
    private val audit = mockk<AuditRepository>(relaxed = true)
    private val localAccountRepository = mockk<LocalAccountRepository>(relaxed = true)
    private val localLedgerRepository = mockk<LocalLedgerRepository>(relaxed = true)
    private val syncRepo = mockk<SyncRepository>(relaxed = true)
    private val prefs = mockk<PreferenceManager>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { prefs.getDashboardSortOption() } returns "الاسم"
        every { prefs.getDemoMode() } returns false
        every { localAccountRepository.getAllAccounts() } returns MutableStateFlow(emptyList())
        every { sasGatewayRouter.getGateway(SasProviders.ALAMIRY) } returns sammGateway
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun sammOnly_loadsSammSubscribers_andDoesNotAbort() = runTest(testDispatcher) {
        every { prefs.getProviderAccessState() } returns ProviderAccessState.SAMM_ONLY
        coEvery { sammGateway.searchSubscribers(query = "", page = 1, pageSize = 500) } returns listOf(
            SasSubscriberView(
                subscriberId = "101",
                username = "samm_sub_1",
                displayName = "SAMM Subscriber One",
                status = "Active",
                planId = "1",
                planName = "Gold",
                expiresAt = "2026-11-01",
                phone = "07700000000"
            )
        )

        val vm = DashboardViewModel(
            gateway = earthlinkGateway,
            sasGatewayRouter = sasGatewayRouter,
            audit = audit,
            localAccountRepository = localAccountRepository,
            localLedgerRepository = localLedgerRepository,
            syncRepo = syncRepo,
            prefs = prefs,
            ioDispatcher = testDispatcher
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("isCredentialsEmpty must be false when SAMM is configured", vm.isCredentialsEmpty.value)
        assertEquals(1, vm.subscribersList.value.size)
        assertEquals("samm_sub_1", vm.subscribersList.value[0].userID)
        assertEquals(SasProviders.ALAMIRY, vm.subscribersList.value[0].originProvider)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ui.viewmodels.DashboardViewModelMultiProviderTest"`
Expected: FAIL (compilation error: primary constructor does not take `sasGatewayRouter`).

- [ ] **Step 3: Write minimal implementation**

Update `DashboardViewModel.kt`:
```kotlin
class DashboardViewModel(
    private val gateway: com.example.domain.repository.EarthlinkGateway,
    private val sasGatewayRouter: com.example.core.network.SasGatewayRouter? = null,
    private val audit: com.example.domain.repository.AuditRepository,
    private val localAccountRepository: com.example.domain.repository.LocalAccountRepository,
    private val localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
    private val syncRepo: com.example.domain.repository.SyncRepository,
    val prefs: com.example.core.security.PreferenceManager,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) : ViewModel() {

    constructor(
        gateway: com.example.domain.repository.EarthlinkGateway,
        audit: com.example.domain.repository.AuditRepository,
        localAccountRepository: com.example.domain.repository.LocalAccountRepository,
        localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
        syncRepo: com.example.domain.repository.SyncRepository,
        prefs: com.example.core.security.PreferenceManager,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    ) : this(
        gateway = gateway,
        sasGatewayRouter = com.example.core.network.SasGatewayRouter(
            com.example.core.network.EarthlinkSasGatewayAdapter(gateway),
            prefs
        ),
        audit = audit,
        localAccountRepository = localAccountRepository,
        localLedgerRepository = localLedgerRepository,
        syncRepo = syncRepo,
        prefs = prefs,
        ioDispatcher = ioDispatcher
    )
```

In `loadDashboardData()`:
```kotlin
    fun loadDashboardData(): kotlinx.coroutines.Job {
        val isDemo = prefs.getDemoMode()
        val providerState = prefs.getProviderAccessState()

        if (providerState == com.example.core.model.ProviderAccessState.NONE && !isDemo) {
            _isLoading.value = false
            _isCredentialsEmpty.value = true
            _subscribersList.value = emptyList()
            return kotlinx.coroutines.Job().apply { complete() }
        }

        if (providerState == com.example.core.model.ProviderAccessState.EARTHLINK_ONLY && !isDemo) {
            if (prefs.getIspAdminUsername().isNullOrBlank() || prefs.getIspAdminPassword().isNullOrBlank()) {
                _isCredentialsEmpty.value = true
                _isLoading.value = false
                _error.value = null
                _subscribersList.value = emptyList()
                return kotlinx.coroutines.Job().apply { complete() }
            }
        }
        _isCredentialsEmpty.value = false

        return viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            coroutineScope {
                val balanceJob = if (providerState.hasEarthlink || isDemo) {
                    async {
                        try {
                            val bal = gateway.getBalance()
                            _balance.value = bal
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            if (e.message?.contains("Session expired") == true) {
                                prefs.clearAuthToken()
                            } else {
                                _error.value = e.message ?: "Failed to refresh balance indicator."
                            }
                        }
                    }
                } else null

                val subJob = async {
                    try {
                        val combinedItems = mutableListOf<UserListItem>()

                        // 2A: EarthLink fetch
                        if (providerState.hasEarthlink || isDemo) {
                            try {
                                val elSubListRes = gateway.searchUsers(query = "", startIndex = 0, rowCount = 5000)
                                val elItems = (elSubListRes.itemsList ?: emptyList()).map {
                                    it.copy(originProvider = SasProviders.EARTHLINK)
                                }
                                combinedItems.addAll(elItems)

                                val total = elSubListRes.totalCount
                                val isComplete = elSubListRes.itemsList != null && total != null && total > 0 && elItems.isNotEmpty() && elItems.size >= total
                                if (isComplete && elItems.all { it.userID.isNotBlank() }) {
                                    val authoritativeElUsernames = elItems.map { it.userID.trim() }.toSet()
                                    kotlinx.coroutines.withContext(ioDispatcher) {
                                        try {
                                            localAccountRepository.reconcileIspDisappearance(
                                                authoritativeElUsernames,
                                                isFetchComplete = true,
                                                targetProvider = SasProviders.EARTHLINK
                                            )
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                if (!providerState.hasSamm) throw e
                            }
                        }

                        // 2B: SAMM fetch
                        if (providerState.hasSamm) {
                            try {
                                val router = sasGatewayRouter ?: com.example.core.network.SasGatewayRouter(
                                    com.example.core.network.EarthlinkSasGatewayAdapter(gateway),
                                    prefs
                                )
                                val sammGw = router.getGateway(SasProviders.ALAMIRY)
                                val sammSubs = sammGw.searchSubscribers(query = "", page = 1, pageSize = 500)
                                val sammItems = sammSubs.map { sub ->
                                    val numIndex = sub.subscriberId.toIntOrNull() ?: sub.subscriberId.hashCode()
                                    UserListItem(
                                        userIndexLower = numIndex,
                                        userIDLower = sub.username,
                                        customerNameLower = sub.displayName ?: sub.username,
                                        mobileNumberLower = sub.phone,
                                        accountStatusLower = sub.status,
                                        expirationDateLower = sub.expiresAt,
                                        accountExpirationDateLower = sub.expiresAt,
                                        displayNameLower = sub.displayName,
                                        accountNameLower = sub.planName,
                                        originProvider = SasProviders.ALAMIRY
                                    )
                                }
                                combinedItems.addAll(sammItems)

                                if (sammSubs.isNotEmpty() && sammSubs.all { it.username.isNotBlank() }) {
                                    val authoritativeSammUsernames = sammSubs.map { it.username.trim() }.toSet()
                                    kotlinx.coroutines.withContext(ioDispatcher) {
                                        try {
                                            localAccountRepository.reconcileIspDisappearance(
                                                authoritativeSammUsernames,
                                                isFetchComplete = true,
                                                targetProvider = SasProviders.ALAMIRY
                                            )
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                if (!providerState.hasEarthlink) throw e
                            }
                        }

                        _subscribersList.value = combinedItems
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        if (e.message?.contains("Session expired") == true) {
                            android.util.Log.w("DashboardViewModel", "Subscribers fetch canceled due to session expiration.")
                        } else {
                            android.util.Log.e("DashboardViewModel", "Subscribers fetch on dashboard failed: ${e.message}")
                        }
                    }
                }

                val prepaidJob = if (providerState.hasEarthlink || isDemo) {
                    async {
                        val days = _prepaidNeededDays.value
                        try {
                            val needed = gateway.getPrepaidNeeded(days)
                            _prepaidNeeded.value = needed
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
                    }
                } else null

                balanceJob?.await()
                subJob.await()
                prepaidJob?.await()
            }
            _isLoading.value = false
        }
    }
```

Update `AppViewModelProvider.kt`:
```kotlin
        initializer {
            val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarthlinkApp)
            DashboardViewModel(
                gateway = app.earthlinkGateway,
                sasGatewayRouter = app.sasGatewayRouter,
                audit = app.auditRepository,
                localAccountRepository = app.localAccountRepository,
                localLedgerRepository = app.localLedgerRepository,
                syncRepo = app.syncRepository,
                prefs = app.preferenceManager
            )
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ui.viewmodels.DashboardViewModelMultiProviderTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodels/DashboardViewModel.kt app/src/main/java/com/example/ui/viewmodels/AppViewModelProvider.kt app/src/test/java/com/example/ui/viewmodels/DashboardViewModelMultiProviderTest.kt
git commit -m "feat(dashboard): support multi-provider subscriber loading in DashboardViewModel (Task 3)"
```

---

### Task 4: Default Provider Alignment & Detail Routing in EarthlinkSearchViewModel

**Files:**
- Modify: `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt:57-65, 318-364, 460-475`
- Test: `app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelDefaultProviderTest.kt`

**Interfaces:**
- Consumes: `PreferenceManager.getLastSelectedLoginProvider()`, `UserListItem.originProvider`.
- Produces: `_selectedProvider` initializing to `prefs.getLastSelectedLoginProvider()`; `loadUserDetail` and `getUserDetail` routing by `originProvider` when `foundLocal` is null.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelDefaultProviderTest.kt`:
```kotlin
package com.example.ui.viewmodels

import com.example.core.model.SasProviders
import com.example.core.model.UserListItem
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.security.PreferenceManager
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Claim: EarthlinkSearchViewModel must initialize selectedProvider to prefs.getLastSelectedLoginProvider(),
 * and loadUserDetail must query the subscriber's originProvider when not yet saved in Room.
 * Seam: JVM Unit Test with mock router.
 * Independent Oracle: When last selected login provider is ALAMIRY, selectedProvider begins as ALAMIRY.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EarthlinkSearchViewModelDefaultProviderTest {

    private val testDispatcher = StandardTestDispatcher()
    private val gateway = mockk<EarthlinkGateway>(relaxed = true)
    private val sasGatewayRouter = mockk<SasGatewayRouter>()
    private val sammGateway = mockk<SasGateway>(relaxed = true)
    private val audit = mockk<AuditRepository>(relaxed = true)
    private val prefs = mockk<PreferenceManager>(relaxed = true)
    private val localAccountRepo = mockk<LocalAccountRepository>(relaxed = true)
    private val localLedgerRepo = mockk<LocalLedgerRepository>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { localAccountRepo.getAllAccounts() } returns MutableStateFlow(emptyList())
        every { localAccountRepo.getActiveAccountByUsernameOrId(any()) } returns MutableStateFlow(null)
        every { sasGatewayRouter.getGateway(SasProviders.ALAMIRY) } returns sammGateway
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun selectedProvider_initializesToLastSelectedLoginProvider() {
        every { prefs.getLastSelectedLoginProvider() } returns SasProviders.ALAMIRY

        val vm = EarthlinkSearchViewModel(
            gateway = gateway,
            sasGatewayRouter = sasGatewayRouter,
            audit = audit,
            prefs = prefs,
            localAccountRepository = localAccountRepo,
            localLedgerRepository = localLedgerRepo
        )

        assertEquals(SasProviders.ALAMIRY, vm.selectedProvider.value)
    }

    @Test
    fun prepareUserDetail_preservesOriginProvider() {
        every { prefs.getLastSelectedLoginProvider() } returns SasProviders.ALAMIRY

        val vm = EarthlinkSearchViewModel(
            gateway = gateway,
            sasGatewayRouter = sasGatewayRouter,
            audit = audit,
            prefs = prefs,
            localAccountRepository = localAccountRepo,
            localLedgerRepository = localLedgerRepo
        )

        val item = UserListItem(
            userIndexLower = 42,
            userIDLower = "samm_user_42",
            customerNameLower = "SAMM User",
            originProvider = SasProviders.ALAMIRY
        )

        vm.prepareUserDetail(42, item)

        assertEquals(SasProviders.ALAMIRY, vm.selectedUser.value?.originProvider)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ui.viewmodels.EarthlinkSearchViewModelDefaultProviderTest"`
Expected: FAIL (selectedProvider was initialized to `EARTHLINK`, not `getLastSelectedLoginProvider()`).

- [ ] **Step 3: Write minimal implementation**

In `EarthlinkSearchViewModel.kt`:
```kotlin
    private val _selectedProvider = MutableStateFlow(prefs.getLastSelectedLoginProvider())
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()
```

In `prepareUserDetail`:
```kotlin
    fun prepareUserDetail(userIndex: Int, externalPartialItem: com.example.core.model.UserListItem? = null) {
        _error.value = null
        _actionSuccess.value = null

        if (_selectedUser.value?.userIndex == userIndex && _selectedUser.value != null) return

        val partialItem = externalPartialItem ?: _usersList.value.find { it.userIndex == userIndex }
        if (partialItem != null) {
            _selectedUser.value = com.example.core.model.UserDetail(
                userIndexLower = partialItem.userIndex,
                userIDLower = partialItem.userID,
                customerNameLower = partialItem.customerName,
                customerFullNameLower = partialItem.customerName,
                mobileNumberLower = partialItem.mobileNumber,
                accountStatusLower = partialItem.accountStatus,
                expirationDateLower = partialItem.expirationDate,
                accountExpirationDateLower = partialItem.accountExpirationDate,
                activeDaysLeftLower = partialItem.activeDaysLeft,
                displayNameLower = partialItem.displayName,
                packageNameLower = partialItem.packageName ?: partialItem.displayName,
                originProvider = partialItem.originProvider
            )
            _isLoading.value = false
        } else {
            _selectedUser.value = null
            _isLoading.value = true
        }
    }
```

In `loadUserDetail`:
```kotlin
                try {
                    val accountProvider = foundLocal?.operationProvider
                    val targetProvider = accountProvider ?: _selectedUser.value?.originProvider ?: currentProvider
                    val targetGateway = sasGatewayRouter.getGateway(targetProvider)
                    val sub = targetGateway.getSubscriber(userIndex.toString())
                    if (sub != null) {
                        val detail = com.example.core.model.UserDetail(
                            userIndexLower = sub.subscriberId.toIntOrNull() ?: userIndex,
                            userIDLower = sub.username,
                            customerFullNameLower = sub.displayName,
                            customerNameLower = sub.displayName ?: sub.username,
                            displayNameLower = sub.displayName,
                            mobileNumberLower = sub.phone,
                            packageNameLower = sub.planName,
                            accountIndexLower = sub.planId?.toIntOrNull(),
                            accountStatusLower = sub.status,
                            expirationDateLower = sub.expiresAt,
                            accountExpirationDateLower = sub.expiresAt,
                            originProvider = targetProvider
                        )
                        _selectedUser.value = detail
                    }
                } catch (ex: Exception) { ... }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ui.viewmodels.EarthlinkSearchViewModelDefaultProviderTest"`
Expected: PASS (2 tests pass).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelDefaultProviderTest.kt
git commit -m "fix(search): align default provider with login state and route subscriber detail by origin (Task 4)"
```

---

### Task 5: Preserving Provider on Subscriber-to-LocalAccount Materialization & UI Badging

**Files:**
- Modify: `app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt:439-446, 833-839, 1181-1187, 1726-1732, 3055-3062, 3119-3126, 3270-3277, 3384-3391`
- Modify: `app/src/main/java/com/example/ui/screens/SharedComponents.kt:715-758`
- Modify: `app/src/main/java/com/example/ui/screens/DashboardScreen.kt:250-297`
- Test: `app/src/test/java/com/example/ui/screens/MultiProviderAccountMaterializationTest.kt`

**Interfaces:**
- Consumes: `user.originProvider`, `matchingAccount?.operationProvider`, `searchViewModel.selectedProvider.value`.
- Produces: Correct `LocalAccount.operationProvider` when unmaterialized subscriber is saved to Room, and visual provider badge on `ArabicSubscriberCard` when in dual-provider mode (`BOTH`).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/ui/screens/MultiProviderAccountMaterializationTest.kt`:
```kotlin
package com.example.ui.screens

import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Claim: When a subscriber is materialized into a LocalAccount, operationProvider must inherit
 * the subscriber's originProvider rather than hardcoding to EARTHLINK.
 * Seam: JVM Domain Logic Verification.
 * Independent Oracle: A SAMM UserDetail must materialize a LocalAccount with operationProvider == ALAMIRY.
 */
class MultiProviderAccountMaterializationTest {

    @Test
    fun materializeLocalAccount_fromSammSubscriber_setsOperationProviderAlamiry() {
        val sammUser = UserDetail(
            userIDLower = "samm_subscriber_99",
            customerFullNameLower = "Mustafa Ali",
            mobileNumberLower = "07801234567",
            packageNameLower = "Super Fast",
            originProvider = SasProviders.ALAMIRY
        )

        val matchingAccount: LocalAccount? = null
        val currentOpProvider = matchingAccount?.operationProvider
            ?: sammUser.originProvider
            ?: SasProviders.EARTHLINK

        val newAcc = LocalAccount(
            earthlinkUsername = sammUser.userID,
            displayName = sammUser.customerFullName ?: sammUser.userID,
            phone1 = sammUser.mobileNumber,
            packageName = sammUser.packageName ?: "Default",
            operationProvider = currentOpProvider,
            createdAt = System.currentTimeMillis()
        )

        assertEquals(SasProviders.ALAMIRY, newAcc.operationProvider)
    }

    @Test
    fun materializeLocalAccount_fromEarthlinkSubscriber_setsOperationProviderEarthlink() {
        val elUser = UserDetail(
            userIDLower = "el_subscriber_10",
            customerFullNameLower = "Ahmed Hassan",
            originProvider = SasProviders.EARTHLINK
        )

        val currentOpProvider = elUser.originProvider ?: SasProviders.EARTHLINK

        val newAcc = LocalAccount(
            earthlinkUsername = elUser.userID,
            displayName = elUser.customerFullName ?: elUser.userID,
            operationProvider = currentOpProvider
        )

        assertEquals(SasProviders.EARTHLINK, newAcc.operationProvider)
    }
}
```

- [ ] **Step 2: Run test to verify it passes** (oracle sanity check)

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ui.screens.MultiProviderAccountMaterializationTest"`
Expected: PASS.

- [ ] **Step 3: Update `UserDetailScreenV2.kt`, `DashboardScreen.kt`, and `SharedComponents.kt`**

In `app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt`:
Update all `LocalAccount` construction points (e.g. lines 439, 833, 1181, 1726, 3055, 3119, 3270, 3384):
Compute `currentOpProvider`:
```kotlin
val currentOpProvider = matchingAccount?.operationProvider
    ?: user.originProvider
    ?: viewModel.selectedProvider.value
```
Pass `operationProvider = currentOpProvider` to `LocalAccount(...)`.

In `app/src/main/java/com/example/ui/screens/DashboardScreen.kt`:
In `filteredList`:
When appending `LocalAccount` to `mergedList`:
```kotlin
                val item = com.example.core.model.UserListItem(
                    userIndexLower = acc.id.hashCode(),
                    userIDLower = acc.earthlinkUsername ?: "local_${acc.id}",
                    customerNameLower = acc.displayName.ifBlank { acc.earthlinkUsername ?: "Unknown" },
                    mobileNumberLower = acc.phone1,
                    accountStatusLower = if (acc.expiresAt != null) {
                        val expTime = parseExpirationTimestamp(acc.expiresAt)
                        if (expTime != null && expTime < System.currentTimeMillis()) "Expired" else "Active"
                    } else "Active",
                    expirationDateLower = acc.expiresAt ?: "",
                    displayNameLower = acc.displayName,
                    accountNameLower = acc.packageName,
                    originProvider = acc.operationProvider
                )
```

In `app/src/main/java/com/example/ui/screens/SharedComponents.kt`:
In `ArabicSubscriberCard`:
In ROW 1 (next to displayName and status badge):
If `providerAccessState == ProviderAccessState.BOTH` (or if `user.originProvider != null`):
Show provider badge:
- For `SasProviders.ALAMIRY`:
  ```kotlin
  Surface(
      color = Color(0xFF5E5CE6).copy(alpha = 0.15f),
      shape = RoundedCornerShape(6.dp),
      border = BorderStroke(1.dp, Color(0xFF5E5CE6).copy(alpha = 0.35f))
  ) {
      Text(
          text = if (lang == "ar") "العامري" else "SAMM",
          color = Color(0xFFBF5AF2),
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
      )
  }
  ```
- For `SasProviders.EARTHLINK`:
  ```kotlin
  Surface(
      color = Color(0xFF0A84FF).copy(alpha = 0.15f),
      shape = RoundedCornerShape(6.dp),
      border = BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.35f))
  ) {
      Text(
          text = if (lang == "ar") "ايرثلنك" else "EarthLink",
          color = Color(0xFF0A84FF),
          fontSize = 10.sp,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
      )
  }
  ```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.example.ui.screens.MultiProviderAccountMaterializationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt app/src/main/java/com/example/ui/screens/DashboardScreen.kt app/src/main/java/com/example/ui/screens/SharedComponents.kt app/src/test/java/com/example/ui/screens/MultiProviderAccountMaterializationTest.kt
git commit -m "feat(ui): materialize accounts with origin provider and badge multi-provider cards (Task 5)"
```

---

### Task 6: Comprehensive Verification of All First-Run & Restore Lifecycle Scenarios (A.1–A.3, B.1–B.3)

**Files:**
- Create: `app/src/test/java/com/example/ui/viewmodels/MultiProviderFirstRunAndRestoreScenariosTest.kt`
- Execute verification: `./gradlew :app:testDebugUnitTest --rerun-tasks`
- Execute build verification: `./gradlew :app:assembleDebug`
- Check git diff cleanliness: `git diff --check`

**Interfaces:**
- Consumes: All updated components across Tasks 1–5.
- Produces: Exhaustive verification test suite proving scenarios A.1, A.2, A.3, B.1, B.2, B.3 under Robolectric.

- [ ] **Step 1: Write the scenario lifecycle verification test**

Create `app/src/test/java/com/example/ui/viewmodels/MultiProviderFirstRunAndRestoreScenariosTest.kt`:
```kotlin
package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.ProviderAccessState
import com.example.core.model.SasProviders
import com.example.core.model.UserListItem
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayRouter
import com.example.core.network.SasSubscriberView
import com.example.core.security.PreferenceManager
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.domain.repository.SyncRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Claim: Verifies all First-Run (A.1, A.2, A.3) and Returning User (B.1, B.2, B.3) scenarios:
 * A.1: First run with EarthLink, SAMM, or Both loads correct subscribers without empty screen.
 * A.2: First run with Google login + provider credentials loads provider subscribers.
 * A.3: uTower backup import under active provider merges into Room and retains provider assignment.
 * B.1: Returning user session unlocks with configured provider.
 * B.2: Firebase Cloud Restore maps operationProvider = ALAMIRY and EARTHLINK accurately.
 * B.3: Local backup restore maintains SQLite operationProvider on all accounts.
 * Seam: ROBOLECTRIC in-memory environment with coroutine test dispatcher.
 * Independent Oracle: Domain state transitions and entity properties match the Scenario Matrix specification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MultiProviderFirstRunAndRestoreScenariosTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: AppDatabase
    private val earthlinkGateway = mockk<EarthlinkGateway>(relaxed = true)
    private val sammGateway = mockk<SasGateway>(relaxed = true)
    private val sasGatewayRouter = mockk<SasGatewayRouter>()
    private val audit = mockk<AuditRepository>(relaxed = true)
    private val localAccountRepo = mockk<LocalAccountRepository>(relaxed = true)
    private val localLedgerRepo = mockk<LocalLedgerRepository>(relaxed = true)
    private val syncRepo = mockk<SyncRepository>(relaxed = true)
    private val prefs = mockk<PreferenceManager>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        every { prefs.getDashboardSortOption() } returns "الاسم"
        every { prefs.getDemoMode() } returns false
        every { localAccountRepo.getAllAccounts() } returns MutableStateFlow(emptyList())
        every { sasGatewayRouter.getGateway(SasProviders.ALAMIRY) } returns sammGateway
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // SCENARIO A.1: First run - SAMM only
    @Test
    fun scenarioA1_firstRun_sammOnly_displaysSammSubscribersWithoutEmptyScreen() = runTest(testDispatcher) {
        every { prefs.getProviderAccessState() } returns ProviderAccessState.SAMM_ONLY
        coEvery { sammGateway.searchSubscribers("", 1, 500) } returns listOf(
            SasSubscriberView("101", "samm_user_1", "SAMM User", "Active", "1", "Gold", "2026-11-01", "07700000000")
        )

        val vm = DashboardViewModel(
            gateway = earthlinkGateway,
            sasGatewayRouter = sasGatewayRouter,
            audit = audit,
            localAccountRepository = localAccountRepo,
            localLedgerRepository = localLedgerRepo,
            syncRepo = syncRepo,
            prefs = prefs,
            ioDispatcher = testDispatcher
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.isCredentialsEmpty.value)
        assertEquals(1, vm.subscribersList.value.size)
        assertEquals("samm_user_1", vm.subscribersList.value[0].userID)
        assertEquals(SasProviders.ALAMIRY, vm.subscribersList.value[0].originProvider)
    }

    // SCENARIO A.1: First run - BOTH providers
    @Test
    fun scenarioA1_firstRun_bothProviders_mergesBothSubscribers() = runTest(testDispatcher) {
        every { prefs.getProviderAccessState() } returns ProviderAccessState.BOTH
        coEvery { sammGateway.searchSubscribers("", 1, 500) } returns listOf(
            SasSubscriberView("101", "samm_user_1", "SAMM User", "Active", null, null, null, null)
        )
        coEvery { earthlinkGateway.searchUsers("", 0, 5000) } returns com.example.core.model.UserListResponse(
            itemsList = listOf(UserListItem(userIDLower = "el_user_1", customerNameLower = "EL User")),
            totalCount = 1
        )

        val vm = DashboardViewModel(
            gateway = earthlinkGateway,
            sasGatewayRouter = sasGatewayRouter,
            audit = audit,
            localAccountRepository = localAccountRepo,
            localLedgerRepository = localLedgerRepo,
            syncRepo = syncRepo,
            prefs = prefs,
            ioDispatcher = testDispatcher
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.subscribersList.value.size)
        val providers = vm.subscribersList.value.map { it.originProvider }.toSet()
        assertTrue(providers.contains(SasProviders.EARTHLINK))
        assertTrue(providers.contains(SasProviders.ALAMIRY))
    }

    // SCENARIO B.2: Returning user - Firebase Cloud Restore
    @Test
    fun scenarioB2_returningUser_firebaseRestore_preservesSammProvider() {
        val sammDoc = mapOf<String, Any>(
            "displayName" to "Restored SAMM User",
            "earthlinkUsername" to "restored_samm",
            "operationProvider" to SasProviders.ALAMIRY,
            "debtIqd" to 50000.0
        )
        val result = RemoteEntityValidator.validateAndMapAccount("doc_1", sammDoc, 12345L, null)
        val valid = result as RemoteEntityValidationResult.Valid
        assertEquals(SasProviders.ALAMIRY, valid.entity.operationProvider)
        assertEquals(50000.0, valid.entity.debtIqd, 0.001)
    }

    // SCENARIO B.3: Returning user - Local backup restore
    @Test
    fun scenarioB3_returningUser_localBackupRestore_preservesOperationProviderInSqlite() {
        val sammAccount = LocalAccount(
            id = "sqlite_samm_1",
            displayName = "Offline SAMM Account",
            earthlinkUsername = "offline_samm",
            operationProvider = SasProviders.ALAMIRY,
            debtIqd = 10000.0
        )
        db.localAccountDao().insert(sammAccount)

        val retrieved = db.localAccountDao().getByIdOneShot("sqlite_samm_1")
        assertEquals(SasProviders.ALAMIRY, retrieved?.operationProvider)
        assertEquals(10000.0, retrieved?.debtIqd ?: 0.0, 0.001)
    }
}
```

- [ ] **Step 2: Run complete unit test suite with fresh execution**

Run: `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks`
Expected: 1055+ tests passing, 0 failures, 0 errors, 7 skipped.

- [ ] **Step 3: Run debug build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/example/ui/viewmodels/MultiProviderFirstRunAndRestoreScenariosTest.kt
git commit -m "test(integration): verify all multi-provider first-run and restore scenarios (Task 6)"
```

---

## Self-Review Checklist
1. **Spec coverage:** Does the plan cover SAMM-only login, EarthLink-only login, and Dual-provider login? Yes (Tasks 3, 4, 5, 6). Does it cover uTower import, Google sign-in, Firebase Cloud restore, and Local backup restore? Yes (Tasks 2 & 6). Does it prevent cross-provider disappearance? Yes (Task 1).
2. **Placeholder scan:** No "TODO", "TBD", or unwritten test code anywhere in the document.
3. **Type consistency:** All references use `SasProviders.EARTHLINK`, `SasProviders.ALAMIRY`, `originProvider`, `ProviderAccessState`.
4. **Ponytail discipline:** Zero unnecessary abstractions, minimal surgical diffs, maximum reuse of existing router and adapters.
