package com.example.domain.repository

import com.example.core.model.*
import kotlinx.coroutines.flow.Flow

interface EarthlinkGateway {
    suspend fun login(username: String, password: String): LoginResponse
    suspend fun getBalance(): Double
    suspend fun getTestUsersCount(affiliateIndex: Int? = null): Int
    suspend fun getActiveTestUsersCount(): Int
    suspend fun getPrepaidNeeded(): Double
    suspend fun getPrepaidNeeded(days: Int): Double = getPrepaidNeeded()
    suspend fun getPackages(): List<AccountPackage>
    suspend fun getAccountCost(accountIndex: Int): Double
    suspend fun searchUsers(query: String, startIndex: Int = 0, rowCount: Int = 30): UserListResponse
    suspend fun getUserDetail(userIndex: Int): UserDetail
    suspend fun autocompleteUser(query: String): List<AutocompleteUser>
    suspend fun checkUsernameAvailable(userId: String): Boolean
    suspend fun checkCustomerByPhone(phone: String): String? // Customer ID / details if exists
    suspend fun createCustomer(name: String, phone: String): Boolean
    suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String?
    suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String?
    suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean
    suspend fun extendUser(userIndex: Int): Boolean
    suspend fun getAccountStatement(startIndex: Int = 0, rowCount: Int = 30, query: String = ""): List<AccountStatementItem>
    suspend fun showUserPassword(userIndex: Int, userId: String): String
    suspend fun showAccountPassword(userIndex: Int, userId: String): String
    suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean
    suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean
    suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean
    suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean
    suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean
    suspend fun invalidateBalanceCache() {}
}

interface LocalAccountRepository {
    fun getAllAccounts(): Flow<List<LocalAccount>>
    suspend fun getAllAccountsOneShot(): List<LocalAccount>
    suspend fun searchAccounts(query: String, limit: Int = 100, offset: Int = 0): List<LocalAccount>
    
    fun searchAccountsFilteredFlow(
        query: String,
        filterDebt: Boolean,
        filterAdvance: Boolean,
        filterNoUsername: Boolean,
        filterCoordinates: Boolean,
        sortOption: String,
        limit: Int,
        offset: Int
    ): Flow<List<LocalAccount>>

    fun countAccountsFilteredFlow(
        query: String,
        filterDebt: Boolean,
        filterAdvance: Boolean,
        filterNoUsername: Boolean,
        filterCoordinates: Boolean
    ): Flow<Int>
    
    fun getAccountById(id: String): Flow<LocalAccount?>
    suspend fun getAccountByIdOneShot(id: String): LocalAccount?
    fun getAccountByUsernameOrId(username: String): Flow<LocalAccount?>
    suspend fun findAccountByUsernameOrIdOneShot(username: String): LocalAccount?
    suspend fun saveAccount(account: LocalAccount): LocalAccount
    suspend fun deleteAccount(id: String)

    /**
     * DEV / MAINTENANCE-ONLY: Enqueues deletion tombstones for all accounts and ledgers,
     * physically clears the tables, and increments the sync generation counter.
     * Must NOT be used for normal production subscriber flows (which preserve history via history-only status).
     */
    suspend fun deleteAllAccounts()

    /**
     * MAINTENANCE / RESET-ONLY: Physically wipes all local tables while preserving and incrementing
     * the local generation counter (g4_local_generation). Used exclusively for dataset restore or full reset.
     */
    suspend fun clearAllData(): Long
}

interface LocalLedgerRepository {
    fun getLedgerForAccount(accountId: String): Flow<List<LocalLedgerEntry>>
    suspend fun addPayment(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null): LocalLedgerEntry
    suspend fun addDebt(accountId: String, amount: Double, note: String?, idempotencyKey: String? = null): LocalLedgerEntry
    suspend fun addNoteTransaction(accountId: String, note: String): LocalLedgerEntry
    suspend fun correctTransaction(originalEntryId: String, intendedAmount: Double, note: String? = null, idempotencyKey: String? = null): LocalLedgerEntry
    suspend fun deleteTransaction(id: String)

    /**
     * LEGACY / MAINTENANCE-ONLY: Enqueues outbox tombstones for all local ledger entries and zeros account balances.
     * Must NOT be used for normal production financial flows; production financial history is strictly additive
     * (RED Invariant 2). Full dataset wipes must use [clearAllData].
     */
    suspend fun deleteAllLedgerEntries()
    suspend fun recordAccountRenewal(account: LocalAccount, newPriceIqd: Double, chargeNote: String, payNote: String?, idempotencyKey: String? = null): LocalLedgerEntry
    suspend fun recordAccountPayment(account: LocalAccount, amount: Double, note: String?, idempotencyKey: String? = null): LocalLedgerEntry
    suspend fun recordAccountDebt(account: LocalAccount, amount: Double, note: String?, idempotencyKey: String? = null): LocalLedgerEntry

    // G1 Durable Pending Operation methods
    suspend fun recordPendingOperation(operation: PendingExternalOperation): PendingExternalOperation
    suspend fun claimDispatchAuthorization(businessTransactionId: String): Boolean
    suspend fun recoverColdStartOrphanedOperations(gateway: EarthlinkGateway, processStartMs: Long)
    suspend fun getUnresolvedClaimedOperations(): List<PendingExternalOperation>
    suspend fun getPendingOperationByIntentId(operationIntentId: String): PendingExternalOperation?
    suspend fun getPendingOperationByTransactionId(businessTransactionId: String): PendingExternalOperation?
    suspend fun getPendingOperationByAccountId(accountId: String): PendingExternalOperation?
    suspend fun getAllPendingOperations(): List<PendingExternalOperation>
    suspend fun getUnresolvedPendingOperations(): List<PendingExternalOperation>
    suspend fun markPendingOperationFailed(businessTransactionId: String, error: String)

    /**
     * LEGACY HELPER: Directly marks a pending operation as completed without canonical materialization.
     * Production external operations MUST use [resolvePendingOperationVerifiedSuccess] to avoid canonical
     * financial materializer bypass (RED Invariant 4).
     */
    @Deprecated("Legacy helper: production external operations MUST use resolvePendingOperationVerifiedSuccess to avoid canonical financial materializer bypass.")
    suspend fun completePendingOperation(businessTransactionId: String, accountId: String, ledgerEntryId: String? = null)

    /**
     * MAINTENANCE-ONLY: Physically deletes a pending external operation record.
     * Normal production operations must resolve through the verification state machine
     * (resolvePendingOperationVerifiedSuccess, resolvePendingOperationVerifiedFailure, or resolvePendingOperationInconclusive).
     */
    suspend fun deletePendingOperation(businessTransactionId: String)

    // G1 Unknown-Outcome Verification Protocol (INV-11)
    suspend fun resolvePendingOperationVerifiedSuccess(businessTransactionId: String, chargeNote: String? = null): LocalLedgerEntry?
    suspend fun resolvePendingOperationVerifiedFailure(businessTransactionId: String, diagnostic: String): Boolean
    suspend fun resolvePendingOperationInconclusive(businessTransactionId: String, diagnostic: String): Boolean
    suspend fun verifyAndResolvePendingOperation(businessTransactionId: String, gateway: EarthlinkGateway, baselineExpirationDate: String? = null): PendingOperationResolution
    suspend fun submitManualVerificationEvidence(businessTransactionId: String, externalEvidence: String): PendingOperationResolution
    suspend fun resolvePendingOperationSerialized(businessTransactionId: String, gateway: EarthlinkGateway, baselineExpirationDate: String? = null): PendingOperationResolution
    suspend fun getPendingSyntheticHistory(): List<LocalLedgerEntry>
    suspend fun sweepAndResolvePendingOperations(gateway: EarthlinkGateway, graceWindowMs: Long = 5000L): List<PendingOperationResolution>
}

interface UtowerImportRepository {
    fun getImportBatches(): Flow<List<ImportBatch>>
    suspend fun processImportPreview(jsonString: String): UtowerImportPreview
    suspend fun commitImport(preview: UtowerImportPreview, fileName: String, fileHash: String): ImportBatch
    suspend fun rollbackImportBatch(batchId: String): Boolean
}

data class UtowerImportPreview(
    val fileName: String = "",
    val totalAccountsFound: Int = 0,
    val totalTransactionsFound: Int = 0,
    val accountsWithEarthlinkUsername: Int = 0,
    val accountsMissingUsername: Int = 0,
    val totalCurrentDebtIqd: Double = 0.0,
    val totalAdvanceIqd: Double = 0.0,
    val accountsWithCoordinates: Int = 0,
    val warnings: List<String> = emptyList(),
    val parsedSubscribers: List<LocalAccount> = emptyList(),
    val parsedTransactions: List<LocalLedgerEntry> = emptyList()
)

enum class SyncReason {
    STARTUP,
    USER_ACTION,
    PERIODIC,
    NETWORK_RECOVERY,
    MANUAL,
    RETRY
}

interface SyncRepository {
    val syncState: kotlinx.coroutines.flow.StateFlow<SyncStatusState>
    val syncProgress: kotlinx.coroutines.flow.StateFlow<SyncProgress>
        get() = kotlinx.coroutines.flow.MutableStateFlow(SyncProgress())
    fun triggerSync()
    fun setupPeriodicSync()
    suspend fun triggerSyncOneShot(): Boolean
    fun requestSync(reason: SyncReason)
    fun triggerSettingsSync(uid: String? = null, reason: String = "manual")
    suspend fun getPendingOutboxCount(): Int
    suspend fun getFailedCount(): Int
    suspend fun retryFailedItems(): Int
    suspend fun anonymousSignIn(): String?
    suspend fun emailSignIn(email: String, password: String): String?
    suspend fun googleSignIn(idToken: String): String?
    fun getFirebaseUid(): String?
    suspend fun signOut(force: Boolean = false, clearData: Boolean = true)
}

data class SyncProgress(
    val isSyncing: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastCompletedTime: Long = 0L,
    val lastError: String? = null
)

enum class SyncPhase {
    IDLE, PREPARING, UPLOADING, DOWNLOADING, COMPLETED, FAILED
}

enum class SyncStatusState {
    IDLE, SYNCING, OFFLINE, ERROR, AUTH_REQUIRED, COMPLETE, COMPLETE_WITH_ERRORS
}

interface AuditRepository {
    fun getAuditLogs(): Flow<List<AuditLog>>
    suspend fun logAction(
        action: String,
        entityType: String?,
        entityId: String?,
        summary: String,
        metadataJsonMasked: String? = null,
        origin: com.example.core.model.AuditOrigin = com.example.core.model.AuditOrigin.USER_ACTION
    )
    
    suspend fun log(
        severity: AuditSeverity,
        action: String,
        message: String,
        actor: String = "system",
        origin: com.example.core.model.AuditOrigin = com.example.core.model.AuditOrigin.SYSTEM_ACTION
    )
    
    suspend fun verifyLogsIntegrity(): List<AuditLogIntegrityIssue>
    suspend fun getRecentLogs(limit: Int = 100): List<AuditLog>
    suspend fun clearAuditTrail()
}
