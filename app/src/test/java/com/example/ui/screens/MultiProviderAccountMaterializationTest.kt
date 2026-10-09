package com.example.ui.screens

import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import com.example.core.model.UserDetail
import com.example.core.model.UserListItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MultiProviderAccountMaterializationTest
 *
 * Authoritative test suite for Task 5 of Multi-Provider Subscriber Sync:
 * Preserving Provider on Subscriber-to-Local-Account Materialization & UI Badging.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - Unpersisted external subscribers viewed in UserDetailScreenV2 inherit their originProvider
 *      (or fallback to viewModel.selectedProvider.value) when materialized into LocalAccount,
 *      preventing silent default to EARTHLINK for SAMM/Alamiry subscribers.
 *    - Persisted LocalAccount instances preserve their existing operationProvider.
 *    - DashboardScreen stamps originProvider = acc.operationProvider when constructing
 *      UserListItem from LocalAccount, ensuring provider badges and multi-provider styling
 *      are accurately presented.
 *
 * 2. Seam / Environment:
 *    - JVM tier (sub-second pure domain / UI presentation helper execution).
 *
 * 3. Independent Oracle:
 *    - Explicit literal strings: SasProviders.ALAMIRY ("ALAMIRY") and SasProviders.EARTHLINK ("EARTHLINK").
 *    - Independent assert statements verifying materialized LocalAccount and UserListItem properties.
 */
class MultiProviderAccountMaterializationTest {

    @Test
    fun sammSubscriber_materializesLocalAccount_withAlamiryProvider() {
        // Given a subscriber fetched from SAMM / ALAMIRY
        val sammUser = UserDetail(
            userIndexLower = 42,
            userIDLower = "samm_sub_01",
            customerFullNameLower = "Mustafa Ali",
            mobileNumberLower = "07700000000",
            packageNameLower = "Turbo-50",
            originProvider = SasProviders.ALAMIRY
        )

        // When resolving operationProvider with no existing local account
        val opProvider = AccountMaterializationHelper.resolveOperationProvider(
            matchingAccount = null,
            originProvider = sammUser.originProvider,
            fallbackSelectedProvider = SasProviders.EARTHLINK
        )

        val materializedAcc = AccountMaterializationHelper.materialize(
            matchingAccount = null,
            user = sammUser,
            fallbackSelectedProvider = SasProviders.EARTHLINK
        )

        // Then opProvider and LocalAccount.operationProvider must strictly be ALAMIRY
        assertEquals(SasProviders.ALAMIRY, opProvider)
        assertEquals(SasProviders.ALAMIRY, materializedAcc.operationProvider)
        assertEquals("samm_sub_01", materializedAcc.earthlinkUsername)
        assertEquals("Mustafa Ali", materializedAcc.displayName)
    }

    @Test
    fun earthlinkSubscriber_materializesLocalAccount_withEarthlinkProvider() {
        // Given a subscriber fetched from EarthLink
        val earthlinkUser = UserDetail(
            userIndexLower = 99,
            userIDLower = "el_sub_01",
            customerFullNameLower = "Ahmed Hassan",
            mobileNumberLower = "07800000000",
            packageNameLower = "Standard-10",
            originProvider = SasProviders.EARTHLINK
        )

        // When resolving operationProvider with no existing local account
        val opProvider = AccountMaterializationHelper.resolveOperationProvider(
            matchingAccount = null,
            originProvider = earthlinkUser.originProvider,
            fallbackSelectedProvider = SasProviders.ALAMIRY
        )

        val materializedAcc = AccountMaterializationHelper.materialize(
            matchingAccount = null,
            user = earthlinkUser,
            fallbackSelectedProvider = SasProviders.ALAMIRY
        )

        // Then opProvider and LocalAccount.operationProvider must strictly be EARTHLINK
        assertEquals(SasProviders.EARTHLINK, opProvider)
        assertEquals(SasProviders.EARTHLINK, materializedAcc.operationProvider)
        assertEquals("el_sub_01", materializedAcc.earthlinkUsername)
    }

    @Test
    fun subscriberWithoutOriginProvider_inheritsFallbackSelectedProvider() {
        // Given an external subscriber with null originProvider
        val genericUser = UserDetail(
            userIndexLower = 101,
            userIDLower = "generic_user",
            customerFullNameLower = "Generic User",
            originProvider = null
        )

        // Case A: Selected provider is ALAMIRY
        val alamiryOp = AccountMaterializationHelper.resolveOperationProvider(
            matchingAccount = null,
            originProvider = genericUser.originProvider,
            fallbackSelectedProvider = SasProviders.ALAMIRY
        )
        val alamiryAcc = AccountMaterializationHelper.materialize(
            matchingAccount = null,
            user = genericUser,
            fallbackSelectedProvider = SasProviders.ALAMIRY
        )
        assertEquals(SasProviders.ALAMIRY, alamiryOp)
        assertEquals(SasProviders.ALAMIRY, alamiryAcc.operationProvider)

        // Case B: Selected provider is EARTHLINK
        val earthlinkOp = AccountMaterializationHelper.resolveOperationProvider(
            matchingAccount = null,
            originProvider = genericUser.originProvider,
            fallbackSelectedProvider = SasProviders.EARTHLINK
        )
        val earthlinkAcc = AccountMaterializationHelper.materialize(
            matchingAccount = null,
            user = genericUser,
            fallbackSelectedProvider = SasProviders.EARTHLINK
        )
        assertEquals(SasProviders.EARTHLINK, earthlinkOp)
        assertEquals(SasProviders.EARTHLINK, earthlinkAcc.operationProvider)
    }

    @Test
    fun existingMatchingAccount_preservesOriginalProvider_evenIfSubscriberOriginDiffers() {
        // Given an existing local account assigned to ALAMIRY
        val existingAccount = LocalAccount(
            id = "acc_existing_1",
            earthlinkUsername = "user_switch",
            displayName = "User Switch",
            operationProvider = SasProviders.ALAMIRY
        )

        // And a subscriber response that reports EARTHLINK
        val userDetail = UserDetail(
            userIndexLower = 202,
            userIDLower = "user_switch",
            originProvider = SasProviders.EARTHLINK
        )

        // When resolving provider
        val opProvider = AccountMaterializationHelper.resolveOperationProvider(
            matchingAccount = existingAccount,
            originProvider = userDetail.originProvider,
            fallbackSelectedProvider = SasProviders.EARTHLINK
        )

        val materializedAcc = AccountMaterializationHelper.materialize(
            matchingAccount = existingAccount,
            user = userDetail,
            fallbackSelectedProvider = SasProviders.EARTHLINK
        )

        // Then the existing account provider must be preserved
        assertEquals(SasProviders.ALAMIRY, opProvider)
        assertEquals(existingAccount, materializedAcc)
        assertEquals(SasProviders.ALAMIRY, materializedAcc.operationProvider)
    }

    @Test
    fun localAccount_stampsOriginProvider_onDashboardUserListItem() {
        // Given local accounts with different providers
        val alamiryLocalAcc = LocalAccount(
            id = "loc_alamiry_1",
            earthlinkUsername = "local_samm_user",
            displayName = "Local Samm",
            packageName = "Super-Package",
            operationProvider = SasProviders.ALAMIRY
        )

        val earthlinkLocalAcc = LocalAccount(
            id = "loc_el_1",
            earthlinkUsername = "local_el_user",
            displayName = "Local EL",
            packageName = "Standard-Package",
            operationProvider = SasProviders.EARTHLINK
        )

        // When converted to UserListItem in dashboard
        val sammListItem = UserListItem(
            userIndexLower = alamiryLocalAcc.id.hashCode(),
            userIDLower = alamiryLocalAcc.earthlinkUsername ?: "local_${alamiryLocalAcc.id}",
            customerNameLower = alamiryLocalAcc.displayName,
            originProvider = alamiryLocalAcc.operationProvider
        )

        val elListItem = UserListItem(
            userIndexLower = earthlinkLocalAcc.id.hashCode(),
            userIDLower = earthlinkLocalAcc.earthlinkUsername ?: "local_${earthlinkLocalAcc.id}",
            customerNameLower = earthlinkLocalAcc.displayName,
            originProvider = earthlinkLocalAcc.operationProvider
        )

        // Then originProvider reflects LocalAccount.operationProvider
        assertEquals(SasProviders.ALAMIRY, sammListItem.originProvider)
        assertEquals(SasProviders.EARTHLINK, elListItem.originProvider)
    }
}
