# Code guide

This document explains the codebase in the order a new contributor should read it.

## Application flow

`SecureApp` configures Material You. `MainActivity` owns the screens and delegates all
vault operations to `VaultRepository`. The Activity must not implement cryptography itself.

```text
MainActivity
  -> VaultRepository
      -> VaultEnvelope / VaultSlot
      -> PinKdf / Crypto
      -> DeviceKeyManager
      -> RecoveryQrPayload
```

`Session` is an in-memory-only holder used when Android recreates the Activity during
camera/OCR flows. It must never be replaced with persistent storage.

## Cryptographic flow

1. `VaultRepository.create()` asks `SecureRandom` for a DEK and a separate `qrSecret`.
2. `PinKdf` derives a passphrase key with Argon2id.
3. HKDF combines the derived key and `qrSecret`.
4. AES-GCM wraps the DEK and encrypts the padded `VaultData`.
5. `DeviceKeyManager` wraps the same DEK with a hardware-backed, biometric RSA key.
6. `VaultEnvelope` writes only ciphertext and authenticated metadata.

The source code, APK and format are intentionally assumed public. Secrets must therefore
come only from runtime input, Android Keystore or CSPRNG output.

## Recovery flow

`RecoveryQrPayload` contains the independent 256-bit recovery secret and format metadata.
It never contains the DEK. `VaultRepository.restore()` validates the vault and QR metadata,
derives the combined PIN+QR wrapping key, opens the DEK, generates a new device key and
atomically writes a new device wrapper.

## UI security rules

* Keep `FLAG_SECURE` on sensitive activities.
* Do not log PINs, passphrases, QR payloads, DEKs, TOTP seeds or decrypted fields.
* Do not put plaintext secrets in notifications, saved state, databases, analytics or crash
  reports.
* Keep OCR processing local and delete temporary photos after the review flow.
* Copy passwords/TOTP codes only after an explicit user action and clear the clipboard.
* Treat every external URI as untrusted input and validate vault bytes before replacing the
  local file.

## Changing the vault format

Never silently reinterpret an old format. Add a new magic/version, bounded decoder,
authenticated metadata and explicit migration tests. Any migration must authenticate the
old vault before writing a new one and must use an atomic temporary-file replacement.

## Testing a change

Run the smallest relevant checks first:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Security-sensitive changes additionally require tests for wrong PIN, wrong QR, modified
metadata, modified ciphertext, downgrade attempts, corrupted files, biometric key
invalidation and recovery on a fresh device.
