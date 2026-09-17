# Soak #2 — probes, fridge plug, PSRAM log

Firmware: `nodes/van-core-probes.yaml` (van-core) and `nodes/van-fridge-plug.yaml`
(the Athom plug). Analysis: `tools/soak_report.py`.
Read-only toward the P310. The inverter stays on and the fridge runs on its own
thermostat at its **default** setting. That makes this run the baseline for
everything that comes later.

## 1. Wiring

### 1-Wire (both probes on one bus)

```
 1.47B header                                  probes (in parallel)
 ─────────────                                 ────────────────────
 3V3  ──────────┬──────────────────────────────  VDD   (red)
                │
               4.7k
                │
 GPIO4 ─────────┴──────────────────────────────  DATA  (yellow / white)
 GND  ──────────────────────────────────────────  GND   (black)
```

- **3V3, not 5V.** The DS18B20 runs happily at 3.3 V and DATA must never
  exceed the S3's 3.3 V.
- **One 4.7 kΩ pull-up in total**, at the board end, DATA to 3V3. Not one per
  probe.
- **3-wire mode.** VDD is connected on both probes; do not leave it floating or
  tie it to GND (that is parasitic mode, and the config does not use it).
- **Check the colours with a meter or the seller's listing before powering.**
  Red/black/yellow is the usual code, but some cheap probes use
  red/yellow/green or swap DATA and GND. A reversed DS18B20 gets hot within
  seconds: after first power-up, touch the probe tip.
- **GPIO4 must be on the header.** It is the pin the real node uses (van-core.yaml,
  wiring.md). Check the silkscreen. If it is not broken out, use any free pin
  that is not 0, 3, 45, 46 (strapping pins), 14–16 (SD), 39–42 (LCD) or 47–48
  (IMU), and change `pin_onewire` in the YAML to match.
- Join the two probes at a small terminal block or with Wagos near the board.
  A star layout with two runs of a few metres is fine at this bus speed.

### Probe placement

| Probe | Where | Why |
|---|---|---|
| Fridge | **Interior side wall, mid-height**, glued or taped flat, foam pad over it | Where the real node's probe will live, so the thresholds you set from this run still apply. **Not** the back wall: that is the evaporator |
| Cabin | Free air near the fridge, **not** on the condenser exhaust or the P310 | It is the ambient that drives the heat leak |

**Gasket crossing.** Do not pinch the round probe cable in the door for a
multi-day run. It holds the gasket open, and the resulting leak is exactly
what this run is trying to measure (§4 in the report). Splice to about 15 cm of
flat cable across the gasket (CLAUDE.md §2). If that has to wait, write down
that the run was done with a round-cable crossing.

### Fridge plug

```
P310 AC outlet ── Athom plug (van-fridge-plug firmware) ── fridge
```

This isolates the fridge's own power, where `out_w` also includes cooking and
everything else on the AC bus. Flash `van-fridge-plug.yaml` **before** putting
it in the fridge's supply. The heater firmware turns the relay off at boot and
after 45 min.

> **Reflash `van-heater.yaml` before the plug goes back on the heater.** The
> fridge firmware has no runtime limit and no overcurrent trip.

## 2. Bring-up (10 min)

1. Flash both nodes (`esphome run nodes/van-fridge-plug.yaml`, then
   `esphome run nodes/van-core-probes.yaml`). Compile at home: OTA needs the laptop on
   the SoftAP, which has no internet.
2. Open the **van-core app**. On this firmware it lands directly on the log
   page (`/ui` is served as `/soak`), with mobile data left on (M13). Opening
   the page syncs the clock. Without this step, rows have no wall-clock time.
   ESPHome's entity page is one link away at `/`.
3. Display or `/`: check that `1-Wire devices` lists **two** addresses. Write
   them down in `measurements.md`. The real node pins probes by address.
4. **Identify the probes.** Hold one probe in your hand for 30 s and watch
   `Probe 0` / `Probe 1`. If probe 0 turns out to be the cabin one, either swap
   `index: 0/1` in the YAML now (before the log matters) or pass
   `--swap-probes` to the report later.
5. `/soak` shows the remote `ok` count rising, and `Fridge power (plug)`
   shows a plausible number (M6: roughly 24–34 W).
6. **Calibrate the fridge probe**: put a glass of water with a reference
   thermometer in the fridge, wait at least 2 h, and record
   `reference − probe`. Without this offset the cabinet numbers cannot be
   compared with food-safety limits.

## 3. The runs

### Test A — baseline, ≥ 72 h, hands off (the main one)
Default thermostat. Use the van normally, but **no deliberate AC-off events**.
Download every day or two. The log holds about 5 days.

**Downloading: use Chrome, not the app.** The app's WebView has no file-save
handler yet, so its Download buttons only print a message. In Chrome, switch
mobile data off for the minute it takes (M12: otherwise Chrome sends
192.168.4.1 to cellular), open `http://192.168.4.1/soak`, tap
`Download all`, switch data back on. Once the app gains a
`VanApp.saveFile(name, text)` bridge, the same button saves from the app with
no page change.

What it answers:

| Question | Report section | Why it matters |
|---|---|---|
| Where the cabinet sits and how much it swings | 2 | Sets how much headroom the ceiling has. All earlier data is from the warmest setting |
| Does the compressor ever stop at the default setting? How long? | 3 | Every natural stop is a free coast-rate measurement (§8.5), and it tunes the rest-block length |
| Fridge Wh/day, and W against (cabin − fridge) | 4 | Heat-leak slope, i.e. what August costs, from data instead of the 114 kWh/yr label |
| Station overhead with the fridge taken out | 5 | Cross-checks M2's ~48 W. It does **not** split off inverter idle; `idle-test` still does that |
| Door openings | 6 | Sizes the control EMA. Foam-covered and wall-mounted should show almost none |
| Internal heap, first-connect time, die temperature with the backlight off | 7 | Closes the gaps M12 left open |

### Test B — one imposed OFF block (optional, afterwards, ~3 h)
This measures the **pulldown penalty**, the single `UNVERIFIED` number the
whole Strategy B saving rests on (CLAUDE.md §1).

1. After at least 12 h of steady running, note the time and switch AC off
   **with the P310's own AC button**. The firmware cannot do it; that is by
   design.
2. Leave it off for **60 min**. Stay nearby. Abort if the probe reads more
   than 8 °C.
3. Switch AC back on and leave everything alone for at least 2 h.

The log already captures `ac_on`, the coast (dT/dt with the compressor locked
out, which gives heat leak per K) and the recovery power curve. Penalty =
extra Wh during recovery above the steady baseline, divided by the Wh the
fridge *would* have used during the OFF hour. Analyse it by hand the first
time. If it proves useful, it gets a section in the report.

The plug is off while AC is off. `fridge_w` goes blank for that hour, which is
correct and not a fault.

## 4. What survives what

| Event | Log |
|---|---|
| Crash, watchdog, OTA, restart button | **Kept.** Buffer in `.ext_ram_noinit`. Clock carried forward (`clock=2`) |
| Power cut to the board, brownout | **Lost** |
| Reflash with a different column list | Discarded (layout hash) |
| Buffer full (~5 days) | Oldest rows overwritten |

Each `# boot` line in the CSV records a reboot and its reset reason. Any line
other than the first `power-on` is a finding.

## 5. Afterwards

```
python tools/soak_report.py soak-YYYYMMDD-HHMM.csv
```

Append the results to `docs/measurements.md` as M14 (M13 is the app test), with the thermostat
setting, the probe offset, the probe addresses and whether the gasket crossing
was flat or round cable.
