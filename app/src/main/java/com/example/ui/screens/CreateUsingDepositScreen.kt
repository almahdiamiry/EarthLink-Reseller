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
fun CreateUsingDepositScreen(
    viewModel: EarthlinkSearchViewModel
) {
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

    LaunchedEffect(selectedPkgIndex) {
        if (selectedPkgIndex != -1) {
            viewModel.previewPackageCost(selectedPkgIndex)
        }
    }

    if (showConfirmDialog) {
        val selectedPkgName = pkgs.find { it.accountIndex == selectedPkgIndex }?.accountName ?: ""
        val costStr = if (costPreview != null) formatIqd(costPreview!!) else "the calculated tier rate"
        ConfirmationDialog(
            title = "Confirm Paid Account Creation",
            message = "Generate paid subscription $userId? This consumes $costStr from your reseller deposit.",
            needsPasswordField = true,
            onCancel = { showConfirmDialog = false },
            onConfirm = { pass ->
                showConfirmDialog = false
                viewModel.createUserUsingDeposit(userId, phone, name, selectedPkgIndex, pass)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Create Profile Using Deposit", fontSize = 20.sp, fontWeight = FontWeight.Bold)

        if (error != null) {
            Surface(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(4.dp)) {
                Text(text = error ?: "", color = Color(0xFFC62828), modifier = Modifier.padding(10.dp), fontSize = 13.sp)
            }
        }
        if (success != null) {
            Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(4.dp)) {
                Text(text = success ?: "", color = Color(0xFF2E7D32), modifier = Modifier.padding(10.dp), fontSize = 13.sp)
            }
        }

        OutlinedTextField(value = userId, onValueChange = { userId = it }, label = { Text("New User ID Username") }, singleLine = true)
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Customer Name") }, singleLine = true)
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Customer Tel") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)

        Text(text = "Select Package Class", fontWeight = FontWeight.Bold, fontSize = 14.sp)

        pkgs.forEach { pkg ->
            val isSel = selectedPkgIndex == pkg.accountIndex
            Card(
                colors = CardDefaults.cardColors(containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedPkgIndex = pkg.accountIndex }
            ) {
                Text(text = pkg.accountName, modifier = Modifier.padding(12.dp))
            }
        }

        if (costPreview != null && selectedPkgIndex != -1) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = "Tier Cost Estimation:")
                    Text(text = formatIqd(costPreview!!), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { showConfirmDialog = true },
            enabled = userId.isNotEmpty() && selectedPkgIndex != -1 && !isActionLoading,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        ) {
            Text("Issue Paid Subscriber", fontWeight = FontWeight.Bold)
        }
    }
}

// --- ACCOUNT STATEMENT BOARD SCREEN ---
