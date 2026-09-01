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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val gateway: com.example.domain.repository.EarthlinkGateway,
    private val audit: com.example.domain.repository.AuditRepository,
    private val localAccountRepository: com.example.domain.repository.LocalAccountRepository,
    private val localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
    private val syncRepo: com.example.domain.repository.SyncRepository,
    val prefs: com.example.core.security.PreferenceManager,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) : ViewModel() {
    val localAccounts = localAccountRepository.getAllAccounts()

    private val _balance = MutableStateFlow(0.0)
    val balance = _balance.asStateFlow()

    private val _prepaidNeeded = MutableStateFlow(0.0)
    val prepaidNeeded = _prepaidNeeded.asStateFlow()

    private val _prepaidNeededDays = MutableStateFlow(7)
    val prepaidNeededDays = _prepaidNeededDays.asStateFlow()

    private val _isPrepaidLoading = MutableStateFlow(false)
    val isPrepaidLoading = _isPrepaidLoading.asStateFlow()

    private val _testCount = MutableStateFlow(0)
    val testCount = _testCount.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _isCredentialsEmpty = MutableStateFlow(false)
    val isCredentialsEmpty = _isCredentialsEmpty.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _subscribersList = MutableStateFlow<List<UserListItem>>(emptyList())
    val subscribersList = _subscribersList.asStateFlow()

    private val _selectedSort = MutableStateFlow(prefs.getDashboardSortOption())
    val selectedSort = _selectedSort.asStateFlow()

    fun setDashboardSortOption(sort: String) {
        _selectedSort.value = sort
        prefs.setDashboardSortOption(sort)
    }

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        val token = prefs.getAuthToken()
        val isDemo = prefs.getDemoMode()
        if (token.isNullOrEmpty() && !isDemo) {
            _isLoading.value = false
            return
        }

        if (!isDemo && (prefs.getIspAdminUsername().isNullOrBlank() || prefs.getIspAdminPassword().isNullOrBlank())) {
            _isCredentialsEmpty.value = true
            _isLoading.value = false
            _error.value = null
            _subscribersList.value = emptyList()
            return
        } else {
            _isCredentialsEmpty.value = false
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            coroutineScope {
                // Parallel fetch 1: Balance (Core indicator)
                val balanceJob = async {
                    try {
                        val bal = gateway.getBalance()
                        _balance.value = bal
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        if (e.message?.contains("Session expired") == true) {
                            android.util.Log.w("DashboardViewModel", "Session expired. Redirecting user to login.")
                            prefs.clearAuthToken()
                        } else {
                            android.util.Log.e("DashboardViewModel", "Balance fetch failed: ${e.message}")
                            _error.value = e.message ?: "Failed to refresh balance indicator."
                        }
                    }
                }

                // Parallel fetch 2: Subscribers
                val subJob = async {
                    try {
                        val subListRes = gateway.searchUsers(query = "", startIndex = 0, rowCount = 5000)
                        _subscribersList.value = subListRes.itemsList ?: emptyList()
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        if (e.message?.contains("Session expired") == true) {
                            android.util.Log.w("DashboardViewModel", "Subscribers fetch canceled due to session expiration.")
                        } else {
                            android.util.Log.e("DashboardViewModel", "Subscribers fetch on dashboard failed: ${e.message}")
                        }
                    }
                }

                // Parallel fetch 3: Prepaid Needed
                val prepaidJob = async {
                    val days = _prepaidNeededDays.value
                    try {
                        var needed = gateway.getPrepaidNeeded(days)
                        if (days == 7) {
                            val accounts = kotlinx.coroutines.withContext(ioDispatcher) {
                                try {
                                    localAccountRepository.getAllAccountsOneShot()
                                } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex;
                                    emptyList()
                                }
                            }
                            if (needed == 0.0 && accounts.isNotEmpty()) {
                                needed = accounts.sumOf { acc ->
                                    val diff = acc.currentPriceIqd - acc.advanceIqd
                                    if (diff > 0.0) diff else 0.0
                                }
                            }
                        }
                        _prepaidNeeded.value = needed
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        android.util.Log.w("DashboardViewModel", "PrepaidNeeded fetch fell back: ${e.message}", e)
                        if (days == 7) {
                            try {
                                val accounts = kotlinx.coroutines.withContext(ioDispatcher) {
                                    try {
                                        localAccountRepository.getAllAccountsOneShot()
                                    } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex;
                                        emptyList()
                                    }
                                }
                                _prepaidNeeded.value = accounts.sumOf { acc ->
                                    val diff = acc.currentPriceIqd - acc.advanceIqd
                                    if (diff > 0.0) diff else 0.0
                                }
                            } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex;
                                _prepaidNeeded.value = 0.0
                            }
                        }
                    }
                }

                // Parallel fetch 4: Active Test Users count
                val testCountJob = async {
                    try {
                        val tests = gateway.getTestUsersCount()
                        _testCount.value = tests
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        android.util.Log.w("DashboardViewModel", "TestCount fetch fell back: ${e.message}", e)
                        _testCount.value = 0
                    }
                }

                awaitAll(balanceJob, subJob, prepaidJob, testCountJob)
            }

            _isLoading.value = false
        }
    }

    fun setPrepaidNeededDays(days: Int) {
        if (days <= 0) return
        _prepaidNeededDays.value = days
        viewModelScope.launch {
            _isPrepaidLoading.value = true
            try {
                val needed = gateway.getPrepaidNeeded(days)
                _prepaidNeeded.value = needed
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.w("DashboardViewModel", "PrepaidNeeded for days $days failed: ${e.message}", e)
                _error.value = e.message ?: "Failed to fetch prepaid needed forecast"
            } finally {
                _isPrepaidLoading.value = false
            }
        }
    }

    fun clearLocalData(
        force: Boolean = false,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                com.example.core.sync.DataOperationCoordinator.withOperation(com.example.core.sync.DataOperationMode.CLEAR_DATA) {
                    if (!force) {
                        val pendingCount = syncRepo.getPendingOutboxCount()
                        if (pendingCount > 0) {
                            onError("UNSYNCED_CHANGES:$pendingCount")
                            return@withOperation
                        }
                    }
                    Log.d("ViewModels", "Clearing local data")
                    localAccountRepository.deleteAllAccounts()
                    syncRepo.requestSync(com.example.domain.repository.SyncReason.MANUAL)
                    Log.d("ViewModels", "Local data cleared")
                    onSuccess()
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.e("ViewModels", "Error clearing local data: ${e.message}", e)
                onError(e.message ?: "Failed to clear local data")
            }
        }
    }
}
