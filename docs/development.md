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
./gradlew testDebugUnitTest              # JVM unit tests (face logic, dance, mic DSP, parsers, web search, Spotify, activity tracker, cloud pipeline via MockWebServer)
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
- **`--es debugAsk "search the web for …"`** sends a text turn through the voice pipeline (thinking → tools → TTS), so you can watch the activity indicators on the home face without speaking.

## Code layout

```
app/src/main/java/com/coactive/companion/
  MainActivity.kt        Compose host, permissions, Spotify OAuth redirect
  assistant/             AssistantViewModel (state machine), AssistantBrain (LLM tool loop), AgentActivity (activity tracker), emotions, DanceMode
  audio/                 16 kHz mic capture, noise suppression/AEC/AGC, MicSensitivity, VAD, earcons
  wakeword/              openWakeWord streaming pipeline on ONNX Runtime
  llm/ stt/ tts/         OpenAI-compatible chat + Whisper, TikTok/ElevenLabs/OpenAI/Android TTS, audio cache, focus
  chat/                  Chat mode: persistent history, image & text attachments
  search/                WebSearch providers, fallback chain, result parsers
  tools/                 LLM tools (DefaultTools lists them): web, Spotify, timer/alarm, open app, datetime, weather, dance
  spotify/               Local / Official (Web API + PKCE) / spotapi controllers, now-playing poller, WebView login
  weather/               Open-Meteo + IP geolocation, weather mood
  settings/              AppSettings, encrypted SettingsRepository, app locale
  ui/                    MainScreen, ChatScreen, PlayerScreen, DynamicIsland, SettingsScreen, ActivityIndicators, theme
  ui/face/               Face canvas, expressions, gaze, personality reactions, dance, weather ambience
```

### Adding a tool

1. Implement `AssistantTool` (name, description, JSON `schema { … }`, `execute()` returning text/JSON).
2. Register it in `tools/DefaultTools.kt`. The LLM sees it automatically.
3. Optional: give it its own activity state. Add an `ActivityKind`, map the tool name (and the argument to show) in `ToolActivity.of()`, and add an icon/label in `ui/ActivityIndicators.kt` plus `act_*` / `step_*` strings in `values/` and `values-in/`. Unmapped tools still show the generic "Using <tool>…" state.

## Activity indicators

`assistant/AgentActivity.kt` holds an `ActivityTracker` that is driven by real events, not timers. `AssistantBrain` reports `onLlmRequest`, `onToolStart` and `onToolEnd` through a `BrainListener`, and the view model adds the voice stages (listening, transcribing, speaking). Each turn gets an id from `beginTurn()`, so a cancelled turn can't clear the status of the next one. A tool result that is JSON with `"error"` or `"ok": false` marks the state as failed.

| State (`ActivityKind`) | Trigger | Face | Pill label (EN / ID) |
|---|---|---|---|
| `LISTENING` | mic opened | existing listening bars + caption "Listening…" (no pill, to avoid duplicating it) | Listening… / Mendengarkan… |
| `TRANSCRIBING` | audio sent to STT | curious, listening gaze | Transcribing… / Mentranskrip suara… |
| `THINKING` | each LLM request | thinking, thought bubble with dots | Thinking… / Lagi mikir… |
| `VISION` | first LLM request with images | curious, reading gaze, image bubble | Looking at the image… |
| `WEB_SEARCH` | `web_search` | curious, scanning gaze, magnifier orbiting above the head | Searching the web: "query" |
| `BROWSING` | `browse_web` | curious, reading gaze, page bubble with a sweeping line | Reading host… |
| `SPOTIFY_*` | `spotify_play`, `spotify_control` (pause/resume/next/previous), `spotify_now_playing` | happy, green bubble with music notes | Playing on Spotify: "query", Pausing Spotify…, … |
| `WEATHER` | `get_weather` | curious, rotating sun + cloud | Checking the weather in city… |
| `TIMER` / `ALARM` / `OPEN_APP` / `DATETIME` | `set_timer` / `set_alarm` / `open_app` / `get_datetime` | thinking/curious, pulsing icon bubble | Setting a 5m timer…, Setting an alarm for 06:30…, Opening YouTube… |
| `DANCE` | `face_dance` | excited | Getting ready to dance… |
| `TOOL` | any other tool | thinking | Using tool_name… |
| `SPEAKING_PREP` | reply ready, TTS loading; cleared on the first audio level | speaking face, voice-icon bubble | Getting ready to speak… / Siap-siap ngomong… |
| `LYRICS` | player lyrics loading | (player screen only) | Loading lyrics… |
| failed | tool returned an error | sad, red error bubble | Didn't work: … / Gagal: … |

- **Placement.** The home pill sits at the bottom, above the caption and the mic. The Dynamic Island owns the top centre, so the pill never goes there. Face effects keep at least 64 dp from the top edge, so they stay clear of the top buttons and the island.
- **Chat.** `ChatController` exposes the same activity. The typing bubble turns into a status row, and each stored reply keeps its `steps` (`StoredChatMessage.steps`, shown as chips before the sources). Old history without `steps` still loads.
- **Performance.** Animations run only while a state is active, using one infinite transition per element and `graphicsLayer` lambdas, which is cheap enough for 32-bit tablets. Very fast tools are held on screen for at least 650 ms so they can be read. This only smooths the display; the tracker state itself is exact.
- **Reduced motion.** With *Remove animations* (animator duration scale 0) everything is drawn static.
- **Tests.** `ActivityTrackerTest` covers the state machine (tool mapping, errors, repeated tools, stale turns, the brain listener, chat persistence). `ActivityScreenshotTest` renders the home states in light/dark, portrait/landscape with the island, a pill sheet with every state, and the chat status row.

### Swapping a provider

Implement `ChatLlm`, `SpeechToText` or `CloudTts`/`TtsEngine` and wire it up in `AssistantViewModel`.

## Known limitations

- The wake word works only while the app is in the foreground (there is no foreground service).
- The VAD is energy-based. In noisy rooms the end of speech can be detected late (the maximum utterance is 15 s).
- Timers and alarms use the system Clock app (`SET_TIMER` / `SET_ALARM` intents).
- TikTok TTS, the `lazypy.ro` proxy, Tavily keyless and DuckDuckGo HTML are unofficial or free-tier services and may break or rate-limit. Fallbacks are built in.
