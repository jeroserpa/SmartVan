# van-core Android wrapper (D-15)

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

Battery %, output/input power, AC state + `AC reason`, fridge temperature,
and when it was read. Long-press the home screen → Widgets → van-core.

- **Data:** the initial state burst of `/events`, read over the Wi-Fi
  network explicitly (`Network.openConnection`), then the connection is
  closed. No firmware change.
- **Refresh:** every 15 min (WorkManager; Android's floor, and deferred
  further in Doze), on ↻, and whenever the app is left.
- **Out of range:** keeps the last values, greyed after 20 min, with
  `last seen …`. It only ever has data while the phone is on van-core's Wi-Fi —
  **it is not an alarm** and says nothing about the van while you are away.
- **Overrides in the AC line:** `PARKED · fridge off` (blue), then
  `P310 link down` (amber).
- **No controls,** on purpose: a Manual AC button on the home screen is the
  phantom-press problem of CLAUDE.md §6.
- **The one duplication of entity ids outside the page.** `VanFeed.kt` names
  eight ids that mirror the `E` map in `ui/index.html`; rename an entity in
  YAML and both need updating. On the soak firmware, fridge temperature,
  parked and AC reason do not exist and simply stay blank.

## Build (cloud)

Any push touching `app/` runs `.github/workflows/android-app.yml`. Download
`van-core-apk` from the run's artifacts, unzip, sideload.

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
