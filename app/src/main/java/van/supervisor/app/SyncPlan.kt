package van.supervisor.app

import kotlin.math.min

/**
 * How much of van-core's ring buffer to ask for. Policy, not networking —
 * which is why it lives apart from [HistorySync] and is tested on the JVM.
 *
 * `GET /soak/log.csv?last=N` counts rows back from the newest. The two sides
 * cannot agree on row numbers across a reboot, so the request is derived from
 * *time* and converted to rows at the node's nominal interval. The conversion
 * is deliberately generous in one direction only:
 *
 *  - **Too many** costs a few seconds of Wi-Fi. `epoch` is the store's primary
 *    key, so the overlap dedupes itself.
 *  - **Too few** leaves a hole that can never be filled. Once the ring wraps
 *    those rows are gone, and a power cut takes the whole buffer with it
 *    (`soak_log.h`) — there is no second chance to fetch them.
 */
object SyncPlan {

    /** Rows written while the download itself is in flight, plus slack. */
    private const val FLOOR = 64

    /**
     * Rows to request, or 0 meaning "the whole buffer".
     *
     * @param newestStored epoch of the newest row already archived, 0 if none.
     * @param intervalS the node's logging interval, from `/soak/status`.
     * @param nodeRows how many rows the node currently holds.
     * @param nowS wall clock, injectable so the tests are not timing-dependent.
     */
    fun rowsNeeded(
        newestStored: Long,
        intervalS: Float,
        nodeRows: Int,
        nowS: Long = System.currentTimeMillis() / 1000L,
    ): Int {
        if (newestStored <= 0L) return 0
        val safeInterval = if (intervalS > 0f) intervalS else 10f
        val gapS = nowS - newestStored
        // The archive is level with, or ahead of, the node — two clocks that
        // disagree by a few minutes. Take a small top-up rather than treating
        // a negative gap as zero work or as an enormous request.
        if (gapS <= 0L) return min(nodeRows, FLOOR)
        val rows = (gapS / safeInterval).toInt() * 2 + FLOOR
        // Past the node's whole buffer there is nothing further to ask for,
        // and "everything" is both cheaper to express and more robust.
        return if (rows >= nodeRows) 0 else rows
    }
}
