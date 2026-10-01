package com.chaitanya.hingeopener

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** API key lives in EncryptedSharedPreferences; everything else is plain prefs. */
class Prefs(context: Context) {
    private val app = context.applicationContext

    private val plain: SharedPreferences =
        app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = secure.getString(KEY_API, "").orEmpty()
        set(value) = secure.edit { putString(KEY_API, value.trim()) }

    var runnerUps: Int
        get() = plain.getInt(KEY_RUNNER_UPS, 2)
        set(value) = plain.edit { putInt(KEY_RUNNER_UPS, value.coerceIn(0, MAX_RUNNER_UPS)) }

    var style: String
        get() = plain.getString(KEY_STYLE, null) ?: Prompts.DEFAULT_STYLE
        set(value) = plain.edit { putString(KEY_STYLE, value) }

    var hingePackage: String
        get() = plain.getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_HINGE_PACKAGE
        set(value) = plain.edit { putString(KEY_PACKAGE, value.trim()) }

    companion object {
        const val DEFAULT_HINGE_PACKAGE = "co.hinge.app"
        const val MAX_RUNNER_UPS = 4
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_RUNNER_UPS = "runner_ups"
        private const val KEY_STYLE = "my_style"
        private const val KEY_PACKAGE = "hinge_package"
    }
}
