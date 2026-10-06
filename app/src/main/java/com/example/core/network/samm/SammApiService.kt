package com.example.core.network.samm

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for SAMM 5.1.15 REST API.
 * Canonical path prefix: /api/v1/
 */
interface SammApiService {

    @GET("api/v1/me")
    suspend fun getMe(): Response<MeResponse>

    @GET("api/v1/customers")
    suspend fun listCustomers(
        @Query("search") search: String? = null,
        @Query("username") username: String? = null,
        @Query("status") status: String? = null,
        @Query("plan_id") planId: Int? = null,
        @Query("page") page: Int? = 1,
        @Query("per_page") perPage: Int? = 20
    ): Response<ListResponse<CustomerListItem>>

    @GET("api/v1/customers/{customer_id}")
    suspend fun getCustomer(
        @Path("customer_id") customerId: Int
    ): Response<CustomerResponse>

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
        @Query("page") page: Int? = 1,
        @Query("per_page") perPage: Int? = 100
    ): Response<ListResponse<PlanResponse>>

    @GET("api/v1/commands")
    suspend fun listCommands(
        @Query("page") page: Int? = 1,
        @Query("per_page") perPage: Int? = 50
    ): Response<ListResponse<CommandItem>>
}
