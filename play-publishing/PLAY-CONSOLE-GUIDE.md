# Google Play publication package

This folder contains draft store copy and generated listing assets for **SecureVault:
Passwords & Docs**. It is a preproduction package, not a completed Play Console
submission. Replace every item marked `TODO` before production.

## Current release values to resolve

| Item | Current value | Required action |
|---|---|---|
| Display name | SecureVault: Passwords & Docs | Use the same title in the main store listing |
| Android application ID | `com.example.securepasswordmanager` | **TODO:** choose the permanent, globally unique ID before the first Play upload. It is set in `app/build.gradle.kts` as `defaultConfig.applicationId`; changing it after publishing creates a different app |
| Version | `1.0` / version code `1` | Increment `versionCode` for every uploaded release |
| Billing product | `pro_unlock` | Create a one-time in-app product with this exact ID in Play Console |
| Support contact | Not provided | **TODO:** enter a monitored support email |
| Privacy contact / publisher | Placeholders remain | **TODO:** replace in `docs/PRIVACY_POLICY.md` |
| Public privacy policy URL | Not provided | **TODO:** host the reviewed policy at a stable HTTPS URL |
| Signing | Not configured here | Configure Play App Signing and keep the upload key outside the repository |

Do not upload a production artifact while the application ID, publisher identity,
support contact, privacy policy URL or store listing placeholders are unresolved.

## Where each item goes in Play Console

1. **Create app / App setup:** set the app name, default language, app/game type and
   free/paid availability. Confirm the permanent package name matches
   `defaultConfig.applicationId`.
2. **Testing → Internal testing:** upload the signed Android App Bundle (`.aab`), add
   testers and publish the internal test release. Install from the Play Store opt-in
   link for realistic Billing tests.
3. **Monetize with Play → Products → One-time products:** create `pro_unlock`, configure
   the price and countries, and activate it before testing purchase/restore.
4. **Grow users → Store presence → Main store listing:** copy the title, short
   description and full description from the matching `store-listing-*.md` file. Upload
   `assets/app-icon-512.png`, `assets/feature-graphic-1024x500.png` and the phone images
   from `assets/screenshots/phone/`.
5. **Store listing → Store listing experiments / translations:** add Polish, English,
   German and French listing text if those locales will be targeted. The app UI contains
   these four locales.
6. **Policy and programs → App content:** complete App access, Ads, Content rating,
   Target audience and Data safety. The app has no account sign-in; provide reviewer
   instructions for creating a disposable local vault. Review `docs/DATA_SAFETY.md`
   against the exact uploaded artifact and SDK disclosures.
7. **Policy and programs → Privacy policy:** enter the public HTTPS URL for the reviewed
   `docs/PRIVACY_POLICY.md`. Replace all legal/publisher/contact placeholders before
   publishing it.
8. **Store settings / App access and contact details:** enter the verified developer,
   support and privacy contacts. Do not use example or personal test credentials.
9. **Production → Countries/regions and release:** choose availability, upload the
   production AAB and complete Play's release declarations.

Console navigation labels may change. Search Play Console for the named form if its
section has moved.

## Screenshot set

Phone screenshots in `assets/screenshots/phone/` must come from the emulator with
synthetic demo data only. Never include a real password, recovery QR, passphrase, email
address or purchase receipt. Review every image at full size and replace any screen that
shows an error, debug control, placeholder or unrelated emulator UI before submission.

## Known preproduction blockers

- Replace the current `com.example.*` application ID before first upload.
- Configure and activate the Play Console one-time product `pro_unlock`.
- Supply the publisher name, support email, privacy contact and public HTTPS policy URL.
- Review the store text and privacy/Data Safety statements against the final release.
- Configure production signing, countries, target audience, content rating and app access.
- The included Play screenshots are listing drafts; check current Play image requirements
  before uploading.
