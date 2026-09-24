package com.example

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.core.backup.BackupManager
import com.example.core.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Permanent Regression Test for BUG-16:
 * Backup Export Reports Success When OutputStream Is Null.
 *
 * Invariant Protection:
 * - Claim: exportBackupToUri must fail-closed (return false) and clean up temporary
 *   artifacts when ContentResolver.openOutputStream returns null.
 * - Test A: Null OutputStream fails closed (returns false), writes 0 bytes, and cleans up temp zip.
 * - Test B: Valid OutputStream writes complete backup zip bytes and returns true.
 * - Test C: Import counterpart preserves established negative twin (null InputStream returns false).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Bug16ForensicReproductionTest {

    private lateinit var app: EarthlinkApp
    private val testUri: Uri = Uri.parse("content://com.example.provider/backup.zip")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.isSafeDebugFallbackAllowedOverride = true
        AppDatabase.closeDatabase()
        app.getDatabasePath("earthlink_reseller_db").parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        AppDatabase.closeDatabase()
    }

    // =====================================================================
    // TEST A — NULL OUTPUTSTREAM FAILS CLOSED (BUG-16 Fix)
    // =====================================================================
    @Test
    fun testA_exportBackupToUri_whenOpenOutputStreamReturnsNull_failsClosedAndCleansUpTempZip() = runBlocking {
        val backupsDir = File(app.cacheDir, "backups").apply { mkdirs() }
        val beforeFiles = backupsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

        val mockResolver = mock<ContentResolver>()
        whenever(mockResolver.openOutputStream(any())).thenReturn(null)

        val contextWrapper = object : ContextWrapper(app) {
            override fun getContentResolver(): ContentResolver = mockResolver
        }

        // Execute export
        val result = BackupManager.exportBackupToUri(contextWrapper, testUri)

        // Fixed behavior: Must fail closed and return false
        assertFalse(
            "exportBackupToUri must return false when openOutputStream returns null",
            result
        )

        // Temporary zip file must be cleaned up
        val afterFiles = backupsDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertEquals(
            "Temporary zip file must be deleted on null output stream failure",
            beforeFiles,
            afterFiles
        )
    }

    // =====================================================================
    // TEST B — SUCCESSFUL OUTPUTSTREAM COPIES BYTES AND RETURNS TRUE
    // =====================================================================
    @Test
    fun testB_exportBackupToUri_whenOpenOutputStreamSucceeds_copiesBytesAndReturnsTrue() = runBlocking {
        val capturedBytes = ByteArrayOutputStream()
        val mockResolver = mock<ContentResolver>()
        whenever(mockResolver.openOutputStream(any())).thenReturn(capturedBytes)

        val contextWrapper = object : ContextWrapper(app) {
            override fun getContentResolver(): ContentResolver = mockResolver
        }

        val result = BackupManager.exportBackupToUri(contextWrapper, testUri)

        assertTrue("Export must succeed when OutputStream is valid", result)
        assertTrue("Exported bytes must not be empty", capturedBytes.size() > 0)
        val header = capturedBytes.toByteArray().take(2)
        assertEquals("Exported bytes must have valid ZIP magic 'P'", 0x50.toByte(), header[0])
        assertEquals("Exported bytes must have valid ZIP magic 'K'", 0x4B.toByte(), header[1])
    }

    // =====================================================================
    // TEST C — EXISTING IMPORT NEGATIVE TWIN
    // =====================================================================
    @Test
    fun testC_importBackupFromUri_whenOpenInputStreamReturnsNull_correctlyReturnsFalse() = runBlocking {
        val mockResolver = mock<ContentResolver>()
        whenever(mockResolver.openInputStream(any())).thenReturn(null)

        val contextWrapper = object : ContextWrapper(app) {
            override fun getContentResolver(): ContentResolver = mockResolver
        }

        val result = BackupManager.importBackupFromUri(contextWrapper, testUri)

        assertFalse(
            "Negative Twin: importBackupFromUri correctly returns false when InputStream is null",
            result
        )
    }
}
