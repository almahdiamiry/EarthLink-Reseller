package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.core.util.AppBuildConfig
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.screens.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodels.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainLayout()
            }
        }
    }
}

@Composable
fun MainLayout() {
    val authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val isLoggedIn by authViewModel.isLoggedIn.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as com.example.EarthlinkApp).preferenceManager }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()

    CompositionLocalProvider(LocalLayoutDirection provides if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
        if (!isLoggedIn) {
            LoginScreen(viewModel = authViewModel)
        } else {
            OperatorMainScreen(authViewModel)
        }
    }
}


@Composable
fun BottomNavPadded(content: @Composable () -> Unit) {
    content()
}

@Composable
fun OperatorMainScreen(authViewModel: AuthViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val dashboardViewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val searchViewModel: EarthlinkSearchViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val localAccountsViewModel: LocalAccountsViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val statementViewModel: StatementViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val syncViewModel: SyncStatusViewModel = viewModel(factory = AppViewModelProvider.Factory)

    val context = LocalContext.current
    val prefs = remember(context) { (context.applicationContext as EarthlinkApp).preferenceManager }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()
    val isAr = currentLang == "ar"

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var showCreateSheet by rememberSaveable { mutableStateOf(false) }

    if (showCreateSheet) {
        CreateChooserBottomSheet(
            onDismissRequest = { 
                focusManager.clearFocus()
                keyboardController?.hide()
                showCreateSheet = false 
            },
            onNavigateToTest = {
                focusManager.clearFocus()
                keyboardController?.hide()
                showCreateSheet = false
                navController.navigate("create_test_user")
            },
            onNavigateToPaid = {
                focusManager.clearFocus()
                keyboardController?.hide()
                showCreateSheet = false
                navController.navigate("create_using_deposit")
            },
            isAr = isAr
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF10161D),
                contentColor = Color.White,
                tonalElevation = 0.dp,
                modifier = Modifier.border(
                    BorderStroke(0.8.dp, Color.White.copy(alpha = 0.08f))
                )
            ) {
                val itemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF38BDF8),
                    selectedTextColor = Color(0xFF38BDF8),
                    unselectedIconColor = Color.White.copy(alpha = 0.45f),
                    unselectedTextColor = Color.White.copy(alpha = 0.45f),
                    indicatorColor = Color(0xFF0288D1).copy(alpha = 0.22f)
                )

                NavigationBarItem(
                    selected = (currentRoute == "dashboard" || currentRoute == "dashboard_status" || currentRoute?.startsWith("status_subscribers") == true || currentRoute == "search") && !showCreateSheet,
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        showCreateSheet = false
                        if (currentRoute != "dashboard") {
                            navController.navigate("dashboard") {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(imageVector = Icons.Default.People, contentDescription = "Subscribers") },
                    label = { Text(if (isAr) "المشتركين" else "Subscribers", fontSize = 11.sp, fontWeight = if ((currentRoute == "dashboard" || currentRoute == "dashboard_status" || currentRoute?.startsWith("status_subscribers") == true || currentRoute == "search") && !showCreateSheet) FontWeight.Bold else FontWeight.Normal) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = showCreateSheet || currentRoute == "create_chooser" || currentRoute == "create_test_user" || currentRoute == "create_using_deposit",
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        showCreateSheet = true
                    },
                    icon = { Icon(imageVector = Icons.Default.AddCircle, contentDescription = "Create") },
                    label = { Text(if (isAr) "إنشاء" else "Create", fontSize = 11.sp, fontWeight = if (showCreateSheet || currentRoute == "create_chooser" || currentRoute == "create_test_user" || currentRoute == "create_using_deposit") FontWeight.Bold else FontWeight.Normal) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = currentRoute == "settings" && !showCreateSheet,
                    onClick = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                        showCreateSheet = false
                        if (currentRoute != "settings") {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text(if (isAr) "الإعدادات" else "Settings", fontSize = 11.sp, fontWeight = if (currentRoute == "settings" && !showCreateSheet) FontWeight.Bold else FontWeight.Normal) },
                    colors = itemColors
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            NavHost(
                navController = navController,
                startDestination = "dashboard"
            ) {
                composable("dashboard") {
                    BottomNavPadded {
                        DashboardScreen(
                            viewModel = dashboardViewModel,
                            lang = currentLang,
                            onNavigateToSearch = { navController.navigate("search") },
                            onNavigateToAccounts = { navController.navigate("local_accounts") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onUserClick = { userItem ->
                                searchViewModel.clearErrorAndSuccess()
                                searchViewModel.prepareUserDetail(userItem.userIndex, userItem)
                                navController.navigate("user_detail/${userItem.userIndex}?userId=${userItem.userIDLower}")
                            },
                            onPlusClick = {
                                showCreateSheet = true
                            },
                            onEClick = {
                                navController.navigate("dashboard_status")
                            }
                        )
                    }
                }
                composable("dashboard_status") {
                    BottomNavPadded {
                        DashboardStatusScreen(
                            viewModel = dashboardViewModel,
                            onBack = { navController.popBackStack() },
                            onCardClick = { filter ->
                                navController.navigate("status_subscribers/${filter.key}")
                            }
                        )
                    }
                }
                composable(
                    route = "status_subscribers/{filterKey}",
                    arguments = listOf(navArgument("filterKey") { type = NavType.StringType })
                ) { backStackEntry ->
                    val filterKey = backStackEntry.arguments?.getString("filterKey") ?: "active"
                    BottomNavPadded {
                        StatusSubscribersScreen(
                            viewModel = dashboardViewModel,
                            filterKey = filterKey,
                            lang = currentLang,
                            onBack = { navController.popBackStack() },
                            onUserClick = { userItem ->
                                searchViewModel.clearErrorAndSuccess()
                                searchViewModel.prepareUserDetail(userItem.userIndex, userItem)
                                navController.navigate("user_detail/${userItem.userIndex}?userId=${userItem.userIDLower}")
                            }
                        )
                    }
                }
                composable("search") {
                    BottomNavPadded {
                        SearchScreen(
                            viewModel = searchViewModel,
                            lang = currentLang,
                            onUserSelect = { user ->
                                searchViewModel.clearErrorAndSuccess()
                                searchViewModel.prepareUserDetail(user.userIndex, user)
                                navController.navigate("user_detail/${user.userIndex}?userId=${user.userIDLower}")
                            }
                        )
                    }
                }
                composable(
                    route = "user_detail/{userIndex}?userId={userId}",
                    arguments = listOf(
                        navArgument("userIndex") { type = NavType.IntType },
                        navArgument("userId") { type = NavType.StringType; nullable = true }
                    ),
                    enterTransition = {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(300)
                        )
                    },
                    exitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(300)
                        )
                    },
                    popExitTransition = {
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(300)
                        )
                    }
                ) { backStackEntry ->
                    val userIndex = backStackEntry.arguments?.getInt("userIndex") ?: 0
                    val userId = backStackEntry.arguments?.getString("userId")
                    UserDetailScreenV2(
                        userIndex = userIndex,
                        userId = userId,
                        viewModel = searchViewModel,
                        lang = currentLang,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("local_accounts") {
                    BottomNavPadded {
                        LocalAccountsScreen(
                            viewModel = localAccountsViewModel,
                            onAccountClick = { acc ->
                                navController.navigate("local_account_detail/${acc.id}")
                            },
                            onNavigateToImport = { navController.navigate("import_utower") }
                        )
                    }
                }
                composable(
                    route = "local_account_detail/{accountId}",
                    arguments = listOf(navArgument("accountId") { type = NavType.StringType })
                ) { backStackEntry ->
                    val accountId = backStackEntry.arguments?.getString("accountId") ?: ""
                    LocalAccountDetailScreen(
                        accountId = accountId,
                        viewModel = localAccountsViewModel,
                        onBack = { navController.popBackStack() },
                        onMapOpen = { lat, lon ->
                            try {
                                val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon"))
                                context.startActivity(mapIntent)
                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                                Toast.makeText(context, "Google Maps client not found", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
                composable("import_utower") {
                    ImportUtowerScreen(
                        viewModel = localAccountsViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("create_chooser") {
                    BottomNavPadded {
                        CreateChooserScreen(
                            onNavigateToTest = { navController.navigate("create_test_user") },
                            onNavigateToPaid = { navController.navigate("create_using_deposit") },
                            onDismiss = { navController.popBackStack() }
                        )
                    }
                }
                composable("create_test_user") {
                    BottomNavPadded {
                        CreateTestUserScreen(
                            viewModel = searchViewModel
                        )
                    }
                }
                composable("create_using_deposit") {
                    BottomNavPadded {
                        CreateUsingDepositScreen(
                            viewModel = searchViewModel
                        )
                    }
                }
                composable("statement") {
                    BottomNavPadded {
                        StatementScreen(
                            viewModel = statementViewModel
                        )
                    }
                }
                composable("sync_status") {
                    BottomNavPadded {
                        SyncStatusScreen(
                            viewModel = syncViewModel
                        )
                    }
                }
                composable("settings") {
                    BottomNavPadded {
                        SettingsScreen(
                            authViewModel = authViewModel,
                            dashboardViewModel = dashboardViewModel,
                            syncViewModel = syncViewModel,
                            onLogout = {
                                navController.navigate("dashboard") {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            onNavigateToImport = { navController.navigate("import_utower") },
                            onNavigateToSubscribers = {
                                navController.navigate("dashboard") {
                                    popUpTo("dashboard") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChooserBottomSheet(
    onDismissRequest: () -> Unit,
    onNavigateToTest: () -> Unit,
    onNavigateToPaid: () -> Unit,
    isAr: Boolean
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    val dismissWithAction: (() -> Unit) -> Unit = { action ->
        focusManager.clearFocus()
        keyboardController?.hide()
        coroutineScope.launch {
            sheetState.hide()
        }.invokeOnCompletion {
            if (!sheetState.isVisible) {
                onDismissRequest()
                action()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            focusManager.clearFocus()
            keyboardController?.hide()
            onDismissRequest()
        },
        sheetState = sheetState,
        containerColor = Color(0xFF141922),
        contentColor = Color.White,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = Color.White.copy(alpha = 0.2f),
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
            )
        },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header: Title
            Text(
                text = if (isAr) "إنشاء مشترك جديد" else "Create Subscriber",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            // Apple Inset Grouped Container
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0E131B),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    // Option 1: Trial Account
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dismissWithAction(onNavigateToTest) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF0A84FF), shape = RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (isAr) "حساب تجريبي (24 ساعة)" else "Trial User (24 Hours)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (isAr) "فحص وتجربة الخط بدون خصم من الرصيد" else "Line testing without balance deduction",
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }

                        Icon(
                            imageVector = if (isAr) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.05f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )

                    // Option 2: Paid Subscriber
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dismissWithAction(onNavigateToPaid) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF30D158), shape = RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = if (isAr) "مشترك دائم بالرصيد" else "Paid Subscriber",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (isAr) "تفعيل رسمي مع خصم سعر الباقة من الصندوق" else "Official activation deducting fee from deposit",
                                fontSize = 11.sp,
                                color = Color(0xFF8E8E93)
                            )
                        }

                        Icon(
                            imageVector = if (isAr) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CreateChooserScreen(
    onNavigateToTest: () -> Unit,
    onNavigateToPaid: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { com.example.core.security.PreferenceManager(context) }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = prefs.getLanguage())
    val isAr = currentLang == "ar"

    CreateChooserBottomSheet(
        onDismissRequest = onDismiss,
        onNavigateToTest = onNavigateToTest,
        onNavigateToPaid = onNavigateToPaid,
        isAr = isAr
    )
}
