# Security audit

**Status:** engineering audit; not a certification or independent penetration test.

## Scope

The audit covered vault format and migration, PIN/passphrase derivation, QR recovery,
Android Keystore/biometrics, backups/SAF, OCR, clipboard, screenshots, auto-lock,
logging, dependencies and source-disclosure assumptions.

## Finding resolved by VLT2

The former format serialized a PIN verifier and allowed `vault.vlt + short PIN` to be
tested and opened offline. Local rate limiting did not protect a copied file.

VLT2 removes the standalone verifier. The DEK wrapper is derived from:

```text
Argon2id(passphrase) + independent 256-bit qrSecret
```

The QR secret is not in the vault. Therefore a copied VLT2 file without the PIN and QR
cannot be tested against PIN guesses in a useful way. A user who exposes both the vault
and QR still needs a strong passphrase; the minimum policy is 12 printable characters,
with 16+ recommended.

## Remaining limitations

* A fully compromised unlocked/rooted device can observe plaintext and user input.
* A photographed or copied Recovery QR is a secret and must be protected.
* A weak, predictable 12-character passphrase remains weak despite Argon2id.
* Hardware-backed biometric protection varies by OEM; VLT2 fails closed when no
  hardware-backed strong-biometric key can be created.
* OCR is best-effort printed-text recognition, not a handwriting-security guarantee.
* The project has not undergone an independent mobile penetration test.

## Release blockers

Before production release:

* test VLT2 recovery on at least one StrongBox device and one TEE-only device;
* test biometric enrollment change, factory reset, reboot and Keystore invalidation;
* test import/export and corrupted external files;
* run JADX/apktool/Frida tests against a debug and release build;
* verify no secret appears in Logcat, crash reports, screenshots, clipboard history or
  Android backups;
* review all third-party licenses and the final dependency tree.
