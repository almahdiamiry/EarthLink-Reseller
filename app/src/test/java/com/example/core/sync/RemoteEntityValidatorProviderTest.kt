package com.example.core.sync

import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification suite for RemoteEntityValidator provider restoration (Scenario B.2).
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - validateAndMapAccount parses operationProvider from the remote Firestore document map.
 *    - If remote payload contains a valid operationProvider (e.g. "ALAMIRY"), it maps to LocalAccount.operationProvider.
 *    - If remote payload omits operationProvider, it preserves existingLocalAccount.operationProvider.
 *    - If remote payload omits operationProvider and there is no existingLocalAccount, it safely defaults to SasProviders.EARTHLINK.
 *    - If remote payload contains an invalid/unrecognized provider, it falls back to existingLocalAccount.operationProvider or SasProviders.EARTHLINK.
 *
 * 2. Seam / Environment:
 *    - JVM unit test tier (RemoteEntityValidator pure domain validation).
 *
 * 3. Independent Oracle:
 *    - Literal expected provider strings ("ALAMIRY", "EARTHLINK") asserted against output LocalAccount.
 */
class RemoteEntityValidatorProviderTest {

    private val baseRemoteMap = mapOf<String, Any>(
        "displayName" to "Ahmed Ali",
        "earthlinkUsername" to "ahmed123",
        "debtIqd" to 15000.0,
        "stateSource" to "UTOWER",
        "isFullSnapshot" to true
    )

    @Test
    fun validateAndMapAccount_parsesAlamiryProviderFromRemotePayload() {
        val remoteMap = baseRemoteMap + ("operationProvider" to SasProviders.ALAMIRY)
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_remote_01",
            d = remoteMap,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = null
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.ALAMIRY, account.operationProvider)
    }

    @Test
    fun validateAndMapAccount_parsesEarthlinkProviderFromRemotePayload() {
        val remoteMap = baseRemoteMap + ("operationProvider" to SasProviders.EARTHLINK)
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_remote_02",
            d = remoteMap,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = null
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.EARTHLINK, account.operationProvider)
    }

    @Test
    fun validateAndMapAccount_preservesExistingProviderWhenRemoteOmitsIt() {
        val existing = LocalAccount(
            id = "acc_remote_03",
            displayName = "Existing SAMM User",
            operationProvider = SasProviders.ALAMIRY
        )
        val remoteMap = baseRemoteMap // No operationProvider in remote map
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_remote_03",
            d = remoteMap,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = existing
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.ALAMIRY, account.operationProvider)
    }

    @Test
    fun validateAndMapAccount_defaultsToEarthlinkWhenBothRemoteAndExistingOmitted() {
        val remoteMap = baseRemoteMap // No operationProvider in remote map
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_remote_04",
            d = remoteMap,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = null
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals(SasProviders.EARTHLINK, account.operationProvider)
    }

    @Test
    fun validateAndMapAccount_fallsBackWhenRemoteProviderIsInvalid() {
        val existing = LocalAccount(
            id = "acc_remote_05",
            displayName = "Existing SAMM User",
            operationProvider = SasProviders.ALAMIRY
        )
        val remoteMap = baseRemoteMap + ("operationProvider" to "UNKNOWN_PROVIDER_XYZ")
        val result = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_remote_05",
            d = remoteMap,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = existing
        )

        assertTrue(result is RemoteEntityValidationResult.Valid)
        val account = (result as RemoteEntityValidationResult.Valid).entity
        assertEquals("Invalid provider must fall back to existing", SasProviders.ALAMIRY, account.operationProvider)

        val resultNoExisting = RemoteEntityValidator.validateAndMapAccount(
            id = "acc_remote_06",
            d = remoteMap,
            remoteUpdatedAt = 1000L,
            existingLocalAccount = null
        )
        assertTrue(resultNoExisting is RemoteEntityValidationResult.Valid)
        val accountNoExisting = (resultNoExisting as RemoteEntityValidationResult.Valid).entity
        assertEquals("Invalid provider with no existing account must fall back to EARTHLINK", SasProviders.EARTHLINK, accountNoExisting.operationProvider)
    }
}
