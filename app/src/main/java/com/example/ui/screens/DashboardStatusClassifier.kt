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

    const val TWO_DAYS_MS = 2L * 24 * 60 * 60 * 1000L
    const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000L

    fun getExpirationTimestamp(user: UserListItem, matchingAccount: LocalAccount? = null): Long? {
        val expStr = listOfNotNull(
            user.manualExpirationDate,
            user.accountExpirationDate,
            user.expirationDate,
            matchingAccount?.expiresAt
        ).firstOrNull { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) && !it.equals("none", ignoreCase = true) }
        return parseExpirationTimestamp(expStr)
    }

    fun isUserExpired(user: UserListItem, matchingAccount: LocalAccount?, nowMs: Long): Boolean {
        val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""
        if (statusClean == "expired" || statusClean == "منتهي" || statusClean == "suspendedbyagent" || statusClean == "suspended") {
            return true
        }
        if (user.userActive == false || user.userActiveManage == false) return true
        if (user.userActive == true || user.userActiveManage == true) return false
        if (statusClean == "active" || statusClean == "فعال" || statusClean == "نشط") return false
        val expTimestamp = getExpirationTimestamp(user, matchingAccount)
        return expTimestamp != null && expTimestamp <= nowMs
    }

    fun isUserActive(user: UserListItem, matchingAccount: LocalAccount? = null, nowMs: Long = System.currentTimeMillis()): Boolean {
        val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""
        if (statusClean == "suspendedbyagent" || statusClean == "suspended" || statusClean == "expired" || statusClean == "منتهي") {
            return false
        }
        if (user.userActive == false || user.userActiveManage == false) {
            return false
        }
        if (user.userActive == true || user.userActiveManage == true) {
            return true
        }
        if (statusClean == "active" || statusClean == "فعال" || statusClean == "نشط") {
            return true
        }
        if (statusClean == "expiringsoon" || statusClean == "expiring") {
            val expTimestamp = getExpirationTimestamp(user, matchingAccount)
            return expTimestamp == null || expTimestamp > nowMs
        }
        return !isUserExpired(user, matchingAccount, nowMs)
    }

    fun isUserOffline(user: UserListItem): Boolean {
        val onlineClean = user.onlineStatus?.trim()?.lowercase(Locale.US) ?: ""
        val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""
        return onlineClean.contains("offline") || statusClean == "offline"
    }

    fun isUserOnline(user: UserListItem, matchingAccount: LocalAccount? = null, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!isUserActive(user, matchingAccount, nowMs)) return false
        return !isUserOffline(user)
    }

    fun matches(
        user: UserListItem,
        matchingAccount: LocalAccount?,
        filter: DashboardStatusFilter,
        nowMs: Long
    ): Boolean {
        val active = isUserActive(user, matchingAccount, nowMs)
        val expired = isUserExpired(user, matchingAccount, nowMs)
        val offline = isUserOffline(user)
        val online = active && !offline
        val expTimestamp = getExpirationTimestamp(user, matchingAccount)

        return when (filter) {
            DashboardStatusFilter.ACTIVE -> active
            DashboardStatusFilter.ONLINE -> online
            DashboardStatusFilter.OFFLINE -> active && offline
            DashboardStatusFilter.EXPIRING_SOON -> {
                if (!active || expired) {
                    false
                } else if (expTimestamp != null) {
                    val remainingMs = expTimestamp - nowMs
                    remainingMs in 1..TWO_DAYS_MS
                } else {
                    val daysLeft = user.activeDaysLeft?.toString()?.toDoubleOrNull()
                    daysLeft != null && daysLeft > 0.0 && daysLeft <= 2.0
                }
            }
            DashboardStatusFilter.RECENTLY_EXPIRED -> {
                if (!expired) {
                    false
                } else if (expTimestamp != null) {
                    val elapsedMs = nowMs - expTimestamp
                    elapsedMs in 0..SEVEN_DAYS_MS
                } else {
                    false
                }
            }
            DashboardStatusFilter.EXPIRED -> expired
        }
    }

    fun countFiltered(
        list: List<UserListItem>,
        localAccountMatcher: LocalAccountMatcher,
        filter: DashboardStatusFilter,
        nowMs: Long
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
        nowMs: Long
    ): List<UserListItem> {
        return list.filter { user ->
            val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
            if (matchingAccount?.isHistoryOnlySubscriber == true) return@filter false
            matches(user, matchingAccount, filter, nowMs)
        }
    }
}
