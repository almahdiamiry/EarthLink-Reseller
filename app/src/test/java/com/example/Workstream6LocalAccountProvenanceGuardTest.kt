package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * Claim: WS6 / ID-01 — Local-only accounts without ISP linkage (earthlinkUsername is null/blank
 * or ID starts with "local_") must never trigger gateway.getUserDetail(userIndex) with arbitrary
 * hashcode integers (INV-09 / RED-9).
 * Seam: Robolectric ViewModel + Room execution.
 * Independent Oracle: Invariant RED-9 (Identity & Provenance Integrity).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE)
class Workstream6LocalAccountProvenanceGuardTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var prefs: PreferenceManager
    private lateinit var dbFile: File

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_ws6_${UUID.randomUUID()}.db")
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
        Dispatchers.resetMain()
        db.close()
        if (dbFile.exists()) dbFile.delete()
    }

    private class ProvenanceSpyGateway : EarthlinkGateway {
        val getUserDetailCallCount = AtomicInteger(0)

        override suspend fun login(username: String, password: String): LoginResponse = throw NotImplementedError()
        override suspend fun getBalance(): Double = 100000.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 35000.0
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse = UserListResponse(itemsList = emptyList())
        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCallCount.incrementAndGet()
            return UserDetail(userIndexLower = userIndex, userIDLower = "isp_user_$userIndex", accountStatusLower = "Active", activeDaysLeftLower = 30.0)
        }
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = null
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = null
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = ""
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = ""
        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }

    @Test
    fun localOnlyAccount_doesNotInvokeGatewayGetUserDetail() = runBlocking {
        val localAcc = LocalAccount(
            id = "local_account_xyz",
            earthlinkUsername = null,
            displayName = "Local Only Customer",
            phone1 = "07700000000"
        )
        accountRepo.saveAccount(localAcc)

        val spyGateway = ProvenanceSpyGateway()
        val viewModel = EarthlinkSearchViewModel(
            gateway = spyGateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        val syntheticUserIndex = localAcc.id.hashCode()
        viewModel.loadUserDetail(syntheticUserIndex, knownUserId = "local_account_xyz")

        var attempts = 0
        while (viewModel.selectedUser.value == null && attempts < 50) {
            kotlinx.coroutines.delay(20)
            attempts++
        }

        assertEquals("gateway.getUserDetail must NEVER be called for local-only accounts", 0, spyGateway.getUserDetailCallCount.get())
        assertNotNull(viewModel.selectedUser.value)
        assertEquals("Local Only Customer", viewModel.selectedUser.value?.customerNameLower)
    }

    @Test
    fun ispLinkedAccount_invokesGatewayGetUserDetail() = runBlocking {
        val ispAcc = LocalAccount(
            id = "acc_real_001",
            earthlinkUsername = "real_isp_user",
            displayName = "ISP Linked Customer",
            phone1 = "07711111111"
        )
        accountRepo.saveAccount(ispAcc)

        val spyGateway = ProvenanceSpyGateway()
        val viewModel = EarthlinkSearchViewModel(
            gateway = spyGateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        viewModel.loadUserDetail(12345, knownUserId = "real_isp_user")

        var attempts = 0
        while (spyGateway.getUserDetailCallCount.get() == 0 && attempts < 50) {
            kotlinx.coroutines.delay(20)
            attempts++
        }

        assertEquals("gateway.getUserDetail must be called for ISP linked accounts", 1, spyGateway.getUserDetailCallCount.get())
    }

    @Test
    fun localAccountWithoutLocalPrefix_doesNotInvokeGatewayGetUserDetail() = runBlocking {
        val localAcc = LocalAccount(
            id = "custom_id_no_local_prefix",
            earthlinkUsername = null, // Strictly local-only
            displayName = "Custom Non-Prefix Customer",
            phone1 = "07709999999"
        )
        accountRepo.saveAccount(localAcc)

        val spyGateway = ProvenanceSpyGateway()
        val viewModel = EarthlinkSearchViewModel(
            gateway = spyGateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo
        )

        val syntheticUserIndex = localAcc.id.hashCode()
        viewModel.loadUserDetail(syntheticUserIndex, knownUserId = "custom_id_no_local_prefix")

        var attempts = 0
        while (viewModel.selectedUser.value == null && attempts < 50) {
            kotlinx.coroutines.delay(20)
            attempts++
        }

        assertEquals("gateway.getUserDetail must NEVER be called for local accounts regardless of ID format", 0, spyGateway.getUserDetailCallCount.get())
        assertNotNull(viewModel.selectedUser.value)
        assertEquals("Custom Non-Prefix Customer", viewModel.selectedUser.value?.customerNameLower)
    }
}
