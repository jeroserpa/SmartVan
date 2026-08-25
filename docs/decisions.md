# Decisions

ADR-style. What was chosen, what it rules out, and what would reopen it.
Dated. Append-only.

---

## 2026-08-18 — D-01: the arbiter is a C++ component, not YAML lambdas

**Decision.** The AC arbitration state machine lives in
`components/ac_arbiter/arbiter_core.{h,cpp}` as plain C++ with no ESPHome
headers, wrapped by a thin ESPHome adapter in `ac_arbiter.{h,cpp}`. YAML
describes the plant; C++ implements the controller.

**Why the split goes exactly there.** `arbiter_core` includes only `<cstdint>`,
so `test/` host-compiles it with g++ and exercises the fail-safe paths on a
laptop in milliseconds. Those paths — BLE loss, stale probe, starved loop,
millis rollover — are otherwise only reachable by breaking things in a van.

**What the adapter is forbidden to contain.** Any `if` that decides whether the
inverter should be on. It reads sensors, timestamps them, and writes one switch.
If a control decision ever appears in `ac_arbiter.cpp` or in a YAML lambda, the
test suite silently stops covering the system.

**Reopen if:** ESPHome's cooperative loop turns out to starve the BLE task even
after moving SD and display to their own FreeRTOS task. Only then is full custom
firmware justified — and it means re-implementing the P310 BLE protocol, which
is the hardest part of the project and already solved upstream.

---

## 2026-08-18 — D-02: sensor freshness is tracked in the adapter, not trusted from ESPHome

**Decision.** `FreshValue` stamps every incoming sensor state with `millis()`,
and the arbiter is told `*_valid` per input rather than being handed a float.

**Why.** ESPHome sensors keep their last state indefinitely. A DS18B20 whose
cable has been cut still reports 3.2 °C forever, and an arbiter reading `.state`
would keep the inverter off while the food warms up. Staleness has to be
represented explicitly or the fail-safe cannot fire.

Belt and braces: the fridge sensor also carries a `timeout:` filter that
publishes NAN, so the value goes invalid by two independent mechanisms.

**Consequence.** `sensor_max_age` (45s) must comfortably exceed the slowest
feeding sensor's update interval, or the arbiter will force AC on permanently.
It is a config key, not a constant, for exactly that reason.

---

## 2026-08-18 — D-03: unknown power counts as "compressor running"

**Decision.** When `output_power` is invalid, the arbiter treats the compressor
as busy rather than idle.

**Why.** The release condition needs 90s of sub-15W draw. If a missing reading
were treated as 0W, a BLE hiccup during a cooling cycle would satisfy it and
release the inverter with the fridge still warm. Unknown must never argue for
OFF. Covered by the `unreadable power sensor never releases the inverter early`
test.

---

## 2026-08-18 — D-04: the anti-short-cycle timer is back-dated at boot

**Decision.** `begin()` sets the fridge state timer to `now - min_off`, so the
5 min minimum-off has already elapsed at boot.

**Why.** Found by the test suite, not by reasoning. Arming the lockout at boot
meant a warm fridge waited out a compressor-protection interval it had not
earned — the inverter would drop at the end of the 60s boot window and stay off
for four more minutes with the fridge above its ceiling. There is no compressor
to protect at power-on.

---

## 2026-08-18 — D-05: the 10 °C hard override outranks the drive inhibit

**Decision.** `ac_on = force_on OR fridge_hard OR ((fridge OR manual OR surplus)
AND NOT drive_inhibit)`. The hard override is latched for the whole cooling
cycle, not just while the temperature is above 10 °C.

**Why.** CLAUDE.md section 9 states the hard override still applies during
sleep mode and during the drive inhibit, but the arbiter as first written
suppressed *every* request behind the inhibit, including the 10 °C one. Found by
the `the 10 C hard override survives the inhibit` test. Latching matters too:
without it the request would be dropped the instant the temperature fell to
9.9 °C, giving a useless 30-second cycle instead of a full run back down.

**Direction of failure, restated because it is opposite to Phase 4's charging
path:** the inhibit fails toward *permitting* AC. Unknown engine state = no
inhibit, and a stuck-true signal expires after 4h regardless.

---

## 2026-08-18 — D-06: `sd_mmc_card` and ESP-FBot both build under esp-idf

**Decision.** Both `van-core.yaml` and `van-core-soak.yaml` target
`framework: type: esp-idf`.

**Why this was open.** CLAUDE.md section 9 flagged a possible framework
conflict: ESP-FBot's example is esp-idf, and the SD component's documentation
shows arduino. `n-serrette/esphome_sd_card` ships `example.esp-idf.yaml`, so
esp-idf supports both. The requirement is the `advanced:` block —
`include_builtin_idf_components: ["fatfs", "spiffs"]` plus re-enabling the VFS
options ESPHome disables to save memory.

**Still open:** the RAM cost of that. That is what the soak config measures.

---

## 2026-08-18 — D-07: secrets.yaml lives in `nodes/`, not the repo root

**Decision.** `nodes/secrets.yaml`, with `nodes/secrets.yaml.example` beside it.

**Why.** ESPHome resolves `!secret` against the directory containing the config
file only; it does not walk up to the repo root. CLAUDE.md section 10 shows
`secrets.yaml` at the root, which does not work with configs in `nodes/`.
**CLAUDE.md section 10 needs updating to match.**

---

## 2026-08-21 — D-08: the BrightEMS replacement is a static page on ESPHome's own web server

**Decision.** `ui/index.html` — one self-contained file, no build step, no
framework — gzipped into flash by `tools/pack_ui.py` and served by
`components/van_ui/`, a handler registered on the `web_server_base` instance
`web_server` already runs. It drives ESPHome's existing API and nothing else:
`GET /events` (SSE) for state, `POST /<domain>/<object_id>/<action>` for
commands.

**Why not the stock ESPHome UI.** It is a flat alphabetical list of ~50
entities. It cannot say *why* the inverter is on, cannot put the manual timer
next to the fridge temperature, and cannot warn that manual AC is still armed.
Those are the three things a van-core UI exists to say. The stock UI stays
reachable at `/` as the fallback that shows every entity when this page's
assumptions are wrong.

**Why not a second web server / SD-served files.** Same origin, same port, same
TCP stack means no CORS, no second listener, and no dependency on a mounted
card for the UI to exist. Served straight from flash with no heap copy — this
node runs a BLE client, a SoftAP and a display in one cooperative loop
(CLAUDE.md section 2), and a ~30kB allocation per page load is not something to
hand it. 9.5kB gzipped as of this commit.

**Why the page cannot make control decisions.** It is a view. It writes only
the same request flags the physical kitchen button writes, through three
`button` entities added to `van-core.yaml`. The arbiter remains the single
writer of `ac_switch` (CLAUDE.md section 5.3), and the AC switch stays exposed
only so commissioning can override it — the UI says out loud that the arbiter
re-asserts on the next tick.

**Development without hardware.** `tools/mock_core.py` serves the identical
`/events` + REST surface from a laptop, with fault injection (`--fault
ble|probe|flat|night`). The page was built and exercised against it; the ESP
serves the same bytes later.

**`UNVERIFIED` until the soak, both about who owns `/`:**
- `van_ui` defaults to `/ui`. `at_root: true` makes it register before
  `web_server` and claim `/`. Untested on hardware.
- `captive_portal` also wants `/` in AP mode, and this node is AP-only. Settle
  the three-way ordering during the soak, not in a car park.

**`VERIFIED` against ESPHome 2025.7.0 source, not assumed:** `web_server_idf`
provides `beginResponse(code, type, const uint8_t *, size_t)` and
`AsyncWebHandler::canHandle(...) const` / `isRequestHandlerTrivial() const`, so
the handler compiles under esp-idf. `WebServer::canHandle` does not claim
`/ui`.

---

## 2026-08-21 — D-09: parked mode inverts the fail-safe, and the safety lives at the entry gate

**Decision.** A `parked` mode that holds the P310 inverter OFF unconditionally —
overriding `force_on`, BLE loss, a stale probe, the starved-loop watchdog and
the 10 °C hard override alike. It is the only suppressor in the system that
outranks the §5.2 fail-safe.

**Why the inversion is correct here and nowhere else.** "Fail toward powered"
is a statement about the cost of being wrong, and parked at home that cost
flips. Empty fridge: a stuck-OFF inverter costs nothing, a stuck-ON one burns
the `MEASURED` ~48W station overhead through a 3.9 kWh pack in about three days
and then leaves it flat. Food spoiling is the expensive failure when there is
food; a flat LiFePO4 pack is the expensive failure when there is not.

**Where the safety went instead.** Once parked, the arbiter protects nothing and
cannot be argued out of it, so the entire risk is concentrated in whether the
mode can be entered by mistake. Hence the arming interlock: refuse while the
cabinet reads more than 3 °C below cabin ambient (a working, loaded fridge), and
refuse when either probe is missing. "Cannot tell" is a refusal. This is what
promoted the cabin DS18B20 from nice-to-have to load-bearing.

**Rejected: a hard timeout on the mode**, by analogy with the drive inhibit's
4h expiry. The inhibit expires because a stuck engine signal must not strand the
fridge; parked mode has no upstream signal to get stuck, and weeks of standing
still is the requirement, not the failure. An expiry would silently re-energise
the inverter of a van nobody is visiting — the exact outcome the mode prevents.

**Rejected: persisting the mode with the switch's own `restore_mode`.** It fires
the switch action at boot, which runs the arming interlock against probes that
have not reported yet, which refuses — quietly un-parking a van on any brownout.
Persistence is an explicit `restore_value` global read at `on_boot` priority
−100 instead.

**Known gap, stated rather than papered over.** The interlock catches arming
with food inside. It cannot catch arming correctly and then loading the van a
week later. Today that is a blue blinking LED and a checklist line; the
engineered fix is Phase 4, where `van-vehicle` running on switched ignition
makes key-on a signal that clears the mode.

**Reopen if:** the parked standby measurement (SOC slope over 48h with AC off)
shows van-core's own draw dominating in winter. The next lever is duty-cycled
deep sleep, and it costs the web UI and the BLE link between wakes — not worth
designing before that number exists.

---

## 2026-08-24 — D-10: the UI opens from a home-screen icon, and the AP deliberately reports "no internet"

**Problem.** Reaching the UI meant: join the van AP by hand, dismiss the phone's
"this network has no internet" nag, open a browser, type `192.168.4.1/ui`. Four
steps, one of which is typing an IP address in the dark.

That splits into two unrelated problems, and they were solved separately.

### Home-screen app — decided: manifest + iOS meta, no service worker

`ui/index.html` now carries a web app manifest, an `apple-touch-icon` and the
`apple-mobile-web-app-*` meta tags. `tools/pack_ui.py` packs the manifest and a
generated icon (`tools/make_icon.py`, deterministic, same palette as the page)
into flash alongside the page; `van_ui` serves them at `/van-ui/*`.

Cost: **13.7 kB of flash in total, +3.2 kB over the page alone.** On 16 MB that
is not a number worth thinking about, and nothing new runs at runtime — it is
three more rows in a string-compare table on a handler that already existed.

- **iOS gets the real thing.** Add to Home Screen launches full-screen, no URL
  bar, no tab. Apple never required a secure context for this.
- **Android gets a shortcut, not a WebAPK.** Chrome gates installability on
  HTTPS, and this node serves plain http on a SoftAP with no CA and no clock to
  validate a certificate against. A self-signed cert trades the URL bar for a
  security interstitial, which is worse. `ACCEPTED`.
- **No service worker**, for the same secure-context reason, and none is wanted:
  the page is already served from flash on the same origin as the API it drives.

### Captive portal — decided: fail validation, do not spoof internet

`captive_portal:` already runs a catch-all DNS server, so every OS connectivity
probe (`/generate_204`, `/hotspot-detect.html`, `/connecttest.txt`, …) lands on
this node. `van_ui` now claims those paths and answers them with a small landing
page — deliberately *not* the 204 / `Success` body the OS is looking for.

| | Fail validation (**chosen**) | Spoof internet (rejected) |
|---|---|---|
| Phone's default data route | stays on cellular | **switches to the van AP** |
| Rest of the phone while at the van | works normally | **no internet at all** |
| Nagging | one "stay connected?" per phone | none |
| Getting into the UI | tap the "sign in to network" notification | type the IP |

Spoofing wins on nagging and loses on everything that matters: an AP that claims
internet and has none makes the phone useless for anything else while parked at
the van. The tap-through is a better answer than the silent auto-join, and the
home-screen icon removes the typing regardless.

The landing page is a **signpost, not the app**. A captive sheet is a stripped
WKWebView the OS closes when it feels like it; running the real UI inside one is
a way to lose state mid-tap. It shows one big link out to the real browser, the
Add-to-Home-Screen instruction that makes this a one-time ritual, and a line
saying the missing internet is expected.

`captive_landing: false` in `nodes/van-core.yaml` hands those URLs back to
ESPHome's own captive_portal.

**Consequence:** `/ui` now answers unconditionally, even under `at_root: true`,
because `ui/portal.html` hard-codes `http://192.168.4.1/ui` — the captive sheet
sees whatever hostname the probe asked for, so a relative link would bookmark
`connectivitycheck.gstatic.com` to the home screen.

**`VERIFY` on hardware during the soak:**
- Handler order against `captive_portal`. `van_ui` registers at
  `setup_priority::WIFI + 1`, i.e. first, so it should win the probe paths —
  but AsyncWebServer order is registration order, and this has not been seen on
  the device yet.
- That Android actually offers the notification rather than silently dropping
  to cellular, and that iOS's sheet renders the landing page.
- Whether the standalone iOS app keeps the SSE connection alive across a
  backgrounding, or reconnects cleanly. If it does not, the page needs a
  `visibilitychange` reconnect.

---

## 2026-08-25 — D-11: the grey tank is a second channel, and its sender is trusted only as far as the cross-check allows

**Decision.** `van-water` reads **two** senders — fresh and grey — on the one
ADS1115. Both levels are computed and published locally; neither can inhibit
anything. What the grey channel *does* drive is a warning and a running
plausibility check against the fresh channel.

**Why the second tank was not deferred.** Every expensive part of tank sensing
is a fixed cost already paid by the first tank: the node, the ADC, the buck,
the enclosure, the gland, and the poured-litres calibration ritual. The grey
tank adds one divider resistor and one cable run. Deferring it saves nothing
and guarantees the enclosure is opened twice. Both senders are already owned.

**Why the reading is treated as suspect.** A float in grey water fouls — soap,
grease, food solids, hair on the stem — and the characteristic failure is a
stuck reading, not a missing one. `FreshValue`-style staleness detection (D-02)
does not catch it: the ADC keeps returning a perfectly fresh, perfectly wrong
number. So freshness is not sufficient here and a second, independent argument
is needed.

`AMENDED 2026-08-25` — the senders turn out to be **sealed reed ladders**, not
wiper types (CLAUDE.md §2), which changes the mechanism without weakening the
argument. Nothing conductive touches the water, so there is no track to erode;
what sticks is the float binding on its stem. The failure still presents as a
fresh, plausible, wrong number, so the cross-check is needed exactly as
written. Two smaller consequences do follow: the recovery is a flush and a wipe
rather than a replacement part, and the output is **quantised** — one step per
reed — so a reading between plateaus is itself evidence of a fault, giving the
plausibility check a second and much faster input than the mass balance.

**The cross-check is that argument.** Between dumps, grey should rise by
roughly what fresh falls. Divergence separates the three failures that all look
identical on a single bar:

| Symptom | Reading |
|---|---|
| Fresh falls, grey flat | Fresh leak, or grey float stuck |
| Grey rises, fresh flat | Inflow, or fresh float stuck |
| Fresh rises, grey falls | Plugs swapped, or one sender is a 240–33Ω part read with a 0–190Ω curve |

The third row is why no connector keying is specified for the senders: both are
2-wire, so BOM's key-by-pin-count rule cannot express the difference, and the
cross-check catches a swap on the first use of the sink. This is the same
preference as D-09 — an engineered detection beats a procedural instruction —
but applied one level down: the error is not prevented, it is made loud.

**What it explicitly does not claim.** Two ±5%-class senders averaged over 60s
resolve gross divergence over hours. That finds a stuck float and a swapped
plug. It does not find a slow drip, and the display must not call it leak
detection.

**Fail-safe direction — opposite to the RV convention.** The usual rule is to
cut the fresh pump when grey reads full. Here a grey sender that is high,
stale, or missing **warns and never opens the pump circuit**: overflowing grey
is a nuisance, no water in a van at an unknown hour is not, and a fouled grey
sender is the expected failure rather than a hypothetical one. §5.2's "fail
toward powered" happens to give the right answer for this path too, but for its
own reason, which is why it is written down rather than inherited.

**Reopen if:** the cross-check log shows the float sticking often enough to be
noise rather than signal. The fix is an external capacitive strip on the grey
tank — a different voltage source into the same ADC channel, so no design
change, only a recalibration. Buy it then, not now (BOM D5).

---

## 2026-08-25 — D-12: "fail toward powered" is only real while the link is alive

**The observation that forced this.** A fail-safe that defaults AC on when the
connection is lost cannot act: with the link down there is nothing to send the
command over. The rule in §5.2 was written as though `van-core` holds the
inverter up, when in fact the state is latched in the P310 and `van-core` only
*commands* it.

**The general form, which is worth more than the fix.** A fail-safe direction
is real only if reaching it requires **no successful communication**. Failing
to the state the system is already in is free; failing to the opposite state is
a wish. That single test explains why the two directions in this project are
not symmetric:

| Path | Direction | Real? |
|---|---|---|
| Fridge, AC currently ON | fail to ON | **Yes** — the station latches; inaction is the fail-safe |
| Fridge, AC currently OFF | fail to ON | **No** — needs a working link, which is exactly what failed |
| Parked mode | fail to OFF | **Yes** — already off, and re-asserting needs no reply |
| Pump interlock (§9 Phase 2b) | fail to permitting | **Yes** — local relay, NC contacts |

Parked mode's inverted fail-safe (D-09) was never in doubt for this reason,
though the reason was not written down at the time.

**Decision.** Keep the direction, correct the claim, and add the one mechanism
that can still act.

- `BLE_LOST` is renamed in intent, not in name: it is **recovery-on-reconnect**,
  holding the request true so AC returns the moment the link does. The test
  asserting it is renamed to say so — it was called "BLE loss forces AC on"
  while asserting the *request*, which is precisely the conflation that let the
  overstatement survive review.
- **`LINK_STALE` is the actual fail-safe.** Station-sourced sensors going quiet
  while `ble_connected` still reads true means a wedged-but-open link, and that
  is the last moment an ON command can still get through. Previously invisible:
  the local DS18B20 kept the thermostat running, and it went on commanding a
  switch nobody was listening to.
- **The reconnect edge re-writes the switch.** A write attempted with the link
  down still updated `last_written_`, so on reconnect the arbiter believed AC
  was already as requested and the re-assert would not go out for up to 60s.
  A minute of fridge-off immediately after recovery, in the exact scenario the
  fail-safe exists for.

**Why `link_stale` trails `sensor_max_age` rather than leading it.** 60s against
45s. Leading it would trip on ordinary late data and force the inverter on
permanently — the same failure mode D-02 records for `sensor_max_age` itself.

**What is left unmitigated, and must stay written down.** A permanent BLE
failure or an unpowered node, arriving during an OFF block, leaves the fridge
off until a human intervenes. Alerting and the P310's physical AC button are
the only remaining tools, and the button matters more than it looks: a wedged
`van-core` holds the station's single BLE connection, so the phone app cannot
take over either. Added to the §11 pre-trip check, along with the note that the
fail-safe test must start from AC **off** — starting from on proves nothing,
since the station would hold the inverter up with the node unplugged entirely.

**Reopen if:** the fridge moves to 12V DC (ANALYSIS §4.3/§5). There is no
inverter to cycle and no remote command in the safety path, so this whole
failure class stops existing rather than being managed — which is a point in
that option's favour that the energy comparison alone does not capture.

---

## 2026-08-25 — D-13: the fridge block scheduler, and why the release is OR not AND

**The defect.** `fridge_req` released only when `cold && quiet && settled`,
where `quiet` meant `output_power` under 15W for 90s. The fridge is an
ESSENTIELB ERT85-55mib6 with a **variable-speed inverter compressor**: it
modulates against accumulated heat for hours and at high ambient does not stop
at all (measurements.md M6, duty cycle `CONFIRMED` 100%). So `quiet` could never
become true, `fridge_req` latched on permanently, and the inverter would have
run 24/7 — **the project's entire saving, silently zero.** Specified in
PATCHES P2 as "the big one" and unapplied in code until now.

**Why the test suite did not catch it.** The rig's baseline is
`output_power_w = 0.0f`, i.e. a fridge drawing nothing, which makes `quiet`
permanently true. Every fridge test passed against a fixed-speed appliance that
stops — the one the project does not own. Two of the new tests now set a
realistic continuous draw, and one is named for the failure directly.

**Decision.** `fridge_req` is a run/rest block scheduler with temperature as an
override ceiling. The supervisor picks the cycles; the appliance no longer does.

- A block **starts** on either the ceiling (safety net) or the schedule (normal
  path). The schedule additionally requires the cabinet to be above the floor —
  without that, every rest period would burn `min_on_ms` of inverter cooling a
  cabinet already at target.
- A block **ends** on **any** of: cold, the block timer, or the compressor
  genuinely stopping. The last of these is ANALYSIS's "Strategy A" kept as an
  opportunistic win rather than a requirement.
- `min_off_ms` moves 5 → 20 min, sized to the block. An inverter compressor
  dislikes restarts and the equalisation penalty scales with cycle *count*.
  The 10 °C hard override is what makes a 20 min lockout safe: it beats the
  anti-short-cycle timer outright, and has a test saying so.

**Strategy A vs B was a false choice.** ANALYSIS §4.2 framed them as
alternatives to be decided by the overnight log. They compose: with an `OR`
release, a night where the compressor does stop is harvested for free, and a
day where it never stops is still cycled. The overnight measurement now tunes
rest-block length instead of selecting an architecture — which also means the
implementation was never actually blocked on it, only the numbers were.

**What stays unmeasured, and it is the number that matters.** The **pulldown
penalty of imposed cycling**, estimated 15–30%, never measured. Both block
lengths ship as `UNVERIFIED` 30 min defaults and are exposed as `number`
entities. Break-even against the full ~48W station overhead is an 89% penalty
so the margin is large; break-even against *inverter idle alone* at 50% duty
and a 20% penalty is ~11W, and if `idle-test` returns below that the answer is
a 12V compressor fridge instead.

**Reopen if:** `idle-test` puts inverter idle under ~11W (see P6, the reopened
12V fridge decision), or the A/B test puts the pulldown penalty far above 30%.
