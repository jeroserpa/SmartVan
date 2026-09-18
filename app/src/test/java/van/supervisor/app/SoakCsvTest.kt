package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoakCsvTest {

    private fun parse(s: String) = SoakCsv.collect(s.reader().buffered())

    private val sample = """
        # van-core soak log. rows=4 interval_s=10.0 boot=3 tz_min=120
        # clock: 0=unknown 1=phone-synced 2=carried across reboot (error ~1 interval)
        # boot 3 reset=SW at_row=0 time=2026-09-17 13:36:00
        local_time,epoch,uptime_s,boot,clock,t_fridge,t_cabin,fridge_w
        2026-09-17 13:36:00,1758108960,100,3,1,7.56,24.1,0.0
        2026-09-17 13:36:10,1758108970,110,3,1,7.58,24.1,0.1
        2026-09-17 13:36:20,1758108980,120,3,1,7.60,,21.8
        2026-09-17 13:36:30,1758108990,130,3,1,7.62,24.2,21.9
    """.trimIndent()

    @Test
    fun `reads the preamble, the columns and the rows`() {
        val (t, r) = parse(sample)
        assertEquals(listOf("t_fridge", "t_cabin", "fridge_w"), t.columns)
        assertEquals(4, t.rows.size)
        assertEquals(10f, r.meta.intervalS, 1e-6f)
        assertEquals(120, r.meta.tzMin)
        assertEquals(3, r.meta.boot)
        assertEquals(4, r.meta.declaredRows)
        assertEquals(1758108960L, t.rows[0].epoch)
        assertEquals(SoakCsv.CLOCK_PHONE, t.rows[0].clock)
    }

    /**
     * The one that would corrupt every analysis quietly: the firmware writes
     * `,,` for a sensor with no state, and reading that as 0 would drop a
     * 0 °C cabin into the middle of a temperature series.
     */
    @Test
    fun `an empty field is NAN, not zero`() {
        val (t, _) = parse(sample)
        val cabin = t.col("t_cabin")!!
        assertTrue(cabin[2].isNaN())
        assertEquals(24.1f, cabin[0], 1e-6f)
    }

    @Test
    fun `a column this app has never heard of still parses`() {
        val (t, _) = parse(sample)
        assertNull(t.col("something_new"))
        assertEquals(-1, t.index("something_new"))
        // and the known ones are unaffected
        assertEquals(4, t.col("fridge_w")!!.size)
    }

    /** Rows logged before anyone opened the page have no clock and no place in time. */
    @Test
    fun `rows with no epoch are dropped, and counted`() {
        val (t, r) = parse(
            """
            # van-core soak log. rows=2 interval_s=10.0 boot=1 tz_min=0
            local_time,epoch,uptime_s,boot,clock,t_fridge
            ,0,10,1,0,7.5
            2026-09-17 13:36:00,1758108960,20,1,1,7.6
            """.trimIndent()
        )
        assertEquals(1, t.rows.size)
        assertEquals(1, r.skipped)
        assertEquals(1758108960L, t.rows[0].epoch)
    }

    @Test
    fun `a truncated row pads with NAN rather than throwing`() {
        val (t, _) = parse(
            """
            # van-core soak log. rows=1 interval_s=10.0 boot=1 tz_min=0
            local_time,epoch,uptime_s,boot,clock,t_fridge,t_cabin,fridge_w
            2026-09-17 13:36:00,1758108960,20,1,1,7.6
            """.trimIndent()
        )
        assertEquals(1, t.rows.size)
        assertEquals(7.6f, t.col("t_fridge")!![0], 1e-6f)
        assertTrue(t.col("fridge_w")!![0].isNaN())
    }

    /**
     * A streaming consumer stores rows as they arrive, so it needs the column
     * names before the first one. Taking them from the returned Result instead
     * means they land after the last callback — and every batch but the final
     * one is written against an empty column list, i.e. thrown away. That was
     * a real bug in HistorySync, and this is the assertion that would have
     * caught it.
     */
    @Test
    fun `columns are announced before the first row`() {
        var columnsAt = -1
        var rowsSeen = 0
        var seen: List<String>? = null
        SoakCsv.parse(
            sample.reader().buffered(),
            onColumns = { seen = it; columnsAt = rowsSeen },
        ) { rowsSeen++ }
        assertEquals("header must precede every row", 0, columnsAt)
        assertEquals(listOf("t_fridge", "t_cabin", "fridge_w"), seen)
        assertEquals(4, rowsSeen)
    }

    @Test
    fun `an empty log is empty, not an exception`() {
        val (t, _) = parse("# van-core soak log. rows=0 interval_s=10.0 boot=1 tz_min=0\n")
        assertTrue(t.isEmpty)
        assertNull(t.col("t_fridge"))
    }
}
