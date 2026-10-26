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
- [ ] Create/activate the one-time in-app product `pro_unlock` as an ad-removal purchase.
  All vault features must remain available without buying it.
- [ ] Add Google accounts as license testers, publish an internal testing release, and
  install it from the Play Store opt-in link. Test purchase, acknowledgement, restore,
  pending purchase and offline cached access with a Play test payment method; test
  purchases do not charge the tester.
- [ ] Confirm restored purchases are queried from Play after reinstall/device transfer;
  the local ad-free cache is device-local and is cleared after a successful query reports
  neither a purchased nor pending ad-removal item.
- [ ] Create the SecureVault app and unlock-screen banner unit in AdMob. Use the official
  test IDs in debug only; configure production IDs through user-level Gradle properties.
- [ ] Configure an EEA/UK consent message in AdMob Privacy & messaging and verify the
  in-app privacy-options entry point.
- [ ] Keep the Play upload key separate from vault/recovery secrets.

## Technical requirements

- [ ] Confirm `targetSdk` satisfies the current Play target API requirement at submission
  time; this project currently targets API 37.
- [ ] Run release lint, unit tests, instrumentation tests and a clean release build.
- [ ] Resolve all current lint errors and warnings that affect the release; the latest
  local `lintDebug` run reported 5 errors and 86 warnings (an AppCompat tint attribute
  and missing German/French XLSX strings).
- [ ] Test Android 12–latest on physical devices, including a StrongBox device and a
  device without StrongBox; verify unlock with biometrics and with only the Android
  device PIN/pattern/password configured.
- [ ] Test classic Autofill on Android 12–13 and Credential Manager password retrieval
  on Android 14+, including provider selection, verified browser origins, biometric
  unlock, cancellation and native-app credential selection.
- [ ] Verify 64-bit ARM packaging and any native libraries.
- [ ] Verify the app does not request unnecessary permissions.
- [ ] Verify Android backup, device transfer and app data extraction rules.
- [ ] Check that `FLAG_SECURE` covers passwords, passphrases, QR and OCR review.

## Store listing

- [ ] Set the app title to `SecureVault: Passwords & Docs` and keep it consistent with
  the in-app name.
- [ ] App name, short description, full description and screenshots are truthful.
- [ ] Do not claim “unbreakable”, “anonymous”, or guaranteed plausible deniability.
- [ ] Explain that the app is local-first and that a Recovery QR is a secret.
- [ ] State that all vault features are free, a banner appears only on the unlock screen,
  and the optional one-time purchase removes ads.
- [ ] Explain that unlock requires a secure Android screen credential and hardware-backed
  Android Keystore/KeyMint; biometric enrollment is optional.
- [ ] Provide a support email: `<support email>`.
- [ ] Publish the privacy policy at a stable HTTPS URL: `<privacy policy URL>`.
- [ ] Complete the content rating questionnaire.
- [ ] Complete target audience and children declarations.
- [ ] Complete the app access/reviewer instructions. Provide a disposable test vault and
  test passphrase; never provide production credentials or a real Recovery QR.

## App content and data declarations

- [ ] Complete the Data safety form from the actual release build and SDK behavior.
- [ ] Declare ads in Play Console and review current AdMob/UMP data disclosures for Data
  Safety and the public privacy policy.
- [ ] Declare camera access for user-triggered OCR, image capture, document scanning
  and calibration.
- [ ] Declare local processing of authentication information, passwords, notes and
  user-selected images as applicable to the Play taxonomy.
- [ ] Disclose that a user-selected Autofill/Credential Manager login is passed to the
  requesting app on the device.
- [ ] Explain that a user-selected automatic-backup destination is refreshed after
  successful saves and receives encrypted vault bytes; individual attachment and CSV
  exports instead write plaintext when the user explicitly requests them.
- [ ] Declare ML Kit and other SDK behavior based on their current documentation.
- [ ] Provide an in-app privacy policy link.
- [ ] Provide a clear way to delete the local vault and all local app data.
- [ ] If an account/backend is added later, add account deletion both in-app and through
  an external web resource as required by Play policy.

## Permissions and disclosure

The camera permission must be requested only immediately before a user-triggered camera
flow (OCR, image capture, document scanning or calibration), after an in-app explanation.
The app must not request camera access at startup.
There is no location, contacts or microphone permission. AdMob may use advertising
identifiers for ads according to user consent; verify behavior against the configured
SDK and consent message. No background ad requests are made outside the unlock screen.

Official references:

- [Google Play User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Data safety and declarations](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Prominent disclosure and consent](https://support.google.com/googleplay/android-developer/answer/11150561)
- [Play Console app content](https://support.google.com/googleplay/android-developer/answer/9859455)
