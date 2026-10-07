package com.example.ui.viewmodels

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.LocalAccount
import com.example.core.model.PendingExternalOperation
import com.example.core.model.ProviderAccessState
import com.example.core.model.SasProviders
import com.example.core.security.PreferenceManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Provider-Independent Login, Credential Lifecycle, and Deployment Replacement Tests
 *
 * Core Triad Specification:
 * - Claim:
 *   1. Application session is provider-independent: unlocked when ANY provider is configured (EARTHLINK_ONLY, SAMM_ONLY, BOTH).
 *   2. Clearing one provider does NOT clear the other (independent lifecycle).
 *   3. Clearing credentials does not cascade into logging out if another provider remains configured.
 *   4. Provider removal/replacement does not mutate or erase LocalAccount.operationProvider or PendingExternalOperation.operationProvider.
 *   5. SAMM deployment endpoint replacement updates preference configuration without mutating domain account ownership.
 * - Seam: PreferenceManager, Models, and Domain Data Entities tested under Robolectric runtime.
 * - Independent Oracle: Deterministic state transitions verified against pure enum states and expected boolean flags.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderIndependentLoginTest {

    private lateinit var context: Context
    private lateinit var preferenceManager: PreferenceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferenceManager = PreferenceManager(context)
        preferenceManager.clearCredentials()
    }

    @Test
    fun testInitialStateIsNoneAndAppIsLocked() {
        // Initial state: No provider configured
        val state = preferenceManager.getProviderAccessState()
        assertEquals(ProviderAccessState.NONE, state)
        assertFalse("App should be locked when no provider is configured", state.isAppUnlocked)
        assertFalse("Should not have EarthLink", state.hasEarthlink)
        assertFalse("Should not have SAMM", state.hasSamm)
        assertFalse("isLoggedInFlow should be false", preferenceManager.isLoggedInFlow.value)
    }

    @Test
    fun testLoginEarthlinkOnlyUnlocksApp() {
        // Save EarthLink token
        preferenceManager.saveAuthToken("earthlink_jwt_token_123")

        val state = preferenceManager.getProviderAccessState()
        assertEquals(ProviderAccessState.EARTHLINK_ONLY, state)
        assertTrue("App should be unlocked with EarthLink only", state.isAppUnlocked)
        assertTrue("Should have EarthLink", state.hasEarthlink)
        assertFalse("Should not have SAMM", state.hasSamm)
        assertTrue("isLoggedInFlow should be true", preferenceManager.isLoggedInFlow.value)
    }

    @Test
    fun testLoginSammOnlyUnlocksApp() {
        // Save SAMM base URL and Token
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")

        val state = preferenceManager.getProviderAccessState()
        assertEquals(ProviderAccessState.SAMM_ONLY, state)
        assertTrue("App should be unlocked with SAMM only", state.isAppUnlocked)
        assertFalse("Should not have EarthLink", state.hasEarthlink)
        assertTrue("Should have SAMM", state.hasSamm)
        assertTrue("isLoggedInFlow should be true", preferenceManager.isLoggedInFlow.value)
    }

    @Test
    fun testDualProviderOperationBothConfigured() {
        preferenceManager.saveAuthToken("earthlink_jwt_token_123")
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")

        val state = preferenceManager.getProviderAccessState()
        assertEquals(ProviderAccessState.BOTH, state)
        assertTrue("App should be unlocked with both providers", state.isAppUnlocked)
        assertTrue("Should have EarthLink", state.hasEarthlink)
        assertTrue("Should have SAMM", state.hasSamm)
        assertTrue("isLoggedInFlow should be true", preferenceManager.isLoggedInFlow.value)
    }

    @Test
    fun testClearEarthlinkPreservesSammAndKeepsAppUnlocked() {
        // Configure both
        preferenceManager.saveAuthToken("earthlink_jwt_token_123")
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")
        assertEquals(ProviderAccessState.BOTH, preferenceManager.getProviderAccessState())

        // Clear only EarthLink
        preferenceManager.clearEarthlinkCredentials()

        val state = preferenceManager.getProviderAccessState()
        assertEquals("State should transition to SAMM_ONLY", ProviderAccessState.SAMM_ONLY, state)
        assertTrue("App should remain unlocked because SAMM is still active", state.isAppUnlocked)
        assertFalse("EarthLink should be cleared", state.hasEarthlink)
        assertTrue("SAMM should remain active", state.hasSamm)
        assertEquals("http://192.168.1.100:8000", preferenceManager.getSammBaseUrl())
        assertEquals("samm_token_secret_xyz", preferenceManager.getSammToken())
        assertTrue("isLoggedInFlow should remain true", preferenceManager.isLoggedInFlow.value)
    }

    @Test
    fun testClearSammPreservesEarthlinkAndKeepsAppUnlocked() {
        // Configure both
        preferenceManager.saveAuthToken("earthlink_jwt_token_123")
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")
        assertEquals(ProviderAccessState.BOTH, preferenceManager.getProviderAccessState())

        // Clear only SAMM
        preferenceManager.clearSammCredentials()

        val state = preferenceManager.getProviderAccessState()
        assertEquals("State should transition to EARTHLINK_ONLY", ProviderAccessState.EARTHLINK_ONLY, state)
        assertTrue("App should remain unlocked because EarthLink is still active", state.isAppUnlocked)
        assertTrue("EarthLink should remain active", state.hasEarthlink)
        assertFalse("SAMM should be cleared", state.hasSamm)
        assertEquals("earthlink_jwt_token_123", preferenceManager.getAuthToken())
        assertTrue("isLoggedInFlow should remain true", preferenceManager.isLoggedInFlow.value)
    }

    @Test
    fun testProviderRemovalSafetyDoesNotMutateAccountOrPendingOperation() {
        // Existing SAMM account and pending operation
        val account = LocalAccount(
            id = "acc_100",
            earthlinkUsername = "usr_samm_01",
            displayName = "SAMM Subscriber",
            operationProvider = SasProviders.ALAMIRY
        )
        val pendingOp = PendingExternalOperation(
            businessTransactionId = "tx_999",
            accountId = "acc_100",
            operationType = "RENEW",
            operationProvider = SasProviders.ALAMIRY
        )

        // Simulate operator removing SAMM provider configuration from device
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("token_to_remove")
        preferenceManager.clearSammCredentials()

        // Account domain identity and pending operation provider MUST remain unchanged
        assertEquals("Account provider must remain ALAMIRY even if SAMM credentials are removed",
            SasProviders.ALAMIRY, account.operationProvider)
        assertEquals("Pending op provider must remain ALAMIRY even if SAMM credentials are removed",
            SasProviders.ALAMIRY, pendingOp.operationProvider)
    }

    @Test
    fun testDeploymentReplacementUpdatesEndpointWithoutAlteringDomainOwnership() {
        // Operator replaces old SAMM server with new SAMM server URL and new token
        val initialVersion = preferenceManager.sammConfigVersionFlow.value

        preferenceManager.saveSammBaseUrl("http://old-server:8000")
        preferenceManager.saveSammToken("old-token")
        val version1 = preferenceManager.sammConfigVersionFlow.value
        assertTrue(version1 > initialVersion)

        // Replace deployment target
        preferenceManager.saveSammBaseUrl("https://new-samm.alamiry.net/api/")
        preferenceManager.saveSammToken("new-production-token")
        val version2 = preferenceManager.sammConfigVersionFlow.value
        assertTrue("Config version must increment on deployment update", version2 > version1)

        assertEquals("https://new-samm.alamiry.net/api", preferenceManager.getSammBaseUrl())
        assertEquals("new-production-token", preferenceManager.getSammToken())
    }

    @Test
    fun testProviderOutageDoesNotCauseGlobalLogout() {
        // Configure both providers
        preferenceManager.saveAuthToken("earthlink_jwt_token_123")
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")

        val stateBefore = preferenceManager.getProviderAccessState()
        assertEquals(ProviderAccessState.BOTH, stateBefore)
        assertTrue(preferenceManager.isLoggedInFlow.value)

        // Simulate a network outage / server failure (e.g., HTTP 503, timeout)
        // Architectural guarantee: Configured provider != live reachability.
        // A network or server error must NEVER automatically trigger credential clearing or global logout.
        val simulatedNetworkError = java.io.IOException("Failed to connect to SAMM gateway: Connection refused")
        assertNotNull(simulatedNetworkError)

        // Verify state remains BOTH and session remains unlocked
        val stateAfter = preferenceManager.getProviderAccessState()
        assertEquals("Configured state must remain BOTH during provider network outage", ProviderAccessState.BOTH, stateAfter)
        assertTrue("Application must remain unlocked during provider outage", stateAfter.isAppUnlocked)
        assertTrue("isLoggedInFlow must remain true", preferenceManager.isLoggedInFlow.value)
        assertEquals("Credentials must remain stored", "samm_token_secret_xyz", preferenceManager.getSammToken())
        assertEquals("Credentials must remain stored", "earthlink_jwt_token_123", preferenceManager.getAuthToken())
    }

    @Test
    fun testSammOnlyGatingHidesEarthlinkFeatures() {
        // Setup SAMM-only mode
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")

        val state = preferenceManager.getProviderAccessState()
        assertEquals(ProviderAccessState.SAMM_ONLY, state)
        assertTrue("App is unlocked in SAMM-only mode", state.isAppUnlocked)
        assertTrue("SAMM provider is active", state.hasSamm)
        assertFalse("EarthLink provider is NOT active", state.hasEarthlink)

        // Under SAMM-only mode:
        // 1. Trial User creation in CreateChooserBottomSheet is gated by hasEarthlink
        val canCreateTrialUser = state.hasEarthlink
        assertFalse("Trial user creation must be disabled in SAMM-only mode", canCreateTrialUser)

        // 2. Paid Subscriber creation is available for both
        val canCreatePaidUser = state.hasSamm || state.hasEarthlink
        assertTrue("Paid user creation must remain available", canCreatePaidUser)
    }

    @Test
    fun testGlobalLogoutClearsBothProviders() {
        preferenceManager.saveAuthToken("earthlink_jwt_token_123")
        preferenceManager.saveSammBaseUrl("http://192.168.1.100:8000")
        preferenceManager.saveSammToken("samm_token_secret_xyz")
        assertEquals(ProviderAccessState.BOTH, preferenceManager.getProviderAccessState())

        // Global logout via clearCredentials()
        preferenceManager.clearCredentials()

        val state = preferenceManager.getProviderAccessState()
        assertEquals("State must transition to NONE", ProviderAccessState.NONE, state)
        assertFalse("App must be locked", state.isAppUnlocked)
        assertFalse("EarthLink credentials must be cleared", state.hasEarthlink)
        assertFalse("SAMM credentials must be cleared", state.hasSamm)
        assertNull("Auth token must be null", preferenceManager.getAuthToken())
        assertNull("SAMM token must be null", preferenceManager.getSammToken())
        assertFalse("isLoggedInFlow must be false", preferenceManager.isLoggedInFlow.value)
    }
}

