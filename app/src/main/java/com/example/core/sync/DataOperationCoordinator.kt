package com.example.core.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Exclusive modes of data operations.
 */
enum class DataOperationMode {
    IDLE,
    SYNC,
    IMPORT,
    RESTORE,
    BACKUP,
    ROLLBACK,
    CLEAR_DATA,
    REMOTE_APPLY
}

/**
 * CoroutineContext element representing active coordinator ownership.
 * Enables safe re-entrant execution for nested coordinator-governed operations
 * within the same coroutine hierarchy without deadlocking.
 */
class CoordinatorOwnershipToken(
    val ownerId: String,
    val mode: DataOperationMode,
    var ownerJobId: Int
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CoordinatorOwnershipToken>
}

/**
 * DataOperationCoordinator (RC-B — Unified Data Operation Coordinator)
 *
 * Single authoritative coordinator providing atomic mutual exclusion across all
 * major database-modifying operations (Sync, Import, Restore, Backup, Rollback, ClearData, RemoteApply).
 */
object DataOperationCoordinator {
    private val mutex = Mutex()
    private val _currentMode = AtomicReference<DataOperationMode>(DataOperationMode.IDLE)

    val currentMode: DataOperationMode
        get() = _currentMode.get()

    val isMaintenanceActive: Boolean
        get() = when (_currentMode.get()) {
            DataOperationMode.IMPORT,
            DataOperationMode.RESTORE,
            DataOperationMode.BACKUP,
            DataOperationMode.ROLLBACK,
            DataOperationMode.CLEAR_DATA -> true
            else -> false
        }

    val isLocked: Boolean
        get() = mutex.isLocked || isMaintenanceActive

    /**
     * Executes an operation exclusively under the designated [DataOperationMode].
     * Supports coroutine-context re-entrancy: if the calling coroutine already holds
     * coordinator ownership via [CoordinatorOwnershipToken] under the exact same Job,
     * it executes re-entrantly. Launched child coroutines (with new Job instances)
     * must acquire the mutex normally.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun <T> withOperation(mode: DataOperationMode, action: suspend () -> T): T {
        val existingToken = coroutineContext[CoordinatorOwnershipToken]
        if (existingToken != null) {
            val currentJob = coroutineContext[Job]
            val currentJobId = currentJob?.hashCode() ?: 0
            val isDirectReEntrant = existingToken.ownerJobId == currentJobId

            if (isDirectReEntrant) {
                val previous = _currentMode.getAndSet(mode)
                return try {
                    val newToken = CoordinatorOwnershipToken(existingToken.ownerId, mode, 0)
                    withContext(newToken) {
                        newToken.ownerJobId = coroutineContext[Job]?.hashCode() ?: 0
                        action()
                    }
                } finally {
                    _currentMode.set(previous)
                }
            }
        }

        return mutex.withLock {
            val token = CoordinatorOwnershipToken(UUID.randomUUID().toString(), mode, 0)
            val previous = _currentMode.getAndSet(mode)
            try {
                withContext(token) {
                    token.ownerJobId = coroutineContext[Job]?.hashCode() ?: 0
                    action()
                }
            } finally {
                _currentMode.set(previous)
            }
        }
    }

    /**
     * Attempts to acquire exclusive lock for [mode] immediately without suspending.
     * Returns the result of [action] if acquired, or null if lock is currently held.
     */
    suspend fun <T> tryWithOperation(mode: DataOperationMode, action: suspend () -> T): T? {
        if (!mutex.tryLock()) {
            return null
        }
        val token = CoordinatorOwnershipToken(UUID.randomUUID().toString(), mode, 0)
        val previous = _currentMode.getAndSet(mode)
        return try {
            withContext(token) {
                token.ownerJobId = coroutineContext[Job]?.hashCode() ?: 0
                action()
            }
        } finally {
            _currentMode.set(previous)
            mutex.unlock()
        }
    }
}
