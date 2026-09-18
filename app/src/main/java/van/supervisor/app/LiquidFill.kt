package van.supervisor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min
import kotlin.math.sin

/**
 * A vessel with liquid standing in it, drawn to a Bitmap.
 *
 * Generic on purpose: this is the battery behind the charge today, and it is
 * what the fresh and grey tanks will be drawn with when Phase 2 puts them on
 * the widget (CLAUDE.md §9). A tank has no terminal, so the battery's nub is
 * a parameter rather than part of the shape.
 *
 * Drawn to a Bitmap rather than assembled from drawables because RemoteViews
 * has no shader, no path and no animator: a launcher will inflate framework
 * layouts and accept a bitmap, and that is the whole of it. It is also why the
 * fill "animates" as a short burst of frames pushed from our own process
 * (VanWidget.renderFilling) — an AppWidgetHost never runs our code, so nothing
 * moves unless we push it.
 *
 * It occupies its own fixed-size box in the layout rather than the whole card,
 * which is what lets it carry a real outline: the bitmap's aspect is known, so
 * `fitXY` does not stretch the corners or the nub out of shape.
 */
internal object LiquidFill {

    /** Frames in one fill, and the gap between them: ~0.8 s in all. */
    const val FRAMES = 12
    const val FRAME_MS = 65L

    /** Wave height as a fraction of the vessel's interior, at full agitation. */
    private const val AMP = 0.06f

    /** How much of the wave remains when the liquid is at rest. */
    private const val AMP_AT_REST = 0.3f

    /**
     * @param level  0..1 of the vessel's interior. 0 draws an empty vessel,
     *               not a sliver.
     * @param motion 0 at rest, 1 mid-pour. Drives both the wave height and its
     *               phase, so `motion = 0` is the single resting picture — a
     *               static redraw after an animation that ended at 0 lands on
     *               exactly the last frame instead of snapping to a new wave.
     * @param nub    a battery terminal on the right. False for a water tank.
     */
    fun bitmap(
        wPx: Int, hPx: Int, level: Float, motion: Float, color: Int, nub: Boolean
    ): Bitmap {
        val w = wPx.coerceIn(24, 600)
        val h = hPx.coerceIn(16, 400)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val lv = level.coerceIn(0f, 1f)
        val mo = motion.coerceIn(0f, 1f)

        val stroke = maxOf(2f, h / 44f)
        val nubW = if (nub) w * 0.045f else 0f
        val body = RectF(
            stroke / 2f, stroke / 2f, w - nubW - stroke / 2f, h - stroke / 2f
        )
        val r = body.height() * 0.24f
        val shell = Path().apply { addRoundRect(body, r, r, Path.Direction.CW) }

        // An empty vessel must still read as a vessel, so the interior is sunk
        // slightly below the card rather than left transparent.
        c.drawPath(shell, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0x3A000000 })

        if (lv > 0f) {
            // Flatten the wave as the surface nears either end, so it can never
            // be cut off and 0 % / 100 % read as exactly empty and exactly full.
            val amp = body.height() * AMP * (AMP_AT_REST + (1f - AMP_AT_REST) * mo) *
                min(1f, min(lv, 1f - lv) / 0.05f)
            val phase = mo * 5f
            val base = body.bottom - body.height() * lv

            // Clip to the shell and let the outline below cover the edge. A
            // software canvas has no antialiased clip, so the join would show;
            // stroking the same path over it afterwards hides it exactly.
            c.save()
            c.clipPath(shell)

            val top = base - amp
            val span = maxOf(1f, body.bottom - top)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            // Roughly twice the alpha it could carry when this filled the
            // whole card: the 11sp muted labels are out on the card now and no
            // longer pay for it. Measured at these stops, the charge figure
            // keeps 4.7:1 or better over the liquid in every band — and it is
            // large bold text, which only needs 3:1. The hairline surface
            // below is brighter than that and can cross a digit, which is what
            // the text shadow in the layouts is for.
            fill.shader = LinearGradient(
                0f, top, 0f, body.bottom,
                intArrayOf(alpha(color, 0x8C), alpha(color, 0x5A), alpha(color, 0x44)),
                floatArrayOf(0f, (0.22f * body.height() / span).coerceIn(0.05f, 0.9f), 1f),
                Shader.TileMode.CLAMP
            )
            c.drawPath(wave(body, base, amp, phase, close = true), fill)

            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeWidth = maxOf(1.5f, h / 60f)
            line.color = alpha(color, 0xE6)
            c.drawPath(wave(body, base, amp, phase, close = false), line)

            c.restore()
        }

        // Outline last: it covers the clip's hard edge, and it is what makes an
        // empty battery still a battery.
        val edge = Paint(Paint.ANTI_ALIAS_FLAG)
        edge.style = Paint.Style.STROKE
        edge.strokeWidth = stroke
        edge.color = alpha(color, 0x9E)
        c.drawPath(shell, edge)

        if (nub) {
            val nubH = h * 0.32f
            val nubR = nubW * 0.6f
            c.drawRoundRect(
                RectF(body.right, (h - nubH) / 2f, w - stroke / 2f, (h + nubH) / 2f),
                nubR, nubR,
                Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = alpha(color, 0x9E) }
            )
        }
        return bmp
    }

    /**
     * The liquid surface across the vessel, and — when [close] — down and back
     * around its floor. Drawn wider than the vessel so the clip, not the path,
     * decides the ends.
     *
     * Two sines of unrelated periods: one alone reads as a metronome.
     */
    private fun wave(
        body: RectF, base: Float, amp: Float, phase: Float, close: Boolean
    ): Path {
        val path = Path()
        val left = body.left - 2f
        val right = body.right + 2f
        val span = right - left
        var x = left
        var first = true
        while (x <= right) {
            val y = at(x, left, span, base, amp, phase)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += 2f
        }
        path.lineTo(right, at(right, left, span, base, amp, phase))
        if (close) {
            path.lineTo(right, body.bottom + 2f)
            path.lineTo(left, body.bottom + 2f)
            path.close()
        }
        return path
    }

    private fun at(x: Float, left: Float, span: Float, base: Float, amp: Float, phase: Float): Float {
        val t = (x - left) / span * 2f * Math.PI.toFloat()
        return base + amp * sin(t * 1.5f + phase) + amp * 0.45f * sin(t * 2.7f - phase * 1.6f)
    }

    private fun alpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
