package van.supervisor.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widget's only parsing rule that can silently blank it (2026-09-16). */
class VanFeedKeyTest {

    @Test
    fun esphome2026IdIsNormalised() {
        assertEquals("sensor-battery", VanFeed.key("sensor/Battery"))
        assertEquals("text_sensor-ac_reason", VanFeed.key("text_sensor/AC reason"))
        assertEquals(
            "binary_sensor-p310_ac_output_active",
            VanFeed.key("binary_sensor/P310 AC output active")
        )
    }

    @Test
    fun bothProbesNormalise() {
        assertEquals("sensor-fridge_temperature", VanFeed.key("sensor/Fridge temperature"))
        assertEquals("sensor-cabin_temperature", VanFeed.key("sensor/Cabin temperature"))
        // The raw probe must not collapse onto the filtered one: the arbiter
        // reads the filtered sensor and the widget has to show the same value.
        assertEquals(
            "sensor-fridge_temperature__raw_",
            VanFeed.key("sensor/Fridge temperature (raw)")
        )
    }

    /**
     * The board temperature is named "${friendly_name} board temperature" in
     * common/base.yaml, so its id differs per node. The widget matches the
     * suffix; this pins that both nodes' ids normalise to something it catches.
     */
    @Test
    fun boardTemperatureIsMatchedBySuffixOnEveryNode() {
        for (id in listOf(
            "sensor/Van core board temperature",
            "sensor/Van core soak board temperature",
            "sensor-van_core_board_temperature",
            "sensor-van_core_soak_board_temperature",
        )) {
            val key = VanFeed.key(id)
            assertTrue("$id -> $key is not a sensor", key.startsWith("sensor-"))
            assertTrue("$id -> $key misses the suffix", key.endsWith(VanFeed.BOARD_SUFFIX))
        }
        // And it must not swallow the probes, which are shown in its place.
        assertTrue(!VanFeed.key("sensor/Cabin temperature").endsWith(VanFeed.BOARD_SUFFIX))
    }

    @Test
    fun legacyIdIsUnchanged() {
        assertEquals("binary_sensor-p310_connected", VanFeed.key("binary_sensor-p310_connected"))
        assertEquals("sensor-output_power", VanFeed.key("sensor-output_power"))
    }

    @Test
    fun deviceSegmentIsDropped() {
        assertEquals("sensor-battery", VanFeed.key("sensor/Van core/Battery"))
    }

    @Test
    fun punctuationBecomesUnderscore() {
        assertEquals("binary_sensor-force_on__fail-safe_", VanFeed.key("binary_sensor/Force on (fail-safe)"))
    }

    @Test
    fun everyWidgetKeyIsAFixedPoint() {
        val keys = listOf(
            VanFeed.SOC, VanFeed.OUT, VanFeed.IN, VanFeed.FRIDGE, VanFeed.CABIN,
            VanFeed.BLE, VanFeed.AC_OUT, VanFeed.PARKED, VanFeed.REASON,
        )
        for (k in keys) assertEquals(k, VanFeed.key(k))
    }
}
