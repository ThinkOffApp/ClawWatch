package com.thinkoff.clawwatch

import android.content.Context

/**
 * Single source of truth for the watch wearer's preferred nickname.
 *
 * Stored under SecurePrefs key `user_nickname`. Returns null when the
 * user hasn't set one yet — callers (voice flow on first run, chat
 * UI when rendering the user's own messages) should branch on null
 * to either prompt or fall back to a generic placeholder.
 *
 * Wire-up sketch (handed off because the voice/onboarding path is
 * still being worked through):
 *
 *   On first ClawWatch wake (e.g. ClawRunner.startConversation):
 *     val name = UserNickname.get(context)
 *     if (name == null) {
 *         tts.speak("What should I call you?")
 *         val heard = stt.listenOnce()
 *         val cleaned = UserNickname.normalize(heard)
 *         if (cleaned != null) {
 *             UserNickname.set(context, cleaned)
 *             tts.speak("Got it, $cleaned. Nice to meet you.")
 *         }
 *     }
 *
 *   In subsequent replies:
 *     val name = UserNickname.get(context) ?: "there"
 *     tts.speak("Hi $name, ...")
 */
object UserNickname {
    private const val PREF_KEY = "user_nickname"
    private const val MAX_LEN = 40

    fun get(context: Context): String? {
        val raw = SecurePrefs.watch(context).getString(PREF_KEY, null) ?: return null
        return raw.trim().takeIf { it.isNotEmpty() }
    }

    fun set(context: Context, nickname: String): Boolean {
        val cleaned = normalize(nickname) ?: return false
        SecurePrefs.watch(context).edit()
            .putString(PREF_KEY, cleaned)
            .apply()
        return true
    }

    fun clear(context: Context) {
        SecurePrefs.watch(context).edit().remove(PREF_KEY).apply()
    }

    /** True if the user has set a nickname. */
    fun isConfigured(context: Context): Boolean = get(context) != null

    /**
     * Sanitize a free-form nickname captured from voice/text input.
     * Returns null if nothing usable remains (empty, only punctuation,
     * looks like a phrase like "I don't know", etc.).
     *
     * Strips trailing punctuation, collapses whitespace, caps length
     * at 40 chars, and rejects clearly-non-name responses.
     */
    fun normalize(input: String?): String? {
        if (input.isNullOrBlank()) return null
        var s = input.trim()
        // Strip wrapping quotes the STT or user might have added.
        s = s.trim('"', '\'', '“', '”', '‘', '’')
        // Collapse interior whitespace.
        s = s.replace(Regex("\\s+"), " ")
        // Drop trailing punctuation.
        s = s.trimEnd('.', ',', '!', '?', ';', ':')
        if (s.isEmpty()) return null
        if (s.length > MAX_LEN) s = s.take(MAX_LEN)
        // Reject obvious non-answers from STT.
        val low = s.lowercase()
        val rejects = listOf(
            "i don't know", "i do not know", "no", "not sure",
            "uhh", "um", "what", "huh", "skip", "cancel",
        )
        if (low in rejects) return null
        return s
    }
}
