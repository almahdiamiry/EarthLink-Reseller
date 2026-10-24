package com.example.core.network.samm

import com.example.core.model.SasProviders
import com.example.core.network.SasAuthException
import com.example.core.network.SasBusinessException
import com.example.core.network.SasGatewayException
import com.example.core.network.SasInconclusiveException
import com.example.core.network.SasNotFoundException
import com.example.core.network.SasOperationResult
import com.example.core.network.SasVerificationOutcome
import kotlinx.coroutines.test.runTest
import com.example.core.network.samm.takeRequestOrFail
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
 *    Every mutation but createSubscriber is username-keyed: it resolves the customer id from the username first.
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

    /**
     * Enqueues the username -> customer id lookup every mutation performs first. The mutation seam is
     * username-keyed: the gateway resolves the customer id from the username, so a mutation test must
     * supply the directory row that resolution reads.
     */
    private fun enqueueCustomerLookup(username: String, id: Int) {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "total": 1,
                      "items": [
                        { "id": $id, "username": "$username" }
                      ]
                    }
                    """.trimIndent()
                )
        )
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
        val recorded = mockServer.takeRequestOrFail()
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

        val recorded = mockServer.takeRequestOrFail()
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
        val recorded = mockServer.takeRequestOrFail()
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
     * Claim: When pageSize > 1000 and total > 1000, searchSubscribers automatically pages
     *        through SAMM API chunks and returns the combined roster up to total/pageSize.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Page 1 returns 1000 items with total=1002; page 2 returns 2 items.
     *        Combined result contains exactly 1002 items and verifies both wire requests with correct offset.
     */
    @Test
    fun searchSubscribers_largePage_pagesAcrossChunksUntilTotal() = runTest {
        val page1Items = (1..1000).joinToString(",") { i ->
            """{"id": $i, "username": "user_$i", "firstname": "User", "lastname": "$i", "status": "active"}"""
        }
        val page2Items = """{"id": 1001, "username": "user_1001", "firstname": "User", "lastname": "1001", "status": "active"},{"id": 1002, "username": "user_1002", "firstname": "User", "lastname": "1002", "status": "active"}"""

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1002, "items": [$page1Items]}""")
        )
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 1002, "items": [$page2Items]}""")
        )

        val results = gateway.searchSubscribers(query = "", page = 1, pageSize = 5000)

        assertEquals(1002, results.size)
        assertEquals("user_1", results.first().username)
        assertEquals("user_1002", results.last().username)

        val req1 = mockServer.takeRequestOrFail("page 1")
        assertTrue(req1.path!!.contains("limit=1000"))
        assertTrue(req1.path!!.contains("offset=0"))

        val req2 = mockServer.takeRequestOrFail("page 2")
        assertTrue(req2.path!!.contains("limit=1000"))
        assertTrue(req2.path!!.contains("offset=1000"))
    }

/**
     * Claim: getSubscriber resolves a username through the directory and reads the detail of THAT
     *        customer. The argument is never used as a customer id.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: The search body is a literal row {id: 379, username: "sub_lab_01"}. The only
     *        correct detail path is therefore /api/v1/customers/379, derived from the search response
     *        and not from the argument.
     */
    @Test
    fun getSubscriber_resolvesUsernameThenReadsThatCustomer() = runTest {
        enqueueCustomerLookup("sub_lab_01", 379)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 379,
                      "username": "sub_lab_01",
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

        val subscriber = gateway.getSubscriber("sub_lab_01")

        assertNotNull(subscriber)
        assertEquals("379", subscriber!!.subscriberId)
        assertEquals("sub_lab_01", subscriber.username)
        assertEquals("Karim", subscriber.displayName)
        assertEquals("active", subscriber.status)
        assertEquals("3", subscriber.planId)
        assertEquals("+9647700000000", subscriber.phone)

        val search = mockServer.takeRequestOrFail()
        assertTrue(
            "Resolution must go through the case-insensitive ?search= filter. Actual: ${search.path}",
            search.path!!.contains("search=sub_lab_01")
        )
        val detail = mockServer.takeRequestOrFail()
        assertEquals("GET", detail.method)
        assertEquals("/api/v1/customers/379", detail.path)
    }

    /**
     * Claim: getSubscriber never reinterprets its argument as a customer id, even a numeric one. An
     *        account's cached customer id is stale BY DESIGN after an ISP move.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: The literal username "909090" is served by the literal id 31415. Dispatching
     *        /api/v1/customers/909090 would mean the argument was treated as an id.
     */
    @Test
    fun getSubscriber_numericUsernameIsNeverUsedAsACustomerId() = runTest {
        enqueueCustomerLookup("909090", 31415)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "id": 31415, "username": "909090", "status": "active" }""")
        )

        val subscriber = gateway.getSubscriber("909090")

        mockServer.takeRequestOrFail()
        val detail = mockServer.takeRequestOrFail()
        assertEquals("/api/v1/customers/31415", detail.path)
        assertEquals("31415", subscriber?.subscriberId)
    }

    /**
     * Claim: getSubscriber returns null without a detail call when the provider definitively does
     *        not carry the username.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: An empty search result is the only outcome consistent with absence, and
     *        exactly one request may have been made.
     */
    @Test
    fun getSubscriber_usernameNotCarried_returnsNullWithoutDetailCall() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "total": 0, "items": [] }""")
        )

        val subscriber = gateway.getSubscriber("absent_user")

        assertNull(subscriber)
        assertEquals(1, mockServer.requestCount)
    }

    /**
     * Claim: getSubscriber returns immediately from directory row when it contains status, avoiding
     *        redundant getCustomer HTTP roundtrip.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Exactly 1 HTTP request (/customers?search=) is made; 0 /customers/{id} calls.
     */
    @Test
    fun getSubscriber_whenDirectoryRowHasStatus_returnsImmediatelyWithoutGetCustomer() = runTest {
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
                          "id": 555,
                          "username": "fast_user",
                          "firstname": "Fast",
                          "lastname": "Subscriber",
                          "mobile": "+9647701112233",
                          "status": "active",
                          "plan_id": 2,
                          "plan_name": "Ultra 50M",
                          "expiration_date": "2026-11-15T12:00:00Z"
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val subscriber = gateway.getSubscriber("fast_user")

        assertNotNull(subscriber)
        assertEquals("555", subscriber!!.subscriberId)
        assertEquals("fast_user", subscriber.username)
        assertEquals("Fast Subscriber", subscriber.displayName)
        assertEquals("active", subscriber.status)
        assertEquals("2", subscriber.planId)
        assertEquals("Ultra 50M", subscriber.planName)
        assertEquals("+9647701112233", subscriber.phone)

        assertEquals("Only 1 request (search) should be made, avoiding getCustomer", 1, mockServer.requestCount)
        val search = mockServer.takeRequestOrFail()
        assertTrue(search.path!!.contains("search=fast_user"))
    }

    /**
     * Claim: getSubscriber returns null when the provider answers HTTP 404 for the resolved customer.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 404 response yields null return value without throwing exception.
     */
    @Test
    fun getSubscriber_on404_returnsNull() = runTest {
        enqueueCustomerLookup("ghost_user", 9999)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Customer not found"}""")
        )

        val subscriber = gateway.getSubscriber("ghost_user")

        assertNull(subscriber)
    }

    /**
     * Claim: a FAILED lookup is never reported as "no such subscriber". An HTTP 500 on the search
     *        propagates as a typed SasGatewayException instead of degrading to a silent null.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: The mocked body carries the literal message "directory exploded", which
     *        must reach the caller rather than being flattened into a null "not found". A 5xx maps to
     *        SasInconclusiveException by the seam's own error taxonomy, so the assertion is written
     *        against the SasGatewayException contract and the literal message, not one subclass.
     */
    @Test
    fun getSubscriber_searchFails_propagatesInsteadOfReportingAbsence() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "directory exploded"}""")
        )

        try {
            val subscriber = gateway.getSubscriber("sub_lab_01")
            fail("Expected the lookup failure to propagate, not to report absence. Got: $subscriber")
        } catch (e: SasGatewayException) {
            assertTrue(
                "The provider's own reason must survive: ${e.message}",
                e.message?.contains("directory exploded") == true
            )
        }
    }

    /**
     * Claim: listPlans queries GET /api/v1/plans and returns parsed PlanResponse list.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: GET /api/v1/plans returns plan items array.
     */
    @Test
    fun listPlans_fetchesAndReturnsPlans() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {
                        "id": 10,
                        "name": "Standard 50M",
                        "price": 35000.0,
                        "speed_download": 50000000,
                        "speed_upload": 10000000
                      }
                    ]
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

        val recorded = mockServer.takeRequestOrFail()
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
     * Claim: suspendSubscriber resolves the username to a customer id, then dispatches
     *        POST /api/v1/customers/{id}/suspend and returns Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Directory row user100 -> 100 is a literal; POST /api/v1/customers/100/suspend
     *        returns HTTP 200 -> Applied. The dispatched id comes from the lookup, not from the caller.
     */
    @Test
    fun suspendSubscriber_sendsPostAndReturnsApplied() = runTest {
        enqueueCustomerLookup("user100", 100)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "suspended"}""")
        )

        val result = gateway.suspendSubscriber("user100")

        assertTrue(result is SasOperationResult.Applied)
        val lookup = mockServer.takeRequestOrFail("lookup")
        assertTrue(lookup.path!!.contains("search=user100"))
        val recorded = mockServer.takeRequestOrFail("suspend")
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/suspend", recorded.path)
    }

    /**
     * Claim: suspendSubscriber fails closed when SAMM does not carry the username: nothing is
     *        dispatched, so no other customer can be suspended by mistake.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: An empty directory row set -> SasNotFoundException, lookup only, zero
     *        suspend requests.
     */
    @Test
    fun suspendSubscriber_unresolvableUsername_throwsSasNotFoundException() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"total": 0, "items": []}""")
        )
        val requestsBefore = mockServer.requestCount

        try {
            gateway.suspendSubscriber("absent_user")
            fail("Expected SasNotFoundException")
        } catch (e: SasNotFoundException) {
            assertTrue(e.message?.contains("absent_user") == true)
        }
        assertEquals(1, mockServer.requestCount - requestsBefore)
    }

    /**
     * Claim: activateSubscriber resolves the username to a customer id, then dispatches
     *        POST /api/v1/customers/{id}/activate and returns Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Directory row user100 -> 100 is a literal; POST /api/v1/customers/100/activate
     *        returns HTTP 200 -> Applied.
     */
    @Test
    fun activateSubscriber_sendsPostAndReturnsApplied() = runTest {
        enqueueCustomerLookup("user100", 100)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "active"}""")
        )

        val result = gateway.activateSubscriber("user100")

        assertTrue(result is SasOperationResult.Applied)
        mockServer.takeRequestOrFail("lookup")
        val recorded = mockServer.takeRequestOrFail("activate")
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/activate", recorded.path)
    }

    /**
     * Claim: renewSubscriber resolves the username, then dispatches POST /api/v1/customers/{id}/renew
     *        with generate_invoice=false and returns Applied with new expiration date evidence.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST /api/v1/customers/100/renew?generate_invoice=false returns HTTP 200
     *                      with new_expiration="2026-12-01T00:00:00Z" -> Applied(evidence="2026-12-01T00:00:00Z").
     */
    @Test
    fun renewSubscriber_sendsPostWithGenerateInvoiceFalseAndReturnsAppliedWithNewExpiration() = runTest {
        enqueueCustomerLookup("user100", 100)
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

        val result = gateway.renewSubscriber("user100")

        assertTrue(result is SasOperationResult.Applied)
        val applied = result as SasOperationResult.Applied
        assertEquals("2026-12-01T00:00:00Z", applied.evidence)

        mockServer.takeRequestOrFail("lookup")
        val recorded = mockServer.takeRequestOrFail("renew")
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/renew?generate_invoice=false", recorded.path)
    }

    /**
     * Claim: changePlan resolves the username and dispatches POST /api/v1/customers/{id}/assign-plan
     *        with plan_id integer and generate_invoice=false, returning Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: POST body has plan_id=15, generate_invoice=false -> Applied.
     */
    @Test
    fun changePlan_sendsPostWithCorrectBodyAndReturnsApplied() = runTest {
        enqueueCustomerLookup("user100", 100)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "plan_id": 15, "plan_name": "Premium 100M"}""")
        )

        val result = gateway.changePlan("user100", "15")

        assertTrue(result is SasOperationResult.Applied)
        mockServer.takeRequestOrFail("lookup")
        val recorded = mockServer.takeRequestOrFail("assign-plan")
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/customers/100/assign-plan", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""plan_id":15"""))
        assertTrue(body.contains(""""generate_invoice":false"""))
    }

    /**
     * Claim: changePlan throws SasBusinessException when newPlanId is not a valid integer, and does
     *        not dispatch the plan change.
     * Seam: JVM Unit Test.
     * Independent Oracle: Throws SasBusinessException with message "Invalid SAMM plan ID integer: 'abc'".
     */
    @Test
    fun changePlan_nonIntegerPlanId_throwsSasBusinessException() = runTest {
        enqueueCustomerLookup("user100", 100)

        try {
            gateway.changePlan("user100", "abc")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertEquals("Invalid SAMM plan ID integer: 'abc'", e.errorMessage)
        }
        assertEquals(1, mockServer.requestCount)
    }

    /**
     * Claim: changePassword resolves the username and dispatches PATCH /api/v1/customers/{id} with
     *        password in body, never stores or leaks password, and returns Applied.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: PATCH /api/v1/customers/100 with password="super_secret_99" -> Applied.
     */
    @Test
    fun changePassword_sendsPatchWithPasswordAndReturnsApplied() = runTest {
        enqueueCustomerLookup("user100", 100)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100"}""")
        )

        val result = gateway.changePassword("user100", "super_secret_99")

        assertTrue(result is SasOperationResult.Applied)
        mockServer.takeRequestOrFail("lookup")
        val recorded = mockServer.takeRequestOrFail("password patch")
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
        val recorded = mockServer.takeRequestOrFail()
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
     * Claim: INV-01, INV-04: verifyOperationOutcome with null or blank baseline strictly returns INCONCLUSIVE.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: baselineEvidence = null -> INCONCLUSIVE without network dispatch.
     */
    @Test
    fun verifyRenewal_nullBaseline_returnsInconclusive() = runTest {
        val outcome = gateway.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "100",
            baselineEvidence = null
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: INV-03, INV-04: REFILL alias with username target resolves id via directory and verifies outcome.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Directory lookup resolves "user100" -> 100, then getCustomer(100) expiry advanced -> VERIFIED_SUCCESS.
     */
    @Test
    fun verifyRenewal_refillAliasWithUsername_resolvesIdAndVerifiesSuccess() = runTest {
        // First request: findDirectoryRow(search = "user100")
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "items": [
                        {
                          "id": 100,
                          "username": "user100",
                          "firstname": "Test",
                          "lastname": "User"
                        }
                      ],
                      "total": 1
                    }
                    """.trimIndent()
                )
        )
        // Second request: getCustomer(100)
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
            operationType = "REFILL",
            targetIdentifier = "user100",
            baselineEvidence = "2026-10-01T00:00:00Z"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: Instant-normalized comparison: Different timezone formatting of the SAME instant evaluates as unchanged -> VERIFIED_FAILURE.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Current ("2026-10-01T03:00:00+03:00") is exact same epoch millisecond as baseline ("2026-10-01T00:00:00Z") -> VERIFIED_FAILURE.
     */
    @Test
    fun verifyRenewal_instantNormalizedDifferentFormatsSameInstant_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "id": 100,
                      "username": "user100",
                      "expiration_date": "2026-10-01T03:00:00+03:00"
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
     * Claim: When username is definitively not found in directory during recovery, verifyOperationOutcome returns VERIFIED_FAILURE.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Directory returns empty items for username -> VERIFIED_FAILURE.
     */
    @Test
    fun verifyRenewal_usernameNotFoundInDirectory_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "items": [],
                      "total": 0
                    }
                    """.trimIndent()
                )
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "nonexistent_user",
            baselineEvidence = "2026-10-01T00:00:00Z"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
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
     * Claim: verifyOperationOutcome for EXTEND returns VERIFIED_FAILURE when customer does not exist (HTTP 404).
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 404 -> VERIFIED_FAILURE (negative evidence doctrine).
     */
    @Test
    fun verifyExtend_whenCustomerDoesNotExist_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Customer 100 not found"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "EXTEND",
            targetIdentifier = "100",
            baselineEvidence = null
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for EXTEND returns VERIFIED_FAILURE when username is not found in directory.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Empty directory items for username -> VERIFIED_FAILURE.
     */
    @Test
    fun verifyExtend_whenUsernameNotFoundInDirectory_returnsVerifiedFailure() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"items": [], "total": 0}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "EXTEND",
            targetIdentifier = "missing_user",
            baselineEvidence = null
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for EXTEND returns INCONCLUSIVE when customer exists, because extension
     *        cannot be positively confirmed from read state alone (negative evidence doctrine).
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: Customer exists -> INCONCLUSIVE (avoids phantom debt / premature release).
     */
    @Test
    fun verifyExtend_whenCustomerExists_returnsInconclusive() = runTest {
        enqueueCustomerLookup("user100", 100)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 100, "username": "user100", "status": "active"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "EXTEND",
            targetIdentifier = "user100",
            baselineEvidence = null
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for EXTEND returns INCONCLUSIVE when a network/server error occurs.
     * Seam: JVM MockWebServer Wire Contract.
     * Independent Oracle: HTTP 500 error -> INCONCLUSIVE.
     */
    @Test
    fun verifyExtend_whenTransportFails_returnsInconclusive() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Internal server error"}""")
        )

        val outcome = gateway.verifyOperationOutcome(
            operationType = "EXTEND",
            targetIdentifier = "100",
            baselineEvidence = null
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
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
        enqueueCustomerLookup("user999", 999)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Customer 999 not found"}""")
        )

        try {
            gateway.renewSubscriber("user999")
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
        enqueueCustomerLookup("user100", 100)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message": "Internal RADIUS sync error"}""")
        )

        try {
            gateway.suspendSubscriber("user100")
            fail("Expected SasInconclusiveException")
        } catch (e: SasInconclusiveException) {
            assertEquals(500, e.statusCode)
            assertEquals("Internal RADIUS sync error", e.message)
        }
    }
}
