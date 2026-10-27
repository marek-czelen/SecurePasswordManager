# Privacy policy — draft

**Published policy URL:** https://www.cretorix.eu/securevault-privacy.html

**Published contact:** talk@cretorix.eu

This file is a working copy for release review, not a substitute for checking the live
page. The publisher confirms the live page identifies Cretorix and has the effective
date 1 October 2023. Keep this copy synchronized with the live policy and the final Play
Console Data Safety declaration.

## What the app does

SecureVault: Passwords & Docs is a local-first encrypted vault for passwords, documents
and files. It encrypts the user’s vault on the device. The publisher does not receive
the user’s passwords, passphrases, Recovery QR secrets, TOTP seeds, notes or vault
plaintext.

## Data processed

The app may process the following data locally at the user’s direction:

- login names, passwords, URLs, notes, folders, tags and TOTP secrets;
- user-selected image, multi-page document and file attachments and their display metadata;
- a passphrase used to derive encryption material;
- a Recovery QR containing a random recovery secret;
- user-selected photos or camera images for OCR, image entries or document scans;
- encrypted `vault.vlt3` containers selected for backup/export/import.

This data is sensitive. It is stored in the encrypted local vault and is not used for
advertising, profiling or sale.

## Advertising

The app does not display advertisements and does not include Google Mobile Ads or Google
UMP SDKs. It does not collect advertising identifiers or use vault data for advertising
or profiling.

## Camera and OCR

Camera access is requested only after a user starts a camera-based flow: OCR/photo
capture, document scanning, or camera calibration. OCR review and recognition are
performed on the device. Temporary capture and calibration images are deleted after
processing; confirmed document scans are stored as encrypted JPG page objects in the
vault. Images selected for an image entry are cropped before being encrypted into the
vault. The app does not upload camera, gallery or OCR images.

## External backups

The user may explicitly select an external document provider such as Google Drive,
Dropbox, OneDrive or local storage through Android’s Storage Access Framework. The app
writes encrypted VLT3 containers for backup. If the user configures an automatic backup
location, it is refreshed after each successful vault save; the provider receives
ciphertext, not the vault plaintext. If the user explicitly exports an individual
attachment, the selected destination receives that file's plaintext. The
selected provider processes stored files under its own terms and privacy policy. The
publisher does not receive the vault key. CSV export is a separate user-initiated
action that writes login fields, including passwords and TOTP secrets, as plaintext to a
user-selected destination. CSV, XLSX (including password-protected workbooks when the
user supplies the password), Bitwarden JSON, KeePass XML and 1Password 1PUX imports are
parsed locally and are not transmitted by the app. Imported 1Password attachments are
not retained.

## Device authentication and Keystore

Authentication is handled by Android `BiometricPrompt` and Android Keystore/KeyMint.
The user can authenticate with strong biometrics or the device's secure screen credential
(PIN, pattern or password). The app does not receive the device credential, a fingerprint,
face image or the identity of the specific biometric used. It receives only authorization
to use the device-bound key. A secure Android screen lock is required; biometric
enrollment is optional.

## Google Play purchases

The optional Pro entitlement is sold as a one-time purchase through Google Play Billing.
Google Play handles payment processing; the app does not receive payment card details.
The app processes the purchased product ID, purchase state and purchase token on-device
to acknowledge and restore the purchase. It stores only a device-local Pro entitlement
flag for offline access; the token is not sent to a
publisher-controlled server. When Settings is opened, the app queries Google Play to
reconcile the cached entitlement.

## Android Autofill and Credential Manager

If the user enables SecureVault as an Android Autofill or Credential Manager provider,
Android may send the provider a form structure or credential request. The provider
processes it and the encrypted vault locally. Verified browser origins are matched
against saved entry URLs by exact host; requests without a verified web origin can be
matched to a saved Android app package. Requests without a verified web origin require
device authentication and explicit entry selection. After the user selects a
credential, Android supplies its username and password to the requesting app, which
receives that plaintext and handles it under its own privacy policy. SecureVault does
not read or automatically save values typed into another app. If no entry matches,
SecureVault may offer to open its own entry form; the user enters any new credentials
there manually. The provider does not send vault data to the publisher. Provider
selection can be changed in Android settings.

## Sharing and retention

The app does not operate a publisher-controlled account, analytics service or
server-side vault. Local vault data remains until the user deletes the vault, clears app
data or uninstalls the app. External backup copies remain wherever the user saved them
and must be deleted by the user.

## Security limitations

No application can protect plaintext on a rooted or fully compromised device after the
vault is unlocked. Users must protect the passphrase and Recovery QR separately.

## User rights and contact

For privacy questions or requests, contact `<privacy email>`. Since the current build
has no publisher-controlled account or cloud database, local deletion is performed
inside Android Settings or by the app’s local vault deletion flow. Any future backend
must add a formal account/data deletion process before release.
