package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one piece of sync arithmetic that can lose data.
 *
 * `?last=N` counts rows from the newest end. Ask for too many and the overlap
 * dedupes itself on `epoch`, costing a few seconds of Wi-Fi. Ask for too few
 * and the gap is permanent: once the node's ring wraps, those rows are gone,
 * and PSRAM loses the lot on a power cut anyway.
 */
class SyncPlanTest {

    /** Fixed, so these assertions do not depend on when they run. */
    private val now = 1_758_000_000L

    @Test
    fun `an empty archive asks for everything`() {
        assertEquals(0, SyncPlan.rowsNeeded(0L, 10f, 30_000, now))
    }

    @Test
    fun `a gap is converted to rows, with margin`() {
        // An hour behind at 10 s is 360 rows; the margin doubles it and adds a
        // fixed floor for the rows written during the download itself.
        val n = SyncPlan.rowsNeeded(now - 3600, 10f, 30_000, now)
        assertTrue("must cover the gap", n > 360)
        assertTrue("and not the whole buffer", n in 361..2000)
    }

    @Test
    fun `a gap wider than the node's buffer asks for everything`() {
        // Three weeks away. The node holds ~4.3 days, so there is no point
        // naming a number larger than its capacity.
        assertEquals(0, SyncPlan.rowsNeeded(now - 21 * 86_400, 10f, 30_000, now))
    }

    /**
     * Clocks disagree. If the archive's newest row is stamped slightly ahead
     * of the phone, the gap goes negative — and a negative row count must not
     * become a huge or a zero request by accident.
     */
    @Test
    fun `a clock running backwards still asks for a sane amount`() {
        val n = SyncPlan.rowsNeeded(now + 600, 10f, 30_000, now)
        assertTrue("small, positive, bounded", n in 1..64)
    }

    @Test
    fun `a nonsense interval does not divide by zero`() {
        val n = SyncPlan.rowsNeeded(now - 3600, 0f, 30_000, now)
        assertTrue("falls back to the 10 s default", n in 361..2000)
    }

    @Test
    fun `a tiny node buffer is never over-asked`() {
        // Capacity smaller than the computed need collapses to "everything".
        assertEquals(0, SyncPlan.rowsNeeded(now - 3600, 10f, 100, now))
    }
}
