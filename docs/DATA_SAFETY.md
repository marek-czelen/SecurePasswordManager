# Google Play Data Safety worksheet — draft

This is a preparation worksheet. The final answers must be entered in Play Console based
on the exact release artifact and the current SDK privacy documentation.

## Current release behavior

### Collection by the developer

The current build has no publisher-controlled backend, analytics, advertising or
telemetry service. It does not collect passwords, passphrases, QR secrets, TOTP seeds,
notes or vault plaintext.

### Local processing

The app processes authentication information, user-created password-manager content,
separately encrypted file attachments and user-selected images locally. These values are
not sent to the publisher.

### User-initiated external transfer

The user can explicitly export or automatically copy one complete encrypted `vault.vlt3`
container to a document provider. This is user-initiated transfer to the provider
selected by the user; the application writes the encrypted header, manifest and object
ciphertexts, not vault plaintext. An explicit export of an individual attachment writes
that file's plaintext to the user-selected destination.

### Camera

Camera is optional and used only for the user-triggered OCR flow. Photos are temporary
inputs and are deleted after review. There is no background camera access.

## Play Console answers to verify

- Does the app collect data? Distinguish developer collection from local processing and
  user-directed provider transfer according to the current Play form definitions.
- Is data encrypted in transit? The app does not send vault plaintext to a publisher
  server; document-provider connections are handled by Android/provider transport.
- Is data encrypted at rest? Yes for the local VLT3 header, manifest, text block and
  separate media objects, and for exported `vault.vlt3` containers.
- Can users request deletion? Provide the local delete-vault flow and explain that
  provider copies are deleted by the user at the provider.
- Is data shared? Declare any actual third-party SDK collection according to the final
  dependency versions. Do not assume “on device” without reviewing each SDK.

## SDK review required before submission

Review current privacy/data practices for:

- Google ML Kit Text Recognition;
- ZXing Embedded and its transitive dependencies;
- AndroidX and Material libraries;
- Bouncy Castle;
- any future Credential Manager, passkey or cloud SDK.

Keep a dated export/screenshot of the completed Data Safety form with the release
artifacts.
