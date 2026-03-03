---
title: "ClawWatch: Running a Real AI Agent Natively on a Samsung Galaxy Watch"
published: true
tags: android, wearos, ai, kotlin, zig
cover_image: ""
description: "How we built the first AI agent running natively on a Samsung Galaxy Watch — Vosk offline STT, NullClaw (2.8MB Zig binary), and Claude Opus 4.6 — in one day, and the five walls we had to punch through to get there."
---

I built an AI agent that runs natively on a Samsung Galaxy Watch. Not mirrored from a phone. Not relayed through a companion app. On the watch itself. Tap, speak, get an answer, hear it spoken back. The whole pipeline — speech recognition, agent runtime, API call, TTS — happens with the watch on your wrist, no phone involved.

It's called **ClawWatch** and it's open source: [github.com/ThinkOffApp/ClawWatch](https://github.com/ThinkOffApp/ClawWatch)

Here's how it works, and more importantly, here's everything that went wrong while building it.

---

## The Stack

```
[tap mic] → Vosk STT (on-device, offline) → NullClaw → Claude Opus 4.6 → Android TTS → [watch speaks]
```

| Component | Role | On-device size |
|---|---|---|
| [NullClaw](https://github.com/nullclaw/nullclaw) | Agent runtime (Zig static binary) | 2.8 MB |
| [Vosk](https://alphacephei.com/vosk/) | Offline speech-to-text | ~68 MB (small model) |
| Android TextToSpeech | Voice output | 0 MB (pre-installed) |
| Claude Opus 4.6 | Intelligence | cloud |

NullClaw is an AI agent runtime written in Zig. It starts in under 8ms, uses ~1MB of RAM, supports 22+ LLM providers, and compiles to a static binary with no runtime dependencies. On a server, you'd run it as a daemon. On a watch, you invoke it per query. Same binary, different use case.

The Vosk model means speech recognition never leaves the device. No round-trip to Google, no cloud STT latency. The response is spoken back through the watch's built-in speaker using Android's `TextToSpeech` — which is already installed on every Wear OS device.

Total on-device runtime footprint for the agent: **2.8 MB**.

---

## Obstacle 1: Samsung Wear OS Is 32-Bit

The Galaxy Watch 4 has an Exynos W920 — a 64-bit ARM chip. Despite that, Samsung ships Wear OS as **32-bit (armeabi-v7a)**. The OS itself is 32-bit. Your app's native libraries must be 32-bit. The ABI is `armeabi-v7a`, full stop.

I built NullClaw for `aarch64-linux-musl` first, which is the sensible Android 64-bit target:

```bash
zig build -Dtarget=aarch64-linux-musl -Doptimize=ReleaseSmall
```

It installed fine. It crashed on launch with `Illegal instruction`.

After some `adb logcat` archaeology, the issue was clear: the binary was 64-bit and Wear OS wouldn't touch it. Switching to the 32-bit target:

```bash
zig build -Dtarget=arm-linux-musleabihf -Doptimize=ReleaseSmall
```

...caused a different failure. NullClaw's internal queue (`qq.zig`) used `std.atomic.Value(i64)` — a 64-bit atomic. On a 32-bit target, the ARM instruction set doesn't have a native 64-bit atomic CAS. Zig's stdlib will refuse to compile this for 32-bit targets.

The fix was a `portable_atomic.zig` module that detects at comptime whether `T` fits in the native word size, and falls back to a mutex-protected wrapper if it doesn't:

```zig
// portable_atomic.zig
pub fn Atomic(comptime T: type) type {
    if (@bitSizeOf(T) <= @bitSizeOf(usize)) {
        return std.atomic.Value(T);  // zero-cost passthrough
    }
    return MutexAtomic(T);           // mutex-guarded fallback
}

fn MutexAtomic(comptime T: type) type {
    return struct {
        raw: T,
        _mutex: std.Thread.Mutex = .{},

        pub fn load(self: *const Self, comptime _: std.builtin.AtomicOrder) T {
            const m = &@constCast(self)._mutex;
            m.lock();
            defer m.unlock();
            return self.raw;
        }
        // ... store, fetchAdd, swap
    };
}
```

On 64-bit hosts (servers, most Android phones) this compiles away to nothing — `@bitSizeOf(i64) <= @bitSizeOf(usize)` is true. On 32-bit ARM, `usize` is 32 bits, so `i64` gets the mutex wrapper. Same API, no performance penalty on normal targets.

---

## Obstacle 2: Android's W^X Policy Means Your Binary Won't Execute

Android enforces W^X (Write XOR Execute): a memory page cannot be both writable and executable. More practically: **files in `filesDir` are not executable**.

The obvious approach — copy a binary from assets to `context.filesDir` and `chmod +x` it — simply doesn't work on modern Android. You'll get `Permission denied` when you try to execute it, regardless of file permissions.

The correct approach is to package the binary as a `.so` file inside `jniLibs/armeabi-v7a/`. Android's package manager extracts these to a directory that is marked executable. So:

```
app/src/main/jniLibs/armeabi-v7a/libnullclaw.so   ← the NullClaw binary, renamed
```

Then in Kotlin:

```kotlin
private val nativeLibDir get() = context.applicationInfo.nativeLibraryDir
private val binaryFile get() = File(nativeLibDir, "libnullclaw.so")
```

`nativeLibraryDir` is the system-managed path where `.so` files are extracted — it's on an executable-capable filesystem partition. The binary runs from there without any `chmod`.

---

## Obstacle 3: `extractNativeLibs` Defaults to False

Related but distinct from obstacle 2: modern APKs default to `extractNativeLibs="false"`, which means native libraries stay zipped inside the APK and are memory-mapped directly. This is great for disk space, terrible for our use case — we need the file to actually exist on disk so we can `exec()` it as a process.

The fix is one line in `AndroidManifest.xml`:

```xml
<application
    android:extractNativeLibs="true"
    ...>
```

Without this, the `libnullclaw.so` path exists at runtime but points to a file that either doesn't exist or can't be executed. With it, the APK install extracts the binary to `nativeLibraryDir` as a real file.

---

## Obstacle 4: Subprocess Network Isolation

This one cost the most time.

The plan was: Kotlin spawns NullClaw as a subprocess, NullClaw handles the Anthropic API call (it already knows how to do this — it's an agent runtime), Kotlin reads the response from stdout.

It worked perfectly on a regular Android phone. On the watch, every API call failed with a connection error. No network access from within the spawned process.

Samsung Wear OS applies network isolation to child processes spawned from app processes. The subprocess runs in a network namespace that has no external connectivity. The parent process (the Android app) has full network access. The child does not.

The solution: don't use NullClaw for the API call on Wear OS. Instead, call the Anthropic API directly from Kotlin using `HttpURLConnection` — which operates in the parent process's network context and works fine:

```kotlin
suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("model", "claude-opus-4-6")
        put("max_tokens", 150)
        put("system", SYSTEM_PROMPT)
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }.toString()

    val url = URL("https://api.anthropic.com/v1/messages")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("x-api-key", apiKey)
    conn.setRequestProperty("anthropic-version", "2023-06-01")
    conn.doOutput = true

    OutputStreamWriter(conn.outputStream).use { it.write(body) }

    val text = JSONObject(conn.inputStream.bufferedReader().readText())
        .getJSONArray("content")
        .getJSONObject(0)
        .getString("text")
        .trim()

    Result.success(text)
}
```

NullClaw is still running on the watch — it manages agent state, context, memory, the system prompt configuration. But the raw HTTP call goes through Kotlin. For a voice assistant use case this is fine; for a long-running daemon agent you'd need a different architecture.

---

## Obstacle 5: NullClaw Config Format and HOME Setup

NullClaw expects to find its config at `~/.nullclaw/config.json`. On Android, there's no conventional `HOME` directory — but we can set the `HOME` environment variable when spawning the process to point to a known directory.

The config format also changed: it's not `"provider"/"model"` as flat fields, but a nested structure with `"agents.defaults.model.primary"` using `"provider/model"` format:

```json
{
  "agents": {
    "defaults": {
      "model": {
        "primary": "anthropic/claude-opus-4-6"
      }
    }
  },
  "providers": {
    "anthropic": {
      "api_key": "sk-ant-..."
    }
  }
}
```

We write this config dynamically at startup from Kotlin, injecting the API key that was pushed via ADB:

```kotlin
private fun writeNullclawHomeConfig() {
    val apiKey = getApiKey() ?: return
    nullclawConfigFile.parentFile?.mkdirs()
    nullclawConfigFile.writeText("""
{
  "agents": {
    "defaults": {
      "model": { "primary": "anthropic/claude-opus-4-6" }
    }
  },
  "providers": {
    "anthropic": { "api_key": "$apiKey" }
  }
}
""".trimIndent())
}
```

Where `nullclawConfigFile` is `File(context.filesDir.parentFile!!, ".nullclaw/config.json")` — placing the `.nullclaw` directory at the app's data root, which we set as `HOME`.

---

## Obstacle 6: API Key Delivery With No Keyboard

A Galaxy Watch 6 screen is 1.4 inches. There is no keyboard. An Anthropic API key is 60+ characters. These facts are in tension.

The solution is a shell script that pushes the key directly from your development machine via ADB:

```bash
#!/bin/bash
# set_key.sh
KEY=$1
PKG="com.thinkoff.clawwatch"

# Write to temp file — never interpolate key into shell command
TMP=$(mktemp /tmp/clawwatch_prefs_XXXXXX.xml)
cat > "$TMP" << XMLEOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="anthropic_api_key">${KEY}</string>
</map>
XMLEOF

adb push "$TMP" /sdcard/clawwatch_tmp.xml
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs'"
adb shell "run-as $PKG sh -c 'cp /sdcard/clawwatch_tmp.xml /data/data/$PKG/shared_prefs/clawwatch_prefs.xml'"
adb shell "rm /sdcard/clawwatch_tmp.xml"
rm "$TMP"
```

One command from your Mac: `./set_key.sh sk-ant-your-key`. The watch reads it from SharedPreferences on next launch. No typing required.

---

## The Voice Pipeline

The full flow in Kotlin is driven by a simple state machine:

```
SETUP → IDLE → LISTENING → THINKING → SPEAKING → IDLE
```

Vosk runs offline STT via the `vosk-android` library. The small English model is about 40MB compressed, unzipped to `filesDir` at first launch. Partial results show on screen while you're speaking; the final result triggers the API call.

TTS uses Android's built-in `TextToSpeech` engine — no dependency, no model download, always available. One important implementation detail: use `UtteranceProgressListener.onDone()` to detect when speech finishes, not a heuristic timer:

```kotlin
fun speak(text: String, onDone: () -> Unit = {}) {
    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onDone(utteranceId: String?) {
            if (utteranceId == TTS_UTTERANCE_ID) onDone()
        }
        override fun onStart(utteranceId: String?) {}
        override fun onError(utteranceId: String?) { onDone() }
    })
    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
}
```

The system prompt tells Claude it's running on a smartwatch and should respond in 1-3 short sentences, no markdown, no lists — just spoken language. This matters more than you'd think. LLMs love bullet points. Watches do not.

---

## The Result

It works. Tap the mic, ask a question, hear the answer in about 3 seconds (mostly network RTT to Claude). The whole thing runs on a device with a 1.5 GB RAM, an ARM chip, and a screen smaller than a credit card.

Total lines of Kotlin: about 400. Total lines of modified Zig: a focused fix in the atomic layer. Built in one day.

The code is AGPL-3.0 at [github.com/ThinkOffApp/ClawWatch](https://github.com/ThinkOffApp/ClawWatch). PRs welcome — there's a lot of room to improve: streaming responses, persistent agent context across queries, wake word detection, tool use.

If you want to run it yourself, you'll need a Galaxy Watch 4 or newer, ADB wireless debugging enabled, and an Anthropic API key. The README has the full build and deploy instructions.

---

*Built by [ThinkOff](https://thinkoff.io) — using [NullClaw](https://github.com/nullclaw/nullclaw), the Zig agent runtime that runs anywhere.*
