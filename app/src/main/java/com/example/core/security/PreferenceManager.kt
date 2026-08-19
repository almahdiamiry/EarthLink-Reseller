package com.example.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.example.core.util.AppBuildConfig
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

open class PreferenceManager(private val context: Context) {

    internal val prefs: SharedPreferences

    init {
        prefs = createEncryptedPrefs(context)
            
        // Migrate and clean up legacy unencrypted preferences if present
        migrateLegacyPrefs(context, prefs)

        // Migrate existing database passphrase to custom local Keystore and enhanced backup/recovery V3 formats
        migrateExistingPassphraseToV3()
    }

    private fun migrateLegacyPrefs(context: Context, newPrefs: SharedPreferences) {
        try {
            val oldPrefs = context.getSharedPreferences("reseller_prefs", Context.MODE_PRIVATE)
            val allEntries = oldPrefs.all
            if (allEntries.isNotEmpty()) {
                val editor = newPrefs.edit()
                for ((key, value) in allEntries) {
                    if (key != null && value != null && !newPrefs.contains(key)) {
                        when (value) {
                            is String -> {
                                editor.putString(key, value)
                                if (key == "db_passphrase" && value.isNotEmpty()) {
                                    backupPassphraseToFallback(context, value)
                                }
                            }
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Set<*> -> {
                                @Suppress("UNCHECKED_CAST")
                                (value as? Set<String>)?.let { editor.putStringSet(key, it) }
                            }
                        }
                    }
                }
                if (editor.commit()) {
                    oldPrefs.edit().clear().apply()
                    android.util.Log.i("PreferenceManager", "Successfully migrated legacy reseller_prefs to encrypted storage")
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to migrate legacy reseller_prefs", e)
        }
    }

    // --- Android Keystore Local Device Protection (AES-GCM-NoPadding) ---
    private fun getOrCreateKeystoreKey(): javax.crypto.SecretKey {
        return try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias("EarthlinkDatabaseLocalKey")) {
                val keyGenerator = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                val builder = android.security.keystore.KeyGenParameterSpec.Builder(
                    "EarthlinkDatabaseLocalKey",
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                keyGenerator.init(builder.build())
                keyGenerator.generateKey()
            }
            (keyStore.getEntry("EarthlinkDatabaseLocalKey", null) as java.security.KeyStore.SecretKeyEntry).secretKey
        } catch (e: Throwable) {
            if (!AppBuildConfig.DEBUG) {
                android.util.Log.e("PreferenceManager", "AndroidKeyStore is unavailable in release runtime!", e)
                throw IllegalStateException("AndroidKeyStore unavailable in release build: ${e.message}", e)
            }
            // Software-backed fallback for JVM/Robolectric test environments
            android.util.Log.w("PreferenceManager", "AndroidKeyStore unavailable, using JVM software-backed fallback key material", e)
            val pass = "Earthlink_Reseller_Fallback_Software_Keystore_AES_Key_Material_v1"
            val salt = "SoftwareKeySalt".toByteArray(Charsets.UTF_8)
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec(pass.toCharArray(), salt, 1000, 256)
            javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        }
    }

    private fun encryptWithKeystore(plainText: String): String {
        if (plainText.isBlank()) return ""
        return try {
            val secretKey = getOrCreateKeystoreKey()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            "v3_local:$ivB64:$cipherB64"
        } catch (e: Exception) {
            android.util.Log.e("PreferenceManager", "Failed to encrypt with Keystore", e)
            ""
        }
    }

    private fun decryptWithKeystore(wrapped: String): String {
        if (!wrapped.startsWith("v3_local:")) return ""
        return try {
            val parts = wrapped.split(":")
            if (parts.size != 3) return ""
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            
            val secretKey = getOrCreateKeystoreKey()
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("PreferenceManager", "Failed to decrypt with Keystore", e)
            ""
        }
    }

    // --- Enhanced Backup/Recovery Protection (V3: 15,000 iterations PBKDF2 + AES-GCM) ---
    private fun encryptPassphraseV3Backup(passphrase: String): String {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "device_fallback_uid_v1"
        return try {
            val salt = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
            val iv = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec(uid.toCharArray(), salt, 15000, 256)
            val secretKey = factory.generateSecret(spec)
            val keySpec = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
            
            val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val cipherB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            
            "v3_backup:$saltB64:$ivB64:$cipherB64"
        } catch (e: Exception) {
            android.util.Log.e("PreferenceManager", "Failed to encrypt V3 backup passphrase", e)
            ""
        }
    }

    private fun decryptPassphraseV3Backup(wrapped: String): String {
        if (!wrapped.startsWith("v3_backup:")) return ""
        val uidsToTry = mutableListOf<String>()
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uidsToTry.add(it) }
        uidsToTry.add("device_fallback_uid_v1")

        for (uid in uidsToTry.distinct()) {
            try {
                val parts = wrapped.split(":")
                if (parts.size == 4) {
                    val salt = Base64.decode(parts[1], Base64.NO_WRAP)
                    val iv = Base64.decode(parts[2], Base64.NO_WRAP)
                    val cipherText = Base64.decode(parts[3], Base64.NO_WRAP)

                    val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    val spec = javax.crypto.spec.PBEKeySpec(uid.toCharArray(), salt, 15000, 256)
                    val secretKey = factory.generateSecret(spec)
                    val keySpec = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)

                    val plainBytes = cipher.doFinal(cipherText)
                    val result = String(plainBytes, Charsets.UTF_8)
                    if (result.isNotEmpty()) {
                        return result
                    }
                }
            } catch (e: Exception) {
                // Try next UID
            }
        }
        return ""
    }

    // --- Legacy Decryption (For Migration Purposes) ---
    private fun getUidAesKeyLegacy(): javax.crypto.spec.SecretKeySpec? {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(uid.toByteArray(Charsets.UTF_8))
        return javax.crypto.spec.SecretKeySpec(hash, "AES")
    }

    private fun encryptPassphrase(passphrase: String): String {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "device_fallback_uid_v1"
        return try {
            val salt = ByteArray(16).apply { java.security.SecureRandom().nextBytes(this) }
            val iv = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }

            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = javax.crypto.spec.PBEKeySpec(uid.toCharArray(), salt, 10000, 256)
            val secretKey = factory.generateSecret(spec)
            val keySpec = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val encrypted = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))
            
            val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
            val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
            val cipherB64 = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
            
            "v2:$saltB64:$ivB64:$cipherB64"
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            ""
        }
    }

    private fun decryptPassphrase(encryptedPassphrase: String): Pair<String, Boolean> {
        val uidsToTry = mutableListOf<String>()
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { uidsToTry.add(it) }
        uidsToTry.add("device_fallback_uid_v1")

        for (uid in uidsToTry.distinct()) {
            if (encryptedPassphrase.startsWith("v2:")) {
                try {
                    val parts = encryptedPassphrase.split(":")
                    if (parts.size == 4) {
                        val salt = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
                        val iv = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
                        val cipherText = android.util.Base64.decode(parts[3], android.util.Base64.NO_WRAP)

                        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        val spec = javax.crypto.spec.PBEKeySpec(uid.toCharArray(), salt, 10000, 256)
                        val secretKey = factory.generateSecret(spec)
                        val keySpec = javax.crypto.spec.SecretKeySpec(secretKey.encoded, "AES")

                        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)

                        val plainBytes = cipher.doFinal(cipherText)
                        val result = String(plainBytes, Charsets.UTF_8)
                        if (result.isNotEmpty()) {
                            return Pair(result, false)
                        }
                    }
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
            }

            if (encryptedPassphrase.startsWith("AES:")) {
                val decoded = try {
                    android.util.Base64.decode(encryptedPassphrase.substring(4), android.util.Base64.NO_WRAP)
                } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }

                if (decoded != null) {
                    try {
                        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        val salt = (uid + "Earthlink_Reseller_Passphrase_Salt_v2").toByteArray(Charsets.UTF_8)
                        val spec = javax.crypto.spec.PBEKeySpec(uid.toCharArray(), salt, 10000, 256)
                        val tmp = factory.generateSecret(spec)
                        val key = javax.crypto.spec.SecretKeySpec(tmp.encoded, "AES")

                        val cipher = javax.crypto.Cipher.getInstance("AES")
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key)
                        val result = String(cipher.doFinal(decoded), Charsets.UTF_8)
                        if (result.isNotEmpty()) {
                            return Pair(result, true)
                        }
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }

                    try {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        val hash = digest.digest(uid.toByteArray(Charsets.UTF_8))
                        val legacyKey = javax.crypto.spec.SecretKeySpec(hash, "AES")

                        val cipher = javax.crypto.Cipher.getInstance("AES")
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, legacyKey)
                        val result = String(cipher.doFinal(decoded), Charsets.UTF_8)
                        if (result.isNotEmpty()) {
                            return Pair(result, true)
                        }
                    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; }
                }
            }
        }
        return Pair("", false)
    }

    // --- Dual Protection Safe Migration (Phase N1) ---
    fun migrateExistingPassphraseToV3() {
        // 1. Migrate Local Device Passphrase to Keystore-wrapped v3_local
        try {
            val currentRawLocal = prefs.getString("db_passphrase", null)
            if (currentRawLocal != null && !currentRawLocal.startsWith("v3_local:")) {
                val oldPassphrase = currentRawLocal
                if (oldPassphrase.isNotEmpty()) {
                    val newLocalWrapped = encryptWithKeystore(oldPassphrase)
                    if (newLocalWrapped.isNotEmpty()) {
                        val verified = decryptWithKeystore(newLocalWrapped)
                        if (verified == oldPassphrase) {
                            prefs.edit().putString("db_passphrase", newLocalWrapped).commit()
                            android.util.Log.i("PreferenceManager", "Successfully migrated local db_passphrase to v3_local Keystore-wrapped format")
                        } else {
                            android.util.Log.e("PreferenceManager", "Verification failed for v3_local migration: decrypted value mismatch")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to migrate local db_passphrase to v3_local", e)
        }

        // 2. Migrate Backup/Recovery Passphrase to enhanced v3_backup
        try {
            val fallbackPrefs = context.getSharedPreferences("db_passphrase_fallback", Context.MODE_PRIVATE)
            val currentFallback = fallbackPrefs.getString("fallback_db_passphrase", null)
            if (currentFallback != null && !currentFallback.startsWith("v3_backup:")) {
                val (oldPassphrase, _) = decryptPassphrase(currentFallback)
                if (oldPassphrase.isNotEmpty()) {
                    val newBackupWrapped = encryptPassphraseV3Backup(oldPassphrase)
                    if (newBackupWrapped.isNotEmpty()) {
                        val verified = decryptPassphraseV3Backup(newBackupWrapped)
                        if (verified == oldPassphrase) {
                            fallbackPrefs.edit().putString("fallback_db_passphrase", newBackupWrapped).commit()
                            android.util.Log.i("PreferenceManager", "Successfully migrated fallback_db_passphrase to v3_backup format")
                        } else {
                            android.util.Log.e("PreferenceManager", "Verification failed for v3_backup migration: decrypted value mismatch")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to migrate fallback db_passphrase to v3_backup", e)
        }
    }

    private fun backupPassphraseToFallback(context: Context, passphrase: String) {
        try {
            val encrypted = encryptPassphraseV3Backup(passphrase)
            if (encrypted.isNotEmpty() && encrypted.startsWith("v3_backup:")) {
                val fallbackPrefs = context.getSharedPreferences("db_passphrase_fallback", Context.MODE_PRIVATE)
                fallbackPrefs.edit().putString("fallback_db_passphrase", encrypted).commit()
            }
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to backup passphrase to fallback prefs", e)
        }
    }

    internal fun getFallbackPassphrase(context: Context): String? {
        return try {
            val fallbackPrefs = context.getSharedPreferences("db_passphrase_fallback", Context.MODE_PRIVATE)
            val stored = fallbackPrefs.getString("fallback_db_passphrase", null)
            if (stored != null) {
                if (stored.startsWith("v3_backup:")) {
                    val pass = decryptPassphraseV3Backup(stored)
                    if (pass.isNotEmpty()) return pass
                }
                
                val (passphrase, isLegacy) = decryptPassphrase(stored)
                if (isLegacy && passphrase.isNotEmpty()) {
                    backupPassphraseToFallback(context, passphrase)
                }
                passphrase.ifEmpty { null }
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun salvageDatabasePassphrase(context: Context, originalException: Throwable): String? {
        val fallbackPass = getFallbackPassphrase(context)
        if (!fallbackPass.isNullOrEmpty()) {
            return fallbackPass
        }

        try {
            val oldPrefs = context.getSharedPreferences("reseller_prefs", Context.MODE_PRIVATE)
            val oldPass = oldPrefs.getString("db_passphrase", null)
            if (!oldPass.isNullOrEmpty()) {
                backupPassphraseToFallback(context, oldPass)
                return oldPass
            }
        } catch (e: Throwable) {
            android.util.Log.w("PreferenceManager", "Could not read legacy reseller_prefs during salvage", e)
        }

        // 3. Try reading from fallback reseller prefs ("reseller_prefs_fallback")
        try {
            val fbPrefs = context.getSharedPreferences("reseller_prefs_fallback", Context.MODE_PRIVATE)
            val fbPass = fbPrefs.getString("db_passphrase", null)
            if (!fbPass.isNullOrEmpty()) {
                backupPassphraseToFallback(context, fbPass)
                return fbPass
            }
        } catch (e: Throwable) {
            android.util.Log.w("PreferenceManager", "Could not read reseller_prefs_fallback during salvage", e)
        }

        // 4. Try reading raw unencrypted file if accessible
        try {
            val rawPrefs = context.getSharedPreferences("enc_reseller_prefs", Context.MODE_PRIVATE)
            val rawPass = rawPrefs.getString("db_passphrase", null)
            if (!rawPass.isNullOrEmpty()) {
                backupPassphraseToFallback(context, rawPass)
                return rawPass
            }
        } catch (rawEx: Throwable) {
            android.util.Log.w("PreferenceManager", "Could not read raw prefs during passphrase salvage", rawEx)
        }

        // 5. Extraction failed completely - record non-fatal to Crashlytics
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().recordException(
                IllegalStateException("Fatal: EncryptedSharedPreferences corrupted and db_passphrase could not be recovered before wiping preferences", originalException)
            )
        } catch (_: Throwable) {
            // Crashlytics might not be initialized yet
        }

        return null
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to create EncryptedSharedPreferences, resetting corrupted preferences", e)
            
            val salvagedPassphrase = salvageDatabasePassphrase(context, e)
            
            try {
                context.deleteSharedPreferences("enc_reseller_prefs")
                val newPrefs = buildEncryptedPrefs(context)
                if (salvagedPassphrase != null) {
                    try {
                        newPrefs.edit().putString("db_passphrase", salvagedPassphrase).commit()
                        backupPassphraseToFallback(context, salvagedPassphrase)
                        android.util.Log.i("PreferenceManager", "Successfully restored salvaged db_passphrase to rebuilt EncryptedSharedPreferences")
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                            try {
                                (context.applicationContext as? com.example.EarthlinkApp)?.auditRepository?.log(
                                    severity = com.example.core.model.AuditSeverity.SECURITY,
                                    action = "SEC_DATABASE_PASSPHRASE_SALVAGED",
                                    message = "Successfully restored salvaged db_passphrase to rebuilt EncryptedSharedPreferences"
                                )
                            } catch (e: Throwable) {}
                        }
                    } catch (restoreError: Throwable) {
                        android.util.Log.e("PreferenceManager", "Failed to write salvaged passphrase into new prefs", restoreError)
                    }
                }
                newPrefs
            } catch (retryException: Throwable) {
                android.util.Log.e("PreferenceManager", "Fatal: EncryptedSharedPreferences creation failed after cleanup", retryException)
                if (AppBuildConfig.DEBUG) {
                    // Resilience fallback for Robolectric / test environments
                    context.getSharedPreferences("reseller_prefs_fallback", Context.MODE_PRIVATE)
                } else {
                    throw RuntimeException("Fatal keystore failure: Could not initialize secure storage", retryException)
                }
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            "enc_reseller_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getDatabasePassphrase(): String {
        var pass: String? = null
        try {
            val stored = prefs.getString("db_passphrase", null)
            if (stored != null) {
                if (stored.startsWith("v3_local:")) {
                    pass = decryptWithKeystore(stored)
                } else {
                    // Stored in raw plaintext or old format. Trigger migration and retrieve.
                    migrateExistingPassphraseToV3()
                    val migrated = prefs.getString("db_passphrase", null)
                    if (migrated != null && migrated.startsWith("v3_local:")) {
                        pass = decryptWithKeystore(migrated)
                    } else if (!migrated.isNullOrEmpty()) {
                        pass = migrated
                    }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to read db_passphrase from prefs", e)
        }

        if (pass.isNullOrEmpty()) {
            val fallbackPass = getFallbackPassphrase(context)
            if (!fallbackPass.isNullOrEmpty()) {
                pass = fallbackPass
                try {
                    val wrappedLocal = encryptWithKeystore(pass)
                    prefs.edit().putString("db_passphrase", wrappedLocal).commit()
                } catch (e: Throwable) {
                    android.util.Log.e("PreferenceManager", "Failed to write fallback passphrase to prefs", e)
                }
            } else {
                val fallbackPrefs = context.getSharedPreferences("db_passphrase_fallback", Context.MODE_PRIVATE)
                val hasStoredFallback = fallbackPrefs.contains("fallback_db_passphrase")
                val dbFileExists = context.getDatabasePath("earthlink_reseller_db").exists()

                if (hasStoredFallback || dbFileExists) {
                    android.util.Log.e("PreferenceManager", "Existing database or fallback passphrase detected but decryption failed. Attempting emergency salvage.")
                    val salvaged = salvageDatabasePassphrase(context, IllegalStateException("Transient decryption failure in getDatabasePassphrase"))
                    if (!salvaged.isNullOrEmpty()) {
                        pass = salvaged
                        try {
                            val wrappedLocal = encryptWithKeystore(pass)
                            if (wrappedLocal.isNotEmpty()) {
                                prefs.edit().putString("db_passphrase", wrappedLocal).commit()
                            }
                        } catch (e: Throwable) {
                            android.util.Log.e("PreferenceManager", "Failed to save salvaged passphrase to prefs", e)
                        }
                        return pass
                    }
                    // CRITICAL FAIL-CLOSED (ROOT-04): If database file exists on disk or fallback was previously saved,
                    // generating a random key will result in opening the encrypted DB with the wrong key and destroying user data.
                    throw IllegalStateException("DATABASE_KEY_RECOVERY_REQUIRED: Existing database or stored credentials detected on device, but passphrase could not be unlocked. Halting key generation to prevent database corruption.")
                } else {
                    // Fresh clean install: generate new random 256-bit passphrase
                    val randomBytes = ByteArray(32)
                    java.security.SecureRandom().nextBytes(randomBytes)
                    pass = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
                    try {
                        val wrappedLocal = encryptWithKeystore(pass)
                        if (wrappedLocal.isNotEmpty()) {
                            prefs.edit().putString("db_passphrase", wrappedLocal).commit()
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("PreferenceManager", "Failed to save generated passphrase to prefs", e)
                    }
                    backupPassphraseToFallback(context, pass)
                }
            }
        } else {
            backupPassphraseToFallback(context, pass)
        }
        return pass
    }

    fun saveDatabasePassphrase(passphrase: String) {
        if (passphrase.isBlank()) {
            android.util.Log.w("PreferenceManager", "Ignoring attempt to save blank database passphrase")
            return
        }
        try {
            val wrappedLocal = encryptWithKeystore(passphrase)
            if (wrappedLocal.isNotEmpty()) {
                prefs.edit().putString("db_passphrase", wrappedLocal).commit()
            }
        } catch (e: Throwable) {
            android.util.Log.e("PreferenceManager", "Failed to save passphrase to encrypted prefs", e)
        }
        backupPassphraseToFallback(context, passphrase)
    }

    @Synchronized
    fun getDeviceId(): String {
        var deviceId = prefs.getString("installation_device_id", null)
        if (deviceId.isNullOrEmpty()) {
            deviceId = "device_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
            try {
                prefs.edit().putString("installation_device_id", deviceId).commit()
            } catch (e: Throwable) {
                android.util.Log.e("PreferenceManager", "Failed to save installation device ID", e)
            }
        }
        return deviceId
    }

    companion object {
        private const val KEY_TOKEN = "enc_ref_token"
        private const val KEY_USERNAME = "enc_ref_username"
        private const val KEY_PASSWORD = "enc_ref_password"
        private const val KEY_DEPOSIT_PASSWORD = "enc_deposit_password"
        private const val KEY_REMEMBER = "remember_login"
        private const val KEY_SYNC_ENABLED = "firebase_sync_enabled"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val KEY_DEMO_MODE = "demo_mode_enabled"
        private const val KEY_SHOW_ACTIVE = "show_active_users_dashboard"
        private const val KEY_SHOW_EXPIRED = "show_expired_users_dashboard"
        private const val KEY_MAX_DASHBOARD_ITEMS = "max_dashboard_subscribers"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_ISP_ADMIN_USERNAME = "isp_admin_username"
        private const val KEY_ISP_ADMIN_PASSWORD = "isp_admin_password"
        private const val KEY_EARTHLINK_API_TOKEN = "enc_earthlink_api_token"
        private const val KEY_LOCAL_BACKUP_ENABLED = "local_auto_backup_enabled"
        private const val KEY_LOCAL_LAST_BACKUP = "local_last_backup_time"
        private const val KEY_SETTINGS_LOCAL_MUTATED_AT = "user_settings_local_mutated_at"
        private const val KEY_SETTINGS_HAS_LOCAL_MUTATION = "user_settings_has_local_mutation"
        private const val KEY_SETTINGS_SYNCED_TIMESTAMP = "user_settings_last_synced_timestamp"
    }

    private val _isLoggedInFlow by lazy { MutableStateFlow(!getAuthToken().isNullOrEmpty()) }
    val isLoggedInFlow by lazy { _isLoggedInFlow.asStateFlow() }

    private val _demoModeFlow by lazy { MutableStateFlow(prefs.getBoolean(KEY_DEMO_MODE, false)) }
    val demoModeFlow by lazy { _demoModeFlow.asStateFlow() }

    private val _languageFlow by lazy { MutableStateFlow(prefs.getString(KEY_LANGUAGE, "ar") ?: "ar") }
    val languageFlow by lazy { _languageFlow.asStateFlow() }

    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, "ar") ?: "ar"
    }

    fun setLanguage(lang: String) {
        prefs.edit().putString(KEY_LANGUAGE, lang).apply()
        _languageFlow.value = lang
    }

    fun getShowActive(): Boolean {
        return prefs.getBoolean(KEY_SHOW_ACTIVE, true)
    }

    fun setShowActive(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ACTIVE, enabled).apply()
    }

    fun getShowExpired(): Boolean {
        return prefs.getBoolean(KEY_SHOW_EXPIRED, true)
    }

    fun setShowExpired(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_EXPIRED, enabled).apply()
    }

    fun getMaxDashboardItems(): Int {
        return prefs.getInt(KEY_MAX_DASHBOARD_ITEMS, 15)
    }

    fun setMaxDashboardItems(count: Int) {
        prefs.edit().putInt(KEY_MAX_DASHBOARD_ITEMS, count).apply()
    }

    fun setDemoMode(enabled: Boolean) {
        if (!AppBuildConfig.DEBUG) {
            prefs.edit().putBoolean(KEY_DEMO_MODE, false).apply()
            _demoModeFlow.value = false
            return
        }
        prefs.edit().putBoolean(KEY_DEMO_MODE, enabled).apply()
        _demoModeFlow.value = enabled
        if (enabled && getAuthToken().isNullOrEmpty()) {
            saveAuthToken("demo_access_token")
        }
    }

    fun getDemoMode(): Boolean {
        if (!AppBuildConfig.DEBUG) return false
        return prefs.getBoolean(KEY_DEMO_MODE, false) // Default to false so user gets live connections by default
    }

    fun saveAuthToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _isLoggedInFlow.value = true
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            try {
                (context.applicationContext as? com.example.EarthlinkApp)?.auditRepository?.log(
                    severity = com.example.core.model.AuditSeverity.SECURITY,
                    action = "SEC_AUTH_TOKEN_REFRESH",
                    message = "User session auth token updated"
                )
            } catch (e: Throwable) {}
        }
    }

    fun getAuthToken(): String? {
        return prefs.getString(KEY_TOKEN, null)
    }

    fun clearAuthToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
        _isLoggedInFlow.value = false
    }

    fun saveUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    fun getUsername(): String? {
        return prefs.getString(KEY_USERNAME, null)
    }

    fun clearUsername() {
        prefs.edit().remove(KEY_USERNAME).apply()
    }

    fun savePassword(password: String) {
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    fun getPassword(): String? {
        return prefs.getString(KEY_PASSWORD, null)
    }

    fun clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply()
    }

    fun saveDepositPassword(password: String, fromRemote: Boolean = false) {
        prefs.edit().putString(KEY_DEPOSIT_PASSWORD, password).apply()
        if (!fromRemote) recordSettingsLocalMutation()
    }

    fun getDepositPassword(): String {
        return prefs.getString(KEY_DEPOSIT_PASSWORD, null) ?: ""
    }

    fun clearDepositPassword() {
        prefs.edit().remove(KEY_DEPOSIT_PASSWORD).apply()
        recordSettingsLocalMutation()
    }

    fun saveIspAdminUsername(username: String, fromRemote: Boolean = false) {
        prefs.edit().putString(KEY_ISP_ADMIN_USERNAME, username).apply()
        if (!fromRemote) recordSettingsLocalMutation()
    }

    fun getIspAdminUsername(): String? {
        return prefs.getString(KEY_ISP_ADMIN_USERNAME, null)
    }

    fun saveIspAdminPassword(password: String, fromRemote: Boolean = false) {
        prefs.edit().putString(KEY_ISP_ADMIN_PASSWORD, password).apply()
        if (!fromRemote) recordSettingsLocalMutation()
    }

    fun getIspAdminPassword(): String? {
        return prefs.getString(KEY_ISP_ADMIN_PASSWORD, null)
    }

    fun getSettingsLocalMutatedAt(): Long {
        return prefs.getLong(KEY_SETTINGS_LOCAL_MUTATED_AT, 0L)
    }

    fun hasSettingsLocalMutation(): Boolean {
        return prefs.getBoolean(KEY_SETTINGS_HAS_LOCAL_MUTATION, false) || prefs.getLong(KEY_SETTINGS_LOCAL_MUTATED_AT, 0L) > 0L
    }

    fun recordSettingsLocalMutation() {
        prefs.edit()
            .putBoolean(KEY_SETTINGS_HAS_LOCAL_MUTATION, true)
            .putLong(KEY_SETTINGS_LOCAL_MUTATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearSettingsLocalMutation() {
        prefs.edit()
            .putBoolean(KEY_SETTINGS_HAS_LOCAL_MUTATION, false)
            .putLong(KEY_SETTINGS_LOCAL_MUTATED_AT, 0L)
            .apply()
    }

    fun getSettingsLastSyncedTimestamp(): Long {
        return prefs.getLong(KEY_SETTINGS_SYNCED_TIMESTAMP, 0L)
    }

    fun saveSettingsLastSyncedTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_SETTINGS_SYNCED_TIMESTAMP, timestamp).apply()
    }

    fun saveEarthlinkApiToken(token: String?) {
        if (token == null) {
            prefs.edit().remove(KEY_EARTHLINK_API_TOKEN).apply()
        } else {
            prefs.edit().putString(KEY_EARTHLINK_API_TOKEN, token).apply()
        }
    }

    fun getEarthlinkApiToken(): String? {
        return prefs.getString(KEY_EARTHLINK_API_TOKEN, null)
    }

    fun setRememberMe(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER, enabled).apply()
    }

    fun getRememberMe(): Boolean {
        return prefs.getBoolean(KEY_REMEMBER, false)
    }

    fun setSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    fun getSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_SYNC_ENABLED, true) // default enabled
    }

    fun saveLastSyncTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC, timestamp).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0L)
    }

    // --- LOCAL AUTO BACKUP SETTINGS ---
    private val _localBackupEnabledFlow by lazy { MutableStateFlow(getLocalBackupEnabled()) }
    val localBackupEnabledFlow by lazy { _localBackupEnabledFlow.asStateFlow() }

    private val _localLastBackupTimeFlow by lazy { MutableStateFlow(getLocalLastBackupTime()) }
    val localLastBackupTimeFlow by lazy { _localLastBackupTimeFlow.asStateFlow() }

    fun setLocalBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCAL_BACKUP_ENABLED, enabled).apply()
        _localBackupEnabledFlow.value = enabled
    }

    fun getLocalBackupEnabled(): Boolean {
        return prefs.getBoolean(KEY_LOCAL_BACKUP_ENABLED, false)
    }

    fun saveLocalLastBackupTime(timestamp: Long) {
        prefs.edit().putLong(KEY_LOCAL_LAST_BACKUP, timestamp).apply()
        _localLastBackupTimeFlow.value = timestamp
    }

    fun getLocalLastBackupTime(): Long {
        return prefs.getLong(KEY_LOCAL_LAST_BACKUP, 0L)
    }

    fun clearAll() {
        clearCredentials()
    }

    fun clearCredentials() {
        // Clear all operator authentication, deposit, and admin credentials, preserving local settings (language, database encryption passphrase, custom pricing).
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_DEPOSIT_PASSWORD)
            .remove(KEY_ISP_ADMIN_USERNAME)
            .remove(KEY_ISP_ADMIN_PASSWORD)
            .remove(KEY_EARTHLINK_API_TOKEN)
            .apply()
        _isLoggedInFlow.value = false
        _demoModeFlow.value = false
    }

    fun getPackageSellingPrice(packageName: String, defaultPrice: Double): Double {
        val key = "pkg_selling_price_${packageName.trim().lowercase()}"
        return prefs.getFloat(key, defaultPrice.toFloat()).toDouble()
    }

    fun setPackageSellingPrice(packageName: String, price: Double) {
        val key = "pkg_selling_price_${packageName.trim().lowercase()}"
        prefs.edit().putFloat(key, price.toFloat()).apply()
    }

    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
