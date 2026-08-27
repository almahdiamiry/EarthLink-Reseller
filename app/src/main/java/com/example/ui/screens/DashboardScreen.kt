package com.example.ui.screens
import com.example.EarthlinkApp
import com.example.domain.repository.SyncStatusState

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alamiry.earthlinkreseller.R
import com.example.core.model.*
import com.example.domain.repository.UtowerImportPreview
import com.example.ui.viewmodels.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

// Formatting helper for Money

private fun parseExpirationTimestamp(dateStr: String?): Long? {
    if (dateStr.isNullOrBlank() || dateStr.equals("n/a", ignoreCase = true) || dateStr.equals("none", ignoreCase = true)) {
        return null
    }

    var cleanStr = dateStr
        .replace("\u200E", "") // LRM
        .replace("\u200F", "") // RLM
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

    // Convert Arabic/Persian numerals
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
        if (e is kotlinx.coroutines.CancellationException) throw e
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
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    return null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    lang: String = "ar",
    onNavigateToSearch: () -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onUserClick: (com.example.core.model.UserListItem) -> Unit,
    onPlusClick: () -> Unit,
    onEClick: () -> Unit
) {
    val prefs = remember { viewModel.prefs }
    val isDemoOn by prefs.demoModeFlow.collectAsStateWithLifecycle()

    LaunchedEffect(isDemoOn) {
        viewModel.loadDashboardData()
    }

    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val prepaidNeeded by viewModel.prepaidNeeded.collectAsStateWithLifecycle()
    val testCount by viewModel.testCount.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isCredentialsEmpty by viewModel.isCredentialsEmpty.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    
    val context = LocalContext.current.applicationContext as EarthlinkApp
    val syncState by context.syncRepository.syncState.collectAsStateWithLifecycle(SyncStatusState.IDLE)

    val subscribers by viewModel.subscribersList.collectAsStateWithLifecycle()

    val forecastAfter = balance - prepaidNeeded
    val localAccounts by viewModel.localAccounts.collectAsStateWithLifecycle(emptyList())
    val localAccountMatcher = remember(localAccounts) { LocalAccountMatcher(localAccounts) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    // Sorting & Filtering State
    var showFinancialSummarySheet by rememberSaveable { mutableStateOf(false) }
    val totalDebt = remember(localAccounts) {
        localAccounts.filter { !it.isHistoryOnlySubscriber }.sumOf { it.debtIqd }
    }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var pendingSortOpen by rememberSaveable { mutableStateOf(false) }
    val selectedSort by viewModel.selectedSort.collectAsStateWithLifecycle()
    var selectedStatusFilter by rememberSaveable { mutableStateOf("الكل") } // Options: "الكل", "فعال", "قريب من الانتهاء", "منتهي"

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val isImeVisible = WindowInsets.isImeVisible
    val imeInsets = WindowInsets.ime

    LaunchedEffect(pendingSortOpen, isImeVisible) {
        if (pendingSortOpen) {
            if (!isImeVisible && imeInsets.getBottom(density) == 0) {
                isSearchActive = false
                showSortSheet = true
                pendingSortOpen = false
            } else {
                snapshotFlow { imeInsets.getBottom(density) }
                    .first { it == 0 }
                isSearchActive = false
                showSortSheet = true
                pendingSortOpen = false
            }
        }
    }

    // 1. FILTERING & SORTING VIA DERIVED STATE: Merge Live ISP Subscribers + Local/Legacy Accounts
    val filteredList by remember(subscribers, localAccounts, localAccountMatcher, selectedStatusFilter, selectedSort, isSearchActive, searchQuery) {
        derivedStateOf {
            val mergedList = ArrayList<com.example.core.model.UserListItem>(subscribers.size + localAccounts.size)
            val seenUsernames = HashSet<String>()
            val seenAccountIds = HashSet<String>()

            // 1. Add live ISP / demo subscribers
            for (sub in subscribers) {
                val matchingAccount = localAccountMatcher.findMatchingByUsername(sub.userID) ?: localAccountMatcher.findMatching(sub)
                if (matchingAccount?.isHistoryOnlySubscriber == true) {
                    continue
                }
                mergedList.add(sub)
                sub.userID?.let { if (it.isNotBlank()) seenUsernames.add(it.lowercase(Locale.US)) }
                matchingAccount?.let { acc ->
                    seenAccountIds.add(acc.id)
                    acc.earthlinkUsername?.let { if (it.isNotBlank()) seenUsernames.add(it.lowercase(Locale.US)) }
                }
            }

            // 2. Append all Local / Legacy Accounts (uTower + local) not represented in live subscribers
            for (acc in localAccounts) {
                if (acc.isHistoryOnlySubscriber) continue
                if (seenAccountIds.contains(acc.id)) continue
                val accUserLower = acc.earthlinkUsername?.lowercase(Locale.US)
                if (accUserLower != null && accUserLower.isNotBlank() && seenUsernames.contains(accUserLower)) {
                    continue
                }

                val item = com.example.core.model.UserListItem(
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

            val baseList = mergedList

            val now = System.currentTimeMillis()

            var list = baseList.filter { user ->
                val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                if (matchingAccount?.isHistoryOnlySubscriber == true) {
                    return@filter false
                }

                val expStr = listOfNotNull(
                    user.manualExpirationDate,
                    user.accountExpirationDate,
                    user.expirationDate,
                    matchingAccount?.expiresAt
                ).firstOrNull { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) && !it.equals("none", ignoreCase = true) }
                val expTimestamp = parseExpirationTimestamp(expStr)
                val statusClean = user.accountStatus?.trim()?.lowercase(Locale.US) ?: ""

                val isExplicitExpired = statusClean == "expired" || statusClean == "منتهي" || statusClean == "suspendedbyagent"
                val isDateExpired = expTimestamp != null && expTimestamp < now
                val isExpired = isExplicitExpired || isDateExpired

                when (selectedStatusFilter) {
                    "الكل" -> true
                    "فعال" -> !isExpired
                    "منتهي" -> isExpired
                    "قريب من الانتهاء" -> {
                        if (expTimestamp != null) {
                            val diffMs = expTimestamp - now
                            diffMs in 0..(7L * 24 * 60 * 60 * 1000L)
                        } else {
                            val daysLeft = user.activeDaysLeft?.toString()?.toDoubleOrNull()
                            if (daysLeft != null) {
                                daysLeft in 0.0..7.0
                            } else false
                        }
                    }
                    else -> true
                }
            }

            list = when (selectedSort) {
                "الاسم" -> list.sortedWith(
                    compareBy<com.example.core.model.UserListItem> { user ->
                        val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                        val name = user.displayName?.takeIf { it.isNotBlank() }
                            ?: matchingAccount?.displayName?.takeIf { it.isNotBlank() }
                            ?: user.customerName?.takeIf { it.isNotBlank() }
                            ?: user.userID
                        name.trim().lowercase(Locale.getDefault())
                    }.thenBy { it.userID }
                )
                "الدين", "دين المشترك" -> list.sortedWith(
                    compareByDescending<com.example.core.model.UserListItem> { user ->
                        val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                        matchingAccount?.debtIqd ?: 0.0
                    }.thenBy { user ->
                        val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                        user.displayName?.takeIf { it.isNotBlank() }
                            ?: matchingAccount?.displayName?.takeIf { it.isNotBlank() }
                            ?: user.customerName?.takeIf { it.isNotBlank() }
                            ?: user.userID
                    }.thenBy { it.userID }
                )
                "نهاية الاشتراك" -> list.sortedWith(
                    compareBy<com.example.core.model.UserListItem> { user ->
                        val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                        val expStr = listOfNotNull(
                            user.manualExpirationDate,
                            user.accountExpirationDate,
                            user.expirationDate,
                            matchingAccount?.expiresAt
                        ).firstOrNull { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) && !it.equals("none", ignoreCase = true) }
                        parseExpirationTimestamp(expStr) ?: Long.MAX_VALUE
                    }.thenBy { user ->
                        val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                        user.displayName?.takeIf { it.isNotBlank() }
                            ?: matchingAccount?.displayName?.takeIf { it.isNotBlank() }
                            ?: user.customerName?.takeIf { it.isNotBlank() }
                            ?: user.userID
                    }.thenBy { it.userID }
                )
                else -> list.sortedWith(
                    compareBy<com.example.core.model.UserListItem> { user ->
                        val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                        val expStr = listOfNotNull(
                            user.manualExpirationDate,
                            user.accountExpirationDate,
                            user.expirationDate,
                            matchingAccount?.expiresAt
                        ).firstOrNull { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) && !it.equals("none", ignoreCase = true) }
                        parseExpirationTimestamp(expStr) ?: Long.MAX_VALUE
                    }.thenBy { it.userID }
                )
            }

            if (isSearchActive && searchQuery.isNotEmpty()) {
                val q = searchQuery.trim().lowercase(Locale.getDefault())
                list = list.filter { user ->
                    user.userIndex.toString().contains(q) ||
                    user.userID.lowercase(Locale.getDefault()).contains(q) ||
                    user.customerName?.lowercase(Locale.getDefault())?.contains(q) == true ||
                    user.displayName?.lowercase(Locale.getDefault())?.contains(q) == true ||
                    user.mobileNumber?.lowercase(Locale.getDefault())?.contains(q) == true
                }
            }

            list
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090D12))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Compact Header Row: [ App Logo ] <---> [ Balance Capsule + Filter Action ]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Clickable App Logo (Pure Transparent Brand Logo - Opens status portal)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(38.dp)
                                .clickable(
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onEClick() }
                                )
                        ) {
                            Image(
                                painter = androidx.compose.ui.res.painterResource(id = R.drawable.app_logo),
                                contentDescription = "EarthLink Logo",
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.height(38.dp)
                            )
                        }

                        // Actions & Financial Balance Group
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Remaining Balance Capsule Promoted to Header (Clickable for Financial Breakdown)
                            Surface(
                                color = Color(0xFF141922),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                modifier = Modifier
                                    .height(38.dp)
                                    .clickable {
                                        val hadFocusOrKeyboard = isSearchActive || isImeVisible || imeInsets.getBottom(density) > 0
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        showFinancialSummarySheet = true
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalanceWallet,
                                        contentDescription = "Balance summary",
                                        tint = Color(0xFF0A84FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = formatIqd(balance),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF0A84FF)
                                    )
                                }
                            }

                            // Filter / Sorting Action Button
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        Color(0xFF141922),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        1.dp,
                                        Color.White.copy(alpha = 0.08f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        val hadFocusOrKeyboard = isSearchActive || isImeVisible || imeInsets.getBottom(density) > 0
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        if (hadFocusOrKeyboard) {
                                            pendingSortOpen = true
                                        } else {
                                            showSortSheet = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = stringResource(id = R.string.action_sort_filter),
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Network/Offline Alert Banner if applicable (Apple Inset Card)
                    if (syncState == SyncStatusState.OFFLINE || syncState == SyncStatusState.ERROR) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF141922),
                            border = BorderStroke(1.dp, Color(0xFFFF9F0A).copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFFFF9F0A).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudOff,
                                        contentDescription = "Offline",
                                        tint = Color(0xFFFF9F0A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = if (lang == "ar") "أنت تعمل الآن في وضع عدم الاتصال (بيانات مخزنة مؤقتاً)." else "You are operating offline (cached data).",
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    
                    if (isCredentialsEmpty && !isDemoOn) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF141922),
                            border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(Color(0xFFFF453A).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                                            contentDescription = "Warning",
                                            tint = Color(0xFFFF453A),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Text(
                                        text = if (lang == "ar") "معلومات حساب ISP غير مكتملة" else "ISP Credentials Missing",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = if (lang == "ar") {
                                        "الرجاء إضافة اسم المستخدم وكلمة مرور ISP في الإعدادات لعرض البيانات الحية والاتصال بالبوابة."
                                    } else {
                                        "Please enter your ISP credentials in Settings to connect and load live subscriber data."
                                    },
                                    fontSize = 12.sp,
                                    color = Color(0xFF8E8E93)
                                )
                                Button(
                                    onClick = { onNavigateToSettings() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text(
                                        text = if (lang == "ar") "فتح الإعدادات" else "Open Settings",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    if (error != null && !isDemoOn) {
                        ErrorStateCard(
                            message = error ?: "Unknown connection anomaly",
                            title = if (lang == "ar") "خطأ في الاتصال" else "Connection Error"
                        )
                    }
                }
            }

            if (filteredList.isEmpty()) {
                item {
                    EmptyStateView(message = if (lang == "ar") "لا توجد نتائج مطابقة للتصفية الحالية." else "No results match current filter.")
                }
            } else {
                items(filteredList) { user ->
                    ArabicSubscriberCard(
                        user = user,
                        localAccounts = localAccounts,
                        localAccountMatcher = localAccountMatcher,
                        lang = lang,
                        onClick = { onUserClick(user) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }

        // BackHandler: Close search if active
        BackHandler(enabled = isSearchActive) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            isSearchActive = false
            searchQuery = ""
        }

        // Floating Capsule Search Pill ("بــحــث") or Expanded Active Search Bar
        AnimatedContent(
            targetState = isSearchActive,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180))) togetherWith
                fadeOut(animationSpec = tween(140))
            },
            label = "SearchTransition",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) { active ->
            if (active) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        color = Color(0xFF141922),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF0A84FF))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFF0A84FF),
                                modifier = Modifier.size(18.dp)
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = if (lang == "ar") "بحث بالاسم، اليوزر، أو الهاتف..." else "Search name, user, phone...",
                                        fontSize = 13.sp,
                                        color = Color(0xFF8E8E93),
                                        maxLines = 1
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        imeAction = ImeAction.Search,
                                        keyboardType = KeyboardType.Text
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onSearch = {
                                            keyboardController?.hide()
                                        }
                                    ),
                                    textStyle = TextStyle(
                                        fontSize = 13.5.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF0A84FF)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                )
                            }

                            if (searchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .clickable { searchQuery = "" },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cancel,
                                        contentDescription = "Clear",
                                        tint = Color(0xFF8E8E93),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            isSearchActive = false
                            searchQuery = ""
                        },
                        shape = CircleShape,
                        color = Color(0xFF141922),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        onClick = { isSearchActive = true },
                        color = Color(0xFF141922),
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        modifier = Modifier
                            .fillMaxWidth(0.38f)
                            .height(44.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFF0A84FF),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "بــحــث",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 2. MATERIAL 3 MODAL BOTTOM SHEET FOR SORT & FILTER
        if (showSortSheet) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SortAndFilterBottomSheet(
                    selectedSort = selectedSort,
                    onSortSelected = { viewModel.setDashboardSortOption(it) },
                    selectedStatusFilter = selectedStatusFilter,
                    onStatusFilterSelected = { selectedStatusFilter = it },
                    onResetDefaults = {
                        viewModel.setDashboardSortOption("الاسم")
                        selectedStatusFilter = "الكل"
                    },
                    onDismissRequest = { showSortSheet = false }
                )
            }
        }

        // 3. MATERIAL 3 MODAL BOTTOM SHEET FOR FINANCIAL SUMMARY
        if (showFinancialSummarySheet) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                FinancialSummaryBottomSheet(
                    balance = balance,
                    totalDebt = totalDebt,
                    prepaidNeeded = prepaidNeeded,
                    forecastAfter = forecastAfter,
                    testCount = testCount,
                    isLoading = isLoading,
                    onRefresh = { viewModel.loadDashboardData() },
                    onDismissRequest = { showFinancialSummarySheet = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinancialSummaryBottomSheet(
    balance: Double,
    totalDebt: Double,
    prepaidNeeded: Double,
    forecastAfter: Double,
    testCount: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val isSufficient = forecastAfter >= 0.0
    val deficitAmount = if (forecastAfter < 0) kotlin.math.abs(forecastAfter) else 0.0

    ModalBottomSheet(
        onDismissRequest = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onDismissRequest()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF141922),
        contentColor = Color.White,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                color = Color.White.copy(alpha = 0.25f)
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // 1. Top Bar: Title & Subtitle + Refresh Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "تحليل الصندوق وتوقعات 7 أيام",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "نظرة فورية على كفاية الرصيد للتجديدات القادمة",
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }

                    // Refresh Button (Apple Glass style)
                    Surface(
                        onClick = onRefresh,
                        shape = CircleShape,
                        color = Color(0xFF1C2430),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFF0A84FF),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "تحديث",
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }

                // 2. HERO CARD: Smart Decision & Forecast (Apple Inset Card)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF0E131B),
                    border = BorderStroke(
                        1.dp,
                        if (isSufficient) Color(0xFF30D158).copy(alpha = 0.25f) else Color(0xFFFF453A).copy(alpha = 0.25f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        // Top Header inside Hero: Title & Status Pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "الهامش المتوقع بعد 7 أيام",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.8f)
                            )

                            // Status Pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSufficient) Color(0xFF30D158).copy(alpha = 0.12f) else Color(0xFFFF453A).copy(alpha = 0.12f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSufficient) Color(0xFF30D158).copy(alpha = 0.3f) else Color(0xFFFF453A).copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                if (isSufficient) Color(0xFF30D158) else Color(0xFFFF453A),
                                                shape = CircleShape
                                            )
                                    )
                                    Text(
                                        text = if (isSufficient) "الرصيد كافٍ ومغطى" else "تحتاج تعبئة رصيد",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSufficient) Color(0xFF30D158) else Color(0xFFFF453A)
                                    )
                                }
                            }
                        }

                        // Big Net Figure
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = if (isSufficient) "+${formatIqd(forecastAfter)}" else "-${formatIqd(deficitAmount)}",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSufficient) Color(0xFF30D158) else Color(0xFFFF453A),
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = if (isSufficient) "فائض نقدي متاح بعد تغطية تجديدات الأسبوع" else "المبلغ المطلوب إيداعه لتغطية تجديدات الأسبوع",
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }

                        // Subtle Divider
                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.05f),
                            thickness = 1.dp
                        )

                        // Breakdown Details (Balance vs 7-Day Required)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Current Balance
                            Column(
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF0A84FF), shape = CircleShape)
                                    )
                                    Text(
                                        text = "رصيد الصندوق الحالي",
                                        fontSize = 11.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }
                                Text(
                                    text = formatIqd(balance),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0A84FF)
                                )
                            }

                            // 7-Day Needed
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF94A3B8), shape = CircleShape)
                                    )
                                    Text(
                                        text = "المطلوب للتجديد (7 أيام)",
                                        fontSize = 11.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }
                                Text(
                                    text = formatIqd(prepaidNeeded),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }

                // 3. SECONDARY CARDS (Trial Users & Total Debts)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Card 1: Active 24h Trial Users
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0E131B),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "مستخدمي التجربة",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.75f)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(Color(0xFFBF5AF2).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Bolt,
                                        contentDescription = null,
                                        tint = Color(0xFFBF5AF2),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Text(
                                text = "$testCount مستخدم",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Text(
                                text = "24 ساعة بدون خصم",
                                fontSize = 10.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }
                    }

                    // Card 2: Total Subscriber Debts
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0E131B),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "إجمالي الديون",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White.copy(alpha = 0.75f)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(Color(0xFFFF9F0A).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ReceiptLong,
                                        contentDescription = null,
                                        tint = Color(0xFFFF9F0A),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Text(
                                text = formatIqd(totalDebt),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )

                            Text(
                                text = "مستحقات بذمة المشتركين",
                                fontSize = 10.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortAndFilterBottomSheet(
    selectedSort: String,
    onSortSelected: (String) -> Unit,
    selectedStatusFilter: String,
    onStatusFilterSelected: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    ModalBottomSheet(
        onDismissRequest = {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
            onDismissRequest()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF141922),
        contentColor = Color.White,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                color = Color.White.copy(alpha = 0.25f)
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Top Header Bar: Title & Reset Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "الفرز والتصفية",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = Color.White
                    )
                    Text(
                        text = "تخصيص ترتيب وحالات الحسابات",
                        fontSize = 11.sp,
                        color = Color(0xFF8E8E93)
                    )
                }

                val isCustomized = selectedSort != "الاسم" || selectedStatusFilter != "الكل"
                if (isCustomized) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1C2430),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.clickable { onResetDefaults() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "إعادة ضبط",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "إعادة ضبط",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // 2. Card 1: معيار الترتيب (Apple Inset Grouped Style)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0E131B),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF0A84FF).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = null,
                                tint = Color(0xFF0A84FF),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "ترتيب القائمة حسب",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // 3 Sort Option Tiles
                    val sortItems = listOf(
                        Triple("الاسم", "الاسم أبجدياً", "ترتيب تصاعدي من أ إلى ي"),
                        Triple("نهاية الاشتراك", "تاريخ نهاية الاشتراك", "الأقرب انتهاءً أولاً لمتابعة التجديدات"),
                        Triple("الدين", "حجم الدين والمستحقات", "المشتركون أصحاب الديون الأعلى أولاً")
                    )

                    sortItems.forEach { (key, title, subtitle) ->
                        val isSelected = selectedSort == key || (key == "الدين" && selectedSort == "دين المشترك")

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) Color(0xFF0A84FF).copy(alpha = 0.12f) else Color(0xFF141922),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF0A84FF).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.04f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSortSelected(key) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .background(
                                                when (key) {
                                                    "نهاية الاشتراك" -> Color(0xFF64D2FF).copy(alpha = 0.15f)
                                                    "الدين" -> Color(0xFFFF9F0A).copy(alpha = 0.15f)
                                                    else -> Color(0xFF0A84FF).copy(alpha = 0.15f)
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when (key) {
                                                "نهاية الاشتراك" -> Icons.Default.Schedule
                                                "الدين" -> Icons.Default.ReceiptLong
                                                else -> Icons.Default.Person
                                            },
                                            contentDescription = null,
                                            tint = when (key) {
                                                "نهاية الاشتراك" -> Color(0xFF64D2FF)
                                                "الدين" -> Color(0xFFFF9F0A)
                                                else -> Color(0xFF0A84FF)
                                            },
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            text = title,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = subtitle,
                                            fontSize = 10.sp,
                                            color = Color(0xFF8E8E93)
                                        )
                                    }
                                }

                                // Selection Indicator Circle
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .border(
                                            1.5.dp,
                                            if (isSelected) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.2f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .background(Color(0xFF0A84FF), CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Card 2: تصفية الحالة (Apple Inset Grouped Style)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0E131B),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color(0xFF30D158).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "تصفية حسب حالة المشترك",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    // 4 Status Pills
                    val statusFilters = listOf(
                        Pair("الكل", Color(0xFF8E8E93)),
                        Pair("فعال", Color(0xFF30D158)),
                        Pair("قريب من الانتهاء", Color(0xFFFF9F0A)),
                        Pair("منتهي", Color(0xFFFF453A))
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            statusFilters.take(2).forEach { (status, dotColor) ->
                                StatusFilterPill(
                                    title = status,
                                    dotColor = dotColor,
                                    isSelected = selectedStatusFilter == status,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onStatusFilterSelected(status) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            statusFilters.drop(2).forEach { (status, dotColor) ->
                                StatusFilterPill(
                                    title = status,
                                    dotColor = dotColor,
                                    isSelected = selectedStatusFilter == status,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onStatusFilterSelected(status) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusFilterPill(
    title: String,
    dotColor: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) dotColor.copy(alpha = 0.12f) else Color(0xFF141922),
        border = BorderStroke(
            1.dp,
            if (isSelected) dotColor.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.05f)
        ),
        modifier = modifier
            .height(40.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFF8E8E93),
                maxLines = 1
            )
        }
    }
}

