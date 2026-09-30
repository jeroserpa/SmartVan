"""ESPHome codegen for the unattended test-day sequencer (van_test.h).

Wiring only: the arbiter it drives, the readbacks it judges by, the fault
injection handles (BLE client, fridge probe poller) and the plug's URL.
"""

import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import binary_sensor, ble_client, esp32, sensor
from esphome.components.ac_arbiter import AcArbiter
from esphome.const import CONF_ID

CODEOWNERS = ["@jeroserpa"]
DEPENDENCIES = ["ac_arbiter", "ble_client"]

van_test_ns = cg.esphome_ns.namespace("van_test")
VanTest = van_test_ns.class_("VanTest", cg.Component)

CONF_ARBITER = "arbiter"
CONF_AC_ACTIVE = "ac_active"
CONF_BLE_CONNECTED = "ble_connected"
CONF_BLE_CLIENT = "ble_client"
CONF_FRIDGE_PROBE = "fridge_probe"
CONF_FRIDGE_POWER = "fridge_power"
CONF_INPUT_POWER = "input_power"
CONF_BATTERY_LEVEL = "battery_level"
CONF_PLUG_URL = "plug_url"
CONF_PLUG_RELAY = "plug_relay"

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(VanTest),
        cv.Required(CONF_ARBITER): cv.use_id(AcArbiter),
        # The station's own report of the inverter (reg 41 bit 11): what every
        # verdict is judged by, never the arbiter's intent.
        cv.Required(CONF_AC_ACTIVE): cv.use_id(binary_sensor.BinarySensor),
        cv.Required(CONF_BLE_CONNECTED): cv.use_id(binary_sensor.BinarySensor),
        cv.Required(CONF_BLE_CLIENT): cv.use_id(ble_client.BLEClient),
        # A polling sensor (dallas_temp): stopping its poller is the probe fault.
        cv.Required(CONF_FRIDGE_PROBE): cv.use_id(sensor.Sensor),
        cv.Required(CONF_FRIDGE_POWER): cv.use_id(sensor.Sensor),
        cv.Required(CONF_INPUT_POWER): cv.use_id(sensor.Sensor),
        cv.Required(CONF_BATTERY_LEVEL): cv.use_id(sensor.Sensor),
        cv.Required(CONF_PLUG_URL): cv.All(
            cv.string_strict,
            lambda v: v.rstrip("/")
            if v.startswith("http://")
            else cv.Invalid("http:// only, on the SoftAP"),
        ),
        cv.Optional(CONF_PLUG_RELAY, default="Fridge relay"): cv.string_strict,
    }
).extend(cv.COMPONENT_SCHEMA)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    esp32.include_builtin_idf_component("esp_http_client")

    cg.add(var.set_arbiter(await cg.get_variable(config[CONF_ARBITER])))
    cg.add(var.set_ac_active(await cg.get_variable(config[CONF_AC_ACTIVE])))
    cg.add(var.set_ble_connected(await cg.get_variable(config[CONF_BLE_CONNECTED])))
    cg.add(var.set_ble_client(await cg.get_variable(config[CONF_BLE_CLIENT])))
    probe = await cg.get_variable(config[CONF_FRIDGE_PROBE])
    cg.add(var.set_fridge_probe(probe, probe))
    cg.add(var.set_fridge_power(await cg.get_variable(config[CONF_FRIDGE_POWER])))
    cg.add(var.set_input_power(await cg.get_variable(config[CONF_INPUT_POWER])))
    cg.add(var.set_battery_level(await cg.get_variable(config[CONF_BATTERY_LEVEL])))
    cg.add(var.set_plug_base_url(config[CONF_PLUG_URL]))
    cg.add(var.set_plug_relay_name(config[CONF_PLUG_RELAY]))
