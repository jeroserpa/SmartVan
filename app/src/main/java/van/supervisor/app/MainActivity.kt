package van.supervisor.app

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Intent
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
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A window onto van-core's web UI (D-15), plus the one screen that cannot be
 * a page at all (D-22).
 *
 * The Dashboard tab is the node's own `ui/index.html`, unchanged and still the
 * source of truth for everything live. The History tab is native, for a
 * structural reason rather than a cosmetic one: `soak_log`'s ring is volatile
 * PSRAM holding ~4.3 days, so the archive has to live somewhere that survives
 * a power cut and works out of range. See [HistoryScreen].
 *
 * The whole point is routing: with mobile data on, Android sends unbound traffic
 * to cellular, where 192.168.4.1 does not exist. This activity binds *its own
 * process* to the Wi-Fi network, so the WebView reaches the van while every
 * other app on the phone keeps using cellular.
 *
 * No logic lives here. The web UI stays the source of truth. The one addition
 * is [VanBridge], which only saves bytes the page hands it: a WebView has no
 * download path of its own that respects the network binding.
 *
 * Everything else in this class is about never showing a blank or a raw
 * WebView error: the page, or a panel that says what is happening and what to
 * do, or the page with a banner when the link dropped underneath it.
 */
class MainActivity : AppCompatActivity() {

    // Full firmware serves the packed UI at /ui; bring-up and soak builds only
    // have ESPHome's stock page at /. ESPHome's IDF web server does not answer
    // an unknown path with a 404: it closes the socket (ERR_EMPTY_RESPONSE,
    // seen 2026-09-16 on van-core-soak). So any failure of /ui falls back to /.
    private val rootUrl = baseUrlOverride ?: "http://192.168.4.1/"
    private val uiUrl = rootUrl + "ui"

    companion object {
        /**
         * Instrumented tests only: point the app at a mock van-core. A static
         * set from the test's own process, deliberately not an intent extra —
         * the activity is exported, and an extra would let any app aim the
         * WebView (and the save bridge) at its own page.
         */
        @VisibleForTesting
        internal var baseUrlOverride: String? = null
    }

    private enum class Phase { SEARCHING, CONNECTING, NO_WIFI, UNREACHABLE, READY }

    private lateinit var cm: ConnectivityManager
    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var banner: View
    private lateinit var panel: View
    private lateinit var panelIcon: ImageView
    private lateinit var panelProgress: View
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelActions: View
    private lateinit var panelDetails: TextView
    private lateinit var btnDetails: MaterialButton
    private val main = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()

    private lateinit var tabs: TabLayout
    private lateinit var pageDashboard: View
    private lateinit var pageHistory: View
    private var history: HistoryScreen? = null

    private var boundNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var phase = Phase.SEARCHING
    /** The last main-frame URL that failed; its own onPageFinished must not count as loaded. */
    private var failedUrl: String? = null
    private var lastError: String? = null

    /** Whether the current load keeps the screen as it is (see [open]). */
    private var quietLoad = false

    /**
     * Out of range is expected; keep trying rather than waiting for a tap.
     * Quietly: the error panel, with its Wi-Fi settings button, stays up
     * until something actually changes, instead of flickering to "searching"
     * for most of every cycle.
     */
    private val autoRetry = Runnable {
        if (phase == Phase.NO_WIFI || phase == Phase.UNREACHABLE) load(quiet = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash while the Wi-Fi lookup settles (normally a few ms),
        // so the first frame is a real state rather than a flash of "searching".
        splash.setKeepOnScreenCondition {
            phase == Phase.SEARCHING && SystemClock.elapsedRealtime() - startedAt < 1_200
        }
        setContentView(R.layout.activity_main)
        cm = getSystemService(ConnectivityManager::class.java)

        web = findViewById(R.id.web)
        swipe = findViewById(R.id.swipe)
        banner = findViewById(R.id.banner)
        panel = findViewById(R.id.panel)
        panelIcon = findViewById(R.id.panel_icon)
        panelProgress = findViewById(R.id.panel_progress)
        panelTitle = findViewById(R.id.panel_title)
        panelBody = findViewById(R.id.panel_body)
        panelActions = findViewById(R.id.panel_actions)
        panelDetails = findViewById(R.id.panel_details)
        btnDetails = findViewById(R.id.btn_details)

        setupWebView()
        setupTabs()

        swipe.setColorSchemeResources(R.color.warn)
        swipe.setProgressBackgroundColorSchemeResource(R.color.card)
        // The page scrolls the document, so the WebView's own scroll position
        // is the right test for "at the top".
        swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        swipe.setOnRefreshListener { load(quiet = true) }

        findViewById<View>(R.id.btn_retry).setOnClickListener { load() }
        findViewById<View>(R.id.btn_wifi).setOnClickListener { openWifiSettings() }
        btnDetails.setOnClickListener { toggleDetails() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // History is a tab, not a screen on a stack, so Back out of it
                // lands on the dashboard rather than leaving the app - which
                // is what anyone who got there by tapping a tab expects.
                if (tabs.selectedTabPosition != 0) {
                    tabs.getTabAt(0)?.select()
                } else if (phase == Phase.READY && web.canGoBack()) {
                    web.goBack()
                } else {
                    finish()
                }
            }
        })

        setPhase(Phase.SEARCHING)
        requestVanNetwork()
    }

    private fun setupTabs() {
        tabs = findViewById(R.id.tabs)
        pageDashboard = findViewById(R.id.page_dashboard)
        pageHistory = findViewById(R.id.page_history)
        tabs.addTab(tabs.newTab().setText(R.string.tab_dashboard))
        tabs.addTab(tabs.newTab().setText(R.string.tab_history))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        showTab(0)
    }

    private fun showTab(index: Int) {
        val onHistory = index == 1
        pageDashboard.visibility = if (onHistory) View.GONE else View.VISIBLE
        pageHistory.visibility = if (onHistory) View.VISIBLE else View.GONE
        if (onHistory) {
            // Built on first use: it opens a database and starts a worker
            // thread, and most launches never leave the dashboard.
            val h = history ?: HistoryScreen(pageHistory).also { history = it }
            h.onShown()
        }
        // A WebView that is not being looked at should not be running timers
        // or holding an SSE stream open on a node that also runs BLE.
        if (onHistory) {
            web.onPause()
            web.pauseTimers()
        } else {
            web.onResume()
            web.resumeTimers()
        }
    }

    /**
     * The save path, shared with the page's `window.VanApp.saveFile`.
     *
     * [HistoryScreen] exports through here rather than through DownloadManager
     * for the same reason the page does: DownloadManager runs outside this
     * process's network binding. The origin check does not apply — this text
     * came from our own database, not from a page.
     */
    fun saveText(name: String, text: String): String {
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

    private fun setupWebView() {
        web.setBackgroundColor(getColor(R.color.bg))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportZoom(false)
        web.webViewClient = object : WebViewClient() {
            // Only the main document matters: a failed icon must not
            // blank a working page.
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val url = request.url.toString()
                failedUrl = url
                if (url == uiUrl) {
                    main.post { open(rootUrl, quietLoad) }
                } else {
                    lastError = "$url\n${error.description} (${error.errorCode})"
                    main.post { setPhase(Phase.UNREACHABLE) }
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, response: WebResourceResponse
            ) {
                if (request.isForMainFrame && request.url.toString() == uiUrl) {
                    failedUrl = uiUrl
                    main.post { open(rootUrl, quietLoad) }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipe.isRefreshing = false
                if (phase == Phase.CONNECTING && url != failedUrl) setPhase(Phase.READY)
            }

            // A crashed or killed renderer takes the WebView with it. Rebuild
            // the activity instead of letting the whole app die.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post { recreate() }
                return true
            }
        }
        web.addJavascriptInterface(VanBridge(), "VanApp")
    }

    private fun setPhase(p: Phase) {
        phase = p
        main.removeCallbacks(autoRetry)
        if (p == Phase.READY) {
            panel.visibility = View.GONE
            banner.visibility = View.GONE
            return
        }
        swipe.isRefreshing = false
        panel.visibility = View.VISIBLE

        val busy = p == Phase.SEARCHING || p == Phase.CONNECTING
        panelProgress.visibility = if (busy) View.VISIBLE else View.GONE
        panelActions.visibility = if (busy) View.GONE else View.VISIBLE
        btnDetails.visibility = if (busy) View.GONE else View.VISIBLE
        if (busy) showDetails(false)

        when (p) {
            Phase.NO_WIFI -> errorIcon(R.drawable.ic_wifi_off)
            Phase.UNREACHABLE -> errorIcon(R.drawable.ic_link_off)
            else -> {
                panelIcon.setImageResource(R.drawable.ic_launcher_foreground)
                panelIcon.clearColorFilter()
                panelIcon.setPadding(0, 0, 0, 0)
            }
        }
        val (title, body) = when (p) {
            Phase.SEARCHING -> R.string.st_searching_title to R.string.st_searching_body
            Phase.CONNECTING -> R.string.st_connecting_title to R.string.st_connecting_body
            Phase.NO_WIFI -> R.string.st_nowifi_title to R.string.st_nowifi_body
            else -> R.string.st_unreachable_title to R.string.st_unreachable_body
        }
        panelTitle.setText(title)
        panelBody.setText(body)
        refreshDetails()

        if (!busy && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            main.postDelayed(autoRetry, 5_000)
        }
    }

    private fun errorIcon(res: Int) {
        panelIcon.setImageResource(res)
        panelIcon.setColorFilter(getColor(R.color.muted))
        val pad = (20 * resources.displayMetrics.density).toInt()
        panelIcon.setPadding(pad, pad, pad, pad)
    }

    private fun toggleDetails() = showDetails(panelDetails.visibility != View.VISIBLE)

    private fun showDetails(show: Boolean) {
        panelDetails.visibility = if (show) View.VISIBLE else View.GONE
        btnDetails.setText(if (show) R.string.btn_hide_details else R.string.btn_details)
        if (show) refreshDetails()
    }

    private fun refreshDetails() {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
        panelDetails.text = buildString {
            lastError?.let { append(it).append("\n\n") }
            append(networkInfo())
            append("\n\nApp ").append(version).append(" · Android ").append(Build.VERSION.RELEASE)
        }
    }

    /** What the process is actually bound to — the first thing to know when a load fails. */
    private fun networkInfo(): String {
        val n = boundNetwork ?: return "Bound network: none"
        val lp = cm.getLinkProperties(n)
        val addrs = lp?.linkAddresses?.joinToString { it.toString() } ?: "?"
        val routes = lp?.routes?.joinToString { it.toString() } ?: "?"
        return "Bound: ${lp?.interfaceName ?: "?"}\nAddress: $addrs\nRoutes: $routes"
    }

    private fun openWifiSettings() {
        val panelIntent = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI)
                          else Intent(Settings.ACTION_WIFI_SETTINGS)
        runCatching { startActivity(panelIntent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
    }

    /**
     * Ask for a Wi-Fi network that is *not* required to have internet. The van
     * AP never validates, and a default request would reject it.
     *
     * UNVERIFIED: this matches whichever Wi-Fi the phone is joined to. At home
     * that is the house network, where 192.168.4.1 is not van-core — the load
     * then fails and the panel says so. SSID matching needs location
     * permission to read; not worth it until this proves to be a problem.
     */
    private fun requestVanNetwork() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (callback !== this) return@post
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                    banner.visibility = View.GONE
                    // Back after a drop with the page still up: reload under it.
                    load(quiet = phase == Phase.READY)
                }
            }

            override fun onLost(network: Network) {
                main.post {
                    if (callback !== this || network != boundNetwork) return@post
                    cm.bindProcessToNetwork(null)
                    boundNetwork = null
                    // Keep the last values on screen, marked, instead of blanking.
                    if (phase == Phase.READY) banner.visibility = View.VISIBLE
                    else setPhase(Phase.NO_WIFI)
                }
            }

            override fun onUnavailable() {
                main.post { if (callback === this) setPhase(Phase.NO_WIFI) }
            }
        }
        callback = cb
        // The timeout variant fires onUnavailable instead of waiting forever,
        // so an out-of-range van gives a message rather than a hang.
        cm.requestNetwork(request, cb, 10_000)
    }

    /** quiet: keep whatever is on screen — the page, or the current error panel. */
    private fun load(quiet: Boolean = false) {
        if (boundNetwork == null) {
            callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            callback = null
            if (quiet) {
                phase = Phase.SEARCHING
                main.removeCallbacks(autoRetry)
            } else {
                setPhase(Phase.SEARCHING)
            }
            requestVanNetwork()
            return
        }
        failedUrl = null
        lastError = null
        open(uiUrl, quiet)
    }

    private fun open(url: String, quiet: Boolean) {
        quietLoad = quiet
        if (quiet) {
            phase = Phase.CONNECTING
            main.removeCallbacks(autoRetry)
        } else {
            setPhase(Phase.CONNECTING)
        }
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
            return saveText(name, text)
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
        if (tabs.selectedTabPosition == 0) {
            web.onResume()
            web.resumeTimers()
            // Back from Wi-Fi settings, or from the home screen: try at once.
            if (phase == Phase.NO_WIFI || phase == Phase.UNREACHABLE) load(quiet = true)
        } else {
            // Returning straight into History: pick up whatever the node has
            // logged since, without waking the page behind it.
            history?.onShown()
        }
    }

    override fun onPause() {
        main.removeCallbacks(autoRetry)
        web.onPause()
        web.pauseTimers()
        // The user was just looking at live values, so van-core is in range:
        // the best moment to bring the widget up to date.
        VanWidget.refreshNow(this)
        super.onPause()
    }

    override fun onDestroy() {
        history?.close()
        history = null
        main.removeCallbacksAndMessages(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        cm.bindProcessToNetwork(null)
        web.destroy()
        super.onDestroy()
    }
}
