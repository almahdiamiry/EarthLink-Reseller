package com.example.core.sync

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Claim: ExpiryNotificationManager skips history-only subscribers (isHistoryOnlySubscriber = true)
 * and only posts expiry notifications for active subscribers whose expiration date is within 3 days.
 * Seam / Environment: ROBOLECTRIC
 * Independent Oracle: Directly verified via Robolectric NotificationManager shadow notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExpiryNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getDatabase(context, ByteArray(0))
    }

    @After
    fun tearDown() {
        AppDatabase.closeAndRemoveInstance("earthlink_reseller_db")
    }

    @Test
    fun testCheckAndNotifyExpiringSubscriptions_skipsHistoryOnlySubscribers() = runBlocking {
        val now = System.currentTimeMillis()
        val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val expDateStr = isoParser.format(Date(now + 24 * 60 * 60 * 1000L)) // 1 day from now

        val activeAccount = LocalAccount(
            id = "acc_active",
            earthlinkUsername = "active_user",
            displayName = "Active User",
            expiresAt = expDateStr,
            isHistoryOnlySubscriber = false,
            createdAt = now,
            updatedAt = now
        )

        val historyOnlyAccount = LocalAccount(
            id = "acc_history_only",
            earthlinkUsername = "history_user",
            displayName = "History Only User",
            expiresAt = expDateStr,
            isHistoryOnlySubscriber = true,
            createdAt = now,
            updatedAt = now
        )

        db.localAccountDao().insert(activeAccount)
        db.localAccountDao().insert(historyOnlyAccount)

        ExpiryNotificationManager.checkAndNotifyExpiringSubscriptions(context, db)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(notificationManager)

        // Only activeAccount should have triggered a notification
        assertEquals(1, shadowNm.allNotifications.size)
        val notification = shadowNm.allNotifications[0]
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        assertTrue("Expected title to be either Arabic or English alert title, got: $title", title == "تنبيه انتهاء الاشتراك" || title == "Subscription Expiry Alert")
        assertTrue("Expected text to contain 'Active User', got: $text", text.contains("Active User"))
        assertTrue("Expected text not to contain 'History Only User', got: $text", !text.contains("History Only User"))
    }
}
