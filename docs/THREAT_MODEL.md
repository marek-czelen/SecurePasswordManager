# VLT2 threat model

## Security assumptions

- The attacker may know the complete source code, APK and binary vault format.
- The attacker may copy `vault.vlt`, cloud backups and the public Recovery QR format.
- The attacker does not know both the user passphrase and the current Recovery QR.
- A rooted/unlocked device with a hooked process is considered compromised after unlock.

## Assets

- 256-bit DEK;
- password entries, notes, TOTP seeds and metadata;
- 256-bit QR recovery secret;
- Android Keystore private key;
- user passphrase.

## Desired properties

| Scenario | Required result |
|---|---|
| Source + vault, no PIN/QR | No DEK recovery or useful PIN verification |
| Vault + PIN, no QR | No recovery |
| Vault + QR, no PIN | No recovery |
| Vault + PIN + QR | Recovery is allowed |
| Original device + strong biometric | Daily local unlock is allowed |
| New device + PIN + QR | New hardware wrapper is created |
| Rooted device after unlock | Plaintext cannot be guaranteed |

## Why source disclosure is safe at rest

VLT2 uses public algorithms and runtime-generated secrets. The source contains no DEK,
QR secret, passphrase or private Keystore key. Knowledge of the derivation code does not
provide the missing runtime inputs. This is an assumption about encrypted-at-rest
security only; it is not protection against a modified app observing user input.

## QR handling

The QR is a high-value secret. It must be shown behind `FLAG_SECURE`, must not be logged,
must not be placed in clipboard history automatically and should be stored separately
from the vault file. A replacement QR requires the current PIN and current QR.

The optional passphrase QR is even more direct: it contains the passphrase itself in a
printable transport format. It is equivalent to writing the passphrase on paper. The
application warns about this and keeps the passphrase QR separate from RQR2. Possession
of only the passphrase QR still does not open VLT2 because the independent RQR2 secret is
also required.
