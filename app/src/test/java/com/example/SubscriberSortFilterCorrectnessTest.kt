package com.example

import com.example.core.model.LocalAccount
import com.example.core.model.UserListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Targeted unit tests for Subscriber Sort / Filter logic correctness
 * according to EarthLink Reseller V1 specifications.
 */
class SubscriberSortFilterCorrectnessTest {

    private fun parseExpirationTimestamp(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank() || dateStr.equals("n/a", ignoreCase = true) || dateStr.equals("none", ignoreCase = true)) {
            return null
        }

        var cleanStr = dateStr
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u206F", "")
            .replace("\u206E", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .replace("\u202D", "")
            .replace("\u202E", "")
            .replace("\u00A0", " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '٨', '٩')
        for (i in 0..9) {
            cleanStr = cleanStr.replace(arabicDigits[i], (i + 48).toChar())
            cleanStr = cleanStr.replace(persianDigits[i], (i + 48).toChar())
        }

        val baghdadTz = java.util.TimeZone.getTimeZone("Asia/Baghdad")

        try {
            val amPmRegex = """(?i)(AM|PM)""".toRegex()
            val amPmMatch = amPmRegex.find(cleanStr)
            val amPm = amPmMatch?.groupValues?.get(1)?.uppercase(Locale.US)

            val timeRegex = """(\d{1,2}):(\d{2})(?::(\d{2}))?""".toRegex()
            val timeMatch = timeRegex.find(cleanStr)

            val dateRegex = """(\d{1,4})[/-](\d{1,2})[/-](\d{1,4})""".toRegex()
            val dateMatch = dateRegex.find(cleanStr)

            if (dateMatch != null) {
                val p1 = dateMatch.groupValues[1].toInt()
                val p2 = dateMatch.groupValues[2].toInt()
                val p3 = dateMatch.groupValues[3].toInt()

                var year = 0
                var month = 0
                var day = 0

                if (p1 > 100) {
                    year = p1
                    month = p2
                    day = p3
                } else if (p3 > 100) {
                    year = p3
                    day = p1
                    month = p2
                } else {
                    year = p3 + 2000
                    day = p1
                    month = p2
                }

                if (month > 12 && day <= 12) {
                    val temp = day
                    day = month
                    month = temp
                }

                var hour = 0
                var minute = 0
                var second = 0

                if (timeMatch != null) {
                    hour = timeMatch.groupValues[1].toInt()
                    minute = timeMatch.groupValues[2].toInt()
                    second = timeMatch.groupValues[3].let { if (it.isEmpty()) 0 else it.toInt() }

                    if (amPm != null) {
                        if (amPm == "PM" && hour < 12) {
                            hour += 12
                        } else if (amPm == "AM" && hour == 12) {
                            hour = 0
                        }
                    }
                }

                if (year > 0 && month in 1..12 && day in 1..31) {
                    val cal = java.util.Calendar.getInstance(baghdadTz)
                    cal.set(java.util.Calendar.YEAR, year)
                    cal.set(java.util.Calendar.MONTH, month - 1)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, day)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
                    cal.set(java.util.Calendar.MINUTE, minute)
                    cal.set(java.util.Calendar.SECOND, second)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        val fallbackPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "yyyy/MM/dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy"
        )

        for (pattern in fallbackPatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = if (pattern.endsWith("'Z'")) java.util.TimeZone.getTimeZone("UTC") else baghdadTz
                }
                val parsed = sdf.parse(cleanStr)
                if (parsed != null) {
                    return parsed.time
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        return null
    }

    @Test
    fun testSortByExpirationDate_earliestFirstAndNullLast() {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        val expiredDateStr = sdf.format(Date(now - 86400000L * 5)) // 5 days ago
        val soonDateStr = sdf.format(Date(now + 86400000L * 2)) // in 2 days
        val laterDateStr = sdf.format(Date(now + 86400000L * 20)) // in 20 days

        val u1 = UserListItem(userIndexLower = 1, userIDLower = "user_expired", expirationDateLower = expiredDateStr)
        val u2 = UserListItem(userIndexLower = 2, userIDLower = "user_soon", expirationDateLower = soonDateStr)
        val u3 = UserListItem(userIndexLower = 3, userIDLower = "user_later", expirationDateLower = laterDateStr)
        val u4 = UserListItem(userIndexLower = 4, userIDLower = "user_none", expirationDateLower = "N/A")

        val list = listOf(u3, u4, u1, u2)

        val sorted = list.sortedWith(
            compareBy<UserListItem> { user ->
                parseExpirationTimestamp(user.expirationDate) ?: Long.MAX_VALUE
            }.thenBy { it.userID }
        )

        assertEquals("user_expired", sorted[0].userID)
        assertEquals("user_soon", sorted[1].userID)
        assertEquals("user_later", sorted[2].userID)
        assertEquals("user_none", sorted[3].userID)
    }

    @Test
    fun testSortByName_alphabeticalAndCaseInsensitive() {
        val u1 = UserListItem(userIndexLower = 1, userIDLower = "u1", displayNameLower = "علي أحمد")
        val u2 = UserListItem(userIndexLower = 2, userIDLower = "u2", displayNameLower = "حسن مهدي")
        val u3 = UserListItem(userIndexLower = 3, userIDLower = "u3", displayNameLower = "احمد جاسم")
        val u4 = UserListItem(userIndexLower = 4, userIDLower = "u4", displayNameLower = "Zaid")
        val u5 = UserListItem(userIndexLower = 5, userIDLower = "u5", displayNameLower = "adam")

        val list = listOf(u1, u2, u3, u4, u5)

        val sorted = list.sortedWith(
            compareBy<UserListItem> { user ->
                val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.userID
                name.trim().lowercase(Locale.getDefault())
            }.thenBy { it.userID }
        )

        // Latin: 'adam' comes before 'Zaid' (case-insensitive)
        val adamIdx = sorted.indexOfFirst { it.displayName == "adam" }
        val zaidIdx = sorted.indexOfFirst { it.displayName == "Zaid" }
        assertTrue(adamIdx < zaidIdx)

        // Arabic: 'احمد جاسم' starts with Alif, comes before 'حسن مهدي' and 'علي أحمد'
        val ahmedIdx = sorted.indexOfFirst { it.displayName == "احمد جاسم" }
        val hassanIdx = sorted.indexOfFirst { it.displayName == "حسن مهدي" }
        val aliIdx = sorted.indexOfFirst { it.displayName == "علي أحمد" }
        assertTrue(ahmedIdx < hassanIdx)
        assertTrue(hassanIdx < aliIdx)
    }

    @Test
    fun testSortByDebt_descendingHighestDebtFirst() {
        val u1 = UserListItem(userIndexLower = 1, userIDLower = "u1", customerNameLower = "Alice")
        val u2 = UserListItem(userIndexLower = 2, userIDLower = "u2", customerNameLower = "Bob")
        val u3 = UserListItem(userIndexLower = 3, userIDLower = "u3", customerNameLower = "Charlie")
        val u4 = UserListItem(userIndexLower = 4, userIDLower = "u4", customerNameLower = "David")

        val accounts = listOf(
            LocalAccount(id = "1", earthlinkUsername = "u1", debtIqd = 10000.0, createdAt = 0L, updatedAt = 0L),
            LocalAccount(id = "2", earthlinkUsername = "u2", debtIqd = 45000.0, createdAt = 0L, updatedAt = 0L),
            LocalAccount(id = "3", earthlinkUsername = "u3", debtIqd = 0.0, createdAt = 0L, updatedAt = 0L)
            // u4 has no local account (debt = 0.0)
        )

        val list = listOf(u1, u3, u4, u2)

        val sorted = list.sortedWith(
            compareByDescending<UserListItem> { user ->
                accounts.find { it.earthlinkUsername == user.userID }?.debtIqd ?: 0.0
            }.thenBy { it.userID }
        )

        assertEquals("u2", sorted[0].userID) // 45,000 IQD
        assertEquals("u1", sorted[1].userID) // 10,000 IQD
        // u3 and u4 both have 0.0 debt; tie-breaker places u3 before u4
        assertEquals("u3", sorted[2].userID)
        assertEquals("u4", sorted[3].userID)
    }

    @Test
    fun testStatusFilter_correctPartitioning() {
        val now = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val expiredDateStr = sdf.format(Date(now - 86400000L * 2)) // 2 days ago
        val nearDateStr = sdf.format(Date(now + 86400000L * 3)) // in 3 days
        val activeFutureDateStr = sdf.format(Date(now + 86400000L * 25)) // in 25 days

        val uActive = UserListItem(userIndexLower = 1, userIDLower = "active_user", accountStatusLower = "Active", expirationDateLower = activeFutureDateStr)
        val uNear = UserListItem(userIndexLower = 2, userIDLower = "near_user", accountStatusLower = "Active", expirationDateLower = nearDateStr)
        val uExpired = UserListItem(userIndexLower = 3, userIDLower = "expired_user", accountStatusLower = "Expired", expirationDateLower = expiredDateStr)

        val allList = listOf(uActive, uNear, uExpired)

        // Filter: "فعال" (Active) -> excludes uExpired
        val activeFiltered = allList.filter { user ->
            val expTimestamp = parseExpirationTimestamp(user.expirationDate)
            val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""
            val isExplicitExpired = statusClean == "expired" || statusClean == "منتهي" || statusClean == "suspendedbyagent"
            val isDateExpired = expTimestamp != null && expTimestamp < now
            val isExpired = isExplicitExpired || isDateExpired
            !isExpired
        }
        assertEquals(2, activeFiltered.size)
        assertTrue(activeFiltered.any { it.userID == "active_user" })
        assertTrue(activeFiltered.any { it.userID == "near_user" })

        // Filter: "قريب من الانتهاء" (Near Expiration 0..7 days) -> only uNear
        val nearFiltered = allList.filter { user ->
            val expTimestamp = parseExpirationTimestamp(user.expirationDate)
            if (expTimestamp != null) {
                val diffMs = expTimestamp - now
                diffMs in 0..(7L * 24 * 60 * 60 * 1000L)
            } else false
        }
        assertEquals(1, nearFiltered.size)
        assertEquals("near_user", nearFiltered[0].userID)

        // Filter: "منتهي" (Expired) -> only uExpired
        val expiredFiltered = allList.filter { user ->
            val expTimestamp = parseExpirationTimestamp(user.expirationDate)
            val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""
            val isExplicitExpired = statusClean == "expired" || statusClean == "منتهي" || statusClean == "suspendedbyagent"
            val isDateExpired = expTimestamp != null && expTimestamp < now
            isExplicitExpired || isDateExpired
        }
        assertEquals(1, expiredFiltered.size)
        assertEquals("expired_user", expiredFiltered[0].userID)
    }
}
