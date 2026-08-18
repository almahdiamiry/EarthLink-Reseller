package com.example.core.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.room.withTransaction
import com.example.core.database.AppDatabase
import com.example.core.model.*
import com.example.domain.repository.UtowerImportPreview
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

data class ImportResult(
    val batchId: String,
    val success: Boolean,
    val subscribersFound: Int,
    val subscribersImported: Int,
    val subscribersMerged: Int,
    val subscribersNotesImported: Int,
    val nanoIpsImported: Int,
    val transactionsImported: Int,
    val transactionNotesImported: Int,
    val warnings: Int,
    val errors: Int,
    val errorMessage: String? = null,
    val failedFile: String? = null,
    val skippedSubscriberDetails: List<String> = emptyList(),
    val skippedTransactionDetails: List<String> = emptyList(),
    
    // Phase I explicit metrics
    val subscribersRead: Int = 0,
    val subscribersInserted: Int = 0,
    val subscribersSkipped: Int = 0,
    val subscribersFailed: Int = 0,
    
    val transactionsRead: Int = 0,
    val transactionsInserted: Int = 0,
    val transactionsMerged: Int = 0,
    val transactionsSkipped: Int = 0,
    val transactionsFailed: Int = 0
)

class UtowerImporter(
    private val context: Context,
    private val appDatabase: AppDatabase
) {
    private val moshi = Moshi.Builder().build()
    private val accountAdapter = moshi.adapter(LocalAccount::class.java)
    private val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)
    private val batchAdapter = moshi.adapter(ImportBatch::class.java)

    private val dateFormatsMap = ConcurrentHashMap<String, SimpleDateFormat>()

    private fun formatBghFull(ms: Long): String {
        val sdf = dateFormatsMap.getOrPut("bgh_full") {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Baghdad")
                isLenient = false
            }
        }
        synchronized(sdf) {
            return sdf.format(Date(ms))
        }
    }

    private fun parseDateString(strVal: String, fmt: String): Date? {
        val sdf = dateFormatsMap.getOrPut(fmt) {
            SimpleDateFormat(fmt, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Baghdad")
                isLenient = false
            }
        }
        synchronized(sdf) {
            return try { sdf.parse(strVal) } catch (_: Exception) { null }
        }
    }

    private fun parseBghDate(strVal: String?): Long? {
        if (strVal.isNullOrBlank() || strVal == "null") return null
        val formats = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )
        for (fmt in formats) {
            val d = parseDateString(strVal, fmt)
            if (d != null) return d.time
        }
        return strVal.toLongOrNull()
    }

    private fun deepMerge(target: JSONObject, source: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val sourceValue = source.get(key)
            val targetValue = target.opt(key)

            if (sourceValue is JSONObject && targetValue is JSONObject) {
                deepMerge(targetValue, sourceValue)
            } else {
                target.put(key, sourceValue)
            }
        }
    }

    private suspend fun extractTransactionsFromNode(
        sourceKey: String,
        json: JSONObject,
        fallbackSubKey: String? = null,
        onTxFound: suspend (String, JSONObject) -> Unit
    ) {
        val isSingleTx = json.has("amount") || json.has("amount_unit") || json.has("amount_iqd") ||
                json.has("type") || json.has("type_raw") || json.has("toWho") || json.has("subscriber_ref") ||
                json.has("date") || json.has("timeOfAction") || json.has("time") || json.has("timestamp_ms")

        if (isSingleTx) {
            val effectiveSub = if (!fallbackSubKey.isNullOrEmpty() &&
                !fallbackSubKey.equals("messagesofhistory", ignoreCase = true) &&
                !fallbackSubKey.equals("history", ignoreCase = true) &&
                !fallbackSubKey.equals("transactions", ignoreCase = true)
            ) {
                fallbackSubKey
            } else if (!sourceKey.equals("messagesofhistory", ignoreCase = true) &&
                !sourceKey.equals("history", ignoreCase = true) &&
                !sourceKey.equals("transactions", ignoreCase = true)
            ) {
                sourceKey
            } else {
                null
            }

            if (!json.has("toWho") && !effectiveSub.isNullOrEmpty()) {
                json.put("toWho", effectiveSub)
            }
            onTxFound(sourceKey, json)
        } else {
            val keys = json.keys()
            while (keys.hasNext()) {
                val childKey = keys.next()
                val childJson = json.optJSONObject(childKey) ?: continue
                val subRef = if (!fallbackSubKey.isNullOrEmpty() &&
                    !fallbackSubKey.equals("messagesofhistory", ignoreCase = true) &&
                    !fallbackSubKey.equals("history", ignoreCase = true) &&
                    !fallbackSubKey.equals("transactions", ignoreCase = true)
                ) {
                    fallbackSubKey
                } else if (!sourceKey.equals("messagesofhistory", ignoreCase = true) &&
                    !sourceKey.equals("history", ignoreCase = true) &&
                    !sourceKey.equals("transactions", ignoreCase = true)
                ) {
                    sourceKey
                } else if (!childKey.equals("messagesofhistory", ignoreCase = true) &&
                    !childKey.equals("history", ignoreCase = true) &&
                    !childKey.equals("transactions", ignoreCase = true)
                ) {
                    childKey
                } else {
                    null
                }
                extractTransactionsFromNode(childKey, childJson, subRef, onTxFound)
            }
        }
    }

    suspend fun importFromPreviewWithDecision(
        preview: UtowerImportPreview,
        fileName: String,
        fileHash: String,
        decision: RestoreMergeDecision,
        shouldReplace: Boolean = false
    ): ImportBatch = withContext(Dispatchers.IO) {
        if (!decision.isValidFor(fileHash, decision.selectedBaselineId)) {
            throw IllegalStateException("Import aborted: RestoreMergeDecision is invalidated, unapproved, or mismatched file hash.")
        }
        importFromPreview(preview, fileName, fileHash, shouldReplace)
    }

    suspend fun importFromPreview(
        preview: UtowerImportPreview,
        fileName: String,
        fileHash: String,
        shouldReplace: Boolean = false
    ): ImportBatch = withContext(Dispatchers.IO) {
        DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
            val existingBatch = appDatabase.importBatchDao().getByFileHash(fileHash)
            var batchId = existingBatch?.id ?: UUID.nameUUIDFromBytes(fileHash.toByteArray(Charsets.UTF_8)).toString()

            val session = ImportSession(
                appDatabase = appDatabase,
                batchId = batchId,
                shouldReplace = shouldReplace,
                accountAdapter = accountAdapter,
                ledgerAdapter = ledgerAdapter,
                formatBghFull = { ms -> formatBghFull(ms) },
                parseBghDate = { s -> parseBghDate(s) }
            )

            // Step 1: Pre-transaction parsing and validation outside database
            for (acc in preview.parsedSubscribers) {
                val subJson = try { JSONObject(acc.rawJson ?: "{}") } catch (_: Exception) { JSONObject() }
                if (!subJson.has("sourceKey") && !acc.sourceExternalId.isNullOrEmpty()) {
                    subJson.put("sourceKey", acc.sourceExternalId)
                }
                if (!subJson.has("name") && acc.displayName.isNotEmpty()) {
                    subJson.put("name", acc.displayName)
                }
                if (!subJson.has("username") && !acc.earthlinkUsername.isNullOrEmpty()) {
                    subJson.put("username", acc.earthlinkUsername)
                }
                if (!subJson.has("phone") && !acc.phone1.isNullOrEmpty()) {
                    subJson.put("phone", acc.phone1)
                }
                if (!subJson.has("debt_iqd")) {
                    subJson.put("debt_iqd", acc.debtIqd)
                }
                if (!subJson.has("price_iqd")) {
                    subJson.put("price_iqd", acc.currentPriceIqd)
                }
                val key = acc.sourceExternalId ?: acc.id
                session.subsFound++
                session.insertOrUpdateUser(key, subJson, acc.isLegacy, originalId = acc.id)
            }

            for (tx in preview.parsedTransactions) {
                val txJson = try { JSONObject(tx.rawJson ?: "{}") } catch (_: Exception) { JSONObject() }
                if (!txJson.has("toWho") && !tx.accountId.isNullOrEmpty()) {
                    txJson.put("toWho", tx.accountId)
                }
                if (!txJson.has("amount_iqd")) {
                    txJson.put("amount_iqd", tx.amountIqd)
                }
                if (!txJson.has("debt_after_iqd")) {
                    txJson.put("debt_after_iqd", tx.debtAfterIqd)
                }
                if (!txJson.has("type")) {
                    txJson.put("type", tx.typeRaw)
                }
                if (!txJson.has("date") && !txJson.has("timeOfAction") && !txJson.has("time") && !txJson.has("timestamp_ms") && !txJson.has("actualTimeMs")) {
                    if (tx.occurredAt > 0L) {
                        txJson.put("actualTimeMs", tx.occurredAt)
                    }
                }
                val key = tx.sourceExternalId ?: tx.id
                session.txsFound++
                session.processTransaction(key, txJson)
            }

            // Step 2: Atomic publishing via single Room transaction boundary
            var finalizedBatch: ImportBatch? = null
            appDatabase.withTransaction {
                val existingBatch = appDatabase.importBatchDao().getByFileHash(fileHash)
                if (existingBatch != null) {
                    batchId = existingBatch.id
                    session.batchId = batchId
                }

                if (shouldReplace) {
                    appDatabase.localLedgerEntryDao().deleteAll()
                    appDatabase.localAccountDao().deleteAll()
                    appDatabase.syncMetadataDao().incrementGeneration()
                }

                val existingAccounts = appDatabase.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
                val existingTxList = appDatabase.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
                session.init(existingAccounts, existingTxList)

                session.commitAll()

                val freshAccounts = appDatabase.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
                val totalImportedDebt = freshAccounts.sumOf { it.debtIqd }
                val totalOpeningDebt = freshAccounts.sumOf { it.openingDebtIqd }
                Log.i("UtowerImporter", "Reconciliation Report -> Total Current Debt: $totalImportedDebt, Total Opening Debt: $totalOpeningDebt, Diff: ${totalImportedDebt - totalOpeningDebt}")

                val warningsStr = preview.warnings.joinToString("; ")
                finalizedBatch = ImportBatch(
                    id = batchId,
                    fileName = fileName,
                    fileHash = fileHash,
                    accountsImported = session.subsImported,
                    transactionsImported = session.txImported,
                    totalDebtIqd = totalImportedDebt,
                    warningsJson = if (warningsStr.isNotEmpty()) warningsStr else null,
                    status = "completed",
                    createdAt = existingBatch?.createdAt ?: System.currentTimeMillis()
                )

                appDatabase.importBatchDao().insert(finalizedBatch!!)
                OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "import_batches", batchId, batchAdapter.toJson(finalizedBatch!!), importBatchId = batchId)
            }

            try {
                ((context.applicationContext as? com.example.EarthlinkApp)?.syncRepository as? SyncRepositoryImpl)?.remoteSyncCoordinator?.clearCache()
            } catch (_: Throwable) {}

            finalizedBatch!!
        }
    }

    suspend fun importFromFile(sourceFile: File, shouldReplace: Boolean = false): ImportResult = withContext(Dispatchers.IO) {
        DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
            val hash = calculateHash(sourceFile)
            val existingBatch = appDatabase.importBatchDao().getByFileHash(hash)
            var batchId = existingBatch?.id ?: UUID.nameUUIDFromBytes(hash.toByteArray(Charsets.UTF_8)).toString()
            val tempDir = File(context.cacheDir, "utower_import_${System.currentTimeMillis()}").apply { mkdirs() }
            var failedFile: String? = null

            val session = ImportSession(
                appDatabase = appDatabase,
                batchId = batchId,
                shouldReplace = shouldReplace,
                accountAdapter = accountAdapter,
                ledgerAdapter = ledgerAdapter,
                formatBghFull = { ms -> formatBghFull(ms) },
                parseBghDate = { s -> parseBghDate(s) }
            )

            try {
                // Step 1: Pre-transaction extraction and parsing outside any database transaction
                val dbFile = if (sourceFile.name.endsWith(".tgz") || sourceFile.name.endsWith(".tar.gz")) {
                    extractDatabaseFromTgz(sourceFile, tempDir)
                } else {
                    sourceFile
                }

                if (dbFile == null || !dbFile.exists()) {
                    throw Exception("Could not find uTower database in the provided file.")
                }
                failedFile = dbFile.name

                Log.d("UtowerImporter", "Found database at ${dbFile.absolutePath}")

                if (dbFile.name.endsWith(".json")) {
                    Log.d("UtowerImporter", "Processing as JSON file via true streaming JsonReader - PASS 1 (Subscribers)")
                    dbFile.inputStream().buffered().use { inputStream ->
                        JsonReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val topKey = reader.nextName()
                                    when (topKey.lowercase()) {
                                        "subscribers" -> {
                                            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                        val sub = readJsonObject(reader)
                                                        val raw = sub.optJSONObject("raw") ?: sub.optJSONObject("raw_data") ?: sub
                                                        val sourceKey = sub.optString("source_key").takeIf { it.isNotEmpty() }
                                                            ?: sub.optString("source_path").split("/").lastOrNull()
                                                            ?: UUID.randomUUID().toString()
                                                        session.subsFound++
                                                        session.insertOrUpdateUser(sourceKey, raw, false)
                                                    } else reader.skipValue()
                                                }
                                                reader.endArray()
                                            } else reader.skipValue()
                                        }
                                        "legacy_users" -> {
                                            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                        val sub = readJsonObject(reader)
                                                        val raw = sub.optJSONObject("raw") ?: sub.optJSONObject("raw_data") ?: sub
                                                        val sourceKey = sub.optString("source_key").takeIf { it.isNotEmpty() }
                                                            ?: sub.optString("source_path").split("/").lastOrNull()
                                                            ?: UUID.randomUUID().toString()
                                                        session.subsFound++
                                                        session.insertOrUpdateUser(sourceKey, raw, true)
                                                    } else reader.skipValue()
                                                }
                                                reader.endArray()
                                            } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                val legacyObj = readJsonObject(reader)
                                                val keys = legacyObj.keys()
                                                while (keys.hasNext()) {
                                                    val id = keys.next()
                                                    legacyObj.optJSONObject(id)?.let {
                                                        session.subsFound++
                                                        session.insertOrUpdateUser(id, it, true)
                                                    }
                                                }
                                            } else reader.skipValue()
                                        }
                                        "live_users", "utower_realtime_live_users" -> {
                                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                val liveObj = readJsonObject(reader)
                                                val keys = liveObj.keys()
                                                while (keys.hasNext()) {
                                                    val id = keys.next()
                                                    liveObj.optJSONObject(id)?.let {
                                                        session.subsFound++
                                                        session.insertOrUpdateUser(id, it, false)
                                                    }
                                                }
                                            } else reader.skipValue()
                                        }
                                        "users", "utower_realtime_legacy_users" -> {
                                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                val legacyObj = readJsonObject(reader)
                                                val keys = legacyObj.keys()
                                                while (keys.hasNext()) {
                                                    val id = keys.next()
                                                    legacyObj.optJSONObject(id)?.let {
                                                        session.subsFound++
                                                        session.insertOrUpdateUser(id, it, true)
                                                    }
                                                }
                                            } else reader.skipValue()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                        }
                    }

                    Log.d("UtowerImporter", "Processing as JSON file via true streaming JsonReader - PASS 2 (Transactions)")
                    dbFile.inputStream().buffered().use { inputStream ->
                        JsonReader(java.io.InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    val topKey = reader.nextName()
                                    when (topKey.lowercase()) {
                                        "transactions" -> {
                                            if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                                reader.beginArray()
                                                var idx = 0
                                                while (reader.hasNext()) {
                                                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                        val tx = readJsonObject(reader)
                                                        val raw = tx.optJSONObject("raw") ?: tx.optJSONObject("raw_data") ?: tx
                                                        val sourceKey = tx.optString("source_key").takeIf { it.isNotEmpty() }
                                                            ?: tx.optString("source_path").split("/").lastOrNull().takeIf { !it.isNullOrEmpty() }
                                                            ?: tx.optString("id").takeIf { it.isNotEmpty() }
                                                            ?: tx.optString("timeId").takeIf { it.isNotEmpty() }
                                                            ?: "tx_idx_$idx"
                                                        session.processTransaction(sourceKey, raw)
                                                    } else reader.skipValue()
                                                    idx++
                                                }
                                                reader.endArray()
                                            } else reader.skipValue()
                                        }
                                        "messagesofhistory", "history", "utower_realtime_messagesofhistory" -> {
                                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                val histObj = readJsonObject(reader)
                                                extractTransactionsFromNode("messagesOfHistory", histObj, null) { key, raw ->
                                                    session.processTransaction(key, raw)
                                                }
                                            } else reader.skipValue()
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                        }
                    }
                } else {
                    Log.d("UtowerImporter", "Processing as SQLite db via cursor streaming")
                    try {
                        dbFile.setReadable(true, false)
                        dbFile.setWritable(true, false)
                        dbFile.parentFile?.setWritable(true, false)
                        val db = try {
                            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                        } catch (_: Exception) {
                            try {
                                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
                            } catch (_: Exception) {
                                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
                            }
                        }
                        try {
                            var hasLiveUsers = false
                            try {
                                db.rawQuery("SELECT 1 FROM serverCache WHERE path LIKE '%live_users%' LIMIT 1", null).use { c ->
                                    if (c.moveToFirst()) {
                                        hasLiveUsers = true
                                    }
                                }
                            } catch (_: Exception) {}

                            // PASS 1: Users
                            db.rawQuery("SELECT path, value FROM serverCache", null).use { cursor ->
                                val pathIdx = cursor.getColumnIndex("path")
                                val valIdx = cursor.getColumnIndex("value")

                                while (cursor.moveToNext()) {
                                    val path = cursor.getString(pathIdx)?.trim('/') ?: ""
                                    val lowerPath = path.lowercase()
                                    if (!(lowerPath.contains("live_users") || lowerPath.contains("legacy_users") || (!hasLiveUsers && lowerPath.contains("users")))) {
                                        continue
                                    }
                                    val valueBlob = cursor.getBlob(valIdx) ?: continue

                                    try {
                                        val jsonStr = String(valueBlob, Charsets.UTF_8)
                                        var parsedValue: Any = jsonStr
                                        while (parsedValue is String) {
                                            val trimmed = (parsedValue as String).trim()
                                            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                                                try {
                                                    parsedValue = org.json.JSONTokener(trimmed).nextValue()
                                                } catch (_: Exception) {
                                                    break
                                                }
                                            } else {
                                                break
                                            }
                                        }
                                        if (parsedValue == JSONObject.NULL) continue

                                        processSqliteCacheNode(path, parsedValue, session, hasLiveUsers)
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        throw Exception("Failed to parse or process SQLite cache node at path '$path': ${e.message}", e)
                                    }
                                }
                            }

                            // PASS 2: Transactions
                            db.rawQuery("SELECT path, value FROM serverCache", null).use { cursor ->
                                val pathIdx = cursor.getColumnIndex("path")
                                val valIdx = cursor.getColumnIndex("value")

                                while (cursor.moveToNext()) {
                                    val path = cursor.getString(pathIdx)?.trim('/') ?: ""
                                    val lowerPath = path.lowercase()
                                    if (lowerPath.contains("live_users") || lowerPath.contains("legacy_users") || (!hasLiveUsers && lowerPath.contains("users"))) {
                                        continue
                                    }
                                    val valueBlob = cursor.getBlob(valIdx) ?: continue

                                    try {
                                        val jsonStr = String(valueBlob, Charsets.UTF_8)
                                        var parsedValue: Any = jsonStr
                                        while (parsedValue is String) {
                                            val trimmed = (parsedValue as String).trim()
                                            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                                                try {
                                                    parsedValue = org.json.JSONTokener(trimmed).nextValue()
                                                } catch (_: Exception) {
                                                    break
                                                }
                                            } else {
                                                break
                                            }
                                        }
                                        if (parsedValue == JSONObject.NULL) continue

                                        processSqliteCacheNode(path, parsedValue, session, hasLiveUsers)
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        throw Exception("Failed to parse or process SQLite cache node at path '$path': ${e.message}", e)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w("UtowerImporter", "Querying serverCache failed for ${dbFile.name}", e)
                            throw Exception("Querying serverCache failed: ${e.message}", e)
                        } finally {
                            db.close()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w("UtowerImporter", "Failed to open SQLite database ${dbFile.name}", e)
                        throw Exception("Failed to open uTower SQLite database: ${e.message}", e)
                    }
                }

                // Step 2: Atomic publishing via single Room transaction boundary
                appDatabase.withTransaction {
                    val existingBatch = appDatabase.importBatchDao().getByFileHash(hash)
                    if (existingBatch != null) {
                        Log.i("UtowerImporter", "File already imported previously (Batch ID: ${existingBatch.id}). Reusing batch ID and performing smart diff-merge.")
                        batchId = existingBatch.id
                        session.batchId = batchId
                    }

                    if (shouldReplace) {
                        appDatabase.localLedgerEntryDao().deleteAll()
                        appDatabase.localAccountDao().deleteAll()
                        appDatabase.syncMetadataDao().incrementGeneration()
                    }

                    val existingAccounts = appDatabase.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
                    val existingTxList = appDatabase.localLedgerEntryDao().getAllOneShot(limit = Int.MAX_VALUE)
                    session.init(existingAccounts, existingTxList)

                    session.commitAll()

                    if (shouldReplace) {
                        val oldBatches = appDatabase.importBatchDao().getAllOneShot().filter { it.id != batchId }
                        val oldBatchIds = oldBatches.map { it.id }
                        if (oldBatchIds.isNotEmpty()) {
                            OutboxManager.deleteWithTombstoneBatch(appDatabase.syncOutboxDao(), "import_batches", oldBatchIds, "{}")
                            appDatabase.importBatchDao().deleteAllExcept(batchId)
                        }
                    }

                    val freshAccounts = appDatabase.localAccountDao().getAllOneShot(limit = Int.MAX_VALUE)
                    val totalImportedDebt = freshAccounts.sumOf { it.debtIqd }
                    val totalOpeningDebt = freshAccounts.sumOf { it.openingDebtIqd }
                    Log.i("UtowerImporter", "Reconciliation Report -> Total Current Debt: $totalImportedDebt, Total Opening Debt: $totalOpeningDebt, Diff: ${totalImportedDebt - totalOpeningDebt}")

                    val finalizedBatch = ImportBatch(
                        id = batchId,
                        fileName = sourceFile.name,
                        fileHash = hash,
                        accountsImported = session.subsImported,
                        transactionsImported = session.txImported,
                        totalDebtIqd = totalImportedDebt,
                        status = "completed",
                        createdAt = existingBatch?.createdAt ?: System.currentTimeMillis()
                    )
                    appDatabase.importBatchDao().insert(finalizedBatch)
                    OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "import_batches", batchId, batchAdapter.toJson(finalizedBatch), importBatchId = batchId)
                }

                try {
                    ((context.applicationContext as? com.example.EarthlinkApp)?.syncRepository as? SyncRepositoryImpl)?.remoteSyncCoordinator?.clearCache()
                } catch (_: Throwable) {}

                ImportResult(
                    batchId = batchId,
                    success = true,
                    subscribersFound = session.subsFound,
                    subscribersImported = session.subsImported,
                    subscribersMerged = session.subscribersMerged,
                    subscribersNotesImported = session.subsNotesImported,
                    nanoIpsImported = session.nanoIpsImported,
                    transactionsImported = session.txImported,
                    transactionNotesImported = session.txNotesImported,
                    warnings = session.warnings,
                    errors = session.errors,
                    subscribersRead = session.subscribersRead,
                    subscribersInserted = session.subscribersInserted,
                    subscribersSkipped = session.subscribersSkipped,
                    subscribersFailed = session.subscribersFailed,
                    transactionsRead = session.transactionsRead,
                    transactionsInserted = session.transactionsInserted,
                    transactionsMerged = session.transactionsMerged,
                    transactionsSkipped = session.transactionsSkipped,
                    transactionsFailed = session.transactionsFailed
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                Log.e("UtowerImporter", "Import failed: ${e.message}", e)
                ImportResult(
                    batchId = batchId,
                    success = false,
                    subscribersFound = session.subsFound,
                    subscribersImported = session.subsImported,
                    subscribersMerged = session.subscribersMerged,
                    subscribersNotesImported = session.subsNotesImported,
                    nanoIpsImported = session.nanoIpsImported,
                    transactionsImported = session.txImported,
                    transactionNotesImported = session.txNotesImported,
                    warnings = session.warnings,
                    errors = session.errors + 1,
                    errorMessage = e.message ?: "Unknown error",
                    failedFile = failedFile,
                    subscribersRead = session.subscribersRead,
                    subscribersInserted = session.subscribersInserted,
                    subscribersSkipped = session.subscribersSkipped,
                    subscribersFailed = session.subscribersFailed,
                    transactionsRead = session.transactionsRead,
                    transactionsInserted = session.transactionsInserted,
                    transactionsMerged = session.transactionsMerged,
                    transactionsSkipped = session.transactionsSkipped,
                    transactionsFailed = session.transactionsFailed
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    private fun extractDatabaseFromTgz(tgzFile: File, outDir: File): File? {
        val extractedFiles = mutableListOf<File>()
        try {
            FileInputStream(tgzFile).use { fis ->
                GZIPInputStream(fis).use { gis ->
                    TarArchiveInputStream(gis).use { tarIn ->
                        var entry: TarArchiveEntry?
                        while (tarIn.nextTarEntry.also { entry = it } != null) {
                            if (entry!!.isDirectory) continue
                            val name = entry!!.name
                            val isTargetFile = name.endsWith(".db") || name.endsWith(".json") ||
                                    name.contains("serverCache") || name.contains("utower") ||
                                    name.contains(".firebaseio")
                            if (isTargetFile) {
                                val isFirebaseDb = name.contains(".firebaseio.com") || name.contains("serverCache")
                                val safeName = if (isFirebaseDb) {
                                    when {
                                        name.endsWith("-wal") -> "utower_cache.db-wal"
                                        name.endsWith("-shm") -> "utower_cache.db-shm"
                                        else -> "utower_cache.db"
                                    }
                                } else {
                                    name.replace('/', '_').replace('\\', '_')
                                }
                                val outFile = File(outDir, safeName)
                                outFile.outputStream().use { out -> tarIn.copyTo(out) }
                                outFile.setReadable(true, false)
                                outFile.setWritable(true, false)
                                if (!name.endsWith("-shm") && !name.endsWith("-wal")) {
                                    extractedFiles.add(outFile)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("UtowerImporter", "Failed to extract tgz", e)
        }
        return extractedFiles
            .filter { it.exists() && it.length() > 0 && !it.name.contains("google_app_measurement") && !it.name.contains("app_measurement") }
            .maxByOrNull { file ->
                val lowerName = file.name.lowercase()
                val weight = when {
                    lowerName.contains(".firebaseio") || lowerName.contains("servercache") -> 1_000_000_000_000_000L
                    lowerName.contains("utower") || lowerName.contains("subscribers") || lowerName.endsWith(".json") -> 1_000_000_000_000L
                    lowerName.endsWith(".db") -> 100_000_000_000L
                    else -> 0L
                }
                weight + file.length()
            }
    }

    private fun calculateHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readJsonObject(reader: JsonReader): JSONObject {
        val json = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> json.put(name, readJsonObject(reader))
                JsonToken.BEGIN_ARRAY -> json.put(name, readJsonArray(reader))
                JsonToken.BOOLEAN -> json.put(name, reader.nextBoolean())
                JsonToken.NULL -> {
                    reader.nextNull()
                    json.put(name, JSONObject.NULL)
                }
                JsonToken.NUMBER -> {
                    val numStr = reader.nextString()
                    if (numStr.contains(".") || numStr.contains("e", ignoreCase = true)) {
                        json.put(name, numStr.toDoubleOrNull() ?: numStr)
                    } else {
                        json.put(name, numStr.toLongOrNull() ?: numStr)
                    }
                }
                JsonToken.STRING -> json.put(name, reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return json
    }

    private fun readJsonArray(reader: JsonReader): org.json.JSONArray {
        val array = org.json.JSONArray()
        reader.beginArray()
        while (reader.hasNext()) {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> array.put(readJsonObject(reader))
                JsonToken.BEGIN_ARRAY -> array.put(readJsonArray(reader))
                JsonToken.BOOLEAN -> array.put(reader.nextBoolean())
                JsonToken.NULL -> reader.nextNull()
                JsonToken.NUMBER -> {
                    val numStr = reader.nextString()
                    if (numStr.contains(".") || numStr.contains("e", ignoreCase = true)) {
                        array.put(numStr.toDoubleOrNull() ?: numStr)
                    } else {
                        array.put(numStr.toLongOrNull() ?: numStr)
                    }
                }
                JsonToken.STRING -> array.put(reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endArray()
        return array
    }

    private suspend fun processSqliteCacheNode(
        path: String,
        node: Any,
        session: ImportSession,
        hasLiveUsers: Boolean = false
    ) {
        val lowerPath = path.lowercase()
        if (node is JSONObject) {
            when {
                lowerPath.contains("live_users") -> {
                    val parts = path.split("/").filter { it.isNotEmpty() }
                    val liveIdx = parts.indexOfFirst { it.equals("live_users", ignoreCase = true) || it.equals("utower_realtime_live_users", ignoreCase = true) }
                    if (liveIdx != -1 && liveIdx + 1 < parts.size) {
                        val subId = parts[liveIdx + 1]
                        if (liveIdx + 2 == parts.size) {
                            session.subsFound++
                            session.insertOrUpdateUser(subId, node, false)
                        } else {
                            val wrapper = JSONObject()
                            var current = wrapper
                            for (i in (liveIdx + 2) until parts.size - 1) {
                                val nextObj = JSONObject()
                                current.put(parts[i], nextObj)
                                current = nextObj
                            }
                            current.put(parts.last(), node)
                            session.subsFound++
                            session.insertOrUpdateUser(subId, wrapper, false)
                        }
                    } else {
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            node.optJSONObject(key)?.let { userObj ->
                                session.subsFound++
                                session.insertOrUpdateUser(key, userObj, false)
                            }
                        }
                    }
                }
                lowerPath.contains("legacy_users") || (!hasLiveUsers && lowerPath.contains("users")) -> {
                    val parts = path.split("/").filter { it.isNotEmpty() }
                    val legIdx = parts.indexOfFirst { it.equals("legacy_users", ignoreCase = true) || it.equals("users", ignoreCase = true) || it.equals("utower_realtime_legacy_users", ignoreCase = true) }
                    if (legIdx != -1 && legIdx + 1 < parts.size) {
                        val subId = parts[legIdx + 1]
                        if (legIdx + 2 == parts.size) {
                            session.subsFound++
                            session.insertOrUpdateUser(subId, node, true)
                        } else {
                            val wrapper = JSONObject()
                            var current = wrapper
                            for (i in (legIdx + 2) until parts.size - 1) {
                                val nextObj = JSONObject()
                                current.put(parts[i], nextObj)
                                current = nextObj
                            }
                            current.put(parts.last(), node)
                            session.subsFound++
                            session.insertOrUpdateUser(subId, wrapper, true)
                        }
                    } else {
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            node.optJSONObject(key)?.let { userObj ->
                                session.subsFound++
                                session.insertOrUpdateUser(key, userObj, true)
                            }
                        }
                    }
                }
                lowerPath.contains("messagesofhistory") || lowerPath.contains("history") || lowerPath.contains("transactions") -> {
                    val parts = path.split("/").filter { it.isNotEmpty() }
                    var fallbackSubId: String? = null
                    val histIdx = parts.indexOfFirst {
                        it.equals("messagesofhistory", ignoreCase = true) ||
                        it.equals("history", ignoreCase = true) ||
                        it.equals("transactions", ignoreCase = true)
                    }
                    if (histIdx != -1 && histIdx + 1 < parts.size) {
                        val candidate = parts[histIdx + 1]
                        if (!candidate.equals("messagesofhistory", ignoreCase = true) && !candidate.equals("history", ignoreCase = true)) {
                            fallbackSubId = candidate
                        }
                    }
                    val leafKey = parts.lastOrNull() ?: "messagesOfHistory"
                    extractTransactionsFromNode(leafKey, node, fallbackSubId) { key, raw ->
                        session.processTransaction(key, raw)
                    }
                }
                else -> {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val child = node.opt(k) ?: continue
                        val childPath = if (path.isEmpty()) k else "$path/$k"
                        processSqliteCacheNode(childPath, child, session, hasLiveUsers)
                    }
                }
            }
        }
    }
}

private class ImportSession(
    val appDatabase: AppDatabase,
    var batchId: String,
    val shouldReplace: Boolean,
    val accountAdapter: com.squareup.moshi.JsonAdapter<LocalAccount>,
    val ledgerAdapter: com.squareup.moshi.JsonAdapter<LocalLedgerEntry>,
    val formatBghFull: (Long) -> String,
    val parseBghDate: (String?) -> Long?
) {
    var subsFound = 0
    var subsImported = 0
    var subsMerged = 0
    var subsNotesImported = 0
    var nanoIpsImported = 0
    var txImported = 0
    var txNotesImported = 0
    var txsFound = 0
    var warnings = 0
    var errors = 0

    // Phase I explicit metrics
    var subscribersRead = 0
    var subscribersInserted = 0
    var subscribersMerged = 0
    var subscribersSkipped = 0
    var subscribersFailed = 0

    var transactionsRead = 0
    var transactionsInserted = 0
    var transactionsMerged = 0
    var transactionsSkipped = 0
    var transactionsFailed = 0

    val touchedAccountIds = mutableSetOf<String>()
    val touchedTxIds = mutableSetOf<String>()
    val accountResetDates = mutableMapOf<String, Long>()

    val parsedSubs = mutableListOf<ParsedSub>()
    val parsedTxs = mutableListOf<ParsedTx>()

    data class ParsedSub(val sourceKey: String, val json: JSONObject, val isLegacy: Boolean, val originalId: String? = null)
    data class ParsedTx(val sourceKey: String, val json: JSONObject)

    val subscriberMap = mutableMapOf<String, String>()
    val subscriberByUsername = mutableMapOf<String, String>()
    val subscriberByPhone = mutableMapOf<String, String>()
    val subscriberByName = mutableMapOf<String, String>()
    val phoneCounts = mutableMapOf<String, Int>()
    val nameCounts = mutableMapOf<String, Int>()

    lateinit var existingAccounts: List<LocalAccount>
    lateinit var accountsById: MutableMap<String, LocalAccount>
    lateinit var accountsByUsername: MutableMap<String, LocalAccount>
    lateinit var accountsByPhone: MutableMap<String, LocalAccount>
    lateinit var accountsBySourceId: MutableMap<String, LocalAccount>
    lateinit var accountsByName: MutableMap<String, LocalAccount>

    lateinit var existingTxList: List<LocalLedgerEntry>
    lateinit var existingTxBySourceExtId: MutableMap<String, LocalLedgerEntry>
    lateinit var existingTxByMatch: MutableMap<String, LocalLedgerEntry>

    fun init(existingAccs: List<LocalAccount>, existingTxs: List<LocalLedgerEntry>) {
        existingAccounts = existingAccs
        accountsById = existingAccounts.associateBy { it.id }.toMutableMap()
        accountsByUsername = existingAccounts.filter { !it.earthlinkUsername.isNullOrEmpty() }.associateBy { it.earthlinkUsername!! }.toMutableMap()
        accountsByPhone = existingAccounts.filter { !it.phone1.isNullOrEmpty() }.associateBy { it.phone1!! }.toMutableMap()
        accountsBySourceId = existingAccounts.filter { !it.sourceExternalId.isNullOrEmpty() }.associateBy { it.sourceExternalId!! }.toMutableMap()
        accountsByName = existingAccounts.filter { !it.displayName.isEmpty() }.associateBy { it.displayName }.toMutableMap()

        existingTxList = existingTxs
        existingTxBySourceExtId = existingTxList
            .filter { !it.sourceExternalId.isNullOrEmpty() }
            .associateBy { TransactionDeduplicator.buildExtIdKey(it.accountId, it.sourceExternalId!!) }
            .toMutableMap()
        existingTxByMatch = existingTxList
            .associateBy { TransactionDeduplicator.buildMatchKey(it.accountId, it.occurredAt, it.amountIqd, it.typeRaw, it.note) }
            .toMutableMap()
    }

    fun insertOrUpdateUser(sourceKey: String, json: JSONObject, isLegacy: Boolean, originalId: String? = null) {
        parsedSubs.add(ParsedSub(sourceKey, json, isLegacy, originalId))
    }

    suspend fun insertOrUpdateUserInternal(sourceKey: String, json: JSONObject, isLegacy: Boolean, originalId: String? = null) {
        subscribersRead++
        try {
            val liveObj = json.optJSONObject("live") ?: JSONObject()
            val utowerObj = json.optJSONObject("utower") ?: JSONObject()

            var earthlinkUsername = (if (isLegacy) {
                json.optString("userName", json.optString("username", json.optString("earthlink_username")))
            } else {
                liveObj.optString("username", liveObj.optString("userName", json.optString("userName", json.optString("username", json.optString("earthlink_username")))))
            })?.trim()
            if (earthlinkUsername.isNullOrEmpty() || earthlinkUsername == "null") {
                earthlinkUsername = json.optString("earthlink_username", json.optString("username", json.optString("userName")))?.trim()
            }

            var phone1 = (if (isLegacy) {
                json.optString("phoneNumber", json.optString("phone", json.optString("phone1")))
            } else {
                (liveObj.optString("phone", liveObj.optString("phoneNumber", json.optString("phoneNumber", json.optString("phone", json.optString("phone1")))))
                    ?: utowerObj.optString("phoneNumber"))
            })?.trim()
            if (phone1.isNullOrEmpty() || phone1 == "null") {
                phone1 = json.optString("phone1", json.optString("phone", json.optString("phoneNumber")))?.trim()
            }

            var name = (if (isLegacy) {
                json.optString("name", json.optString("display_name"))
            } else {
                liveObj.optString("name", json.optString("name", json.optString("display_name", utowerObj.optString("name"))))
            })?.trim()
            if (name.isNullOrEmpty() || name == "null") {
                name = json.optString("display_name", json.optString("name"))?.trim()
            }

            val existing: LocalAccount? = SubscriberMatcher.matchSubscriber(
                candidates = accountsById.values,
                extId = sourceKey,
                username = earthlinkUsername,
                phone = phone1,
                name = name
            )

            val debtKeys = listOf("totalDebit", "debts", "debt", "remindPrice", "totalPrice", "debt_unit")
            val priceKeys = listOf("currentPrice", "price", "current_price_unit")

            val debtUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(json, keys = debtKeys) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, json, keys = debtKeys)

            val directDebtIqd = com.example.core.ledger.MoneyParser.parseAmount(json, utowerObj, keys = listOf("debt_iqd"))
            val directPriceIqd = com.example.core.ledger.MoneyParser.parseAmount(json, utowerObj, keys = listOf("price_iqd", "current_price_iqd"))
            val priceUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(json, keys = priceKeys) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, json, keys = priceKeys)

            val directLoanIqd = com.example.core.ledger.MoneyParser.parseAmount(json, utowerObj, keys = listOf("loan_iqd", "loanIqd"))
            val loanUnit = if (isLegacy) com.example.core.ledger.MoneyParser.parseAmount(json, keys = listOf("loan")) else com.example.core.ledger.MoneyParser.parseAmount(utowerObj, json, keys = listOf("loan"))

            var debtIqd = if (directDebtIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directDebtIqd) else com.example.core.ledger.MoneyParser.parseUtowerAmount(debtUnit)
            val priceIqd = if (directPriceIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directPriceIqd) else com.example.core.ledger.MoneyParser.parseUtowerAmount(priceUnit)
            val loanIqdVal = if (directLoanIqd != null) com.example.core.ledger.MoneyParser.parseRawIqd(directLoanIqd) else if (loanUnit != null) com.example.core.ledger.MoneyParser.parseUtowerAmount(loanUnit) else 0.0

            // B2: Check lastDebtResetDate and resolve authoritative debt from post-reset transactions if present
            var resolvedDebtFromHistory: Double? = null
            val lastDebtResetStr = utowerObj.optString("lastDebtResetDate", json.optString("lastDebtResetDate", liveObj.optString("lastDebtResetDate"))).takeIf { !it.isNullOrBlank() && it != "null" }
            val resetMs = parseBghDate(lastDebtResetStr)
            if (resetMs != null && resetMs > 0L) {
                val txsArray = json.optJSONArray("transactions") ?: json.optJSONArray("operations")
                var latestTxMs = -1L

                if (txsArray != null) {
                    for (i in 0 until txsArray.length()) {
                        val txObj = txsArray.optJSONObject(i) ?: continue
                        val txTimeStr = txObj.optString("date", txObj.optString("createdAt", txObj.optString("timestamp")))
                        val txMs = txObj.optLong("actualTimeMs", 0L).takeIf { it > 0L } ?: (parseBghDate(txTimeStr) ?: 0L)
                        if (txMs > resetMs && txMs > latestTxMs) {
                            val totalDebitAfter = com.example.core.ledger.MoneyParser.parseAmount(txObj, keys = listOf("totalDebitAfter", "total_debit_after", "debtAfter", "debt_after", "debt_after_iqd"))
                            if (totalDebitAfter != null) {
                                latestTxMs = txMs
                                resolvedDebtFromHistory = if (txObj.has("debt_after_iqd")) com.example.core.ledger.MoneyParser.parseRawIqd(totalDebitAfter) else com.example.core.ledger.MoneyParser.parseUtowerAmount(totalDebitAfter)
                            }
                        }
                    }
                }
                if (resolvedDebtFromHistory != null) {
                    debtIqd = resolvedDebtFromHistory!!
                }
            }

            val resolvedStateSource = if (resolvedDebtFromHistory != null) "UTOWER_SNAPSHOT_RESOLVED" else "UTOWER_CURRENT_STATE"

            val nanoIp = if (isLegacy) (json.optString("nanoIp", json.optString("nano_ip"))) else (utowerObj.optString("nanoIp").takeIf { !it.isNullOrBlank() } ?: json.optString("nanoIp", json.optString("nano_ip")))
            if (!nanoIp.isNullOrBlank() && nanoIp != "null") {
                nanoIpsImported++
            }

            val note = if (isLegacy) json.optString("note") else (utowerObj.optString("note").takeIf { !it.isNullOrBlank() } ?: json.optString("note").takeIf { !it.isNullOrBlank() } ?: liveObj.optString("note"))
            if (!note.isNullOrBlank() && note != "null") {
                subsNotesImported++
            }

            val endMs = if (isLegacy) (json.optLong("end", 0L).takeIf { it > 0L } ?: json.optLong("subscription_end_ms", 0L)) else (liveObj.optLong("end", 0L).takeIf { it > 0L } ?: json.optLong("subscription_end_ms", 0L))
            val expiresAt = if (endMs > 0) formatBghFull(endMs) else null

            val finalId = if (existing != null) {
                subsMerged++
                subscribersMerged++
                val targetDebt = debtIqd
                val targetAdvance = 0.0 // B2: Never derive openingAdvanceIqd from history
                val targetLoan = loanIqdVal
                val updated = existing.copy(
                    displayName = name?.takeIf { it.isNotBlank() } ?: existing.displayName,
                    earthlinkUsername = earthlinkUsername?.takeIf { it.isNotBlank() && it != "null" } ?: existing.earthlinkUsername,
                    phone1 = phone1?.takeIf { it.isNotBlank() && it != "null" } ?: existing.phone1,
                    debtIqd = targetDebt,
                    advanceIqd = targetAdvance,
                    loanIqd = targetLoan,
                    openingDebtIqd = debtIqd,
                    openingAdvanceIqd = targetAdvance,
                    openingLoanIqd = targetLoan,
                    stateSource = resolvedStateSource,
                    stateConfidence = "AUTHORITATIVE",
                    snapshotCapturedAt = System.currentTimeMillis(),
                    currentPriceIqd = if (existing.currentPriceIqd == 0.0) priceIqd else existing.currentPriceIqd,
                    nanoIp = existing.nanoIp.takeIf { !it.isNullOrBlank() } ?: nanoIp.takeIf { it != "null" },
                    note = existing.note.takeIf { !it.isNullOrBlank() } ?: note.takeIf { it != "null" },
                    expiresAt = existing.expiresAt.takeIf { !it.isNullOrBlank() } ?: expiresAt,
                    updatedAt = System.currentTimeMillis(),
                    isLegacy = existing.isLegacy && isLegacy
                )
                appDatabase.localAccountDao().update(updated)

                accountsById[existing.id] = updated
                accountsBySourceId[sourceKey] = updated
                updated.sourceExternalId?.let { accountsBySourceId[it] = updated }
                updated.earthlinkUsername?.let { accountsByUsername[it] = updated }
                updated.phone1?.let { accountsByPhone[it] = updated }

                OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "local_accounts", existing.id, accountAdapter.toJson(updated), importBatchId = batchId)
                existing.id
            } else {
                val newId = UUID.randomUUID().toString()

                val newAcc = LocalAccount(
                    id = newId,
                    sourceExternalId = sourceKey,
                    sourceBatchId = batchId,
                    displayName = name ?: "",
                    earthlinkUsername = if (earthlinkUsername == "null") null else earthlinkUsername,
                    phone1 = if (phone1 == "null") null else phone1,
                    phone2 = utowerObj.optString("phoneNumber2").takeIf { it != "null" },
                    packageName = (if (isLegacy) json.optString("packageName") else liveObj.optString("profileName")).takeIf { !it.isNullOrBlank() && it != "null" },
                    currentPriceIqd = priceIqd,
                    debtIqd = debtIqd,
                    loanIqd = loanIqdVal,
                    advanceIqd = 0.0, // B2: Never derive openingAdvanceIqd from history
                    openingDebtIqd = debtIqd,
                    openingAdvanceIqd = 0.0,
                    openingLoanIqd = loanIqdVal,
                    stateSource = resolvedStateSource,
                    stateConfidence = "AUTHORITATIVE",
                    snapshotCapturedAt = System.currentTimeMillis(),
                    towerName = utowerObj.optString("boardName").takeIf { it != "null" },
                    nanoIp = nanoIp.takeIf { it != "null" },
                    note = note.takeIf { it != "null" },
                    expiresAt = expiresAt,
                    rawJson = json.toString(),
                    isLegacy = isLegacy,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val rowId = appDatabase.localAccountDao().insert(newAcc)
                if (rowId <= 0L) {
                    subsMerged++
                    subscribersMerged++
                    val resolvedAcc = (if (sourceKey.isNotEmpty()) appDatabase.localAccountDao().findBySourceExternalId(sourceKey) else null)
                        ?: accountsBySourceId[sourceKey]
                        ?: (if (!earthlinkUsername.isNullOrEmpty() && earthlinkUsername != "null") accountsByUsername[earthlinkUsername] else null)
                        ?: (if (!phone1.isNullOrEmpty() && phone1 != "null") accountsByPhone[phone1] else null)
                        ?: (if (!name.isNullOrEmpty() && name != "null") accountsByName[name] else null)
                        ?: accountsById[newId]

                    val resolvedId = resolvedAcc?.id ?: newId
                    if (resolvedAcc != null) {
                        accountsById[resolvedId] = resolvedAcc
                        accountsBySourceId[sourceKey] = resolvedAcc
                        resolvedAcc.sourceExternalId?.let { accountsBySourceId[it] = resolvedAcc }
                        resolvedAcc.earthlinkUsername?.let { accountsByUsername[it] = resolvedAcc }
                        resolvedAcc.phone1?.let { accountsByPhone[it] = resolvedAcc }
                    }
                    resolvedId
                } else {
                    subsImported++
                    subscribersInserted++
                    accountsById[newAcc.id] = newAcc
                    accountsBySourceId[sourceKey] = newAcc
                    newAcc.sourceExternalId?.let { accountsBySourceId[it] = newAcc }
                    newAcc.earthlinkUsername?.let { accountsByUsername[it] = newAcc }
                    newAcc.phone1?.let { accountsByPhone[it] = newAcc }

                    OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "local_accounts", newId, accountAdapter.toJson(newAcc), importBatchId = batchId)
                    newId
                }
            }

            subscriberMap[sourceKey] = finalId
            touchedAccountIds.add(finalId)
            if (!originalId.isNullOrEmpty()) {
                subscriberMap[originalId] = finalId
                accountsById[finalId]?.let { accountsBySourceId[originalId] = it }
            }

            val oldUserAddTimeId = utowerObj.optString("oldUserAddTimeId").takeIf { !it.isNullOrBlank() && it != "null" }
            if (!oldUserAddTimeId.isNullOrEmpty()) {
                subscriberMap[oldUserAddTimeId] = finalId
                accountsById[finalId]?.let { accountsBySourceId[oldUserAddTimeId] = it }
            }
            val addTimeId = json.optString("addTimeId").takeIf { !it.isNullOrBlank() && it != "null" }
            if (!addTimeId.isNullOrEmpty()) {
                subscriberMap[addTimeId] = finalId
                accountsById[finalId]?.let { accountsBySourceId[addTimeId] = it }
            }

            if (resetMs != null && resetMs > 0L) {
                accountResetDates[finalId] = resetMs
            }

            if (!earthlinkUsername.isNullOrEmpty() && earthlinkUsername != "null") {
                subscriberByUsername[earthlinkUsername] = finalId
            }
            if (!phone1.isNullOrEmpty() && phone1 != "null") {
                phoneCounts[phone1] = (phoneCounts[phone1] ?: 0) + 1
                subscriberByPhone[phone1] = finalId
            }
            if (!name.isNullOrEmpty() && name != "null") {
                nameCounts[name] = (nameCounts[name] ?: 0) + 1
                subscriberByName[name] = finalId
            }

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            subscribersFailed++
            Log.e("UtowerImporter", "Error processing subscriber $sourceKey", e)
            throw Exception("Error processing subscriber $sourceKey: ${e.message}", e)
        }
    }

    fun processTransaction(sourceKey: String, txJson: JSONObject) {
        parsedTxs.add(ParsedTx(sourceKey, txJson))
    }

    suspend fun processTransactionInternal(sourceKey: String, txJson: JSONObject) {
        transactionsRead++
        try {
            txsFound++
            val directAmountIqd = com.example.core.ledger.MoneyParser.parseAmount(txJson, keys = listOf("amount_iqd"))
            val amountUnit = com.example.core.ledger.MoneyParser.parseAmount(txJson, keys = listOf("amount", "amount_unit"))
            val amountIqd = if (directAmountIqd != null) {
                com.example.core.ledger.MoneyParser.parseRawIqd(directAmountIqd)
            } else {
                com.example.core.ledger.MoneyParser.parseUtowerAmount(amountUnit)
            }

            val rawDate = txJson.optString("date").takeIf { it.isNotEmpty() }
                ?: txJson.optString("timeOfAction").takeIf { it.isNotEmpty() }
                ?: txJson.optString("time").takeIf { it.isNotEmpty() }
                ?: txJson.optString("createdAt").takeIf { it.isNotEmpty() }
                ?: txJson.optString("timestamp").takeIf { it.isNotEmpty() }
            val ts = txJson.optLong("actualTimeMs", txJson.optLong("timestamp_ms", txJson.optLong("time_ms", txJson.optLong("time", txJson.optLong("date", txJson.optLong("serverTime", 0L))))))
            val occurredAt = if (ts > 0L) ts else parseBghDate(rawDate)

            if (occurredAt == null || occurredAt <= 0L) {
                // P0-3: Strict elimination of System.currentTimeMillis() fallback for invalid uTower dates.
                // Quarantine/skip unparseable transaction date without corrupting historical ordering or post-reset snapshot debt.
                warnings++
                transactionsSkipped++
                Log.w("UtowerImporter", "Quarantining transaction $sourceKey with invalid/unparseable date: rawDate='$rawDate', ts=$ts")
                return
            }

            var note = txJson.optString("comment").takeIf { it.isNotEmpty() }
                ?: txJson.optString("message").takeIf { it.isNotEmpty() }
                ?: txJson.optString("note").takeIf { it.isNotEmpty() }

            if (!note.isNullOrBlank() && note != "null") {
                txNotesImported++
            }

            var subscriberRef = txJson.optString("toWho").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("userIndex").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("userId").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("user_id").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("username").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("userName").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("subscriber_ref").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("subscriber_id").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("account_id").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("accountId").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("user_ref").takeIf { !it.isNullOrEmpty() && it != "null" }

            if (subscriberRef.isNullOrEmpty() && txJson.has("toWhoHistorySourceTimeIndex")) {
                val indexStr = txJson.optString("toWhoHistorySourceTimeIndex")
                if (indexStr.isNotEmpty() && indexStr != "null") {
                    subscriberRef = indexStr.split("|")[0]
                }
            }

            var accountId = subscriberMap[subscriberRef] ?: accountsBySourceId[subscriberRef]?.id ?: accountsById[subscriberRef]?.id

            if (accountId == null && subscriberRef != null) {
                val cleanRef = subscriberRef.removePrefix("e_").removePrefix("user_").removePrefix("sub_")
                for (prefix in listOf("", "e_", "user_", "sub_")) {
                    val alt = prefix + cleanRef
                    accountId = subscriberMap[alt] ?: accountsBySourceId[alt]?.id
                    if (accountId != null) break
                }
            }

            if (accountId == null && subscriberRef != null) {
                if (subscriberRef.contains("@")) {
                    accountId = subscriberByUsername[subscriberRef.trim()] ?: accountsByUsername[subscriberRef.trim()]?.id
                }
            }

            if (accountId == null && subscriberRef != null) {
                if (subscriberRef.matches(Regex("^[0-9+]+$"))) {
                    val p = subscriberRef.trim()
                    val phoneAccountIds = setOfNotNull(subscriberByPhone[p], accountsByPhone[p]?.id)
                    if (phoneAccountIds.size == 1) {
                        accountId = phoneAccountIds.first()
                    }
                }
            }

            if (accountId == null && subscriberRef != null) {
                val n = subscriberRef.trim()
                val nameAccountIds = setOfNotNull(subscriberByName[n], accountsByName[n]?.id)
                if (nameAccountIds.size == 1) {
                    accountId = nameAccountIds.first()
                }
            }

            val toWhoName = txJson.optString("toWhoName").takeIf { !it.isNullOrEmpty() && it != "null" }
                ?: txJson.optString("subscriber_name").takeIf { !it.isNullOrEmpty() && it != "null" }

            if (accountId == null && toWhoName != null) {
                val n = toWhoName.trim()
                val nameAccountIds = setOfNotNull(subscriberByName[n], accountsByName[n]?.id)
                if (nameAccountIds.size == 1) {
                    accountId = nameAccountIds.first()
                }
            }

            if (accountId != null) {
                touchedAccountIds.add(accountId)
                val rawType = if (txJson.has("type")) txJson.optString("type") else txJson.optString("type_raw")
                val baseTypeNormalized = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(rawType)

                val directAmountIqd = com.example.core.ledger.MoneyParser.parseAmount(txJson, keys = listOf("amount_iqd"))
                val amountUnit = com.example.core.ledger.MoneyParser.parseAmount(txJson, keys = listOf("amount", "amount_unit"))
                val rawAmountIqd = if (directAmountIqd != null) {
                    com.example.core.ledger.MoneyParser.parseRawIqd(directAmountIqd)
                } else {
                    com.example.core.ledger.MoneyParser.parseUtowerAmount(amountUnit)
                }
                var typeNormalized = baseTypeNormalized
                var amountIqdVal = rawAmountIqd
                if (amountIqdVal < 0.0) {
                    amountIqdVal = kotlin.math.abs(amountIqdVal)
                    typeNormalized = when (baseTypeNormalized) {
                        "took" -> "gave"
                        "gave" -> "took"
                        else -> baseTypeNormalized
                    }
                }

                val directDebtAfterIqd = com.example.core.ledger.MoneyParser.parseAmount(txJson, keys = listOf("debt_after_iqd"))
                val debtAfterUnit = com.example.core.ledger.MoneyParser.parseAmount(txJson, keys = listOf("totalDebitAfter", "debt_after_unit", "debt_after"))
                val debtAfterIqd = if (directDebtAfterIqd != null) {
                    com.example.core.ledger.MoneyParser.parseRawIqd(directDebtAfterIqd)
                } else {
                    com.example.core.ledger.MoneyParser.parseUtowerAmount(debtAfterUnit)
                }

                val combinedNote = listOf(note ?: "", txJson.optString("message", "")).filter { it.isNotEmpty() && it != "null" }.joinToString(" | ")

                val sourceExtId = if (sourceKey.isNotEmpty()) {
                    sourceKey
                } else {
                    "import_${batchId}_${transactionsRead}"
                }

                val candidateTx = LocalLedgerEntry(
                    id = "",
                    accountId = accountId,
                    sourceExternalId = sourceExtId,
                    sourceBatchId = batchId,
                    typeRaw = typeNormalized,
                    amountIqd = amountIqdVal,
                    debtAfterIqd = debtAfterIqd,
                    note = combinedNote.takeIf { it.isNotEmpty() },
                    occurredAt = occurredAt,
                    rawJson = txJson.toString(),
                    createdAt = System.currentTimeMillis(),
                    isSnapshotHistory = true
                )

                val existingTx = TransactionDeduplicator.findDuplicate(
                    existingByExtId = existingTxBySourceExtId,
                    existingByMatch = existingTxByMatch,
                    tx = candidateTx
                )

                if (existingTx == null) {
                    val txSeed = "tx_${accountId}_${sourceExtId}"
                    val txId = UUID.nameUUIDFromBytes(txSeed.toByteArray(Charsets.UTF_8)).toString()
                    val tx = candidateTx.copy(id = txId)

                    val rowId = appDatabase.localLedgerEntryDao().insert(tx)
                    if (rowId <= 0L) {
                        transactionsMerged++
                        val resolvedTx = appDatabase.localLedgerEntryDao().findDuplicateTx(accountId, sourceExtId, occurredAt, amountIqdVal, typeNormalized)
                            ?: existingTxBySourceExtId[TransactionDeduplicator.buildExtIdKey(accountId, sourceExtId)]
                            ?: existingTxByMatch[TransactionDeduplicator.buildMatchKey(accountId, occurredAt, amountIqdVal, typeNormalized, tx.note)]
                        val finalTxId = resolvedTx?.id ?: txId
                        touchedTxIds.add(finalTxId)
                        if (resolvedTx != null) {
                            if (sourceKey.isNotEmpty()) {
                                existingTxBySourceExtId[TransactionDeduplicator.buildExtIdKey(accountId, sourceKey)] = resolvedTx
                            }
                            existingTxByMatch[TransactionDeduplicator.buildMatchKey(accountId, occurredAt, amountIqdVal, typeNormalized, resolvedTx.note)] = resolvedTx
                        }
                    } else {
                        txImported++
                        transactionsInserted++
                        OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "local_ledger_entries", tx.id, ledgerAdapter.toJson(tx), importBatchId = batchId)

                        if (sourceKey.isNotEmpty()) {
                            existingTxBySourceExtId[TransactionDeduplicator.buildExtIdKey(accountId, sourceKey)] = tx
                        }
                        existingTxByMatch[TransactionDeduplicator.buildMatchKey(accountId, occurredAt, amountIqdVal, typeNormalized, tx.note)] = tx
                        touchedTxIds.add(tx.id)
                    }
                } else {
                    touchedTxIds.add(existingTx.id)
                    if (existingTx.sourceExternalId.isNullOrEmpty() && sourceKey.isNotEmpty()) {
                        val updatedTx = existingTx.copy(sourceExternalId = sourceKey, isSnapshotHistory = true)
                        val rowId = appDatabase.localLedgerEntryDao().insert(updatedTx)
                        if (rowId > 0L) {
                            transactionsMerged++
                            OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "local_ledger_entries", updatedTx.id, ledgerAdapter.toJson(updatedTx), importBatchId = batchId)
                        } else {
                            transactionsSkipped++
                        }
                    } else {
                        transactionsSkipped++
                    }
                }
            } else {
                warnings++
                transactionsSkipped++
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            transactionsFailed++
            Log.e("UtowerImporter", "Error processing transaction $sourceKey", e)
            throw Exception("Error processing transaction $sourceKey: ${e.message}", e)
        }
    }

    suspend fun commitAll() {
        // Process subscribers in chunks of 500
        val subChunks = parsedSubs.chunked(500)
        for (chunk in subChunks) {
            for (sub in chunk) {
                insertOrUpdateUserInternal(sub.sourceKey, sub.json, sub.isLegacy, sub.originalId)
            }
        }

        // Process transactions in chunks of 1000
        val txChunks = parsedTxs.chunked(1000)
        for (chunk in txChunks) {
            for (tx in chunk) {
                processTransactionInternal(tx.sourceKey, tx.json)
            }
        }

        // Reconcile post-reset debts
        reconcilePostResetDebts()
    }

    suspend fun reconcilePostResetDebts() {
        for ((accountId, resetMs) in accountResetDates) {
            if (resetMs <= 0L) continue
            val txs = appDatabase.localLedgerEntryDao().getByAccountIdOneShot(accountId, limit = Int.MAX_VALUE)
            val postResetTxs = txs.filter { it.occurredAt > resetMs }
                .sortedWith(compareBy<LocalLedgerEntry> { it.occurredAt }.thenBy { it.sourceExternalId ?: "" }.thenBy { it.id })

            if (postResetTxs.isNotEmpty()) {
                val currentAcc = appDatabase.localAccountDao().getByIdOneShot(accountId)
                if (currentAcc != null) {
                    val resolvedDebt = UtowerDebtResolver.resolveDebtForAccount(currentAcc, postResetTxs)
                    val updatedAcc = currentAcc.copy(
                        debtIqd = resolvedDebt,
                        advanceIqd = currentAcc.openingAdvanceIqd,
                        loanIqd = currentAcc.openingLoanIqd,
                        openingDebtIqd = resolvedDebt,
                        openingAdvanceIqd = currentAcc.openingAdvanceIqd,
                        openingLoanIqd = currentAcc.openingLoanIqd,
                        stateSource = "UTOWER_SNAPSHOT_RESOLVED",
                        stateConfidence = "AUTHORITATIVE",
                        updatedAt = System.currentTimeMillis()
                    )
                    appDatabase.localAccountDao().update(updatedAcc)
                    OutboxManager.upsertWithOutbox(appDatabase.syncOutboxDao(), "local_accounts", updatedAcc.id, accountAdapter.toJson(updatedAcc), importBatchId = batchId)
                    accountsById[accountId] = updatedAcc
                }
            }
        }
    }
}
