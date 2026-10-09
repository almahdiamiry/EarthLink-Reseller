package com.example.core.model

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification suite for originProvider tracking in subscriber models (UserListItem, UserDetail).
 *
 * Core Triad Specification:
 * 1. Claim:
 *    - UserListItem and UserDetail default originProvider to null, maintaining 100% backward
 *      compatibility with existing legacy Earthlink API responses and mock payloads.
 *    - When originProvider is set (e.g. SasProviders.ALAMIRY or SasProviders.EARTHLINK),
 *      it is preserved in memory and roundtrips through Moshi JSON serialization.
 *
 * 2. Seam / Environment:
 *    - JVM unit test tier (Moshi JSON code-gen & Kotlin data class properties).
 *
 * 3. Independent Oracle:
 *    - Literal expected strings ("ALAMIRY", "EARTHLINK", null) independently asserted against
 *      deserialized models and JSON strings.
 */
class UserListItemOriginProviderTest {

    private val moshi = Moshi.Builder().build()
    private val itemAdapter = moshi.adapter(UserListItem::class.java)
    private val detailAdapter = moshi.adapter(UserDetail::class.java)

    @Test
    fun userListItem_defaultsOriginProviderToNull() {
        val item = UserListItem(
            userIndexLower = 101,
            userIDLower = "test_user_01"
        )
        assertNull("Default originProvider must be null", item.originProvider)
    }

    @Test
    fun userListItem_preservesExplicitOriginProvider() {
        val item = UserListItem(
            userIndexLower = 202,
            userIDLower = "samm_user_02",
            originProvider = SasProviders.ALAMIRY
        )
        assertEquals(SasProviders.ALAMIRY, item.originProvider)
    }

    @Test
    fun userListItem_moshiSerializationRoundtrip() {
        val original = UserListItem(
            userIndexLower = 303,
            userIDLower = "samm_user_03",
            originProvider = SasProviders.ALAMIRY
        )
        val json = itemAdapter.toJson(original)
        assertTrue("JSON must contain originProvider", json.contains("\"originProvider\":\"ALAMIRY\""))

        val deserialized = itemAdapter.fromJson(json)
        assertEquals(SasProviders.ALAMIRY, deserialized?.originProvider)
        assertEquals("samm_user_03", deserialized?.userID)
    }

    @Test
    fun userListItem_moshiDeserializationWithoutOriginProvider_defaultsToNull() {
        val json = """{"userIndex":404,"userID":"earthlink_user_04"}"""
        val deserialized = itemAdapter.fromJson(json)
        assertNull(deserialized?.originProvider)
        assertEquals("earthlink_user_04", deserialized?.userID)
    }

    @Test
    fun userDetail_defaultsOriginProviderToNull() {
        val detail = UserDetail(
            userIndexLower = 505,
            userIDLower = "detail_user_05"
        )
        assertNull("Default originProvider in UserDetail must be null", detail.originProvider)
    }

    @Test
    fun userDetail_preservesExplicitOriginProvider() {
        val detail = UserDetail(
            userIndexLower = 606,
            userIDLower = "samm_detail_06",
            originProvider = SasProviders.ALAMIRY
        )
        assertEquals(SasProviders.ALAMIRY, detail.originProvider)
    }

    @Test
    fun userDetail_moshiSerializationRoundtrip() {
        val original = UserDetail(
            userIndexLower = 707,
            userIDLower = "samm_detail_07",
            originProvider = SasProviders.ALAMIRY
        )
        val json = detailAdapter.toJson(original)
        assertTrue("JSON must contain originProvider", json.contains("\"originProvider\":\"ALAMIRY\""))

        val deserialized = detailAdapter.fromJson(json)
        assertEquals(SasProviders.ALAMIRY, deserialized?.originProvider)
        assertEquals("samm_detail_07", deserialized?.userID)
    }

    @Test
    fun userDetail_moshiDeserializationWithoutOriginProvider_defaultsToNull() {
        val json = """{"userIndex":808,"userID":"earthlink_detail_08"}"""
        val deserialized = detailAdapter.fromJson(json)
        assertNull(deserialized?.originProvider)
        assertEquals("earthlink_detail_08", deserialized?.userID)
    }
}
