package com.example.core.network.samm

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * SAMM Wire DTOs (Data Transfer Objects)
 *
 * Mapped to SAMM OpenAPI 3.1.0 specification (docs/samm/openapi.json), refreshed from the live
 * server's /api/v1/openapi.json. Live server_version at the time of refresh: 5.2.4.
 * Strict adherence to SAMM identity contract:
 * - Customer IDs are Integers on wire endpoints (`id: Int`).
 * - Dates are ISO-8601 strings.
 * - Passwords are write-only (never returned in responses).
 */

@JsonClass(generateAdapter = true)
data class MeResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "scopes") val scopes: List<String>? = null,
    @Json(name = "rate_limit_per_min") val rateLimitPerMin: Int? = null,
    @Json(name = "server_version") val serverVersion: String? = null,
    @Json(name = "token_id") val tokenId: Int? = null
)

/**
 * Agent identity attached to a token, read back from GET /me.
 *
 * There is no SAMM login/logout DTO: authentication is a static bearer token from System > API,
 * not a username/password session.
 */
@JsonClass(generateAdapter = true)
data class SammAgentInfo(
    @Json(name = "id") val id: Int,
    @Json(name = "username") val username: String,
    @Json(name = "role") val role: String,
    @Json(name = "reseller_id") val resellerId: Int? = null
)

@JsonClass(generateAdapter = true)
data class SessionItemDto(
    @Json(name = "username") val username: String? = null,
    @Json(name = "framed_ip") val framedIp: String? = null,
    @Json(name = "last_session_time") val lastSessionTime: Long? = null,
    @Json(name = "started_at") val startedAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerCreate(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String,
    @Json(name = "firstname") val firstname: String,
    @Json(name = "lastname") val lastname: String,
    @Json(name = "plan_id") val planId: Int,
    @Json(name = "generate_invoice") val generateInvoice: Boolean = false,
    @Json(name = "mobile") val mobile: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerListItem(
    @Json(name = "id") val id: Int,
    @Json(name = "username") val username: String,
    @Json(name = "firstname") val firstname: String? = null,
    @Json(name = "lastname") val lastname: String? = null,
    @Json(name = "mobile") val mobile: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "plan_id") val planId: Int? = null,
    @Json(name = "plan_name") val planName: String? = null,
    @Json(name = "auto_renew") val autoRenew: Boolean? = null,
    @Json(name = "expiration_date") val expirationDate: String? = null,
    @Json(name = "created_at") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "username") val username: String,
    @Json(name = "firstname") val firstname: String? = null,
    @Json(name = "lastname") val lastname: String? = null,
    @Json(name = "mobile") val mobile: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "plan_id") val planId: Int? = null,
    @Json(name = "plan_name") val planName: String? = null,
    @Json(name = "auto_renew") val autoRenew: Boolean? = null,
    @Json(name = "expiration_date") val expirationDate: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "original_plan_id") val originalPlanId: Int? = null
) {
    val effectivePlanId: Int? get() = planId ?: originalPlanId
}

@JsonClass(generateAdapter = true)
data class CustomerUpdate(
    @Json(name = "firstname") val firstname: String? = null,
    @Json(name = "lastname") val lastname: String? = null,
    @Json(name = "email") val email: String? = null,
    @Json(name = "mobile") val mobile: String? = null,
    @Json(name = "password") val password: String? = null,
    @Json(name = "notes") val notes: String? = null
)

@JsonClass(generateAdapter = true)
data class CustomerRenewResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "username") val username: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "new_expiration") val newExpiration: String? = null,
    @Json(name = "invoice_id") val invoiceId: Int? = null
)

@JsonClass(generateAdapter = true)
data class AssignPlanRequest(
    @Json(name = "plan_id") val planId: Int,
    @Json(name = "generate_invoice") val generateInvoice: Boolean = false,
    @Json(name = "reset_state") val resetState: Boolean = false,
    @Json(name = "reactivate") val reactivate: Boolean = false,
    @Json(name = "apply_speed_live") val applySpeedLive: Boolean = true,
    @Json(name = "notify_customer") val notifyCustomer: Boolean = false
)

@JsonClass(generateAdapter = true)
data class AssignPlanResponse(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "username") val username: String? = null,
    @Json(name = "plan_id") val planId: Int? = null,
    @Json(name = "plan_name") val planName: String? = null,
    @Json(name = "expiration_date") val expirationDate: String? = null,
    @Json(name = "invoice_id") val invoiceId: Int? = null
)

@JsonClass(generateAdapter = true)
data class PlanResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "price") val price: Double? = null,
    @Json(name = "speed_download") val speedDownload: Long? = null,
    @Json(name = "speed_upload") val speedUpload: Long? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "download_speed") val downloadSpeed: String? = null,
    @Json(name = "upload_speed") val uploadSpeed: String? = null
)

@JsonClass(generateAdapter = true)
data class ListResponse<T>(
    @Json(name = "total") val total: Int,
    @Json(name = "items") val items: List<T>
)

@JsonClass(generateAdapter = true)
data class HTTPValidationError(
    @Json(name = "detail") val detail: List<ValidationError>? = null
)

@JsonClass(generateAdapter = true)
data class ValidationError(
    @Json(name = "loc") val loc: List<String>? = null,
    @Json(name = "msg") val msg: String? = null,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class CommandItem(
    @Json(name = "id") val id: Int,
    @Json(name = "action") val action: String,
    @Json(name = "target") val target: String? = null,
    @Json(name = "applied") val applied: Boolean,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "applied_at") val appliedAt: String? = null
)
