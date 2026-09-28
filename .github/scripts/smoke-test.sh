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
# Tap the first on-screen node whose attribute $1 (content-desc, or resource-id for a Compose test tag) is exactly $2.
tap_attr() {
  dump_ui || return 1
  local nums
  nums=$(grep -o "$1=\"$2\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" "$OUT/ui.xml" | head -1 \
    | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $nums
  [ $# -ge 4 ] || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
# Close the on-screen keyboard if it is up (Back would leave the page if it isn't).
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q -E "mInputShown=true|mIsInputViewShown=true|isInputViewShown=true"; then
    adb shell input keyevent 4
    sleep 2
  fi
}
# Scroll the page down, well above where the keyboard would be.
swipe_up() { hide_keyboard; adb shell input swipe $((W / 2)) $((H * 55 / 100)) $((W / 2)) $((H * 20 / 100)) 400; sleep 1; }
# Run a tap command, scrolling the page down until it finds its target.
tap_scrolling() { for _ in 1 2 3 4 5 6; do "$@" && return 0; swipe_up; done; return 1; }
type_into() { tap_scrolling tap_attr resource-id "$1" || return 1; sleep 1; adb shell input text "$2"; sleep 1; hide_keyboard; }
# Open a tab of the floating bar. While a search is open the bar shows a back button
# instead of the tabs, so go back (and bring the app forward again) until they show.
go_tab() {
  for _ in 1 2 3 4; do
    tap_attr content-desc "$1" && { sleep 2; return 0; }
    adb shell input keyevent 4
    sleep 2
    adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 2
  done
  return 1
}
wait_screen() { for _ in $(seq 1 "$2"); do screen_text | grep -q -E "$1" && return 0; sleep 2; done; return 1; }

echo "== Launch"
adb shell am start -W -n "$PKG/.MainActivity"
for _ in $(seq 1 120); do
  log_has "yt-dlp runs:|yt-dlp failed to start|yt-dlp doesn't run" && break
  sleep 2
done
screenshot 1-home
if log_has "yt-dlp runs:"; then echo "yt-dlp starts and runs"; else failures+=("yt-dlp did not start"); fi
# The app updates yt-dlp soon after it starts; test with the version people will have.
for _ in $(seq 1 45); do log_has "yt-dlp is now|yt-dlp update failed" && break; sleep 2; done

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
# Servers: an SFTP server (OpenSSH in Docker) and an FTP server (pyftpdlib) run on this
# machine, which the emulator reaches as 10.0.2.2. Both are added through the app's own
# screens; a downloaded track is sent to the SFTP one, and a new download goes straight to FTP.
# Run one step of a flow; when it fails, say which and print what the screen holds.
step() {
  local what=$1
  shift
  echo "  - $what"
  "$@" && return 0
  echo "    FAILED: $what. Windows:"
  diag_windows
  echo "    On screen (ids, texts, descriptions):"
  dump_ui && grep -o 'resource-id="[^"]*"\|text="[^"]*"\|content-desc="[^"]*"' "$OUT/ui.xml" | grep -v '=""' | head -60 | sed 's/^/      /'
  return 1
}
# Tap the last text field on screen (in a dialog, its own field).
tap_last_field() {
  dump_ui || return 1
  local nums
  nums=$(grep -o 'class="android.widget.EditText"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/ui.xml" | tail -1 \
    | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  set -- $nums
  [ $# -ge 4 ] || return 1
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# Which window has focus, and whether uiautomator can see dialog windows (for diagnosis).
diag_windows() {
  adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedWindow" | head -3 | sed 's/^/      /'
  adb shell uiautomator dump --windows /sdcard/uiw.xml 2>&1 | head -2 | sed 's/^/      dump --windows: /'
  adb shell cat /sdcard/uiw.xml 2>/dev/null | grep -o 'text="[^"]*"' | head -12 | sed 's/^/      /'
}
dialog_open() { adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q -v "MainActivity"; }
# The folder dialog's field takes focus when it opens, and Done confirms it.
add_folder() {
  tap_scrolling tap_attr resource-id folder-type || return 1
  sleep 2
  echo "    after tapping Type a path:"
  diag_windows
  adb shell input text "$1"
  sleep 1
  adb shell input keyevent 66
  sleep 2
  hide_keyboard
  wait_screen "^$1\$" 3
}
add_server() { # name, protocol, port, folder
  step "open Settings" go_tab "Settings" || return 1
  step "open Servers" tap_scrolling tap_text "Servers" || return 1
  sleep 2
  step "tap Add server" tap_text "Add server" || return 1
  sleep 2
  step "type the name" type_into server-name "$1" || return 1
  if [ "$2" != "SFTP" ]; then step "choose $2" tap_text "$2" || return 1; fi
  step "type the host" type_into server-host "10.0.2.2" || return 1
  step "type the port" type_into server-port "$3" || return 1
  step "type the username" type_into server-user "kultr" || return 1
  step "type the password" type_into server-password "kultr-pass" || return 1
  step "add the folder $4" add_folder "$4" || return 1
  step "tap Test connection" tap_scrolling tap_attr resource-id server-test || return 1
  for _ in $(seq 1 30); do log_has "Server test: .*10.0.2.2:$3|Server test: (CERT|KEY)" && break; sleep 2; done
  grep "Server test:" "$OUT/logcat.txt" | tail -3
  screenshot "server-$1"
  step "sign in" log_has "Server test: signed in to $2 10.0.2.2:$3" || return 1
  echo "    after the test:"
  diag_windows
  step "close the result" eval 'tap_text "OK" || { dialog_open && adb shell input keyevent 4; }' || return 1
  sleep 1
  step "tap Save" tap_attr resource-id server-save
}

if [ -n "$played_from" ]; then
  echo "== SFTP server"
  if add_server CI-SFTP SFTP 2222 music; then
    sleep 2
    go_tab "Downloads"
    sleep 1
    if tap_scrolling tap_attr content-desc "Send to server" && sleep 2 && tap_text "Send"; then
      for _ in $(seq 1 40); do log_has "Sent .* to SFTP 10.0.2.2" && break; log_has "Download of .* failed" && break; sleep 2; done
      screenshot sftp-sent
      if log_has "Sent .* to SFTP 10.0.2.2"; then echo "Sent to SFTP"; else failures+=("sending to SFTP did not finish"); screen_text | head -40; fi
      docker exec sftp ls -lR /home/kultr/music || true
      docker exec sftp sh -c 'ls /home/kultr/music | grep -q .' || failures+=("no file on the SFTP server")
    else
      failures+=("couldn't send a download to the SFTP server"); screen_text | head -40
    fi
  else
    failures+=("couldn't add the SFTP server"); screenshot sftp-add-failed; screen_text | head -40
  fi

  echo "== FTP server"
  if add_server CI-FTP FTP 2121 music; then
    sleep 2
    go_tab "Settings"
    if tap_scrolling tap_text "Save to" && sleep 2 && tap_text "CI-FTP" && tap_text "Save"; then
      if open_link "${LINKS[1]}" && sleep 2 && { tap_text "Download" || { sleep 2; tap_text "Download"; }; }; then
        for _ in $(seq 1 90); do log_has "Sent .* to FTP 10.0.2.2" && break; log_has "Download of .* failed" && break; sleep 3; done
        screenshot ftp-sent
        if log_has "Sent .* to FTP 10.0.2.2"; then echo "Downloaded to FTP"; else failures+=("the download to FTP did not finish"); screen_text | head -40; fi
        ls -lR /tmp/ftp || true
        find /tmp/ftp -name '*.mp3' | grep -q . || failures+=("no file on the FTP server")
      else
        failures+=("couldn't start a download to FTP"); screen_text | head -40
      fi
    else
      failures+=("couldn't choose the FTP server for downloads"); screen_text | head -40
    fi
  else
    failures+=("couldn't add the FTP server"); screenshot ftp-add-failed; screen_text | head -40
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
