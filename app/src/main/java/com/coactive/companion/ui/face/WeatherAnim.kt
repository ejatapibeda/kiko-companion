package com.coactive.companion.ui.face

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Animasi sekali-jalan untuk reaksi cuaca (dipicu sesekali saat idle, lihat assistant/AssistantViewModel):
 * - [SNEEZE]: "ha… ha…" (mata menyipit, menengadah, membesar) lalu "HACIH!" (menunduk tajam, mata terpejam, mulut lebar),
 * - [SHIVER]: meringkuk & menggigil cepat (miring/geser kecil bergetar),
 * - [OVERHEAT]: lemes kepanasan, bergoyang pelan + "hah-hah" + turun pelan seperti keringat menetes.
 * Semua kembali mulus ke wajah dasar di t = 1.
 */
enum class WeatherAct(val durationMs: Int) { SNEEZE(1500), SHIVER(2600), OVERHEAT(3000) }

/** Kejadian reaksi cuaca. [holdAt] (khusus debug) = bekukan animasi di titik t tersebut. */
data class WeatherEvent(val id: Long, val act: WeatherAct, val holdAt: Float? = null, val at: Long = android.os.SystemClock.uptimeMillis())

object WeatherAnim {
    private val SNEEZE_BUILD = FaceParams(openL = 0.38f, openR = 0.38f, arcL = -0.2f, arcR = -0.2f, scaleL = 1.06f, scaleR = 1.06f,
        lookY = -0.35f, mouth = MouthShape(0.6f, -0.3f, 0.28f), blush = 0.35f, bounceY = -0.14f, squash = 0.07f)

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun osc(t: Float, cycles: Float) = sin(t * 2 * PI.toFloat() * cycles)

    /** Amplop naik/tahan/turun (sama seperti reaksi sentuhan). */
    private fun envelope(t: Float, rise: Float = 0.15f, fall: Float = 0.3f) = when {
        t < rise -> smooth(t / rise)
        t > 1f - fall -> smooth((1f - t) / fall)
        else -> 1f
    }

    private fun blend(base: FaceParams, pose: FaceParams, w: Float) =
        base.lerp(pose.copy(glintX = base.glintX, glintY = base.glintY, zzz = base.zzz), w)

    fun apply(base: FaceParams, act: WeatherAct, t: Float): FaceParams {
        val tt = t.coerceIn(0f, 1f)
        return when (act) {
            WeatherAct.SNEEZE -> {
                // 0–0.55 ancang-ancang "ha… ha…", 0.55–0.6 HACIH (cepat), tahan s/d 0.72, lalu kembali
                val build = smooth(tt / 0.4f) * (1f - smooth((tt - 0.55f) / 0.05f))
                val snap = smooth((tt - 0.55f) / 0.05f) * (1f - smooth((tt - 0.72f) / 0.28f))
                val hitch = if (tt < 0.55f) 0.05f * abs(osc(tt, 3.6f)) * smooth(tt / 0.3f) else 0f
                var p = blend(base, SNEEZE_BUILD, build)
                p = blend(p, FaceParams.of(Expression.SNEEZE), snap)
                // pantulan kecil setelah HACIH
                val rebound = if (tt > 0.6f) -0.1f * sin(((tt - 0.6f) / 0.4f) * PI.toFloat() * 2f) * (1f - tt) else 0f
                p.copy(bounceY = p.bounceY - hitch + rebound, scaleL = p.scaleL + hitch, scaleR = p.scaleR + hitch,
                    tilt = p.tilt + 5f * snap)
            }
            WeatherAct.SHIVER -> {
                val e = envelope(tt)
                val p = blend(base, FaceParams.of(Expression.COLD), e)
                // getar cepat (~13 Hz) dengan amplitudo kecil
                val jitter = osc(tt, 34f)
                p.copy(tilt = p.tilt + 2.6f * jitter * e, lookX = p.lookX + 0.03f * osc(tt, 21f) * e,
                    bounceY = p.bounceY + 0.025f * abs(osc(tt, 17f)) * e)
            }
            WeatherAct.OVERHEAT -> {
                val e = envelope(tt, rise = 0.2f, fall = 0.3f)
                val pant = 0.5f + 0.5f * osc(tt, 4f) // "hah-hah"
                var p = blend(base, FaceParams.of(Expression.HOT), e)
                p = p.copy(mouth = p.mouth.copy(top = p.mouth.top - 0.12f * pant * e, bottom = p.mouth.bottom + 0.08f * pant * e))
                // goyang pelan + turun pelan (seperti meleleh / keringat menetes)
                p.copy(tilt = p.tilt + 3.5f * osc(tt, 1.2f) * e, bounceY = p.bounceY + 0.08f * smooth(tt / 0.7f) * e)
            }
        }
    }
}
