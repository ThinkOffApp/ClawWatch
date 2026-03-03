<p align="center">
  <img src="assets/logo/clawwatch-logo.jpeg" alt="ClawWatch logo" width="280">
</p>

# ClawWatch

**The first intelligent AI agent running natively on a smartwatch.**

Tap. Speak. Get an answer. No cloud STT, no phone dependency, no latency from middlemen.

ClawWatch runs [NullClaw](https://github.com/nullclaw/nullclaw) — a 2.8 MB static ARM binary — directly on a Galaxy Watch, paired with offline speech recognition (Vosk) and the built-in TTS engine. The agent talks to Claude Opus 4.6 (or any model you configure) and speaks the response back through the watch speaker.

**[Watch the first live demo on X](https://x.com/petruspennanen/status/2028503452788166751)**

## Screenshots

| Idle | Listening |
|---|---|
| ![ClawWatch idle state](assets/screenshots/watch-02.png) | ![ClawWatch listening state](assets/screenshots/watch-03.png) |

| Thinking | Speaking |
|---|---|
| ![ClawWatch thinking state](assets/screenshots/watch-04.png) | ![ClawWatch speaking state](assets/screenshots/watch-01.png) |

---

## How it works

```
[tap mic] → Vosk STT (on-device, offline) → NullClaw → Claude Opus 4.6 → Android TTS → [watch speaks]
```

Everything except the LLM call runs on the watch itself. No companion app needed once deployed.

## Stack

| Component | What | Size |
|---|---|---|
| [NullClaw](https://github.com/nullclaw/nullclaw) | Agent runtime (Zig, static binary) | 2.8 MB |
| [Vosk](https://alphacephei.com/vosk/) | Offline speech-to-text | ~68 MB |
| Android TextToSpeech | Voice output | 0 MB (pre-installed) |
| Claude Opus 4.6 | Intelligence | cloud |

**Total on-device footprint: ~71 MB**

## Requirements

- Samsung Galaxy Watch 4 or newer (Wear OS 3+)
- Android phone for initial ADB setup
- Anthropic API key (or any provider NullClaw supports)
- Mac/Linux for building

## Build

### 1. Install tools

```bash
brew install zig   # must be 0.15.2
```

### 2. Build NullClaw for ARM Android

```bash
git clone https://github.com/nullclaw/nullclaw
cd nullclaw
zig build -Dtarget=arm-linux-musleabihf -Doptimize=ReleaseSmall
cp zig-out/bin/nullclaw ../ClawWatch/app/src/main/assets/nullclaw
```

### 3. Configure

```bash
cp app/src/main/assets/nullclaw.json.example app/src/main/assets/nullclaw.json
# Edit nullclaw.json — set your provider and model
# Default: Anthropic + claude-opus-4-6
# The API key is NOT stored in this file — it's pushed via ADB (see Deploy)
```

### 4. Download Vosk model

```bash
cd app/src/main/assets
curl -L https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip -o vosk.zip
unzip vosk.zip && mv vosk-model-small-en-us-0.15 vosk-model-small-en-us && rm vosk.zip
```

### 5. Build APK

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew assembleDebug
```

## Deploy

### Enable ADB on the watch

1. Settings → About watch → Software → tap **Software version 5×** → Developer options unlocked
2. Settings → Developer options → **ADB debugging ON** + **Wireless debugging ON**
3. Note the IP and port shown on screen

### Install

```bash
adb connect <watch-ip>:<port>
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Set API key (from your Mac — no typing on watch)

```bash
./set_key.sh sk-ant-your-key-here
```

That's it. Open ClawWatch on the watch.

## Usage

| Action | Result |
|---|---|
| **Tap mic button** | Start listening |
| **Speak** | Partial transcript shown while speaking |
| **Stop speaking** | NullClaw + LLM processes query |
| **Tap again** | Interrupt at any point |
| **Swipe left on avatar** | Switch to next avatar |
| **Swipe right on avatar** | Close app |

The watch speaks the response aloud via the built-in speaker.

## Watch UI Spec (V1)

- Layout after splash: `avatar` + one adaptive button below it.
- State model: `idle`, `listening`, `thinking`, `searching`, `speaking`, `error`.
- Adaptive button behavior:
  - `idle` -> tap starts listening
  - `listening` -> tap stops listening
  - `thinking/searching/speaking` -> tap cancels current flow
  - `error` -> tap retries (starts listening)
- Auto-listen: after speaking finishes, mic opens automatically for a short follow-up window; if no speech is detected, it returns to idle.
- Low battery rule: when battery is below 20%, avatar turns greyer and avatar animations stop.
- Avatar options: `ant`, `lobster`, `robot`, `boy`, `girl`.
- Conversation memory: rolling recent turns are included in each query for follow-ups.
- Screen policy: screen stays awake during active conversation states (`listening/thinking/searching/speaking`) and returns to normal sleep in idle.
- Action bubble: compact flat bubble (no dark halo/ring overlap).

## Admin Panel

A local web UI for configuring the watch agent from your Mac — no ADB commands needed after initial install.

```bash
cd admin
npm install
node server.js
# Open http://localhost:4747
```

The admin panel lets you:
- **Watch target connect** — set `IP:PORT` and connect from the UI
- **Watch status** — live ADB connection indicator (green dot when connected)
- **API key** — push directly to the watch with one click
- **Tavily key** — recommended live web RAG key (free tier)
- **Brave key** — alternative web search key
- **Model** — switch between providers and models (claude-opus-4-6, gpt-4o, gemini, etc.)
- **Avatar selector** — choose `lobster/ant/robot/boy/girl` and push to watch
- **Max tokens** — slider with live value
- **RAG mode** — `off`, `auto-search`, or `opus tool use`
- **System prompt** — edit the agent's personality and instructions
- **Push all settings** — merges and pushes settings to watch in one click
- **Capture logs** — download watch logcat snapshot for crash/debug review
- **Rebuild & reinstall** — triggers `./gradlew assembleDebug && adb install` from the browser

The admin panel talks to the watch via ADB. Make sure `adb connect <watch-ip>:<port>` is active before pushing.

## Configuration

Edit `nullclaw.json` to change model or provider:

```json
{
  "provider": "anthropic",
  "model": "claude-opus-4-6",
  "max_tokens": 150,
  "system": "Your system prompt here"
}
```

NullClaw supports 22+ providers including OpenRouter, OpenAI, Gemini, Groq, Mistral, Ollama, and any OpenAI-compatible endpoint. See [NullClaw docs](https://github.com/nullclaw/nullclaw) for the full list.

## Why NullClaw?

| | OpenClaw | NullClaw |
|---|---|---|
| RAM | >1 GB | ~1 MB |
| Startup | 500+ s | <8 ms |
| Binary | ~28 MB | **2.8 MB** |
| Language | TypeScript | Zig |

A watch has 1.5–2 GB RAM. NullClaw uses 1 MB of it. OpenClaw would need the entire device.

## License

AGPL-3.0 — see [LICENSE](LICENSE)

Built by [ThinkOff](https://thinkoff.io) · Powered by [NullClaw](https://github.com/nullclaw/nullclaw) · Logo by [herrpunk](https://github.com/herrpunk)
