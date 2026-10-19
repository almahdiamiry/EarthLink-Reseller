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

    /**
     * The narrowest directory window any username-addressed lookup may ask for, written as a literal
     * so it is an independent oracle rather than a restatement of the constant the code under test
     * uses. 100 is SAMM's documented server default and 10x the rowCount EarthLink's verification
     * oracle has always sent; both providers accept it.
     */
    private companion object {
        const val LOOKUP_WINDOW_FLOOR = 100
    }

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
        assertTrue(
            "The lookup window must clear the literal collision set of 50 rows, requested: ${earthlinkFake.lastSearchRowCount}",
            earthlinkFake.lastSearchRowCount!! >= LOOKUP_WINDOW_FLOOR
        )
    }

    // ========================================================================
    // IMPORTANT-1 / IMPORTANT-2: presence is three-valued, and it is read through the
    // same wide, exact-guarded directory lookup every other username operation uses.
    // ========================================================================

    /**
     * Claim: resolveSubscriberPresence reports PRESENT when the provider's own directory returns the
     *        exact row.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The fixture is a literal directory row; the only correct answer is PRESENT.
     */
    @Test
    fun earthlink_presence_providerCarriesTheRow_reportsPresent() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 379, userIDLower = "ahmed@sacx")),
            totalCount = 1
        )

        val presence = earthlinkAdapter.resolveSubscriberPresence("ahmed@sacx")

        assertEquals(SasSubscriberPresence.PRESENT, presence)
        assertEquals("ahmed@sacx", earthlinkFake.lastSearchQuery)
    }

    /**
     * Claim: resolveSubscriberPresence reports ABSENT only after the provider was successfully asked
     *        and had no such row.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: An empty, non-throwing directory response is the only input that
     *        establishes absence.
     */
    @Test
    fun earthlink_presence_providerAnsweredWithNoRow_reportsAbsent() = runTest {
        earthlinkFake.searchResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)

        val presence = earthlinkAdapter.resolveSubscriberPresence("nobody@sacx")

        assertEquals(SasSubscriberPresence.ABSENT, presence)
    }

    /**
     * Claim: a FAILED lookup is reported as UNKNOWN, never as ABSENT. This is the whole point of the
     *        third state: an offline device or an expired token used to render as a confirmed
     *        "not on this provider", telling the operator the subscriber was on neither ISP.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The provider throws a transport failure, which is the input that must NOT
     *        produce ABSENT. The assertion is written against the specific enum constant, so mapping
     *        the catch branch to ABSENT fails it.
     */
    @Test
    fun earthlink_presence_lookupFails_reportsUnknownNotAbsent() = runTest {
        earthlinkFake.searchException = EarthlinkTransportException("ISP unreachable")

        val presence = earthlinkAdapter.resolveSubscriberPresence("ahmed@sacx")

        assertEquals(
            "A failed lookup establishes nothing about the subscriber and must not read as absence",
            SasSubscriberPresence.UNKNOWN,
            presence
        )
        assertTrue(
            "Absence must be distinguishable from an unfinished lookup",
            presence != SasSubscriberPresence.ABSENT
        )
    }

    /**
     * Claim: presence shares the collision-proof window and exact-match guard. An existing subscriber
     *        must be PRESENT, not ABSENT, even when 50 other usernames contain their name and EarthLink
     *        lists the colliders first (newest-first ordering).
     * Seam: JVM headless unit test through a recording fake gateway. The fake honours rowCount, so a
     *        too-narrow window genuinely loses the exact row.
     * Independent Oracle: 50 colliders ahead of the exact row; the only correct answers are the literal
     *        PRESENT constant and a requested window at least LOOKUP_WINDOW_FLOOR wide.
     */
    @Test
    fun earthlink_presence_survivesASubstringCollisionFlood() = runTest {
        val colliders = (1..50).map {
            UserListItem(userIndexLower = 9000 + it, userIDLower = "ahmed${it}_extra@sacx")
        }
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = colliders + UserListItem(userIndexLower = 379, userIDLower = "ahmed@sacx"),
            totalCount = 51
        )

        val presence = earthlinkAdapter.resolveSubscriberPresence("ahmed@sacx")

        assertEquals(
            "A subscriber who EXISTS must not be reported absent because of colliding usernames",
            SasSubscriberPresence.PRESENT,
            presence
        )
        assertTrue(
            "The presence lookup must use the same wide window. requested: ${earthlinkFake.lastSearchRowCount}",
            earthlinkFake.lastSearchRowCount!! >= LOOKUP_WINDOW_FLOOR
        )
    }

    /**
     * Claim: SAMM presence distinguishes the same three states over the real wire contract.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: Three literal HTTP responses - one row, zero rows, HTTP 500 - map one to one
     *        onto the three enum constants. The 500 carries no rows at all, so any implementation that
     *        reads "no rows" as absence fails the third case.
     */
    @Test
    fun samm_presence_distinguishesPresentAbsentAndFailedLookup() = runTest {
        enqueueSammSearch(username = "moved_user", id = 75)
        assertEquals(SasSubscriberPresence.PRESENT, sammGateway.resolveSubscriberPresence("moved_user"))

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "total": 0, "items": [] }""")
        )
        assertEquals(SasSubscriberPresence.ABSENT, sammGateway.resolveSubscriberPresence("absent_user"))

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "directory exploded"}""")
        )
        assertEquals(SasSubscriberPresence.UNKNOWN, sammGateway.resolveSubscriberPresence("moved_user"))
    }

    /**
     * Claim: SAMM presence uses the same collision-proof window as its resolver. The Dispatcher
     *        HONOURS `limit` and truncates, so a narrow window really does drop the exact row.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: 50 colliders ahead of the exact row; the only correct answers are the
     *        literal PRESENT constant and a requested window above 50.
     */
    @Test
    fun samm_presence_survivesASubstringCollisionFlood() = runTest {
        val rows = (1..50).map { """{"id": ${9000 + it}, "username": "ahmed${it}_extra@sacx"}""" } +
            listOf("""{"id": 379, "username": "ahmed@sacx"}""")
        val provider = TruncatingCustomerSearch(rows)
        mockServer.dispatcher = provider

        val presence = sammGateway.resolveSubscriberPresence("ahmed@sacx")

        assertEquals(
            "An existing subscriber must be PRESENT even behind 50 colliding usernames",
            SasSubscriberPresence.PRESENT,
            presence
        )
        assertTrue(
            "The presence lookup must use the same wide window. requested: ${provider.lastRequestedLimit}",
            provider.lastRequestedLimit!! > 50
        )
    }

    // ========================================================================
    // IMPORTANT-3: the read is username-keyed too, so a cached provider id can never
    // address a live subscriber after an ISP move.
    // ========================================================================

    /**
     * Claim: EarthlinkSasGatewayAdapter.getSubscriber resolves the username to the directory row's
     *        userIndex and reads THAT row.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The literal directory row maps "ahmed@sacx" to the literal index 31415, and
     *        "31415" does not appear in the username. The only correct index is 31415; an
     *        implementation that parsed the argument would have no integer to parse at all.
     */
    @Test
    fun earthlink_getSubscriber_resolvesTheUsernameToItsDirectoryIndex() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 31415, userIDLower = "ahmed@sacx")),
            totalCount = 1
        )

        val subscriber = earthlinkAdapter.getSubscriber("ahmed@sacx")

        assertEquals(31415, earthlinkFake.lastUserDetailIndex)
        assertEquals("31415", subscriber?.subscriberId)
        assertEquals("ahmed@sacx", earthlinkFake.lastSearchQuery)
    }

    /**
     * Claim: getSubscriber never reinterprets its argument as a provider index, even a numeric one.
     *        An account's cached ispUserIndex is stale BY DESIGN once the subscriber moves ISP.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The literal username "31415" is served by the literal row index 777. Reading
     *        the argument as an index would dispatch 31415; only resolution dispatches 777.
     */
    @Test
    fun earthlink_getSubscriber_numericUsernameIsNeverUsedAsAUserIndex() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 777, userIDLower = "31415")),
            totalCount = 1
        )

        earthlinkAdapter.getSubscriber("31415")

        assertEquals(
            "The username must be resolved through the directory, never used as an index",
            777,
            earthlinkFake.lastUserDetailIndex
        )
    }

    /**
     * Claim: getSubscriber fails closed for a username the provider does not carry: no detail call is
     *        dispatched against a guessed index.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: Zero detail calls is the only outcome consistent with no dispatch.
     */
    @Test
    fun earthlink_getSubscriber_unknownUsername_dispatchesNoDetailCall() = runTest {
        earthlinkFake.searchResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)

        val subscriber = earthlinkAdapter.getSubscriber("nobody@sacx")

        assertNull(subscriber)
        assertEquals(0, earthlinkFake.getUserDetailCalls.get())
    }

    /**
     * Claim: getSubscriber returns null for ABSENCE and propagates for a FAILED lookup. Routing the
     *        read through resolveSubscriberId would collapse both into a null, which is the same
     *        "could not check" vs "checked, not there" conflation this branch exists to remove.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The provider throws a transport failure. Zero detail calls proves no index
     *        was guessed, and the literal exception message proves the failure was not swallowed.
     */
    @Test
    fun earthlink_getSubscriber_lookupFails_propagatesInsteadOfReturningNull() = runTest {
        earthlinkFake.searchException = EarthlinkTransportException("ISP unreachable")

        try {
            val subscriber = earthlinkAdapter.getSubscriber("moved_user@sacx")
            fail("Expected the lookup failure to propagate, not to report absence. Got: $subscriber")
        } catch (e: SasGatewayException) {
            assertTrue(
                "The transport failure must reach the caller: ${e.message}",
                e.message?.contains("ISP unreachable") == true
            )
        }
        assertEquals(
            "A failed lookup must never dispatch a guessed index",
            0,
            earthlinkFake.getUserDetailCalls.get()
        )
    }

    /**
     * Claim: SammGatewayImpl.getSubscriber resolves the username through the directory and then reads
     *        THAT customer. The argument is never used as a customer id.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: The search body is a literal row {id: 31415, username: "moved_user"}. The
     *        only correct detail path is /api/v1/customers/31415, derived from the search response and
     *        not from the argument.
     */
    @Test
    fun samm_getSubscriber_resolvesTheUsernameToItsCustomerId() = runTest {
        enqueueSammSearch(username = "moved_user", id = 31415)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "id": 31415, "username": "moved_user", "status": "active" }""")
        )

        val subscriber = sammGateway.getSubscriber("moved_user")

        val search = mockServer.takeRequestOrFail()
        assertTrue(
            "Resolution must use ?search=. Actual: ${search.path}",
            search.path!!.contains("search=moved_user")
        )
        val detail = mockServer.takeRequestOrFail()
        assertEquals(
            "The detail must be the row the username resolved to",
            "/api/v1/customers/31415",
            detail.path
        )
        assertEquals("31415", subscriber?.subscriberId)
    }

    /**
     * Claim: SammGatewayImpl.getSubscriber never reinterprets a numeric username as a customer id.
     * Seam: JVM MockWebServer wire contract.
     * Independent Oracle: The literal username "909090" is served by the literal id 31415. The only
     *        correct detail path is /api/v1/customers/31415.
     */
    @Test
    fun samm_getSubscriber_numericUsernameIsNeverUsedAsACustomerId() = runTest {
        enqueueSammSearch(username = "909090", id = 31415)
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{ "id": 31415, "username": "909090", "status": "active" }""")
        )

        sammGateway.getSubscriber("909090")

        mockServer.takeRequestOrFail()
        val detail = mockServer.takeRequestOrFail()
        assertEquals("/api/v1/customers/31415", detail.path)
    }

    /**
     * Claim: getSubscriber does not report a failed lookup as a missing subscriber.
     * Seam: MockWebServer wire contract for SAMM; recording fake for EarthLink.
     * Independent Oracle: A 500 detail response must throw, not return null. A null return here is
     *        indistinguishable from "this subscriber does not exist".
     */
    @Test
    fun earthlink_getSubscriber_detailFailure_propagatesInsteadOfReturningNull() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "moved_user@sacx")),
            totalCount = 1
        )
        val boom = object : EarthlinkGateway by earthlinkFake {
            override suspend fun getUserDetail(userIndex: Int): com.example.core.model.UserDetail =
                throw EarthlinkBusinessException(statusCode = 500, errorMessage = "detail exploded")
        }

        try {
            EarthlinkSasGatewayAdapter(boom).getSubscriber("moved_user@sacx")
            fail("A failed detail read must not be reported as a missing subscriber")
        } catch (e: SasGatewayException) {
            assertTrue(e.message?.contains("detail exploded") == true)
        }
    }

    // ========================================================================
    // Minor 2 / 4 / 5: the dispatched pair must describe ONE row, the resolved query must be
    // the one the operator typed, and a provider refusal must be observable.
    // ========================================================================

    /**
     * Claim: changePlan dispatches the userIndex resolved from the username, and sends the RESOLVED
     *        ROW's own userID beside it.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The directory stores the username with capitals ("Moved_User@sacx") while the
     *        caller passes the lowercased local copy. The gateway pair must be (literal 20562240,
     *        literal "Moved_User@sacx"); sending the caller's spelling would make the pair describe two
     *        different strings.
     */
    @Test
    fun earthlink_changePlan_dispatchesTheResolvedIndexAndTheRowOwnUserId() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "Moved_User@sacx")),
            totalCount = 1
        )

        val result = earthlinkAdapter.changePlan("moved_user@sacx", "7")

        assertTrue("Expected Applied", result.isApplied)
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)
        assertEquals(20562240, earthlinkFake.lastChangeAccountUserIndex)
        assertEquals(
            "The gateway pair must describe one row, so userId is the resolved row's own spelling",
            "Moved_User@sacx",
            earthlinkFake.lastChangeAccountUserId
        )
        assertEquals(7, earthlinkFake.lastChangeAccountNewIndex)
    }

    /**
     * Claim: a provider refusal is observable as Rejected, and the resolved index was still the one
     *        dispatched. A fake that can only return true proves nothing about either.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: changeAccountType answering false is the only input that establishes a
     *        refusal; SasOperationResult.Rejected is the seam's declared signal for it.
     */
    @Test
    fun earthlink_changePlan_providerRefusal_isReportedAsRejected() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "moved_user@sacx")),
            totalCount = 1
        )
        earthlinkFake.changeAccountTypeResult = false

        val result = earthlinkAdapter.changePlan("moved_user@sacx", "7")

        assertTrue("A refused plan change must not read as Applied", result is SasOperationResult.Rejected)
        assertEquals(20562240, earthlinkFake.lastChangeAccountUserIndex)
    }

    /**
     * Claim: changePassword pairs the resolved index with the resolved ROW's own userID.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: Directory stores "Moved_User@sacx"; the caller supplies the lowercased copy.
     *        The dispatched pair must be (20562240, "Moved_User@sacx").
     */
    @Test
    fun earthlink_changePassword_pairsTheResolvedIndexWithTheRowOwnUserId() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "Moved_User@sacx")),
            totalCount = 1
        )

        val result = earthlinkAdapter.changePassword("moved_user@sacx", "new_secret")

        assertTrue("Expected Applied", result.isApplied)
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)
        assertEquals(20562240, earthlinkFake.lastChangePasswordUserIndex)
        assertEquals(
            "The gateway pair must describe one row, so userId is the resolved row's own spelling",
            "Moved_User@sacx",
            earthlinkFake.lastChangePasswordUserId
        )
    }

    /**
     * Claim: every username-addressed mutation asks the directory for the username it was given. A
     *        resolver searching for some other term would pass the "did it dispatch the right index"
     *        assertions above, because the fake answers any query with the same fixture.
     * Seam: JVM headless unit test through a recording fake gateway.
     * Independent Oracle: The literal query string "moved_user@sacx" is compared, not a recomputation.
     */
    @Test
    fun earthlink_mutations_alwaysSearchForTheUsernameTheyWereGiven() = runTest {
        earthlinkFake.searchResponse = UserListResponse(
            itemsList = listOf(UserListItem(userIndexLower = 20562240, userIDLower = "moved_user@sacx")),
            totalCount = 1
        )

        earthlinkAdapter.suspendSubscriber("moved_user@sacx")
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)

        earthlinkAdapter.activateSubscriber("moved_user@sacx")
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)

        earthlinkAdapter.updateDisplayName("moved_user@sacx", "Renamed")
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)

        earthlinkAdapter.changePlan("moved_user@sacx", "7")
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)

        earthlinkAdapter.changePassword("moved_user@sacx", "new_secret")
        assertEquals("moved_user@sacx", earthlinkFake.lastSearchQuery)
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
     * Recording EarthlinkGateway fake: the members this suite observes, plus inert stubs.
     */
    class RecordingEarthlinkGateway : EarthlinkGateway {
        val toggleUserActiveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val changeUserPasswordCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val changeAccountTypeCalls = java.util.concurrent.atomic.AtomicInteger(0)

        var searchResponse: UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        var searchException: Throwable? = null
        var lastSearchQuery: String? = null
        var lastSearchRowCount: Int? = null
        var lastToggleUserIndex: Int? = null
        var lastToggleActiveState: Boolean? = null
        var lastChangePasswordUserIndex: Int? = null
        var lastChangePasswordUserId: String? = null
        val updateUserDisplayNameCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var lastUpdateDisplayNameIndex: Int? = null
        var lastUpdateDisplayName: String? = null

        var changeAccountTypeResult: Boolean = true
        var lastChangeAccountUserIndex: Int? = null
        var lastChangeAccountUserId: String? = null
        var lastChangeAccountNewIndex: Int? = null

        var getUserDetailCalls = java.util.concurrent.atomic.AtomicInteger(0)
        var lastUserDetailIndex: Int? = null

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            lastSearchQuery = query
            lastSearchRowCount = rowCount
            searchException?.let { throw it }
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

        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean {
            changeAccountTypeCalls.incrementAndGet()
            lastChangeAccountUserIndex = userIndex
            lastChangeAccountUserId = userId
            lastChangeAccountNewIndex = accountIndex
            return changeAccountTypeResult
        }

        override suspend fun getUserDetail(userIndex: Int): com.example.core.model.UserDetail {
            getUserDetailCalls.incrementAndGet()
            lastUserDetailIndex = userIndex
            // Echo the index that was actually asked for, so a test can tell which row was reached.
            return com.example.core.model.UserDetail(
                userIndexLower = userIndex,
                userIDLower = searchResponse.itemsList.orEmpty()
                    .firstOrNull { it.userIndex == userIndex }?.userID.orEmpty()
            )
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
        override suspend fun checkUsernameAvailable(userId: String): Boolean = true
        override suspend fun autocompleteUser(query: String): List<com.example.core.model.AutocompleteUser> = emptyList()
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = null
        override suspend fun createUserUsingDeposit(username: String, phone: String, fullName: String, accountIndex: Int, depositPassword: String): String? = null
        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean = true
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<com.example.core.model.AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = ""
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = ""
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
    }
}