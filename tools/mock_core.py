#!/usr/bin/env python3
"""mock_core.py - a fake van-core, so the UI can be built with no hardware.

Speaks the same surface `ui/index.html` speaks to the real node:

    GET  /                         ui/index.html
    GET  /events                   SSE stream of `state` events
    POST /switch/<name>/turn_on|turn_off|toggle
    POST /number/<name>/set?value=X
    POST /select/<name>/set?option=X
    POST /button/<name>/press

The entity ids, URLs and JSON shape follow ESPHome 2026.8.0's web_server, the
version the nodes are built with (docs/measurements.md M16): ids are
"<domain>/<entity name>", URLs match the URL-decoded name and nothing else,
and a number's `value` is a string. `--legacy-ids` switches to the older
"<domain>-<object_id>" form for both, to exercise the page's fallback.

What it is NOT is a model of the arbiter: the plant simulation below is
deliberately crude, just lively enough that every widget moves. The real
control logic is host-tested in test/.

Fault injection, because the states worth designing for are the broken ones:

    python tools/mock_core.py --fault ble      BLE link down -> force_on
    python tools/mock_core.py --fault probe    fridge probe stale
    python tools/mock_core.py --fault flat     SOC at 12%
    python tools/mock_core.py --fault night    sleep mode on, no sun

Stdlib only. No pip, no build step.
"""

import argparse
import json
import math
import queue
import random
import re
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, unquote_plus

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "ui"
INDEX = UI / "index.html"

# The home-screen and captive-portal assets, at the same URLs components/van_ui
# serves them from on the real node. Kept here so the manifest, the icon and the
# landing page can be checked in a desktop browser rather than on hardware.
STATIC = {
    "/van-ui/manifest.webmanifest": (UI / "manifest.webmanifest",
                                     "application/manifest+json"),
    "/van-ui/icon.png": (UI / "icon.png", "image/png"),
}
# What the OS connectivity probes get. See components/van_ui/van_ui.h - the
# point is that this is NOT the 204 the phone wants.
PROBE_PATHS = (
    "/generate_204", "/gen_204", "/hotspot-detect.html",
    "/library/test/success.html", "/connecttest.txt", "/ncsi.txt",
    "/canonical.html", "/success.txt", "/van-ui/portal",
)

# --------------------------------------------------------------------------
# Entity table. id -> dict. Mirrors nodes/van-core.yaml; if a name changes
# there, change it here too and the UI's ENTITIES block stays the arbiter of
# truth for both. The keys below are internal handles only, not necessarily
# ESPHome's object ids; what goes on the wire is derived from the NAME
# (see _wire_id).
# --------------------------------------------------------------------------
STATE = {}
LOCK = threading.RLock()
SUBS = []  # list of queue.Queue, one per connected browser
LEGACY_IDS = False  # --legacy-ids: speak the pre-2026.8 id and URL form


def define(eid, name, value, unit="", options=None):
    STATE[eid] = {"id": eid, "domain": eid.split("-", 1)[0], "name": name,
                  "value": value, "unit": unit}
    if options is not None:
        STATE[eid]["options"] = options


def _init_entities():
    define("sensor-battery", "Battery", 78.0, "%")
    define("sensor-input_power", "Input power", 0.0, "W")
    define("sensor-output_power", "Output power", 27.0, "W")
    define("sensor-system_power", "System power", 22.0, "")
    define("sensor-ac_input_power", "AC input power", 0.0, "W")
    define("sensor-solar_input_power", "Solar input power", 0.0, "W")
    define("sensor-remaining_minutes", "Remaining minutes", 540.0, "min")
    define("sensor-ac_output_voltage", "AC output voltage", 230.0, "V")
    define("sensor-fridge_temperature", "Fridge temperature", 6.4, "°C")
    define("sensor-fridge_temperature_raw", "Fridge temperature (raw)", 6.4, "°C")
    define("sensor-cabin_temperature", "Cabin temperature", 24.0, "°C")
    define("sensor-manual_ac_remaining", "Manual AC remaining", 0.0, "min")
    define("sensor-van_core_uptime", "Van core uptime", 0.0, "s")
    define("sensor-van_core_board_temperature", "Van core board temperature", 41.0, "°C")

    define("binary_sensor-p310_connected", "P310 connected", True)
    define("binary_sensor-p310_ac_output_active", "P310 AC output active", True)
    define("binary_sensor-p310_dc_output_active", "P310 DC output active", True)
    define("binary_sensor-manual_ac_button", "Manual AC button", False)
    define("binary_sensor-ac_on", "AC on", True)
    define("binary_sensor-force_on_fail_safe", "Force on (fail-safe)", True)
    define("binary_sensor-fridge_request", "Fridge request", False)
    define("binary_sensor-manual_request", "Manual request", False)
    define("binary_sensor-surplus_request", "Surplus request", False)

    define("text_sensor-ac_reason", "AC reason", "boot")
    define("text_sensor-van_core_esphome_version", "Van core esphome version",
           "2025.7.0 (mock)" if LEGACY_IDS else "2026.8.0 (mock)")

    define("switch-p310_ac_inverter", "P310 AC inverter", True)
    define("switch-p310_dc_output", "P310 DC output", True)
    define("switch-p310_usb_output", "P310 USB output", True)
    define("switch-p310_silent_charging", "P310 silent charging", False)
    define("switch-sleep_mode", "Sleep mode", False)
    define("switch-drive_inhibit_test", "Drive inhibit (test)", False)
    # Parked mode: switch state is read back from the arbiter, so an armed but
    # unconfirmed request still shows off (CLAUDE.md section 6, D-14).
    define("switch-parked_mode", "Parked mode", False)
    define("binary_sensor-parked", "Parked", False)
    define("sensor-parked_for", "Parked for", 0.0, "d")

    define("sensor-ac_input_level", "AC input level", 800.0, "W")

    for eid, name, val, unit in [
        ("number-charge_max", "Charge max", 80, "%"),
        ("number-discharge_min", "Discharge min", 10, "%"),
        ("number-fridge_on_above", "Fridge on above", 7.0, "°C"),
        ("number-fridge_off_below", "Fridge off below", 4.0, "°C"),
        ("number-fridge_hard_override", "Fridge hard override", 10.0, "°C"),
        ("number-sleep_ceiling", "Sleep ceiling", 6.0, "°C"),
        ("number-sleep_coast_target", "Sleep coast target", 1.0, "°C"),
        ("number-minimum_inverter_on_time", "Minimum inverter on time", 10, "min"),
        ("number-minimum_inverter_off_time", "Minimum inverter off time", 5, "min"),
        ("number-compressor_idle_threshold", "Compressor idle threshold", 15, "W"),
        ("number-compressor_idle_debounce", "Compressor idle debounce", 90, "s"),
        ("number-manual_ac_duration", "Manual AC duration", 45, "min"),
        ("number-manual_ac_release_power", "Manual AC release power", 150, "W"),
        ("number-surplus_margin", "Surplus margin", 200, "W"),
        ("number-surplus_minimum_soc", "Surplus minimum SOC", 85, "%"),
        ("number-parked_charge_max", "Parked charge max", 80, "%"),
    ]:
        define(eid, name, val, unit)

    define("button-manual_ac_start", "Manual AC start", None)
    define("button-manual_ac_extend", "Manual AC extend", None)
    define("button-manual_ac_cancel", "Manual AC cancel", None)
    define("button-van_core_restart", "Van core restart", None)


def get(eid):
    return STATE[eid]["value"]


def put(eid, value):
    """Set a value and push it to every connected browser.

    Emitted even when unchanged, because that is what the real node does:
    web_server hangs off add_on_state_callback, which fires on every
    publish_state, not only on a change. The UI's wall-clock staleness check
    depends on that, so the mock must not be quieter than the hardware.
    """
    with LOCK:
        e = STATE.get(eid)
        if e is None:
            return
        e["value"] = value
        payload = _event(e)
    for q in list(SUBS):
        try:
            q.put_nowait(payload)
        except queue.Full:
            pass


def _object_id(name):
    """ESPHome's pre-2026.8 object id: snake case, then anything outside
    [a-z0-9_-] -> '_'. Derived, not hand-written: the hand-written keys above
    got "Force on (fail-safe)" wrong, and so did the page they were copied to."""
    return re.sub(r"[^a-z0-9_-]", "_", name.lower())


def _wire_id(e):
    """The id the node sends: web_server.cpp set_json_id() in 2026.8."""
    if LEGACY_IDS:
        return f"{e['domain']}-{_object_id(e['name'])}"
    return f"{e['domain']}/{e['name']}"


def _find(domain, seg):
    """The entity a URL segment addresses, or None.

    2026.8 (UrlMatch::match_entity) compares the decoded segment to the entity
    name, exactly, and nothing else - an object id there is a 404, which is the
    failure this mock exists to reproduce.
    """
    for e in STATE.values():
        if e["domain"] != domain:
            continue
        if (_object_id(e["name"]) if LEGACY_IDS else e["name"]) == seg:
            return e
    return None


def _event(e):
    """One SSE `state` frame in ESPHome web_server v3 shape."""
    v = e["value"]
    if isinstance(v, bool):
        state = "ON" if v else "OFF"
    elif isinstance(v, float):
        state = f"{v:.2f} {e['unit']}".strip()
    elif v is None:
        state = ""
    else:
        state = f"{v} {e['unit']}".strip()
    # 2026.8 number_json_() puts the value in as a string, not a number.
    # Decimals here are Python's, not step-derived as on the node.
    if e["domain"] == "number" and not LEGACY_IDS and v is not None:
        v = str(v)
    d = {"id": _wire_id(e), "name": e["name"], "value": v, "state": state}
    if "options" in e:
        d["option"] = e["options"]
    return "event: state\ndata: " + json.dumps(d) + "\n\n"


# --------------------------------------------------------------------------
# Plant + arbiter caricature. Crude on purpose - see the module docstring.
# --------------------------------------------------------------------------
class Sim(threading.Thread):
    daemon = True

    def __init__(self, fault, fast):
        super().__init__()
        self.fault = fault
        self.fast = fast          # simulated seconds per real second
        self.t = 0.0
        self.manual_until = 0.0
        self.boot_until = 20.0
        self.park_armed_at = None   # wall clock: the window is human-timed
        self.parked_since = None    # simulated time

    def park(self, on):
        """D-14 caricature: one request arms, a second within 30s commits."""
        if not on:
            self.park_armed_at = self.parked_since = None
            self.boot_until = self.t + 20          # exit re-arms boot force-on
        elif self.parked_since is not None:
            pass
        elif self.park_armed_at is not None and time.time() - self.park_armed_at <= 30:
            self.park_armed_at = None
            self.parked_since = self.t
        else:
            self.park_armed_at = time.time()

    def press(self, which):
        if which == "start":
            base = max(self.manual_until, self.t)
            self.manual_until = base + get("number-manual_ac_duration") * 60
        elif which == "extend":
            if self.manual_until > self.t:
                self.manual_until += 30 * 60
        elif which == "cancel":
            self.manual_until = 0.0

    def run(self):
        while True:
            time.sleep(0.5)
            self.t += 0.5 * self.fast
            self.step()

    def step(self):
        t = self.t
        hour = (8.0 + t / 3600.0) % 24 if self.fault != "night" else 2.0

        ble = self.fault != "ble"
        put("binary_sensor-p310_connected", ble)

        # --- solar: a crude bell around noon ---
        solar = 0.0
        if 6 < hour < 20:
            solar = 750 * max(0.0, math.sin((hour - 6) / 14 * math.pi)) ** 1.5
            solar *= 0.85 + 0.15 * math.sin(t / 90)     # cloud
        put("sensor-solar_input_power", round(solar, 1))
        put("sensor-ac_input_power", 0.0)
        put("sensor-input_power", round(solar, 1))

        # --- fridge probe ---
        probe_ok = self.fault != "probe"
        ac_on = get("switch-p310_ac_inverter")
        temp = get("sensor-fridge_temperature")
        # The appliance keeps its own mechanical thermostat: the inverter being
        # up only ENABLES cooling, it does not force it. Without this the
        # cabinet slams into the clamp and stops moving, which is not a state
        # the real fridge has. Rates are illustrative, not the measured coast
        # rate - that is still an open measurement (CLAUDE.md section 8.5).
        floor = get("number-sleep_coast_target") if get("switch-sleep_mode") else 2.0
        cooling = ac_on and temp > floor
        temp += (-0.010 if cooling else 0.006) * self.fast
        temp += random.uniform(-0.01, 0.01)
        temp = max(-1.0, min(15.0, temp))
        if probe_ok:
            put("sensor-fridge_temperature", round(temp, 2))
            put("sensor-fridge_temperature_raw", round(temp + random.uniform(-.15, .15), 2))
        put("sensor-cabin_temperature", round(18 + 8 * math.sin((hour - 4) / 24 * math.pi), 1))

        # --- request flags ---
        sleeping = get("switch-sleep_mode")
        ceiling = get("number-sleep_ceiling") if sleeping else get("number-fridge_on_above")
        fridge_req = get("binary_sensor-fridge_request")
        if temp > ceiling:
            fridge_req = True
        elif temp < get("number-fridge_off_below"):
            fridge_req = False
        hard = temp > get("number-fridge_hard_override")

        manual = self.manual_until > t
        put("binary_sensor-manual_request", manual)
        put("sensor-manual_ac_remaining", round(max(0.0, (self.manual_until - t) / 60), 1))

        surplus = (not sleeping
                   and solar - get("sensor-output_power") > get("number-surplus_margin")
                   and get("sensor-battery") >= get("number-surplus_minimum_soc"))
        put("binary_sensor-surplus_request", surplus)
        put("binary_sensor-fridge_request", fridge_req)

        # Parked outranks everything, the fail-safe included (CLAUDE.md 6).
        parked = self.parked_since is not None
        put("switch-parked_mode", parked)
        put("binary_sensor-parked", parked)
        put("sensor-parked_for", round((t - self.parked_since) / 86400, 2) if parked else 0.0)

        force = not parked and ((t < self.boot_until) or (not ble) or (not probe_ok))
        put("binary_sensor-force_on_fail_safe", force)

        inhibit = get("switch-drive_inhibit_test")
        on = not parked and (force or hard or (
            (fridge_req or manual or surplus) and not inhibit))

        reason = ("parked" if parked else
                  "boot" if t < self.boot_until else
                  "ble lost" if not ble else
                  "temp stale" if not probe_ok else
                  "fridge hard" if hard else
                  "manual" if manual else
                  "fridge" if fridge_req else
                  "surplus" if surplus else "off")
        put("text_sensor-ac_reason", reason)
        put("binary_sensor-ac_on", on)
        put("switch-p310_ac_inverter", on)
        put("binary_sensor-p310_ac_output_active", on)

        # --- loads ---
        out = 0.0
        if on:
            out += 48.0                       # station overhead, MEASURED
            out += 27.0 if temp > 3 else 12.0  # the fridge, roughly
            if manual:
                out += 1400.0                 # an induction plate
        put("sensor-output_power", round(out, 1))

        soc = get("sensor-battery")
        if self.fault == "flat":
            soc = 12.0
        else:
            soc += (solar - out) / 3900.0 / 36.0 * self.fast   # % per simulated second
            soc = max(0.0, min(100.0, soc))
        put("sensor-battery", round(soc, 1))
        put("sensor-remaining_minutes", round(soc / 100 * 3900 / max(out, 1) * 60, 0))
        put("sensor-van_core_uptime", round(t, 0))
        put("sensor-van_core_board_temperature", round(38 + 6 * math.sin(t / 400), 1))


SIM = None

# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------
PATH_RE = re.compile(r"^/(switch|number|select|button|light|fan)/([^/]+)/([^/?]+)$")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if "--verbose" in getattr(self.server, "flags", []):
            super().log_message(fmt, *args)

    # -- GET -----------------------------------------------------------
    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html", "/ui", "/ui/"):
            return self._file(INDEX, "text/html; charset=utf-8")
        if path == "/events":
            return self._events()
        if path in STATIC:
            f, ctype = STATIC[path]
            return self._file(f, ctype)
        if path in PROBE_PATHS:
            return self._file(UI / "portal.html", "text/html; charset=utf-8")
        self.send_error(404)

    def _file(self, p, ctype):
        try:
            body = p.read_bytes()
        except OSError:
            return self.send_error(404, f"missing {p}")
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _events(self):
        q = queue.Queue(maxsize=500)
        with LOCK:
            SUBS.append(q)
            initial = "".join(_event(e) for e in STATE.values())
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        try:
            self.wfile.write(initial.encode())
            self.wfile.flush()
            # Two ways to end a stream early, because a reader can survive one
            # and not the other. The stream is infinite, so a real read of it
            # NEVER finishes cleanly - how it dies is the whole question.
            #
            #   ?cut=1  close at once: the client reads EOF. The easy case.
            #   ?rst=1  go quiet long enough for the client to time out a read,
            #           then RESET rather than close, so its next read throws.
            #           This is the one that took the widget down: the reader
            #           let that exception escape and discarded a full snapshot
            #           it had already collected, and the widget then reported
            #           that van-core had not answered. A clean EOF would not
            #           have caught it - only a reset does.
            query = parse_qs(urlparse(self.path).query)
            if query.get("cut"):
                return
            if query.get("rst"):
                time.sleep(3.0)
                self.close_connection = True
                try:
                    self.connection.setsockopt(
                        socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0)
                    )
                    self.connection.close()
                except OSError:
                    pass
                return
            while True:
                try:
                    chunk = q.get(timeout=5)
                except queue.Empty:
                    chunk = "event: ping\ndata: {}\n\n"
                self.wfile.write(chunk.encode())
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            with LOCK:
                if q in SUBS:
                    SUBS.remove(q)

    # -- POST ----------------------------------------------------------
    def do_POST(self):
        u = urlparse(self.path)
        # Decoded before splitting, as web_server_idf's url_to() does.
        m = PATH_RE.match(unquote_plus(u.path))
        if not m:
            return self.send_error(404)
        domain, seg, action = m.groups()
        args = parse_qs(u.query)
        e = _find(domain, seg)
        if e is None:
            return self.send_error(404, f"unknown entity {domain}/{seg}")
        eid = e["id"]

        if eid == "switch-parked_mode" and action in ("turn_on", "turn_off"):
            SIM.park(action == "turn_on")
        elif domain == "switch":
            if action == "turn_on":
                put(eid, True)
            elif action == "turn_off":
                put(eid, False)
            elif action == "toggle":
                put(eid, not get(eid))
        elif domain == "number" and action == "set":
            put(eid, float(args.get("value", ["0"])[0]))
        elif domain == "select" and action == "set":
            put(eid, args.get("option", [""])[0])
        elif domain == "button" and action == "press":
            if eid.endswith("manual_ac_start"):
                SIM.press("start")
            elif eid.endswith("manual_ac_extend"):
                SIM.press("extend")
            elif eid.endswith("manual_ac_cancel"):
                SIM.press("cancel")
            elif eid.endswith("restart"):
                SIM.boot_until = SIM.t + 20
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--fault", choices=["none", "ble", "probe", "flat", "night"],
                    default="none", help="inject a failure mode")
    ap.add_argument("--fast", type=float, default=60.0,
                    help="simulated seconds per real second (default 60)")
    ap.add_argument("--legacy-ids", action="store_true",
                    help="pre-2026.8 ids and URLs (<domain>-<object_id>)")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    global LEGACY_IDS
    LEGACY_IDS = a.legacy_ids
    _init_entities()
    global SIM
    SIM = Sim(a.fault, a.fast)
    if a.fault == "night":
        put("switch-sleep_mode", True)
    SIM.start()

    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    srv.flags = ["--verbose"] if a.verbose else []
    print(f"mock van-core on http://127.0.0.1:{a.port}/   fault={a.fault} fast={a.fast}x"
          f"   ids={'legacy' if LEGACY_IDS else '2026.8'}")
    print("serving", INDEX)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
