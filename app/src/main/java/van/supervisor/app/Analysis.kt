package van.supervisor.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The sums that `docs/measurements.md` currently does by hand.
 *
 * M14 computed station overhead from a CSV in a text editor ("43–59 W per
 * 15 min bin, mean 52.6, SD 3.9") and fitted a cool-down curve the same way
 * ("towards 4.5 °C, τ ≈ 27 min, rmse 0.04 K"). Those are the project's
 * blocked numbers (CLAUDE.md §8.2, §8.4, §8.5 and the pulldown penalty the
 * whole saving rests on), and none of them needs a person once the log is on
 * the phone.
 *
 * Pure functions over [SoakCsv.Table], so every one of them is tested on the
 * JVM in `app/src/test`. No Android types in this file, on purpose.
 */
object Analysis {

    /** `MEASURED` via energy balance, M2. The single biggest lever on [overhead]. */
    const val CAPACITY_WH = 3900f

    /** How wrong that capacity might be. M14: 3 % moves each bin by ~10 W. */
    const val CAPACITY_TOLERANCE = 0.03f

    /** Above this the compressor is running. M14 saw 17.5–24 W running, 0.0–0.1 W idle. */
    const val COMPRESSOR_W = 5f

    // -----------------------------------------------------------------------
    // Station overhead
    // -----------------------------------------------------------------------

    /**
     * Station overhead over one interval: what went in, minus what came out,
     * minus what the battery absorbed. Everything unaccounted for is the
     * station running itself (CLAUDE.md §1, ~48 W `MEASURED`).
     */
    class OverheadBin(
        val from: Long,
        val to: Long,
        /** The answer. Watts the station consumed that no register reports. */
        val overheadW: Float,
        /** ± on [overheadW], from capacity tolerance and tick timing, in quadrature. */
        val errW: Float,
        val inW: Float,
        val outW: Float,
        /** Positive = the battery was absorbing. */
        val battW: Float,
        val dSocPct: Float,
        val samples: Int,
    ) {
        val hours: Float get() = (to - from) / 3600f
    }

    /**
     * Overhead per SOC tick, not per wall-clock bin — and the difference is
     * not cosmetic.
     *
     * SOC is reported to 0.1 %, which on a 3 900 Wh pack is 3.9 Wh. Bin that
     * into fixed 15-minute windows and the quantisation alone is ±15 W on a
     * number whose whole value is being near 48. Measuring between the instants
     * SOC *changes* makes ΔSOC exact and moves the uncertainty into the
     * interval's duration, where one 10 s sample against a multi-minute tick
     * is worth a fraction of a watt. This is what M14 did by hand ("battery
     * power taken from SOC tick times") and the reason its 16 Sep numbers are
     * tighter than its 17 Sep ones.
     *
     * @param minSpanS ignore ticks closer together than this; under heavy
     *   charge SOC can step every few samples, and those intervals are all
     *   timing error.
     */
    fun overhead(
        t: SoakCsv.Table,
        capacityWh: Float = CAPACITY_WH,
        minSpanS: Int = 300,
    ): List<OverheadBin> {
        val soc = t.col("soc") ?: return emptyList()
        val inW = t.col("in_w") ?: return emptyList()
        val outW = t.col("out_w") ?: return emptyList()
        val ep = t.epochs()
        if (ep.size < 3) return emptyList()

        // Indices where SOC takes a new value. The first valid reading opens
        // the first interval.
        val ticks = ArrayList<Int>()
        var last = Float.NaN
        for (i in soc.indices) {
            val s = soc[i]
            if (s.isNaN()) continue
            if (last.isNaN() || s != last) {
                ticks.add(i)
                last = s
            }
        }

        val out = ArrayList<OverheadBin>()
        // Walk the ticks, closing a bin once it has covered minSpanS. Both
        // ends stay on a tick - which is what keeps deltaSOC exact - while the
        // bin is long enough that one sample of timing error is worth little.
        //
        // Grouping rather than filtering, because filtering reported NOTHING
        // on exactly the case worth measuring: charging hard, SOC ticks every
        // ~90 s, and every single interval fell under the minimum.
        var k = 0
        while (k < ticks.size - 1) {
            val a = ticks[k]
            var b = ticks[k + 1]
            var m = k + 1
            while (ep[b] - ep[a] < minSpanS && m < ticks.size - 1) {
                m++
                b = ticks[m]
            }
            val from = ep[a]
            val to = ep[b]
            val spanS = (to - from).toFloat()
            // Only the trailing remnant can still be short; it is dropped
            // rather than reported at an uncertainty nobody would act on.
            if (spanS < minSpanS) break
            k = m
            val hours = spanS / 3600f

            val dSoc = soc[b] - soc[a]
            val battW = dSoc / 100f * capacityWh / hours
            val mIn = timeMean(ep, inW, a, b)
            val mOut = timeMean(ep, outW, a, b)
            // Too much of the interval missing to average over. The cursor has
            // already advanced, so this drops the bin without stalling.
            if (mIn.isNaN() || mOut.isNaN()) continue

            // Two independent errors. The capacity is uncertain by a fixed
            // fraction; the tick instants are uncertain by up to one sample
            // each, which matters only for short intervals.
            val dt = medianStep(ep, a, b)
            val eCap = abs(battW) * CAPACITY_TOLERANCE
            val eTime = if (spanS > 0f) abs(battW) * (dt / spanS) else 0f

            out.add(
                OverheadBin(
                    from = from,
                    to = to,
                    overheadW = mIn - mOut - battW,
                    errW = sqrt(eCap * eCap + eTime * eTime),
                    inW = mIn,
                    outW = mOut,
                    battW = battW,
                    dSocPct = dSoc,
                    samples = b - a + 1,
                )
            )
        }
        return out
    }

    /** Mean and sample SD of a set of bins, weighted by nothing — M14's own summary. */
    class Summary(val n: Int, val mean: Float, val sd: Float, val min: Float, val max: Float)

    fun summarise(values: List<Float>): Summary? {
        val v = values.filter { !it.isNaN() }
        if (v.isEmpty()) return null
        val mean = v.sum() / v.size
        val sd = if (v.size < 2) 0f else
            sqrt(v.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / (v.size - 1))
        return Summary(v.size, mean, sd, v.min(), v.max())
    }

    // -----------------------------------------------------------------------
    // Episodes: the fridge running, and the fridge coasting
    // -----------------------------------------------------------------------

    /** Which column told us whether the compressor was running. */
    enum class Source {
        /** The plug between the P310 and the fridge. Direct, and the only honest one. */
        PLUG,

        /**
         * The inverter's own state. A proxy only: AC on does not mean the
         * compressor is running, and cooking shows up as a false run block.
         * Used when the plug node was not up.
         */
        INVERTER,
    }

    class Episode(
        val from: Long,
        val to: Long,
        val startC: Float,
        val endC: Float,
        /** Linear rate over the episode. Negative = cooling. */
        val slopeKPerH: Float,
        /** Energy the fridge drew, Wh. NAN when there was no plug. */
        val wh: Float,
        val medianW: Float,
        val fit: Fit?,
        val samples: Int,
    ) {
        val durationS: Long get() = to - from
        val deltaC: Float get() = endC - startC

        /**
         * Wh per kelvin removed — the raw material for the pulldown penalty
         * (CLAUDE.md §6, `UNVERIFIED`, and the number the whole saving rests
         * on). Compare a block after a long rest against one after a short
         * rest: the difference is the penalty. NAN unless there was a plug and
         * the block actually moved the temperature.
         */
        val whPerK: Float
            get() = if (wh.isNaN() || abs(deltaC) < 0.2f) Float.NaN else wh / abs(deltaC)
    }

    class Episodes(val source: Source, val cooling: List<Episode>, val coasting: List<Episode>)

    /**
     * Split the log into run blocks and rest blocks.
     *
     * @param minS drop episodes shorter than this. Below a couple of minutes
     *   an "episode" is a sample or two either side of a threshold, and
     *   fitting anything to it is noise with a number attached.
     */
    fun episodes(
        t: SoakCsv.Table,
        compressorW: Float = COMPRESSOR_W,
        minS: Int = 180,
    ): Episodes? {
        val ep = t.epochs()
        if (ep.size < 4) return null

        val plug = t.col("fridge_w")
        val source = if (plug != null) Source.PLUG else Source.INVERTER
        val run: FloatArray = plug ?: t.col("ac_on") ?: return null
        val threshold = if (source == Source.PLUG) compressorW else 0.5f

        // The probe. Raw first: the control copy is an EMA with a 3 min time
        // constant, which would flatten exactly the slopes being measured.
        val temp = t.col("t_fridge") ?: t.col("t_fridge_ctl") ?: t.col("t_fridge_raw")
            ?: return null

        val cooling = ArrayList<Episode>()
        val coasting = ArrayList<Episode>()

        var i = 0
        while (i < ep.size) {
            val v = run[i]
            if (v.isNaN()) { i++; continue }
            val on = v > threshold
            var j = i
            while (j + 1 < ep.size) {
                val n = run[j + 1]
                if (n.isNaN()) break
                if ((n > threshold) != on) break
                j++
            }
            if (j > i && ep[j] - ep[i] >= minS) {
                val e = episode(ep, temp, plug, i, j)
                if (e != null) (if (on) cooling else coasting).add(e)
            }
            i = j + 1
        }
        return Episodes(source, cooling, coasting)
    }

    private fun episode(
        ep: LongArray,
        temp: FloatArray,
        plug: FloatArray?,
        a: Int,
        b: Int,
    ): Episode? {
        val ts = ArrayList<Float>(b - a + 1)
        val cs = ArrayList<Float>(b - a + 1)
        for (i in a..b) {
            val c = temp[i]
            if (c.isNaN()) continue
            ts.add((ep[i] - ep[a]) / 3600f)   // hours from the episode's start
            cs.add(c)
        }
        if (cs.size < 3) return null
        val x = ts.toFloatArray()
        val y = cs.toFloatArray()

        val wh = if (plug == null) Float.NaN else integrateWh(ep, plug, a, b)
        val med = if (plug == null) Float.NaN else median(plug, a, b)

        return Episode(
            from = ep[a],
            to = ep[b],
            startC = y.first(),
            endC = y.last(),
            slopeKPerH = slope(x, y),
            wh = wh,
            medianW = med,
            fit = expFit(x, y),
            samples = y.size,
        )
    }

    // -----------------------------------------------------------------------
    // Duty cycle
    // -----------------------------------------------------------------------

    class DutyBin(val from: Long, val to: Long, val fridgeDuty: Float, val acDuty: Float)

    /**
     * Fraction of each bin the compressor ran, and the inverter was on.
     *
     * §8.4 calls duty cycle the single most important unknown. Note what
     * M6/M14 since found: on this appliance it is usually 100 %, and the two
     * recorded natural stops are the interesting rows, not the average.
     */
    fun duty(t: SoakCsv.Table, binS: Int = 3600, compressorW: Float = COMPRESSOR_W): List<DutyBin> {
        val ep = t.epochs()
        if (ep.size < 2) return emptyList()
        val plug = t.col("fridge_w")
        val ac = t.col("ac_on")
        if (plug == null && ac == null) return emptyList()

        // One pass over the segments, each charged to the bin it starts in.
        // Accumulating into a map is both simpler and harder to get wrong than
        // walking two cursors, and the bins come out sparse - a reboot leaves a
        // gap rather than a bin of invented zeroes.
        val total = HashMap<Long, Float>()
        val fridgeOn = HashMap<Long, Float>()
        val acOn = HashMap<Long, Float>()
        for (j in 0 until ep.size - 1) {
            val dt = (ep[j + 1] - ep[j]).toFloat()
            // A long gap is missing data, not a long sample. Counting it would
            // let one reboot decide the duty of the hour it happened in.
            if (dt <= 0f || dt > 120f) continue
            val bin = Math.floorDiv(ep[j], binS.toLong()) * binS
            total[bin] = (total[bin] ?: 0f) + dt
            if (plug != null && plug[j] > compressorW) fridgeOn[bin] = (fridgeOn[bin] ?: 0f) + dt
            if (ac != null && ac[j] > 0.5f) acOn[bin] = (acOn[bin] ?: 0f) + dt
        }

        val out = ArrayList<DutyBin>()
        for (bin in total.keys.sorted()) {
            val tot = total[bin] ?: continue
            // Half a bin of real samples before it is allowed to report a
            // fraction: a bin holding four samples is not a duty cycle.
            if (tot < binS * 0.5f) continue
            out.add(
                DutyBin(
                    from = bin,
                    to = bin + binS,
                    fridgeDuty = if (plug != null) (fridgeOn[bin] ?: 0f) / tot else Float.NaN,
                    acDuty = if (ac != null) (acOn[bin] ?: 0f) / tot else Float.NaN,
                )
            )
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Fitting
    // -----------------------------------------------------------------------

    /** `T(t) = asymptoteC + (T0 - asymptoteC) * exp(-t / tau)`. */
    class Fit(val asymptoteC: Float, val tauMin: Float, val rmseK: Float)

    /**
     * Fit a first-order thermal response, the way M14 did it by hand.
     *
     * Grid-search the asymptote, and for each candidate the problem is linear:
     * `ln|T - T∞|` against time has slope `-1/τ`. Cheap, and it does not need
     * a starting guess a phone has no way to supply.
     *
     * Returns null when there is nothing to fit — too few points, or a
     * temperature that barely moved, where a τ would be a number invented from
     * noise. Refusing is the point: an unfittable coast should read "flat",
     * not "τ = 4 minutes".
     *
     * @param x hours from the start of the episode, ascending.
     */
    fun expFit(x: FloatArray, y: FloatArray): Fit? {
        if (x.size < 6 || x.size != y.size) return null
        val lo = y.min()
        val hi = y.max()
        if (hi - lo < 0.3f) return null

        val falling = y.last() < y.first()
        // The asymptote lies beyond the last point, on the side the curve is
        // heading. 0.05 K off the extreme at the nearest, 15 K at the furthest.
        val base = if (falling) lo else hi
        val dir = if (falling) -1f else 1f

        var best: Fit? = null
        var bestRmse = Float.MAX_VALUE
        var step = 0.05f
        while (step <= 15f) {
            val tInf = base + dir * step
            val lx = ArrayList<Float>(x.size)
            val ly = ArrayList<Float>(x.size)
            var ok = true
            for (i in x.indices) {
                // The asymptote is beyond every point, so the residual keeps
                // one sign for the whole episode: positive while cooling
                // towards it from above, negative while warming from below.
                val d = y[i] - tInf
                if (falling && d <= 1e-6f) { ok = false; break }
                if (!falling && d >= -1e-6f) { ok = false; break }
                lx.add(x[i])
                ly.add(ln(abs(d).toDouble()).toFloat())
            }
            if (ok && lx.size >= 6) {
                val m = slope(lx.toFloatArray(), ly.toFloatArray())
                if (m < 0f) {
                    val tau = -1f / m                       // hours
                    val c = intercept(lx.toFloatArray(), ly.toFloatArray(), m)
                    val amp = exp(c.toDouble()).toFloat() * (if (falling) 1f else -1f)
                    var se = 0.0
                    for (i in x.indices) {
                        val pred = tInf + amp * exp((-x[i] / tau).toDouble()).toFloat()
                        se += ((pred - y[i]) * (pred - y[i])).toDouble()
                    }
                    val rmse = sqrt(se / x.size).toFloat()
                    if (rmse < bestRmse) {
                        bestRmse = rmse
                        best = Fit(tInf, tau * 60f, rmse)
                    }
                }
            }
            step += 0.05f
        }
        // A fit worse than the spread it is describing is not a fit.
        return if (best != null && bestRmse < (hi - lo) * 0.5f) best else null
    }

    // -----------------------------------------------------------------------
    // Small numerics
    // -----------------------------------------------------------------------

    /** Least-squares slope of y on x, ignoring NAN pairs. */
    fun slope(x: FloatArray, y: FloatArray): Float {
        var n = 0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in x.indices) {
            val a = x[i]; val b = y[i]
            if (a.isNaN() || b.isNaN()) continue
            n++; sx += a; sy += b; sxx += a.toDouble() * a; sxy += a.toDouble() * b
        }
        if (n < 2) return Float.NaN
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-12) return Float.NaN
        return ((n * sxy - sx * sy) / den).toFloat()
    }

    private fun intercept(x: FloatArray, y: FloatArray, m: Float): Float {
        var n = 0; var sx = 0.0; var sy = 0.0
        for (i in x.indices) {
            if (x[i].isNaN() || y[i].isNaN()) continue
            n++; sx += x[i]; sy += y[i]
        }
        if (n == 0) return Float.NaN
        return ((sy - m * sx) / n).toFloat()
    }

    /**
     * Time-weighted mean of [v] between row [a] and row [b].
     *
     * Trapezoidal, and it skips segments whose endpoints are not both valid
     * rather than treating a gap as a held value. If more than half the span
     * is unusable it returns NAN — a mean over a quarter of an interval is not
     * a mean over the interval, and the overhead sum must not quietly use one.
     */
    fun timeMean(ep: LongArray, v: FloatArray, a: Int, b: Int): Float {
        var acc = 0.0
        var span = 0.0
        val total = (ep[b] - ep[a]).toDouble()
        for (i in a until b) {
            val dt = (ep[i + 1] - ep[i]).toDouble()
            val p = v[i]; val q = v[i + 1]
            if (p.isNaN() || q.isNaN() || dt <= 0.0) continue
            acc += (p + q) / 2.0 * dt
            span += dt
        }
        if (span <= 0.0 || (total > 0.0 && span < total * 0.5)) return Float.NaN
        return (acc / span).toFloat()
    }

    /** Watt-hours under [v] between rows [a] and [b]. */
    fun integrateWh(ep: LongArray, v: FloatArray, a: Int, b: Int): Float {
        var acc = 0.0
        for (i in a until b) {
            val dt = (ep[i + 1] - ep[i]).toDouble()
            val p = v[i]; val q = v[i + 1]
            if (p.isNaN() || q.isNaN() || dt <= 0.0 || dt > 120.0) continue
            acc += (p + q) / 2.0 * dt
        }
        return (acc / 3600.0).toFloat()
    }

    fun median(v: FloatArray, a: Int, b: Int): Float {
        val s = ArrayList<Float>(b - a + 1)
        for (i in a..b) if (!v[i].isNaN()) s.add(v[i])
        if (s.isEmpty()) return Float.NaN
        s.sort()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    /** Typical sample spacing in seconds — the timing uncertainty on a tick. */
    private fun medianStep(ep: LongArray, a: Int, b: Int): Float {
        if (b <= a) return 0f
        val d = FloatArray(b - a) { (ep[a + it + 1] - ep[a + it]).toFloat() }
        d.sort()
        return d[d.size / 2]
    }
}
