# Free / Pro feature matrix

This document is the product scope for the first monetization version. Security and
recovery are never paywalled.

**Release entitlement note:** Google Play Billing is not implemented. The Free/Pro
switch exists only in debug builds; release builds report Free, so Pro-gated features
listed below are not currently purchasable or available in release builds.

## Implemented today

| Feature | Status | Free | Pro |
|---|---|---:|---:|
| VLT3 hybrid encrypted vault | Implemented | Yes | Yes |
| Argon2id passphrase + independent QR recovery | Implemented | Yes | Yes |
| Strong biometric hardware unlock | Implemented | Yes | Yes |
| Auto-lock and lock-on-background | Implemented | Yes | Yes |
| Password entries | Implemented | Yes | Yes |
| Password history (up to 5 previous distinct passwords) | Implemented | No | Yes; automatic capture on change, view and copy |
| File attachments up to 10 MiB | Implemented | No | Yes |
| Advanced import/export | Implemented | No | CSV/XLSX mapping (including password-protected XLSX), Bitwarden JSON, KeePass XML and 1Password 1PUX import, standard CSV export |
| Favorites, folders, tags and search | Implemented | Yes | Yes |
| Password generator | Implemented | Yes | Yes |
| TOTP codes | Implemented | Yes | Yes |
| Local weak/reused password health | Implemented | Yes | Yes |
| Android Autofill and Credential Manager password provider | Implemented | Yes | Yes |
| Named JPG image entries in the common list | Implemented | Yes, with VLT3 limits | Yes, same VLT3 limits |
| Multi-page JPG document scans | Implemented | No | Yes; up to 20 pages, 10 MiB per page and 60 MiB per document |
| Manual encrypted export/import | Implemented | Yes | Yes |
| User-selected automatic encrypted backup | Implemented | Yes; refreshed after each successful save | Same behavior; no separate scheduling |
| Recovery QR and passphrase QR printing/sharing | Implemented | Yes | Yes |
| OCR review/crop flow | Implemented as optional code path | Not part of password Add flow | Not a paid security feature |
| Polish/English/German/French UI | Implemented | Yes | Yes |
| In-app user guide | Implemented | Yes | Yes |

## Planned Pro features

| Feature                                         | Current status | Proposed Pro behavior                               |
|-------------------------------------------------|---|-----------------------------------------------------|
| Google Play Billing entitlement                 | Not implemented | One-time `pro_upgrade` purchase                     |
| Offline Pro entitlement cache                   | Not implemented | Use the last verified Play purchase locally         |
| Scheduled backup retries                        | Not implemented | Background scheduling and retry policy |
| Passkeys                                        | Not implemented | Android Credential Provider/WebAuthn                |
| Advanced Autofill                               | Not implemented | Advanced save/update workflows; basic password retrieval remains Free |
| Multiple vaults                                 | Not implemented | Separate VLT3 vaults and device bindings            |
| Encrypted sharing                               | Not implemented | Recipient-key encrypted item packages               |
| Emergency access                                | Not implemented | Requires a backend, recipients and approval workflow |
| Breach intelligence                             | Not implemented | Optional privacy-reviewed k-anonymity service       |
| Advanced reports                                | Basic local health exists | History, age and actionable health dashboard        |

## Autofill implementation and constraints

The classic Android `AutofillService` is available from API 26; this app requires API 31
or newer. Android Credential Manager password-provider support is added on API 34 and
newer. Both paths use the local encrypted vault and keep plaintext out of persistent
provider storage. A locked vault returns an authentication action that opens the
hardware-backed biometric unlock flow. Verified browser origins are matched by exact
host against saved entry URLs; subdomains are not treated as matches. Requests without
a verified web origin require biometric authentication and an explicit login choice.
Saving new credentials from forms is not implemented.

The user must enable SecurePasswordManager under Android's additional credential
providers and choose it as the preferred service. On older Android versions, the user
selects it as the classic autofill service. The app cannot silently replace Google
Password Manager or guarantee a suggestion in every password field: Android and each
target app control whether and how credential suggestions appear. After selection,
Android supplies the username and password to the requesting app.

Implementation reference: [Android Autofill services](https://developer.android.com/identity/autofill/autofill-services).

## Features that stay Free

The following are core trust and safety features and must not depend on Pro:

- encryption and DEK protection;
- passphrase policy;
- biometric unlock;
- Recovery QR creation and recovery;
- manual encrypted export/import;
- vault deletion;
- clipboard protection;
- screenshot protection;
- Android Autofill and Credential Manager password retrieval, including safe site matching;
- accessibility and localization;
- access to existing user data.

## Debug testing

Debug builds expose a **Free / Pro test mode** in Settings. It is guarded by
`BuildConfig.DEBUG` and is absent from release builds. It only changes the entitlement
reported by `PremiumManager`; it never changes cryptographic keys or vault access.

The release entitlement must later be provided by Google Play Billing. A local cached
entitlement is required so already-purchased local features continue to work offline.
