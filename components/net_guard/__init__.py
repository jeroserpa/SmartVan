"""ESPHome codegen for net_guard: van-core's sockets, kept and counted (D-29).

On 2026-10-13 van-core ran out of sockets after 49 h up (docs/measurements.md
M20): the OTA upload, the fridge plug poll, the buttons and every new page
load were refused, while BLE and the arbiter carried on. Two things let it
happen, and this component answers both:

- **A peer that vanishes is never noticed.** ESP-IDF's httpd keeps an idle
  keep-alive connection until the client closes it, and has TCP keep-alive
  off. A phone that walks out of range, or turns its Wi-Fi off, between two
  requests leaves its connections open on the node for good. Every few
  seconds this arms TCP keep-alive on each connected socket, so a dead peer
  is found and its socket freed within about `idle + interval * count`.
- **Nothing said how full the table was.** `open_sockets` counts them, for
  Diag and the log, so a climb is seen before the next flash fails.

It never closes anything itself. A connection with a live peer is that
peer's to close.
"""

import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import sensor
from esphome.const import (
    CONF_ID,
    ENTITY_CATEGORY_DIAGNOSTIC,
    STATE_CLASS_MEASUREMENT,
)

CODEOWNERS = ["@jeroserpa"]
AUTO_LOAD = ["sensor"]

CONF_KEEPALIVE_IDLE = "keepalive_idle"
CONF_KEEPALIVE_INTERVAL = "keepalive_interval"
CONF_KEEPALIVE_COUNT = "keepalive_count"
CONF_OPEN_SOCKETS = "open_sockets"

net_guard_ns = cg.esphome_ns.namespace("net_guard")
NetGuard = net_guard_ns.class_("NetGuard", cg.PollingComponent)

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(NetGuard),
        # Idle long enough that a live but quiet page is never probed for
        # nothing; short enough that a dead one costs a minute or two, not
        # a day.
        cv.Optional(CONF_KEEPALIVE_IDLE, default="60s"): cv.positive_time_period_seconds,
        cv.Optional(CONF_KEEPALIVE_INTERVAL, default="10s"): cv.positive_time_period_seconds,
        cv.Optional(CONF_KEEPALIVE_COUNT, default=3): cv.int_range(min=1, max=20),
        cv.Optional(CONF_OPEN_SOCKETS): sensor.sensor_schema(
            accuracy_decimals=0,
            state_class=STATE_CLASS_MEASUREMENT,
            entity_category=ENTITY_CATEGORY_DIAGNOSTIC,
        ),
    }
).extend(cv.polling_component_schema("5s"))


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    cg.add(
        var.set_keepalive(
            config[CONF_KEEPALIVE_IDLE].total_seconds,
            config[CONF_KEEPALIVE_INTERVAL].total_seconds,
            config[CONF_KEEPALIVE_COUNT],
        )
    )
    if CONF_OPEN_SOCKETS in config:
        s = await sensor.new_sensor(config[CONF_OPEN_SOCKETS])
        cg.add(var.set_open_sockets_sensor(s))
