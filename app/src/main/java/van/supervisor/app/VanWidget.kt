package van.supervisor.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Read-only home-screen widget.
 *
 * Deliberately has no controls: a Manual AC button on the home screen is the
 * phantom-press problem of CLAUDE.md §6 — a pocket tap arms the inverter for
 * 45 min and nobody notices. Tapping the widget opens the app; ↻ refreshes.
 *
 * Android caps background refresh at 15 min. It is not an alarm, and it only
 * has data while the phone is within Wi-Fi range of van-core.
 *
 * Two layouts: compact (2x1, charge + AC state) and full (3x2 and up).
 */
class VanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        schedule(context)
        refreshNow(context)
    }

    // Pre-Android 12 launchers pick no layout themselves: re-render on resize.
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        render(context, manager, intArrayOf(id))
    }

    override fun onEnabled(context: Context) {
        schedule(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshNow(context)
    }

    /** Everything the layouts show, decided once and shared by both. */
    private class Model(
        val soc: Double?,
        val out: Double?,
        val inp: Double?,
        val fridge: Double?,
        val cabin: Double?,
        /** ESP32 die temperature. Only shown when neither probe exists. */
        val board: Double?,
        /**
         * Whether the node publishes either DS18B20 at all — which is a
         * different question from whether they are reading. A probe that has
         * gone NAN must still show "-- °C fridge"; a firmware with no probes
         * should not pretend to have two.
         */
        val probes: Boolean,
        val state: String,
        val stateColor: Int,
        val reason: String,
        val footer: String,
        /** The same, short enough for the compact layout. */
        val footerShort: String,
        val footerColor: Int,
        val stale: Boolean,
        val refreshing: Boolean,
    )

    companion object {
        private const val ACTION_REFRESH = "van.supervisor.app.WIDGET_REFRESH"
        private const val PERIODIC = "van-widget-periodic"
        private const val ONESHOT = "van-widget-now"

        /** Older than one refresh period plus margin: values are shown greyed. */
        private const val STALE_MS = 20 * 60_000L

        /**
         * How long the spinner may claim a refresh is in flight.
         *
         * The worst real read is about 21s - up to 6s waiting for the Wi-Fi
         * network, up to 14s of event burst, then the fill animation - so this
         * is twice that and no more. It exists because a refresh that never
         * finishes (a worker the OS declined to run, a crash before the store
         * was written) otherwise left the widget showing a spinner in place of
         * its own refresh button, with no way to ask again.
         */
        private const val REFRESH_MAX_MS = 45_000L

        // The battery bitmap, at twice the dp of the box it goes in
        // (98x54dp full, 78x42dp compact) so fitXY neither stretches the
        // outline nor wastes pixels.
        private const val FILL_W_FULL = 196
        private const val FILL_H_FULL = 108
        private const val FILL_W_SMALL = 156
        private const val FILL_H_SMALL = 84

        private fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<VanWidgetWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        /** Refresh now, if any widget exists. Called from ↻ and when the app is left. */
        fun refreshNow(context: Context) {
            if (widgetIds(context).isEmpty()) return
            VanStore.startRefresh(context)
            renderAll(context)
            // REPLACE, not KEEP. KEEP drops the new request whenever an older
            // one is still unfinished - and an unfinished one is exactly the
            // state a phone that declined to run it leaves behind. Every press
            // of the refresh button was then silently discarded, forever.
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<VanWidgetWorker>().build()
            )
        }

        fun renderAll(context: Context) {
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }

        /**
         * Redraw, running the liquid up (or down) to the new charge as a short
         * burst of pushed frames.
         *
         * Called from [VanWidgetWorker] and nowhere else, because it sleeps:
         * a widget host never runs our code, so the only way anything moves is
         * for us to push each frame ourselves — and a BroadcastReceiver is not
         * alive long enough to do that. A Worker is, and it holds the process
         * up while it does. Everything else redraws through [render], which
         * lands on the same final frame instantly.
         */
        fun renderFilling(context: Context) {
            val ids = widgetIds(context)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val m = model(context, VanStore.load(context), System.currentTimeMillis())
            val to = levelOf(m)
            val from = VanStore.fill(context)
            VanStore.setFill(context, to)

            // A refresh that did not move the charge is not worth animating:
            // this runs every 15 min and a twitching home screen reads as noise.
            if (abs(to - from) < 0.005f) {
                push(context, manager, ids, m, to, 0f)
                return
            }
            for (i in 1..LiquidFill.FRAMES) {
                val t = i.toFloat() / LiquidFill.FRAMES
                // Ease out, so the liquid arrives and settles rather than
                // stopping dead. The surface sloshes and goes flat again over
                // the same interval, which leaves the last frame at motion 0 —
                // identical to what a later static redraw would draw.
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                push(context, manager, ids, m, from + (to - from) * eased,
                     sin(Math.PI.toFloat() * t))
                if (i < LiquidFill.FRAMES) Thread.sleep(LiquidFill.FRAME_MS)
            }
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, VanWidget::class.java))

        /** Instrumented tests only: the views a given snapshot produces. */
        @VisibleForTesting
        internal fun viewsFor(context: Context, snap: VanStore.Snapshot, now: Long, full: Boolean): RemoteViews {
            val m = model(context, snap, now)
            return build(context, m, full, levelOf(m), 0f)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val m = model(context, VanStore.load(context), System.currentTimeMillis())
            val level = levelOf(m)
            // Record what was drawn, so the next refresh animates from here
            // rather than from wherever the last animation happened to end.
            VanStore.setFill(context, level)
            push(context, manager, ids, m, level, 0f)
        }

        private fun push(
            context: Context, manager: AppWidgetManager, ids: IntArray,
            m: Model, level: Float, motion: Float,
        ) {
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= 31) {
                    // The launcher picks the largest layout that fits, and
                    // switches on resize without calling back.
                    RemoteViews(mapOf(
                        SizeF(110f, 40f) to build(context, m, false, level, motion),
                        SizeF(180f, 100f) to build(context, m, true, level, motion),
                    ))
                } else {
                    val minHeight = manager.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    build(context, m, minHeight >= 100, level, motion)
                }
                manager.updateAppWidget(id, views)
            }
        }

        /** Charge as 0..1 for the liquid. Unknown is empty, not half. */
        private fun levelOf(m: Model): Float =
            ((m.soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)

        private fun model(context: Context, snap: VanStore.Snapshot, now: Long): Model {
            val s = snap.states
            val muted = context.getColor(R.color.muted)
            val dim = context.getColor(R.color.dim)
            val warn = context.getColor(R.color.warn)
            val alt = context.getColor(R.color.alt)

            val stale = snap.okAt == 0L || now - snap.okAt > STALE_MS
            val parked = bool(s, VanFeed.PARKED) == true
            val ac = bool(s, VanFeed.AC_OUT)

            // Parked first (fridge off by design), then a dead P310 link (every
            // station value is then stale at the source), then the AC itself.
            var (state, stateColor) = when {
                snap.okAt == 0L -> "--" to dim
                parked -> "Parked" to alt
                bool(s, VanFeed.BLE) == false -> "P310 offline" to warn
                ac == true -> "AC on" to warn
                ac == false -> "AC off" to muted
                else -> "AC ?" to muted
            }
            if (stale) stateColor = dim
            val reason = if (parked) "fridge off by design" else text(s, VanFeed.REASON) ?: ""

            val at = if (snap.okAt == 0L) "" else DateFormat.getTimeFormat(context).format(Date(snap.okAt))
            val ago = ago(now - snap.okAt)
            val recognised = num(s, VanFeed.SOC) != null || bool(s, VanFeed.BLE) != null
            val n = s.length()
            val entities = if (n == 1) "1 entity" else "$n entities"
            // (full text, compact text, colour)
            val (footer, footerShort, footerColor) = when {
                snap.okAt == 0L && snap.tried ->
                    Triple("Not on van-core Wi-Fi", "Not on van-core Wi-Fi", muted)
                snap.okAt == 0L ->
                    Triple("Waiting for the first update", "Waiting for data", muted)
                // Reached van-core but matched nothing: an entity id format or
                // name change. Say so instead of looking like an empty van.
                snap.lastOk && !recognised && n > 0 ->
                    Triple("$at · $entities, none recognised", "No known entities", warn)
                snap.lastOk && !stale -> Triple(at, "Updated $at", dim)
                snap.lastOk -> Triple("Last seen $ago", "Seen $ago", warn)
                stale -> Triple("Out of range · $ago", "Seen $ago", warn)
                else -> Triple("Out of range · $at", "Updated $at", dim)
            }

            return Model(
                soc = num(s, VanFeed.SOC),
                out = num(s, VanFeed.OUT),
                inp = num(s, VanFeed.IN),
                fridge = num(s, VanFeed.FRIDGE_IDS),
                cabin = num(s, VanFeed.CABIN_IDS),
                board = boardTemp(s),
                probes = present(s, VanFeed.FRIDGE_IDS) || present(s, VanFeed.CABIN_IDS),
                state = state,
                stateColor = stateColor,
                reason = reason,
                footer = footer,
                footerShort = footerShort,
                footerColor = footerColor,
                stale = stale,
                refreshing = snap.refreshingSince != 0L &&
                    now - snap.refreshingSince in 0..REFRESH_MAX_MS,
            )
        }

        private fun build(
            context: Context, m: Model, full: Boolean, level: Float, motion: Float
        ): RemoteViews {
            val v = RemoteViews(context.packageName,
                if (full) R.layout.van_widget else R.layout.van_widget_small)
            val value = context.getColor(if (m.stale) R.color.dim else R.color.fg)
            val muted = context.getColor(R.color.muted)
            val dim = context.getColor(R.color.dim)

            // Bands follow CLAUDE.md §9 Phase 5 load shedding: 30 % sheds,
            // 15 % alerts. The number carries the band as well as the liquid,
            // so it reads at a glance on the compact layout too.
            val soc = m.soc
            val band = when {
                soc == null || m.stale -> Band.NONE
                soc < 15 -> Band.BAD
                soc < 30 -> Band.WARN
                else -> Band.OK
            }
            val bandColor = context.getColor(when (band) {
                Band.BAD -> R.color.bad
                Band.WARN -> R.color.warn
                Band.OK -> R.color.ok
                Band.NONE -> R.color.dim
            })
            v.setImageViewBitmap(R.id.w_fill, LiquidFill.bitmap(
                if (full) FILL_W_FULL else FILL_W_SMALL,
                if (full) FILL_H_FULL else FILL_H_SMALL,
                level, motion, bandColor, nub = true))

            // The figure stays near-white in every band now that it sits on the
            // liquid: the band is already carried twice over, by the fill and
            // by the outline, and white is what reads best on all three colours.
            v.setTextViewText(R.id.w_soc, socText(soc))
            v.setTextColor(R.id.w_soc, value)
            v.setTextViewText(R.id.w_state, m.state)
            v.setTextColor(R.id.w_state, m.stateColor)
            v.setInt(R.id.w_dot, "setColorFilter", m.stateColor)
            v.setTextViewText(R.id.w_age, if (full) m.footer else m.footerShort)
            v.setTextColor(R.id.w_age, m.footerColor)
            v.setViewVisibility(R.id.w_refresh, if (m.refreshing) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_spin, if (m.refreshing) View.VISIBLE else View.GONE)

            if (full) {
                v.setTextViewText(R.id.w_in, suffixed(m.inp, "W", "in", muted))
                v.setTextViewText(R.id.w_out, suffixed(m.out, "W", "out", muted))
                v.setTextColor(R.id.w_in, value)
                v.setTextColor(R.id.w_out, value)
                v.setTextColor(R.id.w_fridge, value)
                v.setTextColor(R.id.w_cabin, value)
                v.setTextViewText(R.id.w_reason, m.reason)

                // A node with no DS18B20 on it — the soak firmware is the whole
                // of that case — has exactly one temperature, the board. Show
                // that rather than two dashes: it is what its web page shows,
                // and on a board running BLE, SoftAP, a display and the SD
                // writer in one loop it is the number worth watching. Labelled,
                // and never in the cabinet's place.
                val showBoard = !m.probes && m.board != null
                if (showBoard) {
                    v.setImageViewResource(R.id.w_ic_fridge, R.drawable.ic_thermo)
                    v.setTextViewText(R.id.w_fridge, temp(m.board, "board", muted))
                } else {
                    v.setImageViewResource(R.id.w_ic_fridge, R.drawable.ic_snow)
                    v.setTextViewText(R.id.w_fridge, temp(m.fridge, "fridge", muted))
                    v.setTextViewText(R.id.w_cabin, temp(m.cabin, "cabin", muted))
                }
                v.setViewVisibility(R.id.w_ic_cabin, if (showBoard) View.GONE else View.VISIBLE)
                v.setViewVisibility(R.id.w_cabin, if (showBoard) View.GONE else View.VISIBLE)

                // Old data: the icons go grey with the numbers, so nothing on
                // the widget still looks live.
                v.setInt(R.id.w_ic_in, "setColorFilter", if (m.stale) dim else context.getColor(R.color.ok))
                v.setInt(R.id.w_ic_out, "setColorFilter", if (m.stale) dim else context.getColor(R.color.warn))
                v.setInt(R.id.w_ic_fridge, "setColorFilter",
                    if (m.stale) dim else if (showBoard) muted else context.getColor(R.color.alt))
                v.setInt(R.id.w_ic_cabin, "setColorFilter", if (m.stale) dim else muted)
            }

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(android.R.id.background, open)
            val refresh = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, VanWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // On the box, not the icon: the spinner sits in the same frame and
            // replaces the icon while a refresh is in flight, so putting the
            // intent on the icon alone made the control disappear exactly when
            // someone would want to press it again.
            v.setOnClickPendingIntent(R.id.w_refresh_box, refresh)
            return v
        }

        private enum class Band { NONE, OK, WARN, BAD }

        /** "78%" with a smaller percent sign. */
        private fun socText(soc: Double?): CharSequence {
            if (soc == null) return "--"
            val text = SpannableString("${soc.roundToInt()}%")
            text.setSpan(RelativeSizeSpan(0.5f), text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }

        /** "310 W in", the label small and muted: an arrow alone is ambiguous. */
        private fun suffixed(v: Double?, unit: String, label: String, labelColor: Int): CharSequence {
            val text = SpannableStringBuilder(v?.let { "${it.roundToInt()} $unit" } ?: "-- $unit")
            val start = text.length
            text.append(" ").append(label)
            text.setSpan(RelativeSizeSpan(0.75f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(labelColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }

        /**
         * "4.6 °C fridge". Same shape as the power flows, and the label is not
         * decoration: two bare temperatures side by side are a guessing game,
         * and guessing wrong here means reading the cabin as the cabinet.
         *
         * A missing value is "-- °C", which on van-core means the probe is
         * NAN — the filter in nodes/van-core.yaml publishes NAN rather than a
         * last-known value, and five minutes of that forces the inverter ON.
         */
        private fun temp(v: Double?, label: String, labelColor: Int): CharSequence {
            val text = SpannableStringBuilder(v?.let { "%.1f °C".format(it) } ?: "-- °C")
            val start = text.length
            text.append(" ").append(label)
            text.setSpan(RelativeSizeSpan(0.72f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(labelColor), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }

        private fun ago(ms: Long): String {
            val min = ms / 60_000
            return when {
                min < 1 -> "just now"
                min < 60 -> "$min min ago"
                min < 48 * 60 -> "${min / 60} h ago"
                else -> "${min / (24 * 60)} d ago"
            }
        }

        /**
         * ESPHome sends both a numeric `value` and a formatted `state`
         * ("4.60 °C"). Prefer the number, fall back to the text: a NAN comes
         * back as a JSON null, and older or hand-rolled builds have been seen
         * to send the value as a string. Either way an unparseable reading is
         * null, never 0 — a fridge reading 0 °C is a decision, a blank is not.
         */
        private fun num(s: JSONObject, id: String): Double? {
            val j = s.optJSONObject(id) ?: return null
            (j.opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { return it }
            val state = j.optString("state").takeIf { it.isNotEmpty() } ?: return null
            return LEADING_NUMBER.find(state)?.value?.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        /** The first of [ids] the node publishes a usable number for. */
        private fun num(s: JSONObject, ids: List<String>): Double? {
            for (id in ids) num(s, id)?.let { return it }
            return null
        }

        /**
         * Whether the node publishes any of [ids] at all — a different question
         * from whether one of them reads. A probe that has gone NAN is present
         * and must show "-- °C"; a node with no probe at all is not.
         */
        private fun present(s: JSONObject, ids: List<String>): Boolean =
            ids.any { s.has(it) }

        private val LEADING_NUMBER = Regex("^[+-]?\\d+(\\.\\d+)?")

        /**
         * The board temperature, whatever the node is called. Its id carries
         * the node's friendly_name (see [VanFeed.BOARD_SUFFIX]), so it is
         * matched by suffix — the alternative is a list of ids that is wrong
         * again the next time a node is added.
         */
        private fun boardTemp(s: JSONObject): Double? {
            val keys = s.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("sensor-") && k.endsWith(VanFeed.BOARD_SUFFIX)) {
                    num(s, k)?.let { return it }
                }
            }
            return null
        }

        private fun bool(s: JSONObject, id: String): Boolean? {
            val j = s.optJSONObject(id) ?: return null
            (j.opt("value") as? Boolean)?.let { return it }
            return when (j.optString("state")) {
                "ON" -> true
                "OFF" -> false
                else -> null
            }
        }

        private fun text(s: JSONObject, id: String): String? =
            s.optJSONObject(id)?.optString("state")?.takeIf { it.isNotEmpty() }
    }
}
