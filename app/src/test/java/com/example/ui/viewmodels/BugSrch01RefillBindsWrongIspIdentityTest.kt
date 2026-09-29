package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.Phase1DuplicateInitiationProtectionTest
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.UserListItem
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED regression test for BUG-SRCH-1.
 *
 * Claim (RED Invariant — Target Product Contract v0.6 lines 50 & 467):
 * "A reseller must be able to use the application without silent loss, deletion,
 *  duplication, or corruption of account/ledger history."
 * A Refill must bind the ISP identity of the subscriber it actually charged, and
 * must never bind one subscriber's ISP index onto a different subscriber's
 * local account.
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite.
 *
 * Independent Oracle (not derived from production code):
 * Two distinct subscribers exist. Subscriber A's refill is dispatched and succeeds.
 * The only thing that changes mid-flight is which subscriber the UI has selected.
 * Expected: account A ends with ispUserIndex == A's index. Anything else means
 * A's account was bound to B's ISP identity.
 *
 * The gateway hook fires INSIDE refillUserDeposit, i.e. exactly at the suspend
 * point (EarthlinkSearchViewModel.kt:872) that sits BEFORE the post-suspension
 * state read at :888. This makes the race deterministic — no sleeps, no timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugSrch01RefillBindsWrongIspIdentityTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var gateway: HookableGateway

    /** ISP index that subscriber A actually owns. */
    private val indexA = 1001
    /** ISP index that subscriber B actually owns. */
    private val indexB = 2002

    class HookableGateway(
        val delegate: Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway =
            Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()
    ) : com.example.domain.repository.EarthlinkGateway by delegate {
        /** Invoked synchronously inside the gateway call, at the real suspend point. */
        var onRefillInFlight: ((userId: String) -> Unit)? = null

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            onRefillInFlight?.invoke(userId)
            return delegate.refillUserDeposit(userId, depositPassword)
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
        gateway = HookableGateway().apply { delegate.simulatedDelayMs = 0L }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun listItem(index: Int, userId: String) = UserListItem(
        userIndexLower = index,
        userIDLower = userId,
        customerNameLower = "Subscriber $index"
    )

    @Test
    fun refill_successWhileOperatorSelectsAnotherSubscriber_doesNotBindForeignIspIndex() = runBlocking {
        // Subscriber A: imported, therefore UNBOUND (ispUserIndex == null).
        // This is the documented default state for every uTower-imported account.
        val accA = LocalAccount(
            id = "acc_sub_a",
            sourceExternalId = "ext_a",
            earthlinkUsername = "sub_a",
            displayName = "Subscriber A",
            ispUserIndex = null,
            debtIqd = 0.0,
            currentPriceIqd = 25000.0,
            createdAt = System.currentTimeMillis()
        )
        accountRepo.saveAccount(accA)

        val vm = EarthlinkSearchViewModel(
            gateway = gateway,
            audit = auditRepo,
            prefs = prefs,
            localAccountRepository = accountRepo,
            localLedgerRepository = ledgerRepo,
            syncRepo = null
        )

        // Operator has subscriber A open. This is the ONLY legitimate selection
        // at the moment the refill is dispatched.
        vm.prepareUserDetail(indexA, listItem(indexA, "sub_a"))
        assertEquals(indexA, vm.selectedUser.value?.userIndex)

        // Mid-flight: the operator navigates to subscriber B while A's HTTP call
        // is still in the air. This is what the ungated BackHandler permits.
        gateway.onRefillInFlight = {
            vm.prepareUserDetail(indexB, listItem(indexB, "sub_b"))
        }

        vm.refillUser(
            userId = "sub_a",
            depositPass = "pass",
            price = 25000.0,
            account = accA
        ).join()

        val accAAfter = accountRepo.getAccountByIdOneShot("acc_sub_a")
        val accAAfterIdx = accAAfter?.ispUserIndex

        val diag = buildString {
            appendLine("dispatched refill for   : sub_a (ISP index $indexA)")
            appendLine("selection mid-flight    : sub_b (ISP index $indexB)")
            appendLine("RESULT acc_sub_a.ispUserIndex = $accAAfterIdx")
        }
        println(diag)

        // The Refill charged subscriber A. A's account must therefore carry A's
        // ISP identity. Binding indexB here is cross-subscriber identity corruption
        // and, via Repositories.kt:1128 (ispUserIndex matched first), would cause
        // subscriber B's next charge to be booked onto account A.
        assertEquals(
            "Subscriber A's account was bound to subscriber B's ISP identity. A's local " +
                "account now resolves to the wrong subscriber, and the next refill for B " +
                "books B's charge onto A's ledger.\n$diag",
            indexA,
            accAAfterIdx
        )
    }
}
