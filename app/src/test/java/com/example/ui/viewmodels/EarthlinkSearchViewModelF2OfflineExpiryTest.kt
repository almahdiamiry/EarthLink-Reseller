package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.Phase1DuplicateInitiationProtectionTest
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.network.EarthlinkTransportException
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EarthlinkSearchViewModelF2OfflineExpiryTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager

    class F2NetworkFailureGateway(
        delegate: EarthlinkGateway = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()
    ) : EarthlinkGateway by delegate {
        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): com.example.core.model.UserListResponse {
            throw EarthlinkTransportException("Network unavailable")
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
            saveIspAdminUsername("admin")
            saveIspAdminPassword("pass")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun testNetworkFailureFallbackSearchExpiry_IsoEvaluatesExpired() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "1", earthlinkUsername = "usr1", expiresAt = "2020-01-01T00:00:00Z"))
        db.localAccountDao().insert(LocalAccount(id = "2", earthlinkUsername = "usr2", expiresAt = "2099-01-01T00:00:00Z"))

        val accRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        val ledgerRepo = LocalLedgerRepositoryImpl(db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao(), db.pendingExternalOperationDao())
        val auditRepo = AuditRepositoryImpl(db, db.auditLogDao())

        val vm = EarthlinkSearchViewModel(F2NetworkFailureGateway(), auditRepo, prefs, accRepo, ledgerRepo)

        vm.setSearchQuery("usr")
        vm.search()
        delay(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val results = vm.usersList.value
        assertEquals("Expired", results.find { it.userID == "usr1" }?.accountStatus)
        assertEquals("Active", results.find { it.userID == "usr2" }?.accountStatus)
    }
}
