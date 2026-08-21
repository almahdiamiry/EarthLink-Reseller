package com.example.core.network

/**
 * Core Domain Exception Hierarchy (com.example.core.network)
 * Eliminates keyword heuristic guessing and ensures all network/API boundaries
 * are strictly typed and fail-closed.
 */
sealed class EarthlinkGatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Transport Uncertainty: Outcome is unknown. Gateway call may or may not have reached the ISP.
 * Handled as: UNKNOWN / INCONCLUSIVE -> PENDING (dispatchClaimCount = 1). Zero ledger mutation.
 */
class EarthlinkTransportException(
    message: String,
    cause: Throwable? = null
) : EarthlinkGatewayException(message, cause)

/**
 * Definitive Business Rejection: ISP explicitly processed and rejected the request.
 * Handled as: EXPLICIT_FAILURE -> FAILED. Zero ledger mutation.
 */
class EarthlinkBusinessException(
    val statusCode: Int? = null,
    val errorMessage: String,
    cause: Throwable? = null
) : EarthlinkGatewayException(errorMessage, cause)

/**
 * Authentication / Session Failure: Session expired or invalid credentials.
 * Handled as: EXPLICIT_FAILURE -> FAILED. Token cleared. Zero ledger mutation.
 */
class EarthlinkAuthException(
    message: String,
    cause: Throwable? = null
) : EarthlinkGatewayException(message, cause)

/**
 * Fail-Closed Typed API Result (RC-C: Fail-Closed External API Contract)
 * Eliminates keyword heuristic guessing and ensures all network/API boundaries
 * are strictly typed and fail-closed.
 */
sealed class ApiResult<out T> {
    data class Success<out T>(val data: T) : ApiResult<T>()
    object SuccessEmpty : ApiResult<Nothing>()
    data class Failure(val code: Int?, val message: String, val isRetryable: Boolean = false) : ApiResult<Nothing>()
    data class NetworkFailure(val error: Throwable) : ApiResult<Nothing>()
    data class AuthFailure(val message: String) : ApiResult<Nothing>()
    data class UnknownFailure(val error: Throwable) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success || this is SuccessEmpty
    val isFailure: Boolean get() = !isSuccess

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is SuccessEmpty -> throw IllegalStateException("ApiResult is SuccessEmpty, no data payload")
        is Failure -> throw IllegalStateException("ApiFailure (code=$code): $message")
        is NetworkFailure -> throw error
        is AuthFailure -> throw IllegalStateException("AuthFailure: $message")
        is UnknownFailure -> throw error
    }
}
