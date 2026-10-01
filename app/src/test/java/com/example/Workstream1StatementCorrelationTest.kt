package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.AccountStatementItem
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.UnknownOutcomeResolutionResult
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.EarthlinkGateway
import com.squareup.moshi.Moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Claim: two independent things.
 *
 * (a) `AccountStatementItem` correctly deserializes the 4 contract-proven ISP statement fields
 *     (deposit, withdrawal, balance, date) per API v0.7.0 lines 1200-1215.
 *
 * (b) The zone an ISP statement timestamp is resolved in is selected from the statement's
 *     FORMAT, not from a fixed offset: an ISO-8601 `"T"` statement is resolved in UTC, and any
 *     other statement format is resolved in Asia/Baghdad. This is INV-05 / RED-Invariant 5, and
 *     it is load-bearing for the ±90 s correlation window that consumes it — a 3-hour error
 *     there is a wrong-correlation window, which is a wrong-charge window.
 *
 * Seam: ROBOLECTRIC for both (a) and (b). `@RunWith(RobolectricTestRunner::class)` is
 * class-level, so (a) executes under Robolectric as well, even though it needs no Android runtime.
 * (b) is under Robolectric because the only route to the production
 * statement timestamp parser is the private `verifyRenewalViaStatement`, reachable only through
 * the public `verifyAndResolvePendingOperation` (`Repositories.kt:1747`), which needs a Room
 * database, a recorded `PendingExternalOperation` and a gateway.
 *
 * Independent Oracle: API documentation contract v0.7.0 lines 1200-1215 for the field names in
 * (a). For (b), the oracle is the ISP statement-time convention itself: one fixed instant
 * T = 1_768_476_600_000L is written `2026-01-15 11:30:00` when read in UTC and
 * `2026-01-15 14:30:00` when read in Asia/Baghdad. Both literals are derived by hand from T and
 * are not produced by any production or test formatter. No offset constant (and no
 * `SimpleDateFormat`) appears anywhere in this file — the zone production chose is observed
 * only through the correlation verdict production itself returns, so this test cannot be
 * satisfied by any hardcoded `+03:00`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream1StatementCorrelationTest {

    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(AccountStatementItem::class.java)

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl

    /** The single instant every case below is built around: 2026-01-15T11:30:00Z. */
    private val opInstant = 1_768_476_600_000L

    /** [opInstant] as its wall clock reads in UTC. */
    private val utcWallClock = "2026-01-15 11:30:00"

    /** The same [opInstant] as its wall clock reads in Asia/Baghdad (UTC+3). */
    private val baghdadWallClock = "2026-01-15 14:30:00"

    /** [opInstant] written in ISO-8601, which is the format carrying the `"T"` discriminator. */
    private val isoStatement = "2026-01-15T11:30:00"

    private val accountId = "user_tz_branch"
    private val amountIqd = 35_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? com.example.EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Returns a gateway whose only account statement carries [occurredAt], for a subscriber that
     * the REFILL branch will successfully look up (so correlation is reached via the 4-tuple
     * statement path rather than via the expiration-date shortcut).
     */
    private class StatementGateway(
        private val occurredAt: String,
        private val accountId: String
    ) : EarthlinkGateway by Phase1DuplicateInitiationProtectionTest.TestEarthlinkGateway() {

        override suspend fun getAccountStatement(
            startIndex: Int,
            rowCount: Int,
            query: String
        ): List<AccountStatementItem> = listOf(
            AccountStatementItem(
                occurredAt = occurredAt,
                operation = "Withdraw",
                depositAmount = 0.0,
                withdrawalAmount = 35_000.0,
                userIDLower = accountId
            )
        )

        override suspend fun searchUsers(
            query: String,
            startIndex: Int,
            rowCount: Int
        ): UserListResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 999, userIDLower = accountId))
        )

        override suspend fun getUserDetail(userIndex: Int): UserDetail = UserDetail(
            userIndexLower = 999,
            userIDLower = accountId,
            accountStatusLower = "Active",
            expirationDateLower = null
        )
    }

    /**
     * Records one REFILL operation created at [opInstant] with an already-acquired dispatch
     * claim, then hands it to the production statement correlator carrying a single statement
     * with [occurredAt]. Each case needs its own operation: a resolved operation short-circuits
     * without ever consulting the statement.
     */
    private suspend fun correlate(
        occurredAt: String,
        transactionId: String
    ): UnknownOutcomeResolutionResult {
        ledgerRepo.recordPendingOperation(
            PendingExternalOperation(
                businessTransactionId = transactionId,
                operationIntentId = "intent_$transactionId",
                accountId = accountId,
                operationType = "REFILL",
                amountIqd = amountIqd,
                status = "PENDING",
                dispatchClaimCount = 1,
                createdAt = opInstant
            )
        )
        return ledgerRepo.verifyAndResolvePendingOperation(
            transactionId,
            StatementGateway(occurredAt, accountId),
            baselineExpirationDate = null
        ).result
    }

    @Test
    fun testContractStatementFieldsDeserialization() {
        val ispJson = """
            {
                "date": "2026-01-15 14:30:00",
                "operation": "Deposit",
                "deposit": 25000.0,
                "withdrawal": 0.0,
                "balance": 150000.0,
                "userID": "test_user_01",
                "description": "Deposit to reseller"
            }
        """.trimIndent()

        val parsed = adapter.fromJson(ispJson)
        assertNotNull(parsed)

        assertEquals("2026-01-15 14:30:00", parsed!!.occurredAt)
        assertEquals(25000.0, parsed.depositAmount ?: 0.0, 0.001)
        assertEquals(0.0, parsed.withdrawalAmount ?: 0.0, 0.001)
        assertEquals(150000.0, parsed.balanceAfter ?: 0.0, 0.001)
    }

    @Test
    fun testStatementZoneIsFormatConditionalIsoUtcOtherwiseBaghdad() = runTest {
        // A verified financial success materializes a charge against this local account, so it
        // must exist before the correlator is allowed to reach the success path.
        db.localAccountDao().insert(
            LocalAccount(
                id = accountId,
                earthlinkUsername = accountId,
                displayName = "Timezone Branch",
                currentPriceIqd = amountIqd.toDouble(),
                debtIqd = 0.0
            )
        )

        // (1) The ISO "T" statement is read in UTC. Its text is the operation's own instant in
        //     UTC wall clock, so it must land inside the ±90 s correlation window.
        assertEquals(
            "CLAIM 1 | An ISO-8601 \"T\" statement must be resolved in UTC. Production resolved it " +
                "in some other zone, placing it outside the ±90s correlation window, so the " +
                "\"T\" -> UTC branch is not in force.",
            UnknownOutcomeResolutionResult.VERIFIED_SUCCESS,
            correlate(isoStatement, "tx_tz_iso_utc")
        )

        // (2) THE BRANCH ITSELF. Identical wall-clock digits to case (1), but in a non-ISO format.
        //     Production must read those digits as Asia/Baghdad, which places the statement 3
        //     hours before the same instant — far outside ±90 s — so it must NOT correlate.
        //     A test that resolved every statement in one single fixed zone would wrongly
        //     correlate here, so this case is what distinguishes a real format-conditional
        //     branch from a hardcoded offset.
        assertEquals(
            "CLAIM 2 | The same wall-clock digits in a non-ISO format must resolve to a DIFFERENT " +
                "instant than the ISO form, and therefore fall outside the ±90s window. It " +
                "correlated, so production is resolving every statement in one zone and the " +
                "format-conditional branch has collapsed.",
            UnknownOutcomeResolutionResult.INCONCLUSIVE,
            correlate(utcWallClock, "tx_tz_same_digits")
        )

        // (3) The other direction. The non-ISO statement carrying the operation's own instant in
        //     Asia/Baghdad wall clock must be read in Asia/Baghdad — that is, back to the
        //     operation's instant — and must correlate.
        assertEquals(
            "CLAIM 3 | A non-ISO statement carrying the Asia/Baghdad wall clock of the operation's " +
                "own instant must be resolved in Asia/Baghdad and correlate. Production resolved it " +
                "in some other zone, so the else -> Baghdad branch is not in force.",
            UnknownOutcomeResolutionResult.VERIFIED_SUCCESS,
            correlate(baghdadWallClock, "tx_tz_non_iso_baghdad")
        )
    }
}
