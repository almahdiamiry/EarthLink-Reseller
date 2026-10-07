package com.example.core.network.samm

import android.util.Log
import com.example.core.network.*
import com.example.core.util.AppBuildConfig
import com.squareup.moshi.Moshi
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * SAMM Network Client & Factory
 *
 * Implements OkHttpClient/Retrofit construction, authorization interceptor,
 * URL normalizer, security redacting logger, and HTTP error mapping to SasGatewayException.
 */
class SammNetworkClient(
    val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    constructor(baseUrl: String, token: String?) : this(baseUrl, { token })

    val normalizedBaseUrl: String = normalizeBaseUrl(baseUrl)
    val moshi: Moshi = createMoshi()
    val okHttpClient: OkHttpClient = createOkHttpClient(tokenProvider)
    val retrofit: Retrofit = createRetrofit(normalizedBaseUrl, okHttpClient, moshi)
    val apiService: SammApiService = retrofit.create(SammApiService::class.java)

    companion object {

        private val JSON_SENSITIVE = "\"([^\"]*?(?:password|Password|NewPassword|secret|token|Authorization|bearer)[^\"]*?)\"\\s*:\\s*(?:\"[^\"]*\"|[^,\\}\\s]+)".toRegex(RegexOption.IGNORE_CASE)
        private val FORM_SENSITIVE = "((?:password|Password|NewPassword|secret|token|Authorization|bearer)=)[^&\\s]+".toRegex(RegexOption.IGNORE_CASE)
        private val HEADER_SENSITIVE = "((?:Authorization|bearer|token|password):\\s*).+".toRegex(RegexOption.IGNORE_CASE)

        /**
         * Normalizes a base URL by:
         * 1. Trimming leading and trailing whitespace.
         * 2. Ensuring valid http:// or https:// scheme (defaulting to https:// if missing).
         * 3. Removing trailing `/api/v1` or `/api/v1/` to prevent duplicate `/api/v1/api/v1` path segments.
         * 4. Ensuring a trailing slash `/` as required by Retrofit baseUrl.
         */
        fun normalizeBaseUrl(rawUrl: String): String {
            var trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) return ""
            if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed = "https://$trimmed"
            }
            var withoutTrailingSlash = trimmed.trimEnd('/')
            if (withoutTrailingSlash.endsWith("/api/v1", ignoreCase = true)) {
                withoutTrailingSlash = withoutTrailingSlash.substring(0, withoutTrailingSlash.length - "/api/v1".length)
            }
            return "${withoutTrailingSlash.trimEnd('/')}/"
        }

        fun createMoshi(): Moshi {
            return Moshi.Builder().build()
        }

        /**
         * Interceptor injecting Bearer authentication token and JSON accept header.
         */
        fun createAuthInterceptor(tokenProvider: () -> String?): Interceptor = Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("Accept", "application/json")

            val token = tokenProvider()
            if (!token.isNullOrBlank()) {
                val authHeader = if (token.startsWith("Bearer ", ignoreCase = true)) {
                    token
                } else {
                    "Bearer $token"
                }
                builder.header("Authorization", authHeader)
            }

            chain.proceed(builder.build())
        }

        /**
         * Logging interceptor that redacts Authorization tokens and sensitive fields (passwords, secrets).
         */
        fun createLoggingInterceptor(isDebug: Boolean = AppBuildConfig.DEBUG): Interceptor {
            val redactingLogger = object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    if (!isDebug) return
                    var sanitized = JSON_SENSITIVE.replace(message) { match ->
                        val key = match.value.substringBefore(":")
                        "$key:\"[REDACTED]\""
                    }
                    sanitized = FORM_SENSITIVE.replace(sanitized, "$1[REDACTED]")
                    sanitized = HEADER_SENSITIVE.replace(sanitized, "$1[REDACTED]")
                    try {
                        Log.d("SammApi", sanitized)
                    } catch (_: Throwable) {
                        // In JVM unit tests Log.d is not mocked
                    }
                }
            }
            return HttpLoggingInterceptor(redactingLogger).apply {
                redactHeader("Authorization")
                level = if (isDebug) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            }
        }

        fun createOkHttpClient(
            tokenProvider: () -> String?,
            timeoutSeconds: Long = 30L,
            isDebug: Boolean = AppBuildConfig.DEBUG
        ): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(createAuthInterceptor(tokenProvider))
                .addInterceptor(createLoggingInterceptor(isDebug))
                .build()
        }

        fun createRetrofit(
            baseUrl: String,
            okHttpClient: OkHttpClient,
            moshi: Moshi = createMoshi()
        ): Retrofit {
            return Retrofit.Builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
        }

        fun createApiService(baseUrl: String, token: String? = null): SammApiService {
            return SammNetworkClient(baseUrl, token).apiService
        }

        fun createApiService(baseUrl: String, tokenProvider: () -> String?): SammApiService {
            return SammNetworkClient(baseUrl, tokenProvider).apiService
        }

        /**
         * Extracts human-readable error detail from JSON error body.
         * Handles both string detail (e.g. {"detail": "Not authenticated"}) and
         * validation error list (e.g. {"detail": [{"loc": [...], "msg": "..."}]}).
         */
        fun extractErrorMessage(errorBody: String?): String? {
            if (errorBody.isNullOrBlank()) return null
            return try {
                val moshi = Moshi.Builder().build()
                val map = moshi.adapter(Any::class.java).fromJson(errorBody) as? Map<*, *>
                val detail = map?.get("detail")
                when (detail) {
                    is String -> detail
                    is List<*> -> {
                        val items = detail.mapNotNull { item ->
                            if (item is Map<*, *>) {
                                val loc = (item["loc"] as? List<*>)?.joinToString(".")
                                val msg = item["msg"] as? String
                                if (!loc.isNullOrBlank() && !msg.isNullOrBlank()) "$loc: $msg"
                                else msg ?: item.toString()
                            } else {
                                item?.toString()
                            }
                        }
                        if (items.isNotEmpty()) "Validation error: ${items.joinToString("; ")}"
                        else null
                    }
                    else -> {
                        (map?.get("message") as? String)
                            ?: (map?.get("error") as? String)
                            ?: errorBody
                    }
                }
            } catch (_: Exception) {
                errorBody
            }
        }

        fun parseErrorDetail(errorBody: String?): String? = extractErrorMessage(errorBody)

        /**
         * Maps Retrofit HTTP error response to typed fail-closed SasGatewayException hierarchy:
         * - 401 -> SasAuthException
         * - 403 -> SasAuthException (insufficient scope)
         * - 404 -> SasNotFoundException
         * - 409 -> SasBusinessException (conflict)
         * - 422 -> SasBusinessException (validation error)
         * - 429 -> SasTransportException (rate limit exceeded with optional Retry-After)
         * - 5xx -> SasInconclusiveException (server/transport failure, operation may have reached server)
         */
        fun parseSammError(response: Response<*>): SasGatewayException {
            val statusCode = response.code()
            val errorBody = try {
                response.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            return parseSammError(statusCode, errorBody, response.headers())
        }

        fun parseSammError(
            statusCode: Int,
            errorBody: String? = null,
            headers: Headers? = null
        ): SasGatewayException {
            val extractedMsg = extractErrorMessage(errorBody)

            return when (statusCode) {
                401 -> {
                    val msg = extractedMsg ?: "Unauthorized (401)"
                    SasAuthException(message = msg)
                }
                403 -> {
                    val msg = extractedMsg ?: "Forbidden: insufficient scope (403)"
                    SasAuthException(message = msg)
                }
                404 -> {
                    val msg = extractedMsg ?: "Customer or resource not found (404)"
                    SasNotFoundException(statusCode = 404, message = msg)
                }
                409 -> {
                    val msg = extractedMsg ?: "Conflict: duplicate resource (409)"
                    SasBusinessException(statusCode = 409, errorMessage = msg)
                }
                422 -> {
                    val msg = extractedMsg ?: "Validation error: unprocessable entity (422)"
                    SasBusinessException(statusCode = 422, errorMessage = msg)
                }
                429 -> {
                    val retryAfter = headers?.get("Retry-After")
                    val msg = when {
                        retryAfter != null -> "${extractedMsg ?: "Rate limit exceeded"}. Retry after $retryAfter seconds."
                        else -> extractedMsg ?: "Rate limit exceeded (429)"
                    }
                    SasTransportException(message = msg)
                }
                in 500..599 -> {
                    val msg = extractedMsg ?: "Server error ($statusCode)"
                    SasInconclusiveException(statusCode = statusCode, message = msg)
                }
                in 400..499 -> {
                    val msg = extractedMsg ?: "Client error ($statusCode)"
                    SasBusinessException(statusCode = statusCode, errorMessage = msg)
                }
                else -> {
                    val msg = extractedMsg ?: "Unexpected gateway response ($statusCode)"
                    SasInconclusiveException(statusCode = statusCode, message = msg)
                }
            }
        }
    }
}

fun normalizeBaseUrl(rawUrl: String): String = SammNetworkClient.normalizeBaseUrl(rawUrl)
fun parseSammError(response: Response<*>): SasGatewayException = SammNetworkClient.parseSammError(response)
fun parseSammError(statusCode: Int, errorBody: String? = null, headers: Headers? = null): SasGatewayException =
    SammNetworkClient.parseSammError(statusCode, errorBody, headers)
