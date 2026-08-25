# van-supervisor

ESPHome-based energy and services supervisor for a van conversion.
Primary goal: **eliminate inverter idle losses** by only energising the 230V
inverter when a load actually needs it, and add local monitoring/control for
water, lighting and vehicle charging — all with **no router, no Home Assistant,
and no internet in the van**.

---

## 1. Problem statement

The van's fridge is a 230V domestic unit. Running it means the AFERIY P310's
3300W inverter is on 24/7.

### Measured / stated figures — `REVISED 2026-08-20`, see `docs/ANALYSIS-2026-08-20.md`

The fridge is an **ESSENTIELB ERT85-55mib6 with a variable-speed inverter
compressor.** Everything this section used to say assumed a cycling fixed-speed
one, and was wrong in the direction that matters.

| Quantity | Value | Confidence |
|---|---|---|
| Compressor type | **Variable-speed inverter** | `CONFIRMED` from model spec |
| Station overhead, AC on | **~48W** | `MEASURED` 2026-08-18, M2 |
| — of which inverter idle | **UNKNOWN** | `ac` was never commanded off; `tools/fbot_probe.py idle-test` splits it |
| Fridge draw, continuous | **24W @ 27 °C, 32–34W earlier** | `MEASURED`, M6 |
| Fridge duty cycle | **100% — it does not cycle** | `CONFIRMED`, M6 |
| Rated consumption | 114 kWh/yr = 13W avg | manufacturer, EU test cycle |
| Power-failure autonomy | 9 h | manufacturer |
| Usable capacity | **~3900 Wh** | `MEASURED` via energy balance, M2 |
| Observed autonomy | 1.5–2 days with cooking | includes cooking + other loads |

**The station overhead is nearly twice the load it supports.** ~48W of overhead
to deliver ~27W battery-side of refrigeration. That is a *stronger* case for
this project than the original "the idle equals the useful load" framing, not a
weaker one — and it is measured rather than quoted from a datasheet.

### What this changed

The compressor does not cycle, so there is no duty cycle to follow and nothing
to switch off between cycles. **The supervisor has to impose the cycles itself**
— see §6 `fridge_req`, rewritten as a block scheduler, and ANALYSIS §4.

### The saving

Imposed cycling, all ~50W assumed to be inverter idle, 20% pulldown penalty:

| Inverter duty | Daily |
|---|---|
| Continuous (now) | 1.59 kWh |
| 50% | 1.07 kWh |
| 33% | 0.87 kWh |

**The `UNVERIFIED` number the whole saving rests on is the pulldown penalty of
imposed cycling** — estimated 15–30%, never measured. Break-even against the
full ~50W overhead is an **89% penalty**, so the margin is enormous; but
break-even against *inverter idle alone* at 50% duty and a 20% penalty is
**~11W**. If `idle-test` returns an idle below that, imposed cycling is not
worth doing and the answer is a 12V compressor fridge instead (ANALYSIS §4.3,
§5, and the reopened decision in `docs/decisions.md`).

**Measure the penalty before tuning block lengths.** Everything downstream is
arithmetic on a number nobody has yet.

The fridge is **not** being replaced — pending the pulldown measurement, which
could reopen that (ANALYSIS §5). The fix is to duty-cycle the inverter.

**Target:** the lowest inverter duty the cabinet's coast will carry, with
control-system standby under 2W total. Note this is no longer "fridge duty plus
overhead": there is no fridge duty to add to.

---

## 2. Hardware inventory

### Power system
- **AFERIY P310** portable power station — 3840Wh LiFePO4, 3300W pure sine
  inverter, expandable. BLE + WiFi, "BrightEMS" app family.
- **750W solar** into the P310 MPPT.
- **Alternator charging** via a pure-sine inverter feeding the P310's AC input
  at ~800W when the engine runs.

### Existing smart devices
- **Meross MSS315 (Matter/WiFi, energy monitoring)** — *has been used in the van
  as a manual on/off switch for the water heater*, but cannot be automated here.
  Matter-over-WiFi needs a Matter controller + IPv6 + mDNS on the LAN, i.e. a
  Pi/HA running 24/7 (~4W), and **ESPHome cannot be a Matter controller** — so
  `van-core` has no way to command it, router or no router. The limitation is the
  protocol, not the plug's form factor or rating: it has handled the 1–2kW load
  in this van without trouble (see BOM D1e, where the vibration and rating
  objections are retracted on that evidence).
  **Reflashing it was investigated and rejected — see BOM D1f.** Teardown shows a
  **Realtek RTL8720CM**, not an ESP32: ESPHome cannot target it, the only route
  is LibreTiny's least-mature platform, and Matter devices commonly burn secure
  boot fuses to protect attestation keys, which would make custom firmware
  permanently impossible. **Decision: replace with an ESPHome-preflashed plug in
  the same socket; the Meross returns to the house.**
- **MiBoxer E2-WR** LED controllers (dual-white/CCT, WiFi+BLE+2.4G RF, Tuya).
  Tuya WiFi side requires internet to provision → unusable in the van as-is.
  The 2.4GHz RF side works standalone. See §7 for the three routes.
- **Two resistive water level senders** with analogue gauges (AliExpress kit) —
  one for the **fresh** tank, one for the **grey** tank. Both fit on the single
  ADS1115 already budgeted, so the second tank costs one resistor: see §9
  Phase 2 and BOM D5.
  **Construction: almost certainly a reed-switch ladder** — a sealed stem
  holding a chain of reed switches with resistors between them, and a doughnut
  float carrying a ring magnet that slides over it. `STRONGLY SUSPECTED
  2026-08-25`, from three observations that only this design explains together:
  nothing conductive is exposed to the water, the float has no wires, and the
  spec is still quoted in ohms. Not capacitive (needs no float) and not
  magnetostrictive (4–20 mA or digital output, and 20× the price).
  **Two consequences run through the whole water design: there is no wetted
  contact to corrode, and the output is quantised into one step per reed, not
  continuous.** Confirm with the bench test in §8.7 before building anything.
  Resistance ranges **unverified, and must be measured separately** — nominally
  identical senders are not necessarily identical parts. See §8.7.

### To acquire
- ESP32-WROOM devkits (classic ESP32, *not* C3 — the ESP-FBot BLE component is
  proven on `esp32dev` + esp-idf).
- **DS18B20 waterproof probe** (fridge interior). **Not a DHT** — DHT11's range
  starts at 0 °C (useless at a 1 °C target) and DHT22 humidity elements drift and
  fail in near-condensing environments. 1-Wire is a bus, so add a second DS18B20
  outside the cabinet for ambient — it correlates directly with duty cycle.
  - **Gasket crossing:** do not pass the probe's own ~4 mm round cable through —
    it holds the magnetic gasket open and creates a permanent cold-air leak and
    frost line. Cross with ~15 cm of **flat cable only** (3-conductor ribbon
    ~0.5 mm, or three 30 AWG PTFE/silicone strands taped flat with Kapton),
    spliced to normal wire both sides. Avoid PVC — it stiffens and cracks when
    flexed cold.
  - Cross on the **hinge side, mid-height**: minimum door travel and gasket
    compression, so minimum flexing fatigue.
  - Seal the crossing with silicone or butyl on both faces — not for water, but
    to stop humid air being drawn along the cable and frosting.
  - Keep splices outside the cold zone or pot them in epoxy.
  - 4.7k pull-up at the ESP32 end. **3-wire mode, not parasitic** — parasitic
    saves one 30 AWG strand, which is no gain, and is less reliable.
  - Alternative route: the condensate drain at the back of the compartment, if
    accessible and it can be done without obstructing the drain.
  - **Probe placement — `REVISED`, no bottle.** An earlier revision specified
    sealing the probe in a water bottle. Unnecessary: the bottle was doing two
    jobs and neither needs physical thermal mass.
    - *Damping door-opening swings* → an exponential moving average over ~10 min
      in ESPHome achieves this. Real fridge dynamics are far slower, so nothing
      is lost.
    - *Reading "product" temperature* → the sensor does not need to be physically
      realistic, only **repeatable**. Calibrate thresholds against what the
      sensor reads in its fixed position and the coast prediction works
      identically. Position consistency beats physical fidelity.
  - **Mounting:** DS18B20 in TO-92 (or the waterproof probe tip) glued flat to an
    **interior side wall** with thermal epoxy, covered on the air side with a
    small foam pad. The foam makes it read the liner rather than the air, giving
    the slow time constant physically at zero volume cost. Cover with aluminium
    HVAC foil tape; do not seal moisture underneath.
  - **Never the back wall** — on a static-cooled fridge that is the evaporator
    plate, which swings below zero during a compressor run and would make the
    control logic nonsense. Not the door (moves, swings on opening), not near the
    interior light. Side wall, mid-height.
  - Stay with DS18B20 over a flat NTC package: digital and factory-calibrated,
    whereas an NTC needs an ADC channel and a precision reference resistor on a
    node with no ADS1115.
  - **Optional third sensor (€3, no extra wiring — same 1-Wire bus):** one on the
    wall (slow, drives control) plus one hanging in free air (fast). The fast one
    detects door openings so the arbiter can explicitly ignore excursions for a
    few minutes rather than filtering blindly. Makes the logic legible later;
    skip if keeping Phase 1 minimal.
  - **Calibration, once mounted:** put a glass of water in the fridge with a
    reference thermometer, settle for a few hours, record the offset to the wall
    sensor. That single number is what makes the food-safety ceiling meaningful.
- ADS1115 (water level ADC — the ESP32 internal ADC is too nonlinear/noisy).
  **One chip covers both tanks**: fresh, grey and an excitation-rail sense on
  three of its four channels.
- **Athom ESPHome-preflashed smart plug (16A EU)** for the water heater — see BOM
  D1e. Ships with ESPHome, so no flashing, no cloud, no router. Uses the wall
  socket and heater plug already in place; the 230V install is not modified.
- Momentary push button with integrated RGB LED (manual AC request).
- **Waveshare ESP32-S3-LCD-1.47** as the `van-core` board, ~€13 — `DECIDED`, see
  BOM D0. ESP32-S3R8, 8MB PSRAM, 16MB flash, 172×320 on **plain SPI ST7789**
  (best-supported ESPHome display path; the T-Display-S3's 8-bit parallel bus
  needs octal-SPI config, and the 3.49" AXS15231B is not a standard ESPHome
  model). **Requires a 24h BLE stability soak with SoftAP and web server active
  before wiring anything in.** Backlight PWM-controllable and must be blanked.
- **External illuminated momentary button** near the kitchen for manual AC.
  Deliberately separate from the display buttons — you should not have to page
  through a UI with a pan heating.

### Button map
The board exposes only **BOOT and RESET**, both tiny side-mounted tactile
switches. **Do not use BOOT as a runtime button** — GPIO0 is a strapping pin, and
holding it during a reset drops the board into download mode, which is a
confusing failure to debug months later. All user controls get free GPIOs and
external buttons.

| Control | Location | Action |
|---|---|---|
| Wake / cycle | Enclosure bezel | Wake display; subsequent presses cycle pages |
| Sleep mode | Enclosure bezel | Long press toggles sleep mode |
| Manual AC | Kitchen, illuminated | Manual AC request (see §6) |

**Why not a touchscreen board:** the screen is blanked most of the time (60s
timeout — no night-light, no wasted backlight), so a touch UI cannot be the input
method; something physical must wake it first. A physical button is needed
regardless, at which point touch only saves one button. Physical buttons also
work with wet hands, cold hands, and in the dark without looking.

**Optional upgrade — rotary encoder with push** (ESPHome `rotary_encoder`)
replaces wake + cycle with one knob: turn to move through pages, press to
wake/select. Pays off most for **threshold tuning**, since every setpoint is an
adjustable `number` — a knob at the fridge beats tapping through a phone browser.
Retrofits cleanly to the same terminal block; skip it to keep Phase 1 lean.

`VERIFY before laying out the carrier board:` how many GPIOs the Waveshare header
actually breaks out. Needed: SPI (display), SDMMC (card), I2C (RTC), 1-Wire,
plus three buttons. The S3 has plenty of pins in principle; the header may not
expose them all.

Display pages: SOC/power → fridge temp + arbiter state → water (fresh + grey,
binding constraint first — §9 Phase 2) → diagnostics.
Blank after 60s of no input.

**Risk:** BLE client + SoftAP + web server + display on one ESP32 can starve the
BLE task. Keep updates at 1–2s, simple fonts, no animation. If BLE dropouts
appear, the display is the first thing to move off this node.
- Optional: NRF24L01+ (~€4) if going the milight-hub route for lighting.

---

## 3. Key upstream dependency: ESP-FBot

https://github.com/Ylianst/ESP-FBot — ESPHome external component that speaks the
P310's BLE protocol locally. Apache-2.0.

Exposes as ESPHome entities:
- **Sensors:** `battery_level`, `input_power`, `output_power`, `system_power`,
  `total_power`, `remaining_time`, `ac_out_voltage`, per-port USB power,
  `threshold_charge`, `threshold_discharge`.
- **Switches:** `ac` (inverter), `dc`, `usb`, `light`, `ac_silent`.
- **Numbers:** charge max / discharge min thresholds.
- **Select:** `ac_charge_limit` (300/500/700/900/1100W), `light_mode`.
- **Binary sensors:** `connected`, output states, expansion battery presence.

**Critical constraint:** the P310 accepts effectively one BLE connection. Once
`van-core` holds the link, the BrightEMS phone app will not connect. This is a
*replacement*, not a coexistence. Accepted — the ESPHome web UI is better anyway.

Optional afterwards: factory-reset the P310 (hold DC + light + USB ~5s) to drop
its cloud/WiFi association. Known side effect: it then broadcasts its own
`ESP_xxxxxx` AP.

---

## 4. Node topology

No router. `van-core` runs SoftAP; other nodes join it as WiFi clients with
**static IPs** (mDNS is unreliable on SoftAP). Phone joins the same AP for the UI.

| Node | Location | Responsibilities |
|---|---|---|
| `van-core` | beside fridge / P310 | BLE→P310, fridge + cabin DS18B20, manual AC button + LED, AC arbiter, heater permit rules, water-temp estimator, SoftAP, web UI |
| `van-heater` | wall socket by the heater | ESPHome-preflashed plug: relay + power metering. **Only powered while the inverter is on** — expected, see BOM D1e |
| `van-water` | between the tanks | fresh + grey level senders (one ADS1115), tank cross-check, future pump control |
| `van-vehicle` | engine bay / dash | ignition + D+ sense, alternator charge limiting (future) |

Static addressing: `192.168.4.1` (core AP), `.10` water, `.11` vehicle,
`.12` heater.
Raise `max_connection` on the SoftAP to 8 (default 4).

### Inter-node protocol
HTTP REST against each node's `web_server`. `van-water` GETs core's sensor JSON
every 30s to read SOC and surplus power. No MQTT broker, no HA, no extra hardware.

### No router — confirmed workable end to end

Every piece of this runs with no router and no internet, including provisioning:

| Concern | How it works without a router |
|---|---|
| Node addressing | Static IPs on core's SoftAP. mDNS is unreliable on SoftAP — never depend on hostnames |
| Heater plug provisioning | Its own setup AP from a phone: point it at core's SoftAP with a static IP, then OTA your own config. All offline. **Contrast §7:** Tuya/MiBoxer cannot be provisioned without internet at all |
| Heater plug control | A normal ESPHome node on the SoftAP — plain HTTP on the local subnet |
| Clock | No SNTP → DS3231 RTC (BOM item 15). Already required for logging |
| Log retrieval | `sd_file_server` over the SoftAP from a phone. No card removal |
| Firmware updates | ESPHome OTA to the static IP |

**Practical gotchas:**
- **OTA needs the laptop joined to the SoftAP, which then has no internet.**
  ESPHome may want to fetch platform packages on first compile. *Compile at home,
  then join the AP and upload* — or use a second interface. Do not discover this
  in a car park.
- **`max_connection` must be raised to 8.** The default of 4 is already met by
  phone + water + heater + vehicle, with no headroom for a second phone.
- **The heater node appears and disappears by design** — it is only powered while
  the inverter is on. `van-core` must treat its absence as normal, never as a
  fault, and must not let a missing heater reply stall the arbiter (§5.1).
- Each extra SoftAP client is load on the node also running BLE, the web server
  and the display (§2 risk note). Poll the heater at low rate.

**Escape hatch (documented, not default):** if inverter EMI degrades the 2.4GHz
link — plausible with 3300W of switching a metre away — migrate inter-node comms
to **RS485 twisted pair (MAX485) or CAN (native TWAI + SN65HVD230)**. In a van
this is arguably more robust regardless; it's deferred only because it means
pulling cable. Design the data exchange as a small, explicit message set so this
swap stays cheap.

---

## 5. Non-negotiable design rules

1. **Node autonomy.** No node's safety function may depend on another node or on
   the network. Fridge control lives entirely on `van-core` and never reads the
   network. `van-water` that loses core for 5 min holds the heater OFF and keeps
   reporting tank level locally.
2. **Fail toward powered — for loads, and only as far as the link allows.**
   Any fault — BLE dropped, DS18B20 stale, reboot, watchdog — must resolve to
   *inverter ON*. Use `on_boot` priority and `filters: - timeout:` on every
   sensor feeding a control decision. A bug that silently kills the fridge for
   two days while nobody is in the van is the one failure mode that actually
   costs money.

   **`CORRECTED 2026-08-25` — this rule was overstated, and the correction
   matters more than the rule.** A fail-safe direction is only real if reaching
   it needs **no successful communication**. The inverter state is latched in
   the P310; `van-core` does not hold it up, it *commands* it. So:

   | Link state when the fault hits | What actually happens |
   |---|---|
   | AC already ON | Station latches ON. Fail-safe achieved **by inaction** — free, and genuinely safe |
   | AC OFF, link still alive | An ON command gets through. **This is the only case the fail-safe can act in** |
   | AC OFF, link gone | The write goes nowhere. **The fridge stays off, and no amount of firmware changes that** |

   The third row is the honest residual risk. In a duty-cycling design the
   inverter is off most of the time, so the exposed window is most of the time
   — not an edge case. Two consequences run through the whole design:

   - **Act on link *degradation*, not on link loss.** By the time
     `ble_connected` goes false there is nothing left to send the command over.
     The arbiter therefore forces ON when station data stops arriving while the
     stack still claims a connection (`LINK_STALE`, `link_stale: 60s`), which
     is the last moment an ON command can still work. `BLE_LOST` is
     *recovery-on-reconnect*, not a fail-safe, and is labelled as such in the
     code and the tests.
   - **Never enter a state you cannot leave.** OFF is a lease that a healthy
     link renews. That is why the reconnect edge re-writes the switch
     immediately instead of waiting for the next re-assert.

   **What remains unmitigated, stated rather than papered over:** a permanent
   BLE failure or a dead node, arriving during an OFF block, leaves the fridge
   off until a human intervenes. The tools left are alerting (buzzer, LED,
   display) and the P310's own physical AC button — see §11. A node that is
   merely *crashed* is fine: the watchdog reboots it and boot force-on covers
   the gap. A node with no power is not, though it correlates with the 12V bus
   being down, which nobody fails to notice.

   **This is a structural argument for the 12V compressor fridge** already on
   the table in `docs/ANALYSIS-2026-08-20.md` §4.3/§5. On the DC bus there is
   no inverter to cycle and no remote command in the safety path, so this
   entire failure class stops existing rather than being managed.
   **Exception 1 — the alternator charging path fails toward DISCONNECTED.** A
   stuck-closed 100A path drains the starter battery and strands the van. See
   §9 Phase 4.
   **Exception 2 — parked mode fails toward UNPOWERED.** With the fridge
   emptied there is no food to protect, and a forced-on inverter flattens the
   pack in days. This is the only mode that disarms `force_on`; everything that
   makes it safe is at the entry gate, not in the loop. See §6 "Parked mode".
   Every control path must have its fail-safe direction stated explicitly; do
   not assume the fridge convention applies elsewhere.
3. **Single writer.** Exactly one place in the code writes `ac_switch`. Multiple
   consumers express *requests* as booleans; an arbiter ORs them. See §6.
4. **Standby power is a first-class requirement.** Every added device gets
   metered before it stays. The whole control system must stay under ~2W; there
   is no point spending 130Wh/day of supervision to save 600Wh/day of losses when
   35Wh/day buys the same result.
5. **No cloud, no internet dependency, ever.** Including for provisioning.

---

## 6. AC inverter arbiter — specification

> **`RECONCILED 2026-08-25.` `docs/ANALYSIS-2026-08-20.md` is still the
> reasoning behind this section, but the patches it called for are now applied
> here and in `components/ac_arbiter/`.** `fridge_req` is a block scheduler
> (P2), sleep mode is the scheduler with its schedule switched off (P3), and
> the thermal-budget table premised on a cycling compressor is gone rather than
> restated. What remains outstanding is measurement, not specification: the
> block lengths are `UNVERIFIED` defaults.

Three independent request flags, ORed by a single 5s interval, with a fail-safe
override on top.

```
ac_on = force_on
        OR fridge_req
        OR manual_req
        OR surplus_req
```

**Future (Phase 4, optional, default OFF):** a `drive_inhibit` suppressor gates
everything except `force_on` — see §9 Phase 4 "Drive-time AC inhibit". It is
noted here so this equation is not read as final:

```
ac_on = force_on
        OR ( (fridge_req OR manual_req OR surplus_req) AND NOT drive_inhibit )
```

**And `parked` gates everything, `force_on` included** — the only suppressor in
the system that outranks the fail-safe. The complete equation is:

```
ac_on = parked ? false
      : force_on
        OR fridge_hard
        OR ( (fridge_req OR manual_req OR surplus_req) AND NOT drive_inhibit )
```

### `force_on` (fail-safe override)
True if any of: node just booted; BLE `connected` false; **station data stale
> 60s while the link still claims to be connected**; fridge temperature sensor
stale > 5 min; arbiter watchdog expired.

**Read §5.2 for what these can and cannot do.** They are not equivalent. The
BLE-lost branch cannot power anything — with the link down the write goes
nowhere — so it is recovery-on-reconnect: it holds the request true so AC
returns the instant the link does. The **station-stale branch is the one real
fail-safe**, because it fires while there is still a link to carry the command.
A wedged-but-open link was previously invisible here: the local probe kept the
thermostat running happily and it went on commanding a switch nobody was
listening to.

### `fridge_req` — `REWRITTEN 2026-08-20, IMPLEMENTED 2026-08-25`

See `docs/ANALYSIS-2026-08-20.md` §4 for the reasoning and
`components/ac_arbiter/arbiter_core.cpp` for the state machine.

**This section previously specified `fridge_req` as a follower of the
compressor. There is nothing to follow.** The fridge is an
**ESSENTIELB ERT85-55mib6 with a variable-speed inverter compressor**: it
modulates its speed against accumulated heat with hours of lag, and at high
ambient it does not stop at all. The old release condition
`output_power < 15W` **can never be satisfied while the compressor modulates**,
so anything built from the old spec latches `fridge_req` true forever. Duty
cycle is not a usable diagnostic for this appliance either — see §8.4.

`fridge_req` is therefore a **block scheduler with a temperature guard**, not a
compressor follower. The supervisor chooses the cycles; the appliance no longer
does.

- **Run/rest block schedule.** Blocks of **20–30 min minimum**, not 5 —
  inverter compressors dislike frequent restarts, and each restart costs a
  pressure-equalisation penalty that scales with cycle *count*.
- **Temperature is an override ceiling, not the primary input.** Cabinet
  temperature above the ceiling forces a run block regardless of schedule.
  This inverts the old design, where temperature drove the request directly.
- **Hard override: temp > 10.0 °C forces true regardless of anything else.**
  Unchanged.
- **Anti-short-cycle:** minimum OFF time before re-energising, now sized to the
  block length rather than the old 5 min.
- Every threshold and both block lengths are tunable ESPHome `number` entities,
  not magic constants.
- **Prefer fewer, longer blocks.** Added thermal ballast remains **rejected** —
  the volume is needed for food. The contents are the only thermal mass
  available, and the 9 h power-failure rating says that buffer is ample.

**There is less temperature headroom than this document used to assume.**
The cabinet sits at **7–8 °C with the mechanical thermostat at its warmest
setting** (`MEASURED` 2026-08-19), not at the 4 °C setpoint §6 was written
around. 7–8 °C is already at the top of the safe band for dairy and meat, so
any ceiling must be set against where the cabinet actually sits. Calibrate the
DS18B20 against a reference thermometer before trusting any ceiling — see §2.

**The DS18B20 is now load-bearing, not a convenience.** With no electrical
signature of cooling state, cabinet temperature is the arbiter's *only*
feedback channel. The two primary probes are required; the optional third
(free-air, door detection) stays optional.

**The state machine is implemented; the *numbers* are still blocked.** Both
block lengths ship as `UNVERIFIED` defaults (30 min each) and the two
measurements below are what turn them into engineering rather than guesses:
1. **`tools/fbot_probe.py idle-test`** — splits the measured ~50 W station
   overhead into inverter idle and station base load. Below ~11 W of inverter
   idle, imposed cycling stops paying and the answer is a 12 V compressor
   fridge instead (ANALYSIS §4.3, §5).
2. **Overnight log** — how long the fridge stays off once it stops. This no
   longer decides Strategy A *versus* Strategy B: the implementation takes both,
   because there was never a reason to choose. A block ends on **any** of cold,
   the block timer, or the compressor genuinely stopping — so a night where the
   compressor does stop is harvested for free, and a day where it never stops
   is still cycled. The measurement now tunes rest-block length rather than
   selecting an architecture.

   **The old spec required the compressor to stop, and that was the bug.**
   Requiring it on an appliance that never stops meant `fridge_req` latched
   true forever and the inverter ran 24/7. `OR`, not `AND`.

The **pulldown penalty of imposed cycling is `UNVERIFIED`** and the entire
Strategy B saving rests on it. Estimated 15–30 %; measure it with the A/B test
in §9 Phase 0 before committing to block lengths.

### `manual_req` (cooking button)
- Short press → true, 45 min timer.
- Short press while active → +30 min.
- Long press (>1s) → cancel immediately.
- Auto-release: after ≥10 min elapsed, if `output_power` < 150W continuously for
  10 min → clear. The 150W floor sits well above the fridge compressor (35W) and
  far below an induction plate or kettle, so a running fridge cannot hold the
  timer open. With a compressor this small the floor could drop to ~100W if
  finer discrimination is ever needed.
- Hard ceiling: 3h regardless.
- Feedback: RGB LED — green = manual active, amber = fridge-driven, off =
  inverter down. Buzzer beep 2 min before auto-release.
- Debounce: `delayed_on: 50ms` in software is sufficient on its own — it rejects
  both contact bounce and induced transients, since a spike would have to persist
  50ms to register. The optional 100Ω series resistor (and 100nF) are for **GPIO
  protection**, not debounce, on a long run past the inverter. Both retrofittable
  at the board end if phantom presses ever appear. A phantom press arming the
  inverter for 45 min is a leak that goes unnoticed for days, so watch for it.

### `surplus_req` (opportunistic)
True when `input_power - output_power` exceeds a margin and SOC is high — solar
that the MPPT would otherwise throw away. Drives thermal banking (§9) and the
water heater. **Suppressed entirely during sleep mode.**

### Sleep mode

The P310 lives under the bed. Its fan cycles because of heat generated by the
**~48W station overhead** (`MEASURED`, §1), which runs all night regardless of
what the fridge is doing. Sleep mode is therefore not primarily about
suppressing compressor cycles — it is about removing the continuous idle heat
source. This is likely the single biggest quality-of-life win in the project.

**`SIMPLIFIED 2026-08-20` — see `docs/PATCHES.md` P3. Sleep mode got easier,
not harder.** It used to be an attempt to suppress cycles the appliance chose.
The appliance chooses nothing now (§6 `fridge_req`): the supervisor picks the
blocks, so sleep mode is just **a pre-cool block, then no scheduled blocks
until morning.** No new machinery — it is the ordinary scheduler with its
schedule switched off and its ceiling raised.

Sequence:
1. **Pre-cool** in the hour before sleep, while noise is irrelevant: run a
   block down to 1 °C.
2. **Coast** through the night. No scheduled blocks at all; only the raised
   ceiling (6–8 °C, configurable) can start one.
3. **If the ceiling is reached**, the run goes all the way back to 1 °C — not to
   the normal 4 °C setpoint. Maximum remaining coast from a single run.
4. **Exit** on schedule or button press; the normal schedule resumes.

The old "at most one compressor cycle" goal is retired: it was framed around an
appliance that cycles on its own. The goal now is simply **no scheduled block
between roughly 23:00 and 06:00**, which the supervisor controls outright. The
only thing that can break the silence is the ceiling, and that is a food
decision, not a scheduling one.

**Coast budget: `UNVERIFIED` pending the dT/dt measurement (§8.5).** The old
table here assumed a ~25W heat leak and produced coast times of 1–5.4h
depending on fill. Those numbers were never measured and the assumed leak came
from the same fixed-speed-compressor model that §1 has now discarded, so they
are removed rather than restated. The adaptive prediction below measures the
real figure every night for free — which is the honest way to fill this in.

### Adaptive prediction
Measure dT/dt over the first 30 min of coast, extrapolate to the ceiling, and
display *"silent until ~04:20"*. Three benefits: the user gets the truth rather
than a promise the automation cannot keep with a near-empty fridge; the sleep
window self-adjusts to load and ambient; and it yields a **live heat-leak
measurement every night for free**, which is what §8.5 otherwise asks the user
to go and measure deliberately.

**Constraints:**
- Pre-cooling to 1 °C will freeze produce and eggs against the back wall. Ballast
  goes at the back, produce at the front.
- An 8 °C overnight ceiling is above the ideal band for meat and dairy. Either
  cap at 6 °C or keep those items elsewhere. The automation must not make this
  choice silently — surface the configured ceiling on the display.
- **The 10 °C hard override still applies during sleep.** If something fails at
  03:00, the compressor runs and the user gets woken. Silence is a preference;
  food is not.
- Sleep mode also blanks the display, suppresses `surplus_req`, and holds the
  water heater off.
- `ac_silent` on the P310 is worth enabling if ever charging from shore power
  overnight.

### Parked mode — `IMPLEMENTED 2026-08-21`

The van standing outside the house for days or weeks: fridge emptied and the
door propped open, nobody living in it, nothing that needs 230V. Sleep mode and
drive inhibit both *defer* compressor work and coast on the food's thermal
mass. Parked mode does neither, because there is nothing in the fridge to
coast.

**This is the one mode where §5.2 is inverted, and the inversion is the whole
feature.** Everywhere else, "fail toward powered" is correct because a bug that
silently kills the fridge costs a fridge full of food. Parked, that reasoning
runs backwards:

| | Fridge loaded | Fridge emptied, parked |
|---|---|---|
| Cost of a stuck-ON inverter | ~48W, annoying | ~48W × weeks — **pack flat** |
| Cost of a stuck-OFF inverter | spoiled food | nothing |

At the `MEASURED` ~48W station overhead, a 3.9 kWh pack held on by a BLE
dropout runs flat in roughly **three days** with no solar, and then sits at 0%
until someone notices. Leaving a LiFePO4 pack flat is the one outcome in this
project that damages hardware rather than food. So while parked, **BLE loss, a
dead probe, a starved loop and the 10 °C hard override all resolve to OFF.**

**Everything that makes this safe is at the entry gate.** Once parked, the
arbiter is not protecting anything and cannot be argued out of it — so the only
question that matters is whether the mode can be entered by mistake.

#### Arming interlock
An emptied fridge with its door propped open equilibrates to cabin ambient; a
loaded, working one does not. **Refuse to arm while the cabinet reads more than
`parked_arm_delta` (default 3 °C) below cabin.** Refuse equally when either
probe is missing — *"I cannot tell whether there is food in there"* is a
refusal, not a shrug. This is the whole reason the cabin DS18B20 stopped being
a nice-to-have.

The refusal is audible (double chirp) and logged with both temperatures,
because the screen is likely blank and the user is about to walk away believing
the van is parked.

`Park (force)` exists as a separate deliberate entity for when the interlock is
wrong — an unplugged probe, a fridge already at room temperature for other
reasons. It is not a flag on the switch: forcing it must always be a decision.

#### Entering
- **Gesture:** bezel sleep button, ≥5s (1–4s is still sleep mode). Or the
  `Parked mode` switch in the web UI.
- Shed `usb`. **Never `dc`** — the 12V habitation bus, and therefore van-core
  itself, hangs off it; shedding it is unrecoverable without walking to the
  P310 (`measurements.md` 2026-08-18).
- Cap `threshold_charge` at `Parked charge max` (default **80%**). Solar-only
  at home with no shore power, so the charge cap is the only calendar-ageing
  lever there is — and dropping it to 60% also drops the buffer against a
  fortnight of December. Lower it deliberately for winter storage, not by
  default. Restored to 100% on exit.
- Blank the display; suppress the buzzer.

#### While parked
- **A dim blue blink on the kitchen button LED, ~1s in 4.** In a dark van with
  a blank screen this is the only sign that the fridge is deliberately dead,
  and it is the mitigation for the one real hazard below.
- The display page still says `PARKED n.n d — FRIDGE OFF` when woken.
- The arbiter keeps re-asserting AC OFF once a minute, so a poke at the P310's
  own front panel does not quietly re-energise the inverter for a fortnight.
- All arbiter timers are rolled forward every tick, so the mode can be left at
  any hour of any week without inheriting an anti-short-cycle lockout or a
  stale-probe trip from storage.

#### Leaving
- Any of: the bezel gesture again, the web switch, **or a press of the kitchen
  cooking button** — someone back in the van wanting 230V is a deliberate press
  of a dedicated button, so treat it as the exit gesture rather than ignoring
  it and looking broken.
- Exit re-arms the boot force-on window: AC comes on immediately and the
  thermostat takes over once the probes have reported. Coming out of parked
  mode is exactly when food gets loaded, and a cabinet sitting at 20 °C should
  not wait on a poll cycle.

#### Persistence, and why it is not a `restore_mode`
Parked state lives in a `restore_value` global, restored explicitly at
`on_boot` priority −100. A switch's own `restore_mode` would fire its
`turn_on`/`turn_off` action at boot — running the arming interlock against
probes that have not reported yet, which refuses, and so **silently un-parks a
van that is meant to stay parked for three weeks** on any brownout. That is the
subtle failure this design exists to avoid; do not "simplify" it back.

A reboot while parked therefore pulses AC on for the fraction of a second
before the global is read. Accepted, and visible in the log as `ON` immediately
followed by `OFF (parked)`. The alternative is holding AC off until a flash
read completes, which puts a storage feature on the fridge's critical path.

#### The real hazard: parking, then loading food
The interlock catches "arm it with food inside". It cannot catch "arm it
correctly, then load the van for a trip a week later and drive off". That is
the two-days-of-spoiled-food failure of §5.2 arriving through a side door.

Mitigations available today: the blue LED, the display page, and the pre-trip
checklist in §11. **The proper mitigation is Phase 4** — `van-vehicle` runs on
switched ignition, so its very existence is a key-on signal, and key-on should
clear parked mode. Until then this is a procedural guard, not an engineered
one, and it is stated here rather than papered over.

#### Open — `UNVERIFIED`
- **van-core's own standby draw while parked.** With AC off, the load is the
  node plus the station's base consumption, and the station's share is exactly
  the unknown that `tools/fbot_probe.py idle-test` exists to split out. Until
  that number exists, the achievable parked duration is a guess. Measure it as
  SOC slope over 48h.
- If that draw turns out to dominate in winter, the remaining lever is
  duty-cycled deep sleep on van-core (wake every 30 min, check SOC, sleep).
  That costs the web UI and the BLE link between wakes and is **not** worth
  designing before the measurement exists.

---

## 7. MiBoxer E2-WR — three routes

Confirmed: WiFi + BLE + 2.4G RF, Tuya Smart provisioning, compatible with the
Mi-Light / MiBoxer 2.4GHz remote family, 30m repeat between fixtures.

- **A — RF remote only.** Zero infrastructure, zero standby from the control
  side, works when everything else is dead. **Start here.** Not every subsystem
  needs to be in the supervisor.
- **B — `esp8266_milight_hub` + NRF24L01+.** ~€4. Emulates the MiBoxer remotes,
  exposes an HTTP REST API. `van-core` drives it via `http_request` over the
  SoftAP. Physical remotes keep working in parallel. Enables e.g. "dim to 20%
  when SOC < 30%".
- **C — LibreTiny.** The Tuya module is very likely a BK7231N (WB3S/WBR3/CB3S),
  which LibreTiny supports with ESPHome. **Before choosing this, open one and
  check whether the RF antenna traces go to the Tuya module or to a separate
  MCU.** If separate (typical), the Tuya module is WiFi-only and talks to the
  main MCU over the Tuya serial protocol → ESPHome's `tuya` platform speaks that
  natively, so you replace only the WiFi brain and keep PWM + RF intact. If
  single-chip, you lose RF; fall back to B.

### Route D — join them to `van-core`'s SoftAP on stock firmware? `NO`

Asked directly, so recorded. **Associating is not controlling**, and both halves
fail independently:

- **Provisioning needs the cloud.** Tuya activation requires reaching Tuya's
  servers; without it the device never completes pairing, so it will not even
  reach the association step in the van. *Workaround:* pair at home, then set
  `van-core`'s SoftAP SSID and password identical to the house network so the
  device believes it is on the same LAN. This solves association only.
- **`van-core` still cannot speak to them.** Commanding a Tuya device on the LAN
  means the **Tuya LAN protocol** — AES-128, CRC, JSON on port 6668, keyed by a
  `local_key` obtainable only through a Tuya IoT developer account at pairing
  time. **ESPHome has no client for this.** Note the trap: ESPHome's `tuya`
  component is the *serial* protocol between a Tuya WiFi module and a co-MCU
  (which is what makes route C work) — it is not a LAN client, despite the name.
  Implementing the LAN stack in C++ on the node that must not starve its BLE task
  (§2) is the wrong place to spend that risk.

**Route C is the clean version of what this question wants:** reflashed, they are
ordinary ESPHome nodes and join the SoftAP like any other. See BOM D1f — BK7231N
is LibreTiny's *best*-supported target, which is why this is the sanctioned
hacking target and the Meross is not.

### Standby power may decide this before control does

**Measure first:** an unprovisioned Tuya module can idle at 0.5–1W while
scanning. Three of them ≈ 40–70 Wh/day for nothing. Meter one before deciding.

These are **permanently powered on the 12V habitation bus** — they must be, to
receive an "on" command — so this is continuous draw, not occasional. At 3 × 1W
it **exceeds the entire §5.4 2W control-system budget on its own**, before
`van-core` is switched on, and costs ~7–14% of the project's whole saving to run
light switches that are off.

Two consequences:
- This is an argument *for* route A or B, where the WiFi radio is never used.
- Counterintuitively, **provisioning might lower standby** rather than raise it —
  an associated idle radio can draw less than one endlessly scanning. But a
  device associated yet unable to reach the cloud may instead retry hard and cost
  more. `UNVERIFIED both ways` — this is a meter question, not an argument.

---

## 8. Open questions — verify before building

Ordered by how much they'd change the design.

1. ~~**Does the fridge auto-restart after power is restored?**~~ **ANSWERED:
   yes** — restarts automatically on the medium-cold setting. Project premise
   holds.
2. **Actual inverter idle draw.** Stated 35W (manufacturer).
   `CORRECTED 2026-08-19 — the method below was wrong.` This used to read
   "AC on, nothing plugged in, read `system_power` / `output_power`", on the
   assumption that ESP-FBot's `system_power` (input reg 21) reported the
   station's own consumption. **It does not: reg 21 is not a power at all**
   (reads 2285 with AC input present, 14–24 without, regardless of charge rate
   — almost certainly AC input voltage ×0.1). See `docs/ANALYSIS-2026-08-20.md`
   §2 and `docs/ble-registers.md`.
   **No register found so far reports the station's own draw.** Every power
   register is an external input or an external output; the ~50W of overhead is
   only visible as the SOC balance not accounted for by any of them, which is
   what `tools/fbot_probe.py idle-test` measures. Fan behaviour and ambient
   heat may push it above spec in an August van.
3. ~~**Is the 35W fridge figure average or compressor-running power?**~~
   **ANSWERED: 35W is the running power.** The compressor is small, so the
   inverter idle equals the useful load.
4. **Fridge duty cycle.** *Now the single most important unknown* — it scales
   the entire saving. Log `output_power` for 24h with the inverter permanently
   on and count minutes-on-per-hour. Expect 35% in mild conditions, possibly
   50–60% in a van at 35 °C.
5. **Thermal coast rate.** With the fridge at 1 °C and the compressor locked
   out, log the temperature rise. Gives the real heat-leak figure and therefore
   the achievable sleep-window length. Repeat at different fill levels — coast
   scales with contents mass.
6. **Condenser ventilation — check this before writing any firmware.** Van
   installs routinely choke the condenser coil in a sealed cabinet, raising
   condensing temperature and pushing duty cycle 1.5–2× above what the same
   fridge achieves in open air. Fixing it costs no interior volume (low and high
   vent grilles, optionally a small 12V thermostat-controlled fan) and improves
   energy use, coast time and night-cycle count simultaneously. **Every other
   lever in this project is downstream of it.** If measured duty cycle exceeds
   ~45% in mild weather, poor condenser airflow is the first suspect, not the
   fridge.
7. **Sender step map — bench both senders, in air, before either is fitted.**
   Supersedes "measure at empty and full": if these are reed ladders (§2) there
   is no continuous curve to measure, only a set of plateaus, and endpoints
   alone would hide how many there are.

   **Procedure — five minutes, a multimeter, no water.** Hold the stem
   vertical, slide the float slowly from bottom to top by hand, and record
   every resistance the meter settles on plus roughly where on the stem the
   float was when it changed. Repeat downward.

   It answers four questions at once:
   - **Construction.** Discrete plateaus that snap between values confirm the
     reed ladder. A smooth continuous sweep means it is a wiper sender after
     all — in which case the corrosion argument retired in §9 Phase 2 comes
     back and the excitation gating is protection again, not just housekeeping.
   - **Step count**, which *is* the resolution of the whole channel. Nothing
     downstream can improve on it.
   - **Direction and range.** Either **0–190Ω (European/VDO)** or **240–33Ω
     (US/GM)** — the second falls with level. A 240–33Ω sender read with a
     0–190Ω map reads *backwards*, which the §9 cross-check would report as a
     tank swap.
   - **Hysteresis.** Up and down sweeps rarely switch at the same point. If the
     gap is a large fraction of a step, the level display needs the deadband or
     it will flicker between two values on a parked van.

   Two senders out of the same bag can still be different parts. Bench them
   **separately**, and record both maps in `docs/measurements.md` against the
   tank each is fitted to.
8. **Grey tank: capacity, geometry and location.** Litres, and whether it is
   internal or underslung — see §9 Phase 2. Decides the cable run, whether
   freezing is in scope, and how nonlinear the sender curve is near the ends.
   `VERIFY` before ordering cable.
9. **The kit gauge's off-state input impedance.** Go/no-go for wiring it in
   parallel with the ADC as a backup readout (§9 Phase 2). Meter its sender
   terminal with the button released: megohms means it is invisible when off
   and only the button window needs handling; a low resistance means it sits
   permanently across the sender and the idea needs re-thinking rather than
   protecting. Five minutes on the bench, before any of the protection parts
   are fitted.
10. **Grey sender fouling rate.** The one genuinely new failure mode the grey
   tank adds (§9 Phase 2). Not answerable up front — it is a "re-read the
   cross-check log after a month of use" question, and the answer decides
   whether the capacitive-strip retrofit is ever bought.
11. **E2-WR internals** — single-chip or Tuya-module-plus-MCU (see §7C).
12. **E2-WR idle power**, unprovisioned.
13. **2.4GHz link quality with the inverter under load** — decides whether the
    RS485 escape hatch gets pulled forward.
14. **van-core's own standby draw in parked mode.** With AC off, the only loads
    left are the node and the station's own base consumption — and the
    station's share is precisely what `tools/fbot_probe.py idle-test` exists to
    separate out (§8.2). Until both numbers exist, how long a van can sit
    parked on solar in December is a guess. Measure as SOC slope over 48h with
    parked mode armed. Decides whether duty-cycled deep sleep on van-core is
    ever needed (§6 "Parked mode").

---

## 9. Feature roadmap

### Phase 0 — no microcontroller required (do this first)

None of the following needs the ESP32, and several could still change the design.

**Protocol exploration (Android):**
- **nRF Connect for Mobile** — scan, connect, browse GATT services and
  characteristics, enable notifications, write raw hex. Establishes which
  characteristic is write and which is notify.
- **Bluetooth HCI snoop log** — enable in Developer Options, drive BrightEMS
  normally (AC on, AC off, DC on, charge limit change; one action at a time with
  pauses), pull `btsnoop_hci.log`, open in Wireshark filtering ATT writes. This
  reveals **exactly what the official app sends**, and verifies ESP-FBot matches
  this unit's firmware revision before any hardware is committed.
- Cross-reference against ESP-FBot's C++ source to learn the frame format
  (typically header, command byte, payload, CRC).
- **Python + Bleak** to prototype on a computer — a ~40-line script that connects
  and toggles the inverter proves the concept end to end. On iOS, LightBlue
  browses GATT but there is no snoop log without a Mac and PacketLogger.

> **Cautions.** One BLE connection at a time — nRF Connect and BrightEMS cannot
> both hold the link. **Replicate, never fuzz:** only send byte sequences
> observed from the official app. Writing arbitrary values to unknown
> characteristics on a device whose BMS holds charge thresholds and protection
> settings is not worth the risk.

**Measurements needing no electronics:**
- **Condenser airflow** (§8.6) — inspect how the fridge is built in. Costs
  nothing, potentially the largest single win in the project.
- **Manual switch temperature** after 10 min of charging — a safety issue
  independent of this project.
- **D3** — does the P310's 12V output stay on at ~100mA? A phone on a USB car
  adapter is approximately that load. Phase 1 blocker.
- **Duty cycle** — a €12 plug-in energy meter between the P310 and the fridge
  gives duty cycle and daily consumption directly, today. **Every saving estimate
  in this document depends on this number.** If only one thing is done before
  parts arrive, this is it.

### Phase 1 — core (the whole justification)
- `van-core`: ESP-FBot BLE link, fridge DS18B20, arbiter, manual button, display.
- `web_server` + SoftAP so there's a UI with no HA and no router.
- **On-board data logging to microSD** — see §12 below.
- **Before writing the arbiter:** log `output_power` for 24h with the inverter
  permanently on. That single dataset answers §8.2, §8.4 and §8.5 and turns the
  predicted saving into a measured one.

### Data logging (Phase 1)

The chosen board has a microSD slot. Use the `sd_mmc_card` external component
(minimum ESPHome 2025.7.0, supports ESP32-S3), which also ships `sd_file_server`
— a web page to browse, download and delete files. **CSVs come off over the
SoftAP from a phone; no card removal, no laptop in the van.**

**Log line every 30s, daily rotation (`YYYYMMDD.csv`):** timestamp, SOC,
`input_power`, `output_power`, all temperatures, arbiter flags
(`fridge_req` / `manual_req` / `surplus_req` / `force_on`), inverter state, sleep
mode. This single table answers duty cycle, coast rate, heat leak and realised
saving — every open question in §8 at once.

**Risks to clear during the 24h BLE soak — add the SD component to that same test
config rather than discovering these later:**
- **Framework conflict.** The `sd_mmc_card` docs show `framework: type: arduino`;
  the ESP-FBot example targets esp-idf. Both must build under one framework on
  this node. `UNVERIFIED`.
- **Memory cost.** The component requires disabling ESPHome's VFS and LWIP
  memory-saving options and explicitly including the `fatfs` and `spiffs` IDF
  components — real RAM and flash on top of BLE, SoftAP and the display. The 8MB
  PSRAM helps; the soak should exercise it.
- **RTC required.** No internet → no SNTP → timestamps would be ms-since-boot,
  useless for correlating a coast test with time of day. DS3231 (BOM item 15)
  keeps time across reboots and resyncs from SNTP when parked at home.

**Write discipline:** wrap every write so a card failure is non-fatal. An
unmounted or full SD must never stall the arbiter — same node-autonomy rule as
everything else.

### Mechanical — carrier board and enclosure

**Carrier board.** Perfboard or a small custom PCB with female headers for the
ESP module and 3.5mm pluggable screw terminals for all external cabling. The ESP
module then becomes replaceable without touching a wire, and screw terminals need
no crimp tool for field repairs.
- **Key connectors by pin count:** 2-pin = 12V in, 3-pin = 1-Wire bus, 4-pin =
  button. Physically impossible to mis-plug — no labels needed to get it right at
  11pm in a car park.

**Enclosure — 3D printed, screen on a hinge.**
- **PETG minimum, ASA if it gets sun. Never PLA** — glass transition ~60 °C is
  exceeded in a parked van in August; the hinge sags and the bezel warps. This is
  the commonest failure mode of printed van parts.
- **Hinge friction matters more than smoothness.** A loose hinge drifts with road
  vibration until the screen faces the floor. Use an M3 screw with a nyloc nut and
  nylon washer as the pivot rather than a print-in-place hinge, so friction is
  adjustable and re-tightenable.
- **M3 heat-set inserts, not self-tappers.** Threads cut directly in PETG strip
  after a handful of open/close cycles, and this box gets opened more than
  expected.
- **Light seal around the display bezel** — backlight leaking from a printed
  enclosure at night defeats the point of blanking the screen.
- Cable entries through glands, vents for the buck converter.

### Phase 2 — services
- **Heater: an ESPHome-preflashed smart plug** (Athom or equivalent), in the wall
  socket already there, as its own node. `REVISED TWICE` — this section once said
  "dumb GPIO + optocoupler, **not** a smart plug", on the grounds that a
  mains-powered relay reboots whenever the arbiter cycles the inverter. That
  objection does not survive the fact that **the heater cannot heat without AC
  anyway**, so the relay has no useful state to hold while the inverter is off.
  A later revision added vibration and current-rating objections to plug-form
  devices; those are **retracted** — the heater has been running through a
  plug-in Meross in this van without trouble. See BOM D1 and D1e.
  `restore_mode: ALWAYS_OFF` puts the fail-OFF property in git, and the plug's
  metering is the `P_heater` term the estimator needs.
- **Heater safety constraint — `ACCEPTED RISK`, see BOM D1c.** The AC supply
  floats and the earthing will not be modified, so there is no RCD behind the
  heater. Automating it adds no *electrical* risk (it already ran on this supply
  manually); what it adds is unattended operation. The D1b permit rules — SOC
  > 85% **and** charging or strong sun — keep heating to times when someone is
  present. **That constraint now does safety work as well as energy work: do not
  relax it, and never heat during sleep mode.**
- `van-water`: **two** level senders — **fresh and grey** — on one ADS1115.
  **No heater involvement** — it reduces to the two tank senders alone.
- Sender conditioning, identical on both channels: excite from a GPIO only
  during a reading, median filter ~30 samples, and a 60s window (sloshing while
  driving makes raw readings useless) reduced by **median, not mean** — see the
  quantisation note below. Calibrate against **actual litres poured**, not the
  nominal curve. For grey, "poured" means measured litres down the sink with
  the dump valve shut; the procedure is the same.

### Grey water tank — `ADDED 2026-08-25`

Two senders are owned, so both tanks get instrumented. **The second tank costs
one resistor**, because the ADC, the node, the enclosure and the excitation
gate are all already there for the first — which is the whole argument for
doing it now rather than "later".

**Channel map on the single ADS1115** (address 0x48, single-ended):

| Ch | Signal | Why |
|---|---|---|
| A0 | Fresh sender | |
| A1 | Grey sender | |
| A2 | **Fresh excitation rail sense** | Makes the reading *ratiometric* — level comes from `V_sender / V_excite`, so supply droop, regulator tolerance and the driving pin's own drop cancel instead of appearing as a level change |
| A3 | **Grey excitation rail sense** | Each sender is excited by its own pin, so each needs its own rail reference |

A0/A1 single-ended is right **only if both senders get their own return wire
back to the node's ground star point.** `CONFIRMED 2026-08-25` — both senders
have return lines, neither grounds through its tank flange, so single-ended it
is and all four channels are used as above.

Recorded because it was a live question and the answer could change on a
re-fit: had either sender grounded through its flange to the chassis, the two
differential pairs (A0–A1, A2–A3) would be required instead — Phase 4 puts up
to 100A of alternator current through that chassis, and tens of mV of ground
drop is ~3% of tank on a 0–190Ω sender. That route costs the ratiometric
channels, which is why fixing it at the sender beats compensating in the ADC.

At 3.3V excitation through a 220Ω divider, a 0–190Ω sender spans 0–1.53V. PGA
`±2.048V` → 62.5 µV/LSB, i.e. ~0.004% of tank per count. **The sender is the
error term, not the ADC.**

**And with a reed ladder (§2) that error is quantisation, not analogue
inaccuracy.** One step per reed switch — typically 6 to 12 over the stem — is
the resolution of the entire channel, and no amount of ADC bits or averaging
improves it. Three things follow, and they are not what an analogue sender
would want:

- **The 16-bit ADC is now overkill for resolution — keep it for
  discrimination.** Its job changes from resolving a smooth curve to confirming
  that each reading lands *exactly* on a known plateau. A value between
  plateaus is then unambiguous evidence of a fault rather than a plausible
  intermediate level, which is worth more here than fine resolution ever was.
- **A reading above the top plateau means "between reeds", not "empty".** Where
  the magnet sits in a gap the ladder can go open circuit, and the ADC then
  sees the full excitation rail — indistinguishable from a disconnected sender
  and easily mistaken for a tank endpoint. Reject out-of-band readings
  explicitly; never clamp them into range.
- **Median, not mean, across the throttle window.** Sloshing moves the float
  between adjacent plateaus, and the mean of two plateaus is a voltage the
  sender can never produce. The median of a quantised signal is always a real
  plateau; the mean is an artefact. This matters more than it sounds — a
  fictitious value defeats the plateau check above.

**Calibration is therefore a step map, not `calibrate_linear`.** Bench each
sender in air first (§8.7) to get its plateaus, then pour measured litres to
find the volume at which each transition happens. Interpolating between
plateaus would assert a precision the sender does not have: between two
transitions the level genuinely is unknown, and the display should show the
step's range rather than invent a midpoint.

**Excitation is switched by a GPIO per sender — no MOSFET.** `REVISED
2026-08-25.` This section previously specified one logic-level MOSFET gating
both senders. **The MOSFET is not needed:** at 3.3V through a 220Ω divider the
excitation current is **15 mA worst case** (sender at 0Ω; 13 mA for a 240–33Ω
part at full), which an ESP32-C3 pin sources directly — ~20 mA is comfortable,
40 mA the absolute maximum. The MOSFET was buying neither isolation nor current
capability, and at 3.3V there is no level shift to do.

- **One pin per sender, excited in sequence**, not one pin gating both. Keeps
  pin current at 15 mA rather than 30, and each sender is read with the other
  de-energised, so there is no crosstalk through the shared ground return.
- **The pin's own drop under load is cancelled by A2/A3.** A GPIO high is not a
  clean 3.3V at 15 mA, which would matter if the level came from an assumed
  rail voltage. It comes from `V_sender / V_excite`, so the drop divides out —
  this is the ratiometric channel earning its place a second time.
- **Drive the idle pin LOW, never high-Z.** Low leaves the ADC node defined at
  ~0V through the divider and 0V across the sender. An input-mode pin lets the
  ADC node float and the reading becomes noise.

**Why the gating survives anyway — but on a much weaker argument.** `REVISED
2026-08-25.` Its original justification was that continuous DC through a wetted
wiper in an electrolyte erodes it, and that grey water being the better
electrolyte made the gating more load-bearing on that channel. **A reed ladder
(§2) has no wetted contact at all — the resistor chain is sealed dry inside the
stem — so that argument is retired in full, not softened.**

What is left is housekeeping: 15 mA at 3.3V is 50 mW per sender, 100 mW for
both, or ~2.4 Wh/day held on continuously. Against the §5.4 two-watt control
budget that is 5% spent on nothing, and gating it away costs one pin state.
Keep it, but **do not describe it as protecting the sender.**

If the §8.7 bench test shows a continuous sweep rather than plateaus, the
senders are wiper types after all and the corrosion argument comes straight
back — at which point the gating is protection again and the analogue gauges
below stop being optional.

#### The kit's gauge, in parallel — `YES, with three conditions`

`ADDED 2026-08-25.` The kit's gauge is **digital and wired behind a momentary
button**, so it is powered only when someone asks for a reading. That settles
the standing-current objection outright: **zero draw when not pressed**, so it
never appears on the §5.4 control budget, and the earlier worry about a gauge
holding its sender energised from 12V continuously does not apply to this one.

Keeping it is worth more than a spare readout:

- **It works with the node dead**, which is the whole point of a backup, and it
  needs neither the SoftAP nor a phone.
- **It is an independent second opinion on the same sender.** During the
  poured-litres calibration it interprets the identical resistance through
  completely different hardware, which is exactly what catches a wrong step map
  — an error no amount of self-consistency in the ESP32's own reading can
  reveal.

**But it must not simply be paralleled onto the ADC node.** Both the gauge and
the divider land on the same sender terminal, and the gauge drives its own
current from 12V:

| Gauge state | Node voltage the ADS1115 sees |
|---|---|
| Off | Whatever its input presents unpowered — **`UNVERIFIED`, condition 1** |
| On, sender at 190Ω | ~3.4V against a 3.6V absolute maximum. Marginal |
| **On, sender open between reeds** | **12V. Over 3× the absolute maximum** |

That last row is the one that matters, because on a reed ladder an open circuit
between steps is a **normal state, not a fault** (§2). Left unprotected the
input dies silently on some ordinary button press, and the sender gets blamed.

**Condition 1 — measure the gauge's off-state input impedance before anything
else.** With the button released, meter its sender terminal to ground and to
its own supply. High impedance (megohms) means it is invisible to the ADC when
off and only the button window needs handling. If it presents a low resistance
instead, it sits permanently across the sender, shifts every reading, and the
whole idea needs re-thinking rather than protecting. This is a five-minute
go/no-go and it comes before the two below.

**Condition 2 — protect the ADC input in hardware.** **10k in series** between
the sender node and the ADS1115 pin limits a 12V fault to 1.2 mA, well inside
the ±10 mA input limit, and it is negligible against the ADC's megohm-class
input. Add an explicit **Schottky clamp to the 3.3V rail** rather than leaning
on the ADS1115's internal ESD diodes: those exist for one-off events, and this
condition recurs on every button press. A **100nF at the pin** is free while
the resistor is there — with 10k it settles in 1 ms against a ~500 ms
excitation burst, so it costs nothing and helps.

**Condition 3 — the button switches both, and firmware is told.** Use a
**DPDT** button: one pole powers the gauge, the other pulls a GPIO. Firmware
then drops its own excitation and marks the channel invalid for the duration,
so the window is a *known gap* rather than a corrupted sample.

The split of responsibility is deliberate and matches §5: **the hardware
protects the chip, the firmware protects the data.** Neither is load-bearing
for the other's job — a firmware bug must never be able to destroy an input,
and a stuck button must never be able to inject a plausible wrong level.

**Worth noting how well this composes with the quantisation rules above.** With
the ESP32's excitation off, its node sits at whatever the gauge is doing, which
is out of band by construction — and out-of-band readings are already rejected
rather than clamped. So the failure mode of forgetting condition 3 entirely is
a rejected reading, not a believed one. That is not a reason to skip it, but it
is the right direction to fail in.

#### What actually differs from the fresh tank

1. **The semantics invert, and that is a UI problem before it is a firmware
   problem.** Fresh: low is bad. Grey: high is bad. Two independent bars invite
   the user to read the reassuring one and get surprised by the other, so the
   display leads with the **binding constraint**:
   `usable = min(fresh remaining, grey headroom)`, in litres, with which tank
   is binding named next to it. The two raw levels stay available underneath.
2. **Fouling is the one genuinely new failure mode — and with a reed ladder
   it is mechanical, not electrical.** `REVISED 2026-08-25.` There is no track
   to erode (§2); what fouls is the sliding fit. Soap scum and grease build up
   on the stem and in the float bore, hair and fibres wrap the stem, and the
   float binds — reading whatever level it stuck at, typically full or parked
   mid-scale. This is *the* known failure of grey level sensing in RVs whatever
   the sensing principle, and it is a when, not an if.
   **Better news than a wiper sender, though:** a bound float usually frees
   with a flush and a wipe, where an eroded resistance track is permanent. So
   the recovery is maintenance rather than a replacement part — worth knowing
   before the capacitive-strip retrofit (BOM D5) gets bought on the first stuck
   reading.
   The response is still **detection, not avoidance** — see the cross-check
   below. Mount the sender away from the drain inlet so it is not sitting under
   the splash, and where the float can actually be reached to clean it.
3. **A stuck sender must not be able to take the water away.** See the pump
   interlock below.
4. **Location decides the rest.** Underslung: longer run, wet and salty
   environment, and freezing is in scope; internal: neither. Currently
   `UNVERIFIED` — §8.8. The fresh sender's "<1m run, no shielding needed"
   finding does **not** transfer to the grey channel until that is known.

#### The cross-check — free, and it earns its keep

Between dumps, grey should rise by roughly what fresh falls, minus what is
drunk, cooked with, or drained outside. So `van-water` integrates both and
flags divergence:

| Symptom | Reading |
|---|---|
| Fresh falls, grey flat | Fresh leak, or **grey sender stuck** — the fouling failure above |
| Grey rises, fresh flat | Inflow (rain into an open vent), or **fresh sender stuck** |
| Fresh rises while grey falls | **The two plugs are swapped**, or one sender is a 240–33Ω part read with a 0–190Ω curve (§8.7) |

That last row is why no keying scheme is specified for the two sender
connectors: both are 2-wire, so BOM item 18's "key by pin count" trick cannot
separate them, and the cross-check catches a swap on the first use of the sink
— loudly, and without extra hardware. Colour the two plugs anyway; do not rely
on it.

**State the limits honestly:** two ±5%-class senders averaged over 60s detect
gross divergence over hours. This finds a stuck float and a swapped plug. It
does **not** find a slow drip, and must not be described on the display as leak
detection.

#### Pump interlock (Phase 2b) — fails toward **PERMITTING** the pump

The RV convention is to inhibit the fresh pump when grey is full. **Rejected as
a hard cut here**, and per §5.2 the direction is stated rather than assumed:

- Overflowing grey is a nuisance and possibly a fine.
- No water in a van is a real problem, at an unknown hour, possibly nowhere.
- A fouled or disconnected grey sender reading full is **likely**, not
  hypothetical (point 2 above).

So a grey sender that is high, stale, or missing raises a **warning** — display
plus buzzer — and never opens the pump circuit. If a hard cut is ever wanted it
needs two independent conditions to agree, and it still expires on a timeout,
like the drive inhibit in Phase 4.

#### Node autonomy (§5.1) is unchanged
Both levels, the cross-check and the warnings are computed on `van-water` from
its own two ADC channels. Nothing here reads the network; core is told, not
asked.
- **Future: estimated water temperature on the display.** The heater tank is a
  sealed, isolated 230V unit — no draw-off during heating, so no unmodeled
  disturbance. Lumped thermal-capacitance model:
  `dT/dt = (P_heater − UA·(T_water − T_cabin)) / (m·c)`.
  **Runs on `van-core`**, which holds all three inputs already: it commands the
  heater, it carries the cabin/ambient DS18B20, and it reads `P_heater` from the
  plug's metering (better than nameplate — it also catches a dead element as
  "commanded on, drawing 0W"). No cross-node staleness to reason about, and it
  is the node with the display. Calibrate `UA` once: heat to a known temp, let
  it coast, log decay against cabin temp. No physical water temp sensor needed.

### Phase 3 — lighting
- Route A/B/C per §7.

### Phase 4 — vehicle / cabin

**The van is Euro 6 → smart (variable-voltage) alternator under BMS control.**
This invalidates naive D+ based logic. Behaviour:
- ~14.8–15V during deceleration/braking (energy recovery)
- ~12.2–12.8V during acceleration and steady cruise — deliberately discharging
  the starter battery
- Zero output during stop-start events
- Battery current metered by an **IBS sensor on the negative post**; the BMS
  charges to a target SoC, not to a fixed voltage

The 800W charging inverter draws ~70A at 12V. During a low-voltage phase that
current comes out of the starter battery, sagging the rail further and
shortening starter battery life.

#### Engine/charge detection — voltage, not D+
Many Euro 6 vehicles expose no classic D+, and where they do it indicates the
engine is turning, not that the alternator is producing. Sense **voltage at the
inverter's DC input** (not at the battery — include cable drop):
- Rising above ~13.2V for 10s → alternator producing
- Falling below ~13.0V for 10s → not producing

Use an **ADS1115**, not the raw ESP32 ADC — these thresholds need better than ±5%.

#### Voltage-adaptive charge limiting
This is what makes `ac_charge_limit` genuinely valuable rather than a nicety.
Modulate the charging load against measured voltage:

| Measured V | `ac_charge_limit` |
|---|---|
| > 13.8V | 1100W |
| 13.4–13.8V | 700W |
| 13.0–13.4V | 300W |
| < 13.0V | off |

Harvests aggressively during regen phases, backs off instead of draining the
starter battery during cruise. Effectively emulates a B2B charger's voltage
tapering using hardware already owned. **Keep this decision local to
`van-vehicle`** so it works with the network down; only reporting needs core.

#### Hazards
- **Stop-start:** engine stops at a light, alternator goes to zero, 70A still
  being drawn. The BMS usually inhibits stop-start under high load, but a
  no-restart is not worth betting on. The voltage cutoff must respond fast;
  disabling stop-start is the safer answer for a converted van.
- **Grounding:** if the inverter negative is bolted to the **battery negative
  post**, it bypasses the IBS and the BMS goes blind to the load, mis-estimating
  SoC. Connect negative to **chassis ground** instead. `VERIFY` how the existing
  install is wired.

#### Current installation — `MEASURED` and problematic
The DC/AC charging inverter is connected to the **vehicle starter battery via a
manual high-current switch**. Delivering 800W into the P310 draws **1200W** from
the 12V side.

**That is 67% chain efficiency, against ~79% predicted — roughly 400W
unaccounted for, at 100A continuous.** Diagnose before designing around it:
- **Voltage drop:** measure at the battery posts and at the inverter terminals
  under load. >0.3V difference means undersized cable. 100A continuous wants
  35–50mm²; many installs use 25mm².
- **The manual switch:** 100A through degraded contacts dissipates real power.
  If it is warm after 10 min of charging, that is the loss — and a fire risk.
- **Inverter loading:** if the inverter is rated 1000–1500W, 1200W is near full
  load where efficiency is worst. Dropping `ac_charge_limit` to 700W may improve
  chain efficiency by several points.
- Confirm how 1200W was measured — a clamp meter on the positive cable settles it.

#### The manual switch is the primary hazard
The only thing preventing 100A draining the starter battery is remembering to
flip a switch. Left on overnight = a van that will not start, potentially far
from help.

**Keep the manual switch as a maintenance disconnect** and add a **continuous-duty
DC contactor** in series, driven by `van-vehicle`. Not an automotive relay —
nothing standard handles 100A continuous. Albright SW80-class, or a Victron
Cyrix-ct 120A (which does voltage-sensing engagement itself and accepts a remote
override input).

> **Fail-safe direction is OPPOSITE to the fridge.** The fridge fails toward
> *powered*; the charging path fails toward *disconnected*. Any fault — node
> dead, coil unpowered, ignition off — must open the circuit. Because
> `van-vehicle` runs on switched ignition, key-off gives this inherently: no coil
> power, contactor open, no drain. Do not defeat this with a permanent 12V feed.

#### Why tapering is protection, not optimisation
At 100A, during a cruise phase when the alternator regulates down to ~12.4V, it
will not supply the load — the difference comes out of the starter battery, which
on a Euro 6 van is an EFB/AGM being SoC-tracked by the BMS. Repeated deep pulls
degrade it and can trigger dashboard warnings. The voltage-adaptive limit above
is what makes this installation safe to leave connected.

#### Future: better charging topology
Direct DC-DC is **blocked** — the P310's DC input is already occupied by the
750W solar array, and DC-DC alternatives run at lower power than 800W.

One option not yet ruled out: a **changeover contactor on the DC input** — solar
when parked, a boost converter from the alternator when driving (engine state is
already detected). Costs a couple of hours of solar on driving days; gains ~92%
efficiency versus the measured 67%. **`UNVERIFIED` and decisive: the P310's DC
input power ceiling. If that port caps at ~500W, this is worse than the current
setup and the idea dies.** Check the spec before spending further thought.

#### Drive-time AC inhibit — `FUTURE, optional, default OFF`

Cut the P310's `ac` output (its own inverter) while the engine is running.
Requested as an option for specific uses, **not** as default behaviour, so it is
a `switch` entity that ships disabled.

**Why.** In rough order of how well each justification holds up:
1. **Specific loads that should not run in motion** — the actual request. Whatever
   is plugged in, this is a single hard interlock rather than a habit.
2. **Safety: `manual_req` left armed.** Leaving the inverter on after cooking and
   driving off is already flagged in the cabin display section as the failure a
   display can catch. This is the automated version — and it should **clear**
   `manual_req`, not merely suppress it, or the 45 min timer keeps running and AC
   returns the moment you park.
3. **Energy — the weakest case, state it honestly.** Saving is 35W of idle ×
   drive hours (~70Wh on a 2h drive); the compressor work is *deferred, not
   avoided*, exactly as in sleep mode. And it accrues while the alternator is
   charging at ~800W, i.e. when energy is cheapest. Do not sell this feature on
   the energy number.

**It is sleep mode with a different trigger.** Suppress compressor cycles, coast
on the food's thermal mass, keep the 10 °C hard override, reuse the §6 adaptive
coast prediction to display *"fridge coasting, ~3h remaining"*. No new control
machinery — only a new reason to enter the same state.

**Arbiter change.** The inhibit is a *suppressor*, never a request, and
`force_on` must still win:

```
ac_on = force_on
        OR ( (fridge_req OR manual_req OR surplus_req)
             AND NOT drive_inhibit )
```

**Hazards — this rule points the opposite way to §5.2, so it needs its own
fail-safe direction stated explicitly:**
- **Fails toward PERMITTING AC.** Unknown or missing engine state = no inhibit.
  A `van-vehicle` that is unreachable, asleep or dead must never be able to hold
  the fridge off — that is the two-days-of-spoiled-food failure §5.2 exists to
  prevent, arriving through a side door.
- **Hard timeout, non-negotiable.** Maximum inhibit ~4h, after which AC is
  permitted regardless of what the engine signal claims. A stuck-true signal must
  expire on its own.
- **The 10 °C hard override still applies**, exactly as in sleep mode.
- **`§5.1 tension`.** Fridge control is required to live entirely on `van-core`
  and never read the network, but engine state lives on `van-vehicle`. This is
  only acceptable because the inhibit is additive and fails open: `van-core`'s
  fridge logic is unchanged and fully functional with no network at all.
  **Never restructure this so that `van-core` must ask permission to run the
  fridge.**

#### Engine detection for the inhibit — `ANSWERED: must come from the vehicle`

`CONFIRMED`: the P310 does report AC-input and DC/solar-input power separately.
**It does not help.** AC-input power measures the *charger*, not the *engine*,
and the charger sits behind a manual switch:
- Switch off, engine running → reads 0W. Engine missed.
- Switch left on, engine off → reads >0W. Engine falsely detected — and this is
  precisely the documented starter-battery-drain hazard, not a hypothetical.

So it fails in both directions and cannot be used. `van-core` cannot determine
engine state over BLE; the signal must come from `van-vehicle`.

**Preferred signal: `van-vehicle`'s own existence.** The node already runs from
**switched ignition, not permanent 12V**, so *the node being alive is the key-on
signal* — no extra sensor, no extra wiring. `van-core` applies the inhibit while
it is hearing from `van-vehicle` and releases it after ~30s of silence, which
degrades in the correct direction by construction.

**Do not use the Phase 4 voltage thresholds for this.** They detect *alternator
producing*, which on a Euro 6 smart alternator is not the same as *engine
running* — during a cruise phase the rail sits at 12.2–12.8V and looks exactly
like key-off. The inhibit would flicker off mid-drive. Those thresholds exist for
charge limiting, where "is the alternator producing" is the actual question.

`If voltage sensing is used here anyway:` **sense upstream of the manual
switch.** Downstream, the sense point goes dead whenever the charger is switched
off — reintroducing the same blindness described above.

Key-on is a slight superset of engine-running (it includes accessory position and
key-on-engine-off). That is acceptable for an interlock, and the hard ~4h timeout
above bounds the consequence of parking with ignition live.

**Consequence:** this feature cannot exist before Phase 4, and it is genuinely
network-dependent — which makes the "fails toward permitting AC" rule above
load-bearing rather than precautionary.

#### Node power
`van-vehicle` runs from **switched ignition, not permanent 12V.** Its only jobs
happen while the engine runs, so its standby cost is zero and a bright display
becomes affordable. The node's own power state doubles as a key-on signal —
but charge decisions use voltage sensing, not key position (see above).

#### Cabin display
- **Waveshare ESP32-S3-LCD-3.16 (320×820, ST7701 RGB, 550 cd/m², ~€25) — leading
  candidate.** Everything that disqualifies it for `van-core` is irrelevant here:
  no BLE requirement, so continuous PSRAM/DMA contention from the RGB panel is
  harmless; ignition-powered, so backlight draw is free; 550 cd/m² addresses the
  sunlight readability problem; and **320×820 in a bar format is close to ideal
  for a driving readout** — a vertical stack of four large values, no menus.
  Onboard PCF85063 RTC and microSD are useful for trip logging. Buy at Phase 4,
  not before.
- Alternative: **T-Display-S3** (1.9", 320×170) — smaller and dimmer, but simpler.
- Alternative already owned: a **HUB75 panel**. Large digits, readable across the
  cab, 2–5W is free on ignition power.
- **Automotive power conditioning is the real engineering.** Cranking dips to
  6–8V; load dump reaches 30–40V+. Required: fuse, reverse-polarity MOSFET, 33V
  TVS across the input, wide-input buck (9–36V). A bare MP1584 off the ignition
  wire will fail. Expect resets during cranking — boot must be clean and must not
  require the network to display something.
- **Vibration:** no Dupont jumpers. JST-XH or soldered, with strain relief.
- **Night dimming:** take a feed from the sidelight circuit through an
  optocoupler (not an LDR — interior lights confuse it) and drop backlight PWM to
  ~15%. A full-brightness screen at night on a mountain road is dangerous.
- **Content:** three or four large numbers, one button to cycle, no interactive
  controls. Highest-value items:
  1. **Charge power in** — the only confirmation the alternator inverter is
     actually delivering. Silent failure of that path is currently invisible
     until the battery is flat two days later.
  2. **"Manual AC still on" warning** — leaving the inverter armed after cooking
     and driving off is exactly the failure a cabin display can catch.
  3. SOC, fridge state, water — the binding tank, not two bars (§9 Phase 2).
- **Graceful degradation:** values come from polling `van-core` across several
  metres of van build. Show stale readings greyed out with an age indicator
  rather than blanking, and never let a missing reading stall the node's own
  control loop.

### Phase 5 — energy strategy
- **Thermal banking:** at peak solar drive the fridge setpoint to 1–2 °C; after
  sunset let it drift to 6–7 °C. Stores energy as cold in the food mass instead
  of cycling the battery, and dodges the inverter idle penalty exactly when it
  hurts most. Note: limited by the contents' heat capacity, since added ballast
  is rejected on volume grounds. Effectiveness scales with how full the fridge
  is — a full fridge is a better battery than an empty one.
- **Battery longevity:** hold `threshold_charge` at 60–80% when parked at home,
  raise to 100% the day before a trip. LiFePO4 tolerates high SOC far better
  than NMC, but calendar ageing in a hot van in August is real.
- **Staged load shedding.** `CORRECTED`: the habitation 12V bus is fed from the
  P310's DC output, which is the `dc` switch. **Shedding the DC rail would kill
  both ESP nodes and all lighting** — never do it. Shed on the 230V side and via
  the arbiter only:
  - SOC 40% → hard lockout on the water heater and all `surplus_req` loads
  - SOC 30% → widen the fridge deadband (fewer, longer cycles); shed `usb` only
  - SOC 20% → fridge duty stretched further, alert on both displays
  - SOC 15% → alert loudly; **still never touch `dc`**
  Better than discovering the problem at 5%.
- **Season-long logging** to decide whether the next euro goes to a fourth panel,
  a bigger alternator charger, or a compressor fridge. Currently that's a guess.

### Phase 6 — home integration
- When parked at home the nodes join the house SSID as a secondary network;
  HA on the ThinkCentre picks them up for history and graphs.
- HA stays at home. It does **not** go in the van.

---

## 10. Repo layout

```
van-supervisor/
├── CLAUDE.md               # this file — source of truth
├── BOM.md                  # parts, prices, open hardware decisions
├── secrets.yaml            # gitignored
├── secrets.yaml.example
├── common/                 # shared packages: wifi, ota, web_server, logger
├── components/
│   └── ac_arbiter/         # custom C++ component: state machine, host-testable
│       ├── ac_arbiter.h
│       ├── ac_arbiter.cpp
│       └── __init__.py
├── test/                   # host-compiled unit tests for the arbiter
├── nodes/
│   ├── van-core.yaml
│   ├── van-water.yaml
│   └── van-vehicle.yaml
├── docs/
│   ├── wiring.md           # pinouts, connectors, fuse ratings
│   ├── measurements.md     # §8 answers as they come in, with dates
│   └── decisions.md        # ADR-style: what was chosen and why
└── tools/                  # calibration helpers, log analysis
```

---

## 11. Conventions

### Firmware architecture — `DECIDED: ESPHome + custom C++ component`

**Stay on ESPHome, but do not write the arbiter as YAML lambdas.**

*Why not full custom firmware:* ESP-FBot solves the P310 BLE protocol — the
hardest and least documented part of the project — and it is an ESPHome
component. Porting it out means doing that work on the one subsystem where a
subtle bug presents as "the fridge occasionally doesn't come back on".
Rebuilding OTA (field tuning from a phone in a car park, not a laptop on the
passenger seat), `web_server`, sensor timeout filters, `number`/`select`
tunables, 1-Wire, ADS1115, display and SD support is weeks of work to arrive
back at the starting point.

*Why not plain ESPHome:* an arbiter with four request flags, a fail-safe
override, anti-short-cycle timing, sleep mode and coast prediction becomes
unreadable and untestable when scattered across `on_...` triggers and globals.

**The split: YAML describes the plant, C++ implements the controller.**
- Arbiter = a custom ESPHome external component: one class, enumerated states,
  an explicit transition table, one `loop()`.
- Host-compile the state machine and unit-test it without hardware. For a
  fail-safe arbiter this is worth real money.
- YAML keeps pin wiring, entity declarations and tunable numbers.

*Escape hatch:* ESPHome runs components in a single cooperative loop, so a slow
SD write or heavy display redraw can starve the BLE task. If the soak test shows
this, the fix is moving SD and display onto their own FreeRTOS task from inside
the custom component — which ESPHome permits. Only if **that** fails is full
custom firmware justified.

### General
- **Pin the ESPHome version** in the repo. Breaking changes between releases are
  common; a routine update must not invalidate a working config two days before a
  trip.
- ESPHome packages under `common/` for anything shared; nodes stay thin.
- Every tunable is a `number` or `select` entity, never a hardcoded literal —
  field tuning must not require a laptop and a reflash.
- Every sensor feeding a control decision carries a `timeout` filter.
- Comment every lambda with its intent, not its mechanics.
- `docs/measurements.md` is append-only and dated. Assumptions that were never
  measured get marked `UNVERIFIED` in the config comments too.
- Test the fail-safe path deliberately before each trip: pull the BLE antenna,
  unplug the probe, and confirm the inverter ends up ON.
  **Test it from AC OFF, not AC ON** — starting from ON proves nothing, because
  the station latches and the inverter would stay on with the node unplugged
  entirely. Wait for a fridge OFF block, then break the link. What that
  actually tests is the reconnect, which is the recoverable half of §5.2.
- **Know the manual override.** If `van-core` wedges while AC is off, the
  fridge stays off and the phone app cannot take over — `van-core` is holding
  the P310's single BLE connection. The recovery is the **station's own
  physical AC button**, which always works. It is the last resort in the one
  failure §5.2 cannot engineer away, so it belongs in the pre-trip check, not
  in a panic at midnight.
- **And confirm parked mode is OFF before loading food.** It is the one state
  in which the previous check is expected to fail: parked, all three faults
  resolve to AC OFF by design (§6 "Parked mode"). Blue blink on the kitchen
  button = still parked.

---

## 12. Working notes for Claude

- The user is a mechatronics engineer with safety-critical embedded and
  industrial automation background. Skip the basics. Direct, no hedging.
- Prefer measurement over estimation. When a number is unknown, say so and
  propose how to measure it rather than assuming a plausible value.
- Flag anything that could strand the user off-grid. That is the real failure
  mode of this project, not a suboptimal duty cycle.
- Watch for scope creep toward "just run Home Assistant in the van" — it has been
  evaluated and rejected on a power budget basis. Reopening it requires new
  numbers, not new enthusiasm.
  **Reopened and re-closed 2026-08-18**, as the easiest way to drive a smart
  plug. The numbers that closed it again:
  - An HA host must be up 24/7. A Pi 4 idles ~4W = **96Wh/day**, against a
    project saving of ~500Wh/day — **~19% of the entire benefit**, spent to
    switch a heater that runs ~20 min/day.
  - It does **not** replace `van-core`. ESP-FBot's BLE link, the fail-safe
    arbiter and node autonomy (§5.1) all still have to exist, so this is 96Wh/day
    *on top*, plus a router or second AP, plus the central dependency §5.1 exists
    to avoid.
  - The thing actually wanted — a plug switched from `van-core` with no router
    and no soldering — is solved by an **ESPHome-preflashed plug** (BOM D1e) at
    ~€15 and no measurable standby, since it is powered only while the inverter
    is on.
  HA remains welcome at home (Phase 6) for history and graphs.
