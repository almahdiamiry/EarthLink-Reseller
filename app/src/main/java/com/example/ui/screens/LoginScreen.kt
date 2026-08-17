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
import androidx.compose.ui.res.painterResource
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
fun LoginScreen(viewModel: AuthViewModel) {
    val prefs = viewModel.prefs
    val currentLang by prefs.languageFlow.collectAsStateWithLifecycle()
    val username by viewModel.username.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val rememberMe by viewModel.rememberMe.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("1023954528299-it54t81l6ptjpt3cbgk7nu4qbn3pu265.apps.googleusercontent.com")
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_CANCELED) {
            Toast.makeText(
                context,
                if (currentLang == "ar") "تم إلغاء تسجيل الدخول بواسطة Google" else "Google Sign-In canceled",
                Toast.LENGTH_SHORT
            ).show()
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            val email = account?.email
            if (idToken != null) {
                viewModel.signInWithGoogle(idToken, email) {
                    Toast.makeText(
                        context,
                        if (currentLang == "ar") "أهلاً بك مجدداً!" else "Welcome back!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Toast.makeText(
                    context,
                    if (currentLang == "ar") "لم يتم العثور على معرف Google ID Token" else "Google ID Token not found.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            val statusCode = (e as? ApiException)?.statusCode ?: -1
            val isDeveloperError = statusCode == 10 || statusCode == 12500 || statusCode == 10200 || e.message?.contains("10:") == true
            val errMsg = if (isDeveloperError) {
                if (currentLang == "ar") "خطأ تهيئة Google (رمز 10/12500). تأكد من إضافة بصمة SHA-1 في Firebase Console." else "Google Config Error (Code 10/12500). Missing SHA-1 in Firebase Console."
            } else {
                e.message ?: if (currentLang == "ar") "فشل تسجيل الدخول بواسطة Google" else "Google Sign-In failed"
            }
            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
        }
    }

    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = if (currentLang == "ar") "شعار التطبيق" else "App Logo",
                modifier = Modifier
                    .size(88.dp)
                    .background(Color.Transparent)
            )
            Text(
                text = if (currentLang == "ar") "بوابة موزعي Earthlink" else "Earthlink Operator Port",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (currentLang == "ar") "مركز فواتير الموزع والسجل المحلي" else "Reseller Billing & Local Ledger Hub",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            var localUsername by remember(username) { mutableStateOf(username) }
            OutlinedTextField(
                value = localUsername,
                onValueChange = {
                    localUsername = it
                    viewModel.setUsername(it)
                },
                label = { Text(if (currentLang == "ar") "اسم المستخدم" else "Reseller Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            )

            var localPassword by remember(password) { mutableStateOf(password) }
            OutlinedTextField(
                value = localPassword,
                onValueChange = {
                    localPassword = it
                    viewModel.setPassword(it)
                },
                label = { Text(if (currentLang == "ar") "كلمة المرور" else "Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = null)
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { viewModel.login() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setRememberMe(!rememberMe) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = null // Handled by Row click
                )
                Text(
                    text = if (currentLang == "ar") "تذكر بيانات الدخول" else "Remember Credentials",
                    fontSize = 14.sp,
                    color = Color.White,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            if (com.alamiry.earthlinkreseller.BuildConfig.DEBUG) {
                val loginDemoMode by viewModel.prefs.demoModeFlow.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (loginDemoMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(
                            color = if (loginDemoMode) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (currentLang == "ar") "وضع المحاكاة دون اتصال" else "Offline Simulation Mode",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (loginDemoMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (currentLang == "ar") "يتجاوز حظر الشبكة" else "Bypasses rapi.earthlink.iq network blocks",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Switch(
                        checked = loginDemoMode,
                        onCheckedChange = { checked ->
                            viewModel.prefs.setDemoMode(checked)
                        }
                    )
                }
            }

            AnimatedVisibility(visible = error != null) {
                Surface(
                    color = Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error ?: "",
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
            }

            Button(
                onClick = { viewModel.login() },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text(if (currentLang == "ar") "تسجيل دخول آمن" else "Secure Sign In", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Text(
                    text = if (currentLang == "ar") "أو" else "OR",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            OutlinedButton(
                onClick = {
                    val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                    val resultCode = availability.isGooglePlayServicesAvailable(context)
                    if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                        if (availability.isUserResolvableError(resultCode)) {
                            (context as? android.app.Activity)?.let { act ->
                                availability.getErrorDialog(act, resultCode, 9000)?.show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                if (currentLang == "ar") "خدمات Google Play غير متوفرة على هذا الجهاز" else "Google Play Services unavailable on this device",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        return@OutlinedButton
                    }
                    try {
                        val intent = googleSignInClient.signInIntent
                        googleSignInLauncher.launch(intent)
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                        Toast.makeText(
                            context,
                            if (currentLang == "ar") "تعذر بدء تسجيل الدخول بواسطة Google: ${e.message}" else "Google Sign-In unavailable: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Google Logo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (currentLang == "ar") "تسجيل الدخول بواسطة Google" else "Sign in with Google",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// --- OPERATOR Indicator STATSCARD ---
