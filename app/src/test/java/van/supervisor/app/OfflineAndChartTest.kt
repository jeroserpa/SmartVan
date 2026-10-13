package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pure parts of D-28: which pages the app may keep as the offline
 * dashboard, and the index arithmetic the chart explorer's every frame
 * rests on. The rest needs a WebView and SQLite, and is in ScreensTest.
 */
class OfflineAndChartTest {

    private fun page(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "ui/index.html")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("ui/index.html not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the node's own page may be kept`() {
        // Removing the marker would silently end the offline dashboard: the
        // app would stop keeping the page and fall back to the status panel.
        assertTrue(OfflinePage.keepable(page()))
    }

    @Test
    fun `nothing else at 192_168_4_1 is kept`() {
        // A house router on the same address must never become "the van".
        assertFalse(OfflinePage.keepable("<html><title>Router login</title></html>"))
        // An older van-core page answers, but saves no readings to show.
        assertFalse(OfflinePage.keepable("<html><head><title>van-core</title></head></html>"))
    }

    @Test
    fun `copies are kept per address`() {
        val van = OfflinePage.fileName("http://192.168.4.1/")
        assertEquals("http_192_168_4_1.html", van)
        assertNotEquals(van, OfflinePage.fileName("http://10.0.2.2:8080/"))
        assertNotEquals(OfflinePage.fileName("http://10.0.2.2:8080/"), OfflinePage.fileName("http://10.0.2.2:8081/"))
    }

    @Test
    fun `bounds find the window edges`() {
        val x = longArrayOf(10, 20, 20, 30, 40)
        assertEquals(0, ChartView.lowerBound(x, 5))
        assertEquals(1, ChartView.lowerBound(x, 20))
        assertEquals(3, ChartView.upperBound(x, 20))
        assertEquals(5, ChartView.lowerBound(x, 41))
        assertEquals(5, ChartView.upperBound(x, 40))
        assertEquals(0, ChartView.lowerBound(LongArray(0), 1))
    }

    @Test
    fun `the step is the typical spacing, not the gaps`() {
        // 10 s rows with two reboot holes: the gap threshold is built from
        // the step, so a hole must not inflate it into joining across holes.
        val x = ArrayList<Long>()
        var t = 1_000_000L
        repeat(500) { x.add(t); t += 10 }
        t += 3_600
        repeat(500) { x.add(t); t += 10 }
        t += 120
        repeat(500) { x.add(t); t += 10 }
        assertEquals(10L, ChartView.medianStep(x.toLongArray()))
        assertEquals(10L, ChartView.medianStep(longArrayOf(5)))
    }
}
