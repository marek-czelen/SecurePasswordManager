# Free / Pro feature matrix

This document is the product scope for the first monetization version. Security and
recovery are never paywalled.

## Implemented today

| Feature | Status | Free | Pro |
|---|---|---:|---:|
| VLT3 hybrid encrypted vault | Implemented | Yes | Yes |
| Argon2id passphrase + independent QR recovery | Implemented | Yes | Yes |
| Strong biometric hardware unlock | Implemented | Yes | Yes |
| Auto-lock and lock-on-background | Implemented | Yes | Yes |
| Password entries | Implemented | Yes | Yes |
| Favorites, folders, tags and search | Implemented | Yes | Yes |
| Password generator | Implemented | Yes | Yes |
| TOTP codes | Implemented | Yes | Yes |
| Local weak/reused password health | Implemented | Yes | Yes |
| Named JPG image entries in the common list | Implemented | Yes, with safe local limits | Yes, expanded limits |
| Manual encrypted export/import | Implemented | Yes | Yes |
| User-selected automatic encrypted backup | Implemented | Basic | Advanced scheduling |
| Recovery QR and passphrase QR printing/sharing | Implemented | Yes | Yes |
| OCR review/crop flow | Implemented as optional code path | Not part of password Add flow | Not a paid security feature |
| Polish/English/German/French UI | Implemented | Yes | Yes |
| In-app user guide | Implemented | Yes | Yes |

## Planned Pro features

| Feature                                         | Current status | Proposed Pro behavior                               |
|-------------------------------------------------|---|-----------------------------------------------------|
| Google Play Billing entitlement                 | Not implemented | One-time `pro_upgrade` purchase                     |
| File attachments up to 10 MiB                   | Implemented | Available to Pro only                               |
| Offline Pro entitlement cache                   | Not implemented | Use the last verified Play purchase locally         |
| Image storage limits                            | Not gated | Higher count/size limits                            |
| Password history                                | Not implemented | Encrypted entry history and restore                 |
| Advanced import                                 | Not implemented | Bitwarden, KeePass, 1Password and CSV import        |
| Advanced export                                 | Basic encrypted vault copy | Additional encrypted interchange formats            |
| Scheduled backups                               | Backup after update exists | WorkManager schedules and retry policy              |
| Passkeys                                        | Not implemented | Android Credential Provider/WebAuthn                |
| Advanced Autofill                               | Not implemented | Credential Manager provider and domain matching     |
| Multiple vaults                                 | Not implemented | Separate VLT2 vaults and device bindings            |
| Encrypted sharing                               | Not implemented | Recipient-key encrypted item packages               |
| Emergency access                                | Not implemented | Requires a backend, recipients and approval workflow |
| Breach intelligence                             | Not implemented | Optional privacy-reviewed k-anonymity service       |
| Advanced reports                                | Basic local health exists | History, age and actionable health dashboard        |

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
- accessibility and localization;
- access to existing user data.

## Debug testing

Debug builds expose a **Free / Pro test mode** in Settings. It is guarded by
`BuildConfig.DEBUG` and is absent from release builds. It only changes the entitlement
reported by `PremiumManager`; it never changes cryptographic keys or vault access.

The release entitlement will later be provided by Google Play Billing. A local cached
entitlement is required so already-purchased local features continue to work offline.
