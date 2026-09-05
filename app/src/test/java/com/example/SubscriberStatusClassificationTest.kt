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
        val activeWithManageFlag = UserListItem(
            userIndexLower = 107,
            userIDLower = "user_active_with_manage_bool",
            accountStatusLower = "Active",
            userActiveManageLower = true,
            onlineStatusLower = "Online"
        )
        val unclassifiedManageOnly = UserListItem(
            userIndexLower = 108,
            userIDLower = "user_unclassified_manage_only",
            accountStatusLower = null,
            userActiveManageLower = true,
            onlineStatusLower = "Online"
        )
        val deactivatedManageFlag = UserListItem(
            userIndexLower = 109,
            userIDLower = "user_deactivated_manage_bool",
            userActiveManageLower = false,
            onlineStatusLower = "Offline"
        )
        val unclassifiedUserActiveOnly = UserListItem(
            userIndexLower = 110,
            userIDLower = "user_unclassified_user_active_true",
            userActiveLower = true,
            accountStatusLower = null,
            userActiveManageLower = null,
            onlineStatusLower = null
        )
        val unclassifiedDateExpired = UserListItem(
            userIndexLower = 111,
            userIDLower = "user_unclassified_date_expired",
            accountStatusLower = null,
            expirationDateLower = dateOffset(-1 * ONE_DAY),
            onlineStatusLower = "Offline"
        )

        // 1. Canonical active subscribers match ACTIVE
        assertTrue(DashboardStatusClassifier.matches(activeCanonical, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(expiringSoonActive, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(activeWithManageFlag, null, DashboardStatusFilter.ACTIVE, fixedNow))

        // Subscriber whose expiration date has passed is classified as EXPIRED, not ACTIVE, even if string accountStatus is "Active"
        assertFalse(DashboardStatusClassifier.matches(activeStatusDateExpired, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(activeStatusDateExpired, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // 2. Inactive / suspended / deactivated subscribers do NOT match ACTIVE
        assertFalse(DashboardStatusClassifier.matches(suspendedByAgent, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredStatusAr, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredStatus, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(deactivatedManageFlag, null, DashboardStatusFilter.ACTIVE, fixedNow))

        // 3. Reseller toggle userActiveManage=true alone is an administrative switch, not an active subscription
        assertFalse(DashboardStatusClassifier.matches(unclassifiedManageOnly, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(unclassifiedManageOnly, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(unclassifiedManageOnly, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // 4. User with only EarthLink default userActive=true (no active status) is unclassified
        assertFalse(DashboardStatusClassifier.matches(unclassifiedUserActiveOnly, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(unclassifiedUserActiveOnly, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(unclassifiedUserActiveOnly, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // 5. Unclassified subscriber with expired date matches EXPIRED, not ACTIVE
        assertFalse(DashboardStatusClassifier.matches(unclassifiedDateExpired, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(unclassifiedDateExpired, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // 6. Subscriber with accountStatus='Active' but activeDaysLeft <= 0 matches EXPIRED, not ACTIVE
        val activeStringZeroDaysLeft = UserListItem(
            userIndexLower = 112,
            userIDLower = "user_active_zero_days",
            accountStatusLower = "Active",
            activeDaysLeftLower = 0.0,
            onlineStatusLower = "Offline"
        )
        assertFalse(DashboardStatusClassifier.matches(activeStringZeroDaysLeft, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(activeStringZeroDaysLeft, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // 7. Null or unparseable activeDaysLeft does NOT trigger expiry when account is Active with future date
        val activeNullDaysLeftFutureDate = UserListItem(
            userIndexLower = 113,
            userIDLower = "user_active_null_days",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(10 * ONE_DAY),
            activeDaysLeftLower = null,
            onlineStatusLower = "Online"
        )
        assertTrue(DashboardStatusClassifier.matches(activeNullDaysLeftFutureDate, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(activeNullDaysLeftFutureDate, null, DashboardStatusFilter.EXPIRED, fixedNow))
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

        // Exact "Online" value matches ONLINE
        assertTrue(DashboardStatusClassifier.matches(onlineExact, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(activeOffline, null, DashboardStatusFilter.ONLINE, fixedNow))

        // Discrete network states (OnlineNoNet, OnlineNoNAS) indicate gateway presence without active internet; NOT ONLINE and NOT OFFLINE
        assertFalse(DashboardStatusClassifier.matches(onlineNoNet, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(onlineNoNet, null, DashboardStatusFilter.OFFLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(onlineNoNet, null, DashboardStatusFilter.ACTIVE, fixedNow))

        assertFalse(DashboardStatusClassifier.matches(onlineNoNAS, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(onlineNoNAS, null, DashboardStatusFilter.OFFLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(onlineNoNAS, null, DashboardStatusFilter.ACTIVE, fixedNow))

        // Active subscriber with null onlineStatus does NOT match ONLINE (requires actual connection) and does NOT match OFFLINE
        assertFalse(DashboardStatusClassifier.matches(activeNullOnlineStatus, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(activeNullOnlineStatus, null, DashboardStatusFilter.OFFLINE, fixedNow))

        // Active Offline subscribers match OFFLINE
        assertTrue(DashboardStatusClassifier.matches(activeOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))

        // Non-active or Expired subscribers with onlineStatus='Offline' MUST NOT match OFFLINE or ONLINE
        assertFalse(DashboardStatusClassifier.matches(suspendedOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredOffline, null, DashboardStatusFilter.OFFLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(suspendedOffline, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(expiredOffline, null, DashboardStatusFilter.ONLINE, fixedNow))

        // Stale online session on a subscriber whose expiration date has passed must NOT match ACTIVE or ONLINE
        val staleOnlineExpiredUser = UserListItem(
            userIndexLower = 208,
            userIDLower = "u_stale_online_expired",
            accountStatusLower = "Active",
            expirationDateLower = dateOffset(-1 * ONE_DAY),
            onlineStatusLower = "Online"
        )
        assertFalse(DashboardStatusClassifier.matches(staleOnlineExpiredUser, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(staleOnlineExpiredUser, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(staleOnlineExpiredUser, null, DashboardStatusFilter.EXPIRED, fixedNow))

        // Live ISP Oracle Case (User #44): accountStatus='ExpiringSoon' with activeDaysLeft='00' and onlineStatus='Online'
        // Must be routed to EXPIRED, and strictly excluded from ACTIVE, ONLINE, and EXPIRING_SOON
        val liveIspOracleUser44 = UserListItem(
            userIndexLower = 10942873,
            userIDLower = "hussam@sacx",
            accountStatusLower = "ExpiringSoon",
            activeDaysLeftLower = "00",
            expirationDateLower = "06/09/2026 12:19 AM",
            onlineStatusLower = "Online"
        )
        assertFalse(DashboardStatusClassifier.matches(liveIspOracleUser44, null, DashboardStatusFilter.ACTIVE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(liveIspOracleUser44, null, DashboardStatusFilter.ONLINE, fixedNow))
        assertFalse(DashboardStatusClassifier.matches(liveIspOracleUser44, null, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
        assertTrue(DashboardStatusClassifier.matches(liveIspOracleUser44, null, DashboardStatusFilter.EXPIRED, fixedNow))
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
            accountStatusLower = null, // unclassified subscriber without explicit status
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
            accountStatusLower = "ExpiringSoon",
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

    @Test
    fun testRealIspDatasetExactMatches() {
        // Simulates the exact dataset verified against real EarthLink ISP:
        // 43 Active users (39 regular active + 4 expiring soon):
        //    - 30 Online (26 regular + 4 expiring soon)
        //    - 1 OnlineNoNet (connected to gateway without internet, not Online for dashboard)
        //    - 12 active users without connection status (null)
        //    - 0 Offline
        // 25 Expired users (1 recently expired within 7 days + 24 long expired).
        // 1 Unclassified user with EarthLink default userActive=true and userActiveManage=true but no active accountStatus.
        // Total = 69 subscribers.
        val subscribers = mutableListOf<UserListItem>()

        // 39 Active users (26 Online, 1 OnlineNoNet, 12 null)
        for (i in 1..39) {
            val online = when {
                i <= 26 -> "Online"
                i == 27 -> "OnlineNoNet"
                else -> null
            }
            subscribers.add(
                UserListItem(
                    userIndexLower = 1000 + i,
                    userIDLower = "active_user_$i",
                    accountStatusLower = "Active",
                    expirationDateLower = if (i % 2 == 0) dateOffset(15 * ONE_DAY) else null,
                    onlineStatusLower = online
                )
            )
        }

        // 4 Expiring Soon active users (expiring within 48h, all online -> 26 + 4 = 30 Online total)
        for (i in 1..4) {
            subscribers.add(
                UserListItem(
                    userIndexLower = 2000 + i,
                    userIDLower = "expiring_soon_user_$i",
                    accountStatusLower = "ExpiringSoon",
                    expirationDateLower = dateOffset((i * 10) * ONE_HOUR), // <= 48h
                    onlineStatusLower = "Online"
                )
            )
        }

        // 1 Recently Expired user (expired 3 days ago)
        subscribers.add(
            UserListItem(
                userIndexLower = 3001,
                userIDLower = "recently_expired_user",
                accountStatusLower = "Expired",
                expirationDateLower = dateOffset(-3 * ONE_DAY),
                onlineStatusLower = "Offline"
            )
        )

        // 24 Older Expired users (expired > 7 days ago)
        for (i in 2..25) {
            subscribers.add(
                UserListItem(
                    userIndexLower = 3000 + i,
                    userIDLower = "expired_user_$i",
                    accountStatusLower = "Expired",
                    expirationDateLower = dateOffset((-10 - i) * ONE_DAY),
                    onlineStatusLower = "Offline"
                )
            )
        }

        // 69th unclassified subscriber: in EarthLink API response has userActive=true and userActiveManage=true
        // by default (reseller toggle not suspended), but no active accountStatus and null onlineStatus.
        // Must NOT match ACTIVE, ONLINE, or EXPIRED.
        subscribers.add(
            UserListItem(
                userIndexLower = 9999,
                userIDLower = "unclassified_isp_subscriber_69",
                userActiveLower = true,
                userActiveManageLower = true,
                accountStatusLower = null,
                onlineStatusLower = null
            )
        )

        val matcher = LocalAccountMatcher(emptyList<LocalAccount>())

        assertEquals(69, subscribers.size)

        // Exact match against real EarthLink ISP counts:
        assertEquals(43, DashboardStatusClassifier.countFiltered(subscribers, matcher, DashboardStatusFilter.ACTIVE, fixedNow))
        assertEquals(30, DashboardStatusClassifier.countFiltered(subscribers, matcher, DashboardStatusFilter.ONLINE, fixedNow))
        assertEquals(0, DashboardStatusClassifier.countFiltered(subscribers, matcher, DashboardStatusFilter.OFFLINE, fixedNow))
        assertEquals(4, DashboardStatusClassifier.countFiltered(subscribers, matcher, DashboardStatusFilter.EXPIRING_SOON, fixedNow))
        assertEquals(1, DashboardStatusClassifier.countFiltered(subscribers, matcher, DashboardStatusFilter.RECENTLY_EXPIRED, fixedNow))
        assertEquals(25, DashboardStatusClassifier.countFiltered(subscribers, matcher, DashboardStatusFilter.EXPIRED, fixedNow))
    }
}
