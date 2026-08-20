package com.example.ui.screens

import android.widget.Toast
import com.example.core.util.AppBuildConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
fun SettingsScreen(
    authViewModel: AuthViewModel,
    dashboardViewModel: DashboardViewModel,
    syncViewModel: SyncStatusViewModel? = null,
    onLogout: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToSubscribers: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext as EarthlinkApp
    val finalSyncViewModel: SyncStatusViewModel = syncViewModel ?: remember {
        SyncStatusViewModel(
            syncRepo = context.syncRepository,
            audit = context.auditRepository,
            prefs = context.preferenceManager
        )
    }

    LaunchedEffect(Unit) {
        finalSyncViewModel.refreshPendingCount()
    }

    val syncState by finalSyncViewModel.syncState.collectAsStateWithLifecycle(SyncStatusState.IDLE)
    val lastSyncTime by finalSyncViewModel.lastSyncTime.collectAsStateWithLifecycle()
    val pendingCount by finalSyncViewModel.pendingCount.collectAsStateWithLifecycle()
    val isSyncingProgress by finalSyncViewModel.isSyncingProgress.collectAsStateWithLifecycle()

    val username by authViewModel.username.collectAsStateWithLifecycle()
    val prefs = authViewModel.prefs
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()
    var showPricingDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var unsyncedWarningDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCountState by rememberSaveable { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    
    val localContext = LocalContext.current
    val focusManager = LocalFocusManager.current

    // --- LOCAL AUTO BACKUP STATES ---
    val isLocalBackupEnabled by prefs.localBackupEnabledFlow.collectAsStateWithLifecycle()
    val lastLocalBackupTime by prefs.localLastBackupTimeFlow.collectAsStateWithLifecycle()

    var isPerformingLocalBackup by rememberSaveable { mutableStateOf(false) }
    var isFetchingLocalBackups by rememberSaveable { mutableStateOf(false) }
    var availableLocalBackups by remember { mutableStateOf<List<java.io.File>?>(null) }
    var selectedBackupToRestore by remember { mutableStateOf<java.io.File?>(null) }
    var isRestoringFromLocal by rememberSaveable { mutableStateOf(false) }
    var pendingOutboxCountForRestore by rememberSaveable { mutableStateOf(0) }
    var pendingFileForRestore by remember { mutableStateOf<java.io.File?>(null) }
    var showUnsyncedOutboxWarningDialog by rememberSaveable { mutableStateOf(false) }
    var backupRestoreDate by rememberSaveable { mutableStateOf<String?>(null) }
    var currentDbStats by remember { mutableStateOf<com.example.core.backup.BackupManager.DatabaseStats?>(null) }

    LaunchedEffect(selectedBackupToRestore) {
        val file = selectedBackupToRestore
        if (file != null) {
            backupRestoreDate = com.example.core.backup.BackupManager.getBackupFormattedDate(file)
            currentDbStats = com.example.core.backup.BackupManager.getCurrentDatabaseStats(localContext)
        } else {
            backupRestoreDate = null
            currentDbStats = null
        }
    }

    val exportLocalBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val success = com.example.core.backup.BackupManager.exportBackupToUri(localContext, uri)
                if (success) {
                    Toast.makeText(localContext, if (currentLang == "ar") "تم تصدير النسخة الاحتياطية بنجاح" else "Backup exported successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(localContext, if (currentLang == "ar") "فشل تصدير النسخة الاحتياطية" else "Backup export failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("1023954528299-it54t81l6ptjpt3cbgk7nu4qbn3pu265.apps.googleusercontent.com")
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(localContext, gso) }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED) {
            Toast.makeText(localContext, if (currentLang == "ar") "تم إلغاء تسجيل الدخول بواسطة Google" else "Google Sign-In canceled", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            val email = account?.email
            if (idToken != null) {
                authViewModel.signInWithGoogle(idToken, email) {
                    Toast.makeText(localContext, if (currentLang == "ar") "تم ربط حساب Google بنجاح" else "Google Account Linked!", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(localContext, if (currentLang == "ar") "لم يتم العثور على معرف Google ID Token" else "Google ID Token not found.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            val statusCode = (e as? ApiException)?.statusCode ?: -1
            val isDeveloperError = statusCode == 10 || statusCode == 12500 || statusCode == 10200 || e.message?.contains("10:") == true
            val errMsg = if (isDeveloperError) {
                if (currentLang == "ar") "خطأ تهيئة Google (رمز 10/12500). تأكد من إضافة بصمة SHA-1 في Firebase Console." else "Google Config Error (Code 10/12500). Missing SHA-1 in Firebase Console."
            } else {
                e.message ?: if (currentLang == "ar") "فشل تسجيل الدخول بواسطة Google" else "Google Sign-In failed"
            }
            Toast.makeText(localContext, errMsg, Toast.LENGTH_LONG).show()
        }
    }
    
    // --- DEV MODE ---
    var showDevMode by rememberSaveable { mutableStateOf(false) }
    val isDemoMode by prefs.demoModeFlow.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(54.dp),
                tint = Color(0xFF007AFF) // Apple System Blue
            )
            Text(
                text = if (currentLang == "ar") "إعدادات الموزع" else "Operator Configuration",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // --- CLOUD SYNC SECTION ---
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (currentLang == "ar") "المزامنة السحابية" else "Cloud Sync",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(imageVector = Icons.Default.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Text(text = if (currentLang == "ar") "الحالة" else "Status", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        
                        val (badgeText, badgeBg, badgeTextClr) = when {
                            isSyncingProgress || syncState == SyncStatusState.SYNCING -> Triple(if (currentLang == "ar") "جاري المزامنة" else "Syncing", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
                            syncState == SyncStatusState.COMPLETE || syncState == SyncStatusState.IDLE -> Triple(if (currentLang == "ar") "مكتمل" else "Up to date", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary)
                            else -> Triple(if (currentLang == "ar") "غير معروف" else "Unknown", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(modifier = Modifier.background(badgeBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Text(text = badgeText, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = badgeTextClr)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = if (currentLang == "ar") "آخر مزامنة:" else "Last sync:", color = Color.Gray, fontSize = 13.sp)
                        Text(text = if (lastSyncTime <= 0L) "-" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(lastSyncTime)), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Button(
                        onClick = { finalSyncViewModel.triggeredSync() },
                        enabled = !isSyncingProgress,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(text = if (currentLang == "ar") "مزامنة الآن" else "Sync Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- LOCAL AUTO BACKUP SECTION ---
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (currentLang == "ar") "النسخ الاحتياطي المحلي التلقائي" else "Local Auto Backup (30 Days)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Daily Auto Backup Switch Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (currentLang == "ar") "نسخ احتياطي يومي (30 يوم)" else "Daily Rolling Backup (30 days)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = if (currentLang == "ar") "يحفظ نسخة احتياطية يومياً في مجلد Documents/EarthlinkBackups ويمسح الأقدم من 30 يوماً" else "Saves a daily backup to Documents/EarthlinkBackups and removes backups older than 30 days",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = isLocalBackupEnabled,
                            onCheckedChange = { enabled ->
                                prefs.setLocalBackupEnabled(enabled)
                                com.example.core.backup.LocalAutoBackupWorker.schedule(localContext, enabled)
                                Toast.makeText(
                                    localContext,
                                    if (enabled) {
                                        if (currentLang == "ar") "تم تفعيل النسخ الاحتياطي اليومي" else "Daily local backup enabled"
                                    } else {
                                        if (currentLang == "ar") "تم إيقاف النسخ الاحتياطي اليومي" else "Daily local backup disabled"
                                    },
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }

                    // Last Backup Time
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (currentLang == "ar") "آخر نسخة احتياطية:" else "Last Backup:",
                            color = Color.Gray,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (lastLocalBackupTime <= 0L) "-" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(lastLocalBackupTime)),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Backup & Restore Buttons
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    isPerformingLocalBackup = true
                                    try {
                                        val zipFile = com.example.core.backup.BackupManager.createDailyRollingBackup(localContext)
                                        if (zipFile != null && zipFile.exists()) {
                                            val now = System.currentTimeMillis()
                                            prefs.saveLocalLastBackupTime(now)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(localContext, if (currentLang == "ar") "تم حفظ النسخة الاحتياطية بنجاح!" else "Local backup created successfully!", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(localContext, if (currentLang == "ar") "فشل إنشاء النسخة الاحتياطية" else "Backup creation failed", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(localContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    } finally {
                                        isPerformingLocalBackup = false
                                    }
                                }
                            },
                            enabled = !isPerformingLocalBackup,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isPerformingLocalBackup) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (currentLang == "ar") "جاري النسخ..." else "Backing up...")
                            } else {
                                Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (currentLang == "ar") "نسخ احتياطي الآن" else "Backup Now", fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isFetchingLocalBackups = true
                                    try {
                                        val list = com.example.core.backup.BackupManager.listDailyBackups(localContext)
                                        if (list.isEmpty()) {
                                            Toast.makeText(localContext, if (currentLang == "ar") "لا توجد نسخ احتياطية سابقة" else "No backups found", Toast.LENGTH_LONG).show()
                                        } else {
                                            availableLocalBackups = list
                                        }
                                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                        Toast.makeText(localContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isFetchingLocalBackups = false
                                    }
                                }
                            },
                            enabled = !isFetchingLocalBackups,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isFetchingLocalBackups) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (currentLang == "ar") "جاري جلب القائمة..." else "Fetching backups...")
                            } else {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (currentLang == "ar") "استرجاع من النسخ المحلية" else "Restore from Local Backups", fontWeight = FontWeight.Bold)
                            }
                        }

                        TextButton(
                            onClick = { exportLocalBackupLauncher.launch("earthlink_backup_${System.currentTimeMillis()}.zip") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (currentLang == "ar") "تصدير نسخة احتياطية (مشاركة)" else "Export / Share Backup (.zip)", fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // --- DIALOG 1: Available Local Backups List ---
        availableLocalBackups?.let { backupsList ->
            AlertDialog(
                onDismissRequest = { availableLocalBackups = null },
                title = {
                    Text(
                        text = if (currentLang == "ar") "النسخ الاحتياطية المحلية" else "Local Backups",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        backupsList.forEach { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            val pendingCount = context.syncRepository.getPendingOutboxCount()
                                            if (pendingCount > 0) {
                                                pendingOutboxCountForRestore = pendingCount
                                                pendingFileForRestore = file
                                                showUnsyncedOutboxWarningDialog = true
                                            } else {
                                                selectedBackupToRestore = file
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(text = file.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(text = "Size: ${file.length() / 1024} KB", fontSize = 11.sp, color = Color.Gray)
                                        Text(text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified())), fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { availableLocalBackups = null }) {
                        Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                    }
                }
            )
        }

        // --- DIALOG 1.5: Unsynced Outbox Warning Before Restore ---
        if (showUnsyncedOutboxWarningDialog && pendingFileForRestore != null) {
            val fileToRestore = pendingFileForRestore!!
            AlertDialog(
                onDismissRequest = {
                    showUnsyncedOutboxWarningDialog = false
                    pendingFileForRestore = null
                },
                title = {
                    Text(
                        text = if (currentLang == "ar") "تحذير: بيانات غير متزامنة معلقة!" else "Warning: Unsynced Pending Outbox Data!",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = if (currentLang == "ar") {
                            "تنبيه هام: يوجد ($pendingOutboxCountForRestore) عملية/تعديل معلق في صندوق المزامنة (Outbox) لم يتم رفعها للسيرفر بعد.\n\nاسترجاع النسخة الاحتياطية سيستبدل قاعدة البيانات الحالية على هذا الجهاز، مما يؤدي إلى مسح هذه البيانات المعلقة بشكل نهائي قبل المزامنة.\n\nهل ترغب بالاستمرار وفرض الاسترجاع؟"
                        } else {
                            "Critical Warning: There are ($pendingOutboxCountForRestore) unsynced pending changes in your sync outbox queue that have not reached the cloud server yet.\n\nRestoring a backup will overwrite your local database, permanently wiping these unsynced changes before they can be synchronized.\n\nDo you want to proceed and force restore anyway?"
                        },
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnsyncedOutboxWarningDialog = false
                            selectedBackupToRestore = fileToRestore
                            pendingFileForRestore = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(if (currentLang == "ar") "فرض الاسترجاع" else "Force Restore")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showUnsyncedOutboxWarningDialog = false
                            pendingFileForRestore = null
                        }
                    ) {
                        Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                    }
                }
            )
        }

        // --- DIALOG 2: Confirm Restore from Local Backup ---
        selectedBackupToRestore?.let { fileInfo ->
            AlertDialog(
                onDismissRequest = { if (!isRestoringFromLocal) selectedBackupToRestore = null },
                title = {
                    Text(
                        text = if (currentLang == "ar") "تأكيد استرجاع البيانات" else "Confirm Data Restore",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    val dateText = backupRestoreDate ?: java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(fileInfo.lastModified()))
                    val stats = currentDbStats
                    val statsInfo = if (stats != null) {
                        if (currentLang == "ar") {
                            "السجلات الحالية: (${stats.accountCount} حساب، ${stats.ledgerCount} حركة مالية)"
                        } else {
                            "Current records: (${stats.accountCount} accounts, ${stats.ledgerCount} ledger entries)"
                        }
                    } else ""

                    Text(
                        text = if (currentLang == "ar") {
                            "تحذير: استرجاع النسخة الاحتياطية (${fileInfo.name}) المؤرخة في [$dateText] سيستبدل جميع البيانات الحالية على هذا الجهاز بالبيانات المسترجعة.\n\n$statsInfo\n\nسيتم إنشاء نسخة احتياطية تلقائية لبياناتك الحالية قبل الاسترجاع لحمايتها من الضياع."
                        } else {
                            "Warning: Restoring backup (${fileInfo.name}) created on [$dateText] will replace ALL current database records on this device with the restored file.\n\n$statsInfo\n\nAn automatic pre-restore backup of your current database will be saved before restoring so you can undo if needed. Proceed?"
                        },
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                isRestoringFromLocal = true
                                try {
                                    val restored = com.example.core.backup.BackupManager.restoreBackupZip(localContext, fileInfo, force = true)
                                    if (restored) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(localContext, if (currentLang == "ar") "تم استرجاع النسخة بنجاح! جاري إعادة التشغيل..." else "Database restored successfully! Restarting...", Toast.LENGTH_LONG).show()
                                            selectedBackupToRestore = null
                                            availableLocalBackups = null
                                            
                                            // Force restart to reinitialize Room InvalidationTracker and singletons
                                            val pm = localContext.packageManager
                                            val intent = pm.getLaunchIntentForPackage(localContext.packageName)
                                            if (intent != null) {
                                                val mainIntent = android.content.Intent.makeRestartActivityTask(intent.component)
                                                localContext.startActivity(mainIntent)
                                                Runtime.getRuntime().exit(0)
                                            }
                                        }
                                    } else {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            Toast.makeText(localContext, if (currentLang == "ar") "فشل استرجاع النسخة" else "Database restore failed", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(localContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    isRestoringFromLocal = false
                                }
                            }
                        },
                        enabled = !isRestoringFromLocal,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        if (isRestoringFromLocal) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (currentLang == "ar") "جاري الاسترجاع..." else "Restoring...")
                        } else {
                            Text(if (currentLang == "ar") "تأكيد واسترجاع" else "Confirm & Restore")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { selectedBackupToRestore = null },
                        enabled = !isRestoringFromLocal
                    ) {
                        Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                    }
                }
            )
        }

        // --- LANGUAGE ---
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (currentLang == "ar") "اللغة" else "Language",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf("ar" to "العربية", "en" to "English").forEach { (code, label) ->
                        val isSelected = currentLang == code
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                .clickable { prefs.setLanguage(code) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // --- DEV MODE (DEBUG BUILD ONLY) ---
        if (AppBuildConfig.DEBUG) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (currentLang == "ar") "وضع المطور" else "Developer Mode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (currentLang == "ar") "وضع التجريب (Demo)" else "Demo Mode")
                            Switch(checked = isDemoMode, onCheckedChange = { prefs.setDemoMode(it) })
                        }
                        var showConfirmDelete by rememberSaveable { mutableStateOf(false) }
                        var unsyncedWarningClearDataDialog by rememberSaveable { mutableStateOf(false) }
                        var pendingClearDataCountState by rememberSaveable { mutableStateOf(0) }

                        if (showConfirmDelete) {
                            AlertDialog(
                                onDismissRequest = { showConfirmDelete = false },
                                title = { Text(if (currentLang == "ar") "حذف البيانات نهائياً؟" else "Delete Data Permanently?") },
                                text = { Text(if (currentLang == "ar") "سيتم حذف جميع البيانات نهائياً من هذا الجهاز ومن السحابة (Firestore). لا يمكن التراجع عن هذا الإجراء أبداً." else "All data will be permanently deleted from this device AND the cloud (Firestore). This action cannot be undone.") },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showConfirmDelete = false
                                            dashboardViewModel.clearLocalData(
                                                force = false,
                                                onSuccess = {},
                                                onError = { err ->
                                                    if (err.startsWith("UNSYNCED_CHANGES:")) {
                                                        pendingClearDataCountState = err.substringAfter("UNSYNCED_CHANGES:").toIntOrNull() ?: 1
                                                        unsyncedWarningClearDataDialog = true
                                                    }
                                                }
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text(if (currentLang == "ar") "حذف نهائي" else "Permanent Delete")
                                    }
                                },
                                dismissButton = { TextButton(onClick = { showConfirmDelete = false }) { Text(if (currentLang == "ar") "إلغاء" else "Cancel") } }
                            )
                        }

                        if (unsyncedWarningClearDataDialog) {
                            AlertDialog(
                                onDismissRequest = { unsyncedWarningClearDataDialog = false },
                                title = { Text(if (currentLang == "ar") "تنبيه: تغييرات غير مزامنة!" else "Warning: Unsynced Changes!") },
                                text = {
                                    Text(
                                        if (currentLang == "ar")
                                            "يوجد $pendingClearDataCountState عملية لم يتم رفعها للسيرفر بعد. مسح البيانات الآن سيؤدي لحذف هذه البيانات نهائياً!"
                                        else
                                            "There are $pendingClearDataCountState unsynced operations pending. Clearing data now will permanently delete these pending changes!"
                                    )
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            unsyncedWarningClearDataDialog = false
                                            dashboardViewModel.clearLocalData(
                                                force = true,
                                                onSuccess = {},
                                                onError = {}
                                            )
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text(if (currentLang == "ar") "حذف بالقوة (مسح البيانات)" else "Force Clear (Delete)")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { unsyncedWarningClearDataDialog = false }) {
                                        Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                                    }
                                }
                            )
                        }
                        TextButton(onClick = { showConfirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (currentLang == "ar") "مسح جميع البيانات المحلية" else "Clear All Local Data", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2E))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (currentLang == "ar") "بيانات الموزع" else "Affiliate User Details",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                Text(
                    text = if (currentLang == "ar") "اسم المستخدم: $username" else "Logged Username: $username",
                    color = Color.White,
                    fontSize = 13.sp
                )
                Text(
                    text = if (currentLang == "ar") "خادم البوابة: rapi.earthlink.iq" else "Base Server API: rapi.earthlink.iq",
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp
                )
                
                Button(
                    onClick = onNavigateToImport,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF))
                ) {
                    Text(if (currentLang == "ar") "استيراد بيانات uTower" else "Import uTower Data")
                }
                Text(
                    text = if (currentLang == "ar") "تشفير البيانات الحساسة: نشط (AES)" else "Secure Credential Encryption: Active (AES/XOR)",
                    color = Color(0xFF30D158),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2E))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (currentLang == "ar") "كلمة مرور الصندوق (تجديد/تعبئة)" else "Deposit/Refill Password",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                
                var depositPassText by rememberSaveable { mutableStateOf(prefs.getDepositPassword()) }
                var isPasswordVisible by rememberSaveable { mutableStateOf(false) }
                
                OutlinedTextField(
                    value = depositPassText,
                    onValueChange = {
                        depositPassText = it
                        prefs.saveDepositPassword(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (currentLang == "ar") "كلمة مرور الصندوق" else "Deposit Password", color = Color(0xFF8E8E93)) },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                                tint = Color(0xFF8E8E93)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF007AFF),
                        unfocusedBorderColor = Color(0xFF2C2C2E)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); Toast.makeText(context, if (currentLang == "ar") "تم حفظ كلمة مرور الصندوق" else "Deposit password saved", Toast.LENGTH_SHORT).show() }),
                    singleLine = true
                )
                
                Text(
                    text = if (currentLang == "ar") {
                        "تستخدم لتجديد رصيد المشتركين دون الحاجة لكتابتها في كل مرة."
                    } else {
                        "Used automatically for subscriber renewals without typing it each time."
                    },
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2E))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (currentLang == "ar") "بيانات بوابة إيرثلنك (ISP Admin)" else "Earthlink Reseller (ISP Admin)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                
                var ispAdminUserText by rememberSaveable { mutableStateOf(prefs.getIspAdminUsername() ?: "") }
                var ispAdminPassText by rememberSaveable { mutableStateOf(prefs.getIspAdminPassword() ?: "") }
                var isIspPasswordVisible by rememberSaveable { mutableStateOf(false) }

                val performSaveAndNavigate = {
                    focusManager.clearFocus()
                    if (ispAdminUserText.isBlank() || ispAdminPassText.isBlank()) {
                        Toast.makeText(
                            localContext,
                            if (currentLang == "ar") "يرجى إدخال اسم المستخدم وكلمة المرور للوكيل" else "Please enter ISP Admin username and password",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        authViewModel.saveIspAdminCredentials(ispAdminUserText, ispAdminPassText)
                        Toast.makeText(
                            localContext,
                            if (currentLang == "ar") "تم حفظ البيانات والتوجه لقائمة المشتركين" else "Saved! Loading subscribers...",
                            Toast.LENGTH_SHORT
                        ).show()
                        dashboardViewModel.loadDashboardData()
                        onNavigateToSubscribers?.invoke()
                    }
                }
                
                OutlinedTextField(
                    value = ispAdminUserText,
                    onValueChange = {
                        ispAdminUserText = it
                        prefs.saveIspAdminUsername(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (currentLang == "ar") "اسم المستخدم للوكيل" else "ISP Admin Username", color = Color(0xFF8E8E93)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF007AFF),
                        unfocusedBorderColor = Color(0xFF2C2C2E)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
                )

                OutlinedTextField(
                    value = ispAdminPassText,
                    onValueChange = {
                        ispAdminPassText = it
                        prefs.saveIspAdminPassword(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (currentLang == "ar") "كلمة المرور للوكيل" else "ISP Admin Password", color = Color(0xFF8E8E93)) },
                    visualTransformation = if (isIspPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isIspPasswordVisible = !isIspPasswordVisible }) {
                            Icon(
                                imageVector = if (isIspPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (isIspPasswordVisible) "Hide password" else "Show password",
                                tint = Color(0xFF8E8E93)
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF007AFF),
                        unfocusedBorderColor = Color(0xFF2C2C2E)
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { performSaveAndNavigate() }),
                    singleLine = true
                )
                
                Text(
                    text = if (currentLang == "ar") {
                        "تُسجل هذه البيانات بشكل آمن لتبادل وإثبات التراخيص والعمليات مع مزود خدمة إيرثلنك تلقائياً."
                    } else {
                        "These credentials are saved securely to authenticate background API sync processes with the Earthlink reseller gateway."
                    },
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp
                )

                Button(
                    onClick = { performSaveAndNavigate() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (currentLang == "ar") "حفظ الحساب والتوجه للمشتركين" else "Save & Go to Subscribers",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = {
                        val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                        val resultCode = availability.isGooglePlayServicesAvailable(localContext)
                        if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                            if (availability.isUserResolvableError(resultCode)) {
                                (localContext as? android.app.Activity)?.let { act ->
                                    availability.getErrorDialog(act, resultCode, 9000)?.show()
                                }
                            } else {
                                Toast.makeText(
                                    localContext,
                                    if (currentLang == "ar") "خدمات Google Play غير متوفرة على هذا الجهاز" else "Google Play Services unavailable on this device",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return@Button
                        }
                        try {
                            val intent = googleSignInClient.signInIntent
                            googleSignInLauncher.launch(intent)
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                            Toast.makeText(
                                localContext,
                                if (currentLang == "ar") "تعذر بدء تسجيل الدخول بواسطة Google: ${e.message}" else "Google Sign-In unavailable: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Google Logo",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (currentLang == "ar") "ربط بحساب Google" else "Link with Google Account",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2E))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (currentLang == "ar") "نطاق الاتصال والتجريب" else "App Mode & Connectivity",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                if (AppBuildConfig.DEBUG) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    val isDemoEnabled by prefs.demoModeFlow.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (currentLang == "ar") "وضع التجريب المحلي (المحاكي)" else "Offline Demo Mode",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (currentLang == "ar") {
                                    "يتيح استخدام التطبيق ببيانات تجريبية محلية دقيقة عند عدم الاتصال بشبكة إيرثلنك الخاصة بالموزعين."
                                } else {
                                    "Bypasses real rapi.earthlink.iq calls with rich mock data when not on Iraq's Earthlink network range."
                                },
                                color = Color(0xFF8E8E93),
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = isDemoEnabled,
                            onCheckedChange = { checked ->
                                prefs.setDemoMode(checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF30D158) // iOS green switch track
                            )
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2E))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (currentLang == "ar") "تخصيص الواجهة الرئيسية" else "Dashboard Customization",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                
                var showActive by rememberSaveable { mutableStateOf(prefs.getShowActive()) }
                var showExpired by rememberSaveable { mutableStateOf(prefs.getShowExpired()) }
                var maxItems by rememberSaveable { mutableStateOf(prefs.getMaxDashboardItems()) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (currentLang == "ar") "عرض المشتركين النشطين" else "Show Active Subscribers",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (currentLang == "ar") "تبديل لعرض قائمة المستخدمين الفعالين بالواحدة الرئيسية" else "Toggle to display live active users list on your home dashboard",
                            color = Color(0xFF8E8E93),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = showActive,
                        onCheckedChange = {
                            prefs.setShowActive(it)
                            showActive = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF30D158)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (currentLang == "ar") "عرض الاشتراكات المنتهية" else "Show Recently Expired Users",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (currentLang == "ar") "تبديل لعرض المشتركين المنتهية صلاحيتهم مؤخراً" else "Toggle to view recently expired users on your home dashboard",
                            color = Color(0xFF8E8E93),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = showExpired,
                        onCheckedChange = {
                            prefs.setShowExpired(it)
                            showExpired = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF30D158)
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (currentLang == "ar") "الحد الأقصى للمشتركين" else "Max Dashboard Subscribers",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (currentLang == "ar") "الحد الحالي المعتمد: $maxItems مشترك" else "Currently configured limit: $maxItems subscribers",
                            color = Color(0xFF8E8E93),
                            fontSize = 11.sp
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = {
                            if (maxItems > 5) {
                                val newVal = maxItems - 5
                                prefs.setMaxDashboardItems(newVal)
                                maxItems = newVal
                            }
                        }) {
                            Icon(imageVector = Icons.Default.RemoveCircleOutline, contentDescription = "Decrease", tint = Color.White)
                        }
                        Text(text = "$maxItems", fontWeight = FontWeight.Bold, color = Color.White)
                        IconButton(onClick = {
                            if (maxItems < 100) {
                                val newVal = maxItems + 5
                                prefs.setMaxDashboardItems(newVal)
                                maxItems = newVal
                            }
                        }) {
                            Icon(imageVector = Icons.Default.AddCircleOutline, contentDescription = "Increase", tint = Color.White)
                        }
                    }
                }
            }
        }

        // --- CUSTOM SUBSCRIPTION PRICING CARD TRIGGER ---
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPricingDialog = true },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
            border = BorderStroke(1.dp, Color(0xFF2C2C2E))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color(0xFF30D158),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (currentLang == "ar") "أسعار بيع الاشتراكات" else "Subscription Selling Prices",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (currentLang == "ar") "تعديل أسعار البيع لتسهيل الحسابات وحساب الأرباح" else "Edit selling prices to simplify accounting and profit",
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E93)
                        )
                    }
                }
            }
        }

        if (showPricingDialog) {
            Dialog(
                onDismissRequest = { showPricingDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.9f)
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    val targetPackages = listOf("economy", "plus", "standard", "turbo", "more", "business")
                    val fallbackPackages = listOf(
                        Triple("economy", if (currentLang == "ar") "اقتصادي (Economy)" else "Economy", 2),
                        Triple("plus", if (currentLang == "ar") "بلس (Plus)" else "Plus", 4),
                        Triple("standard", if (currentLang == "ar") "اعتيادي (Standard)" else "Standard", 3),
                        Triple("turbo", if (currentLang == "ar") "توربو (Turbo)" else "Turbo", 5),
                        Triple("more", if (currentLang == "ar") "مور (More)" else "More", 6),
                        Triple("business", if (currentLang == "ar") "بزنس (Business)" else "Business", 7)
                    )
                    
                    var packagesList by remember { mutableStateOf<List<Triple<String, String, Int>>>(fallbackPackages) }
                    val apiCosts = remember { mutableStateMapOf<String, Double>() }
                    val apiLoading = remember { mutableStateMapOf<String, Boolean>() }
                    val localInputs = remember { mutableStateMapOf<String, String>() }
                    
                    LaunchedEffect(Unit) {
                        try {
                            val pkgs = authViewModel.gateway.getPackages()
                            if (pkgs.isNotEmpty()) {
                                packagesList = pkgs.filter { pkg -> 
                                    pkg.accountName.trim().lowercase() in targetPackages 
                                }.map { pkg ->
                                    val keyName = pkg.accountName.trim().lowercase()
                                    val label = if (currentLang == "ar") {
                                        when (keyName) {
                                            "economy" -> "اقتصادي (Economy)"
                                            "standard" -> "اعتيادي (Standard)"
                                            "plus" -> "بلس (Plus)"
                                            "turbo" -> "توربو (Turbo)"
                                            "more" -> "مور (More)"
                                            "business" -> "بزنس (Business)"
                                            else -> pkg.accountName
                                        }
                                    } else {
                                        pkg.accountName
                                    }
                                    Triple(keyName, label, pkg.accountIndex)
                                }
                            }
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;}
                        
                        packagesList.forEach { (key, _, index) ->
                            val currentCustom = prefs.getPackageSellingPrice(key, 0.0)
                            localInputs[key] = com.example.core.ledger.MoneyParser.formatIqdToUiString(currentCustom)
                            apiLoading[key] = true
                        }
                        
                        val scope = this
                        packagesList.forEach { (key, _, index) ->
                            scope.launch {
                                try {
                                    val cost = authViewModel.gateway.getAccountCost(index)
                                    apiCosts[key] = cost
                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                    apiCosts[key] = 0.0
                                } finally {
                                    apiLoading[key] = false
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (currentLang == "ar") "أسعار بيع الاشتراكات" else "Subscription Selling Prices",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = if (currentLang == "ar") {
                                "اضبط أسعار بيع الاشتراكات لتسهيل المحاسبة والربح التلقائي في التطبيق."
                            } else {
                                "Configure retail selling prices for simplified accounting and automatic profit calculations in the app."
                            },
                            color = Color(0xFF8E8E93),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        
                        packagesList.forEach { (key, label, _) ->
                            val sellingPriceInput = localInputs[key] ?: ""
                            
                            val apiCost = apiCosts[key] ?: 0.0
                            val customSellingPrice = com.example.core.ledger.MoneyParser.parseSubscriptionPriceIqd(sellingPriceInput)?.toDouble() ?: 0.0
                            val profit = customSellingPrice - apiCost
                            
                            val apiText = if (apiLoading[key] == true) {
                                if (currentLang == "ar") "جاري التحميل..." else "Loading..."
                            } else if (apiCost > 0.0) {
                                com.example.core.ledger.MoneyParser.formatIqdForDisplay(apiCost.toDouble()) + (if (currentLang == "ar") " د.ع" else " IQD")
                            } else {
                                if (currentLang == "ar") "غير متوفر" else "N/A"
                            }
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2C2C2E).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1.2f)) {
                                    Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (currentLang == "ar") "تكلفة الاشتراك: " else "Subscription Cost: ",
                                            color = Color(0xFF8E8E93),
                                            fontSize = 11.sp
                                        )
                                        if (apiLoading[key] == true) {
                                            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.dp, color = Color(0xFF007AFF))
                                        } else {
                                            Text(
                                                text = apiText,
                                                color = if (apiCost > 0.0) Color(0xFFE5E5EA) else Color(0xFFFF453A),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    if (customSellingPrice > 0.0 && apiCost > 0.0) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = if (currentLang == "ar") {
                                                "الربح المتوقع: +${com.example.core.ledger.MoneyParser.formatIqdForDisplay(profit.toDouble())} د.ع"
                                            } else {
                                                "Estimated Profit: +${com.example.core.ledger.MoneyParser.formatIqdForDisplay(profit.toDouble())} IQD"
                                            },
                                            color = if (profit >= 0.0) Color(0xFF30D158) else Color(0xFFFF453A),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))
                                
                                OutlinedTextField(
                                    value = sellingPriceInput,
                                    onValueChange = { input ->
                                        val cleanInput = input.filter { it.isDigit() }
                                        localInputs[key] = cleanInput
                                    },
                                    modifier = Modifier.width(95.dp),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = FontWeight.Bold),
                                    suffix = {
                                        Text(
                                            text = if (currentLang == "ar") "ألف" else "k",
                                            color = Color(0xFF8E8E93),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    placeholder = {
                                        Text(
                                            text = com.example.core.ledger.MoneyParser.formatIqdToUiString(apiCost).ifEmpty { "0" },
                                            color = Color.White.copy(alpha = 0.2f),
                                            fontSize = 11.sp,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF007AFF),
                                        unfocusedBorderColor = Color(0xFF3A3A3C),
                                        focusedContainerColor = Color(0xFF1C1C1E),
                                        unfocusedContainerColor = Color(0xFF1C1C1E)
                                    ),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number
                                    )
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showPricingDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C)),
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                            }
                            
                            Button(
                                onClick = {
                                    localInputs.forEach { (k, v) ->
                                        val priceVal = com.example.core.ledger.MoneyParser.parseSubscriptionPriceIqd(v)?.toDouble() ?: 0.0
                                        if (priceVal >= 0.0) {
                                            prefs.setPackageSellingPrice(k, priceVal)
                                        }
                                    }
                                    showPricingDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
                                modifier = Modifier.weight(3.5f).height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "حفظ" else "Save",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- LEGAL & PRIVACY ---
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

            if (showLogoutConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showLogoutConfirmDialog = false },
                    title = { Text(if (currentLang == "ar") "تأكيد تسجيل الخروج" else "Confirm Sign Out") },
                    text = { Text(if (currentLang == "ar") "هل أنت تأكد من تسجيل الخروج؟ سيتم إغلاق الجلسة ومسح قاعدة البيانات المحلية من هذا الجهاز." else "Are you sure you want to sign out? Your session will end and local database tables will be cleared.") },
                    confirmButton = {
                        Button(
                            onClick = {
                                showLogoutConfirmDialog = false
                                authViewModel.logout(
                                    force = false,
                                    onSuccess = {
                                        prefs.clearAll()
                                        onLogout()
                                    },
                                    onError = { err ->
                                        if (err.startsWith("UNSYNCED_CHANGES:")) {
                                            pendingCountState = err.substringAfter("UNSYNCED_CHANGES:").toIntOrNull() ?: 1
                                            unsyncedWarningDialog = true
                                        } else {
                                            prefs.clearAll()
                                            onLogout()
                                        }
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
                        ) {
                            Text(if (currentLang == "ar") "تسجيل الخروج" else "Sign Out")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutConfirmDialog = false }) {
                            Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                        }
                    }
                )
            }

            if (unsyncedWarningDialog) {
                AlertDialog(
                    onDismissRequest = { unsyncedWarningDialog = false },
                    title = { Text(if (currentLang == "ar") "تنبيه: تغييرات غير مزامنة!" else "Warning: Unsynced Changes!") },
                    text = { 
                        Text(
                            if (currentLang == "ar") 
                                "يوجد $pendingCountState عملية لم يتم رفعها للسيرفر بعد. تسجيل الخروج الآن سيؤدي لحذف هذه البيانات نهائياً من هذا الجهاز!" 
                            else 
                                "There are $pendingCountState unsynced operations pending. Signing out now will permanently delete these local changes!"
                        ) 
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                unsyncedWarningDialog = false
                                authViewModel.logout(
                                    force = true,
                                    onSuccess = {
                                        prefs.clearAll()
                                        onLogout()
                                    },
                                    onError = {
                                        prefs.clearAll()
                                        onLogout()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
                        ) {
                            Text(if (currentLang == "ar") "خروج بالقوة (حذف البيانات)" else "Force Sign Out (Delete)")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { unsyncedWarningDialog = false }) {
                            Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                        }
                    }
                )
            }

            Text(
                text = if (currentLang == "ar") "القانونية والخصوصية" else "Legal & Privacy",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = { uriHandler.openUri("https://docs.google.com/document/d/1e7gm4KkC1jjhwlm0YPQMVmwnJXP6eeWeKKJTWlzKg7w/edit?usp=sharing") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (currentLang == "ar") "سياسة الخصوصية" else "Privacy Policy", color = MaterialTheme.colorScheme.onSurface)
                            Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))



        Button(
            onClick = {
                showLogoutConfirmDialog = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)), // iOS Destructive Red
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (currentLang == "ar") "تسجيل الخروج الآمن" else "Secure logout",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}
