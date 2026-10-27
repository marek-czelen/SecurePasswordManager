# VLT3 implementation status

**Review date:** 2026-10-26

**Scope:** current application source, not a security certification.

## Current state

VLT3 is the only vault format used by the application. VLT2 files are not opened or
migrated; users must export data from the previous app version before creating a VLT3
vault. Core vault, backup and recovery code is implemented. Hardware-specific behavior
and release readiness still need verification.

## Implemented

- Versioned VLT3 header, manifest, text block, encrypted objects and backup container.
- Random DEK protected by the passphrase plus independent Recovery QR, and by a
  hardware-backed, strong-biometric Android Keystore key.
- Separately encrypted text, JPG images, thumbnails, scanned-document pages and files.
- Copy-on-write persistence using `manifest.current` as the commit point and
  `manifest.previous` for crash/corruption recovery.
- One-file encrypted backup/restore, import validation, passphrase changes and Recovery QR
  flows.
- Password entries, history, folders, tags, favorites, search, TOTP and local password
  health checks.
- Classic Android Autofill and Android Credential Manager password retrieval.
- CSV/XLSX, Bitwarden JSON, KeePass XML and 1Password 1PUX import; CSV export.
- On-device OCR review, camera-based image capture and multi-page document scanning.
- User-selected automatic encrypted backup location, refreshed after successful saves.

## Technical limits and Free/Pro model

- 10,000 entries; 32 MiB text block; 100 MiB active vault.
- JPG images and document pages: 10 MiB each; thumbnails: 256 KiB; file attachment:
  10 MiB. A scanned document is limited to 20 pages and 60 MiB total.
- Free allows one file, one scanned document, two previous passwords per entry and five
  entries per advanced import/export operation. Pro removes the file/document count and
  import/export per-operation quotas, and retains up to five previous passwords per
  entry. The existing one-time `pro_unlock` Google Play product remains the Pro purchase.
- Advertising, AdMob and UMP are not included.
- No WorkManager schedule/retry for automatic backup; a selected destination is refreshed
  after a successful vault save.
- No VLT2 migration, cloud vault backend, multiple vaults, passkeys or rollback prevention.

## Validation and remaining release work

- `testDebugUnitTest` completed successfully; Gradle reported the task up-to-date.
- The latest `lintDebug` run failed with 5 errors and 86 warnings: an AppCompat tint
  attribute and missing German/French XLSX translations.
- Run Android instrumentation tests on physical devices, including verification of
  biometric/Keystore behavior, camera flows, interrupted writes, SAF providers and
  restore after device change.
- Configure and verify release signing, replace privacy-policy placeholders and example
  URL, review SDK disclosures, and complete an independent security assessment before
  production use.

See [Implementation audit](IMPLEMENTATION_AUDIT.md), [Security audit](SECURITY_AUDIT.md)
and [release checklist](GOOGLE_PLAY_RELEASE_CHECKLIST.md) for details.
