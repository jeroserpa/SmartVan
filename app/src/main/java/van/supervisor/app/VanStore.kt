package van.supervisor.app

import android.content.Context
import org.json.JSONObject

/**
 * Last known van state, kept so the widget can show something — clearly aged —
 * when the phone is out of range of van-core, which is most of the time.
 */
object VanStore {

    private const val PREFS = "van_widget"

    data class Snapshot(
        val states: JSONObject,
        /** Wall-clock time of the last successful read, 0 if never. */
        val okAt: Long,
        /** Whether the most recent attempt reached van-core. */
        val lastOk: Boolean,
        /** Whether any attempt has been made yet. */
        val tried: Boolean,
        val refreshing: Boolean,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveOk(context: Context, states: Map<String, JSONObject>) {
        val json = JSONObject()
        states.forEach { (id, state) -> json.put(id, state) }
        prefs(context).edit()
            .putString("states", json.toString())
            .putLong("okAt", System.currentTimeMillis())
            .putBoolean("lastOk", true)
            .putBoolean("tried", true)
            .putBoolean("refreshing", false)
            .apply()
    }

    /** Out of range is the normal case, not an error: keep the old values. */
    fun saveMiss(context: Context) {
        prefs(context).edit()
            .putBoolean("lastOk", false)
            .putBoolean("tried", true)
            .putBoolean("refreshing", false)
            .apply()
    }

    fun setRefreshing(context: Context, refreshing: Boolean) {
        prefs(context).edit().putBoolean("refreshing", refreshing).apply()
    }

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        val states = runCatching { JSONObject(p.getString("states", "{}")!!) }
            .getOrDefault(JSONObject())
        return Snapshot(
            states = states,
            okAt = p.getLong("okAt", 0L),
            lastOk = p.getBoolean("lastOk", false),
            tried = p.getBoolean("tried", false),
            refreshing = p.getBoolean("refreshing", false),
        )
    }
}
