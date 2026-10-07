package com.example.core.network

import com.example.core.model.AccountPackage
import com.example.core.model.AccountStatementItem
import com.example.core.model.AutocompleteUser
import com.example.core.model.LoginResponse
import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.domain.repository.EarthlinkGateway
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * EarthlinkSasGatewayAdapterTest
 *
 * Authoritative JVM unit test suite verifying [EarthlinkSasGatewayAdapter] fulfilling [SasGateway] contract
 * for the EARTHLINK provider by delegating to [EarthlinkGateway]:
 * 1. Provider Identity: providerName strictly equals [SasProviders.EARTHLINK].
 * 2. Connectivity: checkConnection delegates to getBalance(), handling 200, 401 auth, and transport failures.
 * 3. Read Operations:
 *    - searchSubscribers computes 0-based paging startIndex and normalizes UserListItem to SasSubscriberView.
 *    - getSubscriber converts integer ID, maps UserDetail, returns null on non-numeric or 404.
 * 4. Mutation Operations:
 *    - createSubscriber converts planId, calls createUserUsingDeposit, returns Applied with evidence.
 *    - suspendSubscriber / activateSubscriber convert subscriberId, delegate toggleUserActive.
 *    - renewSubscriber fails closed throwing SasUnsupportedOperationException (requires deposit password).
 *    - changePlan converts subscriberId and planId, delegates changeAccountType.
 *    - changePassword converts subscriberId, delegates changeUserPassword.
 * 5. Recovery Verification Oracle (verifyOperationOutcome):
 *    - ACTIVATION: Evaluates existence via searchUsers, checkUsernameAvailable, or user detail.
 *    - RENEWAL / REFILL: Evaluates expiration advancement against baseline evidence.
 *    - TOGGLE_ACTIVE: Evaluates status equality against baseline target status.
 *    - CHANGE_PLAN: Evaluates plan ID equality against baseline plan ID.
 *    - PASSWORD: Write-only; strictly returns INCONCLUSIVE.
 *    - Unknown operations: Strictly returns INCONCLUSIVE.
 * 6. Error Translation: Delegate exceptions and unexpected throwables propagate typed SasGatewayException.
 *
 * Tier: JVM headless unit test.
 * Standard: Core Triad documented on all tests (Claim, Seam, Independent Oracle).
 */
class EarthlinkSasGatewayAdapterTest {

    private lateinit var fakeGateway: FakeEarthlinkGateway
    private lateinit var adapter: EarthlinkSasGatewayAdapter

    @Before
    fun setUp() {
        fakeGateway = FakeEarthlinkGateway()
        adapter = EarthlinkSasGatewayAdapter(fakeGateway)
    }

    // ========================================================================
    // 1. Provider Identity Contract
    // ========================================================================

    /**
     * Claim: EarthlinkSasGatewayAdapter exposes providerName strictly equal to SasProviders.EARTHLINK ("EARTHLINK").
     * Seam: JVM headless unit test.
     * Independent Oracle: Constant equality assertion against SasProviders.EARTHLINK.
     */
    @Test
    fun providerName_isStrictlyEarthlink() {
        assertEquals("EARTHLINK", adapter.providerName)
        assertEquals(SasProviders.EARTHLINK, adapter.providerName)
    }

    // ========================================================================
    // 2. Connectivity Check Contracts
    // ========================================================================

    /**
     * Claim: checkConnection calls delegate.getBalance() and returns true when call succeeds.
     * Seam: JVM headless unit test.
     * Independent Oracle: getBalance() returning balance -> Boolean true.
     */
    @Test
    fun checkConnection_onSuccess_returnsTrue() = runTest {
        fakeGateway.balanceResult = 150000.0

        val result = adapter.checkConnection()

        assertTrue("Expected checkConnection to return true on success", result)
        assertEquals(1, fakeGateway.getBalanceCalls.get())
    }

    /**
     * Claim: checkConnection returns false when delegate throws EarthlinkAuthException.
     * Seam: JVM headless unit test.
     * Independent Oracle: EarthlinkAuthException caught internally -> Boolean false.
     */
    @Test
    fun checkConnection_onAuthFailure_returnsFalse() = runTest {
        fakeGateway.balanceException = EarthlinkAuthException("EarthLink session expired")

        val result = adapter.checkConnection()

        assertFalse("Expected checkConnection to return false on auth failure", result)
        assertEquals(1, fakeGateway.getBalanceCalls.get())
    }

    /**
     * Claim: checkConnection returns false when delegate throws EarthlinkTransportException.
     * Seam: JVM headless unit test.
     * Independent Oracle: EarthlinkTransportException caught internally -> Boolean false.
     */
    @Test
    fun checkConnection_onTransportFailure_returnsFalse() = runTest {
        fakeGateway.balanceException = EarthlinkTransportException("Connection timed out")

        val result = adapter.checkConnection()

        assertFalse("Expected checkConnection to return false on transport failure", result)
    }

    // ========================================================================
    // 3. Subscriber Search Contracts
    // ========================================================================

    /**
     * Claim: searchSubscribers delegates query with 0-based paging startIndex and maps UserListItem to SasSubscriberView.
     * Seam: JVM headless unit test.
     * Independent Oracle: Page 2 with pageSize 20 yields startIndex = 20, rowCount = 20; fields match literal input.
     */
    @Test
    fun searchSubscribers_delegatesPagingAndMapsFields() = runTest {
        val testUser = UserListItem(
            userIndexLower = 401,
            userIDLower = "user_401@sacx",
            displayNameLower = "Almahdi Amiri",
            customerNameLower = "Almahdi Amiri",
            accountStatusLower = "Active",
            accountNameLower = "Eco 50M",
            expirationDateLower = "2026-11-01T00:00:00",
            mobileNumberLower = "07701234567",
            userActiveLower = true
        )
        fakeGateway.searchResponse = UserListResponse(
            itemsList = listOf(testUser),
            totalCount = 1
        )

        val results = adapter.searchSubscribers(query = "user_401", page = 2, pageSize = 20)

        assertEquals(1, fakeGateway.searchUsersCalls.get())
        assertEquals("user_401", fakeGateway.lastSearchQuery)
        assertEquals(20, fakeGateway.lastSearchStartIndex) // (2 - 1) * 20
        assertEquals(20, fakeGateway.lastSearchRowCount)

        assertEquals(1, results.size)
        val view = results[0]
        assertEquals("401", view.subscriberId)
        assertEquals("user_401@sacx", view.username)
        assertEquals("Almahdi Amiri", view.displayName)
        assertEquals("Active", view.status)
        assertNull("UserListItem does not supply numeric planId", view.planId)
        assertEquals("Eco 50M", view.planName)
        assertEquals("2026-11-01T00:00:00", view.expiresAt)
        assertEquals("07701234567", view.phone)
    }

    /**
     * Claim: searchSubscribers returns emptyList when delegate returns null or empty itemsList.
     * Seam: JVM headless unit test.
     * Independent Oracle: UserListResponse with null itemsList -> emptyList().
     */
    @Test
    fun searchSubscribers_emptyList_returnsEmpty() = runTest {
        fakeGateway.searchResponse = UserListResponse(itemsList = null, totalCount = 0)

        val results = adapter.searchSubscribers(query = "nonexistent", page = 1, pageSize = 10)

        assertTrue("Expected empty list on null itemsList", results.isEmpty())
        assertEquals(0, fakeGateway.lastSearchStartIndex)
    }

    /**
     * Claim: searchSubscribers propagates SasTransportException when delegate throws EarthlinkTransportException.
     * Seam: JVM headless unit test.
     * Independent Oracle: EarthlinkTransportException caught and rethrown without loss.
     */
    @Test
    fun searchSubscribers_transportError_propagatesSasTransportException() = runTest {
        fakeGateway.searchException = EarthlinkTransportException("Gateway socket error")

        try {
            adapter.searchSubscribers("query")
            fail("Expected SasTransportException")
        } catch (e: SasTransportException) {
            assertTrue(e.message?.contains("Gateway socket error") == true)
        }
    }

    // ========================================================================
    // 4. Subscriber Details Contracts
    // ========================================================================

    /**
     * Claim: getSubscriber parses subscriberId integer, invokes getUserDetail, and normalizes UserDetail into SasSubscriberView.
     * Seam: JVM headless unit test.
     * Independent Oracle: UserDetail with userIndex 702 and accountIndex 5 maps to subscriberId "702" and planId "5".
     */
    @Test
    fun getSubscriber_validId_mapsUserDetailToSasSubscriberView() = runTest {
        val detail = UserDetail(
            userIndexLower = 702,
            userIDLower = "test_sub_702",
            customerFullNameLower = "Zaid Ali",
            accountIndexLower = 5,
            packageNameLower = "Turbo 100M",
            accountStatusLower = "Active",
            expirationDateLower = "2026-12-15T00:00:00",
            mobileNumberLower = "07809876543",
            userActiveLower = true
        )
        fakeGateway.userDetailResult = detail

        val subscriber = adapter.getSubscriber("702")

        assertNotNull("Expected non-null subscriber", subscriber)
        assertEquals("702", subscriber!!.subscriberId)
        assertEquals("test_sub_702", subscriber.username)
        assertEquals("Zaid Ali", subscriber.displayName)
        assertEquals("Active", subscriber.status)
        assertEquals("5", subscriber.planId)
        assertEquals("Turbo 100M", subscriber.planName)
        assertEquals("2026-12-15T00:00:00", subscriber.expiresAt)
        assertEquals("07809876543", subscriber.phone)
        assertEquals(702, fakeGateway.lastUserDetailIndex)
    }

    /**
     * Claim: getSubscriber returns null without calling delegate when subscriberId is non-numeric.
     * Seam: JVM headless unit test.
     * Independent Oracle: Non-numeric subscriberId "alpha_numeric" -> null, 0 delegate calls.
     */
    @Test
    fun getSubscriber_nonNumericId_returnsNull() = runTest {
        val result = adapter.getSubscriber("alpha_numeric")

        assertNull("Expected null for non-numeric subscriber ID", result)
        assertEquals(0, fakeGateway.getUserDetailCalls.get())
    }

    /**
     * Claim: getSubscriber returns null when delegate throws EarthlinkBusinessException with 404 status.
     * Seam: JVM headless unit test.
     * Independent Oracle: 404 status code -> null result.
     */
    @Test
    fun getSubscriber_notFound404_returnsNull() = runTest {
        fakeGateway.userDetailException = EarthlinkBusinessException(statusCode = 404, errorMessage = "User not found")

        val result = adapter.getSubscriber("999")

        assertNull("Expected null when user not found (404)", result)
        assertEquals(1, fakeGateway.getUserDetailCalls.get())
    }

    /**
     * Claim: getSubscriber propagates SasGatewayException when delegate throws non-404 business exception.
     * Seam: JVM headless unit test.
     * Independent Oracle: EarthlinkBusinessException with 500 status -> propagated exception.
     */
    @Test
    fun getSubscriber_serverError_propagatesSasGatewayException() = runTest {
        fakeGateway.userDetailException = EarthlinkBusinessException(statusCode = 500, errorMessage = "Internal Error")

        try {
            adapter.getSubscriber("888")
            fail("Expected SasBusinessException")
        } catch (e: SasGatewayException) {
            assertTrue(e.message?.contains("Internal Error") == true)
        }
    }

    // ========================================================================
    // 5. Create Subscriber (Activation) Contracts
    // ========================================================================

    /**
     * Claim: createSubscriber converts planId integer, passes parameters to createUserUsingDeposit, and returns Applied with evidence.
     * Seam: JVM headless unit test.
     * Independent Oracle: Successful createUserUsingDeposit returns "Pass_876543" -> SasOperationResult.Applied(evidence = "Pass_876543").
     */
    @Test
    fun createSubscriber_success_returnsAppliedWithEvidence() = runTest {
        fakeGateway.createUserUsingDepositResult = "Pass_876543"

        val result = adapter.createSubscriber(
            username = "new_sub_01",
            planId = "3",
            password = "deposit_secret",
            firstName = "Almahdi",
            lastName = "Amiri",
            phone = "07700000001"
        )

        assertTrue("Expected Applied result", result is SasOperationResult.Applied)
        assertEquals("Pass_876543", (result as SasOperationResult.Applied).evidence)
        assertEquals(1, fakeGateway.createUserUsingDepositCalls.get())
        assertEquals("new_sub_01", fakeGateway.lastCreateUsername)
        assertEquals("07700000001", fakeGateway.lastCreatePhone)
        assertEquals("Almahdi Amiri", fakeGateway.lastCreateFullName)
        assertEquals(3, fakeGateway.lastCreateAccountIndex)
        assertEquals("deposit_secret", fakeGateway.lastCreateDepositPass)
    }

    /**
     * Claim: createSubscriber returns Rejected when delegate returns null string.
     * Seam: JVM headless unit test.
     * Independent Oracle: Null delegate response -> SasOperationResult.Rejected.
     */
    @Test
    fun createSubscriber_nullResult_returnsRejected() = runTest {
        fakeGateway.createUserUsingDepositResult = null

        val result = adapter.createSubscriber(
            username = "new_sub_02",
            planId = "2",
            password = "secret",
            firstName = "John",
            lastName = "Doe",
            phone = null
        )

        assertTrue("Expected Rejected result", result is SasOperationResult.Rejected)
        val rejected = result as SasOperationResult.Rejected
        assertTrue(rejected.reason.contains("rejected"))
    }

    /**
     * Claim: createSubscriber fails closed throwing SasBusinessException when planId is non-numeric.
     * Seam: JVM headless unit test.
     * Independent Oracle: Non-numeric planId "pkg_pro" -> SasBusinessException with 0 delegate calls.
     */
    @Test
    fun createSubscriber_invalidPlanId_throwsSasBusinessException() = runTest {
        try {
            adapter.createSubscriber(
                username = "new_sub_03",
                planId = "pkg_pro",
                password = "pass",
                firstName = "A",
                lastName = "B",
                phone = null
            )
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertTrue(e.errorMessage.contains("Invalid EarthLink plan ID"))
            assertEquals(0, fakeGateway.createUserUsingDepositCalls.get())
        }
    }

    /**
     * Claim: createSubscriber propagates SasInconclusiveException when delegate throws EarthlinkInconclusiveException.
     * Seam: JVM headless unit test.
     * Independent Oracle: EarthlinkInconclusiveException rethrown preserving message and cause.
     */
    @Test
    fun createSubscriber_inconclusiveError_propagatesException() = runTest {
        fakeGateway.createUserUsingDepositException = EarthlinkInconclusiveException(
            statusCode = 200,
            message = "Missing userIndex payload"
        )

        try {
            adapter.createSubscriber(
                username = "new_sub_04",
                planId = "1",
                password = "pass",
                firstName = "A",
                lastName = "B",
                phone = null
            )
            fail("Expected SasInconclusiveException")
        } catch (e: SasInconclusiveException) {
            assertTrue(e.message?.contains("Missing userIndex payload") == true)
        }
    }

    // ========================================================================
    // 6. Suspend & Activate Subscriber Contracts
    // ========================================================================

    /**
     * Claim: suspendSubscriber converts subscriberId, delegates toggleUserActive(userIndex, false), and returns Applied.
     * Seam: JVM headless unit test.
     * Independent Oracle: toggleUserActive(501, false) returning true -> SasOperationResult.Applied.
     */
    @Test
    fun suspendSubscriber_success_returnsApplied() = runTest {
        fakeGateway.toggleUserActiveResult = true

        val result = adapter.suspendSubscriber("501")

        assertTrue("Expected Applied result", result is SasOperationResult.Applied)
        assertEquals(1, fakeGateway.toggleUserActiveCalls.get())
        assertEquals(501, fakeGateway.lastToggleUserIndex)
        assertEquals(false, fakeGateway.lastToggleActiveState)
    }

    /**
     * Claim: suspendSubscriber returns Rejected when toggleUserActive returns false.
     * Seam: JVM headless unit test.
     * Independent Oracle: toggleUserActive returning false -> SasOperationResult.Rejected.
     */
    @Test
    fun suspendSubscriber_failure_returnsRejected() = runTest {
        fakeGateway.toggleUserActiveResult = false

        val result = adapter.suspendSubscriber("501")

        assertTrue("Expected Rejected result", result is SasOperationResult.Rejected)
    }

    /**
     * Claim: suspendSubscriber fails closed throwing SasBusinessException when subscriberId is non-numeric.
     * Seam: JVM headless unit test.
     * Independent Oracle: Non-numeric subscriberId -> SasBusinessException with 0 delegate calls.
     */
    @Test
    fun suspendSubscriber_invalidId_throwsSasBusinessException() = runTest {
        try {
            adapter.suspendSubscriber("invalid_id")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertTrue(e.errorMessage.contains("Invalid EarthLink subscriber ID"))
            assertEquals(0, fakeGateway.toggleUserActiveCalls.get())
        }
    }

    /**
     * Claim: activateSubscriber converts subscriberId, delegates toggleUserActive(userIndex, true), and returns Applied.
     * Seam: JVM headless unit test.
     * Independent Oracle: toggleUserActive(602, true) returning true -> SasOperationResult.Applied.
     */
    @Test
    fun activateSubscriber_success_returnsApplied() = runTest {
        fakeGateway.toggleUserActiveResult = true

        val result = adapter.activateSubscriber("602")

        assertTrue("Expected Applied result", result is SasOperationResult.Applied)
        assertEquals(1, fakeGateway.toggleUserActiveCalls.get())
        assertEquals(602, fakeGateway.lastToggleUserIndex)
        assertEquals(true, fakeGateway.lastToggleActiveState)
    }

    /**
     * Claim: activateSubscriber returns Rejected when toggleUserActive returns false.
     * Seam: JVM headless unit test.
     * Independent Oracle: toggleUserActive returning false -> SasOperationResult.Rejected.
     */
    @Test
    fun activateSubscriber_failure_returnsRejected() = runTest {
        fakeGateway.toggleUserActiveResult = false

        val result = adapter.activateSubscriber("602")

        assertTrue("Expected Rejected result", result is SasOperationResult.Rejected)
    }

    // ========================================================================
    // 7. Renew Subscriber Contracts (Fail-Closed)
    // ========================================================================

    /**
     * Claim: renewSubscriber fails closed throwing SasUnsupportedOperationException per product contract
     *        because EarthLink renewal strictly requires deposit password authorization.
     * Seam: JVM headless unit test.
     * Independent Oracle: Throws SasUnsupportedOperationException, 0 refill/renew delegate calls.
     */
    @Test
    fun renewSubscriber_alwaysThrowsSasUnsupportedOperationException() = runTest {
        try {
            adapter.renewSubscriber("701")
            fail("Expected SasUnsupportedOperationException")
        } catch (e: SasUnsupportedOperationException) {
            assertTrue(e.message?.contains("EarthLink subscriber renewal requires deposit password") == true)
            assertEquals(0, fakeGateway.refillUserDepositCalls.get())
        }
    }

    // ========================================================================
    // 8. Change Plan Contracts
    // ========================================================================

    /**
     * Claim: changePlan converts subscriberId and planId, delegates changeAccountType, and returns Applied.
     * Seam: JVM headless unit test.
     * Independent Oracle: changeAccountType(301, "301", 7) returning true -> SasOperationResult.Applied.
     */
    @Test
    fun changePlan_success_returnsApplied() = runTest {
        fakeGateway.changeAccountTypeResult = true

        val result = adapter.changePlan("301", "7")

        assertTrue("Expected Applied result", result is SasOperationResult.Applied)
        assertEquals(1, fakeGateway.changeAccountTypeCalls.get())
        assertEquals(301, fakeGateway.lastChangeAccountUserIndex)
        assertEquals("301", fakeGateway.lastChangeAccountUserId)
        assertEquals(7, fakeGateway.lastChangeAccountNewIndex)
    }

    /**
     * Claim: changePlan returns Rejected when changeAccountType returns false.
     * Seam: JVM headless unit test.
     * Independent Oracle: changeAccountType returning false -> SasOperationResult.Rejected.
     */
    @Test
    fun changePlan_failure_returnsRejected() = runTest {
        fakeGateway.changeAccountTypeResult = false

        val result = adapter.changePlan("301", "7")

        assertTrue("Expected Rejected result", result is SasOperationResult.Rejected)
    }

    /**
     * Claim: changePlan fails closed throwing SasBusinessException when subscriberId or planId is non-numeric.
     * Seam: JVM headless unit test.
     * Independent Oracle: Non-numeric subscriberId -> SasBusinessException with 0 delegate calls.
     */
    @Test
    fun changePlan_invalidSubscriberId_throwsSasBusinessException() = runTest {
        try {
            adapter.changePlan("invalid_sub", "5")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertTrue(e.errorMessage.contains("Invalid EarthLink subscriber ID"))
            assertEquals(0, fakeGateway.changeAccountTypeCalls.get())
        }
    }

    /**
     * Claim: changePlan fails closed throwing SasBusinessException when planId is non-numeric.
     * Seam: JVM headless unit test.
     * Independent Oracle: Non-numeric planId -> SasBusinessException with 0 delegate calls.
     */
    @Test
    fun changePlan_invalidPlanId_throwsSasBusinessException() = runTest {
        try {
            adapter.changePlan("301", "pkg_ultra")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertTrue(e.errorMessage.contains("Invalid EarthLink plan ID"))
            assertEquals(0, fakeGateway.changeAccountTypeCalls.get())
        }
    }

    // ========================================================================
    // 9. Change Password Contracts
    // ========================================================================

    /**
     * Claim: changePassword converts subscriberId, delegates changeUserPassword, and returns Applied.
     * Seam: JVM headless unit test.
     * Independent Oracle: changeUserPassword(401, "401", "new_secret") returning true -> SasOperationResult.Applied.
     */
    @Test
    fun changePassword_success_returnsApplied() = runTest {
        fakeGateway.changeUserPasswordResult = true

        val result = adapter.changePassword("401", "new_secret")

        assertTrue("Expected Applied result", result is SasOperationResult.Applied)
        assertEquals(1, fakeGateway.changeUserPasswordCalls.get())
        assertEquals(401, fakeGateway.lastChangePasswordUserIndex)
        assertEquals("401", fakeGateway.lastChangePasswordUserId)
        assertEquals("new_secret", fakeGateway.lastChangePasswordNewPass)
    }

    /**
     * Claim: changePassword returns Rejected when changeUserPassword returns false.
     * Seam: JVM headless unit test.
     * Independent Oracle: changeUserPassword returning false -> SasOperationResult.Rejected.
     */
    @Test
    fun changePassword_failure_returnsRejected() = runTest {
        fakeGateway.changeUserPasswordResult = false

        val result = adapter.changePassword("401", "new_secret")

        assertTrue("Expected Rejected result", result is SasOperationResult.Rejected)
    }

    /**
     * Claim: changePassword fails closed throwing SasBusinessException when subscriberId is non-numeric.
     * Seam: JVM headless unit test.
     * Independent Oracle: Non-numeric subscriberId -> SasBusinessException with 0 delegate calls.
     */
    @Test
    fun changePassword_invalidSubscriberId_throwsSasBusinessException() = runTest {
        try {
            adapter.changePassword("bad_id", "pass")
            fail("Expected SasBusinessException")
        } catch (e: SasBusinessException) {
            assertTrue(e.errorMessage.contains("Invalid EarthLink subscriber ID"))
            assertEquals(0, fakeGateway.changeUserPasswordCalls.get())
        }
    }

    // ========================================================================
    // 10. Operation Verification Oracle Contracts
    // ========================================================================

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns VERIFIED_SUCCESS when subscriber is found in search.
     * Seam: JVM headless unit test.
     * Independent Oracle: Matching user found by username -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyOperationOutcome_activation_userFoundInSearch_verifiedSuccess() = runTest {
        val user = UserListItem(
            userIndexLower = 101,
            userIDLower = "activated_user@sacx"
        )
        fakeGateway.searchResponse = UserListResponse(itemsList = listOf(user), totalCount = 1)

        val outcome = adapter.verifyOperationOutcome(
            operationType = "ACTIVATION",
            targetIdentifier = "activated_user@sacx"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns VERIFIED_SUCCESS when targetIdentifier is userIndex and userDetail succeeds.
     * Seam: JVM headless unit test.
     * Independent Oracle: Integer userIndex found on ISP -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyOperationOutcome_activation_userFoundByIndex_verifiedSuccess() = runTest {
        fakeGateway.userDetailResult = UserDetail(userIndexLower = 102, userIDLower = "user_102")

        val outcome = adapter.verifyOperationOutcome(
            operationType = "ACTIVATION",
            targetIdentifier = "102"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns VERIFIED_SUCCESS when username is not in search but checkUsernameAvailable returns false (taken on ISP).
     * Seam: JVM headless unit test.
     * Independent Oracle: checkUsernameAvailable = false -> subscriber exists on ISP -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyOperationOutcome_activation_usernameTaken_verifiedSuccess() = runTest {
        fakeGateway.searchResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        fakeGateway.usernameAvailable = false // taken on ISP

        val outcome = adapter.verifyOperationOutcome(
            operationType = "ACTIVATION",
            targetIdentifier = "activated_user"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns VERIFIED_FAILURE when subscriber does not exist (checkUsernameAvailable = true).
     * Seam: JVM headless unit test.
     * Independent Oracle: checkUsernameAvailable = true -> subscriber does not exist on ISP -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyOperationOutcome_activation_usernameAvailable_verifiedFailure() = runTest {
        fakeGateway.searchResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        fakeGateway.usernameAvailable = true // still available; registration did not happen

        val outcome = adapter.verifyOperationOutcome(
            operationType = "ACTIVATION",
            targetIdentifier = "unregistered_user"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns VERIFIED_FAILURE when targetIdentifier is userIndex and 404 is returned.
     * Seam: JVM headless unit test.
     * Independent Oracle: 404 on userIndex -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyOperationOutcome_activation_userNotFoundByIndex_verifiedFailure() = runTest {
        fakeGateway.userDetailException = EarthlinkBusinessException(statusCode = 404, errorMessage = "Not found")

        val outcome = adapter.verifyOperationOutcome(
            operationType = "ACTIVATION",
            targetIdentifier = "999"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for ACTIVATION returns INCONCLUSIVE when network error occurs.
     * Seam: JVM headless unit test.
     * Independent Oracle: Transport exception during search -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyOperationOutcome_activation_error_inconclusive() = runTest {
        fakeGateway.searchException = EarthlinkTransportException("Timeout")

        val outcome = adapter.verifyOperationOutcome(
            operationType = "ACTIVATION",
            targetIdentifier = "target_user"
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns VERIFIED_SUCCESS when subscriber's expiration date has advanced beyond baselineEvidence.
     * Seam: JVM headless unit test.
     * Independent Oracle: Current expiration "2026-11-15" != baseline "2026-10-15" -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyOperationOutcome_renewal_expiryAdvanced_verifiedSuccess() = runTest {
        fakeGateway.userDetailResult = UserDetail(
            userIndexLower = 201,
            expirationDateLower = "2026-11-15T00:00:00"
        )

        val outcome = adapter.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "201",
            baselineEvidence = "2026-10-15T00:00:00"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns VERIFIED_FAILURE when subscriber's expiration date has NOT advanced.
     * Seam: JVM headless unit test.
     * Independent Oracle: Current expiration "2026-10-15" == baseline "2026-10-15" -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyOperationOutcome_renewal_expiryUnchanged_verifiedFailure() = runTest {
        fakeGateway.userDetailResult = UserDetail(
            userIndexLower = 201,
            expirationDateLower = "2026-10-15T00:00:00"
        )

        val outcome = adapter.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "201",
            baselineEvidence = "2026-10-15T00:00:00"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns VERIFIED_FAILURE when subscriber is not found (404).
     * Seam: JVM headless unit test.
     * Independent Oracle: 404 response -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyOperationOutcome_renewal_userNotFound_verifiedFailure() = runTest {
        fakeGateway.userDetailException = EarthlinkBusinessException(statusCode = 404, errorMessage = "Not found")

        val outcome = adapter.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "202",
            baselineEvidence = "2026-10-15T00:00:00"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for RENEWAL returns INCONCLUSIVE when network transport fails.
     * Seam: JVM headless unit test.
     * Independent Oracle: Socket exception -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyOperationOutcome_renewal_transportError_inconclusive() = runTest {
        fakeGateway.userDetailException = EarthlinkTransportException("Socket closed")

        val outcome = adapter.verifyOperationOutcome(
            operationType = "RENEWAL",
            targetIdentifier = "203",
            baselineEvidence = "2026-10-15T00:00:00"
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for TOGGLE_ACTIVE returns VERIFIED_SUCCESS when accountStatus matches target baseline status.
     * Seam: JVM headless unit test.
     * Independent Oracle: Current accountStatus "Suspended" == baseline "Suspended" -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyOperationOutcome_toggleActive_statusMatches_verifiedSuccess() = runTest {
        fakeGateway.userDetailResult = UserDetail(
            userIndexLower = 301,
            accountStatusLower = "Suspended"
        )

        val outcome = adapter.verifyOperationOutcome(
            operationType = "TOGGLE_ACTIVE",
            targetIdentifier = "301",
            baselineEvidence = "Suspended"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for TOGGLE_ACTIVE returns VERIFIED_FAILURE when accountStatus differs from target baseline status.
     * Seam: JVM headless unit test.
     * Independent Oracle: Current accountStatus "Active" != baseline "Suspended" -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyOperationOutcome_toggleActive_statusDiffers_verifiedFailure() = runTest {
        fakeGateway.userDetailResult = UserDetail(
            userIndexLower = 301,
            accountStatusLower = "Active"
        )

        val outcome = adapter.verifyOperationOutcome(
            operationType = "TOGGLE_ACTIVE",
            targetIdentifier = "301",
            baselineEvidence = "Suspended"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for CHANGE_PLAN returns VERIFIED_SUCCESS when accountIndex matches target baseline plan.
     * Seam: JVM headless unit test.
     * Independent Oracle: Current accountIndex 8 == baseline "8" -> SasVerificationOutcome.VERIFIED_SUCCESS.
     */
    @Test
    fun verifyOperationOutcome_changePlan_planMatches_verifiedSuccess() = runTest {
        fakeGateway.userDetailResult = UserDetail(
            userIndexLower = 401,
            accountIndexLower = 8
        )

        val outcome = adapter.verifyOperationOutcome(
            operationType = "CHANGE_PLAN",
            targetIdentifier = "401",
            baselineEvidence = "8"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_SUCCESS, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for CHANGE_PLAN returns VERIFIED_FAILURE when accountIndex differs from target baseline plan.
     * Seam: JVM headless unit test.
     * Independent Oracle: Current accountIndex 4 != baseline "8" -> SasVerificationOutcome.VERIFIED_FAILURE.
     */
    @Test
    fun verifyOperationOutcome_changePlan_planDiffers_verifiedFailure() = runTest {
        fakeGateway.userDetailResult = UserDetail(
            userIndexLower = 401,
            accountIndexLower = 4
        )

        val outcome = adapter.verifyOperationOutcome(
            operationType = "CHANGE_PLAN",
            targetIdentifier = "401",
            baselineEvidence = "8"
        )

        assertEquals(SasVerificationOutcome.VERIFIED_FAILURE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for PASSWORD is write-only and strictly returns INCONCLUSIVE.
     * Seam: JVM headless unit test.
     * Independent Oracle: Operation PASSWORD -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyOperationOutcome_password_strictlyInconclusive() = runTest {
        val outcome = adapter.verifyOperationOutcome(
            operationType = "PASSWORD",
            targetIdentifier = "user_pwd"
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    /**
     * Claim: verifyOperationOutcome for unrecognized operation types strictly returns INCONCLUSIVE.
     * Seam: JVM headless unit test.
     * Independent Oracle: Unknown operation "RANDOM_OP" -> SasVerificationOutcome.INCONCLUSIVE.
     */
    @Test
    fun verifyOperationOutcome_unknownOperation_strictlyInconclusive() = runTest {
        val outcome = adapter.verifyOperationOutcome(
            operationType = "RANDOM_OP",
            targetIdentifier = "target_123"
        )

        assertEquals(SasVerificationOutcome.INCONCLUSIVE, outcome)
    }

    // ========================================================================
    // 11. Error Propagation Contracts
    // ========================================================================

    /**
     * Claim: safeGatewayCall maps unexpected java.io.IOException to typed SasTransportException.
     * Seam: JVM headless unit test.
     * Independent Oracle: IOException -> SasTransportException.
     */
    @Test
    fun errorPropagation_generalIOException_mappedToSasTransportException() = runTest {
        fakeGateway.searchException = IOException("Connection reset by peer")

        try {
            adapter.searchSubscribers("test")
            fail("Expected SasTransportException")
        } catch (e: SasTransportException) {
            assertTrue(e.message?.contains("Connection reset by peer") == true)
        }
    }

    /**
     * Claim: safeGatewayCall maps unexpected generic RuntimeException to typed SasInconclusiveException.
     * Seam: JVM headless unit test.
     * Independent Oracle: RuntimeException -> SasInconclusiveException.
     */
    @Test
    fun errorPropagation_genericException_mappedToSasInconclusiveException() = runTest {
        fakeGateway.searchException = IllegalStateException("Unexpected gateway state")

        try {
            adapter.searchSubscribers("test")
            fail("Expected SasInconclusiveException")
        } catch (e: SasInconclusiveException) {
            assertTrue(e.message?.contains("Unexpected gateway state") == true)
        }
    }

    // ========================================================================
    // Fake Earthlink Gateway Implementation
    // ========================================================================

    class FakeEarthlinkGateway : EarthlinkGateway {
        val getBalanceCalls = AtomicInteger(0)
        val searchUsersCalls = AtomicInteger(0)
        val getUserDetailCalls = AtomicInteger(0)
        val createUserUsingDepositCalls = AtomicInteger(0)
        val refillUserDepositCalls = AtomicInteger(0)
        val toggleUserActiveCalls = AtomicInteger(0)
        val changeAccountTypeCalls = AtomicInteger(0)
        val changeUserPasswordCalls = AtomicInteger(0)

        var balanceResult: Double = 100000.0
        var balanceException: Throwable? = null

        var searchResponse: UserListResponse = UserListResponse(itemsList = emptyList(), totalCount = 0)
        var searchException: Throwable? = null
        var lastSearchQuery: String? = null
        var lastSearchStartIndex: Int? = null
        var lastSearchRowCount: Int? = null

        var userDetailResult: UserDetail = UserDetail()
        var userDetailException: Throwable? = null
        var lastUserDetailIndex: Int? = null

        var usernameAvailable: Boolean = true

        var createUserUsingDepositResult: String? = "generated_pass_123"
        var createUserUsingDepositException: Throwable? = null
        var lastCreateUsername: String? = null
        var lastCreatePhone: String? = null
        var lastCreateFullName: String? = null
        var lastCreateAccountIndex: Int? = null
        var lastCreateDepositPass: String? = null

        var toggleUserActiveResult: Boolean = true
        var toggleUserActiveException: Throwable? = null
        var lastToggleUserIndex: Int? = null
        var lastToggleActiveState: Boolean? = null

        var changeAccountTypeResult: Boolean = true
        var changeAccountTypeException: Throwable? = null
        var lastChangeAccountUserIndex: Int? = null
        var lastChangeAccountUserId: String? = null
        var lastChangeAccountNewIndex: Int? = null

        var changeUserPasswordResult: Boolean = true
        var changeUserPasswordException: Throwable? = null
        var lastChangePasswordUserIndex: Int? = null
        var lastChangePasswordUserId: String? = null
        var lastChangePasswordNewPass: String? = null

        override suspend fun getBalance(): Double {
            getBalanceCalls.incrementAndGet()
            balanceException?.let { throw it }
            return balanceResult
        }

        override suspend fun searchUsers(query: String, startIndex: Int, rowCount: Int): UserListResponse {
            searchUsersCalls.incrementAndGet()
            lastSearchQuery = query
            lastSearchStartIndex = startIndex
            lastSearchRowCount = rowCount
            searchException?.let { throw it }
            return searchResponse
        }

        override suspend fun getUserDetail(userIndex: Int): UserDetail {
            getUserDetailCalls.incrementAndGet()
            lastUserDetailIndex = userIndex
            userDetailException?.let { throw it }
            return userDetailResult
        }

        override suspend fun checkUsernameAvailable(userId: String): Boolean = usernameAvailable

        override suspend fun createUserUsingDeposit(
            username: String,
            phone: String,
            fullName: String,
            accountIndex: Int,
            depositPassword: String
        ): String? {
            createUserUsingDepositCalls.incrementAndGet()
            lastCreateUsername = username
            lastCreatePhone = phone
            lastCreateFullName = fullName
            lastCreateAccountIndex = accountIndex
            lastCreateDepositPass = depositPassword
            createUserUsingDepositException?.let { throw it }
            return createUserUsingDepositResult
        }

        override suspend fun toggleUserActive(userIndex: Int, active: Boolean): Boolean {
            toggleUserActiveCalls.incrementAndGet()
            lastToggleUserIndex = userIndex
            lastToggleActiveState = active
            toggleUserActiveException?.let { throw it }
            return toggleUserActiveResult
        }

        override suspend fun refillUserDeposit(userId: String, depositPassword: String): Boolean {
            refillUserDepositCalls.incrementAndGet()
            return true
        }

        override suspend fun changeAccountType(userIndex: Int, userId: String, accountIndex: Int): Boolean {
            changeAccountTypeCalls.incrementAndGet()
            lastChangeAccountUserIndex = userIndex
            lastChangeAccountUserId = userId
            lastChangeAccountNewIndex = accountIndex
            changeAccountTypeException?.let { throw it }
            return changeAccountTypeResult
        }

        override suspend fun changeUserPassword(userIndex: Int, userId: String, newPass: String): Boolean {
            changeUserPasswordCalls.incrementAndGet()
            lastChangePasswordUserIndex = userIndex
            lastChangePasswordUserId = userId
            lastChangePasswordNewPass = newPass
            changeUserPasswordException?.let { throw it }
            return changeUserPasswordResult
        }

        // Unused interface stubs
        override suspend fun login(username: String, password: String): LoginResponse = LoginResponse("token", "Bearer", 3600)
        override suspend fun getTestUsersCount(affiliateIndex: Int?): Int = 0
        override suspend fun getActiveTestUsersCount(): Int = 0
        override suspend fun getPrepaidNeeded(): Double = 0.0
        override suspend fun getPackages(): List<AccountPackage> = emptyList()
        override suspend fun getAccountCost(accountIndex: Int): Double = 0.0
        override suspend fun autocompleteUser(query: String): List<AutocompleteUser> = emptyList()
        override suspend fun checkCustomerByPhone(phone: String): String? = null
        override suspend fun createCustomer(name: String, phone: String): Boolean = true
        override suspend fun createTestUser(username: String, phone: String, fullName: String, accountIndex: Int): String? = null
        override suspend fun extendUser(userIndex: Int): Boolean = true
        override suspend fun getAccountStatement(startIndex: Int, rowCount: Int, query: String): List<AccountStatementItem> = emptyList()
        override suspend fun showUserPassword(userIndex: Int, userId: String): String = ""
        override suspend fun showAccountPassword(userIndex: Int, userId: String): String = ""
        override suspend fun changeAccountPassword(userIndex: Int, userId: String, newPass: String): Boolean = true
        override suspend fun updateUserDisplayName(userIndex: Int, newName: String): Boolean = true
    }
}
