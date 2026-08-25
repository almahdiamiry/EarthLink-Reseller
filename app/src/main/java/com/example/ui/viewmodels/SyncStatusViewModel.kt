package com.example.ui.viewmodels
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EarthlinkApp
import com.example.core.model.*
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.UtowerImportPreview
import java.security.MessageDigest
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SyncStatusViewModel(
    private val syncRepo: com.example.domain.repository.SyncRepository,
    private val audit: com.example.domain.repository.AuditRepository,
    private val prefs: com.example.core.security.PreferenceManager
) : ViewModel() {

    val syncState = syncRepo.syncState
    val syncProgress = syncRepo.syncProgress

    private val _lastSyncTime = MutableStateFlow(prefs.getLastSyncTime())
    val lastSyncTime = _lastSyncTime.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()

    private val _failedCount = MutableStateFlow(0)
    val failedCount = _failedCount.asStateFlow()

    val auditLogs = audit.getAuditLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncingProgress = MutableStateFlow(false)
    val isSyncingProgress = _isSyncingProgress.asStateFlow()

    private val _syncSuccessTrigger = MutableStateFlow(false)
    val syncSuccessTrigger = _syncSuccessTrigger.asStateFlow()

    init {
        refreshPendingCount()
        viewModelScope.launch {
            syncRepo.syncState.collect {
                _lastSyncTime.value = prefs.getLastSyncTime()
                refreshPendingCount()
            }
        }
        viewModelScope.launch {
            syncRepo.syncProgress.collect { progress ->
                if (progress.lastCompletedTime > 0L) {
                    _lastSyncTime.value = progress.lastCompletedTime
                }
                refreshPendingCount()
            }
        }
    }

    fun refreshPendingCount() {
        viewModelScope.launch {
            try {
                _pendingCount.value = syncRepo.getPendingOutboxCount()
                _failedCount.value = syncRepo.getFailedCount()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("SyncStatusVM", "Failed to query pending or failed outbox queue", e)
            }
        }
    }

    fun retryFailedItems() {
        viewModelScope.launch {
            try {
                syncRepo.retryFailedItems()
                refreshPendingCount()
                triggeredSync()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("SyncStatusVM", "Failed to reset failed outbox items", e)
            }
        }
    }

    fun signInAnonymously() {
        viewModelScope.launch {
            syncRepo.anonymousSignIn()
        }
    }

    fun triggeredSync() {
        viewModelScope.launch {
            _isSyncingProgress.value = true
            try {
                val success = syncRepo.triggerSyncOneShot()
                _lastSyncTime.value = prefs.getLastSyncTime()
                if (success) {
                    _syncSuccessTrigger.value = true
                }
                refreshPendingCount()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("SyncStatusVM", "Manual sync triggered error", e)
            } finally {
                _isSyncingProgress.value = false
                _lastSyncTime.value = prefs.getLastSyncTime()
                refreshPendingCount()
            }
        }
    }

    fun getFirebaseUid(): String? = syncRepo.getFirebaseUid()
}
