#!/bin/bash
# Usage: ./set_key.sh sk-ant-your-key-here
# Pushes Anthropic API key to the watch via ADB (no shell interpolation)

KEY=$1
PKG="com.thinkoff.clawwatch"

if [ -z "$KEY" ]; then
  echo "Usage: ./set_key.sh sk-ant-..."
  exit 1
fi

# Validate key — only alphanumeric, dash, underscore allowed
if ! echo "$KEY" | grep -qE '^[a-zA-Z0-9_-]+$'; then
  echo "Error: invalid key format"
  exit 1
fi

# Write to temp file — never interpolate key into shell command
TMP=$(mktemp /tmp/clawwatch_prefs_XXXXXX.xml)
cat > "$TMP" << XMLEOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="anthropic_api_key">${KEY}</string>
</map>
XMLEOF

echo "Pushing API key to watch..."
adb push "$TMP" /sdcard/clawwatch_tmp.xml
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs'"
adb shell "run-as $PKG sh -c 'cp /sdcard/clawwatch_tmp.xml /data/data/$PKG/shared_prefs/clawwatch_prefs.xml'"
adb shell "rm /sdcard/clawwatch_tmp.xml"
rm "$TMP"

echo "Done. Restart ClawWatch on the watch."
