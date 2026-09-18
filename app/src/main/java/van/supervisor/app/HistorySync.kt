package van.supervisor.app

import android.content.Context
import android.net.Network
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import kotlin.math.max

/**
 * Pulls van-core's ring buffer into [HistoryStore].
 *
 * Incremental by default: the node is asked only for the rows newer than the
 * archive already holds, with a generous margin, because `?last=N` counts
 * rows and the two sides do not agree on what a row is after a reboot.
 * Over-requesting is free — `epoch` is the store's primary key, so overlap
 * dedupes itself — while under-requesting loses data permanently once the
 * ring wraps.
 */
object HistorySync {

    private const val BASE = "http://192.168.4.1"

    /** Instrumented tests only: point the sync at a mock van-core. */
    @VisibleForTesting
    internal var baseOverride: String? = null

    private val base: String get() = baseOverride ?: BASE

    /** Why a sync produced nothing, in the words the screen uses. */
    enum class Miss { NO_WIFI, NO_ANSWER, NOT_VAN_CORE, NO_LOG }

    class Result(
        val added: Int,
        /** Rows the node had but that carried no clock, so could not be placed in time. */
        val undateable: Int,
        val nodeRows: Int,
        val nodeCapacity: Int,
        val clockState: Int,
        val miss: Miss?,
    ) {
        val ok: Boolean get() = miss == null
    }

    /**
     * Sync once. Blocking — call it off the main thread.
     *
     * @param full ignore what is stored and ask for the node's whole buffer.
     */
    fun sync(context: Context, store: HistoryStore, full: Boolean = false): Result {
        val routes = VanRoutes.candidates(context)
        // A non-null entry means a Wi-Fi network was found; the trailing null
        // is only the process default. Derived from the same list rather than
        // rescanning, so the footer cannot disagree with what was tried.
        var miss = if (routes.any { it != null }) Miss.NO_ANSWER else Miss.NO_WIFI

        for (route in routes) {
            val status = runCatching { status(route) }.getOrNull()
            if (status == null) continue
            if (!status.has("capacity") || !status.has("interval_s")) {
                // Something answered at 192.168.4.1, but it is not a node with
                // a log on it: a house router, or a firmware without soak_log.
                miss = Miss.NOT_VAN_CORE
                continue
            }

            // Send the phone's clock first. It does not retro-date what is
            // already in the buffer, but every row after this one becomes
            // placeable in time - and on a node that has never been synced,
            // that is the difference between a log and a pile of numbers.
            runCatching { syncClock(route) }

            val intervalS = max(1f, status.optDouble("interval_s", 10.0).toFloat())
            val nodeRows = status.optInt("rows", 0)
            val capacity = status.optInt("capacity", 0)
            if (nodeRows == 0) {
                return Result(
                    added = 0, undateable = 0, nodeRows = 0, nodeCapacity = capacity,
                    clockState = status.optInt("clock", 0), miss = Miss.NO_LOG,
                )
            }

            val want = if (full) 0 else SyncPlan.rowsNeeded(store.newestEpoch(), intervalS, nodeRows)
            val r = runCatching { download(route, want, store) }.getOrNull() ?: continue
            return Result(
                added = r.first,
                undateable = r.second,
                nodeRows = nodeRows,
                nodeCapacity = capacity,
                clockState = status.optInt("clock", 0),
                miss = null,
            )
        }
        return Result(
            added = 0, undateable = 0, nodeRows = 0, nodeCapacity = 0,
            clockState = 0, miss = miss,
        )
    }

    private fun status(route: Network?): JSONObject? {
        val conn = open(route, "$base/soak/status")
        conn.connectTimeout = 4_000
        conn.readTimeout = 5_000
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            return runCatching { JSONObject(body) }.getOrNull()
        } finally {
            conn.disconnect()
        }
    }

    /** POST the phone's wall clock and UTC offset, as the /soak page does. */
    private fun syncClock(route: Network?) {
        val conn = open(route, "$base/soak/clock")
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 4_000
        conn.readTimeout = 5_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val now = System.currentTimeMillis()
            val tzMin = TimeZone.getDefault().getOffset(now) / 60_000
            conn.outputStream.write("ms=$now&tz=$tzMin".toByteArray())
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** @return rows written, rows the node could not date. */
    private fun download(route: Network?, last: Int, store: HistoryStore): Pair<Int, Int> {
        val url = if (last > 0) "$base/soak/log.csv?last=$last" else "$base/soak/log.csv"
        val conn = open(route, url)
        conn.connectTimeout = 4_000
        // Generous: several MB over SoftAP from a node that is also holding a
        // BLE link, and the firmware yields between batches on purpose.
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode != 200) return 0 to 0
            conn.inputStream.bufferedReader().use { reader ->
                return insertStreaming(reader, store)
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Parse and store in batches rather than collecting the whole log first.
     *
     * Four days at 10 s is ~35 000 rows. Holding those as objects while also
     * holding the CSV text is how an app gets killed on a phone that is also
     * running a browser; batching keeps the peak to one batch.
     */
    @VisibleForTesting
    internal fun insertStreaming(reader: BufferedReader, store: HistoryStore): Pair<Int, Int> {
        val batch = ArrayList<SoakCsv.Row>(BATCH)
        var written = 0
        var columns: List<String> = emptyList()
        val result = SoakCsv.parse(
            reader,
            onColumns = { columns = it },
        ) { row ->
            batch.add(row)
            if (batch.size >= BATCH) {
                if (columns.isNotEmpty()) written += store.insert(columns, batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty() && columns.isNotEmpty()) written += store.insert(columns, batch)
        return written to result.skipped
    }

    private const val BATCH = 2_000

    private fun open(route: Network?, url: String): HttpURLConnection {
        val u = URL(url)
        return (route?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }
}
