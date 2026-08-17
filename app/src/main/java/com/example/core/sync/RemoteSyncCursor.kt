package com.example.core.sync

/**
 * Explicit representation of the remote synchronization cursor.
 * Guarantees INV-04: The cursor represents the greatest valid remote server timestamp
 * actually observed from successfully processed remote documents.
 * It NEVER uses local clock (System.currentTimeMillis()) as the remote cursor.
 */
data class RemoteSyncCursor(
    val lastServerTimestamp: Long = 0L,
    val lastDocumentId: String = ""
) : Comparable<RemoteSyncCursor> {
    override fun compareTo(other: RemoteSyncCursor): Int {
        val tsCompare = this.lastServerTimestamp.compareTo(other.lastServerTimestamp)
        if (tsCompare != 0) return tsCompare
        return this.lastDocumentId.compareTo(other.lastDocumentId)
    }
    /**
     * Attempts to advance the cursor to [candidateTimestamp] and [candidateDocId].
     * The cursor advances IF AND ONLY IF:
     * 1. [candidateTimestamp] > [lastServerTimestamp], OR
     * 2. [candidateTimestamp] == [lastServerTimestamp] AND [candidateDocId] > [lastDocumentId].
     */
    fun advanceTo(candidateTimestamp: Long, candidateDocId: String = ""): RemoteSyncCursor {
        if (candidateTimestamp <= 0L) return this
        return if (candidateTimestamp > lastServerTimestamp) {
            RemoteSyncCursor(candidateTimestamp, candidateDocId)
        } else if (candidateTimestamp == lastServerTimestamp && candidateDocId > lastDocumentId) {
            RemoteSyncCursor(candidateTimestamp, candidateDocId)
        } else {
            this
        }
    }

    /**
     * Formats the cursor as a string for persistence ("timestamp:docId" or "timestamp").
     */
    fun toCursorString(): String {
        return if (lastDocumentId.isNotEmpty()) {
            "$lastServerTimestamp:$lastDocumentId"
        } else {
            lastServerTimestamp.toString()
        }
    }

    companion object {
        val EMPTY = RemoteSyncCursor(0L, "")

        /**
         * Parses a persisted cursor string ("timestamp:docId" or "timestamp").
         */
        fun parseCursorString(raw: String?): RemoteSyncCursor {
            if (raw.isNullOrEmpty()) return EMPTY
            val parts = raw.split(":", limit = 2)
            val ts = parts[0].toLongOrNull() ?: return EMPTY
            val docId = if (parts.size > 1) parts[1] else ""
            return RemoteSyncCursor(ts, docId)
        }

        /**
         * Safely parses and validates a remote server timestamp from a raw remote field value.
         * Returns null if missing, malformed, non-positive, or of an unrecognized type.
         */
        fun parseRemoteTimestamp(value: Any?): Long? {
            return when (value) {
                is Long -> value.takeIf { it > 0L }
                is Number -> value.toLong().takeIf { it > 0L }
                is com.google.firebase.Timestamp -> value.toDate().time.takeIf { it > 0L }
                is String -> value.toLongOrNull()?.takeIf { it > 0L }
                else -> null
            }
        }
    }
}
