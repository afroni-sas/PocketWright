# PocketWright bugs

This file records the bugs found during development and what remains unverified. New bugs go in
[GitHub Issues](https://github.com/afroni-sas/PocketWright/issues).

Last update: **November 8, 2026**. Validated on a **TECNO Mobile BG7n** (MT6765, 3.9 GB, Android 13), Chromium 156.0.8078.47 (LineageOS
WebView, app engine), Playwright 1.65.0, Node v24.20.0, optimized build. Fingerprints: 180 of 180 checks (`tests/e2e/fingerprint/run.mjs`).
Browser: 166 of 166.

The full run is described in [tests/e2e](tests/e2e/README.md).

## Open

| ID | Bug | Impact | Details |
| --- | --- | --- | --- |
| PW-011 | A few Chromium 153 renderer processes ended on an internal assertion (`SIGTRAP`, 1 or 2 per full run) | Low: the app does not crash, and every check passes | None seen on Chromium 156 during a full run, which is too little to call it fixed |
| PW-012 | Wrong accessibility bounds on some toolbar buttons (`[0,0][0,0]`), and a message input cut at these same positions | May hinder TalkBack. Touch is not affected | The affected buttons remain usable by touch. Tested by coordinates |

## Not tested

This run does not prove that there are no other bugs. Still to qualify: phone restart and start at boot with the optimized build,
multi-hour use, Doze battery saving, other Android versions and phones, TalkBack, protected video (DRM), camera and microphone.

Messaging, on a phone without a SIM: incoming SMS are placed into the real telephony stack, but have not crossed the network. Still to
qualify with a SIM: a real send, a real MMS download (only its decoding is tested, by `MmsPduTest`), dual SIM, and closing the stream
when another app takes over the role.

The messaging suite (78 of 78) has not been replayed on the LineageOS engine, because it requires turning on API access in the settings. Only
MMS decoding (`MmsPduTest`) has.

Fingerprints were tested on a local site, with FingerprintJS and on amiunique.org (four reports), not on other public detection sites. Restart
after a native crash relies on two alarms, but has not been replayed end to end on the final version.

## Fixed

| ID | Bug | Cause | Fix |
| --- | --- | --- | --- |
| PW-001 to 004 | `addCookies`, `cookies`, `clearCookies`, `storageState` fail ("Browser context management is not supported") | WebView does not handle `Storage.*` at browser level | The CDP gateway translates them to `Network.*` on a page of the context |
| PW-005, 006 | `dialog.dismiss()` and `dialog.accept(text)` ignored | The app answered dialogs before Playwright | The app leaves it to the CDP client, to the user if they are watching, or to the mode set by `dialogs`. A timeout prevents a permanent hang |
| PW-007 | Navigation to another site stuck at 10 % on recent Chromium | The loading library sent every renderer to the same stub | One slot per instance requested by Chromium, 20 slots |
| PW-008 | Extreme slowness, up to 3.5 GB of RAM lost | Chromium keeps one renderer per site visited, never released | A reaper terminates cached renderers beyond a quota, and the hidden tab concerned goes to sleep |
| PW-009 | Janky interface, 8-second start | Debug APK, engine loaded before the first frame | R8 build, engine loaded after the first frame, Node compile cache, inactive tabs paused |
| PW-010 | `newPage()` and `connectOverCDP` stuck if tabs are paused | Page lookup queried frozen tabs | The page is found through a temporary marker in its title, without touching other tabs |
| PW-013 | A GitHub outage displayed a raw error with no way to retry | One attempt, and the HTTP message shown as is | Three spaced attempts, a clear message and a retry button. The installed engine is untouched |
| PW-014 | Returning to Settings erased a running download or the restart button | Each opening started a check that overwrote the state | A check no longer touches an install in progress or waiting for restart. Two simultaneous installs are refused (409) |
| PW-015 | Download resume blocked forever if the app was killed right after completion | The server answers 416 to a resume past the end of the file | Complete file: nothing to request. Inconsistent file: start over |
| PW-016 | After `POST /updates/restart` with the app in the background, the API did not come back (63 s or more) | Android refuses to open a screen from the background, and only an exact alarm can start a foreground service | Restart by exact alarm, also used when the engine hangs |
| PW-017 | After deleting the last SMS, a new message was saved but never streamed | Android reuses identifiers, and the journal refused those already seen | The journal also keeps the message date, so an entry never refers to another message |
| PW-018 | In a long thread, your own reply or an incoming message stayed hidden under the input field | The list preserves its position when an item arrives | The thread follows the last message, unless the user has scrolled up |
| PW-019 | After an engine crash, the app stayed stopped (5 min or more) | This phone does not restart the service on its own | An exact alarm checks the service every five minutes |
| PW-020 | WebRTC gave every page the phone's local address and public IPv6 | WebView does not apply Chrome's masking | Candidate and SDP addresses are replaced by per-session `.local` names |
| PW-021 | All requests announced the app's name (`X-Requested-With`) | WebView adds it unconditionally, and the option to remove it is gone | The header is emptied in fingerprint sessions. It cannot be removed |
| PW-022 | Canvas different on each read, which no real phone does | Random noise from the former engine, Cromite | Moved to the LineageOS WebView, without this randomness. Per-session fixed-seed noise |
| PW-023 | WebGL missing in all tabs | Cromite hard-blocks it in WebView | Moved to the LineageOS WebView: WebGL 1 and 2 on the real GPU |
| PW-024 | Audio different on each render, so the FingerprintJS identifier changed on each visit | Randomness forced by Cromite | Moved to the LineageOS WebView: same identifier after reload |
| PW-025 | `page.screenshot()` and `GET /screenshot` left a hole in place of a WebGL canvas | Off-screen capture is software-drawn, and ignores GPU textures | During the capture, each canvas carries a copy of itself as a background image, under any HTML covering it |
| PW-026 | The engine version check failed after a few attempts (rate limit) | Quota of 60 requests per hour and per IP on the GitHub API | Reads the public commit feed, which has no such quota |
| PW-027 | WebView permissions (`notifications`, `clipboard-read`, `background-sync`… `denied`) and no `Notification`, while the user agent claims Chrome | WebView refuses what it does not handle | In fingerprint sessions: default states from the Chromium 156 sources, and `Notification` as on Chrome Android. On amiunique.org, 20.28 % of fingerprints now have the same permissions, against 0.39 % |
