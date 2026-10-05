<p align="center"><img src=".github/kultrdl-logo.png" width="128" alt="KultrDL"></p>

# KultrDL

Search, play, save, favourite and download music on Android — from YouTube
Music, YouTube, SoundCloud and Bandcamp, and by link or search from Spotify,
Apple Music, Deezer, Tidal, Qobuz and Amazon Music. It has the look of
[Kultr for Android](https://github.com/evropiani/Kultr_Android): liquid glass
tab bar and mini player floating over the page, the round search button that
grows into the search field, and an interface tinted by the artwork of what is
playing.

## Features

- **Search** YouTube Music, YouTube, Spotify, Apple Music, Deezer, SoundCloud or
  Bandcamp from the search field in the floating bar. Albums open with their
  full track lists.
- **Links from anywhere.** Paste or share a track, album or playlist link from
  Spotify, Apple Music, Tidal, Qobuz, Deezer, Amazon Music, YouTube, YouTube
  Music, SoundCloud, Bandcamp — or any of the other sites yt-dlp understands.
- **Play** in the background with a media notification, lock-screen and headset
  controls, a queue, shuffle and repeat. Downloaded tracks play offline.
- **Karousel.** Tap shuffle twice: when the queue runs out, music like what
  was playing keeps coming — a station started from the song playing, songs by
  similar artists and the same ones, and your own music, which also keeps it
  going offline. Its songs show under their own heading in Up next and leave
  the queue when you turn it off. Blocked artists never come up.
- **Library:** favourites (the heart), saved tracks, your own playlists (make
  them, or save any album or playlist from a source as one), downloads and
  listening history. Back it up to a file and restore it.
- **Downloads in the format you choose:**

  | Format | Qualities |
  | --- | --- |
  | FLAC | 16-bit, 24-bit |
  | MP3 | 320 kbps, VBR V0, 256, 192, 128 kbps |
  | AAC (M4A) | original stream, 256, 192, 128 kbps |
  | Opus | original stream, 160, 128, 96 kbps |
  | ALAC (M4A) | 16-bit, 24-bit |
  | WAV | 16-bit, 24-bit |
  | Ogg Vorbis | 320, 256, 192, 128 kbps |
  | Original file | as the source serves it |

  Set a default in Settings → Downloads, pick per download with “Download
  as…”, or have KultrDL ask every time. Files get the track's title, artist,
  album, year, track number, genre and cover art, and are saved to
  `Music/KultrDL`, where other music apps find them.
- **Straight to your server.** Save FTP, FTPS or SFTP servers — a NAS, a
  seedbox, a Plex, Jellyfin or Navidrome box — with the folders music should go
  to (type them or browse the server), then choose one when you download.
  KultrDL converts and tags the file on the phone and uploads it, optionally
  into Artist or Artist/Album folders, keeping a copy on the phone if you like.
  Tracks already downloaded can be sent too (“Send to server…”).

  | Protocol | Signs in with | Trust |
  | --- | --- | --- |
  | SFTP | password or SSH key (OpenSSH, PEM or PuTTY, Ed25519, ECDSA or RSA, with or without a passphrase) | the server's key is saved the first time; if it changes, KultrDL stops and asks |
  | FTPS (explicit or implicit TLS) | password | certificates the phone trusts, or a self-signed one you approve by its fingerprint |
  | FTP | password or anonymous | none — unencrypted |

  Passwords and keys are encrypted with a key kept in the Android Keystore.
  Backups include the servers and their folders but not their passwords.

- **Suggestions, made on the phone.** “For you” on Home shows new releases from
  the artists you play, mixes made for you (Daily Mixes, Release Radar,
  Discover, “Because you play…”), albums to try, albums missing from your
  collection, and old favourites to rediscover. It learns from what you play,
  skip, heart, save, download and put in playlists — and, if you like, from
  the music files on the phone, your Navidrome (plays, stars, ratings and what
  you own), Last.fm and ListenBrainz. New music is found through Deezer's and
  Apple Music's catalogues and YouTube Music's radio. Long-press a suggestion
  for “More like this”, “Not interested” or “Never this artist”; a slider sets
  how familiar or new the mixes are; genres can be left out. New releases can
  notify you as they come out or in a weekly summary. Mixes can be saved as
  playlists that update themselves every day.
- **Navidrome.** Connect your server and its songs play in mixes straight from
  it; “Download to Navidrome” puts downloads into its music folder (over your
  saved SFTP/FTP server) and asks it to rescan. Sign in with the account you
  listen with — Navidrome keeps plays, stars and ratings per account — and,
  if that isn't an admin, add an admin login used only for rescans.
- **Block artists.** Their songs, and every song they're featured on, are
  hidden everywhere (search, albums, playlists, library, suggestions) and
  skipped if they come up in the queue.

## Where the audio comes from

KultrDL uses [yt-dlp](https://github.com/yt-dlp/yt-dlp) (through
[youtubedl-android](https://github.com/yausername/youtubedl-android), with its
own Python, QuickJS and ffmpeg) to play and download from YouTube Music,
YouTube, SoundCloud, Bandcamp and the other sites it supports. yt-dlp updates
itself from inside the app (Settings → Engine), so a change on YouTube's side is
fixed without a new release.

Spotify, Apple Music, Deezer, Tidal, Qobuz and Amazon Music protect their
streams, and KultrDL does not get around that. They are used for what they
publish openly — search, links, track lists, titles, artwork — and each of their
tracks plays and downloads from the matching recording on YouTube Music (same
title and artist, about the same length, and not a live version, remix or cover
unless that is what you picked). The downloaded file carries the catalogue's own
tags and cover. If a match is wrong, “Find another recording” in the track's
menu tries again.

So a FLAC, WAV or ALAC download keeps the source audio exactly, but it cannot add
detail the source never had: YouTube Music's best streams are AAC at about
128 kbps and Opus at about 160 kbps.

Searching Spotify needs a free Client ID and secret from
[developer.spotify.com](https://developer.spotify.com/dashboard), entered in
Settings → Search and sources; Spotify links work without one. Tidal and Amazon
Music links are read through [song.link](https://odesli.co).

Only download music you have the right to keep.

## Getting it

Download from the [latest release](https://github.com/evropiani/KultrDL/releases/latest)
and open the APK on a phone running Android 8.0 (API 26) or later:

- `KultrDL-<version>-arm64-v8a.apk` — nearly every phone from the last years
- `KultrDL-<version>-armeabi-v7a.apk` — older 32-bit phones
- `KultrDL-<version>-x86_64.apk` / `-x86.apk` — emulators and Chromebooks
- `KultrDL-<version>-universal.apk` — any of the above, but much larger

The per-processor APKs are smaller because yt-dlp's Python and ffmpeg are
native code.

## Building

Requirements: JDK 17 or newer and the Android SDK (compile SDK 37).

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :core:test                # catalogue, links, matching, recommendations, FTP/SFTP
./gradlew :app:assembleRelease      # signed, R8-shrunk APKs
```

Every push is built by GitHub Actions; the arm64 debug APK is attached to the
run as `kultrdl-debug-apk`.

### Releasing

Bump `versionCode` and `versionName` in `app/build.gradle.kts` and add notes as
`.github/release-notes/v<version>.md`. Pushing that to `main` publishes the
release (the **Release** workflow skips versions that already have one); a
`v<version>` tag or running the workflow by hand does the same.

### Signing

Releases are signed with the key in `signing/kultrdl-release.p12`, so each one
installs over the last. That key is public (it is in this repository), which
means it keeps updates working but does not prove who built an APK — install
KultrDL only from this repository's releases. To sign with a private key
instead, add the repository secrets `KULTRDL_KEYSTORE_BASE64` (the PKCS#12
keystore, base64-encoded), `KULTRDL_KEYSTORE_PASSWORD` and optionally
`KULTRDL_KEY_ALIAS` (default `kultrdl`); the release workflow then uses it.
Changing keys means uninstalling the old app once, so back up the library
first (Settings → Backup and reset).

## How it is put together

| Module | What it holds |
| --- | --- |
| `core` | Plain Kotlin, no Android: the recommendation engine (taste profile, ranking, mixes, artist blocking), the Navidrome (Subsonic), Last.fm and ListenBrainz clients, the catalogue clients (YouTube Music and YouTube search, the iTunes Search API, Deezer's API, Spotify's embed pages and Web API, Bandcamp, song.link, page metadata), link recognition, reading yt-dlp's JSON, the matcher that finds a catalogue track's recording, and FTP/FTPS (Apache Commons Net) and SFTP (JSch with Bouncy Castle) uploads. Unit-tested on the JVM, the transfers against real SSH and FTP servers. |
| `app` | The Android app: Room for the library and download queue, yt-dlp and ffmpeg through youtubedl-android, a WorkManager download worker, tagging with jaudiotagger, a Media3 playback service that resolves each queue entry to a downloaded file or a stream as it plays, and the Jetpack Compose interface with Kultr's liquid glass. |

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE). KultrDL builds on
youtubedl-android (GPL-3.0), yt-dlp (Unlicense), ffmpeg, Media3, jaudiotagger
and the design of Kultr for Android.
