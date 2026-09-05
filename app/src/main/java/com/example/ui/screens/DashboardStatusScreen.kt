package com.example.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alamiry.earthlinkreseller.R
import com.example.ui.viewmodels.DashboardViewModel

@Composable
fun DashboardStatusScreen(
    viewModel: DashboardViewModel,
    onBack: () -> Unit,
    onCardClick: (DashboardStatusFilter) -> Unit
) {
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val subscribers by viewModel.subscribersList.collectAsStateWithLifecycle()
    val localAccounts by viewModel.localAccounts.collectAsStateWithLifecycle(emptyList())
    val localAccountMatcher = remember(localAccounts) { LocalAccountMatcher(localAccounts) }

    val effectiveSubscribers = remember(subscribers, localAccounts, localAccountMatcher) {
        DashboardStatusClassifier.getEffectiveSubscribers(subscribers, localAccounts, localAccountMatcher)
    }

    val activeCount = remember(effectiveSubscribers, localAccountMatcher) {
        DashboardStatusClassifier.countFiltered(effectiveSubscribers, localAccountMatcher, DashboardStatusFilter.ACTIVE)
    }
    val onlineCount = remember(effectiveSubscribers, localAccountMatcher) {
        DashboardStatusClassifier.countFiltered(effectiveSubscribers, localAccountMatcher, DashboardStatusFilter.ONLINE)
    }
    val offlineCount = remember(effectiveSubscribers, localAccountMatcher) {
        DashboardStatusClassifier.countFiltered(effectiveSubscribers, localAccountMatcher, DashboardStatusFilter.OFFLINE)
    }
    val expiringSoonCount = remember(effectiveSubscribers, localAccountMatcher) {
        DashboardStatusClassifier.countFiltered(effectiveSubscribers, localAccountMatcher, DashboardStatusFilter.EXPIRING_SOON)
    }
    val recentlyExpiredCount = remember(effectiveSubscribers, localAccountMatcher) {
        DashboardStatusClassifier.countFiltered(effectiveSubscribers, localAccountMatcher, DashboardStatusFilter.RECENTLY_EXPIRED)
    }
    val expiredCount = remember(effectiveSubscribers, localAccountMatcher) {
        DashboardStatusClassifier.countFiltered(effectiveSubscribers, localAccountMatcher, DashboardStatusFilter.EXPIRED)
    }

    val formattedBalance = formatIqd(balance)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D12))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .systemBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
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
                        text = "الحالة واللوحات",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Top Account Card (Apple Inset Card)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF141922),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "رصيد الصندوق المتاح",
                                    fontSize = 12.sp,
                                    color = Color(0xFF8E8E93),
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = formattedBalance,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0A84FF)
                                )
                            }

                            // App Logo Squircle
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFF0E131B), shape = RoundedCornerShape(12.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_logo),
                                    contentDescription = "EarthLink Logo",
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                // 6 Grid Stat Cards (2 Columns x 3 Rows)
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Row 1: Active Users & Online Users
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatGridCard(
                                title = DashboardStatusFilter.ACTIVE.titleAr,
                                value = activeCount.toString(),
                                icon = Icons.Default.CheckCircle,
                                iconBg = Color(0xFF30D158),
                                iconColor = Color(0xFF30D158),
                                onClick = { onCardClick(DashboardStatusFilter.ACTIVE) }
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            StatGridCard(
                                title = DashboardStatusFilter.ONLINE.titleAr,
                                value = onlineCount.toString(),
                                icon = Icons.Default.Wifi,
                                iconBg = Color(0xFF0A84FF),
                                iconColor = Color(0xFF0A84FF),
                                onClick = { onCardClick(DashboardStatusFilter.ONLINE) }
                            )
                        }
                    }

                    // Row 2: Offline Users & Users Expiring Soon
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatGridCard(
                                title = DashboardStatusFilter.OFFLINE.titleAr,
                                value = offlineCount.toString(),
                                icon = Icons.Default.WifiOff,
                                iconBg = Color(0xFFFF9F0A),
                                iconColor = Color(0xFFFF9F0A),
                                onClick = { onCardClick(DashboardStatusFilter.OFFLINE) }
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            StatGridCard(
                                title = DashboardStatusFilter.EXPIRING_SOON.titleAr,
                                value = expiringSoonCount.toString(),
                                icon = Icons.Default.HourglassBottom,
                                iconBg = Color(0xFFFFD60A),
                                iconColor = Color(0xFFFFD60A),
                                onClick = { onCardClick(DashboardStatusFilter.EXPIRING_SOON) }
                            )
                        }
                    }

                    // Row 3: Recently Expired Users & Expired Users
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatGridCard(
                                title = DashboardStatusFilter.RECENTLY_EXPIRED.titleAr,
                                value = recentlyExpiredCount.toString(),
                                icon = Icons.Default.History,
                                iconBg = Color(0xFFFF9F0A),
                                iconColor = Color(0xFFFF9F0A),
                                onClick = { onCardClick(DashboardStatusFilter.RECENTLY_EXPIRED) }
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            StatGridCard(
                                title = DashboardStatusFilter.EXPIRED.titleAr,
                                value = expiredCount.toString(),
                                icon = Icons.Default.Cancel,
                                iconBg = Color(0xFFFF453A),
                                iconColor = Color(0xFFFF453A),
                                onClick = { onCardClick(DashboardStatusFilter.EXPIRED) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}
