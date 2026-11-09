# webviewup

A copy of the [WebViewUpgrade](https://github.com/JonaNorman/WebViewUpgrade) library (Apache-2.0), `core` module, at commit
`5a5a7d11b5e67bba37787a697f45c9f5d45e6dda`. The version published on Maven (0.1.0, 2024) is too old for this project.

It lets PocketWright load its own Chromium engine from an APK, without installing it system-wide and without Google Play.

## Changes made for PocketWright

- The Gradle configuration.
- `ActivityManagerHook`: each renderer instance requested by Chromium gets its own slot. Without this, the second renderer attaches to the first and navigations hang.
- 20 renderer slots instead of 5, with the matching `StubSandboxedProcessService` classes and manifest entries.
- `SandboxedProcessServiceDelegate`: a renderer process exits when Chromium releases it. Without this, slots and memory are lost.
