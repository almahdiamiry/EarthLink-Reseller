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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EarthlinkApp
import com.example.ui.viewmodels.EarthlinkSearchViewModel

@Composable
fun CreateUsingDepositScreen(
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
    val costPreview by viewModel.costPreview.collectAsStateWithLifecycle()

    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selectedPkgIndex) {
        if (selectedPkgIndex != -1) {
            viewModel.previewPackageCost(selectedPkgIndex)
        }
    }

    if (showConfirmDialog) {
        val selectedPkgName = pkgs.find { it.accountIndex == selectedPkgIndex }?.accountName ?: ""
        val costStr = if (costPreview != null) formatIqd(costPreview!!) else if (isAr) "سعر الباقة المحسوب" else "calculated tier price"
        ConfirmationDialog(
            title = if (isAr) "تأكيد إنشاء مشترك مدفوع" else "Confirm Paid Account Creation",
            message = if (isAr) "هل تود إنشاء وتفعيل المشترك $userId على الباقة $selectedPkgName؟ سيتم خصم مبلغ $costStr من رصيد الصندوق."
            else "Generate paid subscription $userId with package $selectedPkgName? Consumes $costStr from your deposit balance.",
            needsPasswordField = true,
            onCancel = { showConfirmDialog = false },
            onConfirm = { pass ->
                showConfirmDialog = false
                viewModel.createUserUsingDeposit(userId, phone, name, selectedPkgIndex, pass)
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
                                .background(Color(0xFF30D158).copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp))
                                .border(1.dp, Color(0xFF30D158).copy(alpha = 0.3f), shape = RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = null,
                                tint = Color(0xFF30D158),
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (isAr) "إنشاء مشترك دائم بالرصيد" else "Issue Paid Subscriber",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = if (isAr) "تفعيل باقة واشتراك شهري رسمي يخصم سعر الفئة من رصيد الصندوق" else "Activate permanent paid plan. Deducts the tier fee directly from deposit.",
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

                // Card 1: Subscriber Profile Info
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
                            text = if (isAr) "بيانات المشترك الجديد" else "New Subscriber Information",
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
                            label = { Text(if (isAr) "الاسم الكامل للعميل" else "Customer Full Name") },
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
                            label = { Text(if (isAr) "رقم هاتف العميل" else "Customer Phone Number") },
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

                // Card 2: Package Selection & Cost preview
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
                            text = if (isAr) "اختيار باقة الاشتراك" else "Select Subscription Plan",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        if (pkgs.isEmpty()) {
                            Text(
                                text = if (isAr) "جاري تحميل الباقات المتاحة..." else "Loading available packages...",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        } else {
                            pkgs.forEach { pkg ->
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

                                        if (pkg.price != null && pkg.price > 0.0) {
                                            Text(
                                                text = formatIqd(pkg.price),
                                                color = Color(0xFF38BDF8),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Cost Estimation Card
                        if (costPreview != null && selectedPkgIndex != -1) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = Color(0xFF0288D1).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFF0288D1).copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isAr) "تكلفة التفعيل من الرصيد:" else "Deduction from Deposit:",
                                        fontSize = 13.sp,
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = formatIqd(costPreview!!),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF38BDF8)
                                    )
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
                        containerColor = Color(0xFF30D158),
                        disabledContainerColor = Color(0xFF30D158).copy(alpha = 0.35f)
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
                            text = if (isAr) "جاري تفعيل الاشتراك الدائم..." else "Issuing paid subscriber...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = if (isAr) "تفعيل المشترك بالرصيد الآن" else "Issue Paid Subscriber Now",
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
