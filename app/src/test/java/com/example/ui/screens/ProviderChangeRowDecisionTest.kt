package com.example.ui.screens

import com.example.core.network.SasSubscriberPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderChangeRowDecisionTest
 *
 * The "Change provider" dialog is the only place in the app where a failed lookup used to be shown to
 * an operator as a fact. `checkProviderPresence` is covered at the ViewModel layer by
 * `ProviderPresenceCheckTest`; this file covers the other half of the same claim - that the row
 * rendering can actually EXPRESS the difference, rather than collapsing it back to one greyed-out
 * sentence.
 *
 * Core Triad:
 * 1. Claim - a provider whose lookup failed (UNKNOWN) must not be rendered as "not on this provider",
 *    and must not be greyed out as if it had been checked. A row is unpickable only for a reason the
 *    app can stand behind: no credentials, or a confirmed absence.
 * 2. Seam - JVM headless test of the two pure decision functions the dialog composable calls directly.
 * 3. Oracle - the literal user-facing strings. They are written out here by hand from the product
 *    wording, not captured from the code under test, so a change of wording is a visible diff.
 */
class ProviderChangeRowDecisionTest {

    /**
     * Claim: an UNKNOWN row stays pickable. Greying it out is how an offline device ended up
     *        asserting the subscriber is on neither ISP.
     * Seam: JVM headless.
     * Independent Oracle: Only ABSENT and missing credentials may block a row; UNKNOWN is a third
     *        state and must not inherit either one's behaviour.
     */
    @Test
    fun unknownPresence_keepsTheRowPickable() {
        assertTrue(
            "A lookup that failed is not evidence the subscriber is absent, so the row must stay pickable",
            isProviderRowSelectable(isConfigured = true, presence = SasSubscriberPresence.UNKNOWN)
        )
    }

    /**
     * Claim: a confirmed absence greys the row out, and missing credentials do too.
     * Seam: JVM headless.
     * Independent Oracle: ABSENT and !isConfigured are the only two blocking states; each is asserted
     *        on its own so neither can silently stop blocking.
     */
    @Test
    fun confirmedAbsenceOrMissingCredentials_disablesTheRow() {
        assertFalse(
            "A provider that was successfully asked and does not carry the subscriber is a real block",
            isProviderRowSelectable(isConfigured = true, presence = SasSubscriberPresence.ABSENT)
        )
        assertFalse(
            "A provider with no credentials cannot be targeted at all",
            isProviderRowSelectable(isConfigured = false, presence = SasSubscriberPresence.PRESENT)
        )
        assertFalse(
            "Missing credentials block regardless of whatever the presence lookup managed to find",
            isProviderRowSelectable(isConfigured = false, presence = SasSubscriberPresence.UNKNOWN)
        )
    }

    /**
     * Claim: before the lookup finishes (presence == null) the row is still pickable, which is the
     *        pre-existing behaviour this work must not regress.
     * Seam: JVM headless.
     * Independent Oracle: null means "not asked yet", which is not the same as "asked and absent".
     */
    @Test
    fun notYetLookedUp_keepsTheRowPickableAndSaysNothing() {
        assertTrue(isProviderRowSelectable(isConfigured = true, presence = null))
        assertNull(providerRowNotice(isConfigured = true, presence = null, currentLang = "en"))
    }

    /**
     * Claim: a failed lookup says "Could not verify", which is a different sentence from the one that
     *        reports a confirmed absence. This is the assertion that would have failed before the fix.
     * Seam: JVM headless.
     * Independent Oracle: The literal strings below are the specification. They are deliberately
     *        different from each other so that returning the absence wording for UNKNOWN is a failure.
     */
    @Test
    fun unknownPresence_saysCouldNotVerify_notNotOnThisProvider() {
        val unknownNotice = providerRowNotice(
            isConfigured = true,
            presence = SasSubscriberPresence.UNKNOWN,
            currentLang = "en"
        )
        val absentNotice = providerRowNotice(
            isConfigured = true,
            presence = SasSubscriberPresence.ABSENT,
            currentLang = "en"
        )

        assertEquals("Could not verify", unknownNotice)
        assertEquals("Not on this provider", absentNotice)
        assertTrue(
            "The unknown and absent notices must not be the same sentence",
            unknownNotice != absentNotice
        )
    }

    /**
     * Claim: missing credentials keep their own wording in both languages, and keep priority over the
     *        presence notice, because "not logged in" is the actionable fact.
     * Seam: JVM headless.
     * Independent Oracle: The four literal strings are the product wording for the two languages.
     */
    @Test
    fun notices_areCorrectInBothLanguagesAndMissingCredentialsWins() {
        assertEquals("Not logged in", providerRowNotice(isConfigured = false, presence = SasSubscriberPresence.UNKNOWN, currentLang = "en"))
        assertEquals("Not logged in", providerRowNotice(isConfigured = false, presence = SasSubscriberPresence.PRESENT, currentLang = "en"))
        assertEquals("غير مسجل الدخول", providerRowNotice(isConfigured = false, presence = SasSubscriberPresence.UNKNOWN, currentLang = "ar"))

        assertEquals("Not on this provider", providerRowNotice(isConfigured = true, presence = SasSubscriberPresence.ABSENT, currentLang = "en"))
        assertEquals("غير موجود لدى المزود", providerRowNotice(isConfigured = true, presence = SasSubscriberPresence.ABSENT, currentLang = "ar"))

        assertEquals("Could not verify", providerRowNotice(isConfigured = true, presence = SasSubscriberPresence.UNKNOWN, currentLang = "en"))
        assertEquals("تعذّر التحقق", providerRowNotice(isConfigured = true, presence = SasSubscriberPresence.UNKNOWN, currentLang = "ar"))
    }

    /**
     * Claim: a verified row says nothing at all, so a fully healthy provider is not annotated.
     * Seam: JVM headless.
     * Independent Oracle: PRESENT is the only state with no notice; null is the only correct return.
     */
    @Test
    fun presentProvider_hasNoNotice() {
        assertNull(providerRowNotice(isConfigured = true, presence = SasSubscriberPresence.PRESENT, currentLang = "en"))
        assertNull(providerRowNotice(isConfigured = true, presence = SasSubscriberPresence.PRESENT, currentLang = "ar"))
    }

    /**
     * Claim: an unverified row is warned about in a distinct colour from a confirmed absence, so the
     *        two states are distinguishable at a glance and not only by reading the text.
     * Seam: JVM headless.
     * Independent Oracle: Color equality is the assertion: the UNKNOWN colour must differ from the
     *        muted colour used for a confirmed absence, and must match the dialog's warning colour.
     */
    @Test
    fun unknownPresence_isWarnedAboutWithADistinctColour() {
        val unknownColor = providerNoticeColor(isConfigured = true, presence = SasSubscriberPresence.UNKNOWN)
        val absentColor = providerNoticeColor(isConfigured = true, presence = SasSubscriberPresence.ABSENT)

        assertTrue(
            "An unverified row must not look identical to a confirmed absence",
            unknownColor != absentColor
        )
        assertTrue(
            "The warning must be amber, the colour this dialog already uses for caution",
            unknownColor.red > 0.9f && unknownColor.green in 0.4f..0.7f && unknownColor.blue < 0.1f
        )
        assertTrue(
            "An unconfigured row stays muted",
            providerNoticeColor(isConfigured = false, presence = SasSubscriberPresence.UNKNOWN) == absentColor
        )
    }
}