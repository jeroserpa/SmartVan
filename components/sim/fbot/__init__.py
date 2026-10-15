"""fbot - a SIMULATED P310, for nodes/van-core-home.yaml only.

Same component name, same YAML keys and the same switch/number behaviour as
ESP-FBot (github.com/ylianst/esp-fbot, the source van-core.yaml pins), so
van-core.yaml runs unchanged on top of it. Nothing here speaks BLE: the
station, the fridge plugged into it, the solar input and a cooking load are a
small model in fbot.cpp. See the header of van-core-home.yaml for why this
exists and why it must never be flashed to the board in the van.

Only the keys van-core.yaml uses are accepted. A new key there fails
validation here, loudly, rather than silently simulating nothing.
"""

import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.const import CONF_ID

AUTO_LOAD = ["sensor", "binary_sensor", "switch", "number"]
MULTI_CONF = True

CONF_FBOT_ID = "fbot_id"
CONF_POLLING_INTERVAL = "polling_interval"
CONF_SETTINGS_POLLING_INTERVAL = "settings_polling_interval"
CONF_POLL_TIMEOUT = "poll_timeout"
CONF_MAX_POLL_FAILURES = "max_poll_failures"

fbot_ns = cg.esphome_ns.namespace("fbot")
Fbot = fbot_ns.class_("Fbot", cg.Component)

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(Fbot),
        cv.Optional(CONF_POLLING_INTERVAL, default="2s"): cv.positive_time_period_milliseconds,
        cv.Optional(CONF_SETTINGS_POLLING_INTERVAL, default="60s"): cv.positive_time_period_milliseconds,
        # Accepted so van-core.yaml validates; there is no link to time out.
        cv.Optional(CONF_POLL_TIMEOUT, default="15s"): cv.positive_time_period_milliseconds,
        cv.Optional(CONF_MAX_POLL_FAILURES, default=3): cv.int_range(min=1, max=10),
    }
).extend(cv.COMPONENT_SCHEMA)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    cg.add(var.set_polling_interval(config[CONF_POLLING_INTERVAL]))
    cg.add(var.set_settings_polling_interval(config[CONF_SETTINGS_POLLING_INTERVAL]))
