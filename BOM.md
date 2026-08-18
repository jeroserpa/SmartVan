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

| # | Item | Qty | ~€ | Notes |
|---|---|---|---|---|
| 16 | ESP32 WROOM-32 devkit | 1 | 7 | No display needed; no BLE requirement |
| 17 | ADS1115 16-bit ADC module | 1 | 4 | ESP32 internal ADC is too nonlinear for the sender |
| 18 | Divider resistor for sender | 1 | — | **Value pending §8.7 measurement.** 220Ω if 0–190Ω sender. Sender run **confirmed <1m — no shielding needed** |
| 19 | Logic-level MOSFET (AO3400 / 2N7000) | 1 | 1 | Gates sender excitation — continuous DC corrodes a submerged wiper |
| 20 | DIN contactor, 12V DC coil, 20A/230V + enclosure | 1 | 25 | **Not an SSR** (fails closed) and not a mains-powered smart relay — see D1 |
| 21 | Buck 12V→5V 3A + fuse holder + fuses | 1 | 7 | |
| 22 | ABS enclosure IP65 + glands | 1 | 7 | |
| 23 | Automotive relay 30A + socket (future pump) | 1 | 4 | Phase 2b |
| | **Subtotal** | | **~40–50** | |

## Shared wiring stock

| # | Item | ~€ | Notes |
|---|---|---|---|
| 24 | Silicone-insulated stranded wire 0.5mm² (signal) | 10 | Silicone stays flexible cold; PVC cracks |
| 25 | Silicone stranded 1.0mm² (12V feeds) | 10 | |
| 26 | JST-XH connector kit + crimper | 12 | Board-level connections |
| 27 | Wago 221 lever connectors, assorted | 8 | 12V branch distribution |
| 28 | Bootlace ferrule kit + ratchet crimper | 20 | One-off tool. Non-negotiable for stranded wire into screw terminals |
| 29 | Adhesive-lined heatshrink assortment | 8 | Adhesive-lined, not plain — moisture ingress |
| 30 | Cable labels / label printer tape | 5 | |
| | **Subtotal** | **~73** | Much of it reusable stock |

## Measurement tools

| # | Item | ~€ | Justifies |
|---|---|---|---|
| 31 | DC clamp meter, 200A+ | 30 | Confirms the 1200W alternator figure (§9 Phase 4). Also invaluable for every future debug |
| 32 | Cheap plug-in energy meter | 12 | Independent cross-check of P310 readings |

---

## Totals

| | ~€ |
|---|---|
| Phase 1 | 93 |
| Phase 2 | 40–50 |
| Wiring stock | 73 |
| Tools | 42 |
| **All-in** | **~253** |
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

### D1 — Mains switching for the water heater (item 20) — `REVISED`

`CONFIRMED`: heater is **230V, 1–2kW**.

**Earlier recommendation (Shelly Plus 1PM) is withdrawn.** Reason: the heater's
supply is downstream of the inverter that the arbiter cycles. A mains-powered
smart relay loses power on every inverter cycle, reboots, and takes 10–20s to
rejoin the SoftAP — so commanding it requires "request inverter → wait for AC →
wait for boot → command", and any mid-heat cycle leaves its state ambiguous.

**Chosen: DIN-rail contactor with a 12V DC coil**, driven directly from
`van-water`. ~€20–30.
- Coil runs from the always-on 12V bus → control is instant and independent of
  inverter state.
- **Mechanical contactors fail open.** An SSR fails *closed*, which for a 1–2kW
  heating element is dangerous. Do not use an SSR here.
- Metering is not lost in practice: the P310's `output_power` delta confirms
  whether the element drew current.
- Mount in a proper enclosed box. 2.5mm² mains-rated cable (4.3–8.7A).

### D1b — Heater energy budget

1500W × 20 min = **500Wh ≈ 14% of usable capacity per heating cycle.** The 750W
array cannot cover this from surplus, so any heating draws down the battery.

Therefore the heater is a **driving-time and full-battery load, not an
opportunistic-solar one**:
- Permit only when SOC > 85% **and** (alternator charging active **or**
  `input_power` > 400W)
- Hard lockout below SOC 70%

Otherwise one shower costs half a day of fridge autonomy.

### D1c — 230V safety `VERIFY FIRST`

**Determine whether the P310's AC output is neutral-earth bonded or floating.**
Most portable stations float. On a floating output **an RCD will not trip** —
there is no reference for fault current to return through, so protection you
would assume exists does not. A single fault gives no shock path (not inherently
unsafe), but double faults go undetected, with 1–2kW of mains running to a water
heater in a damp vehicle.

Minimum measures regardless:
- All 230V terminations inside an enclosed, non-accessible box
- 2.5mm² mains-rated cable
- No terminal blocks inside a locker that gets reached into

Making an RCD functional requires bonding N–E in the van, which has its own
consequences and should be decided separately.

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
