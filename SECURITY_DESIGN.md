# Secure Password Manager — VLT2 security design

## Scope and threat model

The vault and APK are public to the attacker. The attacker may copy `vault.vlt`, backups,
the source and the binary, but is assumed not to have the user's passphrase, RQR2 QR
secret, or the original device's hardware-backed Keystore key. A rooted or modified
device can read plaintext after unlock and is outside this design's protection boundary.

The previous VLT1/two-slot/one-slot formats are **not** VLT2. They are recognized as
legacy and rejected by the VLT2 repository with an explicit migration error; they are
never silently reinterpreted with new cryptographic semantics. A future migration must
open the legacy PIN, authenticate its data, generate a fresh RQR2 and atomically write
VLT2. Until that flow is implemented, users must export/recreate rather than risk data
loss.

## VLT2 key hierarchy

* A random 256-bit DEK encrypts the padded TRUE database with AES-256-GCM.
* Setup generates an independent random 256-bit `qrSecret`. RQR2 contains that secret
  and metadata; it never contains a DEK or a DEK-encrypted blob.
* The passphrase is 12–24 printable ASCII characters and cannot be numeric-only.
  Argon2id(passphrase, `pinSalt`, calibrated memory/time/parallelism) produces a
  256-bit password contribution.
* `HKDF-SHA256(Argon2id output || qrSecret, wrapSalt,
  "SPM/VLT2/combined-kek")` produces the combined KEK. The KEK AES-GCM-wraps the DEK.
  There is no serialized standalone PIN verifier, so a copied vault cannot test a
  passphrase without also having the independent QR secret.
* `qrId = SHA-256(qrSecret)` is stored in the vault. Vault metadata, KDF parameters,
  salts, version and slot identifier are included in every VLT2 AES-GCM AAD value.
* Device setup creates a non-exportable RSA-3072 (or compatible RSA-2048 fallback)
  Android Keystore key. StrongBox is attempted first. The private key requires
  `AUTH_BIOMETRIC_STRONG` for every decrypt operation; its public key wraps the DEK
  without authentication. No unauthenticated fallback is accepted for VLT2.

## Binary formats

VLT2 starts with `VLT2` and version 2, followed by:

```text
vaultId[16]
Argon2id algorithm, memory, time, parallelism, pinSalt[16]
wrapSalt[16]
qrId[32]
slotId[16]
length + AES-GCM(combined-KEK, DEK, VLT2 metadata AAD)
length + RSA-OAEP(device public key, DEK)
length + AES-GCM(DEK, padded VaultData, VLT2 metadata AAD)
```

RQR2 is URL-safe unpadded Base64 with bounded input. It contains version, `vaultId`,
the 256-bit random `qrSecret`, `qrId`, Argon2 parameters/salt, `wrapSalt`, and a
SHA-256 checksum over those fields. It has no DEK and no encrypted DEK. The QR window
uses `FLAG_SECURE`, and QR text/secrets are never logged.

An optional RPN1 passphrase QR is a separate printable transport. It contains the
passphrase itself, so the UI warns that it is equivalent to writing the passphrase on
paper. RPN1 and RQR2 must be printed/stored separately; neither one alone is sufficient
to open VLT2.

## Workflows

* **Initial setup:** require the new passphrase and generate/display RQR2. The device
  wrapper is generated atomically with VLT2.
* **Daily unlock:** strong biometric decrypts the device wrapper and opens the same
  TRUE vault. PIN-only unlock is intentionally unavailable.
* **New device/rebind:** scan RQR2, enter the passphrase, validate all authenticated
  metadata, unwrap the DEK, verify the padded database, generate a new biometric key,
  and atomically replace only the device wrapper. The same process recovers after
  biometric enrollment invalidation or factory reset.
* **Backup/import/export:** only validated VLT2 bytes are accepted. Temporary files are
  fsynced before atomic replacement. External storage is treated as untrusted.

## Limitations and operational guidance

Hardware-backed Keystore protects key material and biometric gating, not plaintext in a
compromised process. Java strings and Android UI objects cannot guarantee zeroization.
Argon2id parameters are versioned and should be calibrated and reviewed on target
devices. QR and vault backups must be stored separately: possession of both still
permits offline passphrase guessing, while possession of only one does not.

The fake/duress database is disabled. OCR, encrypted backups, import/export, TOTP,
favorites/folders/tags, password health, auto-lock and Material UI remain application
features over the single TRUE database.

## Tests

Unit tests cover deterministic Argon2id/HKDF derivation, wrong-input separation,
AES-GCM tamper rejection, RQR2/VLT2 round trips, legacy VLT1 recognition, and padded
data. Device tests should additionally cover StrongBox/TEE behavior, biometric
invalidation, factory reset, rollback/downgrade, malformed lengths and atomic recovery.
