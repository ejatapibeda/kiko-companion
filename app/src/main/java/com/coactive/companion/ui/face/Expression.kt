package com.coactive.companion.ui.face

/** Set ekspresi sesuai mockup. */
enum class Expression(val label: String) {
    NEUTRAL("Netral"), HAPPY("Senang"), BLINK("Kedip"), CURIOUS("Penasaran"), EXCITED("Antusias"),
    THINKING("Berpikir"), TALKING("Bicara"), SURPRISED("Terkejut"), SAD("Sedih"), SLEEPY("Ngantuk"),
    /** Ekspresi cuaca (lihat [WeatherAnim]): puncak "hacih!", menggigil, dan gerah/lemes. */
    SNEEZE("Bersin"), COLD("Kedinginan"), HOT("Kepanasan"),
}

/** Bentuk mulut: area terisi antara kurva atas & bawah (offset Y relatif, satuan = radius mata). */
data class MouthShape(val width: Float, val top: Float, val bottom: Float, val wave: Float = 0f) {
    /** [wave] 0..1: campuran ke mulut "berlekuk" (squiggle ~~). */
    fun lerp(to: MouthShape, t: Float): MouthShape {
        fun l(a: Float, b: Float) = a + (b - a) * t
        return MouthShape(l(width, to.width), l(top, to.top), l(bottom, to.bottom), l(wave, to.wave))
    }
}

/**
 * Mulut "Ooo… Ooo…" saat tidur: bulatan kecil yang membesar-mengecil pelan seperti dengkur/napas.
 * [t] = fase siklus 0..1 (satu buka-tutup per siklus); ukuran tetap kecil supaya lucu, bukan menganga.
 */
object SleepMouth {
    /** Durasi satu siklus "Ooo" (= 2 siklus zZz 1600ms, agar seirama). */
    const val CYCLE_MS = 3200

    /** 0..1, halus (kosinus): 0 = paling kecil di awal siklus, 1 = paling besar di tengah. */
    fun open(t: Float): Float = (0.5f - 0.5f * kotlin.math.cos(t * 2f * Math.PI.toFloat()))

    fun shape(t: Float): MouthShape {
        val o = open(t)
        val w = 0.30f + 0.12f * o       // lebar relatif radius mata
        val h = 0.36f + 0.16f * o       // tinggi total (top..bottom) ≈ lebar → bentuk "O"
        return MouthShape(w, -h / 2, h / 2)
    }
}

/**
 * Parameter wajah yang bisa diinterpolasi. Semua ukuran relatif terhadap radius mata.
 * openness 1 = mata bulat, 0 = garis; arc: -1 = "^" (senang), 0 = lurus, +1 = "‿" (tidur).
 */
data class FaceParams(
    val openL: Float = 1f,
    val openR: Float = 1f,
    val arcL: Float = 0f,
    val arcR: Float = 0f,
    val scaleL: Float = 1f,
    val scaleR: Float = 1f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val sparkle: Float = 0f,
    /** Mulut netral: setengah lingkaran kecil mungil (atas sedikit melengkung) — gaya imut referensi. */
    val mouth: MouthShape = MouthShape(0.86f, -0.04f, 0.42f),
    val zzz: Float = 0f,
    /** Offset kilau (relatif radius mata) — kilau tertinggal sedikit saat mata bergerak (kesan bola mata 3D). */
    val glintX: Float = 0f,
    val glintY: Float = 0f,
    /** Rona pipi 0..1 (malu). */
    val blush: Float = 0f,
    /** Transformasi "badan" wajah: miring (derajat), loncat (satuan radius, negatif = naik), squash (+ = memanjang). */
    val tilt: Float = 0f,
    val bounceY: Float = 0f,
    val squash: Float = 0f,
    /** Denyut cahaya mata 0..1 (joget, di ketukan). */
    val glowPulse: Float = 0f,
) {
    fun lerp(to: FaceParams, t: Float): FaceParams {
        fun l(a: Float, b: Float) = a + (b - a) * t
        return FaceParams(
            l(openL, to.openL), l(openR, to.openR), l(arcL, to.arcL), l(arcR, to.arcR),
            l(scaleL, to.scaleL), l(scaleR, to.scaleR), l(lookX, to.lookX), l(lookY, to.lookY),
            l(sparkle, to.sparkle),
            mouth.lerp(to.mouth, t),
            l(zzz, to.zzz),
            l(glintX, to.glintX), l(glintY, to.glintY),
            l(blush, to.blush), l(tilt, to.tilt), l(bounceY, to.bounceY), l(squash, to.squash),
            l(glowPulse, to.glowPulse),
        )
    }

    /**
     * Batasi nilai yang berasal dari spring (bisa overshoot) ke rentang aman sebelum digambar:
     * openness/sparkle/zzz/blush/wave/glowPulse → [0,1], skala & lebar mulut → ≥ 0, kilau → [-1,1].
     * Gerak (look/tilt/bounce) dibiarkan.
     */
    fun sanitized(): FaceParams = copy(
        openL = openL.clampUnit(), openR = openR.clampUnit(),
        scaleL = scaleL.clampNonNegative(), scaleR = scaleR.clampNonNegative(),
        sparkle = sparkle.clampUnit(), zzz = zzz.clampUnit(), blush = blush.clampUnit(),
        glowPulse = glowPulse.clampUnit(),
        glintX = if (glintX.isNaN()) 0f else glintX.coerceIn(-1f, 1f), glintY = if (glintY.isNaN()) 0f else glintY.coerceIn(-1f, 1f),
        mouth = mouth.copy(width = mouth.width.clampNonNegative(), wave = mouth.wave.clampUnit()),
    )

    /** Terapkan kedipan (0 = terbuka, 1 = tertutup) tanpa mengubah mata yang sudah tertutup. */
    fun withBlink(blink: Float) = copy(openL = openL * (1 - blink), openR = openR * (1 - blink))

    /** Mulut bicara dari level audio 0..1. */
    fun withTalkLevel(level: Float): FaceParams {
        val w = 0.95f - level * 0.15f
        return copy(mouth = MouthShape(w, -0.05f - level * 0.45f, 0.3f + level * 0.6f, 0f))
    }

    companion object {
        fun of(e: Expression): FaceParams = when (e) {
            Expression.NEUTRAL -> FaceParams()
            Expression.HAPPY -> FaceParams(openL = 0f, openR = 0f, arcL = -1f, arcR = -1f, mouth = MouthShape(1.15f, 0f, 0.7f))
            Expression.BLINK -> FaceParams(openL = 0f, arcL = 0f, mouth = MouthShape(0.86f, -0.03f, 0.42f))
            Expression.CURIOUS -> FaceParams(scaleL = 0.95f, scaleR = 1.08f, lookX = 0.15f, lookY = -0.05f, mouth = MouthShape(0.42f, -0.24f, 0.24f))
            Expression.EXCITED -> FaceParams(scaleL = 1.1f, scaleR = 1.1f, sparkle = 1f, mouth = MouthShape(1.25f, 0f, 0.8f))
            Expression.THINKING -> FaceParams(lookX = -0.25f, lookY = -0.3f, mouth = MouthShape(0.95f, -0.5f, -0.22f))
            Expression.TALKING -> FaceParams(mouth = MouthShape(0.9f, -0.3f, 0.6f))
            Expression.SURPRISED -> FaceParams(scaleL = 1.15f, scaleR = 1.15f, lookY = -0.05f, mouth = MouthShape(0.8f, -0.42f, 0.42f))
            Expression.SAD -> FaceParams(scaleL = 0.95f, scaleR = 0.95f, lookY = 0.12f, mouth = MouthShape(1.0f, -0.5f, -0.2f))
            Expression.SLEEPY -> FaceParams(openL = 0f, openR = 0f, arcL = 0.45f, arcR = 0.45f, lookY = 0.15f, mouth = MouthShape(0.9f, -0.35f, -0.12f), zzz = 1f)
            // "HACIH!": mata terpejam rapat (sedikit "^"), mulut terbuka lebar, kepala menunduk & memipih
            Expression.SNEEZE -> FaceParams(openL = 0f, openR = 0f, arcL = -0.35f, arcR = -0.35f, scaleL = 0.92f, scaleR = 0.92f,
                lookY = 0.1f, mouth = MouthShape(1.05f, -0.55f, 0.62f), blush = 0.45f, bounceY = 0.22f, squash = -0.1f)
            // menggigil: mata mengecil & agak sayu, gigi gemeletuk (mulut berlekuk), meringkuk, pipi sedikit merah
            Expression.COLD -> FaceParams(openL = 0.72f, openR = 0.72f, scaleL = 0.88f, scaleR = 0.88f, lookY = 0.08f,
                mouth = MouthShape(0.82f, -0.02f, 0.02f, wave = 1f), blush = 0.3f, bounceY = 0.06f, squash = -0.05f)
            // gerah: mata setengah tertutup & melorot, pipi merah, mulut terbuka "hah-hah", badan "meleleh"
            Expression.HOT -> FaceParams(openL = 0.42f, openR = 0.42f, arcL = 0.25f, arcR = 0.25f, lookY = 0.2f,
                mouth = MouthShape(0.8f, -0.32f, 0.46f), blush = 1f, bounceY = 0.1f, squash = -0.07f)
        }
    }
}
