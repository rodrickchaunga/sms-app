package com.example.smsapp.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class VaultSecurity(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "vault_security",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    fun hasPin(): Boolean = prefs.contains("pin_hash")

    /** First-time setup only. Changing an existing PIN must go through [changePin]. */
    fun setPin(pin: String) {
        require(pin.length >= 6 && pin.all(Char::isDigit)) { "PIN must contain at least six digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("pin_salt", salt.joinToString("") { "%02x".format(it) })
            .putString("pin_hash", hash(pin, salt)).putInt("failures", 0).putInt("lock_rounds", 0).putLong("lock_until", 0L).apply()
    }

    /** Requires the current PIN, so an unlocked phone alone cannot take over the vault. */
    fun changePin(current: String, new: String): Boolean {
        if (!verifyPin(current)) return false
        setPin(new)
        return true
    }

    fun lockRemainingSeconds(): Long {
        val remaining = prefs.getLong("lock_until", 0L) - System.currentTimeMillis()
        return if (remaining > 0) (remaining + 999) / 1000 else 0L
    }

    fun verifyPin(pin: String): Boolean {
        if (System.currentTimeMillis() < prefs.getLong("lock_until", 0L)) return false
        val saltHex = prefs.getString("pin_salt", null) ?: return false
        val expected = prefs.getString("pin_hash", null) ?: return false
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val valid = MessageDigest.isEqual(expected.toByteArray(), hash(pin, salt).toByteArray())
        if (valid) prefs.edit().putInt("failures", 0).putInt("lock_rounds", 0).apply()
        else {
            val failures = prefs.getInt("failures", 0) + 1
            if (failures >= 5) {
                // 30 s, 60 s, 2 min ... capped at 1 hour, so guessing slows down instead of repeating every 30 s.
                val rounds = prefs.getInt("lock_rounds", 0) + 1
                val lockMillis = (30_000L * (1L shl (rounds - 1).coerceAtMost(7))).coerceAtMost(3_600_000L)
                prefs.edit().putInt("failures", 0).putInt("lock_rounds", rounds)
                    .putLong("lock_until", System.currentTimeMillis() + lockMillis).apply()
            } else prefs.edit().putInt("failures", failures).apply()
        }
        return valid
    }
    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
