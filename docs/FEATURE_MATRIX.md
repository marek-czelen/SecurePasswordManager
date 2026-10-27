# Feature availability and Free/Pro model

The app has no advertisements. Free provides the core vault with quantitative limits;
the optional one-time Google Play product `pro_unlock` upgrades the user to Pro. Pro
entitlement is cached locally for offline use. The existing entitlement preference key
is retained so previous purchasers keep Pro access.

## Implemented today

| Feature | Status | Available without purchase |
|---|---|---:|
| VLT3 hybrid encrypted vault | Implemented | Yes |
| Argon2id passphrase + independent QR recovery | Implemented | Yes |
| Hardware-backed device unlock (strong biometric or Android screen credential) | Implemented | Yes |
| Auto-lock and lock-on-background | Implemented | Yes |
| Password entries and password history | Implemented | Yes; Free retains/shows up to 2 previous passwords per entry, Pro up to 5 |
| File attachments up to 10 MiB | Implemented | Free: 1 file; Pro: no plan count limit |
| Scanned documents | Implemented | Free: 1 document; Pro: no plan count limit |
| Advanced import/export | Implemented | Free: up to 5 entries per operation; Pro: no per-operation plan limit |
| Favorites, folders, tags and search | Implemented | Yes |
| Password generator | Implemented | Yes |
| TOTP codes | Implemented | Yes |
| Local weak/reused password health | Implemented | Yes |
| Android Autofill and Credential Manager password provider | Implemented | Yes |
| Named JPG image entries in the common list | Implemented | Yes, within VLT3 limits |
| Multi-page JPG document scans | Implemented | Yes; plan quota above, plus technical limits of 20 pages, 10 MiB per page and 60 MiB per document |
| Manual encrypted export/import | Implemented | Yes |
| User-selected automatic encrypted backup | Implemented | Yes; refreshed after each successful save |
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

## Features available regardless of purchase

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
- access to existing vault entries and attachments, including content already above
  Free's file/document quotas and password history already above its new-history quota.

The plan quotas affect adding additional files/documents and the number of records in a
single advanced import/export operation. They never delete existing data or limit a full
encrypted vault backup. VLT3 technical caps (such as 10,000 entries and 100 MiB active
vault size) remain in effect for all plans.

## Debug testing

Debug builds expose a **Free / Pro test mode** in Settings. It is guarded by
`BuildConfig.DEBUG` and is absent from release builds. It only changes tier quotas; it
never changes cryptographic keys or vault access.

Release builds use the Play Billing Pro entitlement, not the debug switch. The existing
`pro_unlock` product and locally cached `play_pro` entitlement preserve Pro access for
previous purchasers. A successful Play purchase query clears the cache only if there is
neither a purchased nor a pending item. The cache is not transferred on reinstall, so a
purchase may need to be restored from Play again.
