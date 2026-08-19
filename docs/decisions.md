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
