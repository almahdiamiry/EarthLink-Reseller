package com.example.core.database

import android.content.Context
import androidx.room.*
import com.example.core.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalAccountDao {
    @Query("SELECT * FROM local_accounts ORDER BY displayName ASC LIMIT :limit")
    fun getAll(limit: Int = 100000): Flow<List<LocalAccount>>

    @Query("SELECT * FROM local_accounts ORDER BY displayName ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun getAllOneShot(limit: Int = 100000, offset: Int = 0): List<LocalAccount>

    @Query("SELECT * FROM local_accounts WHERE id = :id")
    fun getById(id: String): Flow<LocalAccount?>

    @Query("SELECT * FROM local_accounts WHERE id = :id")
    suspend fun getByIdOneShot(id: String): LocalAccount?

    @Query("SELECT * FROM local_accounts WHERE sourceExternalId = :sourceExternalId LIMIT 1")
    suspend fun findBySourceExternalId(sourceExternalId: String): LocalAccount?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: LocalAccount): Long

    @Update
    suspend fun update(account: LocalAccount)

    @Transaction
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accounts: List<LocalAccount>)

    @Transaction
    suspend fun upsert(account: LocalAccount) {
        if (getByIdOneShot(account.id) != null) {
            update(account)
        } else {
            insert(account)
        }
    }

    @Transaction
    suspend fun upsertAll(accounts: List<LocalAccount>) {
        for (account in accounts) {
            upsert(account)
        }
    }

    @Query("SELECT * FROM local_accounts WHERE sourceBatchId = :batchId")
    suspend fun getByBatchId(batchId: String): List<LocalAccount>

    @Query("DELETE FROM local_accounts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_accounts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM local_accounts")
    suspend fun deleteAll()
    
    @Query("SELECT * FROM local_accounts WHERE (earthlinkUsername IS NOT NULL AND earthlinkUsername = :username) OR (phone1 IS NOT NULL AND phone1 = :phone) OR (displayName = :name)")
    suspend fun findDuplicates(username: String?, phone: String?, name: String?): List<LocalAccount>

    @Query("SELECT * FROM local_accounts WHERE id = :username OR (earthlinkUsername IS NOT NULL AND LOWER(earthlinkUsername) = LOWER(:username)) LIMIT 1")
    fun getAccountByUsernameOrId(username: String): Flow<LocalAccount?>

    @Query("SELECT * FROM local_accounts WHERE id = :username OR (earthlinkUsername IS NOT NULL AND LOWER(earthlinkUsername) = LOWER(:username)) LIMIT 1")
    suspend fun findAccountByUsernameOrIdOneShot(username: String): LocalAccount?

    @Query("""
        SELECT * FROM local_accounts 
        WHERE (:query = '' OR displayName LIKE :query || '%' OR earthlinkUsername LIKE :query || '%' OR phone1 LIKE :query || '%' OR phone2 LIKE :query || '%')
        ORDER BY displayName ASC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun searchAccounts(query: String, limit: Int = 100, offset: Int = 0): List<LocalAccount>

    @Query("""
        SELECT COUNT(*) FROM local_accounts 
        WHERE (:query = '' OR displayName LIKE :query || '%' OR earthlinkUsername LIKE :query || '%' OR phone1 LIKE :query || '%' OR phone2 LIKE :query || '%')
    """)
    suspend fun getSearchCount(query: String): Int

    @Query("SELECT COUNT(*) FROM local_accounts")
    suspend fun getTotalCount(): Int

    @RawQuery(observedEntities = [LocalAccount::class])
    fun searchAccountsRawFlow(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<List<LocalAccount>>

    @RawQuery(observedEntities = [LocalAccount::class])
    fun getSearchCountRawFlow(query: androidx.sqlite.db.SupportSQLiteQuery): Flow<Int>
}

@Dao
interface LocalLedgerEntryDao {
    @Query("SELECT * FROM local_ledger_entries WHERE accountId = :accountId ORDER BY occurredAt DESC, createdAt DESC, id DESC LIMIT :limit")
    fun getByAccountId(accountId: String, limit: Int = 100000): Flow<List<LocalLedgerEntry>>

    @Query("SELECT * FROM local_ledger_entries WHERE accountId = :accountId ORDER BY occurredAt DESC, createdAt DESC, id DESC LIMIT :limit")
    suspend fun getByAccountIdOneShot(accountId: String, limit: Int = 100000): List<LocalLedgerEntry>

    @Query("""
        SELECT * FROM local_ledger_entries 
        WHERE accountId = :accountId 
          AND (
               (:sourceExternalId IS NOT NULL AND sourceExternalId = :sourceExternalId) 
               OR 
               (:sourceExternalId IS NULL AND occurredAt = :occurredAt AND amountIqd = :amountIqd AND typeRaw = :typeRaw)
          ) 
        LIMIT 1
    """)
    suspend fun findDuplicateTx(accountId: String, sourceExternalId: String?, occurredAt: Long, amountIqd: Double, typeRaw: String): LocalLedgerEntry?

    @Query("SELECT * FROM local_ledger_entries ORDER BY occurredAt DESC, createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun getAllOneShot(limit: Int = 100000, offset: Int = 0): List<LocalLedgerEntry>

    @Query("SELECT COUNT(*) FROM local_ledger_entries")
    suspend fun getTotalCount(): Int

    @Query("SELECT * FROM local_ledger_entries WHERE id = :id")
    suspend fun getByIdOneShot(id: String): LocalLedgerEntry?

    @Query("SELECT * FROM local_ledger_entries WHERE sourceExternalId = :sourceExternalId LIMIT 1")
    suspend fun findBySourceExternalId(sourceExternalId: String): LocalLedgerEntry?

    @Query("SELECT * FROM local_ledger_entries WHERE accountId = :accountId AND sourceExternalId = :sourceExternalId LIMIT 1")
    suspend fun findByAccountAndExternalId(accountId: String, sourceExternalId: String): LocalLedgerEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: LocalLedgerEntry): Long

    @Update
    suspend fun update(entry: LocalLedgerEntry): Int

    @Transaction
    suspend fun upsert(entry: LocalLedgerEntry) {
        if (getByIdOneShot(entry.id) != null) {
            update(entry)
        } else {
            insert(entry)
        }
    }

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<LocalLedgerEntry>)

    @Query("SELECT * FROM local_ledger_entries WHERE sourceBatchId = :batchId")
    suspend fun getByBatchId(batchId: String): List<LocalLedgerEntry>

    @Query("DELETE FROM local_ledger_entries WHERE sourceBatchId = :batchId")
    suspend fun deleteByBatchId(batchId: String)

    @Query("DELETE FROM local_ledger_entries WHERE accountId = :accountId")
    suspend fun deleteByAccountId(accountId: String)

    @Query("UPDATE local_ledger_entries SET accountId = :newAccountId WHERE accountId = :oldAccountId")
    suspend fun updateAccountId(oldAccountId: String, newAccountId: String): Int

    @Query("DELETE FROM local_ledger_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM local_ledger_entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM local_ledger_entries")
    suspend fun deleteAll()
}

@Dao
interface ImportBatchDao {
    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    fun getAll(): Flow<List<ImportBatch>>

    @Query("SELECT * FROM import_batches ORDER BY createdAt DESC")
    suspend fun getAllOneShot(): List<ImportBatch>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: ImportBatch)

    @Query("SELECT COUNT(*) FROM import_batches WHERE status = 'running'")
    suspend fun getRunningCount(): Int

    @Query("SELECT COUNT(*) FROM import_batches WHERE status IN ('running', 'failed', 'failed/resumable')")
    suspend fun getIncompleteCount(): Int

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun getById(id: String): ImportBatch?

    @Query("SELECT * FROM import_batches WHERE fileHash = :hash AND status = 'completed' LIMIT 1")
    suspend fun getByFileHash(hash: String): ImportBatch?

    @Query("DELETE FROM import_batches")
    suspend fun deleteAll()

    @Query("DELETE FROM import_batches WHERE id != :currentBatchId")
    suspend fun deleteAllExcept(currentBatchId: String)

    @Delete
    suspend fun delete(batch: ImportBatch)
}

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox WHERE status IN ('pending', 'failed') ORDER BY createdAt ASC")
    suspend fun getPending(): List<SyncOutbox>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncOutbox): Long

    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SyncOutbox>): List<Long>

    @Update
    suspend fun update(item: SyncOutbox)

    @Delete
    suspend fun delete(item: SyncOutbox)

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM sync_outbox WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM sync_outbox WHERE entityId = :entityId AND entityType = :entityType")
    suspend fun clearPendingByEntity(entityId: String, entityType: String)

    @Query("DELETE FROM sync_outbox WHERE entityId IN (:entityIds) AND entityType = :entityType")
    suspend fun clearPendingByEntityIds(entityIds: List<String>, entityType: String)

    @Query("DELETE FROM sync_outbox WHERE entityType = :entityType")
    suspend fun clearPendingByEntityType(entityType: String)

    @Query("SELECT * FROM sync_outbox WHERE entityId = :entityId AND entityType = :entityType")
    suspend fun getByEntity(entityId: String, entityType: String): List<SyncOutbox>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE entityId = :entityId AND entityType = :entityType AND status IN ('pending', 'syncing', 'failed')")
    suspend fun hasActiveMutation(entityId: String, entityType: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE entityId = :entityId AND entityType = :entityType AND status IN ('pending', 'syncing', 'failed')")
    suspend fun hasPending(entityId: String, entityType: String): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status IN ('pending', 'syncing', 'failed')")
    suspend fun getAllUnsyncedCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status IN ('pending', 'syncing', 'failed')")
    suspend fun getActivePendingCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status IN ('pending', 'failed')")
    suspend fun getRetryableCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'syncing'")
    suspend fun getInFlightCount(): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE status = 'failed'")
    suspend fun getFailedCount(): Int

    @Query("SELECT * FROM sync_outbox WHERE status = 'failed'")
    suspend fun getFailedItems(): List<SyncOutbox>

    @Query("UPDATE sync_outbox SET status = 'pending', attemptCount = 0, lastError = NULL WHERE status = 'failed'")
    suspend fun resetFailedItems(): Int

    @Query("UPDATE sync_outbox SET status = 'pending' WHERE status = 'syncing'")
    suspend fun resetSyncingToPending(): Int

    @Query("DELETE FROM sync_outbox")
    suspend fun deleteAll()

    @Query("SELECT * FROM sync_outbox")
    suspend fun getAllOneShot(): List<SyncOutbox>
}

@Dao
interface SyncMetadataDao {
    companion object {
        const val KEY_G4_LOCAL_GENERATION = "g4_local_generation"
        const val DEFAULT_GENERATION = 1L
    }

    @Query("SELECT value FROM sync_metadata WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Query("INSERT OR REPLACE INTO sync_metadata (key, value, updatedAt) VALUES (:key, :value, :timestamp)")
    suspend fun put(key: String, value: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM sync_metadata")
    suspend fun deleteAll()

    @Query("DELETE FROM sync_metadata WHERE key != :preserveKey")
    suspend fun deleteAllExcept(preserveKey: String)

    @Query("SELECT * FROM sync_metadata WHERE key != 'g4_local_generation'")
    suspend fun getAllOneShot(): List<com.example.core.model.SyncData>

    @Transaction
    suspend fun getGeneration(): Long {
        val raw = get(KEY_G4_LOCAL_GENERATION)
        return raw?.toLongOrNull() ?: DEFAULT_GENERATION
    }

    @Transaction
    suspend fun setGeneration(gen: Long) {
        put(KEY_G4_LOCAL_GENERATION, gen.toString())
    }

    @Transaction
    suspend fun incrementGeneration(): Long {
        val current = getGeneration()
        val next = current + 1L
        setGeneration(next)
        return next
    }

    @Transaction
    suspend fun ensureGenerationInitialized(): Long {
        val raw = get(KEY_G4_LOCAL_GENERATION)
        return if (raw == null) {
            setGeneration(DEFAULT_GENERATION)
            DEFAULT_GENERATION
        } else {
            raw.toLongOrNull() ?: DEFAULT_GENERATION
        }
    }
}

@Dao
interface AuditLogDao {
    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC")
    fun getAll(): Flow<List<AuditLog>>

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC")
    suspend fun getAllSync(): List<AuditLog>

    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<AuditLog>

    @Query("SELECT * FROM audit_log WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AuditLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AuditLog): Long

    @Query("DELETE FROM audit_log")
    suspend fun clearAll()
}

@Dao
interface PendingExternalOperationDao {
    @Query("SELECT * FROM pending_external_operations WHERE status = 'PENDING' ORDER BY createdAt ASC")
    fun getPendingFlow(): Flow<List<PendingExternalOperation>>

    @Query("SELECT * FROM pending_external_operations WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun getPendingOperations(): List<PendingExternalOperation>

    @Query("SELECT * FROM pending_external_operations WHERE businessTransactionId = :businessTransactionId LIMIT 1")
    suspend fun getByBusinessTransactionId(businessTransactionId: String): PendingExternalOperation?

    @Query("SELECT * FROM pending_external_operations WHERE operationIntentId = :operationIntentId LIMIT 1")
    suspend fun getByOperationIntentId(operationIntentId: String): PendingExternalOperation?

    @Query("SELECT * FROM pending_external_operations WHERE accountId = :accountId ORDER BY createdAt DESC")
    suspend fun getByAccountId(accountId: String): List<PendingExternalOperation>

    @Query("SELECT * FROM pending_external_operations WHERE accountId = :accountId AND status = 'PENDING' LIMIT 1")
    suspend fun getPendingByAccountId(accountId: String): PendingExternalOperation?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(operation: PendingExternalOperation): Long

    @Update
    suspend fun update(operation: PendingExternalOperation)

    @Transaction
    suspend fun upsert(operation: PendingExternalOperation) {
        if (getByBusinessTransactionId(operation.businessTransactionId) != null) {
            update(operation)
        } else {
            insert(operation)
        }
    }

    @Query("UPDATE pending_external_operations SET status = :status, updatedAt = :updatedAt, lastError = :lastError WHERE businessTransactionId = :businessTransactionId")
    suspend fun updateStatus(businessTransactionId: String, status: String, updatedAt: Long = System.currentTimeMillis(), lastError: String? = null)

    @Query("DELETE FROM pending_external_operations WHERE businessTransactionId = :businessTransactionId")
    suspend fun deleteByBusinessTransactionId(businessTransactionId: String)

    @Query("DELETE FROM pending_external_operations WHERE operationIntentId = :operationIntentId")
    suspend fun deleteByOperationIntentId(operationIntentId: String)

    @Query("DELETE FROM pending_external_operations")
    suspend fun deleteAll()

    @Query("SELECT * FROM pending_external_operations")
    suspend fun getAllOneShot(): List<PendingExternalOperation>

    @Query("SELECT * FROM pending_external_operations WHERE status IN ('PENDING', 'RESOLVING') ORDER BY createdAt ASC")
    suspend fun getUnresolvedOperations(): List<PendingExternalOperation>

    @Query("SELECT * FROM pending_external_operations WHERE accountId = :accountId AND status IN ('PENDING', 'RESOLVING') LIMIT 1")
    suspend fun getUnresolvedByAccountId(accountId: String): PendingExternalOperation?
}

@Database(
    entities = [
        LocalAccount::class,
        LocalLedgerEntry::class,
        ImportBatch::class,
        SyncOutbox::class,
        SyncData::class,
        AuditLog::class,
        PendingExternalOperation::class
    ],
    version = AppDatabase.VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun localAccountDao(): LocalAccountDao
    abstract fun localLedgerEntryDao(): LocalLedgerEntryDao
    abstract fun importBatchDao(): ImportBatchDao
    abstract fun syncOutboxDao(): SyncOutboxDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun pendingExternalOperationDao(): PendingExternalOperationDao

    suspend fun getGeneration(): Long = syncMetadataDao().getGeneration()

    suspend fun incrementGeneration(): Long = syncMetadataDao().incrementGeneration()

    suspend fun setGeneration(gen: Long) = syncMetadataDao().setGeneration(gen)

    suspend fun clearAllData(): Long = withTransaction {
        localLedgerEntryDao().deleteAll()
        localAccountDao().deleteAll()
        importBatchDao().deleteAll()
        syncOutboxDao().deleteAll()
        pendingExternalOperationDao().deleteAll()
        syncMetadataDao().deleteAllExcept(SyncMetadataDao.KEY_G4_LOCAL_GENERATION)
        syncMetadataDao().incrementGeneration()
    }

    companion object {
        const val VERSION = 12

        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // No schema changes between 1 and 2, just version bump.
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_accounts_displayName` ON `local_accounts` (`displayName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_accounts_updatedAt` ON `local_accounts` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_occurredAt` ON `local_ledger_entries` (`occurredAt`)")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create a temporary mapping table to map old_id to survivor_id based on ledger entry count and rowid
                db.execSQL("""
                    CREATE TEMP TABLE IF NOT EXISTS `_temp_acc_map` AS 
                    SELECT id AS old_id, (
                        SELECT a2.id FROM `local_accounts` a2 
                        WHERE a2.sourceExternalId = a1.sourceExternalId 
                        ORDER BY (SELECT COUNT(*) FROM `local_ledger_entries` l WHERE l.accountId = a2.id) DESC, a2.rowid ASC 
                        LIMIT 1
                    ) AS survivor_id 
                    FROM `local_accounts` a1 
                    WHERE a1.sourceExternalId IS NOT NULL AND a1.sourceExternalId != ''
                """.trimIndent())

                // 2. Reassign any ledger entries belonging to non-survivor duplicate accounts to survivor_id
                db.execSQL("""
                    UPDATE `local_ledger_entries` 
                    SET `accountId` = (
                        SELECT survivor_id FROM `_temp_acc_map` WHERE old_id = `local_ledger_entries`.`accountId`
                    )
                    WHERE `accountId` IN (SELECT old_id FROM `_temp_acc_map` WHERE old_id != survivor_id)
                """.trimIndent())

                // 3. Remove duplicate non-survivor accounts
                db.execSQL("""
                    DELETE FROM `local_accounts` 
                    WHERE id IN (SELECT old_id FROM `_temp_acc_map` WHERE old_id != survivor_id)
                """.trimIndent())

                // 4. Deduplicate local_ledger_entries safely before applying unique index (keep highest createdAt, rowid DESC)
                db.execSQL("""
                    DELETE FROM `local_ledger_entries` 
                    WHERE `sourceExternalId` IS NOT NULL 
                    AND `sourceExternalId` != '' 
                    AND rowid NOT IN (
                        SELECT l1.rowid FROM `local_ledger_entries` l1
                        WHERE l1.`sourceExternalId` IS NOT NULL AND l1.`sourceExternalId` != ''
                        AND l1.rowid = (
                            SELECT l2.rowid FROM `local_ledger_entries` l2
                            WHERE l2.`accountId` = l1.`accountId` AND l2.`sourceExternalId` = l1.`sourceExternalId`
                            ORDER BY l2.`createdAt` DESC, l2.rowid DESC
                            LIMIT 1
                        )
                    )
                """.trimIndent())

                // 4.5. Recompute survivor account balances (debtIqd, advanceIqd, loanIqd) based on newly merged and deduplicated ledger entries
                db.execSQL("""
                    UPDATE `local_accounts`
                    SET 
                        `debtIqd` = MAX(0.0, (
                            SELECT COALESCE(SUM(
                                CASE 
                                    WHEN LOWER(typeRaw) IN ('took', 'debt', 'debt_added', 'renewal', 'renew', 'sub_renew', 'sub_renewal', 'debt_renew') THEN amountIqd
                                    WHEN LOWER(typeRaw) IN ('gave', 'payment', 'deposit', 'pay') THEN -amountIqd
                                    ELSE 0.0
                                END
                            ), 0.0)
                            FROM `local_ledger_entries`
                            WHERE `accountId` = `local_accounts`.`id`
                        )),
                        `advanceIqd` = MAX(0.0, -1.0 * (
                            SELECT COALESCE(SUM(
                                CASE 
                                    WHEN LOWER(typeRaw) IN ('took', 'debt', 'debt_added', 'renewal', 'renew', 'sub_renew', 'sub_renewal', 'debt_renew') THEN amountIqd
                                    WHEN LOWER(typeRaw) IN ('gave', 'payment', 'deposit', 'pay') THEN -amountIqd
                                    ELSE 0.0
                                END
                            ), 0.0)
                            FROM `local_ledger_entries`
                            WHERE `accountId` = `local_accounts`.`id`
                        )),
                        `loanIqd` = MAX(0.0, (
                            SELECT COALESCE(SUM(
                                CASE 
                                    WHEN LOWER(typeRaw) IN ('took', 'debt', 'debt_added', 'renewal', 'renew', 'sub_renew', 'sub_renewal', 'debt_renew') THEN amountIqd
                                    WHEN LOWER(typeRaw) IN ('gave', 'payment', 'deposit', 'pay') THEN -amountIqd
                                    ELSE 0.0
                                END
                            ), 0.0)
                            FROM `local_ledger_entries`
                            WHERE `accountId` = `local_accounts`.`id`
                        ))
                    WHERE `id` IN (SELECT `survivor_id` FROM `_temp_acc_map` WHERE `old_id` != `survivor_id`)
                """.trimIndent())

                db.execSQL("DROP TABLE IF EXISTS `_temp_acc_map`")

                // 5. Normalise empty string external IDs to NULL before applying unique indices to prevent duplicate empty string constraint violations
                db.execSQL("UPDATE `local_accounts` SET `sourceExternalId` = NULL WHERE `sourceExternalId` = ''")
                db.execSQL("UPDATE `local_ledger_entries` SET `sourceExternalId` = NULL WHERE `sourceExternalId` = ''")

                // 6. Create unique indices
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_accounts_sourceExternalId` ON `local_accounts` (`sourceExternalId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_ledger_entries_accountId_sourceExternalId` ON `local_ledger_entries` (`accountId`, `sourceExternalId`)")

                // 7. Log audit trail for migration
                try {
                    val nowMs = System.currentTimeMillis()
                    db.execSQL("""
                        INSERT INTO `audit_log` (`action`, `entityType`, `entityId`, `summary`, `createdAt`)
                        VALUES ('SYSTEM_MIGRATION', 'DATABASE', 'MIGRATION_4_5', 'Deduplicated accounts and reassigned ledger entries safely during database migration v4->v5', $nowMs)
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `local_ledger_entries_new` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `sourceExternalId` TEXT,
                        `sourceBatchId` TEXT,
                        `typeRaw` TEXT NOT NULL,
                        `amountIqd` REAL NOT NULL,
                        `debtAfterIqd` REAL NOT NULL,
                        `note` TEXT,
                        `occurredAt` INTEGER NOT NULL,
                        `rawJson` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`accountId`) REFERENCES `local_accounts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `local_ledger_entries_new` (
                        `id`, `accountId`, `sourceExternalId`, `sourceBatchId`, `typeRaw`,
                        `amountIqd`, `debtAfterIqd`, `note`, `occurredAt`, `rawJson`, `createdAt`
                    )
                    SELECT `id`, `accountId`, `sourceExternalId`, `sourceBatchId`, `typeRaw`,
                           `amountIqd`, `debtAfterIqd`, `note`, `occurredAt`, `rawJson`, `createdAt`
                    FROM `local_ledger_entries`
                """.trimIndent())
                db.execSQL("DROP TABLE `local_ledger_entries`")
                db.execSQL("ALTER TABLE `local_ledger_entries_new` RENAME TO `local_ledger_entries`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_accountId` ON `local_ledger_entries` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_sourceBatchId` ON `local_ledger_entries` (`sourceBatchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_occurredAt` ON `local_ledger_entries` (`occurredAt`)")
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audit_log` ADD COLUMN `severity` TEXT NOT NULL DEFAULT 'INFO'")
                db.execSQL("ALTER TABLE `audit_log` ADD COLUMN `actor` TEXT NOT NULL DEFAULT 'system'")
                db.execSQL("ALTER TABLE `audit_log` ADD COLUMN `signature` TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_accounts_sourceBatchId` ON `local_accounts` (`sourceBatchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_createdAt` ON `sync_outbox` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_outbox_entityId` ON `sync_outbox` (`entityId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `local_ledger_entries_new` (
                        `id` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `sourceExternalId` TEXT,
                        `sourceBatchId` TEXT,
                        `typeRaw` TEXT NOT NULL,
                        `amountIqd` REAL NOT NULL,
                        `debtAfterIqd` REAL NOT NULL,
                        `note` TEXT,
                        `occurredAt` INTEGER NOT NULL,
                        `rawJson` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`accountId`) REFERENCES `local_accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `local_ledger_entries_new` (
                        `id`, `accountId`, `sourceExternalId`, `sourceBatchId`, `typeRaw`,
                        `amountIqd`, `debtAfterIqd`, `note`, `occurredAt`, `rawJson`, `createdAt`
                    )
                    SELECT `id`, `accountId`, `sourceExternalId`, `sourceBatchId`, `typeRaw`,
                           `amountIqd`, `debtAfterIqd`, `note`, `occurredAt`, `rawJson`, `createdAt`
                    FROM `local_ledger_entries`
                """.trimIndent())
                db.execSQL("DROP TABLE `local_ledger_entries`")
                db.execSQL("ALTER TABLE `local_ledger_entries_new` RENAME TO `local_ledger_entries`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_accountId` ON `local_ledger_entries` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_sourceBatchId` ON `local_ledger_entries` (`sourceBatchId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_occurredAt` ON `local_ledger_entries` (`occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_ledger_entries_sourceExternalId` ON `local_ledger_entries` (`sourceExternalId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_ledger_entries_accountId_sourceExternalId` ON `local_ledger_entries` (`accountId`, `sourceExternalId`)")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `audit_log` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'USER_ACTION'")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE `audit_log_new` (
                        `id` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `entityType` TEXT,
                        `entityId` TEXT,
                        `summary` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `metadataJsonMasked` TEXT,
                        `severity` TEXT NOT NULL,
                        `actor` TEXT NOT NULL,
                        `signature` TEXT,
                        `origin` TEXT NOT NULL DEFAULT 'USER_ACTION',
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                
                db.execSQL("""
                    INSERT INTO `audit_log_new` (
                        `id`, `action`, `entityType`, `entityId`, `summary`,
                        `createdAt`, `metadataJsonMasked`, `severity`, `actor`, `signature`, `origin`
                    )
                    SELECT CAST(`id` AS TEXT), `action`, `entityType`, `entityId`, `summary`,
                           `createdAt`, `metadataJsonMasked`, `severity`, `actor`, `signature`, `origin`
                    FROM `audit_log`
                """.trimIndent())
                
                db.execSQL("DROP TABLE `audit_log`")
                db.execSQL("ALTER TABLE `audit_log_new` RENAME TO `audit_log`")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `openingDebtIqd` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `openingAdvanceIqd` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `openingLoanIqd` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `stateSource` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `stateConfidence` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `local_accounts` ADD COLUMN `snapshotCapturedAt` INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_ledger_entries` ADD COLUMN `isSnapshotHistory` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `import_batches` ADD COLUMN `lastCommittedSubscriberIndex` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `import_batches` ADD COLUMN `lastCommittedTransactionIndex` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Backfill isSnapshotHistory = 1 for historical imported transactions
                db.execSQL("UPDATE `local_ledger_entries` SET `isSnapshotHistory` = 1 WHERE (`sourceBatchId` IS NOT NULL AND `sourceBatchId` != '') OR (`sourceExternalId` IS NOT NULL AND `sourceExternalId` != '')")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pending_external_operations` (
                        `businessTransactionId` TEXT NOT NULL,
                        `operationIntentId` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `operationType` TEXT NOT NULL,
                        `amountIqd` INTEGER NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`businessTransactionId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_external_operations_operationIntentId` ON `pending_external_operations` (`operationIntentId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_external_operations_businessTransactionId` ON `pending_external_operations` (`businessTransactionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_external_operations_accountId` ON `pending_external_operations` (`accountId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_external_operations_status` ON `pending_external_operations` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_external_operations_createdAt` ON `pending_external_operations` (`createdAt`)")
            }
        }

        private val INSTANCES = java.util.concurrent.ConcurrentHashMap<String, AppDatabase>()

        fun getDatabase(context: Context, passphrase: ByteArray, dbName: String = "earthlink_reseller_db"): AppDatabase {
            val cached = INSTANCES[dbName]
            if (cached != null && cached.isOpen) {
                return cached
            }
            return synchronized(this) {
                val cached2 = INSTANCES[dbName]
                if (cached2 != null && cached2.isOpen) {
                    return cached2
                }
                val dbFile = context.applicationContext.getDatabasePath(dbName)
                dbFile.parentFile?.mkdirs()
                val builder = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, dbName)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onCreate(db)
                            val now = System.currentTimeMillis()
                            db.execSQL("INSERT OR REPLACE INTO sync_metadata (key, value, updatedAt) VALUES ('${SyncMetadataDao.KEY_G4_LOCAL_GENERATION}', '${SyncMetadataDao.DEFAULT_GENERATION}', $now);")
                        }

                        override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.execSQL("PRAGMA foreign_keys = ON;")
                            val now = System.currentTimeMillis()
                            db.execSQL("INSERT OR IGNORE INTO sync_metadata (key, value, updatedAt) VALUES ('${SyncMetadataDao.KEY_G4_LOCAL_GENERATION}', '${SyncMetadataDao.DEFAULT_GENERATION}', $now);")
                        }
                    })
                val isRobolectric = try {
                    Class.forName("org.robolectric.Robolectric")
                    true
                } catch (e: Throwable) {
                    false
                }
                if (isRobolectric) {
                    builder.allowMainThreadQueries()
                    builder.setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                }
                if (passphrase.isNotEmpty()) {
                    try {
                        net.sqlcipher.database.SQLiteDatabase.loadLibs(context)
                        builder.openHelperFactory(net.sqlcipher.database.SupportFactory(passphrase, null, false))
                    } catch (e: Throwable) {
                        throw RuntimeException("Failed to load SQLCipher native libraries", e)
                    }
                }
                val instance = builder.build()
                INSTANCES[dbName] = instance
                if (dbName == "earthlink_reseller_db") {
                    INSTANCE = instance
                }
                instance
            }
        }

        fun closeAndRemoveInstance(dbName: String) {
            synchronized(this) {
                val db = INSTANCES.remove(dbName)
                if (db != null && db.isOpen) {
                    try {
                        db.close()
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        android.util.Log.e("AppDatabase", "Error closing database $dbName", e)
                    }
                }
                if (dbName == "earthlink_reseller_db") {
                    INSTANCE = null
                }
            }
        }

        fun closeDatabase() {
            synchronized(this) {
                INSTANCES.values.forEach { db ->
                    if (db.isOpen) {
                        try {
                            db.close()
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                            android.util.Log.e("AppDatabase", "Error closing database", e)
                        }
                    }
                }
                INSTANCES.clear()
                INSTANCE = null
            }
        }
    }
}
