package com.example.planetxt

import android.util.Base64
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.subtle.AesGcmJce
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Simple helper to derive a per-passenger AES-GCM key from (lastName ∥ bookingRef)
 * using PBKDF2-HmacSHA256 then wrap it in Tink’s [AesGcmJce] implementation.
 *
 * Salt is the booking reference bytes; 100k iterations gives ~100 ms on device.
 */
object CryptoUtil {
    init { AeadConfig.register() }

    private const val ITERATIONS = 100_000
    private const val KEY_BYTES = 32 // 256-bit AES

    fun deriveAead(lastName: String, bookingRef: String): AesGcmJce {
        val secret = "$lastName|$bookingRef"
        val spec = PBEKeySpec(secret.toCharArray(), bookingRef.toByteArray(), ITERATIONS, KEY_BYTES * 8)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = skf.generateSecret(spec).encoded
        return AesGcmJce(keyBytes)
    }

    fun encryptMessage(plain: String, lastName: String, bookingRef: String): String {
        val aead = deriveAead(lastName, bookingRef)
        val ct = aead.encrypt(plain.toByteArray(), null)
        return Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decryptMessage(cipherB64: String, lastName: String, bookingRef: String): String? {
        return try {
            val aead = deriveAead(lastName, bookingRef)
            val ct = Base64.decode(cipherB64, Base64.NO_WRAP)
            val pt = aead.decrypt(ct, null)
            String(pt)
        } catch (e: Exception) {
            null
        }
    }
}
