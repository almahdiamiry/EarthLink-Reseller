package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.sync.CoordinatorOwnershipToken
import com.example.core.sync.DataOperationCoordinator
import com.example.core.sync.DataOperationMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented test suite verifying DataOperationCoordinator concurrency,
 * thread-safety, mode transitions, and mutual exclusion on the Android runtime.
 *
 * Covers Invariants: INV-11, INV-13
 */
@RunWith(AndroidJUnit4::class)
class CoordinatorInstrumentedTest {

    @Test
    fun testCoordinatorMutualExclusionUnderAndroidRuntime() = runBlocking(Dispatchers.Default) {
        val concurrentOperationsCount = 10
        val executionCounter = AtomicInteger(0)
        val activeConcurrentHolders = AtomicInteger(0)
        val maxConcurrentObserved = AtomicInteger(0)

        val deferreds = (1..concurrentOperationsCount).map { i ->
            async {
                val mode = if (i % 2 == 0) DataOperationMode.IMPORT else DataOperationMode.RESTORE
                DataOperationCoordinator.withOperation(mode) {
                    val currentConcurrent = activeConcurrentHolders.incrementAndGet()
                    var max = maxConcurrentObserved.get()
                    while (currentConcurrent > max) {
                        if (maxConcurrentObserved.compareAndSet(max, currentConcurrent)) break
                        max = maxConcurrentObserved.get()
                    }

                    // Simulate real I/O work
                    delay(25)

                    activeConcurrentHolders.decrementAndGet()
                    executionCounter.incrementAndGet()
                }
            }
        }

        deferreds.awaitAll()

        assertEquals("All scheduled operations must execute", concurrentOperationsCount, executionCounter.get())
        assertEquals("Max concurrent holders must be exactly 1 (strict mutual exclusion)", 1, maxConcurrentObserved.get())
        assertEquals("Mode must return to IDLE after completion", DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
        assertFalse("Coordinator must not remain locked", DataOperationCoordinator.isLocked)
    }

    @Test
    fun testCoordinatorReentrancyOnAndroidThread() = runBlocking(Dispatchers.Default) {
        var outerExecuted = false
        var innerExecuted = false

        DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
            outerExecuted = true
            assertEquals(DataOperationMode.IMPORT, DataOperationCoordinator.currentMode)

            // Re-entrant execution within same coroutine context
            DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
                innerExecuted = true
                assertEquals(DataOperationMode.IMPORT, DataOperationCoordinator.currentMode)
            }

            assertEquals(DataOperationMode.IMPORT, DataOperationCoordinator.currentMode)
        }

        assertTrue("Outer operation must have executed", outerExecuted)
        assertTrue("Inner re-entrant operation must have executed", innerExecuted)
        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
    }

    @Test
    fun testTryWithOperationNonBlockingOnContention() = runBlocking(Dispatchers.Default) {
        val lockAcquiredSignal = CompletableDeferred<Unit>()
        val releaseLockSignal = CompletableDeferred<Unit>()

        val holderJob = launch {
            DataOperationCoordinator.withOperation(DataOperationMode.BACKUP) {
                lockAcquiredSignal.complete(Unit)
                releaseLockSignal.await()
            }
        }

        lockAcquiredSignal.await()

        // Contention check: tryWithOperation should immediately fail (return null)
        val result = DataOperationCoordinator.tryWithOperation(DataOperationMode.SYNC) {
            "should_not_run"
        }
        assertNull("tryWithOperation must return null while lock is held by another coroutine", result)
        assertTrue("Coordinator must report locked", DataOperationCoordinator.isLocked)
        assertTrue("Coordinator must report maintenance active during BACKUP", DataOperationCoordinator.isMaintenanceActive)

        releaseLockSignal.complete(Unit)
        holderJob.join()

        // After release, tryWithOperation succeeds
        val successResult = DataOperationCoordinator.tryWithOperation(DataOperationMode.SYNC) {
            "success"
        }
        assertEquals("success", successResult)
        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
    }

    @Test
    fun testMaintenanceModeFlagsOnRealAndroidOS() = runBlocking(Dispatchers.Default) {
        val maintenanceModes = listOf(
            DataOperationMode.IMPORT,
            DataOperationMode.RESTORE,
            DataOperationMode.BACKUP,
            DataOperationMode.ROLLBACK,
            DataOperationMode.CLEAR_DATA
        )

        for (mode in maintenanceModes) {
            DataOperationCoordinator.withOperation(mode) {
                assertTrue("isMaintenanceActive must be true for $mode", DataOperationCoordinator.isMaintenanceActive)
                assertTrue("isLocked must be true for $mode", DataOperationCoordinator.isLocked)
                assertEquals(mode, DataOperationCoordinator.currentMode)
            }
        }

        // Non-maintenance mode
        DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
            assertFalse("isMaintenanceActive must be false for SYNC", DataOperationCoordinator.isMaintenanceActive)
            assertTrue("isLocked must be true while SYNC is executing", DataOperationCoordinator.isLocked)
            assertEquals(DataOperationMode.SYNC, DataOperationCoordinator.currentMode)
        }

        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
        assertFalse(DataOperationCoordinator.isMaintenanceActive)
        assertFalse(DataOperationCoordinator.isLocked)
    }
}
