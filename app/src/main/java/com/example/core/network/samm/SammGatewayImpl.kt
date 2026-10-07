package com.example.core.network.samm

import com.example.core.model.SasProviders
import com.example.core.network.SasBusinessException
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayException
import com.example.core.network.SasInconclusiveException
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberView
import com.example.core.network.SasVerificationOutcome
import com.example.core.network.toSasGatewayException
import retrofit2.Response

/**
 * SAMM Gateway Implementation (com.example.core.network.samm)
 *
 * Implements [SasGateway] for the ALAMIRY provider by communicating with SAMM 5.1.15 REST API
 * via [SammApiService].
 *
 * Design Invariants:
 * 1. Provider Identity: providerName is strictly [SasProviders.ALAMIRY].
 * 2. Identity Normalization:
 *    - Ingress: SAMM integer customer ID (dto.id: Int) normalized to domain String (SasSubscriberView.subscriberId = dto.id.toString()).
 *    - Egress: Domain String subscriber ID parsed to integer for SAMM wire endpoints, throwing [SasBusinessException] if invalid.
 * 3. Exact Operation Oracles:
 *    - ACTIVATION: Exact username lookup via GET /customers?username=<user>. Found -> VERIFIED_SUCCESS, Not found -> VERIFIED_FAILURE.
 *    - RENEWAL: Expiration advance comparison against baseline evidence. Changed -> VERIFIED_SUCCESS, Unchanged -> VERIFIED_FAILURE.
 *    - TOGGLE_ACTIVE: Customer status equality comparison against expected target status baseline.
 *    - CHANGE_PLAN: Customer effective plan ID comparison against expected plan ID baseline.
 *    - PASSWORD: Write-only on SAMM; strictly INCONCLUSIVE.
 * 4. Fail-Closed Error Taxonomy: Network/HTTP failures are mapped via [SammNetworkClient.parseSammError]
 *    to typed [SasGatewayException] hierarchy (401 -> SasAuthException, 404 -> SasNotFoundException, 422 -> SasBusinessException, etc.).
 * 5. Security & Privacy: Passwords are write-only, never logged, persisted, or leaked.
 */
class SammGatewayImpl(
    private val apiService: SammApiService
) : SasGateway {

    constructor(client: SammNetworkClient) : this(client.apiService)

    override val providerName: String = SasProviders.ALAMIRY

    // ========================================================================
    // ID Normalization Helpers
    // ========================================================================

    private fun parseCustomerId(subscriberId: String): Int {
        return subscriberId.toIntOrNull()
            ?: throw SasBusinessException(
                statusCode = null,
                errorMessage = "Invalid SAMM customer ID integer: '$subscriberId'"
            )
    }

    private fun parsePlanId(planId: String): Int {
        return planId.toIntOrNull()
            ?: throw SasBusinessException(
                statusCode = null,
                errorMessage = "Invalid SAMM plan ID integer: '$planId'"
            )
    }

    private suspend fun <T> safeApiCall(call: suspend () -> Response<T>): Response<T> {
        val response = try {
            call()
        } catch (e: SasGatewayException) {
            throw e
        } catch (t: Throwable) {
            throw t.toSasGatewayException()
        }
        if (!response.isSuccessful) {
            throw parseSammError(response)
        }
        return response
    }

    // ========================================================================
    // Domain Mapping Helpers (Ingress Normalization)
    // ========================================================================

    private fun CustomerListItem.toSubscriberView(): SasSubscriberView {
        val fullName = listOfNotNull(firstname?.trim(), lastname?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { null }
        return SasSubscriberView(
            subscriberId = id.toString(),
            username = username,
            displayName = fullName,
            status = status ?: "UNKNOWN",
            planId = planId?.toString(),
            planName = planName,
            expiresAt = expirationDate,
            phone = mobile
        )
    }

    private fun CustomerResponse.toSubscriberView(): SasSubscriberView {
        val fullName = listOfNotNull(firstname?.trim(), lastname?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { null }
        return SasSubscriberView(
            subscriberId = id.toString(),
            username = username,
            displayName = fullName,
            status = status ?: "UNKNOWN",
            planId = (effectivePlanId ?: planId)?.toString(),
            planName = planName,
            expiresAt = expirationDate,
            phone = mobile
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
        val trimmed = query.trim()
        val isExactUsername = trimmed.isNotEmpty() && !trimmed.contains(" ")
        val response = try {
            if (isExactUsername) {
                apiService.listCustomers(username = trimmed, page = page, perPage = pageSize)
            } else {
                apiService.listCustomers(search = trimmed.ifEmpty { null }, page = page, perPage = pageSize)
            }
        } catch (e: SasGatewayException) {
            throw e
        } catch (t: Throwable) {
            throw t.toSasGatewayException()
        }
        if (response.code() == 404) {
            return emptyList()
        }
        if (!response.isSuccessful) {
            throw parseSammError(response)
        }
        return response.body()?.items?.map { it.toSubscriberView() } ?: emptyList()
    }

    override suspend fun getSubscriber(subscriberId: String): SasSubscriberView? {
        val customerId = parseCustomerId(subscriberId)
        val response = try {
            apiService.getCustomer(customerId)
        } catch (e: SasGatewayException) {
            throw e
        } catch (t: Throwable) {
            throw t.toSasGatewayException()
        }
        if (response.code() == 404) {
            return null
        }
        if (!response.isSuccessful) {
            throw parseSammError(response)
        }
        return response.body()?.toSubscriberView()
    }

    suspend fun listPlans(enabledOnly: Boolean? = null): List<PlanResponse> {
        val response = safeApiCall { apiService.listPlans(enabledOnly = enabledOnly) }
        return response.body().orEmpty()
    }

    override suspend fun checkConnection(): Boolean {
        return try {
            val response = apiService.getMe()
            response.isSuccessful
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
        val numericPlanId = parsePlanId(planId)
        val request = CustomerCreate(
            username = username,
            password = password,
            firstname = firstName,
            lastname = lastName,
            planId = numericPlanId,
            generateInvoice = false,
            mobile = phone
        )
        val response = safeApiCall { apiService.createCustomer(request) }
        val customer = response.body()
            ?: throw SasInconclusiveException(
                statusCode = response.code(),
                message = "Empty response body from SAMM customer creation"
            )
        return SasOperationResult.Applied(evidence = customer.id.toString())
    }

    override suspend fun suspendSubscriber(subscriberId: String): SasOperationResult {
        val customerId = parseCustomerId(subscriberId)
        safeApiCall { apiService.suspendCustomer(customerId) }
        return SasOperationResult.Applied()
    }

    override suspend fun activateSubscriber(subscriberId: String): SasOperationResult {
        val customerId = parseCustomerId(subscriberId)
        safeApiCall { apiService.activateCustomer(customerId) }
        return SasOperationResult.Applied()
    }

    override suspend fun renewSubscriber(subscriberId: String): SasOperationResult {
        val customerId = parseCustomerId(subscriberId)
        val response = safeApiCall { apiService.renewCustomer(customerId, generateInvoice = false) }
        val body = response.body()
        return SasOperationResult.Applied(evidence = body?.newExpiration)
    }

    override suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult {
        val customerId = parseCustomerId(subscriberId)
        val numericPlanId = parsePlanId(newPlanId)
        val request = AssignPlanRequest(
            planId = numericPlanId,
            generateInvoice = false
        )
        safeApiCall { apiService.assignPlan(customerId, request) }
        return SasOperationResult.Applied()
    }

    override suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult {
        val customerId = parseCustomerId(subscriberId)
        val request = CustomerUpdate(password = newPassword)
        safeApiCall { apiService.updateCustomer(customerId, request) }
        return SasOperationResult.Applied()
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
                try {
                    val response = apiService.listCustomers(username = targetIdentifier)
                    if (response.code() == 404) {
                        return SasVerificationOutcome.VERIFIED_FAILURE
                    }
                    if (!response.isSuccessful) {
                        return SasVerificationOutcome.INCONCLUSIVE
                    }
                    val items = response.body()?.items.orEmpty()
                    val found = items.any { it.username.equals(targetIdentifier, ignoreCase = true) }
                    if (found) SasVerificationOutcome.VERIFIED_SUCCESS else SasVerificationOutcome.VERIFIED_FAILURE
                } catch (_: Throwable) {
                    SasVerificationOutcome.INCONCLUSIVE
                }
            }
            "RENEWAL" -> {
                val customerId = targetIdentifier.toIntOrNull()
                    ?: return SasVerificationOutcome.INCONCLUSIVE
                try {
                    val response = apiService.getCustomer(customerId)
                    if (response.code() == 404) {
                        return SasVerificationOutcome.VERIFIED_FAILURE
                    }
                    if (!response.isSuccessful) {
                        return SasVerificationOutcome.INCONCLUSIVE
                    }
                    val customer = response.body() ?: return SasVerificationOutcome.INCONCLUSIVE
                    if (customer.expirationDate != null && customer.expirationDate != baselineEvidence) {
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } else {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    }
                } catch (_: Throwable) {
                    SasVerificationOutcome.INCONCLUSIVE
                }
            }
            "TOGGLE_ACTIVE" -> {
                val customerId = targetIdentifier.toIntOrNull()
                    ?: return SasVerificationOutcome.INCONCLUSIVE
                try {
                    val response = apiService.getCustomer(customerId)
                    if (response.code() == 404) {
                        return SasVerificationOutcome.VERIFIED_FAILURE
                    }
                    if (!response.isSuccessful) {
                        return SasVerificationOutcome.INCONCLUSIVE
                    }
                    val customer = response.body() ?: return SasVerificationOutcome.INCONCLUSIVE
                    if (customer.status != null && customer.status.equals(baselineEvidence, ignoreCase = true)) {
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } else {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    }
                } catch (_: Throwable) {
                    SasVerificationOutcome.INCONCLUSIVE
                }
            }
            "CHANGE_PLAN" -> {
                val customerId = targetIdentifier.toIntOrNull()
                    ?: return SasVerificationOutcome.INCONCLUSIVE
                try {
                    val response = apiService.getCustomer(customerId)
                    if (response.code() == 404) {
                        return SasVerificationOutcome.VERIFIED_FAILURE
                    }
                    if (!response.isSuccessful) {
                        return SasVerificationOutcome.INCONCLUSIVE
                    }
                    val customer = response.body() ?: return SasVerificationOutcome.INCONCLUSIVE
                    val currentPlanId = (customer.effectivePlanId ?: customer.planId)?.toString()
                    if (currentPlanId != null && currentPlanId == baselineEvidence) {
                        SasVerificationOutcome.VERIFIED_SUCCESS
                    } else {
                        SasVerificationOutcome.VERIFIED_FAILURE
                    }
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
