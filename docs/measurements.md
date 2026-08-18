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

## TODO next
- [ ] Duty cycle: 24h `fbot_probe.py log` with inverter permanently on
- [ ] D3: does P310 12V output stay on at ~100mA
- [ ] Condenser airflow inspection
- [ ] Manual switch temperature after 10min charging
- [ ] Confirm 1200W/800W alternator charging figure with clamp meter
- [ ] Coast rate test: fridge to 1°C, compressor locked out, log dT/dt
- [ ] AC output N-E bonding (floating vs bonded) — RCD implications
