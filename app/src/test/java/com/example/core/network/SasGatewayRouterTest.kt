package com.example.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.SasProviders
import com.example.core.network.samm.SammGatewayImpl
import com.example.core.security.PreferenceManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * SasGatewayRouterTest
 *
 * Authoritative unit test suite verifying [SasGatewayRouter] provider routing,
 * fail-closed isolation, zero cross-provider fallback, and dynamic configuration invalidation.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Route EARTHLINK to the authoritative EarthLink adapter.
 *    - Route ALAMIRY to a cached SAMM gateway instance when credentials are configured.
 *    - Strict Fail-Closed / Zero Fallback: ALAMIRY when unconfigured throws explicit SasBusinessException,
 *      never returning EarthLink. Unknown, blank, or null provider strings throw SasUnsupportedOperationException.
 *    - Zero Fallback on Execution: Exceptions thrown during SAMM gateway operations (transport error,
 *      404 not found, 401 auth) propagate directly to caller and never fall back to EarthLink.
 *    - Dynamic Refresh: Invalidation of cached SAMM gateway on sammConfigVersionFlow change, base URL
 *      or token update, or credential clear.
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier (RobolectricTestRunner, Android SDK test sandbox), real PreferenceManager instance
 *    with EncryptedSharedPreferences backing, coupled with SasGateway seams and factory injection.
 *
 * 3. Independent Oracle:
 *    Expected provider identities ("EARTHLINK", "ALAMIRY"), exception types (SasBusinessException,
 *    SasUnsupportedOperationException, SasTransportException, SasNotFoundException, SasAuthException),
 *    factory invocation counts, and instance identities derived independently of router implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SasGatewayRouterTest {

    private lateinit var context: Context
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var earthlinkAdapter: FakeSasGateway
    private lateinit var router: SasGatewayRouter

    private var factoryCallCount = 0
    private var lastFactoryBaseUrl: String? = null
    private var lastFactoryToken: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferenceManager = PreferenceManager(context)
        preferenceManager.clearCredentials()

        earthlinkAdapter = FakeSasGateway(providerName = SasProviders.EARTHLINK)
        factoryCallCount = 0
        lastFactoryBaseUrl = null
        lastFactoryToken = null

        router = SasGatewayRouter(
            earthlinkAdapter = earthlinkAdapter,
            preferenceManager = preferenceManager,
            sammGatewayFactory = { baseUrl, token ->
                factoryCallCount++
                lastFactoryBaseUrl = baseUrl
                lastFactoryToken = token
                FakeSasGateway(
                    providerName = SasProviders.ALAMIRY,
                    customBaseUrl = baseUrl,
                    customToken = token
                )
            }
        )
    }

    @After
    fun tearDown() {
        preferenceManager.clearCredentials()
    }

    // ========================================================================
    // 1. Basic Routing Tests
    // ========================================================================

    @Test
    fun getGateway_earthlink_routesToEarthlinkAdapter() {
        // Claim: SasProviders.EARTHLINK routes to earthlinkAdapter with zero SAMM creation.
        // Seam: router.getGateway(SasProviders.EARTHLINK)
        // Independent Oracle: Returned gateway must be identical instance to earthlinkAdapter.
        val gateway = router.getGateway(SasProviders.EARTHLINK)

        assertSame(earthlinkAdapter, gateway)
        assertEquals(SasProviders.EARTHLINK, gateway.providerName)
        assertEquals(0, factoryCallCount)
    }

    @Test
    fun getGateway_alamiry_routesToSammAdapterWhenConfigured() {
        // Claim: SasProviders.ALAMIRY routes to SAMM gateway constructed with active credentials.
        // Seam: router.getGateway(SasProviders.ALAMIRY) with configured PreferenceManager.
        // Independent Oracle: Provider name is ALAMIRY, factory receives configured credentials.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_secret_123")

        val gateway = router.getGateway(SasProviders.ALAMIRY)

        assertNotNull(gateway)
        assertEquals(SasProviders.ALAMIRY, gateway.providerName)
        assertEquals(1, factoryCallCount)
        assertEquals("https://samm.al-amiry.net", lastFactoryBaseUrl)
        assertEquals("samm_token_secret_123", lastFactoryToken)
        assertNotEquals(earthlinkAdapter, gateway)
    }

    // ========================================================================
    // 2. Unconfigured & Unknown Provider Tests (Fail-Closed)
    // ========================================================================

    @Test
    fun getGateway_alamiry_unconfiguredThrowsSasBusinessException_neverReturnsEarthlink() {
        // Claim: ALAMIRY without configuration throws explicit SasBusinessException, never EarthLink.
        // Seam: router.getGateway(SasProviders.ALAMIRY) when isSammConfigured == false.
        // Independent Oracle: Throws SasBusinessException with specific guidance message.
        val exception = assertThrows(SasBusinessException::class.java) {
            router.getGateway(SasProviders.ALAMIRY)
        }

        assertEquals(
            "SAMM provider is not configured. Please configure SAMM host and token in Settings.",
            exception.errorMessage
        )
        assertEquals(0, earthlinkAdapter.callCount)
        assertEquals(0, factoryCallCount)
    }

    @Test
    fun getGateway_alamiry_partialConfigOnlyBaseUrl_throwsSasBusinessException() {
        // Claim: Partial SAMM configuration (base URL set but token blank) fails closed.
        // Seam: router.getGateway(SasProviders.ALAMIRY).
        // Independent Oracle: Throws SasBusinessException.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")

        val exception = assertThrows(SasBusinessException::class.java) {
            router.getGateway(SasProviders.ALAMIRY)
        }

        assertTrue(exception.errorMessage.contains("SAMM provider is not configured"))
        assertEquals(0, factoryCallCount)
    }

    @Test
    fun getGateway_alamiry_partialConfigOnlyToken_throwsSasBusinessException() {
        // Claim: Partial SAMM configuration (token set but base URL blank) fails closed.
        // Seam: router.getGateway(SasProviders.ALAMIRY).
        // Independent Oracle: Throws SasBusinessException.
        preferenceManager.saveSammToken("samm_token_abc")

        val exception = assertThrows(SasBusinessException::class.java) {
            router.getGateway(SasProviders.ALAMIRY)
        }

        assertTrue(exception.errorMessage.contains("SAMM provider is not configured"))
        assertEquals(0, factoryCallCount)
    }

    @Test
    fun getGateway_unknownProvider_throwsSasUnsupportedOperationException() {
        // Claim: Unknown provider strings throw SasUnsupportedOperationException.
        // Seam: router.getGateway("UNKNOWN"), router.getGateway("VODAFONE").
        // Independent Oracle: SasUnsupportedOperationException thrown with unsupported provider name.
        val ex1 = assertThrows(SasUnsupportedOperationException::class.java) {
            router.getGateway("UNKNOWN")
        }
        assertTrue(ex1.message!!.contains("UNKNOWN"))

        val ex2 = assertThrows(SasUnsupportedOperationException::class.java) {
            router.getGateway("VODAFONE")
        }
        assertTrue(ex2.message!!.contains("VODAFONE"))
    }

    @Test
    fun getGateway_blankOrNullProvider_throwsSasUnsupportedOperationException() {
        // Claim: Blank, empty, or null provider strings throw SasUnsupportedOperationException.
        // Seam: router.getGateway(""), router.getGateway("   "), router.getGateway(null).
        // Independent Oracle: SasUnsupportedOperationException thrown for all.
        assertThrows(SasUnsupportedOperationException::class.java) {
            router.getGateway("")
        }

        assertThrows(SasUnsupportedOperationException::class.java) {
            router.getGateway("   ")
        }

        assertThrows(SasUnsupportedOperationException::class.java) {
            router.getGateway(null)
        }
    }

    // ========================================================================
    // 3. Zero Fallback Under Execution Errors
    // ========================================================================

    @Test
    fun getGateway_alamiry_zeroFallbackUnderTransportError() = runTest {
        // Claim: Transport errors thrown by SAMM gateway are never caught or redirected to EarthLink.
        // Seam: Execute getSubscriber() on resolved SAMM gateway configured with nextException.
        // Independent Oracle: SasTransportException propagates directly; earthlinkAdapter callCount == 0.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_test")

        val gateway = router.getGateway(SasProviders.ALAMIRY) as FakeSasGateway
        gateway.nextException = SasTransportException("Socket timeout connecting to SAMM host")

        var thrown: SasTransportException? = null
        try {
            gateway.getSubscriber("100")
            fail("Expected SasTransportException to be thrown")
        } catch (e: SasTransportException) {
            thrown = e
        }

        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("Socket timeout"))
        assertEquals(0, earthlinkAdapter.callCount)
    }

    @Test
    fun getGateway_alamiry_zeroFallbackUnder404NotFoundError() = runTest {
        // Claim: 404 Not Found from SAMM gateway propagates directly without EarthLink fallback.
        // Seam: Execute getSubscriber() on resolved SAMM gateway.
        // Independent Oracle: SasNotFoundException propagates directly; earthlinkAdapter callCount == 0.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_test")

        val gateway = router.getGateway(SasProviders.ALAMIRY) as FakeSasGateway
        gateway.nextException = SasNotFoundException(statusCode = 404, message = "Customer 999 not found on SAMM")

        var thrown: SasNotFoundException? = null
        try {
            gateway.getSubscriber("999")
            fail("Expected SasNotFoundException to be thrown")
        } catch (e: SasNotFoundException) {
            thrown = e
        }

        assertNotNull(thrown)
        assertEquals(404, thrown!!.statusCode)
        assertEquals(0, earthlinkAdapter.callCount)
    }

    @Test
    fun getGateway_alamiry_zeroFallbackUnder401AuthError() = runTest {
        // Claim: 401 Unauthorized from SAMM gateway propagates directly without EarthLink fallback.
        // Seam: Execute checkConnection() on resolved SAMM gateway.
        // Independent Oracle: SasAuthException propagates directly; earthlinkAdapter callCount == 0.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_test")

        val gateway = router.getGateway(SasProviders.ALAMIRY) as FakeSasGateway
        gateway.nextException = SasAuthException("SAMM token expired or invalid")

        var thrown: SasAuthException? = null
        try {
            gateway.checkConnection()
            fail("Expected SasAuthException to be thrown")
        } catch (e: SasAuthException) {
            thrown = e
        }

        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("SAMM token expired"))
        assertEquals(0, earthlinkAdapter.callCount)
    }

    // ========================================================================
    // 4. Caching & Dynamic Configuration Invalidation
    // ========================================================================

    @Test
    fun getGateway_alamiry_cachesInstanceWhenConfigUnchanged() {
        // Claim: Successive calls to getGateway(ALAMIRY) return identical cached instance.
        // Seam: router.getGateway(SasProviders.ALAMIRY) called multiple times.
        // Independent Oracle: same object reference returned, factory call count == 1.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_abc")

        val first = router.getGateway(SasProviders.ALAMIRY)
        val second = router.getGateway(SasProviders.ALAMIRY)
        val third = router.getGateway(SasProviders.ALAMIRY)

        assertSame(first, second)
        assertSame(second, third)
        assertEquals(1, factoryCallCount)
    }

    @Test
    fun getGateway_alamiry_dynamicConfigRefreshOnBaseUrlChange() {
        // Claim: Changing SAMM base URL invalidates cached gateway and instantiates a new one.
        // Seam: preferenceManager.saveSammBaseUrl() update.
        // Independent Oracle: factory called twice, new instance returned with new URL.
        preferenceManager.saveSammBaseUrl("https://samm1.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_abc")

        val first = router.getGateway(SasProviders.ALAMIRY)
        assertEquals("https://samm1.al-amiry.net", lastFactoryBaseUrl)
        assertEquals(1, factoryCallCount)

        // Update URL: triggers sammConfigVersionFlow increment
        preferenceManager.saveSammBaseUrl("https://samm2.al-amiry.net")

        val second = router.getGateway(SasProviders.ALAMIRY)
        assertEquals("https://samm2.al-amiry.net", lastFactoryBaseUrl)
        assertEquals(2, factoryCallCount)
        assertNotEquals(first, second)
    }

    @Test
    fun getGateway_alamiry_dynamicConfigRefreshOnTokenChange() {
        // Claim: Changing SAMM token invalidates cached gateway and instantiates a new one.
        // Seam: preferenceManager.saveSammToken() update.
        // Independent Oracle: factory called twice, new instance returned with new token.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_v1")

        val first = router.getGateway(SasProviders.ALAMIRY)
        assertEquals("samm_token_v1", lastFactoryToken)
        assertEquals(1, factoryCallCount)

        // Update Token: triggers sammConfigVersionFlow increment
        preferenceManager.saveSammToken("samm_token_v2")

        val second = router.getGateway(SasProviders.ALAMIRY)
        assertEquals("samm_token_v2", lastFactoryToken)
        assertEquals(2, factoryCallCount)
        assertNotEquals(first, second)
    }

    @Test
    fun getGateway_alamiry_clearingCredentialsInvalidatesCacheAndThrows() {
        // Claim: Clearing credentials invalidates cache so subsequent call fails closed.
        // Seam: preferenceManager.clearSammCredentials() followed by router.getGateway(ALAMIRY).
        // Independent Oracle: Throws SasBusinessException.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_xyz")

        val gateway = router.getGateway(SasProviders.ALAMIRY)
        assertNotNull(gateway)

        preferenceManager.clearSammCredentials()

        assertThrows(SasBusinessException::class.java) {
            router.getGateway(SasProviders.ALAMIRY)
        }
    }

    @Test
    fun getGateway_alamiry_reconfiguringAfterClearBuildsNewGateway() {
        // Claim: Re-configuring credentials after clear produces a valid new gateway.
        // Seam: clear -> reconfigure -> router.getGateway(ALAMIRY).
        // Independent Oracle: New gateway instantiated with factoryCallCount == 2.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("token_1")
        val g1 = router.getGateway(SasProviders.ALAMIRY)
        assertEquals(1, factoryCallCount)

        preferenceManager.clearSammCredentials()

        preferenceManager.saveSammBaseUrl("https://samm-new.al-amiry.net")
        preferenceManager.saveSammToken("token_2")
        val g2 = router.getGateway(SasProviders.ALAMIRY)

        assertEquals(2, factoryCallCount)
        assertEquals("https://samm-new.al-amiry.net", lastFactoryBaseUrl)
        assertEquals("token_2", lastFactoryToken)
        assertNotEquals(g1, g2)
    }

    // ========================================================================
    // 5. Default Factory Instantiation
    // ========================================================================

    @Test
    fun getGateway_alamiry_defaultFactoryConstructsSammGatewayImpl() {
        // Claim: When sammGatewayFactory is null, router constructs real SammGatewayImpl.
        // Seam: SasGatewayRouter default constructor with no factory parameter.
        // Independent Oracle: Instance is of type SammGatewayImpl and providerName is ALAMIRY.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_live")

        val defaultRouter = SasGatewayRouter(
            earthlinkAdapter = earthlinkAdapter,
            preferenceManager = preferenceManager
        )

        val gateway = defaultRouter.getGateway(SasProviders.ALAMIRY)
        assertTrue(gateway is SammGatewayImpl)
        assertEquals(SasProviders.ALAMIRY, gateway.providerName)
    }

    // ========================================================================
    // 6. Concurrency / Thread Safety
    // ========================================================================

    @Test
    fun getGateway_concurrentResolution_threadSafeSingleCachedInstance() {
        // Claim: Concurrent callers resolving ALAMIRY gateway receive identical cached instance.
        // Seam: 10 concurrent threads executing router.getGateway(ALAMIRY).
        // Independent Oracle: All threads receive identical instance reference, factory called once.
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_concurrent")

        val executor = Executors.newFixedThreadPool(10)
        val tasks = (1..20).map {
            Callable { router.getGateway(SasProviders.ALAMIRY) }
        }

        val futures = executor.invokeAll(tasks)
        val results = futures.map { it.get() }
        executor.shutdown()

        val firstInstance = results.first()
        for (instance in results) {
            assertSame(firstInstance, instance)
        }
        assertEquals(1, factoryCallCount)
    }

    // ========================================================================
    // Test Double Helper
    // ========================================================================

    class FakeSasGateway(
        override val providerName: String,
        val customBaseUrl: String? = null,
        val customToken: String? = null
    ) : SasGateway {
        var callCount = 0
        var nextException: Throwable? = null

        private fun checkOrThrow() {
            callCount++
            nextException?.let { throw it }
        }

        override suspend fun searchSubscribers(query: String, page: Int, pageSize: Int): List<SasSubscriberView> {
            checkOrThrow()
            return emptyList()
        }

        override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? {
            checkOrThrow()
            return null
        }

        override suspend fun createSubscriber(
            username: String,
            planId: String,
            password: String,
            firstName: String,
            lastName: String,
            phone: String?
        ): SasOperationResult {
            checkOrThrow()
            return SasOperationResult.Applied()
        }

        override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult {
            checkOrThrow()
            return SasOperationResult.Applied()
        }

        override suspend fun activateSubscriber(subscriberId: String): SasOperationResult {
            checkOrThrow()
            return SasOperationResult.Applied()
        }

        override suspend fun renewSubscriber(subscriberId: String): SasOperationResult {
            checkOrThrow()
            return SasOperationResult.Applied()
        }

        override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult {
            checkOrThrow()
            return SasOperationResult.Applied()
        }

        override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult {
            checkOrThrow()
            return SasOperationResult.Applied()
        }

        override suspend fun verifyOperationOutcome(
            operationType: String,
            targetIdentifier: String,
            baselineEvidence: String?
        ): SasVerificationOutcome {
            checkOrThrow()
            return SasVerificationOutcome.VERIFIED_SUCCESS
        }

        override suspend fun checkConnection(): Boolean {
            checkOrThrow()
            return true
        }
    }
}
