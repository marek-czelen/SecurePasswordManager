# Security audit status

**Status:** engineering review; not an independent penetration test or certification.

## VLT3 controls implemented

- The local UI/repository use VLT3 only. VLT2 is not migrated or silently parsed.
- A random 256-bit DEK remains wrapped by Argon2id(passphrase)+independent Recovery QR
  and by an authentication-bound hardware Keystore key.
- There is no standalone PIN/passphrase verifier and no DEK in Recovery QR.
- Header, manifest, text block, object and backup schemas are independently versioned.
- AES-256-GCM, HKDF-SHA-256, fresh 256-bit object/generation IDs, fresh 96-bit nonces
  and context-bound AAD are used.
- Text records do not contain image/file bytes. Media is separately encrypted and lazy
  loaded. A text-only edit does not encrypt media again.
- Copy-on-write preserves current/previous manifests. Restore stages and validates a
  complete backup before switching the vault directory.
- Manual encrypted backup and Recovery QR+passphrase restore are not gated by Free/Pro.

## Remaining limitations

- SAF providers do not expose a universal atomic replacement operation. The complete
  backup is staged and verified locally before copying; a failure during the final
  provider write may leave an incomplete external document. Import rejects truncation.
- Object count and approximate object sizes are visible in the object store/backup
  framing. No deliberate rollback protection is implemented.
- Media ciphertext hashes are checked on media read and backup, not by eager scanning at
  list display. AES-GCM tags are checked when an object is decrypted.
- Full-zeroization of Java strings, UI objects and provider/bitmap buffers is not
  guaranteed.
- Connected Android tests could not run in the current environment because no device is
  connected. Keystore/biometric/AtomicFile/fsync behavior still needs physical-device
  verification.

## Release gates

- Run `connectedDebugAndroidTest` on a hardware-backed device with strong biometrics.
- Exercise interrupted writes around each fsync/rename and verify current/previous
  recovery.
- Verify recovery and biometric invalidation on a second device and after factory reset.
- Test file/image limits, heap use, lazy list loading and backup on a minimum-supported
  device.
- Verify no plaintext appears in logs, crash reports, screenshots, backups or unintended
  temporary files; complete independent mobile security review before production use.
