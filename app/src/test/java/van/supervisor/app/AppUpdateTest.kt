package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update check's two decisions: what the version file says, and whether to ask (D-23). */
class AppUpdateTest {

    private val sha = "3f".repeat(32)

    /** Exactly the shape the publish step in .github/workflows/android-app.yml writes. */
    private fun file(
        build: String = "187",
        apk: String = "van-core-187.apk",
        size: String = "2012345",
        sha256: String = sha,
    ) = """
        # Latest build of main, read by the app's update check (D-23).
        build=$build
        apk=$apk
        size=$size
        sha256=$sha256
        commit=4f7320f
    """.trimIndent()

    @Test
    fun readsWhatCiPublishes() {
        val r = AppUpdate.parse(file())
        assertNotNull(r)
        assertEquals(187L, r!!.build)
        assertEquals("van-core-187.apk", r.apk)
        assertEquals(2012345L, r.size)
        assertEquals(sha, r.sha256)
    }

    @Test
    fun anUppercaseDigestIsTheSameDigest() {
        assertEquals(sha, AppUpdate.parse(file(sha256 = sha.uppercase()))!!.sha256)
    }

    /**
     * The name is appended to the release URL, so anything that is not a bare
     * file name could aim the download somewhere else.
     */
    @Test
    fun theApkMustBeABareFileName() {
        for (bad in listOf(
            "../van-core.apk", "a/van-core.apk", "https://example.com/x.apk",
            "van-core.apk?x=1", ".apk", "van-core.zip", "van core.apk", "",
        )) {
            assertNull(bad, AppUpdate.parse(file(apk = bad)))
        }
    }

    @Test
    fun anythingMissingOrMalformedMeansNoAnswer() {
        assertNull(AppUpdate.parse(""))
        assertNull(AppUpdate.parse("<html>Not Found</html>"))
        assertNull(AppUpdate.parse(file(build = "x")))
        assertNull(AppUpdate.parse(file(build = "0")))
        assertNull(AppUpdate.parse(file(size = "-1")))
        assertNull(AppUpdate.parse(file(sha256 = "3f".repeat(31))))
        assertNull(AppUpdate.parse(file(sha256 = "zz".repeat(32))))
        assertNull(AppUpdate.parse(file().lines().filterNot { it.startsWith("size=") }.joinToString("\n")))
    }

    @Test
    fun asksOnlyForANewerBuildAndNotWhileSnoozed() {
        val r = AppUpdate.parse(file())!!
        val now = 1_000_000L
        assertTrue(AppUpdate.isOffer(r, installed = 180, snoozeUntil = 0, now = now))
        assertFalse("same build", AppUpdate.isOffer(r, installed = 187, snoozeUntil = 0, now = now))
        assertFalse("older build published", AppUpdate.isOffer(r, installed = 190, snoozeUntil = 0, now = now))
        assertFalse("snoozed", AppUpdate.isOffer(r, installed = 180, snoozeUntil = now + 1, now = now))
        assertTrue("snooze over", AppUpdate.isOffer(r, installed = 180, snoozeUntil = now, now = now))
    }

    /**
     * The launcher shortcut's check: "Later" was about the check that runs on
     * its own, so asking past it gets the question. Asking never makes an old
     * or the same build worth installing.
     */
    @Test
    fun askingGoesPastLaterButNotPastTheBuildNumber() {
        val r = AppUpdate.parse(file())!!
        val now = 1_000_000L
        val later = now + AppUpdate.SNOOZE_MS
        assertTrue("snoozed, but asked", AppUpdate.isOffer(r, installed = 180, snoozeUntil = later, now = now, asked = true))
        assertTrue("asked, not snoozed", AppUpdate.isOffer(r, installed = 180, snoozeUntil = 0, now = now, asked = true))
        assertFalse("asked, same build", AppUpdate.isOffer(r, installed = 187, snoozeUntil = later, now = now, asked = true))
        assertFalse("asked, older build published", AppUpdate.isOffer(r, installed = 190, snoozeUntil = 0, now = now, asked = true))
    }
}
