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
    private val audit: com.example.domain.repository.AuditRepository,
    val prefs: com.example.core.security.PreferenceManager,
    private val localAccountRepository: com.example.domain.repository.LocalAccountRepository,
    private val localLedgerRepository: com.example.domain.repository.LocalLedgerRepository,
    private val syncRepo: com.example.domain.repository.SyncRepository? = null
) : ViewModel() {

    companion object {
        private val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        private val bghFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad") }
        private val bghFormatFull = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad") }

        @Synchronized fun parseIsoDate(str: String): java.util.Date? = try { isoFormat.parse(str) } catch (_: Exception) { null }
        @Synchronized fun parseBghDate(str: String): java.util.Date? = try { bghFormat.parse(str) } catch (_: Exception) { null }
        @Synchronized fun formatBghFullDate(date: java.util.Date): String = try { bghFormatFull.format(date) } catch (_: Exception) { "" }
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
        localAccountRepository.getAccountByUsernameOrId(username)

    fun getLedgerForAccount(accountId: String): Flow<List<com.example.core.model.LocalLedgerEntry>> =
        localLedgerRepository.getLedgerForAccount(accountId)

    suspend fun getResellerBalance(): Double = withContext(Dispatchers.IO) {
        try {
            gateway.getBalance()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            0.0
        }
    }

    suspend fun getAccountCost(accountIndex: Int): Double = withContext(Dispatchers.IO) {
        try {
            gateway.getAccountCost(accountIndex)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            0.0
        }
    }


    fun search() {
        if (_searchQuery.value.isEmpty()) return
        val isDemo = prefs.getDemoMode()
        val isCredentialsEmpty = !isDemo && (prefs.getIspAdminUsername().isNullOrBlank() || prefs.getIspAdminPassword().isNullOrBlank())

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
                val res = gateway.searchUsers(query = _searchQuery.value, startIndex = 0, rowCount = 200)
                _usersList.value = res.itemsList ?: emptyList()
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                val isNetworkIssue = e.message?.contains("Network unavailable", ignoreCase = true) == true || 
                                     e.message?.contains("Connection error", ignoreCase = true) == true || 
                                     e.message?.contains("ConnectException", ignoreCase = true) == true || 
                                     e.message?.contains("UnknownHostException", ignoreCase = true) == true || 
                                     e.message?.contains("SocketTimeoutException", ignoreCase = true) == true

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

    fun loadUserDetail(userIndex: Int, knownUserId: String? = null) {
        viewModelScope.launch {
            _error.value = null
            
            // Prepare instant optimistic detail if available
            prepareUserDetail(userIndex)

            // If still null, try finding in local DB via targeted lookup
            if (_selectedUser.value == null) {
                try {
                    val foundLocal = withContext(Dispatchers.IO) {
                        if (!knownUserId.isNullOrBlank()) {
                            localAccountRepository.findAccountByUsernameOrIdOneShot(knownUserId)
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

            _isRefreshingDetail.value = true
            try {
                val detail = gateway.getUserDetail(userIndex)
                _selectedUser.value = detail
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                if (_selectedUser.value == null) {
                    _error.value = e.message
                }
            } finally {
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

                val cost = try { gateway.getAccountCost(pkgIndex) } catch (e: Exception) { 0.0 }
                if (!cost.isFinite() || cost <= 0.0 || cost % 1.0 != 0.0 || cost % 250.0 != 0.0) {
                    _error.value = "Failed to determine a valid IQD package cost. Operation aborted."
                    return@launch
                }
                val exactAmountIqd = cost.toLong()

                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = username,
                        operationType = "ACTIVATION",
                        amountIqd = exactAmountIqd,
                        payloadJson = "{\"username\":\"$username\",\"phone\":\"$phone\",\"fullName\":\"$fullName\",\"pkgIndex\":$pkgIndex}",
                        status = "PENDING",
                        dispatchClaimCount = 0
                    )
                )

                val claimGranted = localLedgerRepository.claimDispatchAuthorization(businessTxId)
                if (!claimGranted) {
                    _error.value = "Operation is already processing or awaiting verification."
                    return@launch
                }

                val generatedPassword = gateway.createUserUsingDeposit(username, phone, fullName, pkgIndex, depositPass)
                if (generatedPassword != null) {
                    val localAcc = localAccountRepository.getAccountByIdOneShot(username)
                        ?: localAccountRepository.findAccountByUsernameOrIdOneShot(username)
                    if (localAcc == null) {
                        val newAcc = LocalAccount(
                            id = username,
                            earthlinkUsername = username,
                            displayName = fullName.ifBlank { username },
                            phone1 = phone,
                            currentPriceIqd = exactAmountIqd.toDouble(),
                            debtIqd = 0.0
                        )
                        localAccountRepository.saveAccount(newAcc)
                    }
                    localLedgerRepository.resolvePendingOperationVerifiedSuccess(businessTxId, "[VERIFIED ACTIVATION]")
                    _actionSuccess.value = "Paid subscriber $username created successfully.\nPassword: $generatedPassword"
                    audit.logAction("CREATE_PAID_USER", "USER", username, "Created subscriber using reseller deposit")
                } else {
                    localLedgerRepository.resolvePendingOperationVerifiedFailure(businessTxId, "Subscriber creation failed")
                    _error.value = "Subscriber creation failed."
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
        val lock = getAccountLock("${userId}:REFILL")

        return viewModelScope.launch(Dispatchers.IO) {
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

                val localAcc = localAccountRepository.getAccountByIdOneShot(userId)
                    ?: localAccountRepository.findAccountByUsernameOrIdOneShot(userId)
                val effectiveAcc = if (localAcc == null) {
                    val snapshotUser = _selectedUser.value?.takeIf { it.userID.equals(userId, ignoreCase = true) }
                    val snapshotListItem = _usersList.value.find { it.userID.equals(userId, ignoreCase = true) }
                    val displayName = account?.displayName?.ifBlank { null }
                        ?: snapshotUser?.customerFullName
                        ?: snapshotListItem?.customerName
                        ?: userId
                    val phone = account?.phone1 ?: snapshotUser?.mobileNumber ?: snapshotListItem?.mobileNumber
                    val pkgName = account?.packageName ?: snapshotUser?.packageName ?: snapshotListItem?.packageName ?: "Default"
                    val newAcc = account?.copy(
                        id = if (account.id.isNotBlank()) account.id else userId,
                        earthlinkUsername = userId,
                        currentPriceIqd = exactAmountIqd.toDouble()
                    ) ?: LocalAccount(
                        id = userId,
                        earthlinkUsername = userId,
                        displayName = displayName.ifBlank { userId },
                        phone1 = phone,
                        packageName = pkgName,
                        currentPriceIqd = exactAmountIqd.toDouble(),
                        debtIqd = 0.0
                    )
                    localAccountRepository.saveAccount(newAcc)
                    newAcc
                } else {
                    localAcc
                }

                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = userId,
                        operationType = "REFILL",
                        amountIqd = exactAmountIqd,
                        payloadJson = "{\"userId\":\"$userId\",\"price\":$authoritativePrice,\"note\":\"$finalNote\"}",
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

    fun toggleUserActive(userIndex: Int, userId: String, active: Boolean) {
        viewModelScope.launch {
            _isActionLoading.value = true
            _error.value = null
            try {
                val success = gateway.toggleUserActive(userIndex, active)
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
                                     e.message?.contains("SocketTimeoutException", ignoreCase = true) == true

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
            _isActionLoading.value = true
            _error.value = null
            try {
                if (account != null) {
                    val updated = account.copy(
                        packageName = accountName,
                        currentPriceIqd = newPriceIqd ?: account.currentPriceIqd,
                        updatedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        localAccountRepository.saveAccount(updated)
                    }
                }
                val success = gateway.changeAccountType(userIndex, userId, accountIndex)
                if (success) {
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

    fun revealUserPassword(userIndex: Int, userId: String) {
        viewModelScope.launch {
            try {
                val pass = gateway.showUserPassword(userIndex, userId)
                _revealedUserPass.value = pass ?: ""
                audit.logAction("REVEAL_PASSWORD_USER", "USER", userId, "Revealed user portal password")
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _revealedUserPass.value = ""
            }
        }
    }

    fun revealAccountPassword(userIndex: Int, userId: String) {
        viewModelScope.launch {
            try {
                val pass = gateway.showAccountPassword(userIndex, userId)
                _revealedAccountPass.value = pass ?: ""
                audit.logAction("REVEAL_PASSWORD_ACCOUNT", "USER", userId, "Revealed broadband account password")
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _revealedAccountPass.value = ""
            }
        }
    }

    fun changeUserPassword(userIndex: Int, userId: String, newPass: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            _error.value = null
            try {
                val success = gateway.changeUserPassword(userIndex, userId, newPass)
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
    }

    fun changeAccountPassword(userIndex: Int, userId: String, newPass: String) {
        viewModelScope.launch {
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
    }

    fun updateUserDisplayName(userIndex: Int, newName: String, account: LocalAccount? = null): kotlinx.coroutines.Job =
        viewModelScope.launch {
            _isActionLoading.value = true
            _error.value = null
            try {
                if (account != null) {
                    val updated = account.copy(
                        displayName = newName,
                        updatedAt = System.currentTimeMillis()
                    )
                    withContext(Dispatchers.IO) {
                        localAccountRepository.saveAccount(updated)
                    }
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
}
