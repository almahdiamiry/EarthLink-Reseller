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

@OptIn(ExperimentalMaterial3Api::class)
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
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF131A26),
                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f))
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.15f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (isAr) "تأكيد الاستبدال والمسح الشامل" else "Confirm Full Wipe & Replace",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isAr) "إجراء حساس لا يمكن التراجع عنه" else "Irreversible Operation",
                                    fontSize = 12.sp,
                                    color = Color(0xFFEF4444)
                                )
                            }
                        }

                        Text(
                            text = if (isAr)
                                "تحذير هام: هذا الخيار سيقوم بمسح واستبدال كافة المشتركين والسجلات والحركات المالية الحالية محلياً وفي المزامنة السحابية نهائياً، وتعيين الملف الذي ستختاره كقاعدة بيانات أساسية وجديدة للنظام.\n\nهل أنت متأكد من رغبتك في المتابعة؟"
                            else
                                "Warning: This will permanently wipe all existing local and cloud subscriber records and ledger history. The selected file will become the new canonical starting dataset. Do you want to proceed?",
                            fontSize = 13.sp,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 20.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showWipeWarning = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                            ) {
                                Text(if (isAr) "إلغاء" else "Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    showWipeWarning = false
                                    replaceData = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                            ) {
                                Text(if (isAr) "تأكيد واستبدال" else "Wipe & Replace", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF131A26),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f))
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(Color(0xFFF59E0B).copy(alpha = 0.15f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Undo,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (isAr) "تراجع عن عملية الاستيراد" else "Rollback Import Batch",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isAr) "حذف الحسابات والحركات المؤقتة" else "Revert Temporary Import",
                                    fontSize = 12.sp,
                                    color = Color(0xFFF59E0B)
                                )
                            }
                        }

                        Text(
                            text = if (isAr)
                                "سيتم حذف جميع حسابات المشتركين والحركات المالية التي تم إنشاؤها فقط عبر هذه الوجبة (Batch). هل تريد المتابعة والتراجع الآن؟"
                            else
                                "This will remove all subscriber accounts and payments created exclusively by this batch. Revert now?",
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
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                            ) {
                                Text(if (isAr) "إلغاء" else "Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
                            ) {
                                Text(if (isAr) "تراجع الآن" else "Revert Now", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
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
                .background(Color(0xFF090E15))
        ) {
            // 1. Top Modern Header Bar
            Surface(
                color = Color(0xFF101622),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color.White.copy(alpha = 0.05f), shape = CircleShape)
                            .testTag("import_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (isAr) "رجوع" else "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (isAr) "استيراد واستعادة البيانات" else "Import & Restore Data",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Surface(
                                color = Color(0xFF0288D1).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Color(0xFF0288D1).copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "uTower / DB",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (isAr) "ترحيل المشتركين والسجلات المالية بأمان" else "Migrate subscribers and financial ledgers safely",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 2. Info / Intro Banner Card
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF131A26),
                    border = BorderStroke(1.dp, Color(0xFF0288D1).copy(alpha = 0.18f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF0288D1), Color(0xFF005691))
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isAr) "استيراد مباشر وشامل" else "Direct Import & Extraction",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAr)
                                    "يدعم استخراج ملفات uTower بصيغة (.tgz) أو النسخ الاحتياطية المباشرة (.zip / .db) ونقل الحسابات والديون تلقائياً."
                                else
                                    "Supports importing uTower (.tgz) archives or direct database backup files (.zip / .db) with automatic debt mapping.",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                // 3. Error Banner Notification (if present)
                if (error != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = error ?: "",
                                color = Color(0xFFFCA5A5),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // 4. Main Selection & Import Card
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF131A26),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (isAr) "وضع الاستيراد والدمج" else "Import & Merge Mode",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        // Option 1: Merge
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (!replaceData) Color(0xFF0288D1).copy(alpha = 0.12f) else Color(0xFF0D121B),
                            border = BorderStroke(
                                1.dp,
                                if (!replaceData) Color(0xFF0288D1).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { replaceData = false }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = !replaceData,
                                    onClick = { replaceData = false },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFF38BDF8),
                                        unselectedColor = Color(0xFF64748B)
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = if (isAr) "دمج السجلات (موصى به)" else "Merge with current data (Recommended)",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (!replaceData) Color(0xFF38BDF8) else Color.White
                                        )
                                        Surface(
                                            color = Color(0xFF10B981).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isAr) "آمن" else "Safe",
                                                color = Color(0xFF10B981),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isAr) "إضافة وتحديث المشتركين والحركات المالية دون مسح أي بيانات سابقة." else "Adds new subscribers and ledgers without erasing previous records.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }

                        // Option 2: Wipe & Replace
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (replaceData) Color(0xFFEF4444).copy(alpha = 0.12f) else Color(0xFF0D121B),
                            border = BorderStroke(
                                1.dp,
                                if (replaceData) Color(0xFFEF4444).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.06f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!replaceData) {
                                        showWipeWarning = true
                                    } else {
                                        replaceData = false
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                RadioButton(
                                    selected = replaceData,
                                    onClick = {
                                        if (!replaceData) showWipeWarning = true else replaceData = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = Color(0xFFEF4444),
                                        unselectedColor = Color(0xFF64748B)
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = if (isAr) "استبدال ومسح شامل (تعيين أساسي)" else "Replace all data (Full Wipe)",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (replaceData) Color(0xFFEF4444) else Color.White
                                        )
                                        Surface(
                                            color = Color(0xFFEF4444).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isAr) "مسح كامل" else "Wipe",
                                                color = Color(0xFFEF4444),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isAr) "مسح كافة البيانات السابقة محلياً وسحابياً وجعل هذا الملف هو القاعدة الأساسية." else "Wipes existing data and sets this file as the new canonical dataset.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }

                        // Supported Formats Pills
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isAr) "الصيغ المدعومة:" else "Supported:",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                            listOf(".tgz (uTower)", ".zip", ".db").forEach { format ->
                                Surface(
                                    color = Color.White.copy(alpha = 0.05f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = format,
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8),
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Upload CTA Button
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("select_backup_file_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (replaceData) Color(0xFFDC2626) else Color(0xFF0288D1),
                                disabledContainerColor = Color(0xFF1E293B)
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (isAr) "جاري المعالجة والاستيراد الفعلي..." else "Processing & Importing...",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.UploadFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAr) "اختيار ملف الاستيراد (.tgz / .zip / .db)" else "Select Backup File (.tgz / .zip / .db)",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        if (isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF38BDF8),
                                trackColor = Color.White.copy(alpha = 0.08f)
                            )
                        }
                    }
                }

                // 5. Import Result Feedback Card (if available)
                val pre = importResult
                if (pre != null) {
                    val isSuccess = pre.success
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF131A26),
                        border = BorderStroke(
                            1.dp,
                            if (isSuccess) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFFEF4444).copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isSuccess) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                        contentDescription = null,
                                        tint = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isSuccess) {
                                            if (isAr) "اكتمل الاستيراد بنجاح" else "Import Completed Successfully"
                                        } else {
                                            if (isAr) "فشل عملية الاستيراد" else "Import Failed"
                                        },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSuccess) Color(0xFF10B981) else Color(0xFFEF4444)
                                    )
                                    if (!isSuccess && pre.errorMessage != null) {
                                        Text(
                                            text = pre.errorMessage ?: "",
                                            fontSize = 12.sp,
                                            color = Color(0xFFFCA5A5)
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                            // Stats Grid
                            Text(
                                text = if (isAr) "ملخص البيانات المعالجة:" else "Processed Summary:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFCBD5E1)
                            )

                            val stats = listOf(
                                Triple(if (isAr) "إجمالي المشتركين المكتشفين" else "Subscribers Found", "${pre.subscribersFound}", Icons.Default.Groups),
                                Triple(if (isAr) "مشتركين جدد تم إضافتهم" else "New Subscribers", "${pre.subscribersImported}", Icons.Default.PersonAdd),
                                Triple(if (isAr) "مشتركين تم دمجهم" else "Merged Subscribers", "${pre.subscribersMerged}", Icons.Default.SyncAlt),
                                Triple(if (isAr) "الحركات المالية والديون" else "Financial Ledger Entries", "${pre.transactionsImported}", Icons.Default.ReceiptLong),
                                Triple(if (isAr) "ملاحظات المشتركين" else "Subscriber Notes", "${pre.subscribersNotesImported}", Icons.Default.SpeakerNotes),
                                Triple(if (isAr) "عناوين Nano IP" else "Nano IP Addresses", "${pre.nanoIpsImported}", Icons.Default.Language)
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                stats.chunked(2).forEach { rowStats ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowStats.forEach { (label, value, icon) ->
                                            Surface(
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(12.dp),
                                                color = Color(0xFF0E141E),
                                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = icon,
                                                        contentDescription = null,
                                                        tint = Color(0xFF38BDF8),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Column {
                                                        Text(
                                                            text = value,
                                                            fontSize = 15.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                        Text(
                                                            text = label,
                                                            fontSize = 11.sp,
                                                            color = Color(0xFF94A3B8),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (rowStats.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            // Warnings / Errors notice
                            if (pre.warnings > 0 || pre.errors > 0) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f)),
                                    modifier = Modifier.clickable { showSkippedDetails = !showSkippedDetails }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.WarningAmber,
                                                    contentDescription = null,
                                                    tint = Color(0xFFF59E0B),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (isAr)
                                                        "تنبيهات الاستيراد (${pre.warnings} تخطي، ${pre.errors} أخطاء)"
                                                    else
                                                        "Import Warnings (${pre.warnings} skipped, ${pre.errors} errors)",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFF59E0B)
                                                )
                                            }
                                            Icon(
                                                imageVector = if (showSkippedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        if (showSkippedDetails) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = if (isAr)
                                                    "تحدث التنبيهات عادة عند تخطي حركات مالية لا يمكن ربطها بمشتركين محددين لمنع تكرار القيود، بينما الأخطاء تشير لملفات غير مطابقة."
                                                else
                                                    "Warnings indicate skipped transactions due to missing subscriber mappings. Errors indicate malformed payload entries.",
                                                fontSize = 11.sp,
                                                color = Color(0xFFCBD5E1),
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. Historical Import Batches
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (isAr) "سجل وجبات الاستيراد السابقة" else "Historical Import Batches",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    if (batches.isNotEmpty()) {
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${batches.size}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (batches.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF131A26),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderZip,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = if (isAr) "لا توجد وجبات استيراد مسجلة حتى الآن" else "No batch logs imported yet",
                                fontSize = 13.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = if (isAr) "عند استيراد أي ملف، سيظهر السجل التاريخي هنا تلقائياً." else "Imported archives and database batches will appear here.",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    }
                } else {
                    batches.forEach { batch ->
                        val isAccepted = batch.status == "completed" || batch.status == "accepted"
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd · hh:mm a", Locale.getDefault())
                        val formattedDate = dateFormat.format(Date(batch.createdAt))

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF131A26),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .background(Color(0xFF0288D1).copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            text = batch.fileName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
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
                                                fontSize = 12.sp,
                                                color = Color(0xFF38BDF8),
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(text = "•", color = Color(0xFF64748B), fontSize = 10.sp)
                                            Text(
                                                text = if (isAr) "ديون: ${formatIqd(batch.totalDebtIqd)}" else "Debt: ${formatIqd(batch.totalDebtIqd)}",
                                                fontSize = 12.sp,
                                                color = if (batch.totalDebtIqd > 0) Color(0xFFFCA5A5) else Color(0xFF94A3B8)
                                            )
                                        }
                                        Text(
                                            text = formattedDate,
                                            fontSize = 11.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                if (!isAccepted) {
                                    IconButton(
                                        onClick = { showRollbackConfirmId = batch.id },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color(0xFFEF4444).copy(alpha = 0.15f), shape = CircleShape)
                                            .testTag("rollback_batch_button_${batch.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Undo,
                                            contentDescription = if (isAr) "تراجع عن الوجبة" else "Rollback Batch",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.25f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = if (isAr) "سجل معتمد" else "Accepted",
                                                color = Color(0xFF10B981),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}


// --- CREATE TEST USER SCREEN ---
