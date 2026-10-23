# Spotify

Pick a mode in Settings → **Spotify → Spotify source**. All three implement the same `SpotifyController` interface, so the voice commands and LLM tools (`spotify_play`, `spotify_control`, `spotify_now_playing`) work in every mode.

| Mode | Setup | Now playing / Dynamic Island | Lyrics | Premium needed? |
|---|---|---|---|---|
| **Local** (default) | None, but the Spotify app must be installed and logged in | No | No | No |
| **Official Spotify** | Your own Spotify developer app (Client ID) | Yes | No | **Yes, for playback control** |
| **spotapi** | A self-hosted backend (below) | Yes, including album art and device | **Yes** (synced when available) | No |

## A. Local

"Play Tulus" sends `MEDIA_PLAY_FROM_SEARCH` to the Spotify app. Pause, resume and skip use system media keys. The app can't read what is playing. For dance mode, a successful play or skip from Kiko starts a five-minute "music session" instead.

## B. Official Spotify Web API (OAuth PKCE, no client secret)

1. Go to <https://developer.spotify.com/dashboard> → **Create app** and tick **Web API**.
2. Add the redirect URI **`com.coactive.companion://spotify-callback`**. It must match exactly.
3. Copy the **Client ID**. While the app is in *Development mode*, add your Spotify account email under **User Management**.
4. In Kiko: choose **Official Spotify** → paste the Client ID → **Connect Spotify** → approve in the browser.

Scopes: `user-modify-playback-state user-read-playback-state streaming`. The access and refresh tokens are stored encrypted and refreshed automatically. Controlling playback through the Web API requires **Spotify Premium**. If no device is active, the app offers to open Spotify.

## C. spotapi: self-hosted backend

This mode talks to a small **single-user HTTP backend** that wraps [SpotAPI](https://github.com/ejatapibeda/SpotAPI), a Python library that uses Spotify's web-player (private) API. It needs no Premium and no developer app. It's a good fit if you want now-playing info, album art, devices and lyrics on a Free account.

> SpotAPI is unofficial and intended for educational use. Spotify can change its private API at any time, and using it may violate Spotify's Terms of Service. Use it at your own risk with your own account. SpotAPI is GPL-3.0. The backend runs as a separate service, and this app only calls it over HTTP.

The backend is open source: **[ejatapibeda/spotapi-backend](https://github.com/ejatapibeda/spotapi-backend)** (FastAPI + Docker, GPL-3.0, amd64/arm64). Its README has the full setup: `.env` variables, Docker Compose quick start, HTTPS via Tailscale Serve/Funnel/reverse proxy, API reference with examples, and troubleshooting. Below is a summary of the contract the app expects (`app/src/main/java/com/coactive/companion/spotify/SpotapiBackend.kt`).

### Reference architecture

- **Server:** [spotapi-backend](https://github.com/ejatapibeda/spotapi-backend) — **FastAPI** in **Docker**. Use a glibc image such as `python:3.12-slim`, not Alpine, because SpotAPI's `tls_client` loads a Go shared library. It runs on amd64 and arm64 (Raspberry Pi, Armbian boards, …).
- **Process:** one container with one worker, because the player state is kept in memory.
- **Storage:** SQLite under `/data`. The Spotify cookies are stored encrypted with a Fernet key (`ENC_KEY`).
- **Auth:** every endpoint except `/health` and `/auth/pair` requires `Authorization: Bearer <APP_TOKEN>`.
- **Lyrics:** fetched from Spotify's color-lyrics endpoint, with a fallback to [lrclib.net](https://lrclib.net).

Example `.env` (generate your own values and never commit this file):

```bash
APP_TOKEN=$(openssl rand -hex 32)        # bearer token the app uses
ENC_KEY=<32-byte url-safe base64 Fernet key>
PORT=8080
BIND_ADDR=127.0.0.1                      # only listen locally; expose through a tunnel (below)
PAIRING_CODE=                            # optional, e.g. 483-219, enables POST /auth/pair
```

```bash
docker compose up -d --build
curl -s http://127.0.0.1:8080/api/v1/health      # {"ok":true,...}
```

### Endpoints (base URL `https://your-host/api/v1`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness check (no auth) |
| POST | `/auth/pair` | Exchange a short pairing code for the app token (no auth; disabled when `PAIRING_CODE` is empty) |
| GET / POST / DELETE | `/spotify/session` | Session status / upload Spotify web cookies / log out |
| POST | `/play` | `{"query": "song name"}` or `{"uri": "spotify:track:…"}`, with optional `device_id`. The search happens server-side. |
| POST | `/pause`, `/resume`, `/next`, `/prev` | Playback control (optional `device_id`) |
| GET | `/now-playing` | Track, artists, album, art URL, position/duration, `is_playing`, device name |
| GET | `/devices` | Spotify Connect devices |
| GET | `/lyrics?uri=spotify:track:…` | `{synced, source: "spotify" or "lrclib", lines: [{start_ms, text}]}`. Returns 404 if there are no lyrics. |

There is no separate search endpoint: `POST /play` with `{"query": "..."}` searches on the server and plays the top hit (or `index` N). The full OpenAPI contract is [`openapi.yaml`](https://github.com/ejatapibeda/spotapi-backend/blob/main/openapi.yaml).

Errors use one envelope: `{"ok": false, "error": {"code", "message", "retryable"}}`. Common codes:

- `UNAUTHORIZED`
- `SPOTIFY_SESSION_MISSING` / `SPOTIFY_SESSION_INVALID` (log in again)
- `NO_ACTIVE_DEVICE` (409: open Spotify on any device and play something once)
- `RATE_LIMITED` (429 + `Retry-After`)

The app shows the server's message. It never retries playback commands automatically, and requests time out after 15 s.

### Exposing it to the phone

The app only allows **HTTPS** (Android blocks cleartext HTTP by default), so put the backend behind TLS:

- **Tailscale Serve (recommended, private):** `sudo tailscale serve --bg --https=443 http://127.0.0.1:8080`. The URL is `https://<machine>.<tailnet>.ts.net/api/v1`, and the phone must be on the same tailnet.
- **Tailscale Funnel / Cloudflare Tunnel / a reverse proxy with Let's Encrypt:** these let you reach it without a VPN on the phone. The endpoint is then **public**, so the bearer token is the only protection. Use a long random `APP_TOKEN`, keep `PAIRING_CODE` empty when you're not pairing, and rotate the token if it leaks.

### Connecting the app

1. Settings → **Spotify** → source **spotapi** → **Base URL** `https://your-host/api/v1`.
2. Authenticate the app in one of two ways:
   - **Pairing code:** set `PAIRING_CODE` on the server, enter it in the app (`POST /auth/pair`), then clear it on the server.
   - **Static token:** paste `APP_TOKEN` directly into the app on the phone. Don't send it over chat or screenshots.
3. **Connect Spotify account:** log in at `accounts.spotify.com` inside an in-app WebView. The `sp_dc` (and `sp_key` / `sp_t`) cookies are sent **once** to `POST /spotify/session` and are not stored by the app; WebView cookies are cleared before and after. The app never asks for your Spotify password itself.
4. Open Spotify on any device and play something once, so a Connect device is active.

If Official Spotify is also connected, it is used as a fallback when the spotapi session is rejected.
