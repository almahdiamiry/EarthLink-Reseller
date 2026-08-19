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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        content()
    }
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            NavigationBar(modifier = Modifier.align(Alignment.BottomCenter)) {
                NavigationBarItem(
                    selected = currentRoute == "dashboard" || currentRoute == "dashboard_status" || currentRoute == "search",
                    onClick = {
                        if (currentRoute != "dashboard") {
                            navController.navigate("dashboard") {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(imageVector = Icons.Default.People, contentDescription = "Subscribers") },
                    label = { Text(if (currentLang == "ar") "المشتركين" else "Subscribers", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = currentRoute == "create_chooser" || currentRoute == "create_test_user" || currentRoute == "create_using_deposit",
                    onClick = {
                        if (currentRoute != "create_chooser") {
                            navController.navigate("create_chooser") {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(imageVector = Icons.Default.AddCircle, contentDescription = "Create") },
                    label = { Text(if (currentLang == "ar") "إنشاء" else "Create", fontSize = 11.sp) }
                )
                NavigationBarItem(
                    selected = currentRoute == "settings",
                    onClick = {
                        if (currentRoute != "settings") {
                            navController.navigate("settings") {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = { Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text(if (currentLang == "ar") "الإعدادات" else "Settings", fontSize = 11.sp) }
                )
            }
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
                                navController.navigate("create_chooser")
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
                            onPlusClick = {
                                navController.navigate("create_chooser") {
                                    popUpTo("dashboard") { saveState = true }
                                    launchSingleTop = true
                                }
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
                            onNavigateToPaid = { navController.navigate("create_using_deposit") }
                        )
                    }
                }
                composable("create_test_user") {
                    if (!AppBuildConfig.DEBUG) {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            navController.popBackStack()
                        }
                    } else {
                        BottomNavPadded {
                            CreateTestUserScreen(
                                viewModel = searchViewModel
                            )
                        }
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

@Composable
fun CreateChooserScreen(
    onNavigateToTest: () -> Unit,
    onNavigateToPaid: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { com.example.core.security.PreferenceManager(context) }
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle(initialValue = prefs.getLanguage())
    val isAr = currentLang == "ar"

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isAr) "بوابة إنشاء الحسابات" else "Account Generation Portal",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Text(
            text = if (isAr) "اختر المسار المناسب لإنشاء المشترك" else "Select standard creation pathways",
            color = Color.Gray,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.padding(16.dp))

        if (AppBuildConfig.DEBUG) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { onNavigateToTest() }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isAr) "حساب تجريبي (24 ساعة)" else "Trial / Test User Node",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (isAr) "فعال لمدة 24 ساعة لغرض التجربة والربط. لا يخصم من رصيد صندوق الموزع." else "Valid for 24 hours. Does not impact your reseller deposit balance.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clickable { onNavigateToPaid() }
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (isAr) "مشترك رئيسي مدفوع (دائم)" else "Active Paid Subscriber Portal",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF2E7D32)
                )
                Text(
                    text = if (isAr) "تفعيل اشتراك مدفوع دائم. يخصم سعر الفئة من رصيد الصندوق." else "Standard permanent lines. Deducts subscription rates from your deposit ledger.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
