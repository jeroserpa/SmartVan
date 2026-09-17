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
        /**
         * When a refresh was last started, 0 if none is in flight.
         *
         * A timestamp rather than a flag, and the difference is the whole
         * point: while the widget believes it is refreshing it hides the
         * refresh button behind a spinner, so a refresh that never finishes
         * used to leave the widget with no way to ask again. It expires - see
         * REFRESH_MAX_MS in [VanWidget].
         */
        val refreshingSince: Long,
    )

    /** Written into each stored state: when that entity was last read. */
    private const val SEEN = "_seenAt"

    /** An entity not seen for this long is dropped rather than kept forever. */
    private const val FORGET_MS = 24 * 60 * 60_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveOk(context: Context, states: Map<String, JSONObject>) {
        val now = System.currentTimeMillis()
        // Merge over what is already there rather than replacing it. A burst
        // can arrive short - the phone walks out of range mid-read, or van-core
        // is busy - and a short burst must not blank fields that were read a
        // few minutes ago. The widget already says how old the snapshot is, so
        // "stale" is a state it can show; "gone" is not.
        val json = JSONObject()
        val old = load(context).states
        val names = old.keys()
        while (names.hasNext()) {
            val id = names.next()
            val state = old.optJSONObject(id) ?: continue
            if (now - state.optLong(SEEN, now) < FORGET_MS) json.put(id, state)
        }
        states.forEach { (id, state) -> json.put(id, state.put(SEEN, now)) }
        prefs(context).edit()
            .putString("states", json.toString())
            .putLong("okAt", now)
            .putBoolean("lastOk", true)
            .putBoolean("tried", true)
            .putLong("refreshingSince", 0L)
            .apply()
    }

    /** Out of range is the normal case, not an error: keep the old values. */
    fun saveMiss(context: Context) {
        prefs(context).edit()
            .putBoolean("lastOk", false)
            .putBoolean("tried", true)
            .putLong("refreshingSince", 0L)
            .apply()
    }

    fun startRefresh(context: Context) {
        prefs(context).edit().putLong("refreshingSince", System.currentTimeMillis()).apply()
    }

    /**
     * Clear the in-flight marker without touching the data. The worker's
     * success and miss paths clear it themselves; this is for the case where
     * neither ran, so that a refresh can never latch the spinner on.
     */
    fun endRefresh(context: Context) {
        prefs(context).edit().putLong("refreshingSince", 0L).apply()
    }

    /**
     * The liquid level the widget last drew, 0..1. Kept in prefs rather than in
     * memory because the widget is redrawn from a fresh process as often as
     * not, and an animation that always started from empty would make every
     * quarter-hourly refresh look like a fault.
     */
    fun fill(context: Context): Float = prefs(context).getFloat("fill", 0f)

    fun setFill(context: Context, level: Float) {
        prefs(context).edit().putFloat("fill", level).apply()
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
            refreshingSince = p.getLong("refreshingSince", 0L),
        )
    }
}
