package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.example.core.security.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression test for BUG-OB-1 (permission-request half).
 *
 * Claim: the POST_NOTIFICATIONS prompt must be raised BEFORE login, while the operator is
 * still on the login screen, so the decision is made at first run and expiry alerts are
 * usable from the very first session.
 *
 * Seam / Environment: ROBOLECTRIC tier on SDK 33 with a real Activity and Compose.
 *
 * Independent Oracle: the app holds no auth session in a fresh install, so the login screen
 * is what is on screen at first composition. The test proves two things separately -
 * (1) the user is unauthenticated, and (2) the platform actually received a
 * request-permission call for POST_NOTIFICATIONS. Asserting only "a composable ran" would
 * not prove the prompt was ever issued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NotificationPermissionRequestedBeforeLoginTest {

    @get:Rule(order = 0)
    val fallbackRule = object : org.junit.rules.TestRule {
        override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
            object : org.junit.runners.model.Statement() {
                override fun evaluate() {
                    // Allow the unencrypted debug database so MainActivity can start under Robolectric.
                    (ApplicationProvider.getApplicationContext<Application>() as EarthlinkApp)
                        .isSafeDebugFallbackAllowedOverride = true
                    base.evaluate()
                }
            }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstLaunch_promptsForNotificationsWhileStillLoggedOut() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // (1) Precondition: no session, so the login screen is what is displayed.
        val hasToken = app.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            .all
            .any { it.key.contains("token", ignoreCase = true) && it.value.toString().isNotBlank() }
        assertFalse(
            "Precondition: a fresh install must be unauthenticated (login screen visible)",
            hasToken
        )
        assertFalse(
            "Precondition: POST_NOTIFICATIONS must start un-granted",
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )

        // (2) The platform really received the request, not just a composable executing.
        // Robolectric returns a PermissionsRequest wrapper whose shape varies by version, so
        // normalise it down to the list of permission names actually asked for.
        val raw: Any? = shadowOf(composeRule.activity).lastRequestedPermission
        val requestedNames: List<String> = when (raw) {
            null -> emptyList()
            is Array<*> -> raw.mapNotNull { it?.toString() }
            is Collection<*> -> raw.mapNotNull { it?.toString() }
            else -> runCatching {
                val field = raw.javaClass.getField("requestedPermissions")
                (field.get(raw) as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty()
            }.getOrDefault(listOf(raw.toString()))
        }

        assertEquals(
            "POST_NOTIFICATIONS must be requested before the user logs in. " +
                "Actual platform request: ${if (requestedNames.isEmpty()) "<none>" else requestedNames}",
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            requestedNames
        )
    }
}
