# VLT3 security design

## Scope and threat model

The attacker may know the source, APK and VLT3 backup, and may inspect clear container
framing. The design assumes the attacker does not have both the passphrase and the
independent 256-bit Recovery QR secret, and does not control the original device's
hardware-backed Android Keystore key. A rooted or instrumented process can read plaintext
after unlock and is outside the protection boundary.

VLT2 is not read or migrated. If an old `vault.vlt` is present without a VLT3 vault, the
application reports that it is unsupported instead of silently creating an empty vault.

## Key hierarchy and unlock

- Setup creates a random 256-bit DEK and independent random 256-bit Recovery QR secret.
- Argon2id derives a passphrase contribution; HKDF-SHA-256 combines that contribution
  and the QR secret to derive the AES-GCM DEK wrapper key.
- There is no standalone passphrase/PIN verifier. A copied header cannot verify a
  passphrase without the independent QR secret.
- The header also contains an authentication-bound Android Keystore RSA wrapper for the
  same DEK. Daily unlock requires `BiometricPrompt` and a hardware-backed key.
- Each restore creates a new hardware key ID and rewraps the same DEK; it does not
  re-encrypt the manifest, text block or attachments.
- Changing the main passphrase creates a fresh passphrase wrapper around the same DEK.

Header AAD binds container/header schema versions, vault ID, Argon2id parameters and
salt, wrap salt, QR ID, hardware key ID and wrapper generation. The hardware alias is
derived from the vault ID and random hardware key ID.

## Independent internal versions

The container, header, manifest, text-data block, encrypted objects and backup each have
their own schema/version field. VLT3 container format version is 3; initial internal
schemas are version 1. A decoder rejects unknown versions and bounded-length violations.

## Storage layout and confidentiality

The private app directory contains:

```text
vault-v3/
  header.bin
  manifest.current
  manifest.previous
  objects/<random-256-bit-id>.obj
```

The encrypted manifest references one text-block object and an inventory of image,
thumbnail and file objects. The inventory contains opaque IDs, types, lengths and
SHA-256 digests of ciphertext. Entry names, MIME types and references are inside
encrypted data. Filesystem object names contain no user names or plaintext metadata.

The single encrypted text block contains up to 10,000 entries, stable entry IDs, titles,
logins, passwords, URLs, notes, TOTP, folders, tags, favorites, up to five distinct
previous passwords per login, and attachment references. It is padded to 4 KiB
boundaries and capped at 32 MiB. It never contains image or file bytes. Images and
document pages are separate JPG objects up to 10 MiB each; thumbnails are up to 256 KiB,
and files are up to 10 MiB. A scanned document may contain up to 20 pages and 60 MiB
total. The active vault is capped at 100 MiB. The first version does not chunk
attachments.

Each immutable object generation has a fresh random 256-bit object ID and generation ID,
a distinct HKDF-SHA-256-derived key, and a random 96-bit AES-GCM nonce. A derived key is
used for exactly one encryption. AAD binds the vault ID, object type, object/generation
IDs, schema version, owner entry ID and plaintext length. Manifest encryption also uses
a fresh generation ID, separated HKDF info and AAD.

The authenticated manifest hash over ciphertext allows streaming backup and ciphertext
corruption checks without decrypting media. It does not replace the AES-GCM tag. Media is
not read or hashed to render the list: its ciphertext hash and GCM tag are checked when
the resource is opened or copied to a backup. Missing files and malformed object headers
are detected when opening the vault.

## Copy-on-write and recovery

Writes create new immutable objects and a new text block. Every staged file is synced and
renamed into the object store before the next manifest is written. The previous current
manifest is durably copied to `manifest.previous`; atomic replacement of
`manifest.current` is the transaction commit point. Old objects remain reachable through
the current or previous manifest until garbage collection proves they are unreachable.

Startup authenticates the current manifest and text block and checks referenced object
presence/length. If current is damaged or incomplete, a valid previous generation may be
opened with an explicit warning. The user must confirm repair before current is replaced.
Per-resource corruption is checked lazily; when possible, a matching resource from the
previous generation is returned with a warning. Garbage collection validates current
and previous generations and preserves objects pinned by an in-progress backup.

Initial vault restore is staged as a complete sibling directory. The old directory is
renamed to a recoverable sibling before the staged directory is renamed to the active
path. Startup restores the prior directory if a crash occurs between those renames.
External SAF providers do not offer a universal atomic-replace operation. Export first
builds and validates a complete encrypted temporary backup locally; an interrupted
provider copy may leave an incomplete external file, which import rejects.

## Backup and recovery

The VLT3 backup container is one file containing the wrapped header, encrypted manifest
and all ciphertext objects reachable from one captured manifest generation. Export
streams objects one at a time and verifies their ciphertext hashes; it does not decrypt
images or files. Import stages all objects, validates the authenticated manifest and
text block, verifies ciphertext hashes, and rewraps the same DEK for the new device.
Recovery always requires both the passphrase and Recovery QR. Manual export and recovery
are not gated by Free/Pro entitlement.

## Limitations

Header fields, object count and approximate object sizes are visible in the local object
store/backup framing. Random IDs hide names but not those structural metadata. VLT3 does
not prevent deliberate rollback to an older valid backup. Java strings and Android UI
objects cannot guarantee complete plaintext zeroization. No application can protect
plaintext from a compromised process after unlock.

## Verification

JVM tests cover header/KDF/AAD, manifest authentication, object framing and tampering,
text limits/round-trips, backup round-trip, copy-on-write root switching and orphan
collection. Android instrumentation tests cover AtomicFile, image processing and the
Keystore-backed repository, but must be run on a physical device with a hardware-backed
strong-biometric key before release.
