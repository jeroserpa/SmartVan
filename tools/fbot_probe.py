#!/usr/bin/env python3
"""
fbot_probe.py - Talk to an AFERIY / FOSSiBOT / SYDPOWER power station over BLE
from a laptop, with no microcontroller.

Protocol ported from ESP-FBot (https://github.com/Ylianst/ESP-FBot, Apache-2.0).
It is Modbus RTU tunnelled over a BLE GATT characteristic:

    slave address 0x11
    function 0x04  read input registers   (live status)
    function 0x03  read holding registers (settings)
    function 0x06  write single register  (control)

Note: the CRC is CRC-16/Modbus but transmitted HIGH byte first, which is the
opposite of standard Modbus RTU framing. Replicated as-is.

Usage
-----
    pip install bleak

    python fbot_probe.py selftest            # no hardware needed
    python fbot_probe.py scan
    python fbot_probe.py monitor --address AA:BB:CC:DD:EE:FF
    python fbot_probe.py registers --address ...            # all 160 registers
    python fbot_probe.py registers --address ... --watch 600 --csv regs.csv
    python fbot_probe.py monitor --address ... --raw
    python fbot_probe.py log --address ... --csv fridge.csv --interval 10
    python fbot_probe.py log --address ... --csv fridge.csv --hours 24
    python fbot_probe.py set ac on --address ... --i-understand

Long runs
---------
The logger keeps the machine awake by itself (Windows SetThreadExecutionState,
macOS caffeinate, Linux systemd-inhibit) for the duration of `log`. Pass
--no-keep-awake to disable. On Windows this suppresses idle sleep but NOT
closing the lid — leave it open, or set the lid action to "Do nothing" in
Control Panel > Power Options.

It also reconnects automatically after a BLE dropout, sleep, or the station
going away, with backoff, and records the outage in the CSV rather than leaving
a silent hole. A 24h run therefore does not need babysitting:

    python fbot_probe.py log --address ... --csv duty.csv --hours 24

IMPORTANT
---------
* The station accepts ONE BLE connection. Close the BrightEMS app first.
* Only the named commands below are supported. Arbitrary register writes are
  deliberately NOT exposed: this device's holding registers include battery
  protection thresholds, and fuzzing them is a bad idea.


REGISTER MAP CORRECTIONS (2026-08-19, from analysis of fridge_log.csv)
---------------------------------------------------------------------
Three names inherited from ESP-FBot were wrong, and the errors are not cosmetic
-- they invert the meaning of the two most useful channels:

  reg 20  was "total_power"   -> is AC OUTPUT power only (ac_output_w).
          Steps exactly with the fridge compressor, is unaffected by loads on
          the 12V DC output, reads ~0 while the station is in AC pass-through
          (inverter bypassed), and shows the full 2.3kW induction load.

  reg 39  was "output_power"  -> is TOTAL output, AC + DC + USB (total_output_w).
          Always equals reg20 + the DC/USB load.

  reg 21  was "system_power"  -> is NOT a power at all. Reads 2285 whenever an
          AC input is present and 14-24 when it is not, regardless of whether
          the station is charging at 800W or 400W. Almost certainly AC INPUT
          VOLTAGE at scale 0.1 (228.5V). Logged as ac_in_voltage, marked
          UNVERIFIED -- confirm against a meter on the incoming AC.

Consequence worth keeping: (reg39 - reg20) is a free split of the load into
AC-side and DC-side with no extra hardware. Logged as dc_usb_load_w.

Also: remaining_min (reg 59) is junk on this firmware -- it reads 0 or absurd
values like 42679. Kept in the CSV for completeness but do not trust it.
"""

import argparse
import asyncio
import csv
import datetime
import math
import sys

# ---------------------------------------------------------------------------
# Protocol constants (from ESP-FBot components/fbot/fbot.h)
# ---------------------------------------------------------------------------

SERVICE_UUID = "0000a002-0000-1000-8000-00805f9b34fb"
WRITE_CHAR_UUID = "0000c304-0000-1000-8000-00805f9b34fb"
NOTIFY_CHAR_UUID = "0000c305-0000-1000-8000-00805f9b34fb"

SLAVE_ADDR = 0x11
FN_READ_HOLDING = 0x03  # settings
FN_READ_INPUT = 0x04  # live status
FN_WRITE_SINGLE = 0x06  # control

# Control registers
REG_AC_CHARGE_LIMIT = 13
REG_USB_CONTROL = 24
REG_DC_CONTROL = 25
REG_AC_CONTROL = 26
REG_LIGHT_CONTROL = 27
REG_KEY_SOUND = 56
REG_AC_SILENT_CONTROL = 57
REG_THRESHOLD_DISCHARGE = 66
REG_THRESHOLD_CHARGE = 67

# State bitfield lives in input register 41
STATE_BITS = {
    "usb": 512,  # bit 9
    "dc": 1024,  # bit 10
    "ac": 2048,  # bit 11
    "light": 4096,  # bit 12
}

# Registers start at byte offset 6 in the notification, 2 bytes each, big-endian.
REG_DATA_OFFSET = 6
NUM_REGISTERS = 0x50  # 80
FRAME_BODY_LEN = REG_DATA_OFFSET + NUM_REGISTERS * 2  # 166, excluding CRC
EXPECTED_FRAME_LEN = FRAME_BODY_LEN  # kept for compatibility
MAX_FRAME_LEN = FRAME_BODY_LEN + 2  # 168, if a trailing CRC is present

# Live status register map: name -> (register index, scale)
# See REGISTER MAP CORRECTIONS in the module docstring before renaming anything.
STATUS_MAP = [
    ("charge_level_raw", 2, 1.0),
    ("ac_input_w", 3, 1.0),
    ("dc_input_w", 4, 1.0),
    ("input_w", 6, 1.0),
    ("ac_out_voltage", 18, 0.1),
    ("ac_out_freq", 19, 0.1),
    ("ac_output_w", 20, 1.0),  # was mislabelled total_w
    ("ac_in_voltage", 21, 0.1),  # was mislabelled system_w -- UNVERIFIED
    ("ac_in_freq", 22, 0.01),
    ("usb_a1_w", 30, 0.1),
    ("usb_a2_w", 31, 0.1),
    ("usb_c1_w", 34, 0.1),
    ("usb_c2_w", 35, 0.1),
    ("usb_c3_w", 36, 0.1),
    ("usb_c4_w", 37, 0.1),
    ("total_output_w", 39, 1.0),  # was mislabelled output_w
    ("state_flags", 41, 1.0),
    ("battery_pct", 56, 0.1),
    ("time_to_full_min", 58, 1.0),
    ("remaining_min", 59, 1.0),  # junk on this firmware
]

# CSV column order for logging.
CSV_FIELDS = [
    "timestamp",
    "battery_pct",
    "input_w",
    "ac_input_w",
    "dc_input_w",
    "ac_output_w",  # AC side only -- this is the fridge
    "dc_usb_load_w",  # derived: total_output_w - ac_output_w
    "total_output_w",
    "ac_out_voltage",
    "ac_in_voltage",
    "ac_in_freq",
    "ac_charge_limit_w",
    "remaining_min",
    "link_state",  # "ok" for samples, "gap" for a synthetic outage marker
    "ac_on",
    "dc_on",
    "usb_on",
    "light_on",
]

# Plausibility bounds. A frame that fails any of these is discarded rather than
# logged: the corrupt tail rows in the 2026-08-18 dataset (SOC dropping 0.4% in
# one second as the laptop suspended mid-notification) came through because
# nothing was checking.
SANITY = {
    "battery_pct": (0.0, 100.0),
    "input_w": (0, 6000),
    "ac_input_w": (0, 6000),
    "dc_input_w": (0, 2000),
    "ac_output_w": (0, 4000),
    "total_output_w": (0, 4000),
    "ac_out_voltage": (0.0, 300.0),
}

# Reject a sample whose SOC moves faster than this. LiFePO4 at 3.8kWh cannot
# move 0.1%/s (that would be 138kW), so anything near it is frame corruption.
MAX_SOC_SLEW_PCT_PER_S = 0.02


# ---------------------------------------------------------------------------
# Framing
# ---------------------------------------------------------------------------


def crc16_modbus(data: bytes) -> int:
    """CRC-16/Modbus. Polynomial 0xA001, init 0xFFFF, reflected."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc


def build_frame(function: int, reg: int, value: int, addr: int = SLAVE_ADDR) -> bytes:
    """Build an 8-byte request. CRC is appended HIGH byte first (non-standard)."""
    payload = bytes(
        [
            addr,
            function,
            (reg >> 8) & 0xFF,
            reg & 0xFF,
            (value >> 8) & 0xFF,
            value & 0xFF,
        ]
    )
    crc = crc16_modbus(payload)
    return payload + bytes([(crc >> 8) & 0xFF, crc & 0xFF])


def check_crc(frame: bytes):
    """
    Return True / False / None for a response frame.

    None means "this firmware does not append a CRC to responses" -- the frame
    is exactly the body length with no trailing bytes, so there is nothing to
    check and validation falls back to the sanity limits.

    Both byte orders are accepted: the request path sends the CRC high byte
    first, but the response direction has never been confirmed either way.
    """
    if len(frame) < FRAME_BODY_LEN + 2:
        return None
    body, tail = frame[:FRAME_BODY_LEN], frame[FRAME_BODY_LEN:FRAME_BODY_LEN + 2]
    crc = crc16_modbus(body)
    hi_first = bytes([(crc >> 8) & 0xFF, crc & 0xFF])
    lo_first = bytes([crc & 0xFF, (crc >> 8) & 0xFF])
    return tail in (hi_first, lo_first)


def read_status_frame() -> bytes:
    return build_frame(FN_READ_INPUT, 0x0000, NUM_REGISTERS)


def read_settings_frame() -> bytes:
    return build_frame(FN_READ_HOLDING, 0x0000, NUM_REGISTERS)


def get_register(data: bytes, index: int):
    """Extract a big-endian 16-bit register. Returns None if out of range."""
    offset = REG_DATA_OFFSET + index * 2
    if offset + 1 >= len(data):
        return None
    return (data[offset] << 8) | data[offset + 1]


def decode_status(data: bytes) -> dict:
    out = {}
    for name, index, scale in STATUS_MAP:
        raw = get_register(data, index)
        if raw is None:
            out[name] = None
        elif scale == 1.0:
            out[name] = raw
        else:
            out[name] = round(raw * scale, 2)

    flags = out.get("state_flags")
    if flags is not None:
        for name, mask in STATE_BITS.items():
            out[f"{name}_on"] = bool(flags & mask)

    raw_level = out.get("charge_level_raw")
    if raw_level and 1 <= raw_level <= 5:
        out["ac_charge_limit_w"] = 300 + (raw_level - 1) * 200
    else:
        out["ac_charge_limit_w"] = None

    # Derived: everything drawn from the station that is not on the AC output.
    ac = out.get("ac_output_w")
    total = out.get("total_output_w")
    if ac is not None and total is not None:
        out["dc_usb_load_w"] = max(0, total - ac)
    else:
        out["dc_usb_load_w"] = None
    return out


def sanity_check(status: dict):
    """Return None if plausible, else a string describing the first failure."""
    for key, (lo, hi) in SANITY.items():
        v = status.get(key)
        if v is None:
            return f"{key} missing"
        if not (lo <= v <= hi):
            return f"{key}={v} out of range [{lo},{hi}]"
    return None


def decode_settings(data: bytes) -> dict:
    def reg(i):
        return get_register(data, i)

    charge = reg(REG_THRESHOLD_CHARGE)
    discharge = reg(REG_THRESHOLD_DISCHARGE)
    limit = reg(REG_AC_CHARGE_LIMIT)
    return {
        "threshold_charge_pct": charge / 10.0 if charge is not None else None,
        "threshold_discharge_pct": discharge / 10.0 if discharge is not None else None,
        "ac_charge_limit_w": (300 + (limit - 1) * 200) if limit and 1 <= limit <= 5 else None,
        "ac_silent": reg(REG_AC_SILENT_CONTROL) == 1,
        "key_sound": reg(REG_KEY_SOUND) == 1,
        "light_mode": reg(REG_LIGHT_CONTROL),
    }


# ---------------------------------------------------------------------------
# Notification reassembly
# ---------------------------------------------------------------------------


class FrameAssembler:
    """
    BLE notifications may arrive fragmented depending on the negotiated MTU.
    A full 80-register response is ~166 bytes, which exceeds the default MTU of
    23, so reassembly is usually required. A fragment starting with the slave
    address and a known function code is treated as the start of a new frame.

    Once the body length is reached the frame is held for one further
    notification (or a short grace period) in case a 2-byte CRC tail follows,
    so that check_crc() has something to work with. If no tail ever arrives the
    frame is dispatched uncrced and validation falls back to sanity limits.
    """

    def __init__(self, on_frame, raw=False):
        self.buf = bytearray()
        self.on_frame = on_frame
        self.raw = raw

    def feed(self, chunk: bytes):
        if self.raw:
            print(f"  raw[{len(chunk):3d}]: {chunk.hex()}")

        starts_frame = (
            len(chunk) >= 2
            and chunk[0] == SLAVE_ADDR
            and chunk[1] in (FN_READ_INPUT, FN_READ_HOLDING, FN_WRITE_SINGLE)
        )
        if starts_frame:
            # Flush anything incomplete before starting fresh.
            if self.buf:
                self.on_frame(bytes(self.buf), complete=len(self.buf) >= FRAME_BODY_LEN)
            self.buf = bytearray(chunk)
        else:
            self.buf.extend(chunk)

        if len(self.buf) >= MAX_FRAME_LEN:
            self.on_frame(bytes(self.buf[:MAX_FRAME_LEN]), complete=True)
            self.buf = bytearray()

    def flush(self):
        """Dispatch a body-length frame that never received a CRC tail."""
        if len(self.buf) >= FRAME_BODY_LEN:
            self.on_frame(bytes(self.buf), complete=True)
            self.buf = bytearray()
        elif self.buf:
            self.on_frame(bytes(self.buf), complete=False)
            self.buf = bytearray()


# ---------------------------------------------------------------------------
# Terminal rendering
# ---------------------------------------------------------------------------

HEADER = (
    "time      SOC%    in_W  ac_in dc_in |  AC_W  DC_W  tot_W | Vout  Vin   Hz  "
    "| lim_W | AC  DC USB LGT"
)


def format_row(s: dict) -> str:
    def n(key, width, fmt="{:.0f}"):
        v = s.get(key)
        return ("-" if v is None else fmt.format(v)).rjust(width)

    ts = datetime.datetime.now().strftime("%H:%M:%S")
    flags = " ".join(
        ("ON " if s.get(f"{k}_on") else "off").ljust(3)
        for k in ("ac", "dc", "usb", "light")
    )
    return (
        f"{ts} {n('battery_pct',6,'{:.1f}')} "
        f"{n('input_w',7)} {n('ac_input_w',6)} {n('dc_input_w',5)} |"
        f"{n('ac_output_w',6)}{n('dc_usb_load_w',6)}{n('total_output_w',7)} |"
        f"{n('ac_out_voltage',6,'{:.1f}')}{n('ac_in_voltage',6,'{:.1f}')}"
        f"{n('ac_in_freq',5,'{:.2f}')} |"
        f"{n('ac_charge_limit_w',6)} | {flags}"
    )


class KeepAwake:
    """
    Stop the machine sleeping for the duration of a logging run.

    Windows is the reason this lives in the script rather than in a shell
    wrapper: there is no systemd-inhibit equivalent, only the
    SetThreadExecutionState API. ES_CONTINUOUS makes the request persist until
    cleared; ES_SYSTEM_REQUIRED covers system sleep.

    Caveat on Windows: this suppresses *idle* sleep only. Closing the lid, or a
    Modern Standby (S0) machine deciding to drop to a low-power state, are not
    covered — leave the lid open and set the lid action to "Do nothing".
    """

    ES_CONTINUOUS = 0x80000000
    ES_SYSTEM_REQUIRED = 0x00000001

    def __init__(self, enabled=True, why="fbot logging"):
        self.enabled = enabled
        self.why = why
        self.proc = None
        self.method = None

    def __enter__(self):
        if not self.enabled:
            return self
        import platform
        import subprocess

        system = platform.system()
        try:
            if system == "Windows":
                import ctypes

                r = ctypes.windll.kernel32.SetThreadExecutionState(
                    self.ES_CONTINUOUS | self.ES_SYSTEM_REQUIRED
                )
                if r:
                    self.method = "SetThreadExecutionState"
            elif system == "Darwin":
                self.proc = subprocess.Popen(["caffeinate", "-i"])
                self.method = "caffeinate"
            else:
                self.proc = subprocess.Popen(
                    ["systemd-inhibit", "--what=sleep:idle", "--mode=block",
                     f"--why={self.why}", "sleep", "infinity"]
                )
                self.method = "systemd-inhibit"
        except Exception as exc:  # not fatal -- logging is still useful
            print(f"  [keep-awake unavailable: {exc}]")
            return self

        if self.method:
            print(f"  [keep-awake active via {self.method}]")
        else:
            print("  [keep-awake could not be established]")
        return self

    def __exit__(self, *_):
        if self.method == "SetThreadExecutionState":
            import ctypes

            ctypes.windll.kernel32.SetThreadExecutionState(self.ES_CONTINUOUS)
        if self.proc is not None:
            self.proc.terminate()
        return False


class RunStats:
    """Running duty cycle and energy tally -- the point of the exercise."""

    def __init__(self, on_threshold=10.0):
        self.on_threshold = on_threshold
        self.t0 = None
        self.last_t = None
        self.last_on = None
        self.secs_total = 0.0
        self.secs_ac_on = 0.0
        self.wh_ac = 0.0
        self.wh_dc = 0.0
        self.last_ac = 0.0
        self.last_dc = 0.0
        self.soc_first = None
        self.soc_last = None
        self.rows = 0
        self.rejected = 0
        self.transitions = 0

    def update(self, t, s):
        ac = s.get("ac_output_w") or 0.0
        dc = s.get("dc_usb_load_w") or 0.0
        on = ac > self.on_threshold
        if self.t0 is None:
            self.t0 = t
            self.soc_first = s.get("battery_pct")
        else:
            dt = (t - self.last_t).total_seconds()
            if 0 < dt < 300:  # ignore clock jumps and long gaps
                self.secs_total += dt
                # trapezoidal, so a compressor start mid-interval is not
                # attributed to the whole interval
                self.wh_ac += (ac + self.last_ac) / 2 * dt / 3600
                self.wh_dc += (dc + self.last_dc) / 2 * dt / 3600
                if self.last_on:
                    self.secs_ac_on += dt
            if on != self.last_on:
                self.transitions += 1
        self.last_t, self.last_on, self.last_ac, self.last_dc = t, on, ac, dc
        self.soc_last = s.get("battery_pct")
        self.rows += 1

    def summary(self) -> str:
        if not self.secs_total:
            return "no usable samples"
        h = self.secs_total / 3600
        duty = 100 * self.secs_ac_on / self.secs_total
        soc = ""
        if self.soc_first is not None and self.soc_last is not None:
            d = self.soc_last - self.soc_first
            soc = f"  SOC {self.soc_first:.1f} -> {self.soc_last:.1f} ({d:+.1f}%)"
        return (
            f"{self.rows} rows ({self.rejected} rejected) over {h:.2f} h\n"
            f"AC-output duty cycle : {duty:.1f} %  ({self.transitions} transitions)\n"
            f"AC energy            : {self.wh_ac:.1f} Wh  (mean {self.wh_ac/h:.1f} W)\n"
            f"DC/USB energy        : {self.wh_dc:.1f} Wh  (mean {self.wh_dc/h:.1f} W)"
            f"{soc}"
        )


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

CONTROL_COMMANDS = {
    "ac": (REG_AC_CONTROL, {"on": 1, "off": 0}),
    "dc": (REG_DC_CONTROL, {"on": 1, "off": 0}),
    "usb": (REG_USB_CONTROL, {"on": 1, "off": 0}),
    "light": (REG_LIGHT_CONTROL, {"off": 0, "on": 1, "sos": 2, "flash": 3}),
    "silent": (REG_AC_SILENT_CONTROL, {"on": 1, "off": 0}),
    "beep": (REG_KEY_SOUND, {"on": 1, "off": 0}),
}


async def find_device(name_hint=None, timeout=10.0):
    from bleak import BleakScanner

    print(f"Scanning for {timeout:.0f}s...")
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    # Many BLE peripherals only advertise 1-2 UUIDs in the primary packet (28
    # bytes total) and expose the rest only once connected, so absence of the
    # service UUID here does not rule a device out. Fall back to name hints.
    default_hints = ("power", "fossibot", "sydpower", "aferiy", "bright")
    found = []
    for dev, adv in devices.values():
        service_match = SERVICE_UUID.lower() in [u.lower() for u in (adv.service_uuids or [])]
        name = dev.name or adv.local_name or "(unnamed)"
        name_match = any(h in name.lower() for h in default_hints)
        if name_hint:
            name_match = name_match or name_hint.lower() in name.lower()
        found.append((dev, name, service_match or name_match))
    return found


async def cmd_scan(args):
    results = await find_device(args.name, args.timeout)
    likely = [r for r in results if r[2]]
    print(f"\n{len(results)} device(s) seen. Likely matches marked with *\n")
    for dev, name, match in sorted(results, key=lambda r: (not r[2], r[1])):
        mark = "*" if match else " "
        print(f" {mark} {dev.address}   {name}")
    if not likely:
        print(
            "\nNo device advertised the expected service UUID.\n"
            "The station may only advertise it while awake — press a button on it\n"
            "and rescan, and make sure the BrightEMS app is fully closed."
        )
    else:
        print(f"\nTry:  python {sys.argv[0]} monitor --address {likely[0][0].address}")


class LinkStalled(Exception):
    """Raised when the connection is nominally up but no frames are arriving."""


async def run_session(args, on_status, on_settings=None, duration=None):
    """
    Connect, subscribe, and poll status at args.interval until interrupted.

    Two behaviours differ from the original:
      * frames are validated (CRC where available, plausibility always, plus an
        SOC slew guard) before being handed to on_status;
      * accepted samples are rate-limited to one per poll interval, because the
        notification stream and the poll loop race and produced ~250 duplicate
        zero-interval rows in the 2026-08-18 log.
    """
    from bleak import BleakClient

    state = {
        "last_accept": None,
        "last_soc": None,
        "crc_mode": None,  # None = unknown, True = present, False = absent
        "rejected": 0,
    }
    min_gap = max(0.4 * args.interval, 0.5)

    def handle_frame(data, complete):
        if not complete:
            state["rejected"] += 1
            if args.verbose:
                print(f"  ! discarded {len(data)} incomplete bytes")
            return

        if data[1] == FN_READ_HOLDING and on_settings:
            on_settings(decode_settings(data))
            return
        if data[1] == FN_WRITE_SINGLE:
            print(f"  write acknowledged: {data[:8].hex()}")
            return
        if data[1] != FN_READ_INPUT:
            return

        crc_ok = check_crc(data)
        if crc_ok is not None and state["crc_mode"] is None:
            state["crc_mode"] = crc_ok
            print(f"  [frame CRC {'validates' if crc_ok else 'DOES NOT validate'} "
                  f"— {'checking' if crc_ok else 'falling back to sanity limits'}]")
        if crc_ok is False and state["crc_mode"] is True:
            state["rejected"] += 1
            if args.verbose:
                print("  ! CRC mismatch, frame dropped")
            return

        status = decode_status(data)
        bad = sanity_check(status)
        if bad:
            state["rejected"] += 1
            if args.verbose:
                print(f"  ! implausible frame dropped: {bad}")
            return

        now = datetime.datetime.now()

        # SOC slew guard: catches misaligned frames that happen to decode into
        # in-range values, which is how the corrupt tail rows got logged before.
        if state["last_soc"] is not None and state["last_accept"] is not None:
            dt = (now - state["last_accept"]).total_seconds()
            if dt > 0:
                slew = abs(status["battery_pct"] - state["last_soc"]) / dt
                if slew > MAX_SOC_SLEW_PCT_PER_S:
                    state["rejected"] += 1
                    if args.verbose:
                        print(f"  ! SOC slew {slew:.3f} %/s, frame dropped")
                    return

        # Rate-limit: one accepted sample per poll interval.
        if state["last_accept"] is not None:
            if (now - state["last_accept"]).total_seconds() < min_gap:
                return

        state["last_accept"] = now
        state["last_soc"] = status["battery_pct"]
        on_status(status)

    assembler = FrameAssembler(handle_frame, raw=args.raw)

    def notification_handler(_sender, data):
        assembler.feed(bytes(data))

    print(f"Connecting to {args.address} ...")
    async with BleakClient(args.address, timeout=20.0) as client:
        print(f"Connected. MTU = {getattr(client, 'mtu_size', 'unknown')}")
        await client.start_notify(NOTIFY_CHAR_UUID, notification_handler)

        if on_settings:
            await client.write_gatt_char(WRITE_CHAR_UUID, read_settings_frame(), response=False)
            await asyncio.sleep(1.0)

        if getattr(args, "_write_frame", None) is not None:
            print(f"Sending control frame: {args._write_frame.hex()}")
            await client.write_gatt_char(WRITE_CHAR_UUID, args._write_frame, response=False)
            await asyncio.sleep(1.5)

        started = asyncio.get_event_loop().time()
        last_data = started
        stall_after = max(args.stall * args.interval, 60.0)
        while True:
            await client.write_gatt_char(WRITE_CHAR_UUID, read_status_frame(), response=False)
            await asyncio.sleep(args.interval)
            assembler.flush()  # dispatch a frame with no CRC tail

            # Stall watchdog. The 2026-08-19 run lost several hours because
            # BleakClient reported the link as up while notifications had
            # stopped: no exception was ever raised, so the reconnect logic
            # never fired and the poll loop wrote into the void.
            if state["last_accept"] is not None:
                last_data = state["last_accept"].timestamp()
                now_wall = datetime.datetime.now().timestamp()
                if now_wall - last_data > stall_after:
                    raise LinkStalled(
                        f"no valid frame for {now_wall - last_data:.0f}s "
                        f"(threshold {stall_after:.0f}s)"
                    )
            elif asyncio.get_event_loop().time() - started > stall_after:
                raise LinkStalled(f"no valid frame since connect ({stall_after:.0f}s)")

            if duration and asyncio.get_event_loop().time() - started > duration:
                break

        await client.stop_notify(NOTIFY_CHAR_UUID)
    return state


async def cmd_services(args):
    """Dump the GATT table. Use this if monitor connects but sees no data."""
    from bleak import BleakClient

    print(f"Connecting to {args.address} ...")
    async with BleakClient(args.address, timeout=20.0) as client:
        print(f"Connected. MTU = {getattr(client, 'mtu_size', 'unknown')}\n")
        want = {
            SERVICE_UUID.lower(): "EXPECTED SERVICE",
            WRITE_CHAR_UUID.lower(): "EXPECTED WRITE CHAR",
            NOTIFY_CHAR_UUID.lower(): "EXPECTED NOTIFY CHAR",
        }
        for service in client.services:
            tag = want.get(service.uuid.lower(), "")
            print(f"service {service.uuid}  {tag}")
            for char in service.characteristics:
                ctag = want.get(char.uuid.lower(), "")
                props = ",".join(char.properties)
                print(f"    char {char.uuid}  [{props}]  {ctag}")
        print(
            "\nIf the expected UUIDs are absent, this firmware uses a different\n"
            "GATT layout. Look for a characteristic with 'write' or "
            "'write-without-response'\nand another with 'notify'."
        )


# ---------------------------------------------------------------------------
# Full register sweep
# ---------------------------------------------------------------------------
#
# ESP-FBot names 22 of the 80 input registers and 6 of the 80 holding
# registers. The other 132 arrive in the same 168-byte frame every poll and are
# thrown away. This command shows all of them.
#
# Reading is safe: 0x03 and 0x04 are reads, and this command never transmits
# 0x06. The distinction matters, because the ESP-FBot author bricked his own
# P310 into a boot loop by writing a bad setting over BLE (see
# ESP-FBot/internals/README.md). Sweep freely; do not fuzz writes.

# Annotations for the input-register bank (function 0x04).
# A "?" prefix marks a hypothesis, not a fact.
INPUT_NOTES = {
    2: "charge level 1-5 -> 300..1100W",
    3: "AC input W",
    4: "DC/solar input W",
    6: "total input W",
    18: "AC out voltage x0.1",
    19: "AC out frequency x0.1",
    20: "AC OUTPUT W (ESP-FBot calls this total_power)",
    21: "AC in voltage x0.1, UNVERIFIED (ESP-FBot calls this system_power)",
    22: "AC in frequency x0.01",
    30: "USB-A1 W x0.1",
    31: "USB-A2 W x0.1",
    34: "USB-C1 W x0.1",
    35: "USB-C2 W x0.1",
    36: "USB-C3 W x0.1",
    37: "USB-C4 W x0.1",
    39: "TOTAL output W, AC+DC+USB (ESP-FBot calls this output_power)",
    41: "state flags: b9 USB, b10 DC, b11 AC, b12 light",
    53: "expansion battery S1 pct, /10 -1, 0 = absent",
    55: "expansion battery S2 pct, /10 -1, 0 = absent",
    56: "battery pct x0.1",
    58: "time to full, minutes",
    59: "remaining minutes -- junk on this firmware",
    47: "? bitfield, read 0x3000 in the ESP-FBot capture",
    48: "? bitfield, read 0x4000 in the ESP-FBot capture",
    54: "? read 788; sits between the two expansion-battery slots",
    62: "? read 0x00FF -- looks like an absent sentinel",
    63: "? read 0xFFFF -- looks like an absent sentinel",
}

# Annotations for the holding-register bank (function 0x03).
HOLDING_NOTES = {
    13: "AC charge limit 1-5",
    27: "light mode 0=off 1=on 2=SOS 3=flash",
    56: "key sound 0/1",
    57: "AC silent 0/1",
    66: "discharge threshold x0.1 pct",
    67: "charge threshold x0.1 pct",
    18: "? read 115 -- 115V region setting?",
    22: "? read 233 -- 230V region setting?",
    47: "? read 38 -- plausible degC",
    48: "? read 24 -- plausible degC",
    49: "? read 27 -- plausible degC",
    50: "? read 36 -- plausible degC",
    60: "? read 480 -- plausible auto-off minutes (8h)",
    61: "? read 480 -- plausible auto-off minutes (8h)",
    62: "? read 300 -- plausible auto-off minutes (5h)",
}

BANK_ORDER = ("input", "holding")


def bank_spec(name):
    if name == "input":
        return FN_READ_INPUT, INPUT_NOTES, read_status_frame
    return FN_READ_HOLDING, HOLDING_NOTES, read_settings_frame


class RegisterSweep:
    """Accumulates per-register first/last/min/max/change-count for one bank."""

    def __init__(self, notes):
        self.notes = notes
        self.first = [None] * NUM_REGISTERS
        self.last = [None] * NUM_REGISTERS
        self.lo = [None] * NUM_REGISTERS
        self.hi = [None] * NUM_REGISTERS
        self.changes = [0] * NUM_REGISTERS
        self.samples = 0

    def feed(self, data: bytes):
        self.samples += 1
        for i in range(NUM_REGISTERS):
            v = get_register(data, i)
            if v is None:
                continue
            if self.first[i] is None:
                self.first[i] = self.lo[i] = self.hi[i] = v
            elif v != self.last[i]:
                self.changes[i] += 1
            self.lo[i] = min(self.lo[i], v)
            self.hi[i] = max(self.hi[i], v)
            self.last[i] = v

    def report(self, title, show_all=False):
        lines = [
            f"===== {title} bank, {self.samples} sample(s) =====",
            "reg    value    hex   hi/lo    min    max  chg  meaning",
        ]
        hidden = 0
        for i in range(NUM_REGISTERS):
            v = self.last[i]
            if v is None:
                continue
            note = self.notes.get(i, "")
            # Hide registers that are both unnamed and permanently zero: they
            # carry no information and would bury the interesting rows.
            if not show_all and not note and self.hi[i] == 0:
                hidden += 1
                continue
            mark = " " if note and not note.startswith("?") else "*"
            lines.append(
                f"{i:3d} {v:8d} 0x{v:04X} {v >> 8:4d}/{v & 0xFF:<4d}"
                f"{self.lo[i]:7d}{self.hi[i]:7d} {self.changes[i]:4d} {mark}{note}"
            )
        if hidden:
            lines.append(f"    ({hidden} unnamed registers read 0 throughout; --all to show)")
        lines.append("    * = unnamed or hypothesis only. chg = times the value moved.")
        return "\n".join(lines)


async def cmd_registers(args):
    """
    Dump every register in both banks, optionally watching for movement.

    A single snapshot says which registers are populated. --watch is what
    actually identifies them: leave it running while changing one thing at a
    time (AC on, kettle on, charger connected) and the chg column plus the
    min/max spread show which unnamed registers track that action.
    """
    from bleak import BleakClient

    sweeps = {name: RegisterSweep(bank_spec(name)[1]) for name in BANK_ORDER}
    csv_rows = []

    def handle_frame(data, complete):
        if not complete or len(data) < FRAME_BODY_LEN or data[0] != SLAVE_ADDR:
            return
        for name in BANK_ORDER:
            if data[1] == bank_spec(name)[0]:
                sweeps[name].feed(data)
                if args.csv:
                    csv_rows.append(
                        [datetime.datetime.now().isoformat(timespec="seconds"), name]
                        + [get_register(data, i) for i in range(NUM_REGISTERS)]
                    )
                return

    assembler = FrameAssembler(handle_frame, raw=args.raw)

    print(f"Connecting to {args.address} ...")
    async with BleakClient(args.address, timeout=20.0) as client:
        print(f"Connected. MTU = {getattr(client, 'mtu_size', 'unknown')}")
        await client.start_notify(NOTIFY_CHAR_UUID, lambda _s, d: assembler.feed(bytes(d)))

        deadline = asyncio.get_event_loop().time() + max(args.watch, 0.0)
        while True:
            for name in BANK_ORDER:
                await client.write_gatt_char(
                    WRITE_CHAR_UUID, bank_spec(name)[2](), response=False
                )
                await asyncio.sleep(max(args.interval / 2.0, 0.5))
                assembler.flush()
            if asyncio.get_event_loop().time() >= deadline:
                break
            if args.watch:
                print(
                    f"  ... {sweeps['input'].samples} input / "
                    f"{sweeps['holding'].samples} holding samples",
                    end="\r",
                )

        await client.stop_notify(NOTIFY_CHAR_UUID)

    print()
    for name in BANK_ORDER:
        print(sweeps[name].report(name, show_all=args.all))
        print()

    if args.csv and csv_rows:
        with open(args.csv, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["timestamp", "bank"] + [f"reg{i}" for i in range(NUM_REGISTERS)])
            w.writerows(csv_rows)
        print(f"Wrote {len(csv_rows)} rows to {args.csv}")


async def cmd_monitor(args):
    stats = RunStats()
    count = {"n": 0}

    def on_status(s):
        if count["n"] % 20 == 0:
            print(HEADER)
        count["n"] += 1
        stats.update(datetime.datetime.now(), s)
        print(format_row(s))

    def on_settings(cfg):
        print(f"  settings: {cfg}")

    try:
        await run_session(args, on_status, on_settings)
    finally:
        print("\n" + stats.summary())


async def cmd_log(args):
    """
    Log until --hours elapses or Ctrl-C, surviving BLE dropouts.

    A dropout is written to the CSV as an explicit link_state="gap" row rather
    than left as a silent hole in the timestamps, so the analysis can exclude
    the outage instead of interpolating a compressor state across it. RunStats
    already ignores intervals longer than 300s for the same reason.
    """
    fh = open(args.csv, "a", newline="")
    writer = csv.DictWriter(fh, fieldnames=CSV_FIELDS, extrasaction="ignore")
    if fh.tell() == 0:
        writer.writeheader()
    stats = RunStats()
    count = {"n": 0}
    last_sample = {"t": None}

    def on_status(s):
        now = datetime.datetime.now()
        row = {k: s.get(k) for k in CSV_FIELDS}
        row["timestamp"] = now.isoformat(timespec="seconds")
        row["link_state"] = "ok"
        writer.writerow(row)
        fh.flush()
        stats.update(now, s)
        last_sample["t"] = now

        # Full readout, so a run can be sanity-checked at a glance rather than
        # discovered to be wrong after 24 hours.
        if count["n"] % 20 == 0:
            print(HEADER)
        count["n"] += 1
        duty = 100 * stats.secs_ac_on / stats.secs_total if stats.secs_total else 0
        print(f"{format_row(s)}  | duty {duty:4.1f}%  n={count['n']}")

    def mark_gap(reason):
        now = datetime.datetime.now()
        row = {k: None for k in CSV_FIELDS}
        row["timestamp"] = now.isoformat(timespec="seconds")
        row["link_state"] = "gap"
        writer.writerow(row)
        fh.flush()
        gap = ""
        if last_sample["t"]:
            gap = f" after {(now - last_sample['t']).total_seconds()/60:.1f} min of data"
        print(f"\n  [link lost{gap}: {reason}]")

    deadline = None
    if args.hours:
        deadline = asyncio.get_event_loop().time() + args.hours * 3600
    backoff = 5.0
    attempts = {"n": 0}

    try:
        with KeepAwake(enabled=not args.no_keep_awake):
            while True:
                remaining = None
                if deadline is not None:
                    remaining = deadline - asyncio.get_event_loop().time()
                    if remaining <= 0:
                        break
                try:
                    await run_session(args, on_status, duration=remaining)
                    if deadline is not None:
                        break  # ran to completion
                    backoff = 5.0
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    attempts["n"] += 1
                    mark_gap(f"{type(exc).__name__}: {exc}")
                    print(f"  [reconnecting in {backoff:.0f}s "
                          f"(reconnect #{attempts['n']})]")
                    await asyncio.sleep(backoff)
                    backoff = min(backoff * 2, 120.0)
    except KeyboardInterrupt:
        pass
    finally:
        fh.close()
        print(f"\nWrote {count['n']} rows to {args.csv}"
              f"  ({attempts['n']} reconnect(s))")
        print(stats.summary())


# Break-even inverter idle for imposed cycling, from ANALYSIS-2026-08-20 §4.3:
# at 50% inverter duty and a 20% pulldown penalty, below this figure forcing
# run/rest blocks stops paying and the answer becomes a 12V compressor fridge.
BREAKEVEN_IDLE_W = 11.0


def phase_resolution_w(minutes, capacity_wh):
    """
    One 0.1% SOC step -- the station's reporting resolution -- expressed as
    power over a phase of this length. This, not the inverter-efficiency
    assumption, is what limits the idle test: it depends only on duration, so
    the only way to buy precision is longer phases.
    """
    if minutes <= 0:
        return float("inf")
    return 0.1 / 100 * capacity_wh / (minutes / 60)


class Phase:
    """Accumulates one measurement phase for the idle test."""

    def __init__(self, label):
        self.label = label
        self.rows = []

    def add(self, t, s):
        self.rows.append((t, s))

    def result(self, capacity_wh):
        if len(self.rows) < 3:
            return None
        t0, s0 = self.rows[0]
        t1, s1 = self.rows[-1]
        hours = (t1 - t0).total_seconds() / 3600
        if hours <= 0:
            return None

        def mean(key):
            vals = [s.get(key) or 0.0 for _, s in self.rows]
            return sum(vals) / len(vals)

        d_soc = s1["battery_pct"] - s0["battery_pct"]
        batt_w = d_soc / 100 * capacity_wh / hours  # +ve = charging
        ac, dc, inp = mean("ac_output_w"), mean("dc_usb_load_w"), mean("input_w")
        # Fraction of the phase during which the inverter was actually on.
        # With no AC load plugged in, a station that auto-offs its own output
        # would otherwise make the baseline phase silently measure base load,
        # and the test would report ~0 W of inverter idle. See check below.
        ac_on_frac = sum(1 for _, s in self.rows if s.get("ac_on")) / len(self.rows)
        # AC output is delivered through the inverter, DC output is not.
        load_dc_equiv = ac / 0.90 + dc / 0.95
        overhead = inp - load_dc_equiv - batt_w
        return {
            "label": self.label,
            "minutes": hours * 60,
            "soc0": s0["battery_pct"],
            "soc1": s1["battery_pct"],
            "batt_w": batt_w,
            "ac_w": ac,
            "dc_w": dc,
            "input_w": inp,
            "overhead_w": overhead,
            "resolution_w": phase_resolution_w(hours * 60, capacity_wh),
            "ac_on_frac": ac_on_frac,
        }


async def cmd_idle_test(args):
    """
    Measure inverter idle by subtracting the standby overhead with the AC
    output OFF from the standby overhead with it ON. This is the one thing
    neither logging run could answer: ac_on was True for every row, so the
    ~50W of unexplained draw is inverter idle PLUS the station's own base
    electronics, and the two cannot be separated from that data.

    Sequence: baseline with AC on -> AC off for --minutes -> AC back on.

    Three properties this run needs that a plain log run does not, because it
    deliberately de-energises the fridge:

      * The machine must not sleep. A suspend during the OFF phase skips the
        restore entirely and leaves the fridge unpowered until someone notices.
      * A stalled link must abort *into* the restore path, not past it. The
        2026-08-19 run showed BleakClient reporting the link up while
        notifications had stopped, with no exception ever raised.
      * The restore must be verified by reading ac_on back. Control writes go
        out response=False, so a write into a dead link returns successfully
        and proves nothing.

    Only the baseline ON phase feeds the subtraction. The recovery phase
    catches the post-outage pulldown, where AC load is highest and the fixed
    0.90 inverter-efficiency assumption costs the most, so it is reported as a
    sanity check and excluded from the arithmetic.
    """
    phases = {"before": Phase("AC ON (baseline)"),
              "off": Phase("AC OFF"),
              "after": Phase("AC ON (recovery)")}
    current = {"key": None}
    count = {"n": 0}

    fh = writer = None
    if args.csv:
        fh = open(args.csv, "a", newline="")
        writer = csv.DictWriter(fh, fieldnames=CSV_FIELDS, extrasaction="ignore")
        if fh.tell() == 0:
            writer.writeheader()

    state = {"last_accept": None, "last_soc": None, "crc_mode": None,
             "rejected": 0, "last_status": None}
    min_gap = max(0.4 * args.interval, 0.5)

    def on_status(s):
        now = datetime.datetime.now()
        key = current["key"]
        if key:
            phases[key].add(now, s)
        if writer:
            row = {k: s.get(k) for k in CSV_FIELDS}
            row["timestamp"] = now.isoformat(timespec="seconds")
            row["link_state"] = "ok"
            writer.writerow(row)
            fh.flush()
        if count["n"] % 20 == 0:
            print(HEADER)
        count["n"] += 1
        print(f"{format_row(s)}  | {key}")

    def handle_frame(data, complete):
        if not complete or data[1] != FN_READ_INPUT:
            return
        crc_ok = check_crc(data)
        if crc_ok is False and state["crc_mode"] is True:
            state["rejected"] += 1
            return
        if crc_ok is not None and state["crc_mode"] is None:
            state["crc_mode"] = crc_ok
        status = decode_status(data)
        if sanity_check(status):
            state["rejected"] += 1
            return
        now = datetime.datetime.now()
        if state["last_soc"] is not None and state["last_accept"] is not None:
            dt = (now - state["last_accept"]).total_seconds()
            if dt > 0 and abs(status["battery_pct"] - state["last_soc"]) / dt > MAX_SOC_SLEW_PCT_PER_S:
                state["rejected"] += 1
                return
        if state["last_accept"] is not None:
            if (now - state["last_accept"]).total_seconds() < min_gap:
                return
        state["last_accept"], state["last_soc"] = now, status["battery_pct"]
        state["last_status"] = status
        on_status(status)

    assembler = FrameAssembler(handle_frame, raw=args.raw)

    def notification_handler(_sender, data):
        assembler.feed(bytes(data))

    from bleak import BleakClient

    async def send(client, target, value):
        reg, options = CONTROL_COMMANDS[target]
        frame = build_frame(FN_WRITE_SINGLE, reg, options[value])
        await client.write_gatt_char(WRITE_CHAR_UUID, frame, response=False)

    stall_after = max(args.stall * args.interval, 60.0)

    async def poll_for(client, seconds, key):
        """Poll for a fixed duration, raising LinkStalled if frames dry up."""
        current["key"] = key
        loop = asyncio.get_event_loop()
        started = loop.time()
        end = started + seconds
        while loop.time() < end:
            await client.write_gatt_char(WRITE_CHAR_UUID, read_status_frame(), response=False)
            await asyncio.sleep(args.interval)
            assembler.flush()
            if state["last_accept"] is not None:
                idle = (datetime.datetime.now() - state["last_accept"]).total_seconds()
                if idle > stall_after:
                    raise LinkStalled(f"no valid frame for {idle:.0f}s during '{key}'")
            elif loop.time() - started > stall_after:
                raise LinkStalled(f"no valid frame since connect ({stall_after:.0f}s)")

    async def verify_ac_on(client, timeout=45.0):
        """
        Confirm the AC output is genuinely back on by reading the flag back.
        A status frame accepted *after* the restore write, showing ac_on True,
        is the only evidence that counts.
        """
        mark = datetime.datetime.now()
        loop = asyncio.get_event_loop()
        end = loop.time() + timeout
        while loop.time() < end:
            try:
                await client.write_gatt_char(WRITE_CHAR_UUID, read_status_frame(), response=False)
            except Exception:
                return False
            await asyncio.sleep(max(args.interval, 2.0))
            assembler.flush()
            s = state["last_status"]
            if s is not None and state["last_accept"] > mark and s.get("ac_on"):
                return True
        return False

    async def restore_ac(client):
        """
        Get the AC output back on and prove it, reconnecting if necessary --
        the most likely reason a restore is needed at all is that the link
        died. Returns (ok, client); the client may be a fresh one.
        """
        for attempt in range(1, 4):
            if client is not None:
                try:
                    await send(client, "ac", "on")
                    if await verify_ac_on(client):
                        print(f"  AC output confirmed ON (attempt {attempt}).")
                        return True, client
                    print(f"  ! attempt {attempt}: station did not confirm ac_on")
                except Exception as exc:
                    print(f"  ! attempt {attempt} failed: {exc}")
                try:
                    await client.disconnect()
                except Exception:
                    pass
                client = None
            await asyncio.sleep(3 * attempt)
            try:
                client = BleakClient(args.address, timeout=20.0)
                await client.connect()
                await client.start_notify(NOTIFY_CHAR_UUID, notification_handler)
                print("  reconnected for restore")
            except Exception as exc:
                print(f"  ! reconnect failed: {exc}")
                client = None
        return False, client

    print(f"Connecting to {args.address} ...")
    print(f"Plan: {args.baseline:.0f} min AC on, {args.minutes:.0f} min AC OFF, "
          f"{args.recovery:.0f} min AC on (sanity check, excluded from the maths).")
    print("The fridge is unpowered for the OFF phase only, and restarts afterwards.")
    print(f"Resolution floor: baseline "
          f"{phase_resolution_w(args.baseline, args.capacity):.1f} W, off "
          f"{phase_resolution_w(args.minutes, args.capacity):.1f} W "
          f"(one 0.1% SOC step at {args.capacity:.0f} Wh).")
    print(f"Break-even for imposed cycling is ~{BREAKEVEN_IDLE_W:.0f} W.\n")

    restored = False
    aborted = None
    client = BleakClient(args.address, timeout=20.0)

    with KeepAwake(enabled=not args.no_keep_awake, why="fbot idle test"):
        try:
            await client.connect()
            print(f"Connected. MTU = {getattr(client, 'mtu_size', 'unknown')}")
            await client.start_notify(NOTIFY_CHAR_UUID, notification_handler)
            try:
                await poll_for(client, args.baseline * 60, "before")
                print("\n>>> switching AC OUTPUT OFF\n")
                await send(client, "ac", "off")
                await asyncio.sleep(5)
                await poll_for(client, args.minutes * 60, "off")
            except BaseException as exc:
                # Deliberately BaseException: KeyboardInterrupt must not skip
                # the restore below, and on this command getting the fridge
                # back on matters more than propagating the exception.
                aborted = exc
                print(f"\n!!! measurement aborted: {type(exc).__name__}: {exc}")
            finally:
                print("\n>>> restoring AC OUTPUT ON\n")
                restored, client = await restore_ac(client)

            if restored and aborted is None:
                await asyncio.sleep(5)
                try:
                    await poll_for(client, args.recovery * 60, "after")
                except BaseException as exc:
                    print(f"  ! recovery phase ended early: "
                          f"{type(exc).__name__}: {exc}")
        finally:
            if client is not None:
                try:
                    await client.stop_notify(NOTIFY_CHAR_UUID)
                except Exception:
                    pass
                try:
                    await client.disconnect()
                except Exception:
                    pass
            if fh:
                fh.close()

    if not restored:
        print("\n" + "*" * 72)
        print("*** AC OUTPUT NOT CONFIRMED ON -- CHECK THE P310 FRONT PANEL NOW ***")
        print("*** The fridge may still be unpowered.                           ***")
        print("*" * 72)

    print("\n" + "=" * 72)
    results = {k: phases[k].result(args.capacity) for k in ("before", "off", "after")}
    for key in ("before", "off", "after"):
        r = results[key]
        if not r:
            print(f"{phases[key].label:<20} insufficient samples")
            continue
        print(f"{r['label']:<20} {r['minutes']:5.1f} min  "
              f"SOC {r['soc0']:.1f}->{r['soc1']:.1f}  "
              f"in {r['input_w']:6.1f}W  AC {r['ac_w']:5.1f}W  DC {r['dc_w']:5.1f}W  "
              f"batt {r['batt_w']:+7.1f}W  ->  overhead {r['overhead_w']:5.1f}"
              f" +-{r['resolution_w']:.1f} W  [inverter on {r['ac_on_frac'] * 100:3.0f}% of phase]")
    if results["after"]:
        print("  (recovery phase is a restart sanity check only -- not used below)")
    if state["rejected"]:
        print(f"  {state['rejected']} frame(s) rejected by validation")

    on_r, off_r = results["before"], results["off"]
    if not (on_r and off_r):
        print("\nToo few samples in the baseline or OFF phase to compute anything.")
        print("=" * 72)
        return

    # Phase integrity. Both checks catch silent failures that would still
    # produce a plausible-looking number, which is worse than an error.
    invalid = []
    if on_r["ac_on_frac"] < 0.98:
        invalid.append(
            f"the AC output was ON for only {on_r['ac_on_frac'] * 100:.0f}% of the "
            f"baseline phase.\n  With no load plugged in, the station may auto-off "
            f"its own output. The baseline\n  then measures base load, not base + "
            f"inverter idle, and the difference below\n  collapses toward zero for a "
            f"reason that has nothing to do with the inverter."
        )
    if off_r["ac_on_frac"] > 0.02:
        invalid.append(
            f"the AC output was still ON for {off_r['ac_on_frac'] * 100:.0f}% of the "
            f"OFF phase.\n  The switch-off did not take effect."
        )

    idle = on_r["overhead_w"] - off_r["overhead_w"]
    # The two phases' quantisation errors are independent, so they add in
    # quadrature rather than linearly.
    band = math.hypot(on_r["resolution_w"], off_r["resolution_w"])
    print("-" * 72)
    print(f"Base overhead, AC output off : {off_r['overhead_w']:.1f} W")
    print(f"Total overhead, AC output on : {on_r['overhead_w']:.1f} W  (baseline phase)")
    print(f"INVERTER IDLE (difference)   : {idle:.1f} +- {band:.1f} W")
    print(f"\nCapacity assumed: {args.capacity:.0f} Wh (--capacity to change). "
          f"batt_w scales\nlinearly with it, so a 5% capacity error moves each "
          f"overhead figure ~5%.")

    if invalid:
        print()
        for msg in invalid:
            print(f"*** RESULT NOT VALID: {msg}")
        print("\nRe-run with a real AC load plugged in (the fridge) so the station\n"
              "keeps its inverter energised for the whole baseline phase.")
        print("=" * 72)
        return

    print()
    if idle - band > 2 * BREAKEVEN_IDLE_W:
        print("Clearly above break-even. Duty-cycling the inverter recovers real\n"
              "energy; Strategy B (imposed run/rest blocks) is worth building.\n"
              "Next: the overnight log, to choose between Strategy A and B.")
    elif idle + band < BREAKEVEN_IDLE_W / 2:
        print("Clearly below break-even. The overhead is the station's own base\n"
              "load, not the inverter, so no arbiter can recover it. See\n"
              "ANALYSIS-2026-08-20 section 5 -- the platform question reopens.")
    elif on_r["ac_w"] < 5.0:
        # Already ran with no AC load, so the only lever left is duration.
        print(f"AMBIGUOUS: the result straddles the ~{BREAKEVEN_IDLE_W:.0f} W "
              f"break-even.\nThe baseline already had no AC load "
              f"({on_r['ac_w']:.1f} W), so the efficiency\nassumption is not the "
              f"limit -- the 0.1% SOC step is. The only lever left is\nlonger "
              f"phases: --baseline 180 --minutes 180 would halve the band to "
              f"~{math.hypot(phase_resolution_w(180, args.capacity), phase_resolution_w(180, args.capacity)):.1f} W.")
    else:
        print(f"AMBIGUOUS: the result straddles the ~{BREAKEVEN_IDLE_W:.0f} W "
              f"break-even.\nRepeat with the fridge unplugged and longer phases "
              f"before deciding.\nUnplugging removes the AC-load term from both "
              f"phases entirely.")
    if aborted is not None:
        print("\nNOTE: the run aborted early. Treat the figures above as partial.")
    print("=" * 72)


async def cmd_set(args):
    if args.target == "charge-limit":
        watts = int(args.value)
        if watts not in (300, 500, 700, 900, 1100):
            raise SystemExit("charge-limit must be one of 300 500 700 900 1100")
        reg, val = REG_AC_CHARGE_LIMIT, (watts - 300) // 200 + 1
    elif args.target in ("threshold-charge", "threshold-discharge"):
        pct = float(args.value)
        if not 10.0 <= pct <= 100.0:
            raise SystemExit("threshold must be between 10 and 100 percent")
        reg = REG_THRESHOLD_CHARGE if args.target == "threshold-charge" else REG_THRESHOLD_DISCHARGE
        val = int(pct * 10)
    elif args.target in CONTROL_COMMANDS:
        reg, options = CONTROL_COMMANDS[args.target]
        if args.value not in options:
            raise SystemExit(f"{args.target} accepts: {' '.join(options)}")
        val = options[args.value]
    else:
        raise SystemExit(f"unknown target '{args.target}'")

    frame = build_frame(FN_WRITE_SINGLE, reg, val)
    print(f"Command: {args.target} -> {args.value}   register {reg} = {val}")
    print(f"Frame:   {frame.hex()}")

    if not args.i_understand:
        print(
            "\nRefusing to send without --i-understand.\n"
            "Turning DC or USB off will cut power to anything running from those\n"
            "ports, and threshold changes alter the battery management settings."
        )
        return

    args._write_frame = frame
    printed = {"n": 0}

    def on_status(s):
        if printed["n"] % 20 == 0:
            print(HEADER)
        printed["n"] += 1
        print(format_row(s))

    await run_session(args, on_status, duration=6)


def cmd_selftest(_args):
    """Verify framing without any hardware."""
    ok = True

    # Known-good Modbus CRC test vector. The value 0x80B8 is conventionally
    # quoted as "B880" because standard Modbus RTU sends the low byte first.
    # This device sends the HIGH byte first, hence 0x80 then 0xB8 on the wire.
    crc = crc16_modbus(bytes([0x01, 0x04, 0x02, 0xFF, 0xFF]))
    print(f"CRC of 0104 02FFFF         = 0x{crc:04X}  (expect 0x80B8)")
    ok &= crc == 0x80B8

    status = read_status_frame()
    print(f"read status frame          = {status.hex()}")
    ok &= status[:6] == bytes([0x11, 0x04, 0x00, 0x00, 0x00, 0x50])

    settings = read_settings_frame()
    print(f"read settings frame        = {settings.hex()}")
    ok &= settings[:6] == bytes([0x11, 0x03, 0x00, 0x00, 0x00, 0x50])

    ac_on = build_frame(FN_WRITE_SINGLE, REG_AC_CONTROL, 1)
    print(f"AC on frame                = {ac_on.hex()}")
    ok &= ac_on[:6] == bytes([0x11, 0x06, 0x00, 0x1A, 0x00, 0x01])

    # Synthetic status frame: 80 registers, battery 87.3%, AC out 34W (fridge),
    # total out 60W (so 26W on the 12V side), AC+DC on, charge limit 700W.
    payload = bytearray([SLAVE_ADDR, FN_READ_INPUT, 0xA0, 0x00, 0x00, 0x00])
    payload += bytearray(NUM_REGISTERS * 2)

    def poke(idx, value):
        off = REG_DATA_OFFSET + idx * 2
        payload[off] = (value >> 8) & 0xFF
        payload[off + 1] = value & 0xFF

    poke(56, 873)
    poke(39, 60)  # total output
    poke(20, 34)  # AC output only
    poke(21, 2285)  # AC input voltage 228.5V
    poke(6, 750)
    poke(41, STATE_BITS["ac"] | STATE_BITS["dc"])
    poke(2, 3)
    decoded = decode_status(bytes(payload))
    print(
        f"decoded synthetic frame    = SOC {decoded['battery_pct']}% "
        f"AC {decoded['ac_output_w']}W DC {decoded['dc_usb_load_w']}W "
        f"tot {decoded['total_output_w']}W in {decoded['input_w']}W "
        f"Vin {decoded['ac_in_voltage']}V "
        f"AC={decoded['ac_on']} DC={decoded['dc_on']} USB={decoded['usb_on']} "
        f"limit {decoded['ac_charge_limit_w']}W"
    )
    ok &= decoded["battery_pct"] == 87.3
    ok &= decoded["ac_output_w"] == 34
    ok &= decoded["total_output_w"] == 60
    ok &= decoded["dc_usb_load_w"] == 26
    ok &= decoded["ac_in_voltage"] == 228.5
    ok &= decoded["ac_on"] is True and decoded["usb_on"] is False
    ok &= decoded["ac_charge_limit_w"] == 700
    ok &= sanity_check(decoded) is None

    print(f"terminal row               = {format_row(decoded)}")

    # Validation must reject the failure modes seen in the 2026-08-18 log.
    bad = dict(decoded)
    bad["battery_pct"] = 143.2
    ok &= sanity_check(bad) is not None
    bad = dict(decoded)
    bad["ac_out_voltage"] = 812.0
    ok &= sanity_check(bad) is not None
    print("sanity limits reject corrupt frames = OK")

    # CRC handling: body-only frames return None, a correct tail returns True.
    body = bytes(payload)
    assert check_crc(body) is None
    c = crc16_modbus(body)
    ok &= check_crc(body + bytes([(c >> 8) & 0xFF, c & 0xFF])) is True
    ok &= check_crc(body + bytes([0xDE, 0xAD])) is False
    print("CRC detection (absent/valid/invalid) = OK")

    print("\nSELFTEST PASS" if ok else "\nSELFTEST FAIL")
    return 0 if ok else 1


# ---------------------------------------------------------------------------


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def add_common(sp, needs_address=True):
        if needs_address:
            sp.add_argument("--address", required=True, help="BLE address (or UUID on macOS)")
        sp.add_argument("--interval", type=float, default=10.0, help="poll interval, seconds")
        sp.add_argument("--raw", action="store_true", help="dump raw notification hex")
        sp.add_argument("--verbose", action="store_true", help="report dropped frames")
        sp.add_argument("--stall", type=float, default=6.0,
                        help="reconnect after this many missed poll intervals")

    sp = sub.add_parser("selftest", help="verify framing, no hardware needed")

    sp = sub.add_parser("scan", help="find the power station")
    sp.add_argument("--name", help="substring of the advertised name")
    sp.add_argument("--timeout", type=float, default=10.0)

    sp = sub.add_parser("services", help="dump the GATT table (debugging)")
    sp.add_argument("--address", required=True)

    sp = sub.add_parser("monitor", help="live status to the terminal")
    add_common(sp)

    sp = sub.add_parser("log", help="append status to a CSV file")
    add_common(sp)
    sp.add_argument("--csv", default="fbot_log.csv")
    sp.add_argument("--hours", type=float, help="stop automatically after N hours")
    sp.add_argument("--no-keep-awake", action="store_true",
                    help="do not suppress system sleep during the run")

    sp = sub.add_parser("idle-test", help="measure inverter idle by cycling the AC output")
    add_common(sp)
    # 75/75 because the 0.1%% SOC step is the binding constraint: at 3900 Wh a
    # 20 min phase resolves no better than 11.7 W, which is exactly the
    # break-even the test exists to decide. 75 min puts the floor at ~3.1 W.
    sp.add_argument("--baseline", type=float, default=75.0, help="minutes with AC on before")
    sp.add_argument("--minutes", type=float, default=75.0, help="minutes with AC OFF")
    sp.add_argument("--recovery", type=float, default=20.0,
                    help="minutes with AC on after (sanity check, not used in the maths)")
    sp.add_argument("--capacity", type=float, default=3900.0, help="usable Wh for the SOC balance")
    sp.add_argument("--csv", help="also append samples to this CSV")
    sp.add_argument("--no-keep-awake", action="store_true",
                    help="do not suppress system sleep during the run")

    sp = sub.add_parser("registers", help="dump all 160 registers, both banks")
    add_common(sp)
    sp.add_argument("--watch", type=float, default=0.0,
                    help="keep sweeping for N seconds and report which registers move")
    sp.add_argument("--all", action="store_true",
                    help="also list unnamed registers that stay at zero")
    sp.add_argument("--csv", help="write every sweep as a wide row (reg0..reg79)")

    sp = sub.add_parser("set", help="send a control command")
    sp.add_argument("target", help="ac | dc | usb | light | silent | beep | charge-limit | threshold-charge | threshold-discharge")
    sp.add_argument("value", help="on/off, or a number for the limit/threshold targets")
    add_common(sp)
    sp.add_argument("--i-understand", action="store_true", help="required to actually transmit")

    args = p.parse_args()

    if args.cmd == "selftest":
        sys.exit(cmd_selftest(args))

    handlers = {"scan": cmd_scan, "services": cmd_services, "monitor": cmd_monitor,
                "log": cmd_log, "set": cmd_set, "idle-test": cmd_idle_test,
                "registers": cmd_registers}
    try:
        asyncio.run(handlers[args.cmd](args))
    except KeyboardInterrupt:
        print("\nStopped.")
    except ImportError:
        raise SystemExit("bleak is not installed. Run: pip install bleak")


if __name__ == "__main__":
    main()
