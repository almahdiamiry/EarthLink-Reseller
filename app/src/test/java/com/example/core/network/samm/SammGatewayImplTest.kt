package com.example.core.network.samm

import com.example.core.model.SasProviders
import com.example.core.network.SasAuthException
import com.example.core.network.SasBusinessException
import com.example.core.network.SasInconclusiveException
import com.example.core.network.SasNotFoundException
import com.example.core.network.SasOperationResult
import com.example.core.network.SasVerificationOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * SammGatewayImplTest
 *
 * Comprehensive JVM unit test suite verifying [SammGatewayImpl] fulfilling [SasGateway] contract
 * for the ALAMIRY provider against SAMM 5.1.15 REST API wire specifications:
 * 1. Read operations: searchSubscribers (exact username vs search param), getSubscriber (integer ID normalization, 404 handling).
 * 2. Mutation operations: createSubscriber (activation), renewSubscriber, suspendSubscriber, activateSubscriber, changePlan, changePassword.
 * 3. Verification oracle: verifyOperationOutcome for ACTIVATION, RENEWAL, TOGGLE_ACTIVE, CHANGE_PLAN, PASSWORD, and unknown operations.
 * 4. Error translation: 401 -> SasAuthException, 404 -> SasNotFoundException, 422 -> SasBusinessException, 500 -> SasInconclusiveException.
 * 5. Ingress/Egress domain normalization and fail-closed validation of non-integer customer/plan IDs.
 *
 * Tier: JVM headless unit test with MockWebServer wire mocking.
 * Standard: Core Triad documented on all tests (Claim, Seam, Independent Oracle).
 */
class SammGatewayImplTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var gateway: SammGatewayImpl
    private val testToken = "samm_test_secret_token_xyz"

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val baseUrl = mockServer.url("/").toString()
        val client = SammNetworkClient(baseUrl, testToken)
        gateway = SammGatewayImpl(client)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    // ========================================================================
    // 1. Provider Identity Contract
    // ========================================================================

    /**
     * Claim: SammGatewayImpl exposes providerName strictly equal to SasProviders.ALAMIRY ("ALAMIRY").
     * Seam: JVM Unit Test.
     * Independent Oracle: Constant equality assertion against SasProviders.ALAMIRY.
     */
    @Test
    fun providerName_isStrictlyAlamiry() {
        assertEquals("ALAMIRY", gateway.providerName)
        assertEquals(SasProviders.ALAMIRY, gateway.providerName)
    }

    // ========================================================================
    // 2. Connectivity Check Contracts
    // ========================================================================

    /**
     * Claim: checkConnection calls GET /api/v1/me and returns true when provider responds with HTTP 200.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 200 response results in Boolean true.
     */
    @Test
    fun checkConnection_onHttp200_returnsTrue() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"token_id": 1, "name": "pos_admin"}""")
        )

        val isConnected = gateway.checkConnection()

        assertTrue("Expected checkConnection to return true on HTTP 200", isConnected)
        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/me", recorded.path)
    }

    /**
     * Claim: checkConnection returns false when provider responds with HTTP 401 Unauthorized.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 401 response results in Boolean false without throwing uncaught exception.
     */
    @Test
    fun checkConnection_onHttp401_returnsFalse() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Invalid token"}""")
        )

        val isConnected = gateway.checkConnection()

        assertFalse("Expected checkConnection to return false on HTTP 401", isConnected)
    }

    // ========================================================================
    // 3. Read Operations & ID Normalization Contracts
    // ========================================================================

    /**
     * Claim: searchSubscribers uses exact username query parameter for single-word queries,
     *        normalizes wire integer ID (1042) to domain String ("1042"), and correctly maps all view fields.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Query path matches `/api/v1/customers?username=baghdad_user01&page=1&per_page=20`,
     *                      and returned SasSubscriberView contains subscriberId="1042", displayName="Ali Hassan",
     *                      planId="5", phone="+9647801234567".
     */
    @Test
    fun searchSubscribers_singleWordQuery_usesUsernameParameterAndNormalizesId() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "total": 1,
                      "items": [
                        {
                          "id": 1042,
                          "username": "baghdad_user01",
                          "firstname": "Ali",
                          "lastname": "Hassan",
                          "mobile": "+9647801234567",
                          "status": "active",
                          "plan_id": 5,
                          "plan_name": "50Mbps Premium",
                          "expiration_date": "2026-11-01T00:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val results = gateway.searchSubscribers("baghdad_user01")

        assertEquals(1, results.size)
        val subscriber = results[0]
        assertEquals("1042", subscriber.subscriberId)
        assertEquals("baghdad_user01", subscriber.username)
        assertEquals("Ali Hassan", subscriber.displayName)
        assertEquals("active", subscriber.status)
        assertEquals("5", subscriber.planId)
        assertEquals("50Mbps Premium", subscriber.planName)
        assertEquals("2026-11-01T00:00:00Z", subscriber.expiresAt)
        assertEquals("+9647801234567", subscriber.phone)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.contains("username=baghdad_user01"))
        assertFalse(recorded.path!!.contains("search="))
    }

    /**
     * Claim: searchSubscribers uses substring search query parameter when query contains whitespace.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Query path matches `/api/v1/customers?search=Ali%20Hassan&page=1&per_page=20`.
     */
    @Test
    fun searchSubscribers_multiWordQuery_usesSearchParameter() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )

        val results = gateway.searchSubscribers("Ali Hassan")

        assertTrue(results.isEmpty())
        val recorded = mockServer.takeRequest()
        assertTrue(recorded.path!!.contains("search=Ali%20Hassan") || recorded.path!!.contains("search=Ali+Hassan"))
    }

    /**
     * Claim: searchSubscribers returns empty list when provider returns HTTP 404 for search.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 404 response yields empty List<SasSubscriberView>.
     */
    @Test
    fun searchSubscribers_on404_returnsEmptyList() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "No subscribers found"}""")
        )

        val results = gateway.searchSubscribers("unknown_user")

        assertTrue(results.isEmpty())
    }

    /**
     * Claim: getSubscriber fetches customer by numeric ID, formats integer path parameter `/customers/{id}`,
     *        and normalizes wire integer ID into domain String.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Path is `/api/v1/customers/2055`, returned view has subscriberId="2055".
     */
    @Test
    fun getSubscriber_validIntegerId_normalizesIdAndMapsFields() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 2055,
                      "username": "user2055",
                      "firstname": "Karim",
                      "lastname": null,
                      "mobile": "+9647700000000",
                      "status": "active",
                      "plan_id": 3,
                      "plan_name": "Basic 20M",
                      "expiration_date": "2026-10-31T23:59:59Z"
                    }
                    """.trimIndent()
                )
        )

        val subscriber = gateway.getSubscriber("2055")

        assertNotNull(subscriber)
        assertEquals("2055", subscriber!!.subscriberId)
        assertEquals("user2055", subscriber.username)
        assertEquals("Karim", subscriber.displayName)
        assertEquals("active", subscriber.status)
        assertEquals("3", subscriber.planId)
        assertEquals("+9647700000000", subscriber.phone)

        val recorded = mockServer.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/customers/2055", recorded.path)
    }

    /**
     * Claim: getSubscriber returns null when provider responds with HTTP 404 Not Found.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 404 response yields null return value without throwing exception.
     */
    @Test
    fun getSubscriber_on404_returnsNull() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Customer not found"}""")
        )

        val subscriber = gateway.getSubscriber("9999")

        assertNull(subscriber)
    }

    /**
     * Claim: getSubscriber throws SasBusinessException immediately when subscriberId is not a valid integer,
     *        preventing invalid wire requests from reaching the provider.
     * Seam: JVM Unit Test.
     * Independent Oracle: Throws SasBusinessException with exact message "Invalid SAMM customer ID integer: 'not_an_int'".
     */
    @Test
    fun getSubscriber_invalidNonIntegerId_throwsSasBusinessException() = runTest {
        try {
            gateway.getSubscriber("not_an_int")
            fail("Expected SasBusinessException for non-integer subscriber ID")
        } catch (e: SasBusinessException) {
            assertEquals("Invalid SAMM customer ID integer: 'not_an_int'", e.errorMessage)
            assertNull(e.statusCode)
        }
        assertEquals("No network requests should have been dispatched", 0, mockServer.requestCount)
    }

    /**
     * Claim: listPlans queries GET /api/v1/plans and returns parsed PlanResponse list.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: GET /api/v1/plans?page=1&per_page=100 returns plan items.
     */
    @Test
    fun listPlans_fetchesAndReturnsPlans() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "total": 1,
                      "items": [
                        {
                          "id": 10,
                          "name": "Standard 50M",
                          "price": 35000.0,
                          "speed_download": 50000000,
                          "speed_upload": 10000000
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val plans = gateway.listPlans()

        assertEquals(1, plans.size)
        assertEquals(10, plans[0].id)
        assertEquals("Standard 50M", plans[0].name)
    }

    // ========================================================================
    // 4. Mutation Operations Contracts
    // ========================================================================

    /**
     * Claim: createSubscriber dispatches POST /api/v1/customers with generate_invoice=false,
     *        numeric planId, and returns SasOperationResult.Applied with created customer ID as evidence.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST body contains username, password, firstname, lastname, plan_id=10,
     *                      generate_invoice=false; result is Applied("3001").
     */
    @Test
    fun createSubscriber_activation_sendsCorrectWirePayloadAndReturnsApplied() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 3001, "username": "new_user", "plan_id": 10}""")
        )

        val result = gateway.createSubscriber(
            username = "new_user",
            planId = "10",
            password = "secure_password",
            firstName = "John",
            lastName = "Doe",
            phone = "+9647801112233"
        )

        assertTrue(result is SasOperationResult.Applied)
        val applied = result as SasOperationResult.Applied
        assertEquals("3001", applied.evidence)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""username":"new_user""""))
        assertTrue(body.contains(""""plan_id":10"""))
        assertTrue(body.contains(""""generate_invoice":false"""))
        assertTrue(body.contains(""""firstname":"John""""))
        assertTrue(body.contains(""""lastname":"Doe""""))
        assertTrue(body.contains(""""mobile":"+9647801112233""""))
    }

    /**
     * Claim: createSubscriber throws SasBusinessException when planId cannot be parsed to integer.
     * Seam: JVM Unit Test.
     * Independent Oracle: Throws SasBusinessException with message "Invalid SAMM plan ID integer: 'bad_plan'".
     */
    @Test
    fun createSubscriber_nonIntegerPlanId_throwsSasBusinessException() = runTest {
        try {
            gateway.createSubscriber(
                username = "new_user",
                planId = "bad_plan",
                password = "pwd",
                firstName = "F",
                lastName = "L"
            )
            fail("Expected SasBusinessException for invalid plan ID")
        } catch (e: SasBusinessException) {
            assertEquals("Invalid SAMM plan ID integer: 'bad_plan'", e.errorMessage)
        }
        assertEquals(0, mockServer.requestCount)
    }

    /**
     * Claim: suspendSubscriber dispatches POST /api/v1/customers/{id}/suspend and returns Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST /api/v1/customers/100/suspend returns HTTP 200 -> Applied.
     */
    @Test
    fun suspendSubscriber_sendsPostAndReturnsApplied() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "suspended"}""")
        )

        val result = gateway.suspendSubscriber("100")

        assertTrue(result is SasOperationResult.Applied)
        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/suspend", recorded.path)
    }

    /**
     * Claim: suspendSubscriber throws SasBusinessException when subscriberId is not a valid integer.
     * Seam: JVM Unit Test.
     * Independent Oracle: Throws SasBusinessException, zero network calls.
     */
    @Test
    fun suspendSubscriber_nonIntegerId_throwsSasBusinessException() = runTest {
        try {
            gateway.suspendSubscriber("invalid_id")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertEquals("Invalid SAMM customer ID integer: 'invalid_id'", e.errorMessage)
        }
        assertEquals(0, mockServer.requestCount)
    }

    /**
     * Claim: activateSubscriber dispatches POST /api/v1/customers/{id}/activate and returns Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST /api/v1/customers/100/activate returns HTTP 200 -> Applied.
     */
    @Test
    fun activateSubscriber_sendsPostAndReturnsApplied() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "active"}""")
        )

        val result = gateway.activateSubscriber("100")

        assertTrue(result is SasOperationResult.Applied)
        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/activate", recorded.path)
    }

    /**
     * Claim: renewSubscriber dispatches POST /api/v1/customers/{id}/renew with generate_invoice=false
     *        and returns Applied with new expiration date evidence.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST /api/v1/customers/100/renew?generate_invoice=false returns HTTP 200
     *                      with new_expiration="2026-12-01T00:00:00Z" -> Applied(evidence="2026-12-01T00:00:00Z").
     */
    @Test
    fun renewSubscriber_sendsPostWithGenerateInvoiceFalseAndReturnsAppliedWithNewExpiration() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 100,
                      "username": "user100",
                      "status": "active",
                      "new_expiration": "2026-12-01T00:00:00Z",
                      "invoice_id": null
                    }
                    """.trimIndent()
                )
        )

        val result = gateway.renewSubscriber("100")

        assertTrue(result is SasOperationResult.Applied)
        val applied = result as SasOperationResult.Applied
        assertEquals("2026-12-01T00:00:00Z", applied.evidence)

        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/renew?generate_invoice=false", recorded.path)
    }

    /**
     * Claim: changePlan dispatches POST /api/v1/customers/{id}/assign-plan with plan_id integer
     *        and generate_invoice=false, returning Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST body has plan_id=15, generate_invoice=false -> Applied.
     */
    @Test
    fun changePlan_sendsPostWithCorrectBodyAndReturnsApplied() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "plan_id": 15, "plan_name": "Premium 100M"}""")
        )

        val result = gateway.changePlan("100", "15")

        assertTrue(result is SasOperationResult.Applied)
        val recorded = mockServer.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/assign-plan", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""plan_id":15"""))
        assertTrue(body.contains(""""generate_invoice":false"""))
    }

    /**
     * Claim: changePlan throws SasBusinessException when newPlanId is not a valid integer.
     * Seam: JVM Unit Test.
     * Independent Oracle: Throws SasBusinessException with message "Invalid SAMM plan ID integer: 'abc'".
     */
    @Test
    fun changePlan_nonIntegerPlanId_throwsSasBusinessException() = runTest {
        try {
            gateway.changePlan("100", "abc")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertEquals("Invalid SAMM plan ID integer: 'abc'", e.errorMessage)
        }
        assertEquals(0, mockServer.requestCount)
    }

    /**
     * Claim: changePassword dispatches PATCH /api/v1/customers/{id} with password in body,
     *        never stores or leaks password, and returns Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: PATCH /api/v1/customers/100 with password="super_secret_99" -> Applied.
     */
    @Test
    fun changePassword_sendsPatchWithPasswordAndReturnsApplied() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100"}""")
        )

        val result = gateway.changePassword("100", "super_secret_99")

        assertTrue(result is SasOperationResult.Applied)
        val recorded = mockServer.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/customers/100", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""password":"super_secret_99""""))
    }

    // ========================================================================
    // 5. Operation Verification Oracle Contracts
    // ========================================================================

    /**
     * Claim: verifyOperationOutcome for ACTIVATION queries GET /api/v1/customers?username=<exact>
     *        and returns VERIFIED_SUCCESS when matching customer is found.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Single matching record -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyActivation_whenExactUsernameFound_returnsVerifiedSuccess() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "total": 1,
                      "items": [
                        {"id": 50, "username": "verified_user", "status": "active"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val outcome = gateway.verifyOperationOutcome("ACTIVATION", "verified_user", null)

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
        val recorded = mockServer.takeRequest()
        assertTrue(recorded.path!!.contains("username=verified_user"))
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns VERIFIED_FAILURE when username is not found.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Empty items array -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyActivation_whenUsernameNotFound_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )

        val outcome = gateway.verifyOperationOutcome("ACTIVATION", "nonexistent_user", null)

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns INCONCLUSIVE when network or server error occurs.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 500 error -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyActivation_onNetworkError_returnsInconclusive() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message": "Database query timeout"}""")
        )

        val outcome = gateway.verifyOperationOutcome("ACTIVATION", "target_user", null)

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns VERIFIED_SUCCESS when customer's expirationDate
     *        differs from baseline evidence.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: expirationDate ("2026-11-01") != baseline ("2026-10-01") -> VERIFIED_SUCCESS.
     */
    @Test
    fun verifyRenewal_whenExpirationChanged_returnsVerifiedSuccess() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 100,
                      "username": "user100",
                      "expiration_date": "2026-11-01T00:00:00Z"
                    }
                    """.trimIndent()
                )
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "100",
            baselineEvidence = "2026-10-01T00:00:00Z"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns VERIFIED_FAILURE when expirationDate equals baseline.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: expirationDate ("2026-10-01") == baseline ("2026-10-01") -> VERIFIED_FAILURE.
     */
    @Test
    fun verifyRenewal_whenExpirationUnchanged_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 100,
                      "username": "user100",
                      "expiration_date": "2026-10-01T00:00:00Z"
                    }
                    """.trimIndent()
                )
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "100",
            baselineEvidence = "2026-10-01T00:00:00Z"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns VERIFIED_FAILURE when customer is not found (404).
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 404 response -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyRenewal_on404_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Customer not found"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "100",
            baselineEvidence = "2026-10-01T00:00:00Z"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns INCONCLUSIVE when server error occurs.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 503 response -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyRenewal_onNetworkError_returnsInconclusive() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Service unavailable"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "100",
            baselineEvidence = "2026-10-01T00:00:00Z"
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for TOGGLE_ACTIVE returns VERIFIED_SUCCESS when customer status
     *        matches expected target status baseline.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: status ("active") equals baseline ("active") -> VERIFIED_SUCCESS.
     */
    @Test
    fun verifyToggleActive_whenStatusMatchesExpected_returnsVerifiedSuccess() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "active"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "TOGGLE_ACTIVE",
            targetIdentifier = "100",
            baselineEvidence = "active"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for TOGGLE_ACTIVE returns VERIFIED_FAILURE when customer status
     *        does not match expected target status baseline.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: status ("suspended") does not equal baseline ("active") -> VERIFIED_FAILURE.
     */
    @Test
    fun verifyToggleActive_whenStatusDoesNotMatch_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "suspended"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "TOGGLE_ACTIVE",
            targetIdentifier = "100",
            baselineEvidence = "active"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for CHANGE_PLAN returns VERIFIED_SUCCESS when customer's planId
     *        equals expected target planId baseline.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: planId (5) equals baseline ("5") -> VERIFIED_SUCCESS.
     */
    @Test
    fun verifyChangePlan_whenPlanMatchesExpected_returnsVerifiedSuccess() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "plan_id": 5}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "CHANGE_PLAN",
            targetIdentifier = "100",
            baselineEvidence = "5"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for CHANGE_PLAN returns VERIFIED_FAILURE when planId does not match expected.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: planId (2) does not equal baseline ("5") -> VERIFIED_FAILURE.
     */
    @Test
    fun verifyChangePlan_whenPlanDoesNotMatch_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "plan_id": 2}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "CHANGE_PLAN",
            targetIdentifier = "100",
            baselineEvidence = "5"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for PASSWORD strictly returns INCONCLUSIVE without performing network calls,
     *        because passwords are write-only on SAMM.
     * Seam: JVM Unit Test.
     * Independent Oracle: Outcome is SasVerificationOutcome.INCONCLUSIVE, zero mock server requests.
     */
    @Test
    fun verifyPassword_strictlyReturnsInconclusive() = runTest {
        val outcome = gateway.verifyOperationOutcome(
            operationType = "PASSWORD",
            targetIdentifier = "100",
            baselineEvidence = "some_password"
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
        assertEquals("Password verification must not perform network calls", 0, mockServer.requestCount)
    }

    /**
     * Claim: verifyOperationOutcome returns INCONCLUSIVE for unrecognized operation types.
     * Seam: JVM Unit Test.
     * Independent Oracle: Unknown operation name -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyUnknownOperation_returnsInconclusive() = runTest {
        val outcome = gateway.verifyOperationOutcome(
            operationType = "UNRECOGNIZED_ACTION",
            targetIdentifier = "100",
            baselineEvidence = null
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
        assertEquals(0, mockServer.requestCount)
    }

    // ========================================================================
    // 6. Error Translation Contracts
    // ========================================================================

    /**
     * Claim: HTTP 401 Unauthorized maps to SasAuthException.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Throws SasAuthException with message "Invalid or expired token".
     */
    @Test
    fun errorTranslation_http401_throwsSasAuthException() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Invalid or expired token"}""")
        )

        try {
            gateway.getSubscriber("100")
            fail("Expected SasAuthException")
        } catch (e: SasAuthException) {
            assertEquals("Invalid or expired token", e.message)
        }
    }

    /**
     * Claim: HTTP 404 Not Found on mutation operations maps to SasNotFoundException.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Throws SasNotFoundException with statusCode=404.
     */
    @Test
    fun errorTranslation_http404_onMutation_throwsSasNotFoundException() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Customer 999 not found"}""")
        )

        try {
            gateway.renewSubscriber("999")
            fail("Expected SasNotFoundException")
        } catch (e: SasNotFoundException) {
            assertEquals(404, e.statusCode)
            assertEquals("Customer 999 not found", e.message)
        }
    }

    /**
     * Claim: HTTP 422 Unprocessable Entity maps to SasBusinessException.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Throws SasBusinessException with statusCode=422.
     */
    @Test
    fun errorTranslation_http422_throwsSasBusinessException() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "detail": [
                        {
                          "loc": ["body", "plan_id"],
                          "msg": "plan not found",
                          "type": "value_error"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        try {
            gateway.createSubscriber(
                username = "test_user",
                planId = "999",
                password = "pwd",
                firstName = "F",
                lastName = "L"
            )
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertEquals(422, e.statusCode)
            assertTrue(e.errorMessage.contains("body.plan_id: plan not found"))
        }
    }

    /**
     * Claim: HTTP 500 Server Error maps to SasInconclusiveException.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Throws SasInconclusiveException with statusCode=500.
     */
    @Test
    fun errorTranslation_http500_throwsSasInconclusiveException() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message": "Internal RADIUS sync error"}""")
        )

        try {
            gateway.suspendSubscriber("100")
            fail("Expected SasInconclusiveException")
        } catch (e: SasInconclusiveException) {
            assertEquals(500, e.statusCode)
            assertEquals("Internal RADIUS sync error", e.message)
        }
    }
}
