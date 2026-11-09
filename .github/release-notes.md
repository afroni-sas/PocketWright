## Install

Download `${APK}` and install it on an arm64 phone running Android 8.0 or later. With a computer:

```bash
adb install ${APK}
```

Do not add `-g`: it would grant every permission at once, including SMS and contacts.

If a build from source is already installed, Android refuses the update because the signing keys differ. Uninstalling it first erases the app's data.

## Verify

```bash
sha256sum -c ${APK}.sha256
```

```bash
apksigner verify --print-certs ${APK}
```

The certificate's SHA-256 digest must be `${SIGNING_CERT_SHA256}`.

Built from commit ${GITHUB_SHA} by [this workflow run](${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}).

`THIRD_PARTY_NOTICES.txt` contains the licenses of the components shipped in the APK.
