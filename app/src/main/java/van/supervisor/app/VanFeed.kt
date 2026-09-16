package van.supervisor.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot read of van-core's state for the widget.
 *
 * Uses the same /events stream as the web UI: on connect ESPHome sends a
 * `ping` carrying its config, then one `state` event per entity. Reading that
 * initial burst and hanging up gives a full snapshot over a single short
 * connection, with no firmware change.
 *
 * Runs on a WorkManager thread, not the activity, so it cannot rely on the
 * activity's process binding: the request is sent over the Wi-Fi network
 * explicitly with Network.openConnection().
 */
object VanFeed {

    private const val EVENTS_URL = "http://192.168.4.1/events"

    // Entity ids as ESPHome's web_server derives them: "<domain>-<slugified name>".
    // Mirrors the E map in ui/index.html — keep the two in step.
    const val SOC = "sensor-battery"
    const val OUT = "sensor-output_power"
    const val IN = "sensor-input_power"
    const val FRIDGE = "sensor-fridge_temperature"
    const val BLE = "binary_sensor-p310_connected"
    const val AC_OUT = "binary_sensor-p310_ac_output_active"
    const val PARKED = "binary_sensor-parked"
    const val REASON = "text_sensor-ac_reason"

    private val WANTED = setOf(SOC, OUT, IN, FRIDGE, BLE, AC_OUT, PARKED, REASON)

    /** Entity id -> ESPHome state JSON, or null if van-core was not reachable. */
    fun fetch(context: Context): Map<String, JSONObject>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
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
        // Same request as the activity: any Wi-Fi, internet not required. Only
        // matches a network the phone is already joined to; never starts a scan.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(request, cb, 5_000)
        try {
            latch.await(6, TimeUnit.SECONDS)
            val network = found.get() ?: return null
            return runCatching { readInitialBurst(network) }.getOrNull()
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    private fun readInitialBurst(network: Network): Map<String, JSONObject>? {
        val conn = network.openConnection(URL(EVENTS_URL)) as HttpURLConnection
        conn.connectTimeout = 4_000
        // The stream never ends on its own. A quiet gap after the burst is the
        // end-of-snapshot signal; the deadline bounds the keep-alive pings.
        conn.readTimeout = 2_500
        conn.setRequestProperty("Accept", "text/event-stream")
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val states = HashMap<String, JSONObject>()
        var sawEsphome = false
        try {
            if (conn.responseCode != 200) return null
            val reader = conn.inputStream.bufferedReader()
            var event = "message"
            val data = StringBuilder()
            while (SystemClock.elapsedRealtime() < deadline) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    null
                } ?: break

                when {
                    line.isEmpty() -> {
                        if (event == "ping") sawEsphome = true
                        if (event == "state" && data.isNotEmpty()) {
                            val j = runCatching { JSONObject(data.toString()) }.getOrNull()
                            val id = j?.optString("id").orEmpty()
                            if (j != null && id.isNotEmpty()) {
                                states[id] = j
                                sawEsphome = true
                            }
                        }
                        event = "message"
                        data.setLength(0)
                        if (states.keys.containsAll(WANTED)) break
                    }
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).trimStart())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        // A reply from some other device at 192.168.4.1 (a house router, say)
        // is not a snapshot of the van.
        return if (sawEsphome) states else null
    }
}
