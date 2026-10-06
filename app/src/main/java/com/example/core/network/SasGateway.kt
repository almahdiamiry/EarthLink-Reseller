package com.example.core.network

/**
 * Core SAS Gateway Provider Seam (com.example.core.network)
 *
 * Defines the minimal common provider abstraction representing shared product capabilities
 * across SAS providers (EarthLink and SAMM / Alamiry).
 *
 * Design Invariants:
 * 1. Provider-neutral subscriber operations (search, get, create, suspend, activate, renew, changePlan, changePassword).
 * 2. Provider-specific EarthLink operations (balance, refill, prepaid, statement, deposit) remain on
 *    EarthlinkGateway / EarthlinkNetwork and are never forced into this seam.
 * 3. Typed operation results (SasOperationResult) enforce explicit handling of synchronous application,
 *    asynchronous queuing, explicit provider rejection, inconclusive states, and unsupported operations.
 * 4. Operation-aware provider verification (verifyOperationOutcome) enables recovery sweeps to evaluate
 *    provider state against operation intent without blind redispatch or generic weak oracles.
 * 5. Strict fail-closed exception hierarchy (SasGatewayException) guarantees zero unverified ledger mutation.
 */

// ============================================================================
// 1. SAS Gateway Exception Hierarchy
// ============================================================================

/**
 * Base exception for all SAS gateway communication and business failures.
 * Root of the fail-closed network error taxonomy.
 */
open class SasGatewayException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Transport Uncertainty: Gateway call timed out or failed at transport layer.
 * The external state of the operation is unknown.
 * Semantic handling: PENDING (dispatchClaimCount = 1). Zero ledger mutation.
 */
class SasTransportException(
    message: String,
    cause: Throwable? = null
) : SasGatewayException(message, cause)

/**
 * Inconclusive Outcome: Gateway accepted request or returned an ambiguous response,
 * or vital payload data was missing.
 * Semantic handling: PENDING / recovery sweep required. Zero ledger mutation.
 */
class SasInconclusiveException(
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null
) : SasGatewayException(message, cause)

/**
 * Definitive Business Rejection: Provider explicitly processed and rejected the request
 * (e.g. 422 Unprocessable Entity, 409 Conflict, validation failure).
 * Semantic handling: FAILED. Zero ledger mutation.
 */
class SasBusinessException(
    val statusCode: Int? = null,
    val errorMessage: String,
    cause: Throwable? = null
) : SasGatewayException(errorMessage, cause)

/**
 * Authentication / Session Failure: Invalid credentials, token expired, or unauthorized (401 / 403).
 * Semantic handling: FAILED. Clear cached credentials. Zero ledger mutation.
 */
class SasAuthException(
    message: String,
    cause: Throwable? = null
) : SasGatewayException(message, cause)

/**
 * Resource Not Found: Target subscriber or resource does not exist on provider (404).
 */
class SasNotFoundException(
    val statusCode: Int? = 404,
    message: String,
    cause: Throwable? = null
) : SasGatewayException(message, cause)

/**
 * Unsupported Operation: The targeted provider does not support this operation
 * (e.g. REFILL on SAMM). Fails closed immediately with zero ledger mutation.
 */
class SasUnsupportedOperationException(
    message: String,
    cause: Throwable? = null
) : SasGatewayException(message, cause)

// ============================================================================
// 2. Exception Mapping & Bridge
// ============================================================================

/**
 * Maps EarthlinkGatewayException instances cleanly to typed SasGatewayException counterparts
 * without losing root cause or status code.
 */
fun EarthlinkGatewayException.toSasGatewayException(): SasGatewayException = when (this) {
    is EarthlinkTransportException -> SasTransportException(message ?: "EarthLink transport failure", this)
    is EarthlinkInconclusiveException -> SasInconclusiveException(statusCode, message, this)
    is EarthlinkBusinessException -> SasBusinessException(statusCode, errorMessage, this)
    is EarthlinkAuthException -> SasAuthException(message ?: "EarthLink auth failure", this)
}

/**
 * Maps general throwables to safe, fail-closed SasGatewayException.
 */
fun Throwable.toSasGatewayException(): SasGatewayException = when (this) {
    is SasGatewayException -> this
    is EarthlinkGatewayException -> this.toSasGatewayException()
    is java.net.SocketTimeoutException,
    is java.net.UnknownHostException,
    is java.net.ConnectException,
    is java.io.IOException -> SasTransportException(message ?: "Network transport failure", this)
    else -> SasInconclusiveException(
        statusCode = null,
        message = message ?: "Unexpected gateway exception: ${this::class.java.simpleName}",
        cause = this
    )
}

// ============================================================================
// 3. Domain View Models
// ============================================================================

/**
 * Normalized domain view of a subscriber across different SAS providers (EarthLink, SAMM).
 * Hides provider-specific wire schemas and types (e.g. numeric ID vs string ID).
 */
data class SasSubscriberView(
    val subscriberId: String,
    val username: String,
    val displayName: String? = null,
    val status: String,
    val planId: String? = null,
    val planName: String? = null,
    val expiresAt: String? = null,
    val phone: String? = null
)

// ============================================================================
// 4. Typed Operation Results
// ============================================================================

/**
 * Typed outcome of a mutation dispatch against a SAS provider.
 * Eliminates lossy Boolean returns and enforces explicit handling of
 * asynchronous queuing, rejection reasons, inconclusive network states,
 * and unsupported capabilities.
 */
sealed class SasOperationResult {
    /** Operation was applied immediately and synchronously by the provider. */
    data class Applied(val evidence: String? = null) : SasOperationResult()

    /** Operation was accepted and queued by the provider for asynchronous execution. */
    data class Queued(val commandReference: String? = null) : SasOperationResult()

    /** Operation was explicitly rejected by the provider with a reason. */
    data class Rejected(val code: Int? = null, val reason: String) : SasOperationResult()

    /** Provider outcome could not be definitively confirmed (e.g. transport timeout). */
    data class Inconclusive(val reason: String) : SasOperationResult()

    /** Operation is not supported by this provider (e.g. REFILL on SAMM). Fail closed. */
    data class Unsupported(val reason: String) : SasOperationResult()

    val isApplied: Boolean get() = this is Applied
    val isQueued: Boolean get() = this is Queued
    val isRejected: Boolean get() = this is Rejected
    val isInconclusive: Boolean get() = this is Inconclusive
    val isUnsupported: Boolean get() = this is Unsupported
}

// ============================================================================
// 5. Verification Outcomes
// ============================================================================

/**
 * Outcome of an operation-aware verification check during recovery sweeps.
 * Used to evaluate provider state against operation intent without blind redispatch.
 */
enum class SasVerificationOutcome {
    /** Provider state definitively confirms the operation took effect. */
    VERIFIED_SUCCESS,

    /** Provider state definitively confirms the operation did NOT take effect. */
    VERIFIED_FAILURE,

    /** Provider state or transport error prevents definitive determination. */
    INCONCLUSIVE
}

// ============================================================================
// 6. SAS Gateway Provider Interface
// ============================================================================

/**
 * Minimal common provider seam representing real shared subscriber capabilities
 * across EarthLink and SAMM providers.
 *
 * Specific EarthLink-only capabilities (balance, refills, prepaid, statements, deposit)
 * remain on EarthlinkNetwork/EarthlinkGateway and are never forced into this interface.
 */
interface SasGateway {
    /** Canonical provider identifier (e.g. "EARTHLINK", "ALAMIRY"). */
    val providerName: String

    /**
     * Search subscribers matching query string.
     */
    suspend fun searchSubscribers(query: String, page: Int = 1, pageSize: Int = 20): List<SasSubscriberView>

    /**
     * Retrieve subscriber by ID. Returns null if subscriber does not exist.
     */
    suspend fun getSubscriber(subscriberId: String): SasSubscriberView?

    /**
     * Create a new subscriber account.
     */
    suspend fun createSubscriber(
        username: String,
        planId: String,
        password: String,
        firstName: String,
        lastName: String,
        phone: String? = null
    ): SasOperationResult

    /**
     * Suspend an active subscriber account.
     */
    suspend fun suspendSubscriber(subscriberId: String): SasOperationResult

    /**
     * Activate a suspended subscriber account.
     */
    suspend fun activateSubscriber(subscriberId: String): SasOperationResult

    /**
     * Renew an existing subscriber's current plan.
     */
    suspend fun renewSubscriber(subscriberId: String): SasOperationResult

    /**
     * Change a subscriber's plan to newPlanId.
     */
    suspend fun changePlan(subscriberId: String, newPlanId: String): SasOperationResult

    /**
     * Change a subscriber's password.
     */
    suspend fun changePassword(subscriberId: String, newPassword: String): SasOperationResult

    /**
     * Operation-aware verification for recovery sweeps.
     * Evaluates provider state against operation intent without blind redispatch.
     *
     * @param operationType Type of operation being verified (e.g. "ACTIVATION", "RENEWAL", "TOGGLE_ACTIVE", "CHANGE_PLAN")
     * @param targetIdentifier Identifier of target subscriber or username
     * @param baselineEvidence Baseline evidence captured prior to dispatch (e.g. previous expiration date)
     */
    suspend fun verifyOperationOutcome(
        operationType: String,
        targetIdentifier: String,
        baselineEvidence: String? = null
    ): SasVerificationOutcome

    /**
     * Check network connectivity and credentials with the provider gateway.
     */
    suspend fun checkConnection(): Boolean
}
