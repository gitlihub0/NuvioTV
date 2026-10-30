package com.nuvio.tv.core.dub

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates how far a dub is shifted against the original of the same video. Android-free on
 * purpose: it is unit tested on the JVM.
 *
 * Two independent kinds of evidence, both correlated over +-[MAX_LAG] frames:
 *
 *  - SUBTITLES: the cues of an EMBEDDED subtitle track (locked to the video, unlike downloaded
 *    ones) say when somebody speaks; that is matched with the speech-band loudness of the dub.
 *    Needs no decoding of the original audio, so it also works when that is TrueHD/DTS-HD.
 *    Accuracy is roughly +-150 ms: dubbed lines start and end a little away from the originals.
 *  - MUSIC/EFFECTS: music, impacts and effects are shared by the original and the dub while the
 *    dialogue is not. Loudness onsets in the bass, low-mid and treble bands are matched. Precise
 *    (tens of ms) but it needs the original audio as PCM.
 *
 * Decision ([analyze]):
 *  - both conclusive and they agree  -> the music/effects value (precise)
 *  - both conclusive but disagree    -> rejected (one of them is wrong; better to do nothing)
 *  - only subtitles conclusive       -> subtitles, refined by music/effects inside +-[REFINE_RADIUS]
 *    frames when that shows a clear peak there
 *  - only music/effects conclusive   -> music/effects
 *  - otherwise                       -> rejected, the user adjusts by hand
 *
 * "Conclusive" always means: the windows' correlation curves are averaged (the true shift adds up
 * coherently, unrelated peaks cancel), the combined peak stands out from the rest of the curve,
 * AND enough windows show that same shift on their own. A shift that is not constant (different
 * frame rate or cut) fails the second test, so it is reported instead of being "fixed" wrongly.
 */
internal object DubSyncAnalyzer {

    const val FRAME_MS = 10
    const val MAX_LAG = 800
    const val MIN_USABLE_FRAMES = 1000

    /** Windows needed before [analyze] can say anything. */
    const val MIN_WINDOWS = 3

    /** A band quieter than this (mean square) is digital silence/dither: it carries no information. */
    private const val MIN_BAND_ENERGY = 1e-8

    private class Tuning(
        val minPsr: Double,
        /** Frames around the peak left out when measuring the noise of the curve. */
        val exclude: Int,
        val supportZ: Double,
        val supportFraction: Double
    )

    // The onset curves are narrow, the subtitle/speech curves are wide: they need a wide exclusion
    // zone, otherwise the shoulders of the peak inflate the noise estimate and hide a correct peak.
    private val ME = Tuning(minPsr = 6.0, exclude = 50, supportZ = 2.0, supportFraction = 0.40)
    private val SUB = Tuning(minPsr = 6.0, exclude = 350, supportZ = 1.5, supportFraction = 0.50)

    /** Bands used for music/effects; the dialogue band (700-3400 Hz) and the full band are left out. */
    private val ME_BANDS = intArrayOf(ProbeCapture.BAND_LOW, ProbeCapture.BAND_LOWMID, ProbeCapture.BAND_HIGH)

    private const val SUB_SMOOTH_SIGMA = 8.0
    private const val SUB_MIN_COVERAGE = 0.08
    private const val SUB_MAX_COVERAGE = 0.92

    private const val REFINE_RADIUS = 30
    private const val REFINE_MIN_Z = 3.0
    private const val REFINE_MIN_SUPPORT = 2

    /** Two conclusive methods must agree within this, otherwise the result is thrown away. */
    private const val AGREE_MS = 300

    enum class Method(val label: String) {
        MUSIC_EFFECTS("müzik/efekt"),
        SUBTITLES("gömülü altyazı"),
        SUBTITLES_REFINED("gömülü altyazı + müzik/efekt")
    }

    sealed class Result {
        /**
         * [lagMs] > 0: the dub plays [lagMs] later than the original. [precise]: accurate to tens
         * of ms (otherwise about +-150 ms).
         */
        data class Ok(
            val lagMs: Int,
            val method: Method,
            val precise: Boolean,
            val psr: Double,
            val support: Int,
            val windows: Int
        ) : Result()

        data class Unreliable(val reason: String) : Result()
    }

    /** What the loop in the controller needs to know to decide whether more windows would help. */
    fun hasOriginalAudio(windows: List<Pair<ProbeCapture, ProbeCapture>>): Boolean =
        windows.count { it.first.bands != null } >= MIN_WINDOWS

    /** [windows] = (original, dub) captures for the same video stretches. */
    fun analyze(
        windows: List<Pair<ProbeCapture, ProbeCapture>>,
        trace: (String) -> Unit = {}
    ): Result {
        if (windows.size < MIN_WINDOWS) return Result.Unreliable("yeterli ses penceresi yok (${windows.size}/$MIN_WINDOWS)")

        val sub = subtitleCurves(windows)
        val me = musicCurves(windows)
        val subVerdict = sub?.let { judge(it, SUB, "gömülü altyazı", trace) }
        val meVerdict = me?.let { judge(it, ME, "müzik/efekt", trace) }

        val reasons = ArrayList<String>()
        if (sub == null) reasons += "gömülü altyazı yok/yetersiz"
        else if (subVerdict != null && !subVerdict.ok) reasons += subVerdict.reason
        if (me == null) reasons += "orijinal ses çözülemedi"
        else if (meVerdict != null && !meVerdict.ok) reasons += meVerdict.reason

        val s = subVerdict?.takeIf { it.ok }
        val m = meVerdict?.takeIf { it.ok }

        if (s != null && m != null) {
            val diff = abs(s.lagFrames - m.lagFrames) * FRAME_MS
            trace("altyazı ${(s.lagFrames * FRAME_MS).toInt()}ms, müzik/efekt ${(m.lagFrames * FRAME_MS).toInt()}ms, fark ${diff.toInt()}ms")
            if (diff > AGREE_MS) return Result.Unreliable("yöntemler birbiriyle çelişiyor")
            return m.toOk(Method.MUSIC_EFFECTS, precise = true)
        }
        if (m != null) return m.toOk(Method.MUSIC_EFFECTS, precise = true)
        if (s != null) {
            val refined = me?.let { refine(it, s.lagFrames, trace) }
            return if (refined != null) {
                Result.Ok(
                    lagMs = Math.round(refined * FRAME_MS).toInt(),
                    method = Method.SUBTITLES_REFINED,
                    precise = true,
                    psr = s.psr,
                    support = s.support,
                    windows = s.windows
                )
            } else s.toOk(Method.SUBTITLES, precise = false)
        }
        return Result.Unreliable(reasons.joinToString("; ").ifEmpty { "eşleşme bulunamadı" })
    }

    // ---- curves ------------------------------------------------------------------------------

    /** One correlation curve per usable window (subtitle cues vs speech loudness of the dub). */
    private fun subtitleCurves(windows: List<Pair<ProbeCapture, ProbeCapture>>): List<DoubleArray>? {
        val curves = ArrayList<DoubleArray>()
        for ((orig, dub) in windows) {
            val cues = orig.cueActivity ?: continue
            val bands = dub.bands ?: continue
            val n = min(cues.size, bands[0].size)
            if (n < MIN_USABLE_FRAMES) continue
            var on = 0
            for (i in 0 until n) if (cues[i] > 0f) on++
            val coverage = on.toDouble() / n
            if (coverage < SUB_MIN_COVERAGE || coverage > SUB_MAX_COVERAGE) continue
            val lowMid = bands[ProbeCapture.BAND_LOWMID]
            val mid = bands[ProbeCapture.BAND_MID]
            val a = standardize(gaussSmooth(DoubleArray(n) { cues[it].toDouble() }, SUB_SMOOTH_SIGMA)) ?: continue
            val speech = DoubleArray(n) { ln(max((lowMid[it] + mid[it]).toDouble(), MIN_BAND_ENERGY)) }
            val b = standardize(gaussSmooth(speech, SUB_SMOOTH_SIGMA)) ?: continue
            curves.add(crossCorrelate(a, b))
        }
        return curves.takeIf { it.size >= MIN_WINDOWS }
    }

    /** One correlation curve per usable window (loudness onsets, mean over the bands). */
    private fun musicCurves(windows: List<Pair<ProbeCapture, ProbeCapture>>): List<DoubleArray>? {
        val curves = ArrayList<DoubleArray>()
        for ((orig, dub) in windows) {
            val ob = orig.bands ?: continue
            val db = dub.bands ?: continue
            val n = min(ob[0].size, db[0].size)
            if (n < MIN_USABLE_FRAMES) continue
            val perBand = ArrayList<DoubleArray>()
            for (band in ME_BANDS) {
                val a = bandFeatures(ob[band], n) ?: continue
                val b = bandFeatures(db[band], n) ?: continue
                perBand.add(crossCorrelate(a, b))
            }
            if (perBand.isNotEmpty()) curves.add(mean(perBand))
        }
        return curves.takeIf { it.size >= MIN_WINDOWS }
    }

    // ---- decision ----------------------------------------------------------------------------

    private class Verdict(
        val ok: Boolean,
        val reason: String,
        val lagFrames: Double,
        val psr: Double,
        val support: Int,
        val windows: Int
    ) {
        fun toOk(method: Method, precise: Boolean) = Result.Ok(
            lagMs = Math.round(lagFrames * FRAME_MS).toInt(),
            method = method,
            precise = precise,
            psr = psr,
            support = support,
            windows = windows
        )
    }

    private fun judge(curves: List<DoubleArray>, t: Tuning, label: String, trace: (String) -> Unit): Verdict {
        val combined = mean(curves)
        val peak = argMax(combined)
        val psr = zScoreAt(combined, peak, t.exclude)
        val support = curves.count { zScoreAt(it, peak, t.exclude) >= t.supportZ }
        val needed = max(3, ceil(curves.size * t.supportFraction).toInt())
        val lag = (peak - MAX_LAG) + interpolate(combined, peak)
        trace("$label: tepe ${(lag * FRAME_MS).toInt()}ms psr=${"%.1f".format(psr)} (eşik ${t.minPsr}) destek=$support/${curves.size} (gerek $needed)")
        val reason = when {
            psr < t.minPsr -> "$label: eşleşme belirgin değil (%.1f<%.1f)".format(psr, t.minPsr)
            support < needed -> "$label: pencereler aynı kaymayı göstermiyor ($support/${curves.size}); kayma sabit olmayabilir"
            else -> ""
        }
        return Verdict(reason.isEmpty(), reason, lag, psr, support, curves.size)
    }

    /**
     * Music/effects peak close to the subtitle estimate. Accepted only when it is clearly a peak
     * (not just the best of a few dozen noisy values) and not at the edge of the search range.
     */
    private fun refine(curves: List<DoubleArray>, centerFrames: Double, trace: (String) -> Unit): Double? {
        val combined = mean(curves)
        val center = (centerFrames + MAX_LAG).toInt()
        val lo = max(0, center - REFINE_RADIUS)
        val hi = min(combined.size - 1, center + REFINE_RADIUS)
        var p = lo
        for (i in lo..hi) if (combined[i] > combined[p]) p = i
        val z = zScoreAt(combined, p, ME.exclude)
        val support = curves.count { zScoreAt(it, p, ME.exclude) >= ME.supportZ - 0.5 }
        trace("ince ayar: ${((p - MAX_LAG) * FRAME_MS)}ms z=${"%.1f".format(z)} destek=$support")
        if (p == lo || p == hi || z < REFINE_MIN_Z || support < REFINE_MIN_SUPPORT) return null
        return (p - MAX_LAG) + interpolate(combined, p)
    }

    private fun interpolate(c: DoubleArray, p: Int): Double {
        if (p !in 1 until c.size - 1) return 0.0
        val d = c[p - 1] - 2 * c[p] + c[p + 1]
        return if (d == 0.0) 0.0 else (0.5 * (c[p - 1] - c[p + 1]) / d).coerceIn(-1.0, 1.0)
    }

    // ---- features ----------------------------------------------------------------------------

    private fun bandFeatures(energy: FloatArray, n: Int): DoubleArray? {
        var sum = 0.0
        for (i in 0 until n) sum += energy[i]
        if (sum / n < MIN_BAND_ENERGY) return null
        return features(energy.copyOf(n))
    }

    /** Onset flux of the log energy: log, light smoothing, positive first difference, smoothing, z-score. */
    internal fun features(meanSquares: FloatArray): DoubleArray? {
        val n = meanSquares.size
        if (n < MIN_USABLE_FRAMES) return null
        val logE = DoubleArray(n) { ln(max(meanSquares[it].toDouble(), 1e-8)) }
        val smooth = gaussSmooth(logE, 2.0)
        val onset = DoubleArray(n) { if (it == 0) 0.0 else max(smooth[it] - smooth[it - 1], 0.0) }
        val x = gaussSmooth(onset, 3.0)

        var sd = standardDeviation(x)
        if (sd < 1e-9) return null
        val mean = x.average()
        for (i in 0 until n) x[i] = ((x[i] - mean) / sd).coerceIn(-4.0, 4.0)
        val mean2 = x.average()
        for (i in 0 until n) x[i] -= mean2
        sd = standardDeviation(x)
        if (sd < 1e-9) return null
        for (i in 0 until n) x[i] /= sd
        return x
    }

    private fun standardize(x: DoubleArray): DoubleArray? {
        val sd = standardDeviation(x)
        if (sd < 1e-9) return null
        val m = x.average()
        return DoubleArray(x.size) { (x[it] - m) / sd }
    }

    private fun mean(curves: List<DoubleArray>): DoubleArray {
        val out = DoubleArray(curves[0].size)
        for (c in curves) for (i in out.indices) out[i] += c[i] / curves.size
        return out
    }

    private fun argMax(x: DoubleArray): Int {
        var best = 0
        for (i in 1 until x.size) if (x[i] > x[best]) best = i
        return best
    }

    private fun standardDeviation(x: DoubleArray): Double {
        val m = x.average()
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / x.size)
    }

    private fun gaussSmooth(x: DoubleArray, sigma: Double): DoubleArray {
        val k = (sigma * 3).toInt()
        val kernel = DoubleArray(2 * k + 1) { val t = (it - k) / sigma; exp(-0.5 * t * t) }
        val total = kernel.sum()
        for (i in kernel.indices) kernel[i] /= total
        val n = x.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (j in -k..k) acc += x[(i + j).coerceIn(0, n - 1)] * kernel[j + k]
            out[i] = acc
        }
        return out
    }

    // ---- cross-correlation ---------------------------------------------------------------------

    /**
     * r[k + MAX_LAG] = sum_n a[n] * b[n + k] / N, for k in -MAX_LAG..MAX_LAG. Divided by the full
     * length (not by the overlap): at large lags only a short stretch overlaps, and dividing by it
     * would blow the noise up exactly where chance peaks are most likely.
     */
    internal fun crossCorrelate(a: DoubleArray, b: DoubleArray): DoubleArray {
        var nfft = 1
        while (nfft < a.size + b.size) nfft = nfft shl 1
        val ar = a.copyOf(nfft)
        val ai = DoubleArray(nfft)
        val br = b.copyOf(nfft)
        val bi = DoubleArray(nfft)
        fft(ar, ai, inverse = false)
        fft(br, bi, inverse = false)
        // conj(A) * B
        val cr = DoubleArray(nfft)
        val ci = DoubleArray(nfft)
        for (i in 0 until nfft) {
            cr[i] = ar[i] * br[i] + ai[i] * bi[i]
            ci[i] = ar[i] * bi[i] - ai[i] * br[i]
        }
        fft(cr, ci, inverse = true)

        val norm = min(a.size, b.size).toDouble()
        val out = DoubleArray(2 * MAX_LAG + 1)
        for (idx in out.indices) {
            val k = idx - MAX_LAG
            out[idx] = cr[((k % nfft) + nfft) % nfft] / norm
        }
        return out
    }

    /** Peak height in standard deviations of the curve outside +-[exclude] frames around it. */
    private fun zScoreAt(r: DoubleArray, peak: Int, exclude: Int): Double {
        var count = 0
        var sum = 0.0
        for (i in r.indices) if (abs(i - peak) > exclude) { sum += r[i]; count++ }
        if (count == 0) return 0.0
        val mean = sum / count
        var sq = 0.0
        for (i in r.indices) if (abs(i - peak) > exclude) sq += (r[i] - mean) * (r[i] - mean)
        val sd = max(sqrt(sq / count), 1e-6)
        return (r[peak] - mean) / sd
    }

    /** In-place iterative radix-2 FFT. The inverse transform is scaled by 1/n. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }
}
