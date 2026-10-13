package van.supervisor.app

import android.content.Context
import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The phone's copy of van-core's own page, for when van-core is out of reach
 * (D-28).
 *
 * Not a page bundled in the APK: the node's page ships with its firmware, by
 * OTA, in the same commit as the entities it shows (D-25), and an APK built
 * from another commit would show a different page. So the copy is the node's
 * own bytes, fetched after a live load and kept per base URL.
 *
 * Only a page that says it can run from a copy (`<meta name="van-offline">`)
 * is kept. That excludes older firmware, whose page does not save its last
 * readings and would show a screen of dashes, and anything else that answers
 * at 192.168.4.1 - a house router on the same address must never become "the
 * van" when the phone is away.
 *
 * What the copy shows is the page's business: it saved its last readings in
 * localStorage, and the WebView gives the copy the same origin by loading it
 * under the node's URL. This class only keeps the HTML.
 */
class OfflinePage(context: Context) {

    private val dir = File(context.filesDir, "offline")

    companion object {
        /** The page's own declaration that it may run from a copy. */
        private const val MARKER = "name=\"van-offline\""

        /** The page is ~70 kB; anything far past that is not it. */
        private const val MAX_BYTES = 512 * 1024

        /** Whether [html] is a van-core page that can run from a copy. */
        fun keepable(html: String): Boolean =
            html.contains(MARKER) && html.contains("<title>van-core</title>")

        /** One file per base URL, so a test node on another port never stands in for the van. */
        fun fileName(base: String): String =
            base.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_') + ".html"
    }

    private fun file(base: String) = File(dir, fileName(base))

    /** The kept copy for [base], or null if there is none. */
    fun load(base: String): String? =
        runCatching { file(base).takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    fun has(base: String): Boolean = file(base).isFile

    /** Keep [html] for [base] if it is keepable. Written beside and renamed, so a kill mid-write leaves the old copy. */
    fun save(base: String, html: String): Boolean {
        if (!keepable(html)) return false
        return runCatching {
            dir.mkdirs()
            val tmp = File(dir, fileName(base) + ".tmp")
            tmp.writeText(html, Charsets.UTF_8)
            if (!tmp.renameTo(file(base))) {
                file(base).delete()
                check(tmp.renameTo(file(base))) { "rename failed" }
            }
            true
        }.getOrDefault(false)
    }

    /** An HTTP answer. [body] is empty unless the status was 200. */
    class Reply(val code: Int, val body: String)

    /**
     * GET [url] over [network] (null: the process default). Blocking - call
     * off the main thread. Throws when nothing answered at all.
     *
     * Short timeouts on purpose: this doubles as the reachability probe while
     * the copy is on screen, and a probe that hangs for a minute leaves the
     * banner saying "connecting" about a node that is not there.
     */
    fun get(network: Network?, url: String): Reply {
        val u = URL(url)
        val c = (network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = 4_000
            c.readTimeout = 6_000
            c.useCaches = false
            val code = c.responseCode
            if (code != 200) return Reply(code, "")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    // Not the page; and not worth holding in memory to find out.
                    if (out.size() > MAX_BYTES) return Reply(code, "")
                }
            }
            return Reply(code, out.toString("UTF-8"))
        } finally {
            c.disconnect()
        }
    }
}
