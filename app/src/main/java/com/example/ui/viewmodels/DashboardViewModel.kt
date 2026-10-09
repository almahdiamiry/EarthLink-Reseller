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
    private val sasGatewayRouter: com.example.core.network.SasGatewayRouter? = null,
    private val audit: com.example.domain.repository.AuditRepository,
    private val localAccountRepository: com.example.domain.repository.LocalAccountRepository,
    private val localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
    private val syncRepo: com.example.domain.repository.SyncRepository,
    val prefs: com.example.core.security.PreferenceManager,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) : ViewModel() {

    constructor(
        gateway: com.example.domain.repository.EarthlinkGateway,
        audit: com.example.domain.repository.AuditRepository,
        localAccountRepository: com.example.domain.repository.LocalAccountRepository,
        localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
        syncRepo: com.example.domain.repository.SyncRepository,
        prefs: com.example.core.security.PreferenceManager,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    ) : this(
        gateway = gateway,
        sasGatewayRouter = null,
        audit = audit,
        localAccountRepository = localAccountRepository,
        localLedgerRepository = localLedgerRepository,
        syncRepo = syncRepo,
        prefs = prefs,
        ioDispatcher = ioDispatcher
    )

    private fun getEffectiveRouter(): com.example.core.network.SasGatewayRouter? {
        if (sasGatewayRouter != null) return sasGatewayRouter
        return try {
            com.example.core.network.SasGatewayRouter(
                com.example.core.network.EarthlinkSasGatewayAdapter(gateway),
                prefs
            )
        } catch (_: Exception) {
            null
        }
    }

    val localAccounts = localAccountRepository.getAllAccounts()

    private val _balance = MutableStateFlow(0.0)
    val balance = _balance.asStateFlow()

    private val _prepaidNeeded = MutableStateFlow(0.0)
    val prepaidNeeded = _prepaidNeeded.asStateFlow()

    private val _prepaidNeededDays = MutableStateFlow(7)
    val prepaidNeededDays = _prepaidNeededDays.asStateFlow()

    private val _isPrepaidLoading = MutableStateFlow(false)
    val isPrepaidLoading = _isPrepaidLoading.asStateFlow()

    private val _testCount = MutableStateFlow<Int?>(null)
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

    private fun resolveProviderAccessState(): com.example.core.model.ProviderAccessState {
        val state = try { prefs.getProviderAccessState() } catch (_: Exception) { null }
        if (state != null) return state
        val hasEl = !prefs.getAuthToken().isNullOrEmpty() || prefs.getDemoMode()
        val hasSamm = try { prefs.isSammConfigured() } catch (_: Exception) { false }
        return when {
            hasEl && hasSamm -> com.example.core.model.ProviderAccessState.BOTH
            hasEl -> com.example.core.model.ProviderAccessState.EARTHLINK_ONLY
            hasSamm -> com.example.core.model.ProviderAccessState.SAMM_ONLY
            else -> com.example.core.model.ProviderAccessState.NONE
        }
    }

    fun loadDashboardData(): kotlinx.coroutines.Job {
        val providerState = resolveProviderAccessState()
        val isDemo = prefs.getDemoMode()

        if (providerState == com.example.core.model.ProviderAccessState.NONE && !isDemo) {
            _isCredentialsEmpty.value = true
            _isLoading.value = false
            _error.value = null
            _subscribersList.value = emptyList()
            return kotlinx.coroutines.Job().apply { complete() }
        }

        if (providerState == com.example.core.model.ProviderAccessState.EARTHLINK_ONLY && !isDemo) {
            if (prefs.getIspAdminUsername().isNullOrBlank() || prefs.getIspAdminPassword().isNullOrBlank()) {
                _isCredentialsEmpty.value = true
                _isLoading.value = false
                _error.value = null
                _subscribersList.value = emptyList()
                return kotlinx.coroutines.Job().apply { complete() }
            }
        }

        _isCredentialsEmpty.value = false

        return viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            coroutineScope {
                // Parallel fetch 1: Balance (EarthLink only)
                val balanceJob = async {
                    if (providerState.hasEarthlink || isDemo) {
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
                }

                // Parallel fetch 2: Subscribers (Multi-Provider)
                val subJob = async {
                    coroutineScope {
                        val elSubJob = async {
                            if (providerState.hasEarthlink || isDemo) {
                                try {
                                    val subListRes = gateway.searchUsers(query = "", startIndex = 0, rowCount = 5000)
                                    val rawItems = subListRes.itemsList ?: emptyList()
                                    val items = rawItems.map { it.copy(originProvider = com.example.core.model.SasProviders.EARTHLINK) }

                                    val total = subListRes.totalCount
                                    val isComplete = subListRes.itemsList != null && total != null && total > 0 && items.isNotEmpty() && items.size >= total
                                    if (isComplete) {
                                        val allItemsHaveValidUserId = items.all { it.userID.isNotBlank() }
                                        if (allItemsHaveValidUserId) {
                                            val authoritativeUsernames = items.map { it.userID.trim() }.toSet()
                                            kotlinx.coroutines.withContext(ioDispatcher) {
                                                try {
                                                    localAccountRepository.reconcileIspDisappearance(
                                                        authoritativeIspUserIds = authoritativeUsernames,
                                                        isFetchComplete = true,
                                                        targetProvider = com.example.core.model.SasProviders.EARTHLINK
                                                    )
                                                } catch (e: Exception) {
                                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                                    android.util.Log.e("DashboardViewModel", "ISP disappearance reconciliation failed: ${e.message}", e)
                                                }
                                            }
                                        } else {
                                            android.util.Log.w("DashboardViewModel", "ISP disappearance reconciliation skipped: snapshot contains item with blank/invalid userID")
                                        }
                                    }
                                    items
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                    if (e.message?.contains("Session expired") == true) {
                                        android.util.Log.w("DashboardViewModel", "Subscribers fetch canceled due to session expiration.")
                                    } else {
                                        android.util.Log.e("DashboardViewModel", "Subscribers fetch on dashboard failed: ${e.message}")
                                    }
                                    emptyList<UserListItem>()
                                }
                            } else {
                                emptyList<UserListItem>()
                            }
                        }

                        val sammSubJob = async {
                            if (providerState.hasSamm) {
                                try {
                                    val effectiveRouter = getEffectiveRouter()
                                    if (effectiveRouter != null) {
                                        val sammGateway = effectiveRouter.getGateway(com.example.core.model.SasProviders.ALAMIRY)
                                        val subscribers = sammGateway.searchSubscribers(query = "", page = 1, pageSize = 5000)
                                        val items = subscribers.map { sub ->
                                            val numIndex = sub.subscriberId.toIntOrNull() ?: sub.subscriberId.hashCode()
                                            com.example.core.model.UserListItem(
                                                userIndexLower = numIndex,
                                                userIDLower = sub.username,
                                                customerNameLower = sub.displayName ?: sub.username,
                                                mobileNumberLower = sub.phone,
                                                accountStatusLower = sub.status,
                                                expirationDateLower = sub.expiresAt,
                                                accountExpirationDateLower = sub.expiresAt,
                                                displayNameLower = sub.displayName,
                                                accountNameLower = sub.planName,
                                                originProvider = com.example.core.model.SasProviders.ALAMIRY
                                            )
                                        }
                                        if (items.isNotEmpty()) {
                                            val allItemsHaveValidUserId = items.all { it.userID.isNotBlank() }
                                            if (allItemsHaveValidUserId) {
                                                val authoritativeUsernames = items.map { it.userID.trim() }.toSet()
                                                kotlinx.coroutines.withContext(ioDispatcher) {
                                                    try {
                                                        localAccountRepository.reconcileIspDisappearance(
                                                            authoritativeIspUserIds = authoritativeUsernames,
                                                            isFetchComplete = true,
                                                            targetProvider = com.example.core.model.SasProviders.ALAMIRY
                                                        )
                                                    } catch (e: Exception) {
                                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                                        android.util.Log.e("DashboardViewModel", "SAMM ISP disappearance reconciliation failed: ${e.message}", e)
                                                    }
                                                }
                                            } else {
                                                android.util.Log.w("DashboardViewModel", "SAMM ISP disappearance reconciliation skipped: snapshot contains item with blank/invalid userID")
                                            }
                                        }
                                        items
                                    } else {
                                        emptyList<UserListItem>()
                                    }
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                    android.util.Log.e("DashboardViewModel", "SAMM subscribers fetch on dashboard failed: ${e.message}", e)
                                    emptyList<UserListItem>()
                                }
                            } else {
                                emptyList<UserListItem>()
                            }
                        }

                        val elItems = elSubJob.await()
                        val sammItems = sammSubJob.await()
                        _subscribersList.value = elItems + sammItems
                    }
                }

                // Parallel fetch 3: Prepaid Needed (EarthLink only)
                val prepaidJob = async {
                    if (providerState.hasEarthlink || isDemo) {
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
                }

                // Parallel fetch 4: Active Test Users count (EarthLink only)
                val testCountJob = async {
                    if (providerState.hasEarthlink || isDemo) {
                        try {
                            val tests = gateway.getTestUsersCount()
                            _testCount.value = tests
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                            android.util.Log.w("DashboardViewModel", "TestCount fetch fell back: ${e.message}", e)
                            _testCount.value = null
                        }
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
        val providerState = resolveProviderAccessState()
        val isDemo = prefs.getDemoMode()
        if (!providerState.hasEarthlink && !isDemo) return
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
