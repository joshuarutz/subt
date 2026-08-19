# Live Subtitles for Android

Native Android port of the supplied `subtitles.py`.

## What changed

- **Audio input:** Android microphone (`AudioRecord`) replaces Windows WASAPI loopback.
- **Display:** a draggable Android overlay replaces the PySide6 desktop overlay.
- **Core pipeline preserved:** 4-second rolling audio window, repeated OpenRouter STT, transcript overlap merging, grammatical subtitle chunking, latest-only translation behavior, context-aware translation, and three visible subtitle lines.
- **Default translation:** German.
- **STT choices:** Deepgram Nova-3, Microsoft MAI Transcribe 1.5, Google Chirp 3, or a custom OpenRouter STT model.
- **Translation model:** `google/gemini-2.5-flash-lite`.

## Permissions

The app requests:
- Microphone
- Display over other apps
- Notifications (Android 13+)

It uses a microphone foreground service so capture can continue while another app is visible.

## API key

The OpenRouter key is entered in the app. It is passed to the running service in memory and is not compiled into the APK.

## Build

Recommended toolchain:
- Android Studio with Android SDK Platform 35 installed
- JDK 17
- Android Gradle Plugin 8.6.1
- Gradle 8.7 or newer compatible with AGP 8.6

Open this folder in Android Studio and choose **Build > Build APK(s)**.

Command line, if Gradle and Android SDK are installed:

```bash
gradle :app:assembleDebug
```

Expected output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- This app listens to the **device microphone**, not Android internal/system playback.
- It requires internet access because speech recognition and translation are sent to OpenRouter.
- Some Android vendors aggressively stop background services. If subtitles stop after the screen is off for a long period, disable battery optimization for this app.


## GitHub Actions build

A ready-to-run workflow is included at `.github/workflows/build-apk.yml`.

If you put this project in a GitHub repository, open **Actions → Build Android APK → Run workflow**. The resulting `live-subtitles-debug-apk` artifact contains `app-debug.apk`.
