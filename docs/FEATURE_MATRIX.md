# Feature availability and free model

The app is completely free, with no advertisements, in-app purchases, subscriptions or
feature tiers. Every implemented feature is available without payment.

## Implemented today

| Feature | Status | Included at no cost |
|---|---|---:|
| VLT3 hybrid encrypted vault | Implemented | Yes |
| Argon2id passphrase + independent QR recovery | Implemented | Yes |
| Hardware-backed device unlock (strong biometric or Android screen credential) | Implemented | Yes |
| Auto-lock and lock-on-background | Implemented | Yes |
| Password entries and password history | Implemented | Yes; up to 5 previous passwords per entry (format limit) |
| File attachments up to 10 MiB | Implemented | Yes; no plan count limit |
| Scanned documents | Implemented | Yes; no plan count limit |
| Advanced import/export | Implemented | Yes; no plan per-operation limit |
| Favorites, folders, tags and search | Implemented | Yes |
| Password generator | Implemented | Yes |
| TOTP codes | Implemented | Yes |
| Local weak/reused password health | Implemented | Yes |
| Android Autofill and Credential Manager password provider | Implemented | Yes |
| Named JPG image entries in the common list | Implemented | Yes, within VLT3 limits |
| Multi-page JPG document scans | Implemented | Yes; technical limits of 20 pages, 10 MiB per page and 60 MiB per document |
| Manual encrypted export/import | Implemented | Yes |
| User-selected automatic encrypted backup and on-open synchronization | Implemented | Refreshes after successful saves; sync merges newer entries and retains local-only entries |
| Recovery QR and passphrase QR printing/sharing | Implemented | Yes |
| OCR review/crop flow | Implemented as optional code path | Yes |
| Polish/English/German/French UI | Implemented | Yes |
| In-app user guide | Implemented | Yes |
| Advertising | Removed | No ads SDK or advertising requests |

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

## Technical limits

There are no plan-based quotas or payment gates. Technical VLT3/input safeguards still
apply, including 10,000 entries, a 100 MiB active vault, 10 MiB per attachment/page,
20 pages and 60 MiB per scanned document. These format and safety caps are not pricing
tiers.
