package van.supervisor.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The History tab: van-core's log, kept on the phone and reduced to the
 * numbers `docs/measurements.md` is waiting on.
 *
 * It is native rather than another page for one structural reason
 * (docs/decisions.md D-22): `soak_log`'s ring is volatile PSRAM, lost on a
 * power cut, and holds ~4.3 days. This archive survives both, and it works
 * with the van 200 km away — which is when you actually sit down to read it.
 *
 * Every panel leads with a **sentence**, not a chart. A plot nobody can
 * interpret leaves the project exactly where it was: with a CSV and a person
 * doing arithmetic by hand.
 */
class HistoryScreen(private val root: View) {

    private val context: Context = root.context
    private val store = HistoryStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val archive: TextView = root.findViewById(R.id.h_archive)
    private val status: TextView = root.findViewById(R.id.h_status)
    private val syncBtn: MaterialButton = root.findViewById(R.id.h_sync)
    private val exportBtn: MaterialButton = root.findViewById(R.id.h_export)
    private val progress: CircularProgressIndicator = root.findViewById(R.id.h_progress)
    private val ranges: MaterialButtonToggleGroup = root.findViewById(R.id.h_range)

    private val temp = Card(root.findViewById(R.id.card_temp))
    private val power = Card(root.findViewById(R.id.card_power))
    private val overhead = Card(root.findViewById(R.id.card_overhead))
    private val duty = Card(root.findViewById(R.id.card_duty))

    private val epNote: TextView = root.findViewById(R.id.h_ep_note)
    private val epBody: TextView = root.findViewById(R.id.h_ep_body)

    private var rangeS: Long = 86_400L
    private var syncing = false

    /** Title, one-line reading, plot. */
    private class Card(view: View) {
        val title: TextView = view.findViewById(R.id.c_title)
        val read: TextView = view.findViewById(R.id.c_read)
        val chart: ChartView = view.findViewById(R.id.c_chart)
    }

    init {
        syncBtn.setOnClickListener { sync(full = false) }
        exportBtn.setOnClickListener { export() }
        ranges.check(R.id.h_range_1d)
        ranges.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            rangeS = when (id) {
                R.id.h_range_1d -> 86_400L
                R.id.h_range_3d -> 3 * 86_400L
                R.id.h_range_7d -> 7 * 86_400L
                else -> Long.MAX_VALUE / 2
            }
            redraw()
        }
        redraw()
    }

    /** Called when the tab becomes visible. */
    fun onShown() {
        redraw()
        // One automatic attempt per visit: the phone is usually at the van
        // when this screen is opened, and a sync nobody had to ask for is the
        // difference between an archive and a chore.
        if (!syncing) sync(full = false, quiet = true)
    }

    // ------------------------------------------------------------------- sync

    private fun sync(full: Boolean, quiet: Boolean = false) {
        if (syncing) return
        syncing = true
        progress.visibility = View.VISIBLE
        syncBtn.isEnabled = false
        if (!quiet) status.text = context.getString(R.string.h_syncing)

        io.execute {
            val r = runCatching { HistorySync.sync(context, store, full) }.getOrNull()
            main.post {
                syncing = false
                progress.visibility = View.GONE
                syncBtn.isEnabled = true
                status.text = describe(r, quiet)
                redraw()
            }
        }
    }

    private fun describe(r: HistorySync.Result?, quiet: Boolean): CharSequence {
        if (r == null) return context.getString(R.string.h_sync_failed)
        if (r.ok) {
            val parts = ArrayList<String>()
            parts.add(
                if (r.added == 0) context.getString(R.string.h_up_to_date)
                else context.getString(R.string.h_added, r.added)
            )
            if (r.nodeCapacity > 0) {
                val pct = (100f * r.nodeRows / r.nodeCapacity).roundToInt()
                parts.add(context.getString(R.string.h_node_buffer, r.nodeRows, pct))
            }
            // The one condition worth naming: rows the node could not date.
            // They are dropped, and a log full of them means nobody has ever
            // opened a page to hand the node a clock.
            if (r.undateable > 0) parts.add(context.getString(R.string.h_undateable, r.undateable))
            return parts.joinToString(" · ")
        }
        // Out of range is the normal case, not an error. Say which of the
        // three it is, the way the widget's footer had to learn to (D-20).
        return when (r.miss) {
            HistorySync.Miss.NO_WIFI ->
                if (quiet) context.getString(R.string.h_offline_quiet)
                else context.getString(R.string.h_no_wifi)
            HistorySync.Miss.NO_ANSWER -> context.getString(R.string.h_no_answer)
            HistorySync.Miss.NOT_VAN_CORE -> context.getString(R.string.h_not_van_core)
            HistorySync.Miss.NO_LOG -> context.getString(R.string.h_no_log)
            null -> ""
        }
    }

    /**
     * Export what is on screen, not the whole archive.
     *
     * Two reasons, and the second is the load-bearing one. The range you are
     * looking at is almost always the run you want to send somewhere. And the
     * archive has no upper bound — it is years of 10 s samples by design — so
     * rendering all of it into one String, then into one ByteArray, is an
     * out-of-memory kill on the day it finally matters. The range is bounded
     * by construction.
     */
    private fun export() {
        io.execute {
            val (from, to) = window()
            val t = store.read(from, to)
            if (t.isEmpty) {
                main.post { status.text = context.getString(R.string.h_no_rows) }
                return@execute
            }
            val name = "van-history-${file(t.rows.first().epoch)}-${file(t.rows.last().epoch)}.csv"
            // Written off the main thread: this is megabytes, and saveText
            // does not touch the WebView, so there is nothing to marshal.
            val msg = (context as? MainActivity)?.saveText(name, toCsv(t))
                ?: context.getString(R.string.h_export_failed)
            main.post { status.text = msg }
        }
    }

    /** The epoch range the current tab selection is showing. */
    private fun window(): Pair<Long, Long> {
        val span = store.span()
        if (span.rows == 0) return 0L to 0L
        val to = span.newest
        val from = if (rangeS > 1_000_000_000L) 0L else to - rangeS
        return from to to
    }

    private fun toCsv(t: SoakCsv.Table): String {
        val sb = StringBuilder(t.rows.size * 120)
        sb.append("# van-core history, exported from the phone's archive\n")
        sb.append("epoch,local_time,boot,").append(t.columns.joinToString(",")).append('\n')
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK)
        for (r in t.rows) {
            sb.append(r.epoch).append(',').append(f.format(Date(r.epoch * 1000L)))
                .append(',').append(r.boot)
            for (v in r.v) {
                sb.append(',')
                if (!v.isNaN()) sb.append(v)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ----------------------------------------------------------------- render

    private fun redraw() {
        io.execute {
            val span = store.span()
            val (from, to) = window()
            val t = if (span.rows == 0) SoakCsv.Table(emptyList(), emptyList())
                    else store.read(from, to)
            val bins = Analysis.overhead(t)
            val dutyBins = Analysis.duty(t)
            val eps = Analysis.episodes(t)
            main.post { paint(span, t, bins, dutyBins, eps) }
        }
    }

    private fun paint(
        span: HistoryStore.Span,
        t: SoakCsv.Table,
        bins: List<Analysis.OverheadBin>,
        dutyBins: List<Analysis.DutyBin>,
        eps: Analysis.Episodes?,
    ) {
        archive.text = if (span.rows == 0) {
            context.getString(R.string.h_archive_empty)
        } else {
            context.getString(
                R.string.h_archive, span.rows,
                days(span.newest - span.oldest), stamp(span.newest),
            )
        }

        val x = t.epochs()

        // --- temperatures ---------------------------------------------------
        temp.title.setText(R.string.h_temp)
        val fridge = t.col("t_fridge")
        val cabin = t.col("t_cabin")
        temp.chart.setData(
            listOfNotNull(
                fridge?.let { ChartView.Series("fridge", c(R.color.alt), x, it, unit = "°") },
                cabin?.let { ChartView.Series("cabin", c(R.color.warn), x, it, unit = "°") },
            ),
            context.getString(R.string.h_no_rows),
        )
        temp.read.text = if (fridge == null) context.getString(R.string.h_no_probe)
            else context.getString(
                R.string.h_temp_read,
                last(fridge), mean(fridge), peak(fridge),
            )

        // --- power ----------------------------------------------------------
        power.title.setText(R.string.h_power)
        val inW = t.col("in_w")
        val outW = t.col("out_w")
        val fridgeW = t.col("fridge_w")
        power.chart.setData(
            listOfNotNull(
                inW?.let { ChartView.Series("in", c(R.color.ok), x, it, unit = "W") },
                outW?.let { ChartView.Series("out", c(R.color.bad), x, it, unit = "W") },
                fridgeW?.let { ChartView.Series("fridge", c(R.color.alt), x, it, unit = "W") },
            ),
            context.getString(R.string.h_no_rows),
        )
        power.read.text = if (fridgeW == null) context.getString(R.string.h_no_plug)
            else context.getString(R.string.h_power_read, mean(fridgeW), wh(t, "fridge_w"))

        // --- station overhead: §8.2, and the reason this screen exists -------
        overhead.title.setText(R.string.h_overhead)
        val s = Analysis.summarise(bins.map { it.overheadW })
        val est = Analysis.overheadEstimate(bins)
        overhead.chart.setData(
            listOf(
                ChartView.Series(
                    "overhead", c(R.color.warn),
                    LongArray(bins.size) { (bins[it].from + bins[it].to) / 2 },
                    FloatArray(bins.size) { bins[it].overheadW },
                    kind = ChartView.Kind.BAR, unit = "W",
                )
            ),
            context.getString(R.string.h_overhead_none),
        )
        // The headline figure is the weighted estimate, with BOTH error terms.
        // Quoting the statistical one alone would claim a tenth of a watt on a
        // number whose pack-capacity band is worth several.
        overhead.read.text = if (s == null || est == null)
            context.getString(R.string.h_overhead_wait)
        else context.getString(
            R.string.h_overhead_read,
            est.watts, est.se, est.systematicW, s.min, s.max, est.n,
        )

        // --- duty ------------------------------------------------------------
        duty.title.setText(R.string.h_duty)
        val haveFridgeDuty = dutyBins.any { !it.fridgeDuty.isNaN() }
        duty.chart.setData(
            listOfNotNull(
                if (haveFridgeDuty) ChartView.Series(
                    "compressor", c(R.color.alt),
                    LongArray(dutyBins.size) { (dutyBins[it].from + dutyBins[it].to) / 2 },
                    FloatArray(dutyBins.size) { dutyBins[it].fridgeDuty * 100f },
                    kind = ChartView.Kind.BAR, unit = "%",
                ) else null,
                if (dutyBins.any { !it.acDuty.isNaN() }) ChartView.Series(
                    "inverter", c(R.color.dim),
                    LongArray(dutyBins.size) { (dutyBins[it].from + dutyBins[it].to) / 2 },
                    FloatArray(dutyBins.size) { dutyBins[it].acDuty * 100f },
                    kind = ChartView.Kind.LINE, unit = "%",
                ) else null,
            ),
            context.getString(R.string.h_no_rows),
        )
        duty.read.text = when {
            dutyBins.isEmpty() -> context.getString(R.string.h_duty_wait)
            !haveFridgeDuty -> context.getString(R.string.h_no_plug)
            else -> {
                val d = dutyBins.map { it.fridgeDuty }.average().toFloat() * 100f
                context.getString(R.string.h_duty_read, d, dutyBins.size)
            }
        }

        paintEpisodes(eps)
    }

    /**
     * The two episode tables, and the only place the pulldown penalty can come
     * from: Wh per kelvin over a run block, compared across blocks that
     * followed different rest lengths.
     */
    private fun paintEpisodes(eps: Analysis.Episodes?) {
        if (eps == null || (eps.cooling.isEmpty() && eps.coasting.isEmpty())) {
            epNote.setText(R.string.h_ep_wait)
            epBody.text = ""
            return
        }
        epNote.text = when (eps.source) {
            Analysis.Source.PLUG -> context.getString(R.string.h_ep_plug)
            // Say it plainly: without the plug, "running" means the inverter
            // was on, and cooking looks exactly like a run block.
            Analysis.Source.INVERTER -> context.getString(R.string.h_ep_inverter)
        }

        val sb = StringBuilder()
        if (eps.cooling.isNotEmpty()) {
            sb.append(context.getString(R.string.h_ep_cool)).append('\n')
            for (e in eps.cooling.takeLast(6)) sb.append(line(e, true)).append('\n')
        }
        if (eps.coasting.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(context.getString(R.string.h_ep_coast)).append('\n')
            for (e in eps.coasting.takeLast(6)) sb.append(line(e, false)).append('\n')
        }
        val whPerK = eps.cooling.map { it.whPerK }.filter { !it.isNaN() }
        if (whPerK.size >= 2) {
            val sum = Analysis.summarise(whPerK)!!
            sb.append('\n').append(
                context.getString(R.string.h_ep_pulldown, sum.mean, sum.min, sum.max, sum.n)
            )
        }
        epBody.text = sb.toString()
    }

    private fun line(e: Analysis.Episode, cooling: Boolean): String {
        val t = SimpleDateFormat("dd HH:mm", Locale.UK).format(Date(e.from * 1000L))
        val mins = (e.durationS / 60f).roundToInt()
        val sb = StringBuilder()
        sb.append(t).append("  ").append("%3d min".format(mins))
        sb.append("  %5.2f→%5.2f°".format(e.startC, e.endC))
        if (cooling) {
            if (!e.wh.isNaN()) sb.append("  %5.1f Wh".format(e.wh))
            if (!e.whPerK.isNaN()) sb.append("  %4.1f Wh/K".format(e.whPerK))
        } else {
            sb.append("  %+5.2f K/h".format(e.slopeKPerH))
        }
        e.fit?.let { sb.append("  τ%3.0f min".format(it.tauMin)) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ small

    private fun c(res: Int) = context.getColor(res)

    private fun last(v: FloatArray): Float {
        for (i in v.indices.reversed()) if (!v[i].isNaN()) return v[i]
        return Float.NaN
    }

    private fun mean(v: FloatArray): Float {
        var s = 0.0
        var n = 0
        for (x in v) if (!x.isNaN()) { s += x; n++ }
        return if (n == 0) Float.NaN else (s / n).toFloat()
    }

    /** Highest non-NAN value. Named `peak` so it cannot shadow kotlin.maxOf. */
    private fun peak(v: FloatArray): Float {
        var m = Float.NaN
        for (x in v) if (!x.isNaN() && (m.isNaN() || x > m)) m = x
        return m
    }

    /** Watt-hours under a column across the whole loaded range. */
    private fun wh(t: SoakCsv.Table, name: String): Float {
        val v = t.col(name) ?: return Float.NaN
        if (t.rows.size < 2) return Float.NaN
        return Analysis.integrateWh(t.epochs(), v, 0, t.rows.size - 1)
    }

    private fun days(s: Long): Float = s / 86_400f

    private fun file(epoch: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.UK).format(Date(epoch * 1000L))

    private fun stamp(epoch: Long): String =
        if (epoch <= 0) "—"
        else SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date(epoch * 1000L))

    fun close() {
        io.shutdown()
        store.close()
    }
}
