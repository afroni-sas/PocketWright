# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-11-09

First public version. It has been validated on one phone (TECNO BG7n, Android 13), so please treat other devices as untested and [tell us how it behaves](https://github.com/afroni-sas/PocketWright/issues/new/choose). The APK is for arm64 phones.

### Added

- Android browser: tabs with previews, private browsing, bookmarks, history, downloads, print to PDF, find in page, desktop mode, per-site permissions.
- Embedded `playwright-core` 1.65 on Node.js, driven through a local API on `127.0.0.1:8765`: REST, WebSocket and CDP.
- Persistent sessions with one isolated profile per API connection, visible live in the app.
- Per-session device fingerprints that are coherent, stable and changeable on the fly.
- Updates of Chromium (LineageOS WebView) and Playwright from inside the app, without Google Play, with integrity checks and automatic rollback.
- Optional default SMS/MMS app role, with a message stream and full CRUD over one WebSocket.
- Foreground service, restart at boot and a self-monitoring watchdog.
- End-to-end test suites for real devices.

[Unreleased]: https://github.com/afroni-sas/PocketWright/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/afroni-sas/PocketWright/releases/tag/v0.1.0
