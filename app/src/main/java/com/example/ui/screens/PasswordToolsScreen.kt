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
fun PasswordToolsScreen(
    userIndex: Int,
    userId: String,
    viewModel: EarthlinkSearchViewModel,
    currentLang: String,
    onClose: () -> Unit
) {
    val revealedUser by viewModel.revealedUserPass.collectAsStateWithLifecycle()
    val revealedAcc by viewModel.revealedAccountPass.collectAsStateWithLifecycle()
    val isActionLoading by viewModel.isActionLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.actionSuccess.collectAsStateWithLifecycle()

    var showResetUserPass by rememberSaveable { mutableStateOf(false) }
    var showResetAccPass by rememberSaveable { mutableStateOf(false) }

    var inputUserResetPass by rememberSaveable { mutableStateOf("") }
    var inputAccResetPass by rememberSaveable { mutableStateOf("") }

    // Load passwords automatically when entering
    LaunchedEffect(userIndex, userId) {
        viewModel.clearErrorAndSuccess()
        viewModel.clearRevealedPasswords()
        viewModel.revealUserPassword(userIndex, userId)
        viewModel.revealAccountPassword(userIndex, userId)
    }

    // Auto dismiss and clear inputs on successful action
    LaunchedEffect(success) {
        if (success != null && (success!!.contains("password") || success!!.contains("كلمة مرور") || success!!.contains("الاشتراك") || success!!.contains("البوابة"))) {
            inputUserResetPass = ""
            inputAccResetPass = ""
            kotlinx.coroutines.delay(1200)
            viewModel.clearErrorAndSuccess()
            onClose()
        }
    }

    // Clear password state when leaving the screen
    DisposableEffect(userIndex, userId) {
        onDispose {
            viewModel.clearRevealedPasswords()
            viewModel.clearErrorAndSuccess()
        }
    }

    if (showResetUserPass) {
        ConfirmationDialog(
            title = if (currentLang == "ar") "تأكيد تغيير كلمة مرور البوابة" else "Confirm Portal Password Change",
            message = if (currentLang == "ar") "سيتم إعادة تعيين كلمة مرور البوابة للمشترك $userId. هل ترغب بالاستمرار؟" else "Resetting the portal credentials for $userId. Continue?",
            needsPasswordField = false,
            onCancel = { showResetUserPass = false },
            onConfirm = {
                showResetUserPass = false
                viewModel.changeUserPassword(userIndex, userId, inputUserResetPass)
            }
        )
    }

    if (showResetAccPass) {
        ConfirmationDialog(
            title = if (currentLang == "ar") "تأكيد تغيير كلمة مرور حساب PPPoE" else "Confirm PPPoE Account Password Change",
            message = if (currentLang == "ar") "هل أنت متأكد من رغبتك في تغيير كلمة مرور اشتراك الـ PPPoE للمشترك $userId؟" else "Are you sure you want to change the PPPoE broadband password for $userId?",
            needsPasswordField = false,
            onCancel = { showResetAccPass = false },
            onConfirm = {
                showResetAccPass = false
                viewModel.changeAccountPassword(userIndex, userId, inputAccResetPass)
            }
        )
    }

    Dialog(onDismissRequest = onClose) {
        CompositionLocalProvider(
            LocalLayoutDirection provides (if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (currentLang == "ar") "أدوات كلمة المرور" else "Password Tools",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        IconButton(onClick = onClose) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = if (currentLang == "ar") "إغلاق" else "Close"
                            )
                        }
                    }

                    if (error != null) {
                        Surface(
                            color = Color(0xFFFF453A).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFFF453A)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = error ?: "",
                                color = Color(0xFFFF453A),
                                modifier = Modifier.padding(10.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    if (success != null) {
                        Surface(
                            color = Color(0xFF30D158).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF30D158)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = success ?: "",
                                color = Color(0xFF30D158),
                                modifier = Modifier.padding(10.dp),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    HorizontalDivider()

                    // --- Part 1: Portal Password ---
                    Text(
                        text = if (currentLang == "ar") "بيانات مرور بوابة المستخدم" else "User Portal Credentials",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    val rUser = revealedUser
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rUser == null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (currentLang == "ar") "جاري جلب كلمة المرور..." else "Fetching password...",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            val isEmpty = rUser.isBlank()
                            val displayText = if (isEmpty) {
                                if (currentLang == "ar") "لا يوجد" else "None"
                            } else {
                                rUser
                            }
                            Text(
                                text = if (currentLang == "ar") "كلمة المرور: $displayText" else "Password: $displayText",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isEmpty) Color(0xFF8E8E93) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputUserResetPass,
                            onValueChange = { inputUserResetPass = it },
                            label = { Text(if (currentLang == "ar") "كلمة مرور البوابة الجديدة" else "New Portal Pass") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        )
                        IconButton(
                            onClick = { showResetUserPass = true },
                            enabled = inputUserResetPass.isNotEmpty() && !isActionLoading,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = if (currentLang == "ar") "حفظ" else "Save"
                            )
                        }
                    }

                    HorizontalDivider()

                    // --- Part 2: Broadband Account ---
                    Text(
                        text = if (currentLang == "ar") "حساب الاشتراك المنزلي (PPPoE)" else "Broadband account (PPPoE)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    val rAcc = revealedAcc
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rAcc == null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (currentLang == "ar") "جاري جلب كلمة مرور خط PPPoE..." else "Fetching PPPoE password...",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 14.sp
                                )
                            }
                        } else {
                            val isEmpty = rAcc.isBlank()
                            val displayText = if (isEmpty) {
                                if (currentLang == "ar") "لا يوجد" else "None"
                            } else {
                                rAcc
                            }
                            Text(
                                text = if (currentLang == "ar") "كلمة المرور: $displayText" else "Password: $displayText",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = if (isEmpty) Color(0xFF8E8E93) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputAccResetPass,
                            onValueChange = { inputAccResetPass = it },
                            label = { Text(if (currentLang == "ar") "كلمة مرور الخط الجديدة" else "New Line Pass") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                        )
                        IconButton(
                            onClick = { showResetAccPass = true },
                            enabled = inputAccResetPass.isNotEmpty() && !isActionLoading,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = if (currentLang == "ar") "حفظ" else "Save"
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- LOCAL ROOM ACCOUNTS BILLING SCREEN ---
