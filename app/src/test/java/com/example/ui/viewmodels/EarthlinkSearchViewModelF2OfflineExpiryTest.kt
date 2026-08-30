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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EarthlinkSearchViewModelF2OfflineExpiryTest {

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
        Dispatchers.setMain(Dispatchers.Unconfined)
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
    fun testOfflineSearchExpiryEvaluation_IsoAndFullDatetime_EvaluatesExpiredAndActive() = runBlocking {
        val expiredIsoAcc = LocalAccount(
            id = "acc_exp_iso",
            earthlinkUsername = "user_exp_iso",
            displayName = "User Expired ISO",
            expiresAt = "2020-01-01T00:00:00Z",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        val expiredBghAcc = LocalAccount(
            id = "acc_exp_bgh",
            earthlinkUsername = "user_exp_bgh",
            displayName = "User Expired BGH",
            expiresAt = "2020-01-01 12:00:00",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )
        val activeAcc = LocalAccount(
            id = "acc_active",
            earthlinkUsername = "user_active",
            displayName = "User Active",
            expiresAt = "2099-01-01T00:00:00Z",
            currentPriceIqd = 35000.0,
            debtIqd = 0.0
        )

        accountDao.insert(expiredIsoAcc)
        accountDao.insert(expiredBghAcc)
        accountDao.insert(activeAcc)

        preferenceManager.setDemoMode(false)
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

        // Wait briefly for background IO execution and flush main looper
        delay(300)
        ShadowLooper.idleMainLooper()

        val results = viewModel.usersList.value
        val isoResult = results.find { it.userID == "user_exp_iso" }
        val bghResult = results.find { it.userID == "user_exp_bgh" }
        val activeResult = results.find { it.userID == "user_active" }

        assertNotNull("isoResult should not be null", isoResult)
        assertNotNull("bghResult should not be null", bghResult)
        assertNotNull("activeResult should not be null", activeResult)

        assertEquals("Expired", isoResult?.accountStatus)
        assertEquals("Expired", bghResult?.accountStatus)
        assertEquals("Active", activeResult?.accountStatus)
    }
}
