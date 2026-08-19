package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.sync.IspDisappearanceReconciler
import com.example.core.sync.RemoteEntityValidationResult
import com.example.core.sync.RemoteEntityValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verification suite for RC-03 and RC-04:
 * - Separate lifecycle state for isHistoryOnlySubscriber (decoupled from isLegacy).
 * - Monotonic transition for isHistoryOnlySubscriber.
 * - ISP disappearance algorithm using earthlinkUsername <-> userID mapping.
 * - Exclusion of null/blank earthlinkUsername accounts.
 * - Incomplete ISP fetch safety (abort on partial/failed data).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase5IspLifecycleAndHistoryOnlyTest {

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
    fun tearDown() {
        database.close()
    }

    @Test
    fun testIspDisappearance_marksActiveSubscriberAsHistoryOnly() = runBlocking {
        val acc1 = LocalAccount(
            id = "acc_active_1",
            displayName = "Active User 1",
            earthlinkUsername = "user_active_1",
            isHistoryOnlySubscriber = false
        )
        val acc2 = LocalAccount(
            id = "acc_active_2",
            displayName = "Active User 2",
            earthlinkUsername = "user_active_2",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(acc1)
        database.localAccountDao().insert(acc2)

        // ISP fetch contains only user_active_1 (user_active_2 disappeared)
        val authoritativeIsp = setOf("user_active_1")
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = authoritativeIsp,
            isFetchComplete = true
        )

        assertEquals(1, transitioned.size)
        assertEquals("acc_active_2", transitioned[0])

        val updatedAcc1 = database.localAccountDao().getByIdOneShot("acc_active_1")
        val updatedAcc2 = database.localAccountDao().getByIdOneShot("acc_active_2")

        assertNotNull(updatedAcc1)
        assertNotNull(updatedAcc2)
        assertFalse(updatedAcc1!!.isHistoryOnlySubscriber)
        assertTrue(updatedAcc2!!.isHistoryOnlySubscriber)

        // Check audit log
        val auditLogs = database.auditLogDao().getRecent(10)
        assertTrue(auditLogs.any { it.action == "ISP_SUBSCRIBER_DISAPPEARED" && it.entityId == "acc_active_2" })
    }

    @Test
    fun testIspDisappearance_excludesAccountsWithNullOrBlankUsername() = runBlocking {
        val accNoUser = LocalAccount(
            id = "acc_no_user",
            displayName = "Local Only Customer",
            earthlinkUsername = null,
            isHistoryOnlySubscriber = false
        )
        val accBlankUser = LocalAccount(
            id = "acc_blank_user",
            displayName = "Blank Username Customer",
            earthlinkUsername = "   ",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(accNoUser)
        database.localAccountDao().insert(accBlankUser)

        val authoritativeIsp = setOf("some_other_user")
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = authoritativeIsp,
            isFetchComplete = true
        )

        assertTrue(transitioned.isEmpty())

        val acc1 = database.localAccountDao().getByIdOneShot("acc_no_user")
        val acc2 = database.localAccountDao().getByIdOneShot("acc_blank_user")
        assertFalse(acc1!!.isHistoryOnlySubscriber)
        assertFalse(acc2!!.isHistoryOnlySubscriber)
    }

    @Test
    fun testIspDisappearance_abortsWhenFetchIsIncomplete() = runBlocking {
        val acc = LocalAccount(
            id = "acc_test",
            displayName = "Test User",
            earthlinkUsername = "user_test",
            isHistoryOnlySubscriber = false
        )
        database.localAccountDao().insert(acc)

        // Incomplete fetch (e.g. network failure or partial timeout)
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = emptySet(),
            isFetchComplete = false
        )

        assertTrue(transitioned.isEmpty())
        val accAfter = database.localAccountDao().getByIdOneShot("acc_test")
        assertFalse(accAfter!!.isHistoryOnlySubscriber)
    }

    @Test
    fun testIspDisappearance_monotonicity_neverResetsTrueToFalse() = runBlocking {
        val acc = LocalAccount(
            id = "acc_already_history",
            displayName = "History User",
            earthlinkUsername = "user_history",
            isHistoryOnlySubscriber = true
        )
        database.localAccountDao().insert(acc)

        // ISP fetch returns empty - already true, should not re-transition or duplicate audit
        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = database.localAccountDao(),
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = emptySet(),
            isFetchComplete = true
        )

        assertTrue(transitioned.isEmpty())
        val accAfter = database.localAccountDao().getByIdOneShot("acc_already_history")
        assertTrue(accAfter!!.isHistoryOnlySubscriber)
    }

    @Test
    fun testRemoteEntityValidator_monotonicHistoryOnlySubscriberMerge() {
        val existingLocal = LocalAccount(
            id = "acc_val_1",
            displayName = "Existing Local",
            isHistoryOnlySubscriber = true
        )

        // Remote payload has isHistoryOnlySubscriber = false (or absent)
        val remotePayload = mapOf<String, Any>(
            "displayName" to "Existing Local",
            "debtIqd" to 1000.0,
            "isHistoryOnlySubscriber" to false
        )

        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_val_1",
            d = remotePayload,
            remoteUpdatedAt = System.currentTimeMillis(),
            existingLocalAccount = existingLocal
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val mappedAccount = (result as RemoteEntityValidationResult.Valid).entity
        // Monotonic: true must not revert to false
        assertTrue(mappedAccount.isHistoryOnlySubscriber)
    }
}
