# van-core Android wrapper (D-15)

A WebView onto `http://192.168.4.1/ui`, with this app's traffic bound to the
van Wi-Fi so the rest of the phone keeps using mobile data. No logic here —
the web UI stays the source of truth.

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
