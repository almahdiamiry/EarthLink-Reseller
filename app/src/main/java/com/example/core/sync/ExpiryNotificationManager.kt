package com.example.core.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
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

                        postNotification(context, account.id.hashCode(), title, body)
                        sharedPrefs.edit().putLong("notified_${account.id}", currentMs).apply()
                        notificationCount++
                        Log.i("ExpiryNotificationMgr", "Notified expiry for account ID: ${account.id} (username: ${account.earthlinkUsername})")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
                Log.w("ExpiryNotificationMgr", "Failed to parse expiry date string: $expiresAtStr for account: ${account.id}", e)
            }
        }
        Log.d("ExpiryNotificationMgr", "Expiry monitoring complete. Posted $notificationCount notifications.")
    }

    private fun postNotification(context: Context, id: Int, title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // fallback drawable built-in
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)

        try {
            notificationManager.notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.e("ExpiryNotificationMgr", "SecurityException: permission missing for notification posting", e)
        }
    }
}
