package com.thinkoff.clawwatch.phone

import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.thinkoff.clawwatch.R
import kotlinx.coroutines.launch

/**
 * Phone companion app for ClawWatch.
 *
 * Lets you configure:
 *  - Anthropic API key
 *  - Brave Search API key (optional RAG)
 *  - Model selection
 *  - System prompt
 *  - Max tokens
 *  - RAG mode (off / kotlin / opus_tool)
 *
 * Syncs everything to the watch via Wearable Data Layer.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var syncManager: SyncManager
    private lateinit var prefs: android.content.SharedPreferences

    // Views — inflated from activity_main layout
    private lateinit var apiKeyInput: EditText
    private lateinit var braveKeyInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var systemPromptInput: EditText
    private lateinit var maxTokensSeekBar: SeekBar
    private lateinit var maxTokensLabel: TextView
    private lateinit var ragGroup: RadioGroup
    private lateinit var syncButton: Button
    private lateinit var statusText: TextView

    private val models = listOf(
        "claude-opus-4-6",
        "claude-sonnet-4-5",
        "claude-haiku-4-5-20251001",
        "gpt-4o",
        "gpt-4o-mini",
        "gemini-2.0-flash",
        "gemini-1.5-pro"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_phone)

        syncManager = SyncManager(this)
        prefs = SecurePrefs.phone(this)

        bindViews()
        loadSavedPrefs()

        syncButton.setOnClickListener { onSync() }
    }

    private fun bindViews() {
        apiKeyInput      = findViewById(R.id.apiKeyInput)
        braveKeyInput    = findViewById(R.id.braveKeyInput)
        modelSpinner     = findViewById(R.id.modelSpinner)
        systemPromptInput = findViewById(R.id.systemPromptInput)
        maxTokensSeekBar = findViewById(R.id.maxTokensSeekBar)
        maxTokensLabel   = findViewById(R.id.maxTokensLabel)
        ragGroup         = findViewById(R.id.ragGroup)
        syncButton       = findViewById(R.id.syncButton)
        statusText       = findViewById(R.id.statusText)

        // Model spinner
        modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)

        // Max tokens seekbar
        maxTokensSeekBar.max = 90  // steps of 5: 50..500
        maxTokensSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                maxTokensLabel.text = "${50 + p * 5}"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun loadSavedPrefs() {
        apiKeyInput.setText(prefs.getString("anthropic_api_key", "") ?: "")
        braveKeyInput.setText(prefs.getString("brave_api_key", "") ?: "")
        val savedModel = prefs.getString("model", "claude-opus-4-6") ?: "claude-opus-4-6"
        modelSpinner.setSelection(models.indexOf(savedModel).coerceAtLeast(0))
        systemPromptInput.setText(prefs.getString("system_prompt",
            "You are ClawWatch, a smart and relaxed voice presence on a watch. " +
            "Respond in 1-3 short sentences. No markdown. Use plain spoken language. " +
            "Be natural, helpful, and a little playful when it fits.") ?: "")
        val tokens = prefs.getInt("max_tokens", 150)
        maxTokensSeekBar.progress = ((tokens - 50) / 5).coerceIn(0, 90)
        maxTokensLabel.text = "$tokens"

        when (prefs.getString("rag_mode", "kotlin")) {
            "off"       -> ragGroup.check(R.id.ragOff)
            "kotlin"    -> ragGroup.check(R.id.ragKotlin)
            "opus_tool" -> ragGroup.check(R.id.ragOpusTool)
        }
    }

    private fun onSync() {
        val anthropicKey = apiKeyInput.text.toString().trim()
        val braveKey     = braveKeyInput.text.toString().trim().ifBlank { null }
        val model        = models[modelSpinner.selectedItemPosition]
        val systemPrompt = systemPromptInput.text.toString().trim()
        val maxTokens    = 50 + maxTokensSeekBar.progress * 5
        val ragMode      = when (ragGroup.checkedRadioButtonId) {
            R.id.ragKotlin    -> "kotlin"
            R.id.ragOpusTool  -> "opus_tool"
            else              -> "off"
        }

        if (anthropicKey.length < 20) {
            statusText.text = "API key too short"
            return
        }

        // Save locally
        prefs.edit()
            .putString("anthropic_api_key", anthropicKey)
            .putString("brave_api_key", braveKey ?: "")
            .putString("model", model)
            .putString("system_prompt", systemPrompt)
            .putInt("max_tokens", maxTokens)
            .putString("rag_mode", ragMode)
            .apply()

        syncButton.isEnabled = false
        statusText.text = "Syncing to watch…"

        lifecycleScope.launch {
            try {
                syncManager.pushAll(anthropicKey, braveKey, model, systemPrompt, maxTokens, ragMode)
                statusText.text = "✓ Synced to watch"
            } catch (e: Exception) {
                statusText.text = "Sync failed: ${e.message}"
            } finally {
                syncButton.isEnabled = true
            }
        }
    }
}
