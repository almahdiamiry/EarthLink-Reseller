package com.example.core.network

import com.example.core.model.SasProviders
import com.example.core.network.samm.SammGatewayImpl
import com.example.core.network.samm.SammNetworkClient
import com.example.core.security.PreferenceManager

/**
 * SAS Gateway Router (com.example.core.network)
 *
 * Centralizes provider selection and dynamic resolution for SAS providers
 * (EarthLink and SAMM / Alamiry) with strict isolation and zero cross-provider fallback.
 *
 * Design Invariants:
 * 1. Explicit Routing:
 *    - [SasProviders.EARTHLINK] -> [earthlinkAdapter]
 *    - [SasProviders.ALAMIRY] -> [SammGatewayImpl] (cached or newly instantiated)
 * 2. Zero Cross-Provider Fallback:
 *    - If ALAMIRY is requested and unconfigured, throws explicit [SasBusinessException].
 *      Never falls back to EarthLink under any condition.
 *    - Errors/exceptions thrown by the underlying provider gateway are never caught,
 *      swallowed, or redirected to another provider.
 * 3. Fail-Closed Validation:
 *    - Blank, null, unknown, or unsupported provider names throw [SasUnsupportedOperationException].
 * 4. Dynamic Invalidation & Caching:
 *    - Active SAMM gateway instances are cached in memory.
 *    - When [PreferenceManager.sammConfigVersionFlow] advances (e.g. on URL or token update/clear),
 *      or if credentials change, the cached instance is invalidated and rebuilt on the next request.
 */
class SasGatewayRouter(
    private val earthlinkAdapter: SasGateway,
    private val preferenceManager: PreferenceManager,
    private val sammGatewayFactory: ((baseUrl: String, token: String) -> SasGateway)? = null
) {
    @Volatile
    private var cachedSammGateway: SasGateway? = null
    @Volatile
    private var cachedConfigVersion: Int? = null
    @Volatile
    private var cachedBaseUrl: String? = null
    @Volatile
    private var cachedToken: String? = null

    /**
     * Resolves the authoritative gateway for the specified provider.
     * ZERO fallback: ALAMIRY will never return EarthLink under any condition.
     *
     * @param providerName Canonical provider name (e.g. "EARTHLINK", "ALAMIRY").
     * @return [SasGateway] corresponding to the provider.
     * @throws SasBusinessException if ALAMIRY is requested but not configured in preferences.
     * @throws SasUnsupportedOperationException if provider is blank, unknown, or unsupported.
     */
    fun getGateway(providerName: String?): SasGateway {
        if (providerName.isNullOrBlank()) {
            throw SasUnsupportedOperationException("Unsupported or unknown provider: '$providerName'")
        }

        return when (providerName) {
            SasProviders.EARTHLINK -> earthlinkAdapter
            SasProviders.ALAMIRY -> resolveSammGateway()
            else -> throw SasUnsupportedOperationException("Unsupported or unknown provider: '$providerName'")
        }
    }

    @Synchronized
    private fun resolveSammGateway(): SasGateway {
        if (!preferenceManager.isSammConfigured()) {
            invalidateSammCache()
            throw SasBusinessException(
                statusCode = null,
                errorMessage = "SAMM provider is not configured. Please configure SAMM host and token in Settings."
            )
        }

        val currentVersion = preferenceManager.sammConfigVersionFlow.value
        val baseUrl = preferenceManager.getSammBaseUrl()!!
        val token = preferenceManager.getSammToken()!!

        val cached = cachedSammGateway
        if (cached != null &&
            cachedConfigVersion == currentVersion &&
            cachedBaseUrl == baseUrl &&
            cachedToken == token
        ) {
            return cached
        }

        val gateway = sammGatewayFactory?.invoke(baseUrl, token)
            ?: SammGatewayImpl(SammNetworkClient.createApiService(baseUrl, token))

        cachedSammGateway = gateway
        cachedConfigVersion = currentVersion
        cachedBaseUrl = baseUrl
        cachedToken = token
        return gateway
    }

    @Synchronized
    fun invalidateSammCache() {
        cachedSammGateway = null
        cachedConfigVersion = null
        cachedBaseUrl = null
        cachedToken = null
    }
}
