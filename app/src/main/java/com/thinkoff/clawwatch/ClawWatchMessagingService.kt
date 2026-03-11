package com.thinkoff.clawwatch

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class ClawWatchMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "ClawWatchPush"
        private const val PREF_REQUIRE_URGENT_TAG = "require_urgent_tag"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        SecurePrefs.watch(this).edit().putString(AlertContract.PREF_LAST_FCM_TOKEN, token).apply()
        Log.i(TAG, "FCM token updated (${token.take(16)}...)")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val title = data["title"]
            ?: remoteMessage.notification?.title
            ?: "ClawWatch urgent alert"
        val body = data["body"]
            ?: data["message"]
            ?: remoteMessage.notification?.body
            ?: "Open ClawWatch for details."
        val room = data["room"]
        val prompt = data["prompt"]
        val eventId = data["event_id"]
            ?: remoteMessage.messageId
            ?: "evt-${System.currentTimeMillis()}"

        if (!shouldAlert(data, title, body)) {
            Log.i(TAG, "Push ignored: missing URGENT tag. eventId=$eventId")
            return
        }

        UrgentAlertNotifier.show(
            context = this,
            eventId = eventId,
            title = title,
            body = body,
            room = room,
            prompt = prompt
        )
        Log.i(TAG, "Urgent alert posted. eventId=$eventId room=${room ?: "-"}")
    }

    private fun shouldAlert(data: Map<String, String>, title: String, body: String): Boolean {
        val prefs = SecurePrefs.watch(this)
        val requireTag = prefs.getBoolean(PREF_REQUIRE_URGENT_TAG, true)
        if (!requireTag) {
            return hasUrgentTag(data) || hasUrgentKeyword(title, body)
        }
        return hasUrgentTag(data)
    }

    private fun hasUrgentTag(data: Map<String, String>): Boolean {
        val raw = listOf(
            data["tag"],
            data["tags"],
            data["alert_tag"],
            data["alert_tags"]
        ).filterNotNull().joinToString(",")

        return raw
            .split(',', ';', '|', ' ')
            .map { it.trim().removePrefix("#") }
            .any { it.equals("URGENT", ignoreCase = true) }
    }

    private fun hasUrgentKeyword(title: String, body: String): Boolean {
        val source = "$title $body"
        return Regex("""(?i)\b#?urgent\b""").containsMatchIn(source)
    }
}
