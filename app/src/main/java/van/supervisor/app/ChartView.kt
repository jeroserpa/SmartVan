package van.supervisor.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * A small time-series plot, drawn straight onto a Canvas.
 *
 * Canvas rather than a charting library, for the same reason `LiquidFill.kt`
 * is: the release APK is deliberately shrunk (Material + AppCompat already
 * took it from 2 MB to 6 MB, which `app/build.gradle.kts` calls out as too big
 * to hand over on mobile data), and a chart library would cost more than the
 * screen it draws.
 *
 * Conventions taken from the rest of the project:
 *  - **A gap is drawn as a gap.** NAN breaks the line rather than joining
 *    across it. A reboot must look like a reboot, not like a slow ramp.
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
    )

    private val d = resources.displayMetrics.density
    private var series: List<Series> = emptyList()
    private var emptyText: String = ""
    private var tzOffsetMs: Long = TimeZone.getDefault().rawOffset.toLong()

    /** Drawn across the plot at a fixed left-axis value, e.g. a threshold. */
    private var marker: Pair<Float, Int>? = null

    private val grid = paint(color(R.color.line), 1f)
    private val axisText = textPaint(color(R.color.dim), 10f)
    private val legendText = textPaint(color(R.color.muted), 11f)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    fun setData(data: List<Series>, empty: String = "no data yet") {
        series = data
        emptyText = empty
        invalidate()
    }

    fun setMarker(value: Float?, color: Int) {
        marker = value?.let { it to color }
        invalidate()
    }

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

    override fun onDraw(canvas: Canvas) {
        val live = series.filter { it.x.isNotEmpty() }
        val legendH = if (live.isEmpty()) 0f else 16f * d
        val padL = 42f * d
        val padR = 10f * d
        val padT = 6f * d + legendH
        val padB = 18f * d
        val w = width.toFloat()
        val h = height.toFloat()
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW <= 0 || plotH <= 0) return

        if (live.isEmpty()) {
            val t = textPaint(color(R.color.dim), 12f)
            canvas.drawText(emptyText, padL, h / 2f, t)
            return
        }

        // --- ranges ---------------------------------------------------------
        var xMin = Long.MAX_VALUE
        var xMax = Long.MIN_VALUE
        for (s in live) {
            for (v in s.x) {
                if (v < xMin) xMin = v
                if (v > xMax) xMax = v
            }
        }
        if (xMax <= xMin) xMax = xMin + 1

        val left = live.filter { it.axis == Axis.LEFT }
        val right = live.filter { it.axis == Axis.RIGHT }
        val lr = range(left)
        val rr = range(right)

        fun px(t: Long) = padL + (t - xMin).toFloat() / (xMax - xMin).toFloat() * plotW
        fun py(v: Float, r: Pair<Float, Float>) =
            padT + plotH - (v - r.first) / (r.second - r.first) * plotH

        // --- grid and y labels ----------------------------------------------
        if (left.isNotEmpty()) {
            for (t in ticks(lr.first, lr.second)) {
                val y = py(t, lr)
                if (y < padT - 1 || y > padT + plotH + 1) continue
                canvas.drawLine(padL, y, padL + plotW, y, grid)
                axisText.textAlign = Paint.Align.RIGHT
                canvas.drawText(fmt(t) + left[0].unit, padL - 4f * d, y + 3.5f * d, axisText)
            }
        }
        if (right.isNotEmpty()) {
            for (t in ticks(rr.first, rr.second)) {
                val y = py(t, rr)
                if (y < padT - 1 || y > padT + plotH + 1) continue
                axisText.textAlign = Paint.Align.LEFT
                canvas.drawText(fmt(t) + right[0].unit, padL + plotW + 3f * d, y + 3.5f * d, axisText)
            }
        }

        // --- x labels: local time, because "when" is the whole question ------
        axisText.textAlign = Paint.Align.CENTER
        for (t in timeTicks(xMin, xMax)) {
            val x = px(t)
            if (x < padL || x > padL + plotW) continue
            canvas.drawLine(x, padT, x, padT + plotH, grid)
            canvas.drawText(clock(t), x, h - 5f * d, axisText)
        }

        marker?.let { (v, c) ->
            if (left.isNotEmpty() && v >= lr.first && v <= lr.second) {
                val mp = paint(c, 1f).apply { alpha = 140 }
                canvas.drawLine(padL, py(v, lr), padL + plotW, py(v, lr), mp)
            }
        }

        // --- series ----------------------------------------------------------
        for (s in live) {
            val r = if (s.axis == Axis.LEFT) lr else rr
            when (s.kind) {
                Kind.BAR -> {
                    fill.color = s.color
                    fill.alpha = 190
                    val bw = (plotW / s.x.size).coerceAtLeast(1.2f * d) * 0.8f
                    val zero = py(0f.coerceIn(r.first, r.second), r)
                    for (i in s.x.indices) {
                        val v = s.y[i]
                        if (v.isNaN()) continue
                        val x = px(s.x[i])
                        val y = py(v, r)
                        canvas.drawRect(x - bw / 2, minOf(y, zero), x + bw / 2, maxOf(y, zero), fill)
                    }
                }
                Kind.LINE -> {
                    stroke.color = s.color
                    stroke.strokeWidth = 1.6f * d
                    path.reset()
                    var pen = false
                    for (i in s.x.indices) {
                        val v = s.y[i]
                        if (v.isNaN()) {
                            // The break is the point: a gap in the log is a
                            // reboot or an out-of-range probe, and joining
                            // across it would draw a trend that never happened.
                            pen = false
                            continue
                        }
                        val x = px(s.x[i])
                        val y = py(v, r)
                        if (pen) path.lineTo(x, y) else { path.moveTo(x, y); pen = true }
                    }
                    canvas.drawPath(path, stroke)
                }
            }
        }

        // --- legend ----------------------------------------------------------
        var lx = padL
        val ly = 10f * d
        for (s in live) {
            fill.color = s.color
            fill.alpha = 255
            canvas.drawRect(lx, ly - 5f * d, lx + 8f * d, ly - 1f * d, fill)
            legendText.textAlign = Paint.Align.LEFT
            val label = if (s.axis == Axis.RIGHT) "${s.label} →" else s.label
            canvas.drawText(label, lx + 11f * d, ly, legendText)
            lx += 11f * d + legendText.measureText(label) + 14f * d
        }
    }

    /** Min/max over a set of series, padded, never zero-height. */
    private fun range(ss: List<Series>): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (s in ss) for (v in s.y) {
            if (v.isNaN()) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
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
        val first = ceil(lo / step) * step
        val out = ArrayList<Float>()
        var v = first
        while (v <= hi && out.size < 8) { out.add(v); v += step }
        return out
    }

    /** Ticks on round local hours, which is how a person reads a van's day. */
    private fun timeTicks(from: Long, to: Long): List<Long> {
        val span = to - from
        val stepS = when {
            span <= 4 * 3600 -> 3600L
            span <= 12 * 3600 -> 3 * 3600L
            span <= 36 * 3600 -> 6 * 3600L
            span <= 5 * 86400 -> 86400L
            else -> 2 * 86400L
        }
        val out = ArrayList<Long>()
        // Align to local midnight/hour rather than to the epoch, so labels read
        // 06:00 and not 06:37.
        var t = ((from + tzOffsetMs / 1000) / stepS) * stepS - tzOffsetMs / 1000
        while (t < from) t += stepS
        while (t <= to && out.size < 12) { out.add(t); t += stepS }
        return out
    }

    private fun clock(epoch: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = epoch * 1000L
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** Enough digits to tell two ticks apart, and no more. */
    private fun fmt(v: Float): String {
        val a = abs(v)
        return when {
            a >= 100f -> v.toInt().toString()
            a >= 10f -> "%.0f".format(v)
            a >= 1f -> "%.1f".format(v)
            else -> "%.2f".format(v)
        }
    }
}
