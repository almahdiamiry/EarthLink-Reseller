package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A subscriber who disappears from the provider that served them is NOT historical if they are
 * present on the other provider. They moved ISP. Their debt, loan and ledger must stay live, so the
 * account is re-pointed at the new provider instead of being flagged history-only.
 *
 * Core Triad:
 * 1. Claim - a username absent from the target roster but present in the migration roster has its
 *    operationProvider rewritten and is NOT marked history-only. A username absent from both is.
 *    Debt, loan and ledger entries survive the re-point byte for byte, and the re-point writes both
 *    an ISP_SUBSCRIBER_MIGRATED audit record and a local_accounts outbox row, because it re-points a
 *    financially-affecting account and the audit trail is the evidence for it.
 * 2. Seam - ROBOLECTRIC, in-memory Room, real AuditLogDao.
 * 3. Oracle - literal expected provider and literal money amounts per account, asserted by reloading
 * the entity. Expected values are written out by hand from the contract, not computed from the code
 * under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IspDisappearanceMigrationTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun goneFromEarthLinkButAliveOnSamm_migratesProviderAndStaysLive() = runTest {
        val dao = database.localAccountDao()
        // Non-zero money on purpose: an assertion that debt and loan "survive" is vacuous when the
        // fixture seeds 0/0, because 0 is what a naive `assertEquals(0.0, ...)` would also accept
        // from an account that had simply been wiped.
        dao.insertAll(
            listOf(
                LocalAccount(
                    id = "moved",
                    displayName = "Moved Person",
                    earthlinkUsername = "moved_user",
                    operationProvider = SasProviders.EARTHLINK,
                    isHistoryOnlySubscriber = false,
                    debtIqd = 125000.0,
                    loanIqd = 40000.0
                ),
                LocalAccount(
                    id = "dead",
                    displayName = "Gone From Both",
                    earthlinkUsername = "gone_user",
                    operationProvider = SasProviders.EARTHLINK,
                    isHistoryOnlySubscriber = false,
                    debtIqd = 7500.0,
                    loanIqd = 0.0
                )
            )
        )

        // EarthLink roster no longer has either username.
        // SAMM roster has the one who moved, and not the one who is genuinely gone.
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = setOf("someone_else"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK,
            migrationUsernames = setOf("moved_user"),
            migrationTargetProvider = SasProviders.ALAMIRY
        )

        // Only the account that vanished from BOTH providers becomes historical.
        assertEquals(listOf("dead"), transitioned)

        val moved = dao.getByIdOneShot("moved")
        val dead = dao.getByIdOneShot("dead")

        // Moved: re-pointed at SAMM, still live, history intact.
        assertEquals(SasProviders.ALAMIRY, moved!!.operationProvider)
        assertFalse("migrated account must not be history-only", moved.isHistoryOnlySubscriber)

        // Dead: genuinely historical on EarthLink.
        assertEquals(SasProviders.EARTHLINK, dead!!.operationProvider)
        assertTrue("account absent from both providers must become history-only", dead.isHistoryOnlySubscriber)

        // The re-point is a financial re-target, so the money must come through untouched.
        assertEquals(
            "Migration must not silently clear the moved subscriber's debt",
            125000.0,
            moved.debtIqd,
            0.001
        )
        assertEquals(
            "Migration must not silently clear the moved subscriber's loan",
            40000.0,
            moved.loanIqd,
            0.001
        )
        assertEquals(7500.0, dead.debtIqd, 0.001)
    }

    /**
     * Claim: the migration writes an ISP_SUBSCRIBER_MIGRATED audit record. Passing auditDao = null
     *        skipped this path entirely, which is precisely the evidence trail for a re-point that
     *        changes where future financially-affecting operations are sent.
     * Seam: ROBOLECTRIC, in-memory Room with the real AuditLogDao.
     * Independent Oracle: One record whose action, entityId, severity and from/to metadata are
     *        literal values read out of the migration contract, not recomputed from the code.
     */
    @Test
    fun goneFromEarthLinkButAliveOnSamm_writesTheMigrationAuditTrail() = runTest {
        val dao = database.localAccountDao()
        dao.insert(
            LocalAccount(
                id = "moved",
                displayName = "Moved Person",
                earthlinkUsername = "moved_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = false,
                debtIqd = 125000.0
            )
        )

        IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = setOf("someone_else"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK,
            migrationUsernames = setOf("moved_user"),
            migrationTargetProvider = SasProviders.ALAMIRY
        )

        val audits = database.auditLogDao().getAllSync()
        val migration = audits.firstOrNull { it.action == "ISP_SUBSCRIBER_MIGRATED" }
        assertNotNull(
            "A financially-affecting re-point must leave an audit record. Saw: ${audits.map { it.action }}",
            migration
        )
        assertEquals("moved", migration!!.entityId)
        assertEquals("local_accounts", migration.entityType)
        assertEquals("WARNING", migration.severity)
        assertTrue(
            "The record must name the account that moved: ${migration.metadataJsonMasked}",
            migration.metadataJsonMasked!!.contains("\"from\":\"EARTHLINK\"") &&
                migration.metadataJsonMasked!!.contains("\"to\":\"ALAMIRY\"") &&
                migration.metadataJsonMasked!!.contains("\"accountId\":\"moved\"")
        )

        // And the new provider tag must reach the cloud, or every other device keeps routing this
        // account's operations at the ISP that no longer has the subscriber.
        val outbox = database.syncOutboxDao().getByEntity("moved", "local_accounts")
        assertEquals(
            "The re-pointed account must be queued for sync",
            1,
            outbox.size
        )
        assertTrue(
            "The queued payload must carry the NEW provider: ${outbox.first().payloadJson}",
            outbox.first().payloadJson.contains("\"operationProvider\":\"ALAMIRY\"")
        )
    }

    /**
     * Claim: an incomplete fetch aborts before any transition, audit or outbox write (INV-12).
     * Seam: ROBOLECTRIC, in-memory Room with the real AuditLogDao.
     * Independent Oracle: Zero returned transitions, zero audit records and zero outbox rows are the
     *        only outcomes consistent with an abort; the account must be byte-for-byte untouched.
     */
    @Test
    fun incompleteFetch_abortsWithoutAnyWrite() = runTest {
        val dao = database.localAccountDao()
        dao.insert(
            LocalAccount(
                id = "moved",
                displayName = "Moved Person",
                earthlinkUsername = "moved_user",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = false,
                debtIqd = 125000.0
            )
        )

        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = setOf("someone_else"),
            isFetchComplete = false,
            targetProvider = SasProviders.EARTHLINK,
            migrationUsernames = setOf("moved_user"),
            migrationTargetProvider = SasProviders.ALAMIRY
        )

        assertEquals(emptyList<String>(), transitioned)
        val moved = dao.getByIdOneShot("moved")!!
        assertEquals(SasProviders.EARTHLINK, moved.operationProvider)
        assertFalse(moved.isHistoryOnlySubscriber)
        assertEquals(125000.0, moved.debtIqd, 0.001)
        assertEquals(emptyList<Any>(), database.auditLogDao().getAllSync())
        assertEquals(0, database.syncOutboxDao().getByEntity("moved", "local_accounts").size)
    }

    @Test
    fun withoutMigrationRoster_behaviourIsUnchanged() = runTest {
        val dao = database.localAccountDao()
        dao.insertAll(
            listOf(
                LocalAccount(
                    id = "gone",
                    displayName = "Gone",
                    earthlinkUsername = "gone_user",
                    operationProvider = SasProviders.EARTHLINK,
                    isHistoryOnlySubscriber = false,
                    debtIqd = 30000.0,
                    loanIqd = 5000.0
                )
            )
        )

        // Defaults must behave exactly as before: no migration set, no migration target.
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = setOf("someone_else"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )

        assertEquals(listOf("gone"), transitioned)
        val gone = dao.getByIdOneShot("gone")
        assertTrue(gone!!.isHistoryOnlySubscriber)
        assertEquals(SasProviders.EARTHLINK, gone.operationProvider)
        // History-only is a routing decision, never a financial one (INV-02).
        assertEquals(30000.0, gone.debtIqd, 0.001)
        assertEquals(5000.0, gone.loanIqd, 0.001)
    }

    /**
     * Claim: a v17-restore survivor wrongly archived as history-only is un-archived by the same
     *        "absent here, alive there" ISP-presence rule the live rows already enjoy. This is the
     *        exact shape of the post-restore defect: migration 18_19 backfills every restored row
     *        with operationProvider=EARTHLINK, a SAMM subscriber is absent from the EarthLink
     *        roster, and with no SAMM session on the device the reconciler flipped the row to
     *        history-only. The history-only branch must be able to migrate+revive, otherwise the
     *        false archive is a permanent deadlock: the provider-scoped re-appearance pass can
     *        never see an EARTHLINK-labeled row, and the history-only branch bypasses migration.
     * Seam: ROBOLECTRIC, in-memory Room with the real AuditLogDao.
     * Independent Oracle: literal provider/flag/money values re-read from the entity, plus one
     *        ISP_SUBSCRIBER_MIGRATED audit record and one outbox row carrying the new provider;
     *        expected values are written out by hand from the contract, not computed.
     */
    @Test
    fun wronglyArchivedByRestore_absentEarthLink_aliveSamm_migratesAndRevives() = runTest {
        val dao = database.localAccountDao()
        dao.insert(
            LocalAccount(
                id = "ali",
                displayName = "Ali Akili",
                earthlinkUsername = "alialakily@sacx",
                operationProvider = SasProviders.EARTHLINK,
                isHistoryOnlySubscriber = true,
                debtIqd = 230000.0,
                loanIqd = 0.0
            )
        )

        // EarthLink roster: no alialakily@sacx. SAMM roster: the subscriber is there and alive.
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = setOf("someone_else@sacx"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK,
            migrationUsernames = setOf("alialakily@sacx"),
            migrationTargetProvider = SasProviders.ALAMIRY
        )

        // Revival is an un-archival, not a new history-flip: it must not report the account
        // as a disappearance transition.
        assertEquals(emptyList<String>(), transitioned)

        val ali = dao.getByIdOneShot("ali")!!
        assertEquals(
            "A subscriber alive on SAMM must be routed at SAMM, not stay on the provider that lost him",
            SasProviders.ALAMIRY,
            ali.operationProvider
        )
        assertFalse(
            "ISP presence is the un-archive proof: the false history-only fix must be undone",
            ali.isHistoryOnlySubscriber
        )
        // The archive was only ever a routing decision; the money must survive byte for byte.
        assertEquals("Revival must not touch the restored debt", 230000.0, ali.debtIqd, 0.001)
        assertEquals("Revival must not touch the loan", 0.0, ali.loanIqd, 0.001)

        val audits = database.auditLogDao().getAllSync()
        val migration = audits.firstOrNull { it.action == "ISP_SUBSCRIBER_MIGRATED" }
        assertNotNull(
            "Reviving a financially-affecting account must leave an audit record. Saw: ${audits.map { it.action }}",
            migration
        )
        assertEquals("ali", migration!!.entityId)

        val outbox = database.syncOutboxDao().getByEntity("ali", "local_accounts")
        assertEquals(
            "The revived account must be queued for cloud sync",
            1,
            outbox.size
        )
        assertTrue(
            "The queued payload must carry the new provider and the un-archived flag: ${outbox.first().payloadJson}",
            outbox.first().payloadJson.contains("\"operationProvider\":\"ALAMIRY\"") &&
                outbox.first().payloadJson.contains("\"isHistoryOnlySubscriber\":false")
        )
    }
}