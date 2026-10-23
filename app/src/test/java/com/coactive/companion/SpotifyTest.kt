package com.coactive.companion

import com.coactive.companion.settings.AppSettings
import com.coactive.companion.settings.SpotifySource
import com.coactive.companion.spotify.ErrorCode
import com.coactive.companion.spotify.FallbackSpotifyController
import com.coactive.companion.spotify.NowPlaying
import com.coactive.companion.spotify.NowPlayingPoller
import com.coactive.companion.spotify.ObservedSpotifyController
import com.coactive.companion.spotify.OfficialSpotifyController
import com.coactive.companion.spotify.Pkce
import com.coactive.companion.spotify.PollPolicy
import com.coactive.companion.spotify.PollVisibility
import com.coactive.companion.spotify.SessionCookies
import com.coactive.companion.spotify.SpotapiClient
import com.coactive.companion.spotify.SpotapiException
import com.coactive.companion.spotify.SpotapiSpotifyController
import com.coactive.companion.spotify.SpotifyAction
import com.coactive.companion.spotify.SpotifyAuth
import com.coactive.companion.spotify.SpotifyController
import com.coactive.companion.spotify.SpotifyControllerFactory
import com.coactive.companion.spotify.SpotifyError
import com.coactive.companion.spotify.SpotifyResult
import com.coactive.companion.spotify.SpotifyTokenStore
import com.coactive.companion.spotify.SpotifyTrack
import com.coactive.companion.spotify.SpotifyWebLoginActivity
import com.coactive.companion.spotify.toToolJson
import com.coactive.companion.tools.SpotifyControlTool
import com.coactive.companion.tools.SpotifyPlayTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

private fun json(code: Int = 200, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

private class Fake(override val source: SpotifySource, var result: SpotifyResult<SpotifyAction> = SpotifyResult.Ok(SpotifyAction("fake"))) : SpotifyController {
    var calls = 0
    private fun r(): SpotifyResult<SpotifyAction> { calls++; return result }
    override suspend fun pause() = r()
    override suspend fun resume() = r()
    override suspend fun next() = r()
    override suspend fun previous() = r()
    override suspend fun playSearch(query: String, type: String) = r()
    override suspend fun nowPlaying(): SpotifyResult<NowPlaying?> = SpotifyResult.Ok(null)
}

class SpotifyFactoryTest {
    private val local = Fake(SpotifySource.LOCAL)
    private val official = Fake(SpotifySource.OFFICIAL)
    private val spotapi = Fake(SpotifySource.SPOTAPI)
    private fun pick(src: SpotifySource, officialConnected: Boolean = false, ready: Boolean = false, clientId: String = "cid") =
        SpotifyControllerFactory.create(AppSettings(spotifySource = src, spotifyClientId = clientId), officialConnected, ready, { local }, { official }, { spotapi })

    @Test fun defaultIsLocal() = assertEquals(SpotifySource.LOCAL, AppSettings().spotifySource)

    @Test fun picksImplementationByMode() {
        assertSame(local, pick(SpotifySource.LOCAL, officialConnected = true, ready = true))
        assertSame(official, pick(SpotifySource.OFFICIAL, officialConnected = true))
        assertSame(local, pick(SpotifySource.OFFICIAL, officialConnected = false)) // belum login → lokal
        assertSame(local, pick(SpotifySource.OFFICIAL, officialConnected = true, clientId = ""))
        assertSame(spotapi, pick(SpotifySource.SPOTAPI, ready = true))
        assertTrue(pick(SpotifySource.SPOTAPI, officialConnected = true, ready = true) is FallbackSpotifyController)
        assertSame(official, pick(SpotifySource.SPOTAPI, officialConnected = true, ready = false))
        assertSame(local, pick(SpotifySource.SPOTAPI))
    }

    @Test fun fallbackOnlyOnAuthOrSessionErrors() = runBlocking {
        spotapi.result = SpotifyResult.Failure(SpotifyError.SESSION_INVALID)
        val c = FallbackSpotifyController(spotapi, official)
        assertTrue(c.next() is SpotifyResult.Ok); assertEquals(1, official.calls)
        spotapi.result = SpotifyResult.Failure(SpotifyError.NO_ACTIVE_DEVICE) // aksi mungkin relevan → jangan alihkan
        assertEquals(SpotifyError.NO_ACTIVE_DEVICE, (c.next() as SpotifyResult.Failure).error); assertEquals(1, official.calls)
    }

    @Test fun observedReportsNoDeviceWithRetryAndActionDone() = runBlocking {
        val f = Fake(SpotifySource.SPOTAPI, SpotifyResult.Failure(SpotifyError.NO_ACTIVE_DEVICE))
        var done = 0; var retry: (suspend () -> SpotifyResult<SpotifyAction>)? = null
        val c = ObservedSpotifyController(f, { done++ }, { retry = it })
        c.playSearch("tulus", "track")
        assertEquals(0, done); assertTrue(retry != null)
        f.result = SpotifyResult.Ok(SpotifyAction("fake"))
        assertTrue(retry!!() is SpotifyResult.Ok); assertEquals(1, done); assertEquals(2, f.calls)
    }

    @Test fun toolsCallTheInterface() = runBlocking {
        val f = Fake(SpotifySource.LOCAL, SpotifyResult.Ok(SpotifyAction("fake", SpotifyTrack("Hati-Hati di Jalan", listOf("Tulus")))))
        val out = obj(SpotifyPlayTool { f }.execute(obj("""{"query":"hati hati di jalan"}""")))
        assertEquals("Hati-Hati di Jalan", out["playing"]!!.jsonPrimitive.content)
        f.result = SpotifyResult.Failure(SpotifyError.NO_ACTIVE_DEVICE)
        val err = obj(SpotifyControlTool { f }.execute(obj("""{"action":"next"}""")))
        assertEquals("NO_ACTIVE_DEVICE", err["error"]!!.jsonPrimitive.content)
        assertTrue(err["message"]!!.jsonPrimitive.content.contains("Spotify"))
    }
}

private class MemStore : SpotifyTokenStore {
    override var spotifyAccessToken = ""; override var spotifyRefreshToken = ""; override var spotifyExpiresAt = 0L
    override var spotifyPkceVerifier = ""; override var spotifyOAuthState = ""
}

class SpotifyOfficialTest {
    private lateinit var server: MockWebServer
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() = server.shutdown()
    private fun api(token: String? = "tok") = OfficialSpotifyController({ token }, apiBase = server.url("/v1").toString())

    @Test fun pkceChallengeAndAuthorizeUrl() {
        // RFC 7636 Lampiran B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url = SpotifyAuth.buildAuthorizeUrl("https://accounts.spotify.com", "cid", "chal", "st8").toHttpUrl()
        assertEquals("/authorize", url.encodedPath)
        assertEquals("cid", url.queryParameter("client_id")); assertEquals("code", url.queryParameter("response_type"))
        assertEquals("com.coactive.companion://spotify-callback", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method")); assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("st8", url.queryParameter("state"))
        assertEquals(setOf("user-modify-playback-state", "user-read-playback-state", "streaming"), url.queryParameter("scope")!!.split(" ").toSet())
    }

    @Test fun codeExchangeAndRefresh() = runBlocking {
        var now = 1_000_000L
        val store = MemStore()
        val auth = SpotifyAuth(store, accountsBase = server.url("/").toString(), now = { now })
        auth.authorizeUrl("cid")
        val verifier = store.spotifyPkceVerifier
        assertFalse(auth.exchangeCode("CODE", "wrong-state", "cid")) // CSRF
        server.enqueue(json(body = """{"access_token":"A1","refresh_token":"R1","expires_in":3600}"""))
        assertTrue(auth.exchangeCode("CODE", store.spotifyOAuthState, "cid"))
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("grant_type=authorization_code") && form.contains("code=CODE") && form.contains("code_verifier=$verifier"))
        assertTrue(form.contains("redirect_uri=com.coactive.companion%3A%2F%2Fspotify-callback"))
        assertEquals("A1", auth.accessToken("cid")) // masih berlaku → tanpa request
        now += 3_600_000
        server.enqueue(json(body = """{"access_token":"A2","expires_in":3600}"""))
        assertEquals("A2", auth.accessToken("cid"))
        assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token&refresh_token=R1"))
        assertEquals("R1", store.spotifyRefreshToken) // tidak dirotasi → tetap
    }

    @Test fun controlRequests() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(204)) }
        val c = api()
        assertTrue(c.pause() is SpotifyResult.Ok); assertTrue(c.resume() is SpotifyResult.Ok)
        assertTrue(c.next() is SpotifyResult.Ok); assertTrue(c.previous() is SpotifyResult.Ok)
        listOf("PUT /v1/me/player/pause", "PUT /v1/me/player/play", "POST /v1/me/player/next", "POST /v1/me/player/previous").forEach {
            val r = server.takeRequest()
            assertEquals(it, "${r.method} ${r.path}"); assertEquals("Bearer tok", r.getHeader("Authorization"))
        }
    }

    @Test fun searchAndPlayTrackOnActiveDevice() = runBlocking {
        server.enqueue(json(body = """{"tracks":{"items":[{"uri":"spotify:track:abc","name":"Sempurna","artists":[{"name":"Andra and The Backbone"}]}]}}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val r = api().playSearch("sempurna andra", "track") as SpotifyResult.Ok
        assertEquals("Sempurna", r.value.track!!.title); assertEquals(listOf("Andra and The Backbone"), r.value.track!!.artists)
        val search = server.takeRequest().requestUrl!!
        assertEquals("/v1/search", search.encodedPath); assertEquals("sempurna andra", search.queryParameter("q"))
        assertEquals("track", search.queryParameter("type")); assertEquals("1", search.queryParameter("limit"))
        val play = server.takeRequest()
        assertEquals("PUT /v1/me/player/play", "${play.method} ${play.path}")
        assertEquals("""{"uris":["spotify:track:abc"]}""", play.body.readUtf8())
        assertEquals("""{"context_uri":"spotify:playlist:p"}""", OfficialSpotifyController.playBody("playlist", "spotify:playlist:p"))
    }

    @Test fun noActiveDeviceFallsBackToAvailableDeviceThenFriendlyError() = runBlocking {
        val hit = """{"tracks":{"items":[{"uri":"spotify:track:abc","name":"X","artists":[]}]}}"""
        server.enqueue(json(body = hit)); server.enqueue(json(404, """{"error":{"status":404,"message":"Player command failed: No active device found","reason":"NO_ACTIVE_DEVICE"}}"""))
        server.enqueue(json(body = """{"devices":[{"id":"pc","type":"Computer","is_active":false},{"id":"hp","type":"Smartphone","is_active":false}]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api().playSearch("x", "track") is SpotifyResult.Ok)
        repeat(3) { server.takeRequest() }
        assertEquals("/v1/me/player/play?device_id=hp", server.takeRequest().path)

        server.enqueue(json(body = hit)); server.enqueue(json(404, "{}")); server.enqueue(json(body = """{"devices":[]}"""))
        val f = api().playSearch("x", "track") as SpotifyResult.Failure
        assertEquals(SpotifyError.NO_ACTIVE_DEVICE, f.error); assertTrue(f.message.contains("Buka aplikasi Spotify"))
        server.enqueue(json(404, "{}"))
        assertEquals(SpotifyError.NO_ACTIVE_DEVICE, (api().pause() as SpotifyResult.Failure).error)
    }

    @Test fun nowPlayingAndErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertNull((api().nowPlaying() as SpotifyResult.Ok).value)
        server.enqueue(json(body = """{"is_playing":true,"progress_ms":5000,"device":{"id":"d1","name":"Pixel"},
            "item":{"name":"Monokrom","uri":"spotify:track:m","duration_ms":200000,"artists":[{"name":"Tulus"}],"album":{"name":"Monokrom","images":[{"url":"https://i/x.jpg"}]}}}"""))
        val np = (api().nowPlaying() as SpotifyResult.Ok).value!!
        assertEquals("Monokrom", np.track!!.title); assertEquals("Pixel", np.deviceName); assertEquals(5000L, np.positionMs)
        assertEquals(SpotifyError.NOT_CONNECTED, (api(token = null).pause() as SpotifyResult.Failure).error)
        server.enqueue(json(429, "{}").setHeader("Retry-After", "7"))
        val rl = api().next() as SpotifyResult.Failure
        assertEquals(SpotifyError.RATE_LIMITED, rl.error); assertEquals(7L, rl.retryAfterSec)
    }
}

class SpotapiClientTest {
    private lateinit var server: MockWebServer
    private var token = "sk_live_abc"
    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() = server.shutdown()
    private fun client() = SpotapiClient({ server.url("/api/v1").toString() }, { token })
    private fun ctl() = SpotapiSpotifyController(client())
    private fun err(status: Int, code: String, msg: String = "", retryable: Boolean = false) =
        json(status, """{"ok":false,"error":{"code":"$code","message":"$msg","retryable":$retryable}}""")

    @Test fun pairWithoutAuthHeader() = runBlocking {
        server.enqueue(json(body = """{"app_token":"sk_live_new","expires_at":null}"""))
        val r = client().pair(" 483-219 ", "Galaxy S22")
        assertEquals("sk_live_new", r.appToken); assertNull(r.expiresAt)
        val req = server.takeRequest()
        assertEquals("POST /api/v1/auth/pair", "${req.method} ${req.path}"); assertNull(req.getHeader("Authorization"))
        assertEquals(obj("""{"pairing_code":"483-219","device_name":"Galaxy S22"}"""), obj(req.body.readUtf8()))
        server.enqueue(err(401, "UNAUTHORIZED", "Kode pairing salah"))
        val e = runCatching { client().pair("000", "x") }.exceptionOrNull() as SpotapiException
        assertEquals(ErrorCode.UNAUTHORIZED, e.code); assertEquals("Kode pairing salah", e.serverMessage)
    }

    @Test fun spotifySessionLifecycle() = runBlocking {
        server.enqueue(json(body = """{"connected":true,"username":"31n3nix","display_name":"Ejs","product":"FREE","checked_at":"2026-10-22T09:00:00Z"}"""))
        val s = client().createSession(SessionCookies("DC", spKey = "KEY"))
        assertTrue(s.connected); assertEquals("FREE", s.product); assertEquals("Ejs", s.displayName)
        val req = server.takeRequest()
        assertEquals("POST /api/v1/spotify/session", "${req.method} ${req.path}"); assertEquals("Bearer sk_live_abc", req.getHeader("Authorization"))
        val cookies = obj(req.body.readUtf8())["cookies"]!!.jsonObject
        assertEquals("DC", cookies["sp_dc"]!!.jsonPrimitive.content); assertEquals("KEY", cookies["sp_key"]!!.jsonPrimitive.content)
        assertFalse(cookies.containsKey("sp_t")) // null tidak dikirim

        server.enqueue(json(body = """{"connected":false}"""))
        assertFalse(client().sessionStatus().connected); assertEquals("GET", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(204))
        client().deleteSession(); assertEquals("DELETE /api/v1/spotify/session", server.takeRequest().let { "${it.method} ${it.path}" })
        server.enqueue(err(400, "INVALID_COOKIES", ""))
        val e = runCatching { client().createSession(SessionCookies("bad")) }.exceptionOrNull() as SpotapiException
        val f = SpotapiClient.toFailure(e)
        assertEquals(SpotifyError.SESSION_INVALID, f.error); assertTrue(f.message.contains("Hubungkan akun Spotify")) // pesan cadangan
    }

    @Test fun playSuccessAndErrors() = runBlocking {
        server.enqueue(json(body = """{"ok":true,"track":{"uri":"spotify:track:20jb","title":"hate that i made you love me","artists":["Ariana Grande"]},"device_id":"dev1"}"""))
        val ok = ctl().playSearch("hate that i made you love me", "track") as SpotifyResult.Ok
        assertEquals("hate that i made you love me", ok.value.track!!.title); assertEquals(listOf("Ariana Grande"), ok.value.track!!.artists)
        val body = obj(server.takeRequest().body.readUtf8())
        assertEquals("hate that i made you love me", body["query"]!!.jsonPrimitive.content); assertEquals(0, body["index"]!!.jsonPrimitive.content.toInt())

        server.enqueue(json(body = """{"ok":true,"track":{"uri":"spotify:track:x","title":"X","artists":[]},"device_id":"d"}"""))
        ctl().playSearch("spotify:track:x", "track")
        val b2 = obj(server.takeRequest().body.readUtf8())
        assertEquals("spotify:track:x", b2["uri"]!!.jsonPrimitive.content); assertFalse(b2.containsKey("query"))

        server.enqueue(err(404, "NO_RESULTS", "Lagu tidak ditemukan"))
        val nr = ctl().playSearch("zzzz", "track") as SpotifyResult.Failure
        assertEquals(SpotifyError.NO_RESULTS, nr.error); assertEquals("Lagu tidak ditemukan", nr.message)

        server.enqueue(err(409, "NO_ACTIVE_DEVICE", "Buka Spotify di salah satu perangkat dulu"))
        val nd = ctl().playSearch("tulus", "track") as SpotifyResult.Failure
        assertEquals(SpotifyError.NO_ACTIVE_DEVICE, nd.error); assertEquals("Buka Spotify di salah satu perangkat dulu", nd.message)
        assertEquals(3 + 1, server.requestCount)
    }

    @Test fun commandsSendDeviceBodyAndNeverAutoRetry() = runBlocking {
        server.enqueue(json(body = """{"ok":true}"""))
        assertTrue(ctl().pause() is SpotifyResult.Ok)
        server.takeRequest().let { assertEquals("POST /api/v1/pause", "${it.method} ${it.path}"); assertEquals("{}", it.body.readUtf8()) }
        server.enqueue(json(body = """{"ok":true}"""))
        client().resume("dev9")
        assertEquals("""{"device_id":"dev9"}""", server.takeRequest().body.readUtf8())
        server.enqueue(err(502, "COMMAND_FAILED", "", retryable = true)); server.enqueue(json(body = """{"ok":true}"""))
        val f = ctl().next() as SpotifyResult.Failure
        assertEquals(SpotifyError.COMMAND_FAILED, f.error); assertTrue(f.retryable)
        assertEquals("/api/v1/next", server.takeRequest().path)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS)) // tidak ada retry otomatis
        assertEquals(3, server.requestCount)
    }

    @Test fun nowPlayingWithAndWithoutDevice() = runBlocking {
        server.enqueue(json(body = """{"is_playing":true,"is_paused":false,"track":{"uri":"spotify:track:m","title":"Monokrom","artists":["Tulus"],"album":"Monokrom","duration_ms":213000,"image_url":"https://i.scdn.co/x"},"position_ms":42000,"active_device_id":"dev1","device_name":"Galaxy S22"}"""))
        val np = (ctl().nowPlaying() as SpotifyResult.Ok).value!!
        assertTrue(np.isPlaying); assertEquals("Monokrom", np.track!!.title); assertEquals(213000L, np.track!!.durationMs)
        assertEquals("Galaxy S22", np.deviceName); assertEquals(42000L, np.positionMs)
        server.enqueue(json(body = """{"is_playing":false,"is_paused":null,"track":null,"position_ms":null,"active_device_id":null,"device_name":null}"""))
        assertNull((ctl().nowPlaying() as SpotifyResult.Ok).value) // bukan error
        assertEquals("GET", server.takeRequest().method)
    }

    @Test fun devicesHealthAndRateLimit() = runBlocking {
        server.enqueue(json(body = """{"active_device_id":"a","devices":[{"id":"a","name":"Web Player (Chrome)","type":"COMPUTER","is_active":true,"can_play":true,"volume_percent":65},{"id":"b","name":"TV","type":"TV","is_active":false,"can_play":false,"volume_percent":null}]}"""))
        val d = client().devices()
        assertEquals(65, d.devices[0].volumePercent); assertNull(d.devices[1].volumePercent); assertEquals("a", d.activeDeviceId)
        server.takeRequest()
        server.enqueue(json(body = """{"ok":true,"version":"1.0.0"}"""))
        assertEquals("1.0.0", client().health().version); assertNull(server.takeRequest().getHeader("Authorization"))
        server.enqueue(err(429, "RATE_LIMITED", "").setHeader("Retry-After", "12"))
        val rl = ctl().nowPlaying() as SpotifyResult.Failure
        assertEquals(SpotifyError.RATE_LIMITED, rl.error); assertEquals(12L, rl.retryAfterSec); assertTrue(rl.message.contains("12"))
    }

    @Test fun errorEnvelopeParsing() {
        ErrorCode.entries.forEach { code ->
            val e = SpotapiClient.parseError(400, """{"ok":false,"error":{"code":"${code.name}","message":"m","retryable":true}}""", null)
            assertEquals(code, e.code); assertTrue(e.retryable); assertEquals("m", e.serverMessage)
        }
        val unknown = SpotapiClient.parseError(418, """{"ok":false,"error":{"code":"TEAPOT","message":"","retryable":false}}""", null)
        assertNull(unknown.code); assertEquals("TEAPOT", unknown.rawCode)
        val html = SpotapiClient.parseError(503, "<html>Bad gateway</html>", null)
        assertNull(html.code); assertTrue(html.retryable); assertEquals(SpotifyError.UPSTREAM, SpotapiClient.toFailure(html).error)
        assertEquals(SpotifyError.SESSION_MISSING, SpotapiClient.toFailure(SpotapiClient.parseError(403, """{"ok":false,"error":{"code":"SPOTIFY_SESSION_MISSING","message":"","retryable":false}}""", null)).error)
        assertEquals(SpotifyError.UPSTREAM, SpotapiClient.toFailure(SpotapiClient.parseError(503, """{"ok":false,"error":{"code":"TOKEN_UNAVAILABLE","message":"","retryable":true}}""", null)).error)
    }

    @Test fun notConfiguredAndNetwork() = runBlocking {
        val noBase = SpotapiSpotifyController(SpotapiClient({ "" }, { "t" }))
        assertEquals(SpotifyError.NOT_CONFIGURED, (noBase.pause() as SpotifyResult.Failure).error)
        val dead = SpotapiSpotifyController(SpotapiClient({ "http://127.0.0.1:1/api/v1" }, { "t" }))
        assertEquals(SpotifyError.NETWORK, (dead.pause() as SpotifyResult.Failure).error)
        assertTrue(SpotifyResult.Failure(SpotifyError.NETWORK).toToolJson().contains("NETWORK"))
    }

    @Test fun webLoginCookieParsing() {
        val c = SpotifyWebLoginActivity.parseCookies("sp_t=T1; sp_landing=x; sp_dc=AQB123; sp_key=K9")!!
        assertEquals("AQB123", c.spDc); assertEquals("K9", c.spKey); assertEquals("T1", c.spT)
        assertNull(SpotifyWebLoginActivity.parseCookies("sp_t=T1; sp_landing=x"))
        assertNull(SpotifyWebLoginActivity.parseCookies(""))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingPollerTest {
    private val np = NowPlaying(true, false, SpotifyTrack("A", listOf("B")))

    @Test fun policyIntervals() {
        assertEquals(5_000L, PollPolicy.next(PollVisibility.PLAYER, SpotifyResult.Ok(np), 0).first)
        val fg = PollPolicy.next(PollVisibility.FOREGROUND, SpotifyResult.Ok(np), 0).first!!
        assertTrue(fg in 15_000L..30_000L)
        assertNull(PollPolicy.next(PollVisibility.BACKGROUND, SpotifyResult.Ok(np), 0).first)
        assertNull(PollPolicy.next(PollVisibility.PLAYER, SpotifyResult.Failure(SpotifyError.NOT_SUPPORTED), 0).first)
        assertEquals(30_000L, PollPolicy.next(PollVisibility.PLAYER, SpotifyResult.Failure(SpotifyError.RATE_LIMITED, retryAfterSec = 30), 0).first)
        // tanpa Retry-After: 10 → 20 → 40 … maks 120
        var b = 0L; val seq = mutableListOf<Long>()
        repeat(6) { val (d, nb) = PollPolicy.next(PollVisibility.PLAYER, SpotifyResult.Failure(SpotifyError.RATE_LIMITED), b); seq += d!!; b = nb }
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L), seq)
    }

    @Test fun pollsByVisibilityAndOneRefreshAfterAction() = runTest {
        var n = 0
        val p = NowPlayingPoller(backgroundScope, { n++; SpotifyResult.Ok(np) }, { testScheduler.currentTime })
        advanceTimeBy(60_000); assertEquals(0, n) // background: tidak ada polling
        p.setVisibility(PollVisibility.FOREGROUND); runCurrent(); assertEquals(1, n)
        advanceTimeBy(20_001); assertEquals(2, n)
        p.setVisibility(PollVisibility.PLAYER); runCurrent(); assertEquals(3, n)
        advanceTimeBy(5_001); assertEquals(4, n); advanceTimeBy(5_000); assertEquals(5, n)
        p.onActionPerformed(); advanceTimeBy(1_100); assertEquals(5, n); advanceTimeBy(200); assertEquals(6, n) // satu refresh ~1,2 dtk
        p.setVisibility(PollVisibility.BACKGROUND); advanceTimeBy(120_000); assertEquals(6, n) // berhenti total
        assertEquals("A", p.state.value!!.track!!.title)
    }

    @Test fun honorsRetryAfterAndBackoff() = runTest {
        val times = mutableListOf<Long>()
        var mode = 0
        val p = NowPlayingPoller(backgroundScope, {
            times += testScheduler.currentTime
            when (mode) { 0 -> SpotifyResult.Failure(SpotifyError.RATE_LIMITED, retryAfterSec = 30); else -> SpotifyResult.Failure(SpotifyError.RATE_LIMITED) }
        }, { testScheduler.currentTime })
        p.setVisibility(PollVisibility.PLAYER); runCurrent()
        advanceTimeBy(29_000); assertEquals(1, times.size)
        mode = 1; advanceTimeBy(1_500); assertEquals(2, times.size) // Retry-After 30 dtk dihormati
        advanceTimeBy(10_500); assertEquals(3, times.size) // backoff 10
        advanceTimeBy(20_500); assertEquals(4, times.size) // backoff 20
        assertEquals(listOf(0L, 30_000L, 40_000L, 60_000L), times)
    }

    @Test fun stopsOnUnsupportedUntilTriggered() = runTest {
        var n = 0
        val p = NowPlayingPoller(backgroundScope, { n++; SpotifyResult.Failure(SpotifyError.NOT_SUPPORTED) }, { testScheduler.currentTime })
        p.setVisibility(PollVisibility.PLAYER); runCurrent(); advanceTimeBy(60_000); assertEquals(1, n)
        p.refreshNow(); runCurrent(); assertEquals(2, n)
    }
}
