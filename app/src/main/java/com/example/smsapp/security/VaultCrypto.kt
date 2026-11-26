package com.example.smsapp.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/** Encrypts private message bodies with a non-exportable Android Keystore AES key. */
object VaultCrypto {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "sms_private_messages_v1"
    // Looking the key up in the Keystore is slow, and it used to happen for every single message. The handle is safe to reuse.
    @Volatile private var cachedKey: SecretKey? = null
    private fun key(): SecretKey = cachedKey ?: synchronized(this) { cachedKey ?: loadKey().also { cachedKey = it } }
    private fun loadKey(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
    /**
     * Fast format ("vault:v1:s.<base64>"): messages are encrypted with a random data key held in memory. That data key is itself
     * stored encrypted by the Keystore key, so it is unlocked once per app start instead of one slow Keystore call per message.
     * ('.' never appears in Base64, so this cannot be confused with the older format, which is still readable.)
     */
    private const val PREFIX = "vault:v1:"
    private const val FAST = "vault:v1:s."
    @Volatile private var appContext: Context? = null
    @Volatile private var dataKey: SecretKey? = null
    private val random = SecureRandom()

    fun init(context: Context) { appContext = context.applicationContext }

    private fun fastKey(): SecretKey? = dataKey ?: synchronized(this) { dataKey ?: runCatching { loadDataKey() }.getOrNull()?.also { dataKey = it } }

    private fun loadDataKey(): SecretKey? {
        val prefs = appContext?.getSharedPreferences("vault_keys", Context.MODE_PRIVATE) ?: return null
        prefs.getString("wrapped_data_key", null)?.let { saved ->
            // A data key already exists: only ever unlock it. If that fails (Keystore busy, say) report "unavailable" and retry later;
            // never replace it, or every message encrypted with it would become unreadable.
            return runCatching {
                val packed = Base64.decode(saved, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
                SecretKeySpec(cipher.doFinal(packed.copyOfRange(12, packed.size)), "AES")
            }.getOrNull()
        }
        // First use: make a new data key and store it wrapped. commit() so it is on disk before any message uses it.
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val wrapped = cipher.iv + cipher.doFinal(raw)
        if (!prefs.edit().putString("wrapped_data_key", Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()) return null
        return SecretKeySpec(raw, "AES")
    }

    /** True when [value] is stored in the older, slow format (one Keystore call per message). */
    fun isLegacy(value: String) = value.startsWith(PREFIX) && !value.startsWith(FAST)

    fun encrypt(plain: String): String {
        if (plain.startsWith(PREFIX)) return plain
        val fast = fastKey()
        if (fast != null) {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, fast, GCMParameterSpec(128, iv))
            return FAST + Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }
    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val fastFormat = value.startsWith(FAST)
        val packed = Base64.decode(value.removePrefix(if (fastFormat) FAST else PREFIX), Base64.NO_WRAP)
        require(packed.size >= 12 + 16) { "Invalid encrypted message payload" }
        val iv = packed.copyOfRange(0, 12)
        val encrypted = packed.copyOfRange(12, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, if (fastFormat) requireNotNull(fastKey()) { "Private data key unavailable" } else key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
