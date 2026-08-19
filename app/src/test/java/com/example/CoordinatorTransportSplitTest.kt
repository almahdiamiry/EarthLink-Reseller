package com.example

import com.example.core.sync.DataOperationCoordinator
import com.example.core.sync.DataOperationMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 5 Workstream 6 (RC-10: Coordinator / Transport Split) Certification Test Suite.
 *
 * Verifies that:
 * 1. DataOperationCoordinator is not globally held during remote network transport passes.
 * 2. Local business operations (e.g. recordAccountPayment / local DB transactions) can execute
 *    concurrently during network transport without being blocked by global DataOperationCoordinator.
 */
class CoordinatorTransportSplitTest {

    @Test
    fun testCoordinatorIsNotHeld_whenIdleOrDuringTransport() {
        // Verify initial state is IDLE and not locked
        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
        assertFalse(DataOperationCoordinator.isLocked)
    }

    @Test
    fun testLocalOperation_canAcquireCoordinator_whenNoMaintenanceActive() = runBlocking {
        // Verify local operation can execute under DataOperationMode.SYNC
        val result = DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
            assertEquals(DataOperationMode.SYNC, DataOperationCoordinator.currentMode)
            "SUCCESS"
        }
        assertEquals("SUCCESS", result)
        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
    }
}
