# SecurePasswordManager

Local-first password manager for Android with hardware-backed biometric unlock,
PIN/passphrase + Recovery QR recovery, encrypted backups, image entries and a
step-by-step in-app tutorial.

> **Security status:** this is an engineering prototype, not a security certification.
> Do not use it for irreplaceable production secrets until an independent mobile and
> cryptographic assessment has been completed.

## Features

- VLT2 vault format with a random 256-bit DEK.
- AES-256-GCM encryption with authenticated metadata and padded data.
- 12–24 character passphrase policy; numeric-only secrets are rejected.
- Argon2id passphrase derivation through Bouncy Castle.
- Independent 256-bit Recovery QR secret.
- Recovery requires **passphrase + Recovery QR**.
- Hardware-backed RSA-3072 device wrapper with StrongBox/TEE preference.
- Strong biometric unlock through Android `BiometricPrompt`.
- Auto-lock timeout and lock-on-background settings.
- Encrypted password entries with folders, tags, favorites and TOTP.
- Local password-health checks for weak and reused passwords.
- Image entries in the same list as password entries:
  - camera or gallery input,
  - interactive crop with pinch-to-zoom,
  - only the selected fragment is stored as encrypted JPG,
  - image entries have their own names and image icon.
- Encrypted backup/export/import through Android Storage Access Framework.
- Optional automatic encrypted backup to a user-selected provider such as Google Drive.
- On-device OCR review flow for printed text; no image upload.
- Clipboard timeout and `FLAG_SECURE`.
- Polish, English, German and French UI resources.
- In-app step-by-step feature tutorial.
- Debug-only Free/Pro entitlement switch for testing future premium UI.

## Threat model

The design assumes that an attacker can obtain the source code, APK and a copy of
`vault.vlt`. The attacker is assumed not to have both the passphrase and the independent
Recovery QR secret.

The source code intentionally contains no user secrets. VLT2 has no standalone PIN
verifier, so a copied vault cannot validate passphrase guesses without the QR secret.
The device wrapper is protected by a non-exportable Android Keystore key and is not
copied with the vault file.

The design does **not** protect plaintext after a rooted device or a modified process
has unlocked the vault. JVM strings, UI objects and garbage collection do not provide
a guarantee of complete memory zeroization.

Read the detailed documents:

- [`SECURITY_DESIGN.md`](SECURITY_DESIGN.md)
- [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)
- [`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md)
- [`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)

## VLT2 key model

```text
random DEK
  ├── AES-256-GCM encrypts padded VaultData
  ├── RSA device wrapper -> daily biometric unlock
  └── AES-GCM wrapper using:
          Argon2id(passphrase) + independent 256-bit QR secret
```

The Recovery QR never contains the DEK. A separate optional passphrase QR is only a
printable transport for the passphrase and must be stored separately from the Recovery
QR.

## Build

Requirements:

- Android Studio with the bundled JDK.
- Android SDK with compile/target API 37.
- Android SDK platform tools for device testing.

PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Running on a device

1. Enable developer options and USB debugging.
2. Connect the device.
3. Verify it with `adb devices`.
4. Install the debug APK.
5. Configure a secure device lock screen and strong biometrics.
6. Create a new vault.
7. Print/store both the passphrase QR and Recovery QR separately.
8. Test recovery before storing real credentials.

## Project structure

```text
app/src/main/java/.../
  MainActivity.java              UI, flows, auto-lock and tutorial entry point
  ImageCropActivity.java         secure image crop screen
  SelectionOverlayView.java      zoomable editable selection overlay
  crypto/                        formats, KDF, AEAD, TOTP and health checks
  repository/VaultRepository     VLT2 persistence and recovery boundary
  security/                      Android Keystore and passphrase policy

app/src/main/res/
  layout/                        Material 3 screens and dialogs
  drawable/                      vector icons and backgrounds
  values[-locale]/               strings, colors and themes

docs/
  CODE_GUIDE.md                  educational architecture guide
  THREAT_MODEL.md                attacker capabilities and guarantees
  SECURITY_AUDIT.md              audit findings and release blockers
  GOOGLE_PLAY_RELEASE_CHECKLIST.md
  PRIVACY_POLICY.md
  DATA_SAFETY.md
  RELEASE_RUNBOOK.md
  THIRD_PARTY_NOTICES.md
```

## Privacy and Google Play

The app is local-first and has no publisher-controlled account or analytics backend.
Camera OCR is user-triggered and on-device. User-selected encrypted backup providers
remain responsible for their own storage terms.

Before publishing, replace placeholders in `docs/PRIVACY_POLICY.md`, publish it at a
real HTTPS URL, complete Play Console Data Safety, configure Play App Signing and build
a signed release AAB. The full checklist is in
[`docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md`](docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md).

## License and third-party code

The application source license must be chosen by the project owner before publication.
Third-party notices and obligations are listed in
[`docs/THIRD_PARTY_NOTICES.md`](docs/THIRD_PARTY_NOTICES.md).
