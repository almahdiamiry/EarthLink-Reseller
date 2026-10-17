package com.example.core.network.samm

import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import com.example.core.network.SasGatewayException
import com.example.core.network.samm.takeRequestOrFail
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

        val recorded = mockServer.takeRequestOrFail()
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

        val response = apiService.listCustomers(search = "baghdad", limit = 20, offset = 0)

        assertTrue(response.isSuccessful)
        val list = response.body()
        assertNotNull(list)
        assertEquals(1, list?.total)
        assertEquals(1, list?.items?.size)
        val item = list?.items?.first()
        assertEquals(105, item?.id)
        assertEquals("user_baghdad_01", item?.username)
        assertEquals("active", item?.status)

        val recorded = mockServer.takeRequestOrFail()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/customers?search=baghdad&limit=20&offset=0", recorded.path)
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
        assertEquals("/api/v1/me", recorded.path)
        assertFalse(
            "Must never contain duplicate /api/v1/api/v1",
            recorded.path?.contains("/api/v1/api/v1") == true
        )
    }

    // ========================================================================
    // Pagination contract: SAMM uses limit/offset, never page/per_page.
    // Sending page/per_page is silently dropped by the server, which then applies its own
    // default limit of 100 and truncates the result set with no error at all.
    // ========================================================================

    @Test
    fun listCustomers_sendsLimitAndOffset_andNeverPageOrPerPage() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )

        apiService.listCustomers(limit = 200, offset = 400)

        val recorded = mockServer.takeRequestOrFail()
        val path = recorded.path.orEmpty()
        assertTrue("limit must be sent, got '$path'", path.contains("limit=200"))
        assertTrue("offset must be sent, got '$path'", path.contains("offset=400"))
        assertFalse("SAMM has no 'page' param, got '$path'", path.contains("page="))
        assertFalse("SAMM has no 'per_page' param, got '$path'", path.contains("per_page="))
    }

    @Test
    fun searchSubscribers_translatesPageAndPageSizeIntoLimitAndOffset() = runTest {
        val gateway = SammGatewayImpl(apiService)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )
        gateway.searchSubscribers(query = "abc", page = 3, pageSize = 50)

        // page 3 of 50 -> offset (3-1)*50 = 100, limit 50
        val recorded = mockServer.takeRequestOrFail()
        val path = recorded.path.orEmpty()
        assertTrue("page 3 at size 50 must offset by 100, got '$path'", path.contains("offset=100"))
        assertTrue("page size must become limit, got '$path'", path.contains("limit=50"))
        assertFalse("Must not send page=, got '$path'", path.contains("page="))
        assertFalse("Must not send per_page=, got '$path'", path.contains("per_page="))
    }

    @Test
    fun searchSubscribers_clampsAnOversizedPageSizeToTheServerCeiling() = runTest {
        // The dashboard asks for pageSize = 5000; SAMM rejects limit > 1000 with HTTP 422, so the
        // gateway must clamp rather than forward it.
        val gateway = SammGatewayImpl(apiService)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )
        gateway.searchSubscribers(query = "", page = 1, pageSize = 5000)

        val recorded = mockServer.takeRequestOrFail()
        val path = recorded.path.orEmpty()
        assertTrue("Oversized limit must be clamped to 1000, got '$path'", path.contains("limit=1000"))
        assertFalse("Must never send limit=5000, got '$path'", path.contains("limit=5000"))
    }

    // ========================================================================
    // Plan price: a price of 0 is a real value (a free plan), not "unknown".
    // Live reference: plan 10 "Owner" has price 0.0, plan 6 "Economy" has 35.0.
    // ========================================================================

    @Test
    fun getPlanPrice_reportsZeroForAFreePlan_andTheRealPriceOtherwise() = runTest {
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"id": 10, "name": "Owner",   "price": 0.0,  "enabled": true},
                      {"id": 6,  "name": "Economy", "price": 35.0, "enabled": true}
                    ]
                    """.trimIndent()
                )
        )

        assertEquals("A free plan must report 0.0, not null", 0.0, gateway.getPlanPrice("10")!!, 0.001)
        assertEquals(35.0, gateway.getPlanPrice("6")!!, 0.001)
    }

    @Test
    fun getPlanPrice_returnsNullForAnUnknownPlan() = runTest {
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"id": 10, "name": "Owner", "price": 0.0, "enabled": true}]""")
        )

        assertNull("An unknown plan id must be null, never a fabricated number", gateway.getPlanPrice("999"))
        assertNull("A blank plan id must be null", gateway.getPlanPrice(""))
    }

    @Test
    fun listPackages_mapsRealSamPlansWithTheirPrices_forTheChangePlanDialog() = runTest {
        // The change-plan dialog used to fall back to hardcoded demo plans whenever the package
        // list was empty, which is what put a fabricated 40,000 IQD "Economy" on screen.
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"id": 6,  "name": "Economy", "price": 20000.0, "enabled": true},
                      {"id": 10, "name": "Owner",   "price": 0.0,     "enabled": true}
                    ]
                    """.trimIndent()
                )
        )

        val packages = gateway.listPackages()

        assertEquals("Both real plans must be offered", 2, packages.size)
        val owner = packages.single { it.accountName == "Owner" }
        val economy = packages.single { it.accountName == "Economy" }
        assertEquals(10, owner.accountIndex)
        assertEquals("A free plan must carry price 0.0, not a fallback", 0.0, owner.price!!, 0.001)
        assertEquals(6, economy.accountIndex)
        assertEquals(20000.0, economy.price!!, 0.001)
    }

    @Test
    fun getCurrencyLabel_readsTheCurrencySammReports_insteadOfAssumingOne() = runTest {
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "version": "5.2.4",
                      "settings": [
                        {"key": "currency_code",   "value": "IQD", "type": "string"},
                        {"key": "currency_symbol", "value": "IQD", "type": "string"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        assertEquals("IQD", gateway.getCurrencyLabel())
    }

    @Test
    fun changePlan_neverRenewsItOnlyChangesThePlan() = runTest {
        // The app offers "Change Package", which must change the plan and leave the expiry alone.
        // AssignPlanRequest previously omitted extend_expiration / keep_expiration / when /
        // invoice_mode, so the server decided whether the subscriber got renewed as a side effect.
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1, "items": [{"id": 326, "username": "admin@sacx"}]}""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 326, "username": "admin@sacx", "plan_id": 6, "plan_name": "Economy"}""")
        )

        assertTrue(gateway.changePlan("admin@sacx", "6").isApplied)

        mockServer.takeRequestOrFail("username lookup")
        val body = mockServer.takeRequestOrFail("assign-plan").body.readUtf8()
        assertTrue("extend_expiration must be explicitly false, body=$body", body.contains("\"extend_expiration\":false"))
        assertTrue("keep_expiration must be explicitly true, body=$body", body.contains("\"keep_expiration\":true"))
        assertTrue("when must be explicitly 'now', body=$body", body.contains("\"when\":\"now\""))
        assertTrue("invoice_mode must be explicitly 'none', body=$body", body.contains("\"invoice_mode\":\"none\""))
        assertTrue("generate_invoice must stay false, body=$body", body.contains("\"generate_invoice\":false"))
    }

    /** Drains queued requests and returns the PATCH that writes the new expiry. */
    private fun patchRequest(): RecordedRequest {
        var patch: RecordedRequest? = null
        repeat(10) {
            val r = mockServer.takeRequest(2, TimeUnit.SECONDS) ?: return patch!!
            if (r.method == "PATCH") patch = r
        }
        return patch!!
    }

    @Test
    fun extendSubscriberByDays_setsAnAbsoluteExpiryThatIsNdaysPastTheCurrentOne() = runTest {
        // /renew pushes the expiry forward by the PLAN's duration (1 month) and accepts no day
        // count. Extending by an exact number of days is done by setting an absolute expiry, which
        // the API documents as the only way to control it.
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1, "items": [{"id": 326, "username": "admin@sacx"}]}""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id": 326, "username": "admin@sacx", "status": "active",
                        "expiration_date": "2026-10-20T00:00:00+00:00"}""".trimIndent()
                )
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 326, "username": "admin@sacx", "status": "active"}""")
        )

        // 2026-10-10T00:00:00Z, well before the 2026-10-20 expiry.
        val nowMs = 1_791_590_400_000L
        val result = gateway.extendSubscriberByDays("admin@sacx", 5, nowMs = nowMs)

        assertTrue("Extension must be applied, got $result", result.isApplied)
        val request = patchRequest()
        assertEquals("/api/v1/customers/326", request.path)
        val body = request.body.readUtf8()
        assertTrue(
            "2026-10-25 00:00Z is 03:00 in Baghdad, so +5 days is 2026-10-25 03:00, body=$body",
            body.contains("\"2026-10-25 03:00\"")
        )
        // Probed live against SAMM: it accepts ONLY a naive datetime. "Z", "+00:00" and "+03:00"
        // are all rejected with "expiration_date must be ISO 8601 or 'YYYY-MM-DD HH:MM'".
        assertFalse(
            "SAMM rejects any timezone designator, body=$body",
            Regex("""\"expiration_date\":\"[^\"]*(Z|[+-]\d{2}:?\d{2})[^\"]*\"""").containsMatchIn(body)
        )
    }

    @Test
    fun extendSubscriberByDays_neverShortensAnAlreadyExpiredSubscriber() = runTest {
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1, "items": [{"id": 326, "username": "admin@sacx"}]}""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id": 326, "username": "admin@sacx", "status": "expired",
                        "expiration_date": "2020-01-01T00:00:00+00:00"}""".trimIndent()
                )
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 326, "username": "admin@sacx", "status": "active"}""")
        )

        // 2026-10-10T00:00:00Z. Somebody who lapsed in 2020 lands on 2026-10-11, not 2020-01-02.
        val nowMs = 1_791_590_400_000L
        gateway.extendSubscriberByDays("admin@sacx", 1, nowMs = nowMs)

        val body = patchRequest().body.readUtf8()
        assertTrue(
            "A lapsed subscriber must be brought forward to now+1 day in Baghdad, body=$body",
            body.contains("\"2026-10-11 03:00\"")
        )
    }

    @Test
    fun extendSubscriberByDays_rejectsAUsernameTheProviderDoesNotCarryInsteadOfPretending() = runTest {
        // The extension is addressed by username. If SAMM does not carry that username there is no
        // customer to extend, and guessing an id would write somebody else's expiry. It must fail
        // loudly, naming the value, and dispatch nothing.
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )
        val requestsBefore = mockServer.requestCount

        val thrown = try {
            gateway.extendSubscriberByDays("absent@sacx", 3)
            null
        } catch (e: SasGatewayException) {
            e
        }

        assertNotNull("A username the provider does not carry must be rejected", thrown)
        assertTrue(
            "The message must name the offending value, got: ${thrown?.message}",
            thrown?.message?.contains("absent@sacx") == true
        )
        assertEquals(
            "Only the lookup may reach the provider; no expiry may be written",
            1,
            mockServer.requestCount - requestsBefore
        )
    }

    @Test
    fun getCurrencyLabel_returnsNullWhenSammReportsNone() = runTest {
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"version": "5.2.4", "settings": []}""")
        )

        assertNull("No reported currency must stay null, never a hardcoded one", gateway.getCurrencyLabel())
    }

    @Test
    fun updateDisplayName_splitsTheDisplayNameAcrossSammFirstAndLastName() = runTest {
        // The app offers rename for every provider; without a SAMM implementation it fell
        // through to the EarthLink gateway and wrote the SAMM subscriber id to the wrong ISP.
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1, "items": [{"id": 326, "username": "admin@sacx"}]}""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 326, "username": "admin@sacx", "status": "active"}""")
        )

        assertTrue(gateway.updateDisplayName("admin@sacx", "Ahmad Karim").isApplied)

        mockServer.takeRequestOrFail("username lookup")
        val recorded = mockServer.takeRequestOrFail("rename")
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/customers/326", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue("firstname must carry the first part, body=$body", body.contains("\"firstname\":\"Ahmad\""))
        assertTrue("lastname must carry the rest, body=$body", body.contains("\"lastname\":\"Karim\""))
        assertFalse("The username must never be rewritten by a rename, body=$body", body.contains("username"))
    }

    @Test
    fun updateDisplayName_aSingleWordNameGoesToFirstnameAlone() = runTest {
        val gateway = SammGatewayImpl(apiService)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1, "items": [{"id": 326, "username": "admin@sacx"}]}""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 326, "username": "admin@sacx", "status": "active"}""")
        )

        assertTrue(gateway.updateDisplayName("admin@sacx", "Prince").isApplied)

        mockServer.takeRequestOrFail("username lookup")
        val body = mockServer.takeRequestOrFail("rename").body.readUtf8()
        assertTrue("firstname must carry the whole name, body=$body", body.contains("\"firstname\":\"Prince\""))
        assertFalse("lastname must be omitted when absent, body=$body", body.contains("lastname"))
    }

}
