package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.ImeAction
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
fun LocalAccountDetailScreen(
    accountId: String,
    viewModel: LocalAccountsViewModel,
    onBack: () -> Unit,
    onMapOpen: (Double, Double) -> Unit
) {
    val account by viewModel.selectedAccount.collectAsStateWithLifecycle()
    val ledger by viewModel.ledgerEntries.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var showPaymentDialog by rememberSaveable { mutableStateOf(false) }
    var paymentIdempotencyKey by rememberSaveable { mutableStateOf("") }
    var showDebtDialog by rememberSaveable { mutableStateOf(false) }
    var debtIdempotencyKey by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(accountId) {
        val all = viewModel.filteredAccounts.value
        val entity = all.find { it.id == accountId }
        viewModel.selectAccount(entity)
    }

    if (showPaymentDialog) {
        var inputAmt by rememberSaveable { mutableStateOf("") }
        var inputNote by rememberSaveable { mutableStateOf("") }

        ConfirmationDialog(
            title = "Log Local Payment Recieved",
            message = "This decreases outstanding debt limit. Any excess value converts to prepaid advances.",
            onCancel = { 
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showPaymentDialog = false 
            },
            onConfirm = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showPaymentDialog = false
                val amt = com.example.core.ledger.MoneyParser.parseUiThousandsAmount(inputAmt)?.toDouble() ?: 0.0
                viewModel.addPaymentLocal(accountId, amt, inputNote, paymentIdempotencyKey.ifBlank { null })
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputAmt,
                    onValueChange = { inputAmt = it.replace("\n", "").replace("\r", "") },
                    label = { Text("Payment in IQD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = inputNote,
                    onValueChange = { inputNote = it },
                    label = { Text("Optional notes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDebtDialog) {
        var inputAmt by rememberSaveable { mutableStateOf("") }
        var inputNote by rememberSaveable { mutableStateOf("") }

        ConfirmationDialog(
            title = "Log Customer Debt/Loan",
            message = "Increases this subscriber's local debt balance.",
            onCancel = { 
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showDebtDialog = false 
            },
            onConfirm = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showDebtDialog = false
                val amt = com.example.core.ledger.MoneyParser.parseUiThousandsAmount(inputAmt)?.toDouble() ?: 0.0
                viewModel.addDebtLocal(accountId, amt, inputNote, debtIdempotencyKey.ifBlank { null })
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputAmt,
                    onValueChange = { inputAmt = it.replace("\n", "").replace("\r", "") },
                    label = { Text("Debt Load in IQD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                true
                            } else {
                                false
                            }
                        }
                )
                OutlinedTextField(
                    value = inputNote,
                    onValueChange = { inputNote = it },
                    label = { Text("Reason/Optional notes") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                true
                            } else {
                                false
                            }
                        }
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Local Customer File", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        val acc = account
        if (acc == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = acc.displayName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit")
                    }
                }
                Text(text = "Earthlink Username: ${acc.earthlinkUsername ?: "Unassociated"}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(text = "Primary Phone: ${acc.phone1 ?: "N/A"}", fontSize = 13.sp)
                Text(text = "Backup Phone: ${acc.phone2 ?: "N/A"}", fontSize = 13.sp)
                Text(text = "Tower node: ${acc.towerName ?: "N/A"} | IP: ${acc.nanoIp ?: "N/A"}", fontSize = 13.sp)
                Text(text = "Address: ${acc.address ?: "N/A"}", fontSize = 13.sp)

                val lat = acc.latitude
                val lon = acc.longitude
                if (lat != null && lon != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMapOpen(lat, lon) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.LocationOn, contentDescription = "GPS", tint = Color.Red, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "GPS Coordinates: $lat, $lon (Tap to open coordinates)", fontSize = 12.sp, color = Color.Blue)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(text = "Assigned Price", fontSize = 11.sp, color = Color.Gray)
                        Text(text = formatIqd(acc.currentPriceIqd), fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(text = "Debt Limit", fontSize = 11.sp, color = Color.Gray)
                        Text(text = formatIqd(acc.debtIqd), color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(text = "Advance Bank", fontSize = 11.sp, color = Color.Gray)
                        Text(text = formatIqd(acc.advanceIqd), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { 
                    paymentIdempotencyKey = java.util.UUID.randomUUID().toString()
                    showPaymentDialog = true 
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text("Recieve Payment")
            }

            Button(
                onClick = { 
                    debtIdempotencyKey = java.util.UUID.randomUUID().toString()
                    showDebtDialog = true 
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
            ) {
                Text("Charge Debt")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Ledger Transaction feed", fontWeight = FontWeight.Bold, fontSize = 15.sp)

        if (ledger.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                Text(text = "No prior ledger transactions for this record.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
                items(ledger, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Row(
                            modifier = Modifier.padding(10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val txType = if (item.typeRaw == "gave" || item.typeRaw == "payment") "RECEIVED PAYMENT" else if (item.typeRaw == "took" || item.typeRaw == "debt" || item.typeRaw == "debt_added") "DEBT INCREMENT" else if (item.typeRaw == "add" || item.typeRaw == "renewal") "RENEWAL" else "MEMO/NOTE"
                                val color = if (item.typeRaw == "gave" || item.typeRaw == "payment") Color(0xFF2E7D32) else if (item.typeRaw == "took" || item.typeRaw == "debt" || item.typeRaw == "debt_added") Color(0xFFC62828) else Color.DarkGray

                                Text(text = txType, fontWeight = FontWeight.Bold, color = color, fontSize = 12.sp)
                                val cleanNote = com.example.core.ledger.NoteCleaner.extractGenuineNote(item.note, item.amountIqd)
                                if (cleanNote.isNotBlank()) {
                                    Text(text = cleanNote, fontSize = 13.sp)
                                }
                                Text(
                                    text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.occurredAt)),
                                    color = Color.Gray,
                                    fontSize = 11.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (item.amountIqd > 0.0) {
                                    val sign = if (item.typeRaw == "gave" || item.typeRaw == "payment") "-" else "+"
                                    Text(text = "$sign${formatIqd(item.amountIqd)}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                                Text(text = "Remaining Debt: ${formatIqd(item.debtAfterIqd)}", color = Color.Gray, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        if (showEditDialog) {
            EditLocalAccountDialog(
                account = acc,
                onDismiss = { showEditDialog = false },
                onSave = {
                    showEditDialog = false
                    viewModel.saveAccountEdit(it)
                },
                onDelete = {
                    showEditDialog = false
                    viewModel.deleteAccountLocal(acc.id)
                    onBack()
                }
            )
        }
    }
}

// Dialog overlay with content closure for custom fields inputs
