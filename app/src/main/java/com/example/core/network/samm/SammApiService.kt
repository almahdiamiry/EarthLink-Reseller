package com.example.core.network.samm

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the SAMM REST API (live server_version 5.2.4 at the last refresh).
 * Canonical path prefix: /api/v1/
 */
interface SammApiService {

    @GET("api/v1/me")
    suspend fun getMe(): Response<MeResponse>

    /**
     * NOTE: SAMM has NO username/password login and NO logout endpoint. Its only credential is a
     * long-lived static bearer token minted from System > API (starts with `samm_`), presented as
     * `Authorization: Bearer <token>`. Do not reintroduce agent-login/agent-logout here.
     */

    /**
     * SAMM paginates with `limit`/`offset` (limit: min 1, max 1000, default 100) — there are no
     * `page`/`per_page` parameters, and unknown query params are dropped silently, which previously
     * made the server apply its 100-row default and truncate the result set without any error.
     */
    @GET("api/v1/customers")
    suspend fun listCustomers(
        @Query("search") search: String? = null,
        @Query("username") username: String? = null,
        @Query("status") status: String? = null,
        @Query("plan_id") planId: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<ListResponse<CustomerListItem>>

    @GET("api/v1/customers/{customer_id}")
    suspend fun getCustomer(
        @Path("customer_id") customerId: Int
    ): Response<CustomerResponse>

    /**
     * Live RADIUS sessions, filterable by username. This is the only SAMM resource that carries
     * online state; the customer resource itself has no online or session field.
     */
    @GET("api/v1/sessions")
    suspend fun listSessions(
        @Query("username") username: String? = null,
        @Query("limit") limit: Int? = 1
    ): Response<ListResponse<SessionItemDto>>

    @POST("api/v1/customers")
    suspend fun createCustomer(
        @Body request: CustomerCreate
    ): Response<CustomerResponse>

    @PATCH("api/v1/customers/{customer_id}")
    suspend fun updateCustomer(
        @Path("customer_id") customerId: Int,
        @Body request: CustomerUpdate
    ): Response<CustomerResponse>

    @POST("api/v1/customers/{customer_id}/suspend")
    suspend fun suspendCustomer(
        @Path("customer_id") customerId: Int
    ): Response<CustomerResponse>

    @POST("api/v1/customers/{customer_id}/activate")
    suspend fun activateCustomer(
        @Path("customer_id") customerId: Int
    ): Response<CustomerResponse>

    @POST("api/v1/customers/{customer_id}/renew")
    suspend fun renewCustomer(
        @Path("customer_id") customerId: Int,
        @Query("generate_invoice") generateInvoice: Boolean = false
    ): Response<CustomerRenewResponse>

    @POST("api/v1/customers/{customer_id}/assign-plan")
    suspend fun assignPlan(
        @Path("customer_id") customerId: Int,
        @Body request: AssignPlanRequest
    ): Response<AssignPlanResponse>

    @GET("api/v1/plans")
    suspend fun listPlans(
        @Query("enabled_only") enabledOnly: Boolean? = null
    ): Response<List<PlanResponse>>

    @GET("api/v1/commands")
    suspend fun listCommands(
        @Query("applied") applied: Boolean? = null,
        @Query("limit") limit: Int? = 50,
        @Query("offset") offset: Int? = 0
    ): Response<ListResponse<CommandItem>>
}
