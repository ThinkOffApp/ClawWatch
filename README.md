# ClawWatch

NullClaw AI agent on Galaxy Watch 4 — fully offline voice in, voice back.

## Stack

| Component | What | Size |
|---|---|---|
| **NullClaw** | Agent runtime | 678 KB |
| **Vosk** | Offline STT (small-en-us) | ~40 MB |
| **Android TTS** | Voice output | 0 MB (pre-installed) |

## Setup

### 1. Build NullClaw for Android ARM64

```bash
# Install Zig 0.15.2
brew install zig

# Clone and build
git clone https://github.com/nullclaw/nullclaw
cd nullclaw
zig build -Dtarget=aarch64-linux-android -Doptimize=ReleaseSmall \
  --sysroot $ANDROID_NDK/toolchains/llvm/prebuilt/darwin-arm64/sysroot

cp zig-out/bin/nullclaw ../ClawWatch/app/src/main/assets/nullclaw
```

### 2. Configure NullClaw

Create `nullclaw.json` in `app/src/main/assets/`:

```json
{
  "provider": "openrouter",
  "api_key": "YOUR_OPENROUTER_KEY",
  "model": "google/gemini-flash-1.5",
  "max_tokens": 200
}
```

### 3. Download Vosk model

```bash
cd app/src/main/assets
curl -L https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip -o vosk.zip
unzip vosk.zip
mv vosk-model-small-en-us-0.15 vosk-model-small-en-us
rm vosk.zip
```

### 4. Build & deploy

```bash
./gradlew assembleDebug
adb connect <watch-ip>:<port>
adb install app/build/outputs/apk/debug/app-debug.apk
```

## UX Flow

```
IDLE → [tap] → LISTENING → [speech] → THINKING → SPEAKING → IDLE
              ↑ partial transcript shown during listening
```

Tap at any time to interrupt.
