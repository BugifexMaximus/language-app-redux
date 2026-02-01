# Current Status

Last updated: 2026-02-01

## Project state
- Android app package: `com.mystuff.simpletutor`
- Target/compile SDK: 36
- Min SDK: 29

## Completed work
- Android manifest: added permissions for `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, and `POST_NOTIFICATIONS`.
- Foreground service: `MicrophoneForegroundService` declared with `foregroundServiceType="microphone"` and notification channel/notification.
- Voice pipeline: live PCM capture with WebRTC VAD, manual/always-on listening modes, silence-based end detection for manual mode, and OpenAI STT + intent classification.
- Debug overlays: top marquee banner while sending and transcript popup (toggleable).
- VAD settings panel: always-on/manual toggles, thresholds, live level meter, and keyboard dismiss-on-tap-outside.
- Runtime permissions: `POST_NOTIFICATIONS` (API 33+) and `RECORD_AUDIO` requests with rationale; service start is gated by permission results.
- Debug panel (debug-only):
  - `app/src/debug/AndroidManifest.xml` declares `DebugPanelActivity` (non-launcher).
  - `app/src/debug/java/com/mystuff/simpletutor/DebugPanelActivity.kt` supports LLM/STT/TTS model dropdowns, STT language + prompt inputs, mic recording + playback for STT, TTS voices with playback, and formats LLM/STT output (meat only).
  - `app/src/debug/res/layout/activity_debug_panel.xml` includes LLM/STT/TTS dropdowns, voice/audio path inputs, mic record button, action buttons, status, and output.
  - Debug-only override `app/src/debug/res/layout/activity_main.xml` adds an "Open Debug Panel" button.
- Main activity now opens debug panel via class-name intent when the button is present.
- Main activity now has a minimal selection flow: user + target language panel -> mode panel -> chat mode panel, with alexei-1/alexei-2 users and Spanish/Japanese/Korean target languages (Japanese default) mapped to language codes.
- Chat mode panel includes a conversation log placeholder and a voice input dock with status indicator.
- Chat mode now hooks the Start listening button to the VAD manual listening toggle and feeds STT -> LLM -> TTS only when the pipeline route is set to tutor (set by the chat panel); the default route is test_stt for VAD-only testing, and tutor mode suppresses the marquee + transcript popups.
- Debug buttons are only visible on the first (user + language) screen in debug builds.
- OpenAI wiring:
  - `gradle/libs.versions.toml` adds OkHttp + coroutines version catalog entries.
  - `app/build.gradle.kts` reads `OPENAI_API_KEY` from `local.properties` into `BuildConfig`.

## Recent testing
- Emulator launch verified; debug panel opens from the main screen.
- Physical device previously crashed on launch due to missing `RECORD_AUDIO` permission; fixed by gating service start.
- Debug build installed to physical device (SM-S928U1) and emulator.

## Open items
- Verify manual listening UX on a physical device.
- Consider replacing the banner activity with an in-app overlay to avoid pausing other screens.
- Flesh out mode-specific panels once the designs are ready.

## Notes
- `codex-TODO.md` is being used as a running task list.
