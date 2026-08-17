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
import com.example.core.sync.ImportResult
import com.example.core.sync.UtowerImporter
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
fun ImportUtowerScreen(
    viewModel: LocalAccountsViewModel,
    onBack: () -> Unit
) {
    val importResult by viewModel.importResult.collectAsStateWithLifecycle()
    val batches by viewModel.importBatches.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var replaceData by rememberSaveable { mutableStateOf(false) }
    var showWipeWarning by rememberSaveable { mutableStateOf(false) }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importTgzFile(uri, context, replaceData)
            replaceData = false
        }
    }

    if (showWipeWarning) {
        ConfirmationDialog(
            title = "تأكيد مسح واستبدال جميع البيانات",
            message = "تحذير: سيتم مسح جميع المشتركين والسجلات المالية الحالية نهائياً واستبدالها بالبيانات الموجودة في الملف المحدد. لا يمكن التراجع عن هذا الإجراء. هل تريد المتابعة؟\n\nWarning: All existing local subscriber accounts and financial ledger history will be permanently wiped and overwritten by the contents of this file. This action cannot be undone. Do you want to proceed?",
            needsPasswordField = false,
            onCancel = { showWipeWarning = false },
            onConfirm = {
                showWipeWarning = false
                replaceData = true
            }
        )
    }

    var showRollbackConfirmId by rememberSaveable { mutableStateOf<String?>(null) }

    if (showRollbackConfirmId != null) {
        ConfirmationDialog(
            title = "Confirm Batch Rollback",
            message = "Rollback removes all new subscriber profiles and payments registered only by this batch. Revert now?",
            needsPasswordField = false,
            onCancel = { showRollbackConfirmId = null },
            onConfirm = {
                val id = showRollbackConfirmId ?: return@ConfirmationDialog
                showRollbackConfirmId = null
                viewModel.rollbackBatch(id)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Import uTower Data", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        if (error != null) {
            Surface(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(4.dp)) {
                Text(text = error ?: "", color = Color(0xFFC62828), modifier = Modifier.padding(10.dp), fontSize = 13.sp)
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Import Data or Restore Backup (.tgz / .zip / .db)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    text = "Select a uTower `utower_data.tgz` export file or a native application backup file (`.zip` / `.db`).",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { replaceData = false }) {
                    RadioButton(selected = !replaceData, onClick = { replaceData = false })
                    Text("Merge with current data", fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { if (!replaceData) showWipeWarning = true }) {
                    RadioButton(selected = replaceData, onClick = { if (!replaceData) showWipeWarning = true })
                    Text("Replace all current data (Wipe)", fontSize = 14.sp, color = Color.Red)
                }
                
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Icon(imageVector = Icons.Default.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isLoading) "Importing / Restoring..." else "Select Backup File (.tgz / .zip / .db)")
                }
            }
        }

        val pre = importResult
        if (pre != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (pre.success) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(text = if (pre.success) "Import Completed Successfully" else "Import Failed", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (pre.success) Color(0xFF2E7D32) else Color(0xFFC62828))
                    if (!pre.success && pre.errorMessage != null) {
                        Text(text = "Error: ${pre.errorMessage}", fontSize = 12.sp, color = Color(0xFFB71C1C), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    if (!pre.success && pre.failedFile != null) {
                        Text(text = "Processing file: ${pre.failedFile}", fontSize = 12.sp, color = Color(0xFFB71C1C), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                    HorizontalDivider()
                    Text(text = "Total profiles discovered: ${pre.subscribersFound}", fontSize = 13.sp, color = Color.Black)
                    Text(text = "New subscribers imported: ${pre.subscribersImported}", fontSize = 13.sp, color = Color.Black)
                    Text(text = "Existing subscribers merged: ${pre.subscribersMerged}", fontSize = 13.sp, color = Color.Black)
                    Text(text = "Subscriber Notes imported: ${pre.subscribersNotesImported}", fontSize = 13.sp, color = Color.Black)
                    Text(text = "Nano IPs imported: ${pre.nanoIpsImported}", fontSize = 13.sp, color = Color.Black)
                    Text(text = "Historical ledger transactions: ${pre.transactionsImported}", fontSize = 13.sp, color = Color.Black)
                    Text(text = "Transaction Notes imported: ${pre.transactionNotesImported}", fontSize = 13.sp, color = Color.Black)
                    if (pre.warnings > 0 || pre.errors > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        var showDetails by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { showDetails = !showDetails }) {
                            Text(text = if (showDetails) "Hide Details" else "Show Details (${pre.warnings} Warnings, ${pre.errors} Errors)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFD32F2F))
                        }
                        if (showDetails) {
                            Text(text = "Warnings indicate skipped transactions due to missing subscriber mappings. Errors indicate failed processing.", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }

        Text(text = "Historial Import Batches", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (batches.isEmpty()) {
            Text(text = "No batch logs imported yet.", color = Color.Gray, fontSize = 13.sp)
        } else {
            batches.forEach { batch ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(text = batch.fileName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = "Accounts: ${batch.accountsImported} | Debt: ${formatIqd(batch.totalDebtIqd)}", fontSize = 12.sp)
                            Text(
                                text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(batch.createdAt)),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { showRollbackConfirmId = batch.id }) {
                            Icon(imageVector = Icons.Default.Undo, contentDescription = "Rollback", tint = Color.Red)
                        }
                    }
                }
            }
        }
    }
}

// --- CREATE TEST USER SCREEN ---
