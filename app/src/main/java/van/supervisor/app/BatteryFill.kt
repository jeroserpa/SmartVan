package van.supervisor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.min
import kotlin.math.sin

/**
 * The widget background: state of charge as a body of coloured liquid filling
 * the card from the bottom, with a surface that sloshes while it fills.
 *
 * Drawn to a Bitmap rather than assembled from drawables because RemoteViews
 * has no shader, no path and no animator: a launcher will inflate framework
 * layouts and accept a bitmap, and that is the whole of it. It is also why the
 * fill "animates" as a short burst of frames pushed from our own process
 * (VanWidget.renderFilling) — an AppWidgetHost never runs our code, so nothing
 * moves unless we push it.
 *
 * Cheap on purpose: one 240 px wide bitmap, stretched to the card with fitXY.
 * The level is vertical, so horizontal stretching cannot make it read wrong,
 * and a soft wave is the one thing that survives scaling well.
 *
 * **Legibility beats the effect.** The card carries 11sp muted text over this,
 * and muted-on-green loses contrast fast. So the liquid is a bright meniscus
 * just under the surface — which is where the eye wants to read the level
 * anyway — over a body kept nearly as dark as the bare card.
 */
internal object BatteryFill {

    /** Frames in one fill, and the gap between them: ~0.8 s in all. */
    const val FRAMES = 12
    const val FRAME_MS = 65L

    /** Bitmap width. Height comes from the layout it backs. */
    private const val W = 240

    /** Wave height, as a fraction of the card, at full agitation. */
    private const val AMP = 0.034f

    /** How much of the wave remains when the liquid is at rest. */
    private const val AMP_AT_REST = 0.3f

    /**
     * @param heightPx bitmap height; pick it to match the layout's aspect.
     * @param level    0..1. 0 draws nothing at all, not a sliver.
     * @param motion   0 at rest, 1 mid-pour. Drives both the wave height and
     *                 its phase, so `motion = 0` is the single resting picture
     *                 — a static redraw after an animation that ended at 0
     *                 lands on exactly the last frame instead of snapping.
     */
    fun bitmap(heightPx: Int, level: Float, motion: Float, color: Int): Bitmap {
        val h = heightPx.coerceIn(24, 400)
        val w = W
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val lv = level.coerceIn(0f, 1f)
        val mo = motion.coerceIn(0f, 1f)

        // Card-shaped mask first, then the liquid composited into it with
        // SRC_IN. The other order does not work: a DST_IN round rect only
        // touches the pixels its own geometry covers, so the corners would
        // keep their liquid. Drawing the mask into the bitmap and pulling the
        // liquid through it clears them, with an antialiased edge.
        //
        // The radius comes from the bitmap's own height, so after fitXY it errs
        // towards rounder than the card rather than squarer — an overshoot is
        // invisible on a dark background, an undershoot puts liquid outside the
        // card. On API 31+ the card's clipToOutline settles it exactly anyway.
        val card = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val r = h * 0.17f
        c.drawRoundRect(card, r, r, Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = Color.BLACK })
        val layer = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(),
            Paint().also { it.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })

        // Flatten the wave as the surface nears either end, so it can never be
        // cut off by the card edge and 0 % / 100 % read as exactly empty/full.
        val amp = h * AMP * (AMP_AT_REST + (1f - AMP_AT_REST) * mo) *
            min(1f, min(lv, 1f - lv) / 0.05f)
        val phase = mo * 5f
        val base = h * (1f - lv)

        if (lv > 0f) {
            val top = base - amp
            val span = maxOf(1f, h - top)
            val body = Paint(Paint.ANTI_ALIAS_FLAG)
            body.shader = LinearGradient(
                0f, top, 0f, h.toFloat(),
                // Measured, not eyeballed: at these alphas the card's own
                // foreground keeps ~9:1 over the liquid and the muted 11sp
                // labels ~3.6:1, which is better than the dim timestamp
                // already managed on the bare card. The bright surface line
                // below is what actually reads as a level, so the glow under
                // it can be this soft without losing the effect.
                intArrayOf(alpha(color, 0x42), alpha(color, 0x1C), alpha(color, 0x10)),
                floatArrayOf(0f, (0.11f * h / span).coerceIn(0.05f, 0.9f), 1f),
                Shader.TileMode.CLAMP
            )
            c.drawPath(surface(w, h, base, amp, phase, close = true), body)

            // The surface line is the only bright mark on the card, so the eye
            // lands on the level rather than on the liquid.
            val line = Paint(Paint.ANTI_ALIAS_FLAG)
            line.style = Paint.Style.STROKE
            line.strokeWidth = maxOf(1.5f, h / 95f)
            line.color = alpha(color, 0xD8)
            c.drawPath(surface(w, h, base, amp, phase, close = false), line)
        }
        c.restoreToCount(layer)
        return bmp
    }

    /**
     * The liquid surface, as a path along the top and — when [close] — down and
     * back around the bottom of the card.
     *
     * Two sines of unrelated periods: one alone reads as a metronome.
     */
    private fun surface(
        w: Int, h: Int, base: Float, amp: Float, phase: Float, close: Boolean
    ): Path {
        val path = Path()
        var x = 0f
        var first = true
        while (x <= w) {
            val y = waveAt(x, w, base, amp, phase)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += 3f
        }
        path.lineTo(w.toFloat(), waveAt(w.toFloat(), w, base, amp, phase))
        if (close) {
            path.lineTo(w.toFloat(), h.toFloat())
            path.lineTo(0f, h.toFloat())
            path.close()
        }
        return path
    }

    private fun waveAt(x: Float, w: Int, base: Float, amp: Float, phase: Float): Float {
        val t = x / w * 2f * Math.PI.toFloat()
        return base + amp * sin(t * 1.5f + phase) + amp * 0.45f * sin(t * 2.7f - phase * 1.6f)
    }

    private fun alpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
