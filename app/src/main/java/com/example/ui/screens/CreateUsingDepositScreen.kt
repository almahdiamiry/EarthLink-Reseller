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
                        text = if (isAr) "إنشاء مشترك دائم بالرصيد" else "Issue Paid Subscriber",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = if (isAr) "تفعيل باقة واشتراك شهري رسمي مع خصم القيمة من الصندوق" else "Official monthly plan activation deducting fee from deposit",
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

                // Section 1: Subscriber Profile Info
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
                            label = { Text(if (isAr) "الاسم الكامل" else "Full Name") },
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
                            label = { Text(if (isAr) "رقم الهاتف" else "Phone Number") },
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

                // Section 2: Package Selection & Cost preview
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
                            text = if (isAr) "باقة الاشتراك" else "Subscription Package",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )

                        if (pkgs.isEmpty()) {
                            Text(
                                text = if (isAr) "جاري تحميل الباقات المتاحة..." else "Loading packages...",
                                fontSize = 12.sp,
                                color = Color(0xFF8E8E93)
                            )
                        } else {
                            pkgs.forEach { pkg ->
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

                                        if (pkg.price != null && pkg.price > 0.0) {
                                            Text(
                                                text = formatIqd(pkg.price),
                                                color = Color(0xFF0A84FF),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Cost Estimation
                        if (costPreview != null && selectedPkgIndex != -1) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0A84FF).copy(alpha = 0.08f),
                                border = BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.25f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isAr) "تكلفة التفعيل من الصندوق:" else "Deduction from Deposit:",
                                        fontSize = 12.sp,
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = formatIqd(costPreview!!),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0A84FF)
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
                            text = if (isAr) "جاري تفعيل المشترك..." else "Issuing paid subscriber...",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                text = if (isAr) "تفعيل المشترك بالرصيد" else "Issue Paid Subscriber",
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
