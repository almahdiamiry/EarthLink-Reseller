package com.example

import com.example.core.sync.CoordinatorOwnershipToken
import com.example.core.sync.DataOperationCoordinator
import com.example.core.sync.DataOperationMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 3 Verification: DataOperationCoordinator ownership token semantics.
 *
 * Verifies that:
 * 1. Child coroutines (new Job) inherit context but CANNOT bypass mutex.
 * 2. Direct re-entrant calls within the same coroutine/job hierarchy do not deadlock.
 * 3. tryWithOperation respects mutex state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3CoordinatorMutexTokenTest {

    @Test
    fun childCoroutine_cannotBypassMutex() = runBlocking(Dispatchers.Default) {
        val childEnteredWhileParentActive = AtomicBoolean(false)
        val childStarted = CompletableDeferred<Unit>()
        val parentCanFinish = CompletableDeferred<Unit>()
        val executionOrder = mutableListOf<String>()

        val parentJob = launch {
            DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
                executionOrder.add("parent_entered")

                // Launch child coroutine (inherits coroutineContext including CoordinatorOwnershipToken, but has new Job)
                launch {
                    childStarted.complete(Unit)
                    // Child attempts another operation on DataOperationCoordinator
                    // Because child has a distinct Job, it MUST NOT bypass the mutex.
                    DataOperationCoordinator.withOperation(DataOperationMode.IMPORT) {
                        childEnteredWhileParentActive.set(true)
                        executionOrder.add("child_entered")
                    }
                    executionOrder.add("child_finished")
                }

                // Wait for child to attempt operation
                childStarted.await()
                delay(50) // Give child time to try acquiring mutex

                // Verify child did NOT enter while parent is active
                assertFalse("Child coroutine must NOT bypass mutex", childEnteredWhileParentActive.get())

                parentCanFinish.complete(Unit)
                executionOrder.add("parent_finished")
            }
        }

        parentJob.join()
        assertEquals(listOf("parent_entered", "parent_finished", "child_entered", "child_finished"), executionOrder)
    }

    @Test
    fun directReEntrantCall_doesNotDeadlock() = runBlocking(Dispatchers.Default) {
        val callSequence = mutableListOf<String>()

        withTimeout(5000) {
            DataOperationCoordinator.withOperation(DataOperationMode.SYNC) {
                callSequence.add("outer_start")
                assertEquals(DataOperationMode.SYNC, DataOperationCoordinator.currentMode)

                // Direct re-entrant call inside the same coroutine/job
                DataOperationCoordinator.withOperation(DataOperationMode.REMOTE_APPLY) {
                    callSequence.add("nested_start")
                    assertEquals(DataOperationMode.REMOTE_APPLY, DataOperationCoordinator.currentMode)
                    callSequence.add("nested_end")
                }

                // Mode restored to outer
                assertEquals(DataOperationMode.SYNC, DataOperationCoordinator.currentMode)
                callSequence.add("outer_end")
            }
        }

        assertEquals(
            listOf("outer_start", "nested_start", "nested_end", "outer_end"),
            callSequence
        )
        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
    }

    @Test
    fun tryWithOperation_respectsMutexAndSetsMode() = runBlocking(Dispatchers.Default) {
        val count = AtomicInteger(0)

        val result = DataOperationCoordinator.withOperation(DataOperationMode.BACKUP) {
            assertEquals(DataOperationMode.BACKUP, DataOperationCoordinator.currentMode)
            assertTrue(DataOperationCoordinator.isLocked)

            // Concurrent async attempt to acquire should fail immediately
            val concurrentResult = async {
                DataOperationCoordinator.tryWithOperation(DataOperationMode.RESTORE) {
                    count.incrementAndGet()
                    "should_not_run"
                }
            }.await()

            assertNull("tryWithOperation must return null when mutex is held", concurrentResult)
            "outer_success"
        }

        assertEquals("outer_success", result)
        assertEquals(0, count.get())
        assertEquals(DataOperationMode.IDLE, DataOperationCoordinator.currentMode)
        assertFalse(DataOperationCoordinator.isLocked)
    }
}
