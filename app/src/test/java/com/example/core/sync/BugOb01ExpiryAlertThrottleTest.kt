package com.example.core.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * RED regression test for BUG-OB-1.
 *
 * Claim: the expiry manager must not record an expiry alert as delivered unless the
 * platform actually accepted it. A false "notified" marker suppresses that account for
 * the full 24h throttle window, so a subscriber can cross the entire 3-day alert window
 * with no alert at all.
 *
 * On Android 13+ a missing POST_NOTIFICATIONS grant makes NotificationManager.notify a
 * silent no-op that throws nothing, so "did not throw" was never evidence of delivery.
 * targetSdk is 36, so the grant is a runtime permission.
 *
 * Seam / Environment: ROBOLECTRIC tier on SDK 33, real ExpiryNotificationManager over an
 * in-memory Room database.
 *
 * Independent Oracle: the throttle marker is a persisted claim that the reseller WAS told.
 * If the alert was not delivered, no such claim may exist. It is read straight out of the
 * manager's shared-preferences file, not from any internal counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class BugOb01ExpiryAlertThrottleTest {

    private val prefsName = "expiry_notification_prefs"
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun throttleMarker(context: Context, accountId: String): Long =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getLong("notified_$accountId", 0L)

    @Test
    fun suppressedAlert_doesNotWriteThrottleMarker() = runBlocking {
        val context: Application = ApplicationProvider.getApplicationContext()

        // Expires 1 day out, inside the 3-day alert window. Format matches the real
        // producer (Repositories/UtowerImporter write Asia/Baghdad "yyyy-MM-dd HH:mm:ss").
        val baghdad = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Baghdad")
        }
        val expiresAt = baghdad.format(System.currentTimeMillis() + 24 * 60 * 60 * 1000L)

        db.localAccountDao().upsert(
            LocalAccount(
                id = "acc_exp_01",
                earthlinkUsername = "sub_expiring",
                displayName = "Expiring Subscriber",
                expiresAt = expiresAt,
                createdAt = System.currentTimeMillis()
            )
        )

        // POST_NOTIFICATIONS is NOT granted on this API 33 context, matching a real
        // Android 13+ device where the reseller declined the runtime prompt.
        ExpiryNotificationManager.checkAndNotifyExpiringSubscriptions(context, db)

        val marker = throttleMarker(context, "acc_exp_01")
        assertEquals(
            "The expiry alert was suppressed, yet a 'notified' marker was written. That marker " +
                "suppresses this account for 24h even though the reseller was never told, so a " +
                "subscriber can cross the whole 3-day window un-alerted.",
            0L, marker
        )
    }
}
