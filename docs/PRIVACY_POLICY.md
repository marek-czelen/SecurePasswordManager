# Privacy policy — draft

**Effective date:** `<date>`  
**Publisher:** `<legal entity or person>`  
**Contact:** `<privacy email>`  
**Policy URL:** `<HTTPS URL>`

This draft must be reviewed and published at a stable HTTPS URL before a Google Play
submission. Replace placeholders and adapt it to the final release and legal entity.

## What the app does

SecurePasswordManager is a local-first password manager. It encrypts the user’s vault
on the device. The publisher does not receive the user’s passwords, passphrases,
Recovery QR secrets, TOTP seeds, notes or vault plaintext.

## Data processed

The app may process the following data locally at the user’s direction:

- login names, passwords, URLs, notes, folders, tags and TOTP secrets;
- a passphrase used to derive encryption material;
- a Recovery QR containing a random recovery secret;
- user-selected photos or camera images for OCR;
- encrypted `vault.vlt` bytes selected for backup/export/import.

This data is sensitive. It is stored in the encrypted local vault and is not used for
advertising, profiling or sale.

## Camera and OCR

Camera access is requested only when the user chooses camera OCR. The OCR review and
recognition are performed on the device. Temporary camera images are kept in app cache
only for the review flow and are deleted after it completes. The app does not upload
OCR images.

## External backups

The user may explicitly select an external document provider such as Google Drive,
Dropbox, OneDrive or local storage through Android’s Storage Access Framework. The app
writes encrypted vault bytes only. The selected provider processes that file under its
own terms and privacy policy. The publisher does not receive the plaintext vault key.

## Biometrics and Keystore

Biometric authentication is handled by Android `BiometricPrompt` and Android Keystore/
KeyMint. The app does not receive a fingerprint, face image or identity of the specific
biometric used. It receives only authorization to use the device-bound key.

## Sharing and retention

The app does not operate a publisher-controlled account, analytics service, advertising
service or server-side vault. Local data remains until the user deletes the vault,
clears app data or uninstalls the app. External backup copies remain wherever the user
saved them and must be deleted by the user.

## Security limitations

No application can protect plaintext on a rooted or fully compromised device after the
vault is unlocked. Users must protect the passphrase and Recovery QR separately.

## User rights and contact

For privacy questions or requests, contact `<privacy email>`. Since the current build
has no publisher-controlled account or cloud database, local deletion is performed
inside Android Settings or by the app’s local vault deletion flow. Any future backend
must add a formal account/data deletion process before release.
