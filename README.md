# SimpleTutorApp

Android app with a debug-only OpenAI test panel and a microphone/VAD pipeline for voice-driven navigation.
Includes a simple user/language selection flow and a chat mode panel wired to the voice pipeline.

## Debug tools (debug build)
- Open Debug Panel from the main screen (debug-only layout).
  - LLM/STT/TTS model dropdowns and test buttons.
  - STT recording + playback, TTS playback, formatted response output.
- Open VAD Settings to tune always-on/manual listening, thresholds, and see live levels.

## Voice pipeline (foreground service)
- Always-on or manual listening.
- WebRTC VAD segmentation + silence-based end detection for manual mode.
- Sends audio snippets to `/v1/audio/transcriptions`, then routes into `/v1/responses` for intent/chat.
- Shows a top banner while sending and a transcript popup (toggleable); suppressed in tutor chat mode.

## Main flow
- User + target language selection (Japanese default).
- Mode panel, then chat mode panel with a conversation placeholder and voice input dock.
- Manual listening toggle drives the VAD pipeline; STT -> LLM -> TTS only runs in tutor route.

## Local setup
- Add your key to `local.properties`:
  - `OPENAI_API_KEY=...`
- The key is read into `BuildConfig.OPENAI_API_KEY` for debug use.
