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

class StatementViewModel(
    private val gateway: com.example.domain.repository.EarthlinkGateway,
    private val ledgerRepository: com.example.domain.repository.LocalLedgerRepository
) : ViewModel() {

    private val _transactions = MutableStateFlow<List<AccountStatementItem>>(emptyList())
    val transactions = _transactions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadStatement()
    }

    fun loadStatement() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val remoteItems = gateway.getAccountStatement()
                val syntheticItems = ledgerRepository.getPendingSyntheticHistory()
                
                // Map synthetic ledger entries to Statement view models
                val mappedSynthetic = syntheticItems.map { ledger ->
                    AccountStatementItem(
                        occurredAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                        operation = "PENDING_${ledger.typeRaw}",
                        depositAmount = 0.0,
                        withdrawalAmount = ledger.amountIqd,
                        balanceAfter = 0.0,
                        note = ledger.note
                    )
                }

                _transactions.value = mappedSynthetic + remoteItems
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
