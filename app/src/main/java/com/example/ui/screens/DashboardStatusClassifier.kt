package com.example.ui.screens

import com.example.core.model.LocalAccount
import com.example.core.model.UserListItem
import java.util.Locale

enum class DashboardStatusFilter(val key: String, val titleAr: String, val titleEn: String) {
    ACTIVE("active", "المشتركون الفعّالون", "Active Users"),
    ONLINE("online", "المتصلون أونلاين", "Online Users"),
    OFFLINE("offline", "غير المتصلين", "Offline Users"),
    EXPIRING_SOON("expiring_soon", "قريبو الانتهاء", "Users Expiring Soon"),
    RECENTLY_EXPIRED("recently_expired", "المنتهون مؤخراً", "Recently Expired Users"),
    EXPIRED("expired", "المنتهون", "Expired Users");

    companion object {
        fun fromKey(key: String): DashboardStatusFilter {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: ACTIVE
        }
    }
}

object DashboardStatusClassifier {

    private const val TWO_DAYS_MS = 2L * 24 * 60 * 60 * 1000L
    private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000L

    fun getExpirationTimestamp(user: UserListItem, matchingAccount: LocalAccount?): Long? {
        val expStr = listOfNotNull(
            user.manualExpirationDate,
            user.accountExpirationDate,
            user.expirationDate,
            matchingAccount?.expiresAt
        ).firstOrNull { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) && !it.equals("none", ignoreCase = true) }
        return parseExpirationTimestamp(expStr)
    }

    fun isUserExpired(user: UserListItem, matchingAccount: LocalAccount?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""
        val isExplicitExpired = statusClean == "expired" || statusClean == "منتهي" || statusClean == "suspendedbyagent"
        val expTimestamp = getExpirationTimestamp(user, matchingAccount)
        val isDateExpired = expTimestamp != null && expTimestamp < nowMs
        return isExplicitExpired || isDateExpired
    }

    fun isUserOnline(user: UserListItem): Boolean {
        val onlineStatusClean = user.onlineStatus?.trim()?.lowercase(Locale.US) ?: ""
        return onlineStatusClean.contains("online") || onlineStatusClean.startsWith("online")
    }

    fun matches(
        user: UserListItem,
        matchingAccount: LocalAccount?,
        filter: DashboardStatusFilter,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val expired = isUserExpired(user, matchingAccount, nowMs)
        val online = isUserOnline(user)
        val expTimestamp = getExpirationTimestamp(user, matchingAccount)

        return when (filter) {
            DashboardStatusFilter.ACTIVE -> !expired
            DashboardStatusFilter.ONLINE -> online
            DashboardStatusFilter.OFFLINE -> !expired && !online
            DashboardStatusFilter.EXPIRING_SOON -> {
                if (expired) {
                    false
                } else if (expTimestamp != null) {
                    val diffMs = expTimestamp - nowMs
                    diffMs in 0..TWO_DAYS_MS
                } else {
                    val daysLeft = user.activeDaysLeft?.toString()?.toDoubleOrNull()
                    daysLeft != null && daysLeft in 0.0..2.0
                }
            }
            DashboardStatusFilter.RECENTLY_EXPIRED -> {
                if (!expired) {
                    false
                } else if (expTimestamp != null) {
                    val pastMs = nowMs - expTimestamp
                    pastMs in 0..SEVEN_DAYS_MS
                } else {
                    false
                }
            }
            DashboardStatusFilter.EXPIRED -> expired
        }
    }

    fun getEffectiveSubscribers(
        subscribers: List<UserListItem>,
        localAccounts: List<LocalAccount>,
        localAccountMatcher: LocalAccountMatcher
    ): List<UserListItem> {
        val mergedList = ArrayList<UserListItem>(subscribers.size + localAccounts.size)
        val seenUsernames = HashSet<String>()
        val seenAccountIds = HashSet<String>()

        for (sub in subscribers) {
            val matchingAccount = localAccountMatcher.findMatchingByUsername(sub.userID) ?: localAccountMatcher.findMatching(sub)
            if (matchingAccount?.isHistoryOnlySubscriber == true) {
                continue
            }
            mergedList.add(sub)
            sub.userID.let { if (it.isNotBlank()) seenUsernames.add(it.lowercase(Locale.US)) }
            matchingAccount?.let { acc ->
                seenAccountIds.add(acc.id)
                acc.earthlinkUsername?.let { if (it.isNotBlank()) seenUsernames.add(it.lowercase(Locale.US)) }
            }
        }

        for (acc in localAccounts) {
            if (acc.isHistoryOnlySubscriber) continue
            if (seenAccountIds.contains(acc.id)) continue
            val accUserLower = acc.earthlinkUsername?.lowercase(Locale.US)
            if (accUserLower != null && accUserLower.isNotBlank() && seenUsernames.contains(accUserLower)) {
                continue
            }

            val item = UserListItem(
                userIndexLower = acc.id.hashCode(),
                userIDLower = acc.earthlinkUsername ?: "local_${acc.id}",
                customerNameLower = acc.displayName.ifBlank { acc.earthlinkUsername ?: "Unknown" },
                mobileNumberLower = acc.phone1,
                accountStatusLower = if (acc.expiresAt != null) {
                    val expTime = parseExpirationTimestamp(acc.expiresAt)
                    if (expTime != null && expTime < System.currentTimeMillis()) "Expired" else "Active"
                } else "Active",
                expirationDateLower = acc.expiresAt ?: "",
                displayNameLower = acc.displayName,
                accountNameLower = acc.packageName
            )
            mergedList.add(item)
            seenAccountIds.add(acc.id)
            accUserLower?.let { if (it.isNotBlank()) seenUsernames.add(it) }
        }

        return mergedList
    }

    fun countFiltered(
        list: List<UserListItem>,
        localAccountMatcher: LocalAccountMatcher,
        filter: DashboardStatusFilter,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        return list.count { user ->
            val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
            if (matchingAccount?.isHistoryOnlySubscriber == true) return@count false
            matches(user, matchingAccount, filter, nowMs)
        }
    }

    fun filterSubscribers(
        list: List<UserListItem>,
        localAccountMatcher: LocalAccountMatcher,
        filter: DashboardStatusFilter,
        nowMs: Long = System.currentTimeMillis()
    ): List<UserListItem> {
        return list.filter { user ->
            val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
            if (matchingAccount?.isHistoryOnlySubscriber == true) return@filter false
            matches(user, matchingAccount, filter, nowMs)
        }
    }
}
