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
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

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
        val state: String,
        val stateColor: Int,
        val reason: String,
        val footer: String,
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

        private val BARS = intArrayOf(R.id.w_bar_ok, R.id.w_bar_warn, R.id.w_bar_bad, R.id.w_bar_dim)

        private fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<VanWidgetWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        /** Refresh now, if any widget exists. Called from ↻ and when the app is left. */
        fun refreshNow(context: Context) {
            if (widgetIds(context).isEmpty()) return
            VanStore.setRefreshing(context, true)
            renderAll(context)
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VanWidgetWorker>().build()
            )
        }

        fun renderAll(context: Context) {
            val ids = widgetIds(context)
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }

        private fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, VanWidget::class.java))

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val m = model(context, VanStore.load(context), System.currentTimeMillis())
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= 31) {
                    // The launcher picks the largest layout that fits, and
                    // switches on resize without calling back.
                    RemoteViews(mapOf(
                        SizeF(110f, 40f) to build(context, m, full = false),
                        SizeF(180f, 100f) to build(context, m, full = true),
                    ))
                } else {
                    val minHeight = manager.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    build(context, m, full = minHeight >= 100)
                }
                manager.updateAppWidget(id, views)
            }
        }

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
                snap.okAt == 0L -> "—" to dim
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
            val (footer, footerColor) = when {
                snap.okAt == 0L && snap.tried -> "Not on van-core Wi-Fi" to muted
                snap.okAt == 0L -> "Waiting for the first update" to muted
                // Reached van-core but matched nothing: an entity id format or
                // name change. Say so instead of looking like an empty van.
                snap.lastOk && !recognised && s.length() > 0 ->
                    "$at · ${s.length()} entities, none recognised" to warn
                snap.lastOk && !stale -> "Updated $at" to dim
                snap.lastOk -> "Last seen $ago" to warn
                stale -> "Out of range · last seen $ago" to warn
                else -> "Out of range · updated $at" to dim
            }

            return Model(
                soc = num(s, VanFeed.SOC),
                out = num(s, VanFeed.OUT),
                inp = num(s, VanFeed.IN),
                fridge = num(s, VanFeed.FRIDGE),
                state = state,
                stateColor = stateColor,
                reason = reason,
                footer = footer,
                footerColor = footerColor,
                stale = stale,
                refreshing = snap.refreshing,
            )
        }

        private fun build(context: Context, m: Model, full: Boolean): RemoteViews {
            val v = RemoteViews(context.packageName,
                if (full) R.layout.van_widget else R.layout.van_widget_small)
            val value = context.getColor(if (m.stale) R.color.dim else R.color.fg)

            v.setTextViewText(R.id.w_soc, socText(m.soc))
            v.setTextColor(R.id.w_soc, value)
            v.setTextViewText(R.id.w_state, m.state)
            v.setTextColor(R.id.w_state, m.stateColor)
            v.setInt(R.id.w_dot, "setColorFilter", m.stateColor)
            v.setTextViewText(R.id.w_age, m.footer)
            v.setTextColor(R.id.w_age, m.footerColor)
            v.setViewVisibility(R.id.w_refresh, if (m.refreshing) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_spin, if (m.refreshing) View.VISIBLE else View.GONE)

            if (full) {
                v.setTextViewText(R.id.w_out, watts(m.out))
                v.setTextViewText(R.id.w_in, watts(m.inp))
                v.setTextViewText(R.id.w_fridge, m.fridge?.let { "%.1f °C".format(it) } ?: "—")
                v.setTextColor(R.id.w_out, value)
                v.setTextColor(R.id.w_in, value)
                v.setTextColor(R.id.w_fridge, value)
                v.setTextViewText(R.id.w_reason, m.reason)

                // Bands follow CLAUDE.md §9 Phase 5 load shedding: 30 % sheds,
                // 15 % alerts.
                val soc = m.soc
                val band = when {
                    soc == null || m.stale -> R.id.w_bar_dim
                    soc < 15 -> R.id.w_bar_bad
                    soc < 30 -> R.id.w_bar_warn
                    else -> R.id.w_bar_ok
                }
                for (bar in BARS) v.setViewVisibility(bar, if (bar == band) View.VISIBLE else View.GONE)
                v.setProgressBar(band, 100, (soc ?: 0.0).roundToInt().coerceIn(0, 100), false)
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
            v.setOnClickPendingIntent(R.id.w_refresh, refresh)
            return v
        }

        /** "78%" with a smaller percent sign. */
        private fun socText(soc: Double?): CharSequence {
            if (soc == null) return "—"
            val text = SpannableString("${soc.roundToInt()}%")
            text.setSpan(RelativeSizeSpan(0.5f), text.length - 1, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return text
        }

        private fun watts(w: Double?): String = w?.let { "${it.roundToInt()} W" } ?: "— W"

        private fun ago(ms: Long): String {
            val min = ms / 60_000
            return when {
                min < 1 -> "just now"
                min < 60 -> "$min min ago"
                min < 48 * 60 -> "${min / 60} h ago"
                else -> "${min / (24 * 60)} d ago"
            }
        }

        private fun num(s: JSONObject, id: String): Double? {
            val v = s.optJSONObject(id)?.opt("value")
            return (v as? Number)?.toDouble()?.takeIf { it.isFinite() }
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
