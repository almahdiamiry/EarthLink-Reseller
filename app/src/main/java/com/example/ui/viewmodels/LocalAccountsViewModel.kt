package com.example.ui.viewmodels
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.EarthlinkApp
import com.example.core.model.*
import com.example.core.sync.ImportResult
import com.example.core.sync.UtowerImporter
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.UtowerImportPreview
import java.security.MessageDigest
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LocalAccountsViewModel(
    private val localRepo: com.example.domain.repository.LocalAccountRepository,
    private val ledgerRepo: com.example.domain.repository.LocalLedgerRepository,
    private val utowerRepo: com.example.domain.repository.UtowerImportRepository,
    private val audit: com.example.domain.repository.AuditRepository,
    private val syncRepo: com.example.domain.repository.SyncRepository,
    private val appDatabase: com.example.core.database.AppDatabase
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _filterDebt = MutableStateFlow(false)
    val filterDebt = _filterDebt.asStateFlow()

    private val _filterAdvance = MutableStateFlow(false)
    val filterAdvance = _filterAdvance.asStateFlow()

    private val _filterNoUsername = MutableStateFlow(false)
    val filterNoUsername = _filterNoUsername.asStateFlow()

    private val _filterCoordinates = MutableStateFlow(false)
    val filterCoordinates = _filterCoordinates.asStateFlow()

    private val _sortOption = MutableStateFlow("name") // "name", "debt", "price"
    val sortOption = _sortOption.asStateFlow()

    private val _displayLimit = MutableStateFlow(50)
    val displayLimit = _displayLimit.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val totalMatchingCount = combine(
        _searchQuery,
        _filterDebt,
        _filterAdvance,
        _filterNoUsername,
        _filterCoordinates
    ) { q, debt, advance, noUsername, coordinates ->
        Triple(q, debt, advance) to Pair(noUsername, coordinates)
    }.flatMapLatest { (triple, pair) ->
        val (q, debt, advance) = triple
        val (noUsername, coordinates) = pair
        localRepo.countAccountsFilteredFlow(q, debt, advance, noUsername, coordinates)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val filteredAccounts = combine(
        _searchQuery,
        _filterDebt,
        _filterAdvance,
        _filterNoUsername,
        _filterCoordinates,
        _sortOption,
        _displayLimit
    ) { flowsArray ->
        flowsArray
    }.flatMapLatest { params ->
        val q = params[0] as String
        val debt = params[1] as Boolean
        val advance = params[2] as Boolean
        val noUsername = params[3] as Boolean
        val coordinates = params[4] as Boolean
        val sort = params[5] as String
        val limit = params[6] as Int
        
        localRepo.searchAccountsFilteredFlow(q, debt, advance, noUsername, coordinates, sort, limit, 0)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedAccount = MutableStateFlow<LocalAccount?>(null)
    val selectedAccount = _selectedAccount.asStateFlow()

    private val _ledgerEntries = MutableStateFlow<List<LocalLedgerEntry>>(emptyList())
    val ledgerEntries = _ledgerEntries.asStateFlow()

    private val _importPreview = MutableStateFlow<UtowerImportPreview?>(null)
    val importPreview = _importPreview.asStateFlow()

    val importBatches = utowerRepo.getImportBatches().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setSearchQuery(value: String) { _displayLimit.value = 50; _searchQuery.value = value }
    fun toggleFilterDebt() { _displayLimit.value = 50; _filterDebt.value = !_filterDebt.value }
    fun toggleFilterAdvance() { _displayLimit.value = 50; _filterAdvance.value = !_filterAdvance.value }
    fun toggleFilterNoUsername() { _displayLimit.value = 50; _filterNoUsername.value = !_filterNoUsername.value }
    fun toggleFilterCoordinates() { _displayLimit.value = 50; _filterCoordinates.value = !_filterCoordinates.value }
    fun setSortOption(value: String) { _displayLimit.value = 50; _sortOption.value = value }

    fun loadMore() {
        _displayLimit.value += 50
    }

    fun selectAccount(account: LocalAccount?) {
        _selectedAccount.value = account
        if (account != null) {
            viewModelScope.launch {
                ledgerRepo.getLedgerForAccount(account.id).collect {
                    _ledgerEntries.value = it
                }
            }
        } else {
            _ledgerEntries.value = emptyList()
        }
    }

    fun saveAccountEdit(edited: LocalAccount) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val saved = localRepo.saveAccount(edited)
                audit.logAction("EDIT_LOCAL_ACCOUNT", "LOCAL_ACCOUNT", saved.id, "Edited local Billing Account details")
                if (_selectedAccount.value?.id == saved.id) {
                    _selectedAccount.value = saved
                }
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = "Failed to save: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun deleteAccountLocal(id: String) {
        viewModelScope.launch {
            try {
                localRepo.deleteAccount(id)
                audit.logAction("DELETE_LOCAL_ACCOUNT", "LOCAL_ACCOUNT", id, "Marked subscriber as deleted/history-only in local DB")
                if (_selectedAccount.value?.id == id) {
                    _selectedAccount.value = null
                }
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            }
        }
    }

    fun addPaymentLocal(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null) {
        viewModelScope.launch {
            try {
                val entry = ledgerRepo.addPayment(accountId, amount, note, idempotencyKey)
                audit.logAction("ADD_PAYMENT_LOCAL", "LOCAL_ACCOUNT", accountId, "Added customer payment of IQD $amount")
                // refresh selected account details
                val fresh = localRepo.getAccountByIdOneShot(accountId)
                _selectedAccount.value = fresh
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            }
        }
    }

    fun addDebtLocal(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null) {
        viewModelScope.launch {
            try {
                val entry = ledgerRepo.addDebt(accountId, amount, note, idempotencyKey)
                audit.logAction("ADD_DEBT_LOCAL", "LOCAL_ACCOUNT", accountId, "Added loan/debt load of IQD $amount")
                val fresh = localRepo.getAccountByIdOneShot(accountId)
                _selectedAccount.value = fresh
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            }
        }
    }

    fun addNoteLocal(accountId: String, note: String) {
        viewModelScope.launch {
            try {
                ledgerRepo.addNoteTransaction(accountId, note)
                audit.logAction("ADD_NOTE_LOCAL", "LOCAL_ACCOUNT", accountId, "Logged local account audit note")
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = e.message
            }
        }
    }

    private var _rawJsonHash: String? = null

    // --- Import uTower Actions ---

    fun loadImportJson(jsonString: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _importPreview.value = null
            _error.value = null
            try {
                val md = MessageDigest.getInstance("MD5")
                val hashBytes = md.digest(jsonString.toByteArray(Charsets.UTF_8))
                _rawJsonHash = hashBytes.joinToString("") { "%02x".format(it) }

                val preview = utowerRepo.processImportPreview(jsonString)
                _importPreview.value = preview
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = "Parsing error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun commitImport(fileName: String) {
        val preview = _importPreview.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val hashString = _rawJsonHash ?: run {
                    val seed = StringBuilder().apply {
                        preview.parsedSubscribers.forEach { acc ->
                            append(acc.sourceExternalId ?: acc.earthlinkUsername ?: acc.displayName)
                            append(acc.debtIqd)
                        }
                        preview.parsedTransactions.forEach { tx ->
                            append(tx.sourceExternalId ?: tx.id)
                            append(tx.occurredAt)
                            append(tx.amountIqd)
                        }
                    }.toString()
                    val md = MessageDigest.getInstance("MD5")
                    val hashBytes = md.digest(seed.toByteArray(Charsets.UTF_8))
                    hashBytes.joinToString("") { "%02x".format(it) }
                }

                val batch = utowerRepo.commitImport(preview, fileName, hashString)
                val diffMsg = if (batch.accountsImported != preview.totalAccountsFound || batch.transactionsImported != preview.totalTransactionsFound) {
                    " (Skipped duplicates: ${preview.totalAccountsFound - batch.accountsImported} subs, ${preview.totalTransactionsFound - batch.transactionsImported} txs)"
                } else ""
                val successMessage = "Imported ${batch.accountsImported} subscribers and ${batch.transactionsImported} transactions$diffMsg"
                audit.logAction("IMPORT_UTOWER_JSON", "UTOWER", fileName, successMessage)
                _error.value = successMessage // Use error state to show a toast/snackbar with the success message
                _importPreview.value = null
                _rawJsonHash = null
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = "Import Save failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun rollbackBatch(batchId: String): kotlinx.coroutines.Job = viewModelScope.launch {
        _isLoading.value = true
        try {
            val success = utowerRepo.rollbackImportBatch(batchId)
            if (success) {
                audit.logAction("ROLLBACK_IMPORT_BATCH", "BATCH", batchId, "Rolled back unaccepted temporary import batch")
                syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
            } else {
                _error.value = "Cannot rollback import batch: Batch is already accepted into canonical financial history or was not found. Use correction-by-difference for accepted transactions."
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            _error.value = e.message
        } finally {
            _isLoading.value = false
        }
    }
    private val _importResult = kotlinx.coroutines.flow.MutableStateFlow<com.example.core.sync.ImportResult?>(null)
    val importResult = _importResult.asStateFlow()

    fun importTgzFile(uri: android.net.Uri, context: android.content.Context, shouldReplace: Boolean) {
        val appContext = context.applicationContext
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            var tempFile: java.io.File? = null
            try {
                val contentResolver = appContext.contentResolver
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    _error.value = "Could not open file"
                    return@launch
                }
                tempFile = java.io.File(appContext.cacheDir, "utower_upload_${System.currentTimeMillis()}.tgz")
                inputStream.use { input ->
                    java.io.FileOutputStream(tempFile).use { out ->
                        input.copyTo(out)
                    }
                }
                
                val importer = com.example.core.sync.UtowerImporter(appContext, appDatabase)
                val result = importer.importFromFile(tempFile, shouldReplace)
                _importResult.value = result
                
                if (!result.success) {
                    _error.value = result.errorMessage ?: "Import failed during extraction or processing."
                } else {
                    syncRepo.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
                }
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                _error.value = "Failed to process import: ${e.message}"
            } finally {
                tempFile?.delete()
                _isLoading.value = false
            }
        }
    }
}
