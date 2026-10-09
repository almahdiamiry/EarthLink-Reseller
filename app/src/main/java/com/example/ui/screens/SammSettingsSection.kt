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
    var apiTokenText by rememberSaveable { mutableStateOf(prefs.getSammToken() ?: "") }
    var isTokenVisible by rememberSaveable { mutableStateOf(false) }

    var isConfigured by remember { mutableStateOf(prefs.isSammConfigured()) }
    var connectionStatus by remember { mutableStateOf(SammConnectionStatus.IDLE) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showEditForm by rememberSaveable { mutableStateOf(!isConfigured) }

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
                    text = if (isAr) "إعدادات الربط بالخادم عبر رمز الوصول (API Token)" else "Server connection via API Token",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        // Configured Status Box
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
                            text = if (isAr) "بوابة العامري مهيأة ومتصلة" else "Alamiry Gateway Configured",
                            color = Color(0xFF30D158),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Text(
                        text = "${if (isAr) "الخادم" else "Server"}: ${prefs.getSammBaseUrl() ?: baseUrlText}",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "${if (isAr) "رمز الوصول" else "API Token"}: ${prefs.getMaskedSammToken().ifEmpty { "••••••••" }}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }

            // Buttons when configured: Test Connection, Edit, Clear
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
                                val token = prefs.getSammToken() ?: apiTokenText
                                val result = onTestConnectionOverride?.invoke(url, token)
                                    ?: SammConnectionEvaluator.testConnection(url, token)
                                connectionStatus = result
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && !isSaving,
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

                // Edit Settings toggle
                OutlinedButton(
                    onClick = { showEditForm = !showEditForm },
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (showEditForm) {
                            if (isAr) "إلغاء التعديل" else "Cancel Edit"
                        } else {
                            if (isAr) "تعديل" else "Edit"
                        },
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }

                // Clear Credentials Button
                OutlinedButton(
                    onClick = {
                        prefs.clearSammCredentials()
                        baseUrlText = ""
                        apiTokenText = ""
                        isConfigured = false
                        showEditForm = true
                        connectionStatus = SammConnectionStatus.IDLE
                        actionFeedback = if (isAr) "تم مسح بيانات البوابة بنجاح" else "Gateway credentials cleared"
                    },
                    border = BorderStroke(1.dp, Color(0xFFFF453A).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (isAr) "مسح" else "Clear",
                        fontSize = 12.sp,
                        color = Color(0xFFFF453A)
                    )
                }
            }
        }

        // Edit / Configuration Form
        if (showEditForm || !isConfigured) {
            Text(
                text = if (isConfigured) {
                    if (isAr) "تعديل إعدادات الربط" else "Edit Gateway Connection"
                } else {
                    if (isAr) "إعداد الاتصال بالبوابة (API Token)" else "Gateway Connection Setup"
                },
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
                placeholder = { Text("https://isp.alamiry.com", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
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

            // API Token input (masked with visibility toggle)
            OutlinedTextField(
                value = apiTokenText,
                onValueChange = {
                    apiTokenText = it
                    actionFeedback = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isAr) "رمز الوصول (API Token)" else "API Token", fontSize = 12.sp) },
                placeholder = { Text("samm_...", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f)) },
                visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                        Icon(
                            imageVector = if (isTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (isTokenVisible) {
                                if (isAr) "إخفاء رمز الوصول" else "Hide token"
                            } else {
                                if (isAr) "إظهار رمز الوصول" else "Show token"
                            },
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

            // Helper text
            Text(
                text = if (isAr) {
                    "يمكنك إنشاء رمز الدخول من لوحة تحكم SAMM عبر: System -> API (تأكد من تفعيل صلاحيات customers و plans)."
                } else {
                    "Generate the API token in SAMM Web Admin: System -> API (ensure customers and plans scopes are enabled)."
                },
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
                lineHeight = 15.sp
            )

            // Form Action Buttons (Test & Save & Connect)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Test Connection button
                OutlinedButton(
                    onClick = {
                        val rawUrl = baseUrlText.trim()
                        val token = apiTokenText.trim()
                        if (rawUrl.isEmpty() || token.isEmpty()) {
                            actionFeedback = if (isAr) "يرجى إدخال الرابط ورمز الدخول أولاً" else "Please enter Server URL and API Token"
                            return@OutlinedButton
                        }
                        isTesting = true
                        connectionStatus = SammConnectionStatus.TESTING
                        actionFeedback = null
                        scope.launch {
                            try {
                                val normalizedUrl = SammUrlNormalizer.normalize(rawUrl)
                                baseUrlText = normalizedUrl
                                val result = onTestConnectionOverride?.invoke(normalizedUrl, token)
                                    ?: SammConnectionEvaluator.testConnection(normalizedUrl, token)
                                connectionStatus = result
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && !isSaving,
                    border = BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF0A84FF))
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (isAr) "اختبار" else "Test",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0A84FF)
                    )
                }

                // Save & Connect button
                Button(
                    onClick = {
                        val rawUrl = baseUrlText.trim()
                        val token = apiTokenText.trim()
                        if (rawUrl.isEmpty() || token.isEmpty()) {
                            actionFeedback = if (isAr) "يرجى إدخال الرابط ورمز الدخول" else "Please enter Server URL and API Token"
                            return@Button
                        }

                        isSaving = true
                        actionFeedback = null
                        val normalizedUrl = SammUrlNormalizer.normalize(rawUrl)
                        baseUrlText = normalizedUrl

                        scope.launch {
                            try {
                                val testResult = onTestConnectionOverride?.invoke(normalizedUrl, token)
                                    ?: SammConnectionEvaluator.testConnection(normalizedUrl, token)
                                connectionStatus = testResult
                                if (testResult == SammConnectionStatus.CONNECTED) {
                                    prefs.saveSammBaseUrl(normalizedUrl)
                                    prefs.saveSammToken(token)
                                    prefs.setLastSelectedLoginProvider(com.example.core.model.SasProviders.ALAMIRY)
                                    isConfigured = true
                                    showEditForm = false
                                    actionFeedback = if (isAr) "تم الاتصال وحفظ الإعدادات بنجاح" else "Connected and saved successfully"
                                } else {
                                    actionFeedback = if (isAr) "فشل الاتصال: ${testResult.getMessage(true)}" else "Connection failed: ${testResult.getMessage(false)}"
                                }
                            } catch (e: Throwable) {
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                actionFeedback = e.message ?: if (isAr) "فشل الاتصال بالخادم" else "Failed to connect to server"
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                    enabled = !isSaving && !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(2f).height(48.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isAr) "حفظ وتفعيل الاتصال" else "Save & Connect",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
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
