#!/usr/bin/env bash
# Runs the release APK on an emulator: yt-dlp must start, a shared link must
# open, play and download, and the app must not crash along the way.
set -u
APK=app/build/outputs/apk/release/app-x86_64-release.apk
PKG=app.kultr.dl
OUT=smoke
# A YouTube video, and yt-dlp's own Bandcamp test track in case YouTube turns this CI machine away.
LINKS=("https://www.youtube.com/watch?v=jNQXAC9IVRw" "https://youtube-dl.bandcamp.com/track/youtube-dl-test-song")
mkdir -p "$OUT"
failures=()
warnings=()

adb logcat -c
adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &

log_has() { grep -q -E "$1" "$OUT/logcat.txt"; }
app_log() { grep -E "/KultrDL *\(|FATAL EXCEPTION|E/AndroidRuntime" "$OUT/logcat.txt" | grep -v uiautomator; }
screenshot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || return 1
}
# What the screen says, one text per line.
screen_text() { dump_ui && grep -o 'text="[^"]\+"' "$OUT/ui.xml" | sed 's/^text="//; s/"$//' ; }
# Tap the middle of the first on-screen node whose text is exactly $1.
tap_text() {
  dump_ui || return 1
  local nums
  nums=$(grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" "$OUT/ui.xml" | head -1 \
    | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $nums
  [ $# -ge 4 ] || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
crashed() { log_has "FATAL EXCEPTION|ANR in $PKG"; }

echo "== Launch"
adb shell am start -W -n "$PKG/.MainActivity"
for _ in $(seq 1 120); do
  log_has "yt-dlp runs:|yt-dlp failed to start|yt-dlp doesn't run" && break
  sleep 2
done
screenshot 1-home
if log_has "yt-dlp runs:"; then echo "yt-dlp starts and runs"; else failures+=("yt-dlp did not start"); fi

# Share $1 to the app and wait for its Play button (or an error on screen).
open_link() {
  adb shell am start -n "$PKG/.MainActivity" -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "$1" >/dev/null
  for _ in $(seq 1 50); do
    sleep 3
    if tap_text "Play"; then return 0; fi
    if grep -q "Couldn.t open that link" "$OUT/ui.xml" 2>/dev/null; then return 1; fi
  done
  return 1
}

played_from=""
for link in "${LINKS[@]}"; do
  echo "== Share $link"
  if open_link "$link"; then
    echo "Opened; pressed Play"
    playing=0
    for _ in $(seq 1 30); do
      if log_has "KultrDL *\(.*Playing "; then playing=1; break; fi
      sleep 2
    done
    screenshot "play-$(basename "$link")"
    if [ "$playing" = 1 ]; then
      echo "Playing"
      played_from="$link"
      break
    fi
    echo "Playback did not start. On screen:"; screen_text | head -40
  else
    echo "The link did not open. On screen:"; screen_text | head -40
  fi
done

if [ -z "$played_from" ]; then
  failures+=("nothing played")
else
  [ "$played_from" = "${LINKS[0]}" ] || warnings+=("YouTube didn't play on this CI machine; Bandcamp did")
  echo "== Download"
  if tap_text "Download" || { sleep 2; tap_text "Download"; }; then
    saved=0
    for _ in $(seq 1 90); do
      if adb shell ls /sdcard/Music/KultrDL/ 2>/dev/null | grep -q -i "\.mp3"; then saved=1; break; fi
      if log_has "Download of .* failed"; then break; fi
      sleep 3
    done
    adb shell ls -l /sdcard/Music/KultrDL/ 2>/dev/null || true
    if [ "$saved" = 1 ]; then echo "Downloaded"; else failures+=("the download did not finish"); echo "On screen:"; screen_text | head -40; fi
  else
    failures+=("no Download button"); echo "On screen:"; screen_text | head -40
  fi
fi
screenshot 9-end

if crashed; then failures+=("the app crashed"); fi
if log_has "confirm you.re not a bot|Sign in to confirm"; then
  warnings+=("YouTube asked this CI machine to sign in")
fi

echo "== App log"
app_log | head -200
if crashed; then echo "== Crash"; grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80; fi

for w in "${warnings[@]}"; do echo "::warning::$w"; done
if [ ${#failures[@]} -gt 0 ]; then
  for f in "${failures[@]}"; do echo "::error::$f"; done
  exit 1
fi
echo "Smoke test passed"
