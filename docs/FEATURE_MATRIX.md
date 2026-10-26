# Feature availability and advertising model

All implemented vault features are available without purchase. The only optional paid
product removes the AdMob banner on the unlock screen. Security, recovery, attachments,
history, import/export and backups are not paywalled.

**Release entitlement note:** Google Play Billing is integrated for a one-time ad-removal
purchase with product ID `pro_unlock`. The purchase is cached locally so the app remains
ad-free offline. Debug builds use Google's test ad IDs and expose an ad-free test switch;
release builds require AdMob app and banner IDs through user-level Gradle properties.
Use Google's test IDs only for internal-test bundles and real IDs for production.

## Implemented today

| Feature | Status | Available without purchase |
|---|---|---:|
| VLT3 hybrid encrypted vault | Implemented | Yes |
| Argon2id passphrase + independent QR recovery | Implemented | Yes |
| Hardware-backed device unlock (strong biometric or Android screen credential) | Implemented | Yes |
| Auto-lock and lock-on-background | Implemented | Yes |
| Password entries and up to 5 previous passwords per entry | Implemented | Yes |
| File attachments up to 10 MiB | Implemented | Yes |
| Advanced import/export | Implemented | CSV/XLSX mapping (including password-protected XLSX), Bitwarden JSON, KeePass XML, 1Password 1PUX import and CSV export |
| Favorites, folders, tags and search | Implemented | Yes |
| Password generator | Implemented | Yes |
| TOTP codes | Implemented | Yes |
| Local weak/reused password health | Implemented | Yes |
| Android Autofill and Credential Manager password provider | Implemented | Yes |
| Named JPG image entries in the common list | Implemented | Yes, within VLT3 limits |
| Multi-page JPG document scans | Implemented | Yes; up to 20 pages, 10 MiB per page and 60 MiB per document |
| Manual encrypted export/import | Implemented | Yes |
| User-selected automatic encrypted backup | Implemented | Yes; refreshed after each successful save |
| Recovery QR and passphrase QR printing/sharing | Implemented | Yes |
| OCR review/crop flow | Implemented as optional code path | Yes |
| Polish/English/German/French UI | Implemented | Yes |
| In-app user guide | Implemented | Yes |
| Unlock-screen AdMob banner | Implemented | Removed by one-time `pro_unlock` purchase |

## Not implemented

| Feature                                         | Current status | Notes |
|-------------------------------------------------|---|-----------------------------------------------------|
| Scheduled backup retries | Not implemented | Background scheduling and retry policy |
| Passkeys | Not implemented | Android Credential Provider/WebAuthn |
| Advanced Autofill | Not implemented | Save/update workflows |
| Multiple vaults | Not implemented | Separate VLT3 vaults and device bindings |
| Encrypted sharing | Not implemented | Recipient-key encrypted item packages |
| Emergency access | Not implemented | Requires a backend, recipients and approval workflow |
| Breach intelligence | Not implemented | Optional privacy-reviewed k-anonymity service |
| Advanced reports | Basic local health exists | History, age and actionable health dashboard |

## Autofill implementation and constraints

The classic Android `AutofillService` is available from API 26; this app requires API 31
or newer. Android Credential Manager password-provider support is added on API 34 and
newer. Both paths use the local encrypted vault and keep plaintext out of persistent
provider storage. A locked vault returns an authentication action that opens the
hardware-backed device-authentication flow. Verified browser origins are matched by exact
host against saved entry URLs; subdomains are not treated as matches. Requests without
a verified web origin require device authentication and an explicit login choice.
Saving new credentials from forms is not implemented.

The user must enable SecurePasswordManager under Android's additional credential
providers and choose it as the preferred service. On older Android versions, the user
selects it as the classic autofill service. The app cannot silently replace Google
Password Manager or guarantee a suggestion in every password field: Android and each
target app control whether and how credential suggestions appear. After selection,
Android supplies the username and password to the requesting app.

Implementation reference: [Android Autofill services](https://developer.android.com/identity/autofill/autofill-services).

## Features available regardless of purchase

These core trust and safety features, along with every other implemented vault feature,
do not depend on the ad-removal purchase:

- encryption and DEK protection;
- passphrase policy;
- device-authenticated unlock;
- Recovery QR creation and recovery;
- manual encrypted export/import;
- vault deletion;
- clipboard protection;
- screenshot protection;
- Android Autofill and Credential Manager password retrieval, including safe site matching;
- accessibility and localization;
- access to existing user data.

## Debug testing

Debug builds expose an **ads / ad-free test mode** in Settings. It is guarded by
`BuildConfig.DEBUG` and is absent from release builds. It only changes whether the unlock
screen banner is shown; it never changes cryptographic keys or vault access.

Release builds use the Play Billing ad-removal entitlement, not the debug switch. The
device-local cache preserves the ad-free state while offline; a successful Play purchase
query clears it if there is neither a purchased nor a pending item. The cache is not
transferred on reinstall, so the purchase must be restored from Play again.
