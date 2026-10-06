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
    var tokenText by rememberSaveable { mutableStateOf(prefs.getSammToken() ?: "") }
    var isTokenVisible by rememberSaveable { mutableStateOf(false) }

    var connectionStatus by remember { mutableStateOf(SammConnectionStatus.IDLE) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

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
                    text = if (isAr) "تهيئة الرابط ورمز الدخول للعمليات البديلة" else "Configure host URL and token for SAMM gateway",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }

        // Base URL input
        OutlinedTextField(
            value = baseUrlText,
            onValueChange = {
                baseUrlText = it
                actionFeedback = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (isAr) "رابط الخادم (Base URL)" else "SAMM Base URL",
                    fontSize = 12.sp
                )
            },
            placeholder = {
                Text(
                    "https://samm.al-amiry.net",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.3f)
                )
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true
        )

        // API Token input (masked toggle)
        OutlinedTextField(
            value = tokenText,
            onValueChange = {
                tokenText = it
                actionFeedback = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (isAr) "رمز الدخول (API Token)" else "SAMM API Token",
                    fontSize = 12.sp
                )
            },
            visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                    Icon(
                        imageVector = if (isTokenVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (isTokenVisible) {
                            if (isAr) "إخفاء رمز الدخول" else "Hide token"
                        } else {
                            if (isAr) "إظهار رمز الدخول" else "Show token"
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
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            singleLine = true
        )

        // Action Buttons Row: Save, Clear, Test Connection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Save Button
            Button(
                onClick = {
                    val normalizedUrl = SammUrlNormalizer.normalize(baseUrlText)
                    prefs.saveSammBaseUrl(normalizedUrl)
                    prefs.saveSammToken(tokenText.trim())
                    baseUrlText = normalizedUrl
                    actionFeedback = if (isAr) "تم حفظ الإعدادات بنجاح" else "Settings saved successfully"
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5856D6)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isAr) "حفظ" else "Save",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Test Connection Button
            Button(
                onClick = {
                    isTesting = true
                    connectionStatus = SammConnectionStatus.TESTING
                    actionFeedback = null
                    scope.launch {
                        try {
                            val result = onTestConnectionOverride?.invoke(baseUrlText, tokenText)
                                ?: SammConnectionEvaluator.testConnection(baseUrlText, tokenText)
                            connectionStatus = result
                        } finally {
                            isTesting = false
                        }
                    }
                },
                enabled = !isTesting,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.3f)
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
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Clear Button
            OutlinedButton(
                onClick = {
                    prefs.clearSammCredentials()
                    baseUrlText = ""
                    tokenText = ""
                    connectionStatus = SammConnectionStatus.IDLE
                    actionFeedback = if (isAr) "تم مسح البيانات" else "Credentials cleared"
                },
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (isAr) "مسح" else "Clear",
                    fontSize = 13.sp,
                    color = Color(0xFFFF453A)
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
