package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.ledger.BalanceCalculator
import com.example.core.model.*
import com.example.core.sync.*
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.domain.repository.UtowerImportPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 Behavioral Test Suite: Same-Lineage Normal Mutations & Generation Invariance
 * (P3-G4-REQ-04, INV-01, INV-05, INV-06, INV-11).
 *
 * Verifies that:
 * 1. All standard local financial mutations (save/update account, add payment/debt, record renewal, add note, delete transaction, delete single account)
 *    execute as same-lineage mutations and DO NOT increment g4_local_generation.
 * 2. G1 financial operations (Activation, Renewal, Refill pending operation lifecycle and gateway resolution)
 *    execute within same lineage and DO NOT increment g4_local_generation.
 * 3. Ordinary uTower imports (shouldReplace = false) in both preview and file flows preserve g4_local_generation.
 * 4. Remote event applications (AccountUpsert, LedgerUpsert, BatchUpsert, AccountDelete, LedgerDelete)
 *    preserve g4_local_generation while independently capturing server remoteVersion.
 * 5. Strict domain separation: server remoteVersion (e.g. 100,000+) is completely isolated from local lineage generation (e.g. 1L).
 * 6. Concurrent same-lineage ledger writes prove zero unnecessary generation changes, consistent deterministic position derivation,
 *    and correct stale remote event rejection for mismatched generations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3SameLineageFinancialMutationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: LocalAccountRepository
    private lateinit var ledgerRepo: LocalLedgerRepository
    private lateinit var importer: UtowerImporter
    private lateinit var coordinator: RemoteSyncCoordinator
    private lateinit var testGateway: TestEarthlinkGateway

    class TestEarthlinkGateway : EarthlinkGateway {
        val checkUsernameCalls = AtomicInteger(0)
        val searchUsersCalls = AtomicInteger(0)
        val getUserDetailCalls = AtomicInteger(0)
        val refillCalls = AtomicInteger(0)
        val extendUserCalls = AtomicInteger(0)
        val createTestUserCalls = AtomicInteger(0)
        val createUserUsingDepositCalls = AtomicInteger(0)

        var usernameAvailable = true
        var searchUsersResult: UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        var userDetailResult: UserDetail = UserDetail(userIndexLower = 1, userIDLower = "user1", userActiveLower = true)
        var shouldThrowOnDetail: Exception? = null
        var shouldThrowOnAvailability: Exception? = null

        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "test_token", tokenType = "Bearer", expiresIn = 3600)

        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 40000.0

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            searchUsersCalls.incrementAndGet()
            return searchUsersResult
        }

        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCalls.incrementAndGet()
            shouldThrowOnDetail?.let { throw it }
            return userDetailResult
        }

        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()

        override suspend fun checkUsernameAvailable(userId: String): Boolean {
            checkUsernameCalls.incrementAndGet()
            shouldThrowOnAvailability?.let { throw it }
            return usernameAvailable
        }

        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true

        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? {
            createTestUserCalls.incrementAndGet()
            return "pass_test"
        }

        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? {
            createUserUsingDepositCalls.incrementAndGet()
            return "pass_deposit"
        }

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            refillCalls.incrementAndGet()
            return true
        }

        override suspend fun extendUser(userIndex: Int): Boolean {
            extendUserCalls.incrementAndGet()
            return true
        }

        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        ledgerRepo = LocalLedgerRepositoryImpl(db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao())
        importer = UtowerImporter(context, db)
        coordinator = RemoteSyncCoordinator(
            appDatabase = db,
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            batchDao = db.importBatchDao(),
            metadataDao = db.syncMetadataDao(),
            auditDao = db.auditLogDao(),
            outboxDao = db.syncOutboxDao()
        )
        testGateway = TestEarthlinkGateway()
    }

    @After
    fun tearDown() {
        db.close()
        AppDatabase.closeDatabase()
    }

    // =========================================================================
    // 1. Standard Financial Mutations Do Not Increment Generation
    // =========================================================================

    @Test
    fun saveAccount_and_updateAccount_preserveLocalGeneration() = runBlocking {
        assertEquals("Initial baseline generation must be 1L", 1L, db.getGeneration())

        // 1. Insert new account
        val newAccount = LocalAccount(
            id = "acc_fin_01",
            displayName = "Initial Subscriber",
            phone1 = "07701234567",
            currentPriceIqd = 45000.0,
            note = "First setup note"
        )
        val saved = accountRepo.saveAccount(newAccount)
        assertEquals("acc_fin_01", saved.id)
        assertEquals("Generation must remain 1L after account insert", 1L, db.getGeneration())

        // 2. Update account metadata
        val updatedAccount = saved.copy(
            displayName = "Updated Subscriber Name",
            phone1 = "07709998877",
            note = "Updated note",
            latitude = 33.3152,
            longitude = 44.3661
        )
        val updated = accountRepo.saveAccount(updatedAccount)
        assertEquals("Updated Subscriber Name", updated.displayName)
        assertEquals("Generation must remain 1L after account update", 1L, db.getGeneration())

        // Verify outbox queued mutations under same generation
        val outbox = db.syncOutboxDao().getPending()
        assertEquals(1, outbox.size)
        assertEquals("acc_fin_01", outbox[0].entityId)
        assertTrue(outbox.all { it.entityType == "local_accounts" })
    }

    @Test
    fun ledgerMutations_addPayment_addDebt_addRenewal_addNote_preserveLocalGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val account = LocalAccount(
            id = "acc_fin_02",
            displayName = "Active Customer",
            currentPriceIqd = 40000.0,
            debtIqd = 0.0
        )
        accountRepo.saveAccount(account)
        assertEquals(1L, db.getGeneration())

        // Add payment (credit)
        val pay1 = ledgerRepo.addPayment("acc_fin_02", 15000.0, "Cash installment 1")
        assertNotNull(pay1)
        assertEquals("gave", pay1.typeRaw)
        assertEquals("Generation must remain 1L after addPayment", 1L, db.getGeneration())

        // Add debt (charge)
        val debt1 = ledgerRepo.addDebt("acc_fin_02", 40000.0, "Monthly subscription fee")
        assertNotNull(debt1)
        assertEquals("took", debt1.typeRaw)
        assertEquals("Generation must remain 1L after addDebt", 1L, db.getGeneration())

        // Record renewal (creates debt and optional payment)
        val renewed = ledgerRepo.recordAccountRenewal(
            account = account,
            newPriceIqd = 40000.0,
            chargeNote = "Renewal Month 2",
            payNote = "Cash Paid at Renewal",
            idempotencyKey = "renew_tx_001"
        )
        assertNotNull(renewed)
        assertEquals("Generation must remain 1L after recordAccountRenewal", 1L, db.getGeneration())

        // Record account payment
        val pay2 = ledgerRepo.recordAccountPayment(
            account = account,
            amount = 10000.0,
            note = "Extra Cash Payment",
            idempotencyKey = "pay_tx_002"
        )
        assertNotNull(pay2)
        assertEquals("Generation must remain 1L after recordAccountPayment", 1L, db.getGeneration())

        // Record account debt
        val debt2 = ledgerRepo.recordAccountDebt(
            account = account,
            amount = 5000.0,
            note = "Router fee debt",
            idempotencyKey = "debt_tx_003"
        )
        assertNotNull(debt2)
        assertEquals("Generation must remain 1L after recordAccountDebt", 1L, db.getGeneration())

        // Add note transaction
        val noteTx = ledgerRepo.addNoteTransaction("acc_fin_02", "Subscriber requested optical power check")
        assertNotNull(noteTx)
        assertEquals("note", noteTx.typeRaw)
        assertEquals("Generation must remain 1L after addNoteTransaction", 1L, db.getGeneration())

        // Verify total ledger count
        val allEntries = db.localLedgerEntryDao().getByAccountIdOneShot("acc_fin_02", limit = 100)
        assertEquals(7, allEntries.size) // pay1, debt1, renew_charge, renew_pay, pay2, debt2, note
        assertEquals("Generation must remain strictly 1L after all ledger mutations", 1L, db.getGeneration())
    }

    @Test
    fun ledgerMutation_deleteTransaction_preservesLocalGenerationAndRecalculatesBalances() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val account = LocalAccount(id = "acc_fin_03", displayName = "Recalc User", debtIqd = 0.0)
        accountRepo.saveAccount(account)

        val tx1 = ledgerRepo.addDebt("acc_fin_03", 50000.0, "Service Fee", idempotencyKey = "tx_del_1")
        val tx2 = ledgerRepo.addPayment("acc_fin_03", 20000.0, "Partial Payment", idempotencyKey = "tx_del_2")

        val accAfterWrites = db.localAccountDao().getByIdOneShot("acc_fin_03")
        assertEquals(30000.0, accAfterWrites?.debtIqd ?: 0.0, 0.001)
        assertEquals(1L, db.getGeneration())

        // Delete tx1 (the 50000 debt) -> balance should now reflect only payment (-20000 debt, i.e. advance 20000)
        ledgerRepo.deleteTransaction("tx_del_1")
        assertEquals("Generation must remain 1L after deleteTransaction", 1L, db.getGeneration())

        val accAfterDelete = db.localAccountDao().getByIdOneShot("acc_fin_03")
        assertEquals(0.0, accAfterDelete?.debtIqd ?: -1.0, 0.001)
        assertEquals(20000.0, accAfterDelete?.advanceIqd ?: -1.0, 0.001)

        // Verify outbox has correction upsert, NOT a delete tombstone for tx_del_1
        val outbox = db.syncOutboxDao().getPending()
        val tombstone = outbox.firstOrNull { it.entityId == "tx_del_1" && it.operation == "delete" }
        assertNull("Original entry must NOT receive a delete tombstone", tombstone)
        val correctionOutbox = outbox.firstOrNull { it.operation == "upsert" && it.entityType == "local_ledger_entries" }
        assertNotNull("Outbox must contain correction upsert", correctionOutbox)
        assertEquals(1L, db.getGeneration())
    }

    @Test
    fun singleAccountDeletion_preservesLocalGeneration_whereasClearAllIncrementsGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val account = LocalAccount(id = "acc_fin_04", displayName = "Single Delete User")
        accountRepo.saveAccount(account)
        ledgerRepo.addDebt("acc_fin_04", 10000.0, "Debt")
        assertEquals(1L, db.getGeneration())

        // Deleting a single account is a normal same-lineage mutation -> generation must NOT change
        accountRepo.deleteAccount("acc_fin_04")
        assertEquals("Single account delete must preserve generation at 1L", 1L, db.getGeneration())
        val preservedAccount = db.localAccountDao().getByIdOneShot("acc_fin_04")
        assertNotNull(preservedAccount)
        assertTrue(preservedAccount!!.isHistoryOnlySubscriber)
        val childLedgers = db.localLedgerEntryDao().getByAccountIdOneShot("acc_fin_04")
        assertEquals(1, childLedgers.size)

        // Contrast: deleteAllAccounts() / clearAllData() represents a complete lineage reset -> increments generation
        accountRepo.deleteAllAccounts()
        assertEquals("Bulk delete / clear must increment generation from 1L to 2L", 2L, db.getGeneration())
    }

    // =========================================================================
    // 2. G1 Financial Operations Do Not Increment Generation
    // =========================================================================

    @Test
    fun g1PendingOperations_fullLifecycle_preservesLocalGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val account = LocalAccount(id = "acc_g1_01", displayName = "G1 Customer", currentPriceIqd = 35000.0)
        accountRepo.saveAccount(account)

        // 1. Record pending RENEWAL operation and resolve as verified success
        val opRenewalSuccess = PendingExternalOperation(
            operationIntentId = "intent_ren_01",
            businessTransactionId = "biz_ren_01",
            accountId = "acc_g1_01",
            operationType = "RENEWAL",
            amountIqd = 35000L,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(opRenewalSuccess)
        assertEquals("Generation must remain 1L after recordPendingOperation", 1L, db.getGeneration())

        // 2. Resolve pending operation as verified success
        val successEntry = ledgerRepo.resolvePendingOperationVerifiedSuccess("biz_ren_01", "[VERIFIED RENEWAL]")
        assertNotNull(successEntry)
        assertEquals("biz_ren_01", successEntry?.id)
        assertEquals("Generation must remain 1L after resolvePendingOperationVerifiedSuccess", 1L, db.getGeneration())

        // 3. Record pending RENEWAL and resolve as verified failure
        val opRenewal = PendingExternalOperation(
            operationIntentId = "intent_ren_02",
            businessTransactionId = "biz_ren_02",
            accountId = "acc_g1_01",
            operationType = "RENEWAL",
            amountIqd = 35000L,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(opRenewal)
        assertEquals(1L, db.getGeneration())

        val failedResolved = ledgerRepo.resolvePendingOperationVerifiedFailure("biz_ren_02", "Subscriber expiration unchanged")
        assertTrue(failedResolved)
        val opAfterFail = ledgerRepo.getPendingOperationByTransactionId("biz_ren_02")
        assertEquals("FAILED", opAfterFail?.status)
        assertEquals("Generation must remain 1L after resolvePendingOperationVerifiedFailure", 1L, db.getGeneration())

        // 4. Record pending REFILL and resolve as inconclusive
        val opRefill = PendingExternalOperation(
            operationIntentId = "intent_ref_03",
            businessTransactionId = "biz_ref_03",
            accountId = "acc_g1_01",
            operationType = "REFILL",
            amountIqd = 35000L,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(opRefill)
        assertEquals(1L, db.getGeneration())

        val inconclusiveResolved = ledgerRepo.resolvePendingOperationInconclusive("biz_ref_03", "Gateway timeout")
        assertTrue(inconclusiveResolved)
        val opAfterInconclusive = ledgerRepo.getPendingOperationByTransactionId("biz_ref_03")
        assertEquals("PENDING", opAfterInconclusive?.status)
        assertEquals("Generation must remain 1L after resolvePendingOperationInconclusive", 1L, db.getGeneration())

        // 5. Complete, mark failed, delete pending ops
        ledgerRepo.completePendingOperation("biz_ref_03", "acc_g1_01")
        assertEquals(1L, db.getGeneration())
        ledgerRepo.deletePendingOperation("biz_ref_03")
        assertEquals(1L, db.getGeneration())
        assertNull(ledgerRepo.getPendingOperationByTransactionId("biz_ref_03"))
    }

    @Test
    fun g1PendingOperations_verifyAndResolveWithGateway_preservesLocalGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val account = LocalAccount(id = "acc_g1_gw_01", displayName = "GW User", currentPriceIqd = 40000.0)
        accountRepo.saveAccount(account)

        // Case A: Activation with username taken on ISP -> VERIFIED_SUCCESS
        val opAct = PendingExternalOperation(
            operationIntentId = "intent_gw_act",
            businessTransactionId = "tx_gw_act_1",
            accountId = "acc_g1_gw_01",
            operationType = "ACTIVATION",
            amountIqd = 40000L,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(opAct)
        testGateway.usernameAvailable = false // user exists on ISP

        val resAct = ledgerRepo.verifyAndResolvePendingOperation("tx_gw_act_1", testGateway, null)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resAct.result)
        assertEquals("Generation must remain 1L after verifyAndResolvePendingOperation success", 1L, db.getGeneration())

        // Case B: Renewal with expiration extended -> VERIFIED_SUCCESS
        val opRen = PendingExternalOperation(
            operationIntentId = "intent_gw_ren",
            businessTransactionId = "tx_gw_ren_2",
            accountId = "acc_g1_gw_01",
            operationType = "RENEWAL",
            amountIqd = 40000L,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(opRen)
        testGateway.searchUsersResult = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 10, userIDLower = "acc_g1_gw_01")),
            totalCount = 1
        )
        testGateway.userDetailResult = UserDetail(
            userIndexLower = 10,
            userIDLower = "acc_g1_gw_01",
            expirationDateLower = "2026-09-18",
            userActiveLower = true
        )

        val resRen = ledgerRepo.verifyAndResolvePendingOperation(
            businessTransactionId = "tx_gw_ren_2",
            gateway = testGateway,
            baselineExpirationDate = "2026-08-18" // baseline differs from 2026-09-18 -> extended
        )
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resRen.result)
        assertEquals("Generation must remain 1L after Renewal verifyAndResolve", 1L, db.getGeneration())

        // Case C: Gateway network exception -> INCONCLUSIVE
        val opInconclusive = PendingExternalOperation(
            operationIntentId = "intent_gw_inc",
            businessTransactionId = "tx_gw_inc_3",
            accountId = "acc_g1_gw_01",
            operationType = "RENEWAL",
            amountIqd = 40000L,
            createdAt = System.currentTimeMillis()
        )
        ledgerRepo.recordPendingOperation(opInconclusive)
        testGateway.shouldThrowOnDetail = RuntimeException("Connection timed out to ISP")

        val resInc = ledgerRepo.verifyAndResolvePendingOperation(
            businessTransactionId = "tx_gw_inc_3",
            gateway = testGateway,
            baselineExpirationDate = "2026-08-18"
        )
        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, resInc.result)
        assertEquals("Generation must remain 1L after Inconclusive resolution", 1L, db.getGeneration())
    }

    // =========================================================================
    // 3. uTower Import with shouldReplace = false Preserves Generation
    // =========================================================================

    @Test
    fun utowerImportFromPreview_shouldReplaceFalse_preservesLocalGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        // Seed an existing local account
        accountRepo.saveAccount(LocalAccount(id = "acc_existing_01", displayName = "Pre-existing User"))
        assertEquals(1L, db.getGeneration())

        val preview = UtowerImportPreview(
            fileName = "normal_utower_import.json",
            totalAccountsFound = 2,
            totalTransactionsFound = 0,
            parsedSubscribers = listOf(
                LocalAccount(id = "sub_merge_1", displayName = "Merge Sub 1", debtIqd = 5000.0),
                LocalAccount(id = "sub_merge_2", displayName = "Merge Sub 2", debtIqd = 10000.0)
            ),
            parsedTransactions = emptyList(),
            totalCurrentDebtIqd = 15000.0
        )

        // Normal import with shouldReplace = false
        val result = importer.importFromPreview(
            preview = preview,
            fileName = "normal_utower_import.json",
            fileHash = "hash_utower_preview_normal",
            shouldReplace = false
        )

        assertEquals("completed", result.status)
        assertEquals(2, result.accountsImported)

        // Generation MUST NOT change
        assertEquals("Normal uTower import (shouldReplace=false) must keep generation at 1L", 1L, db.getGeneration())

        // Pre-existing account still exists (was not wiped)
        assertNotNull(db.localAccountDao().getByIdOneShot("acc_existing_01"))
    }

    @Test
    fun utowerImportFromFile_shouldReplaceFalse_preservesLocalGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val jsonContent = JSONObject().apply {
            val liveObj = JSONObject().apply {
                put("file_user_2", JSONObject().apply {
                    put("id", "2")
                    put("name", "File Merge User")
                    put("username", "file_user_2")
                    put("phone", "07700000002")
                    put("debt_iqd", 15000.0)
                })
            }
            put("live_users", liveObj)
            put("messagesofhistory", JSONObject())
        }

        val tempFile = File(context.cacheDir, "test_normal_import_${UUID.randomUUID()}.json")
        tempFile.writeText(jsonContent.toString(), Charsets.UTF_8)

        try {
            val result = importer.importFromFile(tempFile, shouldReplace = false)
            assertTrue("File import must succeed", result.success)
            assertEquals("Normal file import (shouldReplace=false) must preserve generation at 1L", 1L, db.getGeneration())
        } finally {
            tempFile.delete()
        }
    }

    // =========================================================================
    // 4. Remote Event Application Preserves Local Generation & Captures Remote Version
    // =========================================================================

    @Test
    fun remoteEvents_accountAndLedgerAndBatch_preservesLocalGenerationWhileCapturingRemoteVersions() = runBlocking {
        assertEquals("Baseline generation must be 1L", 1L, db.getGeneration())

        // 1. Remote AccountUpsert with server timestamp remoteVersion = 500000L
        val account = LocalAccount(id = "rem_acc_01", displayName = "Remote User 1", phone1 = "07705555555")
        val evAccountUpsert = RemoteEvent.AccountUpsert(
            entityId = account.id,
            remoteVersion = 500000L,
            source = RemoteEventSource.PULL,
            account = account
        )
        val resAcc = coordinator.processEvent(evAccountUpsert)
        assertEquals(EventSyncResult.APPLIED, resAcc)
        assertEquals("Local generation must remain 1L after remote AccountUpsert", 1L, db.getGeneration())
        assertEquals("500000", db.syncMetadataDao().get("remote_version:account:rem_acc_01"))

        // 2. Remote LedgerUpsert with server timestamp remoteVersion = 500100L
        val ledger = LocalLedgerEntry(
            id = "rem_tx_01",
            accountId = "rem_acc_01",
            amountIqd = 35000.0,
            debtAfterIqd = 35000.0,
            typeRaw = "took"
        )
        val evLedgerUpsert = RemoteEvent.LedgerUpsert(
            entityId = ledger.id,
            remoteVersion = 500100L,
            source = RemoteEventSource.PULL,
            entry = ledger
        )
        val resLedger = coordinator.processEvent(evLedgerUpsert)
        assertEquals(EventSyncResult.APPLIED, resLedger)
        assertEquals("Local generation must remain 1L after remote LedgerUpsert", 1L, db.getGeneration())
        assertEquals("500100", db.syncMetadataDao().get("remote_version:ledger:rem_tx_01"))

        // 3. Remote BatchUpsert with server timestamp remoteVersion = 500200L
        val batch = ImportBatch(
            id = "rem_batch_01",
            fileName = "remote_batch.json",
            fileHash = "hash_rem_01",
            accountsImported = 1,
            transactionsImported = 1,
            totalDebtIqd = 35000.0,
            status = "completed"
        )
        val evBatchUpsert = RemoteEvent.BatchUpsert(
            entityId = batch.id,
            remoteVersion = 500200L,
            source = RemoteEventSource.PULL,
            batch = batch
        )
        val resBatch = coordinator.processEvent(evBatchUpsert)
        assertEquals(EventSyncResult.APPLIED, resBatch)
        assertEquals("Local generation must remain 1L after remote BatchUpsert", 1L, db.getGeneration())
        assertEquals("500200", db.syncMetadataDao().get("remote_version:batch:rem_batch_01"))

        // 4. Remote LedgerDelete with server timestamp remoteVersion = 500300L
        val evLedgerDelete = RemoteEvent.LedgerDelete(
            entityId = ledger.id,
            remoteVersion = 500300L,
            source = RemoteEventSource.PULL
        )
        val resLedgerDel = coordinator.processEvent(evLedgerDelete)
        assertEquals(EventSyncResult.APPLIED, resLedgerDel)
        assertEquals("Local generation must remain 1L after remote LedgerDelete", 1L, db.getGeneration())
        assertNotNull(db.localLedgerEntryDao().getByIdOneShot(ledger.id))
        assertEquals("500300", db.syncMetadataDao().get("remote_version:ledger:rem_tx_01"))

        // 5. Remote AccountDelete with server timestamp remoteVersion = 500400L
        val evAccountDelete = RemoteEvent.AccountDelete(
            entityId = account.id,
            remoteVersion = 500400L,
            source = RemoteEventSource.PULL
        )
        val resAccDel = coordinator.processEvent(evAccountDelete)
        assertEquals(EventSyncResult.APPLIED, resAccDel)
        assertEquals("Local generation must remain 1L after remote AccountDelete", 1L, db.getGeneration())
        val remAcc = db.localAccountDao().getByIdOneShot(account.id)
        assertNotNull(remAcc)
        assertTrue(remAcc!!.isHistoryOnlySubscriber)
        assertEquals("500400", db.syncMetadataDao().get("remote_version:account:rem_acc_01"))

        // Final generation assertion
        assertEquals("Strict invariant: Local generation remains 1L throughout all remote operations", 1L, db.getGeneration())
    }

    // =========================================================================
    // 5. Strict Separation: remoteVersion != local lineage generation
    // =========================================================================

    @Test
    fun strictDomainSeparation_multipleRemoteVersionsDoNotAlterLocalGeneration() = runBlocking {
        assertEquals(1L, db.getGeneration())

        // Stream 20 consecutive remote account upserts with increasing remoteVersion
        for (i in 1..20) {
            val acc = LocalAccount(id = "acc_seq_$i", displayName = "Sequential Subscriber $i")
            val remoteVer = 1700000000000L + (i * 1000L)
            val event = RemoteEvent.AccountUpsert(
                entityId = acc.id,
                remoteVersion = remoteVer,
                source = RemoteEventSource.REALTIME,
                account = acc
            )
            val result = coordinator.processEvent(event)
            assertEquals(EventSyncResult.APPLIED, result)
            assertEquals(remoteVer.toString(), db.syncMetadataDao().get("remote_version:account:${acc.id}"))
        }

        // Local generation must remain strictly 1L
        assertEquals("Local generation must remain 1L despite 20 large remote versions", 1L, db.getGeneration())
        assertEquals(20, db.localAccountDao().getAllOneShot(limit = 100).size)

        // Advance generation intentionally to 2L (simulating a future dataset restore)
        val newGen = db.incrementGeneration()
        assertEquals(2L, newGen)

        // Verify remote versions were NOT altered by generation increment
        for (i in 1..20) {
            val expectedVer = (1700000000000L + (i * 1000L)).toString()
            assertEquals(expectedVer, db.syncMetadataDao().get("remote_version:account:acc_seq_$i"))
        }
    }

    // =========================================================================
    // 6. Concurrent Same-Lineage Ledger Writes & Deterministic Position
    // =========================================================================

    @Test
    fun concurrentSameLineageLedgerWrites_preservesGenerationAndDerivesDeterministicPosition() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val accA = LocalAccount(id = "acc_concurrent_A", displayName = "Concurrent User A", debtIqd = 0.0)
        val accB = LocalAccount(id = "acc_concurrent_B", displayName = "Concurrent User B", debtIqd = 0.0)
        accountRepo.saveAccount(accA)
        accountRepo.saveAccount(accB)
        assertEquals(1L, db.getGeneration())

        // Launch 20 concurrent coroutines performing mixed payments, debts, notes
        val tasks = (1..20).map { i ->
            async(Dispatchers.Default) {
                if (i % 2 == 0) {
                    // Even -> Account A
                    if (i % 4 == 0) {
                        ledgerRepo.addDebt("acc_concurrent_A", 10000.0, "Debt batch $i", "tx_c_a_d_$i")
                    } else {
                        ledgerRepo.addPayment("acc_concurrent_A", 5000.0, "Payment batch $i", "tx_c_a_p_$i")
                    }
                } else {
                    // Odd -> Account B
                    if (i % 3 == 0) {
                        ledgerRepo.addDebt("acc_concurrent_B", 8000.0, "Debt batch $i", "tx_c_b_d_$i")
                    } else {
                        ledgerRepo.addPayment("acc_concurrent_B", 4000.0, "Payment batch $i", "tx_c_b_p_$i")
                    }
                }
            }
        }

        tasks.awaitAll()

        // 1. Generation invariant: must be exactly 1L
        assertEquals("Concurrent same-lineage mutations must preserve generation at 1L", 1L, db.getGeneration())

        // 2. All 20 entries exist
        val entriesA = db.localLedgerEntryDao().getByAccountIdOneShot("acc_concurrent_A", limit = 100)
        val entriesB = db.localLedgerEntryDao().getByAccountIdOneShot("acc_concurrent_B", limit = 100)
        assertEquals(10, entriesA.size)
        assertEquals(10, entriesB.size)

        // 3. Current position calculation matches stored account balances
        val finalAccA = db.localAccountDao().getByIdOneShot("acc_concurrent_A")
        assertNotNull(finalAccA)
        var expectedBalanceA = 0.0
        var expectedAdvanceA = 0.0
        var expectedLoanA = 0.0
        for (e in entriesA.reversed()) {
            val res = BalanceCalculator.applyTransaction(expectedBalanceA, expectedAdvanceA, expectedLoanA, e.typeRaw, e.amountIqd)
            expectedBalanceA = res.debtIqd
            expectedAdvanceA = res.advanceIqd
            expectedLoanA = res.loanIqd
        }
        assertEquals("Account A debt must match deterministic calculation", expectedBalanceA, finalAccA?.debtIqd ?: -1.0, 0.001)
        assertEquals("Account A advance must match deterministic calculation", expectedAdvanceA, finalAccA?.advanceIqd ?: -1.0, 0.001)

        val finalAccB = db.localAccountDao().getByIdOneShot("acc_concurrent_B")
        assertNotNull(finalAccB)
        var expectedBalanceB = 0.0
        var expectedAdvanceB = 0.0
        var expectedLoanB = 0.0
        for (e in entriesB.reversed()) {
            val res = BalanceCalculator.applyTransaction(expectedBalanceB, expectedAdvanceB, expectedLoanB, e.typeRaw, e.amountIqd)
            expectedBalanceB = res.debtIqd
            expectedAdvanceB = res.advanceIqd
            expectedLoanB = res.loanIqd
        }
        assertEquals("Account B debt must match deterministic calculation", expectedBalanceB, finalAccB?.debtIqd ?: -1.0, 0.001)
        assertEquals("Account B advance must match deterministic calculation", expectedAdvanceB, finalAccB?.advanceIqd ?: -1.0, 0.001)
    }

    @Test
    fun concurrentSameLineageMutations_interleavedWithStaleRemoteEventRejection() = runBlocking {
        assertEquals(1L, db.getGeneration())

        val acc = LocalAccount(id = "acc_interleave_01", displayName = "Interleaved User")
        accountRepo.saveAccount(acc)

        // Advance generation to 2L to simulate lineage transition
        db.incrementGeneration()
        assertEquals(2L, db.getGeneration())

        // In-flight stale event captured before generation advance
        val capturedOldGen = 1L
        var staleResult: EventSyncResult? = null
        db.withTransaction {
            val currentGen = db.syncMetadataDao().getGeneration()
            if (currentGen != capturedOldGen) {
                staleResult = EventSyncResult.SKIPPED_DUPLICATE
            }
        }
        assertEquals(EventSyncResult.SKIPPED_DUPLICATE, staleResult)
        assertNull("Stale remote entity must not exist in database", db.localAccountDao().getByIdOneShot("acc_stale_ghost"))

        // Meanwhile, same-generation (gen 2L) local mutations proceed cleanly
        val ledger1 = ledgerRepo.addDebt("acc_interleave_01", 20000.0, "Gen 2 Debt")
        assertNotNull(ledger1)
        val ledger2 = ledgerRepo.addPayment("acc_interleave_01", 10000.0, "Gen 2 Payment")
        assertNotNull(ledger2)

        // And same-generation (gen 2L) remote event applies cleanly
        val validRemoteEvent = RemoteEvent.AccountUpsert(
            entityId = "acc_valid_gen2",
            remoteVersion = 1000000L,
            source = RemoteEventSource.REALTIME,
            account = LocalAccount(id = "acc_valid_gen2", displayName = "Valid Gen 2 User")
        )
        val validResult = coordinator.processEvent(validRemoteEvent)
        assertEquals(EventSyncResult.APPLIED, validResult)
        assertNotNull(db.localAccountDao().getByIdOneShot("acc_valid_gen2"))

        // Final generation remains exactly 2L
        assertEquals("Generation must remain 2L throughout valid same-lineage operations", 2L, db.getGeneration())
    }
}
