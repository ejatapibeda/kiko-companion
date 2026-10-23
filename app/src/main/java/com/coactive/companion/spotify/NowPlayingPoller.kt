package com.coactive.companion.spotify

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/** Di mana pengguna berada → menentukan laju polling now-playing. */
enum class PollVisibility { BACKGROUND, FOREGROUND, PLAYER }

/**
 * Kebijakan polling (murni, mudah diuji):
 *  - PLAYER (layar pemutar terlihat): tiap 5 dtk
 *  - FOREGROUND (aplikasi terbuka, bukan layar pemutar): tiap 20 dtk (rentang 15–30)
 *  - BACKGROUND: berhenti total (null)
 *  - 429: ikuti Retry-After; tanpa header → backoff eksponensial 10, 20, 40 … maks 120 dtk
 *  - Error yang butuh tindakan pengguna (belum login/sesi/dll.): berhenti sampai ada pemicu (null)
 */
object PollPolicy {
    const val PLAYER_MS = 5_000L
    const val FOREGROUND_MS = 20_000L
    const val AFTER_ACTION_MS = 1_200L
    const val BACKOFF_START_MS = 10_000L
    const val BACKOFF_MAX_MS = 120_000L

    private val STOP_ON = setOf(SpotifyError.NOT_SUPPORTED, SpotifyError.NOT_CONFIGURED, SpotifyError.NOT_CONNECTED,
        SpotifyError.UNAUTHORIZED, SpotifyError.SESSION_INVALID, SpotifyError.SESSION_MISSING)

    fun baseInterval(v: PollVisibility): Long? = when (v) {
        PollVisibility.PLAYER -> PLAYER_MS
        PollVisibility.FOREGROUND -> FOREGROUND_MS
        PollVisibility.BACKGROUND -> null
    }

    /** @return (delayMs atau null = berhenti menunggu pemicu, backoff berikutnya). */
    fun next(v: PollVisibility, last: SpotifyResult<*>?, backoffMs: Long): Pair<Long?, Long> {
        val base = baseInterval(v) ?: return null to backoffMs
        if (last !is SpotifyResult.Failure) return base to 0L
        if (last.error in STOP_ON) return null to 0L
        if (last.error == SpotifyError.RATE_LIMITED) {
            last.retryAfterSec?.let { return maxOf(base, it * 1000) to 0L }
            val b = if (backoffMs <= 0) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
            return maxOf(base, b) to b
        }
        return base to 0L
    }
}

/**
 * Menjalankan polling now-playing sesuai [PollPolicy]. Satu coroutine; berhenti di background.
 * [onActionPerformed] → SATU kali refresh ~1,2 dtk kemudian (tanpa polling cepat).
 */
class NowPlayingPoller(
    private val scope: CoroutineScope,
    private val fetch: suspend () -> SpotifyResult<NowPlaying?>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Batas waktu dari 429 — dihormati juga setelah kembali dari background. */
    private var notBefore = 0L
    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()
    private val _lastError = MutableStateFlow<SpotifyResult.Failure?>(null)
    val lastError: StateFlow<SpotifyResult.Failure?> = _lastError.asStateFlow()

    private var visibility = PollVisibility.BACKGROUND
    private val wake = Channel<Long>(Channel.CONFLATED) // nilai = jeda sebelum refresh
    private var job: Job? = null
    @Volatile var fetchCount = 0; private set

    fun setVisibility(v: PollVisibility) {
        if (v == visibility) return
        val wasBackground = visibility == PollVisibility.BACKGROUND
        visibility = v
        if (v == PollVisibility.BACKGROUND) { job?.cancel(); job = null; return }
        ensureRunning()
        wake.trySend(if (wasBackground || v == PollVisibility.PLAYER) 0L else -1L) // -1 = jadwal ulang saja
    }

    /** Setelah aksi pemutaran: satu refresh tertunda. */
    fun onActionPerformed() {
        if (visibility == PollVisibility.BACKGROUND) return
        ensureRunning(); wake.trySend(PollPolicy.AFTER_ACTION_MS)
    }

    /** Pengaturan berubah (mis. mode/sumber) → refresh segera bila di foreground. */
    fun refreshNow() {
        if (visibility == PollVisibility.BACKGROUND) return
        ensureRunning(); wake.trySend(0L)
    }

    fun clear() { _state.value = null; _lastError.value = null }

    private fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun loop() {
        var backoff = 0L
        var pending: Long? = 0L // refresh pertama segera
        while (true) {
            // tunggu: jadwal reguler ATAU pemicu (aksi / perubahan visibilitas)
            val wait = pending
            val trigger: Long? = if (wait == null) wake.receive() else select {
                wake.onReceive { it }
                onTimeout(wait) { null }
            }
            if (trigger != null && trigger > 0) delay(trigger)
            if (trigger == -1L) { pending = PollPolicy.next(visibility, _lastError.value, backoff).first; continue }
            if (visibility == PollVisibility.BACKGROUND) return
            (notBefore - clock()).takeIf { it > 0 }?.let { delay(it) }
            fetchCount++
            val r = runCatching { fetch() }.getOrElse { SpotifyResult.Failure(SpotifyError.UNKNOWN) }
            when (r) {
                is SpotifyResult.Ok -> { _state.value = r.value; _lastError.value = null }
                is SpotifyResult.Failure -> {
                    _lastError.value = r
                    if (r.error == SpotifyError.NOT_SUPPORTED || r.error == SpotifyError.NOT_CONNECTED || r.error == SpotifyError.NOT_CONFIGURED) _state.value = null
                }
            }
            val (d, b) = PollPolicy.next(visibility, r, backoff)
            backoff = b; pending = d
            notBefore = if (r is SpotifyResult.Failure && r.error == SpotifyError.RATE_LIMITED && d != null) clock() + d else 0L
        }
    }
}
