# End-to-end tests on an Android device

These suites use a real phone connected over ADB, the embedded Node/Playwright engine, Android WebViews and a local synthetic
site. They exercise REST, WebSocket and CDP in the same scenario, with assertions on observable effects.

Find your phone's serial with `adb devices`. Every suite reads it from `ANDROID_SERIAL`.

> [!IMPORTANT]
> The suites only close what they created. A phone you also use day to day keeps its tabs, but the main scenario force-stops
> PocketWright during its persistence check.

## Running

Build and install the APK from the repository root:

```bash
./gradlew :app:assembleRelease -Ppocketwright.abis=arm64-v8a
```

```bash
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/release/app-release.apk
```

Then, in `tests/e2e`:

```bash
npm ci --ignore-scripts
```

```bash
ANDROID_SERIAL=<serial> npm test
```

The phone must be unlocked for the interface checks. With the optimized build, copy the API key (Paramètres → API locale) to
`PW_KEY` or to `out/key.txt`, because `run-as` only works on debug builds. Node 22 or later is required for the built-in WebSocket
client.

Optional settings: `ADB`, `API_PORT` (18766), `SITE_PORT` (18090), `OUT` (the `out` directory), `SUITE=baseline` (the first ten phases
only). No public service or real account is used.

## Messaging

```bash
ANDROID_SERIAL=<serial> node messages.mjs
```

Prerequisites: PocketWright is the default messaging app and « Donner à l'API l'accès aux messages » is on. The script refuses to run
otherwise, because its fake SMS would land in the phone's messaging app.

It places these SMS into the real telephony stack through `service call isms`, with no SIM or real send, and follows them to the API:
security, SSE and WebSocket streams, accents, alphabetic senders, multi-part SMS, bursts, resuming after a disconnection, restart,
killed process, CRUD over the socket, screens and deletion. It only reads its two fictitious senders and deletes them at the end. Sends
are only tested without a SIM, so that nothing is charged.

Options: `--no-crash` skips the deliberate crash, and `--keep` keeps the messages. `PW_ISMS_INJECT` gives the number of
`ISms.injectSmsPduForSubscriber` if it differs from 7 (Android 13).

## Fingerprints

```bash
ANDROID_SERIAL=<serial> node fingerprint/run.mjs
```

Opens several sessions on a small local site and verifies that each fingerprint differs from the others, is coherent (page, iframe,
worker, service worker, HTTP headers, Client Hints), is stable (re-read, reload, restart), can change on the fly without touching other
sessions, is reproducible from a seed, and does not leak an address through WebRTC. `fingerprint/collector.js` measures what a page can
read.

## Comparing WebView engines

`engines/run.mjs` measures speed (JavaScript, WebAssembly, DOM, canvas, WebGL), startup, page loading and memory. Without an engine
argument, it measures the installed app without restarting it. With engines (`name=file.apk`), it loads them in turn in an isolated
copy of the app, built with `-Ppocketwright.probe=true`: another identifier, port 8766, no SMS rights, and the installed app's data stays
intact.

## Scenario phases

| Phase | Scenario and expected result |
| --- | --- |
| 1 | Engine ready. Requests without a key, with a wrong key or with a foreign Host are rejected. |
| 2 | REST creation, wrong password, login with an HttpOnly cookie, role and text selectors, JavaScript with arguments. |
| 3 | WebSocket connection, automatic session creation, user login, pipelined commands and load events. |
| 4 | Alice stays logged in while Bob logs in. A new profile stays a guest. |
| 5 | Playwright script with deferred rendering, three task creations, deletion dialog, logs and state between scripts. |
| 6 | Dedicated CDP, network interception without a server request, console, iframe, upload, viewport/full-page/element captures. |
| 7 | Four concurrent slow navigations, explicit native engine, REST capture. |
| 7 bis | WebGL 2 on the real GPU. Canvas present in `GET /screenshot` and `page.screenshot()`, HTML above it preserved, style restored after the capture. |
| 8 | Tab grid on the Android screen, opening a tab and CDP capture of the displayed tab. |
| 9 | Force-stop then relaunch: same session identifiers, cookies and localStorage kept, former WebSocket cut. |
| 10 | WebSocket reconnection, WS close, CDP close, REST deletion, and no sessions left. |
| 11 | HTTP contracts: authentication variants, hostile hosts, malformed/scalar/array JSON, unknown sessions, invalid action or parameters, recovery. |
| 12 | Unicode forms, Arabic, Chinese, emoji, multi-line, clear/type/keyboard, checkboxes, selection, XPath, attributes, visibility, accessibility. |
| 13 | Deferred appearance and removal, bounded timeout, script exception, recovery, JavaScript and CSS injection. |
| 14 | Back and forward history, cookies and localStorage export, sessionStorage after reload, isolation of the three storages, targeted cookie deletion, Unicode rename. |
| 15 | CDP connection through authenticated HTTP discovery, offline mode then recovery, refused confirm, filled prompt, JavaScript errors, preserved after disconnection. |
| 16 | Wrong WS/CDP key, malformed JSON, missing action, correlation of 20 simultaneous answers, preserved after disconnection. |
| 17 | Six parallel sessions with distinct state, 60 simultaneous reads, three create/use/delete cycles, engine health. |
| 18 | Native engine: Unicode input, reading, queryAll, base64 PNG, JPEG capture and binary signature. |
| 18 bis | Full Playwright: `newPage`, pop-ups, `storageState` of a closed origin, isolated `newContext`, `addCookies`, `grantPermissions`, `setGeolocation`. |
| 18 ter | Dialogs with no external client, according to the mode set through REST. |
| 19 | No leftover test session, API and engine still ready. |

## Results

Written to `out/`, which Git ignores:

- `results.json`: device, browser and engine versions, result of each assertion.
- `progress.json`: incremental results, useful even if the process stops.
- `report.md`: readable report with PASS/FAIL and details.
- `*.png`: captures of pages and of the Android interface.

Exit code: 0 if all checks pass, 1 on failure, 2 if the global limit of fifteen minutes is reached.

An exception ends its phase and counts as a failure, and later phases are still attempted. Assertions following an exception do not
run, so always report the number of checks performed alongside the result. A hard stop can leave sessions or ADB redirections behind:
inspect them before running again, and delete only the test resources.

The Host test uses `node:http` to send exactly the desired header, because `fetch` can normalize it and give a false result.
`device-ui.sh` provides `shot` and `tap_ui` to capture and touch the phone's screen by hand.

## Limits

This scenario is a broad functional regression, not proof of the absence of bugs. It should be completed on a range of Android and
WebView versions and on an x86_64 emulator. Still to qualify separately: phone restart and start at boot, Doze and battery saving, autonomy
and memory leaks over several hours, permission revocation, rotation and screen sizes, TalkBack, APK update with existing data, and
resource exhaustion. CDP offline mode does not simulate a physical mobile network cut.
