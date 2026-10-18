# DualRec
  ![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)
  ![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin&logoColor=white)
  ![License](https://img.shields.io/github/license/Shabbir-369/DualRec)
  [![Download APK](https://img.shields.io/badge/Download-APK-red)](https://github.com/Shabbir-369/DualRec/releases/latest/download/DualRec.apk)
Record a video call's **screen** on one phone and its **sound** on a second phone, then merge both into a single MP4 automatically. One tap to start.

Built for situations where the call app's own audio can't be captured: the audio phone sits next to the video phone's loudspeaker and records the voices, while the video phone records the screen. The two recordings are synced over Bluetooth and muxed together with no re-encoding.

> Developed and used with a **Moto Edge 70** (video phone) and a **POCO M3** (audio phone).

---

## Features

- **One-tap recording** on the video phone: turns Bluetooth on if it's off, connects to the audio phone, starts both recordings.
- **Three ways to start/stop:** big in-app button, home-screen **widget** (with live timer), or **Quick Settings tile**.
- **Automatic sync:** clock offset between the phones is measured over Bluetooth, so audio lines up with the video. A ±50 ms fine-tune is available in Settings.
- **Built for long recordings (1–2 hours):** wake lock, storage checks, direct-to-gallery merge, and a fallback that saves the raw screen video if merging ever fails.
- **Stays out of the video:** the app's own screen is blacked out in recordings, and its notification has no status-bar icon.
- **No re-encoding:** video and audio are muxed as-is, so merging is fast and lossless.
- Dark, responsive UI and an adaptive app icon (themed-icon support included).

## How it works

```
 VIDEO phone                                   AUDIO phone
 ───────────                                   ───────────
 tap ● REC                                     DualRec open, "Listening"
   │  Bluetooth on (if off)
   │  connect over Bluetooth (RFCOMM) ───────►  accepts connection
   │  measure clock offset (20 pings) ◄──────►  replies with its clock
   │  'S' start mic ─────────────────────────►  starts recording (AAC)
   ▼  start screen capture (H.264)
 ... call in progress ...
 tap ■ STOP
   │  stop screen capture
   │  'E' stop mic ──────────────────────────►  stops, sends audio file
   ▼  merge video + audio → Movies/DualRec/Call_YYYYMMDD_HHMMSS.mp4
```

Protocol between the phones is three single-byte commands: `T` (time), `S` (start mic), `E` (stop mic and send file).

## Requirements

- Two Android phones, **Android 11 or newer** (minSdk 30), paired with each other over Bluetooth.
- The audio phone needs a working microphone and should be placed right next to the video phone's loudspeaker.

## Install

The repo includes a GitHub Actions workflow that builds the APK for you.

1. Push this project to a GitHub repository (branch `main` or `master`).
2. Open the **Actions** tab and wait for **Build APK** to finish (or run it manually with *Run workflow*).
3. Open **Releases → "DualRec - latest build"** and download `DualRec.apk` on each phone.
4. Install it (allow "install unknown apps" if asked).

Every build is signed with the same bundled debug key, so a new APK installs over the old one without uninstalling and your settings are kept.

To build locally instead: open the project in Android Studio (JDK 17) and run `assembleDebug`.

## First-time setup

1. On **both** phones: *Android Settings → Bluetooth* → pair them with each other.
2. **Audio phone:** open DualRec → choose **AUDIO phone** → allow the permissions. It shows **Listening**. Leave it running; you can lock the screen.
   - Tap **Keep running in background** and allow it.
   - On Xiaomi/POCO also turn **Autostart** on for DualRec and set *Battery saver → No restrictions*.
3. **Video phone:** open DualRec → choose **VIDEO phone** → allow Bluetooth permission → tap the audio phone in the list.
4. Optional: open *Settings → Add widget to home screen*, and/or add the **Dual Rec** tile to Quick Settings.

You only do this once per phone.

## Recording

1. Make sure the audio phone shows **Listening** and is next to the video phone's speaker.
2. On the video phone, tap the **widget**, the **tile**, or the big **REC** button.
3. Confirm Android's **Start now** screen-capture prompt. This prompt is required by Android for every screen capture and cannot be skipped by an app.
4. Go to your call. Recording runs in the background.
5. To finish, tap the widget/tile again, the notification's **Stop & save**, or open the app and tap the button.
6. Wait while the audio is fetched and merged. The result is saved to **Movies/DualRec** (visible in your Gallery).

## Tips for long recordings

- Keep **at least 4 GB free** on the video phone (the app refuses to start below that). Plan for roughly **8 GB per hour** of recording; the merge happens at the end and needs about as much space again as the video.
- If free space gets low mid-recording, DualRec stops and saves automatically.
- Keep both phones charged or plugged in.
- Audio transfer at the end happens over classic Bluetooth, so a 2-hour recording can take several minutes to fetch. Don't close the app or turn Bluetooth off while it says "Getting audio over Bluetooth…".
- If voices sound slightly early or late in a test clip, adjust **Settings → Fine-tune sync** (+ delays the audio, − makes it earlier).

## What's hidden from the recording

- DualRec's own screen is blacked out (it uses `FLAG_SECURE`; this also blocks screenshots of the app).
- The recording notification uses a minimum-importance channel: no status-bar icon, no pop-up, hidden on the lock screen.
- **Not hideable:** Android's own screen-capture indicator, and the notification shade if you open it to stop. Trim the last second of the video if needed.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Can't reach the audio phone" | Open DualRec on the audio phone and make sure it says **Listening** and Bluetooth is on. Use *Settings → Test Bluetooth connection* on the video phone. |
| Android asks "Allow DualRec to turn on Bluetooth?" | Some phones don't allow silent switch-on. Tap Allow; the rest stays automatic. |
| Audio phone stops listening after a while | Allow background running and disable battery restrictions for DualRec (see setup step 2). |
| Saved "VIDEO ONLY" | The audio couldn't be fetched. Check Bluetooth, and record again. |
| "Merge failed … saved the raw screen video" | The raw video is in Movies/DualRec. Free some storage and try again; the audio is lost in this case. |
| Audio out of sync | Adjust *Fine-tune sync* in Settings. |

## Permissions

| Permission | Why |
|---|---|
| Bluetooth (`BLUETOOTH_CONNECT`, plus legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` on Android ≤ 11) | Turn Bluetooth on and talk to the other phone |
| `RECORD_AUDIO` | Audio phone records the speaker |
| Foreground services (media projection, microphone) | Keep recording while the app is in the background |
| `POST_NOTIFICATIONS` | Show the recording notification |
| `WAKE_LOCK` | Keep the CPU awake during long recordings |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Let the audio phone keep listening in the background |

## Project structure

```
app/src/main/java/com/dualrec/
├── MainActivity.kt          UI for both roles (video / audio), settings
├── QuickStartActivity.kt    Invisible one-tap entry: permissions → Bluetooth → screen-capture prompt → start
├── RecorderService.kt       Video phone: screen capture, audio fetch, merge, storage/wake-lock handling
├── AudioService.kt          Audio phone: Bluetooth server + microphone recording
├── Bt.kt                    Bluetooth connect (auto-enable, retries) and the T/S/E link protocol
├── Util.kt                  Shared state, MP4 merger, gallery saver
├── RecWidget.kt             Home-screen widget
├── RecTile.kt               Quick Settings tile
└── RecButton.kt             Custom round record button
```

## Technical notes

- Screen: H.264, up to 1920 px on the long side, 30 fps, 8 Mbps, written with `MediaRecorder` + `MediaProjection`.
- Audio: AAC, mono, 44.1 kHz, 64 kbps.
- Sync: the clock offset is the median of the 5 fastest of 20 Bluetooth round trips; the audio is trimmed to start exactly at the first video frame.
- `targetSdk` is set to 32 on purpose: Android blocks `BluetoothAdapter.enable()` (silent Bluetooth switch-on) for apps targeting 33+. If the system still refuses, the app falls back to the standard "turn on Bluetooth" prompt.

## Limitations

- Android always shows its own **Start now** prompt for screen capture.
- Apps that block screen capture (some banking/DRM apps) will appear black in the recording.
- Audio quality depends on the audio phone's placement relative to the speaker.

## License

Add a license of your choice (for example MIT) before publishing.