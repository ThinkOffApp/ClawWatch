#!/bin/bash
# Usage: ./set_avatar.sh lobster|orange_lobster|ant|robot|boy|girl

AVATAR=$1
PKG="com.thinkoff.clawwatch"

if [ -z "$AVATAR" ]; then
  echo "Usage: ./set_avatar.sh lobster|orange_lobster|ant|robot|boy|girl"
  exit 1
fi

if ! echo "$AVATAR" | grep -qE '^(lobster|orange_lobster|ant|robot|boy|girl)$'; then
  echo "Error: invalid avatar"
  exit 1
fi

TMP=$(mktemp /tmp/clawwatch_avatar_XXXXXX.xml)
cat > "$TMP" << XMLEOF
<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="avatar_type">${AVATAR}</string>
</map>
XMLEOF

echo "Pushing avatar prefs to watch..."
adb push "$TMP" /data/local/tmp/clawwatch_avatar_tmp.xml
adb shell "run-as $PKG sh -c 'mkdir -p /data/data/$PKG/shared_prefs'"
adb shell "run-as $PKG sh -c 'cp /data/local/tmp/clawwatch_avatar_tmp.xml /data/data/$PKG/shared_prefs/clawwatch_prefs.xml'"
adb shell "rm /data/local/tmp/clawwatch_avatar_tmp.xml"
rm "$TMP"

echo "Done. Restart ClawWatch on the watch."
