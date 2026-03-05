package com.thinkoff.clawwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.thinkoff.clawwatch.WAKE") {
            val prompt = intent.getStringExtra("prompt") ?: return
            val room = intent.getStringExtra("room")
            Log.i("ClawWatch", "WAKE Broadcast received! Waking MainActivity. Prompt: $prompt, Room: $room")
            
            // Re-broadcast the intent as an explicit Activity start so Wear OS brings the app forward
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                action = "com.thinkoff.clawwatch.WAKE"
                putExtra("prompt", prompt)
                putExtra("room", room)
            }
            context.startActivity(launchIntent)
        }
    }
}
