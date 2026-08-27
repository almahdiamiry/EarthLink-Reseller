package com.example.core.sync

import org.junit.Test
import org.junit.Assert.*

class ConflictResolverAuditTest {
    @Test
    fun testReplayIdenticalVersion() {
        val decision = SyncConflictResolver.resolveIncomingChange(
            localTimestamp = 1000L,
            remoteUpdatedAt = 1000L,
            remoteDeletedAt = null,
            hasActiveLocalMutation = false
        )
        // Does it apply upsert or ignore?
        println("Decision for identical version: $decision")
    }
}
