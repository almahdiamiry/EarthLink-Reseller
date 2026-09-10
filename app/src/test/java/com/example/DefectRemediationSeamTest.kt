package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import com.example.core.model.PendingExternalOperation
import com.example.core.model.UserListItem
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.ui.screens.DashboardStatusClassifier
import com.example.ui.screens.DashboardStatusFilter
import com.example.ui.screens.LocalAccountMatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import com.example.ui.viewmodels.LocalAccountsViewModel
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.UtowerImportRepository
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Core Triad Standard:
 * 1. Claim: Verifies the 5 repaired seams for V1 production invariants:
 *    - FIX 1: Missing LocalAccount auto-materialization on ACTIVATION crash recovery.
 *    - FIX 2: Atomic took + gave creation and idempotency for Wasil cash renewals.
 *    - FIX 3: Fail-closed identity matching in LocalAccountMatcher without hijacking or guessing.
 *    - FIX 4: Persistence of towerName and address across saveAccount updates.
 *    - FIX 5: 250-IQD multiple and finite amount boundary validation in correctTransaction.
 * 2. Seam / Environment: ROBOLECTRIC in-memory SQLite database simulating production Room transactions.
 * 3. Independent Oracle: Explicit arithmetic expectations, literal constants, and Target Product Contract v0.6 rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DefectRemediationSeamTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var accountRepository: LocalAccountRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        pendingDao = db.pendingExternalOperationDao()

        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )

        accountRepository = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = outboxDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // FIX 1: ACTIVATION Crash Recovery Auto-Shell Materialization
    // =========================================================================

    @Test
    fun fix1_activationCrashRecovery_createsLocalShellAndSingleLedgerEntry() = runBlocking {
        val txId = "tx_act_recovery_001"
        val username = "subscriber_act_001"
        val payload = "{\"username\":\"$username\",\"phone\":\"07701234567\",\"fullName\":\"Ahmed Ali\",\"pkgIndex\":2}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_act_001",
                accountId = username,
                operationType = "ACTIVATION",
                amountIqd = 35000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // Precondition: SQLite has zero rows for this account
        assertNull("Account must not exist prior to recovery", accountDao.getByIdOneShot(username))

        // Execute recovery materialization
        val chargeEntry = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")

        // Verify account shell was created
        val createdAcc = accountDao.getByIdOneShot(username)
        assertNotNull("LocalAccount must be auto-materialized", createdAcc)
        assertEquals("subscriber_act_001", createdAcc!!.id)
        assertEquals("subscriber_act_001", createdAcc.earthlinkUsername)
        assertEquals("Ahmed Ali", createdAcc.displayName)
        assertEquals("07701234567", createdAcc.phone1)
        assertEquals(35000.0, createdAcc.currentPriceIqd, 0.001)

        // Verify ledger entry
        assertNotNull("Charge entry must be returned", chargeEntry)
        assertEquals(txId, chargeEntry!!.id)
        assertEquals(username, chargeEntry.accountId)
        assertEquals("took", chargeEntry.typeRaw)
        assertEquals(35000.0, chargeEntry.amountIqd, 0.001)

        // Verify operation status
        val op = pendingDao.getByBusinessTransactionId(txId)
        assertEquals("COMPLETED", op?.status)
    }

    @Test
    fun fix1_activationCrashRecovery_isIdempotentOnRepeatedResolution() = runBlocking {
        val txId = "tx_act_recovery_002"
        val username = "subscriber_act_002"
        val payload = "{\"username\":\"$username\",\"phone\":\"07709999999\",\"fullName\":\"Ali Hassan\",\"pkgIndex\":1}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_act_002",
                accountId = username,
                operationType = "ACTIVATION",
                amountIqd = 25000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // First call
        val firstEntry = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")
        assertNotNull(firstEntry)

        // Repeated call (e.g. subsequent cold restart or retry)
        val secondEntry = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")
        assertNotNull(secondEntry)
        assertEquals("Repeated resolution must return same entry", firstEntry!!.id, secondEntry!!.id)

        // Assert exactly 1 account and 1 ledger entry in database
        val allAccounts = accountDao.getAllOneShot().filter { it.id == username }
        assertEquals("Must not create duplicate accounts", 1, allAccounts.size)

        val allEntries = ledgerDao.getByAccountIdOneShot(username)
        assertEquals("Must not create duplicate ledger entries", 1, allEntries.size)
    }

    @Test
    fun fix1_activationCrashRecovery_failsClosedOnMissingUsernameInPayload() = runBlocking {
        val txId = "tx_act_recovery_no_user"
        val payload = "{\"phone\":\"07701234567\",\"fullName\":\"Ahmed Ali\",\"pkgIndex\":2}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_act_no_user",
                accountId = "some_missing_user",
                operationType = "ACTIVATION",
                amountIqd = 35000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        try {
            ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "[VERIFIED ACTIVATION]")
            fail("Expected IllegalStateException due to missing username in payload")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("MISSING_LOCAL_FINANCIAL_TARGET") == true)
        }
    }

    // =========================================================================
    // FIX 2: WASIL Cash Renewal Atomic Took + Gave Recovery
    // =========================================================================

    @Test
    fun fix2_wasilCashRenewal_atomicallyCreatesTookAndGaveEntries() = runBlocking {
        val account = LocalAccount(
            id = "acc_wasil_001",
            earthlinkUsername = "user_wasil_001",
            displayName = "Wasil Customer",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val txId = "tx_renew_wasil_001"
        val payload = "{\"userId\":\"acc_wasil_001\",\"price\":35000,\"note\":\"Renewal 35K\",\"isWasil\":true}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_wasil_001",
                accountId = "acc_wasil_001",
                operationType = "REFILL",
                amountIqd = 35000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // Recovery executes
        val charge = ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "Renewal 35K")
        assertNotNull(charge)

        // Assert both took and gave were created
        val allEntries = ledgerDao.getByAccountIdOneShot("acc_wasil_001")
        assertEquals("Must create exactly 2 entries (took and gave)", 2, allEntries.size)

        val tookEntry = allEntries.find { it.typeRaw == "took" }
        val gaveEntry = allEntries.find { it.typeRaw == "gave" }

        assertNotNull("Took entry must exist", tookEntry)
        assertEquals(txId, tookEntry!!.id)
        assertEquals(35000.0, tookEntry.amountIqd, 0.001)

        assertNotNull("Gave entry must exist", gaveEntry)
        assertEquals("pay_$txId", gaveEntry!!.id)
        assertEquals(35000.0, gaveEntry.amountIqd, 0.001)

        // Assert net balance: 0 debt, 0 advance
        val updatedAcc = accountDao.getByIdOneShot("acc_wasil_001")!!
        assertEquals("Net debt must be 0 after paid renewal", 0.0, updatedAcc.debtIqd, 0.001)
        assertEquals("Net advance must be 0", 0.0, updatedAcc.advanceIqd, 0.001)
    }

    @Test
    fun fix2_wasilCashRenewal_isIdempotentAcrossNormalPathAndRecovery() = runBlocking {
        val account = LocalAccount(
            id = "acc_wasil_002",
            earthlinkUsername = "user_wasil_002",
            displayName = "Wasil Idempotent Customer",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val txId = "tx_renew_wasil_002"
        val payload = "{\"userId\":\"acc_wasil_002\",\"price\":35000,\"note\":\"Renewal\",\"isWasil\":true}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_wasil_002",
                accountId = "acc_wasil_002",
                operationType = "REFILL",
                amountIqd = 35000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // Step 1: resolvePendingOperationVerifiedSuccess executes (creates took + gave)
        ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "Renewal")

        // Step 2: Normal path calls recordAccountRenewal with same idempotencyKey
        ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 35000.0,
            chargeNote = "Renewal",
            payNote = "Renewal",
            idempotencyKey = txId
        )

        // Step 3: Repeated recovery call
        ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "Renewal")

        // Assert total ledger entries remains exactly 2
        val allEntries = ledgerDao.getByAccountIdOneShot("acc_wasil_002")
        assertEquals("Must remain exactly 2 entries with zero duplicates", 2, allEntries.size)
        assertEquals(1, allEntries.count { it.typeRaw == "took" })
        assertEquals(1, allEntries.count { it.typeRaw == "gave" })
    }

    @Test
    fun fix2_wasilCashRenewal_normalPathThenRepeatedThenRecovery() = runBlocking {
        val account = LocalAccount(
            id = "acc_wasil_003",
            earthlinkUsername = "user_wasil_003",
            displayName = "Normal Then Recovery Customer",
            currentPriceIqd = 45000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val txId = "tx_renew_wasil_003"
        val payload = "{\"userId\":\"acc_wasil_003\",\"price\":45000,\"note\":\"Renewal 45K\",\"isWasil\":true}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_wasil_003",
                accountId = "acc_wasil_003",
                operationType = "REFILL",
                amountIqd = 45000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        // Step 1: Normal path runs first
        ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 45000.0,
            chargeNote = "Renewal 45K",
            payNote = "Renewal 45K",
            idempotencyKey = txId
        )

        // Assert 1 took + 1 gave created
        var entries = ledgerDao.getByAccountIdOneShot("acc_wasil_003")
        assertEquals(2, entries.size)

        // Step 2: Repeated normal path call (e.g. retry)
        ledgerRepository.recordAccountRenewal(
            account = account,
            newPriceIqd = 45000.0,
            chargeNote = "Renewal 45K",
            payNote = "Renewal 45K",
            idempotencyKey = txId
        )
        entries = ledgerDao.getByAccountIdOneShot("acc_wasil_003")
        assertEquals("Repeated normal path must not duplicate entries", 2, entries.size)

        // Step 3: Recovery resolution runs after normal path
        ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "Renewal 45K")
        entries = ledgerDao.getByAccountIdOneShot("acc_wasil_003")
        assertEquals("Recovery after normal path must remain exactly 2 entries", 2, entries.size)
        assertEquals(1, entries.count { it.typeRaw == "took" && it.id == txId })
        assertEquals(1, entries.count { it.typeRaw == "gave" && it.id == "pay_$txId" })

        val acc = accountDao.getByIdOneShot("acc_wasil_003")!!
        assertEquals(0.0, acc.debtIqd, 0.001)
        assertEquals(0.0, acc.advanceIqd, 0.001)
    }

    @Test
    fun fix2_legacyPayloadWithoutIsWasil_createsTookOnly() = runBlocking {
        val account = LocalAccount(
            id = "acc_legacy_renew",
            earthlinkUsername = "user_legacy_renew",
            displayName = "Legacy Renewal Customer",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        accountDao.insert(account)

        val txId = "tx_legacy_renew_001"
        // Legacy payload: isWasil field is completely missing
        val payload = "{\"userId\":\"acc_legacy_renew\",\"price\":35000,\"note\":\"Legacy Unpaid Renewal\"}"

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_legacy_001",
                accountId = "acc_legacy_renew",
                operationType = "REFILL",
                amountIqd = 35000L,
                payloadJson = payload,
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "Legacy Unpaid Renewal")

        val allEntries = ledgerDao.getByAccountIdOneShot("acc_legacy_renew")
        assertEquals("Legacy payload must create took entry only", 1, allEntries.size)
        assertEquals("took", allEntries.first().typeRaw)
        assertEquals(35000.0, allEntries.first().amountIqd, 0.001)

        val updatedAcc = accountDao.getByIdOneShot("acc_legacy_renew")!!
        assertEquals("Debt must be 35000 for unpaid renewal", 35000.0, updatedAcc.debtIqd, 0.001)
    }

    // =========================================================================
    // FIX 3: LocalAccountMatcher Fail-Closed Matching Rules
    // =========================================================================

    @Test
    fun fix3_localAccountMatcher_exactUsernameMatch() {
        val acc1 = LocalAccount(id = "acc1", earthlinkUsername = "user_alpha", displayName = "Alpha User")
        val matcher = LocalAccountMatcher(listOf(acc1))

        val user = UserListItem(userIDLower = "user_alpha", customerNameLower = "Different Name")
        val matched = matcher.findMatching(user)

        assertNotNull("Exact username must match", matched)
        assertEquals("acc1", matched!!.id)
    }

    @Test
    fun fix3_localAccountMatcher_rejectsConflictingUsername() {
        val acc1 = LocalAccount(id = "acc1", earthlinkUsername = "user_alpha", displayName = "Same Name")
        val matcher = LocalAccountMatcher(listOf(acc1))

        val user = UserListItem(userIDLower = "user_beta", customerNameLower = "Same Name")
        val matched = matcher.findMatching(user)

        assertNull("Must reject candidate when earthlinkUsername contradicts user.userID", matched)
    }

    @Test
    fun fix3_localAccountMatcher_rejectsDuplicateNameAmbiguity() {
        val acc1 = LocalAccount(id = "acc1", earthlinkUsername = null, displayName = "Common Name")
        val acc2 = LocalAccount(id = "acc2", earthlinkUsername = null, displayName = "Common Name")
        val matcher = LocalAccountMatcher(listOf(acc1, acc2))

        val user = UserListItem(userIDLower = "user_gamma", customerNameLower = "Common Name")
        val matched = matcher.findMatching(user)

        assertNull("Must fail closed (null) on duplicate name ambiguity without guessing", matched)
    }

    @Test
    fun fix3_localAccountMatcher_rejectsHistoryOnlyHijacking() {
        val accHistory = LocalAccount(
            id = "acc_archived",
            earthlinkUsername = "old_user",
            displayName = "Archived Person",
            isHistoryOnlySubscriber = true
        )
        val matcher = LocalAccountMatcher(listOf(accHistory))

        val activeUser = UserListItem(userIDLower = "active_user", customerNameLower = "Archived Person")
        val matched = matcher.findMatching(activeUser)

        assertNull("History-only account must not hijack an active ISP subscriber with different ID", matched)
    }

    @Test
    fun fix3_localAccountMatcher_allowsLocalOnlyUniqueName() {
        val accLocal = LocalAccount(id = "acc_local", earthlinkUsername = null, displayName = "Unique Local Person")
        val matcher = LocalAccountMatcher(listOf(accLocal))

        val user = UserListItem(userIDLower = "user_delta", customerNameLower = "Unique Local Person")
        val matched = matcher.findMatching(user)

        assertNotNull("Unique local-only account with no conflicting username may match", matched)
        assertEquals("acc_local", matched!!.id)
    }

    @Test
    fun gate3_matcherInteraction_provesAllSixVisibilityCases() {
        val nowMs = 1700000000000L

        val accActive = LocalAccount(id = "acc_act", earthlinkUsername = "user_exact", displayName = "Exact User", isHistoryOnlySubscriber = false)
        val accDiffUser = LocalAccount(id = "acc_diff", earthlinkUsername = "other_user", displayName = "Same Name Diff User", isHistoryOnlySubscriber = false)
        val accDup1 = LocalAccount(id = "acc_dup1", earthlinkUsername = null, displayName = "Common Dup Name", isHistoryOnlySubscriber = false)
        val accDup2 = LocalAccount(id = "acc_dup2", earthlinkUsername = null, displayName = "Common Dup Name", isHistoryOnlySubscriber = false)
        val accHistorySameName = LocalAccount(id = "acc_hist_name", earthlinkUsername = "hist_user", displayName = "Hist Person", isHistoryOnlySubscriber = true)
        val accHistoryExactUser = LocalAccount(id = "acc_hist_exact", earthlinkUsername = "isp_user_hist", displayName = "Archived Account", isHistoryOnlySubscriber = true)
        val accLocalOnlyUnique = LocalAccount(id = "acc_local_uniq", earthlinkUsername = null, displayName = "Local Unique Person", isHistoryOnlySubscriber = false)

        val matcher = LocalAccountMatcher(listOf(
            accActive, accDiffUser, accDup1, accDup2, accHistorySameName, accHistoryExactUser, accLocalOnlyUnique
        ))

        // 1. current ISP + exact username + normal LocalAccount => remains visible
        val ispUser1 = UserListItem(userIDLower = "user_exact", accountStatusLower = "Active")
        val match1 = matcher.findMatchingByUsername(ispUser1.userID) ?: matcher.findMatching(ispUser1)
        assertNotNull(match1)
        assertEquals("acc_act", match1!!.id)
        val filtered1 = DashboardStatusClassifier.filterSubscribers(listOf(ispUser1), matcher, DashboardStatusFilter.ACTIVE, nowMs)
        assertEquals(1, filtered1.size)

        // 2. current ISP + same name + different username => remains visible
        val ispUser2 = UserListItem(userIDLower = "isp_different", customerNameLower = "Same Name Diff User", accountStatusLower = "Active")
        val match2 = matcher.findMatchingByUsername(ispUser2.userID) ?: matcher.findMatching(ispUser2)
        assertNull("Must not match candidate with conflicting username", match2)
        val filtered2 = DashboardStatusClassifier.filterSubscribers(listOf(ispUser2), matcher, DashboardStatusFilter.ACTIVE, nowMs)
        assertEquals("Must remain visible as authoritative ISP subscriber", 1, filtered2.size)

        // 3. current ISP + duplicate name => remains visible
        val ispUser3 = UserListItem(userIDLower = "isp_dup_user", customerNameLower = "Common Dup Name", accountStatusLower = "Active")
        val match3 = matcher.findMatchingByUsername(ispUser3.userID) ?: matcher.findMatching(ispUser3)
        assertNull("Must not match ambiguous duplicate names", match3)
        val filtered3 = DashboardStatusClassifier.filterSubscribers(listOf(ispUser3), matcher, DashboardStatusFilter.ACTIVE, nowMs)
        assertEquals("Must remain visible as authoritative ISP subscriber", 1, filtered3.size)

        // 4. current ISP + history-only same name => remains visible
        val ispUser4 = UserListItem(userIDLower = "isp_new_user", customerNameLower = "Hist Person", accountStatusLower = "Active")
        val match4 = matcher.findMatchingByUsername(ispUser4.userID) ?: matcher.findMatching(ispUser4)
        assertNull("Must not match history-only account by name", match4)
        val filtered4 = DashboardStatusClassifier.filterSubscribers(listOf(ispUser4), matcher, DashboardStatusFilter.ACTIVE, nowMs)
        assertEquals("Must remain visible as authoritative ISP subscriber", 1, filtered4.size)

        // 5. exact username pointing to history-only local account => must NOT suppress current ISP subscriber, remains visible
        val ispUser5 = UserListItem(userIDLower = "isp_user_hist", customerNameLower = "Archived Account", accountStatusLower = "Active")
        val match5 = matcher.findMatchingByUsername(ispUser5.userID) ?: matcher.findMatching(ispUser5)
        assertNull("History-only account must NOT be matched to live ISP subscriber", match5)
        val filtered5 = DashboardStatusClassifier.filterSubscribers(listOf(ispUser5), matcher, DashboardStatusFilter.ACTIVE, nowMs)
        assertEquals("Live ISP subscriber MUST remain visible and not suppressed by history-only account", 1, filtered5.size)

        // 6. local-only unique name => existing behavior remains as intended
        val ispUser6 = UserListItem(userIDLower = "isp_local_link", customerNameLower = "Local Unique Person", accountStatusLower = "Active")
        val match6 = matcher.findMatchingByUsername(ispUser6.userID) ?: matcher.findMatching(ispUser6)
        assertNotNull("Unique local-only name matches intended local account", match6)
        assertEquals("acc_local_uniq", match6!!.id)
    }

    // =========================================================================
    // FIX 4: saveAccount Persists TowerName and Address
    // =========================================================================

    @Test
    fun fix4_saveAccount_persistsTowerNameAndAddress() = runBlocking {
        val initial = LocalAccount(
            id = "acc_prop_001",
            displayName = "Original Name",
            towerName = "Old Tower",
            address = "Old Address"
        )
        accountDao.insert(initial)

        val editRequest = initial.copy(
            towerName = "North Tower 42",
            address = "Al-Mansour District St 12"
        )
        accountRepository.saveAccount(editRequest)

        val fetched = accountDao.getByIdOneShot("acc_prop_001")!!
        assertEquals("TowerName must persist across saveAccount", "North Tower 42", fetched.towerName)
        assertEquals("Address must persist across saveAccount", "Al-Mansour District St 12", fetched.address)
    }

    @Test
    fun fix4b_saveAccountInternal_persistsPhone2TowerNameAndAddressViaLedgerOperations() = runBlocking {
        val initial = LocalAccount(
            id = "acc_internal_001",
            displayName = "Subscriber Alpha",
            phone1 = "07700000000",
            phone2 = "Old Phone2",
            towerName = "Old Tower",
            address = "Old Address"
        )
        accountDao.insert(initial)

        val updatedAccount = initial.copy(
            phone2 = "07800000001",
            towerName = "New Station 99",
            address = "Karrada District St 5"
        )

        ledgerRepository.recordAccountPayment(
            account = updatedAccount,
            amount = 25000.0,
            note = "Payment Note",
            idempotencyKey = "tx_pay_int_001"
        )

        val fetched = accountDao.getByIdOneShot("acc_internal_001")!!
        assertEquals("phone2 must persist across saveAccountInternal", "07800000001", fetched.phone2)
        assertEquals("towerName must persist across saveAccountInternal", "New Station 99", fetched.towerName)
        assertEquals("address must persist across saveAccountInternal", "Karrada District St 5", fetched.address)
    }

    // =========================================================================
    // FIX 5: correctTransaction Boundary Validation
    // =========================================================================

    @Test
    fun fix5_correctTransaction_validatesMultiplesOf250AndFiniteAmounts() = runBlocking {
        val acc = LocalAccount(id = "acc_corr_001", displayName = "Correction User", debtIqd = 10000.0)
        accountDao.insert(acc)

        val originalEntry = ledgerRepository.addDebt("acc_corr_001", 10000.0, "Original Debt", "tx_orig_001")
        assertNotNull(originalEntry)

        // 1. Invalid: non-multiple of 250 (100.0)
        try {
            ledgerRepository.correctTransaction("tx_orig_001", 100.0, "Invalid 100")
            fail("Expected IllegalArgumentException for intendedAmount=100.0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("multiple of 250 IQD") == true)
        }

        // 2. Invalid: fractional currency (123.45)
        try {
            ledgerRepository.correctTransaction("tx_orig_001", 123.45, "Invalid 123.45")
            fail("Expected IllegalArgumentException for intendedAmount=123.45")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("multiple of 250 IQD") == true)
        }

        // 3. Invalid: NaN
        try {
            ledgerRepository.correctTransaction("tx_orig_001", Double.NaN, "Invalid NaN")
            fail("Expected IllegalArgumentException for intendedAmount=NaN")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("finite") == true || e.message?.contains("multiple of 250 IQD") == true)
        }

        // 4. Invalid: Positive Infinity
        try {
            ledgerRepository.correctTransaction("tx_orig_001", Double.POSITIVE_INFINITY, "Invalid Infinity")
            fail("Expected IllegalArgumentException for intendedAmount=Infinity")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("finite") == true || e.message?.contains("multiple of 250 IQD") == true)
        }

        // 5. Valid: 0.0 (Full Reversal)
        val reversal = ledgerRepository.correctTransaction("tx_orig_001", 0.0, "Full Reversal")
        assertNotNull("intendedAmount=0.0 must be accepted for reversal", reversal)

        // 6. Valid: 5000.0 (Standard multiple of 250)
        val validCorr = ledgerRepository.correctTransaction("tx_orig_001", 5000.0, "Partial correction")
        assertNotNull("intendedAmount=5000.0 must be accepted", validCorr)
    }

    // =========================================================================
    // F-04: PPPoE Username Recycling & History-Only Decoupling
    // =========================================================================

    @Test
    fun f04_pppoeReuse_historyOnlyAccountNotReturnedByCurrentIspPath_independentEntityCreated() = runBlocking {
        // 1. Initial State: Old subscriber with PPPoE username "reused_user_01" has accumulated debt
        val oldAccount = LocalAccount(
            id = "acc_old_departed_001",
            earthlinkUsername = "reused_user_01",
            displayName = "Old Departed Person",
            phone1 = "07701111111",
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false
        )
        accountDao.insert(oldAccount)
        val oldDebtEntry = ledgerRepository.addDebt("acc_old_departed_001", 50000.0, "Old Debt", "tx_old_001")
        assertNotNull(oldDebtEntry)

        // 2. Old subscriber leaves ISP -> transitions to isHistoryOnlySubscriber = true
        accountRepository.deleteAccount("acc_old_departed_001")
        val postDeleteOld = accountDao.getByIdOneShot("acc_old_departed_001")!!
        assertTrue("Old account must be history-only", postDeleteOld.isHistoryOnlySubscriber)
        assertEquals("Old account debt must remain 50,000 IQD", 50000.0, postDeleteOld.debtIqd, 0.001)

        // 3. Same PPPoE username reused by new ISP user -> Current ISP lookup must NOT return old account
        val activeLookup = accountRepository.findActiveAccountByUsernameOrIdOneShot("reused_user_01")
        assertNull("Current ISP path must NOT return history-only account", activeLookup)

        val activeFlowResult = accountRepository.getActiveAccountByUsernameOrId("reused_user_01").first()
        assertNull("Current ISP flow must NOT return history-only account", activeFlowResult)

        // Historical lookup still sees old account for lineage/integrity
        val historicalLookup = accountRepository.findAccountByUsernameOrIdOneShot("reused_user_01")
        assertNotNull("Historical lookup must preserve lineage of departed account", historicalLookup)
        assertEquals("acc_old_departed_001", historicalLookup!!.id)

        // 4. Save new active account with same username as an independent entity
        val newAccount = LocalAccount(
            id = "acc_new_active_002",
            earthlinkUsername = "reused_user_01",
            displayName = "New ISP Subscriber",
            phone1 = "07802222222",
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false
        )
        val savedNew = accountRepository.saveAccount(newAccount)
        assertEquals("New account must retain its own ID", "acc_new_active_002", savedNew.id)
        assertEquals("New account must be active", false, savedNew.isHistoryOnlySubscriber)

        // 5. Verify both accounts coexist independently
        val allAccounts = accountDao.getAllPersistedOneShot()
        assertEquals("Both accounts must exist in database", 2, allAccounts.size)

        val fetchedOld = accountDao.getByIdOneShot("acc_old_departed_001")!!
        assertEquals("Old account ID must not change", "acc_old_departed_001", fetchedOld.id)
        assertEquals("Old account displayName must not change", "Old Departed Person", fetchedOld.displayName)
        assertEquals("Old account debt must remain 50,000 IQD", 50000.0, fetchedOld.debtIqd, 0.001)
        assertTrue("Old account must remain history-only", fetchedOld.isHistoryOnlySubscriber)

        val fetchedNew = accountDao.getByIdOneShot("acc_new_active_002")!!
        assertEquals("New account ID must be acc_new_active_002", "acc_new_active_002", fetchedNew.id)
        assertEquals("New account displayName must be New ISP Subscriber", "New ISP Subscriber", fetchedNew.displayName)
        assertEquals("New account debt must be 0", 0.0, fetchedNew.debtIqd, 0.001)
        assertFalse("New account must be active", fetchedNew.isHistoryOnlySubscriber)

        // Current ISP path now resolves the new active account
        val activeLookupAfterCreation = accountRepository.findActiveAccountByUsernameOrIdOneShot("reused_user_01")
        assertNotNull("Current ISP path must resolve newly created active account", activeLookupAfterCreation)
        assertEquals("acc_new_active_002", activeLookupAfterCreation!!.id)

        // Historical ledger entries for old account remain untouched
        val oldLedger = ledgerRepository.getLedgerForAccount("acc_old_departed_001").first()
        assertEquals("Old ledger entries must remain intact", 1, oldLedger.size)
        assertEquals("tx_old_001", oldLedger[0].id)
    }

    @Test
    fun f04_pppoeMultiCycleReuse_threeGenerations_preservesHistoryAndNoDebtPollution() = runBlocking {
        // Generation 1: Departed subscriber with debt
        val gen1 = LocalAccount(
            id = "reused_multigen",
            earthlinkUsername = "reused_multigen",
            displayName = "Generation 1 Subscriber",
            debtIqd = 35000.0,
            isHistoryOnlySubscriber = true
        )
        accountDao.insert(gen1)
        ledgerDao.insert(
            LocalLedgerEntry(
                id = "tx_gen1",
                accountId = "reused_multigen",
                typeRaw = "took",
                amountIqd = 35000.0,
                debtAfterIqd = 35000.0
            )
        )

        // Generation 2: Reused username, accrued debt, then also departed
        val gen2 = LocalAccount(
            id = "acc_gen2_uuid",
            earthlinkUsername = "reused_multigen",
            displayName = "Generation 2 Subscriber",
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false
        )
        accountRepository.saveAccount(gen2)
        ledgerRepository.addDebt("acc_gen2_uuid", 40000.0, "Gen 2 Debt", "tx_gen2")
        val gen2Departed = accountDao.getByIdOneShot("acc_gen2_uuid")!!.copy(isHistoryOnlySubscriber = true)
        accountDao.update(gen2Departed)

        // Generation 3: Reused username for a third subscriber
        val gen3 = LocalAccount(
            id = "acc_gen3_uuid",
            earthlinkUsername = "reused_multigen",
            displayName = "Generation 3 Subscriber",
            debtIqd = 0.0,
            isHistoryOnlySubscriber = false
        )
        accountRepository.saveAccount(gen3)
        ledgerRepository.addDebt("acc_gen3_uuid", 15000.0, "Gen 3 Debt", "tx_gen3")

        // 1. All 3 accounts coexist independently in Room
        val allPersisted = accountDao.getAllPersistedOneShot().filter { it.earthlinkUsername == "reused_multigen" }
        assertEquals("All 3 generations must coexist in database", 3, allPersisted.size)

        // 2. Generation 1 checks
        val fetchedGen1 = accountDao.getByIdOneShot("reused_multigen")!!
        assertEquals("Gen 1 must remain history-only", true, fetchedGen1.isHistoryOnlySubscriber)
        assertEquals("Gen 1 debt must remain 35,000 IQD", 35000.0, fetchedGen1.debtIqd, 0.001)

        // 3. Generation 2 checks
        val fetchedGen2 = accountDao.getByIdOneShot("acc_gen2_uuid")!!
        assertEquals("Gen 2 must remain history-only", true, fetchedGen2.isHistoryOnlySubscriber)
        assertEquals("Gen 2 debt must remain 40,000 IQD", 40000.0, fetchedGen2.debtIqd, 0.001)

        // 4. Generation 3 checks
        val fetchedGen3 = accountDao.getByIdOneShot("acc_gen3_uuid")!!
        assertEquals("Gen 3 must be active", false, fetchedGen3.isHistoryOnlySubscriber)
        assertEquals("Gen 3 debt must be 15,000 IQD", 15000.0, fetchedGen3.debtIqd, 0.001)

        // 5. Active queries must return ONLY Generation 3
        val activeOneShot = accountRepository.findActiveAccountByUsernameOrIdOneShot("reused_multigen")
        assertNotNull("Active one-shot must find an active account", activeOneShot)
        assertEquals("Active one-shot must return Gen 3", "acc_gen3_uuid", activeOneShot!!.id)

        val activeFlow = accountRepository.getActiveAccountByUsernameOrId("reused_multigen").first()
        assertNotNull("Active flow must emit an active account", activeFlow)
        assertEquals("Active flow must emit Gen 3", "acc_gen3_uuid", activeFlow!!.id)

        // 6. Ledger entry isolation: each generation has strictly its own ledger entry
        val gen1Ledger = ledgerRepository.getLedgerForAccount("reused_multigen").first()
        assertEquals(1, gen1Ledger.size)
        assertEquals("tx_gen1", gen1Ledger[0].id)
        assertEquals(35000.0, gen1Ledger[0].amountIqd, 0.001)

        val gen2Ledger = ledgerRepository.getLedgerForAccount("acc_gen2_uuid").first()
        assertEquals(1, gen2Ledger.size)
        assertEquals("tx_gen2", gen2Ledger[0].id)
        assertEquals(40000.0, gen2Ledger[0].amountIqd, 0.001)

        val gen3Ledger = ledgerRepository.getLedgerForAccount("acc_gen3_uuid").first()
        assertEquals(1, gen3Ledger.size)
        assertEquals("tx_gen3", gen3Ledger[0].id)
        assertEquals(15000.0, gen3Ledger[0].amountIqd, 0.001)
    }

    // =========================================================================
    // F-02: Error Emission & Clear on Validation/Execution Failure
    // =========================================================================

    @Test
    fun f02_localAccountDetail_invalidAmountRejection_emitsErrorAndClears() = runBlocking {
        val testAccount = LocalAccount(
            id = "acc_f02_test",
            displayName = "F02 Test Account",
            debtIqd = 10000.0
        )
        accountDao.insert(testAccount)

        val mockAudit = mock(AuditRepository::class.java)
        val mockSync = mock(SyncRepository::class.java)
        val mockUtower = mock(UtowerImportRepository::class.java)
        `when`(mockUtower.getImportBatches()).thenReturn(flowOf(emptyList()))

        val vm = LocalAccountsViewModel(
            localRepo = accountRepository,
            ledgerRepo = ledgerRepository,
            utowerRepo = mockUtower,
            audit = mockAudit,
            syncRepo = mockSync,
            appDatabase = db
        )

        // Realistic failure: user inputs 1,200 IQD (not a multiple of 250 IQD)
        vm.addPaymentLocal("acc_f02_test", 1200.0, "Invalid 1200 Payment")

        var attempts = 0
        while (vm.error.value == null && attempts < 50) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            kotlinx.coroutines.delay(50)
            attempts++
        }

        val emittedError = vm.error.value
        assertNotNull("Error must be emitted upon invalid payment input", emittedError)
        assertTrue("Error must explain multiple of 250 IQD requirement", emittedError!!.contains("250 IQD"))

        // clearError resets state
        vm.clearError()
        assertNull("clearError must reset error to null", vm.error.value)
    }
}
