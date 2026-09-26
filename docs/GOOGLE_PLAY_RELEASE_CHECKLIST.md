# Google Play publication checklist

This is an engineering checklist, not legal advice. Replace every `<...>` placeholder
before submitting the app.

## Account and signing

- [ ] Verify the developer account identity and organization details in Play Console.
- [ ] Enable Google Play App Signing.
- [ ] Store the upload keystore and recovery material outside the repository.
- [ ] Build and upload an Android App Bundle (`.aab`), not a debug APK.
- [ ] Increment `versionCode` and set a production `versionName`.
- [ ] Confirm the release build is not debuggable and has release signing.
- [ ] Keep the Play upload key separate from vault/recovery secrets.

## Technical requirements

- [ ] Confirm `targetSdk` satisfies the current Play target API requirement at submission
  time; this project currently targets API 37.
- [ ] Run release lint, unit tests, instrumentation tests and a clean release build.
- [ ] Test Android 12–latest on physical devices, including a StrongBox device and a
  device without StrongBox.
- [ ] Verify 64-bit ARM packaging and any native libraries.
- [ ] Verify the app does not request unnecessary permissions.
- [ ] Verify Android backup, device transfer and app data extraction rules.
- [ ] Check that `FLAG_SECURE` covers passwords, passphrases, QR and OCR review.

## Store listing

- [ ] App name, short description, full description and screenshots are truthful.
- [ ] Do not claim “unbreakable”, “anonymous”, or guaranteed plausible deniability.
- [ ] Explain that the app is local-first and that a Recovery QR is a secret.
- [ ] Explain that biometric unlock depends on the device’s Android Keystore/KeyMint
  implementation.
- [ ] Provide a support email: `<support email>`.
- [ ] Publish the privacy policy at a stable HTTPS URL: `<privacy policy URL>`.
- [ ] Complete the content rating questionnaire.
- [ ] Complete target audience and children declarations.
- [ ] Complete the app access/reviewer instructions. Provide a disposable test vault and
  test passphrase; never provide production credentials or a real Recovery QR.

## App content and data declarations

- [ ] Complete the Data safety form from the actual release build and SDK behavior.
- [ ] Declare camera access for user-triggered OCR.
- [ ] Declare local processing of authentication information, passwords, notes and
  user-selected images as applicable to the Play taxonomy.
- [ ] Explain that user-selected exports to Google Drive/Dropbox/OneDrive are initiated
  by the user and contain encrypted vault bytes.
- [ ] Declare ML Kit and other SDK behavior based on their current documentation.
- [ ] Provide an in-app privacy policy link.
- [ ] Provide a clear way to delete the local vault and all local app data.
- [ ] If an account/backend is added later, add account deletion both in-app and through
  an external web resource as required by Play policy.

## Permissions and disclosure

The camera permission must be requested only immediately before user-triggered camera
OCR, after an in-app explanation. The app must not request camera access at startup.
There is no location, contacts, microphone, advertising ID or background tracking.

Official references:

- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Data safety and declarations](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Prominent disclosure and consent](https://support.google.com/googleplay/android-developer/answer/11150561)
- [Play Console app content](https://support.google.com/googleplay/android-developer/answer/9859455)
