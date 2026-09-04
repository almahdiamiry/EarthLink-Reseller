package com.example.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import com.example.core.database.AppDatabase
import com.example.domain.repository.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.kotlin.any as anyObject
import org.mockito.kotlin.eq as eqValue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Unit test verifying TGZ Replace-All import triggers USER_ACTION synchronization lifecycle.
 *
 * Verifies:
 * A. Successful TGZ import requests USER_ACTION sync exactly once.
 * B. Failed TGZ import does NOT request sync.
 * C. Replace-All pending reconciliation marker is set when shouldReplace=true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocalAccountsViewModelTgzSyncTriggerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var mockLocalRepo: LocalAccountRepository
    private lateinit var mockLedgerRepo: LocalLedgerRepository
    private lateinit var mockUtowerRepo: UtowerImportRepository
    private lateinit var mockAuditRepo: AuditRepository
    private lateinit var mockSyncRepo: SyncRepository
    private lateinit var viewModel: LocalAccountsViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
        AppDatabase.closeDatabase()
        db = (context as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, ByteArray(0), "earthlink_reseller_db")

        runBlocking {
            db.localLedgerEntryDao().deleteAll()
            db.localAccountDao().deleteAll()
            db.importBatchDao().deleteAll()
            db.syncOutboxDao().deleteAll()
            db.syncMetadataDao().deleteAll()
            db.auditLogDao().clearAll()
        }

        mockLocalRepo = mock(LocalAccountRepository::class.java)
        mockLedgerRepo = mock(LocalLedgerRepository::class.java)
        mockAuditRepo = mock(AuditRepository::class.java)
        mockSyncRepo = mock(SyncRepository::class.java)
        val realUtowerRepo = com.example.data.repository.UtowerImportRepositoryImpl(
            context = context,
            database = db,
            batchDao = db.importBatchDao(),
            accountDao = db.localAccountDao(),
            ledgerDao = db.localLedgerEntryDao(),
            outboxDao = db.syncOutboxDao(),
            auditRepo = mockAuditRepo
        )
        mockUtowerRepo = spy(realUtowerRepo)

        `when`(mockUtowerRepo.getImportBatches()).thenReturn(flowOf(emptyList()))

        viewModel = LocalAccountsViewModel(
            localRepo = mockLocalRepo,
            ledgerRepo = mockLedgerRepo,
            utowerRepo = mockUtowerRepo,
            audit = mockAuditRepo,
            syncRepo = mockSyncRepo,
            appDatabase = db
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writeTarEntry(out: OutputStream, name: String, content: ByteArray) {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
        System.arraycopy("0000644\u0000".toByteArray(Charsets.US_ASCII), 0, header, 100, 8)
        System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 108, 8)
        System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 116, 8)
        val sizeOctal = String.format("%011o ", content.size)
        System.arraycopy(sizeOctal.toByteArray(Charsets.US_ASCII), 0, header, 124, 12)
        val mtimeOctal = String.format("%011o ", System.currentTimeMillis() / 1000)
        System.arraycopy(mtimeOctal.toByteArray(Charsets.US_ASCII), 0, header, 136, 12)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = '0'.code.toByte()
        System.arraycopy("ustar\u000000".toByteArray(Charsets.US_ASCII), 0, header, 257, 8)
        var sum = 0
        for (b in header) {
            sum += (b.toInt() and 0xFF)
        }
        val checksumOctal = String.format("%06o\u0000 ", sum)
        System.arraycopy(checksumOctal.toByteArray(Charsets.US_ASCII), 0, header, 148, 8)

        out.write(header)
        out.write(content)
        val remainder = content.size % 512
        if (remainder != 0) {
            out.write(ByteArray(512 - remainder))
        }
    }

    private fun createSampleTgzFile(fileName: String, jsonContent: String): File {
        val tgzFile = File(context.cacheDir, fileName)
        FileOutputStream(tgzFile).use { fos ->
            GZIPOutputStream(fos).use { gzos ->
                val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                writeTarEntry(gzos, "utower_backup.json", bytes)
                // Write 1024 zero bytes trailer for valid tar
                gzos.write(ByteArray(1024))
            }
        }
        return tgzFile
    }

    private fun createSampleJsonDatabase(): String {
        val root = JSONObject()
        val subsArray = JSONArray()

        val sub1 = JSONObject().apply {
            put("source_key", "sub_user_001")
            put("raw", JSONObject().apply {
                put("name", "Test Subscriber 1")
                put("phone", "07700000001")
                put("debt", 25000.0)
            })
        }
        subsArray.put(sub1)
        root.put("subscribers", subsArray)
        return root.toString()
    }

    @Test
    fun testSuccessfulTgzImport_triggersUserActionSyncExactlyOnce_andSetsReplaceAllMarker() {
        runBlocking {
            val json = createSampleJsonDatabase()
            val tgzFile = createSampleTgzFile("valid_backup.tgz", json)
            val uri = Uri.fromFile(tgzFile)

            viewModel.importTgzFile(uri, context, shouldReplace = true)

            // Wait for importResult to be populated
            var attempts = 0
            while (viewModel.importResult.value == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            val result = viewModel.importResult.value
            assertNotNull("Import result should not be null", result)
            assertTrue("Import must be successful: ${result?.errorMessage}", result!!.success)
            assertEquals(1, result.subscribersImported)

            // Verify utowerRepo.importFromFile was invoked on the injected repository seam
            verify(mockUtowerRepo, times(1)).importFromFile(anyObject(), eqValue(true))

            // A. Verify syncRepo.requestSync(USER_ACTION) was invoked exactly once
            verify(mockSyncRepo, times(1)).requestSync(SyncReason.USER_ACTION)
            verifyNoMoreInteractions(mockSyncRepo)

            // C. Verify replace_all_pending_reconciliation marker is set in Room
            val marker = db.syncMetadataDao().get("replace_all_pending_reconciliation")
            assertEquals("true", marker)

            tgzFile.delete()
        }
    }

    @Test
    fun testFailedTgzImport_corruptArchive_doesNotTriggerSync() {
        runBlocking {
            // Create an invalid corrupt TGZ file
            val corruptFile = File(context.cacheDir, "corrupt.tgz")
            corruptFile.writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 0x00, 0x00, 0x12, 0x34))
            val uri = Uri.fromFile(corruptFile)

            viewModel.importTgzFile(uri, context, shouldReplace = true)

            var attempts = 0
            while (viewModel.importResult.value == null && viewModel.error.value == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            // B. Verify sync was NEVER requested
            verifyNoInteractions(mockSyncRepo)

            corruptFile.delete()
        }
    }

    @Test
    fun testInvalidUri_doesNotTriggerSync() {
        runBlocking {
            val nonExistentUri = Uri.parse("file:///non_existent_path/fake.tgz")

            viewModel.importTgzFile(nonExistentUri, context, shouldReplace = true)

            var attempts = 0
            while (viewModel.error.value == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            assertNotNull("Error should be reported", viewModel.error.value)
            verifyNoInteractions(mockSyncRepo)
        }
    }
}
