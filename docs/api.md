# API reference

The API listens on `http://127.0.0.1:8765`, on the phone only. From a computer, forward the port:

```bash
adb forward tcp:8765 tcp:8765
```

## Authentication

The key is in the app under **Paramètres → API locale** (Settings → Local API). Send it in any of these ways:

| Method | Example |
| --- | --- |
| Bearer token | `Authorization: Bearer <key>` |
| Dedicated header | `X-API-Key: <key>` |
| Query parameter, meant for WebSockets | `?key=<key>` |

The key can be regenerated in the app. Requiring it can be turned off, but `/messages/*` always requires it.

Requests from web pages are refused: the `Host` must be local, and a foreign `Origin` or `Sec-Fetch-Site` is rejected.

## Responses and errors

REST responses are JSON:

```json
{"ok": true, "result": {"url": "https://example.com/", "title": "Example Domain"}}
```

```json
{"ok": false, "error": "Message describing the problem"}
```

| Status | Meaning |
| --- | --- |
| `400` | Invalid JSON or parameters |
| `401` | Missing or wrong API key |
| `403` | Blocked by a setting, such as message access being off |
| `404` | Unknown session or action |
| `405` | An action with side effects was sent with `GET` |
| `408` | The command timed out |
| `409` | Conflicting state, such as an update already installing or another default SMS app |
| `410` | The session was closed |
| `500` | Unexpected error |
| `503` | The Playwright engine is not ready for this action |

Error messages are currently in French.

## Server

| Route | Purpose |
| --- | --- |
| `GET /` | Server, Chromium and Playwright status, and the endpoint list |

## Sessions

Tabs opened by hand share one profile, as in a regular browser. Each session opened by the API has its own profile
(cookies and storage). A session survives the end of its connection and app restarts. Only a close command ends it.

| Route | Purpose |
| --- | --- |
| `GET /sessions` | List sessions |
| `POST /sessions` | Open a session |
| `DELETE /sessions` | **Close every session, including the tabs opened by hand** |
| `GET /sessions/{id}` | Describe one session |
| `DELETE /sessions/{id}` | Close one session |
| `POST /sessions/{id}/{action}` | Run a command, with a JSON body |
| `GET /sessions/{id}/{action}` | Run a read-only command, with query parameters |
| `GET /sessions/{id}/screenshot` | The image itself. Accepts `?fullPage=true`, `?type=jpeg`, `?format=base64` |

### Opening a session

```bash
curl -X POST http://127.0.0.1:8765/sessions -H "Authorization: Bearer $KEY" -d '{"name":"demo","url":"https://example.com"}'
```

| Field | Type | Description |
| --- | --- | --- |
| `name` | string | Display name in the app |
| `url` | string | Page to open |
| `userAgent` | string | Overrides the user agent |
| `viewport` | object | `{"width": 412, "height": 915}`. Flat `width` and `height` are also accepted |
| `fingerprint` | object or `false` | See [Fingerprints](fingerprints.md). Omit for a random identity, `false` for the real device |

### Actions

Selectors are Playwright's: CSS, `text=`, `role=button[name="OK"]`, `xpath=` and so on.

```bash
curl -X POST http://127.0.0.1:8765/sessions/$ID/click -H "Authorization: Bearer $KEY" -d '{"selector":"role=button[name=\"OK\"]"}'
```

| Group | Actions |
| --- | --- |
| Navigation | `goto`, `reload`, `back`, `forward`, `setContent`, `waitForLoad`, `waitForURL` |
| Interaction | `click`, `dblclick`, `tap`, `hover`, `focus`, `check`, `uncheck`, `fill`, `clear`, `type`, `press`, `selectOption`, `setInputFiles`, `dragTo`, `scroll`, `scrollIntoView`, `mouse`, `keyboard`, `touchscreen` |
| Reading | `info`, `url`, `title`, `content`, `text`, `textContent`, `html`, `innerHTML`, `attribute`, `inputValue`, `count`, `isVisible`, `isEnabled`, `isChecked`, `boundingBox`, `ariaSnapshot`, `queryAll` |
| Waiting | `wait`, `waitForSelector`, `waitForTimeout`, `waitForFunction` |
| Scripts | `evaluate`, `addInitScript`, `addScriptTag`, `addStyleTag`, `run` |
| Page and context | `screenshot`, `viewport`, `emulateMedia`, `setExtraHTTPHeaders`, `bringToFront`, `cookies`, `addCookies`, `clearCookies`, `storageState`, `setGeolocation`, `grantPermissions`, `setOffline` |
| Session | `rename`, `dialogs` (`{"mode":"accept"\|"dismiss","promptText":"…"}`), `fingerprint`, `setFingerprint`, `close` |

**Read-only actions allowed on `GET`:** `info`, `url`, `title`, `content`, `text`, `textContent`, `html`, `innerHTML`,
`attribute`, `inputValue`, `count`, `isVisible`, `isEnabled`, `isChecked`, `boundingBox`, `ariaSnapshot`, `queryAll`,
`cookies`, `screenshot`, `fingerprint`.

The default command timeout is 30 seconds, as in Playwright. Set `"timeout"` in milliseconds to change it.

### Free-form Playwright scripts

`run` executes the body of an async function, with `page`, `context`, `browser`, `playwright` and `state` in scope.
`state` is kept from one script to the next, and `console.log` output is returned in `logs`. The default timeout for `run`
is 120 seconds.

```bash
curl -X POST http://127.0.0.1:8765/sessions/$ID/run -H "Authorization: Bearer $KEY" -d '{"script":"await page.getByRole(\"link\").first().click(); return page.url();"}'
```

> [!WARNING]
> `run` executes code inside the app, with all the permissions the app holds.

### Native engine

The app has a small built-in engine for basic commands, used when Playwright is not ready yet. `"engine":"native"` forces
it, and `"engine":"playwright"` requires Playwright. `GET /` lists the actions it supports under `nativeActions`.

## WebSocket: one connection, one session

```text
ws://127.0.0.1:8765/ws?key=$KEY
```

A connection opens its own session (`&name=`, `&url=`), or resumes an existing one with `&session=<id>`. When the client
disconnects, the session stays open. The first frame describes the session:

```json
{"type": "session", "session": {"id": "…"}}
```

Send commands in this format:

```json
{"id": 1, "action": "goto", "params": {"url": "https://example.com"}}
```

Each command receives a result:

```json
{"type": "result", "id": 1, "ok": true, "result": {}}
```

The session also sends events: `load`, `console`, `dialog`, `download`, `error`, `crashed` and `closed`.
`{"action":"close"}` closes the session.

## Playwright and Puppeteer over CDP

From any client, on the phone (Termux) or on a computer:

```js
const browser = await chromium.connectOverCDP(`ws://127.0.0.1:8765/cdp?key=${KEY}`, { isWebView: true });
const page = browser.contexts()[0].pages()[0];
```

| Route | Purpose |
| --- | --- |
| `ws://…/cdp` | Opens a new session |
| `ws://…/cdp/{id}` | Connects to an existing session |
| `GET /cdp/{id}/json/version` | HTTP discovery, for clients that start from an `http://` URL |

`browser.close()` disconnects without closing the session, and `page.close()` closes the tab.

`context.newPage()`, `browser.newContext()`, `window.open`, `page.route`, `storageState`, `setInputFiles`, `frameLocator`
and screenshots all work.

Not available in a WebView: `page.pdf()` and Playwright's `download` event. The WebSocket API has its own `download` event.

### Screenshots and WebGL

Off-screen captures are software-drawn, and do not include GPU textures. During a capture, the first 16 canvases of the main
page are temporarily painted as their own CSS background, so WebGL content appears. A WebGL canvas stays blank in an
iframe, or on a site whose Content Security Policy forbids `data:` images.

## Fingerprints

| Route | Purpose |
| --- | --- |
| `GET /sessions/{id}/fingerprint` | The current fingerprint |
| `POST /sessions/{id}/setFingerprint` | Change it on the fly, then reload the page |
| `GET /fingerprints/devices` | The device catalog |

Parameters are described in [Fingerprints](fingerprints.md).

## Updates

| Route | Purpose |
| --- | --- |
| `GET /updates` | Current versions and update state |
| `POST /updates/check` | Look for new versions |
| `POST /updates/chromium` | Install the latest Chromium |
| `POST /updates/playwright` | Install Playwright. Optional body: `{"version":"1.64.0"}` |
| `POST /updates/restart` | Restart the app to apply an update |
| `POST /updates/chromium/system` | Go back to the system WebView |
| `POST /updates/playwright/bundled` | Go back to the bundled Playwright |

See [Updates](updates.md).

## Messages

SMS and MMS routes are off by default and need two explicit steps on the phone. See [Messaging](messaging.md).

| Route | Purpose |
| --- | --- |
| `GET /messages/status` | `defaultApp`, `streaming`, `lastSeq` |
| `GET /messages/stream` | Stream over SSE |
| `ws://…/messages/stream` | Stream and commands over WebSocket |
| `GET /messages/parts/{id}` | One attachment, as binary |
