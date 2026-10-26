# SecureVault: Passwords & Docs

Local-first Android encrypted vault for passwords, documents and files using the VLT3
hybrid encrypted vault.

> **Security status:** this is an engineering prototype, not a security certification.
> Do not use it for irreplaceable production secrets until an independent mobile and
> cryptographic assessment has been completed.

## Features

- Random 256-bit DEK, Argon2id passphrase derivation and independent 256-bit Recovery QR.
- Recovery requires both the passphrase and Recovery QR; the QR never contains the DEK.
- Hardware-backed Android Keystore wrapper for daily unlock with strong biometrics or
  the Android device PIN, pattern or password.
- AES-256-GCM with per-generation HKDF-SHA-256 keys, random 96-bit nonces and
  authenticated object context.
- Small bounded encrypted text block plus separately encrypted immutable image,
  thumbnail, document-page and file objects.
- Password entries, named images, multi-page scanned documents and files appear in the
  same searchable list. Media is loaded only when needed. All vault features are free;
  file, document, history and advanced import/export features have no subscription gate.
- Copy-on-write saves with `manifest.current` as the commit point and
  `manifest.previous` for recovery.
- Encrypted backup/export, passphrase+Recovery-QR import, and an optional
  user-selected encrypted backup location refreshed after each successful save.
- Folders, tags, favorites, TOTP, local password-health checks and auto-lock.
- On-device OCR review, document scanning, clipboard timeout and `FLAG_SECURE`.
- Polish, English, German and French UI resources.

An optional one-time Google Play Billing product (`pro_unlock`) removes the AdMob banner
shown only on the unlock screen. The ad-free entitlement is cached locally for offline
use. Debug builds include test ads and a debug-only ad-free override. Release builds
require AdMob app and banner IDs in user-level Gradle properties; use Google's test IDs
only for internal-test bundles and real IDs for production.

VLT2 is not supported or migrated. If an old `vault.vlt` exists, use the previous app
version to export its data before creating a VLT3 vault.

## Threat model

The attacker may know the source code, APK and a copied VLT3 backup, but is assumed not
to have both the passphrase and the independent Recovery QR secret. A rooted or modified
process can read plaintext after unlock and is outside the protection boundary.

The DEK is never stored plaintext. A passphrase+QR-derived AES-GCM wrapper and an
authentication-bound hardware wrapper protect the same DEK. Text, images, thumbnails
and files are encrypted with separate domain-separated keys. The encrypted manifest
contains object references and authenticated ciphertext hashes.

See [`SECURITY_DESIGN.md`](SECURITY_DESIGN.md), [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md),
[`docs/SECURITY_AUDIT.md`](docs/SECURITY_AUDIT.md), and
[`docs/VLT3_IMPLEMENTATION_PROGRESS.md`](docs/VLT3_IMPLEMENTATION_PROGRESS.md).

## Build and checks

Requirements: Android Studio with bundled JDK and Android SDK platform 37.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Android instrumentation tests require a connected device. Repository tests additionally require hardware-backed Keystore support and a configured
device credential; biometric enrollment is optional.

## Project structure

```text
app/src/main/java/.../
  MainActivity.java              UI, file/image flows, auto-lock and backups
  crypto/                        VLT3 codecs, AEAD, KDF, object and image processing
  repository/VaultV3Repository   VLT3 persistence, copy-on-write and recovery
  security/                      Android Keystore and passphrase policy

docs/
  THREAT_MODEL.md                attacker capabilities and guarantees
  SECURITY_AUDIT.md              audit status and release blockers
  IMPLEMENTATION_AUDIT.md        implementation coverage and current gaps
  VLT3_IMPLEMENTATION_PROGRESS.md current VLT3 implementation status

SECURITY_DESIGN.md                VLT3 key hierarchy, storage and transaction model
```

## Privacy and release

The app has no publisher-controlled account, analytics or vault backend. OCR runs
on-device. AdMob requests are made only on the unlock screen and never include vault
contents; Google may process advertising-related data under its own privacy terms.
Google UMP manages consent and privacy options. Vault backups are encrypted; explicitly
exported attachments and CSV exports contain plaintext at the user-selected destination.
External document providers apply their own storage and privacy terms.

Before publication, replace placeholders in `docs/PRIVACY_POLICY.md`, review SDK
privacy practices and complete `docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md`. The
preproduction store copy, listing graphics, emulator screenshots and Play Console field
map are in [`play-publishing/`](play-publishing/README.md).

## License and third-party code

The application source license must be chosen by the project owner before publication.
Third-party notices and obligations are listed in `docs/THIRD_PARTY_NOTICES.md`.
