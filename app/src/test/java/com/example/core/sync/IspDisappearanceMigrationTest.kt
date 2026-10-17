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
 * 2. Seam - ROBOLECTRIC, in-memory Room.
 * 3. Oracle - literal expected provider per account, asserted by reloading the entity. Expected
 *    values are written out by hand from the contract, not computed from the code under test.
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
        dao.insertAll(
            listOf(
                LocalAccount(
                    id = "moved",
                    displayName = "Moved Person",
                    earthlinkUsername = "moved_user",
                    operationProvider = SasProviders.EARTHLINK,
                    isHistoryOnlySubscriber = false
                ),
                LocalAccount(
                    id = "dead",
                    displayName = "Gone From Both",
                    earthlinkUsername = "gone_user",
                    operationProvider = SasProviders.EARTHLINK,
                    isHistoryOnlySubscriber = false
                )
            )
        )

        // EarthLink roster no longer has either username.
        // SAMM roster has the one who moved, and not the one who is genuinely gone.
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = null,
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
                    isHistoryOnlySubscriber = false
                )
            )
        )

        // Defaults must behave exactly as before: no migration set, no migration target.
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = dao,
            auditDao = null,
            authoritativeIspUserIds = setOf("someone_else"),
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )

        assertEquals(listOf("gone"), transitioned)
        val gone = dao.getByIdOneShot("gone")
        assertTrue(gone!!.isHistoryOnlySubscriber)
        assertEquals(SasProviders.EARTHLINK, gone.operationProvider)
    }
}