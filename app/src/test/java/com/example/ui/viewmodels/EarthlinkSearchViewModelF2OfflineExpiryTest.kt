package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.Phase1DuplicateInitiationProtectionTest
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification test for Finding F2: Offline Search Expiry Status in EarthlinkSearchViewModel.
 *
 * Verifies that when network fails or credentials are empty:
 * 1. An expired local account with an ISO timestamp ("2020-01-01T00:00:00Z") produces accountStatusLower == "Expired".
 * 2. An expired local account with a full datetime string ("2020-01-01 12:00:00") produces accountStatusLower == "Expired".
 * 3. A non-expired local account ("2099-01-01T00:00:00Z") produces accountStatusLower == "Active".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EarthlinkSearchViewModelF2OfflineExpiryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountDao: LocalAccountDao
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var accountRepository: LocalAccountRepository
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var auditRepository: AuditRepository
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var testGateway: Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountDao = db.localAccountDao()
        pendingDao = db.pendingExternalOperationDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()

        accountRepository = LocalAccountRepositoryImpl(
            database = db,
            accountDao = accountDao,
            outboxDao = outboxDao
        )
        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )
        auditRepository = AuditRepositoryImpl(
            database = db,
            auditDao = db.auditLogDao()
        )
        preferenceManager = PreferenceManager(context)
        testGateway = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun testOfflineSearchExpiryEvaluation_IsoAndFullDatetime_EvaluatesExpiredAndActive() = runTest(testDispatcher) {
        val expiredIsoAcc = LocalAccount(
            id = "acc_exp_iso",
            earthlinkUsername = "exp_iso_user",
            displayName = "Expired ISO User",
            expiresAt = "2020-01-01T00:00:00Z",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        val expiredBghAcc = LocalAccount(
            id = "acc_exp_bgh",
            earthlinkUsername = "exp_bgh_user",
            displayName = "Expired BGH User",
            expiresAt = "2020-01-01 12:00:00",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        val activeAcc = LocalAccount(
            id = "acc_active",
            earthlinkUsername = "active_user",
            displayName = "Active User",
            expiresAt = "2099-01-01T00:00:00Z",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )

        accountDao.insert(expiredIsoAcc)
        accountDao.insert(expiredBghAcc)
        accountDao.insert(activeAcc)

        preferenceManager.saveIspAdminUsername("")
        preferenceManager.saveIspAdminPassword("")

        val viewModel = EarthlinkSearchViewModel(
            gateway = testGateway,
            audit = auditRepository,
            prefs = preferenceManager,
            localAccountRepository = accountRepository,
            localLedgerRepository = ledgerRepository
        )

        viewModel.setSearchQuery("user")
        viewModel.search()
        advanceUntilIdle()

        val results = viewModel.usersList.value
        assertEquals(3, results.size)

        val isoResult = results.find { it.userID == "exp_iso_user" }
        assertEquals("Expired", isoResult?.accountStatus)

        val bghResult = results.find { it.userID == "exp_bgh_user" }
        assertEquals("Expired", bghResult?.accountStatus)

        val activeResult = results.find { it.userID == "active_user" }
        assertEquals("Active", activeResult?.accountStatus)
    }
}
