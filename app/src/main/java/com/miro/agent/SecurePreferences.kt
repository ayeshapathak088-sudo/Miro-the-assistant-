package com.miro.agent

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePreferences(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "agent_secure_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value).apply()
    var endpoint: String
        get() = prefs.getString(KEY_ENDPOINT, "https://api.openai.com/v1/chat/completions").orEmpty()
        set(value) = prefs.edit().putString(KEY_ENDPOINT, value).apply()
    var model: String
        get() = prefs.getString(KEY_MODEL, "gpt-4o-mini").orEmpty()
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
    }
}
