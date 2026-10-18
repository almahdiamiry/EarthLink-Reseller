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
    private val prefs: com.example.core.security.PreferenceManager,
    private val ledgerRepo: com.example.domain.repository.LocalLedgerRepository? = null,
    private val sasGatewayRouter: com.example.core.network.SasGatewayRouter? = null,
    private val earthlinkGateway: com.example.domain.repository.EarthlinkGateway? = null
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

    val pendingGatewayOperations: StateFlow<List<PendingExternalOperation>> =
        ledgerRepo?.getPendingOperationsFlow()
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            ?: MutableStateFlow(emptyList<PendingExternalOperation>()).asStateFlow()

    private val _operationActionMessage = MutableStateFlow<String?>(null)
    val operationActionMessage = _operationActionMessage.asStateFlow()

    fun clearOperationActionMessage() {
        _operationActionMessage.value = null
    }

    fun triggerGatewaySweep() {
        viewModelScope.launch {
            _isSyncingProgress.value = true
            try {
                // 1. EarthLink statement sweep FIRST (matches SyncWorker.kt and cold-start in EarthlinkApp.kt)
                earthlinkGateway?.let { elGw ->
                    ledgerRepo?.sweepAndResolvePendingOperations(elGw)
                }
                // 2. Router sweep SECOND (for SAMM and non-financial operations)
                sasGatewayRouter?.let { router ->
                    ledgerRepo?.sweepAndResolvePendingOperations(router)
                }
                refreshPendingCount()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("SyncStatusVM", "Gateway sweep error", e)
            } finally {
                _isSyncingProgress.value = false
            }
        }
    }

    fun confirmOperationSuccess(
        op: PendingExternalOperation,
        evidenceNote: String,
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            if (evidenceNote.trim().length < 5) {
                val errorMsg = "يجب أن تكون ملاحظة الإثبات 5 أحرف على الأقل."
                _operationActionMessage.value = errorMsg
                onComplete(false, errorMsg)
                return@launch
            }
            try {
                val repo = ledgerRepo ?: run {
                    val errorMsg = "Local ledger repository unavailable."
                    _operationActionMessage.value = errorMsg
                    onComplete(false, errorMsg)
                    return@launch
                }
                val resolution = repo.submitManualVerificationEvidence(
                    businessTransactionId = op.businessTransactionId,
                    externalEvidence = evidenceNote.trim()
                )
                val success = resolution.result == UnknownOutcomeResolutionResult.VERIFIED_SUCCESS
                val msg = if (success) "تم تأكيد نجاح العملية وتسجيل القيد المالي بنجاح." else resolution.diagnosticMessage
                _operationActionMessage.value = msg
                refreshPendingCount()
                onComplete(success, msg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val errorMsg = e.message ?: "فشل تأكيد العملية يدوياً"
                _operationActionMessage.value = errorMsg
                Log.e("SyncStatusVM", "Manual success verification failed: $errorMsg", e)
                onComplete(false, errorMsg)
            }
        }
    }

    fun markOperationFailed(
        op: PendingExternalOperation,
        failureReason: String = "إلغاء وتأكيد فشل يدوي من قبل الموزع",
        onComplete: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            try {
                val repo = ledgerRepo ?: run {
                    val errorMsg = "Local ledger repository unavailable."
                    _operationActionMessage.value = errorMsg
                    onComplete(false, errorMsg)
                    return@launch
                }
                val success = repo.resolvePendingOperationVerifiedFailure(
                    businessTransactionId = op.businessTransactionId,
                    diagnostic = failureReason
                )
                val msg = if (success) "تم إلغاء العملية وتأكيد فشلها دون تسجيل أي ديون." else "تعذر إلغاء العملية."
                _operationActionMessage.value = msg
                refreshPendingCount()
                onComplete(success, msg)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val errorMsg = e.message ?: "فشل إلغاء العملية"
                _operationActionMessage.value = errorMsg
                Log.e("SyncStatusVM", "Manual failure resolution failed: $errorMsg", e)
                onComplete(false, errorMsg)
            }
        }
    }
}
