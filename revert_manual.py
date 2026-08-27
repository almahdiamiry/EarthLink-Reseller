import re
with open('app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt', 'r') as f:
    content = f.read()

content = content.replace('suspend fun processEvent(event: RemoteEvent, passedCapturedGen: Long? = null): EventSyncResult {', 'suspend fun processEvent(event: RemoteEvent): EventSyncResult {')
content = content.replace('val capturedGen = passedCapturedGen ?: appDatabase.getGeneration()', 'val capturedGen = appDatabase.getGeneration()')
target = """                    Log.w(
                        "RemoteSyncCoordinator",
                        "Lineage generation mismatch during processEvent for ${event.entityType}:${event.entityId}: capturedGen=$capturedGen != currentGen=$currentGen. Rejecting stale remote result."
                    )
                    result = EventSyncResult.FAILED_RETRYABLE"""
replacement = """                    Log.w(
                        "RemoteSyncCoordinator",
                        "Lineage generation mismatch during processEvent for ${event.entityType}:${event.entityId}: capturedGen=$capturedGen != currentGen=$currentGen. Rejecting stale remote result."
                    )
                    result = EventSyncResult.SKIPPED_DUPLICATE"""
content = content.replace(target, replacement)
with open('app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt', 'w') as f:
    f.write(content)

with open('app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt', 'r') as f:
    content = f.read()

content = content.replace('private suspend fun pullRemoteChanges(\n        uid: String,\n        collName: String,\n        currentCursor: RemoteSyncCursor,\n        fbFirestore: FirebaseFirestore,\n        passGeneration: Long? = null\n    ): RemoteSyncCursor {',
                          'private suspend fun pullRemoteChanges(\n        uid: String,\n        collName: String,\n        currentCursor: RemoteSyncCursor,\n        fbFirestore: FirebaseFirestore\n    ): RemoteSyncCursor {')

content = content.replace('syncResult = remoteSyncCoordinator.processEvent(event, passGeneration)', 'syncResult = remoteSyncCoordinator.processEvent(event)')

target1 = 'val fbFirestore = firestore\n        if (fbAuth == null || fbFirestore == null) {\n            _syncState.value = SyncStatusState.OFFLINE\n            _syncProgress.value = _syncProgress.value.copy(isSyncing = false, phase = SyncPhase.FAILED, lastError = "Offline")\n            return false\n        }\n\n        val passGeneration = metadataDao.getGeneration()'
replacement1 = 'val fbFirestore = firestore\n        if (fbAuth == null || fbFirestore == null) {\n            _syncState.value = SyncStatusState.OFFLINE\n            _syncProgress.value = _syncProgress.value.copy(isSyncing = false, phase = SyncPhase.FAILED, lastError = "Offline")\n            return false\n        }'
content = content.replace(target1, replacement1)

target2 = """            // 2. Downward sync (pull remote changes per collection)
            val collectionsToSync = listOf("local_accounts", "local_ledger_entries", "import_batches", "audit_logs")
            for (collName in collectionsToSync) {
                if (metadataDao.getGeneration() != passGeneration) {
                    Log.w("FirebaseSync", "Generation changed during sync pass. Aborting downward sync loop.")
                    break
                }
                val initialCursor = getCollectionCursor(collName)
                val updatedCursor = pullRemoteChanges(currentUid, collName, initialCursor, fbFirestore, passGeneration)

                if (metadataDao.getGeneration() == passGeneration) {
                    if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                        (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                        saveCollectionCursor(collName, updatedCursor)
                    }
                } else {
                    Log.w("FirebaseSync", "Generation changed after pull for $collName. Aborting cursor save.")
                    break
                }
            }"""
replacement2 = """            // 2. Downward sync (pull remote changes per collection)
            val collectionsToSync = listOf("local_accounts", "local_ledger_entries", "import_batches", "audit_logs")
            for (collName in collectionsToSync) {
                val initialCursor = getCollectionCursor(collName)
                val updatedCursor = pullRemoteChanges(currentUid, collName, initialCursor, fbFirestore)

                if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                    (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                    saveCollectionCursor(collName, updatedCursor)
                }
            }"""
content = content.replace(target2, replacement2)

target3 = """                    singleFlightMutex.withLock {
                        val passGeneration = appDatabase.syncMetadataDao().getGeneration()
                        for (collName in collectionsToSync) {
                            if (appDatabase.syncMetadataDao().getGeneration() != passGeneration) {
                                Log.w("FirebaseSync", "Generation changed. Aborting ReplaceAll reconciliation pull.")
                                break
                            }
                            val initialCursor = getCollectionCursor(collName)
                            var updatedCursor = initialCursor
                            try {
                                updatedCursor = pullRemoteChanges(uid, collName, initialCursor, fbFirestore, passGeneration)
                                if (appDatabase.syncMetadataDao().getGeneration() == passGeneration) {
                                    if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                                        (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                                        saveCollectionCursor(collName, updatedCursor)
                                    }
                                } else {
                                    Log.w("FirebaseSync", "Generation changed. Aborting ReplaceAll reconciliation save.")
                                    break
                                }"""
replacement3 = """                    singleFlightMutex.withLock {
                        for (collName in collectionsToSync) {
                            val initialCursor = getCollectionCursor(collName)
                            var updatedCursor = initialCursor
                            try {
                                updatedCursor = pullRemoteChanges(uid, collName, initialCursor, fbFirestore)
                                if (updatedCursor.lastServerTimestamp > initialCursor.lastServerTimestamp ||
                                    (updatedCursor.lastServerTimestamp == initialCursor.lastServerTimestamp && updatedCursor.lastDocumentId > initialCursor.lastDocumentId)) {
                                    saveCollectionCursor(collName, updatedCursor)
                                }"""
content = content.replace(target3, replacement3)

target4 = """            val completedTime = System.currentTimeMillis()
            if (metadataDao.getGeneration() == passGeneration) {
                prefManager.saveLastSyncTime(completedTime)
                metadataDao.put("last_sync_timestamp", completedTime.toString())
            } else {
                Log.w("FirebaseSync", "Generation changed. Skipping last_sync_timestamp update.")
            }"""
replacement4 = """            val completedTime = System.currentTimeMillis()
            prefManager.saveLastSyncTime(completedTime)
            metadataDao.put("last_sync_timestamp", completedTime.toString())"""
content = content.replace(target4, replacement4)

with open('app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt', 'w') as f:
    f.write(content)
