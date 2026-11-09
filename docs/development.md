# Development guide

## Toolchain

| Tool | Version |
| --- | --- |
| JDK | 17 |
| Android SDK | Compile SDK 37 |
| Android NDK | `30.0.16248370` |
| CMake | `4.1.2` |
| Node.js and npm | Node 22 or later |
| Tools on `PATH` | `bash`, `curl`, `unzip`, `sha256sum` |

The SDK location goes in `local.properties` or `ANDROID_HOME`. Install the SDK components with:

```bash
sdkmanager "platforms;android-37" "ndk;30.0.16248370" "cmake;4.1.2"
```

The first build runs `scripts/prepare-engine.sh`, which downloads Node.js for Android into `third_party/` (verified by SHA-256)
and installs `playwright-core` into the APK's assets.

The app runs on Android 8.0 (API 26) and later.

## Building

| Goal | Command |
| --- | --- |
| Release APK for phones | `./gradlew :app:assembleRelease -Ppocketwright.abis=arm64-v8a` |
| Debug APK for phones | `./gradlew :app:assembleDebug -Ppocketwright.abis=arm64-v8a` |
| All architectures | `./gradlew :app:assembleRelease` |
| Unit tests | `./gradlew :app:testDebugUnitTest -Ppocketwright.abis=arm64-v8a` |

Use the release build on real phones when you evaluate behavior or performance: the debug build is far slower, with a cold start
of 8 s instead of 0.7 s on the reference phone. The release build is signed with the local development key, so that it can be
reinstalled without losing tabs. Use your own key to distribute it.

### Gradle properties

| Property | Effect |
| --- | --- |
| `pocketwright.abis` | Comma-separated architectures. Default: `arm64-v8a,x86_64`. Node.js weighs about 85 MB per architecture |
| `pocketwright.probe` | Builds a separate copy to compare engines: identifier `com.afroni.pocketwright.probe`, port 8766, debuggable, data separate from the installed app |

## Project map

```text
app/src/main/java/com/afroni/pocketwright/
  api/          HTTP, WebSocket and message routes, command dispatch
  cdp/          CDP gateway and DevTools connections
  engine/       Starts Node.js and forwards commands to Playwright
  fingerprint/  Device catalog, native overrides, page script
  messaging/    Default SMS role, storage, MMS decoding, journal
  service/      Foreground service and boot receiver
  session/      Sessions, WebViews, page scripts
  ui/           Compose screens
  update/       Chromium and Playwright updates
app/src/main/cpp/   JNI launcher for Node.js
engine/             Node.js project shipped in the APK
scripts/            Build-time preparation
tests/e2e/          Real-device suites
webviewup/          Modified copy of WebViewUpgrade
```

[Architecture](architecture.md) describes how these parts work together.

## Debugging

App logs use the `PocketWright` tag:

```bash
adb logcat -s PocketWright
```

To inspect a page with Chrome DevTools, open `chrome://inspect` on a computer connected over ADB, and find the app's WebViews. In the app, **menu → Automatisation et console** shows how to attach to a tab, runs Playwright scripts, and displays the
tab's log.

## Testing

See the [contribution guide](../CONTRIBUTING.md#testing) for a summary and [tests/e2e](../tests/e2e/README.md) for the
real-device suites.

## Updating dependencies

| Dependency | How to update |
| --- | --- |
| Gradle libraries | `gradle/libs.versions.toml` |
| Bundled Playwright | Change the version in `engine/package.json` and `tests/e2e/package.json`, then run `npm install --package-lock-only --ignore-scripts` in both directories. Re-run the end-to-end suite: the CDP gateway depends on what Playwright sends. |
| Node.js for Android | Update `NODE_MOBILE_VERSION` and `NODE_MOBILE_SHA256` in `scripts/prepare-engine.sh` |
| WebViewUpgrade | Vendored in `webviewup/`. Re-apply the changes listed in [its README](../webviewup/README.md) |
| NDK, CMake | `app/build.gradle.kts`, this guide, and `.github/workflows/ci.yml` |
