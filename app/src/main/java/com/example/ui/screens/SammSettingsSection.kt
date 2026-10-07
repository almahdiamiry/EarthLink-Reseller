package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.network.samm.SammNetworkClient
import com.example.core.security.PreferenceManager
import kotlinx.coroutines.launch

/**
 * Result states for SAMM connection testing via GET /me.
 */
enum class SammConnectionStatus(
    val englishMessage: String,
    val arabicMessage: String
) {
    IDLE("", ""),
    TESTING("Testing connection...", "جاري اختبار الاتصال..."),
    CONNECTED("Connected successfully", "تم الاتصال بنجاح"),
    INVALID_TOKEN("Invalid or unauthorized API token", "رمز الدخول غير صالح أو غير مصرح به"),
    INSUFFICIENT_CONFIGURATION("Base URL and API token required", "يرجى إدخال الرابط ورمز الدخول"),
    TLS_NETWORK_FAILURE("Network or TLS connection failure", "فشل الاتصال بالشبكة أو شهادة الأمان"),
    SERVER_UNAVAILABLE("Server unavailable", "الخادم غير متاح حالياً"),
    UNEXPECTED_RESPONSE("Unexpected response", "استجابة غير متوقعة");

    fun getMessage(isAr: Boolean): String = if (isAr) arabicMessage else englishMessage
}

/**
 * Normalizes SAMM Base URL input:
 * 1. Trims whitespace.
 * 2. Adds https:// scheme if neither http:// nor https:// is present.
 * 3. Strips trailing /api/v1 or /api/v1/ segments.
 * 4. Strips trailing slashes.
 */
object SammUrlNormalizer {
    fun normalize(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        var cleaned = trimmed.trimEnd('/')
        if (cleaned.endsWith("/api/v1", ignoreCase = true)) {
            cleaned = cleaned.substring(0, cleaned.length - "/api/v1".length).trimEnd('/')
        }
        return cleaned
    }
}

/**
 * Connection test evaluator for SAMM endpoints.
 * Maps HTTP response codes and throwables to typed [SammConnectionStatus] values.
 * Never exposes plaintext tokens, passwords, or raw stack traces.
 */
object SammConnectionEvaluator {

    fun evaluateInputs(baseUrl: String?, token: String?): SammConnectionStatus? {
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) {
            return SammConnectionStatus.INSUFFICIENT_CONFIGURATION
        }
        return null
    }

    fun mapResponseCode(code: Int): SammConnectionStatus {
        return when (code) {
            in 200..299 -> SammConnectionStatus.CONNECTED
            401, 403 -> SammConnectionStatus.INVALID_TOKEN
            in 500..599 -> SammConnectionStatus.SERVER_UNAVAILABLE
            else -> SammConnectionStatus.UNEXPECTED_RESPONSE
        }
    }

    fun mapThrowable(throwable: Throwable): SammConnectionStatus {
        if (throwable is retrofit2.HttpException) {
            return mapResponseCode(throwable.code())
        }
        return when (throwable) {
            is javax.net.ssl.SSLException,
            is java.security.cert.CertificateException,
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            is java.io.IOException -> SammConnectionStatus.TLS_NETWORK_FAILURE
            else -> SammConnectionStatus.UNEXPECTED_RESPONSE
        }
    }

    suspend fun testConnection(
        baseUrl: String?,
        token: String?,
        apiCall: (suspend (normalizedUrl: String, token: String) -> retrofit2.Response<*>)? = null
    ): SammConnectionStatus {
        val inputCheck = evaluateInputs(baseUrl, token)
        if (inputCheck != null) {
            return inputCheck
        }
        val normalizedUrl = SammUrlNormalizer.normalize(baseUrl!!)
        return try {
            val response = if (apiCall != null) {
                apiCall(normalizedUrl, token!!.trim())
            } else {
                val api = SammNetworkClient.createApiService(normalizedUrl, token!!.trim())
                api.getMe()
            }
            mapResponseCode(response.code())
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            mapThrowable(e)
        }
    }
}

@Composable
fun SammSettingsSection(
    prefs: PreferenceManager,
    currentLang: String = "ar",
    onTestConnectionOverride: (suspend (String, String) -> SammConnectionStatus)? = null
) {
    val isAr = currentLang == "ar"
    val scope = rememberCoroutineScope()

    var baseUrlText by rememberSaveable { mutableStateOf(prefs.getSammBaseUrl() ?: "") }
    var agentUsernameText by rememberSaveable { mutableStateOf(prefs.getSammAgentUsername() ?: "") }
    var agentPasswordText by rememberSaveable { mutableStateOf("") }
    var isPasswordVisible by rememberSaveable { mutableStateOf(false) }

    var isConfigured by remember { mutableStateOf(prefs.isSammConfigured()) }
    var connectedAgentUsername by remember { mutableStateOf(prefs.getSammAgentUsername()) }
    var connectedAgentId by remember { mutableStateOf(prefs.getSammAgentId()) }

    var connectionStatus by remember { mutableStateOf(SammConnectionStatus.IDLE) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var showLoginForm by rememberSaveable { mutableStateOf(!isConfigured) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Section Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF5856D6), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }
            Column {
                Text(
                    text = if (isAr) "بيانات بوابة العامري (SAMM)" else "Alamiry (SAMM) Gateway",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = if (isAr) "حساب الوكيل والاتصال بالخادم" else "Agent account and server connection",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        // Configured Agent Status Box
        if (isConfigured) {
            Surface(
                color = Color(0xFF171E29),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color(0xFF30D158), RoundedCornerShape(5.dp))
                        )
                        Text(
                            text = if (isAr) "متصل بحساب الوكيل" else "Connected as Agent",
                            color = Color(0xFF30D158),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        text = "${if (isAr) "اسم المستخدم" else "Username"}: ${connectedAgentUsername ?: "-"}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    if (connectedAgentId != null) {
                        Text(
                            text = "${if (isAr) "معرف الوكيل" else "Agent ID"}: $connectedAgentId",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                    Text(
                        text = "${if (isAr) "الخادم" else "Server"}: ${prefs.getSammBaseUrl() ?: "-"}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }

            // Buttons when configured: Test Connection, Switch Agent, Logout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Test Connection
                Button(
                    onClick = {
                        isTesting = true
                        connectionStatus = SammConnectionStatus.TESTING
                        actionFeedback = null
                        scope.launch {
                            try {
                                val url = prefs.getSammBaseUrl() ?: baseUrlText
                                val token = prefs.getSammToken() ?: ""
                                val result = onTestConnectionOverride?.invoke(url, token)
                                    ?: SammConnectionEvaluator.testConnection(url, token)
                                connectionStatus = result
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.2f)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isAr) "اختبار الاتصال" else "Test Connection",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Switch Agent toggle
                OutlinedButton(
                    onClick = { showLoginForm = !showLoginForm },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (showLoginForm) {
                            if (isAr) "إلغاء" else "Cancel"
                        } else {
                            if (isAr) "تبديل الوكيل" else "Switch Agent"
                        },
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }

                // Logout Button
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val token = prefs.getSammToken()
                            val url = prefs.getSammBaseUrl()
                            if (!token.isNullOrBlank() && !url.isNullOrBlank()) {
                                try {
                                    val api = SammNetworkClient.createApiService(url, token)
                                    api.agentLogout()
                                } catch (_: Throwable) {
                                    // Fail-safe
                                }
                            }
                            prefs.clearSammCredentials()
                            baseUrlText = ""
                            agentUsernameText = ""
                            agentPasswordText = ""
                            isConfigured = false
                            connectedAgentUsername = null
                            connectedAgentId = null
                            showLoginForm = true
                            connectionStatus = SammConnectionStatus.IDLE
                            actionFeedback = if (isAr) "تم تسجيل الخروج ومسح البيانات" else "Logged out and cleared"
                        }
                    },
                    border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (isAr) "خروج" else "Logout",
                        fontSize = 12.sp,
                        color = Color(0xFFFF453A)
                    )
                }
            }
        }

        // Login / Switch Form
        if (showLoginForm || !isConfigured) {
            Text(
                text = if (isAr) "تسجيل الدخول كوكيل (Agent Login)" else "Agent Login Credentials",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.9f)
            )

            // Server URL input
            OutlinedTextField(
                value = baseUrlText,
                onValueChange = {
                    baseUrlText = it
                    actionFeedback = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isAr) "رابط الخادم (Server URL)" else "Server URL", fontSize = 12.sp) },
                placeholder = { Text("https://samm.example.com", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF5856D6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    focusedContainerColor = Color(0xFF0E131B),
                    unfocusedContainerColor = Color(0xFF0E131B)
                ),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = ImeAction.Next),
                singleLine = true
            )

            // Agent Username input
            OutlinedTextField(
                value = agentUsernameText,
                onValueChange = {
                    agentUsernameText = it
                    actionFeedback = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isAr) "اسم مستخدم الوكيل" else "Agent Username", fontSize = 12.sp) },
                placeholder = { Text(if (isAr) "أدخل اسم المستخدم" else "Enter username", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF5856D6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    focusedContainerColor = Color(0xFF0E131B),
                    unfocusedContainerColor = Color(0xFF0E131B)
                ),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                singleLine = true
            )

            // Agent Password input (masked)
            OutlinedTextField(
                value = agentPasswordText,
                onValueChange = {
                    agentPasswordText = it
                    actionFeedback = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isAr) "كلمة مرور الوكيل" else "Agent Password", fontSize = 12.sp) },
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF5856D6),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                    focusedContainerColor = Color(0xFF0E131B),
                    unfocusedContainerColor = Color(0xFF0E131B)
                ),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, imeAction = ImeAction.Done),
                singleLine = true
            )

            // Login / Connect Button
            Button(
                onClick = {
                    val rawUrl = baseUrlText.trim()
                    val user = agentUsernameText.trim()
                    val pass = agentPasswordText

                    if (rawUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                        actionFeedback = if (isAr) "يرجى إدخال الرابط، اسم المستخدم، وكلمة المرور" else "Please enter Server URL, Username, and Password"
                        return@Button
                    }

                    isLoggingIn = true
                    actionFeedback = null
                    val normalizedUrl = SammUrlNormalizer.normalize(rawUrl)
                    baseUrlText = normalizedUrl

                    scope.launch {
                        try {
                            // Transient login call - zero password persistence to prefs/disk
                            val api = SammNetworkClient.createApiService(normalizedUrl, token = null)
                            val response = api.agentLogin(
                                com.example.core.network.samm.SammAgentLoginRequest(
                                    username = user,
                                    password = pass
                                )
                            )

                            if (response.isSuccessful && response.body() != null) {
                                val body = response.body()!!
                                prefs.saveSammBaseUrl(normalizedUrl)
                                prefs.saveSammToken(body.token)
                                prefs.saveSammAgentInfo(
                                    agentId = body.agent.id,
                                    username = body.agent.username,
                                    resellerId = body.agent.resellerId
                                )
                                prefs.setLastSelectedLoginProvider(com.example.core.model.SasProviders.ALAMIRY)

                                isConfigured = true
                                connectedAgentUsername = body.agent.username
                                connectedAgentId = body.agent.id
                                showLoginForm = false
                                agentPasswordText = "" // Clear password from memory
                                actionFeedback = if (isAr) "تم تسجيل الدخول بنجاح" else "Logged in successfully"
                            } else {
                                val errBody = response.errorBody()?.string()
                                val errorDetail = SammNetworkClient.parseErrorDetail(errBody)
                                actionFeedback = when (response.code()) {
                                    401 -> if (isAr) "اسم المستخدم أو كلمة المرور غير صحيحة" else "Invalid username or password"
                                    403 -> errorDetail ?: if (isAr) "الحساب غير مصرح به كوكيل" else "User is not an authorized agent"
                                    else -> errorDetail ?: if (isAr) "فشل تسجيل الدخول (${response.code()})" else "Login failed (${response.code()})"
                                }
                                agentPasswordText = ""
                            }
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            actionFeedback = e.message ?: if (isAr) "فشل الاتصال بالخادم" else "Failed to connect to server"
                            agentPasswordText = ""
                        } finally {
                            isLoggingIn = false
                        }
                    }
                },
                enabled = !isLoggingIn,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isAr) "تسجيل الدخول كوكيل" else "Login as Agent",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Action Feedback Banner
        if (actionFeedback != null) {
            Surface(
                color = Color(0xFF30D158).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFF30D158).copy(alpha = 0.3f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF30D158),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = actionFeedback!!,
                        color = Color(0xFF30D158),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Connection Test Result Banner
        if (connectionStatus != SammConnectionStatus.IDLE) {
            val (bannerColor, icon) = when (connectionStatus) {
                SammConnectionStatus.TESTING -> Pair(Color(0xFF0A84FF), Icons.Default.HourglassTop)
                SammConnectionStatus.CONNECTED -> Pair(Color(0xFF30D158), Icons.Default.CheckCircle)
                else -> Pair(Color(0xFFFF453A), Icons.Default.ErrorOutline)
            }

            Surface(
                color = bannerColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, bannerColor.copy(alpha = 0.35f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = bannerColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = connectionStatus.getMessage(isAr),
                        color = bannerColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
