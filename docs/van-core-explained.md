# van-core, explained

A walkthrough of everything written on 2026-08-18: what each file is, what each
block inside it does, and *why it is that way rather than the obvious way*.

Reading order: §1 (the map) → §2 (the state machine) → §3 (the adapter) →
§4 (the YAML). §7 is the list of things that are still placeholders and will
stop it from working if flashed as-is.

---

## 1. The map

Seven files were added. They fall into three groups.

```
components/ac_arbiter/       the controller — C++
  arbiter_core.h/.cpp          the state machine. NO ESPHome headers. All decisions.
  ac_arbiter.h/.cpp            ESPHome adapter. Sensor plumbing. NO decisions.
  __init__.py                  ESPHome codegen: turns the YAML block into C++ calls.

test/                        proof the controller is correct — runs on the laptop
  test_arbiter.cpp             ~30 scenarios, incl. every fail-safe path
  Makefile                     g++ only. No board, no toolchain, no ESPHome.

nodes/ + common/             the plant — YAML
  van-core.yaml                pins, entities, tunables, display, the real node
  van-core-soak.yaml           the 24h stress test that must pass FIRST
  common/base.yaml             logger/OTA/web_server shared by every node
```

The split in the first group is the single most important design decision in
the codebase (`docs/decisions.md` D-01). Everything else follows from it.

### Why the controller is split from ESPHome

`arbiter_core.cpp` includes exactly one header: `<cstdint>`. It knows nothing
about sensors, switches, BLE or ESPHome. It is a function of
`(time, inputs) → outputs`.

That is what makes `test/` possible. The fail-safe paths — BLE dropped, probe
unplugged, loop starved, `millis()` rolling over at 49.7 days — are otherwise
only reachable by physically breaking things in a van and waiting. On the host
they run in milliseconds:

```bash
cd test && make
```

The rule that keeps this valuable: **no `if` that decides whether the inverter
should be on may appear anywhere except `arbiter_core.cpp`.** Not in the
adapter, not in a YAML lambda. The moment one does, the test suite silently
stops covering the system.

### The data flow

```
P310 ──BLE──> fbot component ──> sensors: output_power, input_power, battery
DS18B20 ─1-Wire─> dallas_temp ──> template sensor (EMA + timeout) ──┐
kitchen button ──> binary_sensor ──> manual_press() / manual_cancel()│
sleep / drive switches ─────────────> set_sleep_mode() / set_drive_inhibit()
                                                                     │
                                          ┌──────────────────────────┘
                                          v
                     AcArbiter::update()  — every 5s
                       stamps freshness, builds ArbiterInputs
                                          v
                     ArbiterCore::tick()  — the only place decisions happen
                                          v
                     ArbiterOutputs { ac_on, reason, flags, timers }
                                          v
                     ac_switch->turn_on/off()   ← the ONLY writer
                     + display, web UI, LED, buzzer (read-only consumers)
```

---

## 2. `arbiter_core` — the state machine

### 2.1 `ArbiterConfig` — the tunables

A plain struct of thresholds with power-on defaults. Nothing here is a magic
constant: every field is bound to a `number` entity in `van-core.yaml`, so it
can be changed from a phone browser in a car park. The struct is exposed by
reference (`arbiter->config().temp_on_c = x`) and the YAML `on_value:` handlers
write straight into it.

Grouped as: fridge thermostat, coast (sleep) setpoints, compressor-satisfied
detection, cycle shaping (min on/off), manual button timers, surplus, fail-safe
timers, drive inhibit.

### 2.2 `ArbiterInputs` — everything it is allowed to know

```c++
bool ble_connected;
bool temp_valid;         float fridge_temp_c;
bool power_valid;        float output_power_w;
bool input_power_valid;  float input_power_w;
bool soc_valid;          float soc_pct;
bool sleep_mode;
bool drive_inhibit;
```

Note the paired `*_valid` flag on every measurement. That is the honest
expression of staleness, and it exists because of one property:

> **An invalid input never argues for OFF.**

A missing temperature does not mean "cold". A missing power reading does not
mean "compressor idle". Both of those mistakes turn the inverter off with a
warm fridge.

### 2.3 `ArbiterOutputs` — and `reason`

Besides `ac_on` and the individual request flags, the arbiter always publishes
**why**: `AcReason` is an enum — `BOOT`, `BLE_LOST`, `TEMP_STALE`, `WATCHDOG`,
`FRIDGE_HARD`, `FRIDGE`, `MANUAL`, `SURPLUS`, `OFF` — rendered as a string on
the display and in the log line.

"The inverter is on" without a reason is an undebuggable system. Three weeks
later, looking at a CSV, `reason` is the difference between diagnosing a
problem and guessing at one.

Note the initialisers: `ac_on = true`, `force_on = true`, `reason = BOOT`. The
struct's *default state is powered*. Even if something goes wrong before
`begin()` runs, the answer is ON.

### 2.4 `begin()` — the boot bias

```c++
fridge_since_ms_ = now_ms - cfg_.min_off_ms;   // back-date the lockout
temp_fresh_ms_   = now_ms;                     // pretend the probe was fresh
compressor_busy_ms_ = now_ms;                  // pretend the compressor was busy
```

Two different tricks, both deliberate:

- **Back-dating** the anti-short-cycle timer means a warm fridge at boot does
  not have to wait out a 5-minute lockout it never earned. There is no
  compressor to protect — nothing was running.
- **Forward-dating** the freshness marks biases toward ON while nothing is
  known. `compressor_busy_ms_ = now` means "assume the compressor is running",
  which blocks release, which keeps AC on.

### 2.5 `tick()` — the order of operations

This is the whole controller, in order:

**1. Watchdog.** Before anything else, check whether our *own* loop was starved:

```c++
const bool watchdog_tripped = elapsed(now_ms, last_tick_ms_, cfg_.watchdog_ms);
```

If more than 30s passed since the last tick, something blocked the cooperative
loop — an SD write, a display redraw, a BLE stall. That is the exact failure
mode CLAUDE.md §11 warns about, and it is *caught* rather than assumed absent.

**2. Freshness bookkeeping.**

```c++
if (!in.power_valid || in.output_power_w >= cfg_.compressor_idle_w)
  compressor_busy_ms_ = now_ms;
```

Read that `!in.power_valid ||` carefully — it is decision D-03. An unreadable
power sensor counts as *compressor running*. If unknown counted as 0W, a BLE
hiccup mid-cooling-cycle would satisfy the 90s idle test and release the
inverter with the fridge still warm.

**3. `force_on` — the fail-safe, evaluated first.** An if/else-if chain, in
precedence order, that also sets `reason`:

| Condition | Reason | Why it forces ON |
|---|---|---|
| within 60s of boot | `BOOT` | nothing is known yet |
| `!ble_connected` | `BLE_LOST` | can't see power, can't command the switch; hold the request so the fridge survives the reconnect |
| probe stale > 5 min | `TEMP_STALE` | no temperature = no thermostat |
| watchdog tripped | `WATCHDOG` | our own loop is unhealthy |

Only if all four are false does `force_on` become `false`.

**4. The three request updaters** run (§2.6–2.8).

**5. Drive inhibit** — Phase 4, and structurally different from everything else:
it is a **suppressor, never a request**. It also self-expires after 4h, so a
stuck-true engine signal cannot hold the fridge off indefinitely.

**6. The final equation:**

```c++
const bool requested = fridge_req || manual_req || surplus_req;
ac_on = force_on || fridge_hard || (requested && !inhibit_active);
```

`fridge_hard` (the 10 °C override) sits *outside* the inhibit gate, alongside
`force_on`. Silence and interlocks are preferences; food is not.

### 2.6 `update_fridge_()` — the thermostat

```c++
if (!in.temp_valid) return;   // hold the previous request
```

It refuses to act on a temperature it does not have — and it does not need to
panic, because `force_on` is already handling that case above.

**Hard override first.** At ≥ 10 °C it sets `fridge_req` *and* latches
`fridge_hard`, ignoring the anti-short-cycle timer. The latch is deliberate:
once the fridge has been let go that far, run a proper cycle all the way back
down rather than stopping the instant it drops below 10.

**Coasting changes the setpoints, not the logic:**

```c++
const bool coasting = in.sleep_mode || in.drive_inhibit;
const float ceiling = coasting ? sleep_ceiling_c : temp_on_c;   // 6.0 vs 7.0
const float floor_c = coasting ? sleep_target_c : temp_off_c;   // 1.0 vs 4.0
```

This is the whole of "sleep mode is drive inhibit with a different trigger" —
one pair of ternaries. Raise the ceiling so a cycle starts later; drop the
floor so that when one does run it goes all the way down and maximises the
remaining coast.

**Release needs all three conditions**, and they are separate for separate
reasons:

```c++
const bool cold    = temp < floor_c;                                // it worked
const bool quiet   = elapsed(now, compressor_busy_ms_, 90s);        // compressor stopped
const bool settled = elapsed(now, fridge_since_ms_, min_on_ms);     // worth having started
```

`quiet` is the money condition. Every second between the compressor stopping
and the inverter shutting down is pure 35W idle waste, paid every cycle. 90s is
debounce, not caution — the BLE poll sees the stop within 5s.

### 2.7 `update_manual_()` — the cooking button

Four ways it ends, checked in this order:

1. **Hard ceiling** (3h from first press). Three hours of inverter is never an
   accident worth honouring.
2. **Auto-release**: past the 10 min grace period *and* nothing above 150W has
   drawn power for 10 min. 150W sits well above the fridge compressor (35W) and
   far below an induction plate, so a running fridge cannot hold the timer open.
3. **Timer expiry** at the deadline.
4. **`manual_cancel()`** — the long press.

`manual_remaining_s` reports **whichever release comes first**, not just the
timer. If the pan came off the heat 6 minutes ago, the display counts down the
4 minutes of auto-release, not the 38 minutes on the clock. The buzzer warning
fires off that same number.

`manual_press()` when already active *extends* by 30 min, clamped to the 3h
ceiling measured from the **first** press — so mashing the button cannot walk
the ceiling forward.

### 2.8 `update_surplus_()` — opportunistic solar

Fully suppressed while coasting, or if any of the three inputs it needs is
invalid. Otherwise: `input_power - output_power > 200W` **and** SOC ≥ 85%.

The release is deliberately asymmetric — surplus must fall below *half* the
margin, and 10 minutes must have passed, before it lets go. A passing cloud
must not cost an inverter start.

### 2.9 `elapsed()` — the rollover-safe helper

```c++
inline bool elapsed(uint32_t now, uint32_t mark, uint32_t span) {
  return static_cast<uint32_t>(now - mark) >= span;
}
```

Every time comparison in the file goes through this. Unsigned subtraction makes
the 49.7-day `millis()` rollover a non-event — which matters for a node expected
to run for months between reboots. There is a dedicated test for it.

---

## 3. `ac_arbiter` — the ESPHome adapter

Deliberately dumb. Its entire job: read sensors, timestamp them, hand a snapshot
to `tick()`, write one switch.

### `FreshValue`

```c++
bool valid(uint32_t max_age_ms) const {
  return seen_ && !std::isnan(value_) && (millis() - stamp_) < max_age_ms;
}
```

The reason this class exists (decision D-02): **ESPHome sensors keep their last
state forever.** A DS18B20 whose cable has been cut through the door gasket
still reports 3.2 °C indefinitely. An arbiter reading `.state` would happily
keep the inverter off while the food warms up over two days.

So staleness is tracked explicitly. Belt and braces: the fridge sensor *also*
carries a `timeout:` filter in YAML that publishes NAN, so the value goes
invalid by two independent mechanisms.

**Consequence to remember:** `sensor_max_age` (45s) must comfortably exceed the
slowest feeding sensor's update interval, or the arbiter forces AC on forever.

### `setup()` and setup priority

```c++
float get_setup_priority() const override { return setup_priority::AFTER_CONNECTION; }

void setup() { core_.begin(millis()); ac_switch_->turn_on(); }
```

It commands AC ON in `setup()`, before any sensor has reported and before BLE
has connected. Fail toward powered from the first millisecond. The `on_boot`
priority-800 log line in the YAML is the belt to this braces.

### `update()` and the re-assert

Runs every 5s. Builds `ArbiterInputs`, calls `tick()`, then:

```c++
const bool changed = !written_once_ || out.ac_on != last_written_;
const bool due     = (now - last_write_ms_) >= REASSERT_MS;   // 60s
```

It rewrites the switch on change **or** once a minute regardless. The P310 can
drop and re-establish BLE, or be poked from its own front panel. The arbiter is
the authority and says so once a minute — which is also why the `ac` switch
stays visible in the web UI: a manual override there is a *temporary* one by
design, good for commissioning, self-correcting afterwards.

Also note `manual_press()` calls `update()` immediately rather than waiting for
the next 5s tick. A button press must light the LED now, not eventually.

### `__init__.py`

ESPHome codegen — pure wiring. It validates the YAML block and emits the
`set_*()` calls.

The one thing worth noticing is what is **not** in the schema: no thresholds.
Temperatures, timers and power floors are deliberately not config keys, because
config keys require a reflash to change. They are `number` entities instead.

Required vs optional is also a decision: without `fridge_temperature`,
`output_power`, `ble_connected` and `ac_switch` the arbiter has no way to ever
know it is safe to turn anything off, so it would sit in `force_on` forever.
`input_power` and `battery_level` are optional because only `surplus_req` needs
them.

---

## 4. `nodes/van-core.yaml` — the plant

### Substitutions (pins)

All pins live at the top as substitutions so there is one place to fix them
after checking the schematic. Two notes:

- The display pins come from the **espp board-support header** for this board,
  not from a datasheet in hand. Marked `VERIFY` before soldering.
- **GPIO0 (BOOT) is not used as a runtime button.** It is a strapping pin;
  holding it across a reset drops the board into download mode. That is a
  baffling failure to debug months later.

External wiring is grouped onto screw terminals keyed by pin count — 2-pin =
12V, 3-pin = 1-Wire, 4-pin = button+LED — so they cannot be mis-plugged in the
dark.

### `esphome:` / `esp32:`

`min_version: 2025.7.0` pins the toolchain: a routine ESPHome update must not
invalidate a working config two days before a trip. Framework is `esp-idf` to
match the ESP-FBot examples — this is flagged `UNVERIFIED` against the SD card
component, and settling it is one of the jobs of the soak config.

### `wifi:` — SoftAP only

There is no `station` block at all, on purpose: this node must never sit there
scanning for a network that does not exist.

`TODO` still open: CLAUDE.md §4 wants `max_connection` raised to 8, and ESPHome
exposes no YAML key for it. With phone + water + heater + vehicle the default
of 4 is already met.

### `fbot:` — the P310 BLE link

`polling_interval: 5s` matches the arbiter tick. No point polling faster than
the thing that consumes it.

### `binary_sensor:` — the buttons

The kitchen button uses `on_click` with two windows: 50–900 ms → `manual_press()`,
1–10 s → `manual_cancel()`.

The `delayed_on: 50ms` / `delayed_off: 50ms` filters are not decoration. A long
button run past a 3300W inverter picks up induced transients; a spike would have
to persist 50ms to register. A phantom press arms the inverter for 45 minutes
and goes unnoticed for days.

The five `template` binary sensors at the bottom expose the arbiter's internal
flags to the web UI and the log. Read-only mirrors, no logic.

### `sensor:` — the fridge probe chain

Three stages, and the layering is the interesting part:

1. **`fridge_temp_raw`** — `dallas_temp`, unfiltered, 10s. This is what the
   coast test and the CSV want: no smoothing, no lies.
2. **`fridge_temp`** — a `template` sensor that just returns the raw state, then
   applies the filters. This is what the **arbiter** reads.
   - `exponential_moving_average: alpha 0.05` ≈ a 3-minute time constant. This
     is what replaced the water bottle from an earlier revision: door-opening
     excursions are damped in software, and real fridge dynamics are far slower
     anyway.
   - `timeout: 60s → NAN`. Publishing NAN is how the arbiter learns the probe is
     gone; 5 minutes later `TEMP_STALE` forces AC on.
3. **`cabin_temp`** — second probe on the same 1-Wire bus. Correlates directly
   with duty cycle, costs one address and no extra wiring.

The reason for the template-sensor indirection: filters applied to the raw
sensor would corrupt the raw log too. Two sensors, two purposes, one probe.

### `switch:`

`ac_switch` carries the single-writer comment. `dc_switch` carries a louder one:
**never shed it** — the 12V habitation bus, every ESP node and all the lighting
hang off it.

`sw_sleep_mode` is `RESTORE_DEFAULT_OFF` and also blanks the display, because
blanking is half the point of sleep mode.

`sw_drive_inhibit` is `ALWAYS_OFF` — Phase 4 ships disabled, and until
`van-vehicle` exists this switch is the bench-test stand-in for an engine
signal. Leaving it on cannot strand the fridge: the arbiter's own 4h timeout
bounds it.

### `number:` — 15 tunables

Every one follows the same pattern: `optimistic: true`, `restore_value: true`,
`entity_category: config`, and an `on_value:` lambda that writes into
`arbiter->config()`. Field tuning never needs a laptop.

Two carry meaning in their limits rather than their defaults:
- **Sleep ceiling is capped at 8 °C** — already above the ideal band for meat
  and dairy. The cap is a food-safety decision expressed in the schema.
- **Manual release power** ranges 50–500W around the 150W default, sitting in
  the gap between a 35W compressor and any cooking load.

### `interval:` — LED, buzzer, blanking

- **1s LED**: green = manual, amber = fridge-driven, off = inverter down. Both
  levels are deliberately dim — this is a metre from where someone sleeps.
- **5s buzzer**: chirps inside the manual warning window, at most once per 30s,
  and **never in sleep mode**. Waking someone to tell them the inverter is about
  to turn itself off is absurd.
- **1s blanking**: 60s of no input, or sleep mode, and the backlight goes to
  zero. No night-light, no glow through a printed bezel.

### `display:` — four pages

Battery → fridge/arbiter → water (Phase 2 stub) → diagnostics. Cycled by the
bezel button; the first press only wakes, because cycling pages on a dark screen
is useless.

1s refresh, simple fonts, no animation — the BLE task must not be starved.

Every value is guarded with `has_state()` so a missing sensor shows `--` rather
than garbage. The fridge page surfaces the configured sleep ceiling, because
that is a food decision the automation must never make silently. The diagnostics
page prints `192.168.4.1` literally: mDNS is unreliable on SoftAP, so that is
the address to type, never a hostname.

Fonts come from `gfonts://Roboto`, which needs internet **on the build machine
only** — compile at home, then join the SoftAP to upload.

---

## 5. `common/base.yaml`

Logger, OTA, web server, safe mode, and three diagnostic sensors, shared by
every node. Three things worth knowing:

- **There is no `api:` block.** No Home Assistant exists in the van and none
  ever will, so the native API is dead weight and one more thing to reconnect.
- **`web_server: local: true` is mandatory.** Without it the UI fetches its
  JS/CSS from esphome.io at page load — exactly the internet dependency the
  project forbids. With it, the assets are compiled into the firmware.
- **`safe_mode:`** — if a bad OTA makes the node crash on boot, it comes back as
  a bare AP that can be reflashed, rather than a brick under a bed.

Log level is INFO, not DEBUG: this node runs BLE, SoftAP, a web server and a
display in one cooperative loop, and logging is not free.

---

## 6. `nodes/van-core-soak.yaml` — the test that comes first

**This is not the real node and contains no arbiter, no relay, and no writes to
the P310.** It runs every load-bearing subsystem simultaneously and nothing
else, to answer three questions before anything is wired in:

1. Does the BLE link hold for 24h with SoftAP + web + display + SD all active?
2. Do `sd_mmc_card` and ESP-FBot build under one framework? (esp-idf, both —
   compiling this file *is* the proof)
3. Is there enough RAM once VFS/LWIP savings are disabled and fatfs is linked in?

Pass criteria are written down **before** the test rather than rationalised
after: zero BLE disconnects or reconnects inside 60s; no reboots; free heap flat
rather than trending down; ~2880 CSV lines with no gaps.

The instrumentation is `ble_drops` / `ble_longest_gap_s` globals driven by the
`connected` sensor's `on_press`/`on_release`, plus free-heap and free-PSRAM
sensors. The backlight is pinned ON for the whole run deliberately — worst case
for both power and DMA contention. The point is to fail here, not later.

And it does something useful while it runs: with the inverter left permanently
on, the CSV it writes **is** the 24h duty-cycle dataset that §8.4 calls the
single most important unknown in the project.

Timestamps are seconds-since-boot, which is right for a soak (a reboot shows as
an obvious discontinuity) and wrong for the real logger — that needs the DS3231.

---

## 7. What is still a placeholder

Nothing here will work if flashed as-is. In order of how badly it breaks:

| Where | What | How to fix |
|---|---|---|
| `van-core.yaml` sensors | **Both DS18B20 addresses are `0x...28` / `0x...29` placeholders** | Flash once, read the 1-Wire scan from the log, paste the real addresses. Both must be written down or swapping a probe silently swaps the roles. |
| `van-core.yaml` display | Panel offsets/rotation from a board header, not a datasheet | A 34px offset in the wrong axis shows as a coloured band down one edge. Fix on first flash. |
| `van-core.yaml` pins | Whole pinout from the espp header | `VERIFY` against the Waveshare schematic before soldering the carrier board. |
| `van-core.yaml` wifi | `max_connection` still 4 | Settle before adding the fourth client, not after. |
| `esp32:` framework | esp-idf + `sd_mmc_card` marked `UNVERIFIED` | The soak config settles it by compiling. |

And the standing procedure from CLAUDE.md §11, which the whole architecture
exists to make survivable:

> Before every trip: pull the BLE antenna, unplug the probe, and confirm the
> inverter ends up **ON**.
