package com.example.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import com.example.core.model.LocalLedgerEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

/**
 * RED regression test for BUG-IMP-1.
 *
 * Claim: an import that matches the recognized container but yields ZERO subscribers must
 * never be reported as a successful replacement, and must never destroy the previous
 * dataset. Target Product Contract v0.6 TQ-12 forbids a silent partial replacement and
 * TQ-11 forbids silently destroying the pre-restore dataset.
 *
 * The operator DID authorize a wipe by confirming "Wipe & Replace", so deleting is allowed.
 * The defect is that the outcome is reported as SUCCESS with every counter at zero and no
 * warning, which makes an irreversible, unrecoverable loss look like a completed import.
 *
 * Seam / Environment: ROBOLECTRIC tier, in-memory Room SQLite, real UtowerImporter.
 *
 * Independent Oracle: a container the importer can open (so its own "could not find uTower
 * database" guard does NOT fire) but which contains no subscriber records must not report
 * success. Success may only be reported when the import actually produced something.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugImp01ReplaceImportMustNotReportSuccessOnEmptyPayloadTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A structurally valid .tgz the importer can open, containing no uTower subscriber data. */
    private fun openableTgzWithNoSubscribers(name: String): File {
        val tgz = File(context.cacheDir, name)
        FileOutputStream(tgz).use { fos ->
            GZIPOutputStream(fos).use { gz ->
                val content = """{"totally":"unrelated"}""".toByteArray(Charsets.UTF_8)
                val header = ByteArray(512)
                val n = "import_probe.json".toByteArray(Charsets.US_ASCII)
                System.arraycopy(n, 0, header, 0, n.size)
                System.arraycopy("0000644\u0000".toByteArray(Charsets.US_ASCII), 0, header, 100, 8)
                System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 108, 8)
                System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 116, 8)
                val size = String.format("%011o ", content.size)
                System.arraycopy(size.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
                val mtime = String.format("%011o ", 0)
                System.arraycopy(mtime.toByteArray(Charsets.US_ASCII), 0, header, 136, 12)
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                header[156] = '0'.code.toByte()
                var sum = 0
                for (b in header) sum += (b.toInt() and 0xFF)
                val cks = String.format("%06o\u0000 ", sum)
                System.arraycopy(cks.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)
                gz.write(header)
                gz.write(content)
                val rem = content.size % 512
                if (rem != 0) gz.write(ByteArray(512 - rem))
                gz.write(ByteArray(1024))
            }
        }
        return tgz
    }

    @Test
    fun replaceImport_withNoUscribers_doesNotReportSuccess() = runBlocking {
        // Seed a real dataset that a replace-import would wipe.
        db.localAccountDao().upsert(
            LocalAccount(
                id = "acc_keep_me", sourceExternalId = "ext_keep", earthlinkUsername = "keep_me",
                displayName = "Precious History", debtIqd = 50000.0, createdAt = System.currentTimeMillis()
            )
        )
        db.localLedgerEntryDao().insertAll(
            listOf(
                LocalLedgerEntry(
                    id = "led_keep_1", accountId = "acc_keep_me", typeRaw = "renewal",
                    amountIqd = 50000.0, debtAfterIqd = 50000.0, createdAt = System.currentTimeMillis()
                )
            )
        )

        val before = db.localAccountDao().getAllPersistedOneShot(limit = Int.MAX_VALUE).size
        assertEquals("Precondition: one account must exist before the import", 1, before)

        val importer = UtowerImporter(context, db)
        val result = importer.importFromFile(openableTgzWithNoSubscribers("probe.tgz"), shouldReplace = true)

        val after = db.localAccountDao().getAllPersistedOneShot(limit = Int.MAX_VALUE).size
        val ledgers = db.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE).size
        val diag = "success=${result.success} found=${result.subscribersFound} " +
            "imported=${result.subscribersImported} accountsAfter=$after ledgersAfter=$ledgers " +
            "error=${result.errorMessage}"

        assertTrue(
            "The container was openable and the operator authorized a replace, so the WIPE itself " +
                "is permitted. The defect is that an import producing ZERO subscribers is reported " +
                "as SUCCESS with every counter at zero, which presents an irreversible, " +
                "unrecoverable loss as a completed import. The batch is stamped 'completed', " +
                "which also hides the rollback button and makes rollbackImportBatch reject it.\n$diag",
            !(result.success && result.subscribersFound == 0 && result.subscribersImported == 0)
        )
    }
}
