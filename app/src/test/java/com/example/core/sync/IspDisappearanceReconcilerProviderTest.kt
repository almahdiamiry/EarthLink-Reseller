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
 * Verification suite for provider-scoped ISP disappearance reconciliation (INV-02).
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Reconciling against EarthLink ISP subscriber list (targetProvider = EARTHLINK) must only
 *      evaluate and transition accounts with operationProvider == EARTHLINK. SAMM (ALAMIRY) accounts
 *      must never be transitioned to isHistoryOnlySubscriber = true during EarthLink reconciliation,
 *      even if their usernames do not appear in the EarthLink ISP list.
 *    - Reconciling against SAMM ISP subscriber list (targetProvider = ALAMIRY) must only evaluate
 *      and transition accounts with operationProvider == ALAMIRY. EarthLink accounts must never be
 *      transitioned to isHistoryOnlySubscriber = true during SAMM reconciliation.
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier ([RobolectricTestRunner]) with in-memory SQLite Room database ([AppDatabase]).
 *
 * 3. Independent Oracle:
 *    - Explicit literal expected lists of transitioned account IDs.
 *    - Direct inspection of reloaded LocalAccount entities from Room ensuring isHistoryOnlySubscriber
 *      flags match independent domain contract expectations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IspDisappearanceReconcilerProviderTest {

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
    fun reconcileEarthlink_doesNotTransitionSammAccount() = runTest {
        val accountDao = database.localAccountDao()

        val elMissing = LocalAccount(
            id = "acc_el_missing",
            displayName = "EarthLink Missing",
            earthlinkUsername = "el_missing_user",
            operationProvider = SasProviders.EARTHLINK,
            isHistoryOnlySubscriber = false
        )
        val elActive = LocalAccount(
            id = "acc_el_active",
            displayName = "EarthLink Active",
            earthlinkUsername = "el_active_user",
            operationProvider = SasProviders.EARTHLINK,
            isHistoryOnlySubscriber = false
        )
        val sammAccount = LocalAccount(
            id = "acc_samm",
            displayName = "SAMM Account",
            earthlinkUsername = "samm_user",
            operationProvider = SasProviders.ALAMIRY,
            isHistoryOnlySubscriber = false
        )

        accountDao.insert(elMissing)
        accountDao.insert(elActive)
        accountDao.insert(sammAccount)

        // EarthLink ISP list contains only el_active_user. Both el_missing_user and samm_user are absent.
        val authoritativeEarthlinkUsers = setOf("el_active_user")

        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = accountDao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = authoritativeEarthlinkUsers,
            isFetchComplete = true,
            targetProvider = SasProviders.EARTHLINK
        )

        // Oracle: Only the missing EarthLink account must be transitioned. SAMM account must be untouched.
        assertEquals(listOf("acc_el_missing"), transitioned)

        val reloadedElMissing = accountDao.getByIdOneShot("acc_el_missing")
        val reloadedElActive = accountDao.getByIdOneShot("acc_el_active")
        val reloadedSamm = accountDao.getByIdOneShot("acc_samm")

        assertNotNull(reloadedElMissing)
        assertNotNull(reloadedElActive)
        assertNotNull(reloadedSamm)

        assertTrue(reloadedElMissing!!.isHistoryOnlySubscriber)
        assertFalse(reloadedElActive!!.isHistoryOnlySubscriber)
        assertFalse(
            "SAMM account must not be transitioned during EarthLink reconciliation",
            reloadedSamm!!.isHistoryOnlySubscriber
        )
    }

    @Test
    fun reconcileSamm_transitionsOnlySammAccounts() = runTest {
        val accountDao = database.localAccountDao()

        val elAccount = LocalAccount(
            id = "acc_el",
            displayName = "EarthLink Account",
            earthlinkUsername = "el_user",
            operationProvider = SasProviders.EARTHLINK,
            isHistoryOnlySubscriber = false
        )
        val sammMissing = LocalAccount(
            id = "acc_samm_missing",
            displayName = "SAMM Missing",
            earthlinkUsername = "samm_missing_user",
            operationProvider = SasProviders.ALAMIRY,
            isHistoryOnlySubscriber = false
        )
        val sammActive = LocalAccount(
            id = "acc_samm_active",
            displayName = "SAMM Active",
            earthlinkUsername = "samm_active_user",
            operationProvider = SasProviders.ALAMIRY,
            isHistoryOnlySubscriber = false
        )

        accountDao.insert(elAccount)
        accountDao.insert(sammMissing)
        accountDao.insert(sammActive)

        // SAMM ISP list contains only samm_active_user. Both el_user and samm_missing_user are absent.
        val authoritativeSammUsers = setOf("samm_active_user")

        val transitioned = IspDisappearanceReconciler.reconcile(
            database = database,
            accountDao = accountDao,
            auditDao = database.auditLogDao(),
            authoritativeIspUserIds = authoritativeSammUsers,
            isFetchComplete = true,
            targetProvider = SasProviders.ALAMIRY
        )

        // Oracle: Only the missing SAMM account must be transitioned. EarthLink account must be untouched.
        assertEquals(listOf("acc_samm_missing"), transitioned)

        val reloadedEl = accountDao.getByIdOneShot("acc_el")
        val reloadedSammMissing = accountDao.getByIdOneShot("acc_samm_missing")
        val reloadedSammActive = accountDao.getByIdOneShot("acc_samm_active")

        assertNotNull(reloadedEl)
        assertNotNull(reloadedSammMissing)
        assertNotNull(reloadedSammActive)

        assertFalse(
            "EarthLink account must not be transitioned during SAMM reconciliation",
            reloadedEl!!.isHistoryOnlySubscriber
        )
        assertTrue(reloadedSammMissing!!.isHistoryOnlySubscriber)
        assertFalse(reloadedSammActive!!.isHistoryOnlySubscriber)
    }
}
