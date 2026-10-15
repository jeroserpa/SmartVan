import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import sensor
from esphome.const import (
    CONF_BATTERY_LEVEL,
    DEVICE_CLASS_BATTERY,
    DEVICE_CLASS_POWER,
    DEVICE_CLASS_VOLTAGE,
    STATE_CLASS_MEASUREMENT,
    UNIT_MINUTE,
    UNIT_PERCENT,
    UNIT_VOLT,
    UNIT_WATT,
)
from . import CONF_FBOT_ID, Fbot

DEPENDENCIES = ["fbot"]


def _watts():
    return sensor.sensor_schema(
        unit_of_measurement=UNIT_WATT,
        accuracy_decimals=0,
        device_class=DEVICE_CLASS_POWER,
        state_class=STATE_CLASS_MEASUREMENT,
    )


# key -> (SensorIdx in fbot.h, schema). Units and decimals as ESP-FBot.
SENSORS = {
    CONF_BATTERY_LEVEL: (
        0,
        sensor.sensor_schema(
            unit_of_measurement=UNIT_PERCENT,
            accuracy_decimals=1,
            device_class=DEVICE_CLASS_BATTERY,
            state_class=STATE_CLASS_MEASUREMENT,
        ),
    ),
    "input_power": (1, _watts()),
    "output_power": (2, _watts()),
    "system_power": (3, _watts()),
    "ac_input_power": (4, _watts()),
    "dc_input_power": (5, _watts()),
    "total_power": (6, _watts()),
    "remaining_time": (
        7,
        sensor.sensor_schema(unit_of_measurement=UNIT_MINUTE, accuracy_decimals=0),
    ),
    "ac_out_voltage": (
        8,
        sensor.sensor_schema(
            unit_of_measurement=UNIT_VOLT,
            accuracy_decimals=2,
            device_class=DEVICE_CLASS_VOLTAGE,
        ),
    ),
    "charge_level": (9, _watts()),
}

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(CONF_FBOT_ID): cv.use_id(Fbot),
        **{cv.Optional(key): schema for key, (_, schema) in SENSORS.items()},
    }
)


async def to_code(config):
    parent = await cg.get_variable(config[CONF_FBOT_ID])
    for key, (idx, _) in SENSORS.items():
        if key in config:
            sens = await sensor.new_sensor(config[key])
            cg.add(parent.set_sensor(idx, sens))
