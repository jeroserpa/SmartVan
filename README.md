# van-supervisor

ESPHome energy and services supervisor for a van conversion. No router, no Home
Assistant, no internet — in the van or during provisioning.

`CLAUDE.md` is the source of truth for why any of this is shaped the way it is.
This file is just how to build and test it.

## What exists

| Path | What it is |
|---|---|
| `components/ac_arbiter/arbiter_core.{h,cpp}` | The AC arbitration state machine. Plain C++, zero ESPHome headers, host-testable. |
| `components/ac_arbiter/ac_arbiter.{h,cpp}` | ESPHome adapter: reads sensors, tracks their freshness, writes exactly one switch. No control logic. |
| `test/` | Host-compiled unit tests for the state machine. |
| `nodes/van-core.yaml` | Phase 1 node: P310 BLE link, fridge probe, arbiter, manual button, display, SoftAP. |
| `nodes/van-core-soak.yaml` | The 24h BLE + SoftAP + web + display + SD stability test. **Run this first.** |
| `common/base.yaml` | Logger, OTA, web server, diagnostics. Shared by every node. |
| `tools/fbot_probe.py` | Laptop-side BLE client. Protocol validation and 24h logging with no microcontroller. |

## Test the arbiter (no hardware needed)

```bash
cd test && make
```

(On Windows with MSYS2/mingw the binary is `mingw32-make`; the Makefile itself
is plain and portable.)

Builds with `g++ -Wall -Wextra -Werror` and runs ~50 assertions in under a
second: fail-safe paths, thermostat hysteresis, anti-short-cycle, sleep-mode
coasting, the manual timer, surplus hysteresis, the Phase 4 drive inhibit, and
the 49.7-day `millis()` rollover.

Two real bugs have already been caught here rather than in a van — see
`docs/decisions.md` D-04 and D-05. Every change to the state machine gets a test
in the same commit.

## Build the firmware

```bash
pip install esphome
```

Then, **from the `nodes/` directory** (ESPHome resolves `!secret` next to the
config file, not from the repo root):

```bash
cd nodes && cp secrets.yaml.example secrets.yaml
```

```bash
cd nodes && esphome compile van-core.yaml
```

## Flashing and OTA, without a router

Everything below runs offline once compiled, but **compiling needs internet** —
external components come from GitHub and the fonts come from Google Fonts.

1. Compile at home, on a normal network.
2. Join `van-core`'s SoftAP (the laptop then has no internet — this is why step
   1 is separate).
3. `esphome upload van-core.yaml --device 192.168.4.1`

Do not discover the ordering of those steps in a car park.

## Before it goes in the van

1. Run `van-core-soak.yaml` for 24h with the inverter permanently on. It proves
   the BLE link survives the other loads *and* produces the duty-cycle dataset
   that every saving estimate in `CLAUDE.md` depends on.
2. Fill in the two DS18B20 addresses in `van-core.yaml` from the 1-Wire scan
   logged on first boot. They are placeholders.
3. Calibrate the fridge probe against a thermometer in a glass of water.
4. Test the fail-safe path deliberately: pull the BLE antenna, unplug the probe,
   confirm the inverter ends up **ON** in both cases.
