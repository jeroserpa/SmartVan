package van.supervisor.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatDialog
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import java.util.concurrent.Executor
import kotlin.math.max

/**
 * One History chart, full screen, to be explored (D-28).
 *
 * The cards on the History tab are still pictures of a range, which is right
 * for a glance and wrong for the questions the archive is for: where in the
 * night did the coast start, how fast did the cabinet climb, what was the
 * load at 14:10. With weeks of 10 s rows, one card-width of plot holds
 * thousands of samples per pixel and none of that can be read off it.
 *
 * So a card opens into this. Pinch and drag move through time, tap reads the
 * values at an instant, the legend hides a series, and the line above the
 * plot gives min, max and mean of what is in view. The buttons at the bottom
 * do what the gestures do.
 *
 * A chart drawn from logged columns has a [Source], and can then go anywhere
 * in the archive: each move asks the store for that window at about two
 * points per pixel (min and max of each slice, [HistoryStore.traces]), so the
 * cost is the screen's width whatever the archive's length. A derived chart
 * (overhead, duty) has none and explores only what it was given - those are
 * computed over the range chosen on the tab.
 *
 * Read-only, like the rest of the History tab (D-25). It commands nothing.
 */
class ChartExplorer(
    private val context: Context,
    private val title: String,
    private val series: List<ChartView.Series>,
    private val empty: String,
    private val source: Source?,
    /** The History tab's own worker, so the store is only ever used from one thread. */
    private val io: Executor,
) {

    /** Re-reads a logged chart for any window. Called on [io]. */
    interface Source {
        /** What the archive holds, or null when it is empty. */
        fun extent(): Pair<Long, Long>?

        /** The chart's series over `[from, to]`, at about [points] points each. */
        fun load(from: Long, to: Long, points: Int): List<ChartView.Series>
    }

    private val main = Handler(Looper.getMainLooper())
    private var dialog: AppCompatDialog? = null
    private lateinit var chart: ChartView
    private lateinit var window: TextView
    private lateinit var stats: TextView
    private lateinit var reset: MaterialButton
    private lateinit var hint: TextView

    /** The window the chart opened on, for Reset. */
    private var homeFrom = 0L
    private var homeTo = 0L

    /** What the chart holds now: window, and seconds per point. */
    private var loadedFrom = 0L
    private var loadedTo = 0L
    private var loadedRes = Long.MAX_VALUE
    private var inFlight = false
    private var again = false
    private val fetchSoon = Runnable { fetch() }

    val showing: Boolean get() = dialog?.isShowing == true

    fun show() {
        val d = AppCompatDialog(context, R.style.Theme_Van_Explorer)
        d.setContentView(R.layout.dialog_chart)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog = d

        chart = d.findViewById<ChartView>(R.id.x_chart)!!
        window = d.findViewById<TextView>(R.id.x_window)!!
        stats = d.findViewById<TextView>(R.id.x_stats)!!
        reset = d.findViewById<MaterialButton>(R.id.x_reset)!!
        hint = d.findViewById<TextView>(R.id.x_hint)!!
        d.findViewById<TextView>(R.id.x_title)!!.text = title
        d.findViewById<View>(R.id.x_close)!!.setOnClickListener { d.dismiss() }
        d.findViewById<View>(R.id.x_earlier)!!.setOnClickListener { chart.panWindows(-0.8f) }
        d.findViewById<View>(R.id.x_later)!!.setOnClickListener { chart.panWindows(0.8f) }
        d.findViewById<View>(R.id.x_out)!!.setOnClickListener { chart.zoomBy(0.5f) }
        d.findViewById<View>(R.id.x_in)!!.setOnClickListener { chart.zoomBy(2f) }
        reset.setOnClickListener { chart.setView(homeFrom, homeTo) }
        d.setOnDismissListener { main.removeCallbacks(fetchSoon) }

        chart.interactive = true
        chart.setData(series, empty)
        homeFrom = chart.viewFrom()
        homeTo = chart.viewTo()
        loadedFrom = homeFrom
        loadedTo = homeTo
        loadedRes = series.filter { it.x.size > 1 }.minOfOrNull { it.step } ?: Long.MAX_VALUE
        chart.onViewport = { from, to ->
            caption(from, to)
            main.removeCallbacks(fetchSoon)
            main.postDelayed(fetchSoon, 120)
        }
        chart.onReadout = { statsLine() }
        caption(homeFrom, homeTo)
        d.show()
        last = this

        // The whole archive becomes reachable once the store has said how
        // much there is; until then the chart pans over what the card held.
        source?.let { src ->
            io.execute {
                val ext = runCatching { src.extent() }.getOrNull() ?: return@execute
                main.post { if (showing) { chart.setExtent(ext.first, ext.second); caption(chart.viewFrom(), chart.viewTo()) } }
            }
        }
    }

    fun dismiss() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    /**
     * Ask the store for what is in view, unless the chart already has it at
     * the resolution the screen can show. Half a window either side, so a
     * drag does not run off the end of the data before the next read lands.
     * One read at a time; a move during a read asks again when it returns.
     */
    private fun fetch() {
        val src = source ?: return
        if (!showing) return
        if (inFlight) { again = true; return }
        val from = chart.viewFrom()
        val to = chart.viewTo()
        val span = max(1L, to - from)
        val px = chart.plotWidthPx()
        val want = max(1L, span / px)
        if (from >= loadedFrom && to <= loadedTo && loadedRes <= want * 2) return

        val qFrom = max(chart.extentFrom(), from - span / 2)
        val qTo = minOf(chart.extentTo(), to + span / 2)
        // About one point per pixel across the window and its margins.
        val points = max(64, (px * (qTo - qFrom) / span).toInt())
        inFlight = true
        io.execute {
            val got = runCatching { src.load(qFrom, qTo, points) }.getOrNull()
            main.post {
                inFlight = false
                if (!showing) return@post
                if (got != null && got.any { it.x.isNotEmpty() }) {
                    chart.setData(got, empty, keepView = true)
                    loadedFrom = qFrom
                    loadedTo = qTo
                    loadedRes = max(1L, (qTo - qFrom) / points)
                    statsLine()
                }
                if (again) { again = false; fetch() }
            }
        }
    }

    private fun caption(from: Long, to: Long) {
        window.text = "${range(from, to)} · ${length(to - from)}"
        reset.visibility = if (from == homeFrom && to == homeTo) View.INVISIBLE else View.VISIBLE
        statsLine()
    }

    /** "fridge 1.2–7.8° · mean 4.3   cabin 18.0–26.5° · mean 22.1" over what is in view. */
    private fun statsLine() {
        val s = chart.visibleStats()
        stats.text = if (s.isEmpty()) context.getString(R.string.h_no_rows)
        else s.joinToString("    ") { st ->
            val u = if (st.unit == "°" || st.unit == "%") st.unit else " ${st.unit}"
            "${st.label} ${num(st.min)}–${num(st.max)}$u · mean ${num(st.mean)}"
        }
        // Once someone has found the gestures, the hint is just clutter.
        if (chart.cursorAt() != null) hint.visibility = View.INVISIBLE
    }

    private fun num(v: Float): String = if (kotlin.math.abs(v) >= 100f) "%.0f".format(v) else "%.1f".format(v)

    /** "Tue 13 Oct 06:00–14:00", or both ends dated when they are different days. */
    private fun range(from: Long, to: Long): String {
        val a = Calendar.getInstance().apply { timeInMillis = from * 1000L }
        val b = Calendar.getInstance().apply { timeInMillis = to * 1000L }
        val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) "${day(a)} ${hm(a)}–${hm(b)}" else "${day(a)} ${hm(a)} – ${day(b)} ${hm(b)}"
    }

    private fun day(c: Calendar): String =
        "${DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"

    private fun hm(c: Calendar): String =
        "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))

    private fun length(s: Long): String = when {
        s < 2 * 3600 -> "${(s + 30) / 60} min"
        s < 72 * 3600 -> "${(s + 1800) / 3600} h"
        else -> "%.1f days".format(s / 86_400f)
    }

    /** Instrumented tests only: the dialog is not in the activity's view tree. */
    @VisibleForTesting
    internal val chartView: ChartView get() = chart

    @VisibleForTesting
    internal fun statsText(): String = stats.text.toString()

    companion object {
        /** Instrumented tests only: the explorer most recently opened. */
        @VisibleForTesting
        internal var last: ChartExplorer? = null

        private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}
