package com.example.ui.screens
import com.example.EarthlinkApp
import com.example.domain.repository.SyncStatusState

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

    // Sorting & Filtering State
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    var selectedSort by rememberSaveable { mutableStateOf("نهاية الاشتراك") } // Options: "نهاية الاشتراك", "الاسم", "الدين"
    var selectedStatusFilter by rememberSaveable { mutableStateOf("الكل") } // Options: "الكل", "فعال", "قريب من الانتهاء", "منتهي"

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // 1. FILTERING & SORTING VIA DERIVED STATE
    val filteredList by remember(subscribers, localAccounts, localAccountMatcher, selectedStatusFilter, selectedSort, isSearchActive, searchQuery) {
        derivedStateOf {
            val baseList = if (subscribers.isEmpty() && localAccounts.isNotEmpty()) {
                localAccounts.map { acc ->
                    com.example.core.model.UserListItem(
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
                }
            } else {
                subscribers
            }

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
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Compact Earthlink Header Row: [ E Logo ] --- [ Balance Capsule ] --- [ Filter Action ]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Clickable E Logo Box (Opens status portal)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    color = Color(0xFF0288D1),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { onEClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "E",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Remaining Balance Capsule Promoted to Header
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = Color(0xFF039BE5),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = formatIqd(balance),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    color = Color(0xFF039BE5)
                                )
                            }
                        }

                        // Filter / Sorting Action Button
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.CircleShape)
                                .clickable { showSortSheet = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = stringResource(id = R.string.action_sort_filter),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Network/Offline Alert Banner if applicable
                    if (syncState == SyncStatusState.OFFLINE || syncState == SyncStatusState.ERROR) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                            border = BorderStroke(1.dp, Color(0xFFFF9800)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = "Offline",
                                    tint = Color(0xFFF57C00),
                                    modifier = Modifier.size(24.dp)
                                )
                                Text(
                                    text = if (lang == "ar") "أنت تعمل الآن في وضع عدم الاتصال (بيانات مخزنة مؤقتاً)." else "You are operating offline (cached data).",
                                    color = Color(0xFFE65100),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    
                    if (isCredentialsEmpty && !isDemoOn) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            border = BorderStroke(1.dp, Color(0xFFEF5350)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = Color(0xFFC62828),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = if (lang == "ar") "تنبيه: معلومات الحساب فارغة!" else "Warning: Account Credentials Empty!",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = Color(0xFFC62828)
                                    )
                                }
                                Text(
                                    text = if (lang == "ar") {
                                        "الرجاء إضافة معلومات مسؤول ISP (اسم المستخدم وكلمة المرور) في الإعدادات للاتصال بـ Earthlink وعرض الحسابات والبيانات الحية."
                                    } else {
                                        "Please add your Earthlink ISP Admin credentials (username and password) in Settings to load live accounts and connect."
                                    },
                                    fontSize = 13.sp,
                                    color = Color(0xFF333333),
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { onNavigateToSettings() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.height(38.dp)
                                ) {
                                    Text(
                                        text = if (lang == "ar") "الذهاب إلى الإعدادات الآن" else "Go to Settings Now",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
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
            isSearchActive = false
            searchQuery = ""
        }

        // Floating Capsule Search Pill ("بــحــث") or Expanded Active Search Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = if (isSearchActive) 16.dp else 24.dp)
        ) {
            if (isSearchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = if (lang == "ar") "ابحث عن الاسم، اليوزر، أو الهاتف..." else "Search name, user, phone...",
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                        },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0288D1),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = Color(0xFF1E2830),
                            unfocusedContainerColor = Color(0xFF1E2830),
                            focusedLabelColor = Color(0xFF0288D1),
                            unfocusedLabelColor = Color.Gray,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 48.dp)
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(fontSize = 13.sp)
                    )

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color.White.copy(alpha = 0.08f), shape = androidx.compose.foundation.shape.CircleShape)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .clickable {
                                isSearchActive = false
                                searchQuery = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .align(Alignment.Center)
                ) {
                    Button(
                        onClick = { isSearchActive = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2830)),
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "بــحــث",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 2. DIM BACKGROUND AND SELECTABLE BOTTOM SORT SLIDE SHEET
        if (showSortSheet) {
            BackHandler(enabled = true) {
                showSortSheet = false
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showSortSheet = false }
            ) {
                // Actual Sheet Container
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}, // prevent dismiss tap leak
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF12181F))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.End // RTL Arabic Support
                    ) {
                        // Drag Handle
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .background(Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(2.dp))
                                .align(Alignment.CenterHorizontally)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // Head Title - "فرز وتصفية"
                        Text(
                            text = "فرز وتصفية",
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            color = Color.White,
                            textAlign = TextAlign.Right
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Section 1 - "ترتيب حسب"
                        Text(
                            text = "ترتيب حسب",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Right
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val sortOptions = listOf("نهاية الاشتراك", "الاسم", "الدين")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            sortOptions.forEach { item ->
                                val selected = selectedSort == item || (item == "الدين" && selectedSort == "دين المشترك")
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (selected) Color(0xFF0288D1) else Color(0xFF1C242E),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .clickable { selectedSort = item }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = item,
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Section 2 - "حالة الاشتراك"
                        Text(
                            text = "حالة الاشتراك",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Right
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        val statusOptions = listOf("الكل", "فعال", "قريب من الانتهاء", "منتهي")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            statusOptions.forEach { item ->
                                val selected = selectedStatusFilter == item
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (selected) Color(0xFF0288D1) else Color(0xFF1C242E),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .clickable { selectedStatusFilter = item }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = item,
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

// Helper custom switch item row for the bottom sorting dialog
