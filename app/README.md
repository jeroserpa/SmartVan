# van-core Android wrapper (D-15)

**Look:** dark only, the `ui/index.html` palette (`res/values/colors.xml`,
converted from the page's OKLCH values — keep them in step). Adaptive and
themed launcher icon from the bolt in `tools/make_icon.py`. The app never shows
a raw WebView error: it is either the page, the page with a "link lost"
banner, or a status panel (searching / connecting / not on van-core Wi-Fi /
not answering) with Retry, a Wi-Fi settings shortcut and a Details view of
the bound network. Errors retry by themselves every 5 s, and again on return
to the app. Pull down on the page to reload it.

A WebView onto `http://192.168.4.1/ui`, with this app's traffic bound to the
van Wi-Fi so the rest of the phone keeps using mobile data. No logic here —
the web UI stays the source of truth.

## Saving files: `window.VanApp.saveFile(name, text)`

A WebView has no download path of its own, and Android's DownloadManager is
not an option: it runs outside this app's network binding, so with mobile
data on it would send 192.168.4.1 to cellular. Instead the page fetches the
file itself (over the bound Wi-Fi) and hands the text to the app, which only
writes it. The soak log page (`/ui`, `/soak`) already does this when the
bridge exists.

- **Android 10+:** public `Download/` via MediaStore, no storage permission.
  On a name clash MediaStore adds ` (1)`; the reply names the file it kept.
- **Android 8–9:** the public folder needs a runtime permission a synchronous
  call cannot ask for, so the file goes to the app's own
  `Android/data/van.supervisor.app/files/Download/` (readable from a file
  manager or over USB on those versions). It is deleted if the app is
  uninstalled. `minSdk` stays 26.
- Written as UTF-8, MIME `text/csv`. The name is reduced to a basename of
  `[A-Za-z0-9._-]`.
- Returns a line for the page to show: `Saved soak-20260917-0830.csv to
  Downloads (812 kB)`, or `Save failed: …`.
- Only answers while the WebView shows `http://192.168.4.1`; any other page
  gets `Refused: …`.
- The call is synchronous: the page waits while a few MB are written.

## Home-screen widget (read-only)

Battery %, output/input power, AC state + `AC reason`, **both** probe
temperatures, and when it was read. Long-press the home screen → Widgets →
van-core.

- **Two sizes:** full (3×2 and up: charge, power in/out, fridge and cabin,
  reason) and compact (2×1: charge and AC state). Android 12+ switches
  between them on resize by itself; older launchers re-render on resize.
- **The charge is the background.** `BatteryFill.kt` draws the card as a
  vessel filling with liquid to the state of charge, and the worker pushes
  ~12 frames (~0.8 s) so it runs up to a new reading rather than jumping.
  A widget host never runs our code and RemoteViews has no animator, so a
  pushed burst is the only animation available; it runs on a refresh that
  actually moved the charge, and nowhere else, so an idle home screen costs
  nothing. This replaced the old four-`ProgressBar` bar, which showed the
  same number twice and cost the row that the second temperature now uses.
- **Liquid colour** follows the load-shedding bands of CLAUDE.md §9 Phase 5:
  green, amber below 30 %, red below 15 %, grey when stale.
- **Both temperatures are labelled** (`4.6 °C fridge`, `24.0 °C cabin`), in
  the same small-muted style as `in`/`out`. Two bare numbers side by side
  would be a guessing game, and reading the cabin as the cabinet is the one
  misreading that matters.
- **Picker preview:** `res/layout/van_widget_preview.xml` is generated from
  `van_widget.xml` — run `python tools/make_widget_preview.py` after editing
  the widget layout. The picker cannot run our code, so the liquid is stood
  in for by `res/drawable/battery_fill_preview.xml`, frozen at 78 %.

- **Data:** the initial state burst of `/events`, read over the Wi-Fi
  network explicitly (`Network.openConnection`), then the connection is
  closed. No firmware change.
- **The burst is read through pauses, not up to the first one.** van-core
  pushes one entity per loop and that loop also runs BLE, the display and the
  SD writer (CLAUDE.md §2), so a second or two of quiet mid-burst is normal.
  Stopping there truncated the snapshot after the station sensors — which are
  declared first in `van-core.yaml` — and left both temperatures, parked and
  the AC reason permanently blank. It now ends on three consecutive quiet
  windows (2 s each) or a 14 s deadline, and a short burst *merges* over the
  stored one instead of replacing it.
- **Refresh:** every 15 min (WorkManager; Android's floor, and deferred
  further in Doze), on ↻, and whenever the app is left.
- **Out of range:** keeps the last values, greyed after 20 min, with
  `last seen …`. It only ever has data while the phone is on van-core's Wi-Fi —
  **it is not an alarm** and says nothing about the van while you are away.
- **Overrides in the AC line:** `PARKED · fridge off` (blue), then
  `P310 link down` (amber).
- **No controls,** on purpose: a Manual AC button on the home screen is the
  phantom-press problem of CLAUDE.md §6.
- **Each probe is a list of ids, not one id**, because the two firmwares that
  carry them name them differently: `nodes/van-core.yaml` publishes
  `Fridge temperature` / `Cabin temperature`, `nodes/van-core-probes.yaml`
  (soak 2) publishes `Fridge probe` / `Cabin probe`. The widget takes
  whichever the node actually has. This is the reason the temperatures were
  blank on the bench node while its own web page showed them.
- **The one duplication of entity ids outside the page.** `VanFeed.kt` names
  the ids that mirror the `E` map in `ui/index.html`; rename an entity in
  YAML and both need updating. On `van-core-soak.yaml` and
  `van-core-probes.yaml` there is no arbiter, so parked and AC reason do not
  exist and stay blank.
- **On a node with no DS18B20 at all, the row shows the board temperature
  instead**, labelled `board` and with the thermometer icon, rather than two
  dashes. That is `nodes/van-core-soak.yaml` (soak 1) and nothing else: it
  has no `one_wire:` bus — its only
  temperature is `internal_temperature` from `common/base.yaml`, the ESP32-S3
  die. That is also the only temperature on its web page, so the widget and
  the page now agree. The id is matched by **suffix**
  (`VanFeed.BOARD_SUFFIX`), because `common/base.yaml` names it
  `"${friendly_name} board temperature"` — `sensor-van_core_board_temperature`
  on van-core, `sensor-van_core_soak_board_temperature` on the soak build.
  It is never shown in the cabinet's place: the moment either probe entity
  exists, the fridge and cabin cells come back, NAN or not.
- **`-- °C` is not nothing.** The fridge sensor in `nodes/van-core.yaml`
  publishes NAN rather than a last-known value once its probe times out, and
  five minutes of that forces the inverter ON (CLAUDE.md §6 `force_on`). A
  blank fridge reading next to a live cabin reading is a probe fault, not a
  widget fault — check the 1-Wire addresses, which are still `PLACEHOLDER`
  in the YAML.

## Build (cloud)

Any push touching `app/` runs `.github/workflows/android-app.yml`.

**On the phone:** https://github.com/jeroserpa/SmartVan/releases/download/app-latest/van-core.apk
— a rolling pre-release that every build of `main` (and, until it is merged,
the app branch) replaces. Public, like the repo; the APK holds no secrets.

Or from the run's artifacts (zip, needs a GitHub login):

```bash
gh run download --name van-core-apk --dir apk
```

### Signing key

Builds are signed with the key in the `DEBUG_KEYSTORE_B64` repo secret
(generated 2026-09-16 with OpenSSL as PKCS12, alias `androiddebugkey`,
passwords `android`; local copy at `~/.android/debug.keystore`). The CI log's
"Show signing certificate" step prints its SHA-256. If the secret is ever
lost, generate a new one and uninstall the app once before updating.

## `VERIFY` on the phone (D-15)
- UI loads with mobile data **on**, and other apps still reach the internet.
- SSE (`/events`) survives backgrounding, or reconnects.
- Out of range: message + Retry within ~10 s, no hang.
- At home on the house Wi-Fi the app binds to that network and fails to load
  — expected; SSID matching is not implemented.
- Soak page **Download all** with mobile data on: the file shows up in
  Downloads, and the page reports the name and a size that matches the file.
- Downloading twice: the second copy gets ` (1)`, and the message names it.
- Widget: add it with the phone on van-core Wi-Fi — values within a few
  seconds; ↻ shows `updating…` then a time; out of range shows
  `not in range · last …` and grey values after 20 min.
