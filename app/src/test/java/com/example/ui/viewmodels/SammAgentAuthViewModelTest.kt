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
 *    - SAMM Agent Login model allows Reseller Agents to authenticate using Username and Password.
 *    - The application receives an Agent-scoped Bearer Token and Agent metadata, never exposing
 *      raw tokens in the UI.
 *    - Zero Password Persistence: Passwords are used transiently during HTTP authentication and
 *      are NEVER written to SharedPreferences, Room, or disk, and are immediately cleared from memory.
 *    - Invalid credentials (401) or unauthorized accounts (403) fail closed with typed errors.
 *    - Agent logout sends revocation request to `/api/v1/auth/agent-logout` and clears local credentials.
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
    fun loginSamm_success_storesAgentScopedTokenAndMetadata_clearsPasswordFromMemory() = runTest {
        val serverUrl = mockServer.url("/").toString()
        val jsonResponse = """
            {
              "token": "samm_agent_secret_abc987",
              "token_type": "bearer",
              "expires_at": null,
              "agent": {
                "id": 5,
                "username": "test_agent_v1",
                "role": "agent",
                "reseller_id": 2
              }
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val vm = createViewModel()
        vm.setSammBaseUrl(serverUrl)
        vm.setSammUsername("test_agent_v1")
        vm.setSammPassword("MockAgentPassword#456")

        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSamm(
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        // 1. Success assertions
        assertTrue("onSuccess should be called", successCalled)
        assertNull("onError should not be called", errorCalled)
        assertNull("ViewModel error flow should be null", vm.error.value)

        // 2. Token and Agent info assertions
        assertEquals("samm_agent_secret_abc987", prefs.getSammToken())
        assertEquals("test_agent_v1", prefs.getSammAgentUsername())
        assertEquals(5, prefs.getSammAgentId())
        assertEquals(2, prefs.getSammAgentResellerId())
        assertEquals(SasProviders.ALAMIRY, prefs.getLastSelectedLoginProvider())

        // 3. Zero Password Persistence Assertion:
        // Plaintext password is NEVER saved in SharedPreferences or on disk
        val allPrefKeys = prefs.prefs.all.keys
        assertFalse("Prefs must not contain password key", allPrefKeys.contains("enc_samm_password"))
        assertFalse("Prefs must not contain agent password", allPrefKeys.contains("agent_password"))
        // Memory state in ViewModel must be cleared immediately
        assertEquals("In-memory password must be cleared after login", "", vm.sammPassword.value)

        // 4. Verify HTTP wire payload
        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/agent-login", recorded.path)
        val bodyText = recorded.body.readUtf8()
        assertTrue(bodyText.contains("\"username\":\"test_agent_v1\""))
        assertTrue(bodyText.contains("\"password\":\"MockAgentPassword#456\""))

        // 5. Audit logged
        assertTrue(
            "Audit log must contain agent login entry",
            audit.loggedActions.any { it.contains("SAMM_LOGIN") && it.contains("Agent test_agent_v1 (ID: 5) logged in") }
        )
    }

    @Test
    fun loginSamm_invalidCredentials_failsClosedWith401AndClearsPassword() = runTest {
        val serverUrl = mockServer.url("/").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Incorrect username or password"}""")
        )

        val vm = createViewModel()
        vm.setSammBaseUrl(serverUrl)
        vm.setSammUsername("test_agent_v1")
        vm.setSammPassword("wrongpass")

        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSamm(
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        assertFalse(successCalled)
        assertEquals("Invalid username or password.", errorCalled)
        assertEquals("Invalid username or password.", vm.error.value)
        assertNull("No token should be stored on 401", prefs.getSammToken())
        assertEquals("Password must be cleared from memory on failure", "", vm.sammPassword.value)
    }

    @Test
    fun loginSamm_forbiddenAccount_failsClosedWith403() = runTest {
        val serverUrl = mockServer.url("/").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "User is an administrator, not a reseller agent"}""")
        )

        val vm = createViewModel()
        vm.setSammBaseUrl(serverUrl)
        vm.setSammUsername("admin_user")
        vm.setSammPassword("adminpass")

        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSamm(
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        assertFalse(successCalled)
        assertEquals("User is an administrator, not a reseller agent", errorCalled)
        assertNull("No token should be stored on 403", prefs.getSammToken())
    }

    @Test
    fun loginSamm_throttled_failsClosedWith429AndClearsPassword() = runTest {
        val serverUrl = mockServer.url("/").toString()
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "300")
                .setBody("""{"detail": "Too many failed login attempts. Please try again later."}""")
        )

        val vm = createViewModel()
        vm.setSammBaseUrl(serverUrl)
        vm.setSammUsername("test_agent_v1")
        vm.setSammPassword("transient_pass")

        var successCalled = false
        var errorCalled: String? = null

        val job = vm.loginSamm(
            onSuccess = { successCalled = true },
            onError = { errorCalled = it }
        )
        job.join()

        assertFalse(successCalled)
        assertEquals("Too many failed login attempts. Please try again later.", errorCalled)
        assertNull("No token should be stored on 429", prefs.getSammToken())
        assertEquals("", vm.sammPassword.value)
    }

    @Test
    fun logoutSamm_revokesTokenOnServerAndClearsLocalPreferences() = runTest {
        val serverUrl = mockServer.url("/").toString()
        prefs.saveSammBaseUrl(serverUrl)
        prefs.saveSammToken("samm_active_token_999")
        prefs.saveSammAgentInfo(agentId = 5, username = "test_agent_v1", resellerId = 2)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status": "success", "message": "Logged out successfully"}""")
        )

        val vm = createViewModel()
        var successCalled = false

        val job = vm.logoutSamm(onSuccess = { successCalled = true })
        job.join()

        assertTrue(successCalled)
        assertNull("Token should be cleared on logout", prefs.getSammToken())
        assertNull("Agent ID should be cleared on logout", prefs.getSammAgentId())
        assertNull("Agent username should be cleared on logout", prefs.getSammAgentUsername())
        assertFalse("SAMM should no longer be configured", prefs.isSammConfigured())

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/agent-logout", recorded.path)
        assertEquals("Bearer samm_active_token_999", recorded.getHeader("Authorization"))

        assertTrue(
            "Audit log must contain agent logout entry",
            audit.loggedActions.any { it.contains("SAMM_LOGOUT") }
        )
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
