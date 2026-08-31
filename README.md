# van-supervisor

ESPHome energy and services supervisor for a van conversion. No router, no Home
Assistant, no internet — in the van or during provisioning.

`CLAUDE.md` is the source of truth for why any of this is shaped the way it is.
This file is just how to build and test it.

## What exists

| Path | What it is |
|---|---|
| `components/ac_arbiter/arbiter_core.{h,cpp}` | The AC arbitration state machine. Plain C++, zero ESPHome headers, host-testable. |
| `components/ac_arbiter/ac_arbiter.{h,cpp}` | ESPHome adapter: reads sensors, tracks their freshness, writes exactly one switch. No control logic. |
| `test/` | Host-compiled unit tests for the state machine. |
| `nodes/van-core.yaml` | Phase 1 node: P310 BLE link, fridge probe, arbiter, manual button, display, SoftAP. |
| `nodes/van-core-soak.yaml` | The 24h BLE + SoftAP + web + display + SD stability test. **Run this first.** |
| `common/base.yaml` | Logger, OTA, web server, diagnostics. Shared by every node. |
| `tools/fbot_probe.py` | Laptop-side BLE client. Protocol validation and 24h logging with no microcontroller. |
| `ui/index.html` | The web UI that replaces the BrightEMS app. One self-contained file: no framework, no build step, no asset from the internet. |
| `components/van_ui/` | Serves that page, gzipped, from flash on the web server ESPHome already runs. |
| `tools/pack_ui.py` | Packs `ui/index.html` into `components/van_ui/van_ui_html.h`. Run it after every UI edit. |
| `tools/mock_core.py` | A fake van-core on the laptop. Same `/events` + REST surface, so the UI is developed and tested with no hardware. |

## Test the arbiter (no hardware needed)

```bash
cd test && make
```

(On Windows with MSYS2/mingw the binary is `mingw32-make`; the Makefile itself
is plain and portable.)

Builds with `g++ -Wall -Wextra -Werror` and runs ~150 assertions across 46 cases in under a
second: fail-safe paths, the fridge block scheduler, anti-short-cycle,
sleep-mode coasting, the manual timer, surplus hysteresis, the Phase 4 drive
inhibit, parked mode and its two-step confirmation, and the 49.7-day
`millis()` rollover.

Real bugs have been caught here rather than in a van — see `docs/decisions.md`
D-04, D-05 and D-13, the last of which would have left the inverter running
24/7 and the project saving nothing. Every change to the state machine gets a
test in the same commit.

## Build the firmware

```bash
pip install esphome
```

Then, **from the `nodes/` directory** (ESPHome resolves `!secret` next to the
config file, not from the repo root):

```bash
cd nodes && cp secrets.yaml.example secrets.yaml
```

```bash
cd nodes && esphome compile van-core.yaml
```

## The web UI

Two pages are served, on purpose:

| URL | What |
|---|---|
| `http://192.168.4.1/ui` | The custom UI. Power, fridge and the arbiter's reasoning, all the tunables, diagnostics. |
| `http://192.168.4.1/` | Stock ESPHome UI. Kept as the fallback that lists every entity when the custom page is wrong about one. |
| `http://192.168.4.1/van-ui/portal` | The captive landing page, as the phone sees it. |

It is a **view**. It never decides anything: it drives the same request flags
the kitchen button drives, and the arbiter stays the single writer of
`ac_switch`.

### Setting up a phone (once)

The AP is a network with no internet, so the phone will say so. That is
deliberate — see `docs/decisions.md` D-10. It keeps mobile data as its default
route, so the rest of the phone still works while parked at the van.

1. Join the van AP. When the phone asks, **stay connected** despite no internet.
   Android: also turn off "switch to mobile data automatically" for this
   network.
2. A **"Sign in to network"** notification appears — tap it. That is van-core's
   landing page. Tap **Open van-core**.
3. In the browser, **Add to Home Screen**.

After that it is one tap on an icon. On iOS it launches full-screen with no URL
bar; on Android it is a shortcut into Chrome, because Chrome only installs a
real web app over HTTPS and this node serves plain http.

The phone auto-joins the AP from then on. Step 2 exists only for the first time
and as the way back in if the icon is ever lost.

To hand the connectivity-probe URLs back to ESPHome's own captive portal, set
`captive_landing: false` under `van_ui:` in `nodes/van-core.yaml`.

### Working on it without hardware

```bash
python tools/mock_core.py --fast 60
```

Then open `http://127.0.0.1:8080/`. The mock speaks ESPHome's `/events` SSE
stream and `POST /<domain>/<object_id>/<action>`, so the page runs unchanged on
the ESP32 afterwards. Edit `ui/index.html`, reload the browser — no rebuild.

The states worth designing for are the broken ones, so they are one flag away:

```bash
python tools/mock_core.py --fault ble
```

`--fault ble | probe | flat | night` gives a dropped BLE link, a stale fridge
probe, a 12% battery and a sleeping van.

### Shipping a UI change

```bash
python tools/pack_ui.py
```

This regenerates `components/van_ui/van_ui_html.h`, which is committed. It packs
four files — `index.html`, `portal.html`, `manifest.webmanifest` and `icon.png`
— into ~14 kB of flash. The component hashes all four at codegen time and
**fails the build** if you forgot — a firmware that flashes cleanly but ships
last week's UI is a bad afternoon.

The icon is generated, not committed as an opaque blob, so it cannot drift from
the page's palette:

```bash
python tools/make_icon.py
```

### After the first flash

Open **Diag > All entities**. Anything in amber is an entity the page does not
know about. If something the page needs is missing, the entity id is wrong:
fix it in the single `ENTITIES` block at the top of the `<script>`, nowhere
else.

## Flashing and OTA, without a router

Everything below runs offline once compiled, but **compiling needs internet** —
external components come from GitHub and the fonts come from Google Fonts.

1. Compile at home, on a normal network.
2. Join `van-core`'s SoftAP (the laptop then has no internet — this is why step
   1 is separate).
3. `esphome upload van-core.yaml --device 192.168.4.1`

Do not discover the ordering of those steps in a car park.

## Before it goes in the van

1. Run `van-core-soak.yaml` for 24h with the inverter permanently on. It proves
   the BLE link survives the other loads *and* produces the duty-cycle dataset
   that every saving estimate in `CLAUDE.md` depends on.
2. Fill in the two DS18B20 addresses in `van-core.yaml` from the 1-Wire scan
   logged on first boot. They are placeholders.
3. Calibrate the fridge probe against a thermometer in a glass of water.
4. Test the fail-safe path deliberately: pull the BLE antenna, unplug the probe,
   confirm the inverter ends up **ON** in both cases.
