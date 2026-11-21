# Live mirror + touch (Cursor tab)

The **Cursor** tab now shows the scooter display as live video and forwards touch to it.

## How it works
* Video: `screenrecord --output-format=h264` on the scooter -> raw ADB `exec:` stream -> hardware `MediaCodec`
  (low-latency flags) -> `SurfaceView`. No frame queue: a busy decoder drops frames instead of lagging.
* Touch (real-time): the touch panel node is found with `getevent -lp`, then raw Linux input events are
  streamed into `/dev/input/eventN` over a binary ADB pipe. Finger down/move/up and multi-touch are
  forwarded live. Falls back to `input tap/swipe` (sent on release) if the node is not writable.
* Back / Home / Recents buttons, Fast / Balanced / Sharp quality, full-screen landscape, and the old
  Snapshot viewer as a fallback.

## Build the APK (no PC needed)
1. Create a GitHub repo, upload this whole folder (keep `.github/` and `android/`).
2. Open the repo's **Actions** tab -> **Build APK** -> wait ~5-8 min.
3. Open the finished run on your phone -> **Artifacts** -> `S1Mods-live-mirror-apk` -> install `app-debug.apk`.

Or on a PC: `cd android && ./gradlew assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`.

## If something is off
* Touch lands in the wrong place -> press the rotate-touch button until it matches.
* "compatible mode" shown -> the panel node was not writable; touches work but are sent on release.
* Video never starts -> check Snapshot mode works; the scooter's `screenrecord` may be restricted.
* Lag -> choose **Fast**, keep phone and scooter close, use 5 GHz hotspot if offered.

# Open Boot (trunk) from the Ball's drawer

The scooter's lower half of the touchscreen does not sense touch, so the boot button cannot be pressed.
**Open Boot** is a tiny app on the scooter. Launch it from the Ball's app drawer and it brings the dashboard
to the front and taps the boot button for you (an injected tap, so it works in the dead area).

One-time setup:
1. Install the new S1Mods APK. Open **Setup** (or **Apps**) and install **Open Boot**
   (it is bundled inside S1Mods and switched on automatically).
2. Open the **Cursor** tab, use the picture to get to the dashboard screen that shows the boot button,
   tap **Set boot button**, then tap the boot button once on the picture. (That tap is only recorded.)
3. On the scooter open the Ball's drawer and launch **Open Boot**. The phone's top-bar boot icon does the same.

Notes: use only when parked. Re-running Balls setup keeps Open Boot switched on. If Open Boot is missing from the
Ball's drawer, launch it from S1Mods -> Apps -> Open Boot -> Open.
