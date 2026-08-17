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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

// Formatting helper for Money

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
    var selectedSort by rememberSaveable { mutableStateOf("نهاية الاشتراك") } // Default selectable option in image 2
    var selectedPanel by rememberSaveable { mutableStateOf("الكل") } // Panels: "الكل", "EarthLink admin@sacx", "محذوفة"
    var selectedStatusFilter by rememberSaveable { mutableStateOf("الكل") } // Statuses: "الكل", "فعال", "قريب من الانتهاء", "منتهي"

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // 1. FILTERING & SORTING VIA DERIVED STATE
    val filteredList by remember(subscribers, localAccounts, localAccountMatcher, selectedPanel, selectedStatusFilter, selectedSort, isSearchActive, searchQuery) {
        derivedStateOf {
            val baseList = if (subscribers.isEmpty() && localAccounts.isNotEmpty()) {
                localAccounts.map { acc ->
                    com.example.core.model.UserListItem(
                        userIndexLower = acc.id.hashCode(),
                        userIDLower = acc.earthlinkUsername ?: "local_${acc.id}",
                        customerNameLower = acc.displayName.ifBlank { acc.earthlinkUsername ?: "Unknown" },
                        mobileNumberLower = acc.phone1,
                        accountStatusLower = if (acc.expiresAt != null) {
                            try {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                val expireDate = sdf.parse(acc.expiresAt)
                                if (expireDate != null && expireDate.before(java.util.Date())) "Expired" else "Active"
                            } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "Active" }
                        } else "Active",
                        expirationDateLower = acc.expiresAt ?: "",
                        displayNameLower = acc.displayName,
                        accountNameLower = acc.packageName
                    )
                }
            } else {
                subscribers
            }

            var list = baseList.filter { user ->
                val matchingAccount = localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user)
                val panelMatch = when (selectedPanel) {
                    "الكل" -> true
                    "EarthLink admin@sacx" -> matchingAccount?.towerName?.equals("sacx", ignoreCase = true) == true
                    "محذوفة" -> false
                    else -> true
                }
                
                val statusMatch = when (selectedStatusFilter) {
                    "الكل" -> true
                    "فعال" -> user.accountStatus?.equals("Active", ignoreCase = true) == true
                    "منتهي" -> user.accountStatus?.equals("Expired", ignoreCase = true) == true
                    "قريب من الانتهاء" -> {
                        val dateStr = user.expirationDate ?: ""
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            val expireDate = sdf.parse(dateStr)
                            if (expireDate != null) {
                                val diffMs = expireDate.time - java.util.Date().time
                                val diffDays = diffMs / (1000 * 60 * 60 * 24)
                                diffDays in 0..7
                            } else false
                        } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; false }
                    }
                    else -> true
                }
                
                panelMatch && statusMatch
            }

            list = when (selectedSort) {
                "الاسم" -> list.sortedBy { it.displayName ?: it.customerName ?: it.userID }
                "نهاية الاشتراك" -> list.sortedBy { it.expirationDate ?: "" }
                "بدء الاشتراك" -> list.sortedByDescending { it.userID }
                "دين المشترك" -> list.sortedByDescending { user ->
                    (localAccountMatcher.findMatchingByUsername(user.userID) ?: localAccountMatcher.findMatching(user))?.debtIqd ?: 0.0
                }
                "سعر الاشتراك" -> list.sortedBy { it.userIndex }
                "رقم الهاتف" -> list.sortedBy { it.mobileNumber ?: "" }
                else -> list
            }

            if (isSearchActive && searchQuery.isNotEmpty()) {
                val q = searchQuery.trim().lowercase()
                list = list.filter { user ->
                    user.userIndex.toString().contains(q) ||
                    user.userID.lowercase().contains(q) ||
                    user.customerName?.lowercase()?.contains(q) == true ||
                    user.displayName?.lowercase()?.contains(q) == true ||
                    user.mobileNumber?.lowercase()?.contains(q) == true
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // High-Fidelity Custom Earthlink Header Row with Clickable E Logo status portal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Clickable E Logo Box
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(
                                color = Color(0xFF0288D1),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onEClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "E",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Column {
                        Text(
                            text = stringResource(id = R.string.header_earthlink_mobile),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(id = R.string.header_reseller_manager),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                }

                // Action controls: Filter/sorting & Search & Plus button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Refined Search Pill Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.CircleShape)
                            .clickable { isSearchActive = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(id = R.string.action_search),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Refined Sorting Pill Button
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

                    // Refined Plus Button Pill
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(38.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(19.dp))
                            .clickable { onPlusClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(id = R.string.action_add_user),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Sleek Operator Deposit & Health Bar
            var showFinancials by rememberSaveable { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFinancials = !showFinancials },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountBalanceWallet,
                                contentDescription = null,
                                tint = Color(0xFF039BE5)
                            )
                            Text(
                                text = if (lang == "ar") "الوكيل والرصيد والصحة" else "Operator Deposit & Health",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = formatIqd(balance),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = Color(0xFF039BE5)
                            )
                            Icon(
                                imageVector = if (showFinancials) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Expand financials",
                                tint = Color.White
                            )
                        }
                    }

                    if (showFinancials) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val activeAccounts = localAccounts.filter { !it.isLegacy }
                            val totalLocalDebt = activeAccounts.sumOf { it.debtIqd }
                            val totalLocalAdvance = activeAccounts.sumOf { it.advanceIqd }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = if (lang == "ar") "إجمالي الديون (محلي)" else "Total Local Debt", fontSize = 12.sp, color = Color.Gray)
                                Text(text = formatIqd(totalLocalDebt), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFEF5350))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = if (lang == "ar") "إجمالي المودع (محلي)" else "Total Local Deposits", fontSize = 12.sp, color = Color.Gray)
                                Text(text = formatIqd(totalLocalAdvance), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF66BB6A))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = if (lang == "ar") "الرصيد المطلوب (7 أيام)" else "Prepaid Needed (7 days)", fontSize = 12.sp, color = Color.Gray)
                                Text(text = formatIqd(prepaidNeeded), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFEF5350))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = if (lang == "ar") "الهامش المتوقع" else "Expected Margin", fontSize = 12.sp, color = Color.Gray)
                                val fColor = if (forecastAfter >= 0) Color(0xFF66BB6A) else Color(0xFFEF5350)
                                Text(text = formatIqd(forecastAfter), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fColor)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = if (lang == "ar") "مستخدمي التجربة المتبقين" else "Remaining Test Users", fontSize = 12.sp, color = Color.Gray)
                                Text(text = "$testCount", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
            }

            // Custom error block if any

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

            // Flat Continuous Subscriber List Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF039BE5).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "${filteredList.size} مشتركين",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF039BE5)
                    )
                }
                
                Text(
                    text = "قائمة المشتركين",
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = Color.White,
                    textAlign = TextAlign.Right
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

        // 4. Center Floating Capsule Search Pill ("بحث") or Full Width Keyboard Search
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
                        .align(Alignment.Center) // Clean center pill sizing
                ) {
                    Button(
                        onClick = { isSearchActive = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2830)), // dark slate search button
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
                                text = "بــحــث", // centered spacious arabic search label
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }

        // 2. DIM BACKGROUND AND SELECTABLE BOTTOM SORT SLIDE SHEET (Image 2)
        if (showSortSheet) {
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
                        .clickable(enabled = false) {}, // prevent dismiss tap leak
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF12181F))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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

                        // Head Title - "الترتيب بحسب"
                        Text(
                            text = "الترتيب بحسب",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = Color.White,
                            textAlign = TextAlign.Right
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Horizontally scrollable sort pills (Exactly matches list in Image 2)
                        val criteria = listOf("نهاية الاشتراك", "الاسم", "بدء الاشتراك", "دين المشترك", "سعر الاشتراك", "رقم الهاتف")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            criteria.forEach { item ->
                                val selected = selectedSort == item
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
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Panels Section - "اللوحات"
                        Text(
                            text = "اللوحات",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = Color.White,
                            textAlign = TextAlign.Right
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // الكل switch row
                            PanelSwitchRow(
                                label = "الكل",
                                checked = selectedPanel == "الكل",
                                icon = Icons.Default.Check,
                                onCheckedChange = { if (it) selectedPanel = "الكل" }
                            )

                            // EarthLink admin@sacx switch row
                            PanelSwitchRow(
                                label = "EarthLink admin@sacx",
                                checked = selectedPanel == "EarthLink admin@sacx",
                                icon = Icons.Default.AccountCircle,
                                onCheckedChange = { if (it) selectedPanel = "EarthLink admin@sacx" }
                            )

                            // محذوفة switch row
                            PanelSwitchRow(
                                label = "محذوفة",
                                checked = selectedPanel == "محذوفة",
                                icon = Icons.Default.Delete,
                                onCheckedChange = { if (it) selectedPanel = "محذوفة" }
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Time Section - "الوقت"
                        Text(
                            text = "الوقت",
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            color = Color.White,
                            textAlign = TextAlign.Right
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PanelSwitchRow(
                                label = "الكل",
                                checked = selectedStatusFilter == "الكل",
                                icon = Icons.Default.List,
                                onCheckedChange = { if (it) selectedStatusFilter = "الكل" }
                            )
                            PanelSwitchRow(
                                label = "فعال",
                                checked = selectedStatusFilter == "فعال",
                                icon = Icons.Default.CheckCircle,
                                onCheckedChange = { if (it) selectedStatusFilter = "فعال" }
                            )
                            PanelSwitchRow(
                                label = "قريب من الانتهاء",
                                checked = selectedStatusFilter == "قريب من الانتهاء",
                                icon = Icons.Default.HourglassEmpty,
                                onCheckedChange = { if (it) selectedStatusFilter = "قريب من الانتهاء" }
                            )
                            PanelSwitchRow(
                                label = "منتهي",
                                checked = selectedStatusFilter == "منتهي",
                                icon = Icons.Default.Cancel,
                                onCheckedChange = { if (it) selectedStatusFilter = "منتهي" }
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // Action Dismiss Button
                        Button(
                            onClick = { showSortSheet = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(text = "تطبيق الفرز", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// Helper custom switch item row for the bottom sorting dialog
