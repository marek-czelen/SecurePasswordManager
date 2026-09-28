# Code guide

## Application flow

`MainActivity` owns screens, biometric prompts, item dialogs and auto-lock. It delegates
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

`VaultTextDataCodec` serializes text entries and opaque media references only. Images,
thumbnails and files use independent immutable AES-256-GCM objects. HKDF-SHA-256
derives a domain-separated key from the DEK, vault ID, object ID, generation, type and
schema. AAD authenticates that context; each object generation uses a fresh ID, key and
96-bit nonce.

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
tests on a device with hardware-backed strong-biometric Keystore before release.
