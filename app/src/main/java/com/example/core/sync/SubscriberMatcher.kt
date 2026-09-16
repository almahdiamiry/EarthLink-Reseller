package com.example.core.sync

import com.example.core.model.LocalAccount

/**
 * Result of subscriber matching during import pipelines.
 */
sealed class SubscriberMatchResult {
    object NoMatch : SubscriberMatchResult()

    data class Unique(val account: LocalAccount) : SubscriberMatchResult()

    data class Ambiguous(
        val stage: String,
        val identifier: String,
        val count: Int,
        val candidateIds: List<String> = emptyList(),
        val additionalStages: List<String> = emptyList(),
        val customReason: String? = null
    ) : SubscriberMatchResult() {
        val message: String get() {
            val stageDesc = customReason ?: if (additionalStages.isNotEmpty()) {
                "$stage '$identifier' and ${additionalStages.joinToString(", ")}"
            } else {
                "$stage '$identifier'"
            }
            return "Ambiguous subscriber row quarantined: $stageDesc matched $count physical containers without a unique match."
        }
    }

    val accountOrNull: LocalAccount? get() = (this as? Unique)?.account
}

/**
 * Shared subscriber matching primitive for import pipelines (UtowerImporter and Repositories.commitImport).
 * Enforces strict matching priority:
 * 1. Username (earthlinkUsername)
 * 2. External ID / Internal ID (sourceExternalId or id)
 * 3. Phone number (phone1 or phone2) — requires strict candidate uniqueness
 * 4. Display Name (displayName) — requires strict candidate uniqueness
 *
 * If a matching stage produces multiple candidate matches (ambiguous match),
 * matching refuses to guess and falls back to subsequent stages.
 * If no subsequent stage produces a unique match, returns [SubscriberMatchResult.Ambiguous].
 * If no stage matches at all, returns [SubscriberMatchResult.NoMatch].
 */
object SubscriberMatcher {

    private data class AmbiguityDetail(
        val stage: String,
        val identifier: String,
        val candidateIds: List<String>,
        val count: Int = candidateIds.size
    )

    fun matchSubscriber(
        candidates: Collection<LocalAccount>,
        extId: String? = null,
        username: String? = null,
        phone: String? = null,
        name: String? = null
    ): SubscriberMatchResult {
        val cleanUsername = username?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val cleanExtId = extId?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val cleanPhone = phone?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        val ambiguities = mutableListOf<AmbiguityDetail>()

        // Stage 1: Username
        if (cleanUsername != null) {
            val usernameMatches = candidates.filter { acc ->
                val matchesUsername = acc.earthlinkUsername?.trim()?.equals(cleanUsername, ignoreCase = true) == true
                if (!matchesUsername) return@filter false
                if (acc.isHistoryOnlySubscriber) {
                    cleanExtId != null && acc.sourceExternalId == cleanExtId
                } else {
                    true
                }
            }
            if (usernameMatches.size == 1) {
                return SubscriberMatchResult.Unique(usernameMatches.first())
            } else if (usernameMatches.size > 1) {
                ambiguities.add(AmbiguityDetail("username", cleanUsername, usernameMatches.map { it.id }))
            }
        }

        // Stage 2: External ID / Internal ID
        if (cleanExtId != null) {
            val extIdMatches = candidates.filter { acc ->
                acc.sourceExternalId == cleanExtId || acc.id == cleanExtId
            }
            if (extIdMatches.size == 1) {
                return SubscriberMatchResult.Unique(extIdMatches.first())
            } else if (extIdMatches.size > 1) {
                ambiguities.add(AmbiguityDetail("sourceExternalId", cleanExtId, extIdMatches.map { it.id }))
            }
        }

        // Stage 3: Phone
        if (cleanPhone != null) {
            val phoneMatches = candidates.filter { acc ->
                if (acc.isHistoryOnlySubscriber) return@filter false
                val matchesPhone = acc.phone1?.trim() == cleanPhone || acc.phone2?.trim() == cleanPhone
                val conflictingExtId = cleanExtId != null && !acc.sourceExternalId.isNullOrEmpty() && acc.sourceExternalId != cleanExtId
                val conflictingUsername = cleanUsername != null && !acc.earthlinkUsername.isNullOrEmpty() && !acc.earthlinkUsername.equals(cleanUsername, ignoreCase = true)
                matchesPhone && !conflictingExtId && !conflictingUsername
            }
            if (phoneMatches.size == 1) {
                return SubscriberMatchResult.Unique(phoneMatches.first())
            } else if (phoneMatches.size > 1) {
                ambiguities.add(AmbiguityDetail("phone", cleanPhone, phoneMatches.map { it.id }))
            }
        }

        // Stage 4: Display Name
        if (cleanName != null) {
            val nameMatches = candidates.filter { acc ->
                if (acc.isHistoryOnlySubscriber) return@filter false
                val matchesName = acc.displayName.trim().equals(cleanName, ignoreCase = true)
                val conflictingExtId = cleanExtId != null && !acc.sourceExternalId.isNullOrEmpty() && acc.sourceExternalId != cleanExtId
                val conflictingUsername = cleanUsername != null && !acc.earthlinkUsername.isNullOrEmpty() && !acc.earthlinkUsername.equals(cleanUsername, ignoreCase = true)
                matchesName && !conflictingExtId && !conflictingUsername
            }
            if (nameMatches.size == 1) {
                return SubscriberMatchResult.Unique(nameMatches.first())
            } else if (nameMatches.size > 1) {
                ambiguities.add(AmbiguityDetail("display-name", cleanName, nameMatches.map { it.id }))
            }
        }

        if (ambiguities.isEmpty()) {
            return SubscriberMatchResult.NoMatch
        }

        val hasExtId = ambiguities.find { it.stage == "sourceExternalId" }
        val hasUsername = ambiguities.find { it.stage == "username" }
        val allCandidateIds = ambiguities.flatMap { it.candidateIds }.distinct()
        val maxCount = ambiguities.maxOf { it.count }

        return if (hasExtId != null && hasUsername != null) {
            SubscriberMatchResult.Ambiguous(
                stage = "sourceExternalId",
                identifier = hasExtId.identifier,
                count = maxCount,
                candidateIds = allCandidateIds,
                additionalStages = ambiguities.filter { it != hasExtId && it != hasUsername }.map { "${it.stage} '${it.identifier}'" },
                customReason = "both sourceExternalId '${hasExtId.identifier}' and username '${hasUsername.identifier}'"
            )
        } else {
            val primary = ambiguities.first()
            SubscriberMatchResult.Ambiguous(
                stage = primary.stage,
                identifier = primary.identifier,
                count = maxCount,
                candidateIds = allCandidateIds,
                additionalStages = ambiguities.drop(1).map { "${it.stage} '${it.identifier}'" }
            )
        }
    }
}
