#!/bin/bash
# Usage: ./set_key.sh sk-ant-your-key-here
# Pushes Anthropic API key to the watch via ADB

KEY=$1
PKG="com.thinkoff.clawwatch"

if [ -z "$KEY" ]; then
  echo "Usage: ./set_key.sh sk-ant-..."
  exit 1
fi

echo "Setting API key on watch..."
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs && echo \"<map><string name=\\\"anthropic_api_key\\\">$KEY</string></map>\" > /data/data/$PKG/shared_prefs/clawwatch_prefs.xml'"
echo "Done. Restart ClawWatch on the watch."
