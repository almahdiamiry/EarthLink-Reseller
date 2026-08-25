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
                .background(Color(0xFF0B0F14))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Banner Card
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF11161F),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color(0xFF0288D1).copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF0288D1).copy(alpha = 0.3f), shape = RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (isAr) "إنشاء مشترك تجريبي (24 ساعة)" else "Issue Trial User (24h)",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (isAr) "حساب تجريبي مجاني لغرض الفحص والربط لا يخصم من رصيد الصندوق" else "Free 24h test account. Does not deduct from deposit balance.",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.55f),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // Error / Success Feedback Banners
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFCA5A5),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = error ?: "",
                                color = Color(0xFFFCA5A5),
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
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(20.dp)
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

                // Card 1: Subscriber Info Form
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF11161F),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = if (isAr) "بيانات المشترك التجريبي" else "Subscriber Information",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        // UserID
                        OutlinedTextField(
                            value = userId,
                            onValueChange = { userId = it },
                            label = { Text(if (isAr) "اسم المستخدم (UserID)" else "Username (UserID)") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF38BDF8))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF171E29),
                                unfocusedContainerColor = Color(0xFF171E29),
                                focusedBorderColor = Color(0xFF0288D1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedLabelColor = Color(0xFF38BDF8),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Full Name
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(if (isAr) "الاسم الكامل للمشترك" else "Subscriber Full Name") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Badge, contentDescription = null, tint = Color(0xFF38BDF8))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF171E29),
                                unfocusedContainerColor = Color(0xFF171E29),
                                focusedBorderColor = Color(0xFF0288D1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedLabelColor = Color(0xFF38BDF8),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Phone
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(if (isAr) "رقم الهاتف (اختياري)" else "Phone Number (Optional)") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Outlined.Phone, contentDescription = null, tint = Color(0xFF38BDF8))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF171E29),
                                unfocusedContainerColor = Color(0xFF171E29),
                                focusedBorderColor = Color(0xFF0288D1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedLabelColor = Color(0xFF38BDF8),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Card 2: Package Selection
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF11161F),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (isAr) "اختيار باقة التجربة" else "Select Test Package",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        val testablePkgs = pkgs.filter { it.canTest == true }
                        if (testablePkgs.isEmpty()) {
                            Text(
                                text = if (isAr) "جاري تحميل الباقات المتاحة..." else "Loading available packages...",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        } else {
                            testablePkgs.forEach { pkg ->
                                val isSel = selectedPkgIndex == pkg.accountIndex
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSel) Color(0xFF0288D1).copy(alpha = 0.16f) else Color(0xFF171E29),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSel) Color(0xFF0288D1) else Color.White.copy(alpha = 0.06f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { selectedPkgIndex = pkg.accountIndex }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                                                    selectedColor = Color(0xFF38BDF8),
                                                    unselectedColor = Color.White.copy(alpha = 0.3f)
                                                )
                                            )
                                            Text(
                                                text = pkg.accountName,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                fontSize = 14.sp,
                                                color = if (isSel) Color.White else Color.White.copy(alpha = 0.8f)
                                            )
                                        }

                                        Surface(
                                            color = Color(0xFF30D158).copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f))
                                        ) {
                                            Text(
                                                text = if (isAr) "تجريبي مجاني" else "Free Trial",
                                                color = Color(0xFF30D158),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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
                        containerColor = Color(0xFF0288D1),
                        disabledContainerColor = Color(0xFF0288D1).copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    if (isActionLoading) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isAr) "جاري تفعيل الحساب التجريبي..." else "Issuing trial account...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = if (isAr) "تفعيل الحساب التجريبي الآن" else "Issue Trial Account Now",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
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
