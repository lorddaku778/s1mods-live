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
