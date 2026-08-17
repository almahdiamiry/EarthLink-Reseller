package com.example.core.sync

/**
 * Global shared synchronization barrier and exclusive lock.
 * Delegates all locking mechanisms to [DataOperationCoordinator] to guarantee unified ownership.
 */
object DataMaintenanceLock {
    val isMaintenanceActive: Boolean
        get() = DataOperationCoordinator.isMaintenanceActive

    suspend fun <T> withMaintenanceLock(action: suspend () -> T): T {
        return DataOperationCoordinator.withOperation(DataOperationMode.IMPORT, action)
    }

    suspend fun <T> withExclusiveLock(action: suspend () -> T): T {
        return DataOperationCoordinator.withOperation(DataOperationMode.SYNC, action)
    }

    suspend fun <T> tryWithExclusiveLock(action: suspend () -> T): T? {
        return DataOperationCoordinator.tryWithOperation(DataOperationMode.SYNC, action)
    }

    fun isLocked(): Boolean = DataOperationCoordinator.isLocked
}
