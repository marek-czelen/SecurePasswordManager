# Code guide

## Application flow

`MainActivity` owns screens, device-authentication prompts, item dialogs and auto-lock. It delegates
vault persistence to `VaultV3Repository`. `Session` holds decrypted text data and the DEK
only while the UI is unlocked or recreated within the same process.

```text
MainActivity / Session
  -> VaultV3Repository
      -> VaultHeaderV3 / VaultHeaderStore
      -> VaultManifest / VaultManifestStore
      -> VaultObjectStore / VaultTextDataCodec
      -> VaultBackupV3
      -> Crypto / VaultV3KeyDerivation / PinKdf
      -> DeviceKeyManager
```

## Storage and cryptography

The header preserves the existing random DEK, Argon2id passphrase contribution,
independent Recovery QR secret and Android Keystore wrapper. Passphrase changes rewrap
the same DEK. The Recovery QR never contains the DEK, and recovery requires both QR and
passphrase.

Passphrase QR export verifies the entered phrase by unwrapping the current DEK with the
in-memory Recovery QR secret; it does not persist the passphrase. If that secret is absent
after a device-authenticated process restart, the user must authenticate once to unlock the local
Keystore-wrapped Recovery QR before the passphrase can be verified. Failed passphrase
checks use the same local rate limit as vault recovery.

`VaultTextDataCodec` serializes text entries and opaque media references only. Images,
thumbnails, scanned-document pages and files use independent immutable AES-256-GCM objects.
Scanned documents are groups of up to 20 JPG page objects, with limits of 10 MiB per
page and 60 MiB per document. HKDF-SHA-256
derives a domain-separated key from the DEK, vault ID, object ID, generation, type and
schema. AAD authenticates that context; each object generation uses a fresh ID, key and
96-bit nonce.

The encrypted text block stores each login's password history, capped at five distinct
previous passwords and updated whenever the password changes. This is available without
purchase. Text schema version 2 adds this field; schema version 1 remains readable and
migrates with an empty history. History values are viewable and copyable but cannot be
restored from the UI.

`AdvancedImportExport` parses CSV and XLSX worksheets for user-selected column mapping,
plus Bitwarden JSON, KeePass XML and 1Password 1PUX exports. Password-protected XLSX files
are supported after the user enters the workbook password; encrypted password-manager
database formats are not. Imports are size-bounded, previewed, and appended as new entries
through `saveData`; 1Password attachments are not imported. Raw imports are limited to
20 MiB, archive expansion is capped, and imports cannot exceed the vault's 10,000-entry
limit. Free accounts import/export at most five entries per advanced operation; Pro has
no per-operation quota. CSV export uses the fixed
`name, username, password, url, notes, folder, tags, totp` column order and writes plaintext
only to a user-selected SAF destination. Images and file attachments are not represented in
this CSV schema.

`PremiumManager` supplies the Free/Pro entitlement. `GooglePlayBillingManager` handles
the one-time `pro_unlock` Play product, purchase acknowledgement and restore queries from
Settings. Its locally cached `play_pro` entitlement remains compatible with prior
versions and works offline. Free permits one file, one scanned document, two previous
passwords per entry and five entries per advanced import/export operation. Pro removes
the file, document and import/export quotas and retains up to five previous passwords
per entry. Existing history and attachments above a Free quota remain accessible and are
not trimmed during the tier change. These plan limits do not change the VLT3 technical
size/count caps. The Free/Pro override remains debug-only. Linking an automatic backup
location is not paywalled: the app writes an encrypted copy after each successful vault
save, without a scheduled background job.

## Persistence and failure handling

`VaultV3Repository` coordinates copy-on-write. New objects are synced before a new
manifest is written. `manifest.previous` is durable before atomic replacement of
`manifest.current`; current replacement is the transaction commit point. Garbage
collection marks resources reachable from current, previous and in-progress backup
snapshots.

`VaultBackupV3` writes a single framed file by streaming the encrypted header, manifest
and referenced object records. Restore validates the manifest and object ciphertext
hashes in a staging directory, validates the text block, rewraps the same DEK for a new
device key, then switches the staged vault directory.

Passphrase QR creation verifies the entered passphrase by unwrapping the current DEK with
the independent Recovery QR secret. The app never persists the passphrase for QR display;
after a device-authenticated process restart it can load the local Recovery QR secret from its
Keystore wrapper, then verify the manually entered passphrase.

## UI security rules

- Keep `FLAG_SECURE` on sensitive activities.
- Do not log passphrases, QR payloads, DEKs, TOTP seeds or decrypted entry values.
- Do not persist plaintext media; files are exported only after explicit user action.
- Keep OCR on-device and clean temporary capture files.
- Clipboard operations remain explicit and clear on timeout.
- Treat SAF inputs as untrusted and enforce size limits before committing.

## Tests

JVM tests cover KDF/AEAD, header and manifest tampering, object framing, text limits,
backup round-trip, root-switch failure recovery and orphan collection. Android tests
cover AtomicFile, bitmap processing and hardware-backed repository flows. Run connected
tests on devices with hardware-backed Keystore, using both biometric and device-credential
authentication before release.
