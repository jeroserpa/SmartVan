package van.supervisor.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A time-series plot, drawn straight onto a Canvas.
 *
 * Canvas rather than a charting library, for the same reason `LiquidFill.kt`
 * is: the release APK is deliberately shrunk (Material + AppCompat already
 * took it from 2 MB to 6 MB, which `app/build.gradle.kts` calls out as too big
 * to hand over on mobile data), and a chart library would cost more than the
 * screen it draws.
 *
 * Two uses. On the History tab it is a still picture of the chosen range. In
 * [ChartExplorer] it is [interactive]: pinch to zoom the time axis, drag to
 * pan, double-tap to zoom in, tap for the values at an instant, long-press and
 * slide to scrub, tap a legend entry to hide that series.
 *
 * Conventions taken from the rest of the project:
 *  - **A gap is drawn as a gap.** NAN breaks the line, and so does a hole in
 *    time several sample intervals long, rather than joining across it. A
 *    reboot must look like a reboot, not like a slow ramp.
 *  - **Nothing is averaged away.** Where there are more samples than pixels,
 *    each pixel column draws the minimum and maximum of the samples under it
 *    (and a series that is already bucketed carries its own maximum in
 *    [Series.hi]). A 30 s spike in a month of data stays visible; a plain
 *    downsample would drop it, and an average would halve it.
 *  - **Colour never carries meaning alone** (CLAUDE.md §2): every series is in
 *    the legend by name, and the axis it belongs to is named with it.
 *  - Figures are tabular and the palette is `ui/index.html`'s, so this does
 *    not look like a different app from the page one tab across.
 */
class ChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Kind { LINE, BAR }
    enum class Axis { LEFT, RIGHT }

    class Series(
        val label: String,
        val color: Int,
        val x: LongArray,
        val y: FloatArray,
        val kind: Kind = Kind.LINE,
        val axis: Axis = Axis.LEFT,
        /** Appended to the axis labels, e.g. "°C" or "W". */
        val unit: String = "",
        /**
         * Where each point stands for a slice of time rather than one sample:
         * the slice's maximum, with its minimum in [y]. Null for plain samples.
         */
        val hi: FloatArray? = null,
    ) {
        /** Typical spacing between points, seconds: the gap threshold and the bar width. */
        val step: Long = medianStep(x)
    }

    /** One series' figures over what is on screen; see [visibleStats]. */
    class Stats(val label: String, val unit: String, val min: Float, val max: Float, val mean: Float, val n: Int)

    /** Where one frame's plot sits, and how time and value map onto it. */
    private class Frame(
        val from: Long, val span: Float,
        val padL: Float, val plotW: Float, val padT: Float, val plotH: Float,
    ) {
        fun px(t: Long) = padL + (t - from).toFloat() / span * plotW
        fun py(v: Float, r: Pair<Float, Float>) = padT + plotH - (v - r.first) / (r.second - r.first) * plotH
    }

    private val d = resources.displayMetrics.density
    private var series: List<Series> = emptyList()
    private val hidden = HashSet<String>()
    private var emptyText: String = ""

    /** What may be panned across, epoch seconds. Defaults to the data's own span. */
    private var extFrom = 0L
    private var extTo = 1L
    private var extentSet = false
    /** What is on screen. */
    private var viewFrom = 0L
    private var viewTo = 1L
    /** The instant the readout is for, or null. */
    private var cursor: Long? = null

    /** Gestures on. Off for the History cards, which scroll with the page and open on a tap. */
    var interactive = false

    /** Called whenever the visible window moves, on the main thread. */
    var onViewport: ((from: Long, to: Long) -> Unit)? = null

    /** Called when the cursor moves or the hidden set changes, so a caption can follow. */
    var onReadout: (() -> Unit)? = null

    /** Drawn across the plot at a fixed left-axis value, e.g. a threshold. */
    private var marker: Pair<Float, Int>? = null

    private val grid = paint(color(R.color.line), 1f)
    private val axisText = textPaint(color(R.color.dim), 10f)
    private val dayText = textPaint(color(R.color.muted), 10f)
    private val legendText = textPaint(color(R.color.muted), 11f)
    private val cursorText = textPaint(color(R.color.fg), 11f)
    private val cursorLine = paint(color(R.color.fg), 1f).apply { alpha = 150 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val legendHits = ArrayList<Pair<RectF, String>>()

    // Layout of the last frame, for the gesture maths.
    private var padL = 0f
    private var plotW = 1f

    fun setData(data: List<Series>, empty: String = "no data yet", keepView: Boolean = false) {
        series = data
        emptyText = empty
        val (lo, hi) = dataExtent(data)
        if (!extentSet) {
            extFrom = lo
            extTo = hi
        }
        if (!keepView || viewTo <= viewFrom) {
            viewFrom = lo
            viewTo = hi
        }
        clampView()
        invalidate()
    }

    /** Widen what can be panned across beyond the data in hand, e.g. to the whole archive. */
    fun setExtent(from: Long, to: Long) {
        if (to <= from) return
        extentSet = true
        extFrom = min(from, viewFrom)
        extTo = max(to, viewTo)
        clampView()
        invalidate()
    }

    fun setMarker(value: Float?, color: Int) {
        marker = value?.let { it to color }
        invalidate()
    }

    fun viewFrom(): Long = viewFrom
    fun viewTo(): Long = viewTo
    fun extentFrom(): Long = extFrom
    fun extentTo(): Long = extTo
    fun cursorAt(): Long? = cursor

    /** Width of the plot area, px: how many points are worth asking for. */
    fun plotWidthPx(): Int = max(1, plotW.roundToInt())

    /** Show `[from, to]`, kept inside the extent. */
    fun setView(from: Long, to: Long) {
        viewFrom = from
        viewTo = to
        clampView()
        onViewport?.invoke(viewFrom, viewTo)
        invalidate()
    }

    /** Put the readout at [t], or clear it. */
    fun showCursor(t: Long?) {
        cursor = t
        onReadout?.invoke()
        invalidate()
    }

    /** Zoom about the middle; factor > 1 zooms in. */
    fun zoomBy(factor: Float) = zoomAt(padL + plotW / 2f, factor)

    /** Step by a fraction of the window: -0.8 is most of a screen earlier. */
    fun panWindows(fraction: Float) {
        val dt = ((viewTo - viewFrom) * fraction).toLong()
        setView(viewFrom + dt, viewTo + dt)
    }

    fun isWholeExtent(): Boolean = viewFrom <= extFrom && viewTo >= extTo

    private fun color(res: Int) = context.getColor(res)

    private fun paint(c: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = c
        strokeWidth = widthDp * d
        style = Paint.Style.STROKE
    }

    private fun textPaint(c: Int, sizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = c
        textSize = sizeSp * resources.displayMetrics.scaledDensity
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        val live = series.filter { it.x.isNotEmpty() }
        val shown = live.filter { it.label !in hidden }
        legendHits.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        padL = 42f * d
        val padR = (if (shown.any { it.axis == Axis.RIGHT }) 40f else 10f) * d
        val legendH = if (live.isEmpty()) 0f else legendHeight(live, w)
        val padT = 6f * d + legendH
        val padB = 18f * d
        plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW <= 0 || plotH <= 0) return

        if (live.isEmpty()) {
            val t = textPaint(color(R.color.dim), 12f)
            canvas.drawText(emptyText, padL, h / 2f, t)
            return
        }

        val from = viewFrom
        val to = max(viewTo, viewFrom + 1)
        val span = (to - from).toFloat()
        fun px(t: Long) = padL + (t - from).toFloat() / span * plotW

        val left = shown.filter { it.axis == Axis.LEFT }
        val right = shown.filter { it.axis == Axis.RIGHT }
        val lr = range(left, from, to)
        val rr = range(right, from, to)
        fun py(v: Float, r: Pair<Float, Float>) =
            padT + plotH - (v - r.first) / (r.second - r.first) * plotH

        // --- grid and y labels ----------------------------------------------
        if (left.isNotEmpty()) {
            axisText.textAlign = Paint.Align.RIGHT
            for (t in ticks(lr.first, lr.second)) {
                val y = py(t, lr)
                if (y < padT - 1 || y > padT + plotH + 1) continue
                canvas.drawLine(padL, y, padL + plotW, y, grid)
                canvas.drawText(fmt(t) + left[0].unit, padL - 4f * d, y + 3.5f * d, axisText)
            }
        }
        if (right.isNotEmpty()) {
            axisText.textAlign = Paint.Align.LEFT
            for (t in ticks(rr.first, rr.second)) {
                val y = py(t, rr)
                if (y < padT - 1 || y > padT + plotH + 1) continue
                if (left.isEmpty()) canvas.drawLine(padL, y, padL + plotW, y, grid)
                canvas.drawText(fmt(t) + right[0].unit, padL + plotW + 4f * d, y + 3.5f * d, axisText)
            }
        }

        // --- x labels: local time, and the day wherever a day starts ---------
        val maxTicks = max(2, (plotW / (58f * d)).toInt())
        val (step, tks) = timeTicks(from, to, maxTicks)
        for (t in tks) {
            val x = px(t)
            if (x < padL || x > padL + plotW) continue
            canvas.drawLine(x, padT, x, padT + plotH, grid)
            val day = step >= DAY || isMidnight(t)
            val label = timeLabel(t, step)
            val p = if (day) dayText else axisText
            p.textAlign = Paint.Align.CENTER
            val half = p.measureText(label) / 2f
            // A label that would hang off either edge is dropped; its line stays.
            if (x - half < 0f || x + half > w) continue
            canvas.drawText(label, x, h - 5f * d, p)
        }

        marker?.let { (v, c) ->
            if (left.isNotEmpty() && v >= lr.first && v <= lr.second) {
                val mp = paint(c, 1f).apply { alpha = 140 }
                canvas.drawLine(padL, py(v, lr), padL + plotW, py(v, lr), mp)
            }
        }

        // --- series, clipped to the plot -------------------------------------
        val frame = Frame(from, span, padL, plotW, padT, plotH)
        canvas.save()
        canvas.clipRect(padL, padT - 2f * d, padL + plotW, padT + plotH + 2f * d)
        for (s in shown) {
            val r = if (s.axis == Axis.LEFT) lr else rr
            when (s.kind) {
                Kind.BAR -> drawBars(canvas, s, frame, r)
                Kind.LINE -> drawLine(canvas, s, frame, r)
            }
        }
        canvas.restore()

        // --- cursor ----------------------------------------------------------
        val c = cursor
        val readings = HashMap<String, String>()
        if (c != null && c in from..to) {
            val x = px(c)
            canvas.drawLine(x, padT, x, padT + plotH, cursorLine)
            for (s in shown) {
                val i = nearest(s, c) ?: continue
                val r = if (s.axis == Axis.LEFT) lr else rr
                val lo = s.y[i]
                val hi = s.hi?.get(i) ?: lo
                readings[s.label] = value(lo, hi, s.unit)
                fill.color = s.color
                fill.alpha = 255
                canvas.drawCircle(x, py((lo + hi) / 2f, r), 3.2f * d, fill)
            }
        }

        // --- legend: the readout too, while there is a cursor ----------------
        drawLegend(canvas, live, w, if (c != null && c in from..to) c else null, readings)
    }

    /**
     * A line, one pixel column at a time: each column draws from where the
     * line enters it, down to its lowest sample, up to its highest, and out
     * to where it leaves. With fewer samples than pixels that is just the
     * line through every sample; with more, it is the full envelope, at a
     * cost that does not grow with the archive.
     */
    private fun drawLine(canvas: Canvas, s: Series, f: Frame, r: Pair<Float, Float>) {
        val from = f.from
        val to = f.from + f.span.toLong()
        fun px(t: Long) = f.px(t)
        fun py(v: Float) = f.py(v, r)
        val x = s.x
        val lo = s.y
        val hi = s.hi
        if (x.isEmpty()) return
        // One point either side of the window, so the line runs off the edge
        // instead of stopping short of it.
        val start = max(0, lowerBound(x, from) - 1)
        val end = min(x.size, upperBound(x, to) + 1)
        val gap = gapS(s)

        stroke.color = s.color
        stroke.strokeWidth = 1.6f * d
        path.reset()
        var pen = false
        var col = Int.MIN_VALUE
        var cx = 0f
        var first = 0f
        var last = 0f
        var cMin = 0f
        var cMax = 0f
        var have = false
        var prevT = Long.MIN_VALUE

        fun flush() {
            if (!have) return
            val yF = py(first)
            if (pen) path.lineTo(cx, yF) else { path.moveTo(cx, yF); pen = true }
            val yLo = py(cMin)
            val yHi = py(cMax)
            if (abs(yLo - yHi) > 0.75f) {
                path.lineTo(cx, yLo)
                path.lineTo(cx, yHi)
            }
            path.lineTo(cx, py(last))
            have = false
        }

        for (k in start until end) {
            val t = x[k]
            val a = lo[k]
            val b = hi?.get(k) ?: a
            if (a.isNaN() || b.isNaN()) {
                // The break is the point: a gap in the log is a reboot or an
                // out-of-range probe, and joining across it would draw a
                // trend that never happened.
                flush(); pen = false; col = Int.MIN_VALUE; prevT = Long.MIN_VALUE
                continue
            }
            if (prevT != Long.MIN_VALUE && t - prevT > gap) {
                flush(); pen = false; col = Int.MIN_VALUE
            }
            prevT = t
            val p = px(t)
            val c = floor(p).toInt()
            val mid = (a + b) / 2f
            if (c != col || !have) {
                flush()
                col = c
                cx = p
                first = mid
                cMin = min(a, b)
                cMax = max(a, b)
                have = true
            } else {
                if (a < cMin) cMin = a
                if (b > cMax) cMax = b
            }
            last = mid
        }
        flush()
        canvas.drawPath(path, stroke)
    }

    private fun drawBars(canvas: Canvas, s: Series, f: Frame, r: Pair<Float, Float>) {
        fill.color = s.color
        fill.alpha = 190
        val bw = max(1.5f * d, s.step / f.span * f.plotW * 0.8f)
        val zero = f.py(0f.coerceIn(r.first, r.second), r)
        val start = max(0, lowerBound(s.x, f.from) - 1)
        val end = min(s.x.size, upperBound(s.x, f.from + f.span.toLong()) + 1)
        for (i in start until end) {
            val v = s.y[i]
            if (v.isNaN()) continue
            val x = f.px(s.x[i])
            val y = f.py(v, r)
            canvas.drawRect(x - bw / 2, min(y, zero), x + bw / 2, max(y, zero), fill)
        }
    }

    /** Rows the legend needs at this width; it wraps rather than running off the card. */
    private fun legendHeight(live: List<Series>, w: Float): Float {
        var rows = 1
        var lx = padL
        for (s in live) {
            // Interactive, measured as wide as a readout can make it, so the
            // values appearing never wrap the legend into the plot.
            val iw = legendItemWidth(s, if (interactive) "-888.8–888.8 W" else null)
            if (lx + iw > w && lx > padL) { rows++; lx = padL }
            lx += iw
        }
        // And a row for the readout's time.
        if (interactive) rows++
        return rows * 16f * d
    }

    private fun legendItemWidth(s: Series, reading: String?): Float {
        val label = legendLabel(s, reading)
        return 11f * d + legendText.measureText(label) + 14f * d
    }

    private fun legendLabel(s: Series, reading: String?): String {
        val base = if (s.axis == Axis.RIGHT) "${s.label} →" else s.label
        return if (reading != null) "$base $reading" else base
    }

    private fun drawLegend(
        canvas: Canvas, live: List<Series>, w: Float, c: Long?, readings: Map<String, String>,
    ) {
        var lx = padL
        var ly = 10f * d
        if (interactive) {
            // The readout's time gets a row of its own, kept even while there
            // is no cursor, so tapping the plot never shifts it down.
            val head = if (c != null) stamp(c) else ""
            if (head.isNotEmpty()) {
                cursorText.textAlign = Paint.Align.LEFT
                canvas.drawText(head, lx, ly, cursorText)
            }
            ly += 16f * d
        }
        for (s in live) {
            val off = s.label in hidden
            val reading = if (off) null else readings[s.label]
            val label = legendLabel(s, reading)
            val iw = legendItemWidth(s, reading)
            if (lx + iw > w && lx > padL) { lx = padL; ly += 16f * d }
            fill.color = s.color
            fill.alpha = if (off) 70 else 255
            canvas.drawRect(lx, ly - 5f * d, lx + 8f * d, ly - 1f * d, fill)
            legendText.textAlign = Paint.Align.LEFT
            legendText.alpha = if (off) 110 else 255
            legendText.isStrikeThruText = off
            canvas.drawText(label, lx + 11f * d, ly, legendText)
            legendText.isStrikeThruText = false
            legendText.alpha = 255
            legendHits.add(RectF(lx - 4f * d, ly - 14f * d, lx + iw, ly + 6f * d) to s.label)
            lx += iw
        }
    }

    // --------------------------------------------------------------- figures

    /** Min, max and mean of each shown series over the visible window. */
    fun visibleStats(): List<Stats> {
        val out = ArrayList<Stats>()
        for (s in series) {
            if (s.label in hidden || s.x.isEmpty()) continue
            val a = lowerBound(s.x, viewFrom)
            val b = upperBound(s.x, viewTo)
            var lo = Float.NaN
            var hi = Float.NaN
            var sum = 0.0
            var n = 0
            for (i in a until b) {
                val l = s.y[i]
                val h = s.hi?.get(i) ?: l
                if (l.isNaN() || h.isNaN()) continue
                if (lo.isNaN() || l < lo) lo = l
                if (hi.isNaN() || h > hi) hi = h
                sum += (l + h) / 2.0
                n++
            }
            if (n > 0) out.add(Stats(s.label, s.unit, lo, hi, (sum / n).toFloat(), n))
        }
        return out
    }

    /** Min/max over the visible part of a set of series, padded, never zero-height. */
    private fun range(ss: List<Series>, from: Long, to: Long): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in ss) {
            val a = lowerBound(s.x, from)
            val b = upperBound(s.x, to)
            for (i in a until b) {
                val l = s.y[i]
                val h = s.hi?.get(i) ?: l
                if (!l.isNaN() && l < lo) lo = l
                if (!h.isNaN() && h > hi) hi = h
            }
        }
        if (lo > hi) return 0f to 1f
        // Bars read as magnitudes, so their axis includes zero or the eye
        // compares the wrong lengths.
        if (ss.any { it.kind == Kind.BAR }) {
            if (lo > 0f) lo = 0f
            if (hi < 0f) hi = 0f
        }
        if (hi - lo < 1e-4f) { lo -= 0.5f; hi += 0.5f }
        val pad = (hi - lo) * 0.08f
        return (lo - pad) to (hi + pad)
    }

    /** The sample nearest [t], if one is near enough to stand for that instant. */
    private fun nearest(s: Series, t: Long): Int? {
        if (s.x.isEmpty()) return null
        val i = lowerBound(s.x, t)
        var best = -1
        var bestD = Long.MAX_VALUE
        for (j in intArrayOf(i - 1, i)) {
            if (j < 0 || j >= s.x.size || s.y[j].isNaN()) continue
            val dd = abs(s.x[j] - t)
            if (dd < bestD) { bestD = dd; best = j }
        }
        if (best < 0) return null
        // Within a sample interval, or within a few pixels when zoomed out:
        // a value from across a gap is not the value at this instant.
        val pxS = (viewTo - viewFrom) / max(1f, plotW) * 4f * d
        val tol = max(s.step.toFloat() * 0.75f, pxS)
        return if (bestD <= tol) best else null
    }

    private fun gapS(s: Series): Long = max(30L, s.step * 3)

    private fun clampView() {
        val ext = max(1L, extTo - extFrom)
        val minSpan = min(ext, minSpan())
        var span = viewTo - viewFrom
        if (span < minSpan) {
            val mid = (viewFrom + viewTo) / 2
            viewFrom = mid - minSpan / 2
            viewTo = viewFrom + minSpan
            span = minSpan
        }
        if (span > ext) {
            viewFrom = extFrom
            viewTo = extTo
            return
        }
        if (viewFrom < extFrom) { viewFrom = extFrom; viewTo = extFrom + span }
        if (viewTo > extTo) { viewTo = extTo; viewFrom = extTo - span }
    }

    /** Five minutes of 10 s samples, or a few bars of a binned series. */
    private fun minSpan(): Long {
        val bars = series.filter { it.kind == Kind.BAR && it.x.size > 1 }
        return if (series.any { it.kind == Kind.LINE && it.x.isNotEmpty() } || bars.isEmpty()) 300L
        else max(300L, bars.maxOf { it.step } * 4)
    }

    // -------------------------------------------------------------- gestures

    private var scrubbing = false
    private val scroller = OverScroller(context)
    private var flingLast = 0

    private fun timeAt(x: Float): Long {
        val f = ((x - padL) / max(1f, plotW)).coerceIn(0f, 1f)
        return viewFrom + ((viewTo - viewFrom) * f).toLong()
    }

    private fun zoomAt(x: Float, factor: Float) {
        if (factor <= 0f || factor.isNaN()) return
        val t = timeAt(x)
        val span = (viewTo - viewFrom).toDouble()
        val next = span / factor
        val f = (t - viewFrom) / span
        val from = t - (next * f).toLong()
        setView(from, from + next.toLong())
    }

    private fun panPx(dx: Float) {
        val dt = (dx / max(1f, plotW) * (viewTo - viewFrom)).toLong()
        if (dt == 0L) return
        setView(viewFrom + dt, viewTo + dt)
    }

    private fun setCursor(x: Float) {
        cursor = timeAt(x)
        onReadout?.invoke()
        invalidate()
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            // The time axis only: a vertical pinch component is noise here,
            // and the y axis fits itself to whatever is in view anyway.
            val f = if (g.previousSpanX > 24f * d) g.currentSpanX / g.previousSpanX else g.scaleFactor
            zoomAt(g.focusX, f)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale.isInProgress || e2.pointerCount > 1) return false
            if (scrubbing) setCursor(e2.x) else panPx(dx)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scrubbing || scale.isInProgress) return false
            flingLast = 0
            scroller.fling(0, 0, -vx.toInt(), 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2, 0, 0)
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            for ((r, label) in legendHits) {
                if (r.contains(e.x, e.y)) {
                    if (!hidden.remove(label)) {
                        // Hiding the last one would leave an empty plot that
                        // looks like missing data.
                        if (series.count { it.x.isNotEmpty() && it.label !in hidden } > 1) hidden.add(label)
                    }
                    onReadout?.invoke()
                    invalidate()
                    return true
                }
            }
            // A second tap on the cursor clears it; anywhere else moves it.
            val c = cursor
            if (c != null && abs(timeToX(c) - e.x) < 20f * d) {
                cursor = null
                onReadout?.invoke()
                invalidate()
            } else {
                setCursor(e.x)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAt(e.x, 2.5f)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            scrubbing = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            setCursor(e.x)
        }
    })

    private fun timeToX(t: Long): Float =
        padL + (t - viewFrom).toFloat() / max(1L, viewTo - viewFrom).toFloat() * plotW

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val x = scroller.currX
            panPx((x - flingLast).toFloat())
            flingLast = x
            // Stop at either end of the archive rather than spinning against it.
            if ((viewFrom <= extFrom && x < 0) || (viewTo >= extTo && x > 0)) {
                scroller.forceFinished(true)
            } else {
                postInvalidateOnAnimation()
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(ev)
        parent?.requestDisallowInterceptTouchEvent(true)
        scale.onTouchEvent(ev)
        gestures.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            scrubbing = false
        }
        return true
    }

    // ------------------------------------------------------------------ text

    /** "4.6°", "241 W"; "4.2–4.8°" where a point stands for a slice with a spread. */
    private fun value(lo: Float, hi: Float, unit: String): String {
        val a = num(lo)
        val b = num(hi)
        val u = if (unit == "°" || unit == "%") unit else " $unit"
        return if (a == b) "$a$u" else "$a–$b$u"
    }

    private fun num(v: Float): String = when {
        abs(v) >= 100f -> "%.0f".format(v)
        else -> "%.1f".format(v)
    }

    /** Three to five round values inside [lo, hi]. */
    private fun ticks(lo: Float, hi: Float): List<Float> {
        val span = hi - lo
        if (span <= 0f) return emptyList()
        val raw = span / 4f
        val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        val step = when {
            raw / mag < 1.5f -> mag
            raw / mag < 3.5f -> 2f * mag
            raw / mag < 7.5f -> 5f * mag
            else -> 10f * mag
        }
        val first = ceil(lo / step)
        val out = ArrayList<Float>()
        // Multiplied, not accumulated, and zero snapped to zero: summing the
        // step drifts, and the axis read "-0.00W".
        var i = 0
        while (out.size < 8) {
            var v = (first + i++) * step
            if (v > hi) break
            if (abs(v) < step * 1e-3f) v = 0f
            out.add(v)
        }
        return out
    }

    /**
     * Ticks on round local times - 06:00, not 06:37 - with the step chosen so
     * at most [maxTicks] fit. Aligned with the zone's offset at each tick, so
     * summer time does not put every label an hour off.
     */
    private fun timeTicks(from: Long, to: Long, maxTicks: Int): Pair<Long, List<Long>> {
        val span = max(1L, to - from)
        val step = STEPS.firstOrNull { span / it <= maxTicks } ?: (span / maxTicks)
        val out = ArrayList<Long>()
        var t = align(from, step)
        while (t < from) t = next(t, step)
        while (t <= to && out.size < 40) {
            out.add(t)
            t = next(t, step)
        }
        return step to out
    }

    private fun next(t: Long, step: Long): Long {
        val n = align(t + step + step / 2, step)
        return if (n > t) n else t + step
    }

    private fun offS(t: Long): Long = TimeZone.getDefault().getOffset(t * 1000L) / 1000L

    private fun align(t: Long, step: Long): Long {
        val o = offS(t)
        val local = t + o
        // Weeks start on a Monday; the epoch began on a Thursday.
        val shift = if (step >= 7 * DAY) 4 * DAY else 0L
        return Math.floorDiv(local - shift, step) * step + shift - o
    }

    private fun isMidnight(t: Long): Boolean = Math.floorMod(t + offS(t), DAY) == 0L

    private fun timeLabel(t: Long, step: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = t * 1000L
        return when {
            step > DAY -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
            step == DAY || isMidnight(t) ->
                "${DAYS[c.get(Calendar.DAY_OF_WEEK) - 1]} ${c.get(Calendar.DAY_OF_MONTH)}"
            else -> "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }
    }

    /** The readout's time: "Tue 13 Oct 14:32". */
    private fun stamp(t: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = t * 1000L
        return "%s %d %s %02d:%02d".format(
            DAYS[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.DAY_OF_MONTH),
            MONTHS[c.get(Calendar.MONTH)], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
        )
    }

    /** Enough digits to tell two ticks apart, and no more. */
    private fun fmt(v: Float): String {
        val a = abs(v)
        return when {
            a == 0f -> "0"
            a >= 100f -> v.toInt().toString()
            a >= 10f -> "%.0f".format(v)
            a >= 1f -> "%.1f".format(v)
            else -> "%.2f".format(v)
        }
    }

    companion object {
        private const val DAY = 86_400L

        /** Tick steps, seconds: minutes up to a month. */
        private val STEPS = longArrayOf(
            60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600, 43200,
            DAY, 2 * DAY, 7 * DAY, 14 * DAY, 28 * DAY,
        )
        private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        private val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )

        /** First index with x[i] >= t. */
        fun lowerBound(x: LongArray, t: Long): Int {
            var lo = 0
            var hi = x.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (x[m] < t) lo = m + 1 else hi = m
            }
            return lo
        }

        /** First index with x[i] > t. */
        fun upperBound(x: LongArray, t: Long): Int {
            var lo = 0
            var hi = x.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (x[m] <= t) lo = m + 1 else hi = m
            }
            return lo
        }

        /** Median spacing of an ascending series, from at most ~2000 samples of it. */
        fun medianStep(x: LongArray): Long {
            if (x.size < 2) return 10L
            val stride = max(1, (x.size - 1) / 2000)
            val diffs = ArrayList<Long>()
            var i = stride
            while (i < x.size) {
                val dd = (x[i] - x[i - stride]) / stride
                if (dd > 0) diffs.add(dd)
                i += stride
            }
            if (diffs.isEmpty()) return 10L
            diffs.sort()
            return max(1L, diffs[diffs.size / 2])
        }

        /** The span a set of series covers. */
        fun dataExtent(data: List<Series>): Pair<Long, Long> {
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            for (s in data) {
                if (s.x.isEmpty()) continue
                lo = min(lo, s.x.first())
                hi = max(hi, s.x.last())
            }
            if (lo == Long.MAX_VALUE) return 0L to 1L
            if (hi <= lo) hi = lo + 1
            return lo to hi
        }
    }
}
