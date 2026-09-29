package com.example.core.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * RED regression test for BUG-BK-2.
 *
 * Claim: the pre-restore safety backup must remain recoverable, per Target Product
 * Contract v0.6 TQ-12:
 *   - "easy recovery to the pre-restore backup" (required property)
 *   - "Restore Replace cannot silently destroy the pre-restore dataset" (item 7)
 *
 * The retention prune selects every "*.zip" in the shared directory with no notion of
 * artifact class, so safety snapshots compete for the same 30-file quota and are evicted
 * by age like any rolling backup.
 *
 * Seam / Environment: ROBOLECTRIC tier, real filesystem retention logic.
 *
 * Independent Oracle: the quota is a policy for ROLLING backups. A pre-restore snapshot is
 * the operator's only recovery path to the dataset that a Replace just overwrote, so it must
 * not be swept by that quota. The contract requires recovery to remain easy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugBk02PreRestoreBackupEvictedByRetentionTest {

    @Test
    fun preRestoreSafetyBackup_isNeverEvictedByTheRollingRetentionQuota() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<com.example.EarthlinkApp>()
        app.isSafeDebugFallbackAllowedOverride = true
        val db = app.database

        // A real database with real content, so the production backup path actually runs.
        db.localAccountDao().upsert(
            com.example.core.model.LocalAccount(
                id = "acc_bk02",
                sourceExternalId = "ext_bk02",
                earthlinkUsername = "bk02_user",
                displayName = "BK02",
                debtIqd = 1000.0,
                createdAt = 1_700_000_000_000L
            )
        )
        db.localLedgerEntryDao().insert(
            com.example.core.model.LocalLedgerEntry(
                id = "led_bk02",
                accountId = "acc_bk02",
                typeRaw = "renewal",
                amountIqd = 1000.0,
                debtAfterIqd = 1000.0,
                createdAt = 1_700_000_000_000L
            )
        )

        // The REAL production directory and REAL prune selection, so this exercises
        // the shipped retention logic rather than a re-implementation of it.
        val dir = BackupManager.getBackupsDirectory(app)
        dir.listFiles()?.forEach { it.delete() }

        // One pre-restore safety snapshot, strictly the OLDEST artifact, so it is the
        // first candidate the age-ordered prune would evict.
        val safety = File(dir, "pre_restore_backup_2020-01-01_00-00.zip")
        safety.writeBytes(byteArrayOf(1, 2, 3))
        safety.setLastModified(900_000_000_000L)

        // Then fill the quota with ordinary rolling backups. 40 distinct names so the
        // directory starts well above the 30-file quota and the prune must actually fire.
        for (i in 0 until 40) {
            val f = File(dir, "backup_2026-%02d-%02d_00-00.zip".format(i / 20, i % 20))
            f.writeBytes(byteArrayOf(1))
            f.setLastModified(1_000_000_000_000L + i * 60_000L)
        }

        val beforeCount = dir.listFiles()?.count { it.name.endsWith(".zip") } ?: 0

        // Drive the real rolling backup so the production prune actually runs.
        val created = BackupManager.createDailyRollingBackup(app, password = null)
        val afterCount = dir.listFiles()?.count { it.name.endsWith(".zip") } ?: 0

        // Guard against passing for the wrong reason: the prune must actually have run
        // (the quota must have shrunk). If the rolling backup failed before the prune,
        // this test would vacuously "pass" while proving nothing.
        org.junit.Assert.assertTrue(
            "Precondition: the rolling backup and its retention prune must actually run, " +
                "otherwise this test proves nothing. created=$created before=$beforeCount " +
                "after=$afterCount",
            created != null && afterCount < beforeCount
        )

        val survives = safety.exists()
        val diag = "created=${created != null} zipsBefore=$beforeCount zipsAfter=$afterCount " +
            "preRestoreSurvives=$survives"

        dir.listFiles()?.forEach { it.delete() }

        assertTrue(
            "The pre-restore safety backup was deleted by the rolling retention quota.\n$diag\n" +
                "TQ-12 requires 'easy recovery to the pre-restore backup' and that Replace " +
                "'cannot silently destroy the pre-restore dataset'. Once evicted, the dataset " +
                "the operator would restore back to is unrecoverable.",
            survives
        )
    }
}
