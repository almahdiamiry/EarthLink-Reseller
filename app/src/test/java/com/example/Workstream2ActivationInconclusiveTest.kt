package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.network.EarthlinkInconclusiveException
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class Workstream2ActivationInconclusiveTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var prefs: PreferenceManager
    private lateinit var dbFile: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_ws2_${UUID.randomUUID()}.db")
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
        auditRepo = AuditRepositoryImpl(auditDao = db.auditLogDao())
        prefs = PreferenceManager(context)
    }

    @After
    fun tearDown() {
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    private open class FakeGateway : EarthlinkGateway {
        override suspend fun login(username: String, password: String): LoginResponse = throw NotImplementedError()
        override suspend fun getBalance(): Double = 100000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = UserListResponse(itemsList = emptyList())
        override suspend fun getUserDetail(userIndex: Int): UserDetail = UserDetail(userIndexLower = 101, userIDLower = "user1", accountStatusLower = "Active", activeDaysLeftLower = 30.0)
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = "pass123"
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = "pass456"
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = "pass"
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    @Test
    fun testActivationMissingUserIndexResolvesToInconclusive() = runTest {
        val fakeGateway = object : FakeGateway() {
            override suspend fun createUserUsingDeposit(
                username: String,
                phone: String,
                fullName: String,
                accountIndex: Int,
                depositPassword: String
            ): String? {
                throw EarthlinkInconclusiveException(
                    statusCode = 200,
                    message = "API returned success without valid userIndex payload."
                )
            }
        }

        val viewModel = EarthlinkSearchViewModel(
            gateway = fakeGateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        val intentId = "intent_ws2_test"
        val businessTxId = "tx_" + intentId
        val job = viewModel.createUserUsingDeposit(
            username = "sub_inconclusive",
            phone = "07700000000",
            fullName = "Sub Inconclusive",
            pkgIndex = 1,
            depositPass = "dep_secret",
            intentId = intentId
        )
        job.join()

        // Verify that the pending operation was NOT marked as FAILED
        val op = ledgerRepo.getPendingOperationByTransactionId(businessTxId)
        assertNotNull("Pending operation must exist in database", op)
        assertNotEquals("Operation must NOT be marked as FAILED", "FAILED", op!!.status)
        assertEquals("PENDING", op.status)
        assertTrue(
            "Last error must record uncertainty",
            op.lastError?.contains("without valid userIndex") == true
        )
    }
}
