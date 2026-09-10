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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = "Cloud Sync & Audit", fontSize = 21.sp, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val statusTextStr = when (state) {
                    SyncStatusState.IDLE -> "IDLE (WAITING CYCLE)"
                    SyncStatusState.SYNCING -> "UPLOADING TO CLOUD..."
                    SyncStatusState.OFFLINE -> "OFFLINE MODE ENABLED"
                    SyncStatusState.ERROR -> "SYNC ERROR REACHED"
                    SyncStatusState.AUTH_REQUIRED -> "AUTHENTICATION NEEDED"
                    SyncStatusState.COMPLETE -> "LEDGER COMPLETELY SYNCED"
                    SyncStatusState.COMPLETE_WITH_ERRORS -> "SYNC COMPLETED WITH ERRORS"
                }
                Text(text = "Sync Status: $statusTextStr", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                Text(text = "Firebase User UID: ${viewModel.getFirebaseUid() ?: "Pending anonymous link"}", fontSize = 12.sp, color = Color.Gray)

                val dateStr = if (lastSyncTime > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(lastSyncTime)) else "Never"
                Text(text = "Last Cloud Backup: $dateStr", fontSize = 13.sp)

                Text(
                    text = "Outbox Queue Size: $pendingCount changes pending synchronization",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = if (pendingCount > 0) Color(0xFFE65100) else Color(0xFF2E7D32)
                )

                if (failedCount > 0) {
                    Text(
                        text = "⚠️ Retrying Failed Items: $failedCount items encountered sync errors and remain queued for retry.",
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
                        Text("Connect Firebase")
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
                            Text("Sync Ledgers Now")
                        }
                    }
                }

                if (failedCount > 0) {
                    OutlinedButton(
                        onClick = { viewModel.retryFailedItems() },
                        enabled = !progress,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    ) {
                        Text("🔄 Reset Backoff & Retry $failedCount Failed Items")
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
            val logTimeFormatter = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
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
                                    text = logTimeFormatter.format(java.util.Date(log.createdAt)),
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
}

// --- OPERATOR SETTINGS SCREEN ---
