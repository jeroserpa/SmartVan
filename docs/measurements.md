# Measurements log

Append-only, dated. This is the file that turns estimates in CLAUDE.md into facts.

## 2026-08-18 — BLE protocol validation (Phase 0)
- Station found via `tools/fbot_probe.py scan`: POWER-0183, E8:06:90:C7:AD:F6
- `services` dump confirms exact GATT match with ESP-FBot expectations:
  service 0000a002, write char c304, notify char c305 all present.
- MTU negotiated to 517 — status frames (~166 bytes) arrive unfragmented,
  single notification per poll.
- `monitor --raw` output validated against BrightEMS readings: plausible.
- Fridge running power (compressor on): 35W AC, user-stated.
- Inverter idle draw: 35W, manufacturer figure, still UNVERIFIED in situ.
- Fridge auto-restarts after power interruption on medium-cold setting: CONFIRMED.
- Water heater: 230V, 1-2kW, CONFIRMED.
- Water level sender run: <1m, CONFIRMED.
- 12V habitation distribution confirmed fed from P310 DC output (the `dc` switch).

## 2026-08-18 — 6.6h energy log, 16:04–22:59 (`fridge_log.csv`, 3818 rows)

First real dataset. Analysis in-session 2026-08-19. Four findings, in
descending order of how much they change the project.

### M1. Register map in `fbot_probe.py` was wrong — `CORRECTED`

Three names inherited from ESP-FBot are misleading, and two of them invert the
meaning of the most useful channels.

| Old name | Reg | Actually is |
|---|---|---|
| `total_w` | 20 | **AC output power only** — i.e. the fridge |
| `output_w` | 39 | **Total output**, AC + DC + USB |
| `system_w` | 21 | **Not a power.** Reads 2285 whenever AC input is present, 14–24 otherwise, regardless of 800W vs 400W charging. Almost certainly **AC input voltage ×0.1** (228.5V). `UNVERIFIED` — confirm against a meter |

Evidence for reg 20: steps exactly with the compressor; unaffected by the
laptop on the 12V output; reads ~0 during AC pass-through charging (inverter
bypassed); shows the full 2.3kW induction load during cooking.

Consequence worth keeping: **`reg39 − reg20` is a free split of the load into
AC-side and DC-side with no extra hardware.** Now logged as `dc_usb_load_w`.
`remaining_min` (reg 59) is junk on this firmware — reads 0 or 42679.

### M2. Station overhead ≈ 48W, not the 35W manufacturer figure

Energy balance over four windows, solving simultaneously for usable capacity
and overhead. Charge and discharge windows constrain capacity from opposite
directions, which is what makes the result trustworthy:

| Window | Duration | Derived overhead |
|---|---|---|
| Fridge ON, no input (21:30–22:48) | 79 min | 49W |
| Fridge ON, no input (20:10–21:20) | 70 min | 52W |
| Fridge OFF, solar (17:58–18:24) | 27 min | 40W |
| Fridge ON, solar (16:05–17:50) | 105 min | (used to solve capacity) |

- **Implied usable capacity ≈ 3900 Wh**, against a 3840 Wh nameplate. The
  method validates itself.
- **Overhead 40–52W, best estimate ~48W.**

**Caveat — this is not yet inverter idle.** `ac_on` was True for every row of
the log, so 48W is inverter idle **plus** the station's own base electronics,
and the two cannot be separated from this dataset. §8.2 remains open; the
`idle-test` command exists to close it.

### M3. Laptop draw isolated — 26W on the 12V output

Controlled unplug at 17:41:27, four samples: `total_output_w` 58 → 33 while
`ac_output_w` stayed at 32. The logging laptop was on the **DC** output, not
AC, which is why the fridge signal in reg 20 is clean for the whole log.
Time-averaged DC load 19.3W, tapering 26W → 10W as the laptop's own battery
filled.

### M4. AC charge taper — explained, not a fault

Input stepped cleanly 800W → 400W at 18:37 and held. **The P310 halves AC
charge power automatically above 70% SOC** (user-confirmed). The 85%
station-side charge acceptance computed across that boundary is therefore
meaningless — recompute below 70% if the figure is ever needed.

### M5. Data quality issues found — all fixed in the tool

- Last 3 rows corrupt: SOC drops 75.1 → 74.7 in one second as the laptop
  suspended mid-notification. Nothing was validating frames.
- Two gaps (8.2 min at 21:21, 11.0 min at 22:48) — laptop sleeping.
- ~250 duplicate zero-interval rows: the poll loop and the notification
  handler were racing.

## 2026-08-19 — Fridge identified: the project premise was wrong

### M6. `ESSENTIELB ERT85-55mib6` has an INVERTER compressor — `DECISIVE`

Spec: 113L top-format (97L fridge + 16L 4★ freezer), **variable-speed inverter
compressor**, 114 kWh/yr, class D, static cold, mechanical thermostat, climate
class N-ST (16–38 °C), 39 dB, **9h power-failure autonomy**, heat rejected
**through the rear and the top**.

Observed: **~24W continuous** at 27 °C ambient with the thermostat at its
warmest setting and ~7 °C interior. Earlier log showed 32–34W continuous.

**An inverter compressor does not duty-cycle.** It modulates speed to match the
heat load and runs continuously. The 79% "duty cycle" computed from the
2026-08-18 log is not a duty cycle at all — it is one long modulated run with a
single genuine off period.

Two earlier diagnoses are **withdrawn**, recorded so the reasoning stays
auditable:
1. *"79% duty means a choked condenser"* — wrong. There is no cycling to
   compare against.
2. *"Falling power with continuous running implies undercharge or a partial
   restriction"* — wrong. That is exactly how a healthy inverter compressor
   behaves as the load falls.

Rated 114 kWh/yr = 13W average on the EU test cycle. Observed 24W at 27 °C is
high but not abnormal for the ambient. Rear/top clearance still worth checking;
side clearance is irrelevant on this unit.

### M7. Consequence — the arbiter spec in CLAUDE.md §6 is obsolete

`fridge_req` was specified to *follow* the compressor and drop the inverter
during its off periods. **There are no off periods, so following it saves
exactly zero.** The `output_power < 15W` release condition can never be met.

The economics are nonetheless **better** than originally framed, because the
fridge is now the small number:

| | Battery-side |
|---|---|
| Fridge (24W AC) | ~27W |
| Station overhead | ~48W |
| | **The supervision overhead is nearly twice the load it supports** |

### M8. Revised strategy — impose cycles rather than follow them

Force the fridge off in blocks and let the cabinet coast on the food mass. The
9h power-failure rating says the buffer is ample.

Estimated, at 27 °C, allowing ~15% penalty for pulldown at higher compressor
speed:

| Inverter duty | Daily |
|---|---|
| Continuous (current) | 1.79 kWh |
| 50% | ~1.30 kWh |
| 33% | ~1.16 kWh |

0.5–0.6 kWh/day — approximately the saving originally projected, by the
opposite mechanism.

**The 15% pulldown penalty is a guess and the entire saving rests on it.**
`UNVERIFIED`. See TODO.

## 2026-08-23 — BLE client starves the SoftAP's WPA2 handshake (bench, TTGO T-Display)

### M9. Radio contention, not a config error — `DECISIVE for node topology`

Bring-up rig: LilyGO TTGO T-Display (ESP32-D0WDQ6 rev v1.0, `esp32dev` +
esp-idf, MAC `ac:67:b2:2a:ea:90`), `nodes/van-core-ttgo.yaml` — BLE client to
the P310 (out of range on the bench, so continuously scanning/retrying) +
SoftAP + `web_server` + display, exactly the load CLAUDE.md section 2 flags as
the risk to watch.

**Symptom:** phone sees the `van-core` SSID, attempts to join, every attempt
ends "saved, connection failure" (Android's WPA2 4-way-handshake-failed
message). Not a DHCP timeout, not a wrong password — checked both.

**Isolation test:** `nodes/van-core-ttgo-noble.yaml` — identical AP config,
`ble_client`/`fbot` removed entirely. Same SSID, same password, same board,
same session. **The phone joined immediately.**

**Conclusion:** with one 2.4GHz radio doing WiFi AP and BLE client duty in
ESPHome's single cooperative loop, an active (especially unconnected/retrying)
BLE scan can starve the AP side badly enough that a client's WPA2 handshake
never completes — not just the BLE-dropout risk section 2 already names, but
the AP itself becoming unjoinable. This happened with a classic ESP32 doing
nothing else demanding (no SD, no arbiter).

**Why this matters more than a single bench result:** section 4 requires the
phone to join `van-core`'s SoftAP *while* the BLE link to the P310 is live -
that is not an edge case, it is every normal use of the UI. If this reproduces
on the real board once the P310 is actually in range and connected (rather
than scanning fruitlessly, as here), it is a problem with the whole
one-radio-does-everything design, not a soak-test footnote.

**Open, blocking:**
- [ ] Repeat this A/B **at the van**, with the P310 in range so `ble_client`
      reaches `connected` state rather than scanning indefinitely. A steady
      connection may poll far less aggressively than a continuous scan/retry
      cycle - this bench result may be a worst case, or it may not be.
- [ ] If it reproduces with BLE connected: look at `esp32_ble_tracker` scan
      `interval`/`window` tuning (a scan running near 100% duty cycle is the
      likely mechanism) before concluding the architecture needs RS485/CAN
      pulled forward from the section 4 escape hatch.
- [ ] Re-test on the actual Waveshare ESP32-S3 board once it arrives - a
      classic ESP32 and an S3 do not necessarily share this failure mode.

`nodes/van-core-ttgo-noble.yaml` is a throwaway isolation config, not a rig to
keep - delete it once this is resolved.

## TODO next
- [ ] **Pulldown penalty** (blocks the whole revised strategy): 3h continuous
      vs 3h at 30-on/30-off at matched ambient, compare Wh on the Meross plug.
      If the penalty is ~40% rather than ~15%, the saving halves and the
      strategy changes again.
- [ ] **`idle-test`** after dark, SOC 30–80%: separates inverter idle from
      station base electronics. Closes §8.2.
- [ ] Rear and top clearance behind the fridge (NOT the sides — heat is
      rejected rear/top on this model)
- [ ] Coast rate with the compressor cut: log dT/dt at 27 °C ambient. Now the
      key input to block scheduling rather than a sleep-mode nicety
- [ ] Confirm reg 21 is AC input voltage — meter on the incoming AC
- [ ] Cold-weather baseline: repeat the 24h log below ~15 °C ambient
- [ ] D3: does P310 12V output stay on at ~100mA
- [ ] Manual switch temperature after 10min charging
- [ ] Confirm 1200W/800W alternator charging figure with clamp meter
- [ ] AC output N-E bonding (floating vs bonded) — RCD implications

## M10 — 2026-08-30 — van-core board bring-up: it is the 1.47B, and two config bugs

Board received is the **ESP32-S3-LCD-1.47B**, not the plain 1.47 the BOM named
(same price, same panel, same chip). Pinout `VERIFIED` against the vendor
schematic, not a board-support header this time.

**Bug 1 — captive_portal broke the SoftAP entirely.** Phone saw the AP, every
join died in "saved, connection failure" (WPA2 handshake never completed) —
even with BLE removed. Cause is in ESPHome 2026.8.0's wifi component
(`wifi_component.cpp:722-731`): captive_portal on an AP-only node forces
AP+STA and starts a station scan that never ends, and the scan state
overwrites the AP state. Removing captive_portal fixed the join instantly,
nothing else changed. It is now deliberately absent from every node config —
`van_ui`'s `captive_landing` answers the phone's connectivity probe instead.
(Second, older reason from M-2026-08-24: it also steals "/" from the UI.)

**Bug 2 — the backlight pin belongs to a different peripheral.** All 1.47
sources say LCD_BL = GPIO48. On the 1.47B, **LCD_BL = GPIO46** (active high
into an SI2302 low-side switch); GPIO47/GPIO48 are the I2C bus of an onboard
QMI8658 IMU the non-B board does not have. So the ST7789 initialised and drew
into a panel whose backlight was never powered, while the config toggled the
IMU's SDA line. The display itself was proven good by the vendor demo firmware.

Also reproduced M9 on this board along the way: ESPHome 2026.8.0 defaults the
BLE tracker to a 100% duty scan (320ms/320ms window=interval); with the P310
out of range this alone kept the AP invisible. `scan_parameters.window: 30ms`
is now set explicitly in van-core.yaml and van-core-soak.yaml, as it already
was in the ttgo rig.

Standing corrections from this session:
- BOM item 1 and D0 should read **1.47B**.
- The 1.47B adds a battery charger (ETA6098, BAT_ADC on IO1) — irrelevant to
  the van install (USB-powered), but the header pins differ from the non-B.
- ESPHome on this laptop must run from PowerShell, not MSYS/Git-Bash: the
  IDF 5.5.5 installer refuses MSYS environments.

## M11 — 2026-09-15 — reg 21 is AC input voltage (×0.1 V): CONFIRMED

At the van, van-core-soak running on the 1.47B, P310 connected. Reference:
the Athom plug (van-heater) voltage sensor, uncalibrated. No multimeter.

| Condition | Plug position | Plug V | Input power | Reg 21 (`system_power`) |
|---|---|---|---|---|
| Solar only, AC output live | P310 AC output | 228.0 | 241 W | 17 |
| Engine on, AC charge limit 400 W | P310 AC **input** | 229.7 | 654 W | 2280 |

- 17 with the output live at 228 V rules out AC output voltage.
- 17 with 241 W solar in rules out any input or total power.
- 17 → 2280 exactly when AC input appears, and 2280/10 = 228.0 V against
  229.7 V metered on the same wire: 0.7 %, inside the plug's tolerance.
- Same ~2280 at 400 W here as at other charge rates in M2-era logs, so it
  is not a power.

**ESP-FBot's `system_power` label is wrong.** Any config or UI showing it as
watts should rename it (AC input voltage, ×0.1). Closes the "Confirm reg 21"
TODO above.

Side findings, same session:
- ESP-FBot's `fbot.h` includes `switch.h` unconditionally; a config with no
  `switch:` block does not compile. Worked around in van-core-soak.yaml.
- van-heater's fallback AP could not be joined (authenticating → saved), same
  symptom as M10. captive_portal removed from van-heater.yaml; not yet
  reflashed to the plug.
- Soak running without an SD card: counters in RAM only, and free heap reads
  optimistic because FAT buffers are never allocated.

## M12 — 2026-09-15/16 — first BLE soak on the 1.47B, P310 connected

van-core-soak.yaml (commit 37bd4c4), no SD card, backlight 100 %. Flashed
14:37; power cut briefly by hand ~15:06 (board heat check), which restarted
the run. After the cut the display showed `BLE DOWN` for a while; how long is
not recorded (see gaps below). Read from the web UI:

| Reading | 15 Sep 21:41 | 16 Sep 12:00 |
|---|---|---|
| Uptime | 6 h 35 min | 20 h 54 min |
| BLE disconnect count | 0 | 0 |
| Longest BLE gap | 0 s | 0 s |
| Free heap (`esp_get_free_heap_size`) | 8 429 912 B | 8 429 624 B |
| Free PSRAM | 8 275 164 B | 8 275 164 B |
| Board temperature (die) | 75.3 °C | 71.3 °C |
| SOC | 50.3 % | 29.9 % |
| Input / output power | 0 W / 59 W | 396 W / 32 W |
| Reg 21 (`system_power`) | 20 | 19 (no AC input) |

**Result: pass on what was measured, 21 h rather than the planned 24 h.**
- Link held: zero drops across ~21 h connected, with SoftAP (phone + heater
  plug), web server and a 1 s display refresh all running.
- No reboot: the two uptimes agree on a boot at ~15:06.
- Heap flat: −288 B over 14 h.

**What this soak did not show:**
- **Reconnect after a power cut is untimed.** It did recover (data flowed
  all night), but the counters only time gaps that follow a disconnect seen
  at runtime, not the wait for the first connection after boot. That is
  exactly the §5.2 recovery path. Needs its own deliberate test, and the soak
  config should record time-to-first-connect.
- **Internal RAM is not visible.** `esp_get_free_heap_size()` includes PSRAM,
  so the "free heap" figure is dominated by it. The BLE stack lives in
  internal RAM; track `heap_caps_get_free_size(MALLOC_CAP_INTERNAL)` and its
  minimum-ever value instead.
- No SD card, so fatfs buffers never allocated and no CSV.
- Engine/charging EMI and induction cooking exposure during the window:
  `UNVERIFIED` — not recorded.

**Thermal: die at 71–75 °C sustained**, backlight at 100 %, in the van in
September. High for an S3 (50–65 °C is typical for a busy board). The octal
PSRAM is the limiting part (85 °C ambient rating). Re-measure with the
backlight blanked as the real node runs, and in the final mounting position,
before August.

**Side note, not a soak result:** SOC fell 50.3 % → 29.9 % over 14 h 19 min
overnight, i.e. ~800 Wh at 3900 Wh usable, with little or no input.

### M12 addendum — 2026-09-16

- **EMI exposure: covered.** During the window the van was driven, charged
  from the alternator on AC input, and the stove and air fryer were used.
  Link never dropped. Replaces the `UNVERIFIED` line above.
- Decision: the soak is accepted as sufficient. The untimed-reconnect and
  internal-RAM gaps are not being chased with another soak run; the
  reconnect is covered by the pre-trip fail-safe test (§11).
- **New issue: the web UI does not load while the phone also has mobile data
  on.** Android marks the SoftAP as "no internet" and routes browser traffic
  over cellular, so 192.168.4.1 is unreachable. Phone-side setting, not a
  node fault.

## M13 — 2026-09-16 — D-15 Android wrapper: UI and mobile data together, WORKS

App from `app/` (built by `.github/workflows/android-app.yml`), phone joined
to the van-core AP with 4G on, van-core running `van-core-soak.yaml`.

- **Routing: confirmed.** The app's process bound to `wlan0`, address
  `192.168.4.100/24`, default route via `192.168.4.1`, while the status bar
  kept 4G. The page loads in the app. Resolves the M12 addendum issue for
  Android; the browser path is unchanged.
- **First failure was not routing.** `/ui` returned `net::ERR_EMPTY_RESPONSE`:
  the soak build has no `/ui` handler, and ESPHome's IDF web server registers
  no not-found handler, so an unknown path closes the socket instead of
  answering 404. Worth remembering for any client that probes paths. The app
  now falls back to `/` on any `/ui` failure.
- Still `UNVERIFIED` (D-15 list): SSE surviving backgrounding; other apps'
  internet while the wrapper is in the foreground; out-of-range behaviour.

## M14 — 2026-09-16/17 — soak 2 first data: probes, fridge plug, PSRAM log — `PRELIMINARY`

Firmware `nodes/van-core-probes.yaml` + `nodes/van-fridge-plug.yaml`, plan in
`docs/soak-probes.md`. Two logs so far; the long run is in progress.

### Setup, as reported
- Thermostat: **default setting** (M6 was at the warmest).
- Fridge probe: interior side wall, held with Kapton tape, **no foam pad**.
  Cable routed through the rear condensate drain, drain not blocked, so the
  door gasket is not crossed.
- 1-Wire addresses (both pass the Dallas CRC):
  - fridge `0xf50e250267176c28`
  - cabin `0x600e2502e02beb28`
  Role assignment: in the 17 Sep log, bus index 0 read 5–8 °C and index 1
  ~25 °C. ESPHome's ROM search takes the 0 branch first, and the addresses
  first differ at bit 8, where `0xf50e…` has the 0, so index 0 is `0xf50e…`.
  Both configs now pin the probes by address.
- Probe **not yet calibrated** against a reference thermometer: every fridge
  temperature below is the probe's reading at its mount, not the food's.

### 16 Sep, 15:52–19:55 — station energy balance (no probes, plug reading 0 W)
AC on throughout, no AC input, solar 472 W falling to 0.
- Solar in 303 Wh + battery 55 Wh (SOC 49.0 → 47.6 %) = total output 142 Wh
  + **station overhead 216 Wh**.
- Overhead per 15 min bin, battery power taken from SOC tick times:
  **43–59 W, mean 52.6, SD 3.9**, flat while solar went 251 W → 4 W.
  Consistent with M2 (~48 W).
- `fridge_w` read 0.0 W on every row. Cause not established; the plug reads
  normally on 17 Sep.
- Board temperature column empty: a second `internal_temperature` sensor never
  publishes. Fixed by logging base.yaml's `board_temp`.

### 17 Sep, 13:36–15:10 — first fridge data (1.6 h)
- **The compressor stopped on its own** (user-confirmed: fridge on, AC on).
  Plug 0.0–0.1 W from log start (13:36) to 13:59:57, so the idle lasted at
  least 24 min. Second natural stop on record; the first was in the
  2026-08-18 log (M6), at the warmest setting.
- During the idle the probe rose +0.4 K/h (7.56 → 7.69 °C), then +7.5 K/h over
  13:56–14:00. Cause of the faster rise unknown: an air fryer ran on and off
  (>500 W, 13:57–14:12), and a door opening is possible but not confirmed.
- **Restart at ~8.1 °C on the probe.** Running power **17.5–24 W, median 21.8**,
  flat for the whole 70 min cool-down (no high-power phase visible). 26 Wh.
- **Probe lag: the probe kept rising for ~6 min after the compressor started**
  (peak 8.38 °C at 14:05). The control loop must allow for it.
- Cool-down: −5.8, −2.6, −1.3 K/h in successive 20 min blocks. Exponential fit
  from 14:08: **towards 4.5 °C (probe), τ ≈ 27 min**, rmse 0.04 K. Still
  running at 15:10 at 4.88 °C.
- Other loads (total output − plug): ~5 W at rest; 34–74 W later.
- Overhead 50–60 W per 15 min bin (~54 mean). Less precise than 16 Sep: with
  250–370 W into/out of the battery, a 3 % error in the 3,900 Wh capacity
  moves each bin ~10 W.
- Node: BLE 100 %, 0 drops, first connect 1.4 s. Min free internal heap
  102 kB (from 145 kB at boot); too short a run to judge a trend.

### Open
- Where the cabinet settles on this thermostat, how long natural stops last,
  and whether they repeat: needs the ≥24 h run.
- Probe offset (glass-of-water calibration).
- CLAUDE.md §1/§6 say the compressor never stops (M6). Two natural stops now
  argue for "rarely", and the block scheduler already ends a block on a
  genuine stop. Revisit the wording once the 24 h run shows how often.

## M15 — 2026-09-17/18 — soak 2: natural stops, overhead with and without load, probe offset

Two logs. `soak-20260917-2048.csv`: previous firmware, 17 Sep 15:49–20:48.
`soak-20260918-1129.csv`: new firmware (AC-output column, board temperature,
probes by address), 17 Sep 23:45 → 18 Sep 11:29. The new log starts with a
**power-on reset at ~23:45**, not at the 20:51 flash; 20:51–23:45 is lost.
Cause not established. Thermostat at the default setting throughout.

### Compressor: it stops regularly at the default setting
| Stop | Length | Probe at stop → restart | Coast | Cabin − probe |
|---|---|---|---|---|
| 17 Sep ≤13:36–14:00 (M14) | ≥24 min | 7.6 → 8.1 °C | +0.4 K/h | ~17 K |
| 17 Sep 20:01–20:27 | 26 min | 7.7 → 8.9 °C | +3.2 K/h (cooking time; cause of the fast rise not established) | 21 K |
| 18 Sep 02:28–03:08 | 40 min | 7.6 → 7.9 °C | +0.7 K/h | 16 K |
| 18 Sep 07:28–08:24 | 56 min | 4.7 → 5.6 °C | +1.0 K/h | 16 K |
| 18 Sep 10:13–10:54 | 40 min | 3.5 → 4.4 °C | +1.2 K/h | 18 K |

- Overnight (23:45–11:29): running 81 % of the time. Runs of 36–260 min.
- **Stop and restart points on the wall probe vary widely** (stops at
  3.5–7.7 °C, restarts at 4.4–8.9 °C), so the thermostat is not tracking what
  this probe sees.
- Running power: **~22 W** (17.1–24 W) on 17 Sep afternoon, cabin 26–30 °C;
  **29–33 W median** (up to ~41 W) overnight, cabin 21–24 °C.
- CLAUDE.md §1's "100 % duty, it does not cycle" (M6) does not hold at the
  default setting.

### Hot evening: the cabinet did not hold
17 Sep, AC on, compressor running continuously at ~22 W from 15:49 to 20:01:
probe 6.4 → 7.9 °C while the cabin went 26.5 → 30.5 °C, flat at ~7.9 °C once
the cabin passed ~29 °C. After the 20:01 stop and a 1.7 kW load (cooking), the
probe reached **10.4 °C at 20:48** and was still **9.2 °C at 23:45**. The
arbiter's 10 °C override could not have helped: AC was already on.

### Fridge energy
Plug integrator (flash-backed, so it covers the van-core gap if the plug
stayed powered): 0.0400 kWh at 17 Sep 15:49 → 0.4874 kWh at 18 Sep 11:29 =
**447 Wh in 19.7 h, 22.7 W average, ~550 Wh/day** at cabin 21–30 °C.
Overnight alone 24.4 W average, which includes pulling down from 9.2 °C.
Rated 114 kWh/yr = 312 Wh/day (EU test, 25 °C). §8.6 (condenser airflow) is the
documented first check when consumption runs this far above the rating.

### Station overhead, night, no solar (SOC-crossing method, 3,900 Wh)
| Window | AC load | Overhead |
|---|---|---|
| 00:00–07:28, compressor running | ~28–32 W (plug) | **54.5 W** (164 SOC ticks) |
| 02:29–03:08, compressor stopped | ~2.6 W (plug self-draw) | **43.4 W** (±6) |
| 07:29–08:23, compressor stopped | ~2.6 W | **42.7 W** (±4) |

- **With AC on and nothing drawing, the station uses ~43 W.** That is
  inverter idle + station base; splitting the two still needs AC off
  (§8.2, CLAUDE.md §1's ~11 W break-even).
- **Running the fridge adds ~12 W of overhead** on top of its ~30 W AC draw
  (range ~7–17 W given the stop windows' uncertainty): low-load inverter
  conversion loss.

### Meters agree
`ac_out_w` (P310 reg 20) − `fridge_w` (plug), with the fridge the only AC
load: **median 2.6 W** (p10 1.8, p90 3.2, n = 4,092), about the plug's own
draw. `out_w` − `ac_out_w` (DC + USB): median 0 W.

### Probe offset (glass of water, "Cuisy" kitchen probe, 1 °C resolution)
The user's DS18B20 readings match the log to ±0.05 °C.
| Time | Probe (log) | Water | Water − probe | Compressor |
|---|---|---|---|---|
| 09:40 | 5.67 | 7 | +1.3 | running; excluded: probe recovering from a +3 K spike at 09:30 |
| 10:47 | 3.69 | 4 | +0.3 | stopped 34 min |
| 11:12 | 3.85 | 4 | +0.15 | running |
| 11:17 | 3.59 | 4 | +0.4 | running |
| 11:23 | 3.20 | 4 | +0.8 | running |
| 11:28 | 2.90 | 3 | +0.1 | running |

**Working figure: water ≈ probe + 0.5 °C (±0.5, set by the reference's 1 °C
steps).** During a run the gap grows: the wall probe cools faster than the
water.

### Node
BLE 0 drops (both logs). Internal heap min-ever 106 kB; −90 B/h over 11.7 h,
not significant. **Board temperature 59–68 °C with the backlight off, ~43 K
above cabin** (M12: 71–75 °C with backlight at 100 %).

### Open
- Inverter idle vs station base inside the 43 W: needs a night AC-off block.
- Pulldown penalty: the same block, if AC stays off long enough.
- Why the board power-cycled at ~23:45.

### M15 addendum — 18 Sep afternoon: thermostat turned toward warmer at 14:30

Same run, extended to 19:02 (`soak-20260918-1902.csv`, 19.3 h, one boot).
User turned the thermostat from the default to a less cold setting at 14:30,
because the cabinet was running too cold.

| Period | Probe | Cabin | Fridge |
|---|---|---|---|
| 12:05–14:25, default setting | 1.4–2.5 °C | 23.5 → 27.7 °C | running, ~29–30 W |
| 14:30–16:17, after the change | 1.7–2.1 °C | 28.6 → 30.6 °C | still running, ~30 W |
| 16:17–17:01 | 2.1 → 5.2 °C | ~29.5 °C | stopped (44 min) |
| 17:01–19:02 | 5.2 → **−0.3 °C** (min, 18:55) | ~30.5 °C | running, 30–35 W |

- **After the change the cabinet got colder, not warmer**, over the ~4.5 h
  logged. One restart after the change is not enough to judge the new setting.
- During the 16:17 stop the probe rose ~3 °C in the first 20 min, then
  flattened at ~5.2 °C. The overnight stops rose steadily at 0.7–1.2 K/h with
  no such jump. The water in the glass lags the wall probe, so read the jump as
  the probe settling, not the food warming at that rate.
- **Same default setting, similar cabin, very different cabinet:** 17 Sep
  afternoon (cabin 26–30 °C) held ~7.9 °C at ~22 W; 18 Sep midday (cabin
  23.5–27.7 °C) held 1.4–2.5 °C at ~30 W. What set the ~22 W vs ~30 W
  compressor power is not known.
- Plug energy over the whole 19.3 h run: 492 Wh, 25.5 W average.
- Node: 0 BLE drops over 19.3 h; internal heap flat (+89 B/h, min 106 kB);
  board 59–71 °C, ~42 K above cabin.
