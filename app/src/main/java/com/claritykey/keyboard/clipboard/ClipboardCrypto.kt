package com.claritykey.keyboard.clipboard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local encryption helper using Android Keystore (AES-256-GCM) for stored clipboard items.
 * New writes require Keystore encryption. Legacy raw1 records remain readable for migration.
 */
object ClipboardCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "breeno_ru_clipboard_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val ENC_PREFIX = "enc1:"
    private const val PLAIN_PREFIX = "raw1:"

    fun sha256Exact(rawText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawText.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun encrypt(plainText: String): String {
        val key = getOrCreateKey() ?: throw IllegalStateException("Хранилище ключей недоступно")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = byteArrayOf(iv.size.toByte()) + iv + encrypted
        return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? {
        return try {
            when {
                stored.startsWith(ENC_PREFIX) -> {
                    val key = getOrCreateKey() ?: return null
                    val combined = Base64.decode(stored.substring(ENC_PREFIX.length), Base64.NO_WRAP)
                    if (combined.size < 2) return null
                    val ivLen = combined[0].toInt() and 0xFF
                    if (combined.size <= 1 + ivLen) return null
                    val iv = combined.copyOfRange(1, 1 + ivLen)
                    val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                    String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
                }
                stored.startsWith(PLAIN_PREFIX) -> {
                    String(Base64.decode(stored.substring(PLAIN_PREFIX.length), Base64.NO_WRAP), Charsets.UTF_8)
                }
                else -> stored
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                gen.init(spec)
                gen.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isKeystoreReady(): Boolean = getOrCreateKey() != null
}
