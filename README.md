# TM Player

**TM Player (Turbo Media Player)** — a fast, customizable video and music
player for Android with a **built-in web browser (TM Browser)**, a private
vault, and a full download manager, built only on Android's own APIs.

**Layout (PLAYit-style):** bottom tabs **Video / Music / Vault / Me** plus a
**Browser** tab that opens TM Browser; the
Video section has its own top tabs (**Video / Folder / Playlist**); the
**Me** page is a feature hub with a Theme page (all themes free), Media
Manage (storage usage, watched / largest / unwatched videos), a Recycle
Bin, History, a PLAYit-style **Clean** page (APK files, thumbnails and
cache scan with a one-tap clean) and an MP3 Converter shortcut. The Vault
uses a numeric PIN pad with optional fingerprint unlock.

The home library can be shown as PLAYit-style rows (grouped by month) or
as a grid, sorted by date, name, size or duration. The History strip on
the home screen has its own clear button. In the player, the top bar
carries an **HDR Enhance** badge (a color/contrast lift — not HDR10
decoding) and a **Screenshot** button that saves the current frame to
Pictures/TM Player.

Player controls sit on dark gradient scrims with badge-backed buttons,
so they stay visible over bright videos. **Customize Theme** can use any
gallery photo as the app background (softened with the theme color so
text stays readable). Settings include a PLAYit-style Video page: video
name display, screen orientation + lock, fixed ratio, battery/time
overlay, resume, gestures, continuous play, auto pop-up (PiP), double-tap
seek time, long-press accelerator with speed and vibration, subtitle
size — plus a scan-list manager to hide folders from the library.
One APK works on **Android 8.0 (API 26) and above** — phones, custom ROMs,
any manufacturer. No root needed.

Developed by **MD. Muklash Mia**
- Telegram: [@muklash_dev](https://t.me/muklash_dev)
- GitHub: [Muklash-Dev](https://github.com/Muklash-Dev)

![TM Player logo](https://github.com/Muklash-Dev/TM-Player/releases/download/v0.1/TMPlayer_Logo.jpg)

## Features

- **TM Browser (new in v0.2)** — a built-in browser on the system WebView:
  tabs + incognito tabs, bookmarks, day-grouped history, find in page,
  desktop / mobile mode, night mode, text size, orientation lock, saved
  pages for offline reading, view source, add-to-home-screen shortcuts,
  and search engines (Google, DuckDuckGo, Bing, Yahoo, Startpage, Ecosia,
  Qwant, Yandex — plus your own custom engines).
- **Ad & popup blocking** — a bundled 30,000-domain blocklist (from the
  Steven Black hosts project) plus optional ad-blocking DNS (DoH presets
  incl. AdGuard); gesture-less popup navigations to ad hosts are killed.
- **Download manager** — foreground-service downloads with live progress
  and speed, pause / resume (HTTP Range), cancel, delete, background
  downloading with a progress notification, and a **download folder you
  can change** (internal storage or any SD-card folder via SAF). A page
  media finder lists the videos, images and files a page offers for
  direct play or download.
- **All storage** — scans internal storage, SD card **and USB pendrives**
  (every mounted volume, unlike many players). Plus *Browse any folder*
  for anything the media library has not indexed.
- **Folders view** — videos grouped by folder, storage overview.
- **Music player** — a full music tab with shuffle-all, a mini player and
  a full-screen player (repeat / shuffle included).
- **History** — recently watched videos on top of the library, one tap to
  resume.
- **Playlists** — create video playlists and add any video to them.
- **Private Vault** — PIN-locked vault inside the app (optional
  fingerprint unlock); videos moved there are hidden from the gallery
  and other apps. Export back any time.
- **Gesture player** — swipe for brightness (left) / volume (right) / seek,
  double-tap to skip ±10 seconds, tap to show or hide controls.
- **Playback controls** — speed 0.25x–4.0x, Fit / Fill / Stretch screen
  modes, rotation, picture-in-picture, control lock, resume where you
  left off, next / previous, repeat and shuffle.
- **Background play** — keep a video's audio playing with the screen off
  or while using other apps (foreground-service backed).
- **Video to Audio** — extract a video's audio track: MP3 stays MP3,
  AAC becomes M4A, saved to Music/TM Player.
- **Subtitles** — load any external `.srt` file while watching.
- **Quality badges** — 4K / 1080p / 720p shown on every video, with a
  details view (resolution, duration, size, folder, date).
- **Customizable** — 6 accent colors, dark / light theme, text size,
  default speed and screen mode, grid columns, sort orders — the app
  remembers everything.
- **Open with** — other apps can hand videos to TM Player.

## Downloads

See [Releases](https://github.com/Muklash-Dev/TM-Player/releases) for the
ready-to-install APK.

## Build

Pure Android framework APIs — no third-party dependencies.

```sh
./build.sh        # manual build (JDK 17 + Android SDK 37; min Android 8, target Android 17), or
# open the project in Android Studio and run the app module
```

Signed release builds use the developer's release keystore; `build.sh`
produces an unsigned APK when no keystore environment is set.

## Notes

Playback uses the device's own codec support (via `MediaPlayer`), so a
file in an exotic format may not play on every device. Format support
grows release by release.

## License

MIT — see [LICENSE](LICENSE).
