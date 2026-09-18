package van.supervisor.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The phone's copy of van-core's log — and the reason any of this is native.
 *
 * `soak_log`'s ring lives in `.ext_ram_noinit` PSRAM. It survives a crash, a
 * watchdog, an OTA and the restart button, and is **lost on a power cut**
 * (`components/soak_log/soak_log.h`). A 12 V bus dropout erases days of
 * measurement. The node also only holds ~4.3 days at 10 s before it starts
 * overwriting itself.
 *
 * So the phone keeps its own copy, and keeps it forever. Everything the web
 * UI does could be done in a page; this could not, and that asymmetry is the
 * whole argument for the History screen being native (docs/decisions.md D-22).
 *
 * ## Columns are data, not code
 *
 * Column names come from the firmware, and a firmware that logs a new column
 * must not need a new APK — the app ships by CI and a download, while the
 * YAML ships by OTA in the same commit as the entity it added. So rather than
 * a fixed schema mirroring `nodes/van-core.yaml`, each column name is assigned
 * a numbered slot on first sight and stored in [TABLE_COLS]. Reads ask by
 * name. An unknown column is stored and plotted like any other; a column that
 * disappears simply stops arriving.
 *
 * This is deliberately *not* how `VanFeed` names entity ids — that one really
 * is a duplication of `ui/index.html`, and it is the one this file exists not
 * to repeat.
 */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "history.db"
        private const val DB_VERSION = 1

        /** `components/soak_log/soak_log.h` caps a log at 40 columns. Match it. */
        const val MAX_SLOTS = 40

        private const val TABLE_ROWS = "samples"
        private const val TABLE_COLS = "cols"
        private const val TABLE_META = "meta"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_COLS (name TEXT PRIMARY KEY, slot INTEGER UNIQUE NOT NULL)"
        )
        db.execSQL("CREATE TABLE $TABLE_META (k TEXT PRIMARY KEY, v TEXT)")
        // epoch is the primary key, so a re-download of overlapping rows
        // dedupes itself. Sync deliberately over-requests, and this is what
        // makes that free.
        val slots = (0 until MAX_SLOTS).joinToString(", ") { "c$it REAL" }
        db.execSQL(
            "CREATE TABLE $TABLE_ROWS (" +
                "epoch INTEGER PRIMARY KEY, boot INTEGER, uptime INTEGER, clock INTEGER, $slots)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // v1 is the first. A future migration adds columns; it must never drop
        // the table, because what is in here may be the only surviving copy of
        // a measurement run - the node's own buffer is volatile.
    }

    // ------------------------------------------------------------------ write

    /**
     * Insert [table]'s rows, assigning slots to any column not seen before.
     *
     * Returns how many rows were written. One transaction and one compiled
     * statement: a full sync is ~35 000 rows, and doing that row-at-a-time on
     * a phone takes minutes rather than seconds.
     */
    fun insert(columns: List<String>, rows: Iterable<SoakCsv.Row>): Int {
        val db = writableDatabase
        val slots = assignSlots(db, columns)
        var written = 0
        db.beginTransaction()
        try {
            val cols = (0 until MAX_SLOTS).joinToString(", ") { "c$it" }
            val qs = (0 until MAX_SLOTS + 4).joinToString(", ") { "?" }
            val st = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE_ROWS (epoch, boot, uptime, clock, $cols) VALUES ($qs)"
            )
            for (r in rows) {
                st.clearBindings()
                st.bindLong(1, r.epoch)
                st.bindLong(2, r.boot.toLong())
                st.bindLong(3, r.uptimeS)
                st.bindLong(4, r.clock.toLong())
                for (i in columns.indices) {
                    val slot = slots[i]
                    if (slot < 0) continue                      // no room; column dropped
                    val v = r.v.getOrElse(i) { Float.NaN }
                    // NAN is stored as NULL. SQLite has no NaN, and a column
                    // holding it would come back as 0.0 - the exact confusion
                    // SoakCsv refuses to make on the way in.
                    if (!v.isNaN()) st.bindDouble(5 + slot, v.toDouble())
                }
                st.executeInsert()
                written++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return written
    }

    /** Slot per column, parallel to [columns]; -1 when the table is full. */
    private fun assignSlots(db: SQLiteDatabase, columns: List<String>): IntArray {
        val known = HashMap<String, Int>()
        db.rawQuery("SELECT name, slot FROM $TABLE_COLS", null).use { c ->
            while (c.moveToNext()) known[c.getString(0)] = c.getInt(1)
        }
        var next = (known.values.maxOrNull() ?: -1) + 1
        val out = IntArray(columns.size)
        for (i in columns.indices) {
            val name = columns[i]
            val existing = known[name]
            if (existing != null) {
                out[i] = existing
            } else if (next < MAX_SLOTS) {
                db.insertWithOnConflict(
                    TABLE_COLS, null,
                    ContentValues().apply {
                        put("name", name)
                        put("slot", next)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                known[name] = next
                out[i] = next
                next++
            } else {
                out[i] = -1
            }
        }
        return out
    }

    // ------------------------------------------------------------------- read

    /** (slot, name) for every column this store has ever seen, in slot order. */
    private fun slotMap(): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        readableDatabase.rawQuery("SELECT name, slot FROM $TABLE_COLS ORDER BY slot", null)
            .use { c -> while (c.moveToNext()) out.add(c.getInt(1) to c.getString(0)) }
        return out
    }

    /** Column names this store has ever seen, in slot order. */
    fun columns(): List<String> = slotMap().map { it.second }

    /**
     * Rows in `[from, to]`, oldest first, as the same [SoakCsv.Table] the
     * parser produces — so every function in [Analysis] works identically on a
     * fresh download and on three weeks of archive.
     */
    fun read(from: Long, to: Long, limit: Int = 200_000): SoakCsv.Table {
        val map = slotMap()
        if (map.isEmpty()) return SoakCsv.Table(emptyList(), emptyList())
        val names = map.map { it.second }
        // Select by the recorded slot, never by position. They agree today
        // because slots are handed out contiguously from 0 - but a future
        // migration that retires a column would break a positional read
        // silently, shifting every series onto its neighbour's name.
        val sel = map.joinToString(", ") { "c${it.first}" }
        val rows = ArrayList<SoakCsv.Row>()
        readableDatabase.rawQuery(
            "SELECT epoch, boot, uptime, clock, $sel FROM $TABLE_ROWS " +
                "WHERE epoch BETWEEN ? AND ? ORDER BY epoch LIMIT ?",
            arrayOf(from.toString(), to.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val v = FloatArray(names.size) { i ->
                    val col = 4 + i
                    if (c.isNull(col)) Float.NaN else c.getFloat(col)
                }
                rows.add(
                    SoakCsv.Row(
                        epoch = c.getLong(0),
                        boot = c.getInt(1),
                        uptimeS = c.getLong(2),
                        clock = c.getInt(3),
                        v = v,
                    )
                )
            }
        }
        return SoakCsv.Table(names, rows)
    }

    class Span(val rows: Int, val oldest: Long, val newest: Long)

    /** What the archive holds. Rows 0 means nothing has ever synced. */
    fun span(): Span {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(epoch), MAX(epoch) FROM $TABLE_ROWS", null
        ).use { c ->
            if (!c.moveToFirst() || c.getInt(0) == 0) return Span(0, 0, 0)
            return Span(c.getInt(0), c.getLong(1), c.getLong(2))
        }
    }

    /** Epoch of the newest stored row, or 0. Drives the incremental sync. */
    fun newestEpoch(): Long = span().newest

    /**
     * Erase everything. Behind a confirmation in the UI, and worth being
     * blunt about: this is the only copy that survives a power cut.
     */
    fun clear() {
        writableDatabase.apply {
            execSQL("DELETE FROM $TABLE_ROWS")
            execSQL("DELETE FROM $TABLE_COLS")
            execSQL("DELETE FROM $TABLE_META")
        }
    }
}
