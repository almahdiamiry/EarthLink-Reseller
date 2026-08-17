package com.example.core.sync

/**
 * Explicit result representing the processing status of a remote synchronization event.
 * Replaces generic Boolean return types in remote sync processing pipelines.
 */
enum class EventSyncResult {
    /**
     * Event was successfully applied to local state.
     */
    APPLIED,

    /**
     * Event was skipped because it was already present or previously processed.
     */
    SKIPPED_DUPLICATE,

    /**
     * Event had a deterministic, permanent identity conflict (e.g. sourceExternalId collision) and was quarantined.
     * Represents a final disposition; cursor can advance past this event.
     */
    QUARANTINED_CONFLICT,

    /**
     * Event contained permanently malformed or invalid payload data and was quarantined.
     * Represents a final disposition; cursor can advance past this event.
     */
    QUARANTINED_MALFORMED,

    /**
     * Legacy alias for accepted quarantine dispositions.
     */
    QUARANTINED_ACCEPTED,

    /**
     * Event has missing, zero, or negative remote version.
     * Cursor CANNOT advance and processing MUST halt immediately to prevent skipping.
     */
    BLOCKED_INVALID_VERSION,

    /**
     * Event processing failed due to a retryable error (e.g. FK error, DB schema issue, missing parent dependency).
     * Cursor MUST NOT advance past this event.
     */
    FAILED_RETRYABLE;

    /**
     * Authoritative logic dictating whether a cursor may advance past an event producing this result.
     */
    fun canAdvanceCursor(): Boolean {
        return when (this) {
            APPLIED, SKIPPED_DUPLICATE, QUARANTINED_CONFLICT, QUARANTINED_MALFORMED, QUARANTINED_ACCEPTED -> true
            BLOCKED_INVALID_VERSION, FAILED_RETRYABLE -> false
        }
    }

    companion object {
        val VALID_APPLIED = APPLIED
        val QUARANTINED_WITH_VALID_VERSION = QUARANTINED_MALFORMED
        val QUARANTINED_MALFORMED_PAYLOAD_VALID_VERSION = QUARANTINED_MALFORMED
        val INVALID_VERSION_BLOCKS_CURSOR = BLOCKED_INVALID_VERSION
        val RETRY_REQUIRED = FAILED_RETRYABLE
    }
}
