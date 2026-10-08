package com.example.core.network.samm

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SammWireContractTest
 *
 * Claim: Verifies the real HTTP wire protocol behavior of SammApiService and SammNetworkClient:
 *        1. Exact URL path prefix `/api/v1/...` without missing or duplicate segments.
 *        2. Correct HTTP methods (GET, POST, PATCH).
 *        3. Mandatory headers injected: `Authorization: Bearer <token>` and `Accept: application/json`.
 *        4. Integer customer ID path formatting (`/customers/{customer_id}`).
 *        5. Wire JSON payload serialization and deserialization matching SAMM OpenAPI 3.1.0 schemas.
 * Seam: JVM MockWebServer Wire Contract (real OkHttp dispatch against local MockWebServer).
 * Independent Oracle: Expected HTTP methods, path strings, injected header values, and serialized
 *                      payload contracts defined independently from the SAMM OpenAPI specification.
 */
class SammWireContractTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var apiService: SammApiService
    private val testToken = "samm_test_secret_abc123"

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val baseUrl = mockServer.url("/").toString()
        apiService = SammNetworkClient.createApiService(baseUrl, testToken)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun getMe_verifiesPathMethodHeadersAndResponseDeserialization() = runTest {
        val jsonResponse = """
            {
              "token_id": 4,
              "name": "reseller-app-pos1",
              "scopes": ["customers:read", "customers:write", "plans:read"],
              "rate_limit_per_min": 120,
              "server_version": "5.1.15"
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.getMe()

        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals(4, body?.tokenId)
        assertEquals("reseller-app-pos1", body?.name)
        assertEquals(listOf("customers:read", "customers:write", "plans:read"), body?.scopes)
        assertEquals(120, body?.rateLimitPerMin)
        assertEquals("5.1.15", body?.serverVersion)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/me", recorded.path)
        assertEquals("Bearer $testToken", recorded.getHeader("Authorization"))
        assertEquals("application/json", recorded.getHeader("Accept"))
    }

    @Test
    fun listCustomers_verifiesQueryParametersAndPath() = runTest {
        val jsonResponse = """
            {
              "total": 1,
              "items": [
                {
                  "id": 105,
                  "username": "user_baghdad_01",
                  "firstname": "Ahmed",
                  "lastname": "Ali",
                  "mobile": "07701234567",
                  "email": null,
                  "status": "active",
                  "plan_id": 12,
                  "plan_name": "Standard 10M",
                  "auto_renew": false,
                  "expiration_date": "2026-10-01T10:00:00Z",
                  "created_at": "2026-08-01T10:00:00Z"
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.listCustomers(search = "baghdad", page = 1, perPage = 20)

        assertTrue(response.isSuccessful)
        val list = response.body()
        assertNotNull(list)
        assertEquals(1, list?.total)
        assertEquals(1, list?.items?.size)
        val item = list?.items?.first()
        assertEquals(105, item?.id)
        assertEquals("user_baghdad_01", item?.username)
        assertEquals("active", item?.status)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/customers?search=baghdad&page=1&per_page=20", recorded.path)
        assertEquals("Bearer $testToken", recorded.getHeader("Authorization"))
    }

    @Test
    fun getCustomer_verifiesIntegerPathParamFormatting() = runTest {
        val customerId = 105
        val jsonResponse = """
            {
              "id": 105,
              "username": "user_baghdad_01",
              "firstname": "Ahmed",
              "lastname": "Ali",
              "mobile": "07701234567",
              "email": null,
              "status": "active",
              "plan_id": 12,
              "plan_name": "Standard 10M",
              "auto_renew": false,
              "expiration_date": "2026-10-01T10:00:00Z",
              "created_at": "2026-08-01T10:00:00Z"
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.getCustomer(customerId)

        assertTrue(response.isSuccessful)
        assertEquals(105, response.body()?.id)
        assertEquals("user_baghdad_01", response.body()?.username)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/customers/105", recorded.path)
    }

    @Test
    fun createCustomer_verifiesPostMethodAndSerializedJsonBody() = runTest {
        val request = CustomerCreate(
            username = "user_baghdad_02",
            password = "SecretPassword123",
            firstname = "Mustafa",
            lastname = "Kamal",
            planId = 12,
            generateInvoice = false,
            mobile = "07801234567"
        )

        val jsonResponse = """
            {
              "id": 106,
              "username": "user_baghdad_02",
              "firstname": "Mustafa",
              "lastname": "Kamal",
              "mobile": "07801234567",
              "status": "active",
              "plan_id": 12,
              "plan_name": "Standard 10M",
              "created_at": "2026-10-06T00:00:00Z"
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.createCustomer(request)

        assertEquals(201, response.code())
        assertEquals(106, response.body()?.id)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers", recorded.path)

        val recordedBody = recorded.body.readUtf8()
        assertTrue("Must include username", recordedBody.contains(""""username":"user_baghdad_02""""))
        assertTrue("Must include password", recordedBody.contains(""""password":"SecretPassword123""""))
        assertTrue("Must include firstname", recordedBody.contains(""""firstname":"Mustafa""""))
        assertTrue("Must include lastname", recordedBody.contains(""""lastname":"Kamal""""))
        assertTrue("Must include plan_id", recordedBody.contains(""""plan_id":12"""))
        assertTrue("Must include generate_invoice false", recordedBody.contains(""""generate_invoice":false"""))
    }

    @Test
    fun updateCustomer_verifiesPatchMethodAndPath() = runTest {
        val customerId = 105
        val request = CustomerUpdate(
            password = "NewPassword456",
            firstname = "AhmedUpdated"
        )

        val jsonResponse = """
            {
              "id": 105,
              "username": "user_baghdad_01",
              "firstname": "AhmedUpdated",
              "lastname": "Ali",
              "status": "active"
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.updateCustomer(customerId, request)

        assertTrue(response.isSuccessful)
        assertEquals("AhmedUpdated", response.body()?.firstname)

        val recorded = mockServer.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/customers/105", recorded.path)

        val recordedBody = recorded.body.readUtf8()
        assertTrue(recordedBody.contains(""""password":"NewPassword456""""))
        assertTrue(recordedBody.contains(""""firstname":"AhmedUpdated""""))
    }

    @Test
    fun suspendCustomer_verifiesPostMethodAndExactPath() = runTest {
        val customerId = 105
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 105, "username": "user1", "status": "suspended"}""")
        )

        val response = apiService.suspendCustomer(customerId)

        assertTrue(response.isSuccessful)
        assertEquals("suspended", response.body()?.status)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/105/suspend", recorded.path)
    }

    @Test
    fun activateCustomer_verifiesPostMethodAndExactPath() = runTest {
        val customerId = 105
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 105, "username": "user1", "status": "active"}""")
        )

        val response = apiService.activateCustomer(customerId)

        assertTrue(response.isSuccessful)
        assertEquals("active", response.body()?.status)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/105/activate", recorded.path)
    }

    @Test
    fun renewCustomer_verifiesPostMethodAndGenerateInvoiceFalseQuery() = runTest {
        val customerId = 105
        val jsonResponse = """
            {
              "id": 105,
              "username": "user_baghdad_01",
              "status": "active",
              "new_expiration": "2026-11-01T10:00:00Z",
              "invoice_id": null
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.renewCustomer(customerId, generateInvoice = false)

        assertTrue(response.isSuccessful)
        val renewBody = response.body()
        assertNotNull(renewBody)
        assertEquals(105, renewBody?.id)
        assertEquals("2026-11-01T10:00:00Z", renewBody?.newExpiration)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/105/renew?generate_invoice=false", recorded.path)
    }

    @Test
    fun assignPlan_verifiesPostMethodAndPayload() = runTest {
        val customerId = 105
        val request = AssignPlanRequest(
            planId = 15,
            generateInvoice = false,
            resetState = false,
            reactivate = false,
            applySpeedLive = true,
            notifyCustomer = false
        )

        val jsonResponse = """
            {
              "id": 105,
              "username": "user_baghdad_01",
              "plan_id": 15,
              "invoice_id": null
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.assignPlan(customerId, request)

        assertTrue(response.isSuccessful)
        assertEquals(15, response.body()?.planId)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/105/assign-plan", recorded.path)

        val recordedBody = recorded.body.readUtf8()
        assertTrue(recordedBody.contains(""""plan_id":15"""))
        assertTrue(recordedBody.contains(""""generate_invoice":false"""))
        assertTrue(recordedBody.contains(""""apply_speed_live":true"""))
    }

    @Test
    fun listPlans_verifiesGetMethodAndPagingParams() = runTest {
        val jsonResponse = """
            [
              {
                "id": 12,
                "name": "Standard 10M",
                "price": 35000.0,
                "speed_download": 10000000,
                "speed_upload": 5000000,
                "description": "Standard Residential"
              }
            ]
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.listPlans(enabledOnly = true)

        assertTrue(response.isSuccessful)
        val list = response.body()
        assertNotNull(list)
        assertEquals(1, list?.size)
        assertEquals("Standard 10M", list?.first()?.name)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/plans?enabled_only=true", recorded.path)
    }

    @Test
    fun listCommands_verifiesGetMethodAndPagingParams() = runTest {
        val jsonResponse = """
            {
              "total": 1,
              "items": [
                {
                  "id": 901,
                  "action": "reset_limit",
                  "target": "user_baghdad_01",
                  "applied": true,
                  "created_at": "2026-10-06T03:00:00Z",
                  "applied_at": "2026-10-06T03:00:02Z"
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.listCommands(limit = 50, offset = 0)

        assertTrue(response.isSuccessful)
        val list = response.body()
        assertNotNull(list)
        assertEquals(1, list?.total)
        val cmd = list?.items?.first()
        assertEquals(901, cmd?.id)
        assertEquals("reset_limit", cmd?.action)
        assertTrue(cmd?.applied == true)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/commands?limit=50&offset=0", recorded.path)
    }

    @Test
    fun clientWithBaseUrlContainingApiV1_normalizesWithoutDuplicatePathSegments() = runTest {
        // Test that if baseUrl is provided with "/api/v1", it is normalized so that
        // outgoing requests are strictly "/api/v1/me" and NOT "/api/v1/api/v1/me".
        val baseUrlWithApiV1 = mockServer.url("/api/v1/").toString()
        val customClientService = SammNetworkClient.createApiService(baseUrlWithApiV1, testToken)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token_id": 1, "name": "test"}""")
        )

        val response = customClientService.getMe()
        assertTrue(response.isSuccessful)

        val recorded = mockServer.takeRequest()
        assertEquals("/api/v1/me", recorded.path)
        assertFalse(
            "Must never contain duplicate /api/v1/api/v1",
            recorded.path?.contains("/api/v1/api/v1") == true
        )
    }

    @Test
    fun agentLogin_verifiesPathMethodBodyAndResponseDeserialization() = runTest {
        // Claim: Agent login posts username & password to /api/v1/auth/agent-login and returns agent token & info
        val jsonResponse = """
            {
              "token": "samm_agent_secret_token_12345",
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

        val unauthClient = SammNetworkClient.createApiService(mockServer.url("/").toString(), token = null)
        val response = unauthClient.agentLogin(
            SammAgentLoginRequest(
                username = "test_agent_v1",
                password = "MockAgentPassword#456"
            )
        )

        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals("samm_agent_secret_token_12345", body?.token)
        assertEquals("bearer", body?.tokenType)
        assertNull(body?.expiresAt)
        assertEquals(5, body?.agent?.id)
        assertEquals("test_agent_v1", body?.agent?.username)
        assertEquals("agent", body?.agent?.role)
        assertEquals(2, body?.agent?.resellerId)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/agent-login", recorded.path)
        assertEquals("application/json", recorded.getHeader("Accept"))
        assertNull("Unauthenticated login must not have Authorization header", recorded.getHeader("Authorization"))
        val requestBody = recorded.body.readUtf8()
        assertTrue(requestBody.contains("\"username\":\"test_agent_v1\""))
        assertTrue(requestBody.contains("\"password\":\"MockAgentPassword#456\""))
    }

    @Test
    fun agentLogout_verifiesPathMethodHeadersAndResponseDeserialization() = runTest {
        // Claim: Agent logout posts to /api/v1/auth/agent-logout with Bearer token
        val jsonResponse = """
            {
              "status": "success",
              "message": "Logged out successfully"
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonResponse)
        )

        val response = apiService.agentLogout()

        assertTrue(response.isSuccessful)
        val body = response.body()
        assertNotNull(body)
        assertEquals("success", body?.status)
        assertEquals("Logged out successfully", body?.message)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/auth/agent-logout", recorded.path)
        assertEquals("Bearer $testToken", recorded.getHeader("Authorization"))
    }

    @Test
    fun agentLogin_whenInvalidCredentials_returns401WithGenericFailure() = runTest {
        // Claim: Agent login failures return HTTP 401 with normalized generic message "Invalid username or password."
        val jsonError = """{"detail": "Invalid username or password."}"""
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody(jsonError)
        )

        val unauthClient = SammNetworkClient.createApiService(mockServer.url("/").toString(), token = null)
        val response = unauthClient.agentLogin(
            SammAgentLoginRequest(
                username = "invalid_user",
                password = "invalid_password"
            )
        )

        assertFalse(response.isSuccessful)
        assertEquals(401, response.code())
        val errorBody = response.errorBody()?.string()
        assertNotNull(errorBody)
        assertTrue(errorBody?.contains("Invalid username or password.") == true)
    }

    @Test
    fun agentLogin_whenThrottled_returns429TooManyRequestsWithRetryAfter() = runTest {
        // Claim: Agent login rate limit returns HTTP 429 Too Many Requests with Retry-After header
        val jsonError = """{"detail": "Too many failed login attempts. Please try again later."}"""
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "300")
                .setBody(jsonError)
        )

        val unauthClient = SammNetworkClient.createApiService(mockServer.url("/").toString(), token = null)
        val response = unauthClient.agentLogin(
            SammAgentLoginRequest(
                username = "throttled_user",
                password = "any_password"
            )
        )

        assertFalse(response.isSuccessful)
        assertEquals(429, response.code())
        assertEquals("300", response.headers()["Retry-After"])
        val errorBody = response.errorBody()?.string()
        assertNotNull(errorBody)
        assertTrue(errorBody?.contains("Too many failed login attempts") == true)
    }
}
