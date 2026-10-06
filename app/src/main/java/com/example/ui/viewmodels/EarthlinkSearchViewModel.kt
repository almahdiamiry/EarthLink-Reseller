package com.example.ui.viewmodels
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EarthlinkApp
import com.example.core.model.*
import com.example.core.network.*
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.UtowerImportPreview
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EarthlinkSearchViewModel(
    private val gateway: com.example.domain.repository.EarthlinkGateway,
    private val sasGatewayRouter: com.example.core.network.SasGatewayRouter,
    private val audit: com.example.domain.repository.AuditRepository,
    val prefs: com.example.core.security.PreferenceManager,
    private val localAccountRepository: com.example.domain.repository.LocalAccountRepository,
    private val localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
    private val syncRepo: com.example.domain.repository.SyncRepository? = null
) : ViewModel() {

    constructor(
        gateway: com.example.domain.repository.EarthlinkGateway,
        audit: com.example.domain.repository.AuditRepository,
        prefs: com.example.core.security.PreferenceManager,
        localAccountRepository: com.example.domain.repository.LocalAccountRepository,
        localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
        syncRepo: com.example.domain.repository.SyncRepository? = null
    ) : this(
        gateway = gateway,
        sasGatewayRouter = com.example.core.network.SasGatewayRouter(
            com.example.core.network.EarthlinkSasGatewayAdapter(gateway),
            prefs
        ),
        audit = audit,
        prefs = prefs,
        localAccountRepository = localAccountRepository,
        localLedgerRepository = localLedgerRepository,
        syncRepo = syncRepo
    )

    companion object {
        private val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        private val bghFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad") }
        private val bghFormatFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad") }

        @Synchronized fun parseIsoDate(str: String): java.util.Date? = try { isoFormat.parse(str) } catch (_: Exception) { null }
        @Synchronized fun parseBghDate(str: String): java.util.Date? = try { bghFormat.parse(str) } catch (_: Exception) { null }
        @Synchronized fun formatBghFullDate(date: java.util.Date): String = try { bghFormatFull.format(date) } catch (_: Exception) { "" }
    }

    private val _selectedProvider = MutableStateFlow(com.example.core.model.SasProviders.EARTHLINK)
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    fun setSelectedProvider(provider: String) {
        _selectedProvider.value = provider
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _usersList = MutableStateFlow<List<UserListItem>>(emptyList())
    val usersList = _usersList.asStateFlow()

    private val _selectedUser = MutableStateFlow<UserDetail?>(null)
    val selectedUser = _selectedUser.asStateFlow()

    private val _packages = MutableStateFlow<List<AccountPackage>>(emptyList())
    val packages = _packages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _isRefreshingDetail = MutableStateFlow(false)
    val isRefreshingDetail = _isRefreshingDetail.asStateFlow()

    private val _isActionLoading = MutableStateFlow(false)
    val isActionLoading = _isActionLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _actionSuccess = MutableStateFlow<String?>(null)
    val actionSuccess = _actionSuccess.asStateFlow()

    private val _costPreview = MutableStateFlow<Double?>(null)
    val costPreview = _costPreview.asStateFlow()

    init {
        loadPackages()
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun clearErrorAndSuccess() { _error.value = null; _actionSuccess.value = null }

    fun clearUserDetail() {
        _error.value = null
        _actionSuccess.value = null
        _selectedUser.value = null
        _isLoading.value = true
    }

    fun hasDepositPassword(): Boolean = !prefs.getDepositPassword().isNullOrBlank()

    fun getAccountByUsernameOrId(username: String): Flow<LocalAccount?> =
        localAccountRepository.getActiveAccountByUsernameOrId(username)

    fun getAccountByUsernameOrIdForUser(userIndex: Int?, username: String): Flow<LocalAccount?> =
        localAccountRepository.observeAccountBySubscriberIdentity(userIndex, username)

    fun getLedgerForAccount(accountId: String): Flow<List<com.example.core.model.LocalLedgerEntry>> =
        localLedgerRepository.getLedgerForAccount(accountId)

    fun getUnifiedLedgerForSubscriber(
        userIndex: Int?,
        username: String?,
        fallbackAccountId: String? = null
    ): Flow<List<com.example.core.model.LocalLedgerEntry>> = flow {
        val peerAccounts = localAccountRepository.findActiveAccountsBySubscriberIdentity(userIndex, username)
        val accountIds = peerAccounts.map { it.id }.toMutableList()
        if (!fallbackAccountId.isNullOrBlank() && fallbackAccountId !in accountIds) {
            val fallbackAcc = localAccountRepository.getAccountByIdOneShot(fallbackAccountId)
            if (fallbackAcc != null) {
                val matches = if (userIndex != null && userIndex > 0) {
                    if (fallbackAcc.ispUserIndex != null) {
                        fallbackAcc.ispUserIndex == userIndex
                    } else {
                        !username.isNullOrBlank() && fallbackAcc.earthlinkUsername?.equals(username, ignoreCase = true) == true
                    }
                } else if (!username.isNullOrBlank()) {
                    fallbackAcc.earthlinkUsername?.equals(username, ignoreCase = true) == true
                } else {
                    fallbackAcc.id == fallbackAccountId
                }
                if (matches) {
                    accountIds.add(fallbackAccountId)
                }
            }
        }
        val distinctIds = accountIds.filter { it.isNotBlank() }.distinct()
        if (distinctIds.isEmpty()) {
            emit(emptyList())
        } else {
            emitAll(localLedgerRepository.getLedgerForAccounts(distinctIds))
        }
    }

    private suspend fun isSyntheticLocalIspIdentity(
        userIndex: Int,
        userId: String
    ): Boolean {
        if (userId.isBlank()) return false
        if (userId.startsWith("local_")) {
            val localId = userId.removePrefix("local_")
            val candidate = localAccountRepository.getAccountByIdOneShot(localId)
            if (candidate != null && candidate.ispUserIndex == null && candidate.id.hashCode() == userIndex) {
                return true
            }
        }
        val candidates = localAccountRepository.findActiveAccountsBySubscriberIdentity(
            null,
            userId
        )
        return candidates.any { it.ispUserIndex == null && it.id.hashCode() == userIndex }
    }

    suspend fun getResellerBalance(): Double = withContext(Dispatchers.IO) {
        gateway.getBalance()
    }

    suspend fun getAccountCost(accountIndex: Int): Double = withContext(Dispatchers.IO) {
        gateway.getAccountCost(accountIndex)
    }


    fun searchUsers() = search()

    fun search() {
        if (_searchQuery.value.isEmpty()) return
        val currentProvider = _selectedProvider.value
        val isDemo = prefs.getDemoMode()
        val isCredentialsEmpty = if (currentProvider == com.example.core.model.SasProviders.ALAMIRY) {
            false
        } else {
            !isDemo && (prefs.getIspAdminUsername().isNullOrBlank() || prefs.getIspAdminPassword().isNullOrBlank())
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _usersList.value = emptyList()

            if (isCredentialsEmpty) {
                try {
                    val q = _searchQuery.value.trim().lowercase()
                    val filtered = withContext(Dispatchers.IO) {
                        localAccountRepository.searchAccounts(q, limit = 200, offset = 0)
                    }
                    _usersList.value = filtered.map { acc ->
                        com.example.core.model.UserListItem(
                            userIndexLower = acc.id.hashCode(),
                            userIDLower = acc.earthlinkUsername ?: "local_${acc.id}",
                            customerNameLower = acc.displayName.ifBlank { acc.earthlinkUsername ?: "Unknown" },
                            mobileNumberLower = acc.phone1,
                            accountStatusLower = if (acc.expiresAt != null) {
                                try {
                                    val expireDate = if (acc.expiresAt.endsWith("Z") && acc.expiresAt.contains("T")) {
                                        parseIsoDate(acc.expiresAt)
                                    } else {
                                        parseBghDate(acc.expiresAt)
                                    }
                                    if (expireDate != null && expireDate.before(java.util.Date())) "Expired" else "Active"
                                } catch(e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    "Active" 
                                }
                            } else "Active",
                            expirationDateLower = if (acc.expiresAt?.endsWith("Z") == true) {
                                try {
                                    val p = parseIsoDate(acc.expiresAt)
                                    if (p != null) formatBghFullDate(p) else acc.expiresAt
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    acc.expiresAt 
                                }
                            } else acc.expiresAt ?: "",
                            displayNameLower = acc.displayName,
                            accountNameLower = acc.packageName
                        )
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    _error.value = e.message
                } finally {
                    _isLoading.value = false
                }
                return@launch
            }

            try {
                val targetGateway = sasGatewayRouter.getGateway(currentProvider)
                val subscribers = targetGateway.searchSubscribers(query = _searchQuery.value, page = 1, pageSize = 200)
                _usersList.value = subscribers.map { sub ->
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
                        accountNameLower = sub.planName
                    )
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                val isNetworkIssue = e.message?.contains("Network unavailable", ignoreCase = true) == true || 
                                     e.message?.contains("Connection error", ignoreCase = true) == true || 
                                     e.message?.contains("ConnectException", ignoreCase = true) == true || 
                                     e.message?.contains("UnknownHostException", ignoreCase = true) == true || 
                                     e.message?.contains("SocketTimeoutException", ignoreCase = true) == true ||
                                     e is com.example.core.network.SasTransportException

                if (isNetworkIssue) {
                    try {
                        val q = _searchQuery.value.trim().lowercase()
                        val filtered = withContext(Dispatchers.IO) {
                            localAccountRepository.searchAccounts(q, limit = 200, offset = 0)
                        }
                        _usersList.value = filtered.map { acc ->
                            com.example.core.model.UserListItem(
                                userIndexLower = acc.id.hashCode(),
                                userIDLower = acc.earthlinkUsername ?: "local_${acc.id}",
                                customerNameLower = acc.displayName.ifBlank { acc.earthlinkUsername ?: "Unknown" },
                                mobileNumberLower = acc.phone1,
                                accountStatusLower = if (acc.expiresAt != null) {
                                    try {
                                        val expireDate = if (acc.expiresAt.endsWith("Z") && acc.expiresAt.contains("T")) {
                                            parseIsoDate(acc.expiresAt)
                                        } else {
                                            parseBghDate(acc.expiresAt)
                                        }
                                        if (expireDate != null && expireDate.before(java.util.Date())) "Expired" else "Active"
                                    } catch(ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex; "Active" }
                                } else "Active",
                                expirationDateLower = if (acc.expiresAt?.endsWith("Z") == true) {
                                try {
                                    val p = parseIsoDate(acc.expiresAt)
                                    if (p != null) formatBghFullDate(p) else acc.expiresAt
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; acc.expiresAt }
                            } else acc.expiresAt ?: "",
                                displayNameLower = acc.displayName,
                                accountNameLower = acc.packageName
                            )
                        }
                        _error.value = if (prefs.getLanguage() == "ar") {
                            "أنت في وضع الأوفلاين. تم عرض نتائج البحث من السجل المحلي."
                        } else {
                            "You are offline. Showing search results from local accounts database."
                        }
                    } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex;
                        _error.value = e.message
                    }
                } else {
                    _error.value = e.message
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun prepareUserDetail(userIndex: Int, externalPartialItem: com.example.core.model.UserListItem? = null) {
        _error.value = null
        _actionSuccess.value = null

        if (_selectedUser.value?.userIndex == userIndex && _selectedUser.value != null) return

        val partialItem = externalPartialItem ?: _usersList.value.find { it.userIndex == userIndex }
        if (partialItem != null) {
            _selectedUser.value = com.example.core.model.UserDetail(
                userIndexLower = partialItem.userIndex,
                userIDLower = partialItem.userID,
                customerNameLower = partialItem.customerName,
                customerFullNameLower = partialItem.customerName,
                mobileNumberLower = partialItem.mobileNumber,
                accountStatusLower = partialItem.accountStatus,
                expirationDateLower = partialItem.expirationDate,
                accountExpirationDateLower = partialItem.accountExpirationDate,
                activeDaysLeftLower = partialItem.activeDaysLeft,
                displayNameLower = partialItem.displayName,
                packageNameLower = partialItem.packageName ?: partialItem.displayName
            )
            _isLoading.value = false
        } else {
            _selectedUser.value = null
            _isLoading.value = true
        }
    }

    suspend fun getUserDetail(userIndex: Int, accountProvider: String? = null): com.example.core.model.UserDetail? {
        val targetProvider = accountProvider ?: _selectedProvider.value
        val targetGateway = sasGatewayRouter.getGateway(targetProvider)
        val sub = targetGateway.getSubscriber(userIndex.toString()) ?: return null
        return com.example.core.model.UserDetail(
            userIndexLower = sub.subscriberId.toIntOrNull() ?: userIndex,
            userIDLower = sub.username,
            customerFullNameLower = sub.displayName,
            customerNameLower = sub.displayName ?: sub.username,
            displayNameLower = sub.displayName,
            mobileNumberLower = sub.phone,
            packageNameLower = sub.planName,
            accountIndexLower = sub.planId?.toIntOrNull(),
            accountStatusLower = sub.status,
            expirationDateLower = sub.expiresAt,
            accountExpirationDateLower = sub.expiresAt
        )
    }

    fun loadUserDetail(userIndex: Int, knownUserId: String? = null): kotlinx.coroutines.Job {
        val currentProvider = _selectedProvider.value
        return viewModelScope.launch {
            _error.value = null
            
            if (knownUserId != null && _selectedUser.value?.userID != knownUserId) {
                _selectedUser.value = null
            }

            // Prepare instant optimistic detail if available
            prepareUserDetail(userIndex)

            var foundLocal: LocalAccount? = null
            // If still null, try finding in local DB via targeted lookup
            if (_selectedUser.value == null) {
                try {
                    foundLocal = withContext(Dispatchers.IO) {
                        if (!knownUserId.isNullOrBlank()) {
                            if (knownUserId.startsWith("local_")) {
                                val localId = knownUserId.removePrefix("local_")
                                val candidate = localAccountRepository.getAccountByIdOneShot(localId)
                                if (candidate != null && candidate.ispUserIndex == null && candidate.id.hashCode() == userIndex) {
                                    return@withContext candidate
                                }
                            }
                            val syntheticCandidate = localAccountRepository.findActiveAccountsBySubscriberIdentity(null, knownUserId)
                                .firstOrNull { it.ispUserIndex == null && it.id.hashCode() == userIndex }
                            if (syntheticCandidate != null) {
                                return@withContext syntheticCandidate
                            }
                            val matches = if (userIndex > 0) {
                                localAccountRepository.findActiveAccountsBySubscriberIdentity(userIndex, knownUserId)
                            } else {
                                emptyList()
                            }
                            if (matches.size == 1) {
                                matches.single()
                            } else if (matches.isEmpty() && userIndex <= 0) {
                                localAccountRepository.findActiveAccountByUsernameOrIdOneShot(knownUserId)
                            } else {
                                null
                            }
                        } else {
                            // Target lookup via search instead of full table scan
                            localAccountRepository.searchAccounts("", limit = 200, offset = 0).find { 
                                (it.earthlinkUsername != null && it.earthlinkUsername.hashCode() == userIndex) || 
                                (it.id.hashCode() == userIndex)
                            }
                        }
                    }
                    if (foundLocal != null) {
                        _selectedUser.value = com.example.core.model.UserDetail(
                            userIndexLower = userIndex,
                            userIDLower = foundLocal.earthlinkUsername ?: "local_${foundLocal.id}",
                            customerNameLower = foundLocal.displayName.ifBlank { foundLocal.earthlinkUsername ?: "Unknown" },
                            customerFullNameLower = foundLocal.displayName,
                            mobileNumberLower = foundLocal.phone1,
                            accountStatusLower = if (foundLocal.expiresAt != null) {
                                try {
                                    val expireDate = if (foundLocal.expiresAt.endsWith("Z") && foundLocal.expiresAt.contains("T")) {
                                        parseIsoDate(foundLocal.expiresAt)
                                    } else {
                                        parseBghDate(foundLocal.expiresAt)
                                    }
                                    if (expireDate != null && expireDate.before(java.util.Date())) "Expired" else "Active"
                                } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex; "Active" }
                            } else "Active",
                            expirationDateLower = if (foundLocal.expiresAt?.endsWith("Z") == true) {
                                try {
                                    val p = parseIsoDate(foundLocal.expiresAt)
                                    if (p != null) formatBghFullDate(p) else foundLocal.expiresAt
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; foundLocal.expiresAt }
                            } else foundLocal.expiresAt ?: "",
                            packageNameLower = foundLocal.packageName,
                            displayNameLower = foundLocal.displayName
                        )
                        _isLoading.value = false
                    }
                } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex;
                    // Ignore
                }
            }

            val currentUserId = _selectedUser.value?.userID ?: knownUserId
            val isSyntheticLocal = if (!currentUserId.isNullOrBlank()) {
                isSyntheticLocalIspIdentity(userIndex, currentUserId)
            } else {
                false
            }
            val hasIspLinkage = if (foundLocal != null) {
                foundLocal.earthlinkUsername?.isNotBlank() == true || foundLocal.ispUserIndex != null || foundLocal.ispSubscriberId != null
            } else {
                currentUserId != null && !currentUserId.startsWith("local_") && currentUserId.isNotBlank()
            }
            if (hasIspLinkage && !isSyntheticLocal) {
                _isRefreshingDetail.value = true
                try {
                    val accountProvider = foundLocal?.operationProvider
                    val targetProvider = accountProvider ?: currentProvider
                    val targetGateway = sasGatewayRouter.getGateway(targetProvider)
                    val sub = targetGateway.getSubscriber(userIndex.toString())
                    if (sub != null) {
                        val detail = com.example.core.model.UserDetail(
                            userIndexLower = sub.subscriberId.toIntOrNull() ?: userIndex,
                            userIDLower = sub.username,
                            customerFullNameLower = sub.displayName,
                            customerNameLower = sub.displayName ?: sub.username,
                            displayNameLower = sub.displayName,
                            mobileNumberLower = sub.phone,
                            packageNameLower = sub.planName,
                            accountIndexLower = sub.planId?.toIntOrNull(),
                            accountStatusLower = sub.status,
                            expirationDateLower = sub.expiresAt,
                            accountExpirationDateLower = sub.expiresAt
                        )
                        // A slower earlier request must not clobber a newer operator selection.
                        // The activity-scoped _selectedUser is shared across navigation, so a late
                        // response for an abandoned subscriber would otherwise resurrect it.
                        val stillSelected = _selectedUser.value
                        if (stillSelected == null || stillSelected.userIndex == userIndex) {
                            _selectedUser.value = detail
                        }
                        if (foundLocal != null && detail.userIndex > 0) {
                            try {
                                localAccountRepository.bindIspIdentity(foundLocal.id, detail.userIndex)
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                Log.w("EarthlinkSearchVM", "ISP identity binding skipped: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    if (_selectedUser.value == null) {
                        _error.value = e.message
                    }
                } finally {
                    _isLoading.value = false
                    _isRefreshingDetail.value = false
                }
            } else {
                _isLoading.value = false
                _isRefreshingDetail.value = false
            }
        }
    }

    private fun loadPackages() {
        val token = prefs.getAuthToken()
        val isDemo = prefs.getDemoMode()
        if (token.isNullOrEmpty() && !isDemo) return

        viewModelScope.launch {
            try {
                val pkgs = gateway.getPackages()
                _packages.value = pkgs
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                if (e.message?.contains("Session expired") == true) {
                    Log.w("EarthlinkSearchVM", "Session expired. Skipping packages load.")
                } else {
                    Log.e("EarthlinkSearchVM", "Failed to load packages options: ${e.message}")
                }
            }
        }
    }

    fun previewPackageCost(pkgIndex: Int) {
        _costPreview.value = null
        viewModelScope.launch {
            try {
                val cost = gateway.getAccountCost(pkgIndex)
                _costPreview.value = cost
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = "Failed package cost preview: ${e.message}"
            }
        }
    }

    private val inflightAccountLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun getAccountLock(accountId: String): kotlinx.coroutines.sync.Mutex =
        inflightAccountLocks.computeIfAbsent(accountId) { kotlinx.coroutines.sync.Mutex() }

    fun createTestUser(username: String, phone: String, fullName: String, pkgIndex: Int, intentId: String? = null): kotlinx.coroutines.Job {
        val lock = getAccountLock("${username}:TEST_USER")

        return viewModelScope.launch(Dispatchers.IO) {
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate test user creation suppressed: account $username has an active inflight operation")
                _error.value = "Operation already in progress or awaiting verification."
                return@launch
            }
            val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
            val businessTxId = "tx_" + opIntentId

            try {
                _isActionLoading.value = true
                _error.value = null
                // Clear the previous attempt's success too - see createUserUsingDeposit.
                _actionSuccess.value = null

                val existingOp = localLedgerRepository.getPendingOperationByIntentId(opIntentId)
                if (existingOp != null && existingOp.status == "COMPLETED") {
                    _actionSuccess.value = "Test subscriber $username created successfully."
                    return@launch
                }

                val available = gateway.checkUsernameAvailable(username)
                if (!available) {
                    _error.value = "Username $username is already taken."
                    return@launch
                }

                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = username,
                        operationType = "TEST_USER",
                        amountIqd = 0L,
                        payloadJson = "{\"username\":\"$username\",\"phone\":\"$phone\",\"fullName\":\"$fullName\",\"pkgIndex\":$pkgIndex,\"isTest\":true}",
                        status = "PENDING",
                        dispatchClaimCount = 0
                    )
                )

                val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
                if (!claimGranted) {
                    _error.value = "Operation is already processing or awaiting verification."
                    return@launch
                }

                val generatedPassword = gateway.createTestUser(username, phone, fullName, pkgIndex)
                if (generatedPassword != null) {
                    localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[TEST_USER]")
                    _actionSuccess.value = "Test subscriber $username created successfully.\nPassword: $generatedPassword"
                    audit.logAction("CREATE_TEST_USER", "USER", username, "Created test user successfully")
                } else {
                    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Test user creation failed")
                    _error.value = "Test user creation failed."
                }
            } catch (e: EarthlinkBusinessException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
                _error.value = e.errorMessage
            } catch (e: EarthlinkAuthException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
                _error.value = "Session expired. Please log in again."
            } catch (e: EarthlinkTransportException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
                _error.value = "Network uncertain. Operation stored for verification."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
                _error.value = "Operation pending verification."
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun createUserUsingDeposit(username: String, phone: String, fullName: String, pkgIndex: Int, depositPass: String, intentId: String? = null): kotlinx.coroutines.Job {
        val targetProvider = _selectedProvider.value
        val lock = getAccountLock("${username}:ACTIVATION")

        return viewModelScope.launch(Dispatchers.IO) {
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate activation suppressed: account $username has an active inflight operation")
                _error.value = "Operation already in progress or awaiting verification."
                return@launch
            }
            val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
            val businessTxId = "tx_" + opIntentId

            try {
                _isActionLoading.value = true
                _error.value = null
                // Clear the previous attempt's success too. This ViewModel is Activity-scoped and
                // shared by every destination, so a stale success banner (which carries the live
                // subscriber password) would otherwise sit next to this attempt's error banner.
                _actionSuccess.value = null

                val existingOp = localLedgerRepository.getPendingOperationByIntentId(opIntentId)
                if (existingOp != null && existingOp.status == "COMPLETED") {
                    _actionSuccess.value = "Paid subscriber $username created successfully."
                    return@launch
                }

                val available = gateway.checkUsernameAvailable(username)
                if (!available) {
                    _error.value = "Username $username is already taken."
                    return@launch
                }

                val cost = try {
                    gateway.getAccountCost(pkgIndex)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: EarthlinkGatewayException) {
                    _error.value = "Failed to determine package cost: ${e.message ?: "API error"}. Operation aborted."
                    return@launch
                } catch (e: Exception) {
                    _error.value = "Failed to determine package cost: ${e.message ?: "error"}. Operation aborted."
                    return@launch
                }
                if (!cost.isFinite() || cost <= 0.0 || cost % 1.0 != 0.0 || cost % 250.0 != 0.0) {
                    _error.value = "Failed to determine a valid IQD package cost. Operation aborted."
                    return@launch
                }
                val exactAmountIqd = cost.toLong()

                val existingPayload = existingOp?.payloadJson?.let {
                    try { org.json.JSONObject(it) } catch (_: Exception) { null }
                }
                val activationLocalAccountId = existingPayload?.optString("localAccountId")?.trim()?.takeIf { it.isNotBlank() }
                    ?: java.util.UUID.randomUUID().toString()

                val payloadJson = org.json.JSONObject().apply {
                    put("username", username)
                    put("phone", phone)
                    put("fullName", fullName)
                    put("pkgIndex", pkgIndex)
                    put("localAccountId", activationLocalAccountId)
                    put("operationProvider", targetProvider)
                }.toString()

                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = username,
                        operationType = "ACTIVATION",
                        amountIqd = exactAmountIqd,
                        payloadJson = payloadJson,
                        status = "PENDING",
                        dispatchClaimCount = 0,
                        operationProvider = targetProvider
                    )
                )

                val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
                if (!claimGranted) {
                    _error.value = "Operation is already processing or awaiting verification."
                    return@launch
                }

                val targetGateway = sasGatewayRouter.getGateway(targetProvider)
                val parts = fullName.trim().split("\\s+".toRegex(), limit = 2)
                val firstName = parts.getOrNull(0) ?: fullName
                val lastName = parts.getOrNull(1) ?: ""
                val opResult = targetGateway.createSubscriber(
                    username = username,
                    planId = pkgIndex.toString(),
                    password = depositPass,
                    firstName = firstName,
                    lastName = lastName,
                    phone = phone
                )

                if (opResult is com.example.core.network.SasOperationResult.Applied) {
                    val generatedPassword = if (targetProvider == com.example.core.model.SasProviders.EARTHLINK) {
                        opResult.evidence ?: depositPass
                    } else {
                        depositPass
                    }
                    localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[VERIFIED ACTIVATION]")
                    _actionSuccess.value = "Paid subscriber $username created successfully.\nPassword: $generatedPassword"
                    audit.logAction("CREATE_PAID_USER", "USER", username, "Created subscriber using reseller deposit")
                } else if (opResult is com.example.core.network.SasOperationResult.Rejected) {
                    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, opResult.reason)
                    _error.value = opResult.reason
                } else if (opResult is com.example.core.network.SasOperationResult.Inconclusive) {
                    localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, opResult.reason)
                    _error.value = "Subscriber created on gateway with missing ID payload. Operation stored for verification."
                } else {
                    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Subscriber creation failed")
                    _error.value = "Subscriber creation failed."
                }
            } catch (e: com.example.core.network.SasInconclusiveException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, e.message ?: "Inconclusive outcome")
                _error.value = "Subscriber created on gateway with missing ID payload. Operation stored for verification."
            } catch (e: EarthlinkInconclusiveException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, e.message)
                _error.value = "Subscriber created on gateway with missing ID payload. Operation stored for verification."
            } catch (e: com.example.core.network.SasBusinessException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
                _error.value = e.errorMessage
            } catch (e: EarthlinkBusinessException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
                _error.value = e.errorMessage
            } catch (e: com.example.core.network.SasAuthException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message ?: "Auth failed"}")
                _error.value = "Session expired. Please log in again."
            } catch (e: EarthlinkAuthException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
                _error.value = "Session expired. Please log in again."
            } catch (e: com.example.core.network.SasTransportException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message ?: "Transport error"}")
                _error.value = "Network uncertain. Operation stored for verification."
            } catch (e: EarthlinkTransportException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
                _error.value = "Network uncertain. Operation stored for verification."
            } catch (e: com.example.core.network.SasUnsupportedOperationException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Unsupported: ${e.message ?: "Unsupported"}")
                _error.value = e.message ?: "Unsupported operation"
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message ?: "Unknown error"}")
                _error.value = "Operation pending verification."
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun recordPayment(
        account: LocalAccount,
        amount: Double,
        note: String? = null,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): kotlinx.coroutines.Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            val noteVal = note?.trim() ?: ""
            val baseNote = if (account.debtIqd > 0) "[PAYMENT]" else "[DEPOSIT]"
            val payNote = if (noteVal.isNotBlank()) "$baseNote $noteVal" else null

            localLedgerRepository.recordAccountPayment(
                account = account,
                amount = amount,
                note = payNote
            )
            syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            try {
                audit.logAction(
                    action = "DEPOSIT_PAYMENT",
                    entityType = "USER",
                    entityId = account.earthlinkUsername ?: account.id,
                    summary = "Recorded payment amount $amount. Note: $payNote"
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            withContext(Dispatchers.Main) {
                onSuccess?.invoke()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("EarthlinkSearchVM", "Failed to add deposit/payment", e)
            withContext(Dispatchers.Main) {
                onError?.invoke(e.localizedMessage ?: e.message ?: "Unknown error")
            }
        }
    }

    fun recordDebt(
        account: LocalAccount,
        amount: Double,
        note: String? = null,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ): kotlinx.coroutines.Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            val noteVal = note?.trim() ?: ""
            val debtNote = if (noteVal.isNotBlank()) "[DEBT] $noteVal" else null

            localLedgerRepository.recordAccountDebt(
                account = account,
                amount = amount,
                note = debtNote
            )
            syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            try {
                audit.logAction(
                    action = "ADD_DEBT",
                    entityType = "USER",
                    entityId = account.earthlinkUsername ?: account.id,
                    summary = "Added debt amount $amount. Note: $debtNote"
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
            withContext(Dispatchers.Main) {
                onSuccess?.invoke()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("EarthlinkSearchVM", "Failed to add debt", e)
            withContext(Dispatchers.Main) {
                onError?.invoke(e.localizedMessage ?: e.message ?: "Unknown error")
            }
        }
    }

    fun saveCustomerNote(account: LocalAccount, note: String): kotlinx.coroutines.Job =
        viewModelScope.launch(Dispatchers.IO) {
            val updated = account.copy(
                note = note,
                updatedAt = System.currentTimeMillis()
            )
            localAccountRepository.saveAccount(updated)
        }

    fun saveCustomNanoIp(account: LocalAccount, nanoIp: String?): kotlinx.coroutines.Job =
        viewModelScope.launch(Dispatchers.IO) {
            val updated = account.copy(
                nanoIp = nanoIp?.trim()?.ifEmpty { null },
                updatedAt = System.currentTimeMillis()
            )
            localAccountRepository.saveAccount(updated)
        }

    fun refillUserDeposit(
        userId: String,
        depositPass: String? = null,
        price: Double? = null,
        note: String? = null,
        intentId: String? = null,
        isWasil: Boolean = false,
        account: LocalAccount? = null,
        onSuccessCallback: (suspend (String) -> Unit)? = null
    ): kotlinx.coroutines.Job = refillUser(userId, depositPass, price, note, intentId, isWasil, account, onSuccessCallback)

    fun refillUser(
        userId: String,
        depositPass: String? = null,
        price: Double? = null,
        note: String? = null,
        intentId: String? = null,
        isWasil: Boolean = false,
        account: LocalAccount? = null,
        onSuccessCallback: (suspend (String) -> Unit)? = null
    ): kotlinx.coroutines.Job {
        val targetProvider = account?.operationProvider ?: _selectedProvider.value
        val lock = getAccountLock("${userId}:REFILL")

        return viewModelScope.launch(Dispatchers.IO) {
            if (targetProvider == com.example.core.model.SasProviders.ALAMIRY) {
                _error.value = "Refill is not supported for ALAMIRY provider."
                return@launch
            }
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate financial operation suppressed: account $userId has an active inflight operation")
                _error.value = "Operation already in progress or awaiting verification."
                return@launch
            }
            val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
            val businessTxId = "charge_" + opIntentId
            val finalNote = note ?: ""

            try {
                _isActionLoading.value = true
                _error.value = null

                val existingOp = localLedgerRepository.getPendingOperationByIntentId(opIntentId)
                if (existingOp != null && existingOp.status == "COMPLETED") {
                    _actionSuccess.value = if (prefs.getLanguage() == "ar") {
                        "تم تجديد اشتراك المشترك $userId بنجاح."
                    } else {
                        "Subscriber $userId was renewed successfully."
                    }
                    return@launch
                }

                val resolvedPass = if (!depositPass.isNullOrBlank()) depositPass else prefs.getDepositPassword()
                if (resolvedPass.isNullOrBlank()) {
                    _error.value = if (prefs.getLanguage() == "ar") {
                        "الرجاء ضبط كلمة مرور الصندوق في الإعدادات أولاً!"
                    } else {
                        "Please set your deposit password in settings first!"
                    }
                    return@launch
                }

                val authoritativePrice = price
                if (authoritativePrice == null || !authoritativePrice.isFinite() || authoritativePrice <= 0.0 ||
                    authoritativePrice % 1.0 != 0.0 || authoritativePrice % 250.0 != 0.0) {
                    _error.value = "Invalid, non-authoritative, or missing package price. Operation aborted."
                    return@launch
                }
                val exactAmountIqd = authoritativePrice.toLong()

                val selectedIndex = _selectedUser.value?.userIndex?.takeIf { it > 0 }
                val effectiveAcc = if (account != null) {
                    val existing = localAccountRepository.getAccountByIdOneShot(account.id)?.takeIf { !it.isHistoryOnlySubscriber }
                    if (existing != null) {
                        if (selectedIndex != null && existing.ispUserIndex != null && existing.ispUserIndex != selectedIndex) {
                            _error.value = "Local account identity is conflicting with selected subscriber. Operation aborted."
                            return@launch
                        }
                        existing
                    } else if (selectedIndex != null && account.ispUserIndex != null && account.ispUserIndex != selectedIndex) {
                        _error.value = "Local account identity is conflicting with selected subscriber. Operation aborted."
                        return@launch
                    } else {
                        val toSave = if (selectedIndex != null && account.ispUserIndex == null) {
                            account.copy(currentPriceIqd = exactAmountIqd.toDouble(), ispUserIndex = selectedIndex)
                        } else {
                            account.copy(currentPriceIqd = exactAmountIqd.toDouble())
                        }
                        localAccountRepository.saveAccount(toSave)
                    }
                } else {
                    val matches = localAccountRepository.findActiveAccountsBySubscriberIdentity(selectedIndex, userId)
                    when {
                        matches.size > 1 -> {
                            _error.value = "Multiple local accounts found for subscriber. Operation aborted to prevent ambiguous financial assignment."
                            return@launch
                        }
                        matches.size == 1 -> matches.single()
                        else -> {
                            val snapshotUser = _selectedUser.value?.takeIf { it.userID.equals(userId, ignoreCase = true) }
                            val snapshotListItem = _usersList.value.find { it.userID.equals(userId, ignoreCase = true) }
                            val displayName = snapshotUser?.customerFullName
                                ?: snapshotListItem?.customerName
                                ?: userId
                            val phone = snapshotUser?.mobileNumber ?: snapshotListItem?.mobileNumber
                            val pkgName = snapshotUser?.packageName ?: snapshotListItem?.packageName ?: "Default"
                            val resolvedId = if (localAccountRepository.getAccountByIdOneShot(userId) == null) {
                                userId
                            } else {
                                java.util.UUID.randomUUID().toString()
                            }
                            val newAcc = LocalAccount(
                                id = resolvedId,
                                earthlinkUsername = userId,
                                displayName = displayName.ifBlank { userId },
                                phone1 = phone,
                                packageName = pkgName,
                                currentPriceIqd = exactAmountIqd.toDouble(),
                                debtIqd = 0.0,
                                ispUserIndex = selectedIndex
                            )
                            localAccountRepository.saveAccount(newAcc)
                            newAcc
                        }
                    }
                }

                if (effectiveAcc.operationProvider == com.example.core.model.SasProviders.ALAMIRY) {
                    _error.value = "Refill is not supported for ALAMIRY provider."
                    return@launch
                }

                val payloadJson = org.json.JSONObject().apply {
                    put("userId", userId)
                    put("localAccountId", effectiveAcc.id)
                    put("price", authoritativePrice)
                    put("note", finalNote)
                    put("isWasil", isWasil)
                }.toString()

                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = userId,
                        operationType = "REFILL",
                        amountIqd = exactAmountIqd,
                        payloadJson = payloadJson,
                        status = "PENDING",
                        dispatchClaimCount = 0
                    )
                )

                val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
                if (!claimGranted) {
                    _error.value = "Operation is already processing or awaiting verification."
                    return@launch
                }

                val success = gateway.refillUserDeposit(userId, resolvedPass)
                if (success) {
                    try {
                        val chargeNote = finalNote.trim().ifEmpty { null }
                        localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, chargeNote)

                        try {
                            val chargeNoteToUse = finalNote.trim()
                            val payNoteToUse = if (isWasil) finalNote.trim() else null
                            localLedgerRepository.recordAccountRenewal(
                                account = effectiveAcc,
                                newPriceIqd = exactAmountIqd.toDouble(),
                                chargeNote = chargeNoteToUse,
                                payNote = payNoteToUse,
                                idempotencyKey = businessTxId
                            )
                            // Reuse the identity captured BEFORE the gateway suspend. Re-reading
                            // _selectedUser here would pick up whichever subscriber the operator
                            // navigated to while the HTTP call was in flight and would bind this
                            // account to a foreign ISP identity.
                            val confirmedUserIndex = selectedIndex
                            if (confirmedUserIndex != null) {
                                try {
                                    localAccountRepository.bindIspIdentity(effectiveAcc.id, confirmedUserIndex)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Log.w("EarthlinkSearchVM", "ISP identity binding on refill skipped: ${e.message}")
                                }
                            }
                            syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e("EarthlinkSearchVM", "Failed to add ledger entry", e)
                            try {
                                audit.logAction(
                                    action = "RECONCILIATION_REQUIRED",
                                    entityType = "USER",
                                    entityId = userId,
                                    summary = "Refill succeeded on API, but local ledger persistence failed: ${e.message}"
                                )
                            } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex }
                        }

                        onSuccessCallback?.invoke(businessTxId)

                        _actionSuccess.value = if (prefs.getLanguage() == "ar") {
                            "تم تجديد اشتراك المشترك $userId بنجاح."
                        } else {
                            "Subscriber $userId was renewed successfully."
                        }
                        
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        val dateStr = sdf.format(java.util.Date())
                        val noteText = if (finalNote.isNotBlank()) {
                            "تجديد اشتراك بسعر $authoritativePrice - $finalNote"
                        } else {
                            "تجديد اشتراك بسعر $authoritativePrice"
                        }
                        
                        val currentBalance = try { gateway.getBalance() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; 0.0 }
                        
                        val statementItem = com.example.core.model.AccountStatementItem(
                            occurredAt = dateStr,
                            operation = "RENEW_SUBSCRIBER",
                            depositAmount = 0.0,
                            withdrawalAmount = authoritativePrice,
                            balanceAfter = currentBalance,
                            note = noteText
                        )

                        audit.logAction(
                            action = "REFILL_USER",
                            entityType = "USER",
                            entityId = userId,
                            summary = "Renewed subscription at price $authoritativePrice. Note: $finalNote"
                        )
                        _selectedUser.value?.userIndex?.let { loadUserDetail(it, _selectedUser.value?.userIDLower) }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        android.util.Log.e("EarthlinkSearchViewModel", "Failed to execute atomic post-call materialization after renewal", e)
                        _error.value = if (prefs.getLanguage() == "ar") {
                            "تم تجديد الاشتراك ولكن فشل تسجيل القيد المحلي. العملية محفوظة للتحقق."
                        } else {
                            "Renewal succeeded on server but local record confirmation is pending verification."
                        }
                    }
                } else {
                    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Refill action failed on server")
                    _error.value = "Refill action failed."
                }
            } catch (e: EarthlinkBusinessException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
                _error.value = e.errorMessage
            } catch (e: EarthlinkAuthException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
                _error.value = "Session expired. Please log in again."
            } catch (e: EarthlinkTransportException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
                _error.value = if (prefs.getLanguage() == "ar") {
                    "فشل الاتصال بالشبكة: تعذر تجديد الاشتراك على خوادم إيرثلنك. تم حفظ العملية للتحقق."
                } else {
                    "Network connection uncertain: Renewal stored for verification."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
                _error.value = if (prefs.getLanguage() == "ar") {
                    "فشل الاتصال بالشبكة: تعذر تجديد الاشتراك على خوادم إيرثلنك. تم حفظ العملية للتحقق."
                } else {
                    "Operation pending verification."
                }
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun extendUser(userIndex: Int, userId: String, intentId: String? = null): kotlinx.coroutines.Job {
        val lock = getAccountLock("${userId}:EXTEND")

        return viewModelScope.launch(Dispatchers.IO) {
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate extension suppressed: account $userId has an active inflight operation")
                _error.value = "Operation already in progress or awaiting verification."
                return@launch
            }
            val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
            val businessTxId = "tx_" + opIntentId

            try {
                _isActionLoading.value = true
                _error.value = null

                val existingOp = localLedgerRepository.getPendingOperationByIntentId(opIntentId)
                if (existingOp != null && existingOp.status == "COMPLETED") {
                    _actionSuccess.value = "Subscription for $userId extended successfully."
                    return@launch
                }

                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = userId,
                        operationType = "EXTEND",
                        amountIqd = 0L,
                        payloadJson = "{\"userIndex\":$userIndex,\"userId\":\"$userId\"}",
                        status = "PENDING",
                        dispatchClaimCount = 0
                    )
                )

                val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
                if (!claimGranted) {
                    _error.value = "Operation is already processing or awaiting verification."
                    return@launch
                }

                val success = gateway.extendUser(userIndex)
                if (success) {
                    localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[EXTEND]")
                    _actionSuccess.value = "Subscription for $userId extended successfully."
                    audit.logAction("EXTEND_USER", "USER", userId, "Extended subscriber duration")
                    loadUserDetail(userIndex, _selectedUser.value?.userIDLower)
                } else {
                    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Extension failed on server")
                    _error.value = "Extension failed."
                }
            } catch (e: EarthlinkBusinessException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, e.errorMessage)
                _error.value = e.errorMessage
            } catch (e: EarthlinkAuthException) {
                localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Auth failed: ${e.message}")
                _error.value = "Session expired. Please log in again."
            } catch (e: EarthlinkTransportException) {
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Transport uncertainty: ${e.message}")
                _error.value = if (prefs.getLanguage() == "ar") {
                    "فشل الاتصال بالشبكة: تعذر تمديد الاشتراك على خوادم إيرثلنك. تم حفظ العملية للتحقق."
                } else {
                    "Network connection uncertain: Extension stored for verification."
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                localLedgerRepository.resolvePendingOperationInconclusive(businessTxId, "Unexpected error: ${e.message}")
                _error.value = if (prefs.getLanguage() == "ar") {
                    "فشل الاتصال بالشبكة: تعذر تمديد الاشتراك على خوادم إيرثلنك. تم حفظ العملية للتحقق."
                } else {
                    "Operation pending verification."
                }
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun resolvePendingOperation(
        businessTransactionId: String,
        baselineExpirationDate: String? = null,
        onResolved: ((PendingOperationResolution) -> Unit)? = null
    ): kotlinx.coroutines.Job {
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                _isActionLoading.value = true
                _error.value = null
                val resolution = localLedgerRepository.resolvePendingOperationSerialized(
                    businessTransactionId = businessTransactionId,
                    gateway = gateway,
                    baselineExpirationDate = baselineExpirationDate
                )
                when (resolution.result) {
                    UnknownOutcomeResolutionResult.VERIFIED_SUCCESS -> {
                        _actionSuccess.value = "Operation verified and resolved successfully: ${resolution.diagnosticMessage}"
                    }
                    UnknownOutcomeResolutionResult.VERIFIED_FAILURE -> {
                        _error.value = "Operation verified not executed: ${resolution.diagnosticMessage}"
                    }
                    UnknownOutcomeResolutionResult.INCONCLUSIVE -> {
                        _error.value = "Verification inconclusive: ${resolution.diagnosticMessage}. Operation remains pending."
                    }
                }
                onResolved?.invoke(resolution)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "Resolution error: ${e.message}"
            } finally {
                _isActionLoading.value = false
            }
        }
    }

    fun toggleUserActive(userIndex: Int, userId: String, active: Boolean): kotlinx.coroutines.Job =
        viewModelScope.launch {
            val fallbackProvider = _selectedProvider.value
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            _isActionLoading.value = true
            _error.value = null
            try {
                val localAcc = withContext(Dispatchers.IO) {
                    localAccountRepository.findActiveAccountsBySubscriberIdentity(userIndex, userId).firstOrNull()
                        ?: localAccountRepository.findActiveAccountByUsernameOrIdOneShot(userId)
                }
                val accountProvider = localAcc?.operationProvider ?: fallbackProvider
                val targetGateway = sasGatewayRouter.getGateway(accountProvider)
                val opResult = if (active) {
                    targetGateway.activateSubscriber(userIndex.toString())
                } else {
                    targetGateway.suspendSubscriber(userIndex.toString())
                }
                val success = opResult.isApplied
                if (success) {
                    val msg = if (active) "Status for $userId updated successfully." else "Subscriber $userId suspended successfully."
                    _actionSuccess.value = msg
                    audit.logAction(
                        if (active) "RESUME_USER" else "SUSPEND_USER",
                        "USER",
                        userId,
                        if (active) "Resumed subscriber account" else "Suspended subscriber account"
                    )
                    loadUserDetail(userIndex, _selectedUser.value?.userIDLower)
                } else {
                    _error.value = "Status update failed."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                val isNetworkIssue = e.message?.contains("Network unavailable", ignoreCase = true) == true || 
                                     e.message?.contains("Connection error", ignoreCase = true) == true || 
                                     e.message?.contains("ConnectException", ignoreCase = true) == true || 
                                     e.message?.contains("UnknownHostException", ignoreCase = true) == true || 
                                     e.message?.contains("SocketTimeoutException", ignoreCase = true) == true ||
                                     e is com.example.core.network.SasTransportException

                if (isNetworkIssue) {
                    val actionTextAr = if (active) "تفعيل الحساب" else "إيقاف الحساب"
                    val actionTextEn = if (active) "Account activation" else "Account suspension"
                    _error.value = if (prefs.getLanguage() == "ar") {
                        "فشل الاتصال بالشبكة: تعذر $actionTextAr على خوادم إيرثلنك. يرجى التأكد من الاتصال بالإنترنت وإعادة المحاولة."
                    } else {
                        "Network connection failed: $actionTextEn could not be processed on Earthlink servers. Please check your internet connection and try again."
                    }
                } else {
                    _error.value = e.message
                }
            } finally {
                _isActionLoading.value = false
            }
        }

    fun changeAccountType(
        userIndex: Int,
        userId: String,
        accountIndex: Int,
        accountName: String,
        account: LocalAccount? = null,
        newPriceIqd: Double? = null
    ): kotlinx.coroutines.Job =
        viewModelScope.launch {
            val fallbackProvider = _selectedProvider.value
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            _isActionLoading.value = true
            _error.value = null
            try {
                val localAcc = if (account == null) {
                    withContext(Dispatchers.IO) {
                        localAccountRepository.findActiveAccountsBySubscriberIdentity(userIndex, userId).firstOrNull()
                            ?: localAccountRepository.findActiveAccountByUsernameOrIdOneShot(userId)
                    }
                } else null
                val accountProvider = account?.operationProvider ?: localAcc?.operationProvider ?: fallbackProvider
                val targetGateway = sasGatewayRouter.getGateway(accountProvider)
                val opResult = targetGateway.changePlan(userIndex.toString(), accountIndex.toString())
                val success = opResult.isApplied
                if (success) {
                    val effectiveAccount = account ?: localAcc
                    if (effectiveAccount != null) {
                        val updated = effectiveAccount.copy(
                            packageName = accountName,
                            currentPriceIqd = newPriceIqd ?: effectiveAccount.currentPriceIqd,
                            updatedAt = System.currentTimeMillis()
                        )
                        withContext(Dispatchers.IO) {
                            localAccountRepository.saveAccount(updated)
                        }
                    }
                    _actionSuccess.value = "Package changed to $accountName successfully."
                    audit.logAction(
                        "CHANGE_PACKAGE",
                        "USER",
                        userId,
                        "Changed subscriber package/account status to $accountName"
                    )
                    loadUserDetail(userIndex, _selectedUser.value?.userIDLower)
                } else {
                    _error.value = "Failed to update package."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            } finally {
                _isActionLoading.value = false
            }
        }

    // --- Password Tools ---
    private val _revealedUserPass = MutableStateFlow<String?>(null)
    val revealedUserPass = _revealedUserPass.asStateFlow()

    private val _revealedAccountPass = MutableStateFlow<String?>(null)
    val revealedAccountPass = _revealedAccountPass.asStateFlow()

    fun clearRevealedPasswords() {
        _revealedUserPass.value = null
        _revealedAccountPass.value = null
    }

    fun revealUserPassword(userIndex: Int, userId: String): kotlinx.coroutines.Job =
        viewModelScope.launch {
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _revealedUserPass.value = null
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            try {
                val pass = gateway.showUserPassword(userIndex, userId)
                if (pass.isNullOrBlank()) {
                    _revealedUserPass.value = null
                    _error.value = "Failed to reveal user password."
                } else {
                    _revealedUserPass.value = pass
                    audit.logAction("REVEAL_PASSWORD_USER", "USER", userId, "Revealed user portal password")
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _revealedUserPass.value = null
                _error.value = e.message ?: "Failed to reveal user password."
            }
        }

    fun revealAccountPassword(userIndex: Int, userId: String): kotlinx.coroutines.Job =
        viewModelScope.launch {
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _revealedAccountPass.value = null
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            try {
                val pass = gateway.showAccountPassword(userIndex, userId)
                if (pass.isNullOrBlank()) {
                    _revealedAccountPass.value = null
                    _error.value = "Failed to reveal broadband account password."
                } else {
                    _revealedAccountPass.value = pass
                    audit.logAction("REVEAL_PASSWORD_ACCOUNT", "USER", userId, "Revealed broadband account password")
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _revealedAccountPass.value = null
                _error.value = e.message ?: "Failed to reveal broadband account password."
            }
        }

    fun changeUserPassword(userIndex: Int, userId: String, newPass: String): kotlinx.coroutines.Job =
        viewModelScope.launch {
            val fallbackProvider = _selectedProvider.value
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            _isActionLoading.value = true
            _error.value = null
            try {
                val localAcc = withContext(Dispatchers.IO) {
                    localAccountRepository.findActiveAccountsBySubscriberIdentity(userIndex, userId).firstOrNull()
                        ?: localAccountRepository.findActiveAccountByUsernameOrIdOneShot(userId)
                }
                val accountProvider = localAcc?.operationProvider ?: fallbackProvider
                val targetGateway = sasGatewayRouter.getGateway(accountProvider)
                val opResult = targetGateway.changePassword(userIndex.toString(), newPass)
                val success = opResult.isApplied
                if (success) {
                    _revealedUserPass.value = newPass
                    _actionSuccess.value = if (prefs.getLanguage() == "ar") "تم تغيير كلمة مرور البوابة بنجاح." else "User password changed successfully."
                    audit.logAction("CHANGE_PASSWORD_USER", "USER", userId, "Modified user password")
                } else {
                    _error.value = "Failed to change user password."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            } finally {
                _isActionLoading.value = false
            }
        }

    fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): kotlinx.coroutines.Job =
        viewModelScope.launch {
            if (isSyntheticLocalIspIdentity(userIndex, userId)) {
                _error.value = "Action unavailable for local-only account."
                return@launch
            }
            _isActionLoading.value = true
            _error.value = null
            try {
                val success = gateway.changeAccountPassword(userIndex, userId, newPass)
                if (success) {
                    _revealedAccountPass.value = newPass
                    _actionSuccess.value = if (prefs.getLanguage() == "ar") "تم تغيير كلمة مرور الاشتراك بنجاح." else "Broadband account password changed successfully."
                    audit.logAction("CHANGE_PASSWORD_ACCOUNT", "USER", userId, "Modified broadband PPPoE password")
                } else {
                    _error.value = "Failed to change PPPoE password."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            } finally {
                _isActionLoading.value = false
        }
    }

    fun updateUserDisplayName(userIndex: Int, newName: String, account: LocalAccount? = null): kotlinx.coroutines.Job =
        viewModelScope.launch {
            _isActionLoading.value = true
            _error.value = null
            try {
                if (account != null) {
                    // Reseller-owned field: persisted locally regardless of gateway API update outcome.
                    val updated = account.copy(
                        displayName = newName,
                        updatedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        localAccountRepository.saveAccount(updated)
                    }
                }
                val targetUserId = account?.earthlinkUsername ?: _selectedUser.value?.userID ?: ""
                if (isSyntheticLocalIspIdentity(userIndex, targetUserId)) {
                    _actionSuccess.value = if (prefs.getLanguage() == "ar") {
                        "تم تعديل اسم المشترك بنجاح."
                    } else {
                        "Subscriber display name updated successfully."
                    }
                    audit.logAction("UPDATE_DISPLAY_NAME", "USER", userIndex.toString(), "Updated display name to $newName")
                    loadUserDetail(userIndex, targetUserId)
                    return@launch
                }
                val success = gateway.updateUserDisplayName(userIndex, newName)
                if (success) {
                    _actionSuccess.value = if (prefs.getLanguage() == "ar") {
                        "تم تعديل اسم المشترك بنجاح."
                    } else {
                        "Subscriber display name updated successfully."
                    }
                    audit.logAction("UPDATE_DISPLAY_NAME", "USER", userIndex.toString(), "Updated display name to $newName")
                    loadUserDetail(userIndex, _selectedUser.value?.userIDLower)
                } else {
                    _error.value = "Failed to update display name on Earthlink."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            } finally {
                _isActionLoading.value = false
            }
        }

    suspend fun hasActivePendingOperation(accountId: String): Boolean {
        if (accountId.isBlank()) return false
        return withContext(Dispatchers.IO) {
            localLedgerRepository.getPendingOperationByAccountId(accountId) != null
        }
    }

    fun updateAccountProvider(
        account: LocalAccount,
        newProvider: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ): kotlinx.coroutines.Job = viewModelScope.launch {
        _isActionLoading.value = true
        _error.value = null
        try {
            if (hasActivePendingOperation(account.id)) {
                val msg = "Cannot change provider: account has an active in-flight operation."
                _error.value = msg
                onError(msg)
                return@launch
            }
            val updated = account.copy(
                operationProvider = newProvider,
                updatedAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) {
                localAccountRepository.saveAccount(updated)
            }
            _actionSuccess.value = if (prefs.getLanguage() == "ar") {
                "تم تغيير مزود الخدمة بنجاح."
            } else {
                "Account provider updated successfully."
            }
            onSuccess()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val msg = e.message ?: "Failed to update account provider"
            _error.value = msg
            onError(msg)
        } finally {
            _isActionLoading.value = false
        }
    }
}
