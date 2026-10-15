import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import number
from esphome.const import CONF_MAX_VALUE, CONF_MIN_VALUE, CONF_STEP, UNIT_PERCENT
from .. import CONF_FBOT_ID, Fbot, fbot_ns

DEPENDENCIES = ["fbot"]

FbotNumber = fbot_ns.class_("FbotNumber", number.Number, cg.Component)

# key -> (NumberIdx in fbot.h, icon, default min, default max). As ESP-FBot.
NUMBERS = {
    "threshold_charge": (0, "mdi:battery-charging-100", 60, 100),
    "threshold_discharge": (1, "mdi:battery-outline", 0, 50),
}

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(CONF_FBOT_ID): cv.use_id(Fbot),
        **{
            cv.Optional(key): number.number_schema(
                FbotNumber, icon=icon, unit_of_measurement=UNIT_PERCENT
            ).extend(
                {
                    cv.Optional(CONF_MIN_VALUE, default=lo): cv.float_,
                    cv.Optional(CONF_MAX_VALUE, default=hi): cv.float_,
                    cv.Optional(CONF_STEP, default=1): cv.float_,
                }
            )
            for key, (_, icon, lo, hi) in NUMBERS.items()
        },
    }
)


async def to_code(config):
    parent = await cg.get_variable(config[CONF_FBOT_ID])
    for key, (idx, _, _, _) in NUMBERS.items():
        if key in config:
            conf = config[key]
            var = await number.new_number(
                conf,
                min_value=conf[CONF_MIN_VALUE],
                max_value=conf[CONF_MAX_VALUE],
                step=conf[CONF_STEP],
            )
            await cg.register_component(var, conf)
            cg.add(var.set_parent(parent))
            cg.add(var.set_index(idx))
            cg.add(parent.set_number(idx, var))
