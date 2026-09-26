# ROLA

Jesteś zespołem ekspertów składającym się z:

* kryptografa,
* Android Security Engineer,
* eksperta Android Keystore / KeyMint / StrongBox,
* projektanta bezpiecznych aplikacji mobilnych,
* eksperta UX/UI Android,
* threat modelera,
* penetration testera / red teamera.

Twoim zadaniem jest zaprojektowanie od podstaw bardzo bezpiecznego menedżera haseł dla Androida.

Nie zakładaj, że moje założenia są poprawne. Każde założenie dotyczące bezpieczeństwa Androida, kryptografii, Keystore, biometrII lub odzyskiwania danych zweryfikuj technicznie.

Jeżeli jakiś element mojego projektu jest niemożliwy, niebezpieczny albo daje fałszywe poczucie bezpieczeństwa — wskaż to i zaproponuj poprawne rozwiązanie.

Nie wymyślaj własnych algorytmów kryptograficznych. Korzystaj ze standardowych, sprawdzonych i audytowanych prymitywów kryptograficznych.

---

# 1. CEL APLIKACJI

Zaprojektuj aplikację typu password manager dla Androida.

Najważniejszym wymaganiem jest możliwość przechowywania zaszyfrowanego magazynu haseł w miejscu, które może być publicznie dostępne.

Przykłady:

* Google Drive,
* Dropbox,
* OneDrive,
* NAS,
* publiczny serwer,
* dowolny katalog współdzielony,
* backup,
* skopiowany plik na komputerze.

Sam plik magazynu nie może umożliwiać odtworzenia haseł bez odpowiednich sekretów i kluczy.

Przykładowy plik:

`vault.vlt`

Może być całkowicie dostępny dla atakującego.

---

# 2. GŁÓWNY MODEL DOSTĘPU

Aplikacja ma posiadać dwa logiczne światy danych:

## TRUE DATABASE

Prawdziwa baza użytkownika.

Dostęp:

`TRUE PIN → TRUE DATABASE`

## FAKE DATABASE

Wiarygodna baza danych przeznaczona do sytuacji przymusu.

Dostęp:

`FAKE PIN → FAKE DATABASE`

oraz:

`BIOMETRIA → FAKE DATABASE`

Aplikacja nigdy nie powinna informować użytkownika, że znajduje się w FAKE DATABASE.

Interfejs obu trybów powinien wyglądać możliwie identycznie.

---

# 3. TRUE PIN

Podczas pierwszej konfiguracji użytkownik ustala:

`TRUE PIN`

PIN nie może być przechowywany w postaci jawnej.

Zaprojektuj odpowiedni mechanizm:

`TRUE PIN → KDF → cryptographic key/material`

Uwzględnij odpowiedni współczesny KDF odporny na ataki offline, np. Argon2id lub odpowiednią alternatywę dostępną i bezpieczną na Androidzie.

Określ:

* parametry KDF,
* salt,
* sposób przechowywania parametrów,
* sposób weryfikacji PIN,
* ochronę przed brute-force,
* rate limiting,
* blokady,
* zachowanie po wielu błędnych próbach.

Nie zakładaj, że sam PIN jest wystarczającym kluczem szyfrującym.

---

# 4. FAKE PIN

FAKE PIN jest tworzony na podstawie TRUE PIN.

Reguła:

każda cyfra TRUE PIN zostaje zwiększona o 1, z zawijaniem:

`0 → 1`
`1 → 2`
...
`8 → 9`
`9 → 0`

Przykład:

TRUE PIN:

`583914`

FAKE PIN:

`694025`

FAKE PIN ma otwierać wyłącznie FAKE DATABASE.

Nie wolno przechowywać w aplikacji jawnej informacji:

`FAKE PIN = TRUE PIN + 1`

Zaprojektuj sposób przygotowania niezależnych materiałów kryptograficznych tak, aby później nie było konieczne przechowywanie tej zależności.

Przeanalizuj również, czy sama reguła PIN+1 nie powoduje dodatkowych problemów bezpieczeństwa.

---

# 5. BIOMETRIA

Biometria ma być obsługiwana przez standardowe mechanizmy Androida, przede wszystkim:

* BiometricPrompt,
* Android Keystore,
* KeyMint,
* TEE,
* StrongBox, jeśli dostępny.

WAŻNE:

Nie zakładaj, że aplikacja może dowiedzieć się, który konkretny palec został użyty.

Standardowe Android Biometric APIs nie powinny być traktowane jako mechanizm:

`finger 1 = TRUE`
`finger 2 = FAKE`

Nie stosuj więc identyfikacji konkretnego palca.

W tym projekcie obowiązuje:

`BIOMETRIA → FAKE DATABASE`

Biometria ma być wygodnym mechanizmem dostępu do świata FAKE.

TRUE DATABASE wymaga TRUE PIN.

Zaprojektuj dokładnie, w jaki sposób Android Keystore może zostać wykorzystany do ochrony kluczy i w jaki sposób uwierzytelnienie biometryczne może autoryzować użycie klucza.

Wyraźnie rozdziel:

* co gwarantuje Android,
* co gwarantuje hardware,
* co jest tylko założeniem aplikacji.

---

# 6. DEK – DATA ENCRYPTION KEY

Wygeneruj losowy:

`DEK = 256-bit random key`

DEK powinien być niezależny od PIN-u.

DEK szyfruje właściwe dane magazynu.

Schemat powinien wyglądać koncepcyjnie:

`Random DEK`
↓
`Encrypt vault`

a następnie:

`DEK`
↓
`protected/wrapped by device hardware key`

Nie przechowuj DEK jawnie.

---

# 7. ANDROID KEYSTORE / HARDWARE SECURITY

Zaprojektuj wykorzystanie Android Keystore.

Preferowane rozwiązanie:

`Android Keystore`
+
`hardware-backed key`
+
`StrongBox`, jeżeli urządzenie ją obsługuje.

Przeanalizuj:

* hardware-backed keys,
* TEE,
* StrongBox,
* KeyMint,
* non-exportable keys,
* authentication-bound keys,
* user authentication requirements,
* biometric authentication,
* invalidation po zmianie danych biometrycznych,
* zachowanie po restarcie urządzenia,
* zachowanie po factory reset,
* różnice między urządzeniami.

Celem jest, aby skopiowanie:

`vault.vlt`

na inne urządzenie nie wystarczyło do normalnego odszyfrowania magazynu.

---

# 8. FORMAT MAGAZYNU

Zaprojektuj szczegółowy format:

`vault.vlt`

Powinien zawierać odpowiednio:

* version,
* salt,
* nonce/IV,
* KDF parameters,
* encrypted key material,
* encrypted vault,
* authentication tag,
* metadata niezbędne do odtworzenia magazynu.

Nie ujawniaj niepotrzebnie:

* liczby prawdziwych wpisów,
* liczby fałszywych wpisów,
* rozmiaru TRUE DATABASE,
* rozmiaru FAKE DATABASE,
* struktury wskazującej, która część jest prawdziwa.

Zaprojektuj padding oraz strukturę danych w taki sposób, aby analiza pliku utrudniała rozróżnienie TRUE/FAKE.

Przeanalizuj również wycieki metadanych:

* rozmiar,
* liczba rekordów,
* czas modyfikacji,
* kolejność rekordów,
* access patterns,
* cache,
* backup,
* logi.

---

# 9. KRYPTOGRAFIA

Nie projektuj własnego algorytmu.

Rozważ:

* AES-256-GCM,
* XChaCha20-Poly1305,
* HKDF,
* Argon2id,
* SHA-256/SHA-512 tylko tam, gdzie ma to uzasadnienie,
* CSPRNG Androida.

Dla każdego zastosowanego mechanizmu podaj:

1. dlaczego jest używany,
2. jaki klucz wykorzystuje,
3. jakie dane chroni,
4. jak przechowywany jest nonce/salt,
5. jak wygląda rotacja klucza.

Zaprojektuj hierarchię kluczy.

---

# 10. TRUE / FAKE STORAGE

Zaprojektuj system tak, aby:

TRUE DATABASE i FAKE DATABASE

nie były łatwe do rozróżnienia na podstawie:

* struktury pliku,
* liczby rekordów,
* rozmiaru,
* kolejności danych,
* metadanych,
* czasu zapisu,
* identyfikatorów rekordów,
* wzorców szyfrowania.

Jeżeli to możliwe, wykorzystaj wspólną strukturę zaszyfrowanego magazynu.

Rozważ również:

* random padding,
* stałe rozmiary bloków,
* dummy records,
* dummy metadata,
* losowe identyfikatory,
* equalized database size.

---

# 11. FAKE DATABASE

FAKE DATABASE nie może być pusta ani oczywiście sztuczna.

Powinna wyglądać jak prawdziwy password manager.

Przykładowe wpisy:

* Google,
* Microsoft,
* Facebook,
* Amazon,
* Netflix,
* Allegro,
* e-mail,
* konto pracy,
* sklep internetowy,
* forum,
* konto społecznościowe,
* Wi-Fi,
* przykładowe konto bankowe,
* notatki,
* kody,
* adresy.

Dane powinny być realistyczne, ale oczywiście nie mogą zawierać prawdziwych danych użytkownika.

Rozważ możliwość generowania części FAKE DATABASE deterministycznie na podstawie specjalnego seed/materialu kryptograficznego.

Zaprojektuj mechanizm, dzięki któremu FAKE DATABASE może wyglądać wiarygodnie również po wielu miesiącach użytkowania.

---

# 12. RECOVERY QR

Podczas tworzenia magazynu aplikacja generuje:

`RECOVERY QR`

Recovery QR służy do odzyskania magazynu na nowym urządzeniu.

WAŻNE:

Nie twórz osobnego Recovery Password.

TRUE PIN jest jedynym sekretem użytkownika potrzebnym do recovery.

Schemat:

`RECOVERY QR + TRUE PIN → recovery key → DEK → vault`

Recovery QR nie powinien zawierać jawnego DEK.

Zaprojektuj:

`DEK → encrypted/wrapped recovery representation`

gdzie materiał odzyskiwania jest chroniony kluczem wyprowadzonym z TRUE PIN.

Określ dokładny format QR.

Rozważ:

* version,
* vault identifier,
* KDF salt,
* KDF parameters,
* encrypted DEK,
* nonce,
* authentication tag,
* checksum,
* anti-tampering.

---

# 13. RECOVERY NA NOWYM URZĄDZENIU

Przypadek:

Użytkownik traci telefon.

Na nowym telefonie:

1. instaluje aplikację,
2. skanuje Recovery QR,
3. wpisuje TRUE PIN,
4. aplikacja odzyskuje DEK,
5. odszyfrowuje vault,
6. generuje nowy hardware-backed key,
7. ponownie owija DEK nowym kluczem urządzenia.

Stary hardware key nie powinien być kopiowany.

Zaprojektuj dokładny workflow.

---

# 14. ZMIANA TRUE PIN

Zaprojektuj bezpieczną zmianę TRUE PIN.

Zmiana PIN nie powinna wymagać ponownego szyfrowania całej bazy, jeżeli nie jest to konieczne.

Rozważ:

`TRUE PIN → KDF → key wrapping key → wrapped DEK`

Dzięki temu zmiana PIN może polegać na ponownym owinięciu DEK.

Zaprojektuj także zmianę FAKE PIN zgodnie z ustaloną regułą.

---

# 15. THREAT MODEL

Przeprowadź szczegółową analizę zagrożeń.

Uwzględnij co najmniej:

### A. Atakujący posiada tylko vault.vlt

Czy może:

* brute-force PIN?
* odzyskać DEK?
* wykryć TRUE/FAKE?
* określić liczbę wpisów?
* określić strukturę danych?

### B. Atakujący posiada vault.vlt + Recovery QR

Czy może:

* odzyskać DEK?
* przeprowadzić offline brute-force?
* wykryć poprawność PIN-u?

### C. Atakujący ma telefon

Uwzględnij:

* locked device,
* unlocked device,
* root,
* custom ROM,
* malware,
* debugger,
* Frida,
* memory dump,
* filesystem extraction.

### D. Atakujący ma aplikację/APK

Czy może:

* reverse engineerować APK?
* znaleźć stałe,
* znaleźć format vault,
* znaleźć algorytmy,
* znaleźć fake database,
* znaleźć informacje o TRUE/FAKE?

### E. Atakujący kontroluje aplikację podczas działania

Przeanalizuj najgorszy przypadek:

* aplikacja została zmodyfikowana,
* urządzenie jest zrootowane,
* attacker może obserwować RAM,
* attacker może hookować funkcje,
* attacker może przechwytywać dane po odszyfrowaniu.

Wyraźnie określ granicę bezpieczeństwa:

`encrypted at rest`

vs.

`application compromised while vault is unlocked`.

---

# 16. PLAUSIBLE DENIABILITY

Przeanalizuj, czy projekt rzeczywiście zapewnia plausible deniability.

Nie wystarczy napisać:

"FAKE DATABASE jest zaszyfrowana."

Sprawdź, czy atakujący może wykryć prawdziwą bazę poprzez:

* rozmiar,
* strukturę,
* statystykę ciphertext,
* liczbę rekordów,
* padding,
* metadata,
* klucze,
* KDF,
* timing,
* różnice w odpowiedzi aplikacji,
* błędy,
* cache,
* logi,
* backupy.

Spróbuj zaatakować projekt jako red team.

---

# 17. BIOMETRIA JAKO DUress ACCESS

Zaprojektuj dokładnie scenariusz:

Użytkownik zostaje zmuszony do odblokowania aplikacji.

Używa:

`BIOMETRIA`

Aplikacja otwiera:

`FAKE DATABASE`

UI nie może wyświetlić:

* "Fake mode",
* "Duress mode",
* "Limited mode",
* żadnego ostrzeżenia.

Aplikacja powinna zachowywać się jak normalny password manager.

Przeanalizuj również, czy obserwator może wykryć różnicę między:

`TRUE PIN`

a

`FAKE PIN`

lub:

`BIOMETRIA`.

---

# 18. UI / UX

Interfejs ma być:

* nowoczesny,
* elegancki,
* minimalistyczny,
* bardzo ergonomiczny,
* szybki,
* czytelny,
* intuicyjny,
* zgodny ze współczesnymi standardami Android,
* zaprojektowany przede wszystkim pod telefon.

Preferowany styl:

* Material 3,
* dynamiczne kolory Androida, jeśli pasują do projektu,
* odpowiedni dark mode,
* jasny mode,
* duże i czytelne elementy interfejsu,
* odpowiednie odstępy,
* wygodna obsługa jedną ręką,
* sensowna hierarchia informacji,
* subtelne animacje,
* brak zbędnych efektów.

Aplikacja ma wyglądać jak profesjonalny produkt komercyjny, a nie projekt demonstracyjny.

Nie przeładowuj interfejsu.

Bezpieczeństwo ma być niewidoczne dla użytkownika tam, gdzie nie jest potrzebne.

---

# 19. EKRANY APLIKACJI

Zaprojektuj co najmniej:

### Ekran 1 – First Run

* utworzenie magazynu,
* TRUE PIN,
* potwierdzenie PIN,
* informacje bezpieczeństwa,
* utworzenie Recovery QR.

### Ekran 2 – Unlock

Bardzo prosty ekran:

* logo,
* PIN,
* przycisk odblokowania,
* przycisk biometrii,
* opcja recovery.

Nie informuj użytkownika o istnieniu FAKE DATABASE w sposób widoczny na ekranie logowania.

### Ekran 3 – Password List

* wyszukiwarka,
* kategorie,
* ulubione,
* ostatnio używane,
* lista wpisów.

### Ekran 4 – Password Details

* nazwa,
* login,
* password,
* URL,
* notatki,
* tagi,
* data modyfikacji.

Hasło powinno być domyślnie ukryte.

### Ekran 5 – Add Password

Ergonomiczny formularz.

### Ekran 6 – Generator Password

* długość,
* wielkie litery,
* małe litery,
* cyfry,
* symbole,
* generowanie CSPRNG.

### Ekran 7 – Recovery

* skanowanie QR,
* wpisanie TRUE PIN,
* proces odzyskiwania.

### Ekran 8 – Settings

* bezpieczeństwo,
* biometria,
* auto-lock,
* clipboard timeout,
* backup/recovery,
* zmiana PIN,
* eksport/import,
* informacje o urządzeniu.

---

# 20. AUTOFILL

Rozważ implementację Android Autofill Framework.

Przeanalizuj:

* AutofillService,
* credential provider,
* Android Credential Manager,
* password autofill,
* phishing resistance,
* identyfikację domen,
* ograniczenie dostępu innych aplikacji.

Zaproponuj bezpieczny sposób integracji.

---

# 21. CLIPBOARD

Hasła kopiowane do clipboardu są potencjalnym źródłem wycieku.

Zaprojektuj:

* automatyczne czyszczenie clipboardu,
* możliwie krótki timeout,
* ograniczenie logowania,
* brak przechowywania haseł w historii,
* odpowiednie zachowanie dla Android 13+.

---

# 22. SCREENSHOT / SCREEN RECORDING

Przeanalizuj zastosowanie:

`FLAG_SECURE`

dla ekranów zawierających:

* hasła,
* Recovery QR,
* dane wrażliwe.

Określ kompromisy UX.

---

# 23. PAMIĘĆ

Przeanalizuj problem:

`RAM`

na Android/JVM/Kotlin.

Uwzględnij:

* String,
* ByteArray,
* immutable objects,
* garbage collector,
* zeroization,
* kopiowanie danych,
* logcat,
* crash reports.

Nie twierdź, że można zagwarantować wymazanie sekretu z RAM w JVM.

Wyraźnie określ ograniczenia.

---

# 24. LOGI

Aplikacja nie może logować:

* PIN,
* DEK,
* hasła,
* Recovery Secret,
* plaintext vault,
* kluczy.

Przygotuj politykę:

`NO SENSITIVE DATA IN LOGS`

---

# 25. BACKUP ANDROID

Przeanalizuj Android Auto Backup.

Nie dopuszczaj do przypadkowego umieszczenia:

* DEK,
* wrapped DEK,
* keystore metadata,
* plaintext database,
* PIN-derived secrets

w niezabezpieczonym backupie.

Określ, co może być backupowane, a czego nie.

---

# 26. CLOUD SYNC

Magazyn może być przechowywany w chmurze.

Chmura nie powinna posiadać klucza odszyfrowującego.

Model:

`Cloud = untrusted storage`

Cloud provider może zobaczyć:

`vault.vlt`

ale nie powinien móc odszyfrować zawartości.

Zaprojektuj bezpieczny sync i konflikt resolution.

---

# 27. TESTY

Przygotuj kompletny plan testów.

### Crypto tests

* poprawne szyfrowanie,
* poprawne odszyfrowanie,
* corrupted ciphertext,
* corrupted nonce,
* corrupted tag,
* corrupted metadata,
* wrong PIN,
* wrong QR.

### Security tests

* brute force,
* replay,
* tampering,
* downgrade,
* rollback,
* malformed vault,
* malformed QR.

### Android tests

* locked device,
* reboot,
* biometric lockout,
* biometric enrollment change,
* factory reset,
* no StrongBox,
* no hardware-backed Keystore.

### Penetration tests

* JADX,
* apktool,
* Frida,
* objection,
* root,
* memory inspection,
* filesystem extraction.

---

# 28. RED TEAM – OBOWIĄZKOWE

Po zaprojektowaniu systemu przestań być jego projektantem.

Stań się atakującym.

Spróbuj odpowiedzieć:

> "Jak mogę zdobyć TRUE DATABASE?"

Mając kolejno:

1. tylko `vault.vlt`,
2. `vault.vlt + APK`,
3. `vault.vlt + Recovery QR`,
4. telefon,
5. telefon + root,
6. telefon + odblokowaną aplikację,
7. kopię backupu,
8. dostęp do chmury,
9. możliwość modyfikacji aplikacji,
10. możliwość monitorowania RAM.

Dla każdego scenariusza:

* opisz atak,
* określ wymagane zasoby,
* określ, czy atak działa,
* określ dlaczego,
* zaproponuj poprawkę.

---

# 29. SECURITY CLAIMS

Na końcu przygotuj tabelę:

| Właściwość | Gwarancja | Założenie | Ograniczenie |
| ---------- | --------- | --------- | ------------ |

Nie używaj stwierdzeń typu:

"system jest niemożliwy do złamania".

Zamiast tego określ precyzyjnie:

> "Przy założeniu X system chroni przed Y."

---

# 30. ARCHITEKTURA PROGRAMISTYCZNA

Zaproponuj architekturę Android:

* Kotlin,
* Jetpack,
* Compose,
* Material 3,
* ViewModel,
* Repository,
* Room lub alternatywa,
* Android Keystore,
* BiometricPrompt,
* Credential Manager,
* Autofill.

Rozdziel:

`UI`
`Domain`
`Crypto`
`Storage`
`Security`
`Recovery`
`Sync`

Crypto powinno być możliwie niezależne od UI.

---

# 31. KOD

Po zaprojektowaniu architektury przygotuj:

1. strukturę projektu,
2. klasy Kotlin,
3. interfejsy,
4. modele danych,
5. crypto layer,
6. key management,
7. vault format,
8. PIN handling,
9. biometric handling,
10. recovery QR,
11. database,
12. UI,
13. tests.

Kod ma być produkcyjny, a nie pseudokod.

Każdy fragment dotyczący bezpieczeństwa powinien zawierać komentarz wyjaśniający:

* przed czym chroni,
* jakie są założenia,
* jakie są ograniczenia.

---

# 32. KOŃCOWY RAPORT

Na początku odpowiedzi przedstaw:

## Architecture Summary

krótki opis całego rozwiązania.

Następnie:

## Threat Model

## Cryptographic Architecture

## Key Hierarchy

## Vault Format

## TRUE / FAKE Architecture

## Biometric Architecture

## Recovery Architecture

## Android Security

## UI/UX Architecture

## Implementation Architecture

## Testing

## Red Team Analysis

## Security Limitations

## Recommended Changes

---

# 33. WAŻNA ZASADA

Nie próbuj bezrefleksyjnie realizować mojego projektu.

Jeżeli znajdziesz błąd w założeniach:

1. wskaż go,
2. wyjaśnij technicznie,
3. pokaż konsekwencje,
4. zaproponuj bezpieczniejszą alternatywę,
5. zaktualizuj architekturę.

Szczególnie dokładnie zweryfikuj:

* Android Keystore,
* StrongBox,
* KeyMint,
* BiometricPrompt,
* hardware-backed keys,
* PIN authentication,
* plausible deniability,
* recovery,
* QR,
* FAKE DATABASE,
* memory security,
* backup,
* root,
* compromised application.

Nie zakładaj możliwości, których Android oficjalnie nie zapewnia.

---

# 34. NAJWAŻNIEJSZY CEL

Końcowy projekt ma łączyć:

**bardzo silną kryptografię**
+
**hardware-backed security**
+
**TRUE PIN**
+
**FAKE PIN**
+
**biometria → FAKE DATABASE**
+
**plausible deniability**
+
**Recovery QR + TRUE PIN**
+
**publiczne przechowywanie zaszyfrowanego vaultu**
+
**nowoczesny, ergonomiczny i atrakcyjny interfejs Android**
+
**prosty UX**
+
**dokładnie zdefiniowany threat model**
+
**red-team security review**

Najważniejsza zasada:

> Bezpieczeństwo ma być silne, ale użytkownik ma mieć wrażenie korzystania z prostego, szybkiego i nowoczesnego password managera.

Najpierw zaprojektuj i zweryfikuj architekturę.

Dopiero po jej zaakceptowaniu przejdź do implementacji.
