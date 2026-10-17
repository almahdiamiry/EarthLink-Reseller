package com.example.core.network.samm

import com.example.core.model.SasProviders
import com.example.core.network.SasBusinessException
import com.example.core.network.SasGateway
import com.example.core.network.SasGatewayException
import com.example.core.network.SasInconclusiveException
import com.example.core.network.SasNotFoundException
import com.example.core.network.SasOperationResult
import com.example.core.network.SasSubscriberSession
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

    private val plansCache = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val planPricesCache = java.util.concurrent.ConcurrentHashMap<Int, Double>()
    private val currencyLabelCache = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private companion object {
        /** SAMM's hard ceiling for GET /customers?limit=. Exceeding it returns HTTP 422. */
        const val MAX_PAGE_SIZE = 1000
    }

    private suspend fun resolvePlanName(planId: Int?): String? {
        if (planId == null) return null
        plansCache[planId]?.let { return it }
        try {
            val plans = listPlans()
            for (p in plans) {
                plansCache[p.id] = p.name
                p.price?.let { planPricesCache[p.id] = it }
            }
            return plansCache[planId]
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun getPlanPrice(planId: String): Double? {
        val id = planId.trim().toIntOrNull() ?: return null
        planPricesCache[id]?.let { return it }
        return try {
            val plans = listPlans()
            for (p in plans) {
                plansCache[p.id] = p.name
                p.price?.let { planPricesCache[p.id] = it }
            }
            planPricesCache[id]
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun listPackages(): List<com.example.core.model.AccountPackage> = try {
        listPlans().map { p ->
            plansCache[p.id] = p.name
            p.price?.let { planPricesCache[p.id] = it }
            com.example.core.model.AccountPackage(
                accountIndex = p.id,
                accountName = p.name,
                canTest = true,
                price = p.price
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Extends an expiry by an exact number of days.
     *
     * POST /customers/{id}/renew accepts no day count - it pushes the expiry forward by the
     * PLAN's duration (1 month on the real plans), which is not what an operator asking for
     * "give them 3 days" means. PATCH /customers/{id} with an absolute expiration_date is the
     * documented way to control the resulting date.
     *
     * The base is max(current expiry, now): extending somebody who lapsed five days ago must
     * bring them forward, not leave them expired.
     */
override suspend fun extendSubscriberByDays(
        username: String,
        days: Int,
        nowMs: Long
    ): SasOperationResult {
        if (days <= 0) {
            return SasOperationResult.Rejected(reason = "Extension must be at least 1 day")
        }
        val customerId = requireCustomerId(username)
        val current = safeApiCall { apiService.getCustomer(customerId) }.body()
        val currentExpiryMillis = current?.expirationDate
            ?.let { com.example.ui.screens.SubscriberDisplayRules.parseInstantMillis(it) }
        val base = maxOf(currentExpiryMillis ?: 0L, nowMs)
        // SAMM only accepts a NAIVE datetime here. Probed against the live server:
        //   ACCEPTED  "2026-10-21 19:02", "2026-10-21T19:02:00"
        //   REJECTED  "...Z", "...+00:00", "...+03:00", and anything with fractional seconds
        // Despite returning "+03:00" on read, a naive write is interpreted in the server's own
        // timezone (Asia/Baghdad), so the arithmetic MUST be done on the Baghdad wall clock or the
        // subscription is silently shortened by the UTC offset.
        val baghdad = java.util.TimeZone.getTimeZone("Asia/Baghdad")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        fmt.timeZone = baghdad
        val newExpiry = fmt.format(java.util.Date(base + days * 86_400_000L))

        safeApiCall { apiService.updateCustomer(customerId, CustomerUpdate(expirationDate = newExpiry)) }
        return SasOperationResult.Applied(evidence = newExpiry)
    }

    override suspend fun getCurrencyLabel(): String? {
        currencyLabelCache.get()?.let { return it }
        return try {
            val settings = safeApiCall { apiService.getSupportSettings() }.body()?.settings.orEmpty()
            val label = settings.firstOrNull { it.key.equals("currency_symbol", ignoreCase = true) }?.value
                ?: settings.firstOrNull { it.key.equals("currency_code", ignoreCase = true) }?.value
            label?.trim()?.takeIf { it.isNotEmpty() }?.also { currencyLabelCache.set(it) }
        } catch (_: Exception) {
            // No settings:read scope, or the endpoint failed. Display simply omits a currency
            // label rather than inventing one.
            null
        }
    }

    private suspend fun CustomerListItem.toSubscriberView(): SasSubscriberView {
        val fullName = listOfNotNull(firstname?.trim(), lastname?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { null }
        val resolvedPlanName = planName?.takeIf { it.isNotBlank() } ?: resolvePlanName(planId)
        return SasSubscriberView(
            subscriberId = id.toString(),
            username = username,
            displayName = fullName,
            status = status ?: "UNKNOWN",
            planId = planId?.toString(),
            planName = resolvedPlanName,
            expiresAt = expirationDate,
            phone = mobile
        )
    }

    private suspend fun CustomerResponse.toSubscriberView(): SasSubscriberView {
        val fullName = listOfNotNull(firstname?.trim(), lastname?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { null }
        val effectiveId = effectivePlanId ?: planId
        val resolvedPlanName = planName?.takeIf { it.isNotBlank() } ?: resolvePlanName(effectiveId)
        return SasSubscriberView(
            subscriberId = id.toString(),
            username = username,
            displayName = fullName,
            status = status ?: "UNKNOWN",
            planId = effectiveId?.toString(),
            planName = resolvedPlanName,
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
        // SAMM speaks limit/offset, not page/perPage, and rejects limit outside 1..1000 with a 422.
        // Translating here keeps the provider-neutral page/pageSize contract on SasGateway intact.
        val limit = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val offset = (page.coerceAtLeast(1) - 1).coerceAtLeast(0) * pageSize.coerceAtLeast(1)
        val response = try {
            if (isExactUsername) {
                apiService.listCustomers(username = trimmed, limit = limit, offset = offset)
            } else {
                apiService.listCustomers(search = trimmed.ifEmpty { null }, limit = limit, offset = offset)
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

    /**
     * SAMM exposes online state only through live RADIUS sessions; the customer resource has no
     * online or session field at all. Absence of a session is the offline answer.
     */
    override suspend fun resolveSubscriberId(username: String): String? {
        val key = username.trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            // ?search=, not ?username= - the SAMM username filter is case-sensitive and silently
            // returns nothing for a lowercased name. search is case-insensitive but is a substring
            // filter, so the guard below is what makes this exact.
            val response = safeApiCall { apiService.listCustomers(search = key, limit = 5) }
            response.body()?.items.orEmpty()
                .firstOrNull { it.username.equals(key, ignoreCase = true) }
                ?.id?.toString()
        } catch (e: SasGatewayException) {
            null
        }
    }

    /**
     * The customer id a mutation must be addressed by, resolved from the username that crossed the
     * seam. Fails closed: an unresolvable username stops before any gateway mutation is dispatched,
     * because the alternative is dispatching against a guessed customer.
     */
    private suspend fun requireCustomerId(username: String): Int {
        val key = username.trim().takeIf { it.isNotEmpty() }
            ?: throw SasBusinessException(
                statusCode = null,
                errorMessage = "A username is required to address a SAMM customer"
            )
        return resolveSubscriberId(key)?.toIntOrNull()
            ?: throw SasNotFoundException(
                statusCode = null,
                message = "Could not resolve the SAMM customer id for $key"
            )
    }

    override suspend fun getSubscriberSession(username: String): SasSubscriberSession? {
        val key = username.trim().takeIf { it.isNotEmpty() } ?: return null
        return try {
            val response = safeApiCall { apiService.listSessions(username = key, limit = 1) }
            val session = response.body()?.items?.firstOrNull { it.username.equals(key, ignoreCase = true) }
                ?: return null
            SasSubscriberSession(
                isOnline = true,
                startedAt = session.startedAt,
                onlineSeconds = session.lastSessionTime,
                ip = session.framedIp
            )
        } catch (_: SasGatewayException) {
            null
        }
    }

    /**
     * One bulk read of every live RADIUS session, so a subscriber list can be marked online without
     * asking per row. The customer resource carries no session field, so /sessions is the only source.
     */
    override suspend fun listOnlineUsernames(): Set<String> {
        return try {
            safeApiCall { apiService.listSessions(limit = 1000) }
                .body()?.items.orEmpty()
                .mapNotNull { it.username?.trim() }
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
                .toSet()
        } catch (_: SasGatewayException) {
            emptySet()
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

    override suspend fun suspendSubscriber(username: String): SasOperationResult {
        val customerId = requireCustomerId(username)
        safeApiCall { apiService.suspendCustomer(customerId) }
        return SasOperationResult.Applied()
    }

    override suspend fun activateSubscriber(username: String): SasOperationResult {
        val customerId = requireCustomerId(username)
        safeApiCall { apiService.activateCustomer(customerId) }
        return SasOperationResult.Applied()
    }

    override suspend fun renewSubscriber(username: String): SasOperationResult {
        val customerId = requireCustomerId(username)
        val response = safeApiCall { apiService.renewCustomer(customerId, generateInvoice = false) }
        val body = response.body()
        return SasOperationResult.Applied(evidence = body?.newExpiration)
    }

    override suspend fun changePlan(username: String, newPlanId: String): SasOperationResult {
        val customerId = requireCustomerId(username)
        val numericPlanId = parsePlanId(newPlanId)
        val request = AssignPlanRequest(
            planId = numericPlanId,
            // "Change Package" must change the plan and nothing else. These four flags are sent
            // explicitly rather than omitted: left to the server they let a plan change silently
            // renew the subscriber's expiry.
            whenMode = "now",
            invoiceMode = "none",
            extendExpiration = false,
            keepExpiration = true,
            generateInvoice = false
        )
        safeApiCall { apiService.assignPlan(customerId, request) }
        return SasOperationResult.Applied()
    }

    override suspend fun changePassword(username: String, newPassword: String): SasOperationResult {
        val customerId = requireCustomerId(username)
        val request = CustomerUpdate(password = newPassword)
        safeApiCall { apiService.updateCustomer(customerId, request) }
        return SasOperationResult.Applied()
    }

    override suspend fun updateDisplayName(username: String, displayName: String): SasOperationResult {
        val customerId = requireCustomerId(username)
        // SAMM stores the name split across two fields; the seam carries one display name.
        val trimmed = displayName.trim()
        val firstSpace = trimmed.indexOf(' ')
        val request = if (firstSpace > 0) {
            CustomerUpdate(
                firstname = trimmed.substring(0, firstSpace),
                lastname = trimmed.substring(firstSpace + 1).trim()
            )
        } else {
            CustomerUpdate(firstname = trimmed)
        }
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
