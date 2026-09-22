package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.LocalAccountDao
import com.example.core.database.LocalLedgerEntryDao
import com.example.core.database.PendingExternalOperationDao
import com.example.core.database.SyncOutboxDao
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.data.repository.LocalLedgerRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for legacy durable ACTIVATION operations created before the durable
 * localAccountId target was introduced.
 *
 * Pre-fix legacy shape:
 * - PendingExternalOperation.accountId == mutable EarthLink username
 * - payloadJson has no localAccountId
 * - historical activation creation could create LocalAccount.id == username
 *
 * When that username is later recycled, the current compatibility path must not
 * treat the old primary-key collision as the new activation's financial target.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LegacyActivationRecycledUsernameTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var pendingDao: PendingExternalOperationDao
    private lateinit var accountDao: LocalAccountDao
    private lateinit var ledgerDao: LocalLedgerEntryDao
    private lateinit var outboxDao: SyncOutboxDao
    private lateinit var ledgerRepository: LocalLedgerRepositoryImpl

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? com.example.EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        pendingDao = db.pendingExternalOperationDao()
        accountDao = db.localAccountDao()
        ledgerDao = db.localLedgerEntryDao()
        outboxDao = db.syncOutboxDao()
        ledgerRepository = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = ledgerDao,
            accountDao = accountDao,
            outboxDao = outboxDao,
            pendingDao = pendingDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun legacyActivationWithoutPinnedTarget_mustFailClosedOnUsernamePrimaryKeyCollision() = runBlocking {
        val username = "recycled_legacy_user"
        val staleAccountId = username
        val txId = "tx_legacy_recycled_001"

        accountDao.insert(
            LocalAccount(
                id = staleAccountId,
                earthlinkUsername = username,
                displayName = "Old Subscriber",
                currentPriceIqd = 25000.0,
                debtIqd = 10000.0,
                isHistoryOnlySubscriber = false
            )
        )

        pendingDao.insert(
            PendingExternalOperation(
                businessTransactionId = txId,
                operationIntentId = "intent_legacy_recycled_001",
                accountId = username,
                operationType = "ACTIVATION",
                amountIqd = 35000L,
                // Legacy shape: no durable localAccountId.
                payloadJson = """{"username":"$username","phone":"07700000000","fullName":"New Subscriber","pkgIndex":1}""",
                status = "PENDING",
                dispatchClaimCount = 1
            )
        )

        try {
            ledgerRepository.resolvePendingOperationVerifiedSuccess(
                txId,
                "[VERIFIED ACTIVATION]"
            )
        } catch (e: IllegalStateException) {
            assertTrue(
                "Legacy Activation without durable target must fail closed",
                e.message?.contains("MISSING_LOCAL_FINANCIAL_TARGET") == true
            )
        }

        val accountAfter = accountDao.getByIdOneShot(staleAccountId)
        assertEquals(
            "Stale account must not receive the new Activation debt",
            10000.0,
            accountAfter?.debtIqd ?: -1.0,
            0.001
        )
        assertTrue(
            "Stale account must not receive a new Activation ledger entry",
            ledgerDao.getByAccountIdOneShot(staleAccountId).isEmpty()
        )

        val operationAfter = pendingDao.getByBusinessTransactionId(txId)
        assertEquals(
            "Legacy unresolved operation must not be completed without a safe target",
            "PENDING",
            operationAfter?.status
        )
        assertTrue(
            "No financial outbox should be emitted for a failed-closed legacy Activation",
            outboxDao.getByEntity(staleAccountId, "local_accounts").isEmpty()
        )
    }
}
