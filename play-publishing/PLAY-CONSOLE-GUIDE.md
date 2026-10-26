# Pakiet publikacyjny Google Play

Ten folder zawiera robocze teksty sklepu i grafiki aplikacji **SecureVault: Passwords &
Docs**. To pakiet przedprodukcyjny, a nie ukończone zgłoszenie w Play Console. Przed
publikacją uzupełnij wszystkie pozycje oznaczone jako `TODO`.

Aplikacja odblokowuje sejf silną biometrią lub blokadą ekranu Androida (PIN, wzór albo
hasło). Wymagana jest bezpieczna blokada ekranu; skonfigurowanie biometrii jest
opcjonalne.

## Aktualne wartości do potwierdzenia

| Pozycja | Aktualna wartość | Co zrobić |
|---|---|---|
| Nazwa wyświetlana | SecureVault: Passwords & Docs | Użyć tej samej nazwy w głównej ofercie sklepu |
| Android application ID | `com.cretorix.securepasswordmanager` | Potwierdzić, że to stały, unikalny identyfikator przed pierwszym przesłaniem AAB. Wartość znajduje się w `app/build.gradle.kts` w `defaultConfig.applicationId`; po publikacji zmiana oznacza osobną aplikację |
| Wersja | `1.2` / kod wersji `3` | Zwiększać `versionCode` przy każdym kolejnym przesłaniu |
| Produkt Billing | `pro_unlock` | Jednorazowy zakup za 1 EUR, który usuwa reklamy |
| Model płatności | Aplikacja bezpłatna z reklamami i opcjonalnym zakupem ich usunięcia | Wybrać bezpłatną dystrybucję; ustawić cenę usunięcia reklam w zakupie w aplikacji |
| Kategoria | Nieustawiona | Rekomendacja: Produktywność; potwierdzić właściwą kategorię dostępną w Play |
| Kontakt pomocy | Niepotwierdzony | **TODO:** podać monitorowany adres e-mail pomocy |
| Kontakt prywatności | `talk@cretorix.eu` | Potwierdzić, że jest monitorowany i aktualny |
| Wydawca / data polityki | Cretorix / 1 października 2023 r. (potwierdzone przez wydawcę) | Upewnić się, że te dane nadal są aktualne |
| Publiczny URL polityki | `https://www.cretorix.eu/securevault-privacy.html` | Działa i jest używany w aplikacji; potwierdzić ten sam URL w Play Console |
| Podpisywanie | Nie skonfigurowano w repozytorium | Włączyć Play App Signing i przechowywać klucz uploadu poza repozytorium |

Nie przesyłaj wydania produkcyjnego, dopóki nie zostaną ustalone: stały application ID,
tożsamość wydawcy, kontakty, publiczny URL polityki prywatności i treści oferty sklepu.

## Jak utworzyć identyfikatory AdMob

1. Zaloguj się na [admob.google.com](https://admob.google.com/) kontem Google i utwórz
   konto AdMob.
2. W AdMob wybierz **Apps → Add app → Android**. Ponieważ SecureVault nie jest jeszcze
   opublikowany w Google Play, wybierz, że aplikacja nie została opublikowana/listowana.
   Nazwa może być `SecureVault`; identyfikator pakietu musi być dokładnie
   `com.cretorix.securepasswordmanager`.
3. Otwórz dodaną aplikację i wybierz **Ad units → Add ad unit → Banner**. Nazwij jednostkę
   np. `unlock_bottom_banner`; jej format musi być banerowy.
4. Zapisz osobno oba identyfikatory:
   - **AdMob App ID**, w formacie `ca-app-pub-…~…`, dla konfiguracji manifestu;
   - **Banner ad unit ID**, w formacie `ca-app-pub-…/…`, dla miejsca reklamy w aplikacji.
5. W AdMob otwórz **Privacy & messaging** i skonfiguruj komunikat zgody dla EOG/UK.
   Aplikacja używa Google UMP do wyświetlania wymaganych zgód i opcji prywatności.
6. W pliku `%USERPROFILE%\.gradle\gradle.properties` dodaj dwie linie bez cudzysłowów:

   ```properties
   ADMOB_APP_ID=ca-app-pub-...~...
   ADMOB_UNLOCK_BANNER_ID=ca-app-pub-.../...
   ```

   Te identyfikatory nie są hasłami, ale plik pozostaje poza repozytorium. Debug używa
   oficjalnych identyfikatorów testowych Google. Build `bundleRelease` celowo kończy się
   błędem, dopóki nie zostaną ustawione produkcyjne identyfikatory.

Nie klikaj własnych reklam produkcyjnych. Testuj wyłącznie na identyfikatorze testowym
Google lub przez licencjonowane konto testera.

## Gdzie wpisać poszczególne elementy w Play Console

1. **Create app / App setup:** wpisać nazwę aplikacji, język domyślny, typ aplikacji/gry
   oraz bezpłatną dystrybucję (usunięcie reklam jest opcjonalnym zakupem w aplikacji). Potwierdzić,
   że stała nazwa pakietu odpowiada `defaultConfig.applicationId`.
2. **Testing → Internal testing:** przesłać podpisany Android App Bundle (`.aab`), dodać
   testerów i opublikować wydanie testów wewnętrznych. Do realistycznych testów Billing
   instalować aplikację ze sklepowego linku dołączenia do testu.
3. **AdMob:** dodać aplikację Android `com.cretorix.securepasswordmanager`, utworzyć
   jednostkę banerową i zapisać AdMob App ID oraz Banner ad unit ID. Skonfigurować
   komunikat zgody EOG/UK w **Privacy & messaging**. Debug używa oficjalnych testowych
   identyfikatorów Google; identyfikatory produkcyjne umieścić w
   `%USERPROFILE%\.gradle\gradle.properties` jako `ADMOB_APP_ID` i
   `ADMOB_UNLOCK_BANNER_ID`. Nie publikować wydania z testowymi identyfikatorami.
4. **Monetize with Play → Products → One-time products:** utworzyć/aktywować
   `pro_unlock` jako jednorazowy zakup „Usuń reklamy”, ustawić cenę bazową 1 EUR i kraje
   dostępności oraz sprawdzić zakup/przywracanie jako licencjonowany tester.
5. **Grow users → Store presence → Main store listing:** skopiować tytuł, krótki i pełny
   opis z odpowiedniego pliku `store-listing-*.md`. Przesłać
   `assets/app-icon-512.png`, `assets/feature-graphic-1024x500.png` i zrzuty z
   `assets/screenshots/phone/`.
6. **Store listing → tłumaczenia:** dodać polski, angielski, niemiecki i francuski tekst
   oferty, jeśli te języki będą kierowane do odbiorców. Aplikacja ma zasoby UI dla tych
   czterech języków.
7. **Policy and programs → App content:** wypełnić App access, Ads, Content rating,
   Target audience i Data safety. Aplikacja nie ma logowania do konta — wkleić
   `app-access-instructions.md`, która opisuje utworzenie lokalnego sejfu, wymagania
   hasła głównego i blokady ekranu oraz testowe dane syntetyczne. Zadeklarować, że
   aplikacja zawiera reklamy. Sprawdzić `data-safety-worksheet.md`, bieżące ujawnienia
   SDK AdMob/UMP i praktyki SDK względem finalnego AAB.
8. **Policy and programs → Privacy policy:** wkleić
   `https://www.cretorix.eu/securevault-privacy.html`. Potwierdzić, że publiczna strona,
   zasoby aplikacji i odpowiedzi Data Safety opisują te same praktyki.
9. **Store settings / App access and contact details:** wpisać zweryfikowane dane
   wydawcy, kontaktu pomocy i prywatności. Nie używać przykładowych ani prywatnych danych
   testowych.
10. **Production → Countries/regions and release:** ustawić kraje dostępności, przesłać
   produkcyjny AAB i uzupełnić deklaracje wydania w Play Console.

Nazwy sekcji w konsoli mogą się zmieniać. Jeśli sekcja została przeniesiona, wyszukaj
w Play Console wymieniony formularz.

## Zrzuty ekranu

Zrzuty telefonu w `assets/screenshots/phone/` pochodzą z emulatora i zawierają wyłącznie
syntetyczne dane. Nie umieszczaj na nich prawdziwych haseł, Recovery QR, passphrase,
adresów e-mail ani potwierdzeń zakupów. Przed przesłaniem obejrzyj każdy obraz w pełnym
rozmiarze i odrzuć ekran z błędem, kontrolką debugowania, placeholderem lub elementem
emulatora.

Przygotowano po cztery zrzuty PNG 1080×1920 po polsku i angielsku. Grafika główna jest
obecnie po angielsku; jeśli będą używane niemieckie lub francuskie oferty, przygotuj
również lokalne zrzuty/grafiki.

Zrzuty powstały w tymczasowej debugowej konfiguracji emulatora, która pozwalała przechwycić
ekrany chronione przez `FLAG_SECURE` i obejść brak sprzętowego Keystore w emulatorze.
Tego trybu nie ma w finalnym kodzie ani wydaniu. Obrazy prezentują interfejs — nie są
potwierdzeniem ochrony kryptograficznej.

## Blokery przedprodukcji

- Potwierdzić, że `com.cretorix.securepasswordmanager` jest docelowym identyfikatorem
  pakietu przed pierwszym przesłaniem.
- Utworzyć i aktywować jednorazowy produkt Play Console `pro_unlock`.
- Podać nazwę prawną wydawcy, e-mail pomocy, kontakt prywatności i publiczny HTTPS URL
  polityki.
- Sprawdzić tekst oferty, politykę prywatności i Data Safety względem finalnego wydania.
- Na fizycznym urządzeniu z hardware-backed KeyMint sprawdzić świeży sejf bez
  skonfigurowanej biometrii, ale z PIN-em/wzorem/hasłem blokady Androida, oraz odblokowanie
  silną biometrią.
- Sejfy utworzone w starszych wydaniach przed fallbackiem mogą używać kluczy
  Keystore-only-biometrycznych. Nadal działają z wcześniej zarejestrowaną biometrią,
  ale nie uzyskują automatycznie fallbacku PIN-em. Do testu nowej ścieżki użyć świeżego
  sejfu; nie usuwać cennych danych testowych.
- Skonfigurować podpis release, kraje, grupę docelową, ocenę treści i instrukcje dostępu
  recenzenta.
- Przed przesłaniem sprawdzić aktualne wymagania Play dla grafik i zrzutów ekranu.
