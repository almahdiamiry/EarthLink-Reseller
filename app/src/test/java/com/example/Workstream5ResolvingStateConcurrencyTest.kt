package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.data.repository.LocalAccountRepositoryImpl
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Claim: WS5 / CONC-04, CONC-05 — Operations in RESOLVING state must return immediately with INCONCLUSIVE
 * without triggering gateway calls (INV-03 / RED-3). Cold-start recovery must serialize through
 * per-account locks.
 * Seam: Robolectric in-memory SQLite repository execution.
 * Independent Oracle: Invariant RED-3 (Single-Writer Claim, no duplicate external execution).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class Workstream5ResolvingStateConcurrencyTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var dbFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_ws5_${UUID.randomUUID()}.db")
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()

        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        accountRepo = LocalAccountRepositoryImpl(
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            database = db
        )
    }

    @After
    fun tearDown() {
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    private open class CountingGateway(
        val callCount: AtomicInteger = AtomicInteger(0)
    ) : EarthlinkGateway {
        override suspend fun login(username: String, password: String) = throw NotImplementedError()
        override suspend fun getBalance() = 100000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?) = 0
        override suspend fun getActiveTestUsersCount() = 0
        override suspend fun getPrepaidNeeded() = 0.0
        override suspend fun getPackages() = emptyList<AccountPackage>()
        override suspend fun getAccountCost(accountIndex: Int) = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int) = UserListResponse(itemsList = emptyList())
        override suspend fun getUserDetail(userIndex: Int) = UserDetail(userIndexLower = 101, userIDLower = "user1", accountStatusLower = "Active")
        override suspend fun autocompleteUser(query: String) = emptyList<AutocompleteUser>()
        override suspend fun checkUsernameAvailable(userId: String): Boolean {
            callCount.incrementAndGet()
            return false
        }
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String) = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int) = "pass123"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String) = "pass456"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String) = true
        override suspend fun extendUser(userIndex: Int) = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String) = emptyList<AccountStatementItem>()
        override suspend fun showUserPassword(userIndex: Int, userId: String) = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String) = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String) = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String) = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean) = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int) = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String) = true
    }

    @Test
    fun testResolvingStateReturnsInconclusiveWithoutHittingGateway() = runBlocking {
        val txId = "tx_resolving_test"
        val accountId = "acc_resolving_test"
        val op = PendingExternalOperation(
            businessTransactionId = txId,
            operationIntentId = "intent_resolving",
            accountId = accountId,
            operationType = "ACTIVATION",
            amountIqd = 35000L,
            status = "RESOLVING" // Pre-set in RESOLVING
        )
        db.pendingExternalOperationDao().insert(op)

        val gateway = CountingGateway()
        val resolution = ledgerRepo.verifyAndResolvePendingOperation(txId, gateway)

        assertEquals("Resolving state must return INCONCLUSIVE", UnknownOutcomeResolutionResult.INCONCLUSIVE, resolution.result)
        assertEquals("Gateway must NOT be called when operation is actively resolving", 0, gateway.callCount.get())
    }
}
