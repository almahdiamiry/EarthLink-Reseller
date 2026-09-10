package com.example.core.sync

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
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Claim: ExpiryNotificationManager ignores tombstoned history-only subscribers (isHistoryOnlySubscriber == true)
 *        during background subscription expiry monitoring to prevent false alert notifications.
 * Seam / Environment: ROBOLECTRIC in-memory Room database.
 * Independent Oracle: A history-only account with an expiry date within 1 day must NOT produce a notification record in shared preferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExpiryNotificationManagerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun checkAndNotifyExpiringSubscriptions_skipsHistoryOnlySubscribers() = runBlocking {
        val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val tomorrowMs = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
        val expiresAtString = isoParser.format(Date(tomorrowMs))

        val activeAccount = LocalAccount(
            id = "acc-active-1",
            displayName = "Active User",
            earthlinkUsername = "active_user",
            expiresAt = expiresAtString,
            isHistoryOnlySubscriber = false
        )

        val historyOnlyAccount = LocalAccount(
            id = "acc-history-2",
            displayName = "Deleted User",
            earthlinkUsername = "deleted_user",
            expiresAt = expiresAtString,
            isHistoryOnlySubscriber = true
        )

        db.localAccountDao().insert(activeAccount)
        db.localAccountDao().insert(historyOnlyAccount)

        ExpiryNotificationManager.checkAndNotifyExpiringSubscriptions(context, db)

        val sharedPrefs = context.getSharedPreferences("expiry_notification_prefs", Context.MODE_PRIVATE)
        val activeNotified = sharedPrefs.getLong("notified_${activeAccount.id}", 0L)
        val historyNotified = sharedPrefs.getLong("notified_${historyOnlyAccount.id}", 0L)

        // Active account should be notified (timestamp > 0)
        assert(activeNotified > 0L) { "Active account should have been notified" }

        // History-only account must NOT be notified (timestamp == 0)
        assertEquals(0L, historyNotified)
    }
}
