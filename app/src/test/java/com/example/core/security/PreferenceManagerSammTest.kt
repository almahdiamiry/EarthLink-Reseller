package com.example.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 4 Unit Test Suite: SAMM Credential and Configuration Lifecycle.
 *
 * Core Triad Specification:
 * 1. Claim:
 *    SAMM base URL and API token credentials must be securely stored in EncryptedSharedPreferences,
 *    normalized upon saving (whitespace trimmed, trailing slashes removed), masked in UI presentation
 *    without leaking plaintext tokens, completely cleared upon operator logout / credential clear,
 *    and tracked via an incrementing configuration revision counter (sammConfigVersionFlow) to
 *    reliably invalidate cached gateway network clients.
 *
 * 2. Seam / Environment:
 *    ROBOLECTRIC tier (RobolectricTestRunner, Android SDK test sandbox), real PreferenceManager
 *    instance operating over real SharedPreferences storage.
 *
 * 3. Independent Oracle:
 *    Expected configuration states, sanitized URLs, token masks ("••••", "••••••••"), and
 *    revision counter increments are derived from fixed protocol expectations independently
 *    of the production PreferenceManager implementation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PreferenceManagerSammTest {

    private lateinit var context: Context
    private lateinit var preferenceManager: PreferenceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferenceManager = PreferenceManager(context)
        preferenceManager.clearCredentials()
    }

    @After
    fun tearDown() {
        preferenceManager.clearCredentials()
    }

    @Test
    fun saveAndGetSammBaseUrl_normalizesTrailingSlashesAndWhitespace() {
        // Trailing slashes should be stripped
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net/")
        assertEquals("https://samm.al-amiry.net", preferenceManager.getSammBaseUrl())

        // Multiple trailing slashes and surrounding whitespace should be normalized
        preferenceManager.saveSammBaseUrl("   http://192.168.1.10:8080/api/v1///   ")
        assertEquals("http://192.168.1.10:8080/api/v1", preferenceManager.getSammBaseUrl())

        // Saving null or blank removes the configuration
        preferenceManager.saveSammBaseUrl("")
        assertNull(preferenceManager.getSammBaseUrl())

        preferenceManager.saveSammBaseUrl("https://valid.host.com")
        preferenceManager.saveSammBaseUrl("   ")
        assertNull(preferenceManager.getSammBaseUrl())

        preferenceManager.saveSammBaseUrl("https://valid.host.com")
        preferenceManager.saveSammBaseUrl(null)
        assertNull(preferenceManager.getSammBaseUrl())
    }

    @Test
    fun saveAndGetSammToken_trimsWhitespaceAndHandlesNullOrBlank() {
        // Valid token is trimmed and stored
        preferenceManager.saveSammToken("  samm_secret_bearer_token_123  ")
        assertEquals("samm_secret_bearer_token_123", preferenceManager.getSammToken())

        // Null, empty, or blank removes the token
        preferenceManager.saveSammToken("")
        assertNull(preferenceManager.getSammToken())

        preferenceManager.saveSammToken("samm_token_456")
        preferenceManager.saveSammToken("   ")
        assertNull(preferenceManager.getSammToken())

        preferenceManager.saveSammToken("samm_token_789")
        preferenceManager.saveSammToken(null)
        assertNull(preferenceManager.getSammToken())
    }

    @Test
    fun getMaskedSammToken_doesNotRevealPlaintextToken() {
        // Unconfigured returns empty string
        assertNull(preferenceManager.getSammToken())
        assertEquals("", preferenceManager.getMaskedSammToken())

        // Short token (<= 4 chars) returns 4 dots
        preferenceManager.saveSammToken("1234")
        assertEquals("••••", preferenceManager.getMaskedSammToken())
        assertFalse(preferenceManager.getMaskedSammToken().contains("1234"))

        // Standard token (> 4 chars) returns 8 dots
        val rawToken = "samm_f7a93b2c1e8d4a6b"
        preferenceManager.saveSammToken(rawToken)
        val masked = preferenceManager.getMaskedSammToken()
        assertEquals("••••••••", masked)
        assertFalse("Masked token must never expose raw token text", masked.contains(rawToken))
    }

    @Test
    fun isSammConfigured_requiresBothBaseUrlAndToken() {
        assertFalse("Initially unconfigured", preferenceManager.isSammConfigured())

        // Only URL set -> false
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        assertFalse("Only URL configured", preferenceManager.isSammConfigured())

        // Only Token set -> false
        preferenceManager.saveSammBaseUrl(null)
        preferenceManager.saveSammToken("samm_token_abc")
        assertFalse("Only token configured", preferenceManager.isSammConfigured())

        // Both URL and Token set -> true
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        assertTrue("Both URL and token configured", preferenceManager.isSammConfigured())

        // Blanking either field invalidates configuration
        preferenceManager.saveSammToken("   ")
        assertFalse("Token blanked", preferenceManager.isSammConfigured())
    }

    @Test
    fun clearSammCredentials_clearsFieldsAndIncrementsVersion() {
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_test")
        assertTrue(preferenceManager.isSammConfigured())

        val versionBefore = preferenceManager.sammConfigVersionFlow.value

        preferenceManager.clearSammCredentials()

        assertNull("Base URL should be cleared", preferenceManager.getSammBaseUrl())
        assertNull("Token should be cleared", preferenceManager.getSammToken())
        assertFalse("Configuration flag should be false", preferenceManager.isSammConfigured())
        assertEquals("Masked token should be empty", "", preferenceManager.getMaskedSammToken())
        assertEquals("Config version must increment on clear", versionBefore + 1, preferenceManager.sammConfigVersionFlow.value)
    }

    @Test
    fun clearCredentials_clearsSammAlongWithOperatorCredentials() {
        // Set up operator and SAMM credentials
        preferenceManager.saveAuthToken("reseller_operator_session_token")
        preferenceManager.saveUsername("operator_john")
        preferenceManager.savePassword("operator_pass")
        preferenceManager.saveDepositPassword("deposit_secret")
        preferenceManager.saveIspAdminUsername("isp_admin")
        preferenceManager.saveIspAdminPassword("isp_pass")
        preferenceManager.saveEarthlinkApiToken("earthlink_gw_token")
        preferenceManager.saveSammBaseUrl("https://samm.al-amiry.net")
        preferenceManager.saveSammToken("samm_token_live")

        val versionBefore = preferenceManager.sammConfigVersionFlow.value

        // Perform full operator logout / credential wipe
        preferenceManager.clearCredentials()

        // Operator credentials cleared
        assertNull(preferenceManager.getAuthToken())
        assertNull(preferenceManager.getUsername())
        assertNull(preferenceManager.getPassword())
        assertTrue(preferenceManager.getDepositPassword().isEmpty())
        assertNull(preferenceManager.getIspAdminUsername())
        assertNull(preferenceManager.getIspAdminPassword())
        assertNull(preferenceManager.getEarthlinkApiToken())

        // SAMM credentials cleared
        assertNull(preferenceManager.getSammBaseUrl())
        assertNull(preferenceManager.getSammToken())
        assertFalse(preferenceManager.isSammConfigured())
        assertEquals("", preferenceManager.getMaskedSammToken())
        assertEquals("Config version must increment on clearCredentials", versionBefore + 1, preferenceManager.sammConfigVersionFlow.value)
    }

    @Test
    fun sammConfigVersionFlow_incrementsOnEveryMutation() {
        val initialVersion = preferenceManager.sammConfigVersionFlow.value

        preferenceManager.saveSammBaseUrl("https://samm.example.com")
        assertEquals(initialVersion + 1, preferenceManager.sammConfigVersionFlow.value)

        preferenceManager.saveSammToken("token_1")
        assertEquals(initialVersion + 2, preferenceManager.sammConfigVersionFlow.value)

        preferenceManager.saveSammBaseUrl("https://samm2.example.com")
        assertEquals(initialVersion + 3, preferenceManager.sammConfigVersionFlow.value)

        preferenceManager.saveSammToken(null)
        assertEquals(initialVersion + 4, preferenceManager.sammConfigVersionFlow.value)

        preferenceManager.clearSammCredentials()
        assertEquals(initialVersion + 5, preferenceManager.sammConfigVersionFlow.value)

        preferenceManager.clearCredentials()
        assertEquals(initialVersion + 6, preferenceManager.sammConfigVersionFlow.value)
    }
}
