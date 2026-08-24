# Web panel redesign — mockups

Design-only. **No firmware here, nothing deployable.** These are five phone
artboards for a replacement `van-core` web UI, drawn against the entities that
already exist in [`nodes/van-core.yaml`](../nodes/van-core.yaml).

| Artboard | What it is |
|---|---|
| `Main` | *Now* — inverter state and reason, power-flow dashboard, 24h balance strip, fridge |
| `Energy` | 24h power decomposition, inverter/compressor duty ribbons, SOC, idle avoided |
| `Fridge` | Temperature, the arbiter as a truth table, coast prediction, cycle history |
| `Setup` | The 14 `number` tunables and the P310 output switches (scrolls) |
| `States` | The hero card in all six arbiter states, including both fail-safe paths |

## Build

```
node gen-chart.mjs > chart.json   # simulate 24h, emit SVG geometry + stats
node build.mjs                    # substitute @@TOKENS@@ into *.dc.html
```

`*.dc.html`, `chart.json` and the seeded canvas are generated and gitignored.

## The data is simulated

`gen-chart.mjs` models one plausible day — a solar bell with two cloud notches,
one 95-minute drive on the alternator, fridge cycles that shorten as the cabin
warms, three cooking events. Everything downstream is derived from it, so the
mockups are internally consistent: the net line is in minus out, SOC integrates
net, and the duty percentages match the ribbon widths to within the minimum
block-width clamp. It asserts and fails if a series would clip outside the axis
it labels.

It is **not measured**. Real numbers need the microSD logger from CLAUDE.md §9
Phase 1, which is not written; ESPHome `web_server` v3 charts are live-only.

## Not yet decided: how this gets built

ESPHome `web_server` v3 renders a flat entity table and has no theming hook that
reaches this far. Two routes, neither costed:

1. **`css_url` / `js_url` bundle** over v3's DOM. Keeps OTA, the entity plumbing
   and `local: true`; fights the framework for layout.
2. **Serve a custom page** from the node and drive it off the v3 REST/SSE
   endpoints. Full control; more flash, more RAM, and it competes with the BLE
   task the §2 risk note is about.

Settle this before turning any of it into YAML.
