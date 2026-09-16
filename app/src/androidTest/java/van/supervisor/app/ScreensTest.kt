package van.supervisor.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream

/**
 * Renders every app and widget state to PNG for review, on an emulator in CI
 * (.github/workflows/android-app.yml, job `screens`). The workflow pulls
 * /data/local/tmp/shots and uploads it.
 *
 * The widget cases go through RemoteViews.apply(), i.e. the same path a
 * launcher takes: an action a layout cannot take fails here, instead of as
 * "Can't load widget" on a phone.
 *
 * A mock van-core (tools/mock_core.py) runs on the CI host, which the emulator
 * reaches as 10.0.2.2.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ScreensTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val mock = "http://10.0.2.2:8080/"

    @After
    fun reset() {
        MainActivity.baseUrlOverride = null
        VanFeed.eventsUrlOverride = null
        shell("svc wifi enable")
    }

    // --- app ---------------------------------------------------------------

    @Test
    fun a1_appWithLivePage() {
        MainActivity.baseUrlOverride = mock
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(10_000)
            save("app-1-page", screen())
        }
    }

    @Test
    fun a2_appWhenNodeDoesNotAnswer() {
        // Nothing listens on 8081 on the host: connection refused. (Not a low
        // port: Chromium refuses those itself with ERR_UNSAFE_PORT.)
        MainActivity.baseUrlOverride = "http://10.0.2.2:8081/"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(6_000)
            save("app-2-unreachable", screen())
            scenario.onActivity { it.findViewById<View>(R.id.btn_details).performClick() }
            Thread.sleep(800)
            save("app-3-unreachable-details", screen())
        }
    }

    @Test
    fun a3_appWithoutWifi() {
        shell("svc wifi disable")
        Thread.sleep(3_000)
        MainActivity.baseUrlOverride = mock
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1_000)
            save("app-4-searching", screen())
            Thread.sleep(12_000)   // requestNetwork times out after 10 s
            save("app-5-no-wifi", screen())
            // Past the 5 s auto-retry: the panel must not have fallen back to
            // "searching" while the retry runs.
            Thread.sleep(6_000)
            save("app-6-no-wifi-while-retrying", screen())
        }
        shell("svc wifi enable")
        Thread.sleep(8_000)
    }

    // --- widget ------------------------------------------------------------

    @Test
    fun b1_widgetFeedReadsMock() {
        VanFeed.eventsUrlOverride = mock + "events"
        val states = VanFeed.fetch(ctx)
        assertNotNull("no snapshot from the mock van-core", states)
        assertNotNull("battery not recognised in the mock's stream", states!![VanFeed.SOC])
        VanStore.saveOk(ctx, states)
        val now = System.currentTimeMillis()
        renderWidget("widget-0-from-mock", VanStore.load(ctx), now)
    }

    @Test
    fun b2_widgetStates() {
        val now = System.currentTimeMillis()
        val min = 60_000L
        val live = states(soc = 78.4, out = 48.0, inp = 310.0, fridge = 4.6,
                          ble = true, ac = true, parked = false, reason = "fridge block")
        val cases = listOf(
            "widget-1-live" to snap(live, okAt = now - 2 * min),
            "widget-2-low-battery" to snap(
                states(soc = 13.0, out = 0.0, inp = 0.0, fridge = 6.8,
                       ble = true, ac = false, parked = false, reason = "rest block"),
                okAt = now - 5 * min),
            "widget-3-warn-battery" to snap(
                states(soc = 24.0, out = 1450.0, inp = 0.0, fridge = 5.1,
                       ble = true, ac = true, parked = false, reason = "manual (cooking)"),
                okAt = now - min),
            "widget-4-stale" to snap(live, okAt = now - 190 * min, lastOk = false),
            "widget-5-out-of-range-recent" to snap(live, okAt = now - 8 * min, lastOk = false),
            "widget-6-parked" to snap(
                states(soc = 64.0, out = 0.0, inp = 120.0, fridge = 19.5,
                       ble = true, ac = false, parked = true, reason = "parked"),
                okAt = now - 3 * min),
            "widget-7-p310-offline" to snap(
                states(soc = 71.0, out = null, inp = null, fridge = 4.9,
                       ble = false, ac = null, parked = false, reason = "BLE lost"),
                okAt = now - min),
            "widget-8-soak-firmware" to snap(
                JSONObject()
                    .put(VanFeed.SOC, v(50.3)).put(VanFeed.OUT, v(59.0)).put(VanFeed.IN, v(0.0))
                    .put(VanFeed.BLE, b(true)).put(VanFeed.AC_OUT, b(true)),
                okAt = now - min),
            "widget-9-refreshing" to snap(live, okAt = now - 2 * min, refreshing = true),
            "widget-a-never-reached" to VanStore.Snapshot(JSONObject(), 0L, false, true, false),
            "widget-b-first-run" to VanStore.Snapshot(JSONObject(), 0L, false, false, false),
            "widget-c-unrecognised" to snap(
                JSONObject().put("sensor-something_else", v(1.0)), okAt = now - min),
        )
        for ((name, s) in cases) renderWidget(name, s, now)

        // The picker preview, inflated the ordinary way.
        var preview: Bitmap? = null
        instr.runOnMainSync {
            val host = FrameLayout(ctx)
            val view = LayoutInflater.from(ctx).inflate(R.layout.van_widget_preview, host, false)
            preview = draw(view, 280, 180)
        }
        save("widget-z-picker-preview", preview!!)
    }

    // --- helpers -----------------------------------------------------------

    /**
     * A cold CI emulator's own launcher tends to raise an ANR dialog over
     * whatever is on screen; close system dialogs before capturing.
     */
    private fun screen(): Bitmap {
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        Thread.sleep(400)
        return instr.uiAutomation.takeScreenshot()
    }

    private fun renderWidget(name: String, s: VanStore.Snapshot, now: Long) {
        val shots = ArrayList<Pair<String, Bitmap>>()
        instr.runOnMainSync {
            for ((suffix, full, w, h) in listOf(
                Quad("full", true, 280, 180),
                Quad("full-wide", true, 360, 180),
                Quad("compact", false, 200, 72),
            )) {
                val views = VanWidget.viewsFor(ctx, s, now, full)
                val view = views.apply(ctx, FrameLayout(ctx))
                shots += "$name-$suffix" to draw(view, w, h)
            }
        }
        for ((n, bmp) in shots) save(n, bmp)
    }

    private data class Quad(val suffix: String, val full: Boolean, val w: Int, val h: Int)

    /** Lays the view out at a widget-cell size (dp) on a wallpaper-ish ground. */
    private fun draw(view: View, wDp: Int, hDp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        val pad = (16 * d).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(0x3A, 0x4E, 0x5C))
        canvas.translate(pad.toFloat(), pad.toFloat())
        view.draw(canvas)
        return bmp
    }

    private fun v(x: Double?) = JSONObject().put("value", x ?: JSONObject.NULL)
        .put("state", x?.toString() ?: "NA")

    private fun b(x: Boolean?) = JSONObject().put("value", x ?: JSONObject.NULL)
        .put("state", when (x) { true -> "ON"; false -> "OFF"; null -> "NA" })

    private fun states(
        soc: Double?, out: Double?, inp: Double?, fridge: Double?,
        ble: Boolean?, ac: Boolean?, parked: Boolean?, reason: String?,
    ): JSONObject = JSONObject()
        .put(VanFeed.SOC, v(soc))
        .put(VanFeed.OUT, v(out))
        .put(VanFeed.IN, v(inp))
        .put(VanFeed.FRIDGE, v(fridge))
        .put(VanFeed.BLE, b(ble))
        .put(VanFeed.AC_OUT, b(ac))
        .put(VanFeed.PARKED, b(parked))
        .put(VanFeed.REASON, JSONObject().put("value", reason).put("state", reason))

    private fun snap(
        states: JSONObject, okAt: Long, lastOk: Boolean = true, refreshing: Boolean = false,
    ) = VanStore.Snapshot(states, okAt, lastOk, true, refreshing)

    /**
     * Writes through a shell process so the files outlive the app and land
     * where `adb pull` can reach them. UiAutomation splits the command on
     * whitespace (no quoting), hence dd rather than a redirect.
     */
    private fun save(name: String, bmp: Bitmap) {
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shell("mkdir -p $DIR")
        val (stdout, stdin) = instr.uiAutomation.executeShellCommandRw("dd of=$DIR/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png.toByteArray()) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    private fun shell(cmd: String) {
        val out = instr.uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(out).use { it.readBytes() }
    }

    companion object {
        private const val DIR = "/data/local/tmp/shots"
    }
}
