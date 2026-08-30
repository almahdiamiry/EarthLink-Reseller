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

@Composable
fun LocalAccountsScreen(
    viewModel: LocalAccountsViewModel,
    onAccountClick: (LocalAccount) -> Unit,
    onNavigateToImport: () -> Unit
) {
    val accounts by viewModel.filteredAccounts.collectAsStateWithLifecycle()
    val totalMatchingCount by viewModel.totalMatchingCount.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hasDebt by viewModel.filterDebt.collectAsStateWithLifecycle()
    val hasAdv by viewModel.filterAdvance.collectAsStateWithLifecycle()
    val noUser by viewModel.filterNoUsername.collectAsStateWithLifecycle()
    val hasCoords by viewModel.filterCoordinates.collectAsStateWithLifecycle()
    val sortOption by viewModel.sortOption.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as EarthlinkApp).preferenceManager }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()
    val isAr = currentLang == "ar"

    val sortLabel = if (isAr) "ترتيب حسب:" else "Sorted by:"
    val sortOptionsList = if (isAr) {
        listOf("name" to "الاسم", "debt" to "الدين", "price" to "السعر")
    } else {
        listOf("name" to "Name", "debt" to "Debt", "price" to "Price")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = if (isAr) "سجل الحسابات المحلية" else "Local Subscriber Billing", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(text = if (isAr) "دفاتر ديون سريعة بدون إنترنت" else "Fast Offline-First Ledgers", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onNavigateToImport, modifier = Modifier.size(48.dp)) {
                Icon(imageVector = Icons.Default.ImportContacts, contentDescription = if (isAr) "استيراد" else "Import", tint = MaterialTheme.colorScheme.primary)
            }
        }

        var localQuery by remember(query) { mutableStateOf(query) }
        OutlinedTextField(
            value = localQuery,
            onValueChange = {
                localQuery = it
                viewModel.setSearchQuery(it)
            },
            label = { Text(if (isAr) "تصفية حسابات السجل المحلي" else "Filter local ledger accounts") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        )

        // Filters categories Scrollable Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = hasDebt,
                onClick = { viewModel.toggleFilterDebt() },
                label = { Text(if (isAr) "ديون متبقية" else "Outstanding Debt") }
            )
            FilterChip(
                selected = hasAdv,
                onClick = { viewModel.toggleFilterAdvance() },
                label = { Text(if (isAr) "رصيد مدفوع مقدماً" else "Advance Prepaid") }
            )
            FilterChip(
                selected = noUser,
                onClick = { viewModel.toggleFilterNoUsername() },
                label = { Text(if (isAr) "بدون اسم يوزر" else "No Username") }
            )
            FilterChip(
                selected = hasCoords,
                onClick = { viewModel.toggleFilterCoordinates() },
                label = { Text(if (isAr) "إحداثيات الموقع" else "GPS Coordinates") }
            )
        }

        // Sorting Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = sortLabel, fontSize = 13.sp, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sortOptionsList.forEach { (key, label) ->
                    val isSel = sortOption == key
                    ElevatedAssistChip(
                        onClick = { viewModel.setSortOption(key) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = if (isSel) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else AssistChipDefaults.assistChipColors()
                    )
                }
            }
        }

        if (accounts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = if (isAr) "لا توجد سجلات تطابق معايير التصفية المحلية." else "No records match local filter criteria.", fontSize = 14.sp, color = Color.Gray)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
                items(accounts, key = { it.id }) { acc ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onAccountClick(acc) }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = acc.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                if (acc.debtIqd > 0.0) {
                                    Text(text = formatIqd(acc.debtIqd), color = Color(0xFFC62828), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                } else if (acc.advanceIqd > 0.0) {
                                    Text(text = "+" + formatIqd(acc.advanceIqd), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                } else {
                                    Text(text = if (isAr) "مسدد" else "Settled", color = Color.Gray, fontSize = 13.sp)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = acc.earthlinkUsername ?: (if (isAr) "حساب إيرثلنك غير مرتبط" else "Missing Earthlink Account"), color = Color.Gray, fontSize = 12.sp)
                                Text(text = "${if (isAr) "الباقة: " else "Pkg: "}${acc.packageName ?: "N/A"}", fontSize = 12.sp)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(text = "${if (isAr) "الهاتف: " else "Phone: "}${acc.phone1 ?: acc.phone2 ?: "N/A"}", fontSize = 12.sp)
                                Text(text = "${if (isAr) "السعر: " else "Price: "}${formatIqd(acc.currentPriceIqd)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                if (accounts.size < totalMatchingCount) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isAr) "عرض ${accounts.size} من أصل $totalMatchingCount مشترك" else "Showing ${accounts.size} of $totalMatchingCount subscribers",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { viewModel.loadMore() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isAr) "تحميل المزيد من المشتركين" else "Load More Subscribers")
                            }
                        }
                    }
                }
            }
        }
    }
}



// --- LOCAL ACCOUNT DETAIL & LEDGER FEED SCREEN ---
