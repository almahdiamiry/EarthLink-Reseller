package com.example.ui.viewmodels

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.Phase1DuplicateInitiationProtectionTest
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED regression test for BUG-ACT-1.
 *
 * Claim: a failed provisioning attempt must not leave the previous attempt's success
 * message on screen. The success string is the only place the generated subscriber
 * password is ever displayed, so a stale one is a false credential claim about the
 * entity the operator is currently working on.
 *
 * The VM is Activity-scoped and shared by every destination (MainActivity creates it above
 * the NavHost), and `_actionSuccess` is never reset at the start of an attempt - only
 * `_error` is. The two banners are sibling AnimatedVisibility composables in the same
 * Column, so they render simultaneously.
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite, real ViewModel.
 *
 * Independent Oracle: after a FAILED attempt, the screen must not assert that some
 * subscriber "was created successfully" with a password. That assertion is only true of
 * the earlier, successful attempt for a different username.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugAct01StaleSuccessBannerOnFailedRetryTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: PreferenceManager
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepositoryImpl
    private lateinit var gateway: ScriptedGateway

    class ScriptedGateway(
        val delegate: Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway =
            Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway()
    ) : com.example.domain.repository.EarthlinkGateway by delegate {
        /** Usernames the ISP will report as already taken. */
        var takenUsernames: MutableSet<String> = mutableSetOf()
        var generatedPassword: String? = "Pass_481293"
        val createCalls: MutableList<String> = mutableListOf()

        override suspend fun checkUsernameAvailable(username: String): Boolean =
            username !in takenUsernames

        override suspend fun createUserUsingDeposit(
            username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String
        ): String? {
            createCalls.add(username)
            return generatedPassword
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
        gateway = ScriptedGateway().apply { delegate.simulatedDelayMs = 0L }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun vm() = EarthlinkSearchViewModel(
        gateway = gateway,
        audit = auditRepo,
        prefs = prefs,
        localAccountRepository = accountRepo,
        localLedgerRepository = ledgerRepo,
        syncRepo = null
    )

    @Test
    fun retryAfterSuccess_failingForAnotherUsername_doesNotLeaveStaleSuccessMessage() = runBlocking {
        val vm = vm()

        // ATTEMPT 1 succeeds for "alice" - the success string carries a live password.
        vm.createUserUsingDeposit(
            username = "alice", phone = "07700000001", fullName = "Alice",
            pkgIndex = 1, depositPass = "pass"
        ).join()

        val successAfterFirst = vm.actionSuccess.value
        assertTrue(
            "Precondition: attempt 1 must have reported success so the stale value exists.\n$successAfterFirst",
            successAfterFirst != null && successAfterFirst.contains("Password:")
        )
        println("TRACE after attempt 1: success='$successAfterFirst'")

        // The operator taps "Issue" again with the same (already-created) username.
        gateway.takenUsernames.add("alice")
        vm.createUserUsingDeposit(
            username = "alice", phone = "07700000001", fullName = "Alice",
            pkgIndex = 1, depositPass = "pass"
        ).join()

        val errorAfterSecond = vm.error.value
        val successAfterSecond = vm.actionSuccess.value
        val diag = "attempt2 error='$errorAfterSecond' staleSuccess='$successAfterSecond'"
        println(diag)

        assertTrue(
            "Precondition: attempt 2 must have failed.\n$diag",
            errorAfterSecond != null
        )
        assertEquals(
            "The second attempt FAILED, but the screen still asserts the earlier subscriber was " +
                "created successfully and shows that subscriber's live password next to a red " +
                "error banner. An operator can hand the wrong customer the wrong credential.\n$diag",
            null, successAfterSecond
        )
    }
}
