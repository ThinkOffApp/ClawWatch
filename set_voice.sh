#!/bin/bash
# Usage:
#   ./set_voice.sh en-GB
#   ./set_voice.sh en-US com.google.android.tts
#   ./set_voice.sh en-GB com.samsung.SMT ""
#
# Pushes TTS voice preferences to the watch via ADB.

LOCALE_TAG=$1
ENGINE_PACKAGE=$2
VOICE_NAME=$3
PKG="com.thinkoff.clawwatch"

if [ -z "$LOCALE_TAG" ]; then
  echo "Usage: ./set_voice.sh <locale-tag> [engine-package] [voice-name]"
  echo "Example: ./set_voice.sh en-GB"
  exit 1
fi

if ! echo "$LOCALE_TAG" | grep -qE '^[a-zA-Z]{2,3}(-[a-zA-Z0-9_]+)?$'; then
  echo "Error: invalid locale tag"
  exit 1
fi

if [ -n "$ENGINE_PACKAGE" ] && ! echo "$ENGINE_PACKAGE" | grep -qE '^[a-zA-Z0-9._]+$'; then
  echo "Error: invalid engine package"
  exit 1
fi

if [ -n "$VOICE_NAME" ] && ! echo "$VOICE_NAME" | grep -qE '^[a-zA-Z0-9._:-]+$'; then
  echo "Error: invalid voice name"
  exit 1
fi

TMP=$(mktemp /tmp/clawwatch_voice_XXXXXX.xml)
cat > "$TMP" << XMLEOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="tts_locale">${LOCALE_TAG}</string>
XMLEOF

if [ -n "$ENGINE_PACKAGE" ]; then
cat >> "$TMP" << XMLEOF
    <string name="tts_engine_package">${ENGINE_PACKAGE}</string>
XMLEOF
fi

if [ -n "$VOICE_NAME" ]; then
cat >> "$TMP" << XMLEOF
    <string name="tts_voice_name">${VOICE_NAME}</string>
XMLEOF
fi

cat >> "$TMP" << XMLEOF
</map>
XMLEOF

echo "Pushing TTS voice prefs to watch..."
adb push "$TMP" /data/local/tmp/clawwatch_voice_tmp.xml
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs'"
adb shell "run-as $PKG sh -c 'cp /data/local/tmp/clawwatch_voice_tmp.xml /data/data/$PKG/shared_prefs/clawwatch_prefs.xml'"
adb shell "rm /data/local/tmp/clawwatch_voice_tmp.xml"
rm "$TMP"

echo "Done. Restart ClawWatch on the watch."
