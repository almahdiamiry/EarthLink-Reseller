package com.example.core.model

import androidx.room.*
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// --- API Elements ---

@JsonClass(generateAdapter = true)
data class ApiEnvelope<T>(
    @Json(name = "value") val value: T?,
    @Json(name = "responseMessage") val responseMessage: String?,
    @Json(name = "error") val error: String?,
    @Json(name = "statusCode") val statusCode: Int?,
    @Json(name = "isSuccessful") val isSuccessful: Boolean?,
    @Json(name = "totalRecords") val totalRecords: Int? = null,
    @Json(name = "TotalRecords") val totalRecordsAlt: Int? = null
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "token_type") val tokenType: String,
    @Json(name = "expires_in") val expiresIn: Int
)

@JsonClass(generateAdapter = true)
data class DashboardSummary(
    val balance: Double = 0.0,
    val prepaidNeeded: Double = 0.0,
    val testUsersCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class AccountPackage(
    @Json(name = "accountIndex") val accountIndex: Int,
    @Json(name = "accountName") val accountName: String,
    @Json(name = "canTest") val canTest: Boolean? = false,
    @Json(name = "price") val price: Double? = 0.0
)

@JsonClass(generateAdapter = true)
data class UserListItem(
    @Json(name = "userIndex") val userIndexLower: Int? = null,
    @Json(name = "userID") val userIDLower: String? = null,
    @Json(name = "customerName") val customerNameLower: String? = null,
    @Json(name = "mobileNumber") val mobileNumberLower: String? = null,
    @Json(name = "accountStatus") val accountStatusLower: String? = null,
    @Json(name = "expirationDate") val expirationDateLower: String? = null,
    @Json(name = "manualExpirationDate") val manualExpirationDateLower: String? = null,
    @Json(name = "accountExpirationDate") val accountExpirationDateLower: String? = null,
    @Json(name = "activeDaysLeft") val activeDaysLeftLower: Any? = null,
    @Json(name = "displayName") val displayNameLower: String? = null,
    @Json(name = "accountName") val accountNameLower: String? = null,

    // TitleCase/Alternative field variants:
    @Json(name = "UserIndex") val userIndexUpper: Int? = null,
    @Json(name = "UserID") val userIDUpper: String? = null,
    @Json(name = "userId") val userIdLowerCamel: String? = null,
    @Json(name = "UserId") val userIdUpperCamel: String? = null,
    @Json(name = "CustomerName") val customerNameUpper: String? = null,
    @Json(name = "MobileNumber") val mobileNumberUpper: String? = null,
    @Json(name = "AccountStatus") val accountStatusUpper: String? = null,
    @Json(name = "ExpirationDate") val expirationDateUpper: String? = null,
    @Json(name = "ManualExpirationDate") val manualExpirationDateUpper: String? = null,
    @Json(name = "AccountExpirationDate") val accountExpirationDateUpper: String? = null,
    @Json(name = "ActiveDaysLeft") val activeDaysLeftUpper: Any? = null,
    @Json(name = "DisplayName") val displayNameUpper: String? = null,
    @Json(name = "AccountName") val accountNameUpper: String? = null,
    @Json(name = "onlineStatus") val onlineStatusLower: String? = null,
    @Json(name = "OnlineStatus") val onlineStatusUpper: String? = null
) {
    val userIndex: Int get() = userIndexLower ?: userIndexUpper ?: 0
    val userID: String get() = userIDLower ?: userIDUpper ?: userIdLowerCamel ?: userIdUpperCamel ?: ""
    val customerName: String? get() = customerNameLower ?: customerNameUpper
    val mobileNumber: String? get() = mobileNumberLower ?: mobileNumberUpper
    val accountStatus: String? get() = accountStatusLower ?: accountStatusUpper
    val expirationDate: String? get() = expirationDateLower ?: expirationDateUpper
    val manualExpirationDate: String? get() = manualExpirationDateLower ?: manualExpirationDateUpper
    val accountExpirationDate: String? get() = accountExpirationDateLower ?: accountExpirationDateUpper
    val activeDaysLeft: Any? get() = activeDaysLeftLower ?: activeDaysLeftUpper
    val displayName: String? get() = displayNameLower ?: displayNameUpper
    val packageName: String? get() = accountNameLower ?: accountNameUpper
    val onlineStatus: String? get() = onlineStatusLower ?: onlineStatusUpper
}

@JsonClass(generateAdapter = true)
data class UserListResponse(
    @Json(name = "itemsList") val itemsList: List<UserListItem>?,
    @Json(name = "totalCount") val totalCount: Int? = 0
)

@JsonClass(generateAdapter = true)
data class ActiveSessionItem(
    @Json(name = "userIndex") val userIndexLower: Int? = null,
    @Json(name = "UserIndex") val userIndexUpper: Int? = null,
    @Json(name = "userID") val userIDLower: String? = null,
    @Json(name = "UserID") val userIDUpper: String? = null,
    @Json(name = "userId") val userIdLowerCamel: String? = null,
    @Json(name = "UserId") val userIdUpperCamel: String? = null,
    
    @Json(name = "userIP") val userIPLower: String? = null,
    @Json(name = "UserIP") val userIPUpper: String? = null,
    @Json(name = "userIp") val userIpLowerCamel: String? = null,
    @Json(name = "UserIp") val userIpUpperCamel: String? = null,
    @Json(name = "ipAddress") val ipAddressLower: String? = null,
    @Json(name = "IPAddress") val ipAddressUpper: String? = null,
    
    @Json(name = "onlineTime") val onlineTimeLower: String? = null,
    @Json(name = "OnlineTime") val onlineTimeUpper: String? = null,
    @Json(name = "usageTime") val usageTimeLower: String? = null,
    @Json(name = "UsageTime") val usageTimeUpper: String? = null,
    @Json(name = "sessionTime") val sessionTimeLower: String? = null,
    @Json(name = "SessionTime") val sessionTimeUpper: String? = null,
    
    @Json(name = "onlineStatus") val onlineStatusLower: String? = null,
    @Json(name = "OnlineStatus") val onlineStatusUpper: String? = null,
    @Json(name = "onlineSince") val onlineSinceLower: String? = null,
    @Json(name = "OnlineSince") val onlineSinceUpper: String? = null,
    @Json(name = "loginTime") val loginTimeLower: String? = null,
    @Json(name = "LoginTime") val loginTimeUpper: String? = null,
    
    @Json(name = "callerMAC") val callerMACLower: String? = null,
    @Json(name = "CallerMAC") val callerMACUpper: String? = null,
    @Json(name = "callerID") val callerIDLower: String? = null,
    @Json(name = "CallerID") val callerIDUpper: String? = null
) {
    val userIndex: Int get() = userIndexLower ?: userIndexUpper ?: 0
    val userID: String get() = userIDLower ?: userIDUpper ?: userIdLowerCamel ?: userIdUpperCamel ?: ""
    val userIP: String? get() = userIPLower ?: userIPUpper ?: userIpLowerCamel ?: userIpUpperCamel ?: ipAddressLower ?: ipAddressUpper
    val onlineTime: String? get() = onlineTimeLower ?: onlineTimeUpper ?: usageTimeLower ?: usageTimeUpper ?: sessionTimeLower ?: sessionTimeUpper
    val onlineStatus: String? get() = onlineStatusLower ?: onlineStatusUpper
    val onlineSince: String? get() = onlineSinceLower ?: onlineSinceUpper ?: loginTimeLower ?: loginTimeUpper
    val callerMAC: String? get() = callerMACLower ?: callerMACUpper ?: callerIDLower ?: callerIDUpper
}

@JsonClass(generateAdapter = true)
data class ActiveSessionResponse(
    @Json(name = "itemsList") val itemsList: List<ActiveSessionItem>?,
    @Json(name = "totalCount") val totalCount: Int? = 0
)

@JsonClass(generateAdapter = true)
data class OnlineSession(
    @Json(name = "userIP") val userIPLower: String? = null,
    @Json(name = "UserIP") val userIPUpper: String? = null,
    @Json(name = "userIp") val userIpLowerCamel: String? = null,
    @Json(name = "UserIp") val userIpUpperCamel: String? = null,
    @Json(name = "ipAddress") val ipAddressLower: String? = null,
    @Json(name = "IPAddress") val ipAddressUpper: String? = null,
    @Json(name = "onlineTime") val onlineTimeLower: String? = null,
    @Json(name = "OnlineTime") val onlineTimeUpper: String? = null,
    @Json(name = "onlineStatus") val onlineStatusLower: String? = null,
    @Json(name = "OnlineStatus") val onlineStatusUpper: String? = null,
    @Json(name = "onlineSince") val onlineSinceLower: String? = null,
    @Json(name = "OnlineSince") val onlineSinceUpper: String? = null,
    @Json(name = "loginTime") val loginTimeLower: String? = null,
    @Json(name = "LoginTime") val loginTimeUpper: String? = null,
    @Json(name = "usageTime") val usageTimeLower: String? = null,
    @Json(name = "UsageTime") val usageTimeUpper: String? = null,
    @Json(name = "sessionTime") val sessionTimeLower: String? = null,
    @Json(name = "SessionTime") val sessionTimeUpper: String? = null
) {
    val userIP: String? get() = userIPLower ?: userIPUpper ?: userIpLowerCamel ?: userIpUpperCamel ?: ipAddressLower ?: ipAddressUpper
    val onlineTime: String? get() = onlineTimeLower ?: onlineTimeUpper ?: usageTimeLower ?: usageTimeUpper ?: sessionTimeLower ?: sessionTimeUpper
    val onlineSince: String? get() = onlineSinceLower ?: onlineSinceUpper ?: loginTimeLower ?: loginTimeUpper
    val onlineStatus: String? get() = onlineStatusLower ?: onlineStatusUpper
}

@JsonClass(generateAdapter = true)
data class UserDetail(
    @Json(name = "userIndex") val userIndexLower: Int? = null,
    @Json(name = "userID") val userIDLower: String? = null,
    @Json(name = "customerFullName") val customerFullNameLower: String? = null,
    @Json(name = "customerName") val customerNameLower: String? = null,
    @Json(name = "displayName") val displayNameLower: String? = null,
    @Json(name = "name") val nameLower: String? = null,
    @Json(name = "mobileNumber") val mobileNumberLower: String? = null,
    @Json(name = "packageName") val packageNameLower: String? = null,
    @Json(name = "accountIndex") val accountIndexLower: Int? = null,
    @Json(name = "accountStatus") val accountStatusLower: String? = null,
    @Json(name = "expirationDate") val expirationDateLower: String? = null,
    @Json(name = "manualExpirationDate") val manualExpirationDateLower: String? = null,
    @Json(name = "accountExpirationDate") val accountExpirationDateLower: String? = null,
    @Json(name = "activeDaysLeft") val activeDaysLeftLower: Any? = null,
    @Json(name = "currentIP") val currentIPLower: String? = null,
    @Json(name = "currentMAC") val currentMACLower: String? = null,
    @Json(name = "accountMAC") val accountMACLower: String? = null,
    @Json(name = "onlineSessionTime") val onlineSessionTimeLower: String? = null,
    @Json(name = "onlineSession") val onlineSessionLower: OnlineSession? = null,
    @Json(name = "OnlineSession") val onlineSessionUpper: OnlineSession? = null,

    // TitleCase/Alternative field variants:
    @Json(name = "UserIndex") val userIndexUpper: Int? = null,
    @Json(name = "UserID") val userIDUpper: String? = null,
    @Json(name = "userId") val userIdLowerCamel: String? = null,
    @Json(name = "UserId") val userIdUpperCamel: String? = null,
    @Json(name = "CustomerFullName") val customerFullNameUpper: String? = null,
    @Json(name = "CustomerName") val customerNameUpper: String? = null,
    @Json(name = "DisplayName") val displayNameUpper: String? = null,
    @Json(name = "Name") val nameUpper: String? = null,
    @Json(name = "MobileNumber") val mobileNumberUpper: String? = null,
    @Json(name = "PackageName") val packageNameUpper: String? = null,
    @Json(name = "accountName") val accountNameLower: String? = null,
    @Json(name = "AccountName") val accountNameUpper: String? = null,
    @Json(name = "AccountIndex") val accountIndexUpper: Int? = null,
    @Json(name = "AccountStatus") val accountStatusUpper: String? = null,
    @Json(name = "ExpirationDate") val expirationDateUpper: String? = null,
    @Json(name = "ManualExpirationDate") val manualExpirationDateUpper: String? = null,
    @Json(name = "AccountExpirationDate") val accountExpirationDateUpper: String? = null,
    @Json(name = "ActiveDaysLeft") val activeDaysLeftUpper: Any? = null,
    @Json(name = "CurrentIP") val currentIPUpper: String? = null,
    @Json(name = "userIP") val userIPLower: String? = null,
    @Json(name = "UserIP") val userIPUpper: String? = null,
    @Json(name = "routerIp") val routerIpLower: String? = null,
    @Json(name = "RouterIp") val routerIpUpper: String? = null,
    @Json(name = "CurrentMAC") val currentMACUpper: String? = null,
    @Json(name = "callerID") val callerIDLower: String? = null,
    @Json(name = "CallerID") val callerIDUpper: String? = null,
    @Json(name = "maxmac") val maxmacLower: String? = null,
    @Json(name = "MAXMAC") val maxmacUpper: String? = null,
    @Json(name = "AccountMAC") val accountMACUpper: String? = null,
    @Json(name = "OnlineSessionTime") val onlineSessionTimeUpper: String? = null,
    @Json(name = "userActive") val userActiveLower: Boolean? = null,
    @Json(name = "UserActive") val userActiveUpper: Boolean? = null,
    @Json(name = "userActiveManage") val userActiveManageLower: Boolean? = null,
    @Json(name = "UserActiveManage") val userActiveManageUpper: Boolean? = null,
    @Json(name = "isBlocked") val isBlockedLower: Boolean? = null,
    @Json(name = "IsBlocked") val isBlockedUpper: Boolean? = null,
    @Json(name = "ipAddress") val ipAddressLower: String? = null,
    @Json(name = "IPAddress") val ipAddressUpper: String? = null,
    @Json(name = "currentIp") val currentIpLowerCamel: String? = null,
    @Json(name = "CurrentIp") val currentIpUpperCamel: String? = null,
    @Json(name = "userIp") val userIpLowerCamel: String? = null,
    @Json(name = "UserIp") val userIpUpperCamel: String? = null,
    @Json(name = "routerIP") val routerIPUpper: String? = null,
    @Json(name = "RouterIP") val routerIPUpperCamel: String? = null,
    @Json(name = "onlineTime") val onlineTimeLower: String? = null,
    @Json(name = "OnlineTime") val onlineTimeUpper: String? = null,
    @Json(name = "usageTime") val usageTimeLower: String? = null,
    @Json(name = "UsageTime") val usageTimeUpper: String? = null,
    @Json(name = "sessionTime") val sessionTimeLower: String? = null,
    @Json(name = "SessionTime") val sessionTimeUpper: String? = null
) {
    val userActive: Boolean? get() = userActiveLower ?: userActiveUpper
    val userActiveManage: Boolean? get() = userActiveManageLower ?: userActiveManageUpper
    val isBlocked: Boolean? get() = isBlockedLower ?: isBlockedUpper
    val userIndex: Int get() = userIndexLower ?: userIndexUpper ?: 0
    val userID: String get() = userIDLower ?: userIDUpper ?: userIdLowerCamel ?: userIdUpperCamel ?: ""
    val customerFullName: String? get() = customerFullNameLower ?: customerFullNameUpper ?: customerNameLower ?: customerNameUpper ?: displayNameLower ?: displayNameUpper ?: nameLower ?: nameUpper
    val mobileNumber: String? get() = mobileNumberLower ?: mobileNumberUpper
    val packageName: String? get() = accountNameLower ?: accountNameUpper ?: packageNameLower ?: packageNameUpper
    val accountIndex: Int? get() = accountIndexLower ?: accountIndexUpper
    val accountStatus: String? get() = accountStatusLower ?: accountStatusUpper
    val expirationDate: String? get() = expirationDateLower ?: expirationDateUpper
    val manualExpirationDate: String? get() = manualExpirationDateLower ?: manualExpirationDateUpper
    val accountExpirationDate: String? get() = accountExpirationDateLower ?: accountExpirationDateUpper
    val activeDaysLeft: Any? get() = activeDaysLeftLower ?: activeDaysLeftUpper
    val onlineSession: OnlineSession? get() = onlineSessionLower ?: onlineSessionUpper
    val currentIP: String? get() {
        val sessionIp = onlineSession?.userIP
        if (!sessionIp.isNullOrBlank()) return sessionIp
        return currentIPLower ?: currentIPUpper ?: currentIpLowerCamel ?: currentIpUpperCamel ?:
               userIPLower ?: userIPUpper ?: userIpLowerCamel ?: userIpUpperCamel ?:
               routerIpLower ?: routerIpUpper ?: routerIPUpper ?: routerIPUpperCamel ?:
               ipAddressLower ?: ipAddressUpper
    }
    val currentMAC: String? get() = currentMACLower ?: currentMACUpper ?: callerIDLower ?: callerIDUpper ?: maxmacLower ?: maxmacUpper
    val accountMAC: String? get() = accountMACLower ?: accountMACUpper
    val onlineSessionTime: String? get() {
        val sessionTime = onlineSession?.onlineTime
        if (!sessionTime.isNullOrBlank()) return sessionTime
        return onlineTimeLower ?: onlineTimeUpper ?:
               usageTimeLower ?: usageTimeUpper ?:
               sessionTimeLower ?: sessionTimeUpper ?:
               onlineSessionTimeLower ?: onlineSessionTimeUpper
    }
}

@JsonClass(generateAdapter = true)
data class AutocompleteUser(
    @Json(name = "userIndex") val userIndex: Int,
    @Json(name = "userID") val userID: String
)

@JsonClass(generateAdapter = true)
data class AccountStatementItem(
    @Json(name = "occurredAt") val occurredAt: String? = null,
    @Json(name = "operation") val operation: String? = null,
    @Json(name = "depositAmount") val depositAmount: Double? = 0.0,
    @Json(name = "withdrawalAmount") val withdrawalAmount: Double? = 0.0,
    @Json(name = "balanceAfter") val balanceAfter: Double? = 0.0,
    @Json(name = "note") val note: String? = null
)

@JsonClass(generateAdapter = true)
data class AccountStatementResponse(
    @Json(name = "itemsList") val itemsList: List<AccountStatementItem>?,
    @Json(name = "totalCount") val totalCount: Int? = 0
)

@JsonClass(generateAdapter = true)
data class PasswordPayload(
    @Json(name = "value") val value: String? = null
)

// --- Local Entities (Room) ---

@Entity(
    tableName = "local_accounts",
    indices = [
        Index(value = ["earthlinkUsername"]),
        Index(value = ["phone1"]),
        Index(value = ["displayName"]),
        Index(value = ["updatedAt"]),
        Index(value = ["sourceBatchId"]),
        Index(value = ["sourceExternalId"], unique = true)
    ]
)
@JsonClass(generateAdapter = true)
data class LocalAccount(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val sourceExternalId: String? = null,
    val sourceBatchId: String? = null,
    val displayName: StringComponents = "",
    val earthlinkUsername: String? = null,
    val phone1: String? = null,
    val phone2: String? = null,
    val packageName: String? = null,
    val currentPriceIqd: Double = 0.0,
    val debtIqd: Double = 0.0,
    val loanIqd: Double = 0.0,
    val advanceIqd: Double = 0.0,
    val towerName: String? = null,
    val zoneName: String? = null,
    val address: String? = null,
    val nanoIp: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val note: String? = null,
    val expiresAt: String? = null,
    val lastPaymentAt: Long? = null,
    val rawJson: String? = null,
    val isLegacy: Boolean = false,
    val openingDebtIqd: Double = 0.0,
    val openingAdvanceIqd: Double = 0.0,
    val openingLoanIqd: Double = 0.0,
    val stateSource: String? = null,
    val stateConfidence: String? = null,
    val snapshotCapturedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    // Overload typealias or simple type to represent String fields
}

@Entity(
    tableName = "local_ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = LocalAccount::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["sourceBatchId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["sourceExternalId"]),
        Index(value = ["accountId", "sourceExternalId"], unique = true)
    ]
)
@JsonClass(generateAdapter = true)
data class LocalLedgerEntry(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val accountId: String,
    val sourceExternalId: String? = null,
    val sourceBatchId: String? = null,
    val typeRaw: String, // "gave" (payment), "took" (debt increase), "note"
    val amountIqd: Double,
    val debtAfterIqd: Double,
    val note: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val rawJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isSnapshotHistory: Boolean = false
)

@Entity(tableName = "import_batches")
@JsonClass(generateAdapter = true)
data class ImportBatch(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val fileHash: String,
    val accountsImported: Int,
    val transactionsImported: Int,
    val totalDebtIqd: Double,
    val warningsJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "in_progress",
    val lastCommittedSubscriberIndex: Int = 0,
    val lastCommittedTransactionIndex: Int = 0
)

@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["entityId"]),
        Index(value = ["entityType", "entityId"])
    ]
)
@JsonClass(generateAdapter = true)
data class SyncOutbox(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val entityType: String, // "local_accounts", "local_ledger_entries", "import_batches"
    val entityId: String,
    val operation: String, // "upsert", "delete"
    val payloadJson: String,
    val status: String = "pending", // "pending", "syncing", "failed"
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val importBatchId: String? = null
)

@Entity(tableName = "sync_metadata")
data class SyncData(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class AuditOrigin {
    USER_ACTION,
    SYSTEM_ACTION,
    SYNC_EVENT,
    SYNC_FAILURE,
    RESTORE_EVENT,
    MIGRATION_EVENT
}

enum class AuditSeverity {
    INFO,       // Normal operational details (e.g., Sync completion, import started)
    WARNING,    // Minor issues (e.g., connection lost during sync, non-critical database recovery)
    SECURITY,   // Security-related activity (e.g., authentication status changes, passphrase generation)
    CRITICAL    // Disastrous failure boundaries (e.g., SQLite file decryption failed, restoration errors, data corruption)
}

data class AuditLogIntegrityIssue(
    val logId: String,
    val expectedSignature: String,
    val actualSignature: String,
    val detail: String
)

@Entity(tableName = "audit_log")
@JsonClass(generateAdapter = true)
data class AuditLog(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val action: String,
    val entityType: String?,
    val entityId: String?,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
    val metadataJsonMasked: String? = null,
    val severity: String = "INFO",
    val actor: String = "system",
    val signature: String? = null,
    @ColumnInfo(defaultValue = "USER_ACTION") val origin: String = AuditOrigin.USER_ACTION.name
)

typealias StringComponents = String
