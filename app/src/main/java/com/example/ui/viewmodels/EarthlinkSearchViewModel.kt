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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EarthlinkSearchViewModel(
    val gateway: com.example.domain.repository.EarthlinkGateway,
    val audit: com.example.domain.repository.AuditRepository,
    val prefs: com.example.core.security.PreferenceManager,
    val localAccountRepository: com.example.domain.repository.LocalAccountRepository,
    val localLedgerRepository: com.example.domain.repository.LocalLedgerRepository
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
                                        val expireDate = parseBghDate(acc.expiresAt)
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
        val lock = getAccountLock(username)
        val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
        val businessTxId = "tx_" + opIntentId

        return viewModelScope.launch(Dispatchers.IO) {
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate activation suppressed: account $username has an active inflight operation")
                return@launch
            }
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
                        operationType = "ACTIVATION",
                        amountIqd = 0L,
                        payloadJson = "{\"username\":\"$username\",\"phone\":\"$phone\",\"fullName\":\"$fullName\",\"pkgIndex\":$pkgIndex,\"isTest\":true}",
                        status = "PENDING"
                    )
                )
                val generatedPassword = gateway.createTestUser(username, phone, fullName, pkgIndex)
                if (generatedPassword != null) {
                    localLedgerRepository.completePendingOperation(businessTxId, username)
                    _actionSuccess.value = "Test subscriber $username created successfully.\nPassword: $generatedPassword"
                    audit.logAction("CREATE_TEST_USER", "USER", username, "Created test user successfully")
                } else {
                    localLedgerRepository.markPendingOperationFailed(businessTxId, "Test user creation failed")
                    _error.value = "Test user creation failed."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                localLedgerRepository.markPendingOperationFailed(businessTxId, e.message ?: "Unknown error")
                _error.value = e.message
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun createUserUsingDeposit(username: String, phone: String, fullName: String, pkgIndex: Int, depositPass: String, intentId: String? = null): kotlinx.coroutines.Job {
        val lock = getAccountLock(username)
        val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
        val businessTxId = "tx_" + opIntentId

        return viewModelScope.launch(Dispatchers.IO) {
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate activation suppressed: account $username has an active inflight operation")
                return@launch
            }
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
                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = username,
                        operationType = "ACTIVATION",
                        amountIqd = 0L,
                        payloadJson = "{\"username\":\"$username\",\"phone\":\"$phone\",\"fullName\":\"$fullName\",\"pkgIndex\":$pkgIndex}",
                        status = "PENDING"
                    )
                )
                val generatedPassword = gateway.createUserUsingDeposit(username, phone, fullName, pkgIndex, depositPass)
                if (generatedPassword != null) {
                    localLedgerRepository.completePendingOperation(businessTxId, username)
                    _actionSuccess.value = "Paid subscriber $username created successfully.\nPassword: $generatedPassword"
                    audit.logAction("CREATE_PAID_USER", "USER", username, "Created subscriber using reseller deposit")
                } else {
                    localLedgerRepository.markPendingOperationFailed(businessTxId, "Subscriber creation failed")
                    _error.value = "Subscriber creation failed."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                localLedgerRepository.markPendingOperationFailed(businessTxId, e.message ?: "Unknown error")
                _error.value = e.message
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun refillUser(
        userId: String,
        depositPass: String,
        price: Double? = null,
        note: String? = null,
        intentId: String? = null,
        onSuccessCallback: (suspend (String) -> Unit)? = null
    ): kotlinx.coroutines.Job {
        val lock = getAccountLock(userId)
        val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
        val businessTxId = "tx_" + opIntentId
        val finalPrice = price ?: 40000.0
        val finalNote = note ?: ""

        return viewModelScope.launch(Dispatchers.IO) {
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate financial operation suppressed: account $userId has an active inflight operation")
                return@launch
            }
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

                // 1. Durably record PendingExternalOperation prior to external API dispatch (G1 / INV-11)
                localLedgerRepository.recordPendingOperation(
                    PendingExternalOperation(
                        businessTransactionId = businessTxId,
                        operationIntentId = opIntentId,
                        accountId = userId,
                        operationType = "REFILL",
                        amountIqd = finalPrice.toLong(),
                        payloadJson = "{\"userId\":\"$userId\",\"price\":$finalPrice,\"note\":\"$finalNote\"}",
                        status = "PENDING"
                    )
                )

                // 2. Dispatch external ISP operation
                val success = gateway.refillUserDeposit(userId, depositPass)
                if (success) {
                    try {
                        if (onSuccessCallback != null) {
                            onSuccessCallback.invoke(businessTxId)
                        } else {
                            val localAcc = localAccountRepository.findAccountByUsernameOrIdOneShot(userId)
                            if (localAcc != null) {
                                localLedgerRepository.recordAccountRenewal(
                                    account = localAcc,
                                    newPriceIqd = finalPrice,
                                    chargeNote = if (finalNote.isNotBlank()) "[RENEW] ${finalNote.trim()}" else "[RENEW]",
                                    payNote = null,
                                    idempotencyKey = businessTxId
                                )
                            } else {
                                localLedgerRepository.completePendingOperation(businessTxId, userId)
                            }
                        }
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        android.util.Log.e("EarthlinkSearchViewModel", "Failed to execute atomic post-call materialization after renewal", e)
                    }

                    _actionSuccess.value = if (prefs.getLanguage() == "ar") {
                        "تم تجديد اشتراك المشترك $userId بنجاح."
                    } else {
                        "Subscriber $userId was renewed successfully."
                    }
                    
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    val dateStr = sdf.format(java.util.Date())
                    val noteText = if (finalNote.isNotBlank()) {
                        "تجديد اشتراك بسعر $finalPrice - $finalNote"
                    } else {
                        "تجديد اشتراك بسعر $finalPrice"
                    }
                    
                    val currentBalance = try { gateway.getBalance() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; 0.0 }
                    
                    val statementItem = com.example.core.model.AccountStatementItem(
                        occurredAt = dateStr,
                        operation = "RENEW_SUBSCRIBER",
                        depositAmount = 0.0,
                        withdrawalAmount = finalPrice,
                        balanceAfter = currentBalance,
                        note = noteText
                    )
                    gateway.addCustomStatement(statementItem)

                    audit.logAction(
                        action = "REFILL_USER",
                        entityType = "USER",
                        entityId = userId,
                        summary = "Renewed subscription at price $finalPrice. Note: $finalNote"
                    )
                    _selectedUser.value?.userIndex?.let { loadUserDetail(it, _selectedUser.value?.userIDLower) }
                } else {
                    localLedgerRepository.markPendingOperationFailed(businessTxId, "Refill action failed on server")
                    _error.value = "Refill action failed."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                localLedgerRepository.markPendingOperationFailed(businessTxId, e.message ?: "Unknown error")
                val isNetworkIssue = e.message?.contains("Network unavailable", ignoreCase = true) == true || 
                                     e.message?.contains("Connection error", ignoreCase = true) == true || 
                                     e.message?.contains("ConnectException", ignoreCase = true) == true || 
                                     e.message?.contains("UnknownHostException", ignoreCase = true) == true || 
                                     e.message?.contains("SocketTimeoutException", ignoreCase = true) == true

                if (isNetworkIssue) {
                    _error.value = if (prefs.getLanguage() == "ar") {
                        "فشل الاتصال بالشبكة: تعذر تجديد الاشتراك على خوادم إيرثلنك. يرجى التأكد من الاتصال بالإنترنت وإعادة المحاولة."
                    } else {
                        "Network connection failed: Renewal could not be processed on Earthlink servers. Please check your internet connection and try again."
                    }
                } else {
                    _error.value = e.message
                }
            } finally {
                _isActionLoading.value = false
                lock.unlock()
            }
        }
    }

    fun extendUser(userIndex: Int, userId: String, intentId: String? = null): kotlinx.coroutines.Job {
        val lock = getAccountLock(userId)
        val opIntentId = intentId ?: java.util.UUID.randomUUID().toString()
        val businessTxId = "tx_" + opIntentId

        return viewModelScope.launch(Dispatchers.IO) {
            if (!lock.tryLock()) {
                Log.w("EarthlinkSearchVM", "Duplicate extension suppressed: account $userId has an active inflight operation")
                return@launch
            }
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
                        operationType = "RENEWAL",
                        amountIqd = 0L,
                        payloadJson = "{\"userIndex\":$userIndex,\"userId\":\"$userId\"}",
                        status = "PENDING"
                    )
                )
                val success = gateway.extendUser(userIndex)
                if (success) {
                    localLedgerRepository.completePendingOperation(businessTxId, userId)
                    _actionSuccess.value = "Subscription for $userId extended successfully."
                    audit.logAction("EXTEND_USER", "USER", userId, "Extended subscriber duration")
                    loadUserDetail(userIndex, _selectedUser.value?.userIDLower)
                } else {
                    localLedgerRepository.markPendingOperationFailed(businessTxId, "Extension failed on server")
                    _error.value = "Extension failed."
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                localLedgerRepository.markPendingOperationFailed(businessTxId, e.message ?: "Unknown error")
                val isNetworkIssue = e.message?.contains("Network unavailable", ignoreCase = true) == true || 
                                     e.message?.contains("Connection error", ignoreCase = true) == true || 
                                     e.message?.contains("ConnectException", ignoreCase = true) == true || 
                                     e.message?.contains("UnknownHostException", ignoreCase = true) == true || 
                                     e.message?.contains("SocketTimeoutException", ignoreCase = true) == true

                if (isNetworkIssue) {
                    _error.value = if (prefs.getLanguage() == "ar") {
                        "فشل الاتصال بالشبكة: تعذر تمديد الاشتراك على خوادم إيرثلنك. يرجى التأكد من الاتصال بالإنترنت وإعادة المحاولة."
                    } else {
                        "Network connection failed: Extension could not be processed on Earthlink servers. Please check your internet connection and try again."
                    }
                } else {
                    _error.value = e.message
                }
            } finally {
                _isActionLoading.value = false
                lock.unlock()
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

    fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int, accountName: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            _error.value = null
            try {
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

    fun updateUserDisplayName(userIndex: Int, newName: String) {
        viewModelScope.launch {
            _isActionLoading.value = true
            _error.value = null
            try {
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
}
