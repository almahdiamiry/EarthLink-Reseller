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
fun SearchScreen(
    viewModel: EarthlinkSearchViewModel,
    lang: String = "ar",
    onUserSelect: (user: com.example.core.model.UserListItem) -> Unit
) {
    val focusManager = LocalFocusManager.current
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val users by viewModel.usersList.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val selectedProvider by viewModel.selectedProvider.collectAsStateWithLifecycle()
    val accessState by viewModel.prefs.providerAccessStateFlow.collectAsStateWithLifecycle(
        initialValue = viewModel.prefs.getProviderAccessState()
    )
    val hasBoth = (accessState.hasEarthlink || viewModel.prefs.getDemoMode()) && accessState.hasSamm

    val titleText = when (selectedProvider) {
        SasProviders.ALAMIRY -> if (lang == "ar") "البحث في سيرفر سام" else "SAMM Server Search"
        else -> if (lang == "ar") "البحث في سيرفر ايرثلنك" else "Earthlink Server Search"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = titleText, fontSize = 20.sp, fontWeight = FontWeight.Bold)

        if (hasBoth) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        Pair(SasProviders.EARTHLINK, if (lang == "ar") "ايرثلنك" else "EarthLink"),
                        Pair(SasProviders.ALAMIRY, if (lang == "ar") "سام" else "SAMM")
                    ).forEach { (providerKey, label) ->
                        val isSelected = selectedProvider == providerKey
                        val activeColor = if (providerKey == SasProviders.EARTHLINK) Color(0xFF0A84FF) else Color(0xFF7C4DFF)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (isSelected) activeColor.copy(alpha = 0.22f) else Color.Transparent)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) activeColor.copy(alpha = 0.6f) else Color.Transparent,
                                    shape = RoundedCornerShape(9.dp)
                                )
                                .clickable {
                                    if (selectedProvider != providerKey) {
                                        viewModel.setSelectedProvider(providerKey)
                                        if (query.isNotBlank()) {
                                            viewModel.search()
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            var localQuery by remember(query) { mutableStateOf(query) }
            OutlinedTextField(
                value = localQuery,
                onValueChange = {
                    localQuery = it
                    viewModel.setSearchQuery(it)
                },
                label = { Text(if (lang == "ar") "ابحث عن مشترك" else "Search subscriber") },
                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        if (!isLoading) {
                            focusManager.clearFocus()
                            viewModel.search()
                        }
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
            )

            Button(
                onClick = {
                    if (!isLoading) {
                        focusManager.clearFocus()
                        viewModel.search()
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.heightIn(min = 52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(imageVector = Icons.Default.Search, contentDescription = if (lang == "ar") "بحث" else "Search")
            }
        }

        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                Text(text = error ?: "", color = Color(0xFFC62828), modifier = Modifier.padding(16.dp), fontSize = 13.sp)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (users.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(imageVector = Icons.Outlined.ContentPasteOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = if (lang == "ar") "لا يوجد مشتركين أو لم يتم البحث بعد." else "No subscribers found or queried yet.", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(users, key = { it.userID }) { user ->
                    ArabicSubscriberCard(
                        user = user,
                        localAccounts = emptyList(),
                        lang = lang,
                        onClick = { onUserSelect(user) }
                    )
                }
            }
        }
    }
}

// --- EARTHLINK USER DETAIL SCREEN ---
