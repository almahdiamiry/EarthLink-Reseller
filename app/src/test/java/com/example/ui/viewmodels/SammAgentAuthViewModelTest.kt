package com.example.ui.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.AuditLog
import com.example.core.model.AuditLogIntegrityIssue
import com.example.core.model.AuditOrigin
import com.example.core.model.AuditSeverity
import com.example.core.model.SasProviders
import com.example.core.security.PreferenceManager
import com.example.domain.repository.AuditRepository
import com.example.domain.repository.EarthlinkGateway
import com.example.domain.repository.SyncRepository
import com.example.ui.screens.SammUrlNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SammAgentAuthViewModelTest
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - SAMM authenticates with an Agent-scoped Bearer Token minted from System > API, never with a
 *      username and password. SAMM exposes no login and no logout endpoint, so this suite contains
 *      no user/password authentication path.
 *    - The application receives an Agent-scoped Bearer Token and Agent metadata, never exposing
 *      raw tokens in the UI.
 *    - An unauthorized token (401) or forbidden token (403) fails closed with typed errors and
 *      persists nothing.
 *    - Logging out clears local credentials and issues no network request, because the token is a
 *      long-lived static credential with no server-side session to revoke.
 *
 * 2. Seam / Environment:
 *    - ROBOLECTRIC tier with real [PreferenceManager] and MockWebServer HTTP wire mock.
 *
 * 3. Independent Oracle:
 *    - Expected token values, agent metadata, HTTP request bodies, and error messages
 *      derived independently of implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SammAgentAuthViewModelTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferenceManager
    private lateinit var mockServer: MockWebServer
    private lateinit var gateway: EarthlinkGateway
    private lateinit var audit: TestAuditRepository
    private lateinit var syncRepo: SyncRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        prefs = PreferenceManager(context)
        prefs.clearCredentials()

        mockServer = MockWebServer()
        mockServer.start()

        gateway = Mockito.mock(EarthlinkGateway::class.java)
        audit = TestAuditRepository()
        syncRepo = Mockito.mock(SyncRepository::class.java)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockServer.shutdown()
        prefs.clearCredentials()
    }

    private fun createViewModel(): AuthViewModel {
        return AuthViewModel(
            gateway = gateway,
            prefs = prefs,
            audit = audit,
            syncRepo = syncRepo
        )
    }


    @Test
    fun logoutSamm_clearsLocalCredentialsWithoutCallingTheServer() = runTest {
        // SAMM exposes no logout endpoint: its token is a long-lived static bearer credential,
        // so signing out is purely local and must issue no network request.
        val serverUrl = mockServer.url("/").toString()
        prefs.saveSammBaseUrl(serverUrl)
        prefs.saveSammToken("samm_active_token_999")
        prefs.saveSammAgentInfo(agentId = 5, username = "test_agent_v1", resellerId = 2)

        val vm = createViewModel()
        var successCalled = false

        val job = vm.logoutSamm(onSuccess = { successCalled = true })
        job.join()

        assertTrue(successCalled)
        assertNull("Token should be cleared on logout", prefs.getSammToken())
        assertNull("Agent ID should be cleared on logout", prefs.getSammAgentId())
        assertNull("Agent username should be cleared on logout", prefs.getSammAgentUsername())
        assertFalse("SAMM should no longer be configured", prefs.isSammConfigured())

        assertEquals("Logout must not call the SAMM server", 0, mockServer.requestCount)

        assertTrue(
            "Audit log must contain agent logout entry",
            audit.loggedActions.any { it.contains("SAMM_LOGOUT") }
        )
    }

    @Test
    fun loginSammWithToken_success_verifiesTokenWithGetMe_savesCredentialsAndUnlocksApp() = runTest {
        val serverUrl = mockServer.url("/").toString()
        val jsonMe = """
            {
              "token_id": 123,
              "name": "Super Admin Token",
              "scopes": ["customers:read", "customers:write", "plans:read"],
              "created_at": "2026-10-08T20:00:00Z"
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonMe)
        )

        val vm = createViewModel()
        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSammWithToken(
            baseUrl = serverUrl,
            token = "samm_valid_secret_token",
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        assertTrue("onSuccess must be called", successCalled)
        assertNull("onError must not be called", errorCalled)
        assertNull("ViewModel error flow must be null", vm.error.value)

        // Verifies token and URL stored in preferences
        assertEquals(SammUrlNormalizer.normalize(serverUrl), prefs.getSammBaseUrl())
        assertEquals("samm_valid_secret_token", prefs.getSammToken())
        assertTrue("SAMM must be configured", prefs.isSammConfigured())

        // Verifies network request to /api/v1/me
        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/me", recorded.path)
        assertEquals("Bearer samm_valid_secret_token", recorded.getHeader("Authorization"))

        // Verifies audit log entry
        assertTrue(
            "Audit log must contain SAMM_LOGIN entry",
            audit.loggedActions.any { it.contains("SAMM_LOGIN") }
        )
    }

    @Test
    fun loginSammWithToken_unauthorized_failsClosedAndDoesNotPersist() = runTest {
        val serverUrl = mockServer.url("/").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Unauthorized API token"}""")
        )

        val vm = createViewModel()
        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSammWithToken(
            baseUrl = serverUrl,
            token = "samm_invalid_token",
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        assertFalse("onSuccess must not be called", successCalled)
        assertEquals("Invalid or unauthorized API token.", errorCalled)
        assertEquals("Invalid or unauthorized API token.", vm.error.value)
        assertNull("Token must NOT be persisted on failure", prefs.getSammToken())
        assertFalse("SAMM must NOT be configured", prefs.isSammConfigured())
    }

    @Test
    fun loginSammWithToken_blankInputs_failsValidationWithoutNetworkCall() = runTest {
        val vm = createViewModel()
        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSammWithToken(
            baseUrl = "",
            token = "",
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        assertFalse("onSuccess must not be called", successCalled)
        assertEquals("Server URL and API Token cannot be empty.", errorCalled)
        assertEquals(0, mockServer.requestCount)
    }

    class TestAuditRepository : AuditRepository {
        val loggedActions = mutableListOf<String>()

        override fun getAuditLogs(): Flow<List<AuditLog>> = flowOf(emptyList())

        override suspend fun logAction(
            action: String,
            entityType: String?,
            entityId: String?,
            summary: String,
            metadataJsonMasked: String?,
            origin: AuditOrigin
        ) {
            loggedActions.add("$action:$entityType:$entityId:$summary")
        }

        override suspend fun log(
            severity: AuditSeverity,
            action: String,
            message: String,
            actor: String,
            origin: AuditOrigin
        ) {
            loggedActions.add("$action:$message")
        }

        override suspend fun verifyLogsIntegrity(): List<AuditLogIntegrityIssue> = emptyList()
        override suspend fun getRecentLogs(limit: Int): List<AuditLog> = emptyList()
        override suspend fun clearAuditTrail() {
            loggedActions.clear()
        }
    }
}
