package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.network.NetworkClient
import com.example.core.security.PreferenceManager
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Claim: WS9 / API-06 — Double-checked token refresh synchronization on tokenLock guarantees
 * that concurrent requests hitting uninitialized or expired tokens execute exactly one refresh
 * call, with all concurrent callers safely receiving the single refreshed token.
 * Seam: Robolectric runtime exercising NetworkClient.authInterceptor with concurrent requests.
 * Independent Oracle: Architecture boundary (Concurrency Safety) — refresh invocation count == 1,
 * all requests receive Authorization: Bearer <refreshed_token>.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Workstream9TokenRefreshConcurrencyTest {

    private lateinit var context: Context
    private lateinit var prefManager: PreferenceManager
    private lateinit var networkClient: NetworkClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefManager = PreferenceManager(context)
        prefManager.saveAuthToken("google_oauth_session_test_123")
        prefManager.saveEarthlinkApiToken(null)
        networkClient = NetworkClient(context)
    }

    private class RecordingChain(val originalRequest: Request) : Interceptor.Chain {
        var proceededRequest: Request? = null
        override fun request(): Request = originalRequest
        override fun proceed(request: Request): Response {
            proceededRequest = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaTypeOrNull()))
                .build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = throw NotImplementedError()
        override fun connectTimeoutMillis(): Int = 15000
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 30000
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 15000
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit): Interceptor.Chain = this
    }

    @Test
    fun productionAuthInterceptor_concurrentRequests_invokesRefreshOnceAndInjectsBearerToken() = runBlocking {
        val refreshExecutionCount = AtomicInteger(0)
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val allCallersReady = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger(0)

        networkClient.tokenRefresherForTest = {
            refreshExecutionCount.incrementAndGet()
            refreshStarted.complete(Unit)
            runBlocking { releaseRefresh.await() }
            val token = "refreshed_token_live_999"
            prefManager.saveEarthlinkApiToken(token)
            token
        }

        val chains = (1..10).map {
            RecordingChain(
                Request.Builder()
                    .url("https://rapi.earthlink.iq/api/reseller/user/getdetail?userIndex=101")
                    .build()
            )
        }

        val jobs = chains.map { chain ->
            launch(Dispatchers.Default) {
                if (readyCount.incrementAndGet() == 10) {
                    allCallersReady.complete(Unit)
                }
                allCallersReady.await()
                networkClient.authInterceptor.intercept(chain)
            }
        }

        refreshStarted.await()
        releaseRefresh.complete(Unit)
        jobs.joinAll()

        assertEquals(
            "Exactly one token refresh must execute across concurrent callers through authInterceptor",
            1,
            refreshExecutionCount.get()
        )

        assertEquals(10, chains.size)
        assertTrue(
            "All 10 requests must have Authorization: Bearer refreshed_token_live_999 header",
            chains.all { it.proceededRequest?.header("Authorization") == "Bearer refreshed_token_live_999" }
        )
    }

    @Test
    fun productionAuthInterceptor_cachedTokenPresent_executesZeroRefreshes() = runBlocking {
        prefManager.saveEarthlinkApiToken("pre_existing_cached_token")
        val refreshExecutionCount = AtomicInteger(0)
        networkClient.tokenRefresherForTest = {
            refreshExecutionCount.incrementAndGet()
            "new_token"
        }

        val chains = (1..10).map {
            RecordingChain(
                Request.Builder()
                    .url("https://rapi.earthlink.iq/api/reseller/user/getdetail?userIndex=101")
                    .build()
            )
        }

        val jobs = chains.map { chain ->
            launch(Dispatchers.Default) {
                networkClient.authInterceptor.intercept(chain)
            }
        }
        jobs.joinAll()

        assertEquals(
            "Zero refreshes must execute when token is already cached in preferences",
            0,
            refreshExecutionCount.get()
        )

        assertTrue(
            "All 10 requests must have Authorization header with pre-existing cached token",
            chains.all { it.proceededRequest?.header("Authorization") == "Bearer pre_existing_cached_token" }
        )
    }

    @Test
    fun concurrentTokenRefresh_executesSingleRefreshAndSharesResult() = runBlocking {
        val tokenLock = Any()
        var cachedToken: String? = null
        val refreshExecutionCount = AtomicInteger(0)
        val acquiredTokens = ConcurrentLinkedQueue<String>()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val allCallersReady = CompletableDeferred<Unit>()
        val readyCount = AtomicInteger(0)

        val jobs = (1..10).map { threadId ->
            launch(Dispatchers.Default) {
                if (readyCount.incrementAndGet() == 10) {
                    allCallersReady.complete(Unit)
                }
                allCallersReady.await()

                var apiToken = cachedToken
                if (apiToken.isNullOrEmpty()) {
                    synchronized(tokenLock) {
                        apiToken = cachedToken
                        if (apiToken.isNullOrEmpty()) {
                            refreshExecutionCount.incrementAndGet()
                            refreshStarted.complete(Unit)
                            runBlocking { releaseRefresh.await() }
                            val newToken = "refreshed_token_val_123"
                            cachedToken = newToken
                            apiToken = newToken
                        }
                    }
                }
                acquiredTokens.add(apiToken)
            }
        }

        refreshStarted.await()
        releaseRefresh.complete(Unit)
        jobs.joinAll()

        assertEquals("Exactly one token refresh must execute across concurrent callers", 1, refreshExecutionCount.get())
        assertEquals("All 10 callers must receive the token", 10, acquiredTokens.size)
        assertTrue("All callers must receive the same refreshed token", acquiredTokens.all { it == "refreshed_token_val_123" })
    }
}
