# Fingerprints

Each API session receives its own device fingerprint, drawn at random and kept across restarts. Tabs opened by hand keep the
real device, with their own canvas noise.

> [!IMPORTANT]
> This separates sessions from each other. It does not make you invisible. Do not use it to get around a service's rules.

## Properties

**Realistic.** A page can measure the GPU it runs on, so the catalog only contains real phones with the same GPU as the
device. On the reference phone (PowerVR GE8320), these are the Galaxy A03s, A04, A04e and A12, Redmi 9A, 9C and 10A, Oppo
A16, realme C21 and Spark 20C. Models come from Google Play's official device list, and specifications from their data
sheets. On any other phone, only that phone's real model is used, until the [catalog is extended](../CONTRIBUTING.md#good-first-contributions).

**Coherent.** Everything derives from the chosen device and reads the same everywhere: Chrome's reduced Android user agent,
Client Hints, Android version, model, memory, processor, screen and languages, in the page, iframes, workers and HTTP headers.
Permissions and `Notification` answer as Chrome on Android does, based on the Chromium sources. A permission request ends as
a dismissed prompt would.

**Stable and distinct.** Canvas, WebGL pixels and audio carry noise computed from the session's seed: the same value on each
read, and a different value in another session.

**Isolated.** Each session has its own Chromium context and renderer processes, so changing the fingerprint or the time zone of
one does not affect the others. Tabs of the same session share one fingerprint.

## Using the API

```bash
curl -X POST http://127.0.0.1:8765/sessions -H "Authorization: Bearer $KEY" -d '{"name":"demo","url":"https://example.com","fingerprint":{"device":"SM-A045F","locale":"fr-SN","timezone":"Africa/Dakar"}}'
```

Omit `fingerprint` for a fully random identity, or pass `"fingerprint": false` to keep the real device.

| Route | Purpose |
| --- | --- |
| `GET /sessions/{id}/fingerprint` | The current fingerprint |
| `POST /sessions/{id}/setFingerprint` | Change it on the fly, then reload the page |
| `GET /fingerprints/devices` | The device catalog |

`setFingerprint` accepts these optional fields. With no field, a new complete identity is drawn.

| Field | Description |
| --- | --- |
| `device` | A model from the catalog |
| `android` | An Android version |
| `memory` | Device memory |
| `locale` | For example `fr-SN` |
| `languages` | The list of languages |
| `timezone` | For example `Africa/Dakar` |
| `seed` | The same seed gives the same fingerprint |
| `reload` | `false` to skip the reload |

A combination that cannot exist, such as a device with an Android version it never ran, is refused.

Over the `/ws` WebSocket: `{"action":"setFingerprint","params":{}}`.

Changing a fingerprint keeps the cookies, so a site that recognizes you by cookie links the two identities. To start from
scratch, open a new session.

## What stays identical or impossible

These limits come from the engine. No fingerprint fixes them, and a determined site can cross-check them.

| | Status |
| --- | --- |
| IP address | Identical for all sessions, because the WebView proxy applies to the whole app. **This is the strongest link between sessions.** The default time zone stays the phone's, to remain consistent with this address. |
| WebGL, WebGPU | WebGL 1 and 2 run on the real GPU, the same for all sessions. `navigator.gpu` exists, but the reference GPU offers no WebGPU adapter. |
| TLS, HTTP/2 | Identical, Chromium's own |
| Fonts, JavaScript engine, GPU | Identical, those of the real hardware |
| `X-Requested-With` | Cannot be removed, only emptied: requests keep an empty header |
| `speechSynthesis`, `PushManager` | Absent from WebView |
| Service workers | Their JavaScript values follow the fingerprint, but their HTTP requests keep WebView's user agent |
| Pop-ups | The script and user agent are in place from the start; the rest follows a few milliseconds later |
| `display-capture`, `periodic-background-sync`, `local-network*` permissions | Left as WebView answers them, because Chrome's Android default could not be confirmed |

## Validation

`tests/e2e/fingerprint/run.mjs` opens several sessions on a local site and checks that each fingerprint differs from the
others, is coherent across page, iframe, worker, service worker, HTTP headers and Client Hints, stays stable across reads,
reloads and restarts, changes on the fly without touching other sessions, is reproducible from a seed, and does not leak an
address through WebRTC.

Values for permissions come from the Chromium 156 source code. They have not been compared with a physical Chrome on Android.
