#!/usr/bin/env bash
# Runs the release APK on an emulator: yt-dlp must start, a shared link must
# open, play and download, and the app must not crash along the way.
set -u
APK=app/build/outputs/apk/release/app-x86_64-release.apk
PKG=app.kultr.dl
OUT=smoke
LINK="https://www.youtube.com/watch?v=jNQXAC9IVRw"
mkdir -p "$OUT"
failures=()
warnings=()

adb logcat -c
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &

log_has() { grep -q -E "$1" "$OUT/logcat.txt"; }
screenshot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
# Tap the middle of the first on-screen node whose text is exactly $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || return 1
  local nums
  nums=$(grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" "$OUT/ui.xml" | head -1 \
    | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $nums
  [ $# -ge 4 ] || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
crashed() { log_has "FATAL EXCEPTION|Process: $PKG|ANR in $PKG"; }

echo "== Launch"
adb shell am start -W -n "$PKG/.MainActivity"
for _ in $(seq 1 120); do
  log_has "yt-dlp runs:|yt-dlp failed to start|yt-dlp doesn't run" && break
  sleep 2
done
screenshot 1-home
if log_has "yt-dlp runs:"; then echo "yt-dlp starts and runs"; else failures+=("yt-dlp did not start"); fi

echo "== Share a link"
adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$LINK" >/dev/null
opened=0
for _ in $(seq 1 60); do
  if tap_text "Play"; then opened=1; break; fi
  sleep 3
done
screenshot 2-link
if [ "$opened" = 1 ]; then
  echo "The link opened; pressed Play"
  playing=0
  for _ in $(seq 1 30); do
    if adb shell dumpsys media_session | grep -q "state=PlaybackState {state=3"; then playing=1; break; fi
    sleep 2
  done
  screenshot 3-playing
  if [ "$playing" = 1 ]; then echo "Playing"; else failures+=("playback did not start"); fi

  echo "== Download"
  if tap_text "Download"; then
    saved=0
    for _ in $(seq 1 90); do
      if adb shell ls /sdcard/Music/KultrDL/ 2>/dev/null | grep -q -i "\.mp3"; then saved=1; break; fi
      sleep 3
    done
    adb shell ls -l /sdcard/Music/KultrDL/ 2>/dev/null || true
    if [ "$saved" = 1 ]; then echo "Downloaded"; else failures+=("the download did not finish"); fi
  else
    failures+=("no Download button")
  fi
else
  failures+=("the shared link did not open")
fi
screenshot 4-end

if crashed; then failures+=("the app crashed"); fi
if log_has "confirm you.re not a bot|Sign in to confirm"; then
  warnings+=("YouTube asked this CI machine to sign in; playback and download results reflect that")
fi

echo "== KultrDL log"
grep -E "KultrDL|AndroidRuntime|FATAL" "$OUT/logcat.txt" | head -150

for w in "${warnings[@]}"; do echo "::warning::$w"; done
if [ ${#failures[@]} -gt 0 ]; then
  for f in "${failures[@]}"; do echo "::error::$f"; done
  exit 1
fi
echo "Smoke test passed"
