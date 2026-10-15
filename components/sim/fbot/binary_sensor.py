import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import binary_sensor
from esphome.const import DEVICE_CLASS_CONNECTIVITY, DEVICE_CLASS_PLUG
from . import CONF_FBOT_ID, Fbot

DEPENDENCIES = ["fbot"]

# key -> (BinaryIdx in fbot.h, device class). As ESP-FBot.
BINARY_SENSORS = {
    "connected": (0, DEVICE_CLASS_CONNECTIVITY),
    "ac_active": (1, DEVICE_CLASS_PLUG),
    "dc_active": (2, DEVICE_CLASS_PLUG),
    "usb_active": (3, DEVICE_CLASS_PLUG),
}

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(CONF_FBOT_ID): cv.use_id(Fbot),
        **{
            cv.Optional(key): binary_sensor.binary_sensor_schema(device_class=dc)
            for key, (_, dc) in BINARY_SENSORS.items()
        },
    }
)


async def to_code(config):
    parent = await cg.get_variable(config[CONF_FBOT_ID])
    for key, (idx, _) in BINARY_SENSORS.items():
        if key in config:
            sens = await binary_sensor.new_binary_sensor(config[key])
            cg.add(parent.set_binary_sensor(idx, sens))
