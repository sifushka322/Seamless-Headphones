package app.systemresponse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("trust", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("system-response", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("system-response", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun save(code: String) {
        val data = Base64.getDecoder().decode(code.trim()); require(data.size == 32) { "Ключ должен содержать 32 байта (44 символа Base64)" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(data)
        check(prefs.edit().putString("secret", Base64.getEncoder().encodeToString(encrypted)).putString("iv", Base64.getEncoder().encodeToString(cipher.iv)).commit())
    }
    fun load(): ByteArray? {
        val encrypted = prefs.getString("secret", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
            doFinal(Base64.getDecoder().decode(encrypted))
        }.also { require(it.size == 32) }
    }
    fun clear() { prefs.edit().clear().apply() }
}
