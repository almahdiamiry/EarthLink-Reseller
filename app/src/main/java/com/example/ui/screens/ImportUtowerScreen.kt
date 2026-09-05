package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EarthlinkApp
import com.example.core.model.ImportBatch
import com.example.core.sync.ImportResult
import com.example.ui.viewmodels.LocalAccountsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val prefs = remember(context) { (context.applicationContext as EarthlinkApp).preferenceManager }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = prefs.getLanguage())
    val isAr = currentLang == "ar"
    val layoutDir = if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr

    var replaceData by rememberSaveable { mutableStateOf(false) }
    var showWipeWarning by rememberSaveable { mutableStateOf(false) }
    var showRollbackConfirmId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSkippedDetails by rememberSaveable { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importTgzFile(uri, context, replaceData)
            replaceData = false
        }
    }

    // --- DIALOGS ---
    if (showWipeWarning) {
        Dialog(
            onDismissRequest = { showWipeWarning = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF141922),
                border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.35f))
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFFF453A).copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFF453A),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = if (isAr) "تأكيد الاستبدال الشامل" else "Confirm Full Replace",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = if (isAr)
                                "سيتم مسح جميع المشتركين والحركات المالية السابقة محلياً وسحابياً واستبدالها بالبيانات الموجودة في الملف المختار. هل أنت متأكد؟"
                            else
                                "This will permanently erase all local and cloud subscribers and ledger history and replace them with the selected file. Continue?",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 19.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showWipeWarning = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8E8E93))
                            ) {
                                Text(if (isAr) "إلغاء" else "Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    showWipeWarning = false
                                    replaceData = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
                            ) {
                                Text(if (isAr) "تأكيد المسح" else "Wipe & Replace", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRollbackConfirmId != null) {
        Dialog(
            onDismissRequest = { showRollbackConfirmId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF141922),
                border = BorderStroke(1.dp, Color(0xFFFF9F0A).copy(alpha = 0.35f))
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFFF9F0A).copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Undo,
                                    contentDescription = null,
                                    tint = Color(0xFFFF9F0A),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = if (isAr) "تراجع عن وجبة الاستيراد" else "Rollback Import Batch",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Text(
                            text = if (isAr)
                                "سيتم إزالة المشتركين والحركات التي تم إنشاؤها عبر هذه الوجبة فقط. هل تريد المتابعة؟"
                            else
                                "This will remove subscribers and ledgers created exclusively by this batch. Proceed?",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 19.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showRollbackConfirmId = null },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF8E8E93))
                            ) {
                                Text(if (isAr) "إلغاء" else "Cancel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Button(
                                onClick = {
                                    val id = showRollbackConfirmId
                                    showRollbackConfirmId = null
                                    if (id != null) {
                                        viewModel.rollbackBatch(id)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F0A))
                            ) {
                                Text(if (isAr) "تراجع" else "Revert", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }
            }
        }
    }

    // --- MAIN SCREEN CONTENT ---
    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D12))
                .statusBarsPadding()
        ) {
            // Clean iOS Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White.copy(alpha = 0.08f), shape = CircleShape)
                        .testTag("import_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (isAr) "رجوع" else "Back",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = if (isAr) "استيراد البيانات" else "Import Data",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Error Banner (if present)
                if (error != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFF453A).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = error ?: "",
                                color = Color(0xFFFF453A),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // 1. IMPORT MODE & FILE SELECTION (Apple Inset Grouped)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isAr) "خيارات الاستيراد" else "IMPORT OPTIONS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF141922),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            // Merge Option Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { replaceData = false }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = !replaceData,
                                    onClick = { replaceData = false },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF0A84FF),
                                        unselectedColor = Color(0xFF636366)
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (isAr) "دمج مع البيانات الحالية" else "Merge with existing data",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Surface(
                                            color = Color(0xFF30D158).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isAr) "موصى به" else "Recommended",
                                                color = Color(0xFF30D158),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (isAr) "إضافة وتحديث السجلات دون حذف أي بيانات سابقة" else "Add and update records without deleting existing data",
                                        fontSize = 12.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                            // Replace Option Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (!replaceData) showWipeWarning = true else replaceData = false
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RadioButton(
                                    selected = replaceData,
                                    onClick = {
                                        if (!replaceData) showWipeWarning = true else replaceData = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFFFF453A),
                                        unselectedColor = Color(0xFF636366)
                                    ),
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = if (isAr) "مسح واستبدال شامل" else "Wipe & Replace all",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (replaceData) Color(0xFFFF453A) else Color.White
                                        )
                                        if (replaceData) {
                                            Surface(
                                                color = Color(0xFFFF453A).copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (isAr) "مسح كامل" else "Full Wipe",
                                                    color = Color(0xFFFF453A),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (isAr) "مسح كافة البيانات الحالية وتعيين الملف كقاعدة جديدة" else "Wipe current database and set file as canonical",
                                        fontSize = 12.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                            // File Picker CTA
                            Button(
                                onClick = { filePickerLauncher.launch("*/*") },
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("select_backup_file_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (replaceData) Color(0xFFFF453A) else Color(0xFF0A84FF),
                                    disabledContainerColor = Color(0xFF2C2C2E)
                                )
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isAr) "جاري الاستيراد..." else "Importing...",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isAr) "اختيار ملف (.tgz / .zip / .db)" else "Choose File (.tgz / .zip / .db)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }

                            if (isLoading) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFF0A84FF),
                                    trackColor = Color.White.copy(alpha = 0.08f)
                                )
                            }
                        }
                    }
                }

                // 2. IMPORT RESULT SUMMARY (if present)
                val pre = importResult
                if (pre != null) {
                    val isSuccess = pre.success
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isAr) "ملخص النتيجة" else "RESULT SUMMARY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF141922),
                            border = BorderStroke(
                                1.dp,
                                if (isSuccess) Color(0xFF30D158).copy(alpha = 0.3f) else Color(0xFFFF453A).copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .background(
                                                if (isSuccess) Color(0xFF30D158) else Color(0xFFFF453A),
                                                shape = RoundedCornerShape(8.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.Close,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isSuccess) {
                                                if (isAr) "اكتمل الاستيراد بنجاح" else "Import Completed"
                                            } else {
                                                if (isAr) "فشل الاستيراد" else "Import Failed"
                                            },
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        if (!isSuccess && pre.errorMessage != null) {
                                            Text(
                                                text = pre.errorMessage ?: "",
                                                fontSize = 12.sp,
                                                color = Color(0xFFFF453A)
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                                val stats = listOf(
                                    Pair(if (isAr) "المكتشفين" else "Found", "${pre.subscribersFound}"),
                                    Pair(if (isAr) "مشتركين جدد" else "New", "${pre.subscribersImported}"),
                                    Pair(if (isAr) "تم دمجهم" else "Merged", "${pre.subscribersMerged}"),
                                    Pair(if (isAr) "الحركات المالية" else "Transactions", "${pre.transactionsImported}"),
                                    Pair(if (isAr) "الملاحظات" else "Notes", "${pre.subscribersNotesImported}"),
                                    Pair(if (isAr) "Nano IPs" else "Nano IPs", "${pre.nanoIpsImported}")
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    stats.chunked(3).forEach { rowStats ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowStats.forEach { (label, value) ->
                                                Surface(
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = Color(0xFF0E131B),
                                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(8.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(
                                                            text = value,
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                        Text(
                                                            text = label,
                                                            fontSize = 11.sp,
                                                            color = Color(0xFF8E8E93),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (pre.warnings > 0 || pre.errors > 0) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { showSkippedDetails = !showSkippedDetails }
                                            .background(Color(0xFFFF9F0A).copy(alpha = 0.1f))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = if (isAr) "تنبيهات: ${pre.warnings} تخطي · ${pre.errors} أخطاء" else "Warnings: ${pre.warnings} skipped · ${pre.errors} errors",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFFF9F0A)
                                        )
                                        Icon(
                                            imageVector = if (showSkippedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = Color(0xFFFF9F0A),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. PREVIOUS IMPORTS (Apple Inset Grouped List)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isAr) "السجلات السابقة" else "PREVIOUS IMPORTS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF8E8E93),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    if (batches.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF141922),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderZip,
                                    contentDescription = null,
                                    tint = Color(0xFF636366),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = if (isAr) "لا توجد عمليات استيراد سابقة" else "No previous import batches",
                                    fontSize = 13.sp,
                                    color = Color(0xFF8E8E93)
                                )
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF141922),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd · hh:mm a", Locale.getDefault()) }
                                batches.forEachIndexed { index, batch ->
                                    val isAccepted = batch.status == "completed" || batch.status == "accepted"
                                    val formattedDate = dateFormat.format(Date(batch.createdAt))

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .background(Color(0xFF0A84FF).copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = Color(0xFF0A84FF),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = batch.fileName,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = if (isAr) "${batch.accountsImported} مشترك" else "${batch.accountsImported} accounts",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF0A84FF)
                                                    )
                                                    Text(text = "•", color = Color(0xFF636366), fontSize = 9.sp)
                                                    Text(
                                                        text = formattedDate,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF8E8E93)
                                                    )
                                                }
                                            }
                                        }

                                        if (!isAccepted) {
                                            IconButton(
                                                onClick = { showRollbackConfirmId = batch.id },
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .background(Color(0xFFFF453A).copy(alpha = 0.15f), shape = CircleShape)
                                                    .testTag("rollback_batch_button_${batch.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Undo,
                                                    contentDescription = if (isAr) "تراجع" else "Rollback",
                                                    tint = Color(0xFFFF453A),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (index < batches.size - 1) {
                                        HorizontalDivider(
                                            color = Color.White.copy(alpha = 0.05f),
                                            modifier = Modifier.padding(horizontal = 14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}


// --- CREATE TEST USER SCREEN ---
