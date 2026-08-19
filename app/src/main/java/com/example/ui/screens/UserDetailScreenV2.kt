package com.example.ui.screens

import android.content.Intent
import com.example.core.util.AppBuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.EarthlinkApp
import com.alamiry.earthlinkreseller.R
import com.example.ui.viewmodels.EarthlinkSearchViewModel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailScreenV2(
    userIndex: Int,
    userId: String? = null,
    viewModel: EarthlinkSearchViewModel,
    lang: String = "ar",
    onBack: () -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onBack)

    val detail by viewModel.selectedUser.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isActionLoading by viewModel.isActionLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val success by viewModel.actionSuccess.collectAsStateWithLifecycle()

    val repo = remember { viewModel.localAccountRepository }
    val targetUsername = detail?.userID?.trim() ?: ""
    val matchingAccountFlow = remember(targetUsername) {
        if (targetUsername.isNotEmpty()) {
            repo.getAccountByUsernameOrId(targetUsername)
        } else {
            emptyFlow()
        }
    }
    val matchingAccount by matchingAccountFlow.collectAsStateWithLifecycle(initialValue = null)

    val audit = remember { viewModel.audit }
    val coroutineScope = rememberCoroutineScope()

    var showRefillDialog by rememberSaveable { mutableStateOf(false) }
    var showDepositDialog by rememberSaveable { mutableStateOf(false) }
    var showExtendDialog by rememberSaveable { mutableStateOf(false) }
    var showPassToolsDialog by rememberSaveable { mutableStateOf(false) }
    var showDebtDialog by rememberSaveable { mutableStateOf(false) }
    var showHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showNotesDialog by rememberSaveable { mutableStateOf(false) }
    var showShareDialog by rememberSaveable { mutableStateOf(false) }

    var showEditPackageDialog by rememberSaveable { mutableStateOf(false) }
    var showEditDisplayNameDialog by rememberSaveable { mutableStateOf(false) }
    var showStopUserDialog by rememberSaveable { mutableStateOf(false) }
    var showEditCustomIpDialog by rememberSaveable { mutableStateOf(false) }

    val prefs = remember { viewModel.prefs }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = lang)
    val context = LocalContext.current

    var ledgerList by remember { mutableStateOf<List<com.example.core.model.LocalLedgerEntry>>(emptyList()) }
    LaunchedEffect(matchingAccount?.id, showHistoryDialog) {
        val currentAcc = matchingAccount
        if (showHistoryDialog && currentAcc != null) {
            viewModel.localLedgerRepository.getLedgerForAccount(currentAcc.id).collect {
                ledgerList = it
            }
        }
    }

    val isDemoMode by viewModel.prefs.demoModeFlow.collectAsStateWithLifecycle()
    val finalLedgerList = remember(ledgerList, showHistoryDialog, isDemoMode) {
        if (ledgerList.isEmpty() && isDemoMode && AppBuildConfig.DEBUG) {
            listOf(
                com.example.core.model.LocalLedgerEntry(
                    id = "mock1",
                    accountId = "mock",
                    typeRaw = "gave",
                    amountIqd = 40000.0,
                    debtAfterIqd = 0.0,
                    note = "تسديد 40,000 د.ع",
                    occurredAt = 1777749970000L // 2026-05-02 20:46:10
                ),
                com.example.core.model.LocalLedgerEntry(
                    id = "mock2",
                    accountId = "mock",
                    typeRaw = "took",
                    amountIqd = 40000.0,
                    debtAfterIqd = 40000.0,
                    note = "تجديد اشتراك بقيمة : 40,000 د.ع",
                    occurredAt = 1776100273000L // 2026-04-13 20:31:13
                ),
                com.example.core.model.LocalLedgerEntry(
                    id = "mock3",
                    accountId = "mock",
                    typeRaw = "gave",
                    amountIqd = 85000.0,
                    debtAfterIqd = 0.0,
                    note = "تسديد 85,000 د.ع",
                    occurredAt = 1773432093000L // 2026-03-14 20:01:33
                ),
                com.example.core.model.LocalLedgerEntry(
                    id = "mock4",
                    accountId = "mock",
                    typeRaw = "took",
                    amountIqd = 45000.0,
                    debtAfterIqd = 85000.0,
                    note = "تجديد اشتراك بقيمة : 45,000 د.ع",
                    occurredAt = 1773431532000L // 2026-03-14 19:52:12
                ),
                com.example.core.model.LocalLedgerEntry(
                    id = "mock5",
                    accountId = "mock",
                    typeRaw = "took",
                    amountIqd = 40000.0,
                    debtAfterIqd = 40000.0,
                    note = "تجديد اشتراك بقيمة : 40,000 د.ع",
                    occurredAt = 1770836232000L // 2026-02-12 18:57:12
                ),
                com.example.core.model.LocalLedgerEntry(
                    id = "mock6",
                    accountId = "mock",
                    typeRaw = "gave",
                    amountIqd = 40000.0,
                    debtAfterIqd = 0.0,
                    note = "تسديد 40,000 د.ع",
                    occurredAt = 1769895132000L // 2026-02-01 21:32:12
                )
            )
        } else {
            ledgerList
        }
    }

    LaunchedEffect(userIndex) {
        viewModel.loadUserDetail(userIndex, userId)
    }

    if (showRefillDialog) {
        val user = detail
        if (user != null) {
            val packagesList by viewModel.packages.collectAsStateWithLifecycle()
            val matchedPackagePrice = remember(user.packageName, packagesList) {
                val name = user.packageName?.trim()?.lowercase() ?: ""
                val found = packagesList.find { it.accountName.trim().lowercase() == name }
                found?.price
            }
            val initialSuggestedPrice = remember(matchingAccount, matchedPackagePrice) {
                val p = matchingAccount?.currentPriceIqd ?: matchedPackagePrice
                if (p != null && p > 0.0) {
                    if (p % 1000.0 == 0.0) "${(p / 1000.0).toLong()}" else "${p / 1000.0}"
                } else "40"
            }
            var priceInput by rememberSaveable { mutableStateOf(initialSuggestedPrice) }
            var isWasilChecked by rememberSaveable { mutableStateOf(false) }
            var isPrintChecked by rememberSaveable { mutableStateOf(false) }
            var noteInput by rememberSaveable { mutableStateOf("") }
            var isSubmitting by rememberSaveable { mutableStateOf(false) }
            
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200)
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            
            var resellerBalance by rememberSaveable { mutableStateOf(0.0) }
            var packageCost by rememberSaveable { mutableStateOf(20000.0) }
            var isLoadingApiData by rememberSaveable { mutableStateOf(true) }
            
            val currentPackages by viewModel.packages.collectAsStateWithLifecycle()
            LaunchedEffect(user.userIndex, currentPackages) {
                try {
                    resellerBalance = viewModel.gateway.getBalance()
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    resellerBalance = 0.0
                }
                try {
                    val name = user.packageName?.trim()?.lowercase() ?: ""
                    val foundPackage = currentPackages.find { it.accountName.trim().lowercase() == name }
                    val acctIdx = user.accountIndex ?: foundPackage?.accountIndex
                    
                    if (acctIdx != null && acctIdx > 0) {
                        val fetchedCost = viewModel.gateway.getAccountCost(acctIdx)
                        if (fetchedCost > 0.0) {
                            packageCost = fetchedCost
                        } else {
                            val price = foundPackage?.price.takeIf { it != null && it > 0.0 } ?: 0.0
                            packageCost = price
                        }
                    } else {
                        val price = foundPackage?.price.takeIf { it != null && it > 0.0 } ?: 0.0
                        packageCost = price
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                    val name = user.packageName?.trim()?.lowercase() ?: ""
                    val foundPackage = currentPackages.find { it.accountName.trim().lowercase() == name }
                    val price = foundPackage?.price.takeIf { it != null && it > 0.0 } ?: 0.0
                    packageCost = price
                }
                isLoadingApiData = false
            }
            
            val balanceAfter = resellerBalance - packageCost
            
            Dialog(
                onDismissRequest = { 
                    isSubmitting = false
                    showRefillDialog = false 
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f)
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                    border = BorderStroke(1.dp, Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        )
                        
                        Text(
                            text = stringResource(id = R.string.title_subscription_renewal),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(id = R.string.label_subscription_price),
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            
                            var isFocused by rememberSaveable { mutableStateOf(false) }
                            var hasClearedOnFirstFocus by rememberSaveable { mutableStateOf(false) }
                            
                            BasicTextField(
                                value = priceInput,
                                onValueChange = { input ->
                                    val trimmed = input.trim()
                                    if ((trimmed.all { it.isDigit() || it == '.' }) && trimmed.count { it == '.' } <= 1) {
                                        priceInput = trimmed
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            isFocused = true
                                            if (!hasClearedOnFirstFocus) {
                                                hasClearedOnFirstFocus = true
                                                priceInput = ""
                                            }
                                        } else {
                                            isFocused = false
                                        }
                                    },
                                textStyle = LocalTextStyle.current.copy(
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End
                                ),
                                cursorBrush = SolidColor(Color(0xFF90CAF9)),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                decorationBox = { innerTextField ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Box(modifier = Modifier.widthIn(min = 20.dp, max = 120.dp)) {
                                                    innerTextField()
                                                }
                                                Text(
                                                    text = ",000",
                                                    color = Color(0xFF90CAF9),
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (currentLang == "ar") "د.ع" else "IQD",
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222E)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "تكلفة الاشتراك" else "Package Cost",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (isLoadingApiData) "..." else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(packageCost.toDouble())} د.ع",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "رصيد اللوحة الحالي" else "Current Panel Balance",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (isLoadingApiData) "..." else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(resellerBalance.toDouble())} د.ع",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "رصيد اللوحة بعد التجديد" else "Balance After Renewal",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    val balanceColor = if (balanceAfter >= 0) Color(0xFF34D399) else Color(0xFFF87171)
                                    Text(
                                        text = if (isLoadingApiData) "..." else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(balanceAfter.toDouble())} د.ع",
                                        color = balanceColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (currentLang == "ar") "ملاحظة" else "Note", color = Color(0xFF9CA3AF)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF374151)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { isWasilChecked = !isWasilChecked },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isWasilChecked) Color(0xFF1E3A8A) else Color.Transparent
                                ),
                                border = BorderStroke(1.dp, if (isWasilChecked) Color(0xFF3B82F6) else Color(0xFF374151)),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "واصل" else "Received",
                                        color = if (isWasilChecked) Color.White else Color(0xFF9CA3AF),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (isWasilChecked) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            
                            Button(
                                onClick = { isPrintChecked = !isPrintChecked },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPrintChecked) Color(0xFF374151) else Color.Transparent
                                ),
                                border = BorderStroke(1.dp, if (isPrintChecked) Color(0xFF6B7280) else Color(0xFF374151)),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "طباعة" else "Print",
                                        color = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Print,
                                        contentDescription = null,
                                        tint = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        
                        Button(
                            onClick = {
                                val depositPass = prefs.getDepositPassword()
                                if (depositPass.isBlank()) {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (currentLang == "ar") "الرجاء ضبط كلمة مرور الصندوق في الإعدادات أولاً!" else "Please set your deposit password in settings first!",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    val parsedPrice = (com.example.core.ledger.MoneyParser.parseSubscriptionPriceIqd(priceInput) ?: 0L).toDouble()
                                    if (parsedPrice <= 0.0) {
                                        android.widget.Toast.makeText(
                                            context,
                                            if (currentLang == "ar") "الرجاء إدخال مبلغ صحيح للتجديد!" else "Please enter a valid subscription price!",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        showRefillDialog = false
                                        val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                            earthlinkUsername = user.userID,
                                            displayName = user.customerFullName ?: user.userID,
                                            phone1 = user.mobileNumber,
                                            packageName = user.packageName ?: "Default",
                                            currentPriceIqd = parsedPrice,
                                            createdAt = System.currentTimeMillis()
                                        )
                                        
                                        isSubmitting = true
                                        val noteVal = noteInput
                                        val isWasil = isWasilChecked
                                        val accToUse = matchingAccount ?: finalAcc

                                        viewModel.refillUser(
                                            userId = user.userID,
                                            depositPass = depositPass,
                                            price = parsedPrice,
                                            note = noteVal,
                                            onSuccessCallback = { txId ->
                                                try {
                                                    val chargeNote = if (noteVal.isNotBlank()) "[RENEW] ${noteVal.trim()}" else ""
                                                    val payNote = if (isWasil) (if (noteVal.isNotBlank()) "[RENEW_PAY] ${noteVal.trim()}" else null) else null
                                                    
                                                    viewModel.localLedgerRepository.recordAccountRenewal(
                                                        account = accToUse,
                                                        newPriceIqd = parsedPrice,
                                                        chargeNote = chargeNote,
                                                        payNote = payNote,
                                                        idempotencyKey = txId
                                                    )
                                                    viewModel.syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)
                                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                                    android.util.Log.e("UserDetailScreen", "Failed to add ledger entry", e)
                                                    try {
                                                        audit.logAction(
                                                            action = "RECONCILIATION_REQUIRED",
                                                            entityType = "USER",
                                                            entityId = user.userID,
                                                            summary = "Refill succeeded on API, but local ledger persistence failed: ${e.message}"
                                                        )
                                                    } catch (ex: Exception) { if (ex is kotlinx.coroutines.CancellationException) throw ex; }
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        if (currentLang == "ar") "تم التجديد على النظام ولكن تعذر الحفظ محلياً. يرجى المزامنة." else "Renewed on system, but failed to save locally. Please sync.",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        )
                                    }
                                }
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text(
                                text = if (currentLang == "ar") "تجديد" else "Renew",
                                color = Color.Black,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDepositDialog) {
        val user = detail
        if (user != null) {
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200)
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            var priceInput by rememberSaveable { mutableStateOf("") }
            var isPrintChecked by rememberSaveable { mutableStateOf(false) }
            var noteInput by rememberSaveable { mutableStateOf("") }
            var isSubmitting by rememberSaveable { mutableStateOf(false) }
            
            val currentDebt = matchingAccount?.debtIqd ?: 0.0
            val parsedPrice = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
            val currentAdvance = matchingAccount?.advanceIqd ?: 0.0
            val balanceAfter = currentDebt - currentAdvance - parsedPrice
            
            Dialog(
                onDismissRequest = { 
                    isSubmitting = false
                    showDepositDialog = false 
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f)
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                    border = BorderStroke(1.dp, Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        )
                        
                        Text(
                            text = if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "تسديد مبلغ" else "إيداع مبلغ" else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Pay Amount" else "Deposit Amount",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "مبلغ التسديد" else "مبلغ الإيداع" else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Payment Amount" else "Deposit Amount",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            
                            var isFocused by rememberSaveable { mutableStateOf(false) }
                            
                            BasicTextField(
                                value = priceInput,
                                onValueChange = { input ->
                                    val trimmed = input.trim()
                                    if ((trimmed.all { it.isDigit() || it == '.' }) && trimmed.count { it == '.' } <= 1) {
                                        priceInput = trimmed
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            isFocused = true
                                        } else {
                                            isFocused = false
                                        }
                                    },
                                textStyle = LocalTextStyle.current.copy(
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End
                                ),
                                cursorBrush = SolidColor(Color(0xFF90CAF9)),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                decorationBox = { innerTextField ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Box(modifier = Modifier.widthIn(min = 20.dp, max = 120.dp)) {
                                                    innerTextField()
                                                }
                                                Text(
                                                    text = ",000",
                                                    color = Color(0xFF90CAF9),
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (currentLang == "ar") "د.ع" else "IQD",
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222E)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "الدين الحالي للمشترك" else "Current Debt",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(currentDebt.toDouble())} د.ع",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "الدين المتبقي بعد الإيداع" else "Debt Remaining After",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    val balanceColor = if (balanceAfter <= 0) Color(0xFF34D399) else Color(0xFFF87171)
                                    val formattedBalance = if (balanceAfter < 0) {
                                        if (currentLang == "ar") "رصيد مقدم: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay((-balanceAfter).toDouble())} د.ع"
                                        else "Advance Credit: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay((-balanceAfter).toDouble())} IQD"
                                    } else {
                                        "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(balanceAfter.toDouble())} د.ع"
                                    }
                                    Text(
                                        text = formattedBalance,
                                        color = balanceColor,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (currentLang == "ar") "ملاحظة" else "Note", color = Color(0xFF9CA3AF)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF374151)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Button(
                            onClick = { isPrintChecked = !isPrintChecked },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPrintChecked) Color(0xFF374151) else Color.Transparent
                            ),
                            border = BorderStroke(1.dp, if (isPrintChecked) Color(0xFF6B7280) else Color(0xFF374151)),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "طباعة" else "Print",
                                    color = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = null,
                                    tint = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        Button(
                            onClick = {
                                val parsedPriceVal = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
                                if (parsedPriceVal <= 0.0) {
                                    android.widget.Toast.makeText(
                                        context,
                                        if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "الرجاء إدخال مبلغ تسديد صحيح!" else "الرجاء إدخال مبلغ إيداع صحيح!" else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Please enter a valid payment amount!" else "Please enter a valid deposit amount!",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    isSubmitting = true
                                    val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                        earthlinkUsername = user.userID,
                                        displayName = user.customerFullName ?: user.userID,
                                        phone1 = user.mobileNumber,
                                        packageName = user.packageName ?: "Default",
                                        currentPriceIqd = 40000.0,
                                        createdAt = System.currentTimeMillis()
                                    )
                                    
                                    coroutineScope.launch {
                                        try {
                                            val accToUse = matchingAccount ?: finalAcc
                                            val baseNote = if ((matchingAccount?.debtIqd ?: 0.0) > 0) "[PAYMENT]" else "[DEPOSIT]"
                                            val payNote = if (noteInput.isNotBlank()) "$baseNote ${noteInput.trim()}" else null
                                            
                                            viewModel.localLedgerRepository.recordAccountPayment(
                                                account = accToUse,
                                                amount = parsedPriceVal,
                                                note = payNote
                                            )
                                            viewModel.syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)

                                            showDepositDialog = false
                                            android.widget.Toast.makeText(
                                                context,
                                                if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "تم تسديد المبلغ بنجاح." else "تم إيداع المبلغ بنجاح." else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Payment completed successfully." else "Deposit completed successfully.",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()

                                            try {
                                                audit.logAction(
                                                    action = "DEPOSIT_USER",
                                                    entityType = "USER",
                                                    entityId = user.userID,
                                                    summary = "Deposited amount $parsedPriceVal. Note: $noteInput"
                                                )
                                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
                                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                            android.util.Log.e("UserDetailScreen", "Failed to add payment", e)
                                            android.widget.Toast.makeText(
                                                context,
                                                if (currentLang == "ar") "فشلت العملية: ${e.localizedMessage}" else "Operation failed: ${e.localizedMessage}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        } finally {
                                            isSubmitting = false
                                        }
                                    }
                                }
                            },
                            enabled = !isSubmitting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Text(
                                text = if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "تسديد" else "إيداع" else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Payment" else "Deposit",
                                color = Color.Black,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExtendDialog) {
        ConfirmationDialog(
            title = if (currentLang == "ar") "تمديد صلاحية الحساب" else "Extend Subscriber Duration",
            message = if (currentLang == "ar") "تأكيد إضافة وتمديد معايير المدة الزمنية للحساب ${detail?.userID}؟" else "Confirm extending the duration parameter of account ${detail?.userID}?",
            needsPasswordField = false,
            onCancel = { showExtendDialog = false },
            onConfirm = {
                showExtendDialog = false
                viewModel.extendUser(userIndex, detail?.userID ?: "")
            }
        )
    }

    if (showDebtDialog) {
        val user = detail
        if (user != null) {
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200)
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            var priceInput by rememberSaveable { mutableStateOf("") }
            var isPrintChecked by rememberSaveable { mutableStateOf(false) }
            var noteInput by rememberSaveable { mutableStateOf("") }
            var isSubmitting by rememberSaveable { mutableStateOf(false) }
            
            val currentDebt = matchingAccount?.debtIqd ?: 0.0
            val parsedPrice = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
            val currentAdvance = matchingAccount?.advanceIqd ?: 0.0
            val balanceAfter = currentDebt - currentAdvance + parsedPrice
            
            Dialog(
                onDismissRequest = { 
                    isSubmitting = false
                    showDebtDialog = false 
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false
                )
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .fillMaxHeight(0.85f)
                        .padding(vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                    border = BorderStroke(1.dp, Color(0xFF1F2937))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        )
                        
                        Text(
                            text = if (currentLang == "ar") "إضافة دين" else "Add Debt",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = if (currentLang == "ar") "مبلغ الدين" else "Debt Amount",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                            
                            var isFocused by rememberSaveable { mutableStateOf(false) }
                            
                            BasicTextField(
                                value = priceInput,
                                onValueChange = { input ->
                                    if (input.all { it.isDigit() }) {
                                        priceInput = input
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        isFocused = focusState.isFocused
                                    },
                                textStyle = LocalTextStyle.current.copy(
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.End
                                ),
                                cursorBrush = SolidColor(Color(0xFF90CAF9)),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                decorationBox = { innerTextField ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Box(modifier = Modifier.widthIn(min = 20.dp, max = 120.dp)) {
                                                    innerTextField()
                                                }
                                                Text(
                                                    text = ",000",
                                                    color = Color(0xFF90CAF9),
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = if (currentLang == "ar") "د.ع" else "IQD",
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A222E)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "الدين الحالي للمشترك" else "Current Debt",
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(currentDebt.toDouble())} د.ع",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") (if (balanceAfter <= 0) "الرصيد المتبقي للمشترك" else "الدين الكلي بعد الإضافة") else (if (balanceAfter <= 0) "Advance Credit After" else "Total Debt After"),
                                        color = Color(0xFF9CA3AF),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (balanceAfter < 0) (if (currentLang == "ar") "رصيد مقدم: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay((-balanceAfter).toDouble())} د.ع" else "Advance: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay((-balanceAfter).toDouble())} IQD") else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(balanceAfter.toDouble())} د.ع",
                                        color = if (balanceAfter <= 0) Color(0xFF34D399) else Color(0xFFF87171),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        
                        OutlinedTextField(
                            value = noteInput,
                            onValueChange = { noteInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (currentLang == "ar") "ملاحظة" else "Note", color = Color(0xFF9CA3AF)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF3B82F6),
                                unfocusedBorderColor = Color(0xFF374151)
                             ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Button(
                            onClick = { isPrintChecked = !isPrintChecked },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPrintChecked) Color(0xFF374151) else Color.Transparent
                            ),
                            border = BorderStroke(1.dp, if (isPrintChecked) Color(0xFF6B7280) else Color(0xFF374151)),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "طباعة" else "Print",
                                    color = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Print,
                                    contentDescription = null,
                                    tint = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { 
                                    isSubmitting = false
                                    showDebtDialog = false 
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2937)),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "إلغاء" else "Cancel",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Button(
                                onClick = {
                                    val parsedPriceVal = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
                                    if (parsedPriceVal <= 0.0) {
                                        android.widget.Toast.makeText(
                                            context,
                                            if (currentLang == "ar") "الرجاء إدخال مبلغ دين صحيح!" else "Please enter a valid debt amount!",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        isSubmitting = true
                                        val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                            earthlinkUsername = user.userID,
                                            displayName = user.customerFullName ?: user.userID,
                                            phone1 = user.mobileNumber,
                                            packageName = user.packageName ?: "Default",
                                            currentPriceIqd = 40000.0,
                                            createdAt = System.currentTimeMillis()
                                        )
                                        
                                        coroutineScope.launch {
                                            try {
                                                val accToUse = matchingAccount ?: finalAcc
                                                val debtNote = if (noteInput.isNotBlank()) "[DEBT] ${noteInput.trim()}" else null
                                                
                                                viewModel.localLedgerRepository.recordAccountDebt(
                                                    account = accToUse,
                                                    amount = parsedPriceVal,
                                                    note = debtNote
                                                )
                                                viewModel.syncRepo?.requestSync(com.example.domain.repository.SyncReason.USER_ACTION)

                                                showDebtDialog = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (currentLang == "ar") "تم إضافة الدين بنجاح." else "Debt added successfully.",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()

                                                try {
                                                    audit.logAction(
                                                        action = "ADD_DEBT",
                                                        entityType = "USER",
                                                        entityId = user.userID,
                                                        summary = "Added debt amount $parsedPriceVal. Note: $noteInput"
                                                    )
                                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
                                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                                android.util.Log.e("UserDetailScreen", "Failed to add debt", e)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (currentLang == "ar") "فشلت العملية: ${e.localizedMessage}" else "Operation failed: ${e.localizedMessage}",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                isSubmitting = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isSubmitting,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF87171)),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "إضافة" else "Add",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHistoryDialog) {
        Dialog(
            onDismissRequest = { showHistoryDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false
            )
        ) {
            val formatLedgerDate: (Long) -> String = { timestamp ->
                val sdf = java.text.SimpleDateFormat("yyyy/MM/dd • h:mm a", java.util.Locale.US)
                sdf.format(java.util.Date(timestamp))
            }
            CompositionLocalProvider(LocalLayoutDirection provides (if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr)) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = if (currentLang == "ar") "السجل" else "History",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { showHistoryDialog = false }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    val accountToExport = matchingAccount ?: com.example.core.model.LocalAccount(
                                        id = "temp_acc",
                                        earthlinkUsername = detail?.userID ?: "demo_user",
                                        displayName = detail?.customerFullName ?: detail?.userID ?: "Demo User",
                                        phone1 = detail?.mobileNumber,
                                        packageName = detail?.packageName ?: "Default",
                                        createdAt = System.currentTimeMillis()
                                    )
                                    com.example.core.sync.PdfStatementGenerator.generateAndShare(
                                        context,
                                        accountToExport,
                                        finalLedgerList
                                    )
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share PDF",
                                        tint = Color.White
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF090D10))
                        )
                    },
                    containerColor = Color(0xFF090D10)
                ) { paddingValues ->
                    if (finalLedgerList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (currentLang == "ar") "لا يوجد سجل حركات" else "No history available",
                                color = Color.Gray,
                                fontSize = 16.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(finalLedgerList, key = { it.id }) { entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                                border = BorderStroke(1.dp, Color(0xFF1F2937))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val noteNonNull = entry.note ?: ""
                                    val isRenew = noteNonNull.startsWith("[RENEW]")
                                    val isRenewPay = noteNonNull.startsWith("[RENEW_PAY]")
                                    val isDebt = noteNonNull.startsWith("[DEBT]")
                                    val isDeposit = noteNonNull.startsWith("[DEPOSIT]")
                                    val isPayment = noteNonNull.startsWith("[PAYMENT]")

                                    val resolvedType = when {
                                        isRenew -> "renew"
                                        isRenewPay -> "renew_pay"
                                        isDebt -> "debt"
                                        isDeposit -> "deposit"
                                        isPayment -> "payment"
                                        else -> {
                                            if (entry.typeRaw == "took" || entry.typeRaw == "add" || entry.typeRaw == "renewal") {
                                                if (noteNonNull.contains("دين") || noteNonNull.contains("Debt")) "debt"
                                                else "renew"
                                            } else if (entry.typeRaw == "debt" || entry.typeRaw == "debt_added") {
                                                "debt"
                                            } else {
                                                if (noteNonNull.contains("تجديد")) "renew_pay"
                                                else "deposit"
                                            }
                                        }
                                    }

                                    val cleanNote = when {
                                        noteNonNull.isEmpty() -> ""
                                        noteNonNull.startsWith("[RENEW]") -> noteNonNull.removePrefix("[RENEW]").trim()
                                        noteNonNull.startsWith("[RENEW_PAY]") -> noteNonNull.removePrefix("[RENEW_PAY]").trim()
                                        noteNonNull.startsWith("[DEBT]") -> noteNonNull.removePrefix("[DEBT]").trim()
                                        noteNonNull.startsWith("[DEPOSIT]") -> noteNonNull.removePrefix("[DEPOSIT]").trim()
                                        else -> {
                                            var temp = noteNonNull
                                            // Handle various separators
                                            val separators = listOf(" | ", " - ", " : ")
                                            var bestTemp = temp
                                            for (sep in separators) {
                                                val idx = temp.indexOf(sep)
                                                if (idx != -1) {
                                                    val parts = temp.split(sep)
                                                    // Find a part that doesn't just contain standard keywords
                                                    for (part in parts) {
                                                        val trimmed = part.trim()
                                                        if (trimmed.isNotEmpty() && 
                                                            !trimmed.contains("تجديد") && 
                                                            !trimmed.contains("تسديد") && 
                                                            !trimmed.contains("دين") &&
                                                            !trimmed.contains("بقيمة")) {
                                                            bestTemp = trimmed
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            if (bestTemp == temp) {
                                                if (temp.contains("تجديد") || temp.contains("تسديد") || temp.contains("دين")) {
                                                    // If it's just a system message like "تجديد اشتراك بقيمة : 40,000"
                                                    // and we couldn't find a better part, clear it as it's redundant with the title.
                                                    if (temp.length < 50) "" else temp
                                                } else {
                                                    temp
                                                }
                                            } else {
                                                bestTemp
                                            }
                                        }
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = if (currentLang == "ar") Alignment.End else Alignment.Start,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {

                                        val titleText = when (resolvedType) {
                                            "debt" -> {
                                                if (currentLang == "ar") "إضافة دين بقيمة : ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Added debt: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            "deposit" -> {
                                                if (currentLang == "ar") "إيداع مبلغ : ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Deposit of ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            "payment" -> {
                                                if (currentLang == "ar") "تسديد مبلغ : ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Payment of ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            "renew_pay" -> {
                                                if (currentLang == "ar") "تسديد تجديد بقيمة : ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Paid subscription renewal: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            else -> { // renew
                                                if (currentLang == "ar") "تجديد اشتراك بقيمة : ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Subscription renewal: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                        }
                                        
                                        Text(
                                            text = titleText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 16.sp,
                                            color = Color.White
                                        )
                                        
                                        Text(
                                            text = if (currentLang == "ar") "التاريخ: ${formatLedgerDate(entry.occurredAt)}" else "Date: ${formatLedgerDate(entry.occurredAt)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF8E8E93)
                                        )
                                        
                                        val balanceText = if (entry.typeRaw == "took") {
                                            if (currentLang == "ar") "الدين الكلي بعد: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.debtAfterIqd.toDouble())} د.ع"
                                            else "Total Debt After: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.debtAfterIqd.toDouble())} IQD"
                                        } else {
                                            if (currentLang == "ar") "الرصيد الكلي بعد: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.debtAfterIqd.toDouble())} د.ع"
                                            else "Total Balance After: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.debtAfterIqd.toDouble())} IQD"
                                        }
                                        
                                        Text(
                                            text = balanceText,
                                            fontSize = 12.sp,
                                            color = Color(0xFF8E8E93)
                                        )
                                        
                                        if (cleanNote.isNotBlank() && cleanNote != "000") {
                                            Text(
                                                text = if (currentLang == "ar") "ملاحظة: $cleanNote" else "Note: $cleanNote",
                                                fontSize = 12.sp,
                                                color = Color(0xFF90CAF9),
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(16.dp))
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(
                                                when (resolvedType) {
                                                    "debt" -> Color(0xFFEF4444)
                                                    "deposit" -> Color(0xFF10B981)
                                                    else -> Color(0xFF2563EB)
                                                }, 
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val iconVector = when (resolvedType) {
                                            "debt" -> Icons.Default.AttachMoney
                                            "deposit" -> Icons.Default.Payment
                                            else -> Icons.Default.Refresh
                                        }
                                        Icon(
                                            imageVector = iconVector,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    } // Close else block
                }
            }
        }
    }

    if (showNotesDialog) {
        var noteText by rememberSaveable { mutableStateOf(matchingAccount?.note ?: "") }
        AlertDialog(
            onDismissRequest = { showNotesDialog = false },
            title = {
                Text(
                    text = if (currentLang == "ar") "ملاحظات المشترك" else "Subscriber Notes",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text(if (currentLang == "ar") "اكتب ملاحظاتك هنا..." else "Type your notes here...", color = Color(0xFF9CA3AF)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF3B82F6),
                        unfocusedBorderColor = Color(0xFF374151),
                        cursorColor = Color(0xFF3B82F6)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = false,
                    maxLines = 15
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val user = detail
                        if (user != null) {
                            val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                earthlinkUsername = user.userID,
                                displayName = user.customerFullName ?: user.userID,
                                phone1 = user.mobileNumber,
                                packageName = user.packageName ?: "Default",
                                createdAt = System.currentTimeMillis()
                             )
                            coroutineScope.launch {
                                val updated = finalAcc.copy(
                                    note = noteText,
                                    updatedAt = System.currentTimeMillis()
                                )
                                repo.saveAccount(updated)
                            }
                        }
                        showNotesDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (currentLang == "ar") "حفظ" else "Save",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotesDialog = false }) {
                    Text(
                        text = if (currentLang == "ar") "إلغاء" else "Cancel",
                        color = Color(0xFF9CA3AF),
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            containerColor = Color(0xFF10161D),
            shape = RoundedCornerShape(20.dp)
        )
    }

    CompositionLocalProvider(LocalLayoutDirection provides (if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.loadUserDetail(userIndex, userId) }) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
                        IconButton(onClick = { showDebtDialog = true }) {
                            Icon(imageVector = Icons.Default.AttachMoney, contentDescription = "Debt", tint = Color.White)
                        }
                        IconButton(onClick = { showShareDialog = true }) {
                            Icon(imageVector = Icons.Default.Notifications, contentDescription = "Share", tint = Color.White)
                        }
                        IconButton(onClick = { showNotesDialog = true }) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.StickyNote2, contentDescription = "Notes", tint = Color.White)
                        }
                        IconButton(onClick = { showHistoryDialog = true }) {
                            Icon(imageVector = Icons.Default.History, contentDescription = "History", tint = Color.White)
                        }
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        IconButton(onClick = { expanded = true }) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(Color(0xFF1C1C1E))
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "ar") "تعديل نوع الاشتراك" else "Edit Package", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    showEditPackageDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "ar") "تعديل اسم المشترك الظاهر" else "Edit Display Name", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    showEditDisplayNameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "ar") "تمديد المشترك" else "Extend User", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    showExtendDialog = true
                                }
                            )
                            val isSuspended = detail?.accountStatus?.trim()?.lowercase() == "suspendedbyagent"
                            DropdownMenuItem(
                                text = { 
                                    if (isSuspended) {
                                        Text(if (currentLang == "ar") "تفعيل المشترك" else "Resume/Activate User", color = Color.White)
                                    } else {
                                        Text(if (currentLang == "ar") "ايقاف المشترك" else "Stop/Suspend User", color = Color.White)
                                    }
                                },
                                onClick = {
                                    expanded = false
                                    showStopUserDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "ar") "IP مخصص (اضافة/تعديل)" else "Edit Custom IP", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    showEditCustomIpDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (currentLang == "ar") "تعديل كلمات المرور" else "Edit Passwords", color = Color.White) },
                                onClick = {
                                    expanded = false
                                    showPassToolsDialog = true
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF10161D))
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF10161D))
                        .padding(bottom = 24.dp, start = 16.dp, end = 16.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showDepositDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(23.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "تسديد مبلغ" else "ايداع مبلغ" else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Pay Amount" else "Deposit Amount", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(imageVector = Icons.Default.Payment, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }

                        Button(
                            onClick = { showDebtDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            border = BorderStroke(1.dp, Color(0xFF2C2C2E)),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(23.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (currentLang == "ar") "إضافة دين" else "Add Debt", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(imageVector = Icons.Default.AttachMoney, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Button(
                        onClick = { showRefillDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (currentLang == "ar") "تجديد الإشتراك" else "Renew Subscription", color = Color.Black, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
            containerColor = Color(0xFF10161D)
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading && detail == null) {
                    Box(modifier = Modifier.height(300.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF007AFF))
                    }
                    return@Column
                }

                val isRefreshingDetail by viewModel.isRefreshingDetail.collectAsStateWithLifecycle()
                if (isRefreshingDetail) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = Color(0xFF007AFF),
                        trackColor = Color.Transparent
                    )
                }

                val user = detail
                if (user == null) {
                    Text(
                        text = if (currentLang == "ar") "عذراً، فشل جلب بيانات المشترك المحدد." else "Selected user could not be retrieved successfully.",
                        color = Color.White,
                        modifier = Modifier.padding(12.dp)
                    )
                    return@Column
                }

                if (error != null) {
                    ErrorStateCard(
                        message = error ?: "",
                        title = if (currentLang == "ar") "خطأ" else "Error"
                    )
                }
                
                val searchListUsers by viewModel.usersList.collectAsStateWithLifecycle()
                val matchedSearchItem = remember(user.userID, searchListUsers) {
                    searchListUsers.find { 
                        it.userID.trim().lowercase() == user.userID.trim().lowercase() || 
                        it.userIndex == userIndex 
                    }
                }

                val displayNameToUse = remember(user.customerFullName, matchedSearchItem, matchingAccount) {
                    val name3 = matchingAccount?.displayName?.trim()
                    if (!name3.isNullOrEmpty() && name3 != "N/A") return@remember name3

                    val name1 = user.customerFullName?.trim()
                    if (!name1.isNullOrEmpty() && name1 != "N/A") return@remember name1
                    
                    val name2 = matchedSearchItem?.customerName?.trim() ?: matchedSearchItem?.displayName?.trim()
                    if (!name2.isNullOrEmpty() && name2 != "N/A") return@remember name2
                    
                    "N/A"
                }

                val finalExpirationStr = remember(user.expirationDate, user.manualExpirationDate, user.accountExpirationDate) {
                    val rawDate = listOfNotNull(
                        user.manualExpirationDate,
                        user.accountExpirationDate,
                        user.expirationDate
                    ).firstOrNull { it.isNotBlank() && it != "N/A" }
                    
                    if (rawDate != null && rawDate.endsWith("Z")) {
                        try {
                            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                            isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            val parsed = isoFormat.parse(rawDate)
                            if (parsed != null) {
                                val targetFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                targetFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Baghdad")
                                targetFormat.format(parsed)
                            } else {
                                rawDate
                            }
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                            rawDate
                        }
                    } else {
                        rawDate
                    }
                }
                val remainingTime = remember(finalExpirationStr, user.activeDaysLeft, lang, user.accountStatus) {
                    getRemainingTime(finalExpirationStr, user.activeDaysLeft?.toString(), lang, user.accountStatus)
                }
                val statusClean = user.accountStatus?.trim()?.lowercase() ?: ""
                val isExpired = remainingTime.contains("منتهي") || remainingTime.contains("Expired") || remainingTime.contains("expired")
                val isDeactivated = statusClean == "suspendedbyagent" || statusClean == "expired" || statusClean == "منتهي"
                val isActive = !isExpired && !isDeactivated
                val isReallyOnline = user.onlineSession != null && 
                    user.onlineSession?.onlineStatus?.trim()?.lowercase()?.contains("offline") != true
                
                if (showShareDialog) {
                    val debtVal = matchingAccount?.debtIqd ?: 0.0
                    val advanceVal = matchingAccount?.advanceIqd ?: 0.0
                    val formattedDebt = if (debtVal > 0) {
                        if (currentLang == "ar") "${formatIqd(debtVal)} (دين)" else "${formatIqd(debtVal)} (Debt)"
                    } else if (advanceVal > 0) {
                        if (currentLang == "ar") "${formatIqd(advanceVal)} (ايداع)" else "${formatIqd(advanceVal)} (Deposit)"
                    } else {
                        if (currentLang == "ar") "0 د.ع" else "0 IQD"
                    }

                    val activeStatusLabel = if (isActive) {
                        if (currentLang == "ar") "فعال" else "Active"
                    } else {
                        if (currentLang == "ar") "غير فعال" else "Inactive"
                    }

                    val messageText = buildString {
                        if (currentLang == "ar") {
                            appendLine("مرحباً ${displayNameToUse}،")
                            appendLine()
                            appendLine("نوع الاشتراك: ${user.packageName ?: "Default"}")
                            appendLine("حالة الاشتراك: $activeStatusLabel")
                            appendLine("المدة المتبقية: $remainingTime")
                            appendLine("تاريخ الانتهاء: ${finalExpirationStr ?: "N/A"}")
                            appendLine()
                            appendLine("الديون: $formattedDebt")
                            appendLine()
                            append("في حال احتجت أي مساعدة أو لديك أي استفسارات، لا تتردد بالتواصل معنا. تحياتنا")
                        } else {
                            appendLine("Hello ${displayNameToUse},")
                            appendLine()
                            appendLine("Subscription Type: ${user.packageName ?: "Default"}")
                            appendLine("Subscription Status: $activeStatusLabel")
                            appendLine("Remaining Duration: $remainingTime")
                            appendLine("Expiration Date: ${finalExpirationStr ?: "N/A"}")
                            appendLine()
                            appendLine("Debts: $formattedDebt")
                            appendLine()
                            append("If you need any assistance or have any questions, please don't hesitate to contact us. Regards")
                        }
                    }

                    Dialog(
                        onDismissRequest = { showShareDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false
                        )
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(0.95f)
                                .padding(16.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                            border = BorderStroke(1.dp, Color(0xFF1F2937))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Header Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Left-side icons (Settings and Share)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = {
                                                // Copy to clipboard
                                                try {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("Subscriber Status", messageText)
                                                    clipboard.setPrimaryClip(clip)
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        if (currentLang == "ar") "تم نسخ نص الرسالة إلى الحافظة!" else "Message copied to clipboard!",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                                    // Handle exception
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Settings,
                                                contentDescription = "Copy message text",
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                // Standard system share
                                                try {
                                                    val sendIntent: Intent = Intent().apply {
                                                        action = Intent.ACTION_SEND
                                                        putExtra(Intent.EXTRA_TEXT, messageText)
                                                        type = "text/plain"
                                                    }
                                                    context.startActivity(Intent.createChooser(sendIntent, null))
                                                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                                    // Handle exception
                                                }
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "System Share",
                                                tint = Color.White.copy(alpha = 0.7f),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    // Title
                                    Text(
                                        text = if (currentLang == "ar") "مشاركة" else "Share",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 20.sp
                                    )
                                }

                                // Subtitle note
                                Text(
                                    text = if (currentLang == "ar") {
                                        "ملاحظة: إلى جانب نظام الإرسال التلقائي (عند التجديد أو الدفع أو انتهاء الاشتراك)، يتيح لك هذا الإجراء إرسال الرسالة يدوياً متى شئت."
                                    } else {
                                        "Note: Alongside the automatic sending system (on renewal, payment, or subscription expiration), this action allows you to send the message manually whenever you wish."
                                    },
                                    color = Color(0xFF9CA3AF),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    textAlign = if (currentLang == "ar") TextAlign.Right else TextAlign.Left,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Message Text Area (Styled exactly like the image card)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161E27)),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp)
                                    ) {
                                        Text(
                                            text = messageText,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            lineHeight = 22.sp,
                                            textAlign = if (currentLang == "ar") TextAlign.Right else TextAlign.Left,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Action Buttons: WhatsApp
                                Button(
                                    onClick = {
                                        try {
                                            val rawPhone = user.mobileNumber?.trim() ?: ""
                                            val whatsappPhone = if (rawPhone.startsWith("0")) {
                                                "964" + rawPhone.substring(1)
                                            } else if (rawPhone.startsWith("+")) {
                                                rawPhone.replace("+", "")
                                            } else if (rawPhone.startsWith("964")) {
                                                rawPhone
                                            } else if (rawPhone.length >= 10) {
                                                "964" + rawPhone
                                            } else {
                                                rawPhone
                                            }
                                            val whatsappUrl = "https://api.whatsapp.com/send?phone=$whatsappPhone&text=${java.net.URLEncoder.encode(messageText, "UTF-8")}"
                                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(whatsappUrl))
                                            context.startActivity(intent)
                                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                            // Fallback to chooser
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, messageText)
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, null))
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E27)),
                                    border = BorderStroke(1.dp, Color(0xFF2C3E50)),
                                    shape = RoundedCornerShape(27.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Chat,
                                            contentDescription = null,
                                            tint = Color(0xFF25D366), // WhatsApp Green
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (currentLang == "ar") "إرسال عبر WhatsApp" else "Send via WhatsApp",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Action Buttons: SMS
                                Button(
                                    onClick = {
                                        try {
                                            val uri = android.net.Uri.parse("smsto:${user.mobileNumber ?: ""}")
                                            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                                                putExtra("sms_body", messageText)
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                            // Fallback
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, messageText)
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, null))
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF161E27)),
                                    border = BorderStroke(1.dp, Color(0xFF2C3E50)),
                                    shape = RoundedCornerShape(27.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sms,
                                            contentDescription = null,
                                            tint = Color(0xFF3498DB), // SMS Blue tint
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = if (currentLang == "ar") "إرسال عبر SMS" else "Send via SMS",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Cancel Button
                                TextButton(
                                    onClick = { showShareDialog = false },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Text(
                                        text = if (currentLang == "ar") "إغلاق" else "Close",
                                        color = Color(0xFF9CA3AF),
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Blue Card
                Card(
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E88E5)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                        Text(
                            text = "E", 
                            fontSize = 48.sp, 
                            fontWeight = FontWeight.ExtraBold, 
                            color = Color.White, 
                            modifier = Modifier.align(Alignment.TopEnd) // because RTL
                        )
                        
                        Column(modifier = Modifier.align(Alignment.TopStart), horizontalAlignment = Alignment.Start) {
                            Text(text = if (currentLang == "ar") "الاسم" else "Name", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                            Text(text = displayNameToUse, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        
                        Column(modifier = Modifier.align(Alignment.BottomStart), horizontalAlignment = Alignment.Start) {
                            val debtVal = matchingAccount?.debtIqd ?: 0.0
                            val advanceVal = matchingAccount?.advanceIqd ?: 0.0
                            if (debtVal > 0) {
                                Text(text = if (currentLang == "ar") "الديون" else "Debt", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                                Text(text = formatIqd(debtVal), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            } else if (advanceVal > 0) {
                                Text(text = if (currentLang == "ar") "مودع" else "Deposited", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                                Text(text = formatIqd(advanceVal), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            } else {
                                Text(text = if (currentLang == "ar") "الديون" else "Debt", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                                Text(text = "0", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                // Dark Details Container Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        
                        // Status
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "الحالة" else "Status", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).background(if (isReallyOnline) Color(0xFF30D158) else Color(0xFFFF453A), shape = CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isReallyOnline && currentLang == "ar") "متصل" else if (isReallyOnline) "Online" else if (currentLang == "ar") "غير متصل" else "Offline", 
                                    color = if (isReallyOnline) Color(0xFF30D158) else Color(0xFFFF453A), 
                                    fontSize = 14.sp, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Account Status (Active, Suspended, etc.)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "حالة الحساب" else "Account Status", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            val statusLabel = when (statusClean) {
                                "suspendedbyagent" -> if (currentLang == "ar") "موقوف من الوكيل" else "Suspended by Agent"
                                "expired" -> if (currentLang == "ar") "منتهي الصلاحية" else "Expired"
                                "recentlyexpired" -> if (currentLang == "ar") "منتهي حديثاً" else "Recently Expired"
                                "active" -> if (currentLang == "ar") "نشط" else "Active"
                                "suspended" -> if (currentLang == "ar") "موقوف" else "Suspended"
                                else -> user.accountStatus ?: "N/A"
                            }
                            val statusColor = when (statusClean) {
                                "active" -> Color(0xFF30D158)
                                "suspendedbyagent", "expired", "suspended" -> Color(0xFFFF453A)
                                "recentlyexpired" -> Color(0xFFFFD60A)
                                else -> Color.White
                            }
                            Text(text = statusLabel, color = statusColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Connection Duration / Online Time
                        val connTime = user.onlineSessionTime
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "مدة الاتصال" else "Online Duration", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(
                                text = if (!connTime.isNullOrBlank()) connTime else "N/A",
                                color = if (!connTime.isNullOrBlank() && connTime != "N/A") Color(0xFF30D158) else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Remaining
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "المتبقي" else "Remaining", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(text = remainingTime, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Sub Price
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "سعر الإشتراك" else "Sub Price", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(text = formatIqd(matchingAccount?.currentPriceIqd ?: 40000.0), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Package Name
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "نوع الإشتراك" else "Package", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(text = user.packageName ?: "Unknown", color = Color(0xFF90CAF9), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Tower
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "البرج" else "Tower", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(text = matchingAccount?.towerName ?: user.userID.substringAfter("@", "N/A"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Username
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    try {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Subscriber Username", user.userID)
                                        clipboard.setPrimaryClip(clip)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (currentLang == "ar") "تم نسخ اسم المستخدم إلى الحافظة!" else "Username copied to clipboard!",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                        // Ignore
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (currentLang == "ar") "اليوزر" else "Username", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.userID,
                                    color = Color(0xFF90CAF9),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color(0xFF90CAF9),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        // Phone
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "رقم الهاتف الاول" else "Phone 1", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(text = user.mobileNumber ?: "N/A", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        // Current IP
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val ip = user.currentIP
                                    if (!ip.isNullOrBlank() && ip != "N/A") {
                                        try {
                                            val formattedIp = if (!ip.startsWith("http://") && !ip.startsWith("https://")) "http://$ip" else ip
                                            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(formattedIp))
                                            context.startActivity(browserIntent)
                                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                            android.widget.Toast.makeText(
                                                context,
                                                if (currentLang == "ar") "فشل فتح المتصفح" else "Failed to open browser",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (currentLang == "ar") "IP" else "IP Address", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.currentIP ?: "N/A",
                                    color = if (!user.currentIP.isNullOrBlank() && user.currentIP != "N/A") Color(0xFF4FC3F7) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!user.currentIP.isNullOrBlank() && user.currentIP != "N/A") {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = "Open",
                                        tint = Color(0xFF4FC3F7),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // Custom IP
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val nanoIp = matchingAccount?.nanoIp
                                    if (!nanoIp.isNullOrBlank() && nanoIp != "N/A") {
                                        try {
                                            val formattedIp = if (!nanoIp.startsWith("http://") && !nanoIp.startsWith("https://")) "http://$nanoIp" else nanoIp
                                            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(formattedIp))
                                            context.startActivity(browserIntent)
                                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                            android.widget.Toast.makeText(
                                                context,
                                                if (currentLang == "ar") "فشل فتح المتصفح" else "Failed to open browser",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (currentLang == "ar") "IP (مخصص)" else "Custom IP", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = matchingAccount?.nanoIp ?: "N/A",
                                    color = if (!matchingAccount?.nanoIp.isNullOrBlank() && matchingAccount?.nanoIp != "N/A") Color(0xFF4FC3F7) else Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!matchingAccount?.nanoIp.isNullOrBlank() && matchingAccount?.nanoIp != "N/A") {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = "Open",
                                        tint = Color(0xFF4FC3F7),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        // Expiration
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (currentLang == "ar") "تاريخ انتهاء الاشتراك" else "Expiration", color = Color(0xFF8E8E93), fontSize = 14.sp)
                            Text(text = finalExpirationStr ?: "N/A", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                if (showPassToolsDialog) {
                    PasswordToolsScreen(
                        userIndex = userIndex,
                        userId = user.userID,
                        viewModel = viewModel,
                        currentLang = currentLang,
                        onClose = { showPassToolsDialog = false }
                    )
                }

                if (showEditPackageDialog) {
                    val packagesList by viewModel.packages.collectAsStateWithLifecycle()
                    val defaultPackages = remember {
                        listOf(
                            com.example.core.model.AccountPackage(1, "Lite", true, 30000.0),
                            com.example.core.model.AccountPackage(2, "Economy", true, 40000.0),
                            com.example.core.model.AccountPackage(3, "Active", true, 50000.0),
                            com.example.core.model.AccountPackage(4, "Turbo", true, 65000.0),
                            com.example.core.model.AccountPackage(5, "Business", true, 100000.0)
                        )
                    }
                    val displayPackages = if (packagesList.isEmpty()) defaultPackages else packagesList

                    var selectedPkg by remember {
                        mutableStateOf(
                            displayPackages.find { it.accountName.lowercase() == (user.packageName?.trim()?.lowercase() ?: "") }
                                ?: displayPackages.firstOrNull()
                        )
                    }
                    var dropdownExpanded by rememberSaveable { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = { showEditPackageDialog = false },
                        title = { Text(if (currentLang == "ar") "تعديل نوع الاشتراك" else "Edit Package") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (currentLang == "ar") "اختر نوع الاشتراك الجديد للمشترك من القائمة:" else "Choose subscriber's new package from the dropdown list:")
                                
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedCard(
                                        onClick = { dropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = selectedPkg?.accountName ?: "Select Package",
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                            }
                                            Icon(
                                                imageVector = if (dropdownExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                                contentDescription = "Dropdown"
                                            )
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = dropdownExpanded,
                                        onDismissRequest = { dropdownExpanded = false },
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                    ) {
                                        displayPackages.forEach { pkg ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(pkg.accountName, fontWeight = FontWeight.Bold)
                                                },
                                                onClick = {
                                                    selectedPkg = pkg
                                                    dropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showEditPackageDialog = false
                                    selectedPkg?.let { pkg ->
                                        val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                            earthlinkUsername = user.userID,
                                            displayName = displayNameToUse,
                                            phone1 = user.mobileNumber,
                                            packageName = pkg.accountName,
                                            currentPriceIqd = pkg.price ?: 0.0
                                        )
                                        val updated = finalAcc.copy(
                                            packageName = pkg.accountName,
                                            currentPriceIqd = pkg.price ?: 0.0,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        coroutineScope.launch {
                                            repo.saveAccount(updated)
                                        }
                                        viewModel.changeAccountType(user.userIndex, user.userID, pkg.accountIndex, pkg.accountName)
                                    }
                                }
                            ) {
                                Text(if (currentLang == "ar") "تغيير" else "Change")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditPackageDialog = false }) {
                                Text(if (currentLang == "ar") "الغاء" else "Cancel")
                            }
                        }
                    )
                }

                if (showEditDisplayNameDialog) {
                    var newName by rememberSaveable { mutableStateOf(if (displayNameToUse != "N/A") displayNameToUse else "") }
                    AlertDialog(
                        onDismissRequest = { showEditDisplayNameDialog = false },
                        title = { Text(if (currentLang == "ar") "تعديل اسم المشترك الظاهر" else "Edit Display Name") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (currentLang == "ar") "أدخل اسم المشترك الجديد للظهور في التطبيق:" else "Enter new subscriber name for local display:")
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text(if (currentLang == "ar") "الاسم الظاهر" else "Display Name") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showEditDisplayNameDialog = false
                                    if (newName.isNotBlank()) {
                                        val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                            earthlinkUsername = user.userID,
                                            displayName = newName,
                                            phone1 = user.mobileNumber,
                                            packageName = user.packageName ?: "Unknown",
                                            currentPriceIqd = 45000.0
                                        )
                                        val updated = finalAcc.copy(
                                            displayName = newName,
                                            updatedAt = System.currentTimeMillis()
                                        )
                                        coroutineScope.launch {
                                            repo.saveAccount(updated)
                                        }
                                        viewModel.updateUserDisplayName(userIndex, newName)
                                    }
                                }
                            ) {
                                Text(if (currentLang == "ar") "حفظ" else "Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditDisplayNameDialog = false }) {
                                Text(if (currentLang == "ar") "الغاء" else "Cancel")
                            }
                        }
                    )
                }

                if (showStopUserDialog) {
                    val isSuspended = user.accountStatus?.trim()?.lowercase() == "suspendedbyagent"
                    ConfirmationDialog(
                        title = if (isSuspended) {
                            if (currentLang == "ar") "تفعيل حساب المشترك" else "Activate Account"
                        } else {
                            if (currentLang == "ar") "إيقاف حساب المشترك" else "Stop/Suspend Account"
                        },
                        message = if (isSuspended) {
                            if (currentLang == "ar") "هل أنت متأكد من رغبتك في إعادة تفعيل حساب المشترك ${user.userID}؟" else "Are you sure you want to activate/resume subscriber account ${user.userID}?"
                        } else {
                            if (currentLang == "ar") "هل أنت متأكد من رغبتك في إيقاف وتعطيل حساب المشترك ${user.userID} مؤقتاً؟" else "Are you sure you want to stop/suspend subscriber account ${user.userID} temporarily?"
                        },
                        needsPasswordField = false,
                        onCancel = { showStopUserDialog = false },
                        onConfirm = {
                            showStopUserDialog = false
                            viewModel.toggleUserActive(user.userIndex, user.userID, isSuspended)
                        }
                    )
                }

                if (showEditCustomIpDialog) {
                    var newIp by rememberSaveable { mutableStateOf(matchingAccount?.nanoIp ?: "") }
                    AlertDialog(
                        onDismissRequest = { showEditCustomIpDialog = false },
                        title = { Text(if (currentLang == "ar") "تعديل الـ IP المخصص" else "Edit Custom IP") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (currentLang == "ar") "أدخل الـ IP المخصص (Nano IP) لهذا المشترك:" else "Enter the custom Nano IP for this subscriber:")
                                OutlinedTextField(
                                    value = newIp,
                                    onValueChange = { newIp = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("e.g. 192.168.10.25") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showEditCustomIpDialog = false
                                    val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                                        earthlinkUsername = user.userID,
                                        displayName = displayNameToUse,
                                        phone1 = user.mobileNumber,
                                        packageName = user.packageName ?: "Economy",
                                        currentPriceIqd = 45000.0
                                    )
                                    val updated = finalAcc.copy(
                                        nanoIp = if (newIp.isNotBlank()) newIp else null,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    coroutineScope.launch {
                                        repo.saveAccount(updated)
                                    }
                                }
                            ) {
                                Text(if (currentLang == "ar") "حفظ" else "Save")
                             }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditCustomIpDialog = false }) {
                                Text(if (currentLang == "ar") "الغاء" else "Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
