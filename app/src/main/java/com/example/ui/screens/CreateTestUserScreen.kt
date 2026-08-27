package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EarthlinkApp
import com.example.ui.viewmodels.EarthlinkSearchViewModel

@Composable
fun CreateTestUserScreen(
    viewModel: EarthlinkSearchViewModel
) {
    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as EarthlinkApp).preferenceManager }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()
    val isAr = currentLang == "ar"

    var userId by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedPkgIndex by rememberSaveable { mutableStateOf(-1) }

    val pkgs by viewModel.packages.collectAsStateWithLifecycle()
    val isActionLoading by viewModel.isActionLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.actionSuccess.collectAsStateWithLifecycle()

    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (showConfirmDialog) {
        val selectedPkgName = pkgs.find { it.accountIndex == selectedPkgIndex }?.accountName ?: ""
        ConfirmationDialog(
            title = if (isAr) "تأكيد إنشاء حساب تجريبي" else "Confirm Trial User Creation",
            message = if (isAr) "هل تود إنشاء الحساب التجريبي $userId وربطه بالباقة $selectedPkgName؟ تنتهي صلاحية الحساب تلقائياً بعد 24 ساعة بدون خصم."
            else "Generate trial active account $userId linked to package $selectedPkgName? Trial expires autonomously in 24 hours.",
            needsPasswordField = false,
            onCancel = { showConfirmDialog = false },
            onConfirm = {
                showConfirmDialog = false
                viewModel.createTestUser(userId, phone, name, selectedPkgIndex)
            }
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D12))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Title
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isAr) "إنشاء مشترك تجريبي (24 ساعة)" else "Issue Trial User (24h)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isAr) "حساب فحص وربط مجاني فعال لمدة 24 ساعة بدون خصم من الرصيد" else "Free 24h testing account without reseller balance deduction",
                        fontSize = 12.sp,
                        color = Color(0xFF8E8E93)
                    )
                }

                // Error / Success Feedback Banners
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFFF453A).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = error ?: "",
                                color = Color(0xFFFF453A),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = success != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = Color(0xFF30D158).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = success ?: "",
                                color = Color(0xFF30D158),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Section 1: Subscriber Info Form
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF141922),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (isAr) "بيانات المشترك" else "Subscriber Information",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        // UserID
                        OutlinedTextField(
                            value = userId,
                            onValueChange = { userId = it },
                            label = { Text(if (isAr) "اسم المستخدم (UserID)" else "Username (UserID)") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF0E131B),
                                unfocusedContainerColor = Color(0xFF0E131B),
                                focusedBorderColor = Color(0xFF0A84FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                                focusedLabelColor = Color(0xFF0A84FF),
                                unfocusedLabelColor = Color(0xFF8E8E93),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF0A84FF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Full Name
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(if (isAr) "الاسم الكامل (اختياري)" else "Subscriber Full Name (Optional)") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Badge, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF0E131B),
                                unfocusedContainerColor = Color(0xFF0E131B),
                                focusedBorderColor = Color(0xFF0A84FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                                focusedLabelColor = Color(0xFF0A84FF),
                                unfocusedLabelColor = Color(0xFF8E8E93),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF0A84FF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Phone
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(if (isAr) "رقم الهاتف (اختياري)" else "Phone Number (Optional)") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Phone, contentDescription = null, tint = Color(0xFF8E8E93), modifier = Modifier.size(18.dp))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                focusManager.clearFocus(force = true)
                            }),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF0E131B),
                                unfocusedContainerColor = Color(0xFF0E131B),
                                focusedBorderColor = Color(0xFF0A84FF),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
                                focusedLabelColor = Color(0xFF0A84FF),
                                unfocusedLabelColor = Color(0xFF8E8E93),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF0A84FF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Section 2: Package Selection
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF141922),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (isAr) "باقة التجربة (24 ساعة)" else "Trial Package (24h)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        val testablePkgs = pkgs.filter { it.canTest == true }
                        if (testablePkgs.isEmpty()) {
                            Text(
                                text = if (isAr) "جاري تحميل الباقات المتاحة..." else "Loading packages...",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93)
                            )
                        } else {
                            testablePkgs.forEach { pkg ->
                                val isSel = selectedPkgIndex == pkg.accountIndex
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSel) Color(0xFF0A84FF).copy(alpha = 0.12f) else Color(0xFF0E131B),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSel) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.06f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { selectedPkgIndex = pkg.accountIndex }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            RadioButton(
                                                selected = isSel,
                                                onClick = null,
                                                colors = RadioButtonDefaults.colors(
                                                    selectedColor = Color(0xFF0A84FF),
                                                    unselectedColor = Color.White.copy(alpha = 0.3f)
                                                )
                                            )
                                            Text(
                                                text = pkg.accountName,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = if (isSel) Color.White else Color.White.copy(alpha = 0.85f)
                                            )
                                        }

                                        Surface(
                                            color = Color(0xFF0A84FF).copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.3f))
                                        ) {
                                            Text(
                                                text = if (isAr) "مجاني" else "Free",
                                                color = Color(0xFF0A84FF),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Action Button
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        showConfirmDialog = true
                    },
                    enabled = userId.isNotBlank() && selectedPkgIndex != -1 && !isActionLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0A84FF),
                        disabledContainerColor = Color(0xFF0A84FF).copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isActionLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAr) "جاري تفعيل الحساب التجريبي..." else "Issuing trial account...",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = if (isAr) "تفعيل الحساب التجريبي (مجاناً)" else "Issue Free Trial Account",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
