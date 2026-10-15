import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import switch
from .. import CONF_FBOT_ID, Fbot, fbot_ns

DEPENDENCIES = ["fbot"]

FbotSwitch = fbot_ns.class_("FbotSwitch", switch.Switch, cg.Component)

# key -> (SwitchIdx in fbot.h, icon). As ESP-FBot.
SWITCHES = {
    "ac": (0, "mdi:power-plug"),
    "dc": (1, "mdi:power-socket-de"),
    "usb": (2, "mdi:usb-port"),
    "ac_silent": (3, "mdi:volume-off"),
}

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(CONF_FBOT_ID): cv.use_id(Fbot),
        **{
            cv.Optional(key): switch.switch_schema(FbotSwitch, icon=icon)
            for key, (_, icon) in SWITCHES.items()
        },
    }
)


async def to_code(config):
    parent = await cg.get_variable(config[CONF_FBOT_ID])
    for key, (idx, _) in SWITCHES.items():
        if key in config:
            var = await switch.new_switch(config[key])
            await cg.register_component(var, config[key])
            cg.add(var.set_parent(parent))
            cg.add(var.set_index(idx))
            cg.add(parent.set_switch(idx, var))
