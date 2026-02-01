# SimpleTutorApp

Android app with a debug-only OpenAI test panel and a microphone/VAD pipeline for voice-driven navigation.

## Debug tools (debug build)
- Open Debug Panel from the main screen (debug-only layout).
  - LLM/STT/TTS model dropdowns and test buttons.
  - STT recording + playback, TTS playback, formatted response output.
- Open VAD Settings to tune always-on/manual listening, thresholds, and see live levels.

## Voice pipeline (foreground service)
- Always-on or manual listening.
- WebRTC VAD segmentation + silence-based end detection for manual mode.
- Sends audio snippets to `/v1/audio/transcriptions`, then classifies intent via `/v1/responses`.
- Shows a top banner while sending and a transcript popup (toggleable).

## Local setup
- Add your key to `local.properties`:
  - `OPENAI_API_KEY=...`
- The key is read into `BuildConfig.OPENAI_API_KEY` for debug use.
