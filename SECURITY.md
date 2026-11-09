# Security policy

## Reporting a vulnerability

**Please do not report security problems in public issues.**

Use GitHub's private reporting: open the
[Security tab](https://github.com/afroni-sas/PocketWright/security/advisories/new) and choose **Report a vulnerability**.

Please include the affected version or commit, the phone and Android version, the steps to reproduce, and the impact you
foresee. Reports can be written in English or French.

We aim to acknowledge reports within a week, and to agree on a disclosure date with you once a fix is ready. PocketWright
is a volunteer-maintained open source project and has no bug bounty.

## Supported versions

Security fixes land on the `main` branch and ship in the next release. Only the latest release is supported.

## Verifying a release

Published APKs are built by GitHub Actions from the tag, and signed with a dedicated key. To check a download:

```bash
sha256sum -c PocketWright-0.1.0-arm64-v8a.apk.sha256
```

```bash
apksigner verify --print-certs PocketWright-0.1.0-arm64-v8a.apk
```

The certificate's SHA-256 digest must be:

```text
2d8ac18c51c6222671f33023bb9ba34f246c98f1078dc662499335220cbc58ba
```

The key is stored in the repository's encrypted Actions secrets. Pull requests from forks cannot reach it.

## Threat model

### What PocketWright protects against

| Threat | Protection |
| --- | --- |
| Other devices on the network | The server binds to `127.0.0.1` only. |
| Other apps on the phone | Any app can reach localhost, so an API key is required by default. Disabling it is possible, but messaging routes always require the key. |
| Web pages calling the local API | Requests with a non-local `Host`, a foreign `Origin` or a cross-site `Sec-Fetch-Site` are rejected, which covers DNS rebinding and cross-site WebSockets. Commands with side effects are refused on `GET`. |
| Tampered updates | Chromium is checked by SHA-256 against LineageOS's Git repository at an exact commit, then by APK certificate. Playwright is checked by the npm registry's SHA-512 integrity value. |
| A broken update | An engine that prevents the app from starting is discarded after three attempts, and the app rolls back. |

### What the API key can do

**Treat the key like a password to the phone's browser.** A program holding it can:

- Read and act on every tab, including logged-in accounts.
- Run arbitrary code inside the app through the `run` action, with all the permissions the app holds.
- Once messaging is enabled, read the whole SMS history, delete or edit messages, and send SMS at your expense.

The "API access to messages" switch configures the API. It is **not** a barrier against a malicious program holding the
key, because `run` executes that program's code inside the app.

### Known risks

- **Renderer isolation.** With the app-private Chromium engine, renderer processes run with the app's privileges instead
  of being sandboxed as in an installed Chrome. A browser exploit could reach the app's data, including SMS and contacts
  if messaging is enabled. The system WebView engine does not have this problem, but is frozen at its installed version
  on phones without Google Play.
- **Update source.** LineageOS signs its WebView with Chromium's public test key, so the signature does not prove the
  file's origin. The SHA-256 read over HTTPS from LineageOS's repository does. Someone who took over that repository
  could publish a malicious engine.
- **WebView debugging.** It is always on, because the CDP gateway depends on it. Anyone with ADB access to the phone can inspect its tabs, so keep USB debugging off when you do not need it.
- **Builds from source.** A release build made without the publishing key is signed with the local development key. It is meant for your own phone. Sign with your own key before distributing it.
- **`adb install -g`** grants every permission without the user's choice, including SMS and contacts. Do not use it.

### Out of scope

- Attacks that need the API key, unless they go beyond the capabilities described above.
- Attacks that need a rooted phone or physical access to an unlocked phone.
- Websites correlating sessions through what is documented as impossible to isolate, such as the shared IP address. See [docs/fingerprints.md](docs/fingerprints.md).
- Vulnerabilities in Chromium itself: report them to the [Chromium project](https://www.chromium.org/Home/chromium-security/reporting-security-bugs/).
