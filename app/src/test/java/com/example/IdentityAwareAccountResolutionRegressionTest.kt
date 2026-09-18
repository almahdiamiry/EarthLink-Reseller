package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.UserListItem
import com.example.core.security.PreferenceManager
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.LocalAccountRepository
import com.example.domain.repository.LocalLedgerRepository
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import com.example.ui.viewmodels.EarthlinkSearchViewModelSeamTest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IdentityAwareAccountResolutionRegressionTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepository: LocalAccountRepository
    private lateinit var ledgerRepository: LocalLedgerRepository
    private lateinit var auditRepository: AuditRepository
    private lateinit var prefs: PreferenceManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? com.example.EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val accountDao = db.localAccountDao()
        val ledgerDao = db.localLedgerEntryDao()
        val outboxDao = db.syncOutboxDao()
        val pendingDao = db.pendingExternalOperationDao()
        accountRepository = LocalAccountRepositoryImpl(db, accountDao, outboxDao)
        ledgerRepository = LocalLedgerRepositoryImpl(db, ledgerDao, accountDao, outboxDao, pendingDao)
        auditRepository = AuditRepositoryImpl(db, db.auditLogDao())
        prefs = PreferenceManager(context)
    }

    @After
    fun tearDown() = db.close()

    private fun vm(): EarthlinkSearchViewModel = EarthlinkSearchViewModel(
        gateway = Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway(),
        audit = auditRepository,
        prefs = prefs,
        localAccountRepository = accountRepository,
        localLedgerRepository = ledgerRepository
    )

    @Test
    fun authoritativeIndex_selectsCorrectContainer_whenUsernameIsRecycled() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002))
        assertEquals("UUID_B", vm().getAccountByUsernameOrIdForUser(2002, "alice").first()?.id)
    }

    @Test
    fun authoritativeIndex_failsClosed_whenSameIdentityHasMultipleContainers() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_B1", earthlinkUsername = "alice", ispUserIndex = 2002))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B2", earthlinkUsername = "alice", ispUserIndex = 2002))
        assertEquals(null, vm().getAccountByUsernameOrIdForUser(2002, "alice").first())
    }

    @Test
    fun authoritativeIndex_failsClosed_whenUsernameBelongsToDifferentIdentity() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        assertEquals(null, vm().getAccountByUsernameOrIdForUser(2002, "alice").first())
    }

    @Test
    fun authoritativeIndex_canResolveSingleUnboundUsernameContainer() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_UNBOUND", earthlinkUsername = "alice", ispUserIndex = null))
        assertEquals("UUID_UNBOUND", vm().getAccountByUsernameOrIdForUser(2002, "alice").first()?.id)
    }

    @Test
    fun identityAwareResolver_remainsRoomReactive() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", displayName = "Alice", earthlinkUsername = "alice", ispUserIndex = 2002))
        val flow = vm().getAccountByUsernameOrIdForUser(2002, "alice")
        assertEquals("Alice", flow.first()?.displayName)
        val next = async { withTimeout(5000) { flow.drop(1).first() } }
        delay(100)
        val current = db.localAccountDao().getByIdOneShot("UUID_B")!!
        db.localAccountDao().update(current.copy(displayName = "Alice Updated"))
        assertEquals("Alice Updated", next.await()?.displayName)
    }

    @Test
    fun refill_withConflictingSyntheticAccount_failsClosedBeforeGatewayDispatch() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        val gateway = EarthlinkSearchViewModelSeamTest.SeamTestGateway()
        val viewModel = EarthlinkSearchViewModel(gateway, auditRepository, prefs, accountRepository, ledgerRepository)
        viewModel.prepareUserDetail(2002, UserListItem(userIndexLower = 2002, userIDLower = "alice"))
        val beforeAccounts = db.localAccountDao().getTotalCount()
        viewModel.refillUser(
            userId = "alice",
            depositPass = "pass",
            price = 35000.0,
            account = LocalAccount(id = "SYNTHETIC", earthlinkUsername = "alice")
        ).join()
        assertEquals(beforeAccounts, db.localAccountDao().getTotalCount())
        assertEquals(0, gateway.refillCalls.get())
        assertTrue(viewModel.error.value?.contains("ambiguous") == true || viewModel.error.value?.contains("conflicting") == true)
    }

    @Test
    fun conflictingIdentity_failsClosedForAllLocalMetadataMutations() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        val viewModel = vm()
        viewModel.prepareUserDetail(2002, UserListItem(userIndexLower = 2002, userIDLower = "alice"))

        val synthetic = LocalAccount(id = "SYNTHETIC", earthlinkUsername = "alice")

        viewModel.saveCustomerNote(synthetic, "must not persist").join()
        viewModel.saveCustomNanoIp(synthetic, "192.168.10.25").join()

        assertEquals(null, db.localAccountDao().getByIdOneShot("SYNTHETIC"))
        assertTrue(viewModel.error.value?.contains("ambiguous") == true || viewModel.error.value?.contains("conflicting") == true)
    }

    @Test
    fun conflictingIdentity_failsClosedForRemoteMutationsBeforeGatewayDispatch() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        val gateway = EarthlinkSearchViewModelSeamTest.SeamTestGateway()
        val viewModel = EarthlinkSearchViewModel(gateway, auditRepository, prefs, accountRepository, ledgerRepository)
        viewModel.prepareUserDetail(2002, UserListItem(userIndexLower = 2002, userIDLower = "alice"))

        val synthetic = LocalAccount(id = "SYNTHETIC", earthlinkUsername = "alice")

        viewModel.changeAccountType(
            userIndex = 2002,
            userId = "alice",
            accountIndex = 3,
            accountName = "Active",
            account = synthetic,
            newPriceIqd = 50000.0
        ).join()

        viewModel.updateUserDisplayName(
            userIndex = 2002,
            newName = "Should Not Persist",
            account = synthetic
        ).join()

        assertEquals(null, db.localAccountDao().getByIdOneShot("SYNTHETIC"))
        assertEquals(0, gateway.changeAccountTypeCalls.get())
        assertEquals(0, gateway.updateDisplayNameCalls.get())
        assertTrue(viewModel.error.value?.contains("ambiguous") == true || viewModel.error.value?.contains("conflicting") == true)
    }

    @Test
    fun refill_withQuotedNote_keepsExplicitPhysicalTargetInValidJson() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        val selected = LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002)
        db.localAccountDao().insert(selected)
        val gateway = EarthlinkSearchViewModelSeamTest.SeamTestGateway()
        val viewModel = EarthlinkSearchViewModel(gateway, auditRepository, prefs, accountRepository, ledgerRepository)
        viewModel.prepareUserDetail(2002, UserListItem(userIndexLower = 2002, userIDLower = "alice"))

        viewModel.refillUser(
            userId = "alice",
            depositPass = "pass",
            price = 35000.0,
            note = "Customer said \"renew\" today",
            account = selected
        ).join()

        val pending = db.pendingExternalOperationDao().getAllOneShot().single()
        val payload = org.json.JSONObject(pending.payloadJson)
        assertEquals("UUID_B", payload.getString("localAccountId"))
        assertEquals("Customer said \"renew\" today", payload.getString("note"))
        assertEquals(1, db.localLedgerEntryDao().getByAccountIdOneShot("UUID_B").size)
        assertEquals(0, db.localLedgerEntryDao().getByAccountIdOneShot("UUID_A").size)
    }

    @Test
    fun verifiedRefillMaterialization_usesExplicitPhysicalAccountId() = runBlocking {
        db.localAccountDao().insert(LocalAccount(id = "UUID_A", earthlinkUsername = "alice", ispUserIndex = 1001))
        db.localAccountDao().insert(LocalAccount(id = "UUID_B", earthlinkUsername = "alice", ispUserIndex = 2002))
        val txId = "charge_explicit_target"
        db.pendingExternalOperationDao().insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_explicit_target",
                accountId = "alice",
                operationType = "REFILL",
                amountIqd = 35000L,
                payloadJson = "{\"userId\":\"alice\",\"localAccountId\":\"UUID_B\",\"isWasil\":false}",
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )
        ledgerRepository.resolvePendingOperationVerifiedSuccess(txId, "")
        assertEquals(0, db.localLedgerEntryDao().getByAccountIdOneShot("UUID_A").size)
        assertEquals(1, db.localLedgerEntryDao().getByAccountIdOneShot("UUID_B").size)
    }
}