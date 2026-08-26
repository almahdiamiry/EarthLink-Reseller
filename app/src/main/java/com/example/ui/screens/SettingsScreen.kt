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
                .background(Color(0xFF0B0F14))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Bar
                SettingsHeader(currentLang = currentLang)

                // 1. GROUP 1: ACCOUNT & ISP GATEWAY
                SettingsGroupCard(
                    title = if (currentLang == "ar") "بيانات الموزع وبوابة إيرثلنك" else "Affiliate & ISP Gateway",
                    subtitle = if (currentLang == "ar") "حساب الوكيل، البوابة، وتراخيص التجديد" else "ISP Admin credentials and auto-renewal box",
                    icon = Icons.Default.AdminPanelSettings,
                    accentColor = Color(0xFF0288D1)
                ) {
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

                // 2. GROUP 2: SYNC, BACKUPS & DATA TRANSFER
                SettingsGroupCard(
                    title = if (currentLang == "ar") "المزامنة والنسخ الاحتياطي" else "Sync & Backup Engine",
                    subtitle = if (currentLang == "ar") "حماية قواعد البيانات والاسترجاع المحلي ونقل البيانات" else "Cloud sync status, daily rolling backups and uTower",
                    icon = Icons.Default.CloudSync,
                    accentColor = Color(0xFF10B981)
                ) {
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

                // 3. GROUP 3: DISPLAY & PRICING
                SettingsGroupCard(
                    title = if (currentLang == "ar") "تخصيص الواجهة والأسعار" else "Display & Pricing",
                    subtitle = if (currentLang == "ar") "فلاتر العرض الرئيسية وأسعار بيع الباقات والربح" else "Dashboard filters and retail pricing model",
                    icon = Icons.Default.Tune,
                    accentColor = Color(0xFFF59E0B)
                ) {
                    DisplayAndPricingSection(
                        prefs = prefs,
                        currentLang = currentLang,
                        onOpenPricingDialog = { showPricingDialog = true }
                    )
                }

                // 4. GROUP 4: GENERAL & PRIVACY
                SettingsGroupCard(
                    title = if (currentLang == "ar") "التفضيلات العامة والنظام" else "General Preferences",
                    subtitle = if (currentLang == "ar") "لغة التطبيق وسياسة الخصوصية" else "Language selection and legal terms",
                    icon = Icons.Default.Language,
                    accentColor = Color(0xFFA78BFA)
                ) {
                    GeneralSection(
                        currentLang = currentLang,
                        onSelectLang = { prefs.setLanguage(it) }
                    )
                }

                // --- DEV MODE (DEBUG BUILD ONLY) ---
                if (AppBuildConfig.DEBUG) {
                    SettingsGroupCard(
                        title = if (currentLang == "ar") "أدوات المطور والتجريب" else "Developer & Sandbox",
                        subtitle = if (currentLang == "ar") "محاكاة البيانات ومسح الجداول المحلية (Debug Only)" else "Mock data simulator and local database purge",
                        icon = Icons.Default.Terminal,
                        accentColor = Color(0xFFEF4444)
                    ) {
                        DeveloperSection(
                            isDemoMode = isDemoMode,
                            onToggleDemo = { prefs.setDemoMode(it) },
                            dashboardViewModel = dashboardViewModel,
                            currentLang = currentLang
                        )
                    }
                }

                // 6. LOGOUT BUTTON
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLogoutConfirmDialog = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentLang == "ar") "تسجيل الخروج الآمن" else "Secure Sign Out",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFFEF4444)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
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

@Composable
private fun SettingsGroupCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF11161F),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Group Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(accentColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            content()
        }
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

    // Affiliate Details Pill
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF171E29),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (currentLang == "ar") "المستخدم النشط: $username" else "User: $username",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "rapi.earthlink.iq",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.4f)
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF10B981).copy(alpha = 0.15f)
            ) {
                Text(
                    text = if (currentLang == "ar") "تشفير نشط" else "Encrypted",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    // ISP Admin Username & Password
    var ispAdminUserText by rememberSaveable { mutableStateOf(prefs.getIspAdminUsername() ?: "") }
    var ispAdminPassText by rememberSaveable { mutableStateOf(prefs.getIspAdminPassword() ?: "") }
    var isIspPasswordVisible by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (currentLang == "ar") "بيانات بوابة إيرثلنك (ISP Admin)" else "ISP Admin Credentials",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.9f)
        )

        OutlinedTextField(
            value = ispAdminUserText,
            onValueChange = {
                ispAdminUserText = it
                prefs.saveIspAdminUsername(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (currentLang == "ar") "اسم المستخدم للوكيل" else "ISP Admin Username", fontSize = 12.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0288D1),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF171E29),
                unfocusedContainerColor = Color(0xFF171E29)
            ),
            shape = RoundedCornerShape(12.dp),
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
            label = { Text(if (currentLang == "ar") "كلمة المرور للوكيل" else "ISP Admin Password", fontSize = 12.sp) },
            visualTransformation = if (isIspPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isIspPasswordVisible = !isIspPasswordVisible }) {
                    Icon(
                        imageVector = if (isIspPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0288D1),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF171E29),
                unfocusedContainerColor = Color(0xFF171E29)
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                if (ispAdminUserText.isNotBlank() && ispAdminPassText.isNotBlank()) {
                    onSaveIsp(ispAdminUserText, ispAdminPassText)
                }
            }),
            singleLine = true
        )

        Button(
            onClick = {
                focusManager.clearFocus()
                if (ispAdminUserText.isBlank() || ispAdminPassText.isBlank()) {
                    Toast.makeText(context, if (currentLang == "ar") "يرجى ملء اسم المستخدم وكلمة المرور" else "Please fill ISP credentials", Toast.LENGTH_SHORT).show()
                } else {
                    onSaveIsp(ispAdminUserText, ispAdminPassText)
                    Toast.makeText(context, if (currentLang == "ar") "تم الحفظ والتحميل بنجاح" else "Saved successfully!", Toast.LENGTH_SHORT).show()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(42.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (currentLang == "ar") "حفظ الحساب والتوجه للمشتركين" else "Save & Go to Subscribers", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }

    // Box / Deposit Password
    var depositPassText by rememberSaveable { mutableStateOf(prefs.getDepositPassword()) }
    var isDepositPassVisible by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (currentLang == "ar") "كلمة مرور الصندوق الافتراضية (التجديد السريع)" else "Default Box / Deposit Password",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.9f)
        )

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
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF0288D1),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                focusedContainerColor = Color(0xFF171E29),
                unfocusedContainerColor = Color(0xFF171E29)
            ),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                Toast.makeText(context, if (currentLang == "ar") "تم حفظ كلمة مرور الصندوق" else "Box password saved", Toast.LENGTH_SHORT).show()
            }),
            singleLine = true
        )
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
    // 1. UNIFIED CLOUD SYNC CARD
    if (!isGoogleLinked || syncState == SyncStatusState.AUTH_REQUIRED) {
        // STATE A: Google Account Not Linked / Sign-in Required
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(1.dp, Color(0xFF0288D1).copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Row: Icon + Title + Status Pill
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
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0288D1).copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.25f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier
                                    .padding(7.dp)
                                    .size(20.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (currentLang == "ar") "المزامنة السحابية (Google Cloud)" else "Cloud Sync (Google Cloud)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (currentLang == "ar") "تأمين وحفظ البيانات سحابياً بأمان" else "Encrypted real-time cloud backup",
                                fontSize = 10.sp,
                                color = Color.White.copy(alpha = 0.45f)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFA855F7).copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = if (currentLang == "ar") "يلزم ربط الحساب" else "Link Required",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFC084FC),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // Explainer Info Box
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF10161D),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (currentLang == "ar")
                                "قم بتسجيل الدخول بواسطة Google لربط التطبيق بالسحابة وتفعيل المزامنة التلقائية لبيانات المشتركين والحركات المالية والحفاظ عليها عند تغيير الجهاز."
                            else
                                "Sign in with Google to enable automatic cloud sync and safely protect your subscribers and financial ledger records.",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            lineHeight = 16.sp
                        )
                    }
                }

                // Action CTA: Google Sign-In & Link
                Button(
                    onClick = onLinkGoogle,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (currentLang == "ar") "تسجيل الدخول وربط حساب Google للتفعيل" else "Sign in with Google to Activate Sync",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
            }
        }
    } else {
        // STATE B: Google Account Linked -> Full Operational Cloud Sync Card
        val iconTint = when {
            isSyncing || syncState == SyncStatusState.SYNCING -> Color(0xFF38BDF8)
            syncState == SyncStatusState.COMPLETE || (syncState == SyncStatusState.IDLE && lastSyncTime > 0L && failedCount == 0) -> Color(0xFF10B981)
            syncState == SyncStatusState.COMPLETE_WITH_ERRORS || failedCount > 0 -> Color(0xFFF59E0B)
            syncState == SyncStatusState.ERROR -> Color(0xFFEF4444)
            syncState == SyncStatusState.OFFLINE -> Color(0xFF94A3B8)
            else -> Color(0xFF38BDF8)
        }

        val (badgeText, badgeBg, badgeTextClr) = when {
            isSyncing || syncState == SyncStatusState.SYNCING -> Triple(
                if (currentLang == "ar") "جاري المزامنة" else "Syncing",
                Color(0xFF0288D1).copy(alpha = 0.2f),
                Color(0xFF38BDF8)
            )
            syncState == SyncStatusState.COMPLETE_WITH_ERRORS || (failedCount > 0 && syncState != SyncStatusState.ERROR) -> Triple(
                if (currentLang == "ar") "أخطاء معلقة ($failedCount)" else "Errors ($failedCount)",
                Color(0xFFF59E0B).copy(alpha = 0.2f),
                Color(0xFFF59E0B)
            )
            syncState == SyncStatusState.ERROR -> Triple(
                if (currentLang == "ar") "تعذر المزامنة" else "Sync Failed",
                Color(0xFFEF4444).copy(alpha = 0.2f),
                Color(0xFFEF4444)
            )
            syncState == SyncStatusState.OFFLINE -> Triple(
                if (currentLang == "ar") "غير متصل" else "Offline",
                Color(0xFF64748B).copy(alpha = 0.2f),
                Color(0xFF94A3B8)
            )
            syncState == SyncStatusState.COMPLETE || (syncState == SyncStatusState.IDLE && lastSyncTime > 0L) -> Triple(
                if (currentLang == "ar") "متزامن" else "Up to date",
                Color(0xFF10B981).copy(alpha = 0.2f),
                Color(0xFF10B981)
            )
            else -> Triple(
                if (currentLang == "ar") "جاهز" else "Ready",
                Color.White.copy(alpha = 0.1f),
                Color.White.copy(alpha = 0.7f)
            )
        }

        val subtitleText = when {
            isSyncing -> {
                when {
                    syncProgress.totalCount > 0 && syncProgress.phase == SyncPhase.UPLOADING -> {
                        if (currentLang == "ar") "جاري رفع البيانات (${syncProgress.processedCount}/${syncProgress.totalCount})"
                        else "Uploading records (${syncProgress.processedCount}/${syncProgress.totalCount})"
                    }
                    syncProgress.phase == SyncPhase.DOWNLOADING -> {
                        if (currentLang == "ar") "جاري جلب التحديثات السحابية..."
                        else "Downloading cloud updates..."
                    }
                    syncProgress.phase == SyncPhase.PREPARING -> {
                        if (currentLang == "ar") "جاري فحص وتجهيز البيانات..."
                        else "Preparing sync pass..."
                    }
                    else -> {
                        if (currentLang == "ar") "بانتظار اكتمال المزامنة..."
                        else "Sync in progress..."
                    }
                }
            }
            lastSyncTime > 0L -> {
                val locale = if (currentLang == "ar") Locale("ar") else Locale.US
                val formattedTime = SimpleDateFormat("h:mm a", locale).format(Date(lastSyncTime))
                val base = if (currentLang == "ar") "آخر مزامنة ناجحة · $formattedTime" else "Last synced · $formattedTime"
                when {
                    failedCount > 0 -> if (currentLang == "ar") "$base ($failedCount عمليات معلقة بحاجة للمحاولة)" else "$base ($failedCount pending retries)"
                    pendingCount > 0 -> if (currentLang == "ar") "$base ($pendingCount حركات قيد المعالجة)" else "$base ($pendingCount pending)"
                    else -> base
                }
            }
            syncState == SyncStatusState.ERROR -> if (currentLang == "ar") "تعذر إتمام المزامنة الأخيرة · تحقق من الاتصال بالإنترنت" else "Last sync failed · Check your connection"
            syncState == SyncStatusState.OFFLINE -> if (currentLang == "ar") "التطبيق في وضع عدم الاتصال" else "App is offline"
            else -> if (currentLang == "ar") "جاهز للمزامنة السحابية الأولى" else "Ready for initial sync"
        }

        val subtitleColor = when {
            isSyncing -> Color(0xFF38BDF8)
            failedCount > 0 || syncState == SyncStatusState.COMPLETE_WITH_ERRORS -> Color(0xFFF59E0B)
            syncState == SyncStatusState.ERROR -> Color(0xFFEF4444)
            lastSyncTime > 0L -> Color.White.copy(alpha = 0.6f)
            else -> Color.White.copy(alpha = 0.45f)
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(
                1.dp,
                if (syncState == SyncStatusState.ERROR) Color(0xFFEF4444).copy(alpha = 0.3f)
                else Color.White.copy(alpha = 0.06f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Row 1: Icon + Title & Account Pill + Status Badge
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
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = iconTint.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, iconTint.copy(alpha = 0.3f))
                        ) {
                            Icon(
                                imageVector = if (syncState == SyncStatusState.COMPLETE && !isSyncing) Icons.Default.CloudDone else Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier
                                    .padding(7.dp)
                                    .size(20.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (currentLang == "ar") "المزامنة السحابية" else "Cloud Sync",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (!googleUserEmail.isNullOrBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = googleUserEmail,
                                        fontSize = 10.sp,
                                        color = Color.White.copy(alpha = 0.5f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Status Badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg,
                        border = BorderStroke(1.dp, badgeTextClr.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextClr,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Row 2: Detailed Subtitle Text (full width, clear & readable)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF10161D),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = subtitleText,
                        fontSize = 11.sp,
                        color = subtitleColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        lineHeight = 15.sp
                    )
                }

                // Progress bar if syncing
                if (isSyncing) {
                    if (syncProgress.totalCount > 0 && syncProgress.phase == SyncPhase.UPLOADING) {
                        val progressFraction = (syncProgress.processedCount.toFloat() / syncProgress.totalCount.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF38BDF8),
                            trackColor = Color.White.copy(alpha = 0.08f)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF38BDF8),
                            trackColor = Color.White.copy(alpha = 0.08f)
                        )
                    }
                }

                // Row 3: Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Main Sync Action Button
                    Button(
                        onClick = onTriggerSync,
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0288D1),
                            disabledContainerColor = Color(0xFF0288D1).copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (currentLang == "ar") "جاري المزامنة..." else "Syncing...",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (currentLang == "ar") "مزامنة الآن" else "Sync Now",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    // Retry Failed Outbox Button (if any failed items exist)
                    if (failedCount > 0) {
                        OutlinedButton(
                            onClick = onRetryFailed,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFF59E0B)
                            ),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(38.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (currentLang == "ar") "إعادة ($failedCount)" else "Retry ($failedCount)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Account Switch / Re-link (Icon button)
                    IconButton(
                        onClick = onLinkGoogle,
                        modifier = Modifier
                            .size(38.dp)
                            .background(Color(0xFF10161D), RoundedCornerShape(10.dp))
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = if (currentLang == "ar") "تبديل أو إعادة ربط الحساب" else "Switch or Re-link Account",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // Daily Auto Backup Switch
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF171E29),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (currentLang == "ar") "نسخ احتياطي يومي تلقائي (30 يوم)" else "Daily Rolling Backup (30 Days)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (currentLang == "ar") "حفظ نسخة محلية مشفرة يومياً وحذف ما زاد عن 30 يوم" else "Saves rolling backup daily to Documents/EarthlinkBackups",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
                Switch(
                    checked = isAutoBackupEnabled,
                    onCheckedChange = { onToggleAutoBackup(it) }
                )
            }

            if (lastLocalBackupTime > 0L) {
                Text(
                    text = "${if (currentLang == "ar") "آخر نسخة محلية:" else "Last Local Backup:"} ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastLocalBackupTime))}",
                    fontSize = 10.sp,
                    color = Color(0xFF10B981)
                )
            }
        }
    }

    // Backup Action Tools Grid (3 Buttons)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Backup Now
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !isPerformingBackup) { onStartManualBackup() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                Text(
                    text = if (currentLang == "ar") "نسخ الآن" else "Backup Now",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        // Restore Local
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = !isFetchingBackups) { onListBackups() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                Text(
                    text = if (currentLang == "ar") "استرجاع محلي" else "Restore",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        // Share / Export Zip
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier
                .weight(1f)
                .clickable { onExportZip() }
        ) {
            Column(
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                Text(
                    text = if (currentLang == "ar") "مشاركة (.zip)" else "Export (.zip)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }

    // uTower Data Import Button
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF171E29),
        border = BorderStroke(1.dp, Color(0xFF0288D1).copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onImportUtower() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                Column {
                    Text(
                        text = if (currentLang == "ar") "استيراد وترحيل بيانات uTower" else "Import uTower Database",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (currentLang == "ar") "استيراد المشتركين وسجل الحركات من ملف uTower القديم" else "Migrate subscribers and historical ledger from uTower",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.45f)
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        }
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Switch: Active Users
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (currentLang == "ar") "عرض المشتركين النشطين" else "Show Active Subscribers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (currentLang == "ar") "إظهار قائمة الحسابات الفعالة في الواجهة الرئيسية" else "Display active accounts on home dashboard",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
            Switch(
                checked = showActive,
                onCheckedChange = {
                    prefs.setShowActive(it)
                    showActive = it
                }
            )
        }

        // Switch: Expired Users
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (currentLang == "ar") "عرض الاشتراكات المنتهية" else "Show Expired Subscribers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (currentLang == "ar") "إظهار الحسابات المنتهية صلاحيتها مؤخراً" else "Display expired accounts on home dashboard",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
            Switch(
                checked = showExpired,
                onCheckedChange = {
                    prefs.setShowExpired(it)
                    showExpired = it
                }
            )
        }

        // Counter: Max Items
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (currentLang == "ar") "الحد الأقصى للعرض في الرئيسية" else "Max Dashboard Items",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${if (currentLang == "ar") "العدد الحالي:" else "Current limit:"} $maxItems",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF171E29),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            if (maxItems > 5) {
                                val newVal = maxItems - 5
                                prefs.setMaxDashboardItems(newVal)
                                maxItems = newVal
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }

                Text(
                    text = "$maxItems",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Surface(
                    shape = CircleShape,
                    color = Color(0xFF171E29),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            if (maxItems < 100) {
                                val newVal = maxItems + 5
                                prefs.setMaxDashboardItems(newVal)
                                maxItems = newVal
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

        // Subscription Pricing Tile
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPricingDialog() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Sell, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                    Column {
                        Text(
                            text = if (currentLang == "ar") "ضبط أسعار بيع الاشتراكات" else "Package Retail Selling Prices",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (currentLang == "ar") "تحديد سعر البيع لكل باقة لحساب الأرباح تلقائياً" else "Set selling prices to calculate profits automatically",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                }
                Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Language Selector Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ar" to "العربية 🇮🇶", "en" to "English 🇺🇸").forEach { (code, label) ->
                val isSelected = currentLang == code
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) Color(0xFF0288D1).copy(alpha = 0.2f) else Color(0xFF171E29),
                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF0288D1) else Color.White.copy(alpha = 0.05f)),
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clickable { onSelectLang(code) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Privacy Policy Link
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF171E29),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    uriHandler.openUri("https://docs.google.com/document/d/1e7gm4KkC1jjhwlm0YPQMVmwnJXP6eeWeKKJTWlzKg7w/edit?usp=sharing")
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Policy, contentDescription = null, tint = Color(0xFFA78BFA), modifier = Modifier.size(18.dp))
                    Text(
                        text = if (currentLang == "ar") "سياسة الخصوصية وشروط الاستخدام" else "Privacy Policy & Terms",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
            }
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Demo Mode Switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (currentLang == "ar") "وضع التجريب المحلي (Demo Sandbox)" else "Demo Sandbox Mode",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (currentLang == "ar") "توليد بيانات وهمية دون إرسال طلبات حقيقية للبوابة" else "Uses local mock dataset without hitting live gateway",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.45f)
                )
            }
            Switch(
                checked = isDemoMode,
                onCheckedChange = { onToggleDemo(it) }
            )
        }

        // Clear All Data Action
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFEF4444).copy(alpha = 0.1f),
            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showConfirmDelete = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (currentLang == "ar") "مسح جميع البيانات المحلية (Clear Local DB)" else "Purge All Local DB Data",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF4444)
                )
            }
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
