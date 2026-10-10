package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SubscriberDisplayRulesTest
 *
 * 1. Claim:
 *    - Expiration is rendered as a short "yyyy-MM-dd h:mm a" stamp in Asia/Baghdad regardless of
 *      which ISO-8601 variant the provider sent (trailing Z, fractional seconds, explicit offset,
 *      or no zone at all), and never leaks a raw wire string to the screen.
 *    - Effective account status is SUSPENDED/EXPIRED whenever the provider reports ACTIVE but the
 *      expiry is already in the past, and otherwise preserves the provider's own answer.
 *    - Online duration renders as compact "Nh Mm".
 *
 * 2. Seam / Environment:
 *    JVM tier (plain JUnit, no Android runtime). Pure functions, no Compose, no IO.
 *
 * 3. Independent Oracle:
 *    Each expectation is a literal string written out by hand from the rule, not computed by the
 *    production formatter. 11:30:45Z is 14:30:45 in Baghdad (UTC+3, no DST), so the expected stamp
 *    is "2026-12-01 2:30 PM" — a constant that would break if the zone or format changed.
 */
class SubscriberDisplayRulesTest {

    // ========================================================================
    // Expiration formatting
    // ========================================================================

    @Test
    fun expiration_everyWireShapeRendersTheSameBaghdadStamp() {
        // 11:30:45Z is 14:30:45 in Baghdad (UTC+3, no DST); every shape below is that same instant.
        val shapes = mapOf(
            "trailing Z" to "2026-12-01T11:30:45Z",
            "fractional seconds" to "2026-12-01T11:30:45.123Z",
            "explicit UTC offset" to "2026-12-01T11:30:45+00:00",
            "non-UTC offset" to "2026-12-01T14:30:45+03:00",
            "no zone (read as UTC)" to "2026-12-01T11:30:45",
            "already a Baghdad stamp" to "2026-12-01 14:30:45"
        )
        for ((label, wire) in shapes) {
            assertEquals("$label must render as the same Baghdad instant", "2026-12-01 2:30 PM", SubscriberDisplayRules.formatSubscriberExpiration(wire))
        }
    }

    @Test
    fun expiration_blankOrNull_returnsNull() {
        assertNull(SubscriberDisplayRules.formatSubscriberExpiration(null))
        assertNull(SubscriberDisplayRules.formatSubscriberExpiration(""))
        assertNull(SubscriberDisplayRules.formatSubscriberExpiration("   "))
        assertNull(SubscriberDisplayRules.formatSubscriberExpiration("N/A"))
    }

    // ========================================================================
    // Effective account status
    // ========================================================================

    @Test
    fun status_activeButExpiryInPast_becomesExpired() {
        val past = 1_000_000L
        assertEquals(
            "expired",
            SubscriberDisplayRules.effectiveAccountStatus("active", past, nowMs = 2_000_000L)
        )
    }

    @Test
    fun status_activeWithFutureExpiry_staysActive() {
        val future = 3_000_000L
        assertEquals(
            "active",
            SubscriberDisplayRules.effectiveAccountStatus("Active", future, nowMs = 2_000_000L)
        )
    }

    @Test
    fun status_providerSuspended_isPreserved() {
        assertEquals(
            "suspended",
            SubscriberDisplayRules.effectiveAccountStatus("suspended", 3_000_000L, nowMs = 2_000_000L)
        )
    }

    @Test
    fun status_providerExpired_isPreserved() {
        assertEquals(
            "expired",
            SubscriberDisplayRules.effectiveAccountStatus("expired", 3_000_000L, nowMs = 2_000_000L)
        )
    }

    @Test
    fun status_unknownStatusWithoutExpiry_isLeftAlone() {
        assertNull(SubscriberDisplayRules.effectiveAccountStatus(null, null, nowMs = 2_000_000L))
        assertEquals(
            "weird",
            SubscriberDisplayRules.effectiveAccountStatus("weird", null, nowMs = 2_000_000L)
        )
    }

    // ========================================================================
    // Online duration
    // ========================================================================

    @Test
    fun onlineDuration_rendersCompactHoursAndMinutes() {
        assertEquals("2h 15m", SubscriberDisplayRules.formatOnlineDuration(8100))
        assertEquals("45m", SubscriberDisplayRules.formatOnlineDuration(2700))
        assertEquals("1h 0m", SubscriberDisplayRules.formatOnlineDuration(3600))
    }

    @Test
    fun onlineDuration_aZeroLengthSessionRendersZeroMinutes() {
        assertEquals("0m", SubscriberDisplayRules.formatOnlineDuration(0))
        assertNull(SubscriberDisplayRules.formatOnlineDuration(null))
        assertNull(SubscriberDisplayRules.formatOnlineDuration(-1))
    }

    // ========================================================================
    // Plan price: 0.0 is a free plan, null is unknown. Conflating them is
    // what made a free plan display as a fabricated 40,000 IQD.
    // Live reference: SAMM plan 10 "Owner" price 0.0, plan 6 "Economy" 35.0.
    // ========================================================================

    @Test
    fun planPrice_zeroReadsAsFreeAndNullReadsAsUnknown() {
        assertEquals("Free", SubscriberDisplayRules.formatPlanPrice(0.0, "IQD", "en"))
        assertEquals("Unknown", SubscriberDisplayRules.formatPlanPrice(null, "IQD", "en"))
        assertEquals("35,000 IQD", SubscriberDisplayRules.formatPlanPrice(35000.0, "IQD", "en"))
    }

    @Test
    fun planPrice_usesTheProviderCurrency_andOmitsTheLabelWhenThereIsNone() {
        // SAMM reports currency_symbol / currency_code; nothing may be hardcoded.
        assertEquals("20,000 IQD", SubscriberDisplayRules.formatPlanPrice(20000.0, "IQD", "en"))
        assertEquals("20,000 USD", SubscriberDisplayRules.formatPlanPrice(20000.0, "USD", "en"))
        assertEquals(
            "A provider that reports no currency must not have one invented",
            "20,000",
            SubscriberDisplayRules.formatPlanPrice(20000.0, null, "en")
        )
        assertEquals(
            "A blank currency label must not render a stray space",
            "20,000",
            SubscriberDisplayRules.formatPlanPrice(20000.0, "  ", "en")
        )
    }

    @Test
    fun isFreePlan_distinguishesAnExplicitZeroFromUnknown() {
        assertTrue(SubscriberDisplayRules.isFreePlan(0.0))
        assertFalse(SubscriberDisplayRules.isFreePlan(null))
        assertFalse(SubscriberDisplayRules.isFreePlan(35000.0))
    }

    @Test
    fun expiration_microsecondTimestampsAreAccepted() {
        // The live server emits 6 fractional digits on created_at/exhausted_at.
        // 21:35+03:00 is already Baghdad local; 18:35Z is 21:35 in Baghdad (UTC+3).
        assertEquals(
            "2026-10-03 9:35 PM",
            SubscriberDisplayRules.formatSubscriberExpiration("2026-10-03T21:35:46.455598+03:00")
        )
        assertEquals(
            "2026-10-03 9:35 PM",
            SubscriberDisplayRules.formatSubscriberExpiration("2026-10-03T18:35:16.333847+00:00")
        )
    }

    // ========================================================================
    // Coupling guard: the "Remaining" row parses the very string we now format
    // ========================================================================

    @Test
    fun remainingTime_stillParsesTheFormattedExpirationStamp() {
        val formatted = SubscriberDisplayRules.formatSubscriberExpiration("2026-12-01T11:30:45Z")
        assertEquals("2026-12-01 2:30 PM", formatted)
        val remaining = getRemainingTime(formatted, null, "en", null)
        assertTrue(
            "getRemainingTime must still understand the h:mm a stamp, got '$remaining'",
            remaining.isNotBlank() && !remaining.equals("N/A", ignoreCase = true) &&
                remaining.contains("day", ignoreCase = true)
        )
    }
}