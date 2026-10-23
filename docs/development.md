# Development

## Toolchain

- JDK 17, Android SDK platform 34, Gradle wrapper (included).
- Kotlin 1.9 with the Compose compiler 1.5.14, Compose BOM 2024.06.
- Main libraries:
  - OkHttp 4.12
  - kotlinx.serialization
  - ONNX Runtime Android 1.18
  - androidx.security-crypto
  - androidx.browser (Custom Tabs)
  - Paparazzi (snapshot rendering)

## Build variants

| Task | Output |
|---|---|
| `./gradlew assembleDebug` | Per-ABI APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and `app-universal-debug.apk` |
| `./gradlew assembleCompact` | Debug-signed, debuggable build with R8 + resource shrinking (smallest APKs) |
| `./gradlew assembleRelease` | Unsigned release. Configure your own signing; keystores are git-ignored. |

## Tests

```bash
./gradlew testDebugUnitTest              # JVM unit tests (face logic, dance, mic DSP, parsers, web search, Spotify, cloud pipeline via MockWebServer)
./gradlew recordPaparazziDebug           # re-render snapshots -> app/src/test/snapshots/images/
./gradlew verifyPaparazziDebug           # compare against recorded snapshots
./gradlew connectedDebugAndroidTest      # on device/emulator: ONNX wake word, audio decode, encrypted settings
```

Live tests against real services are **skipped by default**. Keys come only from environment variables:

```bash
GROQ_API_KEY=... LIVE_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveCloudTest*'
LIVE_TESTS=1 ./gradlew testDebugUnitTest --tests '*WebSearchTest*'      # free search chain, no key
```

## Debug-only hooks (not in release builds)

These exist only in the `debug` and `compact` source sets or behind `BuildConfig.DEBUG`. They are never compiled into `release`.

- **`DebugConfigReceiver`** sets the LLM key, model and TTS provider over adb. It is protected by `android.permission.DUMP`, which `adb shell` has and normal apps don't:
  ```bash
  adb shell am broadcast -n com.coactive.companion/.DebugConfigReceiver \
    -a com.coactive.companion.DEBUG_CONFIG --es llm_key "$GROQ_API_KEY" --es tts TIKTOK
  ```
  This puts the key on your shell command line, so prefer the Settings screen on shared machines.
- **Settings export/import (`settings_sync.json`).** `am start -n com.coactive.companion/.MainActivity --ez debugExportSettings true` writes all settings, **including API keys**, to the app's private `files/settings_sync.json`. `--ez debugImportSettings true` reads that file and then deletes it. It's meant for copying a configuration between your own test devices with `run-as`. Don't share the file.
- **`--es debugWeather RAIN|COLD|HOT`** previews the weather reactions.

## Code layout

```
app/src/main/java/com/coactive/companion/
  MainActivity.kt        Compose host, permissions, Spotify OAuth redirect
  assistant/             AssistantViewModel (state machine), AssistantBrain (LLM tool loop), emotions, DanceMode
  audio/                 16 kHz mic capture, noise suppression/AEC/AGC, MicSensitivity, VAD, earcons
  wakeword/              openWakeWord streaming pipeline on ONNX Runtime
  llm/ stt/ tts/         OpenAI-compatible chat + Whisper, TikTok/ElevenLabs/OpenAI/Android TTS, audio cache, focus
  chat/                  Chat mode: persistent history, image & text attachments
  search/                WebSearch providers, fallback chain, result parsers
  tools/                 LLM tools (DefaultTools lists them): web, Spotify, timer/alarm, open app, datetime, weather, dance
  spotify/               Local / Official (Web API + PKCE) / spotapi controllers, now-playing poller, WebView login
  weather/               Open-Meteo + IP geolocation, weather mood
  settings/              AppSettings, encrypted SettingsRepository, app locale
  ui/                    MainScreen, ChatScreen, PlayerScreen, DynamicIsland, SettingsScreen, theme
  ui/face/               Face canvas, expressions, gaze, personality reactions, dance, weather ambience
```

### Adding a tool

1. Implement `AssistantTool` (name, description, JSON `schema { … }`, `execute()` returning text/JSON).
2. Register it in `tools/DefaultTools.kt`. The LLM sees it automatically.

### Swapping a provider

Implement `ChatLlm`, `SpeechToText` or `CloudTts`/`TtsEngine` and wire it up in `AssistantViewModel`.

## Known limitations

- The wake word works only while the app is in the foreground (there is no foreground service).
- The VAD is energy-based. In noisy rooms the end of speech can be detected late (the maximum utterance is 15 s).
- Timers and alarms use the system Clock app (`SET_TIMER` / `SET_ALARM` intents).
- TikTok TTS, the `lazypy.ro` proxy, Tavily keyless and DuckDuckGo HTML are unofficial or free-tier services and may break or rate-limit. Fallbacks are built in.
