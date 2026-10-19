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
fun SyncStatusScreen(
    viewModel: SyncStatusViewModel
) {
    val context = LocalContext.current.applicationContext as EarthlinkApp
    val prefs = context.preferenceManager
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()
    
    val state by viewModel.syncState.collectAsStateWithLifecycle(initialValue = SyncStatusState.IDLE)
    val lastSyncTime by viewModel.lastSyncTime.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val failedCount by viewModel.failedCount.collectAsStateWithLifecycle()
    val progress by viewModel.isSyncingProgress.collectAsStateWithLifecycle()
    val logs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val pendingOps by viewModel.pendingGatewayOperations.collectAsStateWithLifecycle()
    val actionMessage by viewModel.operationActionMessage.collectAsStateWithLifecycle()

    var selectedSuccessTxId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFailureTxId by rememberSaveable { mutableStateOf<String?>(null) }
    var evidenceInput by rememberSaveable { mutableStateOf("") }

    val toastContext = LocalContext.current
    LaunchedEffect(actionMessage) {
        actionMessage?.let { msg ->
            Toast.makeText(toastContext, msg, Toast.LENGTH_LONG).show()
            viewModel.clearOperationActionMessage()
        }
    }

    val syncDateFormat = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (currentLang == "ar") "مزامنة السحابة والسجل" else "Cloud Sync & Audit",
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val statusTextStr = when (state) {
                    SyncStatusState.IDLE -> if (currentLang == "ar") "خامل (انتظار المزامنة)" else "IDLE (WAITING CYCLE)"
                    SyncStatusState.SYNCING -> if (currentLang == "ar") "جاري الرفع إلى السحابة..." else "UPLOADING TO CLOUD..."
                    SyncStatusState.OFFLINE -> if (currentLang == "ar") "وضع عدم الاتصال مفعل" else "OFFLINE MODE ENABLED"
                    SyncStatusState.ERROR -> if (currentLang == "ar") "حدث خطأ في المزامنة" else "SYNC ERROR REACHED"
                    SyncStatusState.AUTH_REQUIRED -> if (currentLang == "ar") "يتطلب تسجيل الدخول" else "AUTHENTICATION NEEDED"
                    SyncStatusState.COMPLETE -> if (currentLang == "ar") "تمت المزامنة بالكامل" else "LEDGER COMPLETELY SYNCED"
                    SyncStatusState.COMPLETE_WITH_ERRORS -> if (currentLang == "ar") "اكتملت المزامنة مع وجود أخطاء" else "SYNC COMPLETED WITH ERRORS"
                }
                Text(
                    text = if (currentLang == "ar") "حالة المزامنة: $statusTextStr" else "Sync Status: $statusTextStr",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                val pendingUidLabel = if (currentLang == "ar") "بانتظار الربط" else "Pending anonymous link"
                val firebaseUidText = viewModel.getFirebaseUid() ?: pendingUidLabel
                Text(
                    text = if (currentLang == "ar") "معرف حساب Firebase: $firebaseUidText" else "Firebase User UID: $firebaseUidText",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                val neverStr = if (currentLang == "ar") "أبداً" else "Never"
                val dateStr = if (lastSyncTime > 0) syncDateFormat.format(java.util.Date(lastSyncTime)) else neverStr
                Text(
                    text = if (currentLang == "ar") "آخر نسخة سحابية: $dateStr" else "Last Cloud Backup: $dateStr",
                    fontSize = 13.sp
                )

                val outboxText = if (currentLang == "ar") "حجم قائمة الانتظار: $pendingCount تغييرات بانتظار المزامنة" else "Outbox Queue Size: $pendingCount changes pending synchronization"
                Text(
                    text = outboxText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (pendingCount > 0) Color(0xFFE65100) else Color(0xFF2E7D32)
                )

                if (failedCount > 0) {
                    val retryWarningText = if (currentLang == "ar") "⚠️ إعادة محاولة العناصر الفاشلة: $failedCount عناصر واجهت أخطاء وهي قيد الانتظار." else "⚠️ Retrying Failed Items: $failedCount items encountered sync errors and remain queued for retry."
                    Text(
                        text = retryWarningText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.signInAnonymously() },
                        enabled = viewModel.getFirebaseUid() == null,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                    ) {
                        Text(if (currentLang == "ar") "ربط Firebase" else "Connect Firebase")
                    }

                    Button(
                        onClick = { viewModel.triggeredSync() },
                        enabled = !progress,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp)
                    ) {
                        if (progress) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                        } else {
                            Text(if (currentLang == "ar") "مزامنة السجلات الآن" else "Sync Ledgers Now")
                        }
                    }
                }

                if (failedCount > 0) {
                    OutlinedButton(
                        onClick = { viewModel.retryFailedItems() },
                        enabled = !progress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    ) {
                        Text(if (currentLang == "ar") "🔄 إعادة المحاولة ($failedCount عناصر)" else "🔄 Reset Backoff & Retry $failedCount Failed Items")
                    }
                }
            }
        }

        // --- GATEWAY OPERATIONS CARD (Pending Ops & Recovery) ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (pendingOps.isEmpty()) MaterialTheme.colorScheme.surface else Color(0xFFFFF8E1)
            ),
            border = if (pendingOps.isNotEmpty()) BorderStroke(1.dp, Color(0xFFFFB74D)) else null,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (pendingOps.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (pendingOps.isEmpty()) Color(0xFF2E7D32) else Color(0xFFE65100),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (currentLang == "ar") {
                                if (pendingOps.isEmpty()) "عمليات المزودات مستقرة ومؤكدة" else "عمليات معلقة بحاجة للمطابقة (${pendingOps.size})"
                            } else {
                                if (pendingOps.isEmpty()) "Provider Operations Settled" else "Pending Gateway Operations (${pendingOps.size})"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = if (pendingOps.isEmpty()) Color(0xFF2E7D32) else Color(0xFFE65100)
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.triggerGatewaySweep() },
                        enabled = !progress,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 36.dp)
                    ) {
                        Text(
                            text = if (currentLang == "ar") "فحص واسترداد فوري" else "Immediate Sweep",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (pendingOps.isEmpty()) {
                    Text(
                        text = if (currentLang == "ar")
                            "لا توجد عمليات معلقة حالياً على بوابات إيرثلنك أو سام."
                        else
                            "No pending operations awaiting gateway resolution.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (pendingOps.size > 2) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()) else Modifier
                            ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        pendingOps.forEach { op ->
                            val isSamm = op.operationProvider == SasProviders.ALAMIRY
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, Color(0xFFFFCC80)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                color = if (isSamm) Color(0xFFE3F2FD) else Color(0xFFFFF3E0),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (isSamm) "سام (ALAMIRY)" else "إيرثلنك",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSamm) Color(0xFF1565C0) else Color(0xFFE65100),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                val opTypeDisplay = when (op.operationType) {
                                                    "ACTIVATION" -> if (currentLang == "ar") "تفعيل خط" else "Activation"
                                                    "RENEWAL" -> if (currentLang == "ar") "تجديد اشتراك" else "Renewal"
                                                    "REFILL" -> if (currentLang == "ar") "إعادة تعبئة" else "Refill"
                                                    "EXTEND" -> if (currentLang == "ar") "تمديد صلاحية" else "Extend"
                                                    "TEST_USER" -> if (currentLang == "ar") "فحص مستخدم" else "Test User"
                                                    else -> op.operationType
                                                }
                                                Text(
                                                    text = opTypeDisplay,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        if (op.amountIqd > 0) {
                                            Text(
                                                text = com.example.core.ledger.MoneyParser.formatIqdForDisplay(op.amountIqd.toDouble()) + (if (currentLang == "ar") " د.ع" else " IQD"),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = (if (currentLang == "ar") "المشترك: " else "User: ") + op.accountId,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp
                                        )

                                        val hasClaim = op.dispatchClaimCount > 0
                                        Surface(
                                            color = if (hasClaim) Color(0xFFFFF3E0) else Color(0xFFF5F5F5),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (hasClaim) {
                                                    if (currentLang == "ar") "تم الإرسال (Claim=1)" else "Dispatched (Claim=1)"
                                                } else {
                                                    if (currentLang == "ar") "لم يُسجل إرسال خارجي (Claim=0)" else "No Dispatch (Claim=0)"
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (hasClaim) Color(0xFFE65100) else Color(0xFF616161),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val timeStr = if (op.createdAt > 0) syncDateFormat.format(java.util.Date(op.createdAt)) else ""
                                    Text(
                                        text = (if (currentLang == "ar") "التاريخ: " else "Date: ") + timeStr,
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )

                                    if (!op.lastError.isNullOrBlank()) {
                                        Text(
                                            text = (if (currentLang == "ar") "آخر خطأ: " else "Last Error: ") + op.lastError,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 2
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                evidenceInput = ""
                                                selectedSuccessTxId = op.businessTransactionId
                                            },
                                            enabled = !progress,
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.weight(1f).heightIn(min = 36.dp)
                                        ) {
                                            Text(
                                                text = if (currentLang == "ar") "تأكيد النجاح يدوياً" else "Confirm Success",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                selectedFailureTxId = op.businessTransactionId
                                            },
                                            enabled = !progress,
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.weight(1f).heightIn(min = 36.dp)
                                        ) {
                                            Text(
                                                text = if (currentLang == "ar") "إلغاء وفشل" else "Cancel & Fail",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            text = if (currentLang == "ar") "سجل عمليات الموزع" else "Local Operator Audit Log",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (currentLang == "ar") "لا توجد عمليات مسجلة حالياً." else "No operator activities logged on this terminal yet.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val logTimeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
            LazyColumn(modifier = Modifier.fillMaxSize().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs, key = { it.id }) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    text = log.action,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = logTimeFormat.format(java.util.Date(log.createdAt)),
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            
                            val parts = log.summary.split("ملاحظة:", "Note:", ignoreCase = true)
                            Text(text = parts[0].trim(), fontSize = 14.sp)
                            
                            if (parts.size > 1) {
                                val note = parts[1].trim()
                                if (note.isNotBlank() && note != "000") {
                                    Text(
                                        text = (if (currentLang == "ar") "ملاحظة: " else "Note: ") + note,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                            
                            if (log.entityId != null) {
                                Text(
                                    text = (if (currentLang == "ar") "رقم المرجع: " else "Target ID: ") + log.entityId,
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedSuccessTxId != null) {
        val op = pendingOps.find { it.businessTransactionId == selectedSuccessTxId }
        if (op != null) {
            AlertDialog(
                onDismissRequest = {
                    selectedSuccessTxId = null
                    evidenceInput = ""
                },
                title = {
                    Text(
                        text = if (currentLang == "ar") "تأكيد نجاح العملية يدوياً" else "Manual Success Confirmation",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (currentLang == "ar")
                                "تنبيه مالي: تأكيد النجاح يدوياً سيقوم بتسجيل قيد مالي وتثبيت الدين على المشترك (${op.accountId}) بمبلغ ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(op.amountIqd.toDouble())} د.ع بشكل نهائي.\nتأكد من إتمام العملية على بوابة المزود."
                            else
                                "Financial Warning: Manual confirmation will book debt for subscriber (${op.accountId}) in the amount of ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(op.amountIqd.toDouble())} IQD permanently.\nConfirm completion on the gateway first.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        if (op.dispatchClaimCount == 0) {
                            Text(
                                text = if (currentLang == "ar")
                                    "⚠️ تنبيه إضافي: هذه العملية لم تسجل محاولة إرسال ناجحة للشبكة (Claim=0). تأكد من أن التفعيل تم بالفعل على البوابة قبل المتابعة."
                                else
                                    "⚠️ Notice: This operation has no recorded network dispatch (Claim=0). Confirm it was performed on the gateway before proceeding.",
                                color = Color(0xFFE65100),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        OutlinedTextField(
                            value = evidenceInput,
                            onValueChange = { evidenceInput = it },
                            label = {
                                Text(
                                    if (currentLang == "ar") "ملاحظة / مرجع الإثبات (5 أحرف على الأقل)" else "Evidence Note / Ref (min 5 chars)"
                                )
                            },
                            placeholder = {
                                Text(
                                    if (currentLang == "ar") "مثال: تم التأكد من رقم الوصل 12345" else "e.g. Verified via receipt 12345"
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = evidenceInput.trim().length >= 5 && !progress,
                        onClick = {
                            val targetOp = op
                            val note = evidenceInput.trim()
                            selectedSuccessTxId = null
                            evidenceInput = ""
                            viewModel.confirmOperationSuccess(targetOp, note)
                        }
                    ) {
                        Text(if (currentLang == "ar") "تأكيد وتسجيل الدين" else "Confirm & Book Debt")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            selectedSuccessTxId = null
                            evidenceInput = ""
                        }
                    ) {
                        Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                    }
                }
            )
        }
    }

    if (selectedFailureTxId != null) {
        val op = pendingOps.find { it.businessTransactionId == selectedFailureTxId }
        if (op != null) {
            AlertDialog(
                onDismissRequest = { selectedFailureTxId = null },
                title = {
                    Text(
                        text = if (currentLang == "ar") "إلغاء العملية وتأكيد الفشل" else "Cancel Operation & Mark Failed",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = if (currentLang == "ar")
                            "هل أنت متأكد من إلغاء العملية (${op.operationType}) للمشترك (${op.accountId})؟\n\nسيتم وسم العملية كفاشلة ولن يتم تسجيل أي ديون أو مبالغ مالية على المشترك."
                        else
                            "Are you sure you want to cancel operation (${op.operationType}) for subscriber (${op.accountId})?\n\nThis will mark it as failed and will NOT record any financial debt."
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        enabled = !progress,
                        onClick = {
                            val targetOp = op
                            selectedFailureTxId = null
                            viewModel.markOperationFailed(targetOp)
                        }
                    ) {
                        Text(if (currentLang == "ar") "تأكيد الإلغاء والفشل" else "Confirm & Mark Failed")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedFailureTxId = null }) {
                        Text(if (currentLang == "ar") "تراجع" else "Cancel")
                    }
                }
            )
        }
    }
}

// --- OPERATOR SETTINGS SCREEN ---
