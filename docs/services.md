# External services and APIs

This page lists every network service Kiko talks to: what it is used for, whether you need a key, the free limits (as of October 2026, so check the provider's page for current numbers), and where to set it in **Settings**.

All keys you enter are stored encrypted on the device (EncryptedSharedPreferences, AES-256, key in Android Keystore). They are never bundled into the APK and never written to logs.

| Service | Used for | Key needed? | Settings section |
|---|---|---|---|
| Groq (or any OpenAI-compatible API) | LLM, STT (Whisper), optional vision model, optional `browser_search` | **Yes** (free tier) | Brain (LLM), Speech recognition (STT) |
| TikTok TTS via `lazypy.ro` | Default voice | No | Voice (TTS) |
| TikTok official endpoint | TTS fallback | `sessionid` cookie (optional) | Voice (TTS) |
| ElevenLabs / OpenAI-compatible TTS | Alternative voices | Yes | Voice (TTS) |
| Android TTS | Offline voice, last fallback | No | Voice (TTS) |
| Open-Meteo | Weather + geocoding | No | Assistant → default city |
| ipwho.is / geojs.io | Approximate location by IP when GPS is unavailable | No | (automatic) |
| Web search chain | `web_search` / `browse_web` tools | No (default) / optional keys | Web search |
| Spotify (local / Web API / spotapi backend) | Music control, now playing, lyrics | Depends on mode | Spotify |
| openWakeWord (on device) | Wake word detection | No (offline) | Wake word |

---

## LLM: Groq (default)

- **What:** the "brain". It's an OpenAI-compatible `/chat/completions` endpoint with tool calling. The assistant loop (`assistant/AssistantBrain.kt`) sends the conversation plus tool schemas, runs the tools the model calls, and returns a short reply with an emotion tag that drives the face.
- **Defaults:** base URL `https://api.groq.com/openai/v1`, model `openai/gpt-oss-120b`.
- **Key:** create a free key at <https://console.groq.com/keys>. No credit card is needed. Free-tier rate limits are per model (requests/day and tokens/minute) and are listed at <https://console.groq.com/settings/limits>.
- **Where:** Settings → **Brain (LLM)** → API key (and base URL / model if you change provider).
- **Other providers** work by changing the base URL, key and model. Examples:
  - OpenAI: `https://api.openai.com/v1`
  - xAI: `https://api.x.ai/v1`
  - Gemini: `https://generativelanguage.googleapis.com/v1beta/openai`

  The model must support tool calling.

### Vision model (optional, chat mode)

Image attachments in chat are sent as `image_url` data URIs. If your main model can't read images, set a vision-capable model in **Brain (LLM) → Advanced → Vision model for image chat**. It is only used for messages that contain images, and the hint in Settings suggests a Groq model. If it's left empty, the main model is used, and the chat shows a hint if the model rejects images.

## STT (speech-to-text): Whisper on Groq

- **What:** after the voice activity detector (VAD) detects the end of speech, the recorded 16 kHz WAV is sent to `POST /audio/transcriptions`.
- **Default model:** `whisper-large-v3-turbo`. If the STT base URL and key are empty, the **LLM base URL and key are reused**, so one Groq key covers both.
- **Where:** Settings → **Speech recognition (STT)**. For OpenAI use `whisper-1` or `gpt-4o-mini-transcribe`.

## TTS (text-to-speech)

Settings → **Voice (TTS)**. A "Test voice" button plays a sample. Whatever the provider, the mouth animates from the audio amplitude. Recent phrases are cached so repeated lines play instantly.

1. **TikTok (default, free, no key).** The app posts to the community proxy **`https://lazypy.ro`** (`/tts/request_tts.php`, `service=TikTok`) and downloads the returned MP3. Voices include:
   - English: `en_us_001`, `en_us_006`, `en_uk_001`, …
   - Indonesian: `id_001`, `id_male_darma`, `id_female_icha`, …

   This is an **unofficial** service and may stop working or rate-limit at any time.
2. **TikTok official endpoint (optional).** If the proxy fails and you've entered a TikTok **`sessionid`** cookie, the app calls TikTok's own TTS endpoint with it. Treat the `sessionid` like a password: it is your TikTok login session.
3. **ElevenLabs** (API key, voice ID, model such as `eleven_multilingual_v2`) or **any OpenAI-compatible `/audio/speech`** (e.g. `gpt-4o-mini-tts`). Both are official APIs that need keys and paid or free-trial credits.
4. **Android TTS (offline).** This is the last fallback whenever a cloud provider fails (network, quota, bad key). For Indonesian, install the Indonesian voice data in the system TTS settings.

## Weather: Open-Meteo (no key)

- **What:** the `get_weather` tool and the weather-reactive face (rain, cold and heat ambience plus reactions).
- **APIs:** `api.open-meteo.com/v1/forecast` for current temperature and weather code, and `geocoding-api.open-meteo.com` for city names. Both are free for non-commercial use without a key (see <https://open-meteo.com/en/terms>).
- **Location:** the app uses coarse location if you grant it. Otherwise it estimates the location by IP with **ipwho.is**, falling back to **get.geojs.io**. Neither needs a key. If both fail, it uses the **default city** in Settings → **Assistant**.
- Weather reactions can be turned off in Settings → **Appearance**.

## Web search

The LLM gets two tools:

- **`web_search`** returns the top results (title, URL, snippet). The assistant answers briefly in your language. In voice mode it never reads URLs aloud. In chat mode the sources appear as tappable links under the reply.
- **`browse_web`** reads one page. It uses Exa contents if an Exa key is set, otherwise Tavily `/extract` (keyless or keyed), and finally `r.jina.ai` as a fallback.

Choose the provider in Settings → **Web search**. **Auto (free)** is the default and uses this chain, moving to the next provider on error, rate limit, block or timeout:

| # | Provider | Key | Notes |
|---|---|---|---|
| 1 | **Tavily keyless** | No | Rate-limited keyless access mode. Good, fresh results, and it works from any network. |
| 2 | **Your own SearXNG** | No | Only used if you enter a URL (Web search → Advanced; or pick the SearXNG chip to make it first). JSON output must be enabled (`search.formats: [html, json]`) and the limiter should be off for your instance. Public instances usually block apps. |
| 3 | **DuckDuckGo HTML** | No | Works from home and mobile IPs. Datacenter IPs often get a challenge, which counts as "blocked". |
| 4 | **Wikipedia** | No | Always available. Good for reference topics rather than news. |

Each provider gets about 10 s, and the whole search gets at most 25 s. If everything fails, Kiko says it can't search right now instead of guessing.

Optional providers (pick one in the provider chips). Your chosen provider is tried first, and the free chain remains as a fallback:

| Provider | Key | Free allowance (Oct 2026) | Get a key |
|---|---|---|---|
| Tavily | Yes | 1,000 credits/month, no card | <https://app.tavily.com> |
| Exa | Yes | Free starter credits | <https://dashboard.exa.ai/api-keys> |
| Brave Search API | Yes | Monthly free credit; card required | <https://api-dashboard.search.brave.com> |
| Serper (Google results) | Yes | 2,500 one-time queries | <https://serper.dev> |
| LangSearch | Yes | Free plan | <https://langsearch.com> |
| Groq `browser_search` | Uses your **LLM** key | Counts against your Groq limits | No extra key |

Groq `browser_search` runs the search on Groq's side with a `gpt-oss` model. It works well but uses about 8k tokens per search, so on the free tier it can quickly hit the tokens-per-minute limit. That's why it is optional rather than the default.

Keys are entered in masked fields and included in the app's settings export/import. If you saved an Exa key before this feature existed and never chose a provider, the provider is set to **Exa** automatically.

## Spotify

There are three modes (Settings → **Spotify → Spotify source**): **Local** (no setup), **Official Spotify** (Web API with OAuth PKCE; control needs Premium) and **spotapi** (a self-hosted backend that also provides now-playing details, devices and **lyrics**). Lyrics are fetched by the backend from Spotify's color-lyrics endpoint, with a fallback to [lrclib.net](https://lrclib.net) (free, no key). The app itself never contacts lrclib.

Full setup is in [spotify.md](spotify.md).

## Wake word (on device)

- **Models:** openWakeWord ONNX files in `app/src/main/assets/wakeword/`, run with ONNX Runtime. The pipeline is melspectrogram → speech embedding → classifier.
- **Default:** `hey_jarvis_v0.1.onnx`, threshold 0.5. Detection runs entirely offline, and no audio leaves the device until the wake word fires.
- **Custom models:** copy an `.onnx` openWakeWord model to `Android/data/com.coactive.companion/files/`, then enter its file name in Settings → **Wake word → Model file (.onnx)**.
- **Mic sensitivity** (Settings) also scales the wake-word threshold.
- **License:** the bundled pre-trained `hey_jarvis` model is **CC BY-NC-SA 4.0** (non-commercial). The feature models are Apache-2.0.
