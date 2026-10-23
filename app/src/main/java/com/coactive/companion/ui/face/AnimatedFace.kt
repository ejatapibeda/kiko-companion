package com.coactive.companion.ui.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.coactive.companion.BuildConfig
import com.coactive.companion.settings.ListeningStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Wajah beranimasi:
 * - transisi ekspresi dengan spring (halus, tanpa patah),
 * - kedip acak (kadang dua kali, kadang sambil melirik),
 * - lirikan mata alami ([GazePlanner]) dengan spring + micro-drift pupil,
 * - kilau mata tertinggal sedikit (parallax) agar terasa seperti bola mata,
 * - perilaku per mode: mendengarkan = menatap & sedikit melebar, berpikir = menengadah, bicara = gerak kecil,
 * - kilau putih mata mengikuti arah pandang (spring, selalu di dalam bola mata — [Catchlight]),
 * - mode joget ([dancing], [DanceMotion]) saat musik diputar: masuk/keluar halus, reaksi sentuhan tetap jalan.
 * Animasi hanya berjalan saat ada perubahan (tidak ada loop 60 fps terus-menerus saat diam).
 */
@Composable
fun AnimatedFace(
    expression: Expression,
    colors: FaceColors,
    modifier: Modifier = Modifier,
    talking: Boolean = false,
    talkLevel: Float = 0f,
    listening: Boolean = false,
    listeningStyle: ListeningStyle = ListeningStyle.BARS,
    micLevel: Float = 0f,
    darkGlow: Boolean = false,
    reaction: ReactionEvent? = null,
    /** Condong mata mengikuti gravitasi (-1..1, dari akselerometer). */
    lean: Float = 0f,
    /** Joget mengikuti ketukan (pemanggil sudah memastikan fase idle; lihat assistant/DanceMode). */
    dancing: Boolean = false,
    danceBpm: Float = DanceMotion.DEFAULT_BPM,
    /** Reaksi cuaca sesekali (bersin/menggigil/kepanasan); hanya diputar saat idle, lihat [WeatherAnim]. */
    weatherEvent: WeatherEvent? = null,
    /** Pola lirikan khusus saat memakai tool (mis. [GazeMode.SCANNING] saat mencari web). */
    gazeOverride: GazeMode? = null,
) {
    val sleepy = expression == Expression.SLEEPY
    val mode = when {
        sleepy -> GazeMode.SLEEPING
        listening -> GazeMode.LISTENING
        gazeOverride != null -> gazeOverride
        expression == Expression.THINKING -> GazeMode.THINKING
        talking -> GazeMode.TALKING
        else -> GazeMode.IDLE
    }

    // ---- transisi ekspresi (spring) ----
    var from by remember { mutableStateOf(FaceParams.of(expression)) }
    var to by remember { mutableStateOf(FaceParams.of(expression)) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(expression) {
        if (BuildConfig.DEBUG) android.util.Log.d("CompanionFace", "expression=$expression")
        from = from.lerp(to, progress.value)
        to = FaceParams.of(expression)
        progress.snapTo(0f)
        progress.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 220f))
    }

    // ---- kedip ----
    val blink = remember { Animatable(0f) }
    val blinkLock = remember { Mutex() }
    suspend fun doBlink() = blinkLock.withLock {
        blink.animateTo(1f, tween(75))
        blink.animateTo(0f, tween(120))
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2400, 5600))
            val times = if (Random.nextFloat() < 0.2f) 2 else 1
            if (BuildConfig.DEBUG) android.util.Log.d("CompanionFace", "blink x$times")
            repeat(times) { doBlink(); delay(90) }
        }
    }

    // ---- lirikan (gaze) dengan spring ----
    val gazeX = remember { Animatable(0f) }
    val gazeY = remember { Animatable(0f) }
    LaunchedEffect(mode) {
        while (true) {
            val step = GazePlanner.next(mode, gazeX.value, gazeY.value)
            val spec = spring<Float>(dampingRatio = 0.84f, stiffness = GazePlanner.stiffnessFor(step.moveMs))
            coroutineScope {
                if (step.blink) launch { delay(40); doBlink() }
                launch { gazeX.animateTo(step.x, spec) }
                launch { delay(25); gazeY.animateTo(step.y, spec) } // sumbu Y sedikit tertinggal → lintasan melengkung
            }
            delay(step.holdMs.toLong())
        }
    }

    // ---- micro-drift pupil (sangat kecil, pelan) ----
    val microX = remember { Animatable(0f) }
    val microY = remember { Animatable(0f) }
    LaunchedEffect(sleepy) {
        if (sleepy) { microX.animateTo(0f); microY.animateTo(0f); return@LaunchedEffect }
        val spec = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessVeryLow)
        while (true) {
            delay(Random.nextLong(500, 1300))
            launch { microX.animateTo(Random.nextFloat() * 0.07f - 0.035f, spec) }
            microY.animateTo(Random.nextFloat() * 0.05f - 0.025f, spec)
        }
    }

    // ---- reaksi sentuhan/goyangan ----
    var activeReaction by remember { mutableStateOf<Reaction?>(null) }
    val reactT = remember { Animatable(1f) }
    LaunchedEffect(reaction?.id) {
        val ev = reaction ?: return@LaunchedEffect
        if (BuildConfig.DEBUG) android.util.Log.d("CompanionFace", "reaction=${ev.reaction}")
        activeReaction = ev.reaction
        reactT.snapTo(0f)
        reactT.animateTo(1f, tween(ev.reaction.durationMs, easing = LinearEasing))
        activeReaction = null
    }

    // ---- reaksi cuaca: sekali jalan, hanya saat idle netral (tidak menimpa bicara/dengar/joget/reaksi sentuhan) ----
    var activeWeather by remember { mutableStateOf<WeatherAct?>(null) }
    val weatherT = remember { Animatable(0f) }
    var playedWeatherId by remember { mutableStateOf(-1L) }
    val weatherAllowed = mode == GazeMode.IDLE && expression == Expression.NEUTRAL && !dancing
    LaunchedEffect(weatherEvent?.id, weatherAllowed) {
        if (!weatherAllowed) { activeWeather = null; return@LaunchedEffect }
        val ev = weatherEvent ?: return@LaunchedEffect
        if (ev.id == playedWeatherId) return@LaunchedEffect
        playedWeatherId = ev.id
        if (android.os.SystemClock.uptimeMillis() - ev.at > 5_000) return@LaunchedEffect // basi (tertahan saat sibuk) → lewati
        if (activeReaction != null) return@LaunchedEffect // sedang disentuh → lewati saja
        if (BuildConfig.DEBUG) android.util.Log.d("CompanionFace", "weather=${ev.act} hold=${ev.holdAt}")
        activeWeather = ev.act
        weatherT.snapTo(0f)
        val hold = ev.holdAt
        if (hold != null) {
            weatherT.animateTo(hold.coerceIn(0f, 1f), tween((ev.act.durationMs * hold).toInt().coerceAtLeast(1), easing = LinearEasing))
            return@LaunchedEffect // debug: tetap beku di pose ini sampai kejadian berikutnya
        }
        weatherT.animateTo(1f, tween(ev.act.durationMs, easing = LinearEasing))
        activeWeather = null
    }

    // ---- variasi mulut saat idle (hanya wajah netral, tanpa reaksi aktif) ----
    var quirk by remember { mutableStateOf<IdleQuirk?>(null) }
    val quirkAmt = remember { Animatable(0f) }
    val quirkAllowed = mode == GazeMode.IDLE && expression == Expression.NEUTRAL && activeReaction == null && activeWeather == null && !dancing
    LaunchedEffect(quirkAllowed) {
        if (!quirkAllowed) { quirkAmt.animateTo(0f, spring(dampingRatio = 1f, stiffness = 300f)); return@LaunchedEffect }
        var last = quirk
        while (true) {
            val step = IdleMouthPlanner.next(last)
            delay(step.waitMs.toLong())
            quirk = step.quirk; last = step.quirk
            if (BuildConfig.DEBUG) android.util.Log.d("CompanionFace", "quirk=${step.quirk} glance=${step.withGlance}")
            if (step.withGlance) launch {
                val gs = spring<Float>(dampingRatio = 0.84f, stiffness = GazePlanner.stiffnessFor(480))
                val side = if (Random.nextBoolean()) 1f else -1f
                launch { gazeX.animateTo(side * (0.35f + Random.nextFloat() * 0.25f), gs) }
                gazeY.animateTo(-0.1f - Random.nextFloat() * 0.15f, gs)
            }
            quirkAmt.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 140f))
            delay(step.holdMs.toLong())
            quirkAmt.animateTo(0f, spring(dampingRatio = 1f, stiffness = 90f))
        }
    }
    val leanAnim by animateFloatAsState(if (sleepy) 0f else lean, spring(dampingRatio = 0.9f, stiffness = 60f), label = "lean")

    // ---- fase untuk efek mendengarkan / zZz (hanya saat perlu) ----
    val phaseAnim = remember { Animatable(0f) }
    LaunchedEffect(listening || sleepy) {
        if (listening || sleepy) while (true) { phaseAnim.snapTo(0f); phaseAnim.animateTo(1f, tween(1600, easing = LinearEasing)) }
        else phaseAnim.snapTo(0f)
    }
    val phase = phaseAnim.value
    val breathe = if (sleepy) kotlin.math.sin(phase * 2 * Math.PI).toFloat() else 0f
    // mulut "Ooo… Ooo…" saat tidur: siklus 3.2 dtk (2× zZz), hanya berjalan saat SLEEPY
    val snoreAnim = remember { Animatable(0f) }
    LaunchedEffect(sleepy) {
        if (sleepy) while (true) { snoreAnim.snapTo(0f); snoreAnim.animateTo(1f, tween(SleepMouth.CYCLE_MS, easing = LinearEasing)) }
        else snoreAnim.snapTo(0f)
    }
    val listenAmt by animateFloatAsState(if (listening) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 180f), label = "listen")
    val level by rememberUpdatedState(talkLevel)

    // ---- joget: intensitas (spring masuk/keluar) + jam ketukan (hanya berjalan saat perlu) ----
    val danceAmt = remember { Animatable(0f) }
    LaunchedEffect(dancing) {
        if (BuildConfig.DEBUG) android.util.Log.d("CompanionFace", "dancing=$dancing")
        danceAmt.animateTo(if (dancing) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = if (dancing) 90f else 160f))
    }
    var beats by remember { mutableFloatStateOf(0f) }
    val danceRunning = dancing || danceAmt.value > 0.001f
    LaunchedEffect(danceRunning, danceBpm) {
        if (!danceRunning) return@LaunchedEffect
        val bpm = danceBpm.takeIf { it.isFinite() && it in 40f..220f } ?: DanceMotion.DEFAULT_BPM
        val b0 = beats
        val start = withFrameMillis { it }
        while (true) withFrameMillis { ms -> beats = (b0 + (ms - start) * bpm / 60_000f) % DanceMotion.CYCLE_BEATS }
    }
    val dAmt = danceAmt.value.clampUnit()

    // ---- komposisi parameter ----
    var p = from.lerp(to, progress.value)
    quirk?.let { p = IdleMouthPlanner.apply(p, it, quirkAmt.value) }
    val calm = 1f - 0.75f * dAmt // lirikan acak diredam saat joget (mata ikut irama)
    val lx = (gazeX.value + microX.value) * calm + leanAnim * 0.3f
    val ly = (gazeY.value + microY.value) * calm + (if (sleepy) breathe * 0.04f else 0f) - (if (talking) level * 0.04f else 0f)
    val widen = 1f + 0.07f * listenAmt
    p = p.copy(
        lookX = p.lookX + lx,
        lookY = p.lookY + ly,
        scaleL = p.scaleL * widen,
        scaleR = p.scaleR * widen,
    )
    // tidur: mulut berganti ke "O" yang bernapas, dicampur sesuai transisi ke SLEEPY agar masuk/keluar mulus
    if (sleepy) p = p.copy(mouth = p.mouth.lerp(SleepMouth.shape(snoreAnim.value), progress.value.clampUnit()))
    if (dAmt > 0f) p = DanceMotion.apply(p, DanceMotion.frame(beats, dAmt))
    activeWeather?.let { p = WeatherAnim.apply(p, it, weatherT.value) }
    activeReaction?.let { p = it.apply(p, reactT.value) }
    // kilau mengikuti arah pandang akhir dengan spring (sedikit tertinggal → terasa hidup); + ayunan ketukan joget
    val glintX by animateFloatAsState(Catchlight.glintFor(p.lookX), spring(dampingRatio = 0.8f, stiffness = 260f), label = "glintX")
    val glintY by animateFloatAsState(Catchlight.glintFor(p.lookY), spring(dampingRatio = 0.8f, stiffness = 260f), label = "glintY")
    p = p.copy(glintX = glintX + p.glintX, glintY = glintY + p.glintY)
    if (talking) p = p.withTalkLevel(talkLevel)
    if (!sleepy) p = p.withBlink(blink.value)

    FaceCanvas(p, colors, modifier, listenAmt, listeningStyle, phase, micLevel, darkGlow, danceBeats = beats, danceNotes = dAmt)
}
