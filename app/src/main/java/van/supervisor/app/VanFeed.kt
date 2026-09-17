package van.supervisor.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
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

    /** Instrumented tests only: read a mock van-core instead. */
    @VisibleForTesting
    internal var eventsUrlOverride: String? = null

    // Entity keys as "<domain>-<slugified name>", matched through [key] so both
    // id formats work: ESPHome up to 2025 sent "sensor-battery"; 2026.8 sends
    // "sensor/Battery" (domain/name — seen on van-core-soak, 2026-09-16).
    // Names are the ones in nodes/van-core.yaml.
    const val SOC = "sensor-battery"
    const val OUT = "sensor-output_power"
    const val IN = "sensor-input_power"
    // The two probes are named differently by the two firmwares that carry
    // them, so each is a list and the widget takes whichever the node actually
    // publishes. This is not hypothetical tolerance: it is the reason the
    // temperatures were blank on the bench node.
    //   nodes/van-core.yaml         "Fridge temperature" / "Cabin temperature"
    //   nodes/van-core-probes.yaml  "Fridge probe"       / "Cabin probe"
    const val FRIDGE = "sensor-fridge_temperature"
    const val CABIN = "sensor-cabin_temperature"
    const val FRIDGE_PROBE = "sensor-fridge_probe"
    const val CABIN_PROBE = "sensor-cabin_probe"

    /** Preferred first. A node publishes one of each, never both. */
    val FRIDGE_IDS = listOf(FRIDGE, FRIDGE_PROBE)
    val CABIN_IDS = listOf(CABIN, CABIN_PROBE)
    const val BLE = "binary_sensor-p310_connected"
    const val AC_OUT = "binary_sensor-p310_ac_output_active"
    const val PARKED = "binary_sensor-parked"
    const val REASON = "text_sensor-ac_reason"

    /**
     * The board temperature is matched by suffix, not by a fixed id, because
     * common/base.yaml names it "${friendly_name} board temperature" - so it is
     * `sensor-van_core_board_temperature` on van-core and
     * `sensor-van_core_soak_board_temperature` on the soak build. Naming both
     * would still miss the third node to be added.
     *
     * It is the ESP32's own die temperature, never a probe, and the widget
     * labels it "board" so it can never be read as the cabinet. It is shown
     * only when neither DS18B20 exists - which is the whole of the soak
     * firmware, where it is also the only temperature there is.
     */
    const val BOARD_SUFFIX = "board_temperature"

    /**
     * The snapshot is complete once every role has one of its ids. Roles, not
     * a flat set of ids, because a node that names its probes one way can
     * never satisfy the other way — a flat set holding both spellings would
     * mean the early exit never fires on any firmware, and every read would
     * pay the full quiet-window wait.
     */
    private val WANTED: List<List<String>> = listOf(
        listOf(SOC), listOf(OUT), listOf(IN), FRIDGE_IDS, CABIN_IDS,
        listOf(BLE), listOf(AC_OUT), listOf(PARKED), listOf(REASON),
    )

    /** Per-line read timeout. A gap this long is a pause, not the end. */
    private const val READ_TIMEOUT_MS = 2_000

    /** Consecutive quiet windows before the burst is taken as finished. */
    private const val QUIET_WINDOWS = 3

    /** Hard bound on the whole read, whatever the stream does. */
    private const val BURST_MS = 14_000L

    /** Why a read failed, so the widget can say something true about it. */
    enum class Miss { NO_WIFI, NO_ANSWER, NOT_VAN_CORE }

    /** Either the entity states, or the reason there are none. */
    class Reading internal constructor(
        val states: Map<String, JSONObject>?,
        val miss: Miss?,
    )

    /**
     * Read van-core over whatever route reaches it, trying each in turn.
     *
     * It used to depend on `requestNetwork` alone, and that is the fragile
     * half of this: it is asynchronous, it times out, and it needs
     * CHANGE_NETWORK_STATE, which on a modern Android is not a permission an
     * ordinary app simply has. When it failed, the widget declared "Not on
     * van-core Wi-Fi" while the phone was demonstrably on van-core's Wi-Fi and
     * the app was reading the same node over it.
     */
    fun fetch(context: Context): Reading {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        // 1. Networks the phone is already joined to. Costs nothing, answers
        //    at once, needs only ACCESS_NETWORK_STATE - and if we are sitting
        //    on the van's AP, it is in this list right now.
        val routes = LinkedHashSet<Network>()
        runCatching {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) routes.add(n)
            }
        }
        // 2. Only if that found nothing, ask the system to produce one, the way
        //    the activity does - same request, and the same 10s it allows.
        if (routes.isEmpty()) requestWifi(cm)?.let { routes.add(it) }
        val sawWifi = routes.isNotEmpty()

        var miss = if (sawWifi) Miss.NO_ANSWER else Miss.NO_WIFI
        for (route in routes) {
            val r = runCatching { readInitialBurst(route) }.getOrNull() ?: continue
            r.states?.let { return r }
            if (r.miss == Miss.NOT_VAN_CORE) miss = Miss.NOT_VAN_CORE
        }

        // 3. Last resort: the process's own default route. MainActivity binds
        //    the process to the van network while it is open, and a phone with
        //    mobile data off has the van AP as its default anyway.
        val r = runCatching { readInitialBurst(null) }.getOrNull()
        r?.states?.let { return r }
        if (r?.miss == Miss.NOT_VAN_CORE) miss = Miss.NOT_VAN_CORE
        return Reading(null, miss)
    }

    /** The activity's request, verbatim: any Wi-Fi, internet not required. */
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
        // Throws if CHANGE_NETWORK_STATE is not effective. That is a reason to
        // fall through to the other routes, not to fail the whole read.
        runCatching { cm.requestNetwork(request, cb, 10_000) }.onFailure { return null }
        return try {
            latch.await(11, TimeUnit.SECONDS)
            found.get()
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    /**
     * "sensor/Battery" or "sensor-battery" -> "sensor-battery". Slugged the way
     * ESPHome built object ids: lower case, anything outside [a-z0-9_-] -> '_'.
     * A device segment ("domain/device/name"), if ever used, is dropped.
     */
    fun key(id: String): String {
        val slash = id.indexOf('/')
        val (domain, name) = if (slash >= 0) {
            id.substring(0, slash) to id.substringAfterLast('/')
        } else {
            val dash = id.indexOf('-')
            if (dash < 0) return id
            id.substring(0, dash) to id.substring(dash + 1)
        }
        val slug = name.lowercase().map { c ->
            if (c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') c else '_'
        }.joinToString("")
        return "$domain-$slug"
    }

    /** @param network the route to use, or null for the process default. */
    private fun readInitialBurst(network: Network?): Reading {
        val url = URL(eventsUrlOverride ?: EVENTS_URL)
        val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        conn.connectTimeout = 4_000
        // The stream never ends on its own. A run of quiet windows after the
        // burst is the end-of-snapshot signal; the deadline bounds the
        // keep-alive pings.
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "text/event-stream")
        val deadline = SystemClock.elapsedRealtime() + BURST_MS
        val states = HashMap<String, JSONObject>()
        var sawEsphome = false
        try {
            // Something answered, but not with a state stream: a house router
            // at the same address, or a captive portal. Not "no Wi-Fi".
            if (conn.responseCode != 200) return Reading(null, Miss.NOT_VAN_CORE)
            val reader = conn.inputStream.bufferedReader()
            var event = "message"
            val data = StringBuilder()
            var quiet = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketTimeoutException) {
                    // A gap mid-burst is not the end of it. van-core pushes one
                    // entity per loop, and that loop also runs the BLE client,
                    // the display and the SD writer (CLAUDE.md section 2), so a
                    // pause of a second or two is normal. Stopping at the first
                    // one truncated the snapshot after the station sensors -
                    // which are declared first - and everything declared later
                    // (both temperatures, parked, the AC reason) stayed blank.
                    if (++quiet >= QUIET_WINDOWS) break else continue
                } ?: break
                quiet = 0

                when {
                    line.isEmpty() -> {
                        if (event == "ping") sawEsphome = true
                        if (event == "state" && data.isNotEmpty()) {
                            val j = runCatching { JSONObject(data.toString()) }.getOrNull()
                            val id = j?.optString("id").orEmpty()
                            if (j != null && id.isNotEmpty()) {
                                states[key(id)] = j
                                sawEsphome = true
                            }
                        }
                        event = "message"
                        data.setLength(0)
                        if (WANTED.all { role -> role.any { states.containsKey(it) } }) break
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
        return if (sawEsphome) Reading(states, null) else Reading(null, Miss.NOT_VAN_CORE)
    }
}
