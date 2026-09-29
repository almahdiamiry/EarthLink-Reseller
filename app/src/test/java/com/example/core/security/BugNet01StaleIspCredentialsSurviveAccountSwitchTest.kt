package com.example.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.EarthlinkApp
import kotlinx.coroutines.Dispatchers
import org.mockito.Mockito
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED regression test for BUG-NET-1.
 *
 * Claim: a Firebase identity boundary is not a credential-scope boundary. Signing in as a
 * second Google account must not leave the previous account's ISP admin credentials resident,
 * and must not publish them into the new account's settings document.
 *
 * Authority: Target Product Contract v0.6 TQ-18 acceptance item 9 - "A stale session cannot
 * overwrite another user's operational credentials."
 *
 * The credential-clearing guard is applied in 2 of 4 sites: clearCredentials() (7 keys) and
 * SyncRepositoryImpl.signOut() have it; clearAuthToken() (1 key) and signInWithGoogle()
 * (0 keys) do not.
 *
 * Seam / Environment: ROBOLECTRIC tier, real PreferenceManager over real SharedPreferences.
 *
 * Independent Oracle: after a session change, the ISP admin pair in resident storage must
 * belong to no account. Any non-blank ISP credential left behind after the identity changes
 * is, by definition, the previous account's credential.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BugNet01StaleIspCredentialsSurviveAccountSwitchTest {

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
    fun signingInAsSecondAccount_leavesNoResidentIspCredentials() =
        kotlinx.coroutines.runBlocking {
        // Account A is signed in and its ISP credentials are stored.
        prefs.saveIspAdminUsername("reseller_A_isp")
        prefs.saveIspAdminPassword("PASS_A_secret")
        prefs.saveAuthToken("google_oauth_session_uidA")
        val aUser = prefs.getIspAdminUsername()
        val aPass = prefs.getIspAdminPassword()
        assertEquals("Precondition: account A credentials are stored", "reseller_A_isp", aUser)
        assertTrue("Precondition: account A password is stored", aPass!!.isNotBlank())

        // A 401 wipes the session token (the path Repositories.kt:128-136 takes).
        prefs.clearAuthToken()
        assertNull(
            "Precondition: the session token must be cleared on 401",
            prefs.getAuthToken()
        )

        // Account B signs in with Google, exactly as AuthViewModel.signInWithGoogle does.
        val syncRepo = Mockito.mock(com.example.domain.repository.SyncRepository::class.java)
        Mockito.`when`(syncRepo.googleSignIn(Mockito.anyString())).thenReturn("uidB")
        val audit = Mockito.mock(com.example.domain.repository.AuditRepository::class.java)
        val authViewModel = com.example.ui.viewmodels.AuthViewModel(
            gateway = Mockito.mock(com.example.domain.repository.EarthlinkGateway::class.java),
            prefs = prefs,
            audit = audit,
            syncRepo = syncRepo
        )
        authViewModel.signInWithGoogle("idTokenB", "b@example.com") { }

        val leftoverUser = prefs.getIspAdminUsername()
        val leftoverPass = prefs.getIspAdminPassword()
        val diag = "after switching to account B: ispUser='$leftoverUser' " +
            "ispPassPresent=${leftoverPass?.isNotBlank()} token='${prefs.getAuthToken()}'"
        println(diag)

        val leaked = leftoverUser?.isNotBlank() == true || leftoverPass?.isNotBlank() == true
        assertTrue(
            "After switching Firebase identity, the previous account's ISP admin credentials " +
                "are still resident in storage. syncUserSettings uploads the ISP pair into the NEW " +
                "account's settings document, encrypted under the new uid, and the app then " +
                "authenticates to the ISP as the OLD account while signed in as the new one. " +
                "This violates TQ-18 item 9.\n$diag",
            !leaked
        )
    }
}
