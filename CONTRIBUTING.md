# Contributing to PocketWright

Thank you for your interest in PocketWright. You do not need to be an Android or Chromium expert to help: a report
about how the app behaves on your phone is already a valuable contribution.

Issues and pull requests can be written in **English or French**.

By participating, you agree to follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## Ways to contribute

- **Report a bug** with the [bug report form](https://github.com/afroni-sas/PocketWright/issues/new?template=bug_report.yml).
- **Report device compatibility.** The project has been validated on one phone only, so every other model is new information.
- **Suggest a feature** with the [feature request form](https://github.com/afroni-sas/PocketWright/issues/new?template=feature_request.yml).
- **Ask a question or share an idea** in [Discussions](https://github.com/afroni-sas/PocketWright/discussions).
- **Send a pull request** for code, tests, translations or documentation.

Security problems must not go in public issues. Follow [SECURITY.md](SECURITY.md).

## Good first contributions

| Area | What would help | Where to look |
| --- | --- | --- |
| Translation | The interface is hard-coded in French. Move strings to Android resources, then add English and other languages. | `app/src/main/java/.../ui/`, `app/src/main/res/values/` |
| API messages | API errors are in French. Make them English, or stable codes plus a message. | `api/`, `session/`, `engine/main.js` |
| Code comments | Most comments are in French. Translating the ones you touch to English is welcome. | Everywhere |
| Device reports | Run the end-to-end suites on another phone or Android version and share the results. | [`tests/e2e`](tests/e2e/README.md) |
| Fingerprint catalog | The catalog only knows phones with a PowerVR GE8320 GPU. Add real devices for other GPU families. | `fingerprint/Fingerprint.kt`, [docs/fingerprints.md](docs/fingerprints.md) |
| Accessibility | Some toolbar buttons report wrong bounds to TalkBack (PW-012). | [BUGS.md](BUGS.md) |
| Untested areas | Doze, multi-hour runs, dual SIM, real MMS download, other Android versions. | [BUGS.md](BUGS.md#not-tested) |

Before starting something large, open an issue or a discussion so that we can agree on the approach.

## Development setup

The [development guide](docs/development.md) covers the toolchain, build flags and debugging. In short:

```bash
./gradlew :app:assembleDebug -Ppocketwright.abis=arm64-v8a
```

```bash
./gradlew :app:testDebugUnitTest -Ppocketwright.abis=arm64-v8a
```

## Pull request workflow

1. Fork the repository and create a branch from `main`, for example `fix/cdp-cookie-domain` or `feat/session-export`.
2. Keep the change focused. One pull request should do one thing.
3. Add or update tests when behavior changes.
4. Update the documentation when the API or a visible behavior changes, and add an entry to [CHANGELOG.md](CHANGELOG.md).
5. Open the pull request and fill in the template. CI must pass.

### Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/):

```text
feat(api): add a route to export a session's cookies
fix(cdp): keep the session open after a client timeout
docs(fingerprints): describe the stable noise seed
```

Common types are `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `build`, `ci` and `chore`. Common scopes are `api`, `cdp`,
`session`, `engine`, `fingerprint`, `messaging`, `update`, `service`, `ui` and `e2e`.

### Coding guidelines

- Kotlin follows the [official style](https://kotlinlang.org/docs/coding-conventions.html). `.editorconfig` sets the basics.
- Comments explain *why*, especially for WebView and Chromium quirks that would not be obvious from the code.
- Performance matters: the reference device is a 2023 low-end phone. Avoid work on the main thread and in hot paths.
- New API actions must be listed in [docs/api.md](docs/api.md). Read-only actions must be added to `Commands.READ_ONLY`, and only those.
- Anything that changes what a page can observe in a fingerprint session needs a check in `tests/e2e/fingerprint/run.mjs`.

## Testing

| Suite | Runs on | Command |
| --- | --- | --- |
| Unit tests | Any machine | `./gradlew :app:testDebugUnitTest` |
| Browser and API | A real phone over ADB | `ANDROID_SERIAL=<serial> npm test` in `tests/e2e` |
| Fingerprints | A real phone over ADB | `ANDROID_SERIAL=<serial> node fingerprint/run.mjs` in `tests/e2e` |
| Messaging | A real phone, with explicit opt-in | `ANDROID_SERIAL=<serial> node messages.mjs` in `tests/e2e` |

CI runs the unit tests and the build. The real-device suites run locally, so please say in your pull request which
suites you ran and on which device. If you cannot run them, say so: a maintainer can run them for you.

### Testing safely

- Never commit an API key, a keystore, a device serial, or a screenshot that shows personal data. `tests/e2e/out/` is ignored for this reason.
- Use reserved example phone numbers in tests and documentation, such as the French fiction range `+33 6 39 98 xx xx`.
- Do not test against sites or accounts you do not own beyond ordinary browsing.
- On a phone you also use day to day, the messaging suite refuses to run unless you opted in, and only touches its own fake senders. Read [its section](tests/e2e/README.md#messaging) first.

## Licensing of contributions

PocketWright is licensed under the [Apache License 2.0](LICENSE). Unless you state otherwise, any contribution you
intentionally submit is licensed under the same terms, as described in section 5 of the license. Only submit work that
you have the right to contribute.

## Maintainer notes

Maintainers review pull requests as time allows. If yours has been waiting for a week or more, a polite reminder is welcome.
