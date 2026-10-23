package com.coactive.companion.ui.face

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/* ------------------------------------------------------------------ */
/*  1) Variasi mulut saat idle                                         */
/* ------------------------------------------------------------------ */

/** Bentuk mulut "iseng" saat diam. [eyeOpen] < 1 = mata ikut menyipit (mis. menguap). */
enum class IdleQuirk(val label: String, val mouth: MouthShape, val eyeOpen: Float = 1f, val weight: Float = 1f) {
    SMALL_O("o kecil", MouthShape(0.62f, -0.27f, 0.27f)),
    BIG_O("O lebar", MouthShape(0.8f, -0.4f, 0.4f), weight = 0.6f),
    SMILE("senyum", MouthShape(1.2f, 0.04f, 0.74f)),
    POUT("cemberut", MouthShape(0.56f, -0.32f, -0.1f), weight = 0.8f),
    SQUIGGLE("berlekuk", MouthShape(0.95f, 0f, 0f, wave = 1f), weight = 0.8f),
    YAWN("menguap", MouthShape(0.9f, -0.62f, 0.6f), eyeOpen = 0.25f, weight = 0.25f),
}

data class QuirkStep(val quirk: IdleQuirk, val waitMs: Int, val holdMs: Int, val withGlance: Boolean)

object IdleMouthPlanner {
    /** Pilih quirk berikutnya (berbobot, tidak sama dengan sebelumnya). Jeda 5–12 s supaya tidak berlebihan. */
    fun next(last: IdleQuirk?, rnd: Random = Random): QuirkStep {
        val pool = IdleQuirk.entries.filter { it != last }
        var x = rnd.nextFloat() * pool.sumOf { it.weight.toDouble() }.toFloat()
        var q = pool.last()
        for (c in pool) { x -= c.weight; if (x <= 0f) { q = c; break } }
        val hold = if (q == IdleQuirk.YAWN) rnd.nextInt(1300, 1700) else rnd.nextInt(500, 1500)
        return QuirkStep(q, waitMs = rnd.nextInt(5000, 12001), holdMs = hold, withGlance = q != IdleQuirk.YAWN && rnd.nextFloat() < 0.35f)
    }

    /** Terapkan quirk ke parameter wajah dengan intensitas [amount] 0..1. */
    fun apply(p: FaceParams, q: IdleQuirk, amount: Float): FaceParams {
        if (amount <= 0.001f) return p
        val eye = 1f - (1f - q.eyeOpen) * amount
        val yawn = if (q == IdleQuirk.YAWN) amount else 0f
        return p.copy(
            mouth = p.mouth.lerp(q.mouth, amount),
            openL = p.openL * eye, openR = p.openR * eye,
            bounceY = p.bounceY - 0.12f * yawn, squash = p.squash + 0.05f * yawn,
        )
    }
}

/* ------------------------------------------------------------------ */
/*  2) Reaksi sentuhan / goyangan                                      */
/* ------------------------------------------------------------------ */

enum class Haptic { TICK, CLICK, LONG }

/**
 * Reaksi singkat. [pose] = target wajah; gerak badan (miring/loncat/squash/putar mata) dihitung [motion].
 * Semua gerak meluruh ke 0 di akhir sehingga kembali mulus ke idle.
 */
enum class Reaction(val durationMs: Int, val haptic: Haptic, val pose: FaceParams) {
    SURPRISED_POP(800, Haptic.TICK, FaceParams(scaleL = 1.22f, scaleR = 1.22f, lookY = -0.05f, mouth = MouthShape(0.8f, -0.42f, 0.42f))),
    GIGGLE(1000, Haptic.TICK, FaceParams(openL = 0f, openR = 0f, arcL = -1f, arcR = -1f, mouth = MouthShape(1.15f, 0.02f, 0.78f))),
    CURIOUS_TILT(1000, Haptic.TICK, FaceParams(scaleL = 0.94f, scaleR = 1.12f, lookX = 0.18f, lookY = -0.08f, mouth = MouthShape(0.5f, -0.24f, 0.24f))),
    HAPPY_BOUNCE(1200, Haptic.CLICK, FaceParams(openL = 0f, openR = 0f, arcL = -1f, arcR = -1f, sparkle = 1f, mouth = MouthShape(1.3f, 0f, 0.85f))),
    SHY(1500, Haptic.LONG, FaceParams(openL = 0f, openR = 0f, arcL = -0.6f, arcR = -0.6f, lookY = 0.15f, blush = 1f, mouth = MouthShape(0.6f, 0.04f, 0.32f))),
    TICKLISH(1400, Haptic.LONG, FaceParams(openL = 0.12f, openR = 0.12f, scaleL = 0.92f, scaleR = 0.92f, blush = 0.8f, mouth = MouthShape(1.2f, -0.05f, 0.8f))),
    DIZZY(1600, Haptic.CLICK, FaceParams(scaleL = 1.05f, scaleR = 0.9f, mouth = MouthShape(1.2f, 0f, 0f, wave = 1f))),
    ;

    data class Motion(val tilt: Float, val bounceY: Float, val squash: Float, val lookX: Float, val lookY: Float)

    /** Gerak badan pada waktu ternormalisasi [t] 0..1 (meluruh ke 0 di t = 1). */
    fun motion(t: Float): Motion {
        val tt = t.coerceIn(0f, 1f)
        val decay = 1f - tt
        fun osc(cycles: Float) = sin(tt * 2 * PI.toFloat() * cycles)
        return when (this) {
            SURPRISED_POP -> Motion(0f, -0.35f * sin(tt * PI.toFloat()) * decay, 0.08f * sin(tt * PI.toFloat()) * decay, 0f, 0f)
            GIGGLE -> Motion(5f * osc(3f) * decay, -0.12f * kotlin.math.abs(osc(3f)) * decay, 0f, 0f, 0f)
            CURIOUS_TILT -> Motion(9f * sin(tt * PI.toFloat()), 0f, 0f, 0f, 0f)
            HAPPY_BOUNCE -> Motion(2f * osc(2f) * decay, -0.5f * kotlin.math.abs(osc(1.5f)) * decay, -0.06f * osc(3f) * decay, 0f, 0f)
            SHY -> Motion(-6f * sin(tt * PI.toFloat()) + 1.5f * osc(2f) * decay, 0.1f * sin(tt * PI.toFloat()), -0.03f * sin(tt * PI.toFloat()), 0f, 0f)
            TICKLISH -> Motion(4f * osc(6f) * decay, -0.08f * kotlin.math.abs(osc(6f)) * decay, 0.04f * osc(6f) * decay, 0f, 0f)
            DIZZY -> Motion(6f * osc(2f) * decay, 0f, 0f,
                0.38f * cos(tt * 2 * PI.toFloat() * 2.5f) * decay, 0.26f * sin(tt * 2 * PI.toFloat() * 2.5f) * decay)
        }
    }

    /** Amplop campuran pose: naik cepat (15%), tahan, turun halus (35% terakhir). Smoothstep → tanpa patah. */
    fun envelope(t: Float): Float {
        val tt = t.coerceIn(0f, 1f)
        fun smooth(x: Float) = x * x * (3 - 2 * x)
        return when {
            tt < 0.15f -> smooth(tt / 0.15f)
            tt > 0.65f -> smooth((1f - tt) / 0.35f)
            else -> 1f
        }
    }

    /** Wajah [base] + reaksi pada waktu [t]. */
    fun apply(base: FaceParams, t: Float): FaceParams {
        val e = envelope(t)
        val m = motion(t)
        val p = base.lerp(pose.copy(glintX = base.glintX, glintY = base.glintY, zzz = base.zzz), e)
        return p.copy(tilt = p.tilt + m.tilt, bounceY = p.bounceY + m.bounceY, squash = p.squash + m.squash,
            lookX = p.lookX + m.lookX, lookY = p.lookY + m.lookY)
    }

    companion object {
        val TAP = listOf(SURPRISED_POP, GIGGLE, CURIOUS_TILT)
        val LONG_PRESS = listOf(SHY, TICKLISH)
    }
}

/** Kejadian reaksi (id unik agar reaksi yang sama bisa diputar ulang). */
data class ReactionEvent(val id: Long, val reaction: Reaction)

/**
 * Memilih reaksi untuk gestur, dengan anti-spam:
 * - jeda minimal antar reaksi ([cooldownMs]),
 * - ketukan tunggal bergiliran acak (tidak sama berturut-turut),
 * - ≥5 ketukan dalam 4 detik → pusing (DIZZY),
 * - goyangan HP → DIZZY dengan jeda lebih panjang.
 */
class ReactionPicker(private val rnd: Random = Random, private val cooldownMs: Long = 450, private val shakeCooldownMs: Long = 1800) {
    private var lastAt = Long.MIN_VALUE / 2
    private var lastTap: Reaction? = null
    private var lastLong: Reaction? = null
    private val taps = ArrayDeque<Long>()

    private fun ready(now: Long, cd: Long = cooldownMs) = now - lastAt >= cd
    private fun fire(now: Long, r: Reaction): Reaction { lastAt = now; return r }

    fun onTap(now: Long): Reaction? {
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() > 4000) taps.removeFirst()
        if (!ready(now)) return null
        if (taps.size >= 5) { taps.clear(); return fire(now, Reaction.DIZZY) }
        val r = Reaction.TAP.filter { it != lastTap }.let { it[rnd.nextInt(it.size)] }
        lastTap = r
        return fire(now, r)
    }

    fun onDoubleTap(now: Long): Reaction? = if (ready(now)) fire(now, Reaction.HAPPY_BOUNCE) else null

    fun onLongPress(now: Long): Reaction? {
        if (!ready(now)) return null
        val r = Reaction.LONG_PRESS.filter { it != lastLong }.let { it[rnd.nextInt(it.size)] }
        lastLong = r
        return fire(now, r)
    }

    fun onShake(now: Long): Reaction? = if (ready(now, shakeCooldownMs)) fire(now, Reaction.DIZZY) else null
}
