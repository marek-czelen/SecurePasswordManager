# SecurePasswordManager

Local-first Android password manager using the VLT3 hybrid encrypted vault.

> **Security status:** this is an engineering prototype, not a security certification.
> Do not use it for irreplaceable production secrets until an independent mobile and
> cryptographic assessment has been completed.

## Features

- Random 256-bit DEK, Argon2id passphrase derivation and independent 256-bit Recovery QR.
- Recovery requires both the passphrase and Recovery QR; the QR never contains the DEK.
- Hardware-backed Android Keystore wrapper for daily biometric unlock.
- AES-256-GCM with per-generation HKDF-SHA-256 keys, random 96-bit nonces and
  authenticated object context.
- Small bounded encrypted text block plus separately encrypted immutable image,
  thumbnail and file objects.
- Entries, image containers and files appear in the same searchable list. Images/files
  are loaded only when needed; file attachment creation is Pro-gated, while backup and
  recovery remain available independently of Free/Pro.
- Copy-on-write saves with `manifest.current` as the commit point and
  `manifest.previous` for recovery.
- One-file encrypted backup/export and passphrase+Recovery-QR import.
- Folders, tags, favorites, TOTP, local password-health checks and auto-lock.
- On-device OCR review, clipboard timeout and `FLAG_SECURE`.
- Polish, English, German and French UI resources.

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

Android instrumentation tests require a connected device. Repository tests additionally
require hardware-backed strong-biometric Keystore support.

## Project structure

```text
app/src/main/java/.../
  MainActivity.java              UI, file/image flows, auto-lock and backups
  crypto/                        VLT3 codecs, AEAD, KDF, object and image processing
  repository/VaultV3Repository   VLT3 persistence, copy-on-write and recovery
  security/                      Android Keystore and passphrase policy

docs/
  SECURITY_DESIGN.md             VLT3 key hierarchy, storage and transaction model
  THREAT_MODEL.md                attacker capabilities and guarantees
  SECURITY_AUDIT.md              audit status and release blockers
  VLT3_IMPLEMENTATION_PROGRESS.md staged implementation record
```

## Privacy and release

The app has no publisher-controlled account, analytics or vault backend. OCR runs
on-device. User-selected exports contain encrypted vault data; external document
providers apply their own storage and privacy terms.

Before publication, replace placeholders in `docs/PRIVACY_POLICY.md`, review SDK
privacy practices and complete `docs/GOOGLE_PLAY_RELEASE_CHECKLIST.md`.

## License and third-party code

The application source license must be chosen by the project owner before publication.
Third-party notices and obligations are listed in `docs/THIRD_PARTY_NOTICES.md`.
