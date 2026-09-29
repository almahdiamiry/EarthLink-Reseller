package com.example.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.data.repository.LocalAccountRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.SyncRepository
import org.mockito.Mockito
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED regression test for BUG-IMP-3.
 *
 * Claim: a failed import attempt must not leave the previous attempt's success card on
 * screen. LocalAccountsViewModel is Activity-scoped (MainActivity creates it above the
 * NavHost) and ImportUtowerScreen renders the result card and the error banner as sibling
 * `if` blocks in the same Column, so a stale green "Import Completed" card renders
 * simultaneously with a red failure.
 *
 * This is the same class as the fixed EarthlinkSearchViewModel stale-success banner, where
 * the reset guard was already present on four other destinations.
 *
 * Seam / Environment: ROBOLECTRIC tier, real LocalAccountsViewModel over an in-memory DB.
 *
 * Independent Oracle: after a FAILED attempt, the screen must not assert that an import
 * completed. That assertion is only true of the earlier successful run. Reading it straight
 * off the ViewModel's StateFlow avoids depending on any internal flag.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugImp03StaleImportSuccessCardOnFailedRetryTest {

    private lateinit var db: AppDatabase
    private lateinit var vm: LocalAccountsViewModel
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = LocalAccountRepositoryImpl(db, db.localAccountDao(), db.syncOutboxDao())
        val importRepo = com.example.data.repository.UtowerImportRepositoryImpl(
            context, db, db.importBatchDao(), db.localAccountDao(), db.localLedgerEntryDao(), db.syncOutboxDao()
        )
        vm = LocalAccountsViewModel(
            localRepo = repo,
            ledgerRepo = com.example.data.repository.LocalLedgerRepositoryImpl(
                db, db.localLedgerEntryDao(), db.localAccountDao(), db.syncOutboxDao(), db.pendingExternalOperationDao()
            ),
            utowerRepo = importRepo,
            audit = Mockito.mock(AuditRepository::class.java),
            syncRepo = Mockito.mock(SyncRepository::class.java),
            appDatabase = db,
            prefs = com.example.core.security.PreferenceManager(context)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun failedAttempt_doesNotLeavePreviousSuccessCardVisible() {
        // A first import that genuinely succeeds, so a real success card exists.
        val good = java.io.File(context.cacheDir, "good.tgz")
        writeTgz(good, """{"subscribers":[{"raw":{"name":"A","username":"a","phone":"07700000001","debt":0,"currentPrice":25000}}],"transactions":[]}""")
        val goodUri = Uri.fromFile(good)
        val scope = kotlinx.coroutines.MainScope()
        scope.launch {
            vm.importTgzFile(goodUri, context, shouldReplace = false)
            while (vm.isLoading.value) { kotlinx.coroutines.delay(10) }
        }
        Thread.sleep(2500)

        val first = vm.importResult.value
        val diag1 = "afterSuccess result=$first error=${vm.error.value} loading=${vm.isLoading.value}"
        println(diag1)
        println("DIAG openInputStream(goodUri) = " + runCatching { context.contentResolver.openInputStream(goodUri) })
        assertNotNull(
            "Precondition: the first import must have produced a success result so a stale card " +
                "is observable.\n$diag1",
            first
        )
        assertEquals(true, first!!.success)

        // A second attempt whose stream cannot be opened at all. This hits the early-return in
        // LocalAccountsViewModel where _error is set but the result flow is never assigned, so
        // the previous run's success card would survive untouched.
        val missingUri = Uri.fromFile(java.io.File(context.cacheDir, "does_not_exist.tgz"))
        scope.launch {
            vm.importTgzFile(missingUri, context, shouldReplace = false)
            while (vm.isLoading.value) { kotlinx.coroutines.delay(10) }
        }
        Thread.sleep(2500)

        val diag2 = "afterFailure error=${vm.error.value} result=${vm.importResult.value} loading=${vm.isLoading.value}"
        println(diag2)

        assertNotNull(
            "Precondition: the second attempt must have failed.\n$diag2",
            vm.error.value
        )
        assertNull(
            "The attempt FAILED, but the previous run's green \"Import Completed\" card is still " +
                "on screen next to this attempt's error banner. ImportUtowerScreen renders both as " +
                "sibling `if` blocks in the same Column, so they appear simultaneously and the " +
                "operator sees a completed import alongside a failure.\n$diag2",
            vm.importResult.value
        )
    }

    /** Writes a minimal but valid USTAR tgz containing the given JSON payload. */
    private fun writeTgz(target: java.io.File, json: String) {
        val content = json.toByteArray(Charsets.UTF_8)
        java.io.FileOutputStream(target).use { fos ->
            java.util.zip.GZIPOutputStream(fos).use { gz ->
                val header = ByteArray(512)
                val n = "utower_backup.json".toByteArray(Charsets.US_ASCII)
                System.arraycopy(n, 0, header, 0, n.size)
                System.arraycopy("0000644\u0000".toByteArray(Charsets.US_ASCII), 0, header, 100, 8)
                System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 108, 8)
                System.arraycopy("0000000\u0000".toByteArray(Charsets.US_ASCII), 0, header, 116, 8)
                System.arraycopy(
                    String.format("%011o ", content.size).toByteArray(Charsets.US_ASCII), 0, header, 124, 12
                )
                System.arraycopy(
                    String.format("%011o ", 0).toByteArray(Charsets.US_ASCII), 0, header, 136, 12
                )
                for (i in 148 until 156) header[i] = ' '.code.toByte()
                header[156] = '0'.code.toByte()
                var sum = 0
                for (b in header) sum += (b.toInt() and 0xFF)
                System.arraycopy(
                    String.format("%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII), 0, header, 148, 8
                )
                gz.write(header)
                gz.write(content)
                val rem = content.size % 512
                if (rem != 0) gz.write(ByteArray(512 - rem))
                gz.write(ByteArray(1024))
            }
        }
    }
}
