# van-core — electrical wiring

`DRAFT 2026-08-31.` Pin assignments follow [nodes/van-core.yaml](../nodes/van-core.yaml)
except where §9 lists a delta — the YAML still carries the RGB button and the
third button, which this revision removes. Everything about the *board's* own
pinout is `VERIFY` against the Waveshare schematic before the carrier board is
cut (CLAUDE.md §2).

> **`STALE IN PLACES — checked against nodes/van-core.yaml 2026-10-11.`** This
> draft sat uncommitted for six weeks while the node moved on. Before cutting a
> board from it:
> - **The board is the 1.47B variant.** LCD backlight is **GPIO46**, not
>   GPIO48, and **GPIO47/48 are the onboard IMU's I²C bus** — do not use them.
> - **The microSD is in the config:** SDMMC 1-bit on GPIO14 clk / 15 cmd /
>   16 d0. That closes the §2 `14–18?` row and the SD half of §10 item 1.
> - **No RTC.** The clock is set from the phone each time the UI page opens
>   (D-22), so the DS3231 on GPIO9/10 below is not planned for now.
> - **The §9 delta has still not been applied.** The YAML keeps the RGB
>   button LED on GPIO9/10/11, the bezel sleep button on GPIO7 and the page
>   button on GPIO6, and does not use BOOT. The button and LED design in §2–§5
>   and the one in the config disagree; settle which is current before the
>   carrier board, not after.
>
> **`SUPERSEDED 2026-10-15` for buttons — D-30.** There is **one** button:
> **GPIO5 to GND**, short wire, nothing else. Internal pull-up; the 100 Ω and
> 100 nF were for the 3 m run and are not needed. Tap = screen, hold 1–3 s =
> manual AC, hold ≥5 s = cancel. **GPIO6/7 are unused**, BOOT is still not a
> runtime button, and sleep and parked mode are web-UI only. §3's J3, §4 and
> the BOOT plunger in §7 no longer apply. The LED outputs on GPIO9/10/11 stay
> in the config, harmless if nothing is wired to them.

Scope: `van-core` only — the ESP module, its carrier board, its enclosure and the
three cables that leave it. No 230V anywhere in this node: the arbiter commands
the inverter over BLE, so the highest voltage inside this box is 12V.

**Simplified 2026-08-31:** two buttons, no external LED. The page button is the
module's onboard BOOT switch for now; the only indicator is the module's onboard
RGB LED. This deletes the MOSFET drivers, the `+12V_PROT` rail, the 6-pin
connector and one gland — and restores the BOM's original 2/3/4 connector
keying, which the RGB button had broken.

---

## 1. Power chain

```
P310 12V output (dc switch)
  |   habitation 12V distribution (Wago 221)
  v
[F1] 1A blade fuse -- at the DISTRIBUTION end, not at the box
  |                   (a fuse protects the cable, so it goes at the source)
  v   W1: 2x 1.0mm² silicone, ~1 m
gland A -- 2-pin pluggable terminal J1  (+12V, GND)
  |
  +- [D1] SS34 series Schottky -- reverse polarity, ~0.4V drop @ 0.25A
  |
  +- C1 470uF/25V electrolytic + 100nF -- buck input bulk
  v
[U1] Mini560 12V->5V 3A
  |
  v
+5V rail --> ESP module 5V pin (onboard LDO makes 3V3)
  `- 3V3 from the module feeds the 1-Wire bus and the 4.7k pull-up
```

12V now stops at the buck. With no LED to drive there is no `+12V_PROT` rail
leaving the board, so nothing above 5V goes anywhere near a connector that
carries a signal.

**Star ground at one point on the carrier board**, next to J1. The 1-Wire return
and the button return both come back to that single point — not daisy-chained
through the buck module's ground pad.

### Current budget

| Load | Typ | Peak |
|---|---|---|
| ESP32-S3, WiFi SoftAP + BLE client | ~160 mA @5V | ~400 mA TX burst |
| LCD backlight at 80% (60s at a time) | ~30 mA @5V | — |
| Onboard WS2812, one colour dim | ~5 mA @5V | ~60 mA full white |
| Buzzer, 2 min/day | — | ~30 mA @5V |
| **12V input, steady state** | **~95 mA ≈ 1.15 W** | ~0.30 A |

Against the §5.4 budget of 2W for the whole control system. It fits, but
`van-water` and the buck's own ~15% loss are inside that same 2W — **meter the
finished box before it stays** rather than trusting this table.

The 1A fuse is far above the load by design: it protects W1 against a
chafe-to-chassis short, not the node. A 3A buck behind it absorbs its own inrush
without nuisance blows.

**D3 is closed by experience, not by measurement.** The P310's 12V port has not
been observed dropping out at low load, so the bleed-resistor contingency is
dropped and no footprint for it goes on the board. Worth knowing why that matters:
a bleed resistor sized to hold the port up would have cost ~2W, i.e. the entire
control-system budget, to prop up a 1.15W node. If a dropout ever does appear,
the answer is a different supply point, not a resistor.

---

## 2. Pin map

Board: Waveshare ESP32-S3-LCD-1.47 (ESP32-S3R8).

### Onboard — no wiring, but not free either

| GPIO | Function | Note |
|---|---|---|
| 40 | LCD SCLK | |
| 45 | LCD MOSI | also VDD_SPI strapping; the board's own, leave it alone |
| 42 / 41 / 39 | LCD CS / DC / RST | |
| 48 | LCD backlight | LEDC 20 kHz |
| 14–18? | microSD, SDMMC | `VERIFY`; the card is not in the YAML yet |
| **0** | **BOOT — now the page/cycle button** | see §4. Strapping pin: held across a reset it drops the board into download mode |
| **38?** | **Onboard WS2812 RGB — the only indicator** | `VERIFY` the pin; Waveshare S3 boards commonly use GPIO38 |
| 19, 20 | USB D− / D+ | |
| 43, 44 | UART0 | |

### External — through the carrier board

| GPIO | Signal | Mode | Conditioning |
|---|---|---|---|
| 4 | 1-Wire data | in/out | 4.7 kΩ to 3V3 at the ESP end (BOM item 3) |
| 5 | Kitchen AC button | input, pullup, inverted | 100 Ω series (BOM item 8) |
| 6 | External page button — **wired to the terminal, unused for now** | input, pullup, inverted | 100 Ω series |
| 8 | Buzzer | LEDC 2.7 kHz | 100 Ω series; MOSFET + flyback if magnetic |
| 9 | I²C SDA — DS3231 | *proposed* | 4.7 kΩ pull-ups on the module |
| 10 | I²C SCL — DS3231 | *proposed* | |

Free after this revision: **GPIO7, 11**, plus whatever the header exposes beyond
these. Six external GPIOs where the previous revision needed ten — the header
question in CLAUDE.md §2 stops being tight.

GPIO9/10 for the RTC are a proposal, not yet in the YAML. They are free now
because the LED drivers are gone; reserve the two header positions while there is
room.

---

## 3. Connector schedule

Keying is by pin count, so nothing can be mis-plugged in the dark. **With the RGB
button gone, the BOM's original 2 / 3 / 4 scheme stands exactly as written** —
the 6-pin exception in the previous revision is retracted.

| Ref | Type | Pins | Cable | Goes to |
|---|---|---|---|---|
| J1 | 3.5 mm pluggable | 2 | W1, 2× 1.0 mm² | Habitation 12V, via F1 |
| J2 | 3.5 mm pluggable | 3 | W2, 3-core 0.34 mm² | 1-Wire bus → fridge |
| J3 | 3.5 mm pluggable | 4 | W3, 4-core 0.25 mm² alarm cable | Kitchen AC button (+ spare pair) |

### J1 — 12V in (2-pin)
1 `+12V` · 2 `GND`

### J2 — 1-Wire bus (3-pin)
1 `+3V3` · 2 `DATA` · 3 `GND`

3-wire mode, not parasitic (CLAUDE.md §2). Two DS18B20s wired **in parallel** on
the same three conductors — fridge side wall and cabin ambient — plus an optional
third in free air for door detection, which costs no extra wiring.

### J3 — buttons (4-pin)
1 `BTN_AC` · 2 `GND` · 3 `BTN_PAGE` · 4 `GND`

Only pins 1–2 are populated today. **Pins 3–4 go to GPIO6 anyway**, so an
external page button can be added later with a screwdriver instead of a board
respin — which matters more than it looks, because of §4.

**Why the button terminal is not 2-pin.** J1 is 2-pin. A second 2-pin connector
in the same box means a plug that fits both, and plugging 12V into GPIO5 kills
the ESP instantly. Pin-count keying only works while the counts are distinct, so
the button gets 4 pins and the spare pair earns its keep.

---

## 4. Buttons — and the BOOT-pin decision

| Control | Where | GPIO | Gesture |
|---|---|---|---|
| Manual AC | Kitchen, external | 5 | Short = 45 min request, +30 min while active; long >1s = cancel. Also the parked-mode exit |
| Page / cycle | Onboard BOOT switch, for now | 0 | Short = wake, then cycle pages. **Long press must now also carry sleep and parked mode** |

### Switch circuit — identical for both
```
GPIO5 (or 6) --[100R]--+-- J3.1 -- W3 -- button NO contact
                       |
            internal pullup           button common -- J3.2 -- GND
                       |
              (100nF to GND, optional, footprint only)
```
Software debounce (`delayed_on: 50ms`) *is* the debounce; the 100 Ω is GPIO
ESD-diode protection on a long run, and the cap is a fit-if-needed footprint
(BOM item 8). A phantom press arms the inverter for 45 minutes and nobody
notices for days — leave both footprints on the board.

### Using BOOT as a runtime button — what changes

CLAUDE.md §2 and BOM D4 both say *do not*. That prohibition was written against
using it for a control input; **page cycling is not a control input**, so the
objection weakens to a mechanical one. Two things follow.

**The failure is benign but invisible.** Holding BOOT across a reset leaves the
board in download mode: it never runs. Nothing safety-critical depends on it —
with the node dead the arbiter never commands anything, so AC stays wherever the
P310 was left, which is the fail-toward-powered direction. But the node is dead
and gives no sign of it, and recovery is a power cycle you have to think of.

**A jammed plunger is a stuck BOOT.** Reaching a side-mounted 3 mm tactile switch
through a sealed enclosure means a printed plunger aligned to the switch. A
plunger that binds on a hot day holds BOOT down permanently, which is exactly the
above failure with no obvious cause. Print it with clearance and a positive
return, verify travel over a full open/close cycle of the lid, and **prefer an
external button on J3.3 the moment the plunger annoys you** — the terminal pins
are already there.

**The sleep and parked gestures have nowhere else to go.** The bezel sleep button
is deleted, so its 1–4s (sleep) and ≥5s (parked) presses must move onto the page
button, or live only in the web UI. Recommended split: **web UI is the primary
path for parked mode** — it is a deliberate, once-a-fortnight action and the
arming interlock refusal needs to be readable — with a long press on the page
button as the sleep-mode gesture. Exiting parked mode is unchanged and needs no
gesture at all: a press of the kitchen AC button already does it.

---

## 5. Indication — one LED, inside the box

The onboard WS2812 is the whole indicator budget now. It sits on the module,
behind the lid, next to a display that is blank most of the time.

**This costs the parked-mode blue blink its visibility**, which was the mitigation
for the real hazard in CLAUDE.md §6: park the van, then load food into it a week
later and drive off. Blue-blinking inside a closed box is not a mitigation.

**Fix it mechanically, not electrically:** a 3 mm hole in the lid over the LED
with a short clear-PETG or acrylic light pipe, or simply a translucent window
printed into the bezel. Costs nothing, adds no standby draw, and keeps the single
LED doing the job three were doing before. Position it while the lid is being
designed — retrofitting a light pipe into a finished bezel is a bad afternoon.

Keep the WS2812 dim. At full white it draws ~60 mA, i.e. more than a third of the
node, and it is 300 mm from a bed.

Colour scheme unchanged in intent, now on one LED: green = manual AC active,
amber = fridge-driven, blue slow blink = parked, off = inverter down.

---

## 6. 1-Wire run to the fridge

```
J2 ---- W2, 3-core ---- splice, outside the cold zone
                          |
                          `- ~15 cm FLAT ribbon across the door gasket
                                |  hinge side, mid-height
                                `- splice -- probe on the interior SIDE wall
```

The mechanical detail — flat cable only, hinge side, neutral-cure sealant on both
faces, side wall and never the back wall (that is the evaporator), foam pad on
the air side — is specified in CLAUDE.md §2 and not repeated here. Electrically
this is three conductors and one 4.7 kΩ resistor.

**W2's route matters more than its gauge:** keep it away from the AC lead to the
fridge and from the inverter output. 1-Wire is a slow single-ended bus behind a
4.7 kΩ pull-up — the most injection-prone signal in the box — and a probe that
reads `NAN` for 5 minutes trips `force_on` (CLAUDE.md §6). That fails noisily
rather than silently, which is the right direction, but it is still wrong.

---

## 7. Enclosure

ABS ~100 × 68 × 50 IP65 (BOM item 12), display on a hinged lid.

| | |
|---|---|
| **Lid** | ESP module in female headers, display through the cutout. Light seal round the bezel. **Plus two new features:** a plunger aligned to the onboard BOOT switch, and a light-pipe window over the onboard RGB LED |
| **Base** | Carrier board: buck, protection, three terminal blocks along one edge |
| **Gland A** (PG7, bottom face) | W1, 12V in |
| **Gland B** (PG7, bottom face) | W2, 1-Wire to the fridge |
| **Gland C** (PG7, bottom face) | W3, kitchen button |
| **Bezel** | No panel-mount buttons. BOOT plunger and LED window only |

- **The module's position is now mechanically load-bearing.** With a plunger
  bearing on a side-mounted switch, the board can no longer float in its headers:
  it needs at least two M3 standoffs so the plunger meets the switch in the same
  place every time. This is the one thing the simplification made *harder*.
- **All three glands on the bottom face**, so condensation and anything that
  tracks along a cable drips off rather than in.
- **Separate the buck from J2.** U1 and the 12V terminal at one end of the
  carrier board, the 1-Wire terminal at the other. A switching node next to a
  4.7 kΩ pull-up is a self-inflicted noise problem.
- **Vents for the buck**, placed so they are not also a water path — a slot on a
  vertical face, not a hole on top.
- M3 heat-set inserts; nyloc + nylon washer as the hinge pivot so friction is
  adjustable. PETG minimum, ASA if it sees sun. **Never PLA.**
- Ferrule on every stranded conductor entering a screw terminal. Label both ends
  of W1, W2 and W3.

Hinge friction is what actually fails in service: a loose hinge walks with road
vibration until the screen faces the floor.

---

## 8. Commissioning checks

In order, before the box goes in the van:

1. **Bench the supply alone.** 12V in, no ESP fitted: confirm 5.0 ±0.1V at the
   header, and confirm reverse polarity does nothing at all.
2. **Fit the ESP, meter the 12V input current.** Against §1: >200 mA steady means
   something is wrong before it is a budget question.
3. **1-Wire with both probes** in a glass of water — two addresses, plausible and
   equal readings. Then calibrate against a reference thermometer (CLAUDE.md §2);
   that offset is what makes the food-safety ceiling mean anything.
4. **Kitchen button on the full 3 m cable**, with the inverter running a real
   load. Watch the log for phantom presses for an hour. This is the test that
   decides whether the 100 nF gets fitted.
5. **The plunger, with the lid closed.** Ten open/close cycles, then confirm the
   button still actuates and — more importantly — that it *releases*. Then reset
   the board with the lid closed and confirm it boots rather than enumerating as
   a download device.
6. **The light pipe, in the dark.** Arm parked mode and walk out of the van. If
   you cannot see the blink from the door, the window is in the wrong place.
7. **24 h soak** with `nodes/van-core-soak.yaml` — BLE + SoftAP + web server +
   display together, before anything is wired in permanently.
8. **The fail-safe path, deliberately:** pull the BLE antenna, unplug the probe,
   confirm AC ends up **ON** — and confirm parked mode is **OFF** first, because
   parked is the one state where that check is supposed to fail (CLAUDE.md §11).

---

## 9. Delta against `nodes/van-core.yaml` — not yet applied

The YAML predates this revision. To match:

| Change | Detail |
|---|---|
| Delete | `pin_led_r`, `pin_led_g`, `pin_led_b` and the three `ledc` outputs |
| Delete | `pin_btn_sleep` (GPIO7) and its binary sensor |
| Add | onboard WS2812 on the `light` platform, `VERIFY` pin |
| Rework | the LED feedback logic onto one addressable LED |
| Rework | sleep-mode gesture onto the page button's long press; parked mode primarily via the web switch |
| Change | `pin_btn_page` from an external bezel button to GPIO0, keeping GPIO6 declared for the spare terminal pair |
| Add | I²C on GPIO9/10 when the DS3231 goes in |

Firmware is deliberately left alone here — this document is the wiring plan, and
the arbiter's button handling deserves its own pass with the tests in `test/`.

---

## 10. Open — close these before the carrier board is cut

| # | Item |
|---|---|
| 1 | Waveshare header — which GPIOs are actually broken out, and the SD pins |
| 2 | The onboard RGB LED's pin, and whether it is a WS2812 or a plain RGB |
| 3 | Physical position of the BOOT switch relative to the header, for the plunger |
| 4 | Buzzer type — piezo (direct drive) or magnetic (MOSFET + flyback diode) |
| 5 | Display offsets and rotation on first flash, before the lid cutout is measured |

`CLOSED:` the P310 12V port holding up at low load (BOM D3) — no dropout observed
in use; no bleed resistor, no footprint.
