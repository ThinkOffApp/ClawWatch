package com.thinkoff.clawwatch

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Provides encrypted preferences for watch config/secrets and migrates
 * legacy plaintext preferences if present.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val LEGACY_PREFS_NAME = "clawwatch_prefs"
    private const val SECURE_PREFS_NAME = "clawwatch_secure_prefs"

    private val MIGRATION_KEYS = setOf(
        "anthropic_api_key",
        "brave_api_key",
        "tavily_api_key",
        "model",
        "system_prompt",
        "max_tokens",
        "rag_mode",
        "avatar_type"
    )

    fun watch(context: Context): SharedPreferences {
        val secure = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plaintext", e)
            return context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        }

        migrateLegacyIfPresent(context, secure)
        return secure
    }

    private fun migrateLegacyIfPresent(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty()) return

        val editor = secure.edit()
        var migrated = false
        for ((key, value) in legacyAll) {
            if (!MIGRATION_KEYS.contains(key) || value == null) continue
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
            migrated = true
        }
        if (!migrated) return

        editor.apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "Migrated legacy watch prefs to encrypted storage")
    }
}
