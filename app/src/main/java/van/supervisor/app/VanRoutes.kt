package van.supervisor.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * How anything in this app reaches 192.168.4.1.
 *
 * Extracted from [VanFeed] so the widget and the history sync cannot drift
 * apart: depending on a single route was the bug D-20 was written about, and
 * the fix belongs in one place rather than copied into each caller.
 *
 * The routes, in the order they are tried:
 *  1. **Wi-Fi networks the phone is already joined to.** Needs only
 *     `ACCESS_NETWORK_STATE`, answers immediately, and if we are sitting on
 *     the van's AP it is in this list right now.
 *  2. **`requestNetwork`**, the way MainActivity does it. Asynchronous, times
 *     out, and needs `CHANGE_NETWORK_STATE` — which a modern Android does not
 *     simply hand an ordinary app, so it is a fallback and never the plan.
 *  3. **The process default.** MainActivity binds the process while it is
 *     open, and a phone with mobile data off has the van AP as its default
 *     anyway. `null` here means "use whatever the process would use".
 */
object VanRoutes {

    /** Ordered candidates. A `null` entry is the process's own default route. */
    fun candidates(context: Context): List<Network?> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val routes = LinkedHashSet<Network>()
        runCatching {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) routes.add(n)
            }
        }
        if (routes.isEmpty()) requestWifi(cm)?.let { routes.add(it) }
        // The process default always goes last: it is the one that silently
        // succeeds against the wrong device when the phone is at home.
        return routes.toList<Network?>() + listOf(null)
    }

    /** MainActivity's request, verbatim: any Wi-Fi, internet explicitly not required. */
    private fun requestWifi(cm: ConnectivityManager): Network? {
        val found = AtomicReference<Network?>(null)
        val latch = CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.set(network)
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Throws without an effective CHANGE_NETWORK_STATE. That is a reason to
        // fall through to the other routes, never to fail the whole read.
        runCatching { cm.requestNetwork(request, cb, 10_000) }.onFailure { return null }
        return try {
            latch.await(11, TimeUnit.SECONDS)
            found.get()
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }
}
