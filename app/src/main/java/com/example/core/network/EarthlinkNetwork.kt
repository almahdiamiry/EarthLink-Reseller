package com.example.core.network

import android.content.Context
import android.util.Log
import com.example.core.model.*
import com.example.core.security.PreferenceManager
import com.example.core.util.AppBuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface EarthlinkApiService {

    @FormUrlEncoded
    @POST("token")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("loginType") loginType: String = "1",
        @Field("grant_type") grantType: String = "password"
    ): LoginResponse

    @GET("affiliate/deposit/balance")
    suspend fun getBalance(): ApiEnvelope<Double>

    @GET("testcount")
    suspend fun getTestUsersCount(
        @Query("affiliateIndex") affiliateIndex: Int? = null
    ): okhttp3.ResponseBody

    @GET("reports/testsUsed")
    suspend fun getTestsUsed(
        @Query("StartIndex") startIndex: Int = 0,
        @Query("RowCount") rowCount: Int = 1
    ): ApiEnvelope<Any>

    @GET("home/PrepaidNeeded")
    suspend fun getPrepaidNeeded(): ApiEnvelope<Any>

    @GET("accounts/all")
    suspend fun getPackages(): ApiEnvelope<List<AccountPackage>>

    @FormUrlEncoded
    @POST("affiliate/deposit/accountCost")
    suspend fun getAccountCost(
        @Field("AccountID") accountId: Int
    ): okhttp3.ResponseBody

    @FormUrlEncoded
    @POST("user/all")
    suspend fun searchUsers(
        @Field("StartIndex") startIndex: Int,
        @Field("RowCount") rowCount: Int,
        @Field("OrderDescending") orderDescending: Boolean,
        @Field("OrderBy") orderBy: String,
        @Field("AccountStatusID") accountStatusId: String,
        @Field("TimePeriodID") timePeriodId: String,
        @Field("Query") query: String
    ): ApiEnvelope<UserListResponse>

    @GET("user/{userIndex}")
    suspend fun getUserDetail(
        @Path("userIndex") userIndex: Int
    ): ApiEnvelope<UserDetail>

    @FormUrlEncoded
    @POST("usersession/active")
    suspend fun getActiveSessions(
        @Field("StartIndex") startIndex: Int,
        @Field("RowCount") rowCount: Int
    ): ApiEnvelope<ActiveSessionResponse>

    @GET("user/autocomplete")
    suspend fun autocompleteUser(
        @Query("key") key: String
    ): ApiEnvelope<Any>

    @FormUrlEncoded
    @POST("user/checkuseravailable")
    suspend fun checkUserAvailable(
        @Field("UserID") userId: String
    ): ApiEnvelope<Boolean>

    @FormUrlEncoded
    @POST("usercustomer/phone")
    suspend fun customerLookupByPhone(
        @Field("phoneNumber") phone: String
    ): ApiEnvelope<Any>

    @FormUrlEncoded
    @POST("usercustomer/create")
    suspend fun createCustomer(
        @Field("customerFullName") name: String,
        @Field("customerPhoneNumber") phone: String,
        @Field("email") email: String = "",
        @Field("address") address: String = ""
    ): ApiEnvelope<Any>

    @FormUrlEncoded
    @POST("user/newtestuser")
    suspend fun createTestUser(
        @Field("MobileNumber") mobile: String,
        @Field("AccountIndex") accountIndex: Int,
        @Field("UserID") userId: String,
        @Field("DisplayName") displayName: String,
        @Field("AffiliateIndex") affiliateIndex: Int,
        @Field("UserPass") userPass: String
    ): ApiEnvelope<Boolean>

    @FormUrlEncoded
    @POST("user/newuserdeposit")
    suspend fun createUserUsingDeposit(
        @Field("MobileNumber") mobile: String,
        @Field("AccountIndex") accountIndex: Int,
        @Field("UserID") userId: String,
        @Field("DisplayName") displayName: String,
        @Field("AffiliateIndex") affiliateIndex: Int,
        @Field("UserPass") userPass: String,
        @Field("DepositPassword") depositPass: String,
        @Field("customerId") customerId: Int
    ): ApiEnvelope<Boolean>

    @FormUrlEncoded
    @POST("user/newrefilldeposit")
    suspend fun refillUserDeposit(
        @Field("UserID") userId: String,
        @Field("DepositPassword") depositPass: String
    ): ApiEnvelope<Boolean>

    @POST("user/extend/{userIndex}")
    suspend fun extendUser(
        @Path("userIndex") userIndex: Int
    ): ApiEnvelope<Boolean>

    @POST("user/showpassword")
    suspend fun showUserPassword(
        @Body payload: PasswordReq
    ): okhttp3.ResponseBody

    @POST("user/showaccountpassword")
    suspend fun showAccountPassword(
        @Body payload: PasswordReq
    ): okhttp3.ResponseBody

    @POST("user/changepassword")
    suspend fun changeUserPassword(
        @Body payload: ChangePasswordReq
    ): ApiEnvelope<Boolean>

    @POST("user/changeaccountpassword")
    suspend fun changeAccountPassword(
        @Body payload: ChangePasswordReq
    ): ApiEnvelope<Boolean>

    @POST("user/chnageaccounttype")
    suspend fun changeAccountType(
        @Body payload: Map<String, @JvmSuppressWildcards Any>
    ): ApiEnvelope<Boolean>

    @POST("user/{userIndex}")
    suspend fun updateUser(
        @Path("userIndex") userIndex: Int,
        @Body payload: UserDetail
    ): ApiEnvelope<Boolean>

    @GET("affiliate/deposit/accountStatement")
    suspend fun getAccountStatement(
        @Query("StartIndex") startIndex: Int,
        @Query("RowCount") rowCount: Int,
        @Query("Query") query: String,
        @Query("OperationType") opType: String,
        @Query("fromDate") fromDate: String,
        @Query("toDate") toDate: String
    ): ApiEnvelope<AccountStatementResponse>
}

@JsonClass(generateAdapter = true)
data class PasswordReq(val userindex: Int, val userid: String)
@JsonClass(generateAdapter = true)
data class ChangePasswordReq(val userindex: Int, val userid: String, val NewPassword: String)

class NetworkClient(private val context: Context) {
    private val prefManager = PreferenceManager(context)
    private val tokenLock = Any()

    private val moshi = Moshi.Builder()
        .build()

    private fun refreshEarthlinkToken(isGoogleUser: Boolean = true): String? {
        val username = if (isGoogleUser) prefManager.getIspAdminUsername() else prefManager.getUsername()
        val password = if (isGoogleUser) prefManager.getIspAdminPassword() else prefManager.getPassword()
        if (username.isNullOrEmpty() || password.isNullOrEmpty()) return null
        
        // Use a separate client without authInterceptor to avoid infinite recursion
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()

        val formBody = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("loginType", "1")
            .add("grant_type", "password")
            .build()

        val request = Request.Builder()
            .url("https://rapi.earthlink.iq/api/reseller/token")
            .header("User-Agent", "Android 9; Resellers 40001; KotlinCompose")
            .header("Accept", "application/json, text/plain, */*")
            .post(formBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return null
                    val adapter = moshi.adapter(LoginResponse::class.java)
                    val loginResponse = adapter.fromJson(bodyString)
                    val token = loginResponse?.accessToken
                    if (token != null) {
                        if (isGoogleUser) {
                            prefManager.saveEarthlinkApiToken(token)
                        } else {
                            prefManager.saveAuthToken(token)
                        }
                        token
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("EarthlinkNetwork", "Failed to refresh Earthlink API token", e)
            null
        }
    }

    private val tokenAuthenticator = Authenticator { _, response ->
        if (responseCount(response) >= 2) return@Authenticator null

        val request = response.request
        if (request.url.encodedPath.contains("token")) return@Authenticator null

        val token = prefManager.getAuthToken()
        val isGoogleUser = token?.startsWith("google_oauth_session_") == true

        synchronized(tokenLock) {
            val currentToken = if (isGoogleUser) prefManager.getEarthlinkApiToken() else prefManager.getAuthToken()
            val requestHeaderToken = request.header("Authorization")?.removePrefix("Bearer ")

            val newToken = if (!currentToken.isNullOrEmpty() && currentToken != requestHeaderToken) {
                currentToken
            } else {
                refreshEarthlinkToken(isGoogleUser = isGoogleUser)
            }

            if (!newToken.isNullOrEmpty()) {
                request.newBuilder()
                    .header("Authorization", "Bearer $newToken")
                    .build()
            } else {
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    // OkHttp Auth and User-Agent injection Interceptor
    private val authInterceptor = Interceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
            .header("User-Agent", "Android 9; Resellers 40001; KotlinCompose")
            .header("Accept", "application/json, text/plain, */*")

        // Add bearer token if logged in
        val token = prefManager.getAuthToken()
        val isGoogleUser = token?.startsWith("google_oauth_session_") == true

        val actualToken = if (isGoogleUser) {
            var apiToken = prefManager.getEarthlinkApiToken()
            if (apiToken.isNullOrEmpty()) {
                apiToken = refreshEarthlinkToken()
            }
            apiToken
        } else {
            token
        }

        if (!actualToken.isNullOrEmpty() && !original.url.encodedPath.contains("token")) {
            builder.header("Authorization", "Bearer $actualToken")
        }

        chain.proceed(builder.build())
    }

    // Custom logger that masks passwords, passphrases, tokens, authorization headers
    private val redactingLogger = object : HttpLoggingInterceptor.Logger {
        override fun log(message: String) {
            if (!AppBuildConfig.DEBUG) return
            var sanitized = JSON_SENSITIVE.replace(message) { match ->
                val key = match.value.substringBefore(":")
                "$key:\"[REDACTED]\""
            }
            sanitized = FORM_SENSITIVE.replace(sanitized, "$1[REDACTED]")
            sanitized = HEADER_SENSITIVE.replace(sanitized, "$1[REDACTED]")
            Log.d("EarthlinkApi", sanitized)
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor(redactingLogger).apply {
        level = if (AppBuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(authInterceptor)
        .authenticator(tokenAuthenticator)
        .addInterceptor(loggingInterceptor)
        .build()

    companion object {
        private val JSON_SENSITIVE = "\"([^\"]*?(?:password|Password|NewPassword|DepositPassword|UserPass|userPass|access_token|Authorization|bearer|token)[^\"]*?)\"\\s*:\\s*(?:\"[^\"]*\"|[^,\\}\\s]+)".toRegex()
        private val FORM_SENSITIVE = "((?:password|Password|NewPassword|DepositPassword|UserPass|userPass|access_token|Authorization|bearer|token)=)[^&\\s]+".toRegex(RegexOption.IGNORE_CASE)
        private val HEADER_SENSITIVE = "((?:Authorization|bearer|token|password|Password|NewPassword|DepositPassword|UserPass|userPass):\\s*).+".toRegex(RegexOption.IGNORE_CASE)
    }

    val apiService: EarthlinkApiService = Retrofit.Builder()
        .baseUrl("https://rapi.earthlink.iq/api/reseller/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(EarthlinkApiService::class.java)
}
