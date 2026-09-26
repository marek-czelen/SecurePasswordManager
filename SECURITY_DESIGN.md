# Secure Password Manager — security design

> **Current build note (scope change):** The FAKE/duress database has been **disabled** for
> now. The vault contains a **single TRUE database**. Access is granted by the **TRUE PIN**
> or by **successful strong biometric authentication** (both open the same TRUE database).
> The plausible-deniability sections below describe the original two-slot design and are
> retained for reference, but do not reflect the current single-slot build.

## Architecture Summary

The application stores one `vault.vlt` file in private application storage. The file is
intended to be treated as untrusted and may be copied to cloud storage, a NAS or a
computer. It contains two indistinguishable, authenticated encrypted slots. Each slot
has its own random 256-bit DEK, PIN-wrapping representation and padded encrypted
database. The slot order is randomized.

The implemented baseline uses Java because the starter project has no Kotlin/Compose
toolchain. The crypto and repository layers have no UI dependency and can be moved to
Kotlin/Compose without changing the format. The UI uses AppCompat, a secure window,
framework `BiometricPrompt`, Android Keystore and ZXing for recovery QR generation and
scanning.

The important security boundary is:

* `TRUE PIN -> PBKDF2-HMAC-SHA256 -> true wrapping key -> true DEK -> TRUE database`
* `FAKE PIN -> PBKDF2-HMAC-SHA256 -> fake wrapping key -> fake DEK -> FAKE database`
* `strong biometric -> authentication-bound Keystore key -> fake DEK -> FAKE database`
* `recovery QR + TRUE PIN -> PIN-wrapped true DEK -> TRUE database`

The application does not claim that a compromised, rooted device or a modified
application can protect secrets after the vault is unlocked.

## Threat Model

The primary attacker may obtain any public copy of `vault.vlt`, the recovery QR, the APK,
cloud history and backups. A stronger attacker may control a rooted/unlocked device,
hook the application with Frida, inspect process memory, or replace the APK.

The design protects encrypted data at rest against an attacker who does not have a
valid PIN or the original device Keystore key. It does not protect plaintext while the
application is unlocked, nor can it prevent an attacker from observing a user who
voluntarily enters a PIN or supplies biometric authentication.

### Public vault only

The file has random-looking AES-GCM ciphertext, no plaintext PIN, no plaintext DEK and
no Keystore secret. A guessed PIN can be tested offline because the file contains
authenticated PIN-derived slot material. This is unavoidable when recovery must be
`QR + TRUE PIN` and no separate high-entropy secret is allowed. The KDF cost slows
guessing but does not turn a short PIN into a strong password.

### Vault plus APK

The APK reveals algorithms, field layout and the fact that two slots exist. It must not
contain user secrets or the generated fake records. Reverse engineering cannot recover
the DEKs from the public file without a PIN, QR+PIN or device Keystore access.

### Vault plus recovery QR

The QR contains identifiers, KDF parameters and an AES-GCM ciphertext of the true DEK.
It does not contain the DEK or a recovery password. It enables offline PIN guessing, so
the recommended PIN is a long random numeric value rather than a memorable short PIN.

### Device states

* A locked device protects Android Keystore keys according to the device's lock screen,
  KeyMint and hardware implementation.
* An unlocked but uncompromised device still requires the app's TRUE PIN for the true
  slot. The fake biometric key is authentication-bound.
* Root, a custom ROM, a debugger or Frida can bypass application policy, read plaintext
  after decryption and replace the UI.
* A factory reset destroys the original Keystore keys. The vault can only be recovered
  using the QR and TRUE PIN.
* A device without hardware-backed Keystore is supported, but the protection is reduced
  to the platform's software-backed implementation. The app must expose this as a
  security warning in a production settings screen.

## Cryptographic Architecture

| Primitive | Purpose | Key/material | Nonce or salt |
|---|---|---|---|
| AES-256-GCM | Encrypt each database and wrap each DEK | Random DEK or KDF output | Fresh random 96-bit nonce per encryption; nonce is stored beside ciphertext |
| PBKDF2-HMAC-SHA256 | Derive PIN wrapping keys on every Android API supported by the starter | User PIN | Random 128-bit vault salt; 600,000 iterations |
| HMAC-SHA256 | PIN-slot verifier | PIN-derived key | Slot identifier is included in the input |
| HKDF-SHA256 | Available for domain separation when additional subkeys are introduced | High-entropy input key material | Explicit salt and context |
| `SecureRandom` | DEKs, IDs, salts, nonces and padding | OS CSPRNG | N/A |

AES-GCM authentication covers both ciphertext and explicit associated data. Associated
data binds every component to the vault ID, slot ID and purpose, preventing swapping a
valid component into another slot.

PBKDF2 is used instead of claiming an unavailable Android-standard Argon2id API. It is
CPU-hard but not memory-hard. A production release should benchmark a vetted native
Argon2id implementation on representative devices, version its parameters and keep a
PBKDF2 migration path. KDF parameters are authenticated by the slot operations and
stored in the envelope so they can be upgraded without guessing.

## Key Hierarchy

* A random 256-bit true DEK encrypts the true padded database.
* A separate random 256-bit fake DEK encrypts the fake padded database.
* The TRUE PIN derives a true PIN wrapping key; the derived key wraps only the true
  DEK and produces a verifier.
* The derived FAKE PIN derives a separate fake PIN wrapping key; no `PIN + 1` rule is
  serialized in the vault.
* A non-exportable Android Keystore key wraps the true DEK for local device binding.
* An authentication-bound Android Keystore key, preferably StrongBox-backed, wraps the
  fake DEK for biometric access.
* The QR contains only the PIN-encrypted representation of the true DEK.

The local hardware wrappers are useful only with the matching Android Keystore aliases.
Copying `vault.vlt` to another device does not copy those aliases. Recovery creates new
aliases and rewrites the local wrappers.

## Vault Format

`vault.vlt` is a versioned binary envelope:

```text
magic "VLT1"
version
vault_id[16]
kdf_algorithm
kdf_iterations
kdf_salt[16]
slot_count = 2
repeat twice:
    slot_id[16]
    pin_verifier[32]
    length + AES-GCM(pin-wrapped DEK)
    length + AES-GCM(hardware-wrapped DEK)
    length + AES-GCM(padded database)
```

The database plaintext has a version marker, item count, UTF-8 fields and random
padding. Both slots are padded to the same 4096-byte boundary. Updates preserve the
other database and recalculate a common target size. Every component is length-bounded
before allocation to prevent malformed-file memory exhaustion.

Equalized slot sizes and randomized IDs reduce simple structural classification. They
do not hide file modification time, cloud version history, access patterns, application
state, Keystore alias existence, or the fact that the user has two independent
datasets. A production sync layer should use immutable version objects, encrypted
change batches and coarse, padded upload intervals rather than syncing on every edit.

## TRUE / FAKE Architecture

At setup the application derives the fake PIN once using the requested decimal
increment rule and stores only the fake slot verifier and wrapping result. The
relationship is not persisted. `PinPolicy.fakePin()` exists only in the local
transition code needed to change the TRUE PIN or recover a new device.

The requested rule is not a security boundary: anyone who learns the TRUE PIN can
compute the FAKE PIN, and adjacent PINs may collide with a user's familiar PIN
patterns. It is a coercion UX convention, not additional entropy.

The fake database is populated with synthetic, clearly non-user-derived demo records.
It must never be generated from a hard-coded secret in the APK. A mature product
should provide a user-controlled fake dataset and preserve its normal edit history;
deterministic fake content alone would be recognizable across installations.

## Biometric Architecture

The app uses Android framework `BiometricPrompt` with a `Cipher` initialized against
the fake Keystore key. The key requests `AUTH_BIOMETRIC_STRONG`, a short
authentication validity window and invalidation behavior supplied by KeyMint. StrongBox
is requested first and the implementation falls back if the device reports that it is
unavailable.

Android guarantees that the prompt result authorizes use of the supplied cryptographic
operation according to the device implementation. A hardware-backed key is
non-exportable and normally survives app restarts but not a factory reset. Android
does **not** tell this app which finger or face was used. Therefore the application
never maps a particular finger to TRUE or FAKE.

The mapping `biometric -> fake` is application policy. It can be bypassed by a modified
APK on a compromised device, and a user can be coerced into entering the TRUE PIN.
Enrollment changes, biometric lockout, device reboot and OEM differences must be
covered by device tests; the app must fail closed and require the TRUE PIN when the
authentication-bound key is invalidated.

## Recovery Architecture

The QR payload is a URL-safe Base64 encoding of:

```text
magic "RQR1"
version
vault_id[16]
true_slot_id[16]
kdf_algorithm
kdf_iterations
kdf_salt[16]
length + AES-GCM(TRUE PIN-derived key, true DEK)
```

The vault ID, slot ID and purpose are authenticated associated data. A separate
checksum is unnecessary for tampering detection because the AEAD tag rejects modified
payloads; the version and bounded lengths reject malformed input.

Recovery requires both the original `vault.vlt` and the QR payload. The app verifies
that IDs and KDF parameters match, decrypts the true DEK with TRUE PIN, verifies the
slot, derives the fake PIN, creates new Keystore keys and atomically writes a vault
with new hardware wrappers. The old hardware aliases are not exported.

## Android Security

* `FLAG_SECURE` is set for the activity, including PIN, passwords and QR.
* `allowBackup=false`, `backup_rules.xml` and `data_extraction_rules.xml` exclude
  files, databases, preferences and external storage.
* Cleartext network traffic is disabled.
* No PIN, DEK, password, recovery payload or plaintext vault is logged.
* Clipboard and Autofill must not be enabled by default. A production AutofillService
  should use Android Credential Manager, domain-verified associations, explicit user
  confirmation and a short-lived in-memory result.
* Password clipboard copies should use a short timeout and `ClipDescription` sensitive
  metadata on Android 13+. The current baseline does not copy passwords automatically.
* Java `String`, `ByteArray`, immutable UI objects and the Android/JVM garbage collector
  prevent a guarantee of complete RAM zeroization. The code wipes mutable key buffers
  on normal paths, but a rooted process can observe copies or plaintext.

## UI/UX Architecture

Implemented screens and flows are:

* first run with TRUE PIN confirmation and synthetic fake records;
* unlock with PIN or biometric, without exposing a visible fake-mode warning;
* password list, add item, details with hidden password, generator and settings;
* recovery QR display, QR scanning and `vault.vlt` file selection;
* TRUE PIN change and explicit lock.

The current starter UI is a compact AppCompat implementation. A production polish pass
should migrate presentation to Kotlin, Jetpack Compose and Material 3, add accessibility
labels, auto-lock timers, clipboard timeout, domain-aware Autofill and a visible
hardware-backed security status. Those UI changes must not alter the binary vault
contract.

### Added local productivity features

The current TRUE-only build also supports:

* encrypted favorite, folder and tag metadata with local search/filtering;
* local-only password health analysis for weak and reused passwords;
* RFC 6238 TOTP generation from an encrypted Base32 secret, with explicit copy action;
* secure notes remain part of the encrypted entry model.

These analyses run only after unlock in memory. No password, TOTP seed, folder or tag is
sent to a network service. TOTP secrets are stored inside the encrypted vault and are
never logged. Breach-database lookups, Credential Manager autofill, passkeys and sharing
are intentionally deferred until their Android and privacy boundaries are implemented
and tested separately.

## Implementation Architecture

```text
MainActivity
  -> VaultRepository
      -> VaultEnvelope / VaultDataCodec
      -> PinKdf / Crypto
      -> DeviceKeyManager
      -> RecoveryQrPayload
```

The crypto package is UI-independent. `VaultRepository` is the only component that
combines persistence, PIN policy and Android Keystore. `DeviceKeyManager` requests AES
GCM keys from Android Keystore and supplies a decrypt `Cipher` to `BiometricPrompt`.
Writes use a temporary file, `fsync`, then an atomic rename.

## Testing

The included unit tests cover:

* AES-GCM round trip and tamper rejection;
* salted PIN derivation and the requested fake PIN mapping;
* envelope and recovery payload round trips;
* padded database round trips.

Release testing must additionally cover:

* wrong PIN, corrupted magic, salt, KDF parameters, nonce, tag, length and padding;
* slot swapping, replayed old vault versions and downgrade attempts;
* PIN rate limiting and lockout persistence;
* reboot, locked device, biometric lockout and enrollment changes;
* StrongBox present/unavailable, TEE-only and software-backed Keystore;
* factory reset and recovery on a new device;
* Auto Backup and device transfer inspection;
* JADX/apktool, Frida, objection, root, memory inspection and modified APK behavior;
* Android Autofill domain binding, clipboard clearing and screenshot prevention.

## Red Team Analysis

| Scenario | Result | Reason |
|---|---|---|
| `vault.vlt` only | No direct decryption; offline PIN guessing remains possible | No plaintext DEK; PIN verifier and wrapped DEKs are public |
| `vault.vlt + APK` | Same as above | APK describes the format but has no user secrets |
| `vault.vlt + QR` | Offline TRUE PIN guessing | QR is intentionally a PIN-protected recovery transport |
| Original locked phone | Normally protected by lock screen and KeyMint | Keystore keys are non-exportable under supported hardware |
| Phone + root/custom ROM | Assume compromise | Root can hook or replace the application and inspect plaintext |
| Unlocked application | TRUE or FAKE data can be read | Plaintext necessarily exists in process/UI memory |
| Backup copy | Excluded by manifest/rules; verify OEM behavior | Backup implementations and device policies vary |
| Cloud access | Ciphertext and metadata only | Cloud is untrusted storage by design |
| Modified application | Can show false UI or exfiltrate secrets | App integrity is not a hardware-enforced user-intent channel |
| RAM monitoring | Plaintext and transient copies may leak | JVM/Android do not guarantee complete zeroization |

## Security Limitations

1. A numeric PIN has low entropy. The KDF and rate limiting help only in the app; they
   cannot stop an offline attacker who has the file.
2. Plausible deniability is best-effort metadata equalization, not a coercion-proof
   cryptographic guarantee. An attacker may infer use of two slots from the format,
   application behavior, backups, history or a controlled device.
3. Biometric authentication is not finger identification. The fake mapping is policy,
   not an Android guarantee.
4. Hardware-backed Keystore protects local key material, not plaintext after unlock.
5. The current implementation has no remote sync service. Cloud sync must use
   content-addressed encrypted objects, authenticated versions, rollback detection and
   conflict resolution without server-held keys.
6. Java memory handling reduces accidental retention but cannot promise zeroization.

## Recommended Changes

Before a security-sensitive production release:

* migrate the presentation layer to Kotlin/Compose/Material 3 while retaining the
  tested crypto module;
* benchmark and, after review, migrate to a vetted Argon2id implementation;
* add a high-entropy passphrase option, while retaining PIN convenience and clearly
  documenting offline attack resistance;
* add an authenticated, padded sync protocol with rollback/version checks;
* implement Autofill through Credential Manager and verified domains;
* add an auto-lock service, clipboard timeout, crash-report redaction and explicit
  hardware-backed status;
* commission an independent Android/crypto penetration test before claiming
  plausible deniability or coercion resistance.
