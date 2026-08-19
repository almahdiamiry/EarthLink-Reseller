package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EarthlinkApp
import com.alamiry.earthlinkreseller.R
import com.example.core.model.*
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.UtowerImportPreview
import com.example.ui.viewmodels.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

// Formatting helper for Money

fun formatIqd(amount: Double): String {
    val formatted = com.example.core.ledger.MoneyParser.formatIqdForDisplay(amount.toDouble())
    return "\u200E$formatted د.ع"
}

@Composable
fun ErrorStateCard(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "Error"
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun EmptyStateView(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun getRemainingTime(expirationDateStr: String?, activeDaysLeftStr: String? = null, lang: String? = "ar", accountStatus: String? = null): String {
    // Robust parser for activeDaysLeftStr
    fun parseActiveDaysLeft(rawStr: String): Pair<Long, Long>? {
        var cleaned = rawStr.trim().lowercase()
        if (cleaned.isEmpty() || cleaned == "n/a" || cleaned == "none") {
            return null
        }
        
        // Convert Arabic/Persian numerals to standard English digits
        val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '٨', '٩')
        for (i in 0..9) {
            cleaned = cleaned.replace(arabicDigits[i], (i + 48).toChar())
            cleaned = cleaned.replace(persianDigits[i], (i + 48).toChar())
        }
        
        // Check if it's a pure number (integer or double)
        val doubleVal = cleaned.toDoubleOrNull()
        if (doubleVal != null) {
            val days = doubleVal.toLong()
            val fractionalDay = doubleVal - days
            var hours = (fractionalDay * 24 + 0.5).toLong()
            var daysAdjusted = days
            if (hours >= 24) {
                hours = 0
                daysAdjusted += 1
            }
            return Pair(daysAdjusted, hours)
        }
        
        var days = 0L
        var hours = 0L
        var found = false
        var fractionalDayFromRegex = 0.0
        
        // Find days: e.g. "29.5 days", "29 days", "29 d", "29 يوم", "29.2 أيام"
        val dayRegex = """(\d+(?:\.\d+)?)\s*(?:day|days|d|يوم|أيام|ايام)""".toRegex()
        val dayMatch = dayRegex.find(cleaned)
        if (dayMatch != null) {
            val dayNumStr = dayMatch.groupValues[1]
            val dayDouble = dayNumStr.toDoubleOrNull() ?: 0.0
            days = dayDouble.toLong()
            fractionalDayFromRegex = dayDouble - days
            found = true
        }
        
        // Find hours: e.g. "4.5 hours", "4 hours", "4 h", "4 ساعة", "4 ساعات"
        val hourRegex = """(\d+(?:\.\d+)?)\s*(?:hour|hours|h|ساعة|ساعات)""".toRegex()
        val hourMatch = hourRegex.find(cleaned)
        if (hourMatch != null) {
            val hourNumStr = hourMatch.groupValues[1]
            hours = (hourNumStr.toDoubleOrNull() ?: 0.0).toLong()
            found = true
        } else if (fractionalDayFromRegex > 0) {
            hours = (fractionalDayFromRegex * 24 + 0.5).toLong()
        }
        
        if (hours >= 24) {
            val extraDays = hours / 24
            days += extraDays
            hours %= 24
        }
        
        if (!found) {
            // Fallback: extract the first number as days
            val firstNumRegex = """(\d+(?:\.\d+)?)""".toRegex()
            val firstNumMatch = firstNumRegex.find(cleaned)
            if (firstNumMatch != null) {
                val num = firstNumMatch.groupValues[1].toDoubleOrNull()
                if (num != null) {
                    val d = num.toLong()
                    var h = ((num - d) * 24 + 0.5).toLong()
                    var dAdjusted = d
                    if (h >= 24) {
                        h = 0
                        dAdjusted += 1
                    }
                    return Pair(dAdjusted, h)
                }
            }
        }
        
        if (found) {
            return Pair(days, hours)
        }
        return null
    }

    // Pre-process expirationDateStr to handle high-precision ISO fractional seconds
    var sanitizedExpirationStr = expirationDateStr?.trim()
    if (!sanitizedExpirationStr.isNullOrEmpty() && !sanitizedExpirationStr.equals("n/a", ignoreCase = true)) {
        // Normalize 't' and 'z' to uppercase 'T' and 'Z' to match standard parsers
        sanitizedExpirationStr = sanitizedExpirationStr.replace('t', 'T').replace('z', 'Z')
        // Regex to trim high-precision fractional seconds (e.g., .1234567 to .123)
        val fractionalSecondsRegex = """(\.\d{3})\d+""".toRegex()
        sanitizedExpirationStr = fractionalSecondsRegex.replace(sanitizedExpirationStr, "$1")
    }

    // 1. Parse expiration date to get precise datetime if possible
    var expireDate: Date? = null
    var parsedYear = 0
    var parsedMonth = 0
    var parsedDay = 0
    var parsedHour = 0
    var parsedMinute = 0
    var parsedSecond = 0
    var parsedHasTime = false

    if (!sanitizedExpirationStr.isNullOrEmpty() && !sanitizedExpirationStr.equals("none", ignoreCase = true) && !sanitizedExpirationStr.equals("n/a", ignoreCase = true)) {
        // Remove LRM, RLM, and other invisible/directional formatting characters
        sanitizedExpirationStr = sanitizedExpirationStr
            .replace("\u200E", "") // LRM
            .replace("\u200F", "") // RLM
            .replace("\u206F", "")
            .replace("\u206E", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .replace("\u202D", "")
            .replace("\u202E", "")
            .replace("\u00A0", " ") // NBSP to normal space
            .replace("\\s+".toRegex(), " ") // Normalize multiple spaces
            .trim()

        // Convert Arabic/Persian numerals
        val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '٨', '٩')
        for (i in 0..9) {
            sanitizedExpirationStr = sanitizedExpirationStr!!.replace(arabicDigits[i], (i + 48).toChar())
            sanitizedExpirationStr = sanitizedExpirationStr!!.replace(persianDigits[i], (i + 48).toChar())
        }

        val baghdadTz = java.util.TimeZone.getTimeZone("Asia/Baghdad")

        // Component-based parsing to handle any RTL layout or ordering issue (e.g. PM 02:06 01/07/2026 or 01/07/2026 02:06 PM)
        try {
            val cleanStr = sanitizedExpirationStr!!
            
            // 1. Find AM/PM (case insensitive)
            val amPmRegex = """(?i)(AM|PM)""".toRegex()
            val amPmMatch = amPmRegex.find(cleanStr)
            val amPm = amPmMatch?.groupValues?.get(1)?.uppercase()

            // 2. Find Time: HH:MM:SS or HH:MM
            val timeRegex = """(\d{1,2}):(\d{2})(?::(\d{2}))?""".toRegex()
            val timeMatch = timeRegex.find(cleanStr)

            // 3. Find Date: YYYY-MM-DD or DD/MM/YYYY or MM/DD/YYYY or YYYY/MM/DD
            val dateRegex = """(\d{1,4})[/-](\d{1,2})[/-](\d{1,4})""".toRegex()
            val dateMatch = dateRegex.find(cleanStr)

            if (timeMatch != null && dateMatch != null) {
                var hour = timeMatch.groupValues[1].toInt()
                val minute = timeMatch.groupValues[2].toInt()
                val second = timeMatch.groupValues[3].let { if (it.isEmpty()) 0 else it.toInt() }

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

                // Self-correct day/month if month > 12 and day <= 12
                if (month > 12 && day <= 12) {
                    val temp = day
                    day = month
                    month = temp
                }

                if (amPm != null) {
                    if (amPm == "PM" && hour < 12) {
                        hour += 12
                    } else if (amPm == "AM" && hour == 12) {
                        hour = 0
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
                    expireDate = cal.time

                    parsedYear = year
                    parsedMonth = month
                    parsedDay = day
                    parsedHour = hour
                    parsedMinute = minute
                    parsedSecond = second
                    parsedHasTime = true
                }
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            // Fallback to SimpleDateFormat
        }

        if (expireDate == null) {
            val formats = listOf(
                "a hh:mm dd/MM/yyyy",
                "a hh:mm MM/dd/yyyy",
                "a hh:mm:ss dd/MM/yyyy",
                "a hh:mm:ss MM/dd/yyyy",
                "a hh:mm yyyy/MM/dd",
                "a hh:mm:ss yyyy/MM/dd",
                "a hh:mm yyyy-MM-dd",
                "a hh:mm:ss yyyy-MM-dd",
                "a h:mm d/M/yyyy",
                "a h:mm:ss d/M/yyyy",
                "a h:mm M/d/yyyy",
                "a h:mm:ss M/d/yyyy",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss.SS",
                "yyyy-MM-dd'T'HH:mm:ss.S",
                "yyyy-MM-dd HH:mm:ss.SSS",
                "yyyy-MM-dd HH:mm:ss.S",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss a",
                "yyyy-MM-dd HH:mm a",
                "yyyy-MM-dd",
                "yyyy/MM/dd HH:mm:ss a",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm a",
                "yyyy/MM/dd",
                "dd-MM-yyyy HH:mm:ss a",
                "dd-MM-yyyy HH:mm:ss",
                "dd-MM-yyyy HH:mm a",
                "dd-MM-yyyy",
                "dd/MM/yyyy HH:mm:ss a",
                "dd/MM/yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm a",
                "dd/MM/yyyy",
                "M/d/yyyy H:mm:ss a",
                "M/d/yyyy H:mm a",
                "d/M/yyyy H:mm:ss a",
                "d/M/yyyy H:mm a"
            )
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.US)
                    sdf.timeZone = baghdadTz
                    sdf.isLenient = false
                    val parsed = sdf.parse(sanitizedExpirationStr!!)
                    if (parsed != null) {
                        expireDate = parsed
                        
                        val cal = java.util.Calendar.getInstance(baghdadTz)
                        cal.time = parsed
                        parsedYear = cal.get(java.util.Calendar.YEAR)
                        parsedMonth = cal.get(java.util.Calendar.MONTH) + 1
                        parsedDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
                        parsedHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        parsedMinute = cal.get(java.util.Calendar.MINUTE)
                        parsedSecond = cal.get(java.util.Calendar.SECOND)
                        parsedHasTime = true
                        break
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    // Try next
                }
            }
        }
    }

    val currentDate = Date()
    
    // Parse activeDaysLeft as prime representation of subscription time
    var parsedDaysLeft: Pair<Long, Long>? = null
    if (!activeDaysLeftStr.isNullOrEmpty() && activeDaysLeftStr != "N/A" && activeDaysLeftStr != "none") {
        parsedDaysLeft = parseActiveDaysLeft(activeDaysLeftStr)
    }

    // Evaluate device default wall-clock comparison if device timezone is misconfigured
    var wallClockDiffMs: Long? = null
    if (parsedHasTime) {
        try {
            val deviceCal = java.util.Calendar.getInstance()
            val expireCalDeviceTz = java.util.Calendar.getInstance()
            expireCalDeviceTz.set(java.util.Calendar.YEAR, parsedYear)
            expireCalDeviceTz.set(java.util.Calendar.MONTH, parsedMonth - 1)
            expireCalDeviceTz.set(java.util.Calendar.DAY_OF_MONTH, parsedDay)
            expireCalDeviceTz.set(java.util.Calendar.HOUR_OF_DAY, parsedHour)
            expireCalDeviceTz.set(java.util.Calendar.MINUTE, parsedMinute)
            expireCalDeviceTz.set(java.util.Calendar.SECOND, parsedSecond)
            expireCalDeviceTz.set(java.util.Calendar.MILLISECOND, 0)
            wallClockDiffMs = expireCalDeviceTz.timeInMillis - deviceCal.timeInMillis
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;}
    }

    val cleanStatus = accountStatus?.trim()?.lowercase() ?: ""
    val isStatusActive = cleanStatus == "active" || cleanStatus == "expiringsoon" || cleanStatus == "expiring" || cleanStatus == "فعال" || cleanStatus == "نشط"

    // Determine final days and hours values
    var finalDays = 0L
    var finalHours = 0L
    var isExpired = false
    var isLessThanHour = false
    var remainingMinutes = 0L

    val (actDays, actHours) = parsedDaysLeft ?: Pair(0L, 0L)
    
    val absoluteDiffMs = if (expireDate != null) expireDate.time - currentDate.time else -1L

    if (expireDate != null && (absoluteDiffMs > 0 || (isStatusActive && (wallClockDiffMs ?: 0L) > 0) || isStatusActive)) {
        val effectiveDiffMs = if (absoluteDiffMs > 0) {
            absoluteDiffMs
        } else if (wallClockDiffMs != null && wallClockDiffMs > 0) {
            wallClockDiffMs
        } else {
            1800000L // 30 minutes grace fallback for active statuses
        }

        val totalHours = effectiveDiffMs / (1000 * 60 * 60)
        
        if (totalHours > 0) {
            finalDays = totalHours / 24
            finalHours = totalHours % 24
            
            // If API sent activeDaysLeft, it's often more trustworthy for the exact DAYS count (timezone independent)
            if (parsedDaysLeft != null && (actDays > 0 || actHours > 0)) {
                // We use actDays for days instead of our calculated finalDays if they are close (to fix timezone +1/-1 day issues)
                val absDiff = Math.abs(actDays - finalDays)
                if (absDiff <= 2L) {
                    finalDays = actDays
                    finalHours = actHours // Always override hours if we override days to prevent mixing Date math hours with API days
                }
            }
        } else {
            isLessThanHour = true
            remainingMinutes = effectiveDiffMs / (1000 * 60)
            if (remainingMinutes <= 0) {
                remainingMinutes = 30L // Grace fallback
            }
        }
    } else if (parsedDaysLeft != null && (actDays > 0 || actHours > 0)) {
        finalDays = actDays
        finalHours = actHours
    } else if (isStatusActive) {
        // Fallback for active status with unparsed or slightly past expiration
        isLessThanHour = true
        remainingMinutes = 30L
    } else {
        isExpired = true
    }

    if (isExpired) {
        return if (lang == "ar") "منتهي" else "Expired"
    }

    val isAr = lang == "ar"
    
    if (isLessThanHour) {
        val minutesStr = when {
            remainingMinutes <= 0L -> if (isAr) "أقل من دقيقة" else "Less than a minute"
            remainingMinutes == 1L -> if (isAr) "دقيقة واحدة" else "1 minute"
            remainingMinutes == 2L -> if (isAr) "دقيقتين" else "2 minutes"
            remainingMinutes in 3L..10L -> if (isAr) "$remainingMinutes دقائق" else "$remainingMinutes minutes"
            else -> if (isAr) "$remainingMinutes دقيقة" else "$remainingMinutes minutes"
        }
        return if (isAr) "متبقي $minutesStr" else "Remaining: $minutesStr"
    }
    
    val daysStr = when {
        finalDays == 0L -> ""
        finalDays == 1L -> if (isAr) "يوم واحد" else "1 day"
        finalDays == 2L -> if (isAr) "يومين" else "2 days"
        finalDays in 3L..10L -> if (isAr) "$finalDays أيام" else "$finalDays days"
        else -> if (isAr) "$finalDays يوم" else "$finalDays days"
    }
    
    val hoursStr = when {
        finalHours == 0L -> ""
        finalHours == 1L -> if (isAr) "ساعة" else "1 hour"
        finalHours == 2L -> if (isAr) "ساعتين" else "2 hours"
        finalHours in 3L..10L -> if (isAr) "$finalHours ساعات" else "$finalHours hours"
        else -> if (isAr) "$finalHours ساعة" else "$finalHours hours"
    }
    
    val durationPart = when {
        daysStr.isNotEmpty() && hoursStr.isNotEmpty() -> if (isAr) "$daysStr و $hoursStr" else "$daysStr and $hoursStr"
        daysStr.isNotEmpty() -> daysStr
        hoursStr.isNotEmpty() -> hoursStr
        else -> if (isAr) "أقل من ساعة" else "Less than 1 hour"
    }
    
    val result = if (isAr) "متبقي $durationPart" else "Remaining: $durationPart"
    return result
}

class LocalAccountMatcher(localAccounts: List<LocalAccount>) {
    private val usernameMap: Map<String, LocalAccount>
    private val nameMap: Map<String, LocalAccount>

    init {
        val byUsername = HashMap<String, LocalAccount>(localAccounts.size)
        val byName = HashMap<String, LocalAccount>(localAccounts.size)

        for (acc in localAccounts) {
            val username = acc.earthlinkUsername?.trim()?.lowercase()
            if (!username.isNullOrBlank()) {
                byUsername.putIfAbsent(username, acc)
            }
            val displayName = acc.displayName?.trim()?.lowercase()
            if (!displayName.isNullOrBlank()) {
                byName.putIfAbsent(displayName, acc)
            }
        }
        usernameMap = byUsername
        nameMap = byName
    }

    fun findMatching(user: UserListItem): LocalAccount? {
        val uId = user.userID.trim().lowercase()
        if (uId.isNotEmpty()) {
            val matchedByUsername = usernameMap[uId]
            if (matchedByUsername != null) return matchedByUsername
        }
        val dispName = user.displayName?.trim()?.lowercase()
        if (!dispName.isNullOrEmpty()) {
            val matchedByName = nameMap[dispName]
            if (matchedByName != null) return matchedByName
        }
        val custName = user.customerName?.trim()?.lowercase()
        if (!custName.isNullOrEmpty()) {
            val matchedByCustName = nameMap[custName]
            if (matchedByCustName != null) return matchedByCustName
        }
        return null
    }

    fun findMatchingByUsername(username: String): LocalAccount? {
        val uId = username.trim().lowercase()
        return if (uId.isNotEmpty()) usernameMap[uId] else null
    }
}

@Composable
fun ArabicSubscriberCard(
    user: UserListItem,
    localAccounts: List<LocalAccount> = emptyList(),
    localAccountMatcher: LocalAccountMatcher? = null,
    lang: String = "ar",
    onClick: () -> Unit
) {
    val matcher = localAccountMatcher ?: remember(localAccounts) { LocalAccountMatcher(localAccounts) }
    val matchingAccount = remember(user.userID, user.displayName, user.customerName, matcher) {
        matcher.findMatching(user)
    }
    
    val expirationStr = remember(user.expirationDate, user.manualExpirationDate, user.accountExpirationDate, matchingAccount?.expiresAt) {
        listOfNotNull(
            user.manualExpirationDate,
            user.accountExpirationDate,
            user.expirationDate,
            matchingAccount?.expiresAt
        ).firstOrNull { it.isNotBlank() && it != "N/A" }
    }
    
    val remainingTime = remember(expirationStr, user.activeDaysLeft, lang, user.accountStatus) { 
        getRemainingTime(expirationStr, user.activeDaysLeft?.toString(), lang, user.accountStatus) 
    }
    
    val statusClean = user.accountStatus?.trim()?.lowercase() ?: ""
    val isExpired = remainingTime.contains("منتهي") || remainingTime.contains("Expired")
    val isDeactivated = statusClean == "suspendedbyagent" || statusClean == "expired" || statusClean == "منتهي"
    val isActive = !isExpired && !isDeactivated
    
    val containerBg = MaterialTheme.colorScheme.surface
    val onlineStatusClean = user.onlineStatus?.trim()?.lowercase() ?: ""
    val isOnline = onlineStatusClean.contains("online") || onlineStatusClean == "online" || onlineStatusClean == "onlineno9net" || onlineStatusClean == "onlineno_net"
    val debtIqd = matchingAccount?.debtIqd ?: 0.0
    val advanceIqd = matchingAccount?.advanceIqd ?: 0.0
 
    val badgeText = if (isActive && !isExpired) {
        remainingTime
    } else {
        if (lang == "ar") "الاشتراك منتهي" else "Subscription Expired"
    }


    val displayName = when {
        !user.displayName.isNullOrBlank() -> user.displayName!!
        !user.customerName.isNullOrBlank() -> user.customerName!!
        else -> user.userID
    }
 
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onClick() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Right Side: Beautiful Profile Info & Debt Status
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Profile Avatar with Online Status Indicator
                    Box(modifier = Modifier.size(42.dp)) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    brush = if (isActive && !isExpired) {
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(Color(0xFF007AFF), Color(0xFF5856D6))
                                        )
                                    } else {
                                        androidx.compose.ui.graphics.Brush.linearGradient(
                                            listOf(Color(0xFF48484A), Color(0xFF3A3A3C))
                                        )
                                    },
                                    shape = androidx.compose.foundation.shape.CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            val firstLetter = displayName.trim().firstOrNull()?.toString()?.uppercase() ?: "?"
                            Text(
                                text = firstLetter,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        // Status Indicator Dot
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    color = if (isOnline) Color(0xFF30D158) else Color(0xFF8E8E93),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                                .border(1.5.dp, containerBg, androidx.compose.foundation.shape.CircleShape)
                                .align(Alignment.BottomEnd)
                        )
                    }

                    // Text Info (Name & Debt Status ONLY - Tower and ID are removed for clean visual flow)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (matchingAccount != null) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Verified subscriber",
                                    tint = Color(0xFF0A84FF),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }

                        // Apple Wallet Style Minimal debt info
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "الديون والاقساط:",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            val debtText = if (debtIqd > 0) {
                                com.example.core.ledger.MoneyParser.formatIqdForDisplay(debtIqd.toDouble()) + " د.ع"
                            } else if (advanceIqd > 0) {
                                com.example.core.ledger.MoneyParser.formatIqdForDisplay(advanceIqd.toDouble()) + " د.ع (مودع)"
                            } else {
                                "0 د.ع"
                            }
                            Text(
                                text = debtText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (debtIqd > 0) Color(0xFFFF453A) else if (advanceIqd > 0) Color(0xFF10B981) else Color(0xFF30D158)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Left Side: Super Sleek Compact Apple-style Subscription Status block (Removed empty spaces)
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isActive && !isExpired) Color(0xFF30D158).copy(alpha = 0.12f) else Color(0xFFFF453A).copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isActive && !isExpired) Color(0xFF30D158).copy(alpha = 0.3f) else Color(0xFFFF453A).copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isActive && !isExpired) "نشط" else "منتهي",
                            color = if (isActive && !isExpired) Color(0xFF30D158) else Color(0xFFFF453A),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = badgeText,
                        color = if (isActive && !isExpired) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f) else Color(0xFFFF453A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// Global visual style for status badges

@Composable
fun StatusBadge(status: String) {
    val isActive = status.contains("active", ignoreCase = true) || status.contains("online", ignoreCase = true)
    val color = if (isActive) Color(0xFF2E7D32) else Color(0xFFC62828)
    val bg = if (isActive) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)

    Surface(
        color = bg,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = status.uppercase(),
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// --- CONFIRMATION DIALOGUE CARD ---

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    needsPasswordField: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: (password: String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { com.example.core.security.PreferenceManager(context) }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = prefs.getLanguage())
    val isAr = currentLang == "ar"

    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }

    Dialog(onDismissRequest = {
        isSubmitting = false
        onCancel()
    }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Text(text = message, fontSize = 14.sp)

                if (needsPasswordField) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (isAr) "أدخل كلمة مرور الصندوق" else "Enter Deposit Password") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(imageVector = image, contentDescription = null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        isSubmitting = false
                        onCancel()
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (isAr) "إلغاء" else "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !isSubmitting,
                        onClick = { 
                            if (!isSubmitting) {
                                isSubmitting = true
                                onConfirm(password) 
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Confirm")
                    }
                }
            }
        }
    }
}

// --- LOGIN SCREEN ---

@Composable
fun IndicatorCard(
    title: String,
    value: String,
    subText: String? = null,
    icon: ImageVector,
    colorAccent: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (subText != null) {
                    Text(text = subText, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Icon(imageVector = icon, contentDescription = null, tint = colorAccent, modifier = Modifier.size(36.dp))
        }
    }
}

// --- DASHBOARD SCREEN ---

@Composable
fun PanelSwitchRow(
    label: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C242E), shape = RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Toggle Switch on left (representing RTL standard)
        ResellerSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )

        // Label and helper icon (right side aligned)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) Color(0xFF039BE5) else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// Custom simple toggle switch

@Composable
fun ResellerSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val thumbOffset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        label = "switch"
    )
    val bg = if (checked) Color(0xFF0288D1) else Color(0xFF37474F)
    
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(24.dp)
            .background(color = bg, shape = RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .background(color = Color.White, shape = androidx.compose.foundation.shape.CircleShape)
        )
    }
}


// --- DASHBOARD STATUS PANELS SCREEN (Image 3) ---

@Composable
fun StatGridCard(
    title: String,
    value: String,
    icon: ImageVector,
    iconBg: Color,
    iconColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222D)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(iconBg, shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Right
                )
            }

            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    }
}

// --- EARTHLINK USER SEARCH SCREEN ---

@Composable
fun DetailRow(
    icon: ImageVector,
    iconColor: Color,
    label: String,
    value: String,
    valueColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(iconColor.copy(alpha = 0.12f), shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                color = Color(0xFF8E8E93),
                fontWeight = FontWeight.Normal
            )
        }
        Text(
            text = value,
            fontSize = 13.sp,
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

// --- PASSWORD TOOLS OVERLAY ---

@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { com.example.core.security.PreferenceManager(context) }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = prefs.getLanguage())
    val isAr = currentLang == "ar"
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onCancel) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                Text(text = message, fontSize = 13.sp)

                content()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (isAr) "إلغاء" else "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}

// --- LOCAL ACCOUNT EDIT FORM OVERLAY ---

@Composable
fun EditLocalAccountDialog(
    account: LocalAccount,
    onDismiss: () -> Unit,
    onSave: (LocalAccount) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { com.example.core.security.PreferenceManager(context) }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = prefs.getLanguage())
    val isAr = currentLang == "ar"
    var dispName by rememberSaveable { mutableStateOf(account.displayName) }
    var userlink by rememberSaveable { mutableStateOf(account.earthlinkUsername ?: "") }
    var p1 by rememberSaveable { mutableStateOf(account.phone1 ?: "") }
    var p2 by rememberSaveable { mutableStateOf(account.phone2 ?: "") }
    var pkg by rememberSaveable { mutableStateOf(account.packageName ?: "") }

    var price by rememberSaveable { mutableStateOf(com.example.core.ledger.MoneyParser.formatIqdToUiString(account.currentPriceIqd)) }
    var debtLimit by rememberSaveable { mutableStateOf(com.example.core.ledger.MoneyParser.formatIqdToUiString(account.debtIqd)) }
    var advanceBalance by rememberSaveable { mutableStateOf(com.example.core.ledger.MoneyParser.formatIqdToUiString(account.advanceIqd)) }

    var tower by rememberSaveable { mutableStateOf(account.towerName ?: "") }
    var addr by rememberSaveable { mutableStateOf(account.address ?: "") }
    var memo by rememberSaveable { mutableStateOf(account.note ?: "") }

    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    if (showDeleteConfirm) {
        ConfirmationDialog(
            title = if (isAr) "تأكيد حذف المشترك نهائياً" else "Confirm Permanent Account Deletion",
            message = if (isAr)
                "هل أنت تأكد من رغبتك في حذف الحساب ${account.displayName}؟ سيتم حذف هذا المشترك وجميع سجلاته المالية نهائياً من هذا الجهاز ومن السيرفر (Firestore) ولا يمكن التراجع عن هذا الإجراء."
            else
                "Are you absolutely sure you want to permanently delete ${account.displayName}? This will permanently wipe this account and all its financial transaction history from both this device and the cloud database (Firestore). This action cannot be undone.",
            needsPasswordField = false,
            onCancel = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = "Edit Local File", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                HorizontalDivider()

                OutlinedTextField(value = dispName, onValueChange = { dispName = it }, label = { Text("Customer Display Name") }, singleLine = true)
                OutlinedTextField(value = userlink, onValueChange = { userlink = it }, label = { Text("Earthlink Username Mapping") }, singleLine = true)
                OutlinedTextField(value = p1, onValueChange = { p1 = it }, label = { Text("Primary Phone Number") }, singleLine = true)
                OutlinedTextField(value = p2, onValueChange = { p2 = it }, label = { Text("Backup Phone Number") }, singleLine = true)
                OutlinedTextField(value = pkg, onValueChange = { pkg = it }, label = { Text("Package Name Type") }, singleLine = true)

                OutlinedTextField(value = price, onValueChange = { price = it }, label = { Text("Current Quality Price (IQD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(value = debtLimit, onValueChange = { debtLimit = it }, label = { Text("Current Outstanding Debt (IQD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(value = advanceBalance, onValueChange = { advanceBalance = it }, label = { Text("Advance Prepaid balance (IQD)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)

                OutlinedTextField(value = tower, onValueChange = { tower = it }, label = { Text("Tower Node Base") }, singleLine = true)
                OutlinedTextField(value = addr, onValueChange = { addr = it }, label = { Text("Address details") }, singleLine = true)
                OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("Memo Node Note") })

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (isAr) "إلغاء" else "Cancel")
                        }
                        Button(
                            onClick = {
                                val editedObj = account.copy(
                                    displayName = dispName,
                                    earthlinkUsername = if (userlink.isEmpty()) null else userlink,
                                    phone1 = if (p1.isEmpty()) null else p1,
                                    phone2 = if (p2.isEmpty()) null else p2,
                                    packageName = if (pkg.isEmpty()) null else pkg,
                                    currentPriceIqd = if (price.isBlank()) account.currentPriceIqd else (com.example.core.ledger.MoneyParser.parseSubscriptionPriceIqd(price)?.toDouble() ?: account.currentPriceIqd),
                                    debtIqd = if (debtLimit.isBlank()) account.debtIqd else (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(debtLimit)?.toDouble() ?: account.debtIqd),
                                    loanIqd = if (debtLimit.isBlank()) account.debtIqd else (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(debtLimit)?.toDouble() ?: account.debtIqd),
                                    advanceIqd = if (advanceBalance.isBlank()) account.advanceIqd else (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(advanceBalance)?.toDouble() ?: account.advanceIqd),
                                    towerName = if (tower.isEmpty()) null else tower,
                                    address = if (addr.isEmpty()) null else addr,
                                    note = if (memo.isEmpty()) null else memo
                                )
                                onSave(editedObj)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Save File")
                        }
                    }
                }
            }
        }
    }
}

// --- IMPORT UTOWER JSON SCREEN ---
