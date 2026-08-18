# Bill of materials — Phases 1 & 2

Scope: `van-core` (fridge supervision) and `van-water` (heater, tank level,
future pump). **`van-vehicle` deliberately excluded** — deferred.

Prices are indicative EU retail, Aug 2026. AliExpress lead times ~2–4 weeks;
where a part is trip-critical, buy locally.

---

## Phase 1 — `van-core`

| # | Item | Qty | ~€ | Notes |
|---|---|---|---|---|
| 1 | **Waveshare ESP32-S3-LCD-1.47** | 1 | 13 | ESP32-S3R8, 8MB PSRAM, 16MB flash, 172×320 **plain SPI ST7789**. See D0 |
| 2 | DS18B20 waterproof probe, 1m | 2–3 | 6–9 | Fridge wall + ambient. Optional 3rd in free air for door-opening detection — same 1-Wire bus, no extra wiring |
| 3 | 4.7kΩ resistor | 1 | — | 1-Wire pull-up, at the ESP end |
| 4 | Flat 3-core ribbon cable, ~0.5mm thick | 0.5m | 3 | **Gasket crossing only.** Or 30AWG PTFE strands taped flat |
| 5 | Kapton tape 10mm | 1 | 3 | Flattening/bundling the gasket crossing |
| 6 | Neutral-cure silicone or butyl | 1 | 5 | Sealing the gasket crossing. Neutral-cure — acetic attacks contacts |
| 7 | Panel-mount momentary buttons | 3 | 15 | Two on the enclosure bezel (wake/cycle, sleep) + one illuminated 16mm in the kitchen (manual AC). **BOOT/RESET are not usable** — see D4. Optional: swap the two bezel buttons for a rotary encoder with push |
| 8 | 100Ω resistor (+ optional 100nF) | 1 | — | **Debounce is handled in software** (`delayed_on: 50ms`) — the cap is optional. The 100Ω series resistor is the one worth keeping: it limits current into the ESP32 ESD diodes on a long run past the inverter. Both retrofittable at the board end |
| 9 | Buck 12V→5V, 3A (Mini560 or similar) | 1 | 4 | Habitation 12V is clean vs vehicle 12V — no load-dump protection needed here |
| 10 | Inline blade fuse holder + 2A fuses | 1 | 4 | |
| 11 | Schottky diode (SS34) or P-MOSFET | 1 | 1 | Reverse polarity protection |
| 12 | ABS enclosure, ~100×68×50, IP65 | 1 | 6 | Display cutout needed |
| 13 | PG7 cable glands | 3 | 2 | Strain relief at every enclosure exit |
| 14 | Thermal epoxy + aluminium HVAC foil tape + foam offcut | 1 | 6 | Glues the probe flat to the fridge **side wall** (never the back — that's the evaporator). Foam pad on the air side gives the slow time constant. **No water bottle** — superseded |
| 15 | DS3231 RTC module (I2C) | 1 | 3 | **Required for logging.** No internet in the van → no SNTP, so timestamps would be ms-since-boot. Keeps time across reboots; resyncs from SNTP when parked at home |
| 16 | microSD card, 8–32GB | 1 | 6 | Onboard slot. Class 10, small capacity — large cards are slower to mount |
| 17 | Perfboard or custom PCB carrier | 1 | 5 | Female headers for the ESP module + pluggable terminals. JLCPCB ≈ €5 for five if laying out a real board |
| 18 | 3.5mm pluggable screw terminals (2/3/4-pin) | set | 6 | **Key by pin count:** 2-pin = 12V in, 3-pin = 1-Wire bus, 4-pin = button. Physically impossible to mis-plug |
| 19 | Female headers 2.54mm | set | 2 | Makes the ESP module replaceable without touching wiring |
| 20 | M3 heat-set inserts + M3 screws, nyloc nuts, nylon washers | set | 8 | Inserts not self-tappers — PETG threads strip after a few cycles. Nyloc + nylon washer as the hinge pivot so friction is adjustable |
| 21 | PETG or ASA filament | — | — | **Never PLA** — glass transition ~60 °C, exceeded in a parked van in August. The commonest failure of printed van parts |
| | **Subtotal** | | **~93** | |

## Phase 2 — `van-water`

Item numbers continue from Phase 1 — they are order-list identifiers, not
per-phase counters. (Phase 2 previously restarted at 16 and collided with
Phase 1; D1's "item 20" was ambiguous as a result.)

| # | Item | Qty | ~€ | Notes |
|---|---|---|---|---|
| 22 | **ESP32-C3 SuperMini** — already owned | 1 | 0 | Replaces the WROOM-32 devkit. D0 rules the SuperMinis out for `van-core` but clears them "as sensor nodes" — no BLE here, and the load is I²C + three GPIOs. **`VERIFY` RSSI at the installed position first:** the C3 SuperMini's PCB antenna is poorly matched (same radio weakness D0 cites for the C6) and this node holds a SoftAP link across a metal-bodied van. Fall back to a WROOM-32 devkit (€7) if margin is thin |
| 23 | ADS1115 16-bit ADC module | 1 | 4 | ESP32 internal ADC is too nonlinear for the sender |
| 24 | Divider resistor for sender | 1 | — | **Value pending §8.7 measurement.** 220Ω if 0–190Ω sender. Sender run **confirmed <1m — no shielding needed** |
| 25 | Logic-level MOSFET (AO3400 / 2N7000) | 1 | 1 | Gates sender excitation — continuous DC corrodes a submerged wiper |
| 26 | **Shelly Plus 1PM (Gen2)** | 1 | 25 | Heater switching + power metering, flashed with ESPHome. **Gen2 specifically** — see D1. Its own node; not wired to `van-water` |
| 27 | Buck 12V→5V 3A + fuse holder + fuses | 1 | 7 | |
| 28 | ABS enclosure IP65 + glands | 1 | 7 | |
| 29 | Automotive relay 30A + socket (future pump) | 1 | 4 | Phase 2b. **12V DC contacts — never repurpose one for the 230V heater** |
| | **Subtotal** | | **~48** | |

Cabin temperature is **not** listed here — it lives on `van-core` (item 2's
ambient probe). See D1d for what that implies for the estimator.

## Shared wiring stock

| # | Item | ~€ | Notes |
|---|---|---|---|
| 30 | Silicone-insulated stranded wire 0.5mm² (signal) | 10 | Silicone stays flexible cold; PVC cracks |
| 31 | Silicone stranded 1.0mm² (12V feeds) | 10 | |
| 32 | **Mains-rated cable 2.5mm²** | 8 | Heater feed, 4.3–8.7A. See D1c |
| 33 | JST-XH connector kit + crimper | 12 | Board-level connections |
| 34 | Wago 221 lever connectors, assorted | 8 | 12V branch distribution |
| 35 | Bootlace ferrule kit + ratchet crimper | 20 | One-off tool. Non-negotiable for stranded wire into screw terminals |
| 36 | Adhesive-lined heatshrink assortment | 8 | Adhesive-lined, not plain — moisture ingress |
| 37 | Cable labels / label printer tape | 5 | |
| | **Subtotal** | **~81** | Much of it reusable stock |

## Measurement tools

| # | Item | ~€ | Justifies |
|---|---|---|---|
| 38 | DC clamp meter, 200A+ | 30 | Confirms the 1200W alternator figure (§9 Phase 4). Also invaluable for every future debug |
| 39 | Cheap plug-in energy meter | 12 | Independent cross-check of P310 readings |

---

## Totals

| | ~€ |
|---|---|
| Phase 1 | 93 |
| Phase 2 | 48 |
| Wiring stock | 81 |
| Tools | 42 |
| **All-in** | **~264** |
| **Phase 1 only, using existing stock** | **~93** |

Against a saving in the region of 0.5 kWh/day plus the sleep-mode benefit, Phase 1
alone pays for itself in one summer of not running the generator equivalent.

---

## Open decisions

### D0 — `van-core` board — `DECIDED: Waveshare ESP32-S3-LCD-1.47 (€13)`

Earlier revisions of this document said "classic ESP32, not S3" (overstated — it
rested only on the ESP-FBot example targeting `esp32dev`) and then assumed the
Waveshare 1.47" lacked PSRAM (**wrong** — it has 8MB PSRAM and 16MB flash on an
ESP32-S3R8, identical silicon to the T-Display-S3). Both corrections recorded so
the reasoning is auditable.

With RAM no longer differentiating, **the deciding factor is the display bus**,
because that is where ESPHome effort actually goes.

| Board | € | Bus | Verdict |
|---|---|---|---|
| **Waveshare ESP32-S3-LCD-1.47** | 13 | Plain SPI ST7789 | **Chosen.** Best-supported path in ESPHome's `mipi_spi`. Same chip/PSRAM/flash as the €28 board |
| LilyGO T-Display-S3 | 28 | 8-bit parallel | Works, but needs `mipi_spi` with `type: octal`, 8 data pins, 2 strapping-warning overrides. €15 for a larger panel and more friction |
| Waveshare ESP32-S3-LCD-3.16 (320×820, ST7701 RGB) | 25 | 16-bit RGB parallel | **Rejected for core, but see Phase 4.** ~20 GPIOs consumed by the panel; board only leads out UART + I²C + USB ≈ 4 usable pins, against 1-Wire + 3 buttons needed. 524KB framebuffer streams continuously from PSRAM over DMA — permanent bandwidth contention with BLE. Driver requires ESP-IDF, removing the framework fallback if SD and BLE conflict. Porch/pulse timings need per-panel tuning |
| Waveshare ESP32-S3-Touch-LCD-3.49 | 34 | QSPI, AXS15231B | **Rejected.** Not a standard ESPHome model — likely a custom init sequence. Also paying for audio codec, dual mics, echo cancellation and IMU that this project never uses, with a larger backlight on a 24/7 node |

**Buttons:** the Waveshare exposes BOOT (GPIO0) as one usable user button. Wire
the second externally — the kitchen AC button is external anyway. Adjust the
button map in CLAUDE.md §2 accordingly.

**Still ruled out: the ESP32-C6 boards and the ESP32-C3 SuperMinis already
owned.** A documented ESPHome report has a C6 finding one BLE device where an S3
found eight in the same position, with WiFi TX power adjustment making no
difference. Single-core RISC-V running BLE client + WiFi SoftAP + web server +
display is the wrong place to economise. Fine as sensor nodes.

**Required regardless of board:** flash it, connect to the P310, and leave it
running 24h with SoftAP and web server active before wiring anything in. This
validates the assumption everything else rests on.

### D4 — User controls: external buttons, not a touchscreen

The chosen board exposes only **BOOT and RESET**, both tiny side-mounted tactile
switches. **BOOT (GPIO0) must not be used as a runtime button** — it is a
strapping pin, and holding it during a reset drops the board into download mode,
a confusing failure to debug months later.

**Touchscreen boards rejected.** The screen is blanked most of the time (60s
timeout — no night-light, no wasted backlight), so touch cannot be the input
method: something physical has to wake it first. A physical button is needed
regardless, at which point touch saves exactly one button. Physical buttons also
work with wet hands, cold hands, and in the dark without looking — which matters
more in a van than in a living room.

**Chosen:** three external momentary buttons — two panel-mounted on the printed
enclosure bezel (wake/cycle, sleep mode), one illuminated in the kitchen (manual
AC). ~€15 and one more 4-pin terminal on the carrier board.

**Optional:** a rotary encoder with push replaces the two bezel buttons — turn to
cycle pages, press to wake/select. Best value is threshold tuning, since every
setpoint is an adjustable `number`. Retrofits to the same terminal block.

`VERIFY before carrier board layout:` GPIO availability on the Waveshare header —
SPI (display), SDMMC (card), I2C (RTC), 1-Wire, three buttons. The S3 has the
pins; the header may not break them all out.

### D1 — Mains switching for the water heater (item 26) — `REVISED TWICE`

`CONFIRMED`: heater is **230V, 1–2kW**.

**History, recorded so the reasoning stays auditable:** Shelly Plus 1PM → DIN
contactor → **back to Shelly Plus 1PM, flashed with ESPHome.**

**Why the contactor revision was wrong.** It objected that a mains-powered smart
relay sits downstream of the inverter the arbiter cycles, so commanding it means
"request inverter → wait for AC → wait for boot → command". That sequence is
real, but **the heater cannot heat without AC anyway** — there is no state the
relay could usefully hold while the inverter is off. The cost is ~10s of boot on
a ~20-minute heating cycle. The contactor's claimed advantage (instant control
independent of inverter state) buys nothing, and it was paid for with a coil
driver, a GPIO, a DIN enclosure and a DIY 230V termination.

**The hazard that *is* real** — and which the earlier revision did not name — is
the power-on relay state. Set to `restore_last`, every inverter cycle for the
*fridge* boots the Shelly straight into 1500W of unwanted heating, silently, and
the first symptom is a flat battery. Stock firmware puts that safety property in
a config field that a factory reset or firmware update can quietly revert.

**Chosen: Shelly Plus 1PM (Gen2, ESP32) flashed with ESPHome.** ~€25.
- `restore_mode: ALWAYS_OFF` on the switch → relay opens on every boot, and that
  property lives **in YAML, in git**, surviving resets and reflashes. This is
  what implements "heater fails OFF" (CLAUDE.md §5.1) — the one control path
  running *opposite* to the fridge's fail-toward-powered convention.
- Native ESPHome node, so no cloud and no HTTP glue (§5.5).
- **Power metering comes free** and is genuinely load-bearing: it is the
  `P_heater` term in the water-temp estimator, and a dead element shows up as
  "commanded on, drawing 0W" instead of silently never heating.
- Rated 16A/3.5kW — comfortable on a 1–2kW element.
- **Certified mains enclosure.** For 230V in a damp vehicle this is safer than a
  hand-wired contactor box, not merely more convenient.
- Wiring is L/N in, L/N out. No coil driver, no GPIO, no carrier-board change.

`VERIFY before ordering:` **buy the Gen2 "Plus 1PM" specifically.** ESPHome
flashing is well-trodden on Gen2 (ESP32); Gen3/Gen4 are newer and less proven.

`VERIFY before ordering — this one is load-bearing:` **that ESPHome can read the
metering IC.** Shelly Plus 1PM is believed to use an **ADE7953**, for which
ESPHome has a component — but confirm against a working community config for
this exact model. If the metering cannot be read, half the argument for the
Shelly over a dumb relay disappears (no `P_heater`, no dead-element detection)
and the choice is worth revisiting.

**Flashing — bench work, never on mains.** Serial to the internal UART header:
3.3V USB-UART, GPIO0 low for download mode. Straightforward, but do it **before
installation, with mains completely disconnected**. A USB-UART ground referenced
to a live mains device destroys the laptop and can kill the operator — and on a
*floating* supply (D1c) there is no RCD to intervene.

**Fallback if you would rather not flash it:** stock firmware works fully offline
— provisioning is via the Shelly's *own* AP and a local web UI at 192.168.33.1,
so it joins `van-core`'s SoftAP with a static IP and never sees the internet
(unlike the Tuya/MiBoxer devices in §7, which cannot be provisioned offline at
all). Gen2's local RPC API over HTTP is then driven from `van-core` with
`http_request`. Costs: disable cloud, set power-on state to `off` **explicitly**,
and treat that setting as a documented pre-trip check — it is the whole safety
case, living in a config field rather than in git.

**Still rejected: the SSR.** It fails *closed* under a 1–2kW heating element.

### D1b — Heater energy budget

1500W × 20 min = **500Wh ≈ 14% of usable capacity per heating cycle.** The 750W
array cannot cover this from surplus, so any heating draws down the battery.

Therefore the heater is a **driving-time and full-battery load, not an
opportunistic-solar one**:
- Permit only when SOC > 85% **and** (alternator charging active **or**
  `input_power` > 400W)
- Hard lockout below SOC 70%

Otherwise one shower costs half a day of fridge autonomy.

### D1c — 230V safety — `CONFIRMED FLOATING. Now the project's top risk.`

`CONFIRMED 2026-08-18`: the P310's AC output **floats** — no neutral–earth bond.
That is an IT system, and it makes an RCD inoperative on this supply.

**Why this outranks everything else in this document.** Every other failure mode
here costs a flat battery or warm food. This one is an electrocution path. And
the heater is the worst load to expose it: **element-to-sheath insulation
breakdown is the normal end-of-life failure of an immersion element**, so the
"first fault" that an IT system is supposed to tolerate is not a remote
possibility — it is the expected way this appliance dies.

The failure sequence:
1. Element leaks to its sheath → tank → chassis. **No current flows, no RCD
   trips, nothing indicates anything.** The van now has its 230V supply
   referenced to chassis through a fault.
2. Any second fault, anywhere on the 230V system, completes a circuit. In a van
   the chassis is the sink, the tap, the frame, every other appliance's exposed
   metal.
3. There is no protective device in the system that responds to either event.

**Decision — do not energise a fixed immersion heater on a floating supply.**
One of these must be true first:

- **Preferred: bond N–E at a single point and fit a 30mA Type A RCBO.** This
  converts the installation to TN-S and restores conventional protection —
  the same thing a generator bonding plug does. Bond the heater tank and all
  exposed 230V-adjacent metal to chassis.
  `VERIFY FIRST — this is the new blocker:` **does the P310 tolerate an N–E bond
  on its output?** Many portable inverters drive a symmetric H-bridge where both
  output legs swing ~115V relative to ground; forcing one leg to chassis can
  trip the unit's protection or damage it. Confirm with AFERIY, or measure each
  leg to chassis with a high-impedance meter and a known load before bonding
  anything.
- **If the P310 will not tolerate bonding:** the fixed heater does not go on
  this supply. That is a genuine stop, not a hedge. An insulation monitoring
  device is the textbook answer for an unbonded IT system, but specifying one
  for a van is disproportionate — the bond is the practical route.

**The fridge is on the same floating supply**, at 35W and lower consequence, but
the topology is identical. Bonding fixes both at once.

Measures required regardless of the above:
- All 230V terminations inside an enclosed, non-accessible box
- 2.5mm² mains-rated cable
- No terminal blocks inside a locker that gets reached into
- Heater tank bonded to chassis

### D1d — Where the heater logic and the water-temp estimator live

Two consequences of D1, plus the decision to put cabin temperature on
`van-core`, land the same way: **`van-water` is not involved in the heater at
all.**

| Concern | Node |
|---|---|
| Heater relay + power metering | Shelly (own ESPHome node on the 230V line) |
| Cabin temperature (`T_cabin`) | `van-core` — the ambient DS18B20 of item 2 |
| Permit rules (SOC / charging, D1b) | `van-core` — it already holds the BLE link |
| Water-temp estimator | `van-core` — commands the heater, has `T_cabin`, reads `P_heater` |
| Tank level sender | `van-water` |

So `van-water` reduces to SuperMini + ADS1115 + sender excitation. Its heater
GPIO, the coil driver and the separate cabin probe are all deleted.

The estimator having all three inputs on one node is worth more than it looks:
no cross-node staleness to reason about, and it runs on the node that owns the
display anyway.

**Rejected alternatives, for the record:**

| Option | Why not |
|---|---|
| SSR | Fails **closed** under a 1–2kW element |
| Blue SRD-05VDC relay module | Datasheet says 10A/250V, but the screw terminals and PCB traces are not good for 8.7A continuous, and the "optocoupler" usually shares ground so the isolation is illusory |
| Automotive relay (item 31) | Contacts rated for 12V DC, not 230V AC — AC arc behaviour is entirely different |
| DIN contactor + 12V coil | Workable, but see D1 — it solves a sequencing problem that does not exist, at the cost of a coil driver, a GPIO and a DIY 230V box |
| **Meross MSS315 (owned)** | **Matter.** Needs a fabric controller on IPv6 + mDNS — i.e. HA/Apple/Google running 24/7. **ESPHome cannot be a Matter controller**, so `van-core` has no way to command it. See §2; also already commissioned to the house fabric |
| **Any plug-in smart plug** | Wrong *shape* for this load in a vehicle — see D1e |

### D1e — Why not a plug-in smart plug

The question recurs, so the reasoning is recorded rather than re-derived.

Nothing is wrong with plug-form smart plugs electrically; the objections are
van-specific and stack badly with D1c:

- **Vibration versus a 6–9A connection.** A 1–2kW element pulls 4.3–8.7A
  continuously for ~20 minutes. Plug/socket contacts work loose with road
  vibration, and a loose joint at that current heats. This is the mechanism
  behind most smart-plug fires, and a van applies the one stress a house never
  does. The connector policy already bans Dupont jumpers for exactly this reason;
  the stakes here are simply higher.
- **It adds interfaces to a now safety-critical earth path.** D1c makes the
  heater tank's bond to chassis load-bearing, because a floating supply has no
  RCD behind it. A plug and socket insert two more contacts into that path.
- **The heater may not even be plug-connected.** If it is hardwired, using a
  plug means *adding* a socket and plug purely to accommodate the plug — strictly
  worse on every axis above.
- **Consumer 16A ratings are optimistic** and rarely qualified for continuous
  duty; the relay and screw terminals are the usual weak point.

The Shelly Plus 1PM is the right shape precisely because it is designed to be
hardwired into a back-box behind a fixed load, with no connector in the current
path.

**If serial flashing is the real objection, there are two routes that avoid it:**
- **Athom** ships devices with **ESPHome pre-installed** — no flashing, no cloud,
  joins the SoftAP directly. `VERIFY` whether they offer a *hardwired inline*
  module with metering rather than only plug-form; plug-form loses on the points
  above.
- **Shelly on stock firmware** — fully offline, local RPC API, per D1. Costs only
  that `restore_mode` becomes a config field instead of a line in git.

### D2 — Self-power hazard for `van-core` — `REVISED: UPS dropped`

`CONFIRMED`: the habitation 12V distribution is fed from the **P310's 12V
output**, i.e. the `dc` switch. Both ESP nodes and all lighting sit downstream of
a switch the supervisor can throw.

**The 18650 UPS previously specified here is dropped.** A lithium cell in a
sealed enclosure in a van that reaches 50 °C in August is a liability, and
TP4056 modules charging under load are a known reliability weak point — it would
add a failure mode to protect against one. The residual risk it covered (P310
manually switched off, or low-battery cutoff) leaves the unit reachable under the
bed with a flat battery anyway: an inconvenience, not a stranding.

Consequently these become **load-bearing, not belt-and-braces**:
1. `dc` and `usb` marked `internal: true` — the *only* remaining protection.
   Nothing in software may reach them.
2. `restore_mode` set so the node never writes a switch state on boot.
3. Never shed the DC rail in load-shedding logic — see CLAUDE.md §9 Phase 5.
4. **D3 is now a blocker, not a nice-to-have** — with no battery to ride through
   a dropout, the node simply dies and stays dead until manual intervention.

Also verify the P310 12V output current rating (likely 10A/120W). Two ESP nodes
draw ~0.3A; the LED strips are the real consumer.

### D3 — `BLOCKER — VERIFY THIS WEEK`: does the P310's 12V output stay on at ~100mA?

Many power stations auto-shut DC ports below a low-load threshold. An ESP32 draws
~100mA. **With the UPS dropped (D2), there is no battery to ride through a
dropout — the node dies and stays dead until manual intervention.**

Test before ordering. If the port drops out, a bleed resistor becomes mandatory
and items 9–11 change.

---

## Connector policy

- **No Dupont jumpers anywhere.** They back out with vibration.
- **Carrier board with pluggable 3.5mm screw terminals** for all external cabling
  — the ESP module drops into female headers and is replaceable without touching
  wiring.
- **Key connectors by pin count** (2 = 12V, 3 = 1-Wire, 4 = button) so mis-plugging
  is physically impossible.
- Inside enclosures: JST-XH where a terminal block is overkill.
- Crossing a bulkhead or exposed to damp: automotive sealed connectors
  (Superseal / Deutsch DT) or potted glands.
- Stranded wire into any screw terminal: **ferrule, always**.
- Every cable labelled at both ends.
- Every enclosure entry through a gland, never a bare hole.
