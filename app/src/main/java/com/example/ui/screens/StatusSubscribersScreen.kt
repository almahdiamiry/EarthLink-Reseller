package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.UserListItem
import com.example.ui.viewmodels.DashboardViewModel
import java.util.Locale

@Composable
fun StatusSubscribersScreen(
    viewModel: DashboardViewModel,
    filterKey: String,
    lang: String = "ar",
    onBack: () -> Unit,
    onUserClick: (UserListItem) -> Unit
) {
    val filter = remember(filterKey) { DashboardStatusFilter.fromKey(filterKey) }
    val subscribers by viewModel.subscribersList.collectAsStateWithLifecycle()
    val localAccounts by viewModel.localAccounts.collectAsStateWithLifecycle(emptyList())
    val localAccountMatcher = remember(localAccounts) { LocalAccountMatcher(localAccounts) }

    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val effectiveSubscribers = remember(subscribers, localAccounts, localAccountMatcher) {
        DashboardStatusClassifier.getEffectiveSubscribers(subscribers, localAccounts, localAccountMatcher)
    }

    val categorySubscribers = remember(effectiveSubscribers, localAccountMatcher, filter) {
        DashboardStatusClassifier.filterSubscribers(effectiveSubscribers, localAccountMatcher, filter)
    }

    val displayList = remember(categorySubscribers, isSearchActive, searchQuery) {
        if (isSearchActive && searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase(Locale.getDefault())
            categorySubscribers.filter { user ->
                user.userIndex.toString().contains(q) ||
                user.userID.lowercase(Locale.getDefault()).contains(q) ||
                user.customerName?.lowercase(Locale.getDefault())?.contains(q) == true ||
                user.displayName?.lowercase(Locale.getDefault())?.contains(q) == true ||
                user.mobileNumber?.lowercase(Locale.getDefault())?.contains(q) == true
            }
        } else {
            categorySubscribers
        }
    }

    val titleText = if (lang == "ar") filter.titleAr else filter.titleEn

    BackHandler(enabled = isSearchActive) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        isSearchActive = false
        searchQuery = ""
    }

    CompositionLocalProvider(LocalLayoutDirection provides (if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D12))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Header Row: [Back Button] <---> [Category Title + Count Badge]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
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
                        text = titleText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )

                    // Count Badge
                    Surface(
                        color = Color(0xFF141922),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = "${categorySubscribers.size}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color(0xFF0A84FF)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Subscriber List
                if (displayList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyStateView(
                            message = if (lang == "ar") "لا يوجد مشتركون في هذه القائمة." else "No subscribers in this category."
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayList, key = { it.userID }) { user ->
                            ArabicSubscriberCard(
                                user = user,
                                localAccounts = localAccounts,
                                localAccountMatcher = localAccountMatcher,
                                lang = lang,
                                onClick = { onUserClick(user) }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(90.dp))
                        }
                    }
                }
            }

            // Floating Capsule Search Pill / Active Search Bar
            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(180))) togetherWith
                    fadeOut(animationSpec = tween(140))
                },
                label = "SearchTransition",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) { active ->
                if (active) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            color = Color(0xFF141922),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.5.dp, Color(0xFF0A84FF))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = Color(0xFF0A84FF),
                                    modifier = Modifier.size(18.dp)
                                )

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = if (lang == "ar") "بحث بالاسم، اليوزر، أو الهاتف..." else "Search name, user, phone...",
                                            fontSize = 13.sp,
                                            color = Color(0xFF8E8E93),
                                            maxLines = 1
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Search,
                                            keyboardType = KeyboardType.Text
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onSearch = { keyboardController?.hide() }
                                        ),
                                        textStyle = TextStyle(
                                            fontSize = 13.5.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        cursorBrush = SolidColor(Color(0xFF0A84FF)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(focusRequester)
                                    )
                                }

                                if (searchQuery.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .clickable { searchQuery = "" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = "Clear",
                                            tint = Color(0xFF8E8E93),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            onClick = {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                isSearchActive = false
                                searchQuery = ""
                            },
                            shape = CircleShape,
                            color = Color(0xFF141922),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            onClick = { isSearchActive = true },
                            color = Color(0xFF141922),
                            shape = RoundedCornerShape(22.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier
                                .fillMaxWidth(0.38f)
                                .height(44.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = Color(0xFF0A84FF),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (lang == "ar") "بــحــث" else "Search",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
