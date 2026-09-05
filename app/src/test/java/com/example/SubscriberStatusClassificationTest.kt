package com.example

import com.example.core.model.LocalAccount
import com.example.core.model.UserListItem
import com.example.ui.screens.DashboardStatusClassifier
import com.example.ui.screens.DashboardStatusFilter
import com.example.ui.screens.LocalAccountMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Claim: Verifies canonical ISP subscriber status classification across the 6 operational categories:
 * ACTIVE, ONLINE, OFFLINE, EXPIRING_SOON, RECENTLY_EXPIRED, EXPIRED.
 * Seam: JVM headless domain logic.
 * Independent Oracle: Business rules defined in Target Product Contract v0.6, CONTEXT.md, and spec 001.
 */
class SubscriberStatusClassificationTest {

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Baghdad")
    }

    private val fixedNow = 1700000000000L // arbitrary fixed reference epoch

    private fun dateOffset(offsetMillis: Long): String {
        return sdf.format(Date(fixedNow + offsetMillis))
    }

    private val ONE_HOUR = 3600 * 1000L
    private val ONE_DAY = 24 * ONE_HOUR
    private val TWO_DAYS = 2 * ONE_DAY
    private val SEVEN_DAYS = 7 * ONE_DAY

    @Test
    fun testActiveVsNonActiveClassification() {
        val activeCanonical = UserListItem(
            userIndexLower = 101,
            userIDLower = "user_active",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            onlineStatusLower = "Online"
        )
        val expiringSoonActive = UserListItem(
            userIndexLower = 102,
            userIDLower = "user_exp_soon",
            accountStatusLower = "ExpiringSoon",
            expirationDateLower = dateOffset(1 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val suspendedByAgent = UserListItem(
            userIndexLower = 103,
            userIDLower = "user_suspended_agent",
            accountStatusLower = "SuspendedByAgent",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val expiredStatusAr = UserListItem(
            userIndexLower = 104,
            userIDLower = "user_expired_ar",
            accountStatusLower = "منتهي",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val expiredStatus = UserListItem(
            userIndexLower = 105,
            userIDLower = "user_expired_status",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val activeStatusDateExpired = UserListItem(
            userIndexLower = 106,
            userIDLower = "user_active_but_date_expired",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(-1 * ONE_DAY),
            onlineStatusLower = "Offline"
        )

        // 1. Canonical active subscribers match ACTIVE
        assertTrue(DashboardStatusClassifier.matches(activeCanonical, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(expiringSoonActive, null, DashboardStatusFilter.ACTIVE, fixedNow))

        // 2. Inactive / suspended / deactivated subscribers do NOT match ACTIVE
        assertFalse(DashboardStatusClassifier.matches(suspendedByAgent, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredStatusAr, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredStatus, null, DashboardStatusFilter.ACTIVE, fixedNow))

        // 3. Active status with expired date does NOT match ACTIVE, but matches EXPIRED
        assertFalse(DashboardStatusClassifier.matches(activeStatusDateExpired, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(activeStatusDateExpired, null, DashboardStatusFilter.EXPIRED, fixedNow))
    }

    @Test
    fun testOnlineVsOfflineClassification() {
        val onlineExact = UserListItem(
            userIndexLower = 201,
            userIDLower = "u_online",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Online"
        )
        val onlineNoNet = UserListItem(
            userIndexLower = 202,
            userIDLower = "u_onlineno_net",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "OnlineNoNet"
        )
        val onlineNoNAS = UserListItem(
            userIndexLower = 203,
            userIDLower = "u_onlineno_nas",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "OnlineNoNAS"
        )
        val activeOffline = UserListItem(
            userIndexLower = 204,
            userIDLower = "u_offline",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val activeNullOnlineStatus = UserListItem(
            userIndexLower = 205,
            userIDLower = "u_null_status",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = null
        )
        val suspendedOffline = UserListItem(
            userIndexLower = 206,
            userIDLower = "u_suspended_off",
            accountStatusLower = "SuspendedByAgent",
            expirationDateLower = dateOffset(5 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val expiredOffline = UserListItem(
            userIndexLower = 207,
            userIDLower = "u_expired_off",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-5 * ONE_DAY),
            onlineStatusLower = "Offline"
        )

        // Canonical Online values match ONLINE
        assertTrue(DashboardStatusClassifier.matches(onlineExact, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(onlineNoNet, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(onlineNoNAS, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(activeOffline, null, DashboardStatusFilter.ONLINE, fixedNow))

        // Active Offline subscribers match OFFLINE
        assertTrue(DashboardStatusClassifier.matches(activeOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(activeNullOnlineStatus, null, DashboardStatusFilter.OFFLINE, fixedNow))

        // Non-active or Expired subscribers with onlineStatus='Offline' MUST NOT match OFFLINE
        assertFalse(DashboardStatusClassifier.matches(suspendedOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
    }

    @Test
    fun testExactly48HourExpirationBoundary() {
        val userAtExact48h = UserListItem(
            userIndexLower = 301,
            userIDLower = "u_exact_48h",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(TWO_DAYS),
            onlineStatusLower = "Online"
        )
        val userWithin48h = UserListItem(
            userIndexLower = 302,
            userIDLower = "u_within_48h",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(TWO_DAYS - 1000L),
            onlineStatusLower = "Online"
        )
        val userBeyond48h = UserListItem(
            userIndexLower = 303,
            userIDLower = "u_beyond_48h",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(TWO_DAYS + 1000L),
            onlineStatusLower = "Online"
        )
        val suspendedWithin48h = UserListItem(
            userIndexLower = 304,
            userIDLower = "u_suspended_48h",
            accountStatusLower = "SuspendedByAgent",
            expirationDateLower = dateOffset(1 * ONE_DAY),
            onlineStatusLower = "Online"
        )

        // Exact 48-hour boundary is inclusive
        assertTrue(DashboardStatusClassifier.matches(userAtExact48h, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(userWithin48h, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))

        // Beyond 48 hours does not match
        assertFalse(DashboardStatusClassifier.matches(userBeyond48h, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))

        // Inactive subscriber does not match EXPIRING_SOON even if within window
        assertFalse(DashboardStatusClassifier.matches(suspendedWithin48h, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
    }

    @Test
    fun testExactly7DayRecentlyExpiredBoundary() {
        val userAtExact7d = UserListItem(
            userIndexLower = 401,
            userIDLower = "u_exact_7d",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-SEVEN_DAYS),
            onlineStatusLower = "Offline"
        )
        val userWithin7d = UserListItem(
            userIndexLower = 402,
            userIDLower = "u_within_7d",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-SEVEN_DAYS + 1000L),
            onlineStatusLower = "Offline"
        )
        val userBeyond7d = UserListItem(
            userIndexLower = 403,
            userIDLower = "u_beyond_7d",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-SEVEN_DAYS - 1000L),
            onlineStatusLower = "Offline"
        )
        val activeUserNotExpired = UserListItem(
            userIndexLower = 404,
            userIDLower = "u_active_not_exp",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(1 * ONE_DAY),
            onlineStatusLower = "Online"
        )

        // Exact 7-day boundary is inclusive
        assertTrue(DashboardStatusClassifier.matches(userAtExact7d, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(userAtExact7d, null, DashboardStatusFilter.EXPIRED, fixedNow))

        assertTrue(DashboardStatusClassifier.matches(userWithin7d, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(userWithin7d, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // Beyond 7 days: not recently expired, but still expired all-time
        assertFalse(DashboardStatusClassifier.matches(userBeyond7d, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(userBeyond7d, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // Active user is never recently expired
        assertFalse(DashboardStatusClassifier.matches(activeUserNotExpired, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
    }

    @Test
    fun testExpirationExactlyAtNowMs() {
        val userExpiresExactlyNow = UserListItem(
            userIndexLower = 501,
            userIDLower = "u_exp_now",
            accountStatusLower = "Active", // gateway status might still say Active right at cutoff
            expirationDateLower = dateOffset(0),
            onlineStatusLower = "Offline"
        )

        // At exact cutoff timestamp, subscriber has elapsed and is treated as EXPIRED
        assertTrue(DashboardStatusClassifier.isUserExpired(userExpiresExactlyNow, null, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(userExpiresExactlyNow, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(userExpiresExactlyNow, null, DashboardStatusFilter.EXPIRED, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(userExpiresExactlyNow, null, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(userExpiresExactlyNow, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
    }

    @Test
    fun testOneSharedDeterministicNowMsAcrossBatch() {
        val u1ActiveOnline = UserListItem(
            userIndexLower = 601,
            userIDLower = "u1",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            onlineStatusLower = "Online"
        )
        val u2ActiveOffline = UserListItem(
            userIndexLower = 602,
            userIDLower = "u2",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            onlineStatusLower = "Offline"
        )
        val u3ExpiringSoonOnline = UserListItem(
            userIndexLower = 603,
            userIDLower = "u3",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(1 * ONE_DAY), // 24h <= 48h
            onlineStatusLower = "Online"
        )
        val u4RecentlyExpired = UserListItem(
            userIndexLower = 604,
            userIDLower = "u4",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-3 * ONE_DAY), // 3d <= 7d
            onlineStatusLower = "Offline"
        )
        val u5LongExpired = UserListItem(
            userIndexLower = 605,
            userIDLower = "u5",
            accountStatusLower = "Expired",
            expirationDateLower = dateOffset(-20 * ONE_DAY), // 20d > 7d
            onlineStatusLower = "Offline"
        )
        val u6Suspended = UserListItem(
            userIndexLower = 606,
            userIDLower = "u6",
            accountStatusLower = "SuspendedByAgent",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            onlineStatusLower = "Offline"
        )

        val batch = listOf(u1ActiveOnline, u2ActiveOffline, u3ExpiringSoonOnline, u4RecentlyExpired, u5LongExpired, u6Suspended)
        val matcher = LocalAccountMatcher(emptyList<LocalAccount>())

        // Independent expected counts at fixedNow:
        // ACTIVE: u1, u2, u3 (3)
        // ONLINE: u1, u3 (2)
        // OFFLINE: u2 (1) -- u6 is suspended by agent (expired), u4/u5 are date expired
        // EXPIRING_SOON: u3 (1)
        // RECENTLY_EXPIRED: u4 (1)
        // EXPIRED: u4, u5, u6 (3)
        assertEquals(3, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.ACTIVE, fixedNow))
        assertEquals(2, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.ONLINE, fixedNow))
        assertEquals(1, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.OFFLINE, fixedNow))
        assertEquals(1, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
        assertEquals(1, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertEquals(3, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.EXPIRED, fixedNow))

        // Time advancement of 2 days: u3 (previously expiring soon at +24h) is now expired (-24h)
        val advancedNow = fixedNow + 2 * ONE_DAY
        assertEquals(2, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.ACTIVE, advancedNow)) // u1, u2
        assertEquals(0, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.EXPIRING_SOON, advancedNow))
        assertEquals(2, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.RECENTLY_EXPIRED, advancedNow)) // u4, u3
        assertEquals(4, DashboardStatusClassifier.countFiltered(batch, matcher, DashboardStatusFilter.EXPIRED, advancedNow)) // u4, u5, u6, u3
    }
}
