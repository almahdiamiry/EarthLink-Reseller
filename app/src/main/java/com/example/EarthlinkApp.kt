package com.example

import android.app.Application
import com.example.core.database.AppDatabase
import com.example.core.network.NetworkClient
import com.example.core.security.PreferenceManager
import com.example.core.sync.SyncRepositoryImpl
import com.example.core.sync.ExpiryNotificationManager
import com.example.data.repository.*
import com.example.domain.repository.*
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.alamiry.earthlinkreseller.BuildConfig

class EarthlinkApp : Application() {

    val preferenceManager: PreferenceManager by lazy { PreferenceManager(this) }

    internal var isSafeDebugFallbackAllowedOverride: Boolean? = null

    @androidx.annotation.VisibleForTesting
    internal fun isSafeDebugFallbackAllowed(): Boolean {
        if (!BuildConfig.DEBUG) return false
        return isSafeDebugFallbackAllowedOverride
            ?: (android.os.Build.FINGERPRINT.lowercase(java.util.Locale.ROOT) != "robolectric")
    }

    val database: AppDatabase by lazy {
        try {
            AppDatabase.getDatabase(this, preferenceManager.getDatabasePassphrase().toByteArray(Charsets.UTF_8))
        } catch (e: Throwable) {
            if (isSafeDebugFallbackAllowed()) {
                android.util.Log.e("EarthlinkApp", "SQLCipher failed to load native libs, falling back to unencrypted database", e)
                AppDatabase.getDatabase(this, ByteArray(0))
            } else {
                throw RuntimeException("Fatal SQLCipher load failure", e)
            }
        }
    }

    val apiService: NetworkClient by lazy { NetworkClient(this) }
    val earthlinkGateway: EarthlinkGateway by lazy { EarthlinkGatewayImpl(apiService.apiService, preferenceManager) }

    val localAccountRepository: LocalAccountRepository by lazy { LocalAccountRepositoryImpl(database, database.localAccountDao(), database.syncOutboxDao()) }
    val localLedgerRepository: LocalLedgerRepository by lazy { LocalLedgerRepositoryImpl(database, database.localLedgerEntryDao(), database.localAccountDao(), database.syncOutboxDao()) }
    val utowerImportRepository: UtowerImportRepository by lazy { UtowerImportRepositoryImpl(this, database, database.importBatchDao(), database.localAccountDao(), database.localLedgerEntryDao(), database.syncOutboxDao(), auditRepository) }

    val syncRepository: SyncRepository by lazy {
        SyncRepositoryImpl(
            context = this,
            appDatabase = database,
            outboxDao = database.syncOutboxDao(),
            accountDao = database.localAccountDao(),
            ledgerDao = database.localLedgerEntryDao(),
            batchDao = database.importBatchDao(),
            metadataDao = database.syncMetadataDao(),
            auditDao = database.auditLogDao()
        )
    }

    val auditRepository: AuditRepository by lazy { AuditRepositoryImpl(database, database.auditLogDao(), database.syncOutboxDao(), syncRepository, preferenceManager) }

    override fun onCreate() {
        super.onCreate()
        ExpiryNotificationManager.registerNotificationChannel(this)

        // Initialize Firebase synchronously on the main thread to ensure dependencies are ready
        try {
            var initialized = false
            try {
                FirebaseApp.initializeApp(this@EarthlinkApp)
                initialized = !FirebaseApp.getApps(this@EarthlinkApp).isEmpty()
                if (initialized) {
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                }
            } catch (e: Throwable) {
                // Ignore and try manual fallback
            }

            if (!initialized) {
                val apiKey = BuildConfig.FIREBASE_API_KEY
                val appId = BuildConfig.FIREBASE_APPLICATION_ID
                val projectId = BuildConfig.FIREBASE_PROJECT_ID
                val dbUrl = BuildConfig.FIREBASE_DATABASE_URL
                if (apiKey.isBlank() || apiKey.contains("Dummy") || appId.isBlank() || appId.contains("1234567890")) { 
                    throw RuntimeException("Configuration error: Firebase credentials missing. Please set real values in .env for FIREBASE_API_KEY, FIREBASE_APPLICATION_ID, FIREBASE_PROJECT_ID, FIREBASE_DATABASE_URL.")
                } else {
                    val options = FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .setDatabaseUrl(dbUrl)
                        .setProjectId(projectId)
                        .build()
                    FirebaseApp.initializeApp(this@EarthlinkApp, options)
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                    android.util.Log.i("EarthlinkApp", "Firebase initialized successfully for project $projectId")
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("EarthlinkApp", "Failed to initialize Firebase fallback. App will run in offline mode.", e)
        }

        // Setup background tasks
        CoroutineScope(Dispatchers.Default).launch {
            try {
                SyncRepositoryImpl.setupPeriodicSync(this@EarthlinkApp)
            } catch (e: Throwable) {
                android.util.Log.e("EarthlinkApp", "Failed to schedule periodic background sync via WorkManager.", e)
            }
        }
    }
}
