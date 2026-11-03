package com.anderson.onedrivesync.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.anderson.onedrivesync.core.PREFS_FILE
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

interface TokenStorage {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear()
}

class TokenStore(context: Context) : TokenStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): Tokens? {
        val blob = prefs.getString(KEY_TOKENS, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            require(bytes.size > IV_BYTES) { "invalid token blob" }
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            json.decodeFromString<StoredTokens>(cipher.doFinal(ciphertext).decodeToString()).toTokens()
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear()
            null
        } catch (e: GeneralSecurityException) {
            clear()
            null
        } catch (e: IllegalArgumentException) {
            clear()
            null
        }
    }

    override fun save(tokens: Tokens) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(json.encodeToString(tokens.toStored()).encodeToByteArray())
            prefs.edit().putString(KEY_TOKENS, Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)).apply()
        } catch (e: GeneralSecurityException) {
            clear()
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKENS).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "odsync_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_TOKENS = "tokens"
    }
}

@Serializable
private data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

private fun StoredTokens.toTokens() = Tokens(accessToken, refreshToken, expiresAtMs)

private fun Tokens.toStored() = StoredTokens(accessToken, refreshToken, expiresAtMs)
