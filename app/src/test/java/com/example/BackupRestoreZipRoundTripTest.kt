package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * External-backup-file restore round trip.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - A plain-SQLite backup zip in the exact production export shape (entry
 *      "earthlink_reseller_db" + backup_info.json with encryptionMode "NONE")
 *      restores through [BackupManager.restoreBackupZip] end to end: data is
 *      replace-imported into the live Room database and reported as success.
 *    - Mirrors the field artifact the user cannot restore today (production
 *      export zip picked from the Downloads folder).
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier (androidx.test ApplicationProvider context).
 *    - Empty passphrase path: AppDatabase skips the SQLCipher support factory
 *      when the passphrase is empty, so the whole restore chain runs on plain
 *      SQLite inside the JVM sandbox (the encrypted path is INSTRUMENTED-tier).
 *
 * 3. Independent Oracle:
 *    - The source database is built independently in the test, the archive is
 *      hand-assembled with literal entry names and a literal info JSON copied
 *      from the field artifact, and the post-restore assertion re-reads the
 *      live database through the DAO and checks exact column values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BackupRestoreZipRoundTripTest {

    private lateinit var context: Context
    private val sourceDb = "zz_backup_roundtrip_src"
    private val defaultDbName = "earthlink_reseller_db"

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        AppDatabase.closeAndRemoveInstance(sourceDb)
        AppDatabase.closeAndRemoveInstance(defaultDbName)
        AppDatabase.closeAndRemoveInstance("merged_backup.db")
        Dispatchers.resetMain()
    }

    private suspend fun insertSourceAccount(): Unit {
        val db = AppDatabase.getDatabase(context, ByteArray(0), sourceDb)
        db.localAccountDao().insert(
            LocalAccount(
                id = "rt_account_1",
                displayName = "Round Trip User",
                earthlinkUsername = "roundtrip_user",
                operationProvider = SasProviders.EARTHLINK
            )
        )
        AppDatabase.closeAndRemoveInstance(sourceDb)
    }

    private fun buildProductionShapeZip(dbFile: File, outZip: File) {
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            zos.putNextEntry(ZipEntry("earthlink_reseller_db"))
            dbFile.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("backup_info.json"))
            // Literal shape taken from the user's field artifact
            // (earthlink_backup_20-oct-2026-412.zip): NONE encryption, empty passphrase.
            zos.write(
                """{"appName":"Earthlink Reseller","dbVersion":19,"encryptionMode":"NONE","dbPassphrase":""}"""
                    .toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()
        }
    }

    @Test
    fun restoreBackupZip_externalProductionShapeZip_restoresData() = runTest {
        // Claim: a zip created outside the app (Downloads file shape) restores fully.
        // Oracle: restore returns true and the independently-inserted account row
        //         re-reads from the live database with its original values.
        insertSourceAccount()
        val sourceDbFile = context.getDatabasePath(sourceDb)
        assertTrue("fixture: source database file must exist", sourceDbFile.exists())

        val outZip = File(context.cacheDir, "zz_roundtrip_restore.zip")
        buildProductionShapeZip(sourceDbFile, outZip)
        assertTrue(outZip.exists() && outZip.length() > 0L)

        val restored = BackupManager.restoreBackupZip(context, outZip, force = true)
        assertTrue("restore must report success for external production-shape zip", restored)

        val liveDb = AppDatabase.getDatabase(context, ByteArray(0), defaultDbName)
        val restoredAccount = liveDb.localAccountDao().getByIdOneShot("rt_account_1")
        assertNotNull("restored database must contain the source account", restoredAccount)
        assertEquals("Round Trip User", restoredAccount?.displayName)
        assertEquals("roundtrip_user", restoredAccount?.earthlinkUsername)
        assertEquals(SasProviders.EARTHLINK, restoredAccount?.operationProvider)
    }
}
