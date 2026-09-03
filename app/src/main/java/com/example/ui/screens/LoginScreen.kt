package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alamiry.earthlinkreseller.R
import com.example.ui.viewmodels.AuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException

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
    val focusManager = LocalFocusManager.current

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
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val statusCode = (e as? ApiException)?.statusCode ?: -1
            val isDeveloperError = statusCode == 10 || statusCode == 12500 || statusCode == 10200 || e.message?.contains("10:") == true
            val errMsg = if (isDeveloperError) {
                if (currentLang == "ar") "خطأ تهيئة Google (رمز 10/12500). تأكد من إضافة بصمة SHA-1 في Firebase Console."
                else "Google Config Error (Code 10/12500). Missing SHA-1 in Firebase Console."
            } else {
                e.message ?: if (currentLang == "ar") "فشل تسجيل الدخول بواسطة Google" else "Google Sign-In failed"
            }
            Toast.makeText(context, errMsg, Toast.LENGTH_LONG).show()
        }
    }

    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val layoutDir = if (currentLang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0F14))
        ) {
            // Subtle ambient glows in background for visual depth
            Box(
                modifier = Modifier
                    .size(360.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = (-60).dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF0288D1).copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Bar: Language Switcher Capsule (Apple pill style)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF171E29),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                val newLang = if (currentLang == "ar") "en" else "ar"
                                prefs.setLanguage(newLang)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (currentLang == "ar") "English" else "العربية",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Main Floating Apple Card
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF11161F),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // App Logo (Pure Transparent Brand Logo)
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = if (currentLang == "ar") "شعار التطبيق" else "App Logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(84.dp)
                                .padding(vertical = 2.dp)
                        )

                        // App Title & Subtitle Header
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "EarthLink Reseller",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = if (currentLang == "ar") "تسجيل الدخول إلى حساب الوكيل" else "Sign in to operator account",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.55f),
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // 1. Reseller Username Input Field
                        var localUsername by remember(username) { mutableStateOf(username) }
                        OutlinedTextField(
                            value = localUsername,
                            onValueChange = {
                                localUsername = it
                                viewModel.setUsername(it)
                            },
                            label = { Text(if (currentLang == "ar") "اسم المستخدم" else "Username") },
                            placeholder = {
                                Text(
                                    if (currentLang == "ar") "أدخل اسم المستخدم" else "Enter username",
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8)
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF171E29),
                                unfocusedContainerColor = Color(0xFF171E29),
                                focusedBorderColor = Color(0xFF0288D1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedLabelColor = Color(0xFF38BDF8),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        )

                        // 2. Password Input Field
                        var localPassword by remember(password) { mutableStateOf(password) }
                        OutlinedTextField(
                            value = localPassword,
                            onValueChange = {
                                localPassword = it
                                viewModel.setPassword(it)
                            },
                            label = { Text(if (currentLang == "ar") "كلمة المرور" else "Password") },
                            placeholder = {
                                Text(
                                    if (currentLang == "ar") "••••••••" else "••••••••",
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF38BDF8)
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = image,
                                        contentDescription = if (passwordVisible) {
                                            if (currentLang == "ar") "إخفاء كلمة المرور" else "Hide password"
                                        } else {
                                            if (currentLang == "ar") "إظهار كلمة المرور" else "Show password"
                                        },
                                        tint = Color.White.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    viewModel.login()
                                }
                            ),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF171E29),
                                unfocusedContainerColor = Color(0xFF171E29),
                                focusedBorderColor = Color(0xFF0288D1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedLabelColor = Color(0xFF38BDF8),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                        )

                        // 3. Remember Credentials Checkbox Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { viewModel.setRememberMe(!rememberMe) }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rememberMe,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF0288D1),
                                    uncheckedColor = Color.White.copy(alpha = 0.3f),
                                    checkmarkColor = Color.White
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (currentLang == "ar") "تذكر بيانات الدخول" else "Remember login info",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // 4. Error Banner
                        AnimatedVisibility(
                            visible = error != null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Surface(
                                color = Color(0xFFEF4444).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = Color(0xFFFCA5A5),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = error ?: "",
                                        color = Color(0xFFFCA5A5),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // 5. Primary Login Button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.login()
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0288D1),
                                disabledContainerColor = Color(0xFF0288D1).copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (currentLang == "ar") "جاري تسجيل الدخول..." else "Signing in...",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (currentLang == "ar") "تسجيل الدخول" else "Sign In",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // 6. Divider "أو"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = Color.White.copy(alpha = 0.08f)
                            )
                            Text(
                                text = if (currentLang == "ar") "أو" else "OR",
                                modifier = Modifier.padding(horizontal = 14.dp),
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.4f),
                                fontWeight = FontWeight.Medium
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = Color.White.copy(alpha = 0.08f)
                            )
                        }

                        // 7. Google Sign-In Outlined Button
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFF171E29),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(enabled = !isLoading) {
                                    val availability = GoogleApiAvailability.getInstance()
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
                                        return@clickable
                                    }
                                    try {
                                        val intent = googleSignInClient.signInIntent
                                        googleSignInLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        Toast.makeText(
                                            context,
                                            if (currentLang == "ar") "تعذر بدء تسجيل الدخول بواسطة Google: ${e.message}" else "Google Sign-In unavailable: ${e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Google Logo",
                                    tint = Color(0xFF38BDF8),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (currentLang == "ar") "تسجيل الدخول بواسطة Google" else "Sign in with Google",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // Footer Text
                Text(
                    text = "EarthLink Reseller • V1.0",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.25f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
