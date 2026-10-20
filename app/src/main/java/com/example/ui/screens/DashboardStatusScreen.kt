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

import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.core.model.SasProviders

@Composable
fun DashboardStatusScreen(
    viewModel: DashboardViewModel,
    onBack: () -> Unit,
    onCardClick: (DashboardStatusFilter, String) -> Unit
) {
    val currentLang by viewModel.prefs.languageFlow.collectAsStateWithLifecycle(initialValue = viewModel.prefs.getLanguage())
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val subscribers by viewModel.subscribersList.collectAsStateWithLifecycle()
    val localAccounts by viewModel.localAccounts.collectAsStateWithLifecycle(emptyList())
    val localAccountMatcher = remember(localAccounts) { LocalAccountMatcher(localAccounts) }
    val nowMs = remember { System.currentTimeMillis() }

    val access = remember { viewModel.prefs.getProviderAccessState() }
    val isDemo = viewModel.prefs.getDemoMode()
    val hasEarthlink = access.hasEarthlink || isDemo
    val hasSamm = access.hasSamm
    val bothProviders = hasEarthlink && hasSamm

    var selectedProvider by rememberSaveable {
        mutableStateOf(
            if (hasEarthlink && !hasSamm) SasProviders.EARTHLINK
            else if (hasSamm && !hasEarthlink) SasProviders.ALAMIRY
            else viewModel.prefs.getLastSelectedLoginProvider()
        )
    }

    // Authoritative population: current ISP subscribers only (excluding any history-only accounts)
    val ispSubscribers = remember(subscribers, localAccountMatcher) {
        subscribers.filter { sub ->
            val match = localAccountMatcher.findMatchingByUsername(sub.userID) ?: localAccountMatcher.findMatching(sub)
            match?.isHistoryOnlySubscriber != true
        }
    }

    val currentProviderSubscribers = remember(ispSubscribers, selectedProvider) {
        ispSubscribers.filter { it.originProvider == selectedProvider }
    }

    val activeCount = remember(currentProviderSubscribers, localAccountMatcher, nowMs) {
        DashboardStatusClassifier.countFiltered(currentProviderSubscribers, localAccountMatcher, DashboardStatusFilter.ACTIVE, nowMs)
    }
    val onlineCount = remember(currentProviderSubscribers, localAccountMatcher, nowMs) {
        DashboardStatusClassifier.countFiltered(currentProviderSubscribers, localAccountMatcher, DashboardStatusFilter.ONLINE, nowMs)
    }
    val offlineCount = remember(currentProviderSubscribers, localAccountMatcher, nowMs) {
        DashboardStatusClassifier.countFiltered(currentProviderSubscribers, localAccountMatcher, DashboardStatusFilter.OFFLINE, nowMs)
    }
    val expiringSoonCount = remember(currentProviderSubscribers, localAccountMatcher, nowMs) {
        DashboardStatusClassifier.countFiltered(currentProviderSubscribers, localAccountMatcher, DashboardStatusFilter.EXPIRING_SOON, nowMs)
    }
    val recentlyExpiredCount = remember(currentProviderSubscribers, localAccountMatcher, nowMs) {
        DashboardStatusClassifier.countFiltered(currentProviderSubscribers, localAccountMatcher, DashboardStatusFilter.RECENTLY_EXPIRED, nowMs)
    }
    val expiredCount = remember(currentProviderSubscribers, localAccountMatcher, nowMs) {
        DashboardStatusClassifier.countFiltered(currentProviderSubscribers, localAccountMatcher, DashboardStatusFilter.EXPIRED, nowMs)
    }

    val isAr = currentLang == "ar"
    val formattedBalance = formatIqd(balance)

    CompositionLocalProvider(
        LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr)
    ) {
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
                            contentDescription = if (isAr) "رجوع" else "Back",
                            tint = Color.White
                        )
                    }

                    Text(
                        text = if (isAr) "الحالة واللوحات" else "Status & Dashboards",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Provider Switcher Tab (Only rendered when both providers are present)
                if (bothProviders) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF141922), RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(
                            Pair(SasProviders.EARTHLINK, "EarthLink"),
                            Pair(SasProviders.ALAMIRY, "SAMM")
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
                                    .clickable { selectedProvider = providerKey }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color.White else Color(0xFF8E8E93),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                // Top Account Card (Apple Inset Card) - EarthLink only (SAMM does not have fund balance)
                if (selectedProvider == SasProviders.EARTHLINK) {
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
                                        text = if (isAr) "رصيد الصندوق المتاح" else "Available Fund Balance",
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
                                        contentDescription = if (isAr) "شعار التطبيق" else "App logo",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Stat Grid Cards
                if (selectedProvider == SasProviders.EARTHLINK) {
                    // EarthLink: 4 Cards (Active, Expiring Soon, Recently Expired, Expired)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.ACTIVE.titleAr else DashboardStatusFilter.ACTIVE.titleEn,
                                    value = activeCount.toString(),
                                    icon = Icons.Default.CheckCircle,
                                    iconBg = Color(0xFF30D158),
                                    iconColor = Color(0xFF30D158),
                                    onClick = { onCardClick(DashboardStatusFilter.ACTIVE, selectedProvider) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.EXPIRING_SOON.titleAr else DashboardStatusFilter.EXPIRING_SOON.titleEn,
                                    value = expiringSoonCount.toString(),
                                    icon = Icons.Default.HourglassBottom,
                                    iconBg = Color(0xFFFFD60A),
                                    iconColor = Color(0xFFFFD60A),
                                    onClick = { onCardClick(DashboardStatusFilter.EXPIRING_SOON, selectedProvider) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.RECENTLY_EXPIRED.titleAr else DashboardStatusFilter.RECENTLY_EXPIRED.titleEn,
                                    value = recentlyExpiredCount.toString(),
                                    icon = Icons.Default.History,
                                    iconBg = Color(0xFFFF9F0A),
                                    iconColor = Color(0xFFFF9F0A),
                                    onClick = { onCardClick(DashboardStatusFilter.RECENTLY_EXPIRED, selectedProvider) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.EXPIRED.titleAr else DashboardStatusFilter.EXPIRED.titleEn,
                                    value = expiredCount.toString(),
                                    icon = Icons.Default.Cancel,
                                    iconBg = Color(0xFFFF453A),
                                    iconColor = Color(0xFFFF453A),
                                    onClick = { onCardClick(DashboardStatusFilter.EXPIRED, selectedProvider) }
                                )
                            }
                        }
                    }
                } else {
                    // SAMM: 6 Cards (Active, Online, Offline, Expiring Soon, Recently Expired, Expired)
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
                                    title = if (isAr) DashboardStatusFilter.ACTIVE.titleAr else DashboardStatusFilter.ACTIVE.titleEn,
                                    value = activeCount.toString(),
                                    icon = Icons.Default.CheckCircle,
                                    iconBg = Color(0xFF30D158),
                                    iconColor = Color(0xFF30D158),
                                    onClick = { onCardClick(DashboardStatusFilter.ACTIVE, selectedProvider) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.ONLINE.titleAr else DashboardStatusFilter.ONLINE.titleEn,
                                    value = onlineCount.toString(),
                                    icon = Icons.Default.Wifi,
                                    iconBg = Color(0xFF7C4DFF),
                                    iconColor = Color(0xFF7C4DFF),
                                    onClick = { onCardClick(DashboardStatusFilter.ONLINE, selectedProvider) }
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
                                    title = if (isAr) DashboardStatusFilter.OFFLINE.titleAr else DashboardStatusFilter.OFFLINE.titleEn,
                                    value = offlineCount.toString(),
                                    icon = Icons.Default.WifiOff,
                                    iconBg = Color(0xFFFF9F0A),
                                    iconColor = Color(0xFFFF9F0A),
                                    onClick = { onCardClick(DashboardStatusFilter.OFFLINE, selectedProvider) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.EXPIRING_SOON.titleAr else DashboardStatusFilter.EXPIRING_SOON.titleEn,
                                    value = expiringSoonCount.toString(),
                                    icon = Icons.Default.HourglassBottom,
                                    iconBg = Color(0xFFFFD60A),
                                    iconColor = Color(0xFFFFD60A),
                                    onClick = { onCardClick(DashboardStatusFilter.EXPIRING_SOON, selectedProvider) }
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
                                    title = if (isAr) DashboardStatusFilter.RECENTLY_EXPIRED.titleAr else DashboardStatusFilter.RECENTLY_EXPIRED.titleEn,
                                    value = recentlyExpiredCount.toString(),
                                    icon = Icons.Default.History,
                                    iconBg = Color(0xFFFF9F0A),
                                    iconColor = Color(0xFFFF9F0A),
                                    onClick = { onCardClick(DashboardStatusFilter.RECENTLY_EXPIRED, selectedProvider) }
                                )
                            }

                            Box(modifier = Modifier.weight(1f)) {
                                StatGridCard(
                                    title = if (isAr) DashboardStatusFilter.EXPIRED.titleAr else DashboardStatusFilter.EXPIRED.titleEn,
                                    value = expiredCount.toString(),
                                    icon = Icons.Default.Cancel,
                                    iconBg = Color(0xFFFF453A),
                                    iconColor = Color(0xFFFF453A),
                                    onClick = { onCardClick(DashboardStatusFilter.EXPIRED, selectedProvider) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}
