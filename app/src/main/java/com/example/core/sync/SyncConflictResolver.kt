package com.example.core.sync

enum class ConflictDecision {
    IGNORE_LOCAL_PENDING,
    IGNORE_STALE_REMOTE,
    APPLY_DELETE,
    APPLY_UPSERT,
    REJECT_MALFORMED
}

enum class TimestampComparisonResult {
    LOCAL_NEWER,
    REMOTE_NEWER,
    EQUAL,
    UNKNOWN_MISSING
}

object SyncConflictResolver {

    /**
     * Explicitly compares local mutation timestamp against remote mutation timestamp.
     */
    fun compareTimestamps(localTimestamp: Long?, remoteTimestamp: Long?): TimestampComparisonResult {
        if (localTimestamp == null || localTimestamp <= 0L) {
            return TimestampComparisonResult.UNKNOWN_MISSING
        }
        if (remoteTimestamp == null || remoteTimestamp <= 0L) {
            return TimestampComparisonResult.LOCAL_NEWER
        }
        return when {
            remoteTimestamp > localTimestamp -> TimestampComparisonResult.REMOTE_NEWER
            remoteTimestamp < localTimestamp -> TimestampComparisonResult.LOCAL_NEWER
            else -> TimestampComparisonResult.EQUAL
        }
    }

    /**
     * Resolves conflict between local entity state and incoming remote change.
     *
     * @param localTimestamp Timestamp of local entity (remote_version metadata), or null if entity has no remote version.
     * @param remoteUpdatedAt Updated timestamp of incoming remote change.
     * @param remoteDeletedAt Deletion timestamp of incoming remote change (null or <= 0 if not deleted).
     * @param hasActiveLocalMutation True if local entity has unsynced pending changes in outbox (pending, syncing, failed).
     */
    fun resolveIncomingChange(
        localTimestamp: Long?,
        remoteUpdatedAt: Long,
        remoteDeletedAt: Long? = null,
        hasActiveLocalMutation: Boolean
    ): ConflictDecision {
        // Malformed remote state -> reject safely as malformed
        if (remoteUpdatedAt <= 0L && (remoteDeletedAt == null || remoteDeletedAt <= 0L)) {
            return ConflictDecision.REJECT_MALFORMED
        }

        // Local newer active mutation -> local wins / outbox remains
        // Even if dead-letter exists, it will NOT be flagged as active, allowing remote updates
        if (hasActiveLocalMutation) {
            return ConflictDecision.IGNORE_LOCAL_PENDING
        }

        val effectiveRemoteTimestamp = if (remoteDeletedAt != null && remoteDeletedAt > 0L) remoteDeletedAt else remoteUpdatedAt
        val isDeleted = remoteDeletedAt != null && remoteDeletedAt > 0L

        return when (compareTimestamps(localTimestamp, effectiveRemoteTimestamp)) {
            TimestampComparisonResult.UNKNOWN_MISSING -> {
                if (isDeleted) ConflictDecision.APPLY_DELETE else ConflictDecision.APPLY_UPSERT
            }
            TimestampComparisonResult.REMOTE_NEWER -> {
                if (isDeleted) ConflictDecision.APPLY_DELETE else ConflictDecision.APPLY_UPSERT
            }
            TimestampComparisonResult.LOCAL_NEWER -> {
                ConflictDecision.IGNORE_STALE_REMOTE
            }
            TimestampComparisonResult.EQUAL -> {
                // Equal timestamps -> deterministic tie breaker. Remote wins for convergence.
                if (isDeleted) ConflictDecision.APPLY_DELETE else ConflictDecision.APPLY_UPSERT
            }
        }
    }
}

