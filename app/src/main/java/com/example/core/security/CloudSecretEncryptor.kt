package com.example.core.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CloudSecretEncryptor {

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH_BYTES = 12
    private const val STATIC_SALT_V2 = "Earthlink_Reseller_Cloud_Secret_Salt_v2"
    private const val STATIC_SALT_LEGACY = "Earthlink_Reseller_Cloud_Secret_Salt_v1"
    private const val PBKDF2_ITERATIONS = 10000
    private const val KEY_LENGTH_BITS = 256

    private fun deriveKey(userUid: String): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val salt = (userUid + STATIC_SALT_V2).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(userUid.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    private fun deriveKeyLegacy(userUid: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest((userUid + STATIC_SALT_LEGACY).toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptSecret(plainText: String?, userUid: String): String? {
        if (plainText.isNullOrBlank() || userUid.isBlank()) return null
        return try {
            val key = deriveKey(userUid)
            val iv = ByteArray(IV_LENGTH_BYTES)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

            val encryptedData = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encryptedData.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedData, 0, combined, iv.size, encryptedData.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            android.util.Log.e("CloudSecretEncryptor", "Error encrypting secret for cloud sync", e)
            null
        }
    }

    fun decryptSecret(encryptedBase64: String?, userUid: String): String? {
        if (encryptedBase64.isNullOrBlank() || userUid.isBlank()) return null
        val combined = try {
            Base64.decode(encryptedBase64, Base64.NO_WRAP)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null } ?: return null

        if (combined.size <= IV_LENGTH_BYTES) return null

        val iv = ByteArray(IV_LENGTH_BYTES)
        val encryptedData = ByteArray(combined.size - IV_LENGTH_BYTES)
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES)
        System.arraycopy(combined, IV_LENGTH_BYTES, encryptedData, 0, encryptedData.size)

        // Try primary PBKDF2 key derivation first
        try {
            val key = deriveKey(userUid)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)

            val decryptedData = cipher.doFinal(encryptedData)
            return String(decryptedData, Charsets.UTF_8)
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e;
            // Fallback to legacy SHA-256 key derivation for backwards compatibility
            try {
                val legacyKey = deriveKeyLegacy(userUid)
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, legacyKey, parameterSpec)

                val decryptedData = cipher.doFinal(encryptedData)
                return String(decryptedData, Charsets.UTF_8)
            } catch (fallbackEx: Exception) { if (fallbackEx is kotlinx.coroutines.CancellationException) throw fallbackEx;
                android.util.Log.e("CloudSecretEncryptor", "Error decrypting secret from cloud sync with PBKDF2 and legacy keys", fallbackEx)
                return null
            }
        }
    }
}
