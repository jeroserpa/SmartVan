package van.supervisor.app

import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A window onto van-core's web UI (D-15), plus the one screen that cannot be
 * a page at all (D-25).
 *
 * The Dashboard tab is the node's own `ui/index.html`, unchanged and still the
 * source of truth for everything live. The History tab is native, for a
 * structural reason rather than a cosmetic one: `soak_log`'s ring is volatile
 * PSRAM holding ~4 days, so the archive has to live somewhere that survives
 * a power cut and works out of range. See [HistoryScreen].
 *
 * The whole point is routing: with mobile data on, Android sends unbound traffic
 * to cellular, where 192.168.4.1 does not exist. This activity binds *its own
 * process* to the Wi-Fi network, so the WebView reaches the van while every
 * other app on the phone keeps using cellular.
 *
 * No logic lives here. The web UI stays the source of truth. Three additions:
 * [VanBridge], which only saves bytes the page hands it (a WebView has no
 * download path of its own that respects the network binding), the offer of a
 * newer app build (D-23) - the work is in [AppUpdate]; this class asks - and
 * flashing van-core's firmware (D-24), which is all in [FirmwareFlash].
 *
 * Everything else in this class is about never showing a blank or a raw
 * WebView error: the page, or a panel that says what is happening and what to
 * do, or the page with a banner when the link dropped underneath it.
 *
 * And, out of reach, the page anyway (D-28): the node's own page, kept on the
 * phone by [OfflinePage], showing the last readings it saved, with a banner
 * that says why it is not live and opens the panel on a tap. The copy is
 * loaded under the node's URL so it finds those readings in its own storage,
 * and it talks to nobody - see shouldInterceptRequest.
 */
class MainActivity : AppCompatActivity() {

    // Full firmware serves the packed UI at /ui; bring-up and soak builds only
    // have ESPHome's stock page at /. ESPHome's IDF web server does not answer
    // an unknown path with a 404: it closes the socket (ERR_EMPTY_RESPONSE,
    // seen 2026-09-16 on van-core-soak). So any failure of /ui falls back to /.
    private val rootUrl = baseUrlOverride ?: "http://192.168.4.1/"
    private val uiUrl = rootUrl + "ui"

    /** Flashing van-core (D-24). A field: it registers the file picker, which must happen before onStart. */
    private val flash = FirmwareFlash(this, { rootUrl }, { boundNetwork }, { load(quiet = true) })

    companion object {
        /** The launcher shortcut's action (res/xml/shortcuts.xml): straight to the flash file picker. */
        const val ACTION_FLASH = "van.supervisor.app.FLASH_FIRMWARE"

        /** The other shortcut: ask GitHub for a newer app build now, and say what it answered (D-23). */
        const val ACTION_CHECK_UPDATE = "van.supervisor.app.CHECK_UPDATE"

        /**
         * Instrumented tests only: point the app at a mock van-core. A static
         * set from the test's own process, deliberately not an intent extra —
         * the activity is exported, and an extra would let any app aim the
         * WebView (and the save bridge) at its own page.
         */
        @VisibleForTesting
        internal var baseUrlOverride: String? = null

        /** When the last update check started (elapsedRealtime); process-wide, so app switching does not repeat it. */
        private var lastUpdateCheck: Long? = null

        /** The saved copy's history entry: the node's URL, so the copy keeps the node's origin, marked. */
        private const val SAVED_SUFFIX = "#saved"
    }

    private enum class Phase { SEARCHING, CONNECTING, NO_WIFI, UNREACHABLE, READY }

    private lateinit var cm: ConnectivityManager
    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var banner: TextView
    private lateinit var panel: View
    private lateinit var panelIcon: ImageView
    private lateinit var panelProgress: View
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: TextView
    private lateinit var panelActions: View
    private lateinit var panelDetails: TextView
    private lateinit var btnDetails: MaterialButton
    private lateinit var btnOffline: MaterialButton
    private val main = Handler(Looper.getMainLooper())

    /** The phone's copy of the node's page (D-28). */
    private val offlinePage by lazy { OfflinePage(this) }
    /**
     * The WebView holds the saved copy, not the live page. Volatile: the
     * bridge reads it on the JavaBridge thread and shouldInterceptRequest on
     * WebView's IO thread.
     */
    @Volatile private var offlineShown = false
    /** The status panel is open over the saved copy, by a tap on the banner. */
    private var statusOpen = false
    /** A reachability check is out while the saved copy is up. */
    private var probing = false
    /** The live page has been kept once this launch. It changes only by OTA. */
    private var pageKept = false
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

    /** Whichever update dialog is up - the question, the permission, the progress, or an answer. */
    @VisibleForTesting
    internal var updateDialog: AlertDialog? = null
        private set
    /** An update is downloading or being handed to Android; nothing may start another. */
    private var updating = false

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
        btnOffline = findViewById(R.id.btn_offline)

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
        // Over the saved copy the banner is the way to the panel, and the
        // panel's own button is the way back.
        banner.setOnClickListener { if (offlineShown) openStatus(true) }
        btnOffline.setOnClickListener { openStatus(false) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // History is a tab, not a screen on a stack, so Back out of it
                // lands on the dashboard rather than leaving the app - which
                // is what anyone who got there by tapping a tab expects.
                if (tabs.selectedTabPosition != 0) {
                    tabs.getTabAt(0)?.select()
                } else if (statusOpen && offlineShown) {
                    openStatus(false)
                } else if (phase == Phase.READY && web.canGoBack()) {
                    web.goBack()
                } else {
                    finish()
                }
            }
        })

        setPhase(Phase.SEARCHING)
        requestVanNetwork()
        // Not on a re-creation: the shortcut asked once, not on every rotation.
        if (savedInstanceState == null && intent?.action == ACTION_FLASH) main.post { flash.start() }
        // Not posted, unlike the flash: started here, before onResume, the
        // automatic check finds one under way and does not ask a second time.
        if (savedInstanceState == null && intent?.action == ACTION_CHECK_UPDATE) checkForUpdate(asked = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_FLASH) flash.start()
        if (intent.action == ACTION_CHECK_UPDATE) checkForUpdate(asked = true)
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
        if (onHistory) pauseWeb() else resumeWeb()
    }

    /** The WebView is frozen, or about to be. */
    private var webPaused = false

    /**
     * Freeze the dashboard - after asking the page to close its stream.
     *
     * Pausing alone never closed it (D-29): the stream stayed open, stopped
     * draining, and van-core's ESPHome 2026.8 abandoned it after ~20 s
     * without closing the socket, which then stayed held for as long as
     * this app lived in the background. That is one of the ways the node ran
     * out of sockets (M20). A closed stream is a FIN the node acts on.
     *
     * The freeze waits for the page's answer, or 400 ms if it has none: a
     * page that is frozen first never gets to close anything. Older pages
     * have no `VanPage` and are frozen as before.
     */
    private fun pauseWeb() {
        if (webPaused) return
        webPaused = true
        var done = false
        val freeze = Runnable {
            if (done) return@Runnable
            done = true
            if (webPaused && !isDestroyed) {
                web.onPause()
                web.pauseTimers()
            }
        }
        web.evaluateJavascript("window.VanPage&&VanPage.suspend()") { main.post(freeze) }
        main.postDelayed(freeze, 400)
    }

    private fun resumeWeb() {
        webPaused = false
        web.onResume()
        web.resumeTimers()
        web.evaluateJavascript("window.VanPage&&VanPage.resume()", null)
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
                // The saved copy finishing is not van-core answering.
                if (offlineShown || url.endsWith(SAVED_SUFFIX)) return
                if (phase == Phase.CONNECTING && url != failedUrl) {
                    setPhase(Phase.READY)
                    // Only the full UI: the `/` fallback is a bring-up build's
                    // stock page, which has nothing to keep.
                    if (url.startsWith(uiUrl)) keepPage()
                }
            }

            // The saved copy talks to nobody. With the process unbound, a
            // request for 192.168.4.1 would go out on mobile data, and the
            // page asks for its icon and manifest whatever it is told. The
            // copy itself is loaded as data, not fetched, so it never
            // arrives here as a main-frame request.
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                if (!offlineShown || request.isForMainFrame) return null
                return WebResourceResponse(
                    "text/plain", "utf-8", 404, "Not live", emptyMap(),
                    ByteArrayInputStream(ByteArray(0)),
                )
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
            statusOpen = false
            return
        }
        swipe.isRefreshing = false

        // Not live, and a saved copy of the page exists: show that, with a
        // line saying why, rather than a status screen (D-28). The panel
        // below is still filled in; it is one tap on the banner away.
        val copy = showOfflineCopy()
        btnOffline.visibility = if (copy) View.VISIBLE else View.GONE
        panel.visibility = if (copy && !statusOpen) View.GONE else View.VISIBLE
        if (copy) offlineBanner(p)

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

    // --- the saved copy (D-28) -------------------------------------------------

    /** Put the saved copy on screen if it is not already. False when there is none. */
    private fun showOfflineCopy(): Boolean {
        if (offlineShown) return true
        val html = offlinePage.load(rootUrl) ?: return false
        offlineShown = true
        // Under the node's own URL, so the page has the node's origin and
        // with it the readings it saved in localStorage while live. The
        // history entry is marked so its onPageFinished cannot pass for the
        // node answering.
        web.loadDataWithBaseURL(uiUrl, html, "text/html", "UTF-8", uiUrl + SAVED_SUFFIX)
        return true
    }

    private fun offlineBanner(p: Phase) {
        val (text, icon) = when (p) {
            Phase.SEARCHING -> R.string.st_off_searching to R.drawable.ic_refresh
            Phase.CONNECTING -> R.string.st_off_connecting to R.drawable.ic_refresh
            Phase.NO_WIFI -> R.string.st_off_nowifi to R.drawable.ic_wifi_off
            else -> R.string.st_off_unreachable to R.drawable.ic_link_off
        }
        // Muted, not amber: away from the van is the normal state of a phone,
        // not a fault. The chevron says there is more behind it.
        styleBanner(getString(text), R.color.muted, icon, R.drawable.ic_chevron_right)
    }

    /** The live page is up and the Wi-Fi went from under it. */
    private fun lostBanner() =
        styleBanner(getString(R.string.st_lost_banner), R.color.warn, R.drawable.ic_wifi_off, 0)

    private fun styleBanner(text: String, color: Int, start: Int, end: Int) {
        banner.text = text
        banner.setTextColor(getColor(color))
        banner.setCompoundDrawablesRelativeWithIntrinsicBounds(start, 0, end, 0)
        TextViewCompat.setCompoundDrawableTintList(banner, ColorStateList.valueOf(getColor(color)))
        banner.visibility = View.VISIBLE
    }

    /** Open or close the status panel over the saved copy. */
    private fun openStatus(open: Boolean) {
        statusOpen = open
        if (phase != Phase.READY) setPhase(phase)
    }

    /**
     * Keep the live page for next time. A second GET of /ui, once per
     * launch: a WebView will not hand over a page's bytes, and the node
     * serves it from flash with no heap copy, so it costs the node nothing
     * worth saving.
     */
    private fun keepPage() {
        if (pageKept) return
        pageKept = true
        val net = boundNetwork
        thread(name = "keep-page", isDaemon = true) {
            val ok = runCatching {
                offlinePage.save(rootUrl, offlinePage.get(net, uiUrl).body)
            }.getOrDefault(false)
            // A page that is not keepable (older firmware) is not worth asking
            // for again this launch; a failed read is.
            if (!ok && offlinePage.load(rootUrl) == null) main.post { pageKept = false }
        }
    }

    /**
     * Over the saved copy, ask the node before loading it: putting the live
     * URL into the WebView would swap the copy for an error page on every
     * retry, five seconds apart, for as long as the van is out of reach.
     *
     * The copy is replaced only once something answers. /ui first, keeping it
     * if it is the full UI; then `/`, because a bring-up build closes the
     * socket on /ui and serves only ESPHome's stock page - that is still the
     * node answering, and it must not be left behind a copy of a firmware it
     * no longer runs. Nothing answering at all (refused, timed out) skips
     * the second request.
     */
    private fun probe(quiet: Boolean) {
        if (probing) return
        probing = true
        if (!quiet) setPhase(Phase.CONNECTING)
        val net = boundNetwork
        thread(name = "van-probe", isDaemon = true) {
            var why: String? = null
            val ui = runCatching { offlinePage.get(net, uiUrl) }
            val answered = ui.fold(
                onSuccess = { r ->
                    if (r.code == 200) offlinePage.save(rootUrl, r.body)
                    r.code == 200 || runCatching { offlinePage.get(net, rootUrl).code == 200 }
                        .getOrDefault(false)
                },
                onFailure = { e ->
                    why = "$uiUrl\n${e.javaClass.simpleName}: ${e.message ?: ""}"
                    e !is ConnectException && e !is SocketTimeoutException &&
                        e !is NoRouteToHostException && e !is UnknownHostException &&
                        runCatching { offlinePage.get(net, rootUrl).code == 200 }.getOrDefault(false)
                },
            )
            main.post {
                probing = false
                if (isDestroyed) return@post
                if (answered) {
                    pageKept = true
                    offlineShown = false
                    statusOpen = false
                    // Quiet: the copy stays on screen until the live page
                    // commits over it, so there is no flash of anything else.
                    open(uiUrl, quiet = true)
                } else {
                    lastError = why ?: "$uiUrl\nno answer"
                    setPhase(Phase.UNREACHABLE)
                }
            }
        }
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
                    if (phase == Phase.READY) lostBanner()
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
        if (offlineShown) probe(quiet) else open(uiUrl, quiet)
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

        /**
         * Opens the firmware flash flow (D-24): file picker, then the app's own
         * questions. The page only asks for it; nothing is sent without the
         * user picking a file and confirming.
         */
        @JavascriptInterface
        fun flashFirmware(): String {
            if (!fromVanCore()) return "Refused: flashing is only available to van-core pages"
            main.post { flash.start() }
            return "ok"
        }

        /**
         * Whether this is the phone's saved copy of the page rather than the
         * live one (D-28). The page asks once, at load, and then neither
         * connects nor sends anything. Not origin-checked: it reveals nothing,
         * and the answer only ever makes a page do less.
         */
        @JavascriptInterface
        fun offlineCopy(): Boolean = offlineShown
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

    // --- updates (D-23) --------------------------------------------------------

    /**
     * Ask GitHub, off the UI thread, whether a newer build exists. At most once
     * per [AppUpdate.CHECK_EVERY_MS], unless [force]d: back from granting the
     * install permission, the user is waiting on it.
     *
     * Or because the user [asked], from the launcher shortcut. That check
     * ignores the 15 min limit and "Later" both, and always says what it
     * found ([reportUpdate]). The one that runs on its own stays silent by
     * design, which on 2026-10-13 left build 60 unoffered and nothing to tap.
     */
    private fun checkForUpdate(force: Boolean = false, asked: Boolean = false) {
        // Instrumented tests run against a mock van-core; a real GitHub check
        // there would only make the screenshots depend on the network.
        if (baseUrlOverride != null) return
        // Already on screen: the question, the download, or an earlier answer.
        if (updating || updateDialog?.isShowing == true) return
        // Installing closes the app, which would cut a firmware upload off.
        if (flash.busy) {
            if (asked) Toast.makeText(this, R.string.upd_not_while_flashing, Toast.LENGTH_LONG).show()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastUpdateCheck
        if (!force && !asked && last != null && now - last < AppUpdate.CHECK_EVERY_MS) return
        lastUpdateCheck = now
        if (asked) Toast.makeText(this, R.string.upd_checking, Toast.LENGTH_SHORT).show()
        thread(name = "update-check", isDaemon = true) {
            val r = AppUpdate.latest(applicationContext)
            main.post {
                if (asked) reportUpdate(r) else if (r != null) offerUpdate(r)
            }
        }
    }

    /**
     * The answer to a check the user asked for. A newer build gets the usual
     * question, "Later" or not; anything else gets a line saying why there is
     * no question, so that silence never has to be read as an answer.
     */
    @VisibleForTesting
    internal fun reportUpdate(r: AppUpdate.Release?) {
        if (isFinishing || isDestroyed || updating || updateDialog?.isShowing == true) return
        val installed = AppUpdate.installedBuild(this)
        if (r != null && r.build > installed) {
            offerUpdate(r, asked = true)
            return
        }
        val (title, body) = when {
            r == null -> getString(R.string.upd_no_answer_title) to getString(R.string.upd_no_answer_body)
            r.build == installed -> getString(R.string.upd_latest_title) to getString(R.string.upd_latest_body, installed)
            // A build from a branch, installed by hand: nothing published beats it.
            else -> getString(R.string.upd_latest_title) to getString(R.string.upd_ahead_body, installed, r.build)
        }
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun offerUpdate(r: AppUpdate.Release, asked: Boolean = false) {
        if (isFinishing || isDestroyed || updating || updateDialog?.isShowing == true || flash.busy) return
        val installed = AppUpdate.installedBuild(this)
        val pending = AppUpdate.takePending(this)
        if (r.build <= installed) return
        // Back from Android's settings after saying yes: no second question.
        // Still without the permission, the check that runs on its own takes
        // that as "not now"; one the user asked for asks again instead.
        if (pending > 0L) {
            if (packageManager.canRequestPackageInstalls()) {
                downloadUpdate(r)
                return
            }
            if (!asked) {
                AppUpdate.snooze(this)
                return
            }
        }
        if (!AppUpdate.isOffer(r, installed, AppUpdate.snoozeUntil(this), System.currentTimeMillis(), asked)) return
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.upd_title)
            .setMessage(getString(R.string.upd_body, r.build, installed, "%.1f".format(r.size / 1e6)))
            .setPositiveButton(R.string.upd_install) { _, _ -> startUpdate(r) }
            .setNegativeButton(R.string.upd_later) { _, _ -> AppUpdate.snooze(this) }
            // Back, or a tap outside, is "not now" too.
            .setOnCancelListener { AppUpdate.snooze(this) }
            .show()
    }

    /** Android asks once per phone before an app may install apps; go there first if needed. */
    private fun startUpdate(r: AppUpdate.Release) {
        if (packageManager.canRequestPackageInstalls()) {
            downloadUpdate(r)
            return
        }
        updateDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.upd_perm_title)
            .setMessage(R.string.upd_perm_body)
            .setPositiveButton(R.string.upd_perm_open) { _, _ ->
                AppUpdate.setPending(this, r.build)
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }.onFailure { AppUpdate.takePending(this) }
            }
            .setNegativeButton(R.string.upd_later) { _, _ -> AppUpdate.snooze(this) }
            .setOnCancelListener { AppUpdate.snooze(this) }
            .show()
    }

    private fun downloadUpdate(r: AppUpdate.Release) {
        updating = true
        val cancelled = AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.upd_downloading, r.build))
            .setMessage(getString(R.string.upd_progress, 0))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()
        updateDialog = dialog
        thread(name = "update-download", isDaemon = true) {
            var shown = 0
            val result = runCatching {
                AppUpdate.download(applicationContext, r, { pct ->
                    if (pct != shown) {
                        shown = pct
                        main.post { dialog.setMessage(getString(R.string.upd_progress, pct)) }
                    }
                }, { cancelled.get() })
            }
            main.post {
                runCatching { dialog.dismiss() }
                val apk = result.getOrElse { e ->
                    updating = false
                    if (!cancelled.get()) updateFailed(e)
                    return@post
                }
                // Android's confirmation cannot open over another app, so hand
                // over only while this one is on screen. Otherwise drop it; the
                // next open offers it again.
                if (isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    apk.delete()
                    updating = false
                    return@post
                }
                Toast.makeText(this, R.string.upd_installing, Toast.LENGTH_LONG).show()
                thread(name = "update-install", isDaemon = true) {
                    val handed = runCatching { AppUpdate.install(applicationContext, apk) }
                    main.post {
                        updating = false
                        handed.onFailure(::updateFailed)
                    }
                }
            }
        }
    }

    private fun updateFailed(e: Throwable) {
        if (isDestroyed) return
        val why = e.message ?: e.javaClass.simpleName
        Toast.makeText(this, getString(R.string.upd_failed, why), Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (tabs.selectedTabPosition == 0) {
            resumeWeb()
            // Back from Wi-Fi settings, or from the home screen: try at once.
            if (phase == Phase.NO_WIFI || phase == Phase.UNREACHABLE) load(quiet = true)
        } else {
            // Returning straight into History: pick up whatever the node has
            // logged since, without waking the page behind it.
            history?.onShown()
        }
        checkForUpdate(force = AppUpdate.hasPending(this))
    }

    override fun onPause() {
        main.removeCallbacks(autoRetry)
        pauseWeb()
        // The user was just looking at live values, so van-core is in range:
        // the best moment to bring the widget up to date.
        VanWidget.refreshNow(this)
        super.onPause()
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        flash.dismiss()
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
