package van.supervisor.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Read-only home-screen widget.
 *
 * Deliberately has no controls: a Manual AC button on the home screen is the
 * phantom-press problem of CLAUDE.md §6 — a pocket tap arms the inverter for
 * 45 min and nobody notices. Tapping the widget opens the app; ↻ refreshes.
 *
 * Android caps background refresh at 15 min. It is not an alarm, and it only
 * has data while the phone is within Wi-Fi range of van-core.
 */
class VanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        schedule(context)
        refreshNow(context)
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

    companion object {
        private const val ACTION_REFRESH = "van.supervisor.app.WIDGET_REFRESH"
        private const val PERIODIC = "van-widget-periodic"
        private const val ONESHOT = "van-widget-now"

        /** Older than one refresh period plus margin: values are shown greyed. */
        private const val STALE_MS = 20 * 60_000L

        private const val FG = 0xFFE6EDF3.toInt()
        private const val DIM = 0xFF6B7682.toInt()
        private const val AMBER = 0xFFF0B429.toInt()
        private const val BLUE = 0xFF5AA9E6.toInt()   // same as the parked LED blink

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
            val snap = VanStore.load(context)
            val s = snap.states
            val now = System.currentTimeMillis()
            val stale = snap.okAt == 0L || now - snap.okAt > STALE_MS
            val main = if (stale) DIM else FG

            val v = RemoteViews(context.packageName, R.layout.van_widget)

            v.setTextViewText(R.id.w_soc, num(s, VanFeed.SOC)?.let { "%.0f%%".format(it) } ?: "—")
            v.setTextViewText(R.id.w_out, num(s, VanFeed.OUT)?.let { "%.0f W out".format(it) } ?: "— W out")
            v.setTextViewText(R.id.w_in, num(s, VanFeed.IN)?.let { "%.0f W in".format(it) } ?: "")
            v.setTextViewText(R.id.w_fridge, num(s, VanFeed.FRIDGE)?.let { "%.1f °C".format(it) } ?: "")
            v.setTextColor(R.id.w_soc, main)
            v.setTextColor(R.id.w_out, main)
            v.setTextColor(R.id.w_fridge, main)

            val (acText, acColor) = acLine(s)
            v.setTextViewText(R.id.w_ac, acText)
            v.setTextColor(R.id.w_ac, if (stale) DIM else acColor)

            v.setTextViewText(R.id.w_age, footer(context, snap, now, stale))

            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            val refresh = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, VanWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_refresh, refresh)

            manager.updateAppWidget(ids, v)
        }

        /**
         * The AC line leads with whatever overrides the normal reading: parked
         * (fridge off by design) first, then a dead P310 link (every station
         * value on the widget is then stale at the source).
         */
        private fun acLine(s: JSONObject): Pair<String, Int> {
            if (bool(s, VanFeed.PARKED) == true) return "PARKED · fridge off" to BLUE
            if (bool(s, VanFeed.BLE) == false) return "P310 link down" to AMBER
            val ac = when (bool(s, VanFeed.AC_OUT)) {
                true -> "AC on"
                false -> "AC off"
                null -> "AC ?"
            }
            val reason = text(s, VanFeed.REASON)
            return (if (reason != null) "$ac · $reason" else ac) to FG
        }

        private fun footer(context: Context, snap: VanStore.Snapshot, now: Long, stale: Boolean): String {
            if (snap.refreshing) return "updating…"
            if (snap.okAt == 0L) return if (snap.tried) "van-core not in range" else "no data yet"
            val at = DateFormat.getTimeFormat(context).format(Date(snap.okAt))
            // Reached van-core but matched nothing: an entity id format or name
            // change. Say so instead of looking like an empty van.
            if (snap.lastOk && snap.states.length() > 0 &&
                num(snap.states, VanFeed.SOC) == null && bool(snap.states, VanFeed.BLE) == null) {
                return "$at · ${snap.states.length()} entities, none recognised"
            }
            return when {
                snap.lastOk && !stale -> at
                snap.lastOk -> "last seen ${ago(now - snap.okAt)}"
                else -> "not in range · last ${ago(now - snap.okAt)}"
            }
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
