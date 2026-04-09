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
        "groupmind_api_key",
        "groupmind_rooms",
        "model",
        "system_prompt",
        "max_tokens",
        "rag_mode",
        "avatar_type"
    )

    // Old pref key -> new pref key, for seamless rename migration
    private val RENAMED_KEYS = mapOf(
        "antfarm_api_key" to "groupmind_api_key",
        "antfarm_rooms" to "groupmind_rooms",
        "clawhub_api_key" to "groupmind_api_key",
        "clawhub_rooms" to "groupmind_rooms"
    )

    @Volatile
    private var cachedWatchPrefs: SharedPreferences? = null

    fun watch(context: Context): SharedPreferences {
        cachedWatchPrefs?.let { return it }
        val appContext = context.applicationContext

        return synchronized(this) {
            cachedWatchPrefs?.let { return@synchronized it }

            val secure = try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "FATAL: EncryptedSharedPreferences initialization failed. Refusing to fall back to plaintext to protect secrets.", e)
                throw SecurityException("Secure storage initialization failed", e)
            }

            migrateLegacyIfPresent(appContext, secure)
            migrateRenamedKeys(secure)
            cachedWatchPrefs = secure
            secure
        }
    }

    private fun migrateLegacyIfPresent(context: Context, secure: SharedPreferences) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty()) return

        val editor = secure.edit()
        var migrated = false
        for ((key, value) in legacyAll) {
            if (value == null) continue
            // Map old key names to new ones, or keep if already a known key
            val targetKey = RENAMED_KEYS[key] ?: key.takeIf { MIGRATION_KEYS.contains(it) } ?: continue
            when (value) {
                is String -> editor.putString(targetKey, value)
                is Int -> editor.putInt(targetKey, value)
                is Long -> editor.putLong(targetKey, value)
                is Float -> editor.putFloat(targetKey, value)
                is Boolean -> editor.putBoolean(targetKey, value)
            }
            migrated = true
        }
        if (!migrated) return

        editor.apply()
        legacy.edit().clear().apply()
        Log.i(TAG, "Migrated legacy watch prefs to encrypted storage")
    }

    /** Migrate old antfarm/clawhub keys to groupmind within encrypted prefs. */
    private fun migrateRenamedKeys(secure: SharedPreferences) {
        val editor = secure.edit()
        var changed = false
        for ((oldKey, newKey) in RENAMED_KEYS) {
            val oldValue = secure.getString(oldKey, null)
            if (!oldValue.isNullOrBlank() && secure.getString(newKey, null).isNullOrBlank()) {
                editor.putString(newKey, oldValue)
                editor.remove(oldKey)
                changed = true
            }
        }
        if (changed) {
            editor.apply()
            Log.i(TAG, "Migrated renamed pref keys to groupmind")
        }
    }
}
