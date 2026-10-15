#!/usr/bin/env python3
"""
p310_sim.py - a pretend AFERIY P310 on this PC's Bluetooth, so the real
van-core firmware can be tested end to end at home.

The ESP runs nodes/van-core-pcsim.yaml: van-core.yaml exactly, with the P310's
Bluetooth address swapped for this PC's. ESP-FBot on the ESP then connects to
this program instead of the station and cannot tell the difference: same GATT
service, same Modbus-over-BLE frames, same register map, same reply shape
(docs/ble-registers.md). Behind it is a small model of the station, the fridge
on its AC output, solar, a cooking load and an AC charger, plus faults to
inject. Everything on the ESP side is the van build.

What it cannot do: the fridge temperature. van-core reads that from its own
probe and never from the network (CLAUDE.md section 5.1), so it stays real -
put the probe somewhere cold. The fridge model here only shapes the power
draw the station reports.

The plant numbers are PLAUSIBLE, NOT MEASURED - something that answers back,
not a model to tune thresholds against.

Usage
    pip install bleak          # brings the winrt-* Bluetooth bindings used here
    python tools/p310_sim.py                    # control page: http://127.0.0.1:8310/
    python tools/p310_sim.py --csv sim.csv      # also log the station every 10 s
    python tools/p310_sim.py selftest           # frames and model, no Bluetooth

Control: the page, or plain GETs - e.g. /set?solar=400, /set?silent=1,
/state for JSON. Bound to 127.0.0.1 only.

Faults
    silent        station stops answering; the link stays up. ESP-FBot counts
                  poll timeouts and marks it disconnected after ~45 s, and
                  van-core's link_stale fail-safe should act before that.
    ignore_writes answers polls but drops every write (the M17 lost write).
    advertising   0 = stop advertising, so a dropped ESP cannot reconnect.

Windows only (WinRT GATT server). The adapter must support the peripheral
role - checked at start-up.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# ---- protocol (docs/ble-registers.md, ESP-FBot fbot.cpp) --------------------
SERVICE_UUID = "0000a002-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000c304-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000c305-0000-1000-8000-00805f9b34fb"
SLAVE = 0x11
FN_READ_HOLDING, FN_READ_INPUT, FN_WRITE = 0x03, 0x04, 0x06
NUM_REGS = 80
REPLY_LEN = 6 + NUM_REGS * 2 + 2  # 168: header echoing the request, registers, CRC

# Write registers (0x06), as ESP-FBot sends them.
REG_USB, REG_DC, REG_AC, REG_LIGHT = 24, 25, 26, 27
REG_KNOB = 13  # the AC input knob: physical on the P310, a write is ignored (D-16)
REG_KEY_SOUND, REG_AC_SILENT = 56, 57
REG_DISCHARGE_MIN, REG_CHARGE_MAX = 66, 67
# State flags, input register 41.
BIT_USB, BIT_DC, BIT_AC, BIT_LIGHT = 1 << 9, 1 << 10, 1 << 11, 1 << 12

# ---- plant: PLAUSIBLE, NOT MEASURED (same numbers as components/sim/fbot) ---
CAPACITY_WH = 3900.0  # usable, M2
STATION_BASE_W = 8.0
INVERTER_IDLE_W = 40.0  # base + this = M2's ~48 W with AC on; real split UNKNOWN
DC_LOAD_W = 4.0  # the 12 V bus: van-core and friends
LATENCY_S = 1.5  # write -> station acts
FRIDGE_SET_C, FRIDGE_HYST_C = 3.0, 0.5
COOL_C_PER_H, LEAK_TAU_H = 4.0, 20.0
FRIDGE_RUN_W, FRIDGE_IDLE_W = 30.0, 2.0  # M6: 24-34 W running


def crc16_modbus(data: bytes) -> int:
    """CRC-16/Modbus: poly 0xA001, init 0xFFFF, reflected. As fbot_probe.py."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def with_crc(body: bytes) -> bytes:
    """Append the CRC high byte first, as the station's request path does."""
    crc = crc16_modbus(body)
    return body + bytes([(crc >> 8) & 0xFF, crc & 0xFF])


def read_reply(fn: int, regs: list[int]) -> bytes:
    """The 168-byte reply to an 80-register read: request header, data, CRC."""
    body = bytearray([SLAVE, fn, 0x00, 0x00, 0x00, NUM_REGS])
    for value in regs:
        body += int(value & 0xFFFF).to_bytes(2, "big")
    return with_crc(bytes(body))


class Station:
    """The P310 and what hangs off it. All state behind one lock."""

    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.out = {"usb": True, "dc": True, "ac": True, "light": False}
        self.light_mode = 0
        self.key_sound = 1
        self.ac_silent = 0
        self.discharge_min = 100  # permille
        self.charge_max = 1000
        self.soc = 80.0
        self.fridge_c = 5.0
        self.compressor = False
        # what the operator sets
        self.solar_w = 0.0
        self.cooking_w = 0.0
        self.charger_w = 0.0  # AC input: shore power or the alternator inverter
        self.ambient_c = 24.0
        self.time_factor = 1.0
        self.silent = False
        self.ignore_writes = False
        self.advertising = True
        # derived each step
        self.in_w = self.out_w = self.ac_out_w = self.overhead_w = 0.0
        # pending writes: (due_monotonic, reg, value)
        self.pending: list[tuple[float, int, int]] = []
        self.counters = {"polls": 0, "settings": 0, "writes": 0, "dropped": 0}
        self.last_poll = 0.0
        self.events: list[str] = []
        self.step(0.0)

    # -- events --------------------------------------------------------------
    def log(self, msg: str) -> None:
        line = f"{time.strftime('%H:%M:%S')}  {msg}"
        print(line, flush=True)
        with self.lock:
            self.events.append(line)
            del self.events[:-200]

    # -- physics -------------------------------------------------------------
    def step(self, dt_s: float) -> None:
        with self.lock:
            ac = self.out["ac"]
            dt_h = dt_s * self.time_factor / 3600.0
            if not ac:
                self.compressor = False
            elif self.fridge_c > FRIDGE_SET_C + FRIDGE_HYST_C:
                self.compressor = True
            elif self.fridge_c < FRIDGE_SET_C - FRIDGE_HYST_C:
                self.compressor = False
            self.fridge_c += ((self.ambient_c - self.fridge_c) / LEAK_TAU_H
                              - (COOL_C_PER_H if self.compressor else 0.0)) * dt_h

            fridge_w = 0.0 if not ac else FRIDGE_RUN_W if self.compressor else FRIDGE_IDLE_W
            self.ac_out_w = fridge_w + self.cooking_w if ac else 0.0
            self.out_w = self.ac_out_w + (DC_LOAD_W if self.out["dc"] else 0.0)
            self.overhead_w = STATION_BASE_W + (INVERTER_IDLE_W if ac else 0.0)

            self.in_w = self.solar_w + self.charger_w
            if self.soc >= self.charge_max / 10.0:  # at the limit: take only what is used
                self.in_w = min(self.in_w, self.out_w + self.overhead_w)
            net = self.in_w - self.out_w - self.overhead_w
            self.soc = max(0.0, min(100.0, self.soc + net * dt_h / CAPACITY_WH * 100.0))

    # -- writes --------------------------------------------------------------
    def receive_write(self, reg: int, value: int) -> None:
        with self.lock:
            self.counters["writes"] += 1
            if self.ignore_writes:
                self.counters["dropped"] += 1
                self.log(f"write reg {reg} = {value} DROPPED (ignore_writes)")
                return
            self.pending.append((time.monotonic() + LATENCY_S, reg, value))
        self.log(f"write received: {describe_write(reg, value)}")

    def apply_due(self) -> None:
        now = time.monotonic()
        with self.lock:
            due = [p for p in self.pending if p[0] <= now]
            self.pending = [p for p in self.pending if p[0] > now]
        for _, reg, value in due:
            self.apply(reg, value)

    def apply(self, reg: int, value: int) -> None:
        with self.lock:
            outputs = {REG_USB: "usb", REG_DC: "dc", REG_AC: "ac"}
            if reg in outputs:
                name = outputs[reg]
                self.out[name] = value != 0
                self.log(f"station: {name.upper()} -> {'ON' if value else 'OFF'}")
                if name == "dc" and not value:
                    self.log("!!! DC OFF: in the van this kills the 12 V bus and van-core with it")
            elif reg == REG_LIGHT:
                self.light_mode = value
                self.out["light"] = value != 0
            elif reg == REG_KEY_SOUND:
                self.key_sound = 1 if value else 0
            elif reg == REG_AC_SILENT:
                self.ac_silent = 1 if value else 0
                self.log(f"station: silent charging {'ON' if value else 'OFF'}")
            elif reg == REG_DISCHARGE_MIN:
                self.discharge_min = value
                self.log(f"station: discharge min {value / 10:.0f} %")
            elif reg == REG_CHARGE_MAX:
                self.charge_max = value
                self.log(f"station: charge max {value / 10:.0f} %")
            elif reg == REG_KNOB:
                self.log(f"write to reg 13 (AC input knob) ignored - it is a physical knob (D-16)")
            else:
                self.log(f"!!! write to UNKNOWN reg {reg} = {value}: on the real station "
                         f"this is how one gets bricked (ble-registers.md section 2)")
            self.step(0.0)  # the powers follow the outputs at once, not on the next tick

    # -- registers -----------------------------------------------------------
    def input_registers(self) -> list[int]:
        with self.lock:
            r = [0] * NUM_REGS
            ac = self.out["ac"]
            net = self.in_w - self.out_w - self.overhead_w
            r[2] = 2  # AC input knob position 2 = 800 W (D-16)
            r[3] = round(self.charger_w if self.in_w > 0 else 0)
            r[4] = round(max(0.0, self.in_w - r[3]))
            r[6] = round(self.in_w)
            r[18] = 2300 if ac else 0  # AC out voltage x0.1
            r[19] = 500 if ac else 0  # AC out frequency x0.1
            r[20] = round(self.ac_out_w)  # AC output only (M1)
            r[21] = 2285 if self.charger_w > 0 else 20  # AC INPUT voltage x0.1 (M11)
            r[22] = 5000 if self.charger_w > 0 else 0
            r[39] = round(self.out_w)  # AC + DC + USB
            flags = 0
            flags |= BIT_USB if self.out["usb"] else 0
            flags |= BIT_DC if self.out["dc"] else 0
            flags |= BIT_AC if ac else 0
            flags |= BIT_LIGHT if self.out["light"] else 0
            r[41] = flags
            r[56] = round(self.soc * 10)
            if net > 1:
                r[58] = min(5999, round(max(0.0, self.charge_max / 10 - self.soc) / 100
                                        * CAPACITY_WH / net * 60))
            r[59] = 0  # remaining time: junk on our firmware (0 or 42679), so 0
            return r

    def holding_registers(self) -> list[int]:
        with self.lock:
            r = [0] * NUM_REGS
            r[REG_KNOB] = 2
            r[18], r[22] = 115, 233  # region settings, as captured
            r[47:51] = [38, 24, 27, 36]  # the four probable temperatures, as captured
            r[REG_LIGHT] = self.light_mode
            r[REG_KEY_SOUND] = self.key_sound
            r[REG_AC_SILENT] = self.ac_silent
            r[REG_DISCHARGE_MIN] = self.discharge_min
            r[REG_CHARGE_MAX] = self.charge_max
            return r

    # -- requests from the ESP -----------------------------------------------
    def handle_frame(self, data: bytes) -> bytes | None:
        """One request in, the reply to notify (or None)."""
        if len(data) < 8 or data[0] != SLAVE:
            self.log(f"ignored frame: {data.hex()}")
            return None
        if crc16_modbus(data[:6]) != (data[6] << 8 | data[7]):
            self.log(f"bad CRC, ignored: {data.hex()}")
            return None
        fn = data[1]
        with self.lock:
            silent = self.silent
        if fn == FN_READ_INPUT:
            with self.lock:
                self.counters["polls"] += 1
                self.last_poll = time.monotonic()
            return None if silent else read_reply(FN_READ_INPUT, self.input_registers())
        if fn == FN_READ_HOLDING:
            with self.lock:
                self.counters["settings"] += 1
            return None if silent else read_reply(FN_READ_HOLDING, self.holding_registers())
        if fn == FN_WRITE:
            self.receive_write(data[2] << 8 | data[3], data[4] << 8 | data[5])
            # Modbus echoes a single-register write. ESP-FBot ignores it, but any
            # reply counts as "station alive" to it, so a silent station echoes nothing.
            return None if silent else bytes(data[:8])
        self.log(f"function 0x{fn:02x} not simulated: {data.hex()}")
        return None

    # -- operator ------------------------------------------------------------
    SETTABLE = {
        "solar": ("solar_w", float), "cooking": ("cooking_w", float),
        "charger": ("charger_w", float), "ambient": ("ambient_c", float),
        "soc": ("soc", float), "time_factor": ("time_factor", float),
        "fridge": ("fridge_c", float),
        "silent": ("silent", lambda v: v not in ("0", "false", "off", "")),
        "ignore_writes": ("ignore_writes", lambda v: v not in ("0", "false", "off", "")),
        "advertising": ("advertising", lambda v: v not in ("0", "false", "off", "")),
    }

    def set(self, key: str, raw: str) -> None:
        if key not in self.SETTABLE:
            raise KeyError(key)
        attr, conv = self.SETTABLE[key]
        value = conv(raw)
        with self.lock:
            setattr(self, attr, value)
        self.log(f"operator: {key} = {value}")

    def snapshot(self) -> dict:
        with self.lock:
            age = time.monotonic() - self.last_poll if self.last_poll else None
            return {
                "ac": self.out["ac"], "dc": self.out["dc"], "usb": self.out["usb"],
                "soc": round(self.soc, 2), "in_w": round(self.in_w), "out_w": round(self.out_w),
                "ac_out_w": round(self.ac_out_w), "overhead_w": round(self.overhead_w),
                "fridge_model_c": round(self.fridge_c, 2), "compressor": self.compressor,
                "charge_max": self.charge_max / 10, "discharge_min": self.discharge_min / 10,
                "ac_silent": bool(self.ac_silent),
                "solar": self.solar_w, "cooking": self.cooking_w, "charger": self.charger_w,
                "ambient": self.ambient_c, "time_factor": self.time_factor,
                "silent": self.silent, "ignore_writes": self.ignore_writes,
                "advertising": self.advertising,
                "last_poll_s": None if age is None else round(age, 1),
                **self.counters,
                "events": self.events[-40:],
            }


def describe_write(reg: int, value: int) -> str:
    names = {REG_USB: "USB", REG_DC: "DC", REG_AC: "AC", REG_LIGHT: "light",
             REG_KEY_SOUND: "key sound", REG_AC_SILENT: "silent charging",
             REG_DISCHARGE_MIN: "discharge min", REG_CHARGE_MAX: "charge max", REG_KNOB: "knob"}
    if reg in (REG_USB, REG_DC, REG_AC):
        return f"{names[reg]} {'ON' if value else 'OFF'}"
    if reg in (REG_DISCHARGE_MIN, REG_CHARGE_MAX):
        return f"{names[reg]} {value / 10:.0f} %"
    return f"{names.get(reg, f'reg {reg}')} = {value}"


# ---- Bluetooth (WinRT GATT server) -------------------------------------------
class BleStation:
    def __init__(self, station: Station) -> None:
        self.station = station
        self.loop = asyncio.get_running_loop()
        self.provider = None
        self.notify_char = None
        self.advertising = False
        self.last_delivered = -1
        self._tokens = []  # keep the event registrations alive

    async def start(self) -> None:
        from uuid import UUID
        from winrt.windows.devices.bluetooth import BluetoothAdapter, BluetoothError
        import winrt.windows.devices.bluetooth.genericattributeprofile as gatt

        adapter = await BluetoothAdapter.get_default_async()
        if adapter is None:
            raise SystemExit("no Bluetooth adapter")
        if not adapter.is_peripheral_role_supported:
            raise SystemExit("this Bluetooth adapter cannot act as a peripheral")
        addr = ":".join(f"{adapter.bluetooth_address:012X}"[i:i + 2] for i in range(0, 12, 2))
        self.station.log(f"adapter {addr} - van-core-pcsim.yaml must use this as p310_mac")

        res = await gatt.GattServiceProvider.create_async(UUID(SERVICE_UUID))
        if res.error != BluetoothError.SUCCESS:
            raise SystemExit(f"cannot create the GATT service: {res.error}")
        self.provider = res.service_provider
        service = self.provider.service

        wp = gatt.GattLocalCharacteristicParameters()
        wp.characteristic_properties = (gatt.GattCharacteristicProperties.WRITE
                                        | gatt.GattCharacteristicProperties.WRITE_WITHOUT_RESPONSE)
        wp.write_protection_level = gatt.GattProtectionLevel.PLAIN
        wres = await service.create_characteristic_async(UUID(WRITE_UUID), wp)
        if wres.error != BluetoothError.SUCCESS:
            raise SystemExit(f"cannot create the write characteristic: {wres.error}")
        self._tokens.append(wres.characteristic.add_write_requested(self._on_write_requested))

        npar = gatt.GattLocalCharacteristicParameters()
        npar.characteristic_properties = gatt.GattCharacteristicProperties.NOTIFY
        npar.read_protection_level = gatt.GattProtectionLevel.PLAIN
        nres = await service.create_characteristic_async(UUID(NOTIFY_UUID), npar)
        if nres.error != BluetoothError.SUCCESS:
            raise SystemExit(f"cannot create the notify characteristic: {nres.error}")
        self.notify_char = nres.characteristic
        self._tokens.append(self.notify_char.add_subscribed_clients_changed(self._on_subscribers))

        self._tokens.append(self.provider.add_advertisement_status_changed(self._on_adv_status))
        self.set_advertising(True)

    def set_advertising(self, on: bool) -> None:
        import winrt.windows.devices.bluetooth.genericattributeprofile as gatt
        if on == self.advertising:
            return
        if on:
            p = gatt.GattServiceProviderAdvertisingParameters()
            p.is_connectable = True
            p.is_discoverable = True
            self.provider.start_advertising_with_parameters(p)
        else:
            self.provider.stop_advertising()
        self.advertising = on

    # WinRT calls these on its own threads; hand everything to the loop.
    def _on_adv_status(self, sender, args) -> None:
        self.loop.call_soon_threadsafe(self.station.log, f"advertising: {args.status.name}")

    def _on_subscribers(self, sender, args) -> None:
        clients = list(sender.subscribed_clients)
        sizes = [c.max_notification_size for c in clients]
        msg = f"subscribed clients: {len(clients)}, max notification size {sizes}"
        if any(s < REPLY_LEN for s in sizes):
            msg += f" - BELOW the {REPLY_LEN}-byte reply, ESP-FBot will see truncated frames"
        self.loop.call_soon_threadsafe(self.station.log, msg)

    def _on_write_requested(self, sender, args) -> None:
        deferral = args.get_deferral()
        asyncio.run_coroutine_threadsafe(self._handle_write(args, deferral), self.loop)

    async def _handle_write(self, args, deferral) -> None:
        import winrt.windows.devices.bluetooth.genericattributeprofile as gatt
        try:
            req = await args.get_request_async()
            data = bytes(bytearray(req.value))
            if req.option == gatt.GattWriteOption.WRITE_WITH_RESPONSE:
                req.respond()
        finally:
            deferral.complete()
        reply = self.station.handle_frame(data)
        if reply is not None:
            await self.notify(reply)

    async def notify(self, frame: bytes) -> None:
        from winrt.windows.storage.streams import Buffer
        buf = Buffer(len(frame))
        buf.length = buf.capacity
        with memoryview(buf) as mv:
            mv[:] = frame
        results = list(await self.notify_char.notify_value_async(buf))
        delivered = sum(1 for r in results if r.status.name == "SUCCESS")
        for r in results:
            if r.status.name != "SUCCESS":
                self.station.log(f"notify failed: {r.status.name}")
        # Say so whenever delivery changes: zero means the ESP never subscribed,
        # and ESP-FBot cannot report that - it only times out after a first reply.
        if delivered != self.last_delivered:
            self.last_delivered = delivered
            self.station.log(f"replies now reaching {delivered} subscriber(s)"
                             + ("" if delivered else " - NOTHING IS GETTING THROUGH"))


# ---- control page --------------------------------------------------------------
PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>P310 simulator</title><style>
body{font:14px system-ui,sans-serif;margin:16px;background:#111;color:#ddd}
h1{font-size:18px;margin:0 0 4px}.sub{color:#888;margin-bottom:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px}
.card{background:#1c1c1c;border-radius:8px;padding:8px 10px}.k{color:#888;font-size:12px}
.v{font-size:20px;font-variant-numeric:tabular-nums}.on{color:#5c5}.off{color:#888}.warn{color:#e95}
label{display:block;margin:6px 0}input[type=number]{width:90px}
pre{background:#1c1c1c;padding:8px;border-radius:8px;max-height:320px;overflow:auto;font-size:12px}
</style></head><body>
<h1>P310 simulator</h1><div class="sub">The ESP running van-core-pcsim talks to this instead of the station.</div>
<div class="grid" id="st"></div>
<h2 style="font-size:15px">Conditions</h2><div class="grid">
<label>Solar W <input type="number" data-k="solar" step="10"></label>
<label>Cooking W <input type="number" data-k="cooking" step="50"></label>
<label>AC charger W <input type="number" data-k="charger" step="100"></label>
<label>Ambient &deg;C <input type="number" data-k="ambient" step="0.5"></label>
<label>Battery % <input type="number" data-k="soc" step="1"></label>
<label>Time factor <input type="number" data-k="time_factor" step="1" min="1"></label>
</div>
<h2 style="font-size:15px">Faults</h2>
<label><input type="checkbox" data-k="silent"> Silent: stop answering (link stays up)</label>
<label><input type="checkbox" data-k="ignore_writes"> Ignore writes (the M17 lost write)</label>
<label><input type="checkbox" data-k="advertising"> Advertising (off: a dropped ESP cannot reconnect)</label>
<h2 style="font-size:15px">Events</h2><pre id="ev"></pre>
<script>
const st=document.getElementById('st'),ev=document.getElementById('ev');let editing=null;
function card(k,v,c){return `<div class="card"><div class="k">${k}</div><div class="v ${c||''}">${v}</div></div>`}
async function poll(){try{const s=await (await fetch('state')).json();
st.innerHTML=card('AC',s.ac?'ON':'off',s.ac?'on':'off')+card('DC',s.dc?'ON':'OFF',s.dc?'on':'warn')
+card('Battery',s.soc.toFixed(1)+' %')+card('In / out',s.in_w+' / '+s.out_w+' W')
+card('AC output',s.ac_out_w+' W')+card('Fridge model',s.fridge_model_c.toFixed(1)+' \\u00b0C'+(s.compressor?' run':''))
+card('Charge max',s.charge_max+' %')+card('Last poll',s.last_poll_s===null?'never':s.last_poll_s+' s ago',s.last_poll_s===null||s.last_poll_s>15?'warn':'')
+card('Polls / writes',s.polls+' / '+s.writes);
document.querySelectorAll('[data-k]').forEach(e=>{if(e===editing)return;const v=s[e.dataset.k];
if(e.type==='checkbox')e.checked=!!v;else e.value=v});
ev.textContent=s.events.slice().reverse().join('\\n')}catch(e){st.innerHTML=card('simulator','not answering','warn')}}
document.querySelectorAll('[data-k]').forEach(e=>{e.addEventListener('focus',()=>editing=e);
e.addEventListener('blur',()=>editing=null);e.addEventListener('change',()=>{
const v=e.type==='checkbox'?(e.checked?1:0):e.value;fetch('set?'+e.dataset.k+'='+encodeURIComponent(v)).then(poll)})});
poll();setInterval(poll,1000);
</script></body></html>"""


def make_handler(station: Station):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code: int, body: bytes, ctype: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self) -> None:  # noqa: N802
            url = urlparse(self.path)
            if url.path == "/":
                return self._send(200, PAGE.encode(), "text/html; charset=utf-8")
            if url.path == "/state":
                return self._send(200, json.dumps(station.snapshot()).encode(), "application/json")
            if url.path == "/set":
                try:
                    for key, values in parse_qs(url.query).items():
                        station.set(key, values[-1])
                except (KeyError, ValueError) as e:
                    return self._send(400, f"bad setting: {e}".encode(), "text/plain")
                return self._send(200, json.dumps(station.snapshot()).encode(), "application/json")
            self._send(404, b"not found", "text/plain")

        def log_message(self, *args) -> None:  # the page polls every second
            pass

    return Handler


# ---- main ----------------------------------------------------------------------
async def run(args) -> None:
    station = Station()
    ble = BleStation(station)
    await ble.start()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(station))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    station.log(f"control page: http://127.0.0.1:{args.port}/")

    writer = None
    if args.csv:
        f = open(args.csv, "a", newline="")
        fields = ["time", "ac", "dc", "usb", "soc", "in_w", "out_w", "ac_out_w", "overhead_w",
                  "fridge_model_c", "compressor", "solar", "cooking", "charger", "silent",
                  "ignore_writes", "advertising", "polls", "writes", "dropped"]
        writer = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore")
        if f.tell() == 0:
            writer.writeheader()

    last = time.monotonic()
    last_csv = 0.0
    while True:
        await asyncio.sleep(0.25)
        now = time.monotonic()
        station.apply_due()
        if now - last >= 1.0:
            station.step(now - last)
            last = now
        with station.lock:
            want_adv = station.advertising
        if want_adv != ble.advertising:
            ble.set_advertising(want_adv)
        if writer and now - last_csv >= args.csv_every:
            last_csv = now
            row = station.snapshot()
            row["time"] = time.strftime("%Y-%m-%d %H:%M:%S")
            writer.writerow(row)
            f.flush()


def selftest() -> int:
    """Frames and model, no Bluetooth. Decodes exactly as ESP-FBot does."""
    def reg(frame: bytes, i: int) -> int:
        off = 6 + 2 * i
        return frame[off] << 8 | frame[off + 1]

    s = Station()
    s.log = lambda msg: None
    ok = True

    def check(cond, what: str) -> None:
        nonlocal ok
        cond = bool(cond)
        print(("ok    " if cond else "FAIL  ") + what)
        ok &= cond

    poll = with_crc(bytes([SLAVE, FN_READ_INPUT, 0, 0, 0, NUM_REGS]))
    check(len(poll) == 8 and poll[:6] == bytes([SLAVE, FN_READ_INPUT, 0, 0, 0, NUM_REGS]), f"poll frame {poll.hex()}")
    reply = s.handle_frame(poll)
    check(reply is not None and len(reply) == REPLY_LEN, f"input reply is {REPLY_LEN} bytes")
    check(reply[:6] == poll[:6], "reply header echoes the request")
    check(crc16_modbus(reply[:-2]) == (reply[-2] << 8 | reply[-1]), "reply CRC, high byte first")
    check(reg(reply, 56) == 800, "battery 80.0 % in reg 56")
    check(reg(reply, 41) & BIT_AC and reg(reply, 41) & BIT_DC, "AC and DC flags set at start")

    off = with_crc(bytes([SLAVE, FN_WRITE, 0, REG_AC, 0, 0]))
    echo = s.handle_frame(off)
    check(echo == off, "write is echoed")
    s.pending = [(0.0, r, v) for _, r, v in s.pending]  # skip the latency
    s.apply_due()
    reply = s.handle_frame(poll)
    check(not reg(reply, 41) & BIT_AC, "AC off after the write lands")
    check(reg(reply, 20) == 0 and reg(reply, 18) == 0, "no AC power or voltage with AC off")

    s.set("silent", "1")
    check(s.handle_frame(poll) is None, "silent station does not answer")
    s.set("silent", "0")
    s.set("ignore_writes", "1")
    s.handle_frame(with_crc(bytes([SLAVE, FN_WRITE, 0, REG_AC, 0, 1])))
    s.pending = [(0.0, r, v) for _, r, v in s.pending]
    s.apply_due()
    check(not s.out["ac"], "ignored write changes nothing")
    s.set("ignore_writes", "0")

    s.set("cooking", "2000")
    s.handle_frame(with_crc(bytes([SLAVE, FN_WRITE, 0, REG_AC, 0, 1])))
    s.pending = [(0.0, r, v) for _, r, v in s.pending]
    s.apply_due()
    s.step(1.0)
    reply = s.handle_frame(poll)
    check(reg(reply, 20) >= 2000 and reg(reply, 39) > reg(reply, 20), "cooking load shows on AC and total output")

    soc0 = s.soc
    s.set("time_factor", "3600")
    s.step(1.0)  # one simulated hour
    check(s.soc < soc0 - 40, f"an hour of 2 kW drains the pack ({soc0:.1f} -> {s.soc:.1f} %)")

    holding = s.handle_frame(with_crc(bytes([SLAVE, FN_READ_HOLDING, 0, 0, 0, NUM_REGS])))
    check(reg(holding, REG_CHARGE_MAX) == 1000 and reg(holding, REG_DISCHARGE_MIN) == 100, "thresholds in 66/67")
    bad = bytearray(poll)
    bad[-1] ^= 0xFF
    check(s.handle_frame(bytes(bad)) is None, "bad CRC is ignored")
    print("selftest", "passed" if ok else "FAILED")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", nargs="?", default="run", choices=["run", "selftest"])
    ap.add_argument("--port", type=int, default=8310, help="control page port (127.0.0.1)")
    ap.add_argument("--csv", help="append the station state to this CSV")
    ap.add_argument("--csv-every", type=float, default=10.0, help="seconds between CSV rows")
    args = ap.parse_args()
    if args.command == "selftest":
        return selftest()
    if sys.platform != "win32":
        print("Windows only: the GATT server is WinRT.", file=sys.stderr)
        return 2
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
