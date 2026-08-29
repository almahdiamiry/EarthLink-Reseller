package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Workstream 13 Certification Test: G1 Real Process-Restart & File-Backed Persistence Durability.
 *
 * Verifies:
 * 1. True file-backed persistence across process-restart simulation (old DB completely closed).
 * 2. Old DB instance is completely unusable after close, confirming zero in-memory reuse.
 * 3. Fresh DB instance re-opens the file-backed SQLite database and re-hydrates state.
 * 4. Distinct handling for:
 *    - Case A: Request definitely failed (username still available on ISP -> marked FAILED, zero ledgers).
 *    - Case B: Request definitely succeeded (username unavailable on ISP / expiration advanced -> marked COMPLETED, exact 1 ledger).
 *    - Case C: Ambiguous/inconclusive network inspection (remains safe, zero rogue ledgers).
 * 5. Idempotent re-execution of startup/SyncWorker sweep (zero duplicate ledgers or outbox items).
 * 6. Durability of original businessTransactionId, account balance coherence, and outbox sync integrity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream13G1RealRestartCertificationTest {

    private lateinit var context: Context
    private lateinit var dbFile: File

    class MockGateway : EarthlinkGateway {
        var availableUsernames = mutableSetOf<String>()
        var userDetails = mutableMapOf<String, UserDetail>()

        override suspend fun login(username: String, password: String): LoginResponse =
            LoginResponse(accessToken = "test_token", tokenType = "Bearer", expiresIn = 3600)
        override suspend fun getBalance(): Double = 500000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            val matched = userDetails.values.filter { it.userID.contains(query, ignoreCase = true) }
            return UserListResponse(
                itemsList = matched.map { UserListItem(userIndexLower = it.userIndex, userIDLower = it.userID) },
                totalCount = matched.size
            )
        }
        override suspend fun getUserDetail(userIndex: Int): UserDetail =
            userDetails.values.firstOrNull { it.userIndex == userIndex } ?: UserDetail(userIndexLower = userIndex, userIDLower = "user_$userIndex")
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean =
            availableUsernames.contains(userId)
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        var statements = mutableListOf<AccountStatementItem>()
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = statements
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "g1_restart_test_${System.currentTimeMillis()}.db")
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    private fun openDatabase(file: File): AppDatabase {
        file.parentFile?.mkdirs()
        return Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }

    @Test
    fun testFileBackedPersistence_ProcessRestartRecovery_SuccessAndFailureCases() = runBlocking {
        val gateway = MockGateway()

        // -------------------------------------------------------------
        // PHASE 1: Process 1 creates initial data and pending ops on file-backed DB
        // -------------------------------------------------------------
        var db1: AppDatabase? = openDatabase(dbFile)
        val accSuccess = LocalAccount(
            id = "acc_g1_success",
            displayName = "G1 Succeeded User",
            earthlinkUsername = "acc_g1_success",
            debtIqd = 0.0,
            currentPriceIqd = 35000.0,
            updatedAt = 1000L
        )
        val accFailed = LocalAccount(
            id = "acc_g1_failed",
            displayName = "G1 Failed User",
            earthlinkUsername = "acc_g1_failed",
            debtIqd = 0.0,
            currentPriceIqd = 35000.0,
            updatedAt = 1000L
        )
        db1!!.localAccountDao().insert(accSuccess)
        db1.localAccountDao().insert(accFailed)

        val txIdSuccess = "tx_pending_success_001"
        val txIdFailed = "tx_pending_failed_002"

        val opSuccess = PendingExternalOperation(
            businessTransactionId = txIdSuccess,
            operationType = "ACTIVATION",
            accountId = "acc_g1_success",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = 1000L
        )
        val opFailed = PendingExternalOperation(
            businessTransactionId = txIdFailed,
            operationType = "ACTIVATION",
            accountId = "acc_g1_failed",
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = 1000L
        )

        val repo1 = LocalLedgerRepositoryImpl(
            database = db1,
            ledgerDao = db1.localLedgerEntryDao(),
            accountDao = db1.localAccountDao(),
            outboxDao = db1.syncOutboxDao()
        )
        repo1.recordPendingOperation(opSuccess)
        repo1.recordPendingOperation(opFailed)

        // Set ISP state:
        // acc_g1_success is NOT available (activation succeeded on ISP) and has matching 4-tuple statement
        // acc_g1_failed IS available (activation failed on ISP)
        gateway.availableUsernames.add("acc_g1_failed")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        gateway.statements.add(
            AccountStatementItem(
                occurredAt = sdf.format(java.util.Date(1000L)),
                operation = "Withdraw",
                withdrawalAmount = 35000.0,
                userIDLower = "acc_g1_success"
            )
        )

        // -------------------------------------------------------------
        // PHASE 2: SIMULATE PROCESS KILL
        // Close db1 completely, null out references, ensure old DB is closed and unusable
        // -------------------------------------------------------------
        db1.close()
        try {
            db1.localAccountDao().getByIdOneShot("acc_g1_success")
            fail("Old database must be completely closed and unusable")
        } catch (e: IllegalStateException) {
            // Expected: Database is closed
        }
        db1 = null

        // -------------------------------------------------------------
        // PHASE 3: SIMULATE FRESH PROCESS STARTUP
        // Open brand new DB instance from the exact same file-backed storage
        // -------------------------------------------------------------
        val db2 = openDatabase(dbFile)
        val repo2 = LocalLedgerRepositoryImpl(
            database = db2,
            ledgerDao = db2.localLedgerEntryDao(),
            accountDao = db2.localAccountDao(),
            outboxDao = db2.syncOutboxDao()
        )

        // Verify state loaded from disk
        val reloadedOps = db2.pendingExternalOperationDao().getPendingOperations()
        assertEquals(2, reloadedOps.size)

        // Execute recovery sweep (production entry point)
        val resolutions = repo2.sweepAndResolvePendingOperations(gateway, graceWindowMs = 0L)
        assertEquals(2, resolutions.size)

        val resSuccess = resolutions.first { it.operation.businessTransactionId == txIdSuccess }
        val resFailed = resolutions.first { it.operation.businessTransactionId == txIdFailed }

        // Assertions for Case B (Definitely Succeeded)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, resSuccess.result)
        assertEquals("COMPLETED", resSuccess.operation.status)
        assertNotNull("Ledger entry must be generated for verified success", resSuccess.ledgerEntry)
        assertEquals(txIdSuccess, resSuccess.ledgerEntry?.id)
        assertEquals("took", resSuccess.ledgerEntry?.typeRaw)
        assertEquals(35000.0, resSuccess.ledgerEntry?.amountIqd ?: 0.0, 0.001)

        // Verify account balance updated in Room DB
        val updatedAccSuccess = db2.localAccountDao().getByIdOneShot("acc_g1_success")
        assertEquals(35000.0, updatedAccSuccess?.debtIqd ?: 0.0, 0.001)

        // Verify exactly one ledger row exists for acc_g1_success
        val ledgersSuccess = db2.localLedgerEntryDao().getByAccountIdOneShot("acc_g1_success")
        assertEquals(1, ledgersSuccess.size)
        assertEquals(txIdSuccess, ledgersSuccess.first().id)

        // Verify outbox entry created for sync
        val outboxRows = db2.syncOutboxDao().getPending()
        val outboxSuccess = outboxRows.filter { it.entityId == txIdSuccess }
        assertEquals("Exactly one outbox entry for completed activation", 1, outboxSuccess.size)

        // Assertions for Case A (Definitely Failed)
        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_FAILURE, resFailed.result)
        assertEquals("FAILED", resFailed.operation.status)
        assertNull("Zero ledger entries generated for verified failure", resFailed.ledgerEntry)

        val updatedAccFailed = db2.localAccountDao().getByIdOneShot("acc_g1_failed")
        assertEquals(0.0, updatedAccFailed?.debtIqd ?: -1.0, 0.001)
        val ledgersFailed = db2.localLedgerEntryDao().getByAccountIdOneShot("acc_g1_failed")
        assertTrue("Zero ledgers for failed activation", ledgersFailed.isEmpty())

        // -------------------------------------------------------------
        // PHASE 4: IDEMPOTENCY CHECK
        // Second sweep must be completely idempotent
        // -------------------------------------------------------------
        val secondSweep = repo2.sweepAndResolvePendingOperations(gateway, graceWindowMs = 0L)
        assertTrue("No more pending operations remain to resolve", secondSweep.isEmpty())

        val finalLedgers = db2.localLedgerEntryDao().getByAccountIdOneShot("acc_g1_success")
        assertEquals("Ledger entries must not duplicate after second sweep", 1, finalLedgers.size)

        db2.close()
    }

    @Test
    fun testRenewalPendingOperation_ExpirationAdvanced_VerifiedSuccess() = runBlocking {
        val db = openDatabase(dbFile)
        val gateway = MockGateway()

        val accountId = "acc_renew_001"
        val account = LocalAccount(
            id = accountId,
            displayName = "Renew Subscriber",
            earthlinkUsername = accountId,
            debtIqd = 0.0,
            updatedAt = 1000L
        )
        db.localAccountDao().insert(account)

        val txId = "tx_renew_001"
        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationType = "RENEWAL",
            accountId = accountId,
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = 1000L
        )

        val repo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
        repo.recordPendingOperation(pendingOp)

        // Detail on ISP shows active user with future expiration
        gateway.userDetails[accountId] = UserDetail(
            userIndexLower = 10,
            userIDLower = accountId,
            userActiveLower = true,
            activeDaysLeftLower = 28,
            expirationDateLower = "2026-09-18"
        )

        val res = repo.verifyAndResolvePendingOperation(
            businessTransactionId = txId,
            gateway = gateway,
            baselineExpirationDate = "2026-08-18" // baseline was 1 month earlier
        )

        assertEquals(UnknownOutcomeResolutionResult.VERIFIED_SUCCESS, res.result)
        assertEquals("COMPLETED", res.operation.status)
        assertNotNull(res.ledgerEntry)
        assertEquals(txId, res.ledgerEntry?.id)
        assertEquals("took", res.ledgerEntry?.typeRaw)

        db.close()
    }

    @Test
    fun testInconclusivePendingOperation_NoLedgerPollution() = runBlocking {
        val db = openDatabase(dbFile)
        val throwingGateway = object : EarthlinkGateway by MockGateway() {
            override suspend fun checkUsernameAvailable(userId: String): Boolean {
                throw java.io.IOException("503 Service Unavailable")
            }
        }

        val accountId = "acc_inconclusive_001"
        val account = LocalAccount(
            id = accountId,
            displayName = "Inconclusive Subscriber",
            earthlinkUsername = accountId,
            debtIqd = 0.0,
            updatedAt = 1000L
        )
        db.localAccountDao().insert(account)

        val txId = "tx_inconclusive_001"
        val pendingOp = PendingExternalOperation(
            businessTransactionId = txId,
            operationType = "ACTIVATION",
            accountId = accountId,
            amountIqd = 35000L,
            status = "PENDING",
            dispatchClaimCount = 1,
            createdAt = 1000L
        )

        val repo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
        repo.recordPendingOperation(pendingOp)

        val res = repo.verifyAndResolvePendingOperation(txId, throwingGateway)
        assertEquals(UnknownOutcomeResolutionResult.INCONCLUSIVE, res.result)
        assertEquals("PENDING", res.operation.status)
        assertNotNull("Diagnostic error must be recorded for inconclusive resolution", res.operation.lastError)
        assertNull(res.ledgerEntry)

        // Verify no ledger created
        val ledgers = db.localLedgerEntryDao().getByAccountIdOneShot(accountId)
        assertTrue("Inconclusive operation must not create ledger entry", ledgers.isEmpty())

        db.close()
    }

    private fun findSourceFile(relPath: String): File {
        val candidates = listOf(
            File(relPath),
            File(relPath.removePrefix("app/")),
            File("app", relPath),
            File("..", relPath),
            File("../..", relPath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Source file not found for candidate paths $candidates (cwd: ${File(".").absolutePath})")
    }

    @Test
    fun testEarthlinkAppStartup_WiresRecoverySweep() {
        val appSource = findSourceFile("app/src/main/java/com/example/EarthlinkApp.kt")
        assertTrue("EarthlinkApp.kt must exist", appSource.exists())
        val content = appSource.readText()
        assertTrue(
            "EarthlinkApp startup wiring must trigger sweepAndResolvePendingOperations",
            content.contains("sweepAndResolvePendingOperations(earthlinkGateway)")
        )
    }
}
