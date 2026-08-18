# Measurements log

Append-only, dated. This is the file that turns estimates in CLAUDE.md into facts.

## 2026-08-18 — BLE protocol validation (Phase 0)
- Station found via `tools/fbot_probe.py scan`: POWER-0183, E8:06:90:C7:AD:F6
- `services` dump confirms exact GATT match with ESP-FBot expectations:
  service 0000a002, write char c304, notify char c305 all present.
- MTU negotiated to 517 — status frames (~166 bytes) arrive unfragmented,
  single notification per poll.
- `monitor --raw` output validated against BrightEMS readings: plausible.
- Fridge running power (compressor on): 35W AC, user-stated.
- Inverter idle draw: 35W, manufacturer figure, still UNVERIFIED in situ.
- Fridge auto-restarts after power interruption on medium-cold setting: CONFIRMED.
- Water heater: 230V, 1-2kW, CONFIRMED.
- Water level sender run: <1m, CONFIRMED.
- 12V habitation distribution confirmed fed from P310 DC output (the `dc` switch).

## 2026-08-18 — P310 AC output earthing: `CONFIRMED FLOATING`

User-confirmed: the P310's 230V output is **floating** (no neutral–earth bond).
This is an IT system, and it changes the safety case for every 230V load,
not just the heater.

Consequences, in order:
- **An RCD fitted to this supply will not trip on an earth fault.** It measures
  L–N imbalance; with no earth reference at the source there is no return path,
  so a line-to-chassis fault draws only capacitive leakage (µA–mA). Protection
  that would be assumed present is absent.
- **The first fault is silent, not safe-by-design.** IT systems tolerate a first
  fault *because* they are paired with an insulation monitoring device that
  alarms on it. Without one, faults accumulate invisibly.
- **The second fault is the dangerous one** and, in a van, the chassis is
  everywhere — sink, tap, frame, another appliance's exposed metal.
- **A 1–2kW immersion element is the worst possible load to put on this**, because
  element-to-sheath insulation breakdown is its normal end-of-life failure mode.
  That failure *is* the first fault.

See BOM D1c for the decision this forces.

## TODO next
- [ ] Duty cycle: 24h `fbot_probe.py log` with inverter permanently on
- [ ] D3: does P310 12V output stay on at ~100mA
- [ ] Condenser airflow inspection
- [ ] Manual switch temperature after 10min charging
- [ ] Confirm 1200W/800W alternator charging figure with clamp meter
- [ ] Coast rate test: fridge to 1°C, compressor locked out, log dT/dt
- [x] AC output N-E bonding — CONFIRMED FLOATING, see above
- [ ] **D1c BLOCKER:** does the P310 tolerate an N-E bond at its output?
