"""ESPHome codegen for soak_log: a PSRAM ring-buffer logger served over HTTP.

Stands in for the microSD log until a card is fitted. One fixed-size binary
row per interval, every column a float, rendered to CSV only when a phone asks
for it - so the buffer holds days, not hours.

The buffer lives in the `.ext_ram_noinit` PSRAM section: it survives a crash,
a watchdog reset and an OTA, and is lost on a power cut. See soak_log.h.

Also polls other nodes' REST sensors (the fridge plug) from its own FreeRTOS
task. ESPHome's `http_request` runs in the main loop and blocks for its whole
timeout when the peer is absent - on the node that also holds the BLE link,
which is exactly the stall CLAUDE.md section 4 warns about.
"""

import re

import esphome.codegen as cg
from esphome.components import binary_sensor, esp32, sensor, web_server_base
import esphome.config_validation as cv
from esphome.const import (
    CONF_ACCURACY_DECIMALS,
    CONF_BINARY_SENSOR,
    CONF_ID,
    CONF_INTERVAL,
    CONF_NAME,
    CONF_SENSOR,
    CONF_URL,
)

CODEOWNERS = ["@jeroserpa"]
DEPENDENCIES = ["web_server_base", "psram"]
AUTO_LOAD = ["web_server_base", "sensor"]

soak_log_ns = cg.esphome_ns.namespace("soak_log")
SoakLog = soak_log_ns.class_("SoakLog", cg.Component)

CONF_COLUMNS = "columns"
CONF_BUFFER_SIZE = "buffer_size"
CONF_REMOTE = "remote"
CONF_REMOTE_INTERVAL = "remote_interval"
CONF_STALE_AFTER = "stale_after"
CONF_SERVE_AT_UI = "serve_at_ui"

MAX_COLUMNS = 40
_COL_NAME = re.compile(r"^[a-z][a-z0-9_]{0,23}$")


def _col_name(value):
    value = cv.string_strict(value)
    if not _COL_NAME.match(value):
        raise cv.Invalid(
            "column names are CSV headers: lowercase, digits, underscore, max 24"
        )
    return value


def _unique_names(cols):
    seen = set()
    for c in cols:
        if c[CONF_NAME] in seen:
            raise cv.Invalid(f"duplicate column name '{c[CONF_NAME]}'")
        seen.add(c[CONF_NAME])
    return cols


COLUMN_SCHEMA = cv.All(
    cv.Schema(
        {
            cv.Required(CONF_NAME): _col_name,
            cv.Exclusive(CONF_SENSOR, "source"): cv.use_id(sensor.Sensor),
            cv.Exclusive(CONF_BINARY_SENSOR, "source"): cv.use_id(
                binary_sensor.BinarySensor
            ),
            cv.Optional(CONF_ACCURACY_DECIMALS, default=2): cv.int_range(0, 4),
        }
    ),
    cv.has_exactly_one_key(CONF_SENSOR, CONF_BINARY_SENSOR),
)

REMOTE_SCHEMA = cv.Schema(
    {
        # Plain http only. A URL on the SoftAP, never the internet (section 5.5).
        cv.Required(CONF_URL): cv.All(
            cv.string_strict,
            lambda v: v if v.startswith("http://") else cv.Invalid("http:// only"),
        ),
        cv.Required(CONF_SENSOR): sensor.sensor_schema(accuracy_decimals=2),
    }
)

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(SoakLog),
        cv.GenerateID(web_server_base.CONF_WEB_SERVER_BASE_ID): cv.use_id(
            web_server_base.WebServerBase
        ),
        cv.Optional(CONF_INTERVAL, default="10s"): cv.All(
            cv.positive_time_period_milliseconds,
            cv.Range(min=cv.TimePeriod(seconds=1)),
        ),
        # Bytes. Static, so it is carved out of PSRAM at link time and never
        # competes with the heap at runtime. 8MB part, ~8.2MB free in M12.
        cv.Optional(CONF_BUFFER_SIZE, default=4_000_000): cv.int_range(
            min=64_000, max=6_000_000
        ),
        cv.Required(CONF_COLUMNS): cv.All(
            cv.ensure_list(COLUMN_SCHEMA),
            cv.Length(min=1, max=MAX_COLUMNS),
            _unique_names,
        ),
        cv.Optional(CONF_REMOTE, default=[]): cv.ensure_list(REMOTE_SCHEMA),
        cv.Optional(CONF_REMOTE_INTERVAL, default="10s"): cv.positive_time_period_milliseconds,
        # A remote value older than this publishes NAN rather than a stale
        # number that looks live.
        cv.Optional(CONF_STALE_AFTER, default="60s"): cv.positive_time_period_milliseconds,
        # Also answer /ui, where the Android app looks. Only on builds without
        # van_ui, which owns that path.
        cv.Optional(CONF_SERVE_AT_UI, default=False): cv.boolean,
    }
).extend(cv.COMPONENT_SCHEMA)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    base = await cg.get_variable(config[web_server_base.CONF_WEB_SERVER_BASE_ID])
    cg.add(var.set_base(base))
    cg.add(var.set_row_interval(config[CONF_INTERVAL].total_milliseconds))
    cg.add(var.set_stale_after(config[CONF_STALE_AFTER].total_milliseconds))
    cg.add(var.set_remote_interval(config[CONF_REMOTE_INTERVAL].total_milliseconds))
    cg.add(var.set_serve_ui(config[CONF_SERVE_AT_UI]))

    # The buffer is a static array; its size must be a compile-time constant.
    cg.add_define("SOAK_LOG_BUFFER_BYTES", config[CONF_BUFFER_SIZE])
    # Keep the array out of .bss so a software reset does not zero it. IDF's
    # boot memtest skips this section (esp_psram.c, esp_psram_extram_test).
    esp32.add_idf_sdkconfig_option("CONFIG_SPIRAM_ALLOW_NOINIT_SEG_EXTERNAL_MEMORY", True)
    esp32.include_builtin_idf_component("esp_http_client")

    # Remotes first: columns may log them, and get_variable() on a sensor this
    # function has not created yet waits forever ("circular dependency").
    for rem in config[CONF_REMOTE]:
        s = await sensor.new_sensor(rem[CONF_SENSOR])
        cg.add(var.add_remote(rem[CONF_URL], s))

    for col in config[CONF_COLUMNS]:
        if CONF_SENSOR in col:
            src = await cg.get_variable(col[CONF_SENSOR])
            cg.add(var.add_sensor_column(col[CONF_NAME], src, col[CONF_ACCURACY_DECIMALS]))
        else:
            src = await cg.get_variable(col[CONF_BINARY_SENSOR])
            cg.add(var.add_binary_column(col[CONF_NAME], src))
