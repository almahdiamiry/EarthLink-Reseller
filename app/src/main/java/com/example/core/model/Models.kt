package com.example.core.model

import androidx.room.*
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

import java.util.Locale

// --- API Elements ---

internal fun parseBoolLike(value: Any?): Boolean? {
    return when (value) {
        null -> null
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> {
            val s = value.trim().lowercase(Locale.US)
            when (s) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
        }
        else -> null
    }
}

@JsonClass(generateAdapter = true)
data class ApiEnvelope<T>(
    @Json(name = "value") val value: T?,
    @Json(name = "responseMessage") val responseMessage: String?,
    @Json(name = "error") val error: Any? = null,
    @Json(name = "statusCode") val statusCode: Int?,
    @Json(name = "isSuccessful") val isSuccessful: Boolean?,
    @Json(name = "totalRecords") val totalRecords: Int? = null,
    @Json(name = "TotalRecords") val totalRecordsAlt: Int? = null
) {
    val errorString: String?
        get() = when (val e = error) {
            null -> null
            is String -> e
            is Map<*, *> -> e["message"]?.toString() ?: e.toString()
            else -> e.toString()
        }
}

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
    @Json(name = "OnlineStatus") val onlineStatusUpper: String? = null,
    @Json(name = "userActive") val userActiveLower: Any? = null,
    @Json(name = "UserActive") val userActiveUpper: Any? = null,
    @Json(name = "userActiveManage") val userActiveManageLower: Any? = null,
    @Json(name = "UserActiveManage") val userActiveManageUpper: Any? = null,
    @Json(name = "isBlocked") val isBlockedLower: Any? = null,
    @Json(name = "IsBlocked") val isBlockedUpper: Any? = null
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
    val userActive: Boolean? get() = parseBoolLike(userActiveLower ?: userActiveUpper)
    val userActiveManage: Boolean? get() = parseBoolLike(userActiveManageLower ?: userActiveManageUpper)
    val isBlocked: Boolean? get() = parseBoolLike(isBlockedLower ?: isBlockedUpper)
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
    @Json(name = "userActive") val userActiveLower: Any? = null,
    @Json(name = "UserActive") val userActiveUpper: Any? = null,
    @Json(name = "userActiveManage") val userActiveManageLower: Any? = null,
    @Json(name = "UserActiveManage") val userActiveManageUpper: Any? = null,
    @Json(name = "isBlocked") val isBlockedLower: Any? = null,
    @Json(name = "IsBlocked") val isBlockedUpper: Any? = null,
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
    val userActive: Boolean? get() = parseBoolLike(userActiveLower ?: userActiveUpper)
    val userActiveManage: Boolean? get() = parseBoolLike(userActiveManageLower ?: userActiveManageUpper)
    val isBlocked: Boolean? get() = parseBoolLike(isBlockedLower ?: isBlockedUpper)
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
    @Json(name = "note") val note: String? = null,
    @Json(name = "userID") val userIDLower: String? = null,
    @Json(name = "UserID") val userIDUpper: String? = null,
    @Json(name = "userId") val userIdLowerCamel: String? = null,
    @Json(name = "UserId") val userIdUpperCamel: String? = null,
    @Json(name = "user") val user: String? = null
) {
    val userID: String? get() = userIDLower ?: userIDUpper ?: userIdLowerCamel ?: userIdUpperCamel ?: user
}

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

/**
 * LocalAccount: Primary Subscriber Entity in Room.
 *
 * SEMANTIC GUARDRAILS:
 * 1. Monetary Representation (Double / REAL):
 *    Monetary fields (debtIqd, currentPriceIqd, openingDebtIqd, etc.) use Double / SQLite REAL.
 *    This is accepted V1 technical debt (AGENTS.md Section 5), NOT a financial correctness bug.
 *    Runtime accuracy is enforced at application boundaries (whole-IQD & 250-IQD multiples).
 *    Do not attempt to migrate these fields to Long as an unrelated refactor (FW-03 is deferred post-V1).
 *
 * 2. Subscriber Lifecycle State (isHistoryOnlySubscriber):
 *    isHistoryOnlySubscriber is a subscriber lifecycle/data-set state.
 *    It must not be interpreted as authorization to physically delete historical account or ledger records.
 *    Historical debt and payments remain preserved and immutable (RED Invariant 2).
 *
 * 3. Snapshot Baseline Fields (openingDebtIqd, stateSource, etc.):
 *    Represent the imported baseline state under the snapshot contract. When stateSource != null,
 *    this baseline is authoritative for starting financial position (AGENTS.md Section 9.4).
 */
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
    val isHistoryOnlySubscriber: Boolean = false,
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

/**
 * LocalLedgerEntry: Immutable Financial Transaction Record in Room.
 *
 * SEMANTIC GUARDRAILS:
 * 1. Additive Immutability:
 *    Ledger history is strictly additive. Physical rows are never deleted for user-level financial corrections.
 *    Corrections are performed via contra-entries pointing to the original entry via correctsEntryId.
 *
 * 2. Snapshot History Flag (isSnapshotHistory):
 *    isSnapshotHistory marks imported historical/snapshot context.
 *    Whether such rows participate in reconstruction depends on the current baseline/state-source rules in BalanceCalculator.
 *    Do NOT confuse isSnapshotHistory with subscriber lifecycle flag isHistoryOnlySubscriber.
 *
 * 3. Monetary Representation (Double / REAL):
 *    amountIqd and debtAfterIqd use Double as accepted V1 technical debt (FW-03 deferred).
 */
@Entity(
    tableName = "local_ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = LocalAccount::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.NO_ACTION
        )
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["sourceBatchId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["sourceExternalId"]),
        Index(value = ["accountId", "sourceExternalId"], unique = true),
        Index(value = ["correctsEntryId"])
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
    val isSnapshotHistory: Boolean = false,
    val correctsEntryId: String? = null
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

/**
 * SyncOutbox: Durable Transport Queue for Synchronized Mutations (INV-13 / P1-G2-REQ-01).
 * Status values are strictly:
 * - "pending": Awaiting synchronization attempt
 * - "syncing": Currently in-flight during active batch push
 * - "failed": Encountered transient or persistent error; remains fully durable & retryable indefinitely
 * Note: Terminal "dead_letter" semantics are strictly prohibited.
 */
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
    val entityType: String, // "local_accounts", "local_ledger_entries", "import_batches", "audit_logs"
    val entityId: String,
    val operation: String, // "upsert", "delete"
    val payloadJson: String,
    val status: String = "pending", // strictly: "pending", "syncing", "failed" (no terminal dead_letter)
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

/**
 * PendingExternalOperation: Durable Pending-Operation Record for Financial ISP Operations (G1 / INV-11).
 * Records intent and pre-allocated transaction ID before external API dispatch.
 * Status values:
 * - "PENDING": Recorded locally prior to external call dispatch
 * - "RESOLVING": In the process of atomic post-call materialization / inspection
 * - "COMPLETED": Confirmed successful external execution and atomically materialized into local ledger + outbox
 * - "FAILED": Confirmed external failure; resolved without local ledger mutation
 *
 * SEMANTIC GUARDRAILS (dispatchClaimCount):
 * - dispatchClaimCount = 0: Local single-writer claim was NOT acquired. Execution was not authorized on this path.
 * - dispatchClaimCount = 1: Single-writer hardware claim WAS acquired before gateway dispatch. External execution may have occurred.
 * - Do NOT attempt blind redispatch when status=PENDING and dispatchClaimCount=1; restart recovery must inspect gateway statements first (RED Invariant 5).
 */
@Entity(
    tableName = "pending_external_operations",
    indices = [
        Index(value = ["operationIntentId"], unique = true),
        Index(value = ["businessTransactionId"], unique = true),
        Index(value = ["accountId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
@JsonClass(generateAdapter = true)
data class PendingExternalOperation(
    @PrimaryKey val businessTransactionId: String = java.util.UUID.randomUUID().toString(),
    val operationIntentId: String = java.util.UUID.randomUUID().toString(),
    val accountId: String,
    val operationType: String, // "ACTIVATION", "RENEWAL", "REFILL"
    val amountIqd: Long = 0L,
    val payloadJson: String = "{}",
    val status: String = "PENDING", // "PENDING", "RESOLVING", "COMPLETED", "FAILED"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val verificationEvidence: String? = null,
    @ColumnInfo(defaultValue = "0") val dispatchClaimCount: Int = 0
)

typealias StringComponents = String

enum class UnknownOutcomeResolutionResult {
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    INCONCLUSIVE
}

data class PendingOperationResolution(
    val result: UnknownOutcomeResolutionResult,
    val operation: PendingExternalOperation,
    val ledgerEntry: LocalLedgerEntry? = null,
    val diagnosticMessage: String = ""
)

/**
 * Thrown when a financial transaction or ledger entry write reuses an existing transaction ID
 * but contains a divergent payload (differing amount, account, or type), strictly violating ledger immutability (INV-01 / INV-11).
 */
class DivergentPayloadConflictException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Thrown when an operation attempts to mix a baseline from one snapshot lineage with the ledger history
 * of another snapshot lineage, violating complete-lineage preservation (P2-G3-REQ-02 / INV-01 / INV-06).
 */
class MixedLineageConflictException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Thrown when conflicting opening/current baselines exist between datasets without an approved deterministic
 * lineage selection choice prior to Room commit (P2-G3-REQ-01 / P2-G3-REQ-02 / INV-11).
 */
class IncompatibleBaselineConflictException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * ConflictResolutionChoice: Deterministic resolution choice for conflicting entities or lineages during Restore/Import.
 */
enum class ConflictResolutionChoice {
    USE_LIVE,
    USE_BACKUP,
    REPLACE,
    KEEP_BOTH,
    FAIL_ON_CONFLICT
}

/**
 * RestoreMergeDecision: Deterministic operator-approved decision contract for Restore and Import operations (P2-G3-REQ-01 / P2-G3-REQ-03 / INV-11).
 * Encapsulates all pre-commit conflict resolutions, lineage scope, and target dataset identity.
 * Strictly evaluated and verified outside the final Room write transaction.
 */
@JsonClass(generateAdapter = true)
data class RestoreMergeDecision(
    val artifactIdentity: String,
    val selectedBaselineId: String,
    val selectedLineageScope: String = "COMPLETE_LINEAGE",
    val conflictDecisions: Map<String, ConflictResolutionChoice> = emptyMap(),
    val targetDatasetSummary: String = "",
    val isApproved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * Invalidation rule: if backup artifact identity (hash) or source baseline changes,
     * or if operator has not approved the decision, the decision is invalidated and must be recomputed.
     */
    fun isValidFor(currentArtifactIdentity: String, currentBaselineId: String): Boolean {
        return isApproved &&
                artifactIdentity.equals(currentArtifactIdentity, ignoreCase = true) &&
                selectedBaselineId == currentBaselineId
    }

    fun isInvalidated(currentArtifactIdentity: String, currentBaselineId: String): Boolean {
        return !isValidFor(currentArtifactIdentity, currentBaselineId)
    }
}

/**
 * SnapshotLineage: A complete, self-contained snapshot lineage comprising an accepted baseline
 * and its associated eligible ledger history (P2-G3-REQ-02 / TQ-25).
 */
@JsonClass(generateAdapter = true)
data class SnapshotLineage(
    val lineageId: String,
    val baselineAccounts: List<LocalAccount> = emptyList(),
    val ledgerHistory: List<LocalLedgerEntry> = emptyList(),
    val importBatches: List<ImportBatch> = emptyList()
)

/**
 * RestoreMergeEvaluation: Analysis produced outside the Room transaction detailing detected conflicts,
 * deduplication candidates, and lineage pairings between live and incoming datasets (P2-G3-REQ-01 / INV-11).
 */
@JsonClass(generateAdapter = true)
data class RestoreMergeEvaluation(
    val totalLiveAccounts: Int = 0,
    val totalBackupAccounts: Int = 0,
    val totalLiveLedgers: Int = 0,
    val totalBackupLedgers: Int = 0,
    val conflictingBaselineAccounts: List<String> = emptyList(),
    val deduplicatedTransactions: List<String> = emptyList(),
    val divergentPayloadTransactions: List<String> = emptyList(),
    val newTransactions: List<String> = emptyList(),
    val isCleanMergePossible: Boolean = true
)

/**
 * RestoreMergeResult: Result of an atomic Restore Merge execution (P2-G3-REQ-01 / P2-G3-REQ-02).
 */
@JsonClass(generateAdapter = true)
data class RestoreMergeResult(
    val success: Boolean,
    val accountsMerged: Int = 0,
    val ledgersMerged: Int = 0,
    val ledgersDeduplicated: Int = 0,
    val conflictsResolved: Int = 0,
    val summary: String = ""
)



