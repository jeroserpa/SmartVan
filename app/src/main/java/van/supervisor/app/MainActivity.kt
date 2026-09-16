package van.supervisor.app

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A window onto van-core's web UI, and nothing more (D-15).
 *
 * The whole point is routing: with mobile data on, Android sends unbound traffic
 * to cellular, where 192.168.4.1 does not exist. This activity binds *its own
 * process* to the Wi-Fi network, so the WebView reaches the van while every
 * other app on the phone keeps using cellular.
 *
 * No logic lives here. The web UI stays the source of truth.
 */
class MainActivity : Activity() {

    // Full firmware serves the packed UI at /ui; bring-up and soak builds only
    // have ESPHome's stock page at /. Try /ui first, fall back on a 404.
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
                    if (request.isForMainFrame) {
                        showMessage("van-core not reachable\n${error.description} (${error.errorCode})\n\n${networkInfo()}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView, request: WebResourceRequest, response: WebResourceResponse
                ) {
                    if (request.isForMainFrame && response.statusCode == 404 &&
                        request.url.toString() == uiUrl) {
                        view.loadUrl(rootUrl)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!overlayForcedByError) veil.visibility = View.GONE
                }
            }
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

    private var overlayForcedByError = false

    /** What the process is actually bound to — the first thing to know when a load fails. */
    private fun networkInfo(): String {
        val n = boundNetwork ?: return "Bound network: none"
        val lp = cm.getLinkProperties(n)
        val addrs = lp?.linkAddresses?.joinToString { it.toString() } ?: "?"
        val routes = lp?.routes?.joinToString { it.toString() } ?: "?"
        return "Bound: ${lp?.interfaceName ?: "?"}\nAddress: $addrs\nRoutes: $routes"
    }

    private fun showMessage(text: String) {
        overlayForcedByError = true
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
        overlayForcedByError = false
        status.text = "Connecting to van-core…"
        web.loadUrl(uiUrl)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
    }

    override fun onPause() {
        web.onPause()
        web.pauseTimers()
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
