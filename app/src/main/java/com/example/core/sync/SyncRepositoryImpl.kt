package com.example.core.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import androidx.work.BackoffPolicy
import com.example.core.util.AppBuildConfig
import com.example.core.database.*
import com.example.core.model.*
import com.example.core.security.CloudSecretEncryptor
import com.example.core.security.PreferenceManager
import com.example.domain.repository.SyncRepository
import com.example.domain.repository.SyncStatusState
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.QuerySnapshot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class SyncRepositoryImpl(
    private val context: Context,
    private val appDatabase: com.example.core.database.AppDatabase,
    private val outboxDao: SyncOutboxDao,
    private val accountDao: LocalAccountDao,
    private val ledgerDao: LocalLedgerEntryDao,
    private val batchDao: ImportBatchDao,
    private val metadataDao: SyncMetadataDao,
    private val auditDao: AuditLogDao
) : SyncRepository {

    private val moshi = com.squareup.moshi.Moshi.Builder().build()
    private val ledgerAdapter = moshi.adapter(LocalLedgerEntry::class.java)

    private var auth: FirebaseAuth? = try {
        FirebaseAuth.getInstance()
    } catch (e: Throwable) {
        Log.w("SyncRepository", "Firebase Auth initialization failed", e)
        null
    }

    private var firestore: FirebaseFirestore? = try {
        FirebaseFirestore.getInstance()
    } catch (e: Throwable) {
        Log.w("SyncRepository", "Firebase Firestore initialization failed", e)
        null
    }
    
    @androidx.annotation.VisibleForTesting
    fun setFirebaseInstancesForTest(mockAuth: FirebaseAuth?, mockFirestore: FirebaseFirestore?) {
        this.auth = mockAuth
        this.firestore = mockFirestore
    }
    private val prefManager = PreferenceManager(context)
    val remoteSyncCoordinator = RemoteSyncCoordinator(
        appDatabase = appDatabase,
        accountDao = accountDao,
        ledgerDao = ledgerDao,
        batchDao = batchDao,
        outboxDao = outboxDao,
        metadataDao = metadataDao
    )
    private val snapshotMutex = Mutex()
    private val singleFlightMutex = Mutex()
    @androidx.annotation.VisibleForTesting
    internal val settingsSyncMutex = Mutex()
    private val pendingRunAfterCurrent = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listenersMutex = Mutex()
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var accountListener: ListenerRegistration? = null
    private var ledgerListener: ListenerRegistration? = null
    private var batchListener: ListenerRegistration? = null
    private var auditListener: ListenerRegistration? = null

    private val _syncState = MutableStateFlow(SyncStatusState.IDLE)
    override val syncState: StateFlow<SyncStatusState> = _syncState.asStateFlow()

    init {
        // If there is an auth state listener, we can update status
        val currentAuth = auth
        if (currentAuth != null) {
            currentAuth.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                if (user == null) {
                    _syncState.value = SyncStatusState.AUTH_REQUIRED
                    stopRealtimeSync()
                } else {
                    if (_syncState.value == SyncStatusState.AUTH_REQUIRED) {
                        _syncState.value = SyncStatusState.IDLE
                    }
                    startRealtimeSync(user.uid)
                    triggerSettingsSync(user.uid, "auth_state_changed")
                }
            }
        } else {
            _syncState.value = SyncStatusState.OFFLINE
        }
    }

    override suspend fun getPendingOutboxCount(): Int {
        return outboxDao.getAllUnsyncedCount()
    }

    override suspend fun getFailedCount(): Int {
        return outboxDao.getFailedCount()
    }

    override suspend fun retryFailedItems(): Int {
        val count = outboxDao.resetFailedItems()
        if (count > 0) {
            (context.applicationContext as? com.example.EarthlinkApp)?.auditRepository?.log(
                severity = AuditSeverity.INFO,
                action = "SYNC_RETRY_FAILED_ITEMS",
                message = "Reset $count failed outbox items back to pending queue for re-syncing.",
                origin = com.example.core.model.AuditOrigin.SYNC_EVENT
            )
        }
        return count
    }

    override fun requestSync(reason: com.example.domain.repository.SyncReason) {
        Log.i("SyncRepo", "Synchronization requested with reason: $reason")
        
        val constraintsBuilder = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)

        // Map SyncReason to distinct prioritization, retry timing, and enqueueing policies
        val (workPolicy, backoffDelay, backoffPolicy) = when (reason) {
            com.example.domain.repository.SyncReason.USER_ACTION,
            com.example.domain.repository.SyncReason.MANUAL -> {
                // User-triggered actions: preempt existing/stuck tasks (REPLACE) and start immediately
                Triple(ExistingWorkPolicy.REPLACE, 15L, BackoffPolicy.EXPONENTIAL)
            }
            com.example.domain.repository.SyncReason.RETRY -> {
                // Background retry task: non-intrusive (KEEP) and conservative retry backoff
                Triple(ExistingWorkPolicy.KEEP, 60L, BackoffPolicy.EXPONENTIAL)
            }
            com.example.domain.repository.SyncReason.NETWORK_RECOVERY -> {
                // Network recovery: use existing queued pass but standard backoff
                Triple(ExistingWorkPolicy.KEEP, 30L, BackoffPolicy.EXPONENTIAL)
            }
            com.example.domain.repository.SyncReason.STARTUP,
            com.example.domain.repository.SyncReason.PERIODIC -> {
                // Periodic maintenance/startup triggers: run in the background without canceling existing
                Triple(ExistingWorkPolicy.KEEP, 45L, BackoffPolicy.EXPONENTIAL)
            }
        }

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraintsBuilder.build())
            .setBackoffCriteria(backoffPolicy, backoffDelay, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        _syncState.value = SyncStatusState.SYNCING

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "firebase_local_sync",
                workPolicy,
                syncRequest
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SyncRepo", "WorkManager requestSync failed for reason: $reason", e)
        }
    }

    override fun triggerSync() {
        _syncState.value = SyncStatusState.SYNCING
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "firebase_local_sync",
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            android.util.Log.e("SyncRepo", "WorkManager triggersync failed (e.g. test environment)", e)
        }
    }

    override fun setupPeriodicSync() {
        setupPeriodicSync(context)
    }

    companion object {
        fun setupPeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES)
                .build()

            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "firebase_periodic_sync",
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                android.util.Log.e("SyncRepo", "WorkManager setupPeriodicSync failed (e.g. test environment)", e)
            }
        }
    }

    override suspend fun triggerSyncOneShot(): Boolean {
        if (!singleFlightMutex.tryLock()) {
            pendingRunAfterCurrent.set(true)
            android.util.Log.d("FirebaseSync", "Sync already in progress, scheduled PENDING_AFTER_CURRENT.")
            return true
        }
        try {
            var overallSuccess = true
            do {
                pendingRunAfterCurrent.set(false)
                overallSuccess = executeSyncPassInternal() && overallSuccess
            } while (pendingRunAfterCurrent.getAndSet(false))
            return overallSuccess
        } finally {
            singleFlightMutex.unlock()
        }
    }

    private suspend fun executeSyncPassInternal(): Boolean {
        _syncState.value = SyncStatusState.SYNCING

        if (prefManager.getAuthToken().isNullOrEmpty()) {
            Log.w("FirebaseSync", "No active session auth token in PreferenceManager. Sync requires authentication.")
            _syncState.value = SyncStatusState.AUTH_REQUIRED
            return false
        }

        val fbAuth = auth
        val fbFirestore = firestore
        if (fbAuth == null || fbFirestore == null) {
            _syncState.value = SyncStatusState.OFFLINE
            return false
        }
        return try {
            val uid = fbAuth.currentUser?.uid
            if (uid == null) {
                // Try to sign in anonymously
                val anonymousUid = anonymousSignIn()
                if (anonymousUid == null) {
                    _syncState.value = SyncStatusState.AUTH_REQUIRED
                    return false
                }
            }

            val currentUid = fbAuth.currentUser?.uid ?: return false
            val syncStartTime = System.currentTimeMillis()

            // Sync user settings (ISP credentials & deposit pass) with Firestore
            triggerSettingsSync(currentUid, "pull_remote_changes")

            // Reset any items stuck in 'syncing' status from a previous interrupted sync run
            OutboxManager.resetSyncingToPending(outboxDao)

            // Critical Maintenance rule: pause sync if global data maintenance (restore/import/signout) is in progress
            if (DataOperationCoordinator.isMaintenanceActive) {
                Log.w("SyncRepository", "Sync paused because global data maintenance is active.")
                return false
            }

            // Critical Outbox rule: pause sync if an import is running or incomplete (running or failed/resumable)
            if (appDatabase.importBatchDao().getIncompleteCount() > 0) {
                Log.w("SyncRepository", "Sync paused because import is incomplete.")
                return false
            }

            // 1. Process local outbox changes and upload to Firestore
            val rawPendingItems = OutboxManager.getPending(outboxDao)
            val pendingItems = rawPendingItems.filter { item ->
                val batchCompleted = if (item.importBatchId == null) {
                    true
                } else {
                    val batch = batchDao.getById(item.importBatchId)
                    batch?.status == "completed"
                }
                batchCompleted && OutboxManager.isEligibleForSync(item, syncStartTime)
            }
            
            // Partition outbox items: apply state-based deduplication only for state-based collections
            // (where latest state overrides are desired), while keeping event-based/append-only collections
            // (like audit_logs) entirely separate to preserve their full sequential history.
            val stateBasedTypes = setOf("local_accounts", "local_ledger_entries", "import_batches")
            val deduplicatedItems = mutableListOf<Pair<SyncOutbox, List<SyncOutbox>>>()

            val (stateItems, eventItems) = pendingItems.partition { it.entityType in stateBasedTypes }

            // 1a. Deduplicate state-based items
            val groupedState = stateItems.groupBy { Pair(it.entityType, it.entityId) }
            groupedState.values.forEach { itemsForEntity ->
                val latest = itemsForEntity.maxByOrNull { it.id } ?: itemsForEntity.last()
                deduplicatedItems.add(Pair(latest, itemsForEntity))
            }

            // 1b. Retain all event-based items as distinct individual entries to preserve execution order
            eventItems.forEach { item ->
                deduplicatedItems.add(Pair(item, listOf(item)))
            }

            val chunkedItems = deduplicatedItems.chunked(500)

            for (chunk in chunkedItems) {
                val latestItemsInChunk = chunk.map { it.first }

                appDatabase.withTransaction {
                    OutboxManager.markInFlight(outboxDao, latestItemsInChunk)
                }

                // Per-item preparation & validation (isolating malformed/poison payloads & detecting orphans)
                val preparedItems = mutableListOf<Triple<SyncOutbox, List<SyncOutbox>, Map<String, Any?>>>()

                for (itemPair in chunk) {
                    val (latestItem, allForEntity) = itemPair

                    // Check for orphaned outbox obligation (missing or invalid target entity in local DB)
                    val orphanReason = checkOrphanStatus(latestItem)
                    if (orphanReason != null) {
                        Log.w("FirebaseSync", "Detected orphaned outbox item ${latestItem.entityType}:${latestItem.entityId}: $orphanReason")
                        appDatabase.withTransaction {
                            OutboxManager.markOrphanFailure(outboxDao, allForEntity, orphanReason)
                        }
                        continue
                    }

                    try {
                        val dataMap = buildOutboxPayloadMap(latestItem)
                        preparedItems.add(Triple(latestItem, allForEntity, dataMap))
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("FirebaseSync", "Malformed outbox payload for ${latestItem.entityType}:${latestItem.entityId}, isolating failure", e)
                        appDatabase.withTransaction {
                            val errReason = "Malformed payload error: ${e.message ?: "Invalid JSON"}"
                            OutboxManager.markRetryableFailure(outboxDao, allForEntity, errReason)
                        }
                    }
                }

                if (preparedItems.isNotEmpty()) {
                    var batchSucceeded = false
                    if (preparedItems.size > 1) {
                        try {
                            val batch = fbFirestore.batch()
                            for ((item, _, dataMap) in preparedItems) {
                                val collRef = getCollectionRef(item.entityType, currentUid, fbFirestore)
                                if (collRef != null) {
                                    val docRef = collRef.document(item.entityId)
                                    batch.set(docRef, dataMap, SetOptions.merge())
                                }
                            }
                            batch.commit().await()
                            batchSucceeded = true

                            // Batch succeeded: acknowledge and read-back for each item
                            for ((item, allForEntity, _) in preparedItems) {
                                appDatabase.withTransaction {
                                    OutboxManager.markSucceeded(outboxDao, allForEntity.map { it.id })
                                }
                                confirmRemoteVersionReadBack(item, currentUid, fbFirestore)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w("FirebaseSync", "Batch push commit failed; falling back to per-item isolation push", e)
                        }
                    }

                    // Fallback to per-item isolation if batch failed or single item
                    if (!batchSucceeded) {
                        for ((item, allForEntity, dataMap) in preparedItems) {
                            executeSingleItemPush(item, allForEntity, dataMap, currentUid, fbFirestore)
                        }
                    }
                }
            }

            // 2. Downward sync (pull remote changes per collection)
            val collectionsToSync = listOf("local_accounts", "local_ledger_entries", "import_batches", "audit_logs")
            for (collName in collectionsToSync) {
                val initialCursor = getCollectionCursor(collName)
                val updatedCursor = pullRemoteChanges(currentUid, collName, initialCursor, fbFirestore)
                if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                    (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                    saveCollectionCursor(collName, updatedCursor)
                }
            }

            _syncState.value = SyncStatusState.COMPLETE
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("FirebaseSync", "One shot sync error", e)
            val isAuthError = (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                    (e.cause as? com.google.firebase.firestore.FirebaseFirestoreException)?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                    (e as? com.google.firebase.firestore.FirebaseFirestoreException)?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED ||
                    (e.cause as? com.google.firebase.firestore.FirebaseFirestoreException)?.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED ||
                    (e as? com.google.firebase.auth.FirebaseAuthException) != null ||
                    (e.cause as? com.google.firebase.auth.FirebaseAuthException) != null ||
                    e.message?.contains("UNAUTHENTICATED", ignoreCase = true) == true ||
                    e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true
            if (isAuthError) {
                _syncState.value = SyncStatusState.AUTH_REQUIRED
            } else {
                _syncState.value = SyncStatusState.ERROR
            }
            false
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun getCollectionRef(
        entityType: String,
        uid: String,
        firestore: FirebaseFirestore
    ): com.google.firebase.firestore.CollectionReference? {
        return when (entityType) {
            "local_accounts", "accounts" -> firestore.collection("users").document(uid).collection("local_accounts")
            "local_ledger_entries", "ledger", "ledger_entries" -> firestore.collection("users").document(uid).collection("local_ledger_entries")
            "import_batches", "batches" -> firestore.collection("users").document(uid).collection("import_batches")
            "audit_logs", "audit" -> firestore.collection("users").document(uid).collection("audit_logs")
            else -> null
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun buildOutboxPayloadMap(item: SyncOutbox): Map<String, Any?> {
        val json = JSONObject(item.payloadJson)
        val syncMutationId = if (json.has("syncMutationId")) json.optString("syncMutationId") else null
        val dataMap = mutableMapOf<String, Any?>()

        if (item.operation == "delete") {
            if (syncMutationId != null) {
                dataMap["syncMutationId"] = syncMutationId
            }
            dataMap["deletedAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
            dataMap["localUpdatedAt"] = System.currentTimeMillis()
            dataMap["updatedAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
            dataMap["schemaVersion"] = 1
            val deviceId = prefManager.getDeviceId()
            dataMap["deviceId"] = deviceId
            dataMap["lastModifiedByDeviceId"] = deviceId
            return dataMap
        }

        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (item.entityType == "local_accounts" && (k == "packageName" || k == "towerName" || k == "zoneName")) {
                continue
            }
            val v = json.get(k)
            dataMap[k] = if (v == JSONObject.NULL) null else v
        }

        if (syncMutationId != null) {
            dataMap["syncMutationId"] = syncMutationId
        }

        if (dataMap.containsKey("updatedAt")) {
            dataMap["localUpdatedAt"] = dataMap["updatedAt"]
        } else if (dataMap.containsKey("createdAt")) {
            dataMap["localUpdatedAt"] = dataMap["createdAt"]
        } else {
            dataMap["localUpdatedAt"] = System.currentTimeMillis()
        }

        dataMap["updatedAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
        dataMap["deletedAt"] = null
        dataMap["schemaVersion"] = 1
        if (item.entityType == "local_accounts" || item.entityType == "accounts") {
            dataMap["isFullSnapshot"] = true
            // Strip ISP-specific diagnostic/meta fields only.
            // Keep essential fields for multi-device sync, including nanoIp (Custom IP managed by reseller).
            dataMap.remove("rawJson")
            dataMap.remove("stateSource")
            dataMap.remove("stateConfidence")
            dataMap.remove("address")
            dataMap.remove("latitude")
            dataMap.remove("longitude")
        } else if (item.entityType == "local_ledger_entries" || item.entityType == "ledger" || item.entityType == "ledger_entries") {
            // LocalLedgerEntry.rawJson is local-only source/import data and must NOT be uploaded to Cloud Firestore.
            dataMap.remove("rawJson")
        }
        val deviceId = prefManager.getDeviceId()
        dataMap["deviceId"] = deviceId
        dataMap["lastModifiedByDeviceId"] = deviceId
        return dataMap
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun checkOrphanStatus(item: SyncOutbox): String? {
        if (item.operation == "delete") {
            // Deletion tombstones are valid obligations even when local entity has already been deleted
            return null
        }
        return when (item.entityType) {
            "local_accounts" -> {
                val acc = accountDao.getByIdOneShot(item.entityId)
                if (acc == null) "Entity ${item.entityId} of type ${item.entityType} not found in local database" else null
            }
            "local_ledger_entries" -> {
                val ledger = ledgerDao.getByIdOneShot(item.entityId)
                if (ledger == null) {
                    "Entity ${item.entityId} of type ${item.entityType} not found in local database"
                } else {
                    val parentAcc = accountDao.getByIdOneShot(ledger.accountId)
                    if (parentAcc == null) {
                        "Parent account ${ledger.accountId} for ledger entry ${item.entityId} not found in local database"
                    } else {
                        null
                    }
                }
            }
            "import_batches" -> {
                val batch = batchDao.getById(item.entityId)
                if (batch == null) "Entity ${item.entityId} of type ${item.entityType} not found in local database" else null
            }
            "audit_logs" -> {
                val audit = auditDao.getById(item.entityId)
                if (audit == null) "Entity ${item.entityId} of type ${item.entityType} not found in local database" else null
            }
            else -> "Unknown entity type ${item.entityType}"
        }
    }

    private suspend fun executeSingleItemPush(
        item: SyncOutbox,
        allForEntity: List<SyncOutbox>,
        dataMap: Map<String, Any?>,
        currentUid: String,
        fbFirestore: FirebaseFirestore
    ) {
        try {
            val collRef = getCollectionRef(item.entityType, currentUid, fbFirestore)
            if (collRef != null) {
                val docRef = collRef.document(item.entityId)
                docRef.set(dataMap, SetOptions.merge()).await()

                appDatabase.withTransaction {
                    OutboxManager.markSucceeded(outboxDao, allForEntity.map { it.id })
                }

                confirmRemoteVersionReadBack(item, currentUid, fbFirestore)
            }
        } catch (itemError: Exception) {
            if (itemError is kotlinx.coroutines.CancellationException) throw itemError
            Log.e("FirebaseSync", "Individual item push failed for ${item.entityType}:${item.entityId}", itemError)
            appDatabase.withTransaction {
                val errReason = itemError.localizedMessage ?: "Sync error"
                OutboxManager.markRetryableFailure(outboxDao, allForEntity, errReason)
            }
        }
    }

    private suspend fun confirmRemoteVersionReadBack(
        item: SyncOutbox,
        currentUid: String,
        fbFirestore: FirebaseFirestore
    ) {
        val collName = item.entityType
        val entityTypeKey = when (collName) {
            "local_accounts" -> "account"
            "local_ledger_entries" -> "ledger"
            "import_batches" -> "batch"
            else -> null
        }

        if (entityTypeKey != null) {
            try {
                val collRef = fbFirestore.collection("users").document(currentUid).collection(collName)
                val serverDoc = collRef.document(item.entityId).get(Source.SERVER).await()

                val docData = serverDoc.data
                val hasPendingWrites = serverDoc.metadata.hasPendingWrites()
                val fromCache = serverDoc.metadata.isFromCache

                val deletedAt = RemoteSyncCursor.parseRemoteTimestamp(docData?.get("deletedAt"))
                val remoteUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(docData?.get("updatedAt"))
                val isDeleted = (deletedAt != null && deletedAt > 0L)
                val serverVersion = if (isDeleted) (deletedAt ?: remoteUpdatedAt ?: 0L) else (remoteUpdatedAt ?: 0L)

                val mutationIdInPayload = try {
                    val json = JSONObject(item.payloadJson)
                    if (json.has("syncMutationId")) json.optString("syncMutationId") else null
                } catch (e: Exception) { null }

                val serverMutationId = docData?.get("syncMutationId") as? String

                // Strict validation: must not have pending writes, must not be cache, must have valid server version > 0
                if (serverDoc.exists() && !hasPendingWrites && !fromCache && serverVersion > 0L) {
                    // Verify mutation correlation if mutationId is present
                    val mutationMatches = mutationIdInPayload != null &&
                            serverMutationId != null &&
                            mutationIdInPayload == serverMutationId

                    if (mutationMatches) {
                        appDatabase.withTransaction {
                            if (item.operation == "delete" || isDeleted) {
                                metadataDao.put("tombstone:$entityTypeKey:${item.entityId}", serverVersion.toString())
                            }
                            metadataDao.putMonotonicRemoteVersion("remote_version:$entityTypeKey:${item.entityId}", serverVersion)
                            metadataDao.put("version_capture_retry:$entityTypeKey:${item.entityId}", "0")
                        }
                        Log.d("FirebaseSync", "Captured server-confirmed remote_version $serverVersion for $entityTypeKey:${item.entityId}")
                    } else {
                        Log.w("FirebaseSync", "Server read-back mutation correlation mismatch for $entityTypeKey:${item.entityId}. Expected $mutationIdInPayload, found $serverMutationId")
                        metadataDao.put("version_capture_retry:$entityTypeKey:${item.entityId}", "1")
                    }
                } else {
                    Log.w("FirebaseSync", "Server read-back did not yield confirmed version for $entityTypeKey:${item.entityId} (exists=${serverDoc.exists()}, pending=$hasPendingWrites, cached=$fromCache, version=$serverVersion)")
                    metadataDao.put("version_capture_retry:$entityTypeKey:${item.entityId}", "1")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("FirebaseSync", "Server read-back failed for $entityTypeKey:${item.entityId}. Setting version_capture_retry.", e)
                metadataDao.put("version_capture_retry:$entityTypeKey:${item.entityId}", "1")
            }
        }
    }

    private suspend fun getCollectionCursor(collName: String): RemoteSyncCursor {
        val collKey = "last_sync_$collName"
        val collSyncStr = metadataDao.get(collKey)
        if (collSyncStr != null) {
            return RemoteSyncCursor.parseCursorString(collSyncStr)
        }
        val isMigrated = metadataDao.get("legacy_cursor_migration_completed") != null
        if (!isMigrated) {
            metadataDao.put("legacy_cursor_migration_completed", "true")
        }
        return RemoteSyncCursor.EMPTY
    }

    private suspend fun saveCollectionCursor(collName: String, cursor: RemoteSyncCursor) {
        if (cursor.lastServerTimestamp <= 0L) return
        val collKey = "last_sync_$collName"
        metadataDao.put(collKey, cursor.toCursorString())

        val currentGlobalStr = metadataDao.get("last_sync_timestamp")
        val currentGlobal = RemoteSyncCursor.parseCursorString(currentGlobalStr).lastServerTimestamp
        if (cursor.lastServerTimestamp > currentGlobal) {
            metadataDao.put("last_sync_timestamp", cursor.lastServerTimestamp.toString())
            prefManager.saveLastSyncTime(cursor.lastServerTimestamp)
        }
    }

    @Volatile
    private var isRealtimeSyncActive = false
    @Volatile
    private var activeSyncUid: String? = null

    private fun startRealtimeSync(uid: String) {
        val fbFirestore = firestore ?: return

        syncScope.launch {
            listenersMutex.withLock {
                if (isRealtimeSyncActive && activeSyncUid == uid) return@withLock

                // Detach any previous listeners before switching or re-binding UID
                accountListener?.remove()
                accountListener = null
                ledgerListener?.remove()
                ledgerListener = null
                batchListener?.remove()
                batchListener = null
                auditListener?.remove()
                auditListener = null

                isRealtimeSyncActive = true
                activeSyncUid = uid

                try {
                    // 1. Load initial per-collection cursors & run initial bootstrap pull
                    val collectionsToSync = listOf("local_accounts", "local_ledger_entries", "import_batches", "audit_logs")
                    val activeCursors = mutableMapOf<String, RemoteSyncCursor>()

                    singleFlightMutex.withLock {
                        for (collName in collectionsToSync) {
                            val initialCursor = getCollectionCursor(collName)
                            var updatedCursor = initialCursor
                            try {
                                updatedCursor = pullRemoteChanges(uid, collName, initialCursor, fbFirestore)
                                if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                                    (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                                    saveCollectionCursor(collName, updatedCursor)
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.w("FirebaseSync", "Initial bootstrap pull failed for $collName", e)
                            }
                            activeCursors[collName] = if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                                (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                                updatedCursor
                            } else {
                                initialCursor
                            }
                        }
                    }

                    // 2. Start realtime listeners using per-collection composite cursors
                    accountListener?.remove()
                    ledgerListener?.remove()
                    batchListener?.remove()
                    auditListener?.remove()

                    suspend fun buildRealtimeQuery(collName: String): com.google.firebase.firestore.Query {
                        val cursor = activeCursors[collName] ?: getCollectionCursor(collName)
                        var q = fbFirestore.collection("users").document(uid).collection(collName)
                            .orderBy("updatedAt")
                            .orderBy(com.google.firebase.firestore.FieldPath.documentId())
                        if (cursor.lastServerTimestamp > 0L) {
                            val startDate = java.util.Date(cursor.lastServerTimestamp)
                            q = if (cursor.lastDocumentId.isNotEmpty()) {
                                q.startAfter(startDate, cursor.lastDocumentId)
                            } else {
                                q.startAfter(startDate)
                            }
                        }
                        return q
                    }

                    accountListener = buildRealtimeQuery("local_accounts")
                        .addSnapshotListener { snapshot, error ->
                            handleSnapshot(snapshot, error, "local_accounts", uid)
                        }

                    ledgerListener = buildRealtimeQuery("local_ledger_entries")
                        .addSnapshotListener { snapshot, error ->
                            handleSnapshot(snapshot, error, "local_ledger_entries", uid)
                        }

                    batchListener = buildRealtimeQuery("import_batches")
                        .addSnapshotListener { snapshot, error ->
                            handleSnapshot(snapshot, error, "import_batches", uid)
                        }

                    auditListener = buildRealtimeQuery("audit_logs")
                        .addSnapshotListener { snapshot, error ->
                            handleSnapshot(snapshot, error, "audit_logs", uid)
                        }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("FirebaseSync", "Failed to start realtime listeners", e)
                    isRealtimeSyncActive = false
                    activeSyncUid = null
                }
            }
        }
    }

    fun stopRealtimeSync() {
        syncScope.launch {
            listenersMutex.withLock {
                isRealtimeSyncActive = false
                activeSyncUid = null
                accountListener?.remove()
                accountListener = null
                ledgerListener?.remove()
                ledgerListener = null
                batchListener?.remove()
                batchListener = null
                auditListener?.remove()
                auditListener = null
            }
        }
    }

    internal fun isRealtimeSyncActive(): Boolean = isRealtimeSyncActive
    internal fun getActiveSyncUid(): String? = activeSyncUid

    internal suspend fun startRealtimeSyncForTest(uid: String) {
        listenersMutex.withLock {
            accountListener?.remove()
            accountListener = null
            ledgerListener?.remove()
            ledgerListener = null
            batchListener?.remove()
            batchListener = null
            auditListener?.remove()
            auditListener = null

            isRealtimeSyncActive = true
            activeSyncUid = uid
        }
    }

    internal suspend fun stopRealtimeSyncForTest() {
        listenersMutex.withLock {
            isRealtimeSyncActive = false
            activeSyncUid = null
            accountListener?.remove()
            accountListener = null
            ledgerListener?.remove()
            ledgerListener = null
            batchListener?.remove()
            batchListener = null
            auditListener?.remove()
            auditListener = null
        }
    }

    internal suspend fun mapToRemoteEvent(
        collName: String,
        id: String,
        data: Map<String, Any>,
        source: RemoteEventSource,
        dcType: com.google.firebase.firestore.DocumentChange.Type? = null,
        preFetchedParentAccount: LocalAccount? = null
    ): RemoteEvent? {
        val deletedAt = RemoteSyncCursor.parseRemoteTimestamp(data["deletedAt"])
        val remoteUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(data["updatedAt"])
            ?: 0L

        val isDeleted = (deletedAt != null && deletedAt > 0L)
        
        // P0-2: updatedAt / deletedAt is the single universal source of truth for remoteVersion across all collections
        val version = if (isDeleted) {
            (deletedAt ?: remoteUpdatedAt)
        } else {
            remoteUpdatedAt
        }

        // Issue 7: Reject events with missing/invalid remoteVersion (<= 0L) before entering coordinator
        if (version <= 0L) {
            Log.w("SyncRepositoryImpl", "Rejecting remote event for $collName:$id due to missing or invalid remoteVersion ($version)")
            return null
        }

        val syncMutationId = data["syncMutationId"] as? String

        return when (collName) {
            "local_accounts" -> {
                if (isDeleted) {
                    RemoteEvent.AccountDelete(entityId = id, remoteVersion = version, source = source, syncMutationId = syncMutationId)
                } else {
                    val existing = accountDao.getByIdOneShot(id)
                    when (val validated = RemoteEntityValidator.validateAndMapAccount(id, data, remoteUpdatedAt ?: version, existing)) {
                        is RemoteEntityValidationResult.Valid -> {
                            RemoteEvent.AccountUpsert(entityId = id, remoteVersion = version, source = source, account = validated.entity, syncMutationId = syncMutationId)
                        }
                        is RemoteEntityValidationResult.Malformed -> {
                            Log.w("SyncRepositoryImpl", "Quarantining malformed remote account $id: ${validated.reason}")
                            null
                        }
                        is RemoteEntityValidationResult.Retryable -> {
                            Log.w("SyncRepositoryImpl", "Retryable error for remote account $id: ${validated.reason}")
                            null
                        }
                    }
                }
            }
            "local_ledger_entries" -> {
                if (isDeleted) {
                    RemoteEvent.LedgerDelete(entityId = id, remoteVersion = version, source = source, syncMutationId = syncMutationId)
                } else {
                    val existingLocalLedger = ledgerDao.getByIdOneShot(id)
                    when (val validated = RemoteEntityValidator.validateAndMapLedgerEntry(id, data, remoteUpdatedAt ?: version, existingLocalLedger)) {
                        is RemoteEntityValidationResult.Valid -> {
                            RemoteEvent.LedgerUpsert(entityId = id, remoteVersion = version, source = source, entry = validated.entity, preFetchedParentAccount = preFetchedParentAccount, syncMutationId = syncMutationId)
                        }
                        is RemoteEntityValidationResult.Malformed -> {
                            Log.w("SyncRepositoryImpl", "Quarantining malformed remote ledger $id: ${validated.reason}")
                            null
                        }
                        is RemoteEntityValidationResult.Retryable -> {
                            Log.w("SyncRepositoryImpl", "Retryable error for remote ledger $id: ${validated.reason}")
                            null
                        }
                    }
                }
            }
            "import_batches" -> {
                if (!isDeleted) {
                    when (val validated = RemoteEntityValidator.validateAndMapImportBatch(id, data, remoteUpdatedAt ?: version)) {
                        is RemoteEntityValidationResult.Valid -> {
                            RemoteEvent.BatchUpsert(entityId = id, remoteVersion = version, source = source, batch = validated.entity, syncMutationId = syncMutationId)
                        }
                        is RemoteEntityValidationResult.Malformed -> {
                            Log.w("SyncRepositoryImpl", "Quarantining malformed remote import batch $id: ${validated.reason}")
                            null
                        }
                        is RemoteEntityValidationResult.Retryable -> {
                            Log.w("SyncRepositoryImpl", "Retryable error for remote import batch $id: ${validated.reason}")
                            null
                        }
                    }
                } else null
            }
            else -> null
        }
    }

    private fun handleSnapshot(snapshot: QuerySnapshot?, error: Exception?, collName: String, uid: String? = null) {
        if (error != null) {
            Log.w("FirebaseSync", "Listen failed for $collName: ${error.message}")
            val code = (error as? com.google.firebase.firestore.FirebaseFirestoreException)?.code
            if (code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED ||
                code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAUTHENTICATED) {
                _syncState.value = SyncStatusState.AUTH_REQUIRED
                stopRealtimeSync()
            }
            return
        }
        if (snapshot == null || snapshot.isEmpty) return

        syncScope.launch {
            DataOperationCoordinator.withOperation(DataOperationMode.REMOTE_APPLY) {
                snapshotMutex.withLock {
                    val initialCursor = getCollectionCursor(collName)
                    var snapshotCursor = initialCursor

                    for (dc in snapshot.documentChanges) {
                        val doc = dc.document
                        // 3A: Skip local echoes per document instead of suppressing entire snapshot
                        if (doc.metadata.hasPendingWrites()) {
                            Log.d("FirebaseSync", "Skipping local echo for document ${doc.id} in $collName (hasPendingWrites)")
                            continue
                        }

                        val data = doc.data
                        val id = doc.id

                        val deletedAt = RemoteSyncCursor.parseRemoteTimestamp(data["deletedAt"])
                        val remoteUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(data["updatedAt"])
                        val isDeleted = (deletedAt != null && deletedAt > 0L)
                        val effectiveVersion = if (isDeleted) (deletedAt ?: remoteUpdatedAt ?: 0L) else (remoteUpdatedAt ?: 0L)

                        // Pre-fetch missing parent account from network OUTSIDE database transaction block
                        var preFetchedParentAccount: LocalAccount? = null
                        if (collName == "local_ledger_entries" && !isDeleted) {
                            val remoteLedger = mapToLocalLedgerEntry(id, data, remoteUpdatedAt ?: 0L)
                            if (remoteLedger != null && accountDao.getByIdOneShot(remoteLedger.accountId) == null && uid != null && firestore != null) {
                                try {
                                    val parentDoc = firestore?.collection("users")?.document(uid)?.collection("local_accounts")?.document(remoteLedger.accountId)?.get(Source.SERVER)?.await()
                                    if (parentDoc != null && parentDoc.exists() && !parentDoc.metadata.hasPendingWrites() && !parentDoc.metadata.isFromCache) {
                                        val parentData = parentDoc.data
                                        if (parentData != null) {
                                            val parentDeletedAt = RemoteSyncCursor.parseRemoteTimestamp(parentData["deletedAt"])
                                            if (parentDeletedAt == null || parentDeletedAt <= 0L) {
                                                val parentUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(parentData["updatedAt"]) ?: 0L
                                                preFetchedParentAccount = mapToLocalAccount(remoteLedger.accountId, parentData, parentUpdatedAt)
                                            } else {
                                                Log.w("FirebaseSync", "Parent account ${remoteLedger.accountId} was deleted remotely. Skipping zombie resurrection.")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w("FirebaseSync", "Could not fetch parent account ${remoteLedger.accountId} from Firestore on demand", e)
                                }
                            }
                        }

                        var syncResult: EventSyncResult
                        if (effectiveVersion <= 0L) {
                            val quarantineAudit = AuditLog(
                                action = "MALFORMED_REMOTE_EVENT",
                                entityType = collName,
                                entityId = id,
                                summary = "Blocked cursor on realtime event with missing/invalid remoteVersion ($effectiveVersion) in $collName (docId: $id)",
                                createdAt = System.currentTimeMillis(),
                                severity = "WARNING",
                                origin = AuditOrigin.SYSTEM_ACTION.name
                            )
                            auditDao.insert(quarantineAudit)
                            syncResult = EventSyncResult.BLOCKED_INVALID_VERSION
                        } else {
                            val event = mapToRemoteEvent(
                                collName = collName,
                                id = id,
                                data = data,
                                source = RemoteEventSource.REALTIME,
                                dcType = dc.type,
                                preFetchedParentAccount = preFetchedParentAccount
                            )

                            try {
                                if (event != null) {
                                    syncResult = remoteSyncCoordinator.processEvent(event)
                                } else if (collName == "audit_logs") {
                                    val remoteAudit = mapToAuditLog(id, data)
                                    auditDao.insert(remoteAudit)
                                    syncResult = EventSyncResult.APPLIED
                                } else {
                                    val quarantineAudit = AuditLog(
                                        action = "MALFORMED_REMOTE_EVENT",
                                        entityType = collName,
                                        entityId = id,
                                        summary = "Quarantined malformed realtime event with valid version ($effectiveVersion) in $collName (docId: $id)",
                                        createdAt = effectiveVersion,
                                        severity = "WARNING",
                                        origin = AuditOrigin.SYSTEM_ACTION.name
                                    )
                                    auditDao.insert(quarantineAudit)
                                    syncResult = EventSyncResult.QUARANTINED_MALFORMED
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.e("FirebaseSync", "Constraint/DB exception handling realtime event $id in $collName", e)
                                try {
                                    val constraintAudit = AuditLog(
                                        action = "REMOTE_CONSTRAINT_CONFLICT",
                                        entityType = collName,
                                        entityId = id,
                                        summary = "Halted cursor on realtime event due to DB conflict: ${e.message}",
                                        createdAt = effectiveVersion,
                                        severity = "ERROR",
                                        origin = AuditOrigin.SYSTEM_ACTION.name
                                    )
                                    auditDao.insert(constraintAudit)
                                } catch (_: Exception) {}
                                syncResult = EventSyncResult.FAILED_RETRYABLE
                            }
                        }

                        if (syncResult.canAdvanceCursor()) {
                            if (effectiveVersion > 0L) {
                                snapshotCursor = snapshotCursor.advanceTo(effectiveVersion, id)
                            }
                        } else {
                            Log.w("FirebaseSync", "Failed or blocked processing realtime event $id in collection $collName ($syncResult). Halting snapshot cursor advancement.")
                            break
                        }
                    }

                    if (snapshotCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                        (snapshotCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && snapshotCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                        saveCollectionCursor(collName, snapshotCursor)
                    }
                }
            }
        }
    }

    private suspend fun pullRemoteChanges(
        uid: String,
        collName: String,
        currentCursor: RemoteSyncCursor,
        fbFirestore: FirebaseFirestore
    ): RemoteSyncCursor {
        var lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
        var hasMore = true
        var updatedCursor = currentCursor

        while (hasMore) {
            var query = fbFirestore.collection("users")
                .document(uid)
                .collection(collName)
                .orderBy("updatedAt")
                .orderBy(com.google.firebase.firestore.FieldPath.documentId())
                .limit(500)

            if (lastDoc != null) {
                query = query.startAfter(lastDoc)
            } else if (currentCursor.lastServerTimestamp > 0L) {
                val startDate = java.util.Date(currentCursor.lastServerTimestamp)
                if (currentCursor.lastDocumentId.isNotEmpty()) {
                    query = query.startAfter(startDate, currentCursor.lastDocumentId)
                } else {
                    query = query.startAt(startDate)
                }
            }

            val querySnapshot = query.get(Source.SERVER).await()
            if (querySnapshot.isEmpty) {
                hasMore = false
                break
            }

            lastDoc = querySnapshot.documents.last()

            for (doc in querySnapshot.documents) {
                // Ensure document is from server and has no pending writes
                if (doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) {
                    continue
                }
                val data = doc.data ?: continue
                val id = doc.id
                val deletedAt = RemoteSyncCursor.parseRemoteTimestamp(data["deletedAt"])
                val remoteUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(data["updatedAt"])
                val isDeleted = (deletedAt != null && deletedAt > 0L)
                val effectiveVersion = if (isDeleted) (deletedAt ?: remoteUpdatedAt ?: 0L) else (remoteUpdatedAt ?: 0L)

                var preFetchedParentAccount: LocalAccount? = null
                if (collName == "local_ledger_entries" && !isDeleted) {
                    val remoteLedger = mapToLocalLedgerEntry(id, data, remoteUpdatedAt ?: 0L)
                    if (remoteLedger != null && accountDao.getByIdOneShot(remoteLedger.accountId) == null) {
                        try {
                            val parentDoc = fbFirestore.collection("users").document(uid).collection("local_accounts").document(remoteLedger.accountId).get(Source.SERVER).await()
                            if (parentDoc.exists() && !parentDoc.metadata.hasPendingWrites() && !parentDoc.metadata.isFromCache) {
                                val parentData = parentDoc.data
                                if (parentData != null) {
                                    val parentDeletedAt = RemoteSyncCursor.parseRemoteTimestamp(parentData["deletedAt"])
                                    if (parentDeletedAt == null || parentDeletedAt <= 0L) {
                                        val parentUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(parentData["updatedAt"]) ?: 0L
                                        preFetchedParentAccount = mapToLocalAccount(remoteLedger.accountId, parentData, parentUpdatedAt)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w("FirebaseSync", "Could not fetch parent account ${remoteLedger.accountId} from Firestore", e)
                        }
                    }
                }

                var syncResult: EventSyncResult
                if (effectiveVersion <= 0L) {
                    val quarantineAudit = AuditLog(
                        action = "MALFORMED_REMOTE_EVENT",
                        entityType = collName,
                        entityId = id,
                        summary = "Blocked cursor on remote pull event with missing/invalid remoteVersion ($effectiveVersion) in $collName (docId: $id)",
                        createdAt = System.currentTimeMillis(),
                        severity = "WARNING",
                        origin = AuditOrigin.SYSTEM_ACTION.name
                    )
                    auditDao.insert(quarantineAudit)
                    syncResult = EventSyncResult.BLOCKED_INVALID_VERSION
                } else {
                    val event = mapToRemoteEvent(
                        collName = collName,
                        id = id,
                        data = data,
                        source = RemoteEventSource.PULL,
                        preFetchedParentAccount = preFetchedParentAccount
                    )

                    try {
                        if (event != null) {
                            syncResult = remoteSyncCoordinator.processEvent(event)
                        } else if (collName == "audit_logs") {
                            val remoteAudit = mapToAuditLog(id, data)
                            auditDao.insert(remoteAudit)
                            syncResult = EventSyncResult.APPLIED
                        } else {
                            val quarantineAudit = AuditLog(
                                action = "MALFORMED_REMOTE_EVENT",
                                entityType = collName,
                                entityId = id,
                                summary = "Quarantined malformed remote pull event with valid version ($effectiveVersion) in $collName (docId: $id)",
                                createdAt = effectiveVersion,
                                severity = "WARNING",
                                origin = AuditOrigin.SYSTEM_ACTION.name
                            )
                            auditDao.insert(quarantineAudit)
                            syncResult = EventSyncResult.QUARANTINED_MALFORMED
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.e("FirebaseSync", "Constraint/DB exception processing remote pull event $id in $collName", e)
                        try {
                            val constraintAudit = AuditLog(
                                action = "REMOTE_CONSTRAINT_CONFLICT",
                                entityType = collName,
                                entityId = id,
                                summary = "Halted cursor on pull event due to DB conflict: ${e.message}",
                                createdAt = effectiveVersion,
                                severity = "ERROR",
                                origin = AuditOrigin.SYSTEM_ACTION.name
                            )
                            auditDao.insert(constraintAudit)
                        } catch (_: Exception) {}
                        syncResult = EventSyncResult.FAILED_RETRYABLE
                    }
                }

                if (syncResult.canAdvanceCursor()) {
                    if (effectiveVersion > 0L) {
                        updatedCursor = updatedCursor.advanceTo(effectiveVersion, id)
                    }
                } else {
                    Log.w("FirebaseSync", "Failed or blocked processing remote event $id in collection $collName ($syncResult). Halting cursor advancement at ${updatedCursor.lastServerTimestamp}.")
                    hasMore = false
                    break
                }
            }
        }
        return updatedCursor
    }

    private suspend fun mapToLocalAccount(id: String, d: Map<String, Any>, updatedAt: Long): LocalAccount? {
        val existing = accountDao.getByIdOneShot(id)
        return when (val res = RemoteEntityValidator.validateAndMapAccount(id, d, updatedAt, existing)) {
            is RemoteEntityValidationResult.Valid -> res.entity
            is RemoteEntityValidationResult.Malformed -> {
                Log.w("SyncRepositoryImpl", "Malformed remote account $id: ${res.reason}")
                null
            }
            is RemoteEntityValidationResult.Retryable -> null
        }
    }

    private suspend fun mapToLocalLedgerEntry(id: String, d: Map<String, Any>, updatedAt: Long = 0L): LocalLedgerEntry? {
        val existingLocalLedger = ledgerDao.getByIdOneShot(id)
        return when (val res = RemoteEntityValidator.validateAndMapLedgerEntry(id, d, updatedAt, existingLocalLedger)) {
            is RemoteEntityValidationResult.Valid -> res.entity
            is RemoteEntityValidationResult.Malformed -> {
                Log.w("SyncRepositoryImpl", "Malformed remote ledger $id: ${res.reason}")
                null
            }
            is RemoteEntityValidationResult.Retryable -> null
        }
    }

    private fun mapToImportBatch(id: String, d: Map<String, Any>, updatedAt: Long): ImportBatch? {
        return when (val res = RemoteEntityValidator.validateAndMapImportBatch(id, d, updatedAt)) {
            is RemoteEntityValidationResult.Valid -> res.entity
            is RemoteEntityValidationResult.Malformed -> {
                Log.w("SyncRepositoryImpl", "Malformed remote import batch $id: ${res.reason}")
                null
            }
            is RemoteEntityValidationResult.Retryable -> null
        }
    }

    private fun mapToAuditLog(id: String, d: Map<String, Any>): AuditLog {
        return AuditLog(
            id = id,
            action = d["action"] as? String ?: "ACTION",
            entityType = d["entityType"] as? String,
            entityId = d["entityId"] as? String,
            summary = d["summary"] as? String ?: "",
            createdAt = (d["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
            metadataJsonMasked = d["metadataJsonMasked"] as? String,
            severity = d["severity"] as? String ?: "INFO",
            actor = d["actor"] as? String ?: "system",
            signature = d["signature"] as? String,
            origin = d["origin"] as? String ?: AuditOrigin.SYSTEM_ACTION.name
        )
    }

    override suspend fun anonymousSignIn(): String? {
        val fbAuth = auth ?: return null
        return try {
            val result = fbAuth.signInAnonymously().await()
            val uid = result.user?.uid
            if (uid != null) {
                _syncState.value = SyncStatusState.IDLE
            }
            uid
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("FirebaseSync", "Anonymous sign in failed", e)
            null
        }
    }

    override suspend fun emailSignIn(email: String, password: String): String? {
        val fbAuth = auth ?: return null
        return try {
            val credential = EmailAuthProvider.getCredential(email, password)
            val currentUser = fbAuth.currentUser
            val result = if (currentUser != null && currentUser.isAnonymous) {
                try {
                    currentUser.linkWithCredential(credential).await()
                } catch (collision: FirebaseAuthUserCollisionException) {
                    Log.w("FirebaseSync", "User collision during linkWithCredential (email), falling back to signInWithCredential", collision)
                    fbAuth.signInWithCredential(credential).await()
                }
            } else {
                fbAuth.signInWithEmailAndPassword(email, password).await()
            }
            val uid = result.user?.uid
            if (uid != null) {
                _syncState.value = SyncStatusState.IDLE
                triggerSettingsSync(uid, "email_sign_in")
            }
            uid
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("FirebaseSync", "Email/password sign in failed", e)
            null
        }
    }

    override suspend fun googleSignIn(idToken: String): String? {
        if (AppBuildConfig.DEBUG && idToken == "simulated_google_token") {
            _syncState.value = SyncStatusState.IDLE
            return "simulated_google_uid"
        }
        val fbAuth = auth ?: return null
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val currentUser = fbAuth.currentUser
            val result = if (currentUser != null && currentUser.isAnonymous) {
                try {
                    currentUser.linkWithCredential(credential).await()
                } catch (collision: FirebaseAuthUserCollisionException) {
                    Log.w("FirebaseSync", "User collision during linkWithCredential (Google), falling back to signInWithCredential", collision)
                    fbAuth.signInWithCredential(credential).await()
                }
            } else {
                fbAuth.signInWithCredential(credential).await()
            }
            val uid = result.user?.uid
            if (uid != null) {
                _syncState.value = SyncStatusState.IDLE
                triggerSettingsSync(uid, "google_sign_in")
            }
            uid
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("FirebaseSync", "Google sign in failed", e)
            null
        }
    }

    override fun triggerSettingsSync(uid: String?, reason: String) {
        syncScope.launch {
            try {
                Log.d("FirebaseSync", "Settings sync triggered: reason=$reason")
                syncUserSettings(uid)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("FirebaseSync", "Settings sync failed (reason=$reason)", e)
            }
        }
    }

    internal suspend fun syncUserSettings(uid: String? = null): Boolean {
        val targetUid = uid ?: auth?.currentUser?.uid ?: return false
        val fbFirestore = firestore ?: return false
        return settingsSyncMutex.withLock {
            try {
                kotlinx.coroutines.withTimeoutOrNull(10000) {
                    val userDocRef = fbFirestore.collection("users").document(targetUid)
                    val snapshot = userDocRef.get(Source.SERVER).await()

                    // RC-09b / P5-G6-REQ-02: Strict session isolation verification after network await
                    val activeUidAfterFetch = auth?.currentUser?.uid
                    if (activeUidAfterFetch != targetUid) {
                        Log.w("FirebaseSync", "Session identity changed during settings fetch await (targetUid=$targetUid, activeUid=$activeUidAfterFetch). Aborting syncUserSettings.")
                        return@withTimeoutOrNull false
                    }

                    if (snapshot.metadata.hasPendingWrites() || snapshot.metadata.isFromCache) {
                        return@withTimeoutOrNull false
                    }

                    val remoteIspUser = snapshot.getString("ispAdminUsername")
                    val encIspPass = snapshot.getString("encIspAdminPassword")
                    val encDepositPass = snapshot.getString("encDepositPassword")
                    val remoteUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(snapshot.get("updatedAt")) ?: 0L

                    val decryptedRemoteIsp = if (!encIspPass.isNullOrBlank()) CloudSecretEncryptor.decryptSecret(encIspPass, targetUid) else null
                    val decryptedRemoteDeposit = if (!encDepositPass.isNullOrBlank()) CloudSecretEncryptor.decryptSecret(encDepositPass, targetUid) else null

                    val localIspUser = prefManager.getIspAdminUsername() ?: ""
                    val localIspPass = prefManager.getIspAdminPassword() ?: ""
                    val localDepositPass = prefManager.getDepositPassword() ?: ""

                    val lastSyncedServerTimestamp = prefManager.getSettingsLastSyncedTimestamp()
                    val hasLocalMutation = prefManager.hasSettingsLocalMutation()

                    // Check if local and remote values match
                    val isIdentical = (localIspUser == (remoteIspUser ?: "")) &&
                            (localIspPass == (decryptedRemoteIsp ?: "")) &&
                            (localDepositPass == (decryptedRemoteDeposit ?: ""))

                    if (isIdentical) {
                        if (remoteUpdatedAt > 0L) {
                            prefManager.saveSettingsLastSyncedTimestamp(remoteUpdatedAt)
                        }
                        prefManager.clearSettingsLocalMutation()
                        Log.i("FirebaseSync", "syncUserSettings: Local and remote settings are identical. No remote write needed.")
                        return@withTimeoutOrNull true
                    }

                    // Distributed winner selection without device clock authority (INV-06 / INV-10):
                    // 1. If remote document exists and was updated on server newer than our last synced baseline (or local was never mutated),
                    //    authoritative remote server state wins and updates local storage.
                    if (snapshot.exists() && (remoteUpdatedAt > lastSyncedServerTimestamp || !hasLocalMutation)) {
                        if (remoteIspUser != null) prefManager.saveIspAdminUsername(remoteIspUser, fromRemote = true)
                        if (decryptedRemoteIsp != null) prefManager.saveIspAdminPassword(decryptedRemoteIsp, fromRemote = true)
                        if (decryptedRemoteDeposit != null) prefManager.saveDepositPassword(decryptedRemoteDeposit, fromRemote = true)
                        prefManager.saveSettingsLastSyncedTimestamp(remoteUpdatedAt)
                        prefManager.clearSettingsLocalMutation()
                        Log.i("FirebaseSync", "syncUserSettings: Applied authoritative remote settings to local state (remoteUpdatedAt=$remoteUpdatedAt, lastSynced=$lastSyncedServerTimestamp).")
                        return@withTimeoutOrNull true
                    }

                    // 2. If local was mutated and remote has not changed since last sync (or remote doc does not exist),
                    //    upload local settings to remote Firestore and record new server timestamp.
                    if (hasLocalMutation || !snapshot.exists()) {
                        val updates = mutableMapOf<String, Any?>()
                        if (localIspUser.isNotBlank()) {
                            updates["ispAdminUsername"] = localIspUser
                        }
                        if (localIspPass.isNotBlank()) {
                            val enc = CloudSecretEncryptor.encryptSecret(localIspPass, targetUid)
                            if (enc != null) updates["encIspAdminPassword"] = enc
                        }
                        if (localDepositPass.isNotBlank()) {
                            val enc = CloudSecretEncryptor.encryptSecret(localDepositPass, targetUid)
                            if (enc != null) updates["encDepositPassword"] = enc
                        }

                        if (updates.isNotEmpty()) {
                            updates["updatedAt"] = com.google.firebase.firestore.FieldValue.serverTimestamp()
                            updates["payloadKind"] = "FULL_SNAPSHOT"
                            userDocRef.set(updates, SetOptions.merge()).await()

                            val updatedSnapshot = userDocRef.get().await()

                            // RC-09b / P5-G6-REQ-02: Re-check session identity after write await
                            val activeUidAfterWrite = auth?.currentUser?.uid
                            if (activeUidAfterWrite != targetUid) {
                                Log.w("FirebaseSync", "Session identity changed during settings write await (targetUid=$targetUid, activeUid=$activeUidAfterWrite). Aborting local preference update.")
                                return@withTimeoutOrNull false
                            }

                            val newRemoteUpdatedAt = RemoteSyncCursor.parseRemoteTimestamp(updatedSnapshot.get("updatedAt")) ?: 0L
                            if (newRemoteUpdatedAt > 0L) {
                                prefManager.saveSettingsLastSyncedTimestamp(newRemoteUpdatedAt)
                            }
                            prefManager.clearSettingsLocalMutation()
                            Log.i("FirebaseSync", "syncUserSettings: Uploaded newer local settings to remote Firestore (newRemoteUpdatedAt=$newRemoteUpdatedAt).")
                        }
                        return@withTimeoutOrNull true
                    }

                    true
                } ?: false
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("FirebaseSync", "Error syncing user settings with Firestore", e)
                false
            }
        }
    }

    override fun getFirebaseUid(): String? {
        return auth?.currentUser?.uid
    }

    override suspend fun signOut(force: Boolean, clearData: Boolean) {
        val mode = if (clearData) DataOperationMode.CLEAR_DATA else DataOperationMode.SYNC
        DataOperationCoordinator.withOperation(mode) {
            val pendingCount = appDatabase.syncOutboxDao().getAllUnsyncedCount()
            if (clearData && !force && pendingCount > 0) {
                throw IllegalStateException("UNSYNCED_CHANGES:$pendingCount")
            }
            try {
                auth?.signOut()
                stopRealtimeSync()
                if (clearData) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        appDatabase.clearAllData()
                    }
                }
                try {
                    val prefs = com.example.core.security.PreferenceManager(context)
                    prefs.saveUsername("")
                    prefs.savePassword("")
                    prefs.saveDepositPassword("")
                    prefs.saveIspAdminUsername("")
                    prefs.saveIspAdminPassword("")
                    prefs.saveAuthToken("")
                    prefs.saveEarthlinkApiToken(null)
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    Log.e("FirebaseSync", "Failed to clear ISP credentials on sign out", e)
                }
                _syncState.value = SyncStatusState.AUTH_REQUIRED
                Log.d("FirebaseSync", "Successfully signed out from Firebase Auth (clearData=$clearData)")
            } catch (e: kotlinx.coroutines.CancellationException) { 
                throw e 
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("FirebaseSync", "Error signing out from Firebase Auth", e)
                throw e
            }
        }
    }
}
