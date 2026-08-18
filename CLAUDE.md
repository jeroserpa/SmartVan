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

### Measured / stated figures

| Quantity | Value | Confidence |
|---|---|---|
| Inverter idle draw | **35W** | manufacturer figure, `UNVERIFIED` in situ |
| Fridge draw **while compressor runs** | **35W AC** | stated |
| Fridge duty cycle | **UNKNOWN** | *the critical unknown — see §8.3* |
| Usable capacity | ~3500Wh of 3840Wh | assumed reserve |
| Observed autonomy | 1.5–2 days with cooking | includes cooking + other loads |

### Battery-side power states

| State | DC draw |
|---|---|
| Inverter ON, compressor running | ~74W (35 idle + 35/0.9 conversion) |
| Inverter ON, compressor off | 35W |
| Inverter OFF | ~0W |

**The idle equals the useful load.** The fridge subsystem spends 35W of pure
overhead to deliver 35W of refrigeration. The cheaper the compressor, the more
completely the fixed idle dominates — which is what makes this project worth
doing.

### The saving

```
saving = 35W × (fraction of time the inverter is OFF)
```

At an assumed 35% duty cycle:

| | Now | Supervised |
|---|---|---|
| Fridge subsystem | ~49W → 1.17 kWh/day | ~28W → 0.66 kWh/day |

**~43% cut on the fridge subsystem.** Overall autonomy gain is smaller
(~35–40%) because cooking is a large share of the remaining budget.

Duty cycle scales this directly. In a van at 35 °C in August, duty could be
50–60% rather than 35%, shrinking the saving proportionally. **Measure it before
tuning anything.**

The fridge is **not** being replaced. The fix is to duty-cycle the inverter.

**Target:** inverter duty ≈ fridge duty + <10% overhead, control-system standby
under 2W total.

---

## 2. Hardware inventory

### Power system
- **AFERIY P310** portable power station — 3840Wh LiFePO4, 3300W pure sine
  inverter, expandable. BLE + WiFi, "BrightEMS" app family.
- **750W solar** into the P310 MPPT.
- **Alternator charging** via a pure-sine inverter feeding the P310's AC input
  at ~800W when the engine runs.

### Existing smart devices
- **Meross MSS315 (Matter/WiFi, energy monitoring)** — *not used in the van.*
  Matter-over-WiFi needs a Matter controller + IPv6 + mDNS on the LAN, i.e. a
  Pi/HA running 24/7 (~4W). Energy monitoring was historically not exposed over
  Matter. **Decision: this plug stays on the house HA instance.**
- **MiBoxer E2-WR** LED controllers (dual-white/CCT, WiFi+BLE+2.4G RF, Tuya).
  Tuya WiFi side requires internet to provision → unusable in the van as-is.
  The 2.4GHz RF side works standalone. See §7 for the three routes.
- **Resistive water level sender** with analogue gauge (AliExpress kit).
  Resistance range **unverified** — see §8.

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
- Shelly Plus 1PM (Gen2) for the water heater, flashed with ESPHome — see BOM D1.
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

Display pages: SOC/power → fridge temp + arbiter state → water → diagnostics.
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
| `van-heater` | on the 230V heater feed | Shelly Plus 1PM (ESPHome): relay + power metering. **Only powered while the inverter is on** — expected, see BOM D1 |
| `van-water` | beside tank | level sender (ADS1115), future pump control |
| `van-vehicle` | engine bay / dash | ignition + D+ sense, alternator charge limiting (future) |

Static addressing: `192.168.4.1` (core AP), `.10` water, `.11` vehicle,
`.12` heater.
Raise `max_connection` on the SoftAP to 8 (default 4).

### Inter-node protocol
HTTP REST against each node's `web_server`. `van-water` GETs core's sensor JSON
every 30s to read SOC and surplus power. No MQTT broker, no HA, no extra hardware.

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
2. **Fail toward powered — for loads.** Any fault — BLE dropped, DS18B20 stale,
   reboot, watchdog — must resolve to *inverter ON*. Use `on_boot` priority and
   `filters: - timeout:` on every sensor feeding a control decision. A bug that
   silently kills the fridge for two days while nobody is in the van is the one
   failure mode that actually costs money.
   **Exception — the alternator charging path fails toward DISCONNECTED.** A
   stuck-closed 100A path drains the starter battery and strands the van. See
   §9 Phase 4. Every control path must have its fail-safe direction stated
   explicitly; do not assume the fridge convention applies elsewhere.
3. **Single writer.** Exactly one place in the code writes `ac_switch`. Multiple
   consumers express *requests* as booleans; an arbiter ORs them. See §6.
4. **Standby power is a first-class requirement.** Every added device gets
   metered before it stays. The whole control system must stay under ~2W; there
   is no point spending 130Wh/day of supervision to save 600Wh/day of losses when
   35Wh/day buys the same result.
5. **No cloud, no internet dependency, ever.** Including for provisioning.

---

## 6. AC inverter arbiter — specification

Three independent request flags, ORed by a single 5s interval, with a fail-safe
override on top.

```
ac_on = force_on
        OR fridge_req
        OR manual_req
        OR surplus_req
```

### `force_on` (fail-safe override)
True if any of: node just booted; BLE `connected` false; fridge temperature
sensor stale > 5 min; arbiter watchdog expired.

### `fridge_req`
- Set true when fridge temp > 7.0 °C.
- Set false when **all** of: temp < 4.0 °C **and** `output_power` < 15W
  continuously for **90s** (compressor satisfied) **and** inverter has been on
  ≥ 10 min.
- **Why 90s and not 5 min:** the tail between compressor stop and inverter
  shutdown is pure idle waste, incurred every cycle. At 12 cycles/day a 5 min
  tail burns 60 min of idle = 35 Wh/day, i.e. ~7% of the entire project saving
  spent on detection latency. The BLE power sensor sees the compressor stop
  within one poll (~5s); 90s is ample debounce. Make this a tunable `number` and
  push it lower once behaviour is observed.
- **Prefer fewer, longer cycles.** Widen the temperature deadband as far as food
  safety allows. (Added thermal ballast is **rejected** — the volume is needed
  for food. The contents are the only thermal mass available.)
- Hard override: temp > 10.0 °C forces true regardless of anything else.
- **Anti-short-cycle:** enforce a 5 min minimum OFF time before re-energising.
- Thresholds are tunable ESPHome `number` entities, not magic constants.

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
**35W idle**, which runs all night regardless of whether the compressor ever
starts. Sleep mode is therefore not primarily about suppressing compressor
cycles — it is about removing the continuous idle heat source. This is likely
the single biggest quality-of-life win in the project.

**Goal: at most one compressor cycle between roughly 23:00 and 06:00.**
Zero is not achievable without added thermal mass, which is **rejected — the
volume is needed for food.** The food itself is the thermal mass.

Sequence:
1. **Pre-cool** in the hour before sleep, while noise is irrelevant: drive the
   fridge to 1 °C.
2. **Coast** through the night with a raised ceiling (6–8 °C, configurable).
3. **If the ceiling is reached**, run a full cycle back down to 1 °C — not to the
   normal 4 °C setpoint. Same single run, maximum remaining coast; often turns
   two cycles into one.
4. **Exit** on schedule or button press; normal thresholds resume.

Thermal budget (order-of-magnitude, verify by test — §8.5). Coast length varies
with how full the fridge is:

| Contents | Heat capacity | 1 → 8 °C | Coast at ~25W leak |
|---|---|---|---|
| Well stocked (~20 kg) | ~70 kJ/K | 490 kJ ≈ 136 Wh | ~5.4 h |
| Half full (~10 kg) | ~35 kJ/K | 245 kJ ≈ 68 Wh | ~2.7 h |
| Nearly empty | — | — | ~1 h |

**The real comparison is not "one cycle vs zero" but "one 15-minute cycle vs
35W of continuous idle heat and uncontrolled fan cycling all night."**

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

**Measure first:** an unprovisioned Tuya module can idle at 0.5–1W while
scanning. Three of them ≈ 40–70 Wh/day for nothing. Meter one before deciding.

---

## 8. Open questions — verify before building

Ordered by how much they'd change the design.

1. ~~**Does the fridge auto-restart after power is restored?**~~ **ANSWERED:
   yes** — restarts automatically on the medium-cold setting. Project premise
   holds.
2. **Actual inverter idle draw.** Stated 35W (manufacturer). Confirm in situ:
   AC on, nothing plugged in, read `system_power` / `output_power`. Fan
   behaviour and ambient heat may push it above spec in an August van.
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
7. **Water sender resistance range.** Measure at empty and full. Almost
   certainly either **0–190Ω (European/VDO)** or **240–33Ω (US/GM)**. Determines
   the divider resistor (220Ω for the 0–190Ω type).
8. **E2-WR internals** — single-chip or Tuya-module-plus-MCU (see §7C).
9. **E2-WR idle power**, unprovisioned.
10. **2.4GHz link quality with the inverter under load** — decides whether the
    RS485 escape hatch gets pulled forward.

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
- **Heater: a Shelly Plus 1PM flashed with ESPHome**, as its own node on the
  230V line. `REVISED` — an earlier version of this section said "dumb GPIO +
  optocoupler, **not** a smart plug", on the grounds that a mains-powered relay
  reboots whenever the arbiter cycles the inverter. That objection does not
  survive contact with the fact that **the heater cannot heat without AC
  anyway**, so the relay has no useful state to hold while the inverter is off.
  See BOM D1. `restore_mode: ALWAYS_OFF` puts the fail-OFF property in git, and
  the onboard metering is the `P_heater` term the estimator needs.
- `van-water`: level sender via ADS1115. **No heater involvement** — it reduces
  to the tank sender alone.
- Sender conditioning: excite through a MOSFET/GPIO only during a reading (DC
  through a submerged sender corrodes the wiper), median filter ~30 samples,
  `throttle_average: 60s` (sloshing while driving makes raw readings useless),
  calibrate with `calibrate_linear` against **actual litres poured**, not the
  nominal curve — these senders are rarely linear near the ends.
- **Future: estimated water temperature on the display.** The heater tank is a
  sealed, isolated 230V unit — no draw-off during heating, so no unmodeled
  disturbance. Lumped thermal-capacitance model:
  `dT/dt = (P_heater − UA·(T_water − T_cabin)) / (m·c)`.
  **Runs on `van-core`**, which holds all three inputs already: it commands the
  heater, it carries the cabin/ambient DS18B20, and it reads `P_heater` from the
  Shelly's metering (better than nameplate — it also catches a dead element as
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
  3. SOC, fridge state, water level.
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
