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
    private val gateway: com.example.domain.repository.EarthlinkGateway
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
                val items = gateway.getAccountStatement()
                _transactions.value = items
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }
}
