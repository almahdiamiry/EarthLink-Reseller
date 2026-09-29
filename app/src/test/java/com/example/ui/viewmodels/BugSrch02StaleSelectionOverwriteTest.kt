package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * RED regression test for BUG-SRCH-2.
 *
 * Claim: loadUserDetail must not leave _selectedUser pointing at a subscriber the
 * operator has already navigated away from. Target Product Contract v0.6:467
 * requires the reseller to run without known behavior that corrupts account history;
 * a stale selection is the input to a wrong-subscriber Extend (BUG-SRCH-2 harm).
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite.
 *
 * Independent Oracle: the operator's latest explicit selection is subscriber B.
 * When A's slower detail request finally returns, the visible selection must still
 * be B. Nothing about A's response is permitted to change the operator's choice.
 *
 * The gateway hook lets us hold A's response open while B is selected, then release
 * it — deterministic ordering with no sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugSrch02StaleSelectionOverwriteTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var gateway: ControlledDetailGateway

    private val indexA = 1001
    private val indexB = 2002

    class ControlledDetailGateway(
        val delegate: EarthlinkSearchViewModelSeamTest.SeamTestGateway =
            EarthlinkSearchViewModelSeamTest.SeamTestGateway()
    ) : EarthlinkGateway by delegate {
        /** Blocks inside getUserDetail until released. */
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var gateForUserIndex: Int? = null
        @Volatile var gateAwaited = false

        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            if (userIndex == gateForUserIndex) {
                gateAwaited = true
                gate?.await()
            }
            return UserDetail(
                userIndexLower = userIndex,
                userIDLower = "sub_$userIndex",
                customerFullNameLower = "Subscriber $userIndex",
                mobileNumberLower = "0770000$userIndex"
            )
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = PreferenceManager(context).apply {
            setDemoMode(false)
            saveIspAdminUsername("admin")
            saveIspAdminPassword("pass")
            setLanguage("ar")
        }
        accountRepo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        ledgerRepo = LocalLedgerRepositoryImpl(
            db, db.localLedgerEntryDao(), db.localAccountDao(),
            db.syncOutboxDao(), db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        gateway = ControlledDetailGateway()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun listItem(index: Int) = UserListItem(
        userIndexLower = index,
        userIDLower = "sub_$index",
        customerNameLower = "Subscriber $index"
    )

    @Test
    fun slowerEarlierDetailResponse_doesNotOverwriteNewerOperatorSelection() = runBlocking {
        accountRepo.saveAccount(
            LocalAccount(
                id = "acc_a", sourceExternalId = "ext_a", earthlinkUsername = "sub_$indexA",
                displayName = "Subscriber A", ispUserIndex = indexA,
                createdAt = System.currentTimeMillis()
            )
        )
        accountRepo.saveAccount(
            LocalAccount(
                id = "acc_b", sourceExternalId = "ext_b", earthlinkUsername = "sub_$indexB",
                displayName = "Subscriber B", ispUserIndex = indexB,
                createdAt = System.currentTimeMillis()
            )
        )

        val vm = EarthlinkSearchViewModel(
            gateway = gateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )

        // A's detail request is issued first but held open by the gateway.
        gateway.gateForUserIndex = indexA
        gateway.gate = kotlinx.coroutines.CompletableDeferred()
        val jobA = vm.loadUserDetail(indexA, "sub_$indexA")

        // Let A's coroutine actually reach the gate before the operator navigates.
        // Deterministic handshake instead of a sleep.
        while (!gateway.gateAwaited) {
            kotlinx.coroutines.yield()
        }

        // The operator gives up on A and opens B, which completes normally.
        vm.prepareUserDetail(indexB, listItem(indexB))
        val jobB = vm.loadUserDetail(indexB, "sub_$indexB")
        jobB.join()
        assertEquals(
            "Precondition: after selecting B, the selection is B",
            indexB, vm.selectedUser.value?.userIndex
        )

        // A's stale response now lands late.
        gateway.gate?.complete(Unit)
        jobA.join()

        val finalIndex = vm.selectedUser.value?.userIndex
        val diag = "final selectedUser.userIndex = $finalIndex (operator's latest choice was $indexB)"
        println(diag)

        assertEquals(
            "A's late detail response overwrote the operator's newer selection of B. The screen " +
                "now shows subscriber A while the navigation route still carries B's index, so a " +
                "confirm dialog can name A while Extend is dispatched against B.\n$diag",
            indexB, finalIndex
        )
    }
}
