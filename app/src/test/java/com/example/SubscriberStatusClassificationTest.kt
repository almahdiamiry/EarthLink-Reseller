package com.example

import com.example.core.model.UserListItem
import com.example.ui.screens.DashboardStatusClassifier
import com.example.ui.screens.DashboardStatusFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Claim: Verifies subscriber status classification across the 6 operational categories:
 * ACTIVE, ONLINE, OFFLINE, EXPIRING_SOON, RECENTLY_EXPIRED, EXPIRED.
 * Seam: JVM headless domain logic.
 * Independent Oracle: Business rules defined in CONTEXT.md and spec 001.
 */
class SubscriberStatusClassificationTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Baghdad")
    }

    private val fixedNow = 1700000000000L // arbitrary fixed epoch

    private fun dateOffset(offsetMillis: Long): String {
        return sdf.format(Date(fixedNow + offsetMillis))
    }

    private val ONE_HOUR = 3600 * 1000L
    private val ONE_DAY = 24 * ONE_HOUR

    @Test
    fun testActiveUserMatches() {
        val activeUser = UserListItem(
            userIDLower = "user_active",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            onlineStatusLower = "Offline"
        )

        assertTrue(DashboardStatusClassifier.matches(activeUser, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(activeUser, null, DashboardStatusFilter.EXPIRED, fixedNow))
    }

    @Test
    fun testOnlineUserMatches() {
        val onlineUser = UserListItem(
            userIDLower = "user_online",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Online"
        )
        val onlinePrefixUser = UserListItem(
            userIDLower = "user_online_prefix",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "OnlineNoNet"
        )

        assertTrue(DashboardStatusClassifier.matches(onlineUser, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(onlinePrefixUser, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(onlineUser, null, DashboardStatusFilter.OFFLINE, fixedNow))
    }

    @Test
    fun testOfflineUserRequiresActiveSubscription() {
        val activeOffline = UserListItem(
            userIDLower = "user_act_off",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val expiredOffline = UserListItem(
            userIDLower = "user_exp_off",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-10 * ONE_DAY),
            onlineStatusLower = "Offline"
        )

        assertTrue(DashboardStatusClassifier.matches(activeOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
        // Critical domain invariant: An expired user is NOT classified as an "Offline User" (troubleshooting pool)
        assertFalse(DashboardStatusClassifier.matches(expiredOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
    }

    @Test
    fun testExpiringSoonThresholdIsTwoDays() {
        val expiringTomorrow = UserListItem(
            userIDLower = "user_soon",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(1 * ONE_DAY + 12 * ONE_HOUR), // 36 hours
            onlineStatusLower = "Online"
        )
        val expiringInFourDays = UserListItem(
            userIDLower = "user_later",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(4 * ONE_DAY),
            onlineStatusLower = "Online"
        )

        assertTrue(DashboardStatusClassifier.matches(expiringTomorrow, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiringInFourDays, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
    }

    @Test
    fun testRecentlyExpiredVsExpiredAllTime() {
        val expiredThreeDaysAgo = UserListItem(
            userIDLower = "user_recent_exp",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-3 * ONE_DAY), // 3 days ago <= 7 days
            onlineStatusLower = "Offline"
        )
        val expiredTwentyDaysAgo = UserListItem(
            userIDLower = "user_old_exp",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-20 * ONE_DAY), // 20 days ago > 7 days
            onlineStatusLower = "Offline"
        )

        // Recently expired (<= 7 days)
        assertTrue(DashboardStatusClassifier.matches(expiredThreeDaysAgo, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredTwentyDaysAgo, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))

        // All-time expired
        assertTrue(DashboardStatusClassifier.matches(expiredThreeDaysAgo, null, DashboardStatusFilter.EXPIRED, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(expiredTwentyDaysAgo, null, DashboardStatusFilter.EXPIRED, fixedNow))
    }
}
