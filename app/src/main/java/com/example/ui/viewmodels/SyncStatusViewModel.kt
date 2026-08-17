package com.example.ui.viewmodels
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EarthlinkApp
import com.example.core.model.*
import com.example.domain.repository.SyncStatusState
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

    private val _lastSyncTime = MutableStateFlow(prefs.getLastSyncTime())
    val lastSyncTime = _lastSyncTime.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount = _pendingCount.asStateFlow()

    private val _deadLetterCount = MutableStateFlow(0)
    val deadLetterCount = _deadLetterCount.asStateFlow()

    val auditLogs = audit.getAuditLogs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSyncingProgress = MutableStateFlow(false)
    val isSyncingProgress = _isSyncingProgress.asStateFlow()

    private val _syncSuccessTrigger = MutableStateFlow(false)
    val syncSuccessTrigger = _syncSuccessTrigger.asStateFlow()

    init {
        refreshPendingCount()
    }

    fun refreshPendingCount() {
        viewModelScope.launch {
            try {
                _pendingCount.value = syncRepo.getPendingOutboxCount()
                _deadLetterCount.value = syncRepo.getDeadLetterCount()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("SyncStatusVM", "Failed to query pending or dead-letter queue", e)
            }
        }
    }

    fun retryDeadLetters() {
        viewModelScope.launch {
            try {
                syncRepo.retryDeadLetters()
                refreshPendingCount()
                triggeredSync()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("SyncStatusVM", "Failed to reset dead-letter queue", e)
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
                if (success) {
                    _lastSyncTime.value = prefs.getLastSyncTime()
                    _syncSuccessTrigger.value = true
                    refreshPendingCount()
                }
            } finally {
                _isSyncingProgress.value = false
            }
        }
    }

    fun getFirebaseUid(): String? = syncRepo.getFirebaseUid()


}
