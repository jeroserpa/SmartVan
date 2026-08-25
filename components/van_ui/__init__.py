"""ESPHome codegen for the custom van-core web UI.

Registers one handler on the web server ESPHome already runs and serves the
contents of `ui/`, packed into flash by `tools/pack_ui.py`: the app itself, its
home-screen manifest and icon, and the landing page the phone's connectivity
probe gets (see van_ui.h).

The one non-obvious thing this file does is refuse to build when the generated
header is stale. A firmware that flashes cleanly but ships last week's UI is a
failure that only shows up as "did that change not work?", which is exactly the
kind of thing you do not want to be debugging in a car park.
"""

import hashlib
import re
from pathlib import Path

import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import web_server_base
from esphome.const import CONF_ID

CODEOWNERS = ["@jeroserpa"]
DEPENDENCIES = ["web_server_base"]
AUTO_LOAD = ["web_server_base"]

van_ui_ns = cg.esphome_ns.namespace("van_ui")
VanUi = van_ui_ns.class_("VanUi", cg.Component)

CONF_AT_ROOT = "at_root"
CONF_CAPTIVE_LANDING = "captive_landing"
CONF_PATH = "path"

_HERE = Path(__file__).resolve().parent
_UI = _HERE.parent.parent / "ui"
_HDR = _HERE / "van_ui_html.h"

# Must match ASSETS in tools/pack_ui.py, in order - the staleness hash below is
# taken over the sources in exactly this sequence.
_SOURCES = ["index.html", "portal.html", "manifest.webmanifest", "icon.png"]


def _check_generated(config):
    """Fail the build if van_ui_html.h does not match the sources in ui/."""
    if not _HDR.is_file():
        raise cv.Invalid(
            f"{_HDR.name} is missing. Run:  python tools/pack_ui.py"
        )
    paths = [_UI / n for n in _SOURCES]
    if not all(p.is_file() for p in paths):
        # Someone is building from a copy of components/ without ui/. That is
        # legitimate - the header is committed - so accept it rather than
        # blocking a flash.
        return config
    h = hashlib.sha256()
    for name, path in zip(_SOURCES, paths):
        h.update(name.encode())
        h.update(path.read_bytes())
    want = h.hexdigest()
    m = re.search(r'#define VAN_UI_SHA256 "([0-9a-f]{64})"',
                  _HDR.read_text(encoding="utf-8"))
    if m is None:
        raise cv.Invalid(
            f"{_HDR.name} has no hash marker; regenerate it: python tools/pack_ui.py"
        )
    if m.group(1) != want:
        raise cv.Invalid(
            "ui/ has changed since van_ui_html.h was generated. "
            "Run:  python tools/pack_ui.py"
        )
    return config


CONFIG_SCHEMA = cv.All(
    cv.Schema(
        {
            cv.GenerateID(): cv.declare_id(VanUi),
            cv.GenerateID(web_server_base.CONF_WEB_SERVER_BASE_ID): cv.use_id(
                web_server_base.WebServerBase
            ),
            # Default /ui, so the stock ESPHome UI stays reachable at /. That
            # fallback shows every entity when this page's assumptions turn out
            # to be wrong, which is worth more than a tidy URL.
            cv.Optional(CONF_PATH, default="/ui"): cv.All(
                cv.string_strict,
                cv.Length(min=2),
                lambda v: v if v.startswith("/") else cv.Invalid("must start with /"),
            ),
            # `VERIFY on hardware`: with at_root, this handler registers before
            # web_server and takes "/". captive_portal also wants "/" in AP
            # mode, and on this node the SoftAP is the only mode there is, so
            # settle who wins during the soak - not in the van.
            cv.Optional(CONF_AT_ROOT, default=False): cv.boolean,
            # Answer the OS connectivity probes with the landing page, so the
            # phone reports "no internet", keeps mobile data as its default
            # route, and offers a one-tap notification into this UI. Setting
            # this false hands those URLs back to ESPHome's captive_portal.
            # See van_ui.h and docs/decisions.md.
            cv.Optional(CONF_CAPTIVE_LANDING, default=True): cv.boolean,
        }
    ).extend(cv.COMPONENT_SCHEMA),
    _check_generated,
)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    base = await cg.get_variable(config[web_server_base.CONF_WEB_SERVER_BASE_ID])
    cg.add(var.set_base(base))
    cg.add(var.set_path("/" if config[CONF_AT_ROOT] else config[CONF_PATH]))
    cg.add(var.set_captive_landing(config[CONF_CAPTIVE_LANDING]))
