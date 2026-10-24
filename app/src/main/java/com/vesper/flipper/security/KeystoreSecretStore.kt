package com.vesper.flipper.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed encryption for secrets at rest.
 *
 * Secrets are encrypted with an AES-256/GCM key that never leaves the Android
 * Keystore, then stored as a versioned, self-describing string:
 *
 * ```
 * v1:<base64(iv)>:<base64(ciphertext+tag)>
 * ```
 *
 * Every call is defensive: if the Keystore is unavailable (locked device, key
 * invalidated by a lock-screen change, corrupted entry, emulator without a
 * keystore daemon) the operation returns `null` instead of throwing. Callers
 * decide how to degrade — [com.vesper.flipper.data.SettingsStore] falls back to
 * plaintext storage so the app stays usable.
 *
 * The plaintext secret is never logged, and no exception message (which could
 * contain key material on some OEM keystores) is ever written to logcat.
 */
@Singleton
class KeystoreSecretStore @Inject constructor() {

    private val lock = Any()

    /**
     * Encrypt [plaintext] with the app's Keystore key.
     * @return the versioned ciphertext envelope, or null when encryption is unavailable.
     */
    fun encrypt(plaintext: String): String? {
        if (plaintext.isEmpty()) return null
        synchronized(lock) {
            return try {
                encryptOnce(plaintext)
            } catch (e: Exception) {
                // A stale/invalidated key is recoverable: drop it and retry once.
                Log.w(TAG, "Keystore encrypt failed (${e.javaClass.simpleName}); regenerating key")
                runCatching { deleteKey() }
                try {
                    encryptOnce(plaintext)
                } catch (retry: Exception) {
                    Log.w(TAG, "Keystore unavailable (${retry.javaClass.simpleName}); secret will not be encrypted at rest")
                    null
                }
            }
        }
    }

    /**
     * Decrypt a value produced by [encrypt].
     * @return the plaintext, or null when the envelope is malformed or the key is gone.
     */
    fun decrypt(envelope: String): String? {
        if (envelope.isBlank()) return null
        synchronized(lock) {
            return try {
                val parts = envelope.split(SEPARATOR, limit = 3)
                if (parts.size != 3 || parts[0] != FORMAT_VERSION) return null
                val iv = Base64.getDecoder().decode(parts[1])
                val ciphertext = Base64.getDecoder().decode(parts[2])
                if (iv.isEmpty() || ciphertext.size <= TAG_BITS / 8) return null

                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getKey(forceRegenerate = false) ?: return null,
                    GCMParameterSpec(TAG_BITS, iv)
                )
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Exception) {
                // Never surface the message: it may quote key material.
                Log.w(TAG, "Keystore decrypt failed (${e.javaClass.simpleName}); treating secret as unavailable")
                null
            }
        }
    }

    /** True when a Keystore-backed AES/GCM key can be created or loaded. */
    fun isAvailable(): Boolean = synchronized(lock) {
        try {
            getKey(forceRegenerate = false) != null
        } catch (e: Exception) {
            Log.w(TAG, "Keystore not available (${e.javaClass.simpleName})")
            false
        }
    }

    /** Remove the Keystore entry. Existing ciphertext becomes undecryptable afterwards. */
    fun deleteKey() {
        synchronized(lock) {
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }.onFailure { Log.w(TAG, "Keystore delete failed (${it.javaClass.simpleName})") }
        }
    }

    private fun encryptOnce(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(forceRegenerate = false) ?: throw IllegalStateException("keystore key unavailable"))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return buildString {
            append(FORMAT_VERSION)
            append(SEPARATOR)
            append(encoder.encodeToString(cipher.iv))
            append(SEPARATOR)
            append(encoder.encodeToString(ciphertext))
        }
    }

    private fun getKey(forceRegenerate: Boolean): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (forceRegenerate) {
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        } else {
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            existing?.secretKey?.let { return it }
        }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "KeystoreSecretStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vesper.openrouter.api_key.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_BITS = 128
        const val FORMAT_VERSION = "v1"
        const val SEPARATOR = ":"
    }
}
