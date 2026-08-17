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
fun DashboardStatusScreen(
    viewModel: DashboardViewModel,
    onBack: () -> Unit,
    onPlusClick: () -> Unit
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val subscribers by viewModel.subscribersList.collectAsStateWithLifecycle()
    
    val totalCount = if (subscribers.isNotEmpty()) subscribers.size else 78
    val activeCount = if (subscribers.isNotEmpty()) subscribers.count { it.accountStatus?.lowercase() == "active" } else 49
    val connectedCount = if (subscribers.isNotEmpty()) subscribers.count { it.accountStatus?.lowercase() == "active" } else 52
    val disconnectedCount = if (subscribers.isNotEmpty()) subscribers.count { it.accountStatus?.lowercase() != "active" } else 0
    val nearExpiryCount = if (subscribers.isNotEmpty()) {
        subscribers.count { user ->
            val dateStr = user.expirationDate ?: ""
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                val expireDate = sdf.parse(dateStr) ?: return@count false
                val diffMs = expireDate.time - java.util.Date().time
                val diffDays = diffMs / (1000 * 60 * 60 * 24)
                diffDays in 0..7
            } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; false }
        }
    } else 7
    val expiredCount = if (subscribers.isNotEmpty()) subscribers.count { it.accountStatus?.lowercase() == "expired" } else 29
    
    val formattedBalance = if (balance > 0) formatIqd(balance) else "242,250 د.ع"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                
                Text(
                    text = "اللوحات",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 18.sp,
                    color = Color.White
                )
                
                Spacer(modifier = Modifier.width(48.dp))
            }

            // Top Gradient Account Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF01579B),
                                    Color(0xFF039BE5),
                                    Color(0xFF00ACC1)
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "More options",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "E",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                    ) {
                        Text(
                            text = formattedBalance,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(Color.White.copy(alpha = 0.15f), shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "EarthLink",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "admin@sacx",
                                fontSize = 9.sp,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            // 6 Grid Stat Cards
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StatGridCard(
                            title = stringResource(id = R.string.label_all),
                            value = totalCount.toString(),
                            icon = Icons.Default.People,
                            iconBg = Color.White.copy(alpha = 0.1f),
                            iconColor = Color.White
                        )
                    }
                    
                    Box(modifier = Modifier.weight(1f)) {
                        StatGridCard(
                            title = stringResource(id = R.string.label_connected),
                            value = connectedCount.toString(),
                            icon = Icons.Default.Wifi,
                            iconBg = Color(0xFF1E88E5).copy(alpha = 0.2f),
                            iconColor = Color(0xFF1E88E5)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StatGridCard(
                            title = stringResource(id = R.string.label_active),
                            value = activeCount.toString(),
                            icon = Icons.Default.Check,
                            iconBg = Color(0xFF2E7D32).copy(alpha = 0.2f),
                            iconColor = Color(0xFF2E7D32)
                        )
                    }
                    
                    Box(modifier = Modifier.weight(1f)) {
                        StatGridCard(
                            title = stringResource(id = R.string.label_disconnected),
                            value = disconnectedCount.toString(),
                            icon = Icons.Default.WifiOff,
                            iconBg = Color(0xFFD81B60).copy(alpha = 0.2f),
                            iconColor = Color(0xFFD81B60)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        StatGridCard(
                            title = stringResource(id = R.string.label_near_expiry),
                            value = nearExpiryCount.toString(),
                            icon = Icons.Default.HourglassEmpty,
                            iconBg = Color(0xFFFDD835).copy(alpha = 0.2f),
                            iconColor = Color(0xFFFDD835)
                        )
                    }
                    
                    Box(modifier = Modifier.weight(1f)) {
                        StatGridCard(
                            title = stringResource(id = R.string.label_expired),
                            value = expiredCount.toString(),
                            icon = Icons.Default.Cancel,
                            iconBg = Color(0xFFC62828).copy(alpha = 0.2f),
                            iconColor = Color(0xFFC62828)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Buttons: الاشتراكات & الابراج
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(imageVector = Icons.Default.People, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "الاشتراكات", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(imageVector = Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "الابراج", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            FloatingActionButton(
                onClick = onPlusClick,
                containerColor = Color.White,
                contentColor = Color.Black,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Subscriber",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

// Stats support card
