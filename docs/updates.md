# Updating Chromium and Playwright

Everything happens in **Paramètres → Mises à jour** (Settings → Updates), without Google Play or root, followed by an app
restart. The same operations are available through the [API](api.md#updates).

| Component | Source | Verification |
| --- | --- | --- |
| Chromium | WebView published by [LineageOS](https://github.com/LineageOS/android_external_chromium-webview_prebuilt_arm64), built from the Chromium sources | SHA-256 read from LineageOS's Git repository at the exact commit, then the APK certificate |
| Playwright | `playwright-core` on the official npm registry | The registry's SHA-512 integrity value |

Android only accepts a *system* WebView signed by Google and delivered by the Play Store. PocketWright therefore loads its own
engine for itself only, through [WebViewUpgrade](../webviewup/README.md), without installing anything system-wide.

## Safety net

- An engine or Playwright version that prevents the app from starting is discarded after three attempts, and the app returns to the previous version by itself.
- « Revenir au moteur du système » and « Revenir à la version intégrée » go back manually.
- An interrupted download resumes where it stopped.

## Trade-offs

**Sandboxing.** With the app-private engine, renderer processes are not isolated as in an installed Chrome: they run with the
app's privileges, so an engine vulnerability would expose the app's data. The system engine is sandboxed, but stuck at its
installed version on phones without Google Play.

**Release rhythm.** The engine follows LineageOS's releases, every two to five weeks in recent months, sometimes after Chrome.
The version check reads GitHub's public Atom feed, which has no per-IP quota.

**What the verification guarantees.** LineageOS signs its WebView with Chromium's public test key, so the signature does not
prove where the file came from. The SHA-256, read over HTTPS from LineageOS's repository, does. Someone taking control of that
repository could publish a compromised engine.

## Bundled versions

The APK ships with a known-good Playwright. See the [development guide](development.md#updating-dependencies) to change it.
