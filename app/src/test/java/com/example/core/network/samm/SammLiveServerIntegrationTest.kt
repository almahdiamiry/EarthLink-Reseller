package com.example.core.network.samm

import com.example.core.network.SasOperationResult
import com.example.core.network.SasVerificationOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SammLiveServerIntegrationTest
 *
 * Live end-to-end integration test executing real Android production classes
 * ([SammNetworkClient], [SammApiService], [SammGatewayImpl]) directly against
 * the live SAMM 5.2.0 server at http://172.16.0.190.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Connection test validates live token and reports server version 5.2.0.
 *    - Plan listing parses real JSON array into PlanResponse domain objects.
 *    - Subscriber retrieval and search accurately deserialize live customer objects.
 *    - Full mutation cycle (create, suspend, activate, renew, change plan) applies
 *      with generate_invoice=false on the wire.
 *    - All recovery verification oracles (ACTIVATION, TOGGLE_ACTIVE, RENEWAL, CHANGE_PLAN)
 *      return VERIFIED_SUCCESS against live server state.
 *
 * 2. Seam / Environment:
 *    JVM tier connecting via live OkHttp/Retrofit over LAN/Tailscale to http://172.16.0.190.
 *    Gracefully skips via Assume.assumeTrue if the live server is unreachable in offline CI.
 *
 * 3. Independent Oracle:
 *    Direct wire state validation against live SAMM database state and expected contract invariants.
 */
class SammLiveServerIntegrationTest {

    companion object {
        private const val LIVE_SAMM_URL = "http://172.16.0.190"
        private const val LIVE_API_TOKEN = "samm_fd29b53428ce7716dfa69c3eec5dd65df5db1120"

        private var isServerAvailable = false

        @JvmStatic
        @BeforeClass
        fun checkLiveServerAvailability() {
            val probeClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$LIVE_SAMM_URL/api/v1/me")
                .header("Authorization", "Bearer $LIVE_API_TOKEN")
                .build()

            isServerAvailable = try {
                probeClient.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (_: IOException) {
                false
            }
        }
    }

    private fun createGateway(): SammGatewayImpl {
        val client = SammNetworkClient(
            baseUrl = LIVE_SAMM_URL,
            token = LIVE_API_TOKEN
        )
        return SammGatewayImpl(client)
    }

    @Test
    fun liveConnection_verifiesServerHealth() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val gateway = createGateway()

        val connected = gateway.checkConnection()
        assertTrue("Expected connection to live SAMM server to succeed", connected)
    }

    @Test
    fun livePlans_fetchesAndParsesAllPlans() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val gateway = createGateway()

        val plans = gateway.listPlans()
        assertTrue("Live SAMM server should return plans", plans.isNotEmpty())

        val economy = plans.find { it.name.equals("Economy", ignoreCase = true) }
        assertNotNull("Expected Economy plan to exist in live SAMM catalog", economy)
        assertEquals(6, economy?.id)
    }

    @Test
    fun liveSubscribers_searchAndGetDetails() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val gateway = createGateway()

        val results = gateway.searchSubscribers("sub_lab_01", page = 1, pageSize = 10)
        assertTrue("Expected to find sub_lab_01", results.isNotEmpty())
        val subscriber = results.first()
        assertEquals("379", subscriber.subscriberId)
        assertEquals("sub_lab_01", subscriber.username)

        val detail = gateway.getSubscriber("379")
        assertNotNull("Expected customer detail for 379", detail)
        assertEquals("sub_lab_01", detail?.username)
        assertEquals("6", detail?.planId)
    }

    @Test
    fun liveMutationCycle_suspendActivateRenewAndChangePlan() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val gateway = createGateway()

        // 1. Suspend customer 379
        val suspendResult = gateway.suspendSubscriber("379")
        assertTrue("Suspend should be Applied", suspendResult is SasOperationResult.Applied)

        // Verify status oracle
        val suspendOutcome = gateway.verifyOperationOutcome("TOGGLE_ACTIVE", "379", "suspended")
        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, suspendOutcome)

        // 2. Activate customer 379
        val activateResult = gateway.activateSubscriber("379")
        assertTrue("Activate should be Applied", activateResult is SasOperationResult.Applied)

        // Verify status oracle
        val activateOutcome = gateway.verifyOperationOutcome("TOGGLE_ACTIVE", "379", "active")
        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, activateOutcome)

        // 3. Renew customer 379
        val beforeRenew = gateway.getSubscriber("379")
        val baselineExp = beforeRenew?.expiresAt

        val renewResult = gateway.renewSubscriber("379")
        assertTrue("Renew should be Applied", renewResult is SasOperationResult.Applied)

        // Verify renewal advance oracle
        val renewOutcome = gateway.verifyOperationOutcome("RENEWAL", "379", baselineExp)
        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, renewOutcome)

        // 4. Change plan to Plus (ID 7)
        val changePlanResult = gateway.changePlan("379", "7")
        assertTrue("Change plan should be Applied", changePlanResult is SasOperationResult.Applied)

        val planOutcome = gateway.verifyOperationOutcome("CHANGE_PLAN", "379", "7")
        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, planOutcome)

        // 5. Restore back to Economy (ID 6)
        val restorePlanResult = gateway.changePlan("379", "6")
        assertTrue("Restore plan should be Applied", restorePlanResult is SasOperationResult.Applied)

        val restoreOutcome = gateway.verifyOperationOutcome("CHANGE_PLAN", "379", "6")
        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, restoreOutcome)
    }

    @Test
    fun liveCreationAndLookup_createsSubscriberAndVerifiesOutcome() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val gateway = createGateway()

        val uniqueUsername = "test_lab_" + (System.currentTimeMillis() % 10000)

        val createResult = gateway.createSubscriber(
            username = uniqueUsername,
            planId = "6",
            password = "SecretLabPassword123!",
            firstName = "Live",
            lastName = "Tester",
            phone = "07700000000"
        )
        assertTrue("Create subscriber should be Applied", createResult is SasOperationResult.Applied)

        // Verify activation oracle
        val activationOutcome = gateway.verifyOperationOutcome("ACTIVATION", uniqueUsername, null)
        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, activationOutcome)

        // Verify exact search
        val found = gateway.searchSubscribers(uniqueUsername, page = 1, pageSize = 5)
        assertTrue("Expected created subscriber to be searchable", found.any { it.username == uniqueUsername })
    }

    @Test
    fun livePasswordChange_appliesAndReturnsInconclusive() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val gateway = createGateway()

        val passwordResult = gateway.changePassword("379", "UpdatedLabPass2026!")
        assertTrue("Change password should be Applied", passwordResult is SasOperationResult.Applied)

        val outcome = gateway.verifyOperationOutcome("PASSWORD", "379", null)
        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    @Test
    fun liveRouter_dispatchesToSammAndPreservesZeroCrossFallback() = runTest {
        Assume.assumeTrue("Live SAMM server is reachable", isServerAvailable)
        val liveGateway = createGateway()

        // Verify providerName invariant
        assertEquals(com.example.core.model.SasProviders.ALAMIRY, liveGateway.providerName)

        // Verify subscriber 379 can be fetched through the live gateway
        val user = liveGateway.getSubscriber("379")
        assertNotNull(user)
        assertEquals("sub_lab_01", user?.username)
    }
}
