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
    python fbot_probe.py monitor --address ... --raw
    python fbot_probe.py log --address ... --csv fridge.csv --interval 10
    python fbot_probe.py set ac on --address ... --i-understand

IMPORTANT
---------
* The station accepts ONE BLE connection. Close the BrightEMS app first.
* Only the named commands below are supported. Arbitrary register writes are
  deliberately NOT exposed: this device's holding registers include battery
  protection thresholds, and fuzzing them is a bad idea.
"""

import argparse
import asyncio
import csv
import datetime
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
EXPECTED_FRAME_LEN = REG_DATA_OFFSET + NUM_REGISTERS * 2  # 166

# Live status register map: name -> (register index, scale)
STATUS_MAP = [
    ("charge_level_raw", 2, 1.0),
    ("ac_input_w", 3, 1.0),
    ("dc_input_w", 4, 1.0),
    ("input_w", 6, 1.0),
    ("ac_out_voltage", 18, 0.1),
    ("ac_out_freq", 19, 0.1),
    ("total_w", 20, 1.0),
    ("system_w", 21, 1.0),
    ("ac_in_freq", 22, 0.01),
    ("usb_a1_w", 30, 0.1),
    ("usb_a2_w", 31, 0.1),
    ("usb_c1_w", 34, 0.1),
    ("usb_c2_w", 35, 0.1),
    ("usb_c3_w", 36, 0.1),
    ("usb_c4_w", 37, 0.1),
    ("output_w", 39, 1.0),
    ("state_flags", 41, 1.0),
    ("battery_pct", 56, 0.1),
    ("time_to_full_min", 58, 1.0),
    ("remaining_min", 59, 1.0),
]

# CSV column order for logging
CSV_FIELDS = [
    "timestamp",
    "battery_pct",
    "input_w",
    "ac_input_w",
    "dc_input_w",
    "output_w",
    "system_w",
    "total_w",
    "ac_out_voltage",
    "remaining_min",
    "ac_on",
    "dc_on",
    "usb_on",
    "light_on",
]


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
    return out


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
                self.on_frame(bytes(self.buf), complete=False)
            self.buf = bytearray(chunk)
        else:
            self.buf.extend(chunk)

        if len(self.buf) >= EXPECTED_FRAME_LEN:
            self.on_frame(bytes(self.buf), complete=True)
            self.buf = bytearray()


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


async def run_session(args, on_status, on_settings=None, duration=None):
    """Connect, subscribe, and poll status at args.interval until interrupted."""
    from bleak import BleakClient

    assembler_state = {}

    def handle_frame(data, complete):
        if not complete:
            print(f"  ! discarded {len(data)} incomplete bytes")
            return
        if data[1] == FN_READ_INPUT:
            on_status(decode_status(data))
        elif data[1] == FN_READ_HOLDING and on_settings:
            on_settings(decode_settings(data))
        elif data[1] == FN_WRITE_SINGLE:
            print(f"  write acknowledged: {data[:8].hex()}")

    assembler = FrameAssembler(handle_frame, raw=args.raw)

    def notification_handler(_sender, data):
        assembler.feed(bytes(data))

    print(f"Connecting to {args.address} ...")
    async with BleakClient(args.address, timeout=20.0) as client:
        print("Connected. Subscribing to notifications.")
        await client.start_notify(NOTIFY_CHAR_UUID, notification_handler)

        if on_settings:
            await client.write_gatt_char(WRITE_CHAR_UUID, read_settings_frame(), response=False)
            await asyncio.sleep(1.0)

        if getattr(args, "_write_frame", None) is not None:
            print(f"Sending control frame: {args._write_frame.hex()}")
            await client.write_gatt_char(WRITE_CHAR_UUID, args._write_frame, response=False)
            await asyncio.sleep(1.5)

        started = asyncio.get_event_loop().time()
        while True:
            await client.write_gatt_char(WRITE_CHAR_UUID, read_status_frame(), response=False)
            await asyncio.sleep(args.interval)
            if duration and asyncio.get_event_loop().time() - started > duration:
                break

        await client.stop_notify(NOTIFY_CHAR_UUID)
    return assembler_state


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


async def cmd_monitor(args):
    def on_status(s):
        ts = datetime.datetime.now().strftime("%H:%M:%S")
        outs = " ".join(
            f"{k.upper()}={'ON ' if s.get(f'{k}_on') else 'off'}" for k in ("ac", "dc", "usb")
        )
        print(
            f"[{ts}] SOC {s.get('battery_pct')}%  "
            f"in {s.get('input_w')}W  out {s.get('output_w')}W  "
            f"sys {s.get('system_w')}W  {outs}  "
            f"rem {s.get('remaining_min')}min"
        )

    def on_settings(cfg):
        print(f"  settings: {cfg}")

    await run_session(args, on_status, on_settings)


async def cmd_log(args):
    fh = open(args.csv, "a", newline="")
    writer = csv.DictWriter(fh, fieldnames=CSV_FIELDS, extrasaction="ignore")
    if fh.tell() == 0:
        writer.writeheader()
    count = {"n": 0}

    def on_status(s):
        row = {k: s.get(k) for k in CSV_FIELDS}
        row["timestamp"] = datetime.datetime.now().isoformat(timespec="seconds")
        writer.writerow(row)
        fh.flush()
        count["n"] += 1
        print(
            f"\r{count['n']} rows | SOC {s.get('battery_pct')}% "
            f"out {s.get('output_w')}W AC={'ON' if s.get('ac_on') else 'off'}   ",
            end="",
            flush=True,
        )

    try:
        await run_session(args, on_status)
    finally:
        fh.close()
        print(f"\nWrote {count['n']} rows to {args.csv}")


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
    await run_session(args, lambda s: print(f"  state now: AC={'ON' if s.get('ac_on') else 'off'} "
                                            f"DC={'ON' if s.get('dc_on') else 'off'} "
                                            f"out={s.get('output_w')}W"), duration=6)


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

    # Synthetic status frame: 80 registers, battery 87.3%, output 412W, AC+DC on
    payload = bytearray([SLAVE_ADDR, FN_READ_INPUT, 0xA0, 0x00, 0x00, 0x00])
    payload += bytearray(NUM_REGISTERS * 2)

    def poke(idx, value):
        off = REG_DATA_OFFSET + idx * 2
        payload[off] = (value >> 8) & 0xFF
        payload[off + 1] = value & 0xFF

    poke(56, 873)
    poke(39, 412)
    poke(6, 750)
    poke(41, STATE_BITS["ac"] | STATE_BITS["dc"])
    poke(2, 3)
    decoded = decode_status(bytes(payload))
    print(
        f"decoded synthetic frame    = SOC {decoded['battery_pct']}% "
        f"out {decoded['output_w']}W in {decoded['input_w']}W "
        f"AC={decoded['ac_on']} DC={decoded['dc_on']} USB={decoded['usb_on']} "
        f"limit {decoded['ac_charge_limit_w']}W"
    )
    ok &= decoded["battery_pct"] == 87.3
    ok &= decoded["output_w"] == 412
    ok &= decoded["ac_on"] is True and decoded["usb_on"] is False
    ok &= decoded["ac_charge_limit_w"] == 700

    print("\nSELFTEST PASS" if ok else "\nSELFTEST FAIL")
    return 0 if ok else 1


# ---------------------------------------------------------------------------


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    def add_common(sp, needs_address=True):
        if needs_address:
            sp.add_argument("--address", required=True, help="BLE address (or UUID on macOS)")
        sp.add_argument("--interval", type=float, default=2.0, help="poll interval, seconds")
        sp.add_argument("--raw", action="store_true", help="dump raw notification hex")

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

    sp = sub.add_parser("set", help="send a control command")
    sp.add_argument("target", help="ac | dc | usb | light | silent | beep | charge-limit | threshold-charge | threshold-discharge")
    sp.add_argument("value", help="on/off, or a number for the limit/threshold targets")
    add_common(sp)
    sp.add_argument("--i-understand", action="store_true", help="required to actually transmit")

    args = p.parse_args()

    if args.cmd == "selftest":
        sys.exit(cmd_selftest(args))

    handlers = {"scan": cmd_scan, "services": cmd_services, "monitor": cmd_monitor,
                "log": cmd_log, "set": cmd_set}
    try:
        asyncio.run(handlers[args.cmd](args))
    except KeyboardInterrupt:
        print("\nStopped.")
    except ImportError:
        raise SystemExit("bleak is not installed. Run: pip install bleak")


if __name__ == "__main__":
    main()
