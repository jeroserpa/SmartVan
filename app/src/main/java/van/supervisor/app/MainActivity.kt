package van.supervisor.app

import android.annotation.TargetApi
import android.app.Activity
import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A window onto van-core's web UI, and nothing more (D-15).
 *
 * The whole point is routing: with mobile data on, Android sends unbound traffic
 * to cellular, where 192.168.4.1 does not exist. This activity binds *its own
 * process* to the Wi-Fi network, so the WebView reaches the van while every
 * other app on the phone keeps using cellular.
 *
 * No logic lives here. The web UI stays the source of truth. The one addition
 * is [VanBridge], which only saves bytes the page hands it: a WebView has no
 * download path of its own that respects the network binding.
 */
class MainActivity : Activity() {

    // Full firmware serves the packed UI at /ui; bring-up and soak builds only
    // have ESPHome's stock page at /. ESPHome's IDF web server does not answer
    // an unknown path with a 404: it closes the socket (ERR_EMPTY_RESPONSE,
    // seen 2026-09-16 on van-core-soak). So any failure of /ui falls back to /.
    private val uiUrl = "http://192.168.4.1/ui"
    private val rootUrl = "http://192.168.4.1/"

    private lateinit var cm: ConnectivityManager
    private lateinit var web: WebView
    private lateinit var veil: LinearLayout
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())

    private var boundNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(ConnectivityManager::class.java)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                // Only the main document matters: a failed icon must not
                // blank a working page.
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (!request.isForMainFrame) return
                    val url = request.url.toString()
                    failedUrl = url
                    if (url == uiUrl) {
                        main.post { open(rootUrl) }
                    } else {
                        showMessage("van-core not reachable\n$url\n${error.description} (${error.errorCode})\n\n${networkInfo()}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, response: WebResourceResponse
                ) {
                    if (request.isForMainFrame && request.url.toString() == uiUrl) {
                        failedUrl = uiUrl
                        main.post { open(rootUrl) }
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!veilPinned && url != failedUrl) veil.visibility = View.GONE
                }
            }
            addJavascriptInterface(VanBridge(), "VanApp")
        }

        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        val retry = Button(this).apply {
            text = "Retry"
            setOnClickListener { load() }
        }
        veil = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101418.toInt())
            status.setTextColor(0xFFE0E0E0.toInt())
            addView(status)
            addView(retry, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }

        setContentView(FrameLayout(this).apply {
            addView(web)
            addView(veil)
        })

        showMessage("Looking for the van-core Wi-Fi…")
        requestVanNetwork()
    }

    /** True while a message must stay up regardless of page events. */
    private var veilPinned = false
    /** The last main-frame URL that failed; its own onPageFinished must not unveil an error page. */
    private var failedUrl: String? = null

    /** What the process is actually bound to — the first thing to know when a load fails. */
    private fun networkInfo(): String {
        val n = boundNetwork ?: return "Bound network: none"
        val lp = cm.getLinkProperties(n)
        val addrs = lp?.linkAddresses?.joinToString { it.toString() } ?: "?"
        val routes = lp?.routes?.joinToString { it.toString() } ?: "?"
        return "Bound: ${lp?.interfaceName ?: "?"}\nAddress: $addrs\nRoutes: $routes"
    }

    private fun showMessage(text: String) {
        veilPinned = true
        status.text = text
        veil.visibility = View.VISIBLE
    }

    /**
     * Ask for a Wi-Fi network that is *not* required to have internet. The van
     * AP never validates, and a default request would reject it.
     *
     * UNVERIFIED: this matches whichever Wi-Fi the phone is joined to. At home
     * that is the house network, where 192.168.4.1 is not van-core — the load
     * then fails and the message says so. SSID matching needs location
     * permission to read; not worth it until this proves to be a problem.
     */
    private fun requestVanNetwork() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                boundNetwork = network
                main.post { load() }
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    cm.bindProcessToNetwork(null)
                    boundNetwork = null
                    main.post { showMessage("Wi-Fi to van-core lost.\nWaiting for it to come back…") }
                }
            }

            override fun onUnavailable() {
                main.post { showMessage("No Wi-Fi connected.\nJoin the van-core network, then Retry.") }
            }
        }
        callback = cb
        // The timeout variant fires onUnavailable instead of waiting forever,
        // so an out-of-range van gives a message rather than a hang.
        cm.requestNetwork(request, cb, 10_000)
    }

    private fun load() {
        if (boundNetwork == null) {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            showMessage("Looking for the van-core Wi-Fi…")
            requestVanNetwork()
            return
        }
        failedUrl = null
        open(uiUrl)
    }

    private fun open(url: String) {
        veilPinned = false
        status.text = "Connecting to van-core…"
        veil.visibility = View.VISIBLE
        web.loadUrl(url)
    }

    /**
     * `window.VanApp` for van-core's pages: saves text the page has already
     * fetched. The page does the fetch over the bound network; DownloadManager
     * is not an option because it runs outside this process's binding and
     * would send 192.168.4.1 to cellular (D-15).
     *
     * Bridge calls arrive on WebView's JavaBridge thread, never the UI thread,
     * and the page's JS waits for the return value — so the write happens here,
     * synchronously, and the string it returns is the outcome the page shows.
     */
    inner class VanBridge {
        @JavascriptInterface
        fun saveFile(name: String, text: String): String {
            if (!fromVanCore()) return "Refused: saving is only available to van-core pages"
            val file = safeName(name)
            val bytes = text.toByteArray(Charsets.UTF_8)
            return try {
                val where = if (Build.VERSION.SDK_INT >= 29) saveToDownloads(file, bytes)
                            else saveToAppDownloads(file, bytes)
                "Saved $where (${Math.round(bytes.size / 1024.0)} kB)"
            } catch (e: Exception) {
                "Save failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * Whether the page currently shown is van-core's. The interface is injected
     * into whatever the WebView loads, so the check is per call. WebView may
     * only be read on the UI thread; the timeout means a stuck UI thread
     * refuses the save rather than hanging the page.
     */
    private fun fromVanCore(): Boolean {
        val url = AtomicReference<String?>()
        val done = CountDownLatch(1)
        main.post { url.set(web.url); done.countDown() }
        if (!done.await(2, TimeUnit.SECONDS)) return false
        val page = url.get()?.let(Uri::parse) ?: return false
        val van = Uri.parse(rootUrl)
        return page.scheme == van.scheme && page.host == van.host && page.port in setOf(-1, 80)
    }

    /** Basename only, `[A-Za-z0-9._-]`, no leading dots: nothing the page sends can pick a directory. */
    private fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimStart('.')
            .take(100)
        return base.ifEmpty { "van-core.csv" }
    }

    /** Android 10+: public Downloads through MediaStore, no storage permission. Returns what to tell the user. */
    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(name: String, bytes: ByteArray): String {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val row = contentResolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IllegalStateException("Downloads refused the file")
        try {
            contentResolver.openOutputStream(row)?.use { it.write(bytes) }
                ?: throw IllegalStateException("could not open $name for writing")
            contentResolver.update(row, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            runCatching { contentResolver.delete(row, null, null) }
            throw e
        }
        // MediaStore renames on collision ("x (1).csv"); report the name it kept.
        val kept = contentResolver.query(row, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        return "$kept to Downloads"
    }

    /**
     * Android 8-9: the public Downloads folder needs a runtime storage
     * permission, which a synchronous bridge call cannot ask for. The app's
     * own Downloads folder needs none, and on these versions any file manager
     * or USB can read it. It is removed if the app is uninstalled.
     */
    private fun saveToAppDownloads(name: String, bytes: ByteArray): String {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("shared storage not available")
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "${name.substring(0, dot)} (${n++})${name.substring(dot)}")
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(this, arrayOf(file.path), arrayOf("text/csv"), null)
        return "${file.name} to ${file.parent}"
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
    }

    override fun onPause() {
        web.onPause()
        web.pauseTimers()
        // The user was just looking at live values, so van-core is in range:
        // the best moment to bring the widget up to date.
        VanWidget.refreshNow(this)
        super.onPause()
    }

    override fun onDestroy() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cm.bindProcessToNetwork(null)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
