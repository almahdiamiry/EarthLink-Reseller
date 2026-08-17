package com.example.core.network

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
