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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.*
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
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

object HistoryPresentationManager {
    fun prepareDisplayLedgerList(rawList: List<com.example.core.model.LocalLedgerEntry>): List<com.example.core.model.LocalLedgerEntry> {
        if (rawList.isEmpty()) return emptyList()

        val pairedChargeIds = mutableSetOf<String>()
        for (payment in rawList) {
            if (payment.isSnapshotHistory) continue
            val payId = payment.id
            if (!payId.startsWith("pay_charge_")) continue

            val paymentCanonicalType = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(payment.typeRaw)
            if (paymentCanonicalType != "gave" && paymentCanonicalType != "payment" && payment.typeRaw != "gave") continue

            val expectedChargeId = payId.removePrefix("pay_")
            val matchingCharge = rawList.find { charge ->
                !charge.isSnapshotHistory &&
                charge.id == expectedChargeId &&
                charge.accountId == payment.accountId &&
                kotlin.math.abs(charge.amountIqd - payment.amountIqd) < 0.0001
            }
            if (matchingCharge != null) {
                pairedChargeIds.add(matchingCharge.id)
            }
        }

        return rawList.filterNot { it.id in pairedChargeIds }
    }

    fun classifyHistoryItem(
        entry: com.example.core.model.LocalLedgerEntry,
        fullList: List<com.example.core.model.LocalLedgerEntry> = emptyList()
    ): String {
        val noteNonNull = entry.note ?: ""
        val isLegacyRenew = noteNonNull.startsWith("[RENEW]")
        val isLegacyRenewPay = noteNonNull.startsWith("[RENEW_PAY]")
        val isDebt = noteNonNull.startsWith("[DEBT]")
        val isDeposit = noteNonNull.startsWith("[DEPOSIT]")
        val isPayment = noteNonNull.startsWith("[PAYMENT]")

        val isChargeIdRenew = entry.id.startsWith("charge_")
        val isCanonicalRenewal = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(entry.typeRaw) == "renewal"

        val isUtowerHistoricalRecord = entry.isSnapshotHistory &&
            (!entry.sourceBatchId.isNullOrBlank() || !entry.sourceExternalId.isNullOrBlank())
        val isUtowerHistoricalWasel = isUtowerHistoricalRecord &&
            isCanonicalRenewal &&
            noteNonNull.contains("(واصل)")

        val isPairedRenewalPayment = if (!entry.isSnapshotHistory && entry.id.startsWith("pay_charge_")) {
            if (fullList.isNotEmpty()) {
                val expectedChargeId = entry.id.removePrefix("pay_")
                fullList.any { charge ->
                    !charge.isSnapshotHistory &&
                    charge.id == expectedChargeId &&
                    charge.accountId == entry.accountId &&
                    kotlin.math.abs(charge.amountIqd - entry.amountIqd) < 0.0001
                }
            } else {
                true
            }
        } else {
            false
        }

        val isRenewPay = isLegacyRenewPay || entry.typeRaw.equals("renewal_payment", ignoreCase = true) || isUtowerHistoricalWasel || isPairedRenewalPayment
        val isRenew = isLegacyRenew || isChargeIdRenew || isCanonicalRenewal

        return when {
            isRenewPay -> "renew_pay"
            isRenew -> "renew"
            isDebt -> "debt"
            isDeposit -> "deposit"
            isPayment -> "payment"
            else -> {
                val normType = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(entry.typeRaw)
                when (normType) {
                    "renewal" -> "renew"
                    "took" -> "debt"
                    "gave" -> {
                        if (noteNonNull.contains("إيداع") || noteNonNull.contains("ايداع") || noteNonNull.contains("Deposit") || noteNonNull.contains("deposit")) "deposit"
                        else "payment"
                    }
                    else -> "payment"
                }
            }
        }
    }
}

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
            
            val focusManager = LocalFocusManager.current
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

            val performRefill: () -> Unit = {
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
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
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
                                    val chargeNote = noteVal.trim()
                                    val payNote = if (isWasil) noteVal.trim() else null
                                    
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
            }
            
            Dialog(
                onDismissRequest = { 
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    isSubmitting = false
                    showRefillDialog = false 
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .systemBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 580.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                        border = BorderStroke(1.dp, Color(0xFF1F2937))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(40.dp)
                                    .height(4.dp)
                                    .background(Color.Gray.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                            )

                            Text(
                                text = if (currentLang == "ar") "تجديد الاشتراك" else "Subscription Renewal",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "سعر الاشتراك" else "Subscription Price",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                
                                var isFocused by rememberSaveable { mutableStateOf(false) }
                                var hasClearedOnFirstFocus by rememberSaveable { mutableStateOf(false) }
                                
                                BasicTextField(
                                    value = priceInput,
                                    onValueChange = { input ->
                                        val cleanInput = input.replace("\n", "").replace("\r", "")
                                        val trimmed = cleanInput.trim()
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
                                        }
                                        .onPreviewKeyEvent { keyEvent ->
                                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                                if (!isSubmitting) {
                                                    performRefill()
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF90CAF9)),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (!isSubmitting) {
                                            performRefill()
                                        }
                                    }),
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
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161F2C)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (currentLang == "ar") "تكلفة الاشتراك" else "Package Cost",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = if (isLoadingApiData) "..." else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(packageCost.toDouble())} د.ع",
                                            color = Color.White,
                                            fontSize = 13.sp,
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
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = if (isLoadingApiData) "..." else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(resellerBalance.toDouble())} د.ع",
                                            color = Color.White,
                                            fontSize = 13.sp,
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
                                            fontSize = 13.sp
                                        )
                                        val balanceColor = if (balanceAfter >= 0) Color(0xFF34D399) else Color(0xFFF87171)
                                        Text(
                                            text = if (isLoadingApiData) "..." else "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(balanceAfter.toDouble())} د.ع",
                                            color = balanceColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            OutlinedTextField(
                                value = noteInput,
                                onValueChange = { noteInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(if (currentLang == "ar") "ملاحظة (اختياري)" else "Note (Optional)", color = Color(0xFF9CA3AF), fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (!isSubmitting) {
                                        performRefill()
                                    }
                                }),
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
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { isWasilChecked = !isWasilChecked },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isWasilChecked) Color(0xFF1E3A8A) else Color.Transparent
                                    ),
                                    border = BorderStroke(1.dp, if (isWasilChecked) Color(0xFF3B82F6) else Color(0xFF374151)),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = if (currentLang == "ar") "واصل" else "Received",
                                            color = if (isWasilChecked) Color.White else Color(0xFF9CA3AF),
                                            fontSize = 13.sp,
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
                                        .height(40.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPrintChecked) Color(0xFF374151) else Color.Transparent
                                    ),
                                    border = BorderStroke(1.dp, if (isPrintChecked) Color(0xFF6B7280) else Color(0xFF374151)),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = if (currentLang == "ar") "طباعة" else "Print",
                                            color = if (isPrintChecked) Color.White else Color(0xFF9CA3AF),
                                            fontSize = 13.sp,
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
                                onClick = { performRefill() },
                                enabled = !isSubmitting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                                shape = RoundedCornerShape(24.dp)
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
    }

    if (showDepositDialog) {
        val user = detail
        if (user != null) {
            val focusManager = LocalFocusManager.current
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200)
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            var priceInput by rememberSaveable { mutableStateOf("") }
            var noteInput by rememberSaveable { mutableStateOf("") }
            var isSubmitting by rememberSaveable { mutableStateOf(false) }
            
            val currentDebt = matchingAccount?.debtIqd ?: 0.0
val parsedPrice = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
            val currentAdvance = matchingAccount?.advanceIqd ?: 0.0
            val currentLoan = matchingAccount?.loanIqd ?: 0.0

            val performDeposit: () -> Unit = {
                val parsedPriceVal = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
                if (parsedPriceVal <= 0.0) {
                    android.widget.Toast.makeText(
                        context,
                        if (currentLang == "ar") "الرجاء إدخال مبلغ صحيح!" else "Please enter a valid amount!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                        earthlinkUsername = user.userID,
                        displayName = user.customerFullName ?: user.userID,
                        phone1 = user.mobileNumber,
                        packageName = user.packageName ?: "Default",
                        createdAt = System.currentTimeMillis()
                    )
                    
                    isSubmitting = true
                    val noteVal = noteInput.trim()
                    val accToUse = matchingAccount ?: finalAcc
                    val baseNote = if ((matchingAccount?.debtIqd ?: 0.0) > 0) "[PAYMENT]" else "[DEPOSIT]"
                    val payNote = if (noteVal.isNotBlank()) "$baseNote $noteVal" else null
                    
                    coroutineScope.launch {
                        try {
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
                                    action = "DEPOSIT_PAYMENT",
                                    entityType = "USER",
                                    entityId = user.userID,
                                    summary = "Recorded payment amount $parsedPriceVal. Note: $payNote"
                                )
                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                            android.util.Log.e("UserDetailScreen", "Failed to add deposit", e)
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
            }

            val postDepositBalances = com.example.core.ledger.BalanceCalculator.applyTransaction(
                currentDebt = currentDebt,
                currentAdvance = currentAdvance,
                currentLoan = currentLoan,
                txType = "gave",
                amount = parsedPrice
            )
            
            Dialog(
                onDismissRequest = { 
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    isSubmitting = false
                    showDepositDialog = false 
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .systemBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 580.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                        border = BorderStroke(1.dp, Color(0xFF1F2937))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (currentLang == "ar") if ((matchingAccount?.debtIqd ?: 0.0) > 0) "مبلغ التسديد" else "مبلغ الإيداع" else if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Payment Amount" else "Deposit Amount",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                
                                var isFocused by rememberSaveable { mutableStateOf(false) }
                                
                                BasicTextField(
                                    value = priceInput,
                                    onValueChange = { input ->
                                        val cleanInput = input.replace("\n", "").replace("\r", "")
                                        val trimmed = cleanInput.trim()
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
                                        }
                                        .onPreviewKeyEvent { keyEvent ->
                                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                                if (!isSubmitting) {
                                                    performDeposit()
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF90CAF9)),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (!isSubmitting) {
                                            performDeposit()
                                        }
                                    }),
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
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161F2C)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (currentLang == "ar") "الرصيد المالي الحالي" else "Current Position",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 13.sp
                                        )
                                        val displayStr = if (currentDebt > 0) {
                                            "مطلوب: \u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(currentDebt)} د.ع"
                                        } else if (currentAdvance > 0) {
                                            "واصل: \u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(currentAdvance)} د.ع"
                                        } else {
                                            "خالص: 0 د.ع"
                                        }
                                        val curColor = if (currentDebt > 0) Color(0xFFF87171) else if (currentAdvance > 0) Color(0xFF34D399) else Color.White
                                        Text(
                                            text = displayStr,
                                            color = curColor,
                                            fontSize = 13.sp,
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
                                            text = if (currentLang == "ar") "الرصيد المالي بعد العملية" else "Position After",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 13.sp
                                        )
                                        val displayStr = if (postDepositBalances.debtIqd > 0) {
                                            "مطلوب: \u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDepositBalances.debtIqd)} د.ع"
                                        } else if (postDepositBalances.advanceIqd > 0) {
                                            "واصل: \u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDepositBalances.advanceIqd)} د.ع"
                                        } else {
                                            "خالص: 0 د.ع"
                                        }
                                        val curColor = if (postDepositBalances.debtIqd > 0) Color(0xFFF87171) else if (postDepositBalances.advanceIqd > 0) Color(0xFF34D399) else Color.White
                                        Text(
                                            text = displayStr,
                                            color = curColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            OutlinedTextField(
                                value = noteInput,
                                onValueChange = { noteInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(if (currentLang == "ar") "ملاحظة (اختياري)" else "Note (Optional)", color = Color(0xFF9CA3AF), fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (!isSubmitting) {
                                        performDeposit()
                                    }
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF374151)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            
                            Button(
                                onClick = { performDeposit() },
                                enabled = !isSubmitting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                                shape = RoundedCornerShape(24.dp)
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
            val focusManager = LocalFocusManager.current
            val focusRequester = remember { FocusRequester() }
            val keyboardController = LocalSoftwareKeyboardController.current
            
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(200)
                focusRequester.requestFocus()
                keyboardController?.show()
            }

            var priceInput by rememberSaveable { mutableStateOf("") }
            var noteInput by rememberSaveable { mutableStateOf("") }
            var isSubmitting by rememberSaveable { mutableStateOf(false) }
            
            val currentDebt = matchingAccount?.debtIqd ?: 0.0
            val parsedPrice = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
            val currentAdvance = matchingAccount?.advanceIqd ?: 0.0
            val currentLoan = matchingAccount?.loanIqd ?: 0.0

            val postDebtBalances = com.example.core.ledger.BalanceCalculator.applyTransaction(
                currentDebt = currentDebt,
                currentAdvance = currentAdvance,
                currentLoan = currentLoan,
                txType = "took",
                amount = parsedPrice
            )
            
            val performAddDebt: () -> Unit = {
                val parsedPriceVal = (com.example.core.ledger.MoneyParser.parseUiThousandsAmount(priceInput) ?: 0L).toDouble()
                if (parsedPriceVal <= 0.0) {
                    android.widget.Toast.makeText(
                        context,
                        if (currentLang == "ar") "الرجاء إدخال مبلغ صحيح!" else "Please enter a valid amount!",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    val finalAcc = matchingAccount ?: com.example.core.model.LocalAccount(
                        earthlinkUsername = user.userID,
                        displayName = user.customerFullName ?: user.userID,
                        phone1 = user.mobileNumber,
                        packageName = user.packageName ?: "Default",
                        createdAt = System.currentTimeMillis()
                    )
                    
                    isSubmitting = true
                    val noteVal = noteInput.trim()
                    val accToUse = matchingAccount ?: finalAcc
                    val debtNote = if (noteVal.isNotBlank()) "[DEBT] $noteVal" else null
                    
                    coroutineScope.launch {
                        try {
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
                                    summary = "Added debt amount $parsedPriceVal. Note: $debtNote"
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
            }

            Dialog(
                onDismissRequest = { 
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    isSubmitting = false
                    showDebtDialog = false 
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .systemBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 580.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF10161D)),
                        border = BorderStroke(1.dp, Color(0xFF1F2937))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (currentLang == "ar") "مبلغ الدين" else "Debt Amount",
                                    color = Color(0xFF8E8E93),
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(bottom = 2.dp)
                                )
                                
                                var isFocused by rememberSaveable { mutableStateOf(false) }
                                
                                BasicTextField(
                                    value = priceInput,
                                    onValueChange = { input ->
                                        val cleanInput = input.replace("\n", "").replace("\r", "")
                                        val trimmed = cleanInput.trim()
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
                                        }
                                        .onPreviewKeyEvent { keyEvent ->
                                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                                if (!isSubmitting) {
                                                    performAddDebt()
                                                }
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End
                                    ),
                                    cursorBrush = SolidColor(Color(0xFF90CAF9)),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        if (!isSubmitting) {
                                            performAddDebt()
                                        }
                                    }),
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
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF161F2C)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (currentLang == "ar") "الدين الحالي للمشترك" else "Current Debt",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(currentDebt.toDouble())} د.ع",
                                            color = Color.White,
                                            fontSize = 13.sp,
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
                                            text = if (currentLang == "ar") "الدين بعد الإضافة" else "Debt After",
                                            color = Color(0xFF9CA3AF),
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "\u200E${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDebtBalances.debtIqd)} د.ع",
                                            color = Color(0xFFF87171),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            OutlinedTextField(
                                value = noteInput,
                                onValueChange = { noteInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(if (currentLang == "ar") "ملاحظة (اختياري)" else "Note (Optional)", color = Color(0xFF9CA3AF), fontSize = 12.sp) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (!isSubmitting) {
                                        performAddDebt()
                                    }
                                }),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF374151)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            
                            Button(
                                onClick = { performAddDebt() },
                                enabled = !isSubmitting,
                                modifier = Modifier
                                    .fillMaxWidth()
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
            val entryBalancesMap = remember(finalLedgerList, matchingAccount) {
                val openingDebt = matchingAccount?.openingDebtIqd ?: 0.0
                val openingAdvance = matchingAccount?.openingAdvanceIqd ?: 0.0
                val openingLoan = matchingAccount?.openingLoanIqd ?: 0.0

                val eligibleTxs = if (matchingAccount?.stateSource != null) {
                    finalLedgerList.filter { !it.isSnapshotHistory }
                } else {
                    finalLedgerList
                }

                val sortedTxs = eligibleTxs.sortedWith(
                    compareBy<com.example.core.model.LocalLedgerEntry> { it.occurredAt }
                        .thenBy { it.sourceExternalId ?: "" }
                        .thenBy { it.id }
                )

                var runningDebt = openingDebt
                var runningAdvance = openingAdvance
                var runningLoan = openingLoan

                val map = mutableMapOf<String, Pair<Double, Double>>()
                for (tx in sortedTxs) {
                    val canonicalType = com.example.core.ledger.TransactionTypeNormalizer.normalizeTransactionType(tx.typeRaw)
                    val updatedBalances = com.example.core.ledger.BalanceCalculator.applyTransaction(
                        currentDebt = runningDebt,
                        currentAdvance = runningAdvance,
                        currentLoan = runningLoan,
                        txType = canonicalType,
                        amount = tx.amountIqd
                    )
                    runningDebt = updatedBalances.debtIqd
                    runningAdvance = updatedBalances.advanceIqd
                    runningLoan = updatedBalances.loanIqd
                    map[tx.id] = Pair(runningDebt, runningAdvance)
                }
                map
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
                    val displayLedgerList = remember(finalLedgerList) {
                        HistoryPresentationManager.prepareDisplayLedgerList(finalLedgerList)
                    }
                    if (displayLedgerList.isEmpty()) {
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
                            items(displayLedgerList, key = { it.id }) { entry ->
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
                                    val resolvedType = HistoryPresentationManager.classifyHistoryItem(entry, finalLedgerList)

                                    // Two Primary Visual States: Paid / Received vs Debt / Unpaid
                                    val isPaidState = resolvedType == "renew_pay" || resolvedType == "payment" || resolvedType == "deposit"
                                    val stateColor = if (isPaidState) Color(0xFF10B981) else Color(0xFFEF4444)
                                    val stateIcon = if (isPaidState) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.TrendingUp

                                    val cleanNote = com.example.core.ledger.NoteCleaner.extractGenuineNote(entry.note, entry.amountIqd.toDouble())

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.Start,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val titleText = when (resolvedType) {
                                            "debt" -> {
                                                if (currentLang == "ar") "إضافة دين: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Added debt: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            "deposit" -> {
                                                if (currentLang == "ar") "إيداع رصيد: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Deposit: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            "payment" -> {
                                                if (currentLang == "ar") "تسديد مبلغ: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Payment: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                            "renew_pay" -> {
                                                if (currentLang == "ar") "تجديد اشتراك: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع — واصل"
                                                else "Subscription renewal: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD — Paid"
                                            }
                                            else -> { // renew
                                                if (currentLang == "ar") "تجديد اشتراك: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} د.ع"
                                                else "Subscription renewal: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(entry.amountIqd.toDouble())} IQD"
                                            }
                                        }
                                        
                                        Text(
                                            text = titleText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color.White
                                        )
                                        
                                        Text(
                                            text = if (currentLang == "ar") "التاريخ: ${formatLedgerDate(entry.occurredAt)}" else "Date: ${formatLedgerDate(entry.occurredAt)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF8E8E93)
                                        )
                                        
                                        val (postDebt, postAdvance) = entryBalancesMap[entry.id]
                                            ?: if (entry.debtAfterIqd < 0.0) Pair(0.0, -entry.debtAfterIqd.toDouble()) else Pair(entry.debtAfterIqd.toDouble(), 0.0)
                                        
                                        val balanceText = when {
                                            postDebt > 0.0 && postAdvance > 0.0 -> {
                                                if (currentLang == "ar") "الدين المتبقي: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDebt)} د.ع • رصيد مقدم: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postAdvance)} د.ع"
                                                else "Remaining debt: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDebt)} IQD • Advance: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postAdvance)} IQD"
                                            }
                                            postDebt == 0.0 && postAdvance > 0.0 -> {
                                                if (currentLang == "ar") "الدين المتبقي: 0 د.ع • رصيد مقدم: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postAdvance)} د.ع"
                                                else "Remaining debt: 0 IQD • Advance: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postAdvance)} IQD"
                                            }
                                            postDebt > 0.0 -> {
                                                if (currentLang == "ar") "الدين المتبقي: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDebt)} د.ع"
                                                else "Remaining debt: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postDebt)} IQD"
                                            }
                                            postAdvance > 0.0 -> {
                                                if (currentLang == "ar") "رصيد مقدم: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postAdvance)} د.ع"
                                                else "Advance credit: ${com.example.core.ledger.MoneyParser.formatIqdForDisplay(postAdvance)} IQD"
                                            }
                                            else -> {
                                                if (currentLang == "ar") "الدين المتبقي: 0 د.ع (مسدد)"
                                                else "Remaining debt: 0 IQD (Settled)"
                                            }
                                        }
                                        
                                        Text(
                                            text = balanceText,
                                            fontSize = 12.sp,
                                            color = Color(0xFFA1A1AA)
                                        )
                                        
                                        if (cleanNote.isNotBlank()) {
                                            Text(
                                                text = if (currentLang == "ar") "ملاحظة: $cleanNote" else "Note: $cleanNote",
                                                fontSize = 12.sp,
                                                color = Color(0xFF93C5FD),
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(12.dp))
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .background(stateColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = stateIcon,
                                            contentDescription = if (isPaidState) "Paid" else "Debt",
                                            tint = Color.White,
                                            modifier = Modifier.size(22.dp)
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
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        var noteText by rememberSaveable { mutableStateOf(matchingAccount?.note ?: "") }
        AlertDialog(
            onDismissRequest = { 
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showNotesDialog = false 
            },
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
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
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
                TextButton(onClick = { 
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    showNotesDialog = false 
                }) {
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
                        Box {
                            IconButton(onClick = { expanded = true }) {
                                Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier
                                    .background(Color(0xFF161C26), shape = RoundedCornerShape(16.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Color(0xFF161C26)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentLang == "ar") "تعديل نوع الاشتراك" else "Edit Package",
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = null,
                                            tint = Color(0xFF0288D1),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showEditPackageDialog = true
                                    }
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentLang == "ar") "تعديل اسم المشترك الظاهر" else "Edit Display Name",
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Badge,
                                            contentDescription = null,
                                            tint = Color(0xFF0288D1),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showEditDisplayNameDialog = true
                                    }
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentLang == "ar") "تمديد المشترك" else "Extend User",
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.HourglassTop,
                                            contentDescription = null,
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showExtendDialog = true
                                    }
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                                val isSuspended = detail?.accountStatus?.trim()?.lowercase() == "suspendedbyagent"
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = if (isSuspended) {
                                                if (currentLang == "ar") "تفعيل المشترك" else "Resume/Activate User"
                                            } else {
                                                if (currentLang == "ar") "إيقاف المشترك" else "Stop/Suspend User"
                                            },
                                            color = if (isSuspended) Color(0xFF30D158) else Color(0xFFFF453A),
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isSuspended) Icons.Default.PlayCircle else Icons.Default.PauseCircle,
                                            contentDescription = null,
                                            tint = if (isSuspended) Color(0xFF30D158) else Color(0xFFFF453A),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showStopUserDialog = true
                                    }
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentLang == "ar") "IP مخصص (إضافة/تعديل)" else "Edit Custom IP",
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Router,
                                            contentDescription = null,
                                            tint = Color(0xFF0288D1),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showEditCustomIpDialog = true
                                    }
                                )
                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.8.dp)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentLang == "ar") "تعديل كلمات المرور" else "Edit Passwords",
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.LockReset,
                                            contentDescription = null,
                                            tint = Color(0xFF0288D1),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    onClick = {
                                        expanded = false
                                        showPassToolsDialog = true
                                    }
                                )
                            }
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
                        .padding(bottom = 16.dp, start = 14.dp, end = 14.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showDepositDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C242F)),
                        border = BorderStroke(1.dp, Color(0xFF2C3E50)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (currentLang == "ar") {
                                    if ((matchingAccount?.debtIqd ?: 0.0) > 0) "تسديد مبلغ" else "ايداع مبلغ"
                                } else {
                                    if ((matchingAccount?.debtIqd ?: 0.0) > 0) "Pay Amount" else "Deposit Amount"
                                },
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Payment,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Button(
                        onClick = { showRefillDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF90CAF9)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (currentLang == "ar") "تجديد الإشتراك" else "Renew Subscription",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
                            )
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
                    .padding(horizontal = 14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                
                // Blue Identity Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E88E5)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "E",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White.copy(alpha = 0.22f),
                            modifier = Modifier.align(Alignment.TopEnd)
                        )

                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = if (currentLang == "ar") "الاسم" else "Name",
                                    fontSize = 11.5.sp,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = displayNameToUse,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val debtVal = matchingAccount?.debtIqd ?: 0.0
                                val advanceVal = matchingAccount?.advanceIqd ?: 0.0
                                if (debtVal > 0) {
                                    Text(
                                        text = if (currentLang == "ar") "الديون" else "Debt",
                                        fontSize = 11.5.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = formatIqd(debtVal),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                } else if (advanceVal > 0) {
                                    Text(
                                        text = if (currentLang == "ar") "مودع" else "Deposited",
                                        fontSize = 11.5.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = formatIqd(advanceVal),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                } else {
                                    Text(
                                        text = if (currentLang == "ar") "الديون" else "Debt",
                                        fontSize = 11.5.sp,
                                        color = Color.White.copy(alpha = 0.8f)
                                    )
                                    Text(
                                        text = "0",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Dark Details Container Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    border = BorderStroke(1.dp, Color(0xFF2C2C2E).copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // --- GROUP 1: Status & Session Information ---

                        // Status
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "الحالة" else "Status",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(if (isReallyOnline) Color(0xFF30D158) else Color(0xFFFF453A), shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isReallyOnline && currentLang == "ar") "متصل" else if (isReallyOnline) "Online" else if (currentLang == "ar") "غير متصل" else "Offline",
                                    color = if (isReallyOnline) Color(0xFF30D158) else Color(0xFFFF453A),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Account Status
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "حالة الحساب" else "Account Status",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
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
                            Text(text = statusLabel, color = statusColor, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }

                        // Online Duration
                        val connTime = user.onlineSessionTime
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "مدة الاتصال" else "Online Duration",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = if (!connTime.isNullOrBlank()) connTime else "N/A",
                                color = if (!connTime.isNullOrBlank() && connTime != "N/A") Color(0xFF30D158) else Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Remaining
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "المتبقي" else "Remaining",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = remainingTime,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // --- GROUP 2: Subscription & Financial Information ---

                        // Sub Price
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "سعر الإشتراك" else "Sub Price",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = formatIqd(matchingAccount?.currentPriceIqd ?: 40000.0),
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Package Name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "نوع الإشتراك" else "Package",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = user.packageName ?: "Unknown",
                                color = Color(0xFF90CAF9),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Expiration
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "تاريخ انتهاء الاشتراك" else "Expiration",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = finalExpirationStr ?: "N/A",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // --- GROUP 3: Identity & Network Information ---

                        // Username (Clickable with copy icon)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
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
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                    }
                                }
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "اليوزر" else "Username",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.userID,
                                    color = Color(0xFF90CAF9),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = Color(0xFF90CAF9),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        // Phone
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "رقم الهاتف الاول" else "Phone 1",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Text(
                                text = user.mobileNumber ?: "N/A",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Current IP (Clickable to open)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val ip = user.currentIP
                                    if (!ip.isNullOrBlank() && ip != "N/A") {
                                        try {
                                            val formattedIp = if (!ip.startsWith("http://") && !ip.startsWith("https://")) "http://$ip" else ip
                                            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(formattedIp))
                                            context.startActivity(browserIntent)
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            android.widget.Toast.makeText(
                                                context,
                                                if (currentLang == "ar") "فشل فتح المتصفح" else "Failed to open browser",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "IP" else "IP Address",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = user.currentIP ?: "N/A",
                                    color = if (!user.currentIP.isNullOrBlank() && user.currentIP != "N/A") Color(0xFF4FC3F7) else Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!user.currentIP.isNullOrBlank() && user.currentIP != "N/A") {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = "Open",
                                        tint = Color(0xFF4FC3F7),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }

                        // Custom IP (Clickable to open)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val nanoIp = matchingAccount?.nanoIp
                                    if (!nanoIp.isNullOrBlank() && nanoIp != "N/A") {
                                        try {
                                            val formattedIp = if (!nanoIp.startsWith("http://") && !nanoIp.startsWith("https://")) "http://$nanoIp" else nanoIp
                                            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(formattedIp))
                                            context.startActivity(browserIntent)
                                        } catch (e: Exception) {
                                            if (e is kotlinx.coroutines.CancellationException) throw e
                                            android.widget.Toast.makeText(
                                                context,
                                                if (currentLang == "ar") "فشل فتح المتصفح" else "Failed to open browser",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                .padding(vertical = 1.5.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLang == "ar") "IP (مخصص)" else "Custom IP",
                                color = Color(0xFF8E8E93),
                                fontSize = 13.5.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = matchingAccount?.nanoIp ?: "N/A",
                                    color = if (!matchingAccount?.nanoIp.isNullOrBlank() && matchingAccount?.nanoIp != "N/A") Color(0xFF4FC3F7) else Color.White,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (!matchingAccount?.nanoIp.isNullOrBlank() && matchingAccount?.nanoIp != "N/A") {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = "Open",
                                        tint = Color(0xFF4FC3F7),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
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
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current
                    var newName by rememberSaveable { mutableStateOf(if (displayNameToUse != "N/A") displayNameToUse else "") }
                    AlertDialog(
                        onDismissRequest = { 
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            showEditDisplayNameDialog = false 
                        },
                        title = { Text(if (currentLang == "ar") "تعديل اسم المشترك الظاهر" else "Edit Display Name") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (currentLang == "ar") "أدخل اسم المشترك الجديد للظهور في التطبيق:" else "Enter new subscriber name for local display:")
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                    }),
                                    placeholder = { Text(if (currentLang == "ar") "الاسم الظاهر" else "Display Name") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
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
                            TextButton(onClick = { 
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                showEditDisplayNameDialog = false 
                            }) {
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
                    val focusManager = LocalFocusManager.current
                    val keyboardController = LocalSoftwareKeyboardController.current
                    var newIp by rememberSaveable { mutableStateOf(matchingAccount?.nanoIp ?: "") }
                    AlertDialog(
                        onDismissRequest = { 
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            showEditCustomIpDialog = false 
                        },
                        title = { Text(if (currentLang == "ar") "تعديل الـ IP المخصص" else "Edit Custom IP") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (currentLang == "ar") "أدخل الـ IP المخصص (Nano IP) لهذا المشترك:" else "Enter the custom Nano IP for this subscriber:")
                                OutlinedTextField(
                                    value = newIp,
                                    onValueChange = { newIp = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                    }),
                                    placeholder = { Text("e.g. 192.168.10.25") }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
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
                            TextButton(onClick = { 
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                showEditCustomIpDialog = false 
                            }) {
                                Text(if (currentLang == "ar") "الغاء" else "Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
