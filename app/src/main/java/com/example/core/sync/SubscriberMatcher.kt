package com.example.core.sync

import com.example.core.model.LocalAccount

/**
 * Shared subscriber matching primitive for import pipelines (UtowerImporter and Repositories.commitImport).
 * Enforces strict matching priority:
 * 1. Username (earthlinkUsername)
 * 2. External ID / Internal ID (sourceExternalId or id)
 * 3. Phone number (phone1 or phone2) — requires strict candidate uniqueness
 * 4. Display Name (displayName) — requires strict candidate uniqueness
 *
 * If a matching stage produces multiple candidate matches (ambiguous match),
 * matching refuses to guess and falls back to subsequent stages or returns null.
 */
object SubscriberMatcher {

    fun matchSubscriber(
        candidates: Collection<LocalAccount>,
        extId: String? = null,
        username: String? = null,
        phone: String? = null,
        name: String? = null
    ): LocalAccount? {
        val cleanUsername = username?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (cleanUsername != null) {
            val usernameMatches = candidates.filter { acc ->
                acc.earthlinkUsername?.trim()?.equals(cleanUsername, ignoreCase = true) == true
            }
            if (usernameMatches.size == 1) return usernameMatches.first()
        }

        val cleanExtId = extId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (cleanExtId != null) {
            val extIdMatches = candidates.filter { acc ->
                acc.sourceExternalId == cleanExtId || acc.id == cleanExtId
            }
            if (extIdMatches.size == 1) return extIdMatches.first()
        }

        val cleanPhone = phone?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (cleanPhone != null) {
            val phoneMatches = candidates.filter { acc ->
                val matchesPhone = acc.phone1?.trim() == cleanPhone || acc.phone2?.trim() == cleanPhone
                val conflictingExtId = cleanExtId != null && !acc.sourceExternalId.isNullOrEmpty() && acc.sourceExternalId != cleanExtId
                matchesPhone && !conflictingExtId
            }
            if (phoneMatches.size == 1) return phoneMatches.first()
        }

        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        if (cleanName != null) {
            val nameMatches = candidates.filter { acc ->
                val matchesName = acc.displayName.trim().equals(cleanName, ignoreCase = true)
                val conflictingExtId = cleanExtId != null && !acc.sourceExternalId.isNullOrEmpty() && acc.sourceExternalId != cleanExtId
                val conflictingUsername = cleanUsername != null && !acc.earthlinkUsername.isNullOrEmpty() && !acc.earthlinkUsername.equals(cleanUsername, ignoreCase = true)
                matchesName && !conflictingExtId && !conflictingUsername
            }
            if (nameMatches.size == 1) return nameMatches.first()
        }

        return null
    }
}
