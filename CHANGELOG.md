# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).
APKs for each version are attached to the [GitHub Releases](https://github.com/ejatapibeda/kiko-companion/releases).

## [Unreleased]

## [1.0.0] - 2026-10-24

First public release.

### Added

- Voice chat: Whisper speech-to-text, an LLM with tool calling (Groq `openai/gpt-oss-120b` by default, any
  OpenAI-compatible endpoint) and spoken replies with a mouth that follows the audio level.
- On-device wake word with openWakeWord models on ONNX Runtime ("Hey Jarvis" by default), custom `.onnx` models
  and an adjustable threshold.
- Chat mode with saved history, Markdown replies, image attachments for vision models and `.txt` / `.md` files as context.
- Activity indicators for listening, transcribing, thinking, looking at an image, web search (with the query),
  reading a page, Spotify actions, weather, timers and alarms, opening apps, the time, dancing, preparing speech and
  loading lyrics: face expressions, small animations, a status pill, an inline chat status row and per-reply step lists.
  Failed tools show a "Didn't work" state; everything is static when system animations are off.
- Dynamic Island now-playing pill that expands into a mini player, plus a full player with album-art colors and synced lyrics.
- Spotify control in three modes: local intents and media keys, the official Web API (OAuth PKCE) and the
  self-hosted [spotapi-backend](https://github.com/ejatapibeda/spotapi-backend).
- Weather-reactive face from Open-Meteo (coarse location or IP fallback): rain streaks, drifting six-armed
  snowflakes, warm haze, and sneezing, shivering and overheating reactions.
- Dance mode with beat bounce, music notes, EQ bars, a beat halo and sparkles, and the `face_dance` tool.
- Sleep animation after inactivity; tap, shake or the wake word wakes Kiko up.
- Touch and motion personality: taps, long-presses, rapid taps, shaking and tilting, with optional haptics.
- TTS fallback chain: TikTok voices through a free proxy, the official TikTok endpoint with a `sessionid`,
  ElevenLabs or any OpenAI-compatible `/audio/speech`, and Android TTS as the last fallback.
- Mic processing: noise suppression, echo cancellation, AGC and a mic sensitivity slider.
- Web search with a keyless default chain (Tavily keyless → SearXNG → DuckDuckGo → Wikipedia) and optional
  Tavily, Exa, Brave, Serper, LangSearch and Groq `browser_search` providers.
- Timers and alarms, opening apps, date and time, and weather tools.
- English and Indonesian UI, prompts and voice defaults.
- Encrypted key storage (EncryptedSharedPreferences + Android Keystore); no keys bundled or logged.
- App version and a Releases link in Settings → About.
- Signed release builds (`kiko-companion-v<version>-<abi>.apk` for arm64-v8a, armeabi-v7a, x86_64 and universal)
  with SHA-256 checksums, a tag-triggered release workflow and a CI workflow for unit and snapshot tests.

[Unreleased]: https://github.com/ejatapibeda/kiko-companion/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/ejatapibeda/kiko-companion/releases/tag/v1.0.0
