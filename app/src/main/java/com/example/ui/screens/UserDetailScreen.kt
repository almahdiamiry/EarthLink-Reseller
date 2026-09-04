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
fun UserDetailScreen(
    userIndex: Int,
    viewModel: EarthlinkSearchViewModel,
    lang: String = "ar",
    onBack: () -> Unit
) {
    val detail by viewModel.selectedUser.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isActionLoading by viewModel.isActionLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.actionSuccess.collectAsStateWithLifecycle()

    var showRefillDialog by rememberSaveable { mutableStateOf(false) }
    var showExtendDialog by rememberSaveable { mutableStateOf(false) }
    var showPassToolsDialog by rememberSaveable { mutableStateOf(false) }

    val prefs = remember { viewModel.prefs }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()

    LaunchedEffect(userIndex) {
        viewModel.loadUserDetail(userIndex)
    }

    if (showRefillDialog) {
        ConfirmationDialog(
            title = if (currentLang == "ar") "تعبئة رصيد المشترك" else "Refill Subscriber Deposit",
            message = if (currentLang == "ar") "هل أنت متأكد من تعبئة يوزر ${detail?.userID}؟ سيتم خصم القيمة من محفظتك الدائنة." else "Are you sure you want to refill ${detail?.userID}? This uses your deposit budget.",
            needsPasswordField = true,
            onCancel = { showRefillDialog = false },
            onConfirm = { pass ->
                showRefillDialog = false
                detail?.userID?.let { viewModel.refillUser(it, pass) }
            }
        )
    }

    if (showExtendDialog) {
        ConfirmationDialog(
            title = if (currentLang == "ar") "تمديد صلاحية الحساب" else "Extend Subscriber Duration",
            message = if (currentLang == "ar") "تأكيد إضافة وتمديد معايير المدة الزمنية للحساب ${detail?.userID}؟" else "Confirm extending the duration parameter of account ${detail?.userID}?",
            needsPasswordField = false,
            onCancel = { showExtendDialog = false },
            onConfirm = {
                showExtendDialog = false
                viewModel.extendUser(userIndex, detail?.userID ?: "")
            }
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides (if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF10161D)) // Cohesive Apple dark canvas background
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (currentLang == "ar") "رجوع" else "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = if (currentLang == "ar") "تفاصيل المشترك" else "Subscriber Details",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.height(300.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF007AFF))
                }
                return@Column
            }

            val user = detail
            if (user == null) {
                Text(
                    text = if (currentLang == "ar") "عذراً، فشل جلب بيانات المشترك المحدد." else "Selected user could not be retrieved successfully.",
                    color = Color.White,
                    modifier = Modifier.padding(12.dp)
                )
                return@Column
            }

            if (error != null) {
                Surface(
                    color = Color(0xFFFF453A).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF453A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error ?: "",
                        color = Color(0xFFFF453A),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            if (success != null) {
                Surface(
                    color = Color(0xFF30D158).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF30D158)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = success ?: "",
                        color = Color(0xFF30D158),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            val finalExpirationStr = remember(user.expirationDate, user.manualExpirationDate, user.accountExpirationDate) {
                listOfNotNull(
                    user.manualExpirationDate,
                    user.accountExpirationDate,
                    user.expirationDate
                ).firstOrNull { it.isNotBlank() && it != "N/A" }
            }
            val remainingTime = remember(finalExpirationStr, user.activeDaysLeft, lang, user.accountStatus) {
                getRemainingTime(finalExpirationStr, user.activeDaysLeft?.toString(), lang, user.accountStatus)
            }
            val statusClean = user.accountStatus?.trim()?.lowercase() ?: ""
            val isExpired = remainingTime.contains("منتهي") || remainingTime.contains("Expired")
            val isDeactivated = statusClean == "suspendedbyagent" || statusClean == "expired" || statusClean == "منتهي"
            val isSubscriptionActive = !isExpired && !isDeactivated

            // Core Profile Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)), // iOS Dynamic Dark Background
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = user.userID,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(status = user.accountStatus ?: "unknown")
                    }
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    // Apple Wallet style high-fidelity duration badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (isSubscriptionActive && !isExpired) Color(0xFF007AFF).copy(alpha = 0.08f) else Color(0xFFFF453A).copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSubscriptionActive && !isExpired) Color(0xFF007AFF).copy(alpha = 0.25f) else Color(0xFFFF453A).copy(alpha = 0.25f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (currentLang == "ar") "المتبقي للاشتراك" else "Subscription Remaining",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (isSubscriptionActive && !isExpired) remainingTime else (if (currentLang == "ar") "الاشتراك منتهي" else "Expired"),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isSubscriptionActive && !isExpired) Color(0xFF30D158) else Color(0xFFFF453A),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Core Subscriber metadata rows using high-fidelity helper
                    DetailRow(
                        icon = Icons.Default.Fingerprint,
                        iconColor = Color(0xFF8E8E93),
                        label = if (currentLang == "ar") "المعرف الرقمي (Index)" else "User Index",
                        value = "${user.userIndex}"
                    )
                    DetailRow(
                        icon = Icons.Default.Person,
                        iconColor = Color(0xFF0A84FF),
                        label = if (currentLang == "ar") "الاسم الكامل" else "Full Name",
                        value = user.customerFullName ?: "N/A"
                    )
                    DetailRow(
                        icon = Icons.Default.Phone,
                        iconColor = Color(0xFF30D158),
                        label = if (currentLang == "ar") "رقم الهاتف" else "Phone Number",
                        value = user.mobileNumber ?: "N/A"
                    )
                    DetailRow(
                        icon = Icons.Default.Layers,
                        iconColor = Color(0xFFBF5AF2),
                        label = if (currentLang == "ar") "الباقة المشترك بها" else "Active Package",
                        value = user.packageName ?: "N/A",
                        valueColor = Color(0xFF0A84FF)
                    )
                    DetailRow(
                        icon = Icons.Default.DateRange,
                        iconColor = Color(0xFFFF9F0A),
                        label = if (currentLang == "ar") "تاريخ صلاحية الاشتراك" else "Expiration Date",
                        value = user.expirationDate ?: "N/A",
                        valueColor = Color(0xFFFF9F0A)
                    )
                }
            }

            // Connection Parameters Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (currentLang == "ar") "معايير الاتصال بالشبكة" else "Network Parameters",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    
                    DetailRow(
                        icon = Icons.Default.Dns,
                        iconColor = Color(0xFF30D158),
                        label = if (currentLang == "ar") "عنوان IP الحالي" else "Current IP Address",
                        value = user.currentIP ?: "N/A"
                    )
                    DetailRow(
                        icon = Icons.Default.SettingsEthernet,
                        iconColor = Color(0xFF0A84FF),
                        label = if (currentLang == "ar") "الماك النشط (MAC)" else "Active MAC",
                        value = user.currentMAC ?: "N/A"
                    )
                    DetailRow(
                        icon = Icons.Default.Lock,
                        iconColor = Color(0xFFBF5AF2),
                        label = if (currentLang == "ar") "الماك الافتراضي للحساب" else "Binding MAC",
                        value = user.accountMAC ?: "N/A"
                    )
                    DetailRow(
                        icon = Icons.Default.Schedule,
                        iconColor = Color(0xFFFF9F0A),
                        label = if (currentLang == "ar") "زمن الاتصال الشغال" else "Online Session Time",
                        value = user.onlineSessionTime ?: "N/A"
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action sections
            Text(
                text = if (currentLang == "ar") "العمليات المتاحة للموزع" else "Available Operator Actions",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.White
            )

            Button(
                onClick = { showRefillDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)), // Apple Green
                enabled = !isActionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Filled.AddBox, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentLang == "ar") "تعبئة الرصيد من المحفظة" else "Refill Balance Using Budget",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Button(
                onClick = { showExtendDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBF5AF2)), // Apple Purple
                enabled = !isActionLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.DateRange, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentLang == "ar") "تمديد فترة الاشتراك" else "Extend Account Duration",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Button(
                onClick = { showPassToolsDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E242E)),
                border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.LockOpen, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentLang == "ar") "عرض وتغيير كلمة المرور" else "Show & Reset Passwords",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Pass Tools Dialog Overlay
            if (showPassToolsDialog) {
                PasswordToolsScreen(
                    userIndex = userIndex,
                    userId = user.userID,
                    viewModel = viewModel,
                    currentLang = currentLang,
                    onClose = { showPassToolsDialog = false }
                )
            }
        }
    }
}
