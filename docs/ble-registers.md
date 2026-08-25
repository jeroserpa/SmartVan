# P310 BLE register map

What is actually reachable over Bluetooth on the AFERIY P310 (SYD-N051-DC-V1.5,
SYDPOWER OEM), and how much of it is still unnamed.

Source: [ESP-FBot](https://github.com/Ylianst/ESP-FBot) — `components/fbot/fbot.cpp`
for the parsing, `internals/README.md` for the protocol teardown and two captured
frames. Apache-2.0. Everything below marked `?` is inference from those captures,
not measurement on our unit.

---

## 1. Transport

Modbus RTU tunnelled over one GATT service. No authentication of any kind.

| | |
|---|---|
| Service | `0000a002-0000-1000-8000-00805f9b34fb` |
| Write (client → station) | `0000c304-...` |
| Notify (station → client) | `0000c305-...` |
| Slave address | `0x11` |
| Byte order | big-endian |
| CRC | CRC-16/Modbus, appended **high byte first** (the opposite of standard Modbus RTU framing) |

Four function codes:

| Fn | Meaning | Frame |
|---|---|---|
| `0x03` | read 80 **holding** registers (settings) | `11 03 00 00 00 50 CRC CRC` |
| `0x04` | read 80 **input** registers (live status) | `11 04 00 00 00 50 CRC CRC` |
| `0x06` | write one register | `11 06 REG_H REG_L VAL_H VAL_L CRC CRC` |
| `0x07` | set WiFi credentials — non-standard, variable length: `11 07 SSID_LEN PASS_LEN <ssid> <pass> CRC CRC` |

Both reads return the same 168-byte shape: 6 header bytes echoing the request,
then 80 registers × 2 bytes, then the CRC. Register *N* lives at byte offset
`6 + 2N`.

**So every poll already carries 160 registers, and ESP-FBot decodes 28 of them.**
The other 132 are free — no extra BLE traffic, no extra load on the node.

---

## 2. Two hazards, stated once

1. **Writes can brick the station.** The ESP-FBot author put his own P310 into a
   7–8 s reboot loop with a bad setting sent over BLE, and could not recover it —
   Bluetooth never came up long enough to write the register back. He ended up
   cutting the ESP32's TX pin to get a working (radio-less) power station. Reads
   (`0x03`/`0x04`) are safe and unlimited; **never fuzz `0x06`.** The holding
   bank contains BMS protection thresholds.
2. **No authentication.** Anyone within BLE range can write any register. This
   is a property of the product, not of our design, but it is worth knowing that
   the fridge's power supply is open to the car park.

---

## 3. Input registers — function `0x04` (live status)

Names in **bold** are corrected against ESP-FBot from our own 2026-08-19 log
analysis; the ESP-FBot name is given where it differs and is wrong.

| Reg | Meaning | Scale | Confidence |
|---|---|---|---|
| 2 | AC charge level, 1–5 → 300/500/700/900/1100 W | raw | ESP-FBot |
| 3 | AC input power | W | ESP-FBot |
| 4 | DC/solar input power | W | ESP-FBot |
| 6 | Total input power | W | ESP-FBot |
| 18 | AC output voltage | ×0.1 V | ESP-FBot |
| 19 | AC output frequency | ×0.1 Hz | ESP-FBot |
| 20 | **AC output power only** (FBot: `total_power`) | W | `MEASURED` 2026-08-19 |
| 21 | **AC input voltage** (FBot: `system_power`) | ×0.1 V | `UNVERIFIED` — reads 2285 whenever AC input is present, 14–24 when not |
| 22 | AC input frequency | ×0.01 Hz | ESP-FBot |
| 30, 31 | USB-A1, USB-A2 power | ×0.1 W | ESP-FBot |
| 34–37 | USB-C1..C4 power | ×0.1 W | ESP-FBot |
| 39 | **Total output, AC+DC+USB** (FBot: `output_power`) | W | `MEASURED` 2026-08-19 |
| 41 | State flags: b9 USB, b10 DC, b11 AC, b12 light | bitmask | ESP-FBot |
| 53, 55 | Expansion battery S1/S2 percent, `/10 − 1`; 0 = absent | % | ESP-FBot |
| 56 | Main battery | ×0.1 % | ESP-FBot |
| 58 | Time to full | min | ESP-FBot |
| 59 | Remaining time | min | **junk on our firmware** — reads 0 or 42679 |

`reg39 − reg20` splits the load into AC-side and DC/USB-side for free. That is
the term the arbiter needs and it costs nothing extra.

### Unnamed but non-zero in the ESP-FBot capture

That capture was taken from a **boot-looping unit**, so treat the values as
hints about *which* registers carry data, not as correct readings.

| Reg | Value | Guess |
|---|---|---|
| 47 | `0x3000` | bitfield — only bits 12–13 set. Fault/alarm mask? |
| 48 | `0x4000` | bitfield — only bit 14 set. Same family as 47 |
| 54 | 788 | sits between the S1 (53) and S2 (55) slots. Pack voltage ×0.1 → 78.8 V? Cycle count? |
| 62 | `0x00FF` | sentinel, likely "slot absent" |
| 63 | `0xFFFF` | sentinel, likely "slot absent" |

**Everything else in the input bank read zero.** With the unit in a fault state
and nothing plugged in, that is expected for the power channels — but it means
the capture cannot tell us whether e.g. registers 5, 7–17, 23–29 populate under
load. Only a sweep on our own station answers that.

---

## 4. Holding registers — function `0x03` (settings)

| Reg | Meaning | Values |
|---|---|---|
| 13 | AC charge limit | 1–5 → 300…1100 W |
| 27 | Light mode | 0 off, 1 on, 2 SOS, 3 flashing |
| 56 | Key sound | 0/1 |
| 57 | AC silent mode | 0/1 |
| 66 | Discharge threshold | ×0.1 % (write range 0–500) |
| 67 | Charge threshold | ×0.1 % (write range 100–1000) |

### Unnamed but non-zero — this is where the interesting stuff is

The holding bank came back far more populated than the input bank: **30 of its
80 registers were non-zero, and only two of those (13 and 67) are ones ESP-FBot
names.** The input bank had 9 non-zero. Grouped by what the values look like:

| Reg | Value | Guess |
|---|---|---|
| 18 | 115 | mains region setting — 115 V |
| 22 | 233 | mains region setting — 230 V (or measured nominal) |
| 47, 48, 49, 50 | 38, 24, 27, 36 | **four plausible °C readings.** If these are internal thermistors — inverter, MOSFET bank, pack, ambient — that is the single most valuable find available here (see §6) |
| 60, 61 | 480, 480 | plausible auto-off timers, minutes (8 h) |
| 62 | 300 | plausible auto-off timer, minutes (5 h) |
| 14, 16 | 1500, 2000 | limits? 1500/2000 W thresholds |
| 17, 20 | 20, 20 | paired with 14/16? |
| 19, 21 | 1600, 768 | — |
| 11, 12 | `0x0600`, 9 | version/build fields? |
| 30, 31, 32 | 281, 266, 515 | version fields? (2.81 / 2.66 / 2.3) |
| 40, 41 | 592, `0x0F04` | — |
| 2, 3 | `0x00FF`, `0xFFFF` | sentinels, same pattern as input 62/63 |
| 5, 37, 59, 68 | 1, 3, 3, 5 | small enums |

---

## 5. How to identify the rest — `tools/fbot_probe.py registers`

Added for exactly this. It reads both banks, prints all 160 registers with
min/max/change-count, and never transmits a `0x06`.

Snapshot — which registers are populated at all on our unit:

```bash
python tools/fbot_probe.py registers --address AA:BB:CC:DD:EE:FF
```

Watch mode — this is the part that actually identifies them. Sweep for a while
and change **one thing at a time**; the `chg` column and the min/max spread show
which unnamed registers track the action:

```bash
python tools/fbot_probe.py registers --address AA:BB:CC:DD:EE:FF --watch 900 --csv regs.csv
```

`--all` also lists the permanently-zero unnamed registers. The CSV is one wide
row per sweep (`reg0`…`reg79`) so the correlation can be done offline against
`docs/measurements.md` timestamps.

**Stimuli worth running, in order of what they would tell us:**

| Action | Expect to move |
|---|---|
| Inverter off → on, no load | AC-side registers; anything thermal starting to climb |
| Kettle or induction plate on for 5 min | power registers, and the °C candidates 47–50 if they are real |
| Let the unit sit idle 30 min after that load | the same °C candidates decaying — a decay curve is what distinguishes a temperature from a constant |
| Alternator charging at 800 W | AC-input registers, charge-limit registers, thermal |
| Solar only | DC-input registers |
| Expansion battery, if ever fitted | 53–55, 62, 63 |

A register that never moves across all of that is a constant (model code,
firmware version, calibration) and can be dropped.

### The one sweep worth running first: hunting an idle-power register

**No register found so far reports the station's own consumption.** Every power
register is either an external input (3, 4, 6) or an external output (20, 39,
30–37). ESP-FBot's `system_power` (reg 21) looks like the obvious candidate from
its name — and `CLAUDE.md` §8.2 used to say so — but it is not a power at all;
it reads 2285 with AC input present and 14–24 without, regardless of whether the
station is charging at 800 W or 400 W. That is why `idle-test` has to recover the
~50 W of overhead from the SOC balance instead of reading it.

There is a clean discriminating test for whether such a register exists at all:

> Inverter **on, nothing plugged in**, DC and USB off, no charging. External
> output is genuinely zero — regs 20 and 39 will read ~0 — while the battery is
> draining at roughly 50 W. Sweep for 15 min in that state.
>
> **Any register sitting near 50, or near 500 at ×0.1, is the internal-draw
> register.** Then turn the inverter off and confirm it collapses to the
> station's base electronics.

That single run is worth more than the rest of the stimulus table: it would turn
`idle-test`'s hour-long SOC-balance measurement into a direct reading, and give
the arbiter a live idle figure instead of a one-off constant.

---

## 6. Why this matters to the project

Not curiosity — three concrete uses:

1. **Internal temperature (holding 47–50, if confirmed).** §6 of `CLAUDE.md`
   sizes the whole sleep-mode argument around the P310's fan cycling under the
   bed from 35 W of inverter idle heat. If the station reports its own internal
   temperature, the fan-noise problem becomes *measurable* instead of inferred,
   and the arbiter could bias cycles away from the hot state directly.
2. **Anything resembling a fault or protection register (input 47, 48).** §5.2
   fails toward powered on the basis that a silent fridge shutdown is the
   expensive failure. A real fault bitfield would let the display say *why*
   instead of just showing a dropped BLE link.
3. **Confirming input reg 21 is AC input voltage.** It is currently logged
   `UNVERIFIED` and is the only shore/alternator supply-quality signal available
   without extra hardware. A meter on the incoming AC settles it in a minute.

Two things that do **not** need to come from here: engine state (§9 Phase 4 —
AC-input power fails in both directions and must come from `van-vehicle`), and
fridge cooling state (there is no electrical signature to follow — see
`docs/ANALYSIS-2026-08-20.md` §4).

---

## 7. Writable registers (for completeness — treat as a do-not-touch list)

| Reg | Control | Valid |
|---|---|---|
| 13 | AC charge limit | 1–5 |
| 24 | USB output | 0/1 |
| 25 | DC output | 0/1 |
| 26 | **AC output — the one the arbiter drives** | 0/1 |
| 27 | Light | 0–3 |
| 56 | Key sound | 0/1 |
| 57 | AC silent | 0/1 |
| 66 | Discharge threshold | 0–500 |
| 67 | Charge threshold | 100–1000 |

`fbot_probe.py set` exposes only these, and requires `--i-understand`.
Registers outside this list are deliberately not writable from the tool.
