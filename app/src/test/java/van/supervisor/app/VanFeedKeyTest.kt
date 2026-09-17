package van.supervisor.app

import org.junit.Assert.assertEquals
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
            VanFeed.SOC, VanFeed.OUT, VanFeed.IN, VanFeed.FRIDGE,
            VanFeed.BLE, VanFeed.AC_OUT, VanFeed.PARKED, VanFeed.REASON,
        )
        for (k in keys) assertEquals(k, VanFeed.key(k))
    }
}
