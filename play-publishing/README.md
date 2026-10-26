# Materiały SecureVault do Google Play

Pakiet przedprodukcyjny dla aplikacji **SecureVault: Passwords & Docs**. Zawiera
robocze opisy sklepu i grafiki; przed publikacją trzeba uzupełnić dane wydawcy oraz
konfigurację Play Console. Szczegółowa mapa pól znajduje się w
[`PLAY-CONSOLE-GUIDE.md`](PLAY-CONSOLE-GUIDE.md).

## Zawartość

| Plik lub folder | Zawartość |
|---|---|
| `PLAY-CONSOLE-GUIDE.md` | Gdzie w Play Console wpisać poszczególne dane i jakie są blokery |
| `app-access-instructions.md` | Instrukcja dostępu dla recenzenta Google Play |
| `store-listing-pl.md` | Polski tytuł, krótki i pełny opis |
| `store-listing-en.md` | Angielski tytuł, krótki i pełny opis |
| `store-listing-de.md` | Niemiecki tytuł, krótki i pełny opis |
| `store-listing-fr.md` | Francuski tytuł, krótki i pełny opis |
| `privacy-policy-draft.md` | Kopia robocza do kontroli zgodności z opublikowaną polityką |
| `data-safety-worksheet.md` | Arkusz przygotowawczy Data Safety, nie są to finalne odpowiedzi prawne |
| `third-party-notices-draft.md` | Lista zależności do przeglądu przed wydaniem |
| `assets/app-icon-512.png` | Ikona aplikacji do Google Play |
| `assets/feature-graphic-1024x500.png` | Grafika główna oferty |
| `assets/screenshots/phone/` | Zrzuty PL i EN z emulatora z syntetycznymi danymi |

## Przed przesłaniem do Google Play

1. **Potwierdź stały identyfikator aplikacji.** Aktualny `applicationId` to
   `com.cretorix.securepasswordmanager` w `app/build.gradle.kts` → `android` →
   `defaultConfig`. Sprawdź, czy jest unikalny i docelowy przed pierwszym przesłaniem
   do Play. Po utworzeniu aplikacji nie można go zmienić. `namespace` jest osobną
   przestrzenią nazw kodu i zasobów.
2. **Skonfiguruj podpis i AAB.** Włącz Play App Signing, przechowuj klucz uploadu poza
   repozytorium, zwiększaj `versionCode` w `app/build.gradle.kts` przy każdym wydaniu
   i przesyłaj podpisany plik `.aab`, nie debug APK.
3. **Skonfiguruj AdMob i Billing.** Utwórz aplikację SecureVault i jednostkę banerową
   w AdMob, skonfiguruj komunikat zgody EOG/UK, a identyfikatory produkcyjne ustaw w
   user-level `%USERPROFILE%\.gradle\gradle.properties` jako `ADMOB_APP_ID` i
   `ADMOB_UNLOCK_BANNER_ID`. W Play Console ustaw `pro_unlock` jako jednorazowy zakup
   usunięcia reklam; zakup/przywracanie sprawdź jako licencjonowany tester.
4. **Potwierdź dane wydawcy i politykę.** Opublikowana polityka jest pod adresem
   `https://www.cretorix.eu/securevault-privacy.html`; według wydawcy zawiera nazwę
   Cretorix, datę 1 października 2023 r. i kontakt `talk@cretorix.eu`. Potwierdź, że
   te dane są aktualne, a następnie podaj osobny, monitorowany kontakt pomocy w Play
   Console.
5. **Uzupełnij deklaracje Play.** Zaznacz, że aplikacja zawiera reklamy. Zweryfikuj
   Data Safety względem finalnego AAB i
   ujawnionych praktyk SDK; wypełnij formularze Ads, Content rating, Target audience,
   App access, kraje dostępności i pozostałe wymagane sekcje. Instrukcja App access
   znajduje się w `app-access-instructions.md`.
6. **Przetestuj uwierzytelnianie na sprzęcie.** Na fizycznym urządzeniu z hardware-backed
   KeyMint sprawdź nowy sejf bez skonfigurowanej biometrii, ale z PIN-em/wzorem/hasłem
   blokady Androida, oraz odblokowanie silną biometrią. Starsze sejfy testowe zachowują
   klucze tylko-biometryczne.
7. **Sprawdź grafiki oferty.** Dostępne są po 4 zrzuty 1080×1920 dla PL i EN. Zrzuty
   lub grafiki DE/FR przygotuj osobno, jeśli te wersje językowe będą kierowane do
   odbiorców. Nie przesyłaj prawdziwego Recovery QR ani danych produkcyjnych.
