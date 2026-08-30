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
