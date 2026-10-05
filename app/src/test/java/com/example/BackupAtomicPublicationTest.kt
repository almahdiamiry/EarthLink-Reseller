package com.example

import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.example.core.backup.BackupManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Regression for confirmed bug C1 — "publish backups atomically".
 *
 * WHAT: Before the fix, `createDailyRollingBackup` streamed the completed cache archive
 *   directly onto its final restore-visible name with `copyTo(finalZipFile, overwrite = true)`.
 *   A process death during that streaming copy left a structurally invalid zip under the
 *   final name, which `listDailyBackups()` then returned as the NEWEST candidate and which
 *   consumed a rolling-retention slot, evicting valid older backups.
 *
 * SEAM / ENVIRONMENT: JVM tier (androidx.test ApplicationProvider context; no emulator needed
 *   because the defect and the fix are pure filesystem publication semantics).
 *
 * INDEPENDENT ORACLE: the failure is injected through the copier seam so the mid-transfer
 *   abort is deterministic — no SIGKILL, no timing window, no probabilistic retry.
 *   Expectations are filesystem facts (which names exist), not production-code echoes:
 *   "an incomplete archive must exist under no `.zip` name" is derived from the invariant,
 *   not from reading `publishBackupAtomically`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BackupAtomicPublicationTest {

    private lateinit var backupsDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        backupsDir = BackupManager.getBackupsDirectory(ctx)
        backupsDir.listFiles()?.forEach { it.delete() }
    }

private fun goodArchive(name: String): File {
        val f = File.createTempFile(name, ".zip", backupsDir)
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("earthlink_reseller.db"))
            z.write(ByteArray(2048) { 0x41 })
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("backup_info.json"))
            z.write("""{"dbVersion":18}""".toByteArray())
            z.closeEntry()
        }
        return f
    }

    @Test
    fun inducedPublicationFailure_leavesNoAdvertisedArchive() {
        val previousGood = goodArchive("backup_2026-01-01_00-00.zip")
        assertTrue("fixture: a valid archive exists before the failure", previousGood.exists())
        ZipFile(previousGood).use { assertEquals(2, it.size()) }

        val source = goodArchive("earthlink_backup_src.zip")
        val destination = File(backupsDir, "backup_2026-01-02_00-00.zip")

        // Deterministic mid-transfer failure: some bytes are written, then the copy aborts.
        val failure = runCatching {
            BackupManager.publishBackupAtomically(source, destination) { _, part ->
                part.outputStream().use { it.write(ByteArray(512) { 0x42 }) }
                throw IOException("injected mid-transfer failure")
            }
        }.exceptionOrNull()

        assertTrue("publication must surface the failure to the caller", failure is IOException)

        // THE C1 INVARIANT: no incomplete archive under the final name.
        assertFalse("no final .zip may exist after an incomplete publication", destination.exists())

        // The staged file must not survive cleanup.
        assertFalse(
            "no .part may be left behind",
            File(backupsDir, "${destination.name}.part").exists()
        )

        // The previously valid backup is untouched and still discoverable.
        assertTrue("previous valid backup must remain available", previousGood.exists())
    }

    @Test
    fun successfulPublication_producesValidArchiveAndNoPartFile() {
        val source = goodArchive("earthlink_backup_src_ok.zip")
        val destination = File(backupsDir, "backup_2026-01-03_00-00.zip")

        BackupManager.publishBackupAtomically(source, destination)

        assertTrue("final archive must exist after a complete publication", destination.exists())
        assertTrue("final archive must keep the .zip name", destination.name.endsWith(".zip"))
        assertFalse(
            "no .part may remain after a successful publication",
            File(backupsDir, "${destination.name}.part").exists()
        )

        // Content must be preserved byte-for-byte from the completed cache archive.
        assertTrue(
            "published archive must be byte-identical to the completed source archive",
            source.readBytes().contentEquals(destination.readBytes())
        )

        ZipFile(destination).use { z ->
            val actual = mutableListOf<String>()
            val e = z.entries()
            while (e.hasMoreElements()) {
                actual += e.nextElement().name
            }
            assertTrue(
                "archive must contain the database entry; actual entries = $actual",
                actual.contains("earthlink_reseller.db")
            )
            assertTrue(
                "archive must contain backup_info.json; actual entries = $actual",
                actual.contains("backup_info.json")
            )
        }
    }

    @Test
    fun failedPublication_isNotReturnedByListDailyBackups() = runTest {
        val previousGood = goodArchive("backup_2026-02-01_00-00.zip")
        val source = goodArchive("earthlink_backup_src_list.zip")
        val destination = File(backupsDir, "backup_2026-02-02_00-00.zip")

        runCatching {
            BackupManager.publishBackupAtomically(source, destination) { _, part ->
                part.outputStream().use { it.write(ByteArray(256)) }
                throw IOException("injected mid-transfer failure")
            }
        }

        val listed = BackupManager.listDailyBackups(ApplicationProvider.getApplicationContext())
        val names = listed.map { it.name }

        assertFalse(
            "the failed publication must not be advertised as a backup candidate",
            names.contains(destination.name)
        )
        assertTrue(
            "the previous valid backup must still be discoverable",
            names.contains(previousGood.name)
        )
    }
}