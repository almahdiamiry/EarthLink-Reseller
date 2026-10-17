package com.example.core.network

import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.core.network.samm.SammGatewayImpl
import com.example.core.network.samm.SammNetworkClient
import com.example.core.network.samm.takeRequestOrFail
import com.example.domain.repository.EarthlinkGateway
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * SubscriberIdentityResolutionTest
 *
 * Username is the only identity both ISPs share. A provider id (a SAMM customer id, an EarthLink
 * userIndex) is local to one ISP and addresses a different customer on the other, so the six mutation
 * methods must resolve it inside the adapter that owns it and the app must never pass one across.
 *
 * Tier: JVM headless unit test. SAMM is exercised over a MockWebServer wire contract; EarthLink is
 * exercised through a recording fake gateway. No network, no Room.
 * Standard: Core Triad documented on all tests (Claim, Seam, Independent Oracle).
 */
class SubscriberIdentityResolutionTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sammGateway: SammGatewayImpl
    private val testToken = "samm_test_secret_token_xyz"

    private lateinit var earthlinkFake: RecordingEarthlinkGateway
    private lateinit var earthlinkAdapter: EarthlinkSasGatewayAdapter

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        sammGateway = SammGatewayImpl(SammNetworkClient(mockServer.url("/").toString(), testToken))

        earthlinkFake = RecordingEarthlinkGateway()
        earthlinkAdapter = EarthlinkSasGatewayAdapter(earthlinkFake)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    /**
     * Claim: SammGatewayImpl.resolveSubscriberId maps a username to that provider's own customer id.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: The id "75" is a literal in the mocked response body and the expected value
     *        is that literal. It is not recomputed from the code under test.
     */
    @Test
    fun samm_resolveSubscriberId_returnsProviderOwnedCustomerId() = runTest {
        // The provider stores this username with capitals; the app holds it lowercased.
        enqueueSammSearch(username = "Moved_User", id = 75)

        val resolved = sammGateway.resolveSubscriberId("moved_user")

        assertEquals("75", resolved)
        val recorded = mockServer.takeRequestOrFail()
        assertEquals("/api/v1/customers", recorded.path!!.substringBefore("?"))
        assertTrue(
            "Resolution must use ?search=, which is case-insensitive. Actual: ${recorded.path}",
            recorded.path!!.contains("search=moved_user")
        )
        assertFalse(
            "?username= is case-sensitive on SAMM and returns nothing for a lowercased name. Actual: ${recorded.path}",
            recorded.path!!.contains("username=")
        )
    }

    /**
     * Claim: SammGatewayImpl.resolveSubscriberId is exact, not approximate. ?search= is a substring
     *        filter, so a subscriber whose name merely contains the key must never win.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: Two literal rows are returned, ordered so the substring match ("moved_user_old",
     *        id 12) comes first. The only correct answer is the second row's literal id 75.
     */
    @Test
    fun samm_resolveSubscriberId_ignoresSubstringMatches() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "total": 2,
                      "items": [
                        { "id": 12, "username": "moved_user_old" },
                        { "id": 75, "username": "Moved_User" }
                      ]
                    }
                    """.trimIndent()
                )
        )

        val resolved = sammGateway.resolveSubscriberId("moved_user")

        assertEquals(
            "A substring match must not be accepted as an exact username",
            "75",
            resolved
        )
    }

    /**
     * Claim: SammGatewayImpl.suspendSubscriber addresses the customer id its own provider owns, taken
     *        from the username it was given. This is the seam that previously accepted a caller's id.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: Username "moved_user" and customer id 75 are independent literals; the
     *        expected request path /api/v1/customers/75/suspend is derived from the id alone.
     */
    @Test
    fun samm_suspendSubscriber_dispatchesToTheResolvedCustomerId() = runTest {
        enqueueSammSearch(username = "moved_user", id = 75)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id": 75, "username": "moved_user", "status": "suspended"}""")
        )

        val result = sammGateway.suspendSubscriber("moved_user")

        assertTrue("Expected Applied", result.isApplied)
        mockServer.takeRequestOrFail()
        val mutation = mockServer.takeRequestOrFail()
        assertEquals("POST", mutation.method)
        assertEquals("/api/v1/customers/75/suspend", mutation.path)
    }

    /**
     * Claim: SammGatewayImpl.resolveSubscriberId returns null, never a guess, for a username the
     *        provider does not carry.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: The mocked provider returns zero items, so null is the only possible answer.
     */
    @Test
    fun samm_unknownUsername_resolvesToNull() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "total": 0, "items": [] }""")
        )

        val resolved = sammGateway.resolveSubscriberId("absent_user")

        assertNull("An unknown username must resolve to null, never a fallback id", resolved)
    }

    /**
     * Claim: SammGatewayImpl mutation methods fail closed on an unresolvable username: nothing is
     *        dispatched, so no other customer can be mutated by accident.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: One search request is expected and zero further requests; SasNotFoundException
     *        is the declared "does not exist on provider" signal of the seam taxonomy.
     */
    @Test
    fun samm_unresolvableUsername_failsClosedWithoutDispatching() = runTest {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "total": 0, "items": [] }""")
        )
        val requestsBefore = mockServer.requestCount

        try {
            sammGateway.suspendSubscriber("absent_user")
            fail("Expected SasNotFoundException")
        } catch (e: SasNotFoundException) {
            assertTrue(e.message?.contains("absent_user") == true)
        }
        assertEquals(
            "Only the lookup may reach the provider; no mutation may be dispatched",
            1,
            mockServer.requestCount - requestsBefore
        )
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter.resolveSubscriberId maps a username to the EarthLink userIndex.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The id 20562240 is a literal in the fake's fixture and is unrelated to the
     *        index 999 of the substring decoy row; the expected value is the literal 20562240.
     */
    @Test
    fun earthlink_resolveSubscriberId_returnsUserIndex() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(
                UserListItem(userIndexLower = 999, userIDLower = "moved_user_old"),
                UserListItem(userIndexLower = 20562240, userIDLower = "Moved_User@sacx")
            ),
            totalCount = 2
        )

        val resolved = earthlinkAdapter.resolveSubscriberId("moved_user@sacx")

        assertEquals("20562240", resolved)
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter.suspendSubscriber toggles the userIndex that belongs to the
     *        username it was given, never an id handed in by the caller.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: Username "moved_user@sacx" and userIndex 20562240 are independent literals.
     */
    @Test
    fun earthlink_suspendSubscriber_togglesTheResolvedUserIndex() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "moved_user@sacx")),
            totalCount = 1
        )

        val result = earthlinkAdapter.suspendSubscriber("moved_user@sacx")

        assertTrue("Expected Applied", result.isApplied)
        assertEquals(20562240, earthlinkFake.lastToggleUserIndex)
        assertEquals(false, earthlinkFake.lastToggleActiveState)
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter.changePassword sends the username as the gateway's userId and
     *        the resolved userIndex, so a numeric caller-supplied id can no longer be smuggled in.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The gateway's userId parameter is expected to equal the literal username.
     */
    @Test
    fun earthlink_changePassword_sendsUsernameNotACallerSuppliedId() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "moved_user@sacx")),
            totalCount = 1
        )

        val result = earthlinkAdapter.changePassword("moved_user@sacx", "new_secret")

        assertTrue("Expected Applied", result.isApplied)
        assertEquals(20562240, earthlinkFake.lastChangePasswordUserIndex)
        assertEquals("moved_user@sacx", earthlinkFake.lastChangePasswordUserId)
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter mutation methods fail closed on an unresolvable username:
     *        nothing is dispatched to the gateway.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: Zero recorded toggle calls is the only outcome consistent with no dispatch.
     */
    @Test
    fun earthlink_unresolvableUsername_failsClosedWithoutDispatching() = runTest {
        earthlinkFake.searchResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)

        try {
            earthlinkAdapter.activateSubscriber("absent_user")
            fail("Expected SasNotFoundException")
        } catch (e: SasNotFoundException) {
            assertTrue(e.message?.contains("absent_user") == true)
        }
        assertEquals(
            "No mutation may be dispatched against an unresolved username",
            0,
            earthlinkFake.toggleUserActiveCalls.get()
        )
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter.updateDisplayName renames the resolved userIndex. It must never
     *        fall through to a sentinel index when the username does not resolve, because a sentinel
     *        would silently rename whichever subscriber the gateway treats as index -1 or 0.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: Directory row moved_user@sacx -> 20562240; the expected index recorded by the
     *        gateway is that fixture literal.
     */
    @Test
    fun earthlink_updateDisplayName_renamesTheResolvedUserIndex() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "moved_user@sacx")),
            totalCount = 1
        )

        val result = earthlinkAdapter.updateDisplayName("moved_user@sacx", "New Name")

        assertTrue("Expected Applied", result.isApplied)
        assertEquals(20562240, earthlinkFake.lastUpdateDisplayNameIndex)
        assertEquals("New Name", earthlinkFake.lastUpdateDisplayName)
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter.updateDisplayName fails closed on an unresolvable username and
     *        dispatches nothing.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: Zero recorded rename calls is the only outcome consistent with no dispatch.
     */
    @Test
    fun earthlink_updateDisplayName_unresolvableUsername_failsClosed() = runTest {
        earthlinkFake.searchResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)

        try {
            earthlinkAdapter.updateDisplayName("absent_user", "New Name")
            fail("Expected SasNotFoundException")
        } catch (e: SasNotFoundException) {
            assertTrue(e.message?.contains("absent_user") == true)
        }
        assertEquals(
            "A rename must never be dispatched against an unresolved username",
            0,
            earthlinkFake.updateUserDisplayNameCalls.get()
        )
    }

    /**
 * Claim: SammGatewayImpl.resolveSubscriberId still finds the exact account when the username is a
 *        substring of many others. ?search= is a substring filter, so the colliders crowd the exact
 *        row; if the lookup window is narrower than the collision set, the exact row is never in the
 *        response and a subscriber who EXISTS resolves to null - unsuspendable, unrenewable,
 *        unextendable.
     * Seam: JVM MockWebServer wire contract. The provider is simulated with a Dispatcher that HONOURS
     *        `limit` and truncates. A queued static body would return the exact row no matter what the
     *        client asked for, and would prove nothing about the window.
     * Independent Oracle: The fixture holds 50 colliders ahead of the exact row, mirroring EarthLink's
     *        newest-first ordering. The only correct answers are the literal id "379" and a requested
     *        window larger than the 50-row collision set.
     */
    @Test
    fun samm_resolveSubscriberId_survivesASubstringCollisionFlood() = runTest {
        val rows = (1..50).map { """{"id": ${9000 + it}, "username": "ahmed${it}_extra@sacx"}""" } +
            listOf("""{"id": 379, "username": "ahmed@sacx"}""")
        val provider = TruncatingCustomerSearch(rows)
        mockServer.dispatcher = provider

        val resolved = sammGateway.resolveSubscriberId("ahmed@sacx")

        assertEquals(
            "An existing subscriber must resolve even when 50 other usernames contain their name",
            "379",
            resolved
        )
        assertTrue(
            "The lookup window must exceed the collision set, requested: ${provider.lastRequestedLimit}",
            provider.lastRequestedLimit!! > 50
        )
    }

    /**
     * A customer search that behaves like the real server under `limit`: it returns only the first
     * `limit` rows. MockWebServer's queued responses ignore `limit`, which would let a too-narrow
     * window still "succeed" and hide the very defect this test exists to catch.
     */
    private class TruncatingCustomerSearch(private val rows: List<String>) : Dispatcher() {
        var lastRequestedLimit: Int? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            val limit = request.requestUrl?.queryParameter("limit")?.toIntOrNull() ?: rows.size
            lastRequestedLimit = limit
            return MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "total": ${rows.size}, "items": [${rows.take(limit).joinToString(",")}] }""")
        }
    }

    /**
     * Claim: EarthlinkSasGatewayAdapter.resolveSubscriberId still finds the exact account when the
     *        username is a substring of many others. EarthLink's user search is a substring filter and
     *        returns newest-first, so a short window drops the exact row precisely because it is old.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: 50 colliders are listed ahead of the exact row. The expected answers are the
     *        literal id 379 and the literal window constant, neither recomputed from the code under test.
     */
    @Test
    fun earthlink_resolveSubscriberId_survivesASubstringCollisionFlood() = runTest {
        val colliders = (1..50).map {
            UserListItem(userIndexLower = 9000 + it, userIDLower = "ahmed${it}_extra@sacx")
        }
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = colliders + UserListItem(userIndexLower = 379, userIDLower = "ahmed@sacx"),
            totalCount = 51
        )

        val resolved = earthlinkAdapter.resolveSubscriberId("ahmed@sacx")

        assertEquals("379", resolved)
        assertEquals(SUBSCRIBER_LOOKUP_WINDOW, earthlinkFake.lastSearchRowCount)
        assertTrue(
            "The window must exceed a realistic collision set",
            earthlinkFake.lastSearchRowCount!! > 50
        )
    }

    private fun enqueueSammSearch(username: String, id: Int) {
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

    /**
     * Recording EarthlinkGateway fake: the three members this suite observes, plus inert stubs.
     */
    class RecordingEarthlinkGateway : EarthlinkGateway {
        val toggleUserActiveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val changeUserPasswordCalls = java.util.concurrent.atomic.AtomicInteger(0)

        var searchResponse: UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        var lastSearchQuery: String? = null
        var lastSearchRowCount: Int? = null
        var lastToggleUserIndex: Int? = null
        var lastToggleActiveState: Boolean? = null
        var lastChangePasswordUserIndex: Int? = null
        var lastChangePasswordUserId: String? = null
        val updateUserDisplayNameCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var lastUpdateDisplayNameIndex: Int? = null
        var lastUpdateDisplayName: String? = null

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            lastSearchQuery = query
            lastSearchRowCount = rowCount
            // Honours rowCount the way a real paginated endpoint does, so a resolver asking for too
            // narrow a window genuinely loses rows instead of silently seeing all of them.
            val windowed = searchResponse.itemsList.orEmpty().drop(startIndex).take(rowCount)
            return UserListResponse(itemsList = windowed, totalCount = searchResponse.totalCount)
        }

        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean {
            toggleUserActiveCalls.incrementAndGet()
            lastToggleUserIndex = userIndex
            lastToggleActiveState = active
            return true
        }

        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean {
            changeUserPasswordCalls.incrementAndGet()
            lastChangePasswordUserIndex = userIndex
            lastChangePasswordUserId = userId
            return true
        }

        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean {
            updateUserDisplayNameCalls.incrementAndGet()
            lastUpdateDisplayNameIndex = userIndex
            lastUpdateDisplayName = newName
            return true
        }

        // Unused interface stubs
        override suspend fun login(username: String, password: String) = com.example.core.model.LoginResponse("token", "Bearer", 3600)
        override suspend fun getBalance(): Double = 0.0
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<com.example.core.model.AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 0.0
        override suspend fun getUserDetail(userIndex: Int): com.example.core.model.UserDetail = com.example.core.model.UserDetail()
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun autocompleteUser(query: String): List<com.example.core.model.AutocompleteUser> = emptyList()
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = null
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = null
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<com.example.core.model.AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = ""
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = ""
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
    }
}