package com.example.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EarthlinkApp
import com.example.core.backup.BackupManager
import com.example.core.backup.LocalAutoBackupWorker
import com.example.core.ledger.MoneyParser
import com.example.core.util.AppBuildConfig
import com.example.domain.repository.SyncStatusState
import com.example.domain.repository.SyncProgress
import com.example.domain.repository.SyncPhase
import com.example.ui.viewmodels.AuthViewModel
import com.example.ui.viewmodels.DashboardViewModel
import com.example.ui.viewmodels.SyncStatusViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    authViewModel: AuthViewModel,
    dashboardViewModel: DashboardViewModel,
    syncViewModel: SyncStatusViewModel? = null,
    onLogout: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToSubscribers: (() -> Unit)? = null
) {
    val app = LocalContext.current.applicationContext as EarthlinkApp
    val localContext = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()

    val finalSyncViewModel: SyncStatusViewModel = syncViewModel ?: remember {
        SyncStatusViewModel(
            syncRepo = app.syncRepository,
            audit = app.auditRepository,
            prefs = app.preferenceManager
        )
    }

    LaunchedEffect(Unit) {
        finalSyncViewModel.refreshPendingCount()
    }

    val syncState by finalSyncViewModel.syncState.collectAsStateWithLifecycle(SyncStatusState.IDLE)
    val syncProgress by finalSyncViewModel.syncProgress.collectAsStateWithLifecycle()
    val lastSyncTime by finalSyncViewModel.lastSyncTime.collectAsStateWithLifecycle()
    val isSyncingProgress by finalSyncViewModel.isSyncingProgress.collectAsStateWithLifecycle()
    val pendingCount by finalSyncViewModel.pendingCount.collectAsStateWithLifecycle()
    val failedCount by finalSyncViewModel.failedCount.collectAsStateWithLifecycle()

    val username by authViewModel.username.collectAsStateWithLifecycle()
    val prefs = authViewModel.prefs
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()

    // Dialog States
    var showPricingDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var unsyncedWarningDialog by rememberSaveable { mutableStateOf(false) }
    var pendingCountState by rememberSaveable { mutableStateOf(0) }

    // Backup & Restore States
    val isLocalBackupEnabled by prefs.localBackupEnabledFlow.collectAsStateWithLifecycle()
    val lastLocalBackupTime by prefs.localLastBackupTimeFlow.collectAsStateWithLifecycle()

    var isPerformingLocalBackup by rememberSaveable { mutableStateOf(false) }
    var isFetchingLocalBackups by rememberSaveable { mutableStateOf(false) }
    var availableLocalBackups by remember { mutableStateOf<List<File>?>(null) }
    var selectedBackupToRestore by remember { mutableStateOf<File?>(null) }
    var isRestoringFromLocal by rememberSaveable { mutableStateOf(false) }
    var pendingOutboxCountForRestore by rememberSaveable { mutableStateOf(0) }
    var pendingFileForRestore by remember { mutableStateOf<File?>(null) }
    var showUnsyncedOutboxWarningDialog by rememberSaveable { mutableStateOf(false) }
    var backupRestoreDate by rememberSaveable { mutableStateOf<String?>(null) }
    var currentDbStats by remember { mutableStateOf<BackupManager.DatabaseStats?>(null) }

    // Password Protected Backup
    var showBackupPasswordOptionsDialog by rememberSaveable { mutableStateOf(false) }
    var backupPasswordInput by rememberSaveable { mutableStateOf("") }
    var backupPasswordError by rememberSaveable { mutableStateOf<String?>(null) }
    var isBackupOperationExport by rememberSaveable { mutableStateOf(false) }
    var selectedEncryptionModePassword by rememberSaveable { mutableStateOf(false) }
    var tempBackupPassword by rememberSaveable { mutableStateOf<String?>(null) }

    var showRestorePasswordPromptDialog by rememberSaveable { mutableStateOf(false) }
    var restorePasswordInput by rememberSaveable { mutableStateOf("") }
    var restorePasswordError by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreTargetFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(selectedBackupToRestore) {
        val file = selectedBackupToRestore
        if (file != null) {
            backupRestoreDate = BackupManager.getBackupFormattedDate(file)
            currentDbStats = BackupManager.getCurrentDatabaseStats(localContext)
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
                val success = BackupManager.exportBackupToUri(localContext, uri, tempBackupPassword)
                if (success) {
                    Toast.makeText(localContext, if (currentLang == "ar") "تم تصدير النسخة الاحتياطية بنجاح" else "Backup exported successfully!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(localContext, if (currentLang == "ar") "فشل تصدير النسخة الاحتياطية" else "Backup export failed", Toast.LENGTH_LONG).show()
                }
                tempBackupPassword = null
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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val statusCode = (e as? ApiException)?.statusCode ?: -1
            val isDeveloperError = statusCode == 10 || statusCode == 12500 || statusCode == 10200 || e.message?.contains("10:") == true
            val errMsg = if (isDeveloperError) {
                if (currentLang == "ar") "خطأ تهيئة Google (رمز 10/12500). تأكد من إضافة بصمة SHA-1 في Firebase Console." else "Google Config Error (Code 10/12500)."
            } else {
                e.message ?: if (currentLang == "ar") "فشل تسجيل الدخول بواسطة Google" else "Google Sign-In failed"
            }
            Toast.makeText(localContext, errMsg, Toast.LENGTH_LONG).show()
        }
    }

    val isDemoMode by prefs.demoModeFlow.collectAsStateWithLifecycle()

    val currentUser = remember(syncState) {
        try {
            com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        } catch (_: Throwable) {
            null
        }
    }
    val isGoogleLinked = (currentUser != null && !currentUser.uid.isNullOrEmpty()) && syncState != SyncStatusState.AUTH_REQUIRED
    val googleUserEmail = currentUser?.email?.ifBlank { null } ?: (if (isGoogleLinked) prefs.getUsername() else null)

    val layoutDir = if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D12))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Clean Title (No bloated subtitle, no redundant gear icon)
                Text(
                    text = if (currentLang == "ar") "الإعدادات" else "Settings",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )

                // 2. ISP GATEWAY CREDENTIALS (بوابة إيرثلنك)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionTitle(text = if (currentLang == "ar") "بوابة إيرثلنك" else "EARTHLINK GATEWAY")
                    
                    SettingsCardGroup {
                        AccountAndIspSection(
                            username = username,
                            prefs = prefs,
                            currentLang = currentLang,
                            onSaveIsp = { u, p ->
                                authViewModel.saveIspAdminCredentials(u, p)
                                dashboardViewModel.loadDashboardData()
                                onNavigateToSubscribers?.invoke()
                            }
                        )
                    }
                }

                // 3. CLOUD SYNC & DATA MANAGEMENT (المزامنة والبيانات)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionTitle(text = if (currentLang == "ar") "المزامنة والبيانات" else "SYNC & DATA")
                    
                    SettingsCardGroup {
                        SyncAndBackupSection(
                            currentLang = currentLang,
                            syncState = syncState,
                            syncProgress = syncProgress,
                            lastSyncTime = lastSyncTime,
                            isSyncing = isSyncingProgress || syncState == SyncStatusState.SYNCING || syncProgress.isSyncing,
                            pendingCount = pendingCount,
                            failedCount = failedCount,
                            isGoogleLinked = isGoogleLinked,
                            googleUserEmail = googleUserEmail,
                            onLinkGoogle = {
                                val availability = GoogleApiAvailability.getInstance()
                                val resultCode = availability.isGooglePlayServicesAvailable(localContext)
                                if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                                    if (availability.isUserResolvableError(resultCode)) {
                                        (localContext as? android.app.Activity)?.let { act ->
                                            availability.getErrorDialog(act, resultCode, 9000)?.show()
                                        }
                                    } else {
                                        Toast.makeText(localContext, if (currentLang == "ar") "خدمات Google Play غير متوفرة" else "Google Play Services unavailable", Toast.LENGTH_LONG).show()
                                    }
                                    return@SyncAndBackupSection
                                }
                                try {
                                    val intent = googleSignInClient.signInIntent
                                    googleSignInLauncher.launch(intent)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Toast.makeText(localContext, "Google Sign-In Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            onTriggerSync = { finalSyncViewModel.triggeredSync() },
                            onRetryFailed = { finalSyncViewModel.retryFailedItems() },
                            isAutoBackupEnabled = isLocalBackupEnabled,
                            onToggleAutoBackup = { enabled ->
                                prefs.setLocalBackupEnabled(enabled)
                                LocalAutoBackupWorker.schedule(localContext, enabled)
                            },
                            lastLocalBackupTime = lastLocalBackupTime,
                            isPerformingBackup = isPerformingLocalBackup,
                            onStartManualBackup = {
                                isBackupOperationExport = false
                                backupPasswordInput = ""
                                backupPasswordError = null
                                selectedEncryptionModePassword = false
                                showBackupPasswordOptionsDialog = true
                            },
                            isFetchingBackups = isFetchingLocalBackups,
                            onListBackups = {
                                coroutineScope.launch {
                                    isFetchingLocalBackups = true
                                    try {
                                        val list = BackupManager.listDailyBackups(localContext)
                                        if (list.isEmpty()) {
                                            Toast.makeText(localContext, if (currentLang == "ar") "لا توجد نسخ احتياطية سابقة" else "No backups found", Toast.LENGTH_SHORT).show()
                                        } else {
                                            availableLocalBackups = list
                                        }
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        Toast.makeText(localContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isFetchingLocalBackups = false
                                    }
                                }
                            },
                            onExportZip = {
                                isBackupOperationExport = true
                                backupPasswordInput = ""
                                backupPasswordError = null
                                selectedEncryptionModePassword = false
                                showBackupPasswordOptionsDialog = true
                            },
                            onImportUtower = onNavigateToImport
                        )
                    }
                }

                // 4. DISPLAY & PRICING (التخصيص والأسعار)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionTitle(text = if (currentLang == "ar") "التخصيص والأسعار" else "PREFERENCES & PRICING")
                    
                    SettingsCardGroup {
                        DisplayAndPricingSection(
                            prefs = prefs,
                            currentLang = currentLang,
                            onOpenPricingDialog = { showPricingDialog = true }
                        )
                    }
                }

                // 5. GENERAL & SYSTEM (عام)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSectionTitle(text = if (currentLang == "ar") "عام" else "GENERAL")
                    
                    SettingsCardGroup {
                        GeneralSection(
                            currentLang = currentLang,
                            onSelectLang = { prefs.setLanguage(it) }
                        )
                    }
                }

                // 6. DEVELOPER MODE (DEBUG BUILD ONLY)
                if (AppBuildConfig.DEBUG) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSectionTitle(text = if (currentLang == "ar") "أدوات المطور (DEBUG)" else "DEVELOPER")
                        
                        SettingsCardGroup {
                            DeveloperSection(
                                isDemoMode = isDemoMode,
                                onToggleDemo = { prefs.setDemoMode(it) },
                                dashboardViewModel = dashboardViewModel,
                                currentLang = currentLang
                            )
                        }
                    }
                }

                // 7. SIGN OUT (Apple-Style Destructive Row)
                SettingsCardGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showLogoutConfirmDialog = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = Color(0xFFFF453A),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentLang == "ar") "تسجيل الخروج" else "Sign Out",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = Color(0xFFFF453A)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // --- ALL DIALOGS MANAGED SAFELY ---

    // 1. Available Local Backups Dialog
    availableLocalBackups?.let { backupsList ->
        AlertDialog(
            onDismissRequest = { availableLocalBackups = null },
            title = {
                Text(
                    text = if (currentLang == "ar") "النسخ الاحتياطية المحلية" else "Local Backups",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
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
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF171E29),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch {
                                        val pendingCount = app.syncRepository.getPendingOutboxCount()
                                        if (pendingCount > 0) {
                                            pendingOutboxCountForRestore = pendingCount
                                            pendingFileForRestore = file
                                            showUnsyncedOutboxWarningDialog = true
                                        } else {
                                            if (BackupManager.isBackupPasswordProtected(file)) {
                                                restoreTargetFile = file
                                                restorePasswordInput = ""
                                                restorePasswordError = null
                                                showRestorePasswordPromptDialog = true
                                            } else {
                                                selectedBackupToRestore = file
                                            }
                                        }
                                    }
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(text = file.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(text = "${file.length() / 1024} KB", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                                    Text(
                                        text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(file.lastModified())),
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.5f)
                                    )
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

    // 2. Unsynced Outbox Warning Before Restore
    if (showUnsyncedOutboxWarningDialog && pendingFileForRestore != null) {
        val fileToRestore = pendingFileForRestore!!
        AlertDialog(
            onDismissRequest = {
                showUnsyncedOutboxWarningDialog = false
                pendingFileForRestore = null
            },
            title = {
                Text(
                    text = if (currentLang == "ar") "تحذير: بيانات غير متزامنة معلقة!" else "Warning: Unsynced Data Pending!",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444),
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = if (currentLang == "ar") {
                        "يوجد ($pendingOutboxCountForRestore) عملية معلقة في صندوق المزامنة لم يتم رفعها للسيرفر بعد.\nاسترجاع النسخة الاحتياطية سيستبدل قاعدة البيانات الحالية مما يؤدي لمسح هذه البيانات.\nهل تريد فرض الاسترجاع؟"
                    } else {
                        "There are ($pendingOutboxCountForRestore) unsynced operations pending. Restoring a backup will overwrite the database and erase these pending changes. Proceed?"
                    },
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnsyncedOutboxWarningDialog = false
                        if (BackupManager.isBackupPasswordProtected(fileToRestore)) {
                            restoreTargetFile = fileToRestore
                            restorePasswordInput = ""
                            restorePasswordError = null
                            showRestorePasswordPromptDialog = true
                        } else {
                            selectedBackupToRestore = fileToRestore
                        }
                        pendingFileForRestore = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(if (currentLang == "ar") "فرض الاسترجاع" else "Force Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsyncedOutboxWarningDialog = false
                    pendingFileForRestore = null
                }) {
                    Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                }
            }
        )
    }

    // 3. Confirm Restore Dialog
    selectedBackupToRestore?.let { fileInfo ->
        AlertDialog(
            onDismissRequest = { if (!isRestoringFromLocal) selectedBackupToRestore = null },
            title = {
                Text(
                    text = if (currentLang == "ar") "تأكيد استرجاع البيانات" else "Confirm Restore",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                val dateText = backupRestoreDate ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(fileInfo.lastModified()))
                val stats = currentDbStats
                val statsInfo = if (stats != null) {
                    if (currentLang == "ar") "السجلات الحالية: (${stats.accountCount} مشترك، ${stats.ledgerCount} حركة مالية)"
                    else "Current: (${stats.accountCount} accounts, ${stats.ledgerCount} entries)"
                } else ""

                Text(
                    text = if (currentLang == "ar") {
                        "استرجاع النسخة (${fileInfo.name}) المؤرخة في [$dateText] سيستبدل جميع السجلات الحالية.\n\n$statsInfo\n\n(سيتم أخذ نسخة أمان احتياطية تلقائياً قبل الاسترجاع)."
                    } else {
                        "Restoring (${fileInfo.name}) from [$dateText] will replace all current records.\n\n$statsInfo"
                    },
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            isRestoringFromLocal = true
                            try {
                                val restored = BackupManager.restoreBackupZip(localContext, fileInfo, force = true)
                                withContext(Dispatchers.Main) {
                                    if (restored) {
                                        Toast.makeText(localContext, if (currentLang == "ar") "تم استرجاع النسخة بنجاح! جاري إعادة التشغيل..." else "Restored! Restarting...", Toast.LENGTH_LONG).show()
                                        selectedBackupToRestore = null
                                        availableLocalBackups = null
                                        val pm = localContext.packageManager
                                        val intent = pm.getLaunchIntentForPackage(localContext.packageName)
                                        if (intent != null) {
                                            val mainIntent = Intent.makeRestartActivityTask(intent.component)
                                            localContext.startActivity(mainIntent)
                                            Runtime.getRuntime().exit(0)
                                        }
                                    } else {
                                        Toast.makeText(localContext, if (currentLang == "ar") "فشل استرجاع النسخة" else "Restore failed", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(localContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                isRestoringFromLocal = false
                            }
                        }
                    },
                    enabled = !isRestoringFromLocal,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
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

    // 4. Backup Password Options Dialog
    if (showBackupPasswordOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showBackupPasswordOptionsDialog = false },
            title = {
                Text(
                    text = if (currentLang == "ar") "خيارات حماية النسخة الاحتياطية" else "Backup Protection",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (currentLang == "ar") "اختر مستوى الحماية للنسخة الاحتياطية:" else "Select security level:",
                        fontSize = 13.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedEncryptionModePassword = false }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = !selectedEncryptionModePassword,
                            onClick = { selectedEncryptionModePassword = false }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = if (currentLang == "ar") "بدون تشفير" else "No Encryption", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(text = if (currentLang == "ar") "غير محمية بكلمة مرور (سهلة الاسترجاع)" else "Not password protected", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedEncryptionModePassword = true }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = selectedEncryptionModePassword,
                            onClick = { selectedEncryptionModePassword = true }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = if (currentLang == "ar") "محمية بكلمة مرور" else "Password Protected", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(text = if (currentLang == "ar") "تشفير AES-256 آمن" else "AES-256 encryption", fontSize = 11.sp, color = Color.Gray)
                        }
                    }

                    if (selectedEncryptionModePassword) {
                        OutlinedTextField(
                            value = backupPasswordInput,
                            onValueChange = {
                                backupPasswordInput = it
                                backupPasswordError = if (it.length < 4) {
                                    if (currentLang == "ar") "يجب أن تكون 4 أحرف على الأقل" else "Must be >= 4 chars"
                                } else null
                            },
                            label = { Text(if (currentLang == "ar") "كلمة مرور النسخة" else "Password") },
                            isError = backupPasswordError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                        backupPasswordError?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedEncryptionModePassword) {
                            if (backupPasswordInput.length < 4) {
                                backupPasswordError = if (currentLang == "ar") "يجب أن تكون 4 أحرف على الأقل" else "Must be >= 4 chars"
                                return@Button
                            }
                            tempBackupPassword = backupPasswordInput
                        } else {
                            tempBackupPassword = null
                        }

                        showBackupPasswordOptionsDialog = false

                        if (isBackupOperationExport) {
                            exportLocalBackupLauncher.launch("earthlink_backup_${System.currentTimeMillis()}.zip")
                        } else {
                            CoroutineScope(Dispatchers.IO).launch {
                                isPerformingLocalBackup = true
                                try {
                                    val zipFile = BackupManager.createDailyRollingBackup(localContext, tempBackupPassword)
                                    if (zipFile != null && zipFile.exists()) {
                                        prefs.saveLocalLastBackupTime(System.currentTimeMillis())
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(localContext, if (currentLang == "ar") "تم حفظ النسخة الاحتياطية بنجاح!" else "Backup created successfully!", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(localContext, if (currentLang == "ar") "فشل إنشاء النسخة الاحتياطية" else "Backup failed", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(localContext, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    isPerformingLocalBackup = false
                                    tempBackupPassword = null
                                }
                            }
                        }
                    }
                ) {
                    Text(if (currentLang == "ar") "متابعة" else "Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupPasswordOptionsDialog = false }) {
                    Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                }
            }
        )
    }

    // 5. Restore Password Prompt Dialog
    if (showRestorePasswordPromptDialog && restoreTargetFile != null) {
        val fileToRestore = restoreTargetFile!!
        AlertDialog(
            onDismissRequest = {
                showRestorePasswordPromptDialog = false
                restoreTargetFile = null
            },
            title = {
                Text(
                    text = if (currentLang == "ar") "فك تشفير النسخة الاحتياطية" else "Decrypt Backup",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (currentLang == "ar") "هذه النسخة مشفرة، يرجى إدخال كلمة المرور للاسترجاع:" else "Enter password to decrypt and restore:",
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = restorePasswordInput,
                        onValueChange = {
                            restorePasswordInput = it
                            restorePasswordError = null
                        },
                        label = { Text(if (currentLang == "ar") "كلمة المرور" else "Password") },
                        isError = restorePasswordError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    restorePasswordError?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restorePasswordInput.isEmpty()) {
                            restorePasswordError = if (currentLang == "ar") "يرجى إدخال كلمة المرور" else "Enter password"
                            return@Button
                        }

                        CoroutineScope(Dispatchers.IO).launch {
                            isRestoringFromLocal = true
                            try {
                                val restored = BackupManager.restoreBackupZip(localContext, fileToRestore, force = true, password = restorePasswordInput)
                                withContext(Dispatchers.Main) {
                                    if (restored) {
                                        Toast.makeText(localContext, if (currentLang == "ar") "تم استرجاع النسخة بنجاح! جاري إعادة التشغيل..." else "Restored! Restarting...", Toast.LENGTH_LONG).show()
                                        showRestorePasswordPromptDialog = false
                                        restoreTargetFile = null
                                        availableLocalBackups = null
                                        val pm = localContext.packageManager
                                        val intent = pm.getLaunchIntentForPackage(localContext.packageName)
                                        if (intent != null) {
                                            val mainIntent = Intent.makeRestartActivityTask(intent.component)
                                            localContext.startActivity(mainIntent)
                                            Runtime.getRuntime().exit(0)
                                        }
                                    } else {
                                        restorePasswordError = if (currentLang == "ar") "كلمة مرور خاطئة أو فشل فك التشفير" else "Incorrect password or decryption failed"
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                withContext(Dispatchers.Main) {
                                    restorePasswordError = e.message ?: "Decryption error"
                                }
                            } finally {
                                isRestoringFromLocal = false
                            }
                        }
                    },
                    enabled = !isRestoringFromLocal
                ) {
                    if (isRestoringFromLocal) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (currentLang == "ar") "جاري فك التشفير..." else "Decrypting...")
                    } else {
                        Text(if (currentLang == "ar") "فك التشفير والاسترجاع" else "Decrypt & Restore")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestorePasswordPromptDialog = false
                        restoreTargetFile = null
                    },
                    enabled = !isRestoringFromLocal
                ) {
                    Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                }
            }
        )
    }

    // 6. Pricing Dialog
    if (showPricingDialog) {
        PricingManagementDialog(
            authViewModel = authViewModel,
            currentLang = currentLang,
            onDismiss = { showPricingDialog = false }
        )
    }

    // 7. Logout Confirm Dialog
    if (showLogoutConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmDialog = false },
            title = { Text(if (currentLang == "ar") "تأكيد تسجيل الخروج" else "Confirm Sign Out") },
            text = { Text(if (currentLang == "ar") "هل أنت متأكد من تسجيل الخروج؟ سيتم إغلاق الجلسة ومسح قاعدة البيانات المحلية من هذا الجهاز." else "Are you sure you want to sign out? Your session will end and local database tables will be cleared.") },
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
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

    // 8. Logout Unsynced Warning Dialog
    if (unsyncedWarningDialog) {
        AlertDialog(
            onDismissRequest = { unsyncedWarningDialog = false },
            title = { Text(if (currentLang == "ar") "تنبيه: تغييرات غير متزامنة!" else "Warning: Unsynced Changes!") },
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(if (currentLang == "ar") "خروج بالقوة (حذف البيانات)" else "Force Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { unsyncedWarningDialog = false }) {
                    Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                }
            }
        )
    }
}

// ==========================================
// SUB-COMPOSABLES & MODULAR UI CARDS
// ==========================================

@Composable
private fun SettingsHeader(currentLang: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (currentLang == "ar") "إعدادات النظام" else "System Settings",
                fontWeight = FontWeight.Black,
                fontSize = 20.sp,
                color = Color.White
            )
            Text(
                text = if (currentLang == "ar") "إدارة الحسابات، البيانات، والتفضيلات" else "Manage accounts, sync engine & app preferences",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }

        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(0xFF0288D1).copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

// ==========================================
// APPLE-STYLE SUB-COMPOSABLES & MODULAR UI
// ==========================================

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF8E8E93),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun SettingsCardGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF141922),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsIconSquircle(
    icon: ImageVector,
    backgroundColor: Color,
    iconTint: Color = Color.White
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(backgroundColor, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(17.dp)
        )
    }
}

// 1. ACCOUNT & ISP SECTION
@Composable
private fun AccountAndIspSection(
    username: String,
    prefs: com.example.core.security.PreferenceManager,
    currentLang: String,
    onSaveIsp: (String, String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var ispAdminUserText by rememberSaveable { mutableStateOf(prefs.getIspAdminUsername() ?: "") }
    var ispAdminPassText by rememberSaveable { mutableStateOf(prefs.getIspAdminPassword() ?: "") }
    var isIspPasswordVisible by rememberSaveable { mutableStateOf(false) }

    var depositPassText by rememberSaveable { mutableStateOf(prefs.getDepositPassword()) }
    var isDepositPassVisible by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Section Row Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsIconSquircle(
                icon = Icons.Default.AdminPanelSettings,
                backgroundColor = Color(0xFF0A84FF)
            )
            Text(
                text = if (currentLang == "ar") "بيانات الوكيل (ISP Admin)" else "ISP Admin Credentials",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        // ISP Username
        OutlinedTextField(
            value = ispAdminUserText,
            onValueChange = {
                ispAdminUserText = it
                prefs.saveIspAdminUsername(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (currentLang == "ar") "اسم المستخدم" else "Username", fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF0E131B),
                unfocusedContainerColor = Color(0xFF0E131B)
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true
        )

        // ISP Password
        OutlinedTextField(
            value = ispAdminPassText,
            onValueChange = {
                ispAdminPassText = it
                prefs.saveIspAdminPassword(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (currentLang == "ar") "كلمة المرور" else "Password", fontSize = 12.sp) },
            visualTransformation = if (isIspPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isIspPasswordVisible = !isIspPasswordVisible }) {
                    Icon(
                        imageVector = if (isIspPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isIspPasswordVisible) {
                            if (currentLang == "ar") "إخفاء كلمة المرور" else "Hide password"
                        } else {
                            if (currentLang == "ar") "إظهار كلمة المرور" else "Show password"
                        },
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0A84FF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF0E131B),
                unfocusedContainerColor = Color(0xFF0E131B)
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 4.dp))

        // Box / Deposit Password
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsIconSquircle(
                icon = Icons.Default.Lock,
                backgroundColor = Color(0xFF5856D6)
            )
            Text(
                text = if (currentLang == "ar") "كلمة مرور الصندوق (التجديد السريع)" else "Box / Deposit Password",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        OutlinedTextField(
            value = depositPassText,
            onValueChange = {
                depositPassText = it
                prefs.saveDepositPassword(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (currentLang == "ar") "كلمة مرور الصندوق" else "Box Password", fontSize = 12.sp) },
            visualTransformation = if (isDepositPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isDepositPassVisible = !isDepositPassVisible }) {
                    Icon(
                        imageVector = if (isDepositPassVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isDepositPassVisible) {
                            if (currentLang == "ar") "إخفاء كلمة المرور" else "Hide password"
                        } else {
                            if (currentLang == "ar") "إظهار كلمة المرور" else "Show password"
                        },
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF5856D6),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF0E131B),
                unfocusedContainerColor = Color(0xFF0E131B)
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                if (ispAdminUserText.isNotBlank() && ispAdminPassText.isNotBlank()) {
                    onSaveIsp(ispAdminUserText, ispAdminPassText)
                }
                prefs.saveDepositPassword(depositPassText)
                Toast.makeText(context, if (currentLang == "ar") "تم الحفظ بنجاح" else "Saved successfully!", Toast.LENGTH_SHORT).show()
            }),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Save All Gateway Settings Button
        Button(
            onClick = {
                focusManager.clearFocus()
                if (ispAdminUserText.isNotBlank() || ispAdminPassText.isNotBlank()) {
                    if (ispAdminUserText.isBlank() || ispAdminPassText.isBlank()) {
                        Toast.makeText(context, if (currentLang == "ar") "يرجى ملء اسم المستخدم وكلمة المرور للوكيل" else "Please fill ISP credentials", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    onSaveIsp(ispAdminUserText, ispAdminPassText)
                }
                prefs.saveDepositPassword(depositPassText)
                Toast.makeText(context, if (currentLang == "ar") "تم حفظ إعدادات البوابة بنجاح" else "Gateway settings saved successfully!", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (currentLang == "ar") "حفظ الإعدادات" else "Save Settings", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

// 2. SYNC & BACKUP SECTION
@Composable
private fun SyncAndBackupSection(
    currentLang: String,
    syncState: SyncStatusState,
    syncProgress: SyncProgress,
    lastSyncTime: Long,
    isSyncing: Boolean,
    pendingCount: Int,
    failedCount: Int,
    isGoogleLinked: Boolean,
    googleUserEmail: String?,
    onLinkGoogle: () -> Unit,
    onTriggerSync: () -> Unit,
    onRetryFailed: () -> Unit,
    isAutoBackupEnabled: Boolean,
    onToggleAutoBackup: (Boolean) -> Unit,
    lastLocalBackupTime: Long,
    isPerformingBackup: Boolean,
    onStartManualBackup: () -> Unit,
    isFetchingBackups: Boolean,
    onListBackups: () -> Unit,
    onExportZip: () -> Unit,
    onImportUtower: () -> Unit
) {
    // 1. CLOUD SYNC ROW
    if (!isGoogleLinked || syncState == SyncStatusState.AUTH_REQUIRED) {
        // Unlinked State
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsIconSquircle(
                        icon = Icons.Default.CloudOff,
                        backgroundColor = Color(0xFFAF52DE)
                    )
                    Column {
                        Text(
                            text = if (currentLang == "ar") "المزامنة السحابية" else "Cloud Sync",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (currentLang == "ar") "Google Cloud غير متصل" else "Google Cloud not linked",
                            fontSize = 11.sp,
                            color = Color(0xFFFF9F0A)
                        )
                    }
                }

                Button(
                    onClick = onLinkGoogle,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(
                        text = if (currentLang == "ar") "ربط الحساب" else "Link Account",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    } else {
        // Linked State
        val (badgeText, badgeBg, badgeTextClr) = when {
            isSyncing || syncState == SyncStatusState.SYNCING -> Triple(
                if (currentLang == "ar") "جاري المزامنة" else "Syncing",
                Color(0xFF0A84FF).copy(alpha = 0.2f),
                Color(0xFF0A84FF)
            )
            syncState == SyncStatusState.COMPLETE_WITH_ERRORS || (failedCount > 0 && syncState != SyncStatusState.ERROR) -> Triple(
                if (currentLang == "ar") "أخطاء ($failedCount)" else "Errors ($failedCount)",
                Color(0xFFFF9F0A).copy(alpha = 0.2f),
                Color(0xFFFF9F0A)
            )
            syncState == SyncStatusState.ERROR -> Triple(
                if (currentLang == "ar") "تعذر المزامنة" else "Sync Failed",
                Color(0xFFFF453A).copy(alpha = 0.2f),
                Color(0xFFFF453A)
            )
            syncState == SyncStatusState.OFFLINE -> Triple(
                if (currentLang == "ar") "غير متصل" else "Offline",
                Color(0xFF8E8E93).copy(alpha = 0.2f),
                Color(0xFF8E8E93)
            )
            else -> Triple(
                if (currentLang == "ar") "متزامن" else "Up to date",
                Color(0xFF30D158).copy(alpha = 0.2f),
                Color(0xFF30D158)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    SettingsIconSquircle(
                        icon = Icons.Default.CloudSync,
                        backgroundColor = Color(0xFF30D158)
                    )
                    Column {
                        Text(
                            text = if (currentLang == "ar") "المزامنة السحابية" else "Cloud Sync",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = googleUserEmail ?: if (currentLang == "ar") "متصل" else "Connected",
                            fontSize = 11.sp,
                            color = Color(0xFF8E8E93),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextClr,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Sync Action Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onTriggerSync,
                    enabled = !isSyncing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (currentLang == "ar") "جاري المزامنة..." else "Syncing...", fontSize = 12.sp, color = Color.White)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (currentLang == "ar") "مزامنة الآن" else "Sync Now", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }

                if (failedCount > 0) {
                    OutlinedButton(
                        onClick = onRetryFailed,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9F0A)),
                        border = BorderStroke(1.dp, Color(0xFFFF9F0A).copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(text = if (currentLang == "ar") "إعادة ($failedCount)" else "Retry ($failedCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

    // 2. DAILY AUTO BACKUP SWITCH
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f)
        ) {
            SettingsIconSquircle(
                icon = Icons.Default.Backup,
                backgroundColor = Color(0xFF64D2FF)
            )
            Column {
                Text(
                    text = if (currentLang == "ar") "نسخ احتياطي يومي تلقائي" else "Daily Auto Backup",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                if (lastLocalBackupTime > 0L) {
                    Text(
                        text = "${if (currentLang == "ar") "آخر نسخة:" else "Last:"} ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(lastLocalBackupTime))}",
                        fontSize = 11.sp,
                        color = Color(0xFF8E8E93)
                    )
                }
            }
        }

        Switch(
            checked = isAutoBackupEnabled,
            onCheckedChange = { onToggleAutoBackup(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF30D158)
            )
        )
    }

    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

    // 3. BACKUP ACTIONS (3 Clean Pill Tiles)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Backup
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0E131B),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !isPerformingBackup) { onStartManualBackup() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF0A84FF), modifier = Modifier.size(18.dp))
                Text(if (currentLang == "ar") "نسخ الآن" else "Backup", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        // Restore
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0E131B),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !isFetchingBackups) { onListBackups() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF30D158), modifier = Modifier.size(18.dp))
                Text(if (currentLang == "ar") "استرجاع" else "Restore", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }

        // Export Zip
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF0E131B),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
            modifier = Modifier
                .weight(1f)
                .clickable { onExportZip() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFFFF9F0A), modifier = Modifier.size(18.dp))
                Text(if (currentLang == "ar") "مشاركة (.zip)" else "Export", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }

    HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

    // 4. UTOWER IMPORT ROW
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onImportUtower() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsIconSquircle(
                icon = Icons.Default.Download,
                backgroundColor = Color(0xFFBF5AF2)
            )
            Text(
                text = if (currentLang == "ar") "استيراد بيانات uTower" else "Import uTower Database",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(18.dp))
    }
}

// 3. DISPLAY & PRICING SECTION
@Composable
private fun DisplayAndPricingSection(
    prefs: com.example.core.security.PreferenceManager,
    currentLang: String,
    onOpenPricingDialog: () -> Unit
) {
    var showActive by rememberSaveable { mutableStateOf(prefs.getShowActive()) }
    var showExpired by rememberSaveable { mutableStateOf(prefs.getShowExpired()) }
    var maxItems by rememberSaveable { mutableStateOf(prefs.getMaxDashboardItems()) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Retail Pricing Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPricingDialog() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.Sell,
                    backgroundColor = Color(0xFFFF9F0A)
                )
                Text(
                    text = if (currentLang == "ar") "أسعار بيع الباقات والأرباح" else "Package Retail Prices & Profit",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(18.dp))
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

        // Switch: Active Subscribers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.Person,
                    backgroundColor = Color(0xFF30D158)
                )
                Text(
                    text = if (currentLang == "ar") "عرض المشتركين النشطين" else "Show Active Subscribers",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
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

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

        // Switch: Expired Subscribers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.PersonOff,
                    backgroundColor = Color(0xFFFF453A)
                )
                Text(
                    text = if (currentLang == "ar") "عرض المشتركين المنتهين" else "Show Expired Subscribers",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
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

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

        // Stepper: Max Dashboard Items
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.FormatListNumbered,
                    backgroundColor = Color(0xFF5E5CE6)
                )
                Text(
                    text = if (currentLang == "ar") "عدد عناصر الرئيسية" else "Max Dashboard Items",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF0E131B),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .size(28.dp)
                        .clickable {
                            if (maxItems > 5) {
                                val newVal = maxItems - 5
                                prefs.setMaxDashboardItems(newVal)
                                maxItems = newVal
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }

                Text(
                    text = "$maxItems",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Surface(
                    shape = CircleShape,
                    color = Color(0xFF0E131B),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .size(28.dp)
                        .clickable {
                            if (maxItems < 100) {
                                val newVal = maxItems + 5
                                prefs.setMaxDashboardItems(newVal)
                                maxItems = newVal
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// 4. GENERAL SECTION
@Composable
private fun GeneralSection(
    currentLang: String,
    onSelectLang: (String) -> Unit
) {
    val uriHandler = LocalUriHandler.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Language Selector Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.Language,
                    backgroundColor = Color(0xFF0A84FF)
                )
                Text(
                    text = if (currentLang == "ar") "اللغة" else "Language",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ar" to "العربية", "en" to "English").forEach { (code, label) ->
                    val isSelected = currentLang == code
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF0A84FF) else Color(0xFF0E131B),
                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .clickable { onSelectLang(code) }
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else Color(0xFF8E8E93),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

        // Privacy Policy Link
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://docs.google.com/document/d/1e7gm4KkC1jjhwlm0YPQMVmwnJXP6eeWeKKJTWlzKg7w/edit?usp=sharing")
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.Policy,
                    backgroundColor = Color(0xFF8E8E93)
                )
                Text(
                    text = if (currentLang == "ar") "الخصوصية والشروط" else "Privacy & Terms",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(16.dp))
        }
    }
}

// 5. DEVELOPER SECTION (DEBUG ONLY)
@Composable
private fun DeveloperSection(
    isDemoMode: Boolean,
    onToggleDemo: (Boolean) -> Unit,
    dashboardViewModel: DashboardViewModel,
    currentLang: String
) {
    var showConfirmDelete by rememberSaveable { mutableStateOf(false) }
    var unsyncedWarningClearDataDialog by rememberSaveable { mutableStateOf(false) }
    var pendingClearDataCountState by rememberSaveable { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Demo Mode Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsIconSquircle(
                    icon = Icons.Default.Science,
                    backgroundColor = Color(0xFFFF9F0A)
                )
                Text(
                    text = if (currentLang == "ar") "وضع التجريب المحلي (Demo)" else "Demo Sandbox Mode",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
            Switch(
                checked = isDemoMode,
                onCheckedChange = { onToggleDemo(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF30D158)
                )
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.padding(vertical = 2.dp))

        // Clear All Data Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showConfirmDelete = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsIconSquircle(
                icon = Icons.Default.DeleteForever,
                backgroundColor = Color(0xFFFF453A)
            )
            Text(
                text = if (currentLang == "ar") "مسح قاعدة البيانات المحلية" else "Purge Local Database",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF453A)
            )
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text(if (currentLang == "ar") "مسح البيانات المحلية نهائياً؟" else "Delete Local Data?") },
            text = { Text(if (currentLang == "ar") "سيتم مسح جميع البيانات المحلية من هذا الجهاز. هل أنت متأكد؟" else "All local data will be permanently cleared from this device.") },
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(if (currentLang == "ar") "حذف نهائي" else "Permanent Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                }
            }
        )
    }

    if (unsyncedWarningClearDataDialog) {
        AlertDialog(
            onDismissRequest = { unsyncedWarningClearDataDialog = false },
            title = { Text(if (currentLang == "ar") "تنبيه: تغييرات غير متزامنة!" else "Warning: Unsynced Changes!") },
            text = {
                Text(
                    if (currentLang == "ar")
                        "يوجد $pendingClearDataCountState عملية لم يتم رفعها للسيرفر بعد. مسح البيانات سيحذفها نهائياً!"
                    else
                        "There are $pendingClearDataCountState unsynced operations. Clearing will permanently delete them!"
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(if (currentLang == "ar") "حذف بالقوة" else "Force Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { unsyncedWarningClearDataDialog = false }) {
                    Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                }
            }
        )
    }
}

// 6. PRICING MANAGEMENT DIALOG
@Composable
private fun PricingManagementDialog(
    authViewModel: AuthViewModel,
    currentLang: String,
    onDismiss: () -> Unit
) {
    val prefs = authViewModel.prefs

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF11161F),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
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
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }

                packagesList.forEach { (key, _, _) ->
                    val currentCustom = prefs.getPackageSellingPrice(key, 0.0)
                    localInputs[key] = MoneyParser.formatIqdToUiString(currentCustom)
                    apiLoading[key] = true
                }

                packagesList.forEach { (key, _, index) ->
                    launch {
                        try {
                            val cost = authViewModel.gateway.getAccountCost(index)
                            apiCosts[key] = cost
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            apiCosts[key] = 0.0
                        } finally {
                            apiLoading[key] = false
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Dialog Header
                    Text(
                        text = if (currentLang == "ar") "أسعار بيع باقات الاشتراك" else "Subscription Retail Prices",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (currentLang == "ar") "اضبط أسعار البيع بالآلاف (مثال: 35 يعني 35,000 د.ع) لحساب أرباحك تلقائياً." else "Configure retail selling prices (e.g. 35 = 35,000 IQD).",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    packagesList.forEach { (key, label, _) ->
                        val sellingPriceInput = localInputs[key] ?: ""
                        val apiCost = apiCosts[key] ?: 0.0
                        val customSellingPrice = MoneyParser.parseSubscriptionPriceIqd(sellingPriceInput)?.toDouble() ?: 0.0
                        val profit = customSellingPrice - apiCost

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF171E29),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(text = label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(text = if (currentLang == "ar") "التكلفة:" else "Cost:", fontSize = 10.sp, color = Color.White.copy(alpha = 0.45f))
                                        if (apiLoading[key] == true) {
                                            CircularProgressIndicator(modifier = Modifier.size(8.dp), color = Color(0xFF38BDF8), strokeWidth = 1.dp)
                                        } else {
                                            Text(
                                                text = if (apiCost > 0.0) "${MoneyParser.formatIqdForDisplay(apiCost)} ${if (currentLang == "ar") "د.ع" else "IQD"}" else "N/A",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White.copy(alpha = 0.8f)
                                            )
                                        }
                                    }

                                    if (customSellingPrice > 0.0 && apiCost > 0.0) {
                                        Text(
                                            text = "${if (currentLang == "ar") "الربح:" else "Profit:"} +${MoneyParser.formatIqdForDisplay(profit)} ${if (currentLang == "ar") "د.ع" else "IQD"}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (profit >= 0.0) Color(0xFF10B981) else Color(0xFFEF4444)
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = sellingPriceInput,
                                    onValueChange = { input ->
                                        localInputs[key] = input.filter { it.isDigit() }
                                    },
                                    modifier = Modifier.width(90.dp),
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                                    suffix = {
                                        Text(
                                            text = if (currentLang == "ar") "ألف" else "k",
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    placeholder = {
                                        Text(
                                            text = MoneyParser.formatIqdToUiString(apiCost).ifEmpty { "0" },
                                            color = Color.White.copy(alpha = 0.2f),
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF0288D1),
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                        focusedContainerColor = Color(0xFF11161F),
                                        unfocusedContainerColor = Color(0xFF11161F)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
                        }
                    }
                }

                // Action Buttons at Bottom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (currentLang == "ar") "إلغاء" else "Cancel")
                    }

                    Button(
                        onClick = {
                            localInputs.forEach { (k, v) ->
                                val priceVal = MoneyParser.parseSubscriptionPriceIqd(v)?.toDouble() ?: 0.0
                                if (priceVal >= 0.0) {
                                    prefs.setPackageSellingPrice(k, priceVal)
                                }
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                        modifier = Modifier
                            .weight(2f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (currentLang == "ar") "حفظ الأسعار" else "Save Prices", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}
