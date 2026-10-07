package com.example.core.network

import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import com.example.core.model.UserListResponse
import com.example.domain.repository.EarthlinkGateway

/**
 * EarthLink SAS Gateway Adapter (com.example.core.network)
 *
 * Implements [SasGateway] for the EARTHLINK provider by delegating common subscriber operations
 * to the existing [EarthlinkGateway] implementation without altering EarthLink-specific behavior
 * or methods (balance, prepaid, statement, test-user, deposit passwords).
 *
 * Design Invariants:
 * 1. Provider Identity: providerName is strictly [SasProviders.EARTHLINK].
 * 2. Identity Normalization:
 *    - Ingress: EarthLink integer userIndex normalized to domain String (SasSubscriberView.subscriberId = userIndex.toString()).
 *    - Egress: Domain String subscriberId parsed to integer for EarthlinkGateway endpoints, throwing [SasBusinessException] if non-numeric.
 * 3. EarthLink-Only Operations Preserved:
 *    - Specialized portal operations (balance, statements, prepaid, test users, refill deposit passwords) remain directly on [EarthlinkGateway].
 * 4. Operation Oracles (verifyOperationOutcome):
 *    - ACTIVATION: Confirms subscriber existence via searchUsers or checkUsernameAvailable (taken -> VERIFIED_SUCCESS, available -> VERIFIED_FAILURE).
 *    - RENEWAL / REFILL: Confirms expiration advancement against baselineEvidence (changed -> VERIFIED_SUCCESS, unchanged -> VERIFIED_FAILURE).
 *    - TOGGLE_ACTIVE: Status equality comparison against target baseline status.
 *    - CHANGE_PLAN: Effective plan ID comparison against target baseline plan ID.
 *    - PASSWORD: Write-only; strictly INCONCLUSIVE.
 * 5. Fail-Closed Error Taxonomy:
 *    - EarthlinkGateway exceptions (inheriting from [SasGatewayException]) and unexpected Throwables are caught and propagated
 *      strictly within the typed [SasGatewayException] hierarchy.
 */
class EarthlinkSasGatewayAdapter(
    private val delegate: EarthlinkGateway
) : SasGateway {

    override val providerName: String = SasProviders.EARTHLINK

    // ========================================================================
    // ID Normalization & Safe Call Helpers
    // ========================================================================

    private fun parseSubscriberId(subscriberId: String): Int {
        return subscriberId.toIntOrNull()
            ?: throw SasBusinessException(
                statusCode = null,
                errorMessage = "Invalid EarthLink subscriber ID integer: '$subscriberId'"
            )
    }

    private fun parsePlanId(planId: String): Int {
        return planId.toIntOrNull()
            ?: throw SasBusinessException(
                statusCode = null,
                errorMessage = "Invalid EarthLink plan ID integer: '$planId'"
            )
    }

    private suspend fun <T> safeGatewayCall(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: EarthlinkGatewayException) {
            throw e.toSasGatewayException()
        } catch (e: SasGatewayException) {
            throw e
        } catch (t: Throwable) {
            throw t.toSasGatewayException()
        }
    }

    // ========================================================================
    // Domain Mapping Helpers (Ingress Normalization)
    // ========================================================================

    private fun UserListItem.toSasSubscriberView(): SasSubscriberView {
        val fullName = listOfNotNull(displayName?.trim(), customerName?.trim())
            .firstOrNull { it.isNotEmpty() }
        val statusStr = accountStatus ?: if (userActive == true) "Active" else if (userActive == false) "Suspended" else "UNKNOWN"
        val expiry = expirationDate ?: manualExpirationDate ?: accountExpirationDate
        return SasSubscriberView(
            subscriberId = userIndex.toString(),
            username = userID,
            displayName = fullName,
            status = statusStr,
            planId = null,
            planName = packageName,
            expiresAt = expiry,
            phone = mobileNumber
        )
    }

    private fun UserDetail.toSasSubscriberView(): SasSubscriberView {
        val fullName = listOfNotNull(customerFullName?.trim(), displayNameLower?.trim(), displayNameUpper?.trim())
            .firstOrNull { it.isNotEmpty() }
        val statusStr = accountStatus ?: if (userActive == true) "Active" else if (userActive == false) "Suspended" else "UNKNOWN"
        val expiry = expirationDate ?: manualExpirationDate ?: accountExpirationDate
        return SasSubscriberView(
            subscriberId = userIndex.toString(),
            username = userID,
            displayName = fullName,
            status = statusStr,
            planId = accountIndex?.toString(),
            planName = packageName,
            expiresAt = expiry,
            phone = mobileNumber
        )
    }

    // ========================================================================
    // Read Operations
    // ========================================================================

    override suspend fun searchSubscribers(
        query: String,
        page: Int,
        pageSize: Int
    ): List<SasSubscriberView> {
        val startIndex = if (page <= 1) 0 else (page - 1) * pageSize
        val response = safeGatewayCall {
            delegate.searchUsers(query = query.trim(), startIndex = startIndex, rowCount = pageSize)
        }
        return response.itemsList?.map { it.toSasSubscriberView() } ?: emptyList()
    }

    override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? {
        val userIndex = subscriberId.toIntOrNull() ?: return null
        return try {
            val detail = delegate.getUserDetail(userIndex)
            detail.toSasSubscriberView()
        } catch (e: SasNotFoundException) {
            null
        } catch (e: EarthlinkBusinessException) {
            if (e.statusCode == 404) null else throw e.toSasGatewayException()
        } catch (e: EarthlinkGatewayException) {
            throw e.toSasGatewayException()
        } catch (e: SasGatewayException) {
            throw e
        } catch (t: Throwable) {
            throw t.toSasGatewayException()
        }
    }

    override suspend fun checkConnection(): Boolean {
        return try {
            delegate.getBalance()
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ========================================================================
    // Mutation Operations
    // ========================================================================

    override suspend fun createSubscriber(
        username: String,
        planId: String,
        password: String,
        firstName: String,
        lastName: String,
        phone: String?
    ): SasOperationResult {
        val accountIndex = parsePlanId(planId)
        val fullName = listOfNotNull(firstName.trim(), lastName.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { username }
        val result = safeGatewayCall {
            delegate.createUserUsingDeposit(
                username = username,
                phone = phone.orEmpty(),
                fullName = fullName,
                accountIndex = accountIndex,
                depositPassword = password
            )
        }
        return if (result != null) {
            SasOperationResult.Applied(evidence = result)
        } else {
            SasOperationResult.Rejected(reason = "EarthLink subscriber creation rejected")
        }
    }

    override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult {
        val userIndex = parseSubscriberId(subscriberId)
        val success = safeGatewayCall {
            delegate.toggleUserActive(userIndex, false)
        }
        return if (success) {
            SasOperationResult.Applied()
        } else {
            SasOperationResult.Rejected(reason = "EarthLink toggleUserActive(false) rejected")
        }
    }

    override suspend fun activateSubscriber(subscriberId: String): SasOperationResult {
        val userIndex = parseSubscriberId(subscriberId)
        val success = safeGatewayCall {
            delegate.toggleUserActive(userIndex, true)
        }
        return if (success) {
            SasOperationResult.Applied()
        } else {
            SasOperationResult.Rejected(reason = "EarthLink toggleUserActive(true) rejected")
        }
    }

    override suspend fun renewSubscriber(subscriberId: String): SasOperationResult {
        // EarthLink subscriber renewal requires deposit password authorization via EarthlinkGateway.refillUserDeposit.
        // Fails closed as SasUnsupportedOperationException per product contract.
        throw SasUnsupportedOperationException(
            "EarthLink subscriber renewal requires deposit password authorization via EarthlinkGateway.refillUserDeposit"
        )
    }

    override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult {
        val userIndex = parseSubscriberId(subscriberId)
        val accountIndex = parsePlanId(newPlanId)
        val success = safeGatewayCall {
            delegate.changeAccountType(userIndex, subscriberId, accountIndex)
        }
        return if (success) {
            SasOperationResult.Applied()
        } else {
            SasOperationResult.Rejected(reason = "EarthLink changeAccountType rejected")
        }
    }

    override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult {
        val userIndex = parseSubscriberId(subscriberId)
        val success = safeGatewayCall {
            delegate.changeUserPassword(userIndex, subscriberId, newPassword)
        }
        return if (success) {
            SasOperationResult.Applied()
        } else {
            SasOperationResult.Rejected(reason = "EarthLink changeUserPassword rejected")
        }
    }

    // ========================================================================
    // Operation Verification Oracle (Recovery Sweeps)
    // ========================================================================

    override suspend fun verifyOperationOutcome(
        operationType: String,
        targetIdentifier: String,
        baselineEvidence: String?
    ): SasVerificationOutcome {
        return when (operationType.uppercase()) {
            "ACTIVATION" -> {
                val userIndex = targetIdentifier.toIntOrNull()
                if (userIndex != null) {
                    try {
                        delegate.getUserDetail(userIndex)
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } catch (e: SasNotFoundException) {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    } catch (e: EarthlinkBusinessException) {
                        if (e.statusCode == 404) SasVerificationOutcome.VERIFIED_FAILURE
                        else SasVerificationOutcome.INCONCLUSIVE
                    } catch (_: Throwable) {
                        SasVerificationOutcome.INCONCLUSIVE
                    }
                } else {
                    try {
                        val search = delegate.searchUsers(query = targetIdentifier, startIndex = 0, rowCount = 10)
                        val match = search.itemsList?.any { it.userID.equals(targetIdentifier, ignoreCase = true) } == true
                        if (match) {
                            SasVerificationOutcome.VERIFIED_SUCCESS
                        } else {
                            val available = try {
                                delegate.checkUsernameAvailable(targetIdentifier)
                            } catch (_: Throwable) {
                                null
                            }
                            if (available == false) {
                                SasVerificationOutcome.VERIFIED_SUCCESS
                            } else if (available == true) {
                                SasVerificationOutcome.VERIFIED_FAILURE
                            } else {
                                SasVerificationOutcome.VERIFIED_FAILURE
                            }
                        }
                    } catch (_: Throwable) {
                        SasVerificationOutcome.INCONCLUSIVE
                    }
                }
            }
            "RENEWAL", "REFILL" -> {
                val userIndex = targetIdentifier.toIntOrNull()
                try {
                    val detail = if (userIndex != null) {
                        delegate.getUserDetail(userIndex)
                    } else {
                        val search = delegate.searchUsers(query = targetIdentifier, startIndex = 0, rowCount = 10)
                        val item = search.itemsList?.find { it.userID.equals(targetIdentifier, ignoreCase = true) }
                            ?: return SasVerificationOutcome.VERIFIED_FAILURE
                        delegate.getUserDetail(item.userIndex)
                    }
                    val currentExpiry = detail.expirationDate ?: detail.manualExpirationDate ?: detail.accountExpirationDate
                    if (currentExpiry != null && currentExpiry != baselineEvidence) {
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } else {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    }
                } catch (e: SasNotFoundException) {
                    SasVerificationOutcome.VERIFIED_FAILURE
                } catch (e: EarthlinkBusinessException) {
                    if (e.statusCode == 404) SasVerificationOutcome.VERIFIED_FAILURE
                    else SasVerificationOutcome.INCONCLUSIVE
                } catch (_: Throwable) {
                    SasVerificationOutcome.INCONCLUSIVE
                }
            }
            "TOGGLE_ACTIVE" -> {
                val userIndex = targetIdentifier.toIntOrNull()
                try {
                    val detail = if (userIndex != null) {
                        delegate.getUserDetail(userIndex)
                    } else {
                        val search = delegate.searchUsers(query = targetIdentifier, startIndex = 0, rowCount = 10)
                        val item = search.itemsList?.find { it.userID.equals(targetIdentifier, ignoreCase = true) }
                            ?: return SasVerificationOutcome.VERIFIED_FAILURE
                        delegate.getUserDetail(item.userIndex)
                    }
                    val currentStatus = detail.accountStatus ?: if (detail.userActive == true) "Active" else "Suspended"
                    if (currentStatus.equals(baselineEvidence, ignoreCase = true)) {
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } else {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    }
                } catch (e: SasNotFoundException) {
                    SasVerificationOutcome.VERIFIED_FAILURE
                } catch (e: EarthlinkBusinessException) {
                    if (e.statusCode == 404) SasVerificationOutcome.VERIFIED_FAILURE
                    else SasVerificationOutcome.INCONCLUSIVE
                } catch (_: Throwable) {
                    SasVerificationOutcome.INCONCLUSIVE
                }
            }
            "CHANGE_PLAN" -> {
                val userIndex = targetIdentifier.toIntOrNull()
                try {
                    val detail = if (userIndex != null) {
                        delegate.getUserDetail(userIndex)
                    } else {
                        val search = delegate.searchUsers(query = targetIdentifier, startIndex = 0, rowCount = 10)
                        val item = search.itemsList?.find { it.userID.equals(targetIdentifier, ignoreCase = true) }
                            ?: return SasVerificationOutcome.VERIFIED_FAILURE
                        delegate.getUserDetail(item.userIndex)
                    }
                    val currentPlanId = detail.accountIndex?.toString()
                    if (currentPlanId != null && currentPlanId == baselineEvidence) {
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } else {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    }
                } catch (e: SasNotFoundException) {
                    SasVerificationOutcome.VERIFIED_FAILURE
                } catch (e: EarthlinkBusinessException) {
                    if (e.statusCode == 404) SasVerificationOutcome.VERIFIED_FAILURE
                    else SasVerificationOutcome.INCONCLUSIVE
                } catch (_: Throwable) {
                    SasVerificationOutcome.INCONCLUSIVE
                }
            }
            "PASSWORD" -> {
                SasVerificationOutcome.INCONCLUSIVE
            }
            else -> {
                SasVerificationOutcome.INCONCLUSIVE
            }
        }
    }
}
