package com.gemmabridge

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Stores and validates API keys backed by [EncryptedSharedPreferences].
 *
 * Each key is `gma_live_<24 random bytes, url-safe base64>`. The store keeps the full key
 * (we need to validate it on incoming requests; we are local-only and the file is already
 * encrypted-at-rest by the framework).
 */
class KeyManager(context: Context) {

    @Serializable
    data class KeyRecord(
        val key: String,
        val name: String,
        val createdAt: Long,
    )

    @Serializable
    private data class KeyStore(val keys: List<KeyRecord> = emptyList())

    private val prefs: SharedPreferences = run {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<KeyRecord> = read().keys

    fun isValid(key: String): Boolean = read().keys.any { it.key == key }

    @OptIn(ExperimentalEncodingApi::class)
    fun create(name: String = "default"): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val tail = Base64.UrlSafe.encode(bytes).trimEnd('=')
        val key = "gma_live_$tail"
        val store = read()
        val updated = store.copy(
            keys = store.keys + KeyRecord(key, name, System.currentTimeMillis()),
        )
        write(updated)
        return key
    }

    fun revoke(prefix: String): Int {
        val store = read()
        val (drop, keep) = store.keys.partition { it.key.startsWith(prefix) }
        write(store.copy(keys = keep))
        return drop.size
    }

    private fun read(): KeyStore {
        val raw = prefs.getString(STORE_KEY, null) ?: return KeyStore()
        return runCatching { json.decodeFromString<KeyStore>(raw) }.getOrDefault(KeyStore())
    }

    private fun write(store: KeyStore) {
        prefs.edit().putString(STORE_KEY, json.encodeToString(store)).apply()
    }

    companion object {
        private const val PREFS_NAME = "gemma_bridge_keys"
        private const val STORE_KEY = "store"
    }
}
