package com.aiden.screentopdf.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keeps the user's Anthropic API key and chosen model on the device.
 * The key is stored in encrypted preferences. If encryption is not available
 * on the device, it falls back to normal private preferences and logs a warning.
 * Call [init] once from Application.onCreate before any other function.
 */
object ApiKeyStore {
    const val MODEL_SONNET = "claude-sonnet-5-5"
    const val MODEL_HAIKU = "claude-haiku-5-5"

    private const val TAG = "ApiKeyStore"
    private const val PREFS_NAME = "secure_prefs"
    private const val FALLBACK_PREFS_NAME = "plain_prefs"
    private const val KEY_API_KEY = "anthropic_api_key"
    private const val KEY_MODEL = "anthropic_model"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val appContext = context.applicationContext
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted preferences not available, using plain preferences", e)
            appContext.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /** The saved API key, or null when none is saved. */
    fun getKey(): String? = requirePrefs().getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    /** Saves the key. Pass null or a blank string to remove it. */
    fun setKey(key: String?) {
        val trimmed = key?.trim()
        requirePrefs().edit().apply {
            if (trimmed.isNullOrEmpty()) remove(KEY_API_KEY) else putString(KEY_API_KEY, trimmed)
        }.apply()
    }

    /** The chosen model id. Defaults to Sonnet 5.5. */
    fun getModel(): String = requirePrefs().getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: MODEL_SONNET

    fun setModel(id: String) {
        requirePrefs().edit().putString(KEY_MODEL, id.trim()).apply()
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("ApiKeyStore.init(context) has not been called")
}
