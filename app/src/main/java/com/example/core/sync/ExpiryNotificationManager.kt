package com.example.core.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.core.database.AppDatabase
import com.example.core.model.LocalAccount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ExpiryNotificationManager {

    private const val CHANNEL_ID = "expiry_alerts_channel"
    private const val PREFS_NAME = "expiry_notification_prefs"
    private const val MIN_DAYS_ALERT = 3L // Alert if <= 3 days left
    private const val SPAM_THROTTLE_MS = 24 * 60 * 60 * 1000L // 24 hours throttle per account

    fun registerNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Subscription Expiry Alerts"
            val descriptionText = "Alerts operators when a subscriber's package is close to expiring"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d("ExpiryNotificationMgr", "Notification channel registered")
        }
    }

    suspend fun checkAndNotifyExpiringSubscriptions(context: Context, database: AppDatabase) {
        Log.d("ExpiryNotificationMgr", "Running subscription expiry monitor...")
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val language = try { com.example.core.security.PreferenceManager(context).getLanguage() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "ar" }

        val accounts = try {
            database.localAccountDao().getAllOneShot()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            Log.e("ExpiryNotificationMgr", "Failed to retrieve accounts from database", e)
            return
        }

        val currentMs = System.currentTimeMillis()
        val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Baghdad local formatting for exact timezone alignment
        val baghdadFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Baghdad")
        }

        var notificationCount = 0

        for (account in accounts) {
            val expiresAtStr = account.expiresAt ?: continue
            try {
                var expiryDate = try { isoParser.parse(expiresAtStr) } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                if (expiryDate == null) {
                    val fallbackParser = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Baghdad") }
                    expiryDate = try { fallbackParser.parse(expiresAtStr) } catch(e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                }
                if (expiryDate == null) continue
                val expiryMs = expiryDate.time
                val timeDiffMs = expiryMs - currentMs

                // Check if expiring in the future and within our warning window (<= 3 days)
                if (timeDiffMs in 0.. (MIN_DAYS_ALERT * 24 * 60 * 60 * 1000L)) {
                    val lastNotified = sharedPrefs.getLong("notified_${account.id}", 0L)
                    
                    // Throttle notification to avoid spamming the user every 15-minute sync worker cycle
                    if (currentMs - lastNotified > SPAM_THROTTLE_MS) {
                        val hoursLeft = timeDiffMs / (1000 * 60 * 60)
                        val daysLeft = hoursLeft / 24
                        val exactBaghdadTime = baghdadFormatter.format(expiryDate)

                        val title = if (language == "ar") "تنبيه انتهاء الاشتراك" else "Subscription Expiry Alert"
                        
                        val body = if (language == "ar") {
                            "المشترك \"${account.displayName}\" (${account.earthlinkUsername ?: "بدون اسم مستخدم"}) سينتهي اشتراكه بتاريخ: $exactBaghdadTime (متبقي حوالي $daysLeft يوم و ${hoursLeft % 24} ساعة)."
                        } else {
                            "Subscriber \"${account.displayName}\" (${account.earthlinkUsername ?: "N/A"}) is expiring on: $exactBaghdadTime ($daysLeft d, ${hoursLeft % 24} h remaining)."
                        }

                        val delivered = postNotification(context, account.id.hashCode(), title, body)
                        if (delivered) {
                            // Only throttle once the alert is genuinely on screen. Recording it
                            // otherwise would suppress this account for the whole throttle window
                            // even though the reseller was never told.
                            sharedPrefs.edit().putLong("notified_${account.id}", currentMs).apply()
                            notificationCount++
                            Log.i("ExpiryNotificationMgr", "Notified expiry for account ID: ${account.id} (username: ${account.earthlinkUsername})")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.w("ExpiryNotificationMgr", "Failed to parse expiry date string: $expiresAtStr for account: ${account.id}", e)
            }
        }
        Log.d("ExpiryNotificationMgr", "Expiry monitoring complete. Posted $notificationCount notifications.")
    }

    /**
     * Posts the alert and reports whether it was actually handed to the platform.
     *
     * On Android 13+ a missing POST_NOTIFICATIONS grant makes [NotificationManager.notify] a
     * silent no-op that throws nothing, so the caller must not treat a non-throwing call as
     * proof of delivery.
     *
     * @return true when the notification was posted, false when it was suppressed.
     */
    private fun postNotification(context: Context, id: Int, title: String, content: String): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("ExpiryNotificationMgr", "POST_NOTIFICATIONS not granted; expiry alert for id $id was not delivered")
            return false
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // fallback drawable built-in
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        return try {
            notificationManager.notify(id, builder.build())
            true
        } catch (e: SecurityException) {
            Log.e("ExpiryNotificationMgr", "SecurityException: permission missing for notification posting", e)
            false
        } catch (e: Exception) {
            Log.e("ExpiryNotificationMgr", "Failed to post expiry notification for id $id", e)
            false
        }
    }
}
