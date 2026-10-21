package com.example.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the global SAMM-logout tombstone flag (GAP-1).
 *
 * Claim: `clearSammCredentials()` (a logout) latches a pending-clear flag so the settings
 * sync can emit explicit Firestore document deletions instead of silently preserving the
 * old SAMM credentials in the cloud (blank locals are otherwise skipped by the
 * blank-skip write rules). Any subsequent non-blank SAMM save (a re-connection) or an
 * explicit `markSammClearUploaded()` (post upload) must clear the latch.
 *
 * Authority: Target Product Contract v0.6 (settings sync must not resurrect credentials
 * after the user disconnected a provider); sync design for GAP-1 "global logout
 * propagates".
 *
 * Seam / Environment: ROBOLECTRIC tier, real PreferenceManager over real SharedPreferences.
 *
 * Independent Oracle: the flag is a state machine written explicitly by the code under
 * test — logout sets it, re-configure clears it. Expected values are literal true/false,
 * never derived from production math.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SammLogoutTombstonePendingFlagTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        (context as? EarthlinkApp)?.isSafeDebugFallbackAllowedOverride = true
        context.getSharedPreferences("reseller_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = PreferenceManager(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("reseller_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun logoutLatchesPendingClear() {
        assertFalse("Fresh state must not carry a stale tombstone", prefs.isSammClearPendingForSync())

        prefs.saveSammBaseUrl("https://samm.example.com")
        prefs.saveSammToken("tok")
        assertTrue(prefs.isSammConfigured())

        prefs.clearSammCredentials()

        assertFalse("Logout must disconnect the local SAMM config", prefs.isSammConfigured())
        assertTrue("Logout must latch the pending-clear flag for the next settings sync", prefs.isSammClearPendingForSync())
    }

    @Test
    fun reconfigureCancelsPendingClear() {
        prefs.saveSammBaseUrl("https://samm.example.com")
        prefs.saveSammToken("tok")
        prefs.clearSammCredentials()
        assertTrue(prefs.isSammClearPendingForSync())

        // The user re-connects before the next sync pass runs: the pending tombstone must
        // be abandoned, otherwise the sync would upload a deletion for live credentials.
        // A re-connection re-saves both the URL and the token.
        prefs.saveSammBaseUrl("https://samm.example.com")
        prefs.saveSammToken("tok2")

        assertFalse("A non-blank re-save must cancel the pending tombstone", prefs.isSammClearPendingForSync())
        assertTrue(prefs.isSammConfigured())
    }

    @Test
    fun markUploadedClearsPendingClear() {
        prefs.clearSammCredentials()
        assertTrue(prefs.isSammClearPendingForSync())

        prefs.markSammClearUploaded()

        assertFalse("Uploading the tombstone must clear the latch so it fires exactly once", prefs.isSammClearPendingForSync())
        assertFalse("Marking uploaded must not resurrect SAMM config", prefs.isSammConfigured())
    }
}
