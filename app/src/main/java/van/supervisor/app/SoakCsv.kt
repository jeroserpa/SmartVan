package van.supervisor.app

import java.io.BufferedReader

/**
 * The soak log's CSV, as emitted by `components/soak_log/soak_log.cpp`.
 *
 * Streaming on purpose. Four days at 10s is ~35k rows and several MB, and a
 * phone that parses that through one `String` pays for it twice — once as
 * chars, once as the row objects. [parse] hands each row to a callback so the
 * store can write it and forget it; [collect] is the convenience the tests and
 * the in-memory views use.
 *
 * The format, which this file is the only place that knows:
 *
 *     # van-core soak log. rows=1234 interval_s=10.0 boot=3 tz_min=120
 *     # clock: 0=unknown 1=phone-synced 2=carried across reboot
 *     # boot 1 reset=POWERON at_row=0 time=unknown
 *     local_time,epoch,uptime_s,boot,clock,t_fridge,t_cabin,...
 *     2026-09-17 13:36:00,1758108960,1234,3,1,7.56,24.1,...
 *
 * Two conventions matter and neither is guessable from the header:
 *  - **An empty field is NAN**, not zero. The firmware writes `,,` for a
 *    sensor that has no state. Reading those as 0 would put a 0 °C fridge and
 *    a 0 W load into the middle of every analysis.
 *  - **`local_time` is empty when the clock is unknown**, and then `epoch` is
 *    0 too. Such rows carry real sensor values but cannot be placed in time,
 *    so they are dropped rather than stacked at the epoch.
 */
object SoakCsv {

    /** One logged sample. [v] is parallel to [Table.columns]; NAN means "no state". */
    class Row(
        val epoch: Long,
        val uptimeS: Long,
        val boot: Int,
        val clock: Int,
        val v: FloatArray,
    )

    /** Clock provenance, straight from the firmware's `clock` column. */
    const val CLOCK_UNKNOWN = 0
    const val CLOCK_PHONE = 1
    const val CLOCK_CARRIED = 2

    /**
     * Rows plus the column names they are indexed by.
     *
     * Column names are *data*, never assumed: the node decides them, and a
     * firmware that logs a column this app has never heard of must still
     * parse. Everything downstream asks by name through [col] and copes with
     * null — which it has to anyway, since the fridge plug is a separate node
     * that is often simply not there.
     */
    class Table(val columns: List<String>, val rows: List<Row>) {
        fun index(name: String): Int = columns.indexOf(name)

        /** The named column as a series, or null if this log has no such column. */
        fun col(name: String): FloatArray? {
            val i = index(name)
            if (i < 0) return null
            return FloatArray(rows.size) { r -> rows[r].v.getOrElse(i) { Float.NaN } }
        }

        /** Epochs, seconds. Always ascending: [parse] drops undateable rows. */
        fun epochs(): LongArray = LongArray(rows.size) { rows[it].epoch }

        val isEmpty: Boolean get() = rows.isEmpty()
    }

    /** What the preamble said about the log as a whole. */
    class Meta(
        val intervalS: Float,
        val tzMin: Int,
        val boot: Int,
        val declaredRows: Int,
        val comments: List<String>,
    )

    class Result(val meta: Meta, val columns: List<String>, val rows: Int, val skipped: Int)

    /**
     * Parse [reader], calling [onRow] for each dateable row in file order.
     *
     * [onColumns] fires once, when the header is read and **before the first
     * row**. A streaming consumer needs the names to store a row at all, and
     * taking them from the returned [Result] instead means they arrive after
     * the last callback — which silently discards every batch but the final
     * one. That is not hypothetical: it is what the first version of
     * `HistorySync.insertStreaming` did.
     *
     * Returns what the preamble and header said, and how many rows were
     * dropped for having no clock — a number worth showing rather than
     * swallowing, because a log full of them means nobody ever opened the
     * page to sync the node's clock.
     */
    fun parse(
        reader: BufferedReader,
        onColumns: (List<String>) -> Unit = {},
        onRow: (Row) -> Unit,
    ): Result {
        var intervalS = 0f
        var tzMin = 0
        var boot = 0
        var declared = 0
        val comments = ArrayList<String>()
        var columns: List<String> = emptyList()
        var ncol = 0
        var kept = 0
        var skipped = 0

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                comments.add(line.removePrefix("#").trim())
                intervalS = kv(line, "interval_s") ?: intervalS
                tzMin = kv(line, "tz_min")?.toInt() ?: tzMin
                boot = kv(line, "boot")?.toInt() ?: boot
                declared = kv(line, "rows")?.toInt() ?: declared
                continue
            }

            if (columns.isEmpty()) {
                // The header. The five fixed fields are bookkeeping; whatever
                // follows them is this firmware's column set.
                val head = line.split(',')
                if (head.size <= FIXED || head[0] != "local_time") continue
                columns = head.subList(FIXED, head.size).map { it.trim() }
                ncol = columns.size
                onColumns(columns)
                continue
            }

            val f = split(line)
            if (f.size < FIXED) { skipped++; continue }
            val epoch = f[1].toLongOrNull() ?: 0L
            // No clock, no place in time. These rows hold real readings, but
            // putting them at the epoch would draw a 1970 spike through every
            // chart, and merging them by time is not possible at all.
            if (epoch <= 0L) { skipped++; continue }

            val v = FloatArray(ncol) { i ->
                val idx = FIXED + i
                if (idx < f.size) num(f[idx]) else Float.NaN
            }
            onRow(
                Row(
                    epoch = epoch,
                    uptimeS = f[2].toLongOrNull() ?: 0L,
                    boot = f[3].toIntOrNull() ?: 0,
                    clock = f[4].toIntOrNull() ?: CLOCK_UNKNOWN,
                    v = v,
                )
            )
            kept++
        }
        return Result(Meta(intervalS, tzMin, boot, declared, comments), columns, kept, skipped)
    }

    /** [parse], collecting into a [Table]. For tests and for small reads. */
    fun collect(reader: BufferedReader): Pair<Table, Result> {
        val rows = ArrayList<Row>()
        val r = parse(reader) { rows.add(it) }
        return Table(r.columns, rows) to r
    }

    /** local_time, epoch, uptime_s, boot, clock. */
    private const val FIXED = 5

    /** An empty field is NAN — the firmware's way of saying "no state". */
    private fun num(s: String): Float {
        val t = s.trim()
        if (t.isEmpty()) return Float.NaN
        return t.toFloatOrNull() ?: Float.NaN
    }

    /**
     * Split without a regex. This runs 35k times per sync on a phone, and
     * `String.split(",")` on a `String` delimiter is already the cheap path —
     * but it allocates a list per row, so keep it to exactly one pass.
     */
    private fun split(line: String): List<String> = line.split(',')

    /** `key=value` out of a preamble comment. */
    private fun kv(line: String, key: String): Float? {
        val at = line.indexOf("$key=")
        if (at < 0) return null
        val from = at + key.length + 1
        var to = from
        while (to < line.length && !line[to].isWhitespace() && line[to] != ',') to++
        return line.substring(from, to).toFloatOrNull()
    }
}
