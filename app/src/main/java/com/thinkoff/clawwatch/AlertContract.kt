package com.thinkoff.clawwatch

object AlertContract {
    const val ACTION_ALERT_OPEN = "com.thinkoff.clawwatch.ALERT_OPEN"

    const val EXTRA_ALERT_EVENT_ID = "alert_event_id"
    const val EXTRA_ALERT_TITLE = "alert_title"
    const val EXTRA_ALERT_BODY = "alert_body"
    const val EXTRA_ALERT_ROOM = "alert_room"
    const val EXTRA_ALERT_PROMPT = "alert_prompt"

    const val CHANNEL_URGENT_ALERTS = "clawwatch_urgent_alerts"
    const val PREF_LAST_FCM_TOKEN = "last_fcm_token"
    const val PREF_LAST_FCM_SYNC_AT = "last_fcm_sync_at"
    const val PREF_LAST_REGISTERED_FCM_TOKEN = "last_registered_fcm_token"
}
