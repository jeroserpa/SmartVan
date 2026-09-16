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

### Stable signing key (one-time)

Without it every build is signed with a new throwaway key and must be
uninstalled before updating. Generate once — keytool ships with any JDK, so
this can be done in a Codespace or any machine with Java:

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

```bash
base64 -w0 debug.keystore | gh secret set DEBUG_KEYSTORE_B64
```

## `VERIFY` on the phone (D-15)
- UI loads with mobile data **on**, and other apps still reach the internet.
- SSE (`/events`) survives backgrounding, or reconnects.
- Out of range: message + Retry within ~10 s, no hang.
- At home on the house Wi-Fi the app binds to that network and fails to load
  — expected; SSID matching is not implemented.
