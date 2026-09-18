package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * The analyses, checked against the measurements they exist to reproduce.
 *
 * Where a case comes from `docs/measurements.md` it is named, because the
 * point of these numbers is that a person already derived them by hand and
 * the app must agree. A synthetic case is only used where the real log does
 * not isolate the effect.
 */
class AnalysisTest {

    /** Build a table directly, without going through CSV text. */
    private fun table(columns: List<String>, vararg rows: Pair<Long, FloatArray>): SoakCsv.Table =
        SoakCsv.Table(columns, rows.map { (e, v) -> SoakCsv.Row(e, 0, 1, 1, v) })

    // ---------------------------------------------------------------- overhead

    /**
     * A station drawing exactly 50 W of its own, seen only as the SOC balance
     * (CLAUDE.md §1, M2 ~48 W, M14 mean 52.6 W).
     *
     * 300 W in, 100 W out, so 200 W should reach the battery; 150 W actually
     * does, and the missing 50 W is the answer. Over an hour on a 3 900 Wh
     * pack, 150 W is 3.846 % of SOC.
     */
    @Test
    fun `overhead recovers a known station draw from the SOC balance`() {
        val cols = listOf("soc", "in_w", "out_w")
        val rows = ArrayList<Pair<Long, FloatArray>>()
        val t0 = 1_758_000_000L
        // One hour at 10 s. SOC steps smoothly; ticks are what overhead() uses.
        for (i in 0..360) {
            val soc = 50f + 3.846f * (i / 360f)
            // Quantise to 0.1 %, exactly as the station reports it.
            val q = Math.round(soc * 10f) / 10f
            rows.add((t0 + i * 10L) to floatArrayOf(q, 300f, 100f))
        }
        val bins = Analysis.overhead(table(cols, *rows.toTypedArray()), minSpanS = 300)
        assertTrue("expected some bins", bins.isNotEmpty())
        val mean = bins.map { it.overheadW }.average().toFloat()
        // Within a watt or two: the SOC quantisation is what is left.
        assertEquals(50f, mean, 3f)
    }

    /**
     * Why [Analysis.overhead] bins by SOC tick and not by wall clock, and why
     * short ticks are grouped rather than dropped.
     *
     * SOC resolution is 0.1 %, which is 3.9 Wh on this pack — sampled into a
     * fixed 15-minute window that alone is ±15 W on a number worth ~48. So
     * both ends of a bin sit on a tick, making ΔSOC exact.
     *
     * Charging hard, though, SOC ticks every few samples, and *filtering*
     * those out reported nothing at all on exactly the condition worth
     * measuring. They are accumulated instead: every emitted bin spans at
     * least `minSpanS`, however finely the station ticked inside it.
     */
    @Test
    fun `fast ticks are grouped into trustworthy bins, never dropped`() {
        val cols = listOf("soc", "in_w", "out_w")
        // 20 min of fast charge, SOC ticking every single 10 s sample.
        val rows = (0..120).map { i ->
            (HOUR + i * 10L) to floatArrayOf(50f + i * 0.1f, 900f, 50f)
        }
        val bins = Analysis.overhead(table(cols, *rows.toTypedArray()), minSpanS = 300)
        assertTrue("fast ticks must still produce bins", bins.isNotEmpty())
        for (b in bins) {
            assertTrue("every bin spans at least minSpanS", b.to - b.from >= 300)
        }
        // And they are still tick-aligned, so no bin invents a partial ΔSOC.
        for (b in bins) assertTrue(b.dSocPct > 0f)
    }

    @Test
    fun `overhead reports its own uncertainty and it grows with battery power`() {
        val cols = listOf("soc", "in_w", "out_w")
        fun run(socPerHour: Float): Analysis.OverheadBin {
            val rows = (0..360).map { i ->
                val soc = 50f + socPerHour * (i / 360f)
                (1_758_000_000L + i * 10L) to
                    floatArrayOf(Math.round(soc * 10f) / 10f, 300f, 100f)
            }
            return Analysis.overhead(table(cols, *rows.toTypedArray()), minSpanS = 300).last()
        }
        val gentle = run(1f)
        val hard = run(10f)
        assertTrue("more battery power, more uncertainty", hard.errW > gentle.errW)
        // M14's own note: a 3 % capacity error moves a hard-charging bin ~10 W.
        assertTrue(hard.errW > 5f)
    }

    /**
     * A full pack curtails rather than absorbing, so input no longer has to
     * equal output plus storage plus overhead. On the mock log five hours
     * pinned at 100 % reported 334 W against a true 50 W, and it dominated the
     * mean. Such intervals are excluded, never clamped: no answer beats a
     * confident wrong one.
     */
    @Test
    fun `an interval pinned at a full pack is excluded, not clamped`() {
        val cols = listOf("soc", "in_w", "out_w")
        val rows = (0..1080).map { i ->
            // Three hours at 100 %, 500 W of surplus going nowhere measurable.
            (HOUR + i * 10L) to floatArrayOf(100f, 540f, 40f)
        }
        assertTrue(Analysis.overhead(table(cols, *rows.toTypedArray())).isEmpty())
    }

    @Test
    fun `charging into the top of the pack is still measured`() {
        val cols = listOf("soc", "in_w", "out_w")
        // 97 -> 99.8: at the top, but genuinely still absorbing.
        val rows = (0..360).map { i ->
            val soc = 97f + 2.8f * (i / 360f)
            (HOUR + i * 10L) to floatArrayOf(Math.round(soc * 10f) / 10f, 300f, 100f)
        }
        assertTrue(
            "the rail guard must not swallow a real charge",
            Analysis.overhead(table(cols, *rows.toTypedArray()), minSpanS = 300).isNotEmpty(),
        )
    }

    @Test
    fun `the weighted estimate trusts the quiet bins over the noisy ones`() {
        fun bin(w: Float, err: Float) = Analysis.OverheadBin(
            from = 0, to = 900, overheadW = w, errW = err, errRandomW = err,
            inW = 0f, outW = 0f, battW = 0f, dSocPct = 0f, samples = 90,
        )
        // Three precise bins near 50, one wild one that a plain mean would let
        // drag the answer 30 W away.
        val bins = listOf(bin(50f, 1f), bin(51f, 1f), bin(49f, 1f), bin(200f, 60f))
        val plain = Analysis.summarise(bins.map { it.overheadW })!!
        val weighted = Analysis.overheadEstimate(bins)!!
        assertTrue("a plain mean is dragged", plain.mean > 80f)
        assertEquals("the weighted one is not", 50f, weighted.watts, 1.5f)
        assertTrue("and it reports its own standard error", weighted.se in 0.01f..2f)
        assertEquals(4, weighted.n)
    }

    /**
     * The capacity band is common to every bin, so more bins must not shrink
     * it. Quoting only the statistical error on a number this dominates is
     * exactly the false precision CLAUDE.md keeps calling out.
     */
    @Test
    fun `the capacity band does not average away`() {
        fun bin(n: Int) = List(n) {
            Analysis.OverheadBin(
                from = 0, to = 900, overheadW = 50f, errW = 11f, errRandomW = 2f,
                inW = 300f, outW = 100f, battW = 150f, dSocPct = 1f, samples = 90,
            )
        }
        val few = Analysis.overheadEstimate(bin(4))!!
        val many = Analysis.overheadEstimate(bin(400))!!
        assertTrue("statistical error shrinks with n", many.se < few.se / 5f)
        assertEquals("the capacity band does not", few.systematicW, many.systematicW, 0.01f)
        // 3 % of a 150 W battery flow.
        assertEquals(4.5f, many.systematicW, 0.1f)
    }

    @Test
    fun `overhead needs the station columns and says nothing without them`() {
        val t = table(listOf("t_fridge"), 1_758_000_000L to floatArrayOf(7.5f))
        assertTrue(Analysis.overhead(t).isEmpty())
    }

    // ---------------------------------------------------------------- episodes

    /**
     * M14, 17 Sep: the compressor stopped on its own, the probe rose slowly,
     * then a 70-minute cool-down at a flat ~21.8 W median.
     */
    @Test
    fun `episodes split a real stop-and-restart into a coast and a cool-down`() {
        val cols = listOf("t_fridge", "fridge_w")
        val rows = ArrayList<Pair<Long, FloatArray>>()
        val t0 = 1_758_112_560L
        // 24 min idle, probe rising +0.4 K/h from 7.56.
        for (i in 0 until 144) {
            val h = i * 10 / 3600f
            rows.add((t0 + i * 10L) to floatArrayOf(7.56f + 0.4f * h, 0.05f))
        }
        // 70 min running at ~21.8 W, cooling towards 4.5 °C with tau 27 min.
        val c0 = 8.1f
        for (i in 0 until 420) {
            val min = i * 10 / 60f
            val c = 4.5f + (c0 - 4.5f) * exp((-min / 27f).toDouble()).toFloat()
            rows.add((t0 + 1440L + i * 10L) to floatArrayOf(c, 21.8f))
        }
        val e = Analysis.episodes(table(cols, *rows.toTypedArray()))!!
        assertEquals(Analysis.Source.PLUG, e.source)
        assertEquals(1, e.coasting.size)
        assertEquals(1, e.cooling.size)

        val coast = e.coasting[0]
        assertEquals("the measured coast rate", 0.4f, coast.slopeKPerH, 0.05f)
        assertTrue("a coast draws nothing", coast.wh < 0.1f)

        val cool = e.cooling[0]
        assertEquals("M14's median running power", 21.8f, cool.medianW, 0.2f)
        // 21.8 W for 70 min is 25.4 Wh; M14 recorded 26 Wh for the block.
        assertEquals(25.4f, cool.wh, 1.5f)
        val fit = cool.fit!!
        assertEquals("M14 fitted tau ~27 min", 27f, fit.tauMin, 3f)
        assertEquals("towards 4.5 C on the probe", 4.5f, fit.asymptoteC, 0.5f)
        assertTrue("a clean fit", fit.rmseK < 0.1f)
    }

    /**
     * The pulldown penalty (CLAUDE.md §6, `UNVERIFIED`) is Wh per kelvin
     * across blocks. The episode has to expose it, and refuse it when the
     * block did not actually move the temperature.
     */
    @Test
    fun `whPerK is the pulldown raw material, and NAN when the block moved nothing`() {
        val cols = listOf("t_fridge", "fridge_w")
        fun block(from: Float, to: Float): Analysis.Episode {
            val rows = (0 until 360).map { i ->
                val c = from + (to - from) * (i / 359f)
                (1_758_000_000L + i * 10L) to floatArrayOf(c, 20f)
            }
            return Analysis.episodes(table(cols, *rows.toTypedArray()))!!.cooling[0]
        }
        val real = block(8f, 4f)
        // 20 W for ~60 min is 20 Wh, over 4 K.
        assertEquals(5f, real.whPerK, 0.5f)

        val flat = block(8f, 7.95f)
        assertTrue("0.05 K is not a pulldown", flat.whPerK.isNaN())
    }

    /** With no plug node there is still a signal, and it must say it is weaker. */
    @Test
    fun `without the plug the inverter stands in, and says so`() {
        val cols = listOf("t_fridge", "ac_on")
        val rows = (0 until 200).map { i ->
            (1_758_000_000L + i * 10L) to floatArrayOf(7f - i * 0.002f, if (i < 100) 1f else 0f)
        }
        val e = Analysis.episodes(table(cols, *rows.toTypedArray()))!!
        assertEquals(Analysis.Source.INVERTER, e.source)
        assertEquals(1, e.cooling.size)
        assertTrue("no plug, no energy figure", e.cooling[0].wh.isNaN())
    }

    @Test
    fun `an episode too short to mean anything is dropped`() {
        val cols = listOf("t_fridge", "fridge_w")
        val rows = (0 until 40).map { i ->
            // 20 s of "running" in the middle: threshold noise, not a block.
            (1_758_000_000L + i * 10L) to floatArrayOf(7f, if (i in 20..21) 20f else 0f)
        }
        val e = Analysis.episodes(table(cols, *rows.toTypedArray()), minS = 180)!!
        assertTrue(e.cooling.isEmpty())
    }

    // -------------------------------------------------------------------- fit

    /** Refusing is a feature: a flat coast must read flat, not "tau = 4 min". */
    @Test
    fun `expFit declines a curve that is not there`() {
        val x = FloatArray(60) { it / 360f }
        val flat = FloatArray(60) { 7.0f }
        assertNull(Analysis.expFit(x, flat))
        val noisy = FloatArray(60) { 7.0f + (if (it % 2 == 0) 0.01f else -0.01f) }
        assertNull(Analysis.expFit(x, noisy))
        assertNull("too few points", Analysis.expFit(FloatArray(3) { it.toFloat() }, floatArrayOf(9f, 7f, 5f)))
    }

    @Test
    fun `expFit handles a warming coast as well as a cooling one`() {
        // Cabinet coasting up towards 20 C cabin, tau 6 h, from 2 C.
        val n = 200
        val x = FloatArray(n) { it * 60 / 3600f }
        val y = FloatArray(n) { 20f - 18f * exp((-x[it] / 6f).toDouble()).toFloat() }
        val fit = Analysis.expFit(x, y)
        assertNotNull(fit)
        assertEquals(6f * 60f, fit!!.tauMin, 30f)
        assertEquals(20f, fit.asymptoteC, 1.5f)
    }

    // ------------------------------------------------------------------- duty

    /**
     * Bins are wall-clock aligned, so the fixture starts on an hour boundary.
     * An unaligned start splits one run across two bins and neither reads as
     * the duty you meant - which is the behaviour, not a bug, but it makes a
     * poor fixture.
     */
    private val HOUR = 1_757_998_800L   // 1_758_000_000 rounded down to the hour

    @Test
    fun `duty is the fraction of the bin the compressor ran`() {
        val cols = listOf("fridge_w", "ac_on")
        // One hour: 15 min running, 45 min idle. The inverter stays on.
        val rows = (0..360).map { i ->
            (HOUR + i * 10L) to floatArrayOf(if (i < 90) 20f else 0f, 1f)
        }
        val d = Analysis.duty(table(cols, *rows.toTypedArray()), binS = 3600)
        assertEquals(1, d.size)
        assertEquals(0.25f, d[0].fridgeDuty, 0.02f)
        assertEquals(1.0f, d[0].acDuty, 0.02f)
    }

    /** A reboot leaves a gap. It must not be charged to the bin as run time. */
    @Test
    fun `a long gap is missing data, not a long sample`() {
        val cols = listOf("fridge_w", "ac_on")
        val rows = ArrayList<Pair<Long, FloatArray>>()
        // 30 min running, then 15 min of nothing logged, then 15 min idle.
        for (i in 0..180) rows.add((HOUR + i * 10L) to floatArrayOf(20f, 1f))
        for (i in 0..90) rows.add((HOUR + 2700L + i * 10L) to floatArrayOf(0f, 1f))
        val d = Analysis.duty(table(cols, *rows.toTypedArray()), binS = 3600)
        assertEquals(1, d.size)
        // 1800 s running of 2700 s observed - not of the full 3600 s, which
        // would read 0.5 and quietly credit the outage as idle time.
        assertEquals(0.667f, d[0].fridgeDuty, 0.03f)
    }

    @Test
    fun `a bin with barely any samples reports nothing`() {
        val cols = listOf("fridge_w", "ac_on")
        val rows = (0 until 5).map {
            (1_758_000_000L + it * 10L) to floatArrayOf(20f, 1f)
        }
        assertTrue(Analysis.duty(table(cols, *rows.toTypedArray()), binS = 3600).isEmpty())
    }

    // --------------------------------------------------------------- numerics

    @Test
    fun `timeMean refuses a mean over a fraction of the span`() {
        val ep = LongArray(11) { 1_758_000_000L + it * 10L }
        val mostlyMissing = FloatArray(11) { if (it < 3) 100f else Float.NaN }
        assertTrue(Analysis.timeMean(ep, mostlyMissing, 0, 10).isNaN())
        val complete = FloatArray(11) { 100f }
        assertEquals(100f, Analysis.timeMean(ep, complete, 0, 10), 1e-3f)
    }

    @Test
    fun `integrateWh is watt-hours and skips gaps`() {
        val ep = LongArray(361) { 1_758_000_000L + it * 10L }
        val w = FloatArray(361) { 100f }
        assertEquals("100 W for an hour", 100f, Analysis.integrateWh(ep, w, 0, 360), 0.1f)
    }

    @Test
    fun `median ignores NAN`() {
        val v = floatArrayOf(1f, Float.NaN, 3f, 2f, Float.NaN)
        assertEquals(2f, Analysis.median(v, 0, 4), 1e-6f)
    }

    @Test
    fun `summarise reproduces the shape of M14's own summary`() {
        val s = Analysis.summarise(listOf(43f, 48f, 52f, 55f, 59f))!!
        assertEquals(5, s.n)
        assertEquals(51.4f, s.mean, 0.1f)
        assertEquals(43f, s.min, 1e-6f)
        assertEquals(59f, s.max, 1e-6f)
        assertTrue(s.sd > 5f && s.sd < 7f)
    }
}
