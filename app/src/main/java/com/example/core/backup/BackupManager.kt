package com.example.core.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.EarthlinkApp
import com.example.core.database.AppDatabase
import com.example.core.sync.DataMaintenanceLock
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    private const val TAG = "BackupManager"
    private const val DB_NAME = "earthlink_reseller_db"

    suspend fun createLocalBackupZip(context: Context): File = withContext(Dispatchers.IO) {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.BACKUP) {
            createLocalBackupZipInternal(context)
        }
    }

    private suspend fun createLocalBackupZipInternal(context: Context): File = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? EarthlinkApp
        val passphraseStr = app?.preferenceManager?.getDatabasePassphrase() ?: ""
        val passphrase = passphraseStr.toByteArray(Charsets.UTF_8)

        val backupDir = File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val zipFile = File(backupDir, "earthlink_backup_$timeStamp.zip")

        val isRobolectric = try {
            Class.forName("org.robolectric.Robolectric")
            true
        } catch (_: Throwable) {
            false
        }

        // D-1 Fix: Use VACUUM INTO to create an atomic, uncorrupted snapshot of the live WAL database
        val tempVacuumDbFile = if (isRobolectric) {
            File("temp_vacuum_$timeStamp.db")
        } else {
            File(context.cacheDir, "temp_vacuum_$timeStamp.db")
        }
        tempVacuumDbFile.parentFile?.mkdirs()
        if (tempVacuumDbFile.exists()) tempVacuumDbFile.delete()

        var vacuumSuccess = false
        try {
            val db = try {
                (context.applicationContext as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, passphrase)
            } catch (_: Throwable) {
                AppDatabase.getDatabase(context, ByteArray(0))
            }
            val sqliteDb = db.openHelper.writableDatabase
            val vacuumPath = if (isRobolectric) "temp_vacuum_$timeStamp.db" else tempVacuumDbFile.absolutePath.replace('\\', '/')
            sqliteDb.execSQL("VACUUM INTO '$vacuumPath'")
            vacuumSuccess = tempVacuumDbFile.exists() && tempVacuumDbFile.length() > 0
            Log.i(TAG, "VACUUM INTO succeeded, created atomic DB copy (${tempVacuumDbFile.length()} bytes)")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "VACUUM INTO failed, falling back to checkpoint file copy: ${e.message}")
        }

        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                if (vacuumSuccess) {
                    addFileToZip(zos, tempVacuumDbFile, DB_NAME)
                } else {
                    // Fallback: Force WAL Checkpoint to flush memory/journal data to disk
                    try {
                        val db = (context.applicationContext as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, passphrase)
                        val sqliteDb = db.openHelper.writableDatabase
                        sqliteDb.query("PRAGMA wal_checkpoint(TRUNCATE);")?.use { cursor ->
                            cursor.moveToFirst()
                        }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e(TAG, "Failed to run WAL checkpoint before backup", e)
                    }

                    val dbFile = context.getDatabasePath(DB_NAME)
                    val walFile = File(dbFile.path + "-wal")
                    val shmFile = File(dbFile.path + "-shm")

                    if (dbFile.exists()) {
                        addFileToZip(zos, dbFile, DB_NAME)
                        if (walFile.exists()) addFileToZip(zos, walFile, "$DB_NAME-wal")
                        if (shmFile.exists()) addFileToZip(zos, shmFile, "$DB_NAME-shm")
                    } else {
                        // In-memory database fallback (e.g. Robolectric / Unit Test environment)
                        val tempDiskDbName = "temp_vacuum_$timeStamp.db"
                        val diskDbFile = context.getDatabasePath(tempDiskDbName)
                        diskDbFile.parentFile?.mkdirs()
                        if (diskDbFile.exists()) diskDbFile.delete()
                        try {
                            val diskDb = AppDatabase.getDatabase(context, ByteArray(0), tempDiskDbName)
                            val liveDb = (context.applicationContext as? EarthlinkApp)?.database ?: AppDatabase.getDatabase(context, passphrase)
                            
                            val accList = liveDb.localAccountDao().getAllOneShot(limit = 100000, offset = 0)
                            if (accList.isNotEmpty()) diskDb.localAccountDao().insertAll(accList)
                            val ledgerList = liveDb.localLedgerEntryDao().getAllOneShot(limit = 100000, offset = 0)
                            if (ledgerList.isNotEmpty()) diskDb.localLedgerEntryDao().insertAll(ledgerList)
                            for (b in liveDb.importBatchDao().getAllOneShot()) {
                                diskDb.importBatchDao().insert(b)
                            }
                            for (s in liveDb.syncMetadataDao().getAllOneShot()) {
                                diskDb.syncMetadataDao().put(s.key, s.value, s.updatedAt)
                            }
                            for (a in liveDb.auditLogDao().getAllSync()) {
                                diskDb.auditLogDao().insert(a)
                            }
                            diskDb.close()
                            AppDatabase.closeAndRemoveInstance(tempDiskDbName)
                            if (diskDbFile.exists() && diskDbFile.length() > 0L) {
                                addFileToZip(zos, diskDbFile, DB_NAME)
                            }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e(TAG, "Failed to export in-memory database to disk backup: ${e.message}", e)
                        } finally {
                            if (diskDbFile.exists()) diskDbFile.delete()
                            File(diskDbFile.path + "-wal").delete()
                            File(diskDbFile.path + "-shm").delete()
                        }
                    }
                }

                // Add metadata JSON with database encryption passphrase
                val metadata = JSONObject().apply {
                    put("appName", "Earthlink Reseller")
                    put("dbVersion", AppDatabase.VERSION)
                    put("createdAt", System.currentTimeMillis())
                    put("formattedDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    val firebaseUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    val uidKeySeed = firebaseUid ?: (app?.preferenceManager?.getDeviceId() ?: "default_device_id")
                    
                    val salt = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
                    val iv = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }

                    val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    val spec = javax.crypto.spec.PBEKeySpec(uidKeySeed.toCharArray(), salt, 10000, 256)
                    val secretKey = factory.generateSecret(spec)
                    val keySpec = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

                    val encryptedPassBytes = cipher.doFinal(passphraseStr.toByteArray(Charsets.UTF_8))
                    val encryptedPass = android.util.Base64.encodeToString(encryptedPassBytes, android.util.Base64.NO_WRAP)
                    
                    put("encVersion", 2)
                    put("dbPassphrase", encryptedPass)
                    put("iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                    put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                }
                val metadataBytes = metadata.toString().toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("backup_info.json"))
                zos.write(metadataBytes)
                zos.closeEntry()
            }
        } finally {
            if (tempVacuumDbFile.exists()) tempVacuumDbFile.delete()
        }

        Log.i(TAG, "Backup ZIP created successfully at: ${zipFile.absolutePath} (${zipFile.length()} bytes)")
        (context.applicationContext as? EarthlinkApp)?.auditRepository?.log(
            severity = com.example.core.model.AuditSeverity.INFO,
            action = "BACKUP_SUCCESS",
            message = "Backup ZIP created successfully at: ${zipFile.absolutePath} (${zipFile.length()} bytes)"
        )
        return@withContext zipFile
    }

    fun getBackupsDirectory(context: Context): File {
        val baseDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "Documents")
        return File(baseDir, "EarthlinkBackups").apply { if (!exists()) mkdirs() }
    }

    suspend fun createDailyRollingBackup(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val dailyBackupsDir = getBackupsDirectory(context)
            val tempZip = createLocalBackupZip(context)
            
            val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val finalZipFile = File(dailyBackupsDir, "backup_$timeStamp.zip")
            
            // Move temp zip to daily directory
            tempZip.copyTo(finalZipFile, overwrite = true)
            tempZip.delete()
            
            // Delete old backups if more than 30
            val files = dailyBackupsDir.listFiles()?.filter { it.name.endsWith(".zip") }?.sortedBy { it.lastModified() }
            if (files != null && files.size > 30) {
                val filesToDelete = files.take(files.size - 30)
                for (f in filesToDelete) {
                    f.delete()
                    Log.i(TAG, "Deleted old daily backup: ${f.name}")
                }
            }
            return@withContext finalZipFile
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e(TAG, "Failed to create daily rolling backup", e)
            return@withContext null
        }
    }

    suspend fun listDailyBackups(context: Context): List<File> = withContext(Dispatchers.IO) {
        val dailyBackupsDir = getBackupsDirectory(context)
        if (!dailyBackupsDir.exists()) return@withContext emptyList()
        return@withContext dailyBackupsDir.listFiles()?.filter { it.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    data class DatabaseStats(
        val accountCount: Int,
        val ledgerCount: Int
    )

    data class RestoreTransportSnapshot(
        val artifactPath: String,
        val artifactHash: String,
        val lineageSnapshotToken: String,
        val unresolvedObligations: List<com.example.core.model.SyncOutbox>
    )

    /**
     * Executes the Transport Reconstruction Decision Table (P1-G2-REQ-05 / INV-13).
     *
     * Rules:
     * 1. Historical outbox & cursor metadata from backup archive -> DISCARDED.
     * 2. Pre-restore unresolved obligations with valid targets in restored dataset -> RECONSTRUCTED with stable identity.
     * 3. Pre-restore unresolved obligations whose targets are absent in restored dataset -> CLASSIFIED AS ORPHANED (status='failed', lastError='ORPHAN: ...') and preserved durable.
     * 4. Restored snapshot business baseline -> Zero duplicate outbox storm.
     * 5. Sync metadata / remote cursors -> RESET to clean baseline.
     */
    suspend fun reconstructTransportState(
        liveDb: AppDatabase,
        unresolvedObligations: List<com.example.core.model.SyncOutbox>
    ) {
        val now = System.currentTimeMillis()
        for (ob in unresolvedObligations) {
            val targetExists = when (ob.entityType) {
                "local_accounts", "accounts" -> liveDb.localAccountDao().getByIdOneShot(ob.entityId) != null
                "local_ledger_entries", "ledger_entries" -> liveDb.localLedgerEntryDao().getByIdOneShot(ob.entityId) != null
                "import_batches", "batches" -> liveDb.importBatchDao().getById(ob.entityId) != null
                "audit_logs", "audit_log" -> liveDb.auditLogDao().getById(ob.entityId) != null
                else -> false
            }

            if (targetExists) {
                // Retain / Reconstruct current cloud obligation with stable identity
                val reconstructed = com.example.core.model.SyncOutbox(
                    entityType = ob.entityType,
                    entityId = ob.entityId,
                    operation = ob.operation,
                    payloadJson = ob.payloadJson,
                    status = if (ob.status == "syncing") "pending" else ob.status,
                    attemptCount = ob.attemptCount,
                    lastError = ob.lastError,
                    createdAt = ob.createdAt,
                    updatedAt = now,
                    importBatchId = ob.importBatchId
                )
                liveDb.syncOutboxDao().insert(reconstructed)
            } else {
                // Target absent / orphaned: retain as failed orphan with diagnostics (INV-13 / P1-G2-REQ-03)
                val orphaned = com.example.core.model.SyncOutbox(
                    entityType = ob.entityType,
                    entityId = ob.entityId,
                    operation = ob.operation,
                    payloadJson = ob.payloadJson,
                    status = "failed",
                    attemptCount = ob.attemptCount + 1,
                    lastError = "ORPHAN: Target entity ${ob.entityId} of type ${ob.entityType} absent in restored dataset [ORPHAN_TARGET_ENTITY_MISSING]",
                    createdAt = ob.createdAt,
                    updatedAt = now,
                    importBatchId = ob.importBatchId
                )
                liveDb.syncOutboxDao().insert(orphaned)
            }
        }
    }

    suspend fun getCurrentDatabaseStats(context: Context): DatabaseStats = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as? EarthlinkApp
            val passphraseStr = try { app?.preferenceManager?.getDatabasePassphrase() ?: "" } catch (_: Throwable) { "" }
            val passphrase = passphraseStr.toByteArray(Charsets.UTF_8)
            val db = try {
                app?.database ?: AppDatabase.getDatabase(context, passphrase)
            } catch (_: Throwable) {
                AppDatabase.getDatabase(context, ByteArray(0))
            }
            val accounts = db.localAccountDao().getTotalCount()
            val ledger = db.localLedgerEntryDao().getTotalCount()
            DatabaseStats(accountCount = accounts, ledgerCount = ledger)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e(TAG, "Failed to query database stats", e)
            DatabaseStats(accountCount = 0, ledgerCount = 0)
        }
    }

    suspend fun getBackupFormattedDate(backupFile: File): String = withContext(Dispatchers.IO) {
        try {
            if (backupFile.name.endsWith(".zip", ignoreCase = true)) {
                ZipInputStream(FileInputStream(backupFile)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "backup_info.json") {
                            val jsonStr = zis.bufferedReader(Charsets.UTF_8).readText()
                            val json = JSONObject(jsonStr)
                            if (json.has("formattedDate")) {
                                return@withContext json.getString("formattedDate")
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.w(TAG, "Failed to read formattedDate from backup ZIP", e)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return@withContext sdf.format(Date(backupFile.lastModified()))
    }

    suspend fun getPendingOutboxCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext as? EarthlinkApp
            val passphraseStr = try { app?.preferenceManager?.getDatabasePassphrase() ?: "" } catch (_: Throwable) { "" }
            val db = try {
                app?.database ?: AppDatabase.getDatabase(context, passphraseStr.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                AppDatabase.getDatabase(context, ByteArray(0))
            }
            db.syncOutboxDao().getAllUnsyncedCount()
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e(TAG, "Failed to check pending outbox count", e)
            0
        }
    }

    fun calculateFileHash(file: File): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            "unknown_hash"
        }
    }

    /**
     * Prepares a deterministic RestoreMergeDecision outside any Room write transaction (P2-G3-REQ-01 / P2-G3-REQ-03 / INV-11).
     * All parsing, checksum calculation, and candidate evaluation occur here without holding or modifying live database state.
     */
    suspend fun prepareRestoreMergeDecision(
        context: Context,
        backupFile: File,
        selectedBaselineId: String = "BACKUP_SNAPSHOT",
        selectedLineageScope: String = "COMPLETE_LINEAGE",
        conflictDecisions: Map<String, com.example.core.model.ConflictResolutionChoice> = emptyMap(),
        isApproved: Boolean = false
    ): com.example.core.model.RestoreMergeDecision = withContext(Dispatchers.IO) {
        val artifactHash = calculateFileHash(backupFile)
        val stats = getCurrentDatabaseStats(context)
        val summary = "Artifact: ${backupFile.name} (Hash: ${artifactHash.take(8)}), LiveAccounts: ${stats.accountCount}, LiveLedgers: ${stats.ledgerCount}"
        com.example.core.model.RestoreMergeDecision(
            artifactIdentity = artifactHash,
            selectedBaselineId = selectedBaselineId,
            selectedLineageScope = selectedLineageScope,
            conflictDecisions = conflictDecisions,
            targetDatasetSummary = summary,
            isApproved = isApproved
        )
    }

    suspend fun restoreWithDecision(
        context: Context,
        backupFile: File,
        decision: com.example.core.model.RestoreMergeDecision,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val artifactHash = calculateFileHash(backupFile)
        if (!decision.isValidFor(artifactHash, decision.selectedBaselineId)) {
            Log.e(TAG, "Restore aborted: RestoreMergeDecision is invalidated, unapproved, or mismatched artifact hash.")
            return@withContext false
        }
        restoreBackupZipInternal(context, backupFile, decision, force)
    }

    /**
     * Executes Restore Merge as a complete-lineage decision operation (P2-G3-REQ-01 / P2-G3-REQ-02 / INV-01 / INV-11).
     * Enforces complete lineage pairing, same-ID divergent payload conflict detection, and idempotent merge without double-counting.
     */
    suspend fun restoreMergeWithDecision(
        context: Context,
        backupFile: File,
        decision: com.example.core.model.RestoreMergeDecision,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val artifactHash = calculateFileHash(backupFile)
        if (!decision.isValidFor(artifactHash, decision.selectedBaselineId)) {
            Log.e(TAG, "Restore Merge aborted: RestoreMergeDecision is invalidated, unapproved, or mismatched artifact hash.")
            return@withContext false
        }
        restoreMergeBackupZipInternal(context, backupFile, decision, force)
    }

    /**
     * Programmatic / Database-level execution of Direct Atomic Room Restore Replace inside an existing active transaction (P2-G3-REQ-01 / P2-G3-REQ-05 / INV-11 / INV-13).
     * Wipes business tables and replaces them atomically with the snapshot dataset in paginated chunks.
     * Quarantines incomplete import batches, preserves live audit log trail, resets operational outbox/cursors, and reconstructs valid unresolved obligations.
     */
    suspend fun executeRestoreReplaceInternal(
        liveDb: AppDatabase,
        backupDb: AppDatabase,
        unresolvedObligations: List<com.example.core.model.SyncOutbox> = emptyList(),
        passphrase: String = ""
    ) {
        val nextGen = liveDb.syncMetadataDao().getGeneration() + 1L

        // Exact Snapshot Restore: clear business tables and replace with exact backup snapshot
        liveDb.localLedgerEntryDao().deleteAll()
        liveDb.localAccountDao().deleteAll()
        liveDb.importBatchDao().deleteAll()
        liveDb.syncOutboxDao().deleteAll()
        liveDb.pendingExternalOperationDao().deleteAll()
        liveDb.syncMetadataDao().deleteAll()
        liveDb.syncMetadataDao().setGeneration(nextGen)

        val batchSize = 500
        var accOffset = 0
        while (true) {
            val backupAccountsChunk = backupDb.localAccountDao().getAllOneShot(limit = batchSize, offset = accOffset)
            if (backupAccountsChunk.isEmpty()) break
            liveDb.localAccountDao().insertAll(backupAccountsChunk)
            accOffset += backupAccountsChunk.size
            if (backupAccountsChunk.size < batchSize) break
        }

        var ledgerOffset = 0
        while (true) {
            val backupLedgersChunk = backupDb.localLedgerEntryDao().getAllOneShot(limit = batchSize, offset = ledgerOffset)
            if (backupLedgersChunk.isEmpty()) break
            liveDb.localLedgerEntryDao().insertAll(backupLedgersChunk)
            ledgerOffset += backupLedgersChunk.size
            if (backupLedgersChunk.size < batchSize) break
        }

        val backupBatches = backupDb.importBatchDao().getAllOneShot()
        for (b in backupBatches) {
            val batchToRestore = if (b.status != "completed") {
                b.copy(
                    status = "failed",
                    warningsJson = ((b.warningsJson ?: "") + " [Quarantined on restore: incomplete backup state]").trim()
                )
            } else {
                b
            }
            liveDb.importBatchDao().insert(batchToRestore)
        }

        // Operational sync state: Reinitialized. Stale outbox and metadata from backup is NOT replayed to cloud.
        // Sync cursors in syncMetadataDao remain cleared so sync reconciles cleanly with remote.
        // Live audit logs are preserved (not cleared) and backup audits are appended so the restore audit trail is unbroken.
        val backupAudits = backupDb.auditLogDao().getAllSync()
        for (auditItem in backupAudits) {
            liveDb.auditLogDao().insert(auditItem)
        }

        // Execute the Transport Reconstruction Decision Table (P1-G2-REQ-05 / INV-13)
        reconstructTransportState(liveDb, unresolvedObligations)

        val now = System.currentTimeMillis()
        val salt = if (passphrase.isNotBlank()) passphrase else "default_test_salt"
        val rawString = "$now|INFO|DATABASE_RESTORE|Backup restored and verified successfully.|system|$salt"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sig = digest.digest(rawString.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        liveDb.auditLogDao().insert(
            com.example.core.model.AuditLog(
                action = "DATABASE_RESTORE",
                entityType = null,
                entityId = null,
                summary = "Backup restored and verified successfully.",
                createdAt = now,
                severity = "INFO",
                actor = "system",
                signature = sig,
                origin = com.example.core.model.AuditOrigin.RESTORE_EVENT.name
            )
        )

        Log.i(TAG, "Successfully restored exact database snapshot across all business tables and reconstructed transport state.")
    }

    /**
     * Programmatic / Database-level execution of Restore Merge inside an existing active transaction.
     * Evaluates complete-lineage pairing, same-ID deduplication, material divergence protection, and deterministic balance updates.
     */
    suspend fun executeRestoreMergeInternal(
        liveDb: AppDatabase,
        backupDb: AppDatabase,
        decision: com.example.core.model.RestoreMergeDecision
    ): com.example.core.model.RestoreMergeResult {
        val liveAccounts = liveDb.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
        val backupAccounts = backupDb.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)

        val liveLedgers = liveDb.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
        val backupLedgers = backupDb.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)

        val liveAccMap = liveAccounts.associateBy { it.id }
        val backupAccMap = backupAccounts.associateBy { it.id }

        val liveLedgerByAcc = liveLedgers.groupBy { it.accountId }
        val backupLedgerByAcc = backupLedgers.groupBy { it.accountId }

        val allAccountIds = (liveAccMap.keys + backupAccMap.keys).distinct()

        var accountsMergedCount = 0
        var ledgersMergedCount = 0
        var ledgersDeduplicatedCount = 0
        var conflictsResolvedCount = 0

        for (accId in allAccountIds) {
            val liveAcc = liveAccMap[accId]
            val backupAcc = backupAccMap[accId]

            val liveAccLedgers = liveLedgerByAcc[accId] ?: emptyList()
            val backupAccLedgers = backupLedgerByAcc[accId] ?: emptyList()

            if (liveAcc != null && backupAcc == null) {
                // Live only: retain live account and its ledger entries
                continue
            } else if (liveAcc == null && backupAcc != null) {
                // Backup only: insert backup account and all its ledger entries
                liveDb.localAccountDao().insert(backupAcc)
                if (backupAccLedgers.isNotEmpty()) {
                    liveDb.localLedgerEntryDao().insertAll(backupAccLedgers)
                    ledgersMergedCount += backupAccLedgers.size
                }
                accountsMergedCount++
            } else if (liveAcc != null && backupAcc != null) {
                // Account in both: evaluate baseline compatibility
                val isOpeningBaselineIdentical = kotlin.math.abs(liveAcc.openingDebtIqd - backupAcc.openingDebtIqd) < 0.001 &&
                        kotlin.math.abs(liveAcc.openingAdvanceIqd - backupAcc.openingAdvanceIqd) < 0.001 &&
                        kotlin.math.abs(liveAcc.openingLoanIqd - backupAcc.openingLoanIqd) < 0.001 &&
                        kotlin.math.abs(liveAcc.currentPriceIqd - backupAcc.currentPriceIqd) < 0.001 &&
                        (liveAcc.sourceExternalId == null || backupAcc.sourceExternalId == null || liveAcc.sourceExternalId == backupAcc.sourceExternalId) &&
                        (liveAcc.sourceBatchId == null || backupAcc.sourceBatchId == null || liveAcc.sourceBatchId == backupAcc.sourceBatchId) &&
                        (liveAcc.stateSource == null || backupAcc.stateSource == null || liveAcc.stateSource == backupAcc.stateSource)

                val isBaselineConflict = !isOpeningBaselineIdentical || (
                        liveAccLedgers.isEmpty() && backupAccLedgers.isEmpty() &&
                        (kotlin.math.abs(liveAcc.debtIqd - backupAcc.debtIqd) > 0.001 ||
                         kotlin.math.abs(liveAcc.advanceIqd - backupAcc.advanceIqd) > 0.001 ||
                         kotlin.math.abs(liveAcc.loanIqd - backupAcc.loanIqd) > 0.001)
                )

                if (isBaselineConflict) {
                    val accChoice = decision.conflictDecisions[accId]
                    val resolvedLineage = accChoice ?: when {
                        decision.selectedBaselineId == "LIVE" || decision.selectedBaselineId == "LIVE_SNAPSHOT" -> com.example.core.model.ConflictResolutionChoice.USE_LIVE
                        decision.selectedBaselineId == "BACKUP" || decision.selectedBaselineId == "BACKUP_SNAPSHOT" -> com.example.core.model.ConflictResolutionChoice.USE_BACKUP
                        else -> com.example.core.model.ConflictResolutionChoice.FAIL_ON_CONFLICT
                    }

                    when (resolvedLineage) {
                        com.example.core.model.ConflictResolutionChoice.USE_LIVE -> {
                            // Complete Lineage Rule (P2-G3-REQ-02): retain live baseline + live ledger history ONLY.
                            // Do NOT mix backup ledger history from conflicting baseline into live baseline.
                            conflictsResolvedCount++
                        }
                        com.example.core.model.ConflictResolutionChoice.USE_BACKUP -> {
                            // Complete Lineage Rule (P2-G3-REQ-02): retain backup baseline + backup ledger history ONLY.
                            liveDb.localLedgerEntryDao().deleteByAccountId(accId)
                            liveDb.localAccountDao().update(backupAcc)
                            if (backupAccLedgers.isNotEmpty()) {
                                liveDb.localLedgerEntryDao().insertAll(backupAccLedgers)
                                ledgersMergedCount += backupAccLedgers.size
                            }
                            conflictsResolvedCount++
                        }
                        else -> {
                            throw com.example.core.model.IncompatibleBaselineConflictException(
                                "Incompatible opening/current baseline for account $accId requires explicit lineage resolution before final Room write."
                            )
                        }
                    }
                } else {
                    // Baselines are compatible / same lineage -> merge transactions with deduplication and material divergence detection
                    val liveLedgerById = liveAccLedgers.associateBy { it.id }
                    val backupLedgerById = backupAccLedgers.associateBy { it.id }
                    val allTxIds = (liveLedgerById.keys + backupLedgerById.keys).distinct()

                    val finalLedgerEntries = mutableListOf<com.example.core.model.LocalLedgerEntry>()

                    for (txId in allTxIds) {
                        val liveTx = liveLedgerById[txId]
                        val backupTx = backupLedgerById[txId]

                        if (liveTx != null && backupTx == null) {
                            finalLedgerEntries.add(liveTx)
                        } else if (liveTx == null && backupTx != null) {
                            finalLedgerEntries.add(backupTx)
                            ledgersMergedCount++
                        } else if (liveTx != null && backupTx != null) {
                            // Same ID in both: check if payload is identical
                            val isPayloadIdentical = liveTx.accountId == backupTx.accountId &&
                                    liveTx.typeRaw == backupTx.typeRaw &&
                                    kotlin.math.abs(liveTx.amountIqd - backupTx.amountIqd) < 0.0001

                            if (isPayloadIdentical) {
                                // Deduplicate to 1 logical transaction (INV-01 / P2-G3-REQ-02)
                                finalLedgerEntries.add(liveTx)
                                ledgersDeduplicatedCount++
                            } else {
                                // Materially divergent payload! (INV-01 / P2-G3-REQ-02)
                                val txChoice = decision.conflictDecisions[txId]
                                when (txChoice) {
                                    com.example.core.model.ConflictResolutionChoice.USE_LIVE -> {
                                        finalLedgerEntries.add(liveTx)
                                        conflictsResolvedCount++
                                    }
                                    com.example.core.model.ConflictResolutionChoice.USE_BACKUP -> {
                                        finalLedgerEntries.add(backupTx)
                                        conflictsResolvedCount++
                                    }
                                    else -> {
                                        throw com.example.core.model.DivergentPayloadConflictException(
                                            "Same-ID divergent payload conflict for transaction $txId: " +
                                            "live={accountId=${liveTx.accountId}, type=${liveTx.typeRaw}, amount=${liveTx.amountIqd}}, " +
                                            "backup={accountId=${backupTx.accountId}, type=${backupTx.typeRaw}, amount=${backupTx.amountIqd}}"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Recalculate account balances deterministically from accepted baseline + unique deduplicated ledger entries
                    val isSnapshot = liveAcc.stateSource != null
                    val (derivedBalances, updatedLedgerList) = com.example.core.ledger.BalanceCalculator.reconstructCurrentPosition(
                        openingDebt = liveAcc.openingDebtIqd,
                        openingAdvance = liveAcc.openingAdvanceIqd,
                        openingLoan = liveAcc.openingLoanIqd,
                        transactions = finalLedgerEntries,
                        isSnapshotBaseline = isSnapshot
                    )

                    val updatedAccount = liveAcc.copy(
                        debtIqd = derivedBalances.debtIqd,
                        advanceIqd = derivedBalances.advanceIqd,
                        loanIqd = derivedBalances.loanIqd,
                        updatedAt = System.currentTimeMillis()
                    )

                    liveDb.localLedgerEntryDao().deleteByAccountId(accId)
                    liveDb.localAccountDao().update(updatedAccount)
                    if (updatedLedgerList.isNotEmpty()) {
                        liveDb.localLedgerEntryDao().insertAll(updatedLedgerList)
                    }
                    accountsMergedCount++
                }
            }
        }

        // Import batches merge
        val backupBatches = backupDb.importBatchDao().getAllOneShot()
        for (b in backupBatches) {
            val existing = liveDb.importBatchDao().getById(b.id)
            if (existing == null) {
                liveDb.importBatchDao().insert(b)
            }
        }

        return com.example.core.model.RestoreMergeResult(
            success = true,
            accountsMerged = accountsMergedCount,
            ledgersMerged = ledgersMergedCount,
            ledgersDeduplicated = ledgersDeduplicatedCount,
            conflictsResolved = conflictsResolvedCount,
            summary = "Restore Merge completed: $accountsMergedCount accounts, $ledgersMergedCount ledgers merged, $ledgersDeduplicatedCount deduplicated, $conflictsResolvedCount conflicts resolved."
        )
    }

    /**
     * Lineage-safe merger of two complete snapshot lineages (P2-G3-REQ-02).
     */
    fun mergeSnapshotLineages(
        liveLineage: com.example.core.model.SnapshotLineage,
        backupLineage: com.example.core.model.SnapshotLineage,
        decision: com.example.core.model.RestoreMergeDecision
    ): com.example.core.model.SnapshotLineage {
        val liveAccMap = liveLineage.baselineAccounts.associateBy { it.id }
        val backupAccMap = backupLineage.baselineAccounts.associateBy { it.id }

        val liveLedgerByAcc = liveLineage.ledgerHistory.groupBy { it.accountId }
        val backupLedgerByAcc = backupLineage.ledgerHistory.groupBy { it.accountId }

        val mergedAccounts = mutableListOf<com.example.core.model.LocalAccount>()
        val mergedLedgers = mutableListOf<com.example.core.model.LocalLedgerEntry>()

        val allAccountIds = (liveAccMap.keys + backupAccMap.keys).distinct()

        for (accId in allAccountIds) {
            val liveAcc = liveAccMap[accId]
            val backupAcc = backupAccMap[accId]
            val liveLedgers = liveLedgerByAcc[accId] ?: emptyList()
            val backupLedgers = backupLedgerByAcc[accId] ?: emptyList()

            if (liveAcc != null && backupAcc == null) {
                mergedAccounts.add(liveAcc)
                mergedLedgers.addAll(liveLedgers)
            } else if (liveAcc == null && backupAcc != null) {
                mergedAccounts.add(backupAcc)
                mergedLedgers.addAll(backupLedgers)
            } else if (liveAcc != null && backupAcc != null) {
                val isOpeningBaselineIdentical = kotlin.math.abs(liveAcc.openingDebtIqd - backupAcc.openingDebtIqd) < 0.001 &&
                        kotlin.math.abs(liveAcc.openingAdvanceIqd - backupAcc.openingAdvanceIqd) < 0.001 &&
                        kotlin.math.abs(liveAcc.openingLoanIqd - backupAcc.openingLoanIqd) < 0.001 &&
                        kotlin.math.abs(liveAcc.currentPriceIqd - backupAcc.currentPriceIqd) < 0.001 &&
                        (liveAcc.sourceExternalId == null || backupAcc.sourceExternalId == null || liveAcc.sourceExternalId == backupAcc.sourceExternalId) &&
                        (liveAcc.sourceBatchId == null || backupAcc.sourceBatchId == null || liveAcc.sourceBatchId == backupAcc.sourceBatchId) &&
                        (liveAcc.stateSource == null || backupAcc.stateSource == null || liveAcc.stateSource == backupAcc.stateSource)

                val isBaselineConflict = !isOpeningBaselineIdentical || (
                        liveLedgers.isEmpty() && backupLedgers.isEmpty() &&
                        (kotlin.math.abs(liveAcc.debtIqd - backupAcc.debtIqd) > 0.001 ||
                         kotlin.math.abs(liveAcc.advanceIqd - backupAcc.advanceIqd) > 0.001 ||
                         kotlin.math.abs(liveAcc.loanIqd - backupAcc.loanIqd) > 0.001)
                )

                val accChoice = decision.conflictDecisions[accId]

                if (isBaselineConflict) {
                    val resolvedLineage = accChoice ?: when {
                        decision.selectedBaselineId == "LIVE" || decision.selectedBaselineId == "LIVE_SNAPSHOT" || decision.selectedBaselineId == liveLineage.lineageId -> com.example.core.model.ConflictResolutionChoice.USE_LIVE
                        decision.selectedBaselineId == "BACKUP" || decision.selectedBaselineId == "BACKUP_SNAPSHOT" || decision.selectedBaselineId == backupLineage.lineageId -> com.example.core.model.ConflictResolutionChoice.USE_BACKUP
                        else -> com.example.core.model.ConflictResolutionChoice.FAIL_ON_CONFLICT
                    }

                    when (resolvedLineage) {
                        com.example.core.model.ConflictResolutionChoice.USE_LIVE -> {
                            mergedAccounts.add(liveAcc)
                            mergedLedgers.addAll(liveLedgers)
                        }
                        com.example.core.model.ConflictResolutionChoice.USE_BACKUP -> {
                            mergedAccounts.add(backupAcc)
                            mergedLedgers.addAll(backupLedgers)
                        }
                        else -> {
                            throw com.example.core.model.IncompatibleBaselineConflictException(
                                "Incompatible opening/current baseline for account $accId requires explicit lineage resolution before final Room write."
                            )
                        }
                    }
                } else {
                    val liveLedgerById = liveLedgers.associateBy { it.id }
                    val backupLedgerById = backupLedgers.associateBy { it.id }
                    val allTxIds = (liveLedgerById.keys + backupLedgerById.keys).distinct()

                    val accountLedgers = mutableListOf<com.example.core.model.LocalLedgerEntry>()

                    for (txId in allTxIds) {
                        val liveTx = liveLedgerById[txId]
                        val backupTx = backupLedgerById[txId]

                        if (liveTx != null && backupTx == null) {
                            accountLedgers.add(liveTx)
                        } else if (liveTx == null && backupTx != null) {
                            accountLedgers.add(backupTx)
                        } else if (liveTx != null && backupTx != null) {
                            val isIdentical = liveTx.accountId == backupTx.accountId &&
                                    liveTx.typeRaw == backupTx.typeRaw &&
                                    kotlin.math.abs(liveTx.amountIqd - backupTx.amountIqd) < 0.0001

                            if (isIdentical) {
                                accountLedgers.add(liveTx)
                            } else {
                                val txChoice = decision.conflictDecisions[txId]
                                when (txChoice) {
                                    com.example.core.model.ConflictResolutionChoice.USE_LIVE -> accountLedgers.add(liveTx)
                                    com.example.core.model.ConflictResolutionChoice.USE_BACKUP -> accountLedgers.add(backupTx)
                                    else -> throw com.example.core.model.DivergentPayloadConflictException(
                                        "Same-ID divergent payload conflict for transaction $txId: " +
                                        "live={accountId=${liveTx.accountId}, type=${liveTx.typeRaw}, amount=${liveTx.amountIqd}}, " +
                                        "backup={accountId=${backupTx.accountId}, type=${backupTx.typeRaw}, amount=${backupTx.amountIqd}}"
                                    )
                                }
                            }
                        }
                    }

                    val isSnapshot = liveAcc.stateSource != null
                    val (derivedBalances, finalLedgersForAcc) = com.example.core.ledger.BalanceCalculator.reconstructCurrentPosition(
                        openingDebt = liveAcc.openingDebtIqd,
                        openingAdvance = liveAcc.openingAdvanceIqd,
                        openingLoan = liveAcc.openingLoanIqd,
                        transactions = accountLedgers,
                        isSnapshotBaseline = isSnapshot
                    )

                    val finalAccount = liveAcc.copy(
                        debtIqd = derivedBalances.debtIqd,
                        advanceIqd = derivedBalances.advanceIqd,
                        loanIqd = derivedBalances.loanIqd,
                        updatedAt = System.currentTimeMillis()
                    )
                    mergedAccounts.add(finalAccount)
                    mergedLedgers.addAll(finalLedgersForAcc)
                }
            }
        }

        return com.example.core.model.SnapshotLineage(
            lineageId = "merged_${liveLineage.lineageId}_${backupLineage.lineageId}",
            baselineAccounts = mergedAccounts,
            ledgerHistory = mergedLedgers,
            importBatches = (liveLineage.importBatches + backupLineage.importBatches).distinctBy { it.id }
        )
    }

    /**
     * Lineage pairing validator ensuring a baseline account is strictly accompanied by its own eligible ledger entries.
     */
    fun validateLineagePairing(
        baseline: com.example.core.model.LocalAccount,
        ledgerEntries: List<com.example.core.model.LocalLedgerEntry>,
        expectedLineageId: String? = null
    ) {
        for (entry in ledgerEntries) {
            if (entry.accountId != baseline.id) {
                throw com.example.core.model.MixedLineageConflictException(
                    "Lineage purity violation: transaction ${entry.id} for account ${entry.accountId} cannot be attached to baseline account ${baseline.id}"
                )
            }
            if (expectedLineageId != null && entry.sourceBatchId != null && entry.sourceBatchId != expectedLineageId && baseline.sourceBatchId != null && baseline.sourceBatchId != entry.sourceBatchId) {
                throw com.example.core.model.MixedLineageConflictException(
                    "Lineage purity violation: transaction ${entry.id} belonging to lineage ${entry.sourceBatchId} cannot be mixed with baseline ${baseline.id} of lineage ${baseline.sourceBatchId}"
                )
            }
        }
    }

    private suspend fun restoreMergeBackupZipInternal(
        context: Context,
        backupFile: File,
        decision: com.example.core.model.RestoreMergeDecision,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.RESTORE) {
            val app = context.applicationContext as? EarthlinkApp
            val currentPassphrase = try {
                app?.preferenceManager?.getDatabasePassphrase() ?: ""
            } catch (_: Throwable) {
                ""
            }

            val artifactHash = calculateFileHash(backupFile)
            if (!decision.isValidFor(artifactHash, decision.selectedBaselineId)) {
                Log.e(TAG, "Restore Merge aborted: RestoreMergeDecision is invalidated, unapproved, or mismatched artifact hash.")
                return@withOperation false
            }

            if (!force) {
                val pendingOutbox = getPendingOutboxCount(context)
                if (pendingOutbox > 0) {
                    Log.w(TAG, "Restore Merge aborted: $pendingOutbox unsynced pending changes exist in outbox queue. Pass force = true to override.")
                    return@withOperation false
                }
            }

            val liveDbInstance = try {
                app?.database ?: AppDatabase.getDatabase(context, currentPassphrase.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                AppDatabase.getDatabase(context, ByteArray(0))
            }
            val preRestoreUnresolved = try {
                liveDbInstance.syncOutboxDao().getAllOneShot().filter {
                    it.status in listOf("pending", "syncing", "failed")
                }
            } catch (_: Throwable) {
                emptyList()
            }

            val lineageSnapshotToken = "abstract_lineage_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"
            val restoreSnapshot = RestoreTransportSnapshot(
                artifactPath = backupFile.absolutePath,
                artifactHash = artifactHash,
                lineageSnapshotToken = lineageSnapshotToken,
                unresolvedObligations = preRestoreUnresolved
            )

            try {
                val dailyBackupsDir = getBackupsDirectory(context)
                val tempZip = createLocalBackupZipInternal(context)
                val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
                val preRestoreFile = File(dailyBackupsDir, "pre_restore_merge_backup_$timeStamp.zip")
                tempZip.copyTo(preRestoreFile, overwrite = true)
                tempZip.delete()
                Log.i(TAG, "Successfully created persistent pre-restore merge backup: ${preRestoreFile.absolutePath}")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to create persistent pre-restore backup before restore merge", e)
                if (!force) {
                    return@withOperation false
                }
            }

            val tempDbName = "merge_source_${System.currentTimeMillis()}.db"
            val tempDb = context.getDatabasePath(tempDbName)
            val tempWal = File(tempDb.path + "-wal")
            val tempShm = File(tempDb.path + "-shm")

            var extractedPassphrase: String? = null
            var backupDb: AppDatabase? = null

            try {
                if (tempDb.exists()) tempDb.delete()
                if (tempWal.exists()) tempWal.delete()
                if (tempShm.exists()) tempShm.delete()

                if (backupFile.name.endsWith(".zip", ignoreCase = true)) {
                    ZipInputStream(FileInputStream(backupFile)).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            when {
                                entry.name == "backup_info.json" -> {
                                    try {
                                        val baos = java.io.ByteArrayOutputStream()
                                        val buf = ByteArray(1024)
                                        var count: Int
                                        while (zis.read(buf).also { count = it } != -1) {
                                            baos.write(buf, 0, count)
                                        }
                                        val jsonStr = baos.toString("UTF-8")
                                        val json = JSONObject(jsonStr)
                                        if (json.has("dbPassphrase")) {
                                            extractedPassphrase = json.getString("dbPassphrase")
                                        }
                                    } catch (_: Throwable) {}
                                }
                                entry.name == DB_NAME || entry.name.endsWith(".db", ignoreCase = true) -> {
                                    tempDb.parentFile?.mkdirs()
                                    FileOutputStream(tempDb).use { fos -> zis.copyTo(fos) }
                                }
                                entry.name == "$DB_NAME-wal" -> {
                                    FileOutputStream(tempWal).use { fos -> zis.copyTo(fos) }
                                }
                                entry.name == "$DB_NAME-shm" -> {
                                    FileOutputStream(tempShm).use { fos -> zis.copyTo(fos) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } else {
                    tempDb.parentFile?.mkdirs()
                    FileInputStream(backupFile).use { fis ->
                        FileOutputStream(tempDb).use { fos -> fis.copyTo(fos) }
                    }
                }

                if (!tempDb.exists() || tempDb.length() == 0L) {
                    Log.e(TAG, "No valid database file found in merge backup.")
                    return@withOperation false
                }

                val fallbackPass = app?.preferenceManager?.getFallbackPassphrase(context)
                val firebaseUid = app?.syncRepository?.getFirebaseUid()
                val candidates = listOfNotNull(
                    extractedPassphrase,
                    currentPassphrase,
                    fallbackPass,
                    firebaseUid,
                    ""
                ).distinct()
                var verifiedPassphrase: String? = null

                for (candPass in candidates) {
                    var testDb: AppDatabase? = null
                    try {
                        testDb = AppDatabase.getDatabase(context, candPass.toByteArray(Charsets.UTF_8), tempDbName)
                        val sqliteDb = testDb.openHelper.writableDatabase
                        sqliteDb.query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='local_accounts'").use { cursor ->
                            if (!cursor.moveToFirst() || cursor.getInt(0) == 0) {
                                throw IllegalStateException("Restored database does not contain local_accounts table")
                            }
                        }
                        verifiedPassphrase = candPass
                        backupDb = testDb
                        break
                    } catch (_: Throwable) {
                        testDb?.close()
                        AppDatabase.closeAndRemoveInstance(tempDbName)
                    }
                }

                if (verifiedPassphrase != null && backupDb != null) {
                    val liveDb = try {
                        app?.database ?: AppDatabase.getDatabase(context, currentPassphrase.toByteArray(Charsets.UTF_8))
                    } catch (_: Throwable) {
                        AppDatabase.getDatabase(context, ByteArray(0))
                    }
                    liveDb.withTransaction {
                        executeRestoreMergeInternal(liveDb, backupDb!!, decision)

                        reconstructTransportState(liveDb, restoreSnapshot.unresolvedObligations)

                        val now = System.currentTimeMillis()
                        val salt = try { app?.preferenceManager?.getDatabasePassphrase() ?: currentPassphrase } catch (_: Throwable) { "default_test_salt" }
                        val rawString = "$now|INFO|DATABASE_RESTORE_MERGE|Backup merged and verified successfully with complete lineage.|system|$salt"
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val sig = digest.digest(rawString.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

                        liveDb.auditLogDao().insert(
                            com.example.core.model.AuditLog(
                                action = "DATABASE_RESTORE_MERGE",
                                entityType = null,
                                entityId = null,
                                summary = "Backup merged and verified successfully with complete lineage.",
                                createdAt = now,
                                severity = "INFO",
                                actor = "system",
                                signature = sig,
                                origin = com.example.core.model.AuditOrigin.RESTORE_EVENT.name
                            )
                        )
                    }

                    try {
                        (app?.syncRepository as? com.example.core.sync.SyncRepositoryImpl)?.remoteSyncCoordinator?.clearCache()
                    } catch (_: Throwable) {}

                    Log.i(TAG, "Restore Merge completed successfully.")
                    true
                } else {
                    Log.e(TAG, "Failed to decrypt/open backup database for merge.")
                    false
                }
            } finally {
                try {
                    backupDb?.close()
                    AppDatabase.closeAndRemoveInstance(tempDbName)
                } catch (_: Throwable) {}
                if (tempDb.exists()) tempDb.delete()
                if (tempWal.exists()) tempWal.delete()
                if (tempShm.exists()) tempShm.delete()
            }
        }
    }

    suspend fun restoreBackupZip(context: Context, backupFile: File, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        restoreBackupZipInternal(context, backupFile, decision = null, force = force)
    }

    private suspend fun restoreBackupZipInternal(
        context: Context,
        backupFile: File,
        decision: com.example.core.model.RestoreMergeDecision?,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.RESTORE) {
            val app = context.applicationContext as? EarthlinkApp
            val currentPassphrase = try {
                app?.preferenceManager?.getDatabasePassphrase() ?: ""
            } catch (_: Throwable) {
                ""
            }

            val artifactHash = calculateFileHash(backupFile)
            if (decision != null && !decision.isValidFor(artifactHash, decision.selectedBaselineId)) {
                Log.e(TAG, "Restore aborted: RestoreMergeDecision is invalidated, unapproved, or mismatched artifact hash.")
                return@withOperation false
            }

            // Safety check: verify no unsynced outbox entries exist unless force restore is explicitly requested
            if (!force) {
                val pendingOutbox = getPendingOutboxCount(context)
                if (pendingOutbox > 0) {
                    Log.w(TAG, "Restore aborted: $pendingOutbox unsynced pending changes exist in outbox queue. Pass force = true to override.")
                    return@withOperation false
                }
            }

            // Snapshot unresolved obligations prior to restore (P1-G2-REQ-05 / INV-13)
            val liveDbInstance = try {
                app?.database ?: AppDatabase.getDatabase(context, currentPassphrase.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                AppDatabase.getDatabase(context, ByteArray(0))
            }
            val preRestoreUnresolved = try {
                liveDbInstance.syncOutboxDao().getAllOneShot().filter {
                    it.status in listOf("pending", "syncing", "failed")
                }
            } catch (_: Throwable) {
                emptyList()
            }

            val lineageSnapshotToken = "abstract_lineage_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"
            val restoreSnapshot = RestoreTransportSnapshot(
                artifactPath = backupFile.absolutePath,
                artifactHash = artifactHash,
                lineageSnapshotToken = lineageSnapshotToken,
                unresolvedObligations = preRestoreUnresolved
            )

        // 0. Force-create persistent pre-restore backup in EarthlinkBackups directory
        var preRestoreSuccess = false
        try {
            val dailyBackupsDir = getBackupsDirectory(context)
            val tempZip = createLocalBackupZipInternal(context)
            val timeStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS", Locale.US).format(Date())
            val preRestoreFile = File(dailyBackupsDir, "pre_restore_backup_$timeStamp.zip")
            tempZip.copyTo(preRestoreFile, overwrite = true)
            tempZip.delete()
            preRestoreSuccess = true
            Log.i(TAG, "Successfully created persistent pre-restore backup: ${preRestoreFile.absolutePath}")
            (context.applicationContext as? EarthlinkApp)?.auditRepository?.log(
                severity = com.example.core.model.AuditSeverity.INFO,
                action = "PRE_RESTORE_BACKUP_CREATED",
                message = "Successfully created persistent pre-restore backup: ${preRestoreFile.absolutePath}"
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to create persistent pre-restore backup before restore", e)
            (context.applicationContext as? EarthlinkApp)?.auditRepository?.log(
                severity = com.example.core.model.AuditSeverity.CRITICAL,
                action = "PRE_RESTORE_BACKUP_FAILED",
                message = "Failed to create persistent pre-restore backup: ${e.localizedMessage}"
            )
            if (!force) {
                Log.e(TAG, "Aborting restore because pre-restore safety backup could not be created and force=false.")
                return@withOperation false
            }
        }

        val tempDbName = "merged_backup.db"
        val tempDb = context.getDatabasePath(tempDbName)
        val tempWal = File(tempDb.path + "-wal")
        val tempShm = File(tempDb.path + "-shm")

        var extractedPassphrase: String? = null
        var backupDb: AppDatabase? = null

        try {
            // Clear old target WAL/SHM sidecar files
            if (tempDb.exists()) tempDb.delete()
            if (tempWal.exists()) tempWal.delete()
            if (tempShm.exists()) tempShm.delete()

            if (backupFile.name.endsWith(".zip", ignoreCase = true)) {
                // Extract DB, sidecars, and backup_info.json from ZIP
                ZipInputStream(FileInputStream(backupFile)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == "backup_info.json" -> {
                                try {
                                    val baos = java.io.ByteArrayOutputStream()
                                    val buf = ByteArray(1024)
                                    var count: Int
                                    while (zis.read(buf).also { count = it } != -1) {
                                        baos.write(buf, 0, count)
                                    }
                                    val jsonStr = baos.toString("UTF-8")
                                    val json = JSONObject(jsonStr)
                                    if (json.has("dbPassphrase")) {
                                        val encPass = json.getString("dbPassphrase")
                                        if (encPass.isNotBlank()) {
                                            val firebaseUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                                            val deviceId = app?.preferenceManager?.getDeviceId() ?: "default_device_id"
                                            val candidateSeeds = listOfNotNull(firebaseUid, deviceId, "default_fallback_uid").distinct()
                                            var decrypted: String? = null

                                            if (json.has("salt") && json.has("iv")) {
                                                val iv = android.util.Base64.decode(json.getString("iv"), android.util.Base64.NO_WRAP)
                                                val salt = android.util.Base64.decode(json.getString("salt"), android.util.Base64.NO_WRAP)
                                                val cipherBytes = android.util.Base64.decode(encPass, android.util.Base64.NO_WRAP)

                                                for (seed in candidateSeeds) {
                                                    try {
                                                        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                                                        val spec = javax.crypto.spec.PBEKeySpec(seed.toCharArray(), salt, 10000, 256)
                                                        val secretKey = factory.generateSecret(spec)
                                                        val keySpec = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

                                                        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                                                        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                                                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)

                                                        val res = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                                                        if (res.isNotBlank()) {
                                                            decrypted = res
                                                            break
                                                        }
                                                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                                                }
                                            } else {
                                                // Legacy AES/ECB decryption path
                                                for (seed in candidateSeeds) {
                                                    try {
                                                        val key = java.security.MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
                                                        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
                                                        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
                                                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey)
                                                        val res = String(cipher.doFinal(android.util.Base64.decode(encPass, android.util.Base64.NO_WRAP)), Charsets.UTF_8)
                                                        if (res.isNotBlank()) {
                                                            decrypted = res
                                                            break
                                                        }
                                                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
                                                }
                                            }
                                            extractedPassphrase = decrypted ?: encPass
                                        }
                                    }
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                    Log.w(TAG, "Failed to parse backup_info.json in backup ZIP", e)
                                }
                            }
                            entry.name == DB_NAME || entry.name.endsWith(".db", ignoreCase = true) -> {
                                tempDb.parentFile?.mkdirs()
                                FileOutputStream(tempDb).use { fos ->
                                    zis.copyTo(fos)
                                }
                                Log.i(TAG, "Successfully extracted DB from backup ZIP to ${tempDb.absolutePath}")
                            }
                            entry.name == "$DB_NAME-wal" -> {
                                FileOutputStream(tempWal).use { fos -> zis.copyTo(fos) }
                            }
                            entry.name == "$DB_NAME-shm" -> {
                                FileOutputStream(tempShm).use { fos -> zis.copyTo(fos) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                // Direct .db copy
                tempDb.parentFile?.mkdirs()
                FileInputStream(backupFile).use { fis ->
                    FileOutputStream(tempDb).use { fos ->
                        fis.copyTo(fos)
                    }
                }
                Log.i(TAG, "Directly copied .db backup file to ${tempDb.absolutePath}")
            }

            if (!tempDb.exists() || tempDb.length() == 0L) {
                Log.e(TAG, "No valid database file found in backup.")
                return@withOperation false
            }

            // Build candidate passphrases list
            val fallbackPass = app?.preferenceManager?.getFallbackPassphrase(context)
            val firebaseUid = app?.syncRepository?.getFirebaseUid()
            val candidates = listOfNotNull(
                extractedPassphrase,
                currentPassphrase,
                fallbackPass,
                firebaseUid,
                "" // Empty passphrase candidate for unencrypted SQLite databases
            ).distinct()
            var verifiedPassphrase: String? = null

            System.err.println("RESTORE_DEBUG: tempDb.exists=${tempDb.exists()}, length=${tempDb.length()}, candidates=$candidates")
            for (candPass in candidates) {
                var testDb: AppDatabase? = null
                try {
                    if (candPass == "") {
                        val header = ByteArray(16)
                        var readBytes = 0
                        java.io.FileInputStream(tempDb).use { fis ->
                            readBytes = fis.read(header)
                        }
                        if (readBytes != 16 || !header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))) {
                            Log.w(TAG, "Unencrypted candidate failed: not a valid SQLite header")
                            continue
                        }
                    }

                    System.err.println("RESTORE_DEBUG: Testing candidate: '$candPass'")
                    testDb = AppDatabase.getDatabase(context, candPass.toByteArray(Charsets.UTF_8), tempDbName)
                    val sqliteDb = testDb.openHelper.writableDatabase
                    sqliteDb.query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='local_accounts'").use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getInt(0) == 0) {
                            throw IllegalStateException("Restored database does not contain local_accounts table")
                        }
                    }
                    verifiedPassphrase = candPass
                    backupDb = testDb
                    System.err.println("RESTORE_DEBUG: SUCCESS on candidate: '$candPass'")
                    Log.i(TAG, "Verified database decryption with passphrase candidate (${if (candPass.isEmpty()) "unencrypted" else "passphrase-protected"})")
                    break
                } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    System.err.println("RESTORE_DEBUG: Candidate '$candPass' failed: ${e.message}")
                    Log.w(TAG, "Candidate passphrase failed to open restored database: ${e.message}")
                    testDb?.close()
                    AppDatabase.closeAndRemoveInstance(tempDbName)
                }
            }

            if (verifiedPassphrase != null && backupDb != null) {
                // Phase 10: Single massive transaction to prevent partial-live-state corruption
                try {
                    val liveDb = try {
                        app?.database ?: AppDatabase.getDatabase(context, currentPassphrase.toByteArray(Charsets.UTF_8))
                    } catch (_: Throwable) {
                        AppDatabase.getDatabase(context, ByteArray(0))
                    }
                    liveDb.withTransaction {
                        executeRestoreReplaceInternal(
                            liveDb = liveDb,
                            backupDb = backupDb!!,
                            unresolvedObligations = restoreSnapshot.unresolvedObligations,
                            passphrase = currentPassphrase
                        )
                    }

                    try {
                        (app?.syncRepository as? com.example.core.sync.SyncRepositoryImpl)?.remoteSyncCoordinator?.clearCache()
                    } catch (_: Throwable) {}
                } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    System.err.println("RESTORE_DEBUG: Error merging entities: ${e.message}")
                    Log.e(TAG, "Error merging entities during restore", e)
                    e.printStackTrace()
                    return@withOperation false
                }

                Log.i(TAG, "Backup restored and verified successfully.")
                true
            } else {
                System.err.println("RESTORE_DEBUG: Decryption failed, verifiedPassphrase=$verifiedPassphrase, backupDb=$backupDb")
                Log.e(TAG, "None of the candidate passphrases could decrypt the restored database file. Reverting restore.")
                try {
                    (context.applicationContext as? EarthlinkApp)?.auditRepository?.log(
                        severity = com.example.core.model.AuditSeverity.CRITICAL,
                        action = "RESTORE_DECRYPTION_FAILED",
                        message = "None of the candidate passphrases could decrypt the restored database file."
                    )
                } catch (_: Throwable) {}
                false
            }
        } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e;
            System.err.println("RESTORE_DEBUG: Exception in restoreBackupZip: ${e.message}")
            Log.e(TAG, "Failed to restore backup file: ${backupFile.absolutePath}", e)
            try {
                (context.applicationContext as? EarthlinkApp)?.auditRepository?.log(
                    severity = com.example.core.model.AuditSeverity.CRITICAL,
                    action = "RESTORE_FAILED",
                    message = "Failed to restore backup: ${e.localizedMessage}"
                )
            } catch (_: Throwable) {}
            e.printStackTrace()
            false
        } finally {
            try {
                if (backupDb != null && (app == null || backupDb !== app.database)) {
                    backupDb.close()
                }
                AppDatabase.closeAndRemoveInstance(tempDbName)
            } catch (e: Exception) {
                Log.w(TAG, "Error closing backup DB in restore cleanup: ${e.message}")
            }
            if (tempDb.exists()) tempDb.delete()
            if (tempWal.exists()) tempWal.delete()
            if (tempShm.exists()) tempShm.delete()
        }
    }
}

    suspend fun exportBackupToUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val zipFile = createLocalBackupZip(context)
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(zipFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            zipFile.delete() // Cleanup temporary zip file
            Log.i(TAG, "Successfully exported backup to URI: $uri")
            true
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e(TAG, "Failed to export backup to URI: $uri", e)
            false
        }
    }

    suspend fun importBackupFromUri(context: Context, uri: Uri, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "imported_restore_temp.zip")
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false

            val success = restoreBackupZip(context, tempFile, force = force)
            tempFile.delete()
            success
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e(TAG, "Failed to import backup from URI: $uri", e)
            false
        }
    }

    private fun addFileToZip(zos: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { fis ->
            zos.putNextEntry(ZipEntry(entryName))
            fis.copyTo(zos)
            zos.closeEntry()
        }
    }
}
