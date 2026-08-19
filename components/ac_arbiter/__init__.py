"""ESPHome codegen for the AC inverter arbiter.

Wiring only. The schema takes references to sensors that already exist (the
ESP-FBot BLE entities and the DS18B20) plus the one switch the arbiter is
allowed to write. Tunables are not config keys on purpose: every threshold is a
`number` entity in YAML so it can be changed from a phone in a car park
(CLAUDE.md section 11).
"""

import esphome.codegen as cg
import esphome.config_validation as cv
from esphome import core
from esphome.components import binary_sensor, sensor, switch
from esphome.const import CONF_ID

CODEOWNERS = ["@jeroserpa"]
DEPENDENCIES = []
AUTO_LOAD = ["sensor", "binary_sensor", "switch"]

ac_arbiter_ns = cg.esphome_ns.namespace("ac_arbiter")
AcArbiter = ac_arbiter_ns.class_("AcArbiter", cg.PollingComponent)

CONF_FRIDGE_TEMPERATURE = "fridge_temperature"
CONF_OUTPUT_POWER = "output_power"
CONF_INPUT_POWER = "input_power"
CONF_BATTERY_LEVEL = "battery_level"
CONF_BLE_CONNECTED = "ble_connected"
CONF_AC_SWITCH = "ac_switch"
CONF_SENSOR_MAX_AGE = "sensor_max_age"

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(AcArbiter),
        # Required: without these the arbiter has no way to know it is safe to
        # turn anything off, and would sit in force_on forever.
        cv.Required(CONF_FRIDGE_TEMPERATURE): cv.use_id(sensor.Sensor),
        cv.Required(CONF_OUTPUT_POWER): cv.use_id(sensor.Sensor),
        cv.Required(CONF_BLE_CONNECTED): cv.use_id(binary_sensor.BinarySensor),
        cv.Required(CONF_AC_SWITCH): cv.use_id(switch.Switch),
        # Optional: only surplus_req needs these.
        cv.Optional(CONF_INPUT_POWER): cv.use_id(sensor.Sensor),
        cv.Optional(CONF_BATTERY_LEVEL): cv.use_id(sensor.Sensor),
        # How old a reading may be before it counts as missing. Must comfortably
        # exceed the slowest feeding sensor's update_interval.
        cv.Optional(CONF_SENSOR_MAX_AGE, default="30s"): cv.positive_time_period_milliseconds,
    }
).extend(cv.polling_component_schema("5s"))


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)

    cg.add(var.set_fridge_temperature(await cg.get_variable(config[CONF_FRIDGE_TEMPERATURE])))
    cg.add(var.set_output_power(await cg.get_variable(config[CONF_OUTPUT_POWER])))
    cg.add(var.set_ble_connected(await cg.get_variable(config[CONF_BLE_CONNECTED])))
    cg.add(var.set_ac_switch(await cg.get_variable(config[CONF_AC_SWITCH])))

    if CONF_INPUT_POWER in config:
        cg.add(var.set_input_power(await cg.get_variable(config[CONF_INPUT_POWER])))
    if CONF_BATTERY_LEVEL in config:
        cg.add(var.set_battery_level(await cg.get_variable(config[CONF_BATTERY_LEVEL])))

    cg.add(var.set_sensor_max_age(config[CONF_SENSOR_MAX_AGE]))
