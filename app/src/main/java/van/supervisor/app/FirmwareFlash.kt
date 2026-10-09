package van.supervisor.app

import android.content.Context
import android.content.DialogInterface
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Flashing van-core from the phone (D-24): pick an ESPHome `.ota.bin`, say
 * what it is and whether AC is on, upload it over the van Wi-Fi with ESPHome's
 * own OTA protocol ([EspOta]), then wait for the node to come back and show
 * what it is running.
 *
 * The web UI cannot do this - the running firmware has no browser upload and
 * a page has no TCP socket - so it is the one thing the app does that the page
 * does not. It still decides nothing about the van: it carries an image the
 * user picked, after the user confirmed it, to the node the app already shows.
 *
 * Construct it as a field of the activity: it registers the file picker, which
 * Android allows only before the activity starts.
 */
class FirmwareFlash(
    private val activity: AppCompatActivity,
    /** van-core's root URL: http://192.168.4.1/, or the mock in instrumented tests. */
    private val baseUrl: () -> String,
    /** The van Wi-Fi as the activity has it bound, or null while it has none. */
    private val network: () -> Network?,
    /** Called once van-core answers again, to reload the page onto it. */
    private val onNodeBack: () -> Unit,
) {
    private class Picked(val name: String, val bytes: ByteArray, val image: EspOta.Image)

    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var dialog: AlertDialog? = null

    /**
     * From the picker to the node answering again. Nothing may start a second
     * flash meanwhile, and no app update may close the app under it (D-23).
     */
    @Volatile
    var busy = false
        private set

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false else read(uri)
    }

    fun start() {
        if (busy || !alive()) return
        busy = true
        // */*: ESPHome's .bin has no MIME type a provider reliably reports.
        runCatching { picker.launch(arrayOf("*/*")) }.onFailure { e -> failed(e.message ?: "no file picker") }
    }

    fun dismiss() {
        dialog?.let { runCatching { it.dismiss() } }
        dialog = null
    }

    // --- 1. read and describe, and ask van-core about AC -------------------------

    private fun read(uri: Uri) {
        show(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.fl_checking_title)
                .setMessage(R.string.fl_checking_body)
                .setCancelable(false)
        )
        thread(name = "flash-read", isDaemon = true) {
            val name = displayName(uri)
            val picked = runCatching {
                val bytes = activity.contentResolver.openInputStream(uri)
                    ?.use { readCapped(it, EspOta.MAX_IMAGE + 1) }
                    ?: throw IOException("cannot open the file")
                Picked(name, bytes, EspOta.describe(bytes))
            }
            val ac = if (picked.isSuccess) acState() else null
            main.post {
                dismiss()
                if (!alive()) {
                    busy = false
                    return@post
                }
                picked.onSuccess { confirm(it, ac) }.onFailure { e ->
                    busy = false
                    show(
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.fl_not_image_title)
                            .setMessage(activity.getString(R.string.fl_not_image_body, name, sentence(e)))
                            .setPositiveButton(android.R.string.ok, null)
                    )
                }
            }
        }
    }

    /**
     * The station's own report of its inverter, `P310 AC output active`, read
     * straight from van-core's REST API. Null when van-core does not say: the
     * dialog then says that, rather than guessing.
     */
    private fun acState(): Boolean? {
        val j = getJson("/binary_sensor/P310%20AC%20output%20active") ?: return null
        (j.opt("value") as? Boolean)?.let { return it }
        return when (j.optString("state")) {
            "ON" -> true
            "OFF" -> false
            else -> null
        }
    }

    // --- 2. confirm ----------------------------------------------------------------

    private fun confirm(p: Picked, ac: Boolean?) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_flash, null)
        val img = p.image
        val about = activity.getString(
            R.string.fl_image, img.project, img.version, img.chip, img.built, img.size / 1024, p.name
        )
        // ESPHome names the IDF project after the node; every van-core build,
        // test and soak variants included, starts with it.
        val foreign = !img.project.startsWith("van-core")
        view.findViewById<TextView>(R.id.flash_image).text =
            if (foreign) about + "\n\n" + activity.getString(R.string.fl_foreign, img.project) else about
        view.findViewById<TextView>(R.id.flash_ac).apply {
            setText(
                when (ac) {
                    true -> R.string.fl_ac_on
                    false -> R.string.fl_ac_off
                    null -> R.string.fl_ac_unknown
                }
            )
            setTextColor(activity.getColor(if (ac == true) R.color.ok else R.color.warn))
        }
        val password = view.findViewById<EditText>(R.id.flash_password)
        password.setText(prefs.getString(PASSWORD, ""))

        show(
            MaterialAlertDialogBuilder(activity)
                .setTitle(if (foreign) R.string.fl_title_foreign else R.string.fl_title)
                .setView(view)
                .setPositiveButton(R.string.fl_flash) { _, _ -> upload(p, password.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> busy = false }
                // Back, or a tap outside, is "no" too.
                .setOnCancelListener { busy = false }
        )
    }

    // --- 3. upload -----------------------------------------------------------------

    private fun upload(p: Picked, password: String) {
        val net = network() ?: return failed(activity.getString(R.string.fl_no_wifi))
        val cancelled = AtomicBoolean(false)
        val d = show(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.fl_uploading)
                .setMessage(activity.getString(R.string.fl_progress, 0))
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
        )
        // A phone that sleeps mid-upload can drop the Wi-Fi under it.
        keepScreenOn(true)
        thread(name = "flash-upload", isDaemon = true) {
            var shown = -1
            val result = runCatching {
                EspOta.upload(
                    net.socketFactory, Uri.parse(baseUrl()).host ?: "192.168.4.1", EspOta.PORT, password.ifEmpty { null }, p.bytes,
                    { pct ->
                        if (pct != shown) {
                            shown = pct
                            main.post {
                                d.setMessage(activity.getString(R.string.fl_progress, pct))
                                // Once it is all sent, van-core is committing:
                                // there is nothing left to cancel.
                                if (pct >= 100) d.getButton(DialogInterface.BUTTON_NEGATIVE)?.isEnabled = false
                            }
                        }
                    },
                    { cancelled.get() },
                )
            }
            main.post {
                dismiss()
                result.onSuccess {
                    if (password.isNotEmpty()) prefs.edit().putString(PASSWORD, password).apply()
                    waitForNode(p, committed = false)
                }.onFailure { e ->
                    when {
                        (e as? EspOta.OtaException)?.committed == true -> waitForNode(p, committed = true)
                        cancelled.get() -> {
                            keepScreenOn(false)
                            busy = false
                            if (alive()) Toast.makeText(activity, R.string.fl_cancelled, Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            keepScreenOn(false)
                            failed(activity.getString(R.string.fl_failed_body, sentence(e)))
                        }
                    }
                }
            }
        }
    }

    // --- 4. wait for it to come back -------------------------------------------------

    private fun waitForNode(p: Picked, committed: Boolean) {
        val stop = AtomicBoolean(false)
        show(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.fl_restarting_title)
                .setMessage(if (committed) R.string.fl_restarting_maybe else R.string.fl_restarting_body)
                .setCancelable(false)
                .setNegativeButton(R.string.fl_stop_waiting) { _, _ -> stop.set(true) }
        )
        thread(name = "flash-wait", isDaemon = true) {
            // The old firmware is still up for a moment after the last byte;
            // an answer from it must not count as the new one.
            sleepUnless(stop, 8_000)
            val deadline = SystemClock.elapsedRealtime() + WAIT_MS
            var back = false
            var running: String? = null
            while (!stop.get() && SystemClock.elapsedRealtime() < deadline) {
                // The AP went down with the node; the activity rebinds when the
                // phone rejoins it, so ask for the network afresh every time.
                if (network() != null) {
                    val v = getJson("/text_sensor/Van%20core%20esphome%20version")
                    running = v?.optString("state")?.takeIf { it.isNotEmpty() }
                        ?: (v?.opt("value") as? String)
                    back = v != null || answers("/")
                    if (back) break
                }
                sleepUnless(stop, 3_000)
            }
            main.post {
                dismiss()
                keepScreenOn(false)
                busy = false
                if (!alive()) return@post
                when {
                    back -> {
                        onNodeBack()
                        show(
                            MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.fl_back_title)
                                .setMessage(
                                    if (running != null) activity.getString(R.string.fl_back_version, running, p.image.built)
                                    else activity.getString(R.string.fl_back_noversion, p.image.built)
                                )
                                .setPositiveButton(android.R.string.ok, null)
                        )
                    }
                    !stop.get() -> show(
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.fl_gone_title)
                            .setMessage(activity.getString(R.string.fl_gone_body, WAIT_MS / 1000 + 8))
                            .setPositiveButton(android.R.string.ok, null)
                    )
                }
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------

    private fun failed(message: String) {
        busy = false
        if (!alive()) return
        show(
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.fl_failed_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        )
    }

    private fun show(b: MaterialAlertDialogBuilder): AlertDialog {
        dismiss()
        return b.show().also { dialog = it }
    }

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun keepScreenOn(on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** One GET to van-core over the bound van Wi-Fi; its JSON, or null for any failure. */
    private fun getJson(path: String): JSONObject? {
        val conn = open(path) ?: return null
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else JSONObject(String(readCapped(conn.inputStream, 4_096), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Whether van-core's web server answers [path] at all. */
    private fun answers(path: String): Boolean {
        val conn = open(path) ?: return false
        return try {
            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    /** A connection over the van Wi-Fi itself, never the default route (D-15). */
    private fun open(path: String): HttpURLConnection? {
        val net = network() ?: return null
        return try {
            (net.openConnection(URL(baseUrl().trimEnd('/') + path)) as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 4_000
                useCaches = false
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "firmware"

    /** At most [cap] bytes, so a wrong pick cannot stream a film into memory. */
    private fun readCapped(input: InputStream, cap: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        if (out.size() >= cap) throw EspOta.OtaException("too large for an app image")
        return out.toByteArray()
    }

    private fun sleepUnless(stop: AtomicBoolean, ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!stop.get() && SystemClock.elapsedRealtime() < until) Thread.sleep(250)
    }

    /** "wrong OTA password" -> "Wrong OTA password". */
    private fun sentence(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).replaceFirstChar { it.uppercaseChar() }

    companion object {
        private const val PREFS = "firmware_flash"
        private const val PASSWORD = "ota_password"

        /** How long to wait for van-core after the upload, beyond the first 8 s. */
        private const val WAIT_MS = 150_000L
    }
}
