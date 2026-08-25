# Patches to apply to CLAUDE.md — 2026-08-19

Not applied automatically: I don't have the repo. These are the edits the
2026-08-18 log and the fridge identification require. Ordered by severity.

**Status 2026-08-25.** P1, P2 and P3 are **APPLIED** — P2 in code as well as
prose, with the block scheduler in `components/ac_arbiter/` and its regression
test in `test/`. P4–P8 remain outstanding: P4 and P5 partly overtaken by the
§8 rewrites for the water work, P6 (reopening the 12V fridge) and P7/P8 (BOM)
still to do.

Rationale for each lives in `docs/measurements.md` (M1–M8) — the edits below
should reference it rather than restate it.

---

## P1 — §1 Problem statement: replace the figures table — `APPLIED 2026-08-25`

The fridge is an **ESSENTIELB ERT85-55mib6 with an inverter compressor**.
Everything in §1 that assumes a cycling fixed-speed compressor is wrong.

Replace the "Measured / stated figures" table with:

| Quantity | Value | Confidence |
|---|---|---|
| Compressor type | **Variable-speed inverter** | `CONFIRMED` from model spec |
| Station overhead, AC on | **~48W** | `MEASURED` 2026-08-18, M2 |
| — of which inverter idle | **UNKNOWN** | `ac_on` was never False; see `idle-test` |
| Fridge draw, continuous | **24W @ 27 °C, 32–34W earlier** | `MEASURED`, M6 |
| Fridge duty cycle | **100% — it does not cycle** | `CONFIRMED`, M6 |
| Rated consumption | 114 kWh/yr = 13W avg | manufacturer, EU test cycle |
| Power-failure autonomy | 9 h | manufacturer |
| Usable capacity | **~3900 Wh** | `MEASURED` via energy balance, M2 |

Delete the "Battery-side power states" table and the `saving = 35W × (fraction
of time the inverter is OFF)` line. Both assume compressor cycling.

Replace "The idle equals the useful load" paragraph with: **the station
overhead (~48W) is nearly twice the load it supports (~27W battery-side).**
That is a stronger case for the project than the original framing, not weaker.

Replace the saving table with the M8 estimates, and mark the 15% pulldown
penalty `UNVERIFIED` in bold — the entire saving rests on it.

## P2 — §6 arbiter: `fridge_req` becomes a scheduler — **the big one** — `APPLIED 2026-08-25, code included`

`fridge_req` was specified to follow the compressor. There is nothing to
follow. Rewrite as a block scheduler with a temperature guard:

- **Delete** the `output_power < 15W` release condition. Power never drops.
- **Delete** the entire "Why 90s and not 5 min" paragraph. Compressor-stop
  detection is meaningless on an inverter compressor.
- `fridge_req` = a run/rest block schedule. Blocks of **20–30 min minimum**,
  not 5 — inverter compressors dislike frequent restarts.
- Temperature becomes an **override ceiling only**, not the primary input:
  interior > ceiling → force a run block regardless of schedule.
- Keep: the 10 °C hard override, anti-short-cycle minimum OFF, tunable
  `number` entities.
- The DS18B20 is now **more** important, not less: with no compressor signal to
  read, temperature is the only feedback the arbiter has.

## P3 — §6 Sleep mode: simplify — `APPLIED 2026-08-25`

Sleep mode gets easier, not harder. It is no longer an attempt to suppress
cycles the fridge chooses — the supervisor chooses them. A pre-cool block
before bed and nothing until morning is now a scheduling decision.

Delete the "at most one compressor cycle" framing and the thermal budget table
premised on cycling. Replace with a coast-time budget, `UNVERIFIED` pending the
dT/dt measurement.

## P4 — §8 open questions

- §8.1 — already answered, unchanged.
- §8.2 — **still open**, but reframed: 48W total overhead is measured; the
  inverter's share is not. `idle-test` closes it.
- §8.3 — obsolete, the 35W figure was never a running power for this unit.
- §8.4 duty cycle — **answered: 100%, structurally.** Replace with the new
  critical unknown: **the pulldown penalty of imposed cycling.**
- §8.5 coast rate — promoted from nice-to-have to **blocker**. It sets the
  block length.
- §8.6 condenser — **rewrite.** Heat is rejected rear and top on this model,
  not the sides. And it can no longer be diagnosed from duty cycle, since duty
  cycle is structurally 100%. Compare W at matched ambient instead.

## P5 — §9 Phase 0 / Phase 1

- The "log `output_power` for 24h with the inverter permanently on" instruction
  now measures nothing useful — it is already permanently on and the fridge
  never stops. Replace with the **pulldown-penalty A/B test** on the Meross
  plug.
- Note that the plug-in energy meter is now the primary instrument for the
  decisive measurement, so item 32 in BOM.md moves from "nice cross-check" to
  required.

## P6 — reopened decision, `decisions.md`

**12V compressor fridge was rejected on the assumption that the 230V unit
worked and the only cost was inverter idle.** That assumption held; what
changed is that the fridge cannot be duty-cycled by the arbiter without a
pulldown penalty of unknown size. Record the option as **reopened pending the
pulldown measurement**, not as a live recommendation — a 24W inverter fridge is
genuinely efficient, and if the penalty turns out small the 230V unit stays.

---

# Patches to BOM.md

## P7 — item 32, plug-in energy meter

Promote from "independent cross-check of P310 readings" to **required
instrument for the pulldown-penalty measurement (M8)**. User already has a
Meross MSS315, so cost is zero — note that it is used stand-alone here, with no
Matter controller, purely as a display.

## P8 — item 2, DS18B20 probes

Strengthen the justification: with an inverter compressor there is no
electrical signature of cooling state, so interior temperature is the arbiter's
**only** feedback channel. The optional third probe for door detection stays
optional; the two primary probes are now load-bearing.
