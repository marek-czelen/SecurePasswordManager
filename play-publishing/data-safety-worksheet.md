# Google Play Data Safety worksheet — draft

This is a preparation worksheet. The final answers must be entered in Play Console based
on the exact release artifact and the current SDK privacy documentation.

## Current release behavior

### Collection by the developer

The current build has no publisher-controlled backend, analytics or telemetry service.
It does not collect passwords, passphrases, QR secrets, TOTP seeds, notes or vault
plaintext. The app does not display advertisements and does not include Google Mobile
Ads or UMP SDKs.

Google Play Billing handles an optional one-time Pro purchase. The app receives the
product ID, purchase state and purchase token for acknowledgement/restore; it stores only
a boolean Pro entitlement in private local preferences and does not send purchase tokens
to a publisher backend. Payment card details are handled by Google Play, not the app.
Review the current Play Billing SDK disclosures when completing the final Data Safety form.

### Local processing

The app processes authentication information, user-created password-manager content
(including encrypted password history), separately encrypted image/document/file
attachments, user-selected images and user-selected password export files locally. When
the user enables Android Autofill or Credential Manager, the provider handles form/credential
requests locally and accesses vault entries only from an already-unlocked session or
after device authentication with biometrics or the Android screen credential. These
values are not sent to the publisher.

### User-initiated external transfer

When the user selects a saved login in an Autofill or Credential Manager suggestion,
Android supplies its username and password to the requesting app. This transfers the
selected plaintext credential to that app on the device; it is not sent to the
publisher by SecureVault. Requests without a verified web origin are matched by app
package and require device authentication and explicit user selection; verified web
origins are matched by exact host. If no entry matches, SecureVault may offer to open
its own entry form, where the user can add an item manually. The provider does not read
or automatically save credentials typed into the requesting app.

The user can explicitly export one complete encrypted `vault.vlt3` container or configure
a document-provider location that is refreshed after each successful vault save. The
location is selected by the user; the application writes the encrypted header, manifest
and object ciphertexts, not vault plaintext. An explicit export of an individual attachment writes
that file's plaintext to the user-selected destination. CSV export writes login
fields, including passwords and TOTP secrets, as unencrypted UTF-8 CSV to a destination
explicitly selected by the user. CSV export excludes images and file attachments. The app
warns that spreadsheet programs may interpret formula-like cell values when opening CSV.

### Camera

Camera is optional and used only for user-triggered image capture, OCR, document scanning
and camera calibration. OCR and calibration inputs are temporary and deleted after use;
confirmed scanned-document pages are retained only as encrypted vault objects. There is
no background camera access.

## Play Console answers to verify

- Does the app collect data? Distinguish developer collection from local processing and
  user-directed provider transfer according to the current Play form definitions.
- Is data encrypted in transit? The app does not send vault plaintext to a publisher
  server; document-provider connections are handled by Android/provider transport.
- Is data encrypted at rest? Yes for the local VLT3 header, manifest, text block and
  separate media objects, and for exported `vault.vlt3` containers.
- Can users request deletion? Provide the local delete-vault flow and explain that
  provider copies are deleted by the user at the provider.
- Is data shared? Review Google Play Billing and each remaining SDK against the final
  dependency versions. The app no longer includes Google Mobile Ads or UMP.

## SDK review required before submission

Review current privacy/data practices for:

- Google ML Kit Text Recognition;
- CameraX and OpenCV;
- Apache POI (XLSX import);
- ZXing Embedded and its transitive dependencies;
- AndroidX and Material libraries;
- AndroidX Credential Manager;
- Bouncy Castle;
- any future passkey or cloud SDK.

Before production, confirm that the public privacy policy and Play Console Data Safety
form describe the final release artifact and its remaining SDK behavior.

Keep a dated export/screenshot of the completed Data Safety form with the release
artifacts.
