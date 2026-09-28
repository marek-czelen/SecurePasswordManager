# VLT3 threat model

## Security assumptions

- The attacker may know the source, APK, VLT3 framing and a copy of the backup.
- The attacker does not have both the passphrase and independent Recovery QR secret.
- The attacker does not control the original device's hardware-backed Keystore key.
- A rooted or instrumented process after unlock is considered compromised.

## Assets

- Random 256-bit DEK.
- Password entries, notes, TOTP secrets, folders, tags and favorites.
- Separately encrypted image, thumbnail and file objects.
- Independent 256-bit Recovery QR secret and passphrase.
- Android Keystore private key and biometric authorization state.

## Desired properties

| Scenario | Required result |
|---|---|
| Source + backup, no passphrase/QR | No DEK recovery or useful standalone passphrase verification |
| Backup + passphrase, no QR | No recovery |
| Backup + QR, no passphrase | No recovery |
| Backup + passphrase + QR | Restore is allowed and creates a local hardware wrapper |
| Original device + strong biometric | Daily unlock is allowed |
| Text edit | Media ciphertext is unchanged |
| Media ciphertext tampering | Hash/tag failure is surfaced; a matching previous resource may be offered |
| Interrupted write | Current or previous complete manifest remains recoverable |
| Rooted device after unlock | Plaintext cannot be guaranteed |

## Exposure boundaries

The encrypted manifest hides entry metadata, names and media references. Object IDs are
random and filenames contain no user data. Container framing still reveals object count
and approximate sizes. Ciphertext SHA-256 values are authenticated by the encrypted
manifest and allow backup copies to be checked without decrypting every media object;
AES-GCM remains the cryptographic authenticity check when data is opened.

The list decrypts the bounded text block, not images or files. Visible image thumbnails
are loaded lazily. A damaged media object is detected on open or backup rather than by
reading all media during list display. Current and previous manifests are both roots for
reachability, so GC must not remove objects needed by either or by an active backup.

## Recovery and rollback

`manifest.current` is the commit point; `manifest.previous` contains the prior complete
state. Fallback to the previous state is reported to the user and editing stays disabled
until the user confirms repair. This is accidental-corruption/crash recovery only, not
protection against an attacker replaying an older valid backup.

External SAF providers do not expose a universal atomic replacement protocol. The app
stages and validates a complete encrypted backup locally before copying it to the
provider. A provider failure during the final copy may leave an incomplete external
document; the VLT3 importer rejects incomplete containers.

## QR handling

RQR2 contains the independent recovery secret and authenticated metadata, never a DEK.
It is shown behind `FLAG_SECURE`, is not logged, and must be stored separately from the
vault backup. The optional passphrase QR contains the passphrase itself and is equivalent
to writing it on paper; it is not sufficient without RQR2.

## Out of scope

VLT2 migration, server synchronization, deliberate rollback prevention and chunked
attachments are not implemented. Generic file attachment creation is Pro-gated; manual
encrypted backup and recovery are not.
