# Release runbook

## Pre-release

1. Review `SECURITY_DESIGN.md`, `docs/SECURITY_AUDIT.md` and this checklist.
2. Replace all placeholders in `docs/PRIVACY_POLICY.md`.
3. Review third-party licenses and include required notices in the release.
4. Confirm no secrets, test vaults, QR payloads or local properties are in the source.
5. Test first run, VLT2 QR generation, biometric unlock, PIN+QR recovery, old-format
   rejection, import/export, OCR, auto-lock, clipboard clearing and delete-vault.
6. Test wrong PIN, wrong QR, tampered QR, tampered vault, changed KDF parameters,
   corrupted tags, rollback and malformed files.

## Build

Use a local JDK configured for the Android toolchain:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean testDebugUnitTest lintDebug assembleRelease
```

Do not build a release with `local.properties`, signing keys, debug keystores or
production recovery material in the repository.

## Artifact review

- Verify the final `.aab` has the expected application ID and version.
- Inspect the merged manifest and permissions.
- Inspect native libraries and dependency licenses.
- Run a release APK/AAB through JADX and apktool; source visibility is expected and must
  not reveal secrets.
- Install the release artifact on clean devices and test StrongBox, TEE-only and
  biometric-enrollment-change behavior.

## Play upload

1. Upload the AAB through Play Console with Play App Signing.
2. Complete store listing, privacy policy, app content, Data Safety and reviewer access.
3. Use a disposable vault for reviewers. Never submit a real PIN, QR or password.
4. Start with internal testing, then closed testing, then production.
5. Archive the exact AAB, mapping/proguard outputs, test report, license notices and
   completed Play forms.
