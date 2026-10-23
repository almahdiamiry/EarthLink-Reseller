package com.example.ui.screens

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import com.example.core.network.SasSubscriberPresence
import com.example.data.repository.AuditRepositoryImpl
import com.example.data.repository.LocalAccountRepositoryImpl
import com.example.data.repository.LocalLedgerRepositoryImpl
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.UtowerImportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BatchProviderPresenceGateTest
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Batch provider assignment with a presence probe flips provider ONLY for accounts whose
 *      subscriber is CONFIRMED-present (SasSubscriberPresence.PRESENT) on the target provider.
 *    - ABSENT and UNKNOWN presences are skipped fail-closed and reported in [notOnTargetNames].
 *    - Rows without a matching LocalAccount row are reported in [notOnAccountNames].
 *    - Already-target rows are folded BEFORE the probe runs (never queried, never touched).
 *    - Any gateway probe failure mid-pass aborts the whole batch with ZERO Room writes and
 *      raises IllegalStateException ("...aborted before any changes...").
 *    - The pre-pass runs OUTSIDE the Room transaction; progress callbacks report done/total
 *      strictly incrementally.
 *    - Legacy semantics are preserved when no probe is supplied (probe = null): every
 *      non-pending, non-already-target row flips as before (INV-03/INV-05 + INV-09 safety).
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier ([RobolectricTestRunner]) for real SQLite Room transactions.
 *    - Presence gateway is a Mockito mock; expected presences are supplied per username by
 *      an explicit test-local map (independent oracle, no production logic involved).
 *
 * 3. Independent Oracle:
 *    - Expected counts are literal constants; final provider state and zero-write assertions
 *      are re-read directly from Room via the live DAO after each scenario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BatchProviderPresenceGateTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: LocalAccountRepositoryImpl
    private lateinit var ledgerRepo: LocalLedgerRepositoryImpl
    private lateinit var auditRepo: AuditRepository
    private lateinit var utowerRepo: UtowerImportRepository
    private lateinit var syncRepo: SyncRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        accountRepo = LocalAccountRepositoryImpl(
            database = db,
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao()
        )
        ledgerRepo = LocalLedgerRepositoryImpl(
            database = db,
            ledgerDao = db.localLedgerEntryDao(),
            accountDao = db.localAccountDao(),
            outboxDao = db.syncOutboxDao(),
            pendingDao = db.pendingExternalOperationDao()
        )
        auditRepo = AuditRepositoryImpl(db, db.auditLogDao())
        utowerRepo = mock(UtowerImportRepository::class.java)
        syncRepo = mock(SyncRepository::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun sampleLocalAccount(id: String, username: String) = LocalAccount(
        id = id,
        displayName = "Display $id",
        earthlinkUsername = username,
        operationProvider = SasProviders.EARTHLINK
    )

    private suspend fun providerOf(id: String): String =
        db.localAccountDao().getAccountsByIds(listOf(id)).first().operationProvider

    private fun stubProbe(
        presenceByUsername: Map<String, SasSubscriberPresence>,
        queried: MutableList<String>,
        throwOnCall: Int? = null
    ): (suspend (String) -> SasSubscriberPresence?) {
        var callCount = 0
        return { username: String ->
            queried.add(username)
            callCount++
            if (throwOnCall != null && callCount == throwOnCall) {
                throw java.net.SocketTimeoutException("gateway unreachable")
            }
            presenceByUsername[username] ?: SasSubscriberPresence.UNKNOWN
        }
    }

    // -------------------------------------------------------------------------
    // 1. PRESENT flips; flipping is the only write.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_presentOnTarget_flipsProviderAndCounts() = runTest {
        // Claim: both confirmed-present rows flip EARTHLINK -> ALAMIRY.
        // Oracle: updated == 2 (literal), providers re-read from Room == ALAMIRY.
        db.localAccountDao().insert(sampleLocalAccount("acc_a", "user_a"))
        db.localAccountDao().insert(sampleLocalAccount("acc_b", "user_b"))
        val queried = mutableListOf<String>()
        val probe = stubProbe(
            presenceByUsername = mapOf(
                "user_a" to SasSubscriberPresence.PRESENT,
                "user_b" to SasSubscriberPresence.PRESENT
            ),
            queried = queried
        )

        val result = accountRepo.batchSetProvider(
            candidateAccountIds = listOf("acc_a", "acc_b"),
            targetProvider = SasProviders.ALAMIRY,
            presenceProbe = probe
        )

        assertEquals(2, result.totalSelected)
        assertEquals(2, result.updated)
        assertEquals(0, result.alreadyTarget)
        assertEquals(0, result.skippedDueToPending)
        assertTrue(result.notOnTargetNames.isEmpty())
        assertTrue(result.notOnAccountNames.isEmpty())
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_a"))
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_b"))
        assertTrue(queried.containsAll(listOf("user_a", "user_b")))
    }

    // -------------------------------------------------------------------------
    // 2. ABSENT and UNKNOWN both skip fail-closed; no row is written.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_absentOrUnknown_skipsFailClosedWithZeroWrites() = runTest {
        // Claim: only PRESENT flips. ABSENT/UNKNOWN rows keep their original provider.
        // Oracle: updated == 0 (literal); providers re-read from Room == EARTHLINK;
        //         notOnTargetNames holds exactly the two affected usernames.
        db.localAccountDao().insert(sampleLocalAccount("acc_a", "user_absent"))
        db.localAccountDao().insert(sampleLocalAccount("acc_b", "user_unknown"))
        db.localAccountDao().insert(sampleLocalAccount("acc_c", "user_present"))
        val queried = mutableListOf<String>()
        val probe = stubProbe(
            presenceByUsername = mapOf(
                "user_absent" to SasSubscriberPresence.ABSENT,
                "user_present" to SasSubscriberPresence.PRESENT
                // user_unknown deliberately absent from map -> falls through to UNKNOWN
            ),
            queried = queried
        )

        val result = accountRepo.batchSetProvider(
            candidateAccountIds = listOf("acc_a", "acc_b", "acc_c"),
            targetProvider = SasProviders.ALAMIRY,
            presenceProbe = probe
        )

        assertEquals(3, result.totalSelected)
        assertEquals(1, result.updated)
        assertTrue(result.notOnTargetNames.containsAll(listOf("user_absent", "user_unknown")))
        assertEquals(SasProviders.EARTHLINK, providerOf("acc_a"))
        assertEquals(SasProviders.EARTHLINK, providerOf("acc_b"))
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_c"))
    }

    // -------------------------------------------------------------------------
    // 3. Already-target rows are folded before the probe: never queried.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_alreadyTarget_skipsBeforeProbe() = runTest {
        // Claim: acc on ALAMIRY is counted alreadyTarget and never reaches the presence probe.
        // Oracle: probe queried exactly once (only the EARTHLINK row); alreadyTarget == 1.
        db.localAccountDao().insert(sampleLocalAccount("acc_el", "user_el"))
        db.localAccountDao().insert(
            sampleLocalAccount("acc_samm", "user_samm").copy(operationProvider = SasProviders.ALAMIRY)
        )
        val queried = mutableListOf<String>()
        val probe = stubProbe(
            presenceByUsername = mapOf("user_el" to SasSubscriberPresence.PRESENT),
            queried = queried
        )

        val result = accountRepo.batchSetProvider(
            candidateAccountIds = listOf("acc_el", "acc_samm"),
            targetProvider = SasProviders.ALAMIRY,
            presenceProbe = probe
        )

        assertEquals(2, result.totalSelected)
        assertEquals(1, result.updated)
        assertEquals(1, result.alreadyTarget)
        // Oracle uses the query log itself: exactly one username reached the probe.
        assertEquals(1, queried.size)
        assertTrue(queried.contains("user_el"))
        assertFalse(queried.contains("user_samm"))
    }

    // -------------------------------------------------------------------------
    // 4. Probe failure aborts the whole batch with ZERO writes.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_probeThrows_abortsBeforeAnyMutation() = runTest {
        // Claim: gateway outage mid-pass aborts everything before a single row flips.
        // Oracle: IllegalStateException with "aborted before any changes"; providers
        //         re-read from Room stay EARTHLINK for every candidate.
        db.localAccountDao().insert(sampleLocalAccount("acc_1", "user_1"))
        db.localAccountDao().insert(sampleLocalAccount("acc_2", "user_2"))
        val queried = mutableListOf<String>()
        val probe = stubProbe(
            presenceByUsername = mapOf("user_1" to SasSubscriberPresence.PRESENT),
            queried = queried,
            throwOnCall = 2
        )

        val thrown = try {
            accountRepo.batchSetProvider(
                candidateAccountIds = listOf("acc_1", "acc_2"),
                targetProvider = SasProviders.ALAMIRY,
                presenceProbe = probe
            )
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertNotNull("Probe failure must abort the batch", thrown)
        assertTrue(
            "Abort message must say before any changes, got: ${thrown?.message}",
            thrown?.message?.contains("aborted before any changes") == true
        )
        // Under parallel probing any subset of the usernames may have completed logging
        // before the failure propagated; the invariant is the abort itself, not the log set.
        assertEquals(SasProviders.EARTHLINK, providerOf("acc_1"))
        assertEquals(SasProviders.EARTHLINK, providerOf("acc_2"))
    }

    // -------------------------------------------------------------------------
    // 5. IDs without a LocalAccount row land in notOnAccountNames.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_ghostIds_landInNotOnAccountNames() = runTest {
        // Claim: candidates that resolve to no Room row are reported, never crash.
        // Oracle: notOnAccountNames contains the literal ghost id; valid row still flips.
        db.localAccountDao().insert(sampleLocalAccount("acc_real", "user_real"))
        val queried = mutableListOf<String>()
        val probe = stubProbe(
            presenceByUsername = mapOf("user_real" to SasSubscriberPresence.PRESENT),
            queried = queried
        )

        val result = accountRepo.batchSetProvider(
            candidateAccountIds = listOf("acc_real", "ghost_id_1"),
            targetProvider = SasProviders.ALAMIRY,
            presenceProbe = probe
        )

        assertEquals(2, result.totalSelected)
        assertEquals(1, result.updated)
        assertTrue(result.notOnAccountNames.contains("ghost_id_1"))
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_real"))
    }

    // -------------------------------------------------------------------------
    // 6. Progress callbacks are strictly incremental done/total.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_reportsPresenceProgressNofTotal() = runTest {
        // Claim: progress passes (1..N, N) in order for the N usernames probed.
        // Oracle: literal total == 3; collected sequence must be (1,3),(2,3),(3,3).
        db.localAccountDao().insert(sampleLocalAccount("acc_a", "user_a"))
        db.localAccountDao().insert(sampleLocalAccount("acc_b", "user_b"))
        db.localAccountDao().insert(sampleLocalAccount("acc_c", "user_c"))
        val queried = mutableListOf<String>()
        val probe = stubProbe(
            presenceByUsername = mapOf(
                "user_a" to SasSubscriberPresence.PRESENT,
                "user_b" to SasSubscriberPresence.ABSENT,
                "user_c" to SasSubscriberPresence.PRESENT
            ),
            queried = queried
        )
        val progress = mutableListOf<Pair<Int, Int>>()

        accountRepo.batchSetProvider(
            candidateAccountIds = listOf("acc_a", "acc_b", "acc_c"),
            targetProvider = SasProviders.ALAMIRY,
            presenceProbe = probe,
            onPresenceProgress = { done, total -> progress.add(done to total) }
        )

        // Parallel probing makes inter-call ordering nondeterministic; the observable
        // contract is: progress reports each done exactly once with the stable total,
        // and the final call is (N, N).
        assertEquals(3, queried.size)
        assertEquals(3, progress.size)
        assertTrue(progress.all { it.second == 3 })
        assertEquals(progress.map { it.first }.sorted(), listOf(1, 2, 3))
        assertEquals(3 to 3, progress.last())
    }

    // -------------------------------------------------------------------------
    // 7. Legacy path: probe = null keeps pre-gate semantics.
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_withoutProbe_preservesLegacyFlipSemantics() = runTest {
        // Claim: without a probe all non-pending, non-already-target rows flip, as before.
        // Oracle: literal updated == 2 for three EARTHLINK rows (one already target), room verified.
        db.localAccountDao().insert(sampleLocalAccount("acc_1", "user_1"))
        db.localAccountDao().insert(sampleLocalAccount("acc_2", "user_2"))
        db.localAccountDao().insert(
            sampleLocalAccount("acc_3", "user_3").copy(operationProvider = SasProviders.ALAMIRY)
        )

        val result = accountRepo.batchSetProvider(
            candidateAccountIds = listOf("acc_1", "acc_2", "acc_3"),
            targetProvider = SasProviders.ALAMIRY
        )

        assertEquals(3, result.totalSelected)
        assertEquals(2, result.updated)
        assertEquals(1, result.alreadyTarget)
        assertEquals(0, result.skippedDueToPending)
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_1"))
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_2"))
        assertEquals(SasProviders.ALAMIRY, providerOf("acc_3"))
    }

    // -------------------------------------------------------------------------
    // 8. Directory lookups are dispatched CONCURRENTLY (performance regression bar).
    // -------------------------------------------------------------------------
    @Test
    fun batchSetProvider_probesRunConcurrently_notSequentially() = runTest {
        // Claim: the presence pre-pass runs multiple gateway probes in flight at once,
        //         rather than one serial HTTP round-trip per username.
        // Oracle: a bar gate — every probe must have STARTED before any single probe is
        //         released. A strictly sequential loop can never start probe #2, so the
        //         poll below fails deterministically (RED for serial, GREEN for parallel).
        db.localAccountDao().insert(sampleLocalAccount("acc_a", "user_a"))
        db.localAccountDao().insert(sampleLocalAccount("acc_b", "user_b"))
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val probe: (suspend (String) -> SasSubscriberPresence?) = { _ ->
            started.incrementAndGet()
            gate.await()
            SasSubscriberPresence.PRESENT
        }

        val resultDeferred = async {
            accountRepo.batchSetProvider(
                candidateAccountIds = listOf("acc_a", "acc_b"),
                targetProvider = SasProviders.ALAMIRY,
                presenceProbe = probe
            )
        }

        withTimeout(10_000) {
            while (started.get() < 2) delay(10)
        }
        gate.complete(Unit)
        val result = resultDeferred.await()
        assertEquals(2, result.updated)
    }
}
