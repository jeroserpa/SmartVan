package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The widget's footer and the web page's header describe the same moment in
 * the same words. Both tables are written by hand, in two languages, so this
 * reads the page's REASON table out of ui/index.html and holds the widget to
 * it: a reworded reason on one side fails here instead of on a phone.
 */
class ReasonTextTest {

    private fun page(): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "ui/index.html")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("ui/index.html not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the widget says what the page says, for every reason the page knows`() {
        val table = page().substringAfter("const REASON = {").substringBefore("};")
        val pairs = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""").findAll(table)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        // All eleven of arbiter_core.cpp's reasons, or the table moved.
        assertTrue("found only ${pairs.size} reasons in the page", pairs.size >= 11)
        for ((raw, words) in pairs) {
            assertEquals("reason \"$raw\"", words, VanFeed.reasonText(raw))
        }
    }

    @Test
    fun `an unknown reason passes through rather than vanishing`() {
        assertEquals("something new", VanFeed.reasonText("something new"))
        assertEquals("", VanFeed.reasonText(null))
        assertEquals("", VanFeed.reasonText(""))
    }
}
