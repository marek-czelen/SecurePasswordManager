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
| Produkt w aplikacji | Brak | Ustawić bezpłatną dystrybucję; wycofać dawny `pro_unlock` z aktywnej oferty |
| Model płatności | Całkowicie bezpłatna aplikacja, bez reklam i zakupów | Wszystkie funkcje dostępne bez opłat |
| Kategoria | Nieustawiona | Rekomendacja: Produktywność; potwierdzić właściwą kategorię dostępną w Play |
| Kontakt pomocy | Niepotwierdzony | **TODO:** podać monitorowany adres e-mail pomocy |
| Kontakt prywatności | `talk@cretorix.eu` | Potwierdzić, że jest monitorowany i aktualny |
| Wydawca / data polityki | Cretorix / 1 października 2023 r. (potwierdzone przez wydawcę) | Upewnić się, że te dane nadal są aktualne |
| Publiczny URL polityki | `https://www.cretorix.eu/securevault-privacy.html` | Działa i jest używany w aplikacji; potwierdzić ten sam URL w Play Console |
| Podpisywanie | Nie skonfigurowano w repozytorium | Włączyć Play App Signing i przechowywać klucz uploadu poza repozytorium |

Nie przesyłaj wydania produkcyjnego, dopóki nie zostaną ustalone: stały application ID,
tożsamość wydawcy, kontakty, publiczny URL polityki prywatności i treści oferty sklepu.

## Bezpłatny model aplikacji

- Wszystkie dostępne funkcje są bezpłatne; nie ma limitów planowych, subskrypcji ani
  zakupów w aplikacji.
- Limity techniczne VLT3 nadal obowiązują, w tym limity liczby wpisów, rozmiaru sejfu,
  załączników i stron skanów.
- Aplikacja nie używa Google Play Billing. Wycofaj dawny produkt `pro_unlock` z aktywnej
  oferty Play Console i potwierdź, że formularze sklepu nie opisują już zakupów.
- Wersja aplikacji nie zawiera reklam ani SDK AdMob/UMP. W formularzu Play zadeklaruj
  brak reklam i sprawdź Data Safety dla finalnych zależności.

## Gdzie wpisać poszczególne elementy w Play Console

1. **Create app / App setup:** wpisać nazwę aplikacji, język domyślny, typ aplikacji/gry
   oraz bezpłatną dystrybucję bez zakupów w aplikacji. Potwierdzić, że stała nazwa
   pakietu odpowiada `defaultConfig.applicationId`.
2. **Testing → Internal testing:** przesłać podpisany Android App Bundle (`.aab`), dodać
   testerów i opublikować wydanie testów wewnętrznych.
3. **Monetize with Play → Products:** sprawdzić, czy dawny `pro_unlock` nie jest już
   aktywnie oferowany. Nie dodawać nowych produktów ani przepływów zakupowych.
4. **Grow users → Store presence → Main store listing:** skopiować tytuł, krótki i pełny
   opis z odpowiedniego pliku `store-listing-*.md`. Przesłać
   `assets/app-icon-512.png`, `assets/feature-graphic-1024x500.png` i zrzuty z
   `assets/screenshots/phone/`.
5. **Store listing → tłumaczenia:** dodać polski, angielski, niemiecki i francuski tekst
   oferty, jeśli te języki będą kierowane do odbiorców. Aplikacja ma zasoby UI dla tych
   czterech języków.
6. **Policy and programs → App content:** wypełnić App access, Ads, Content rating,
   Target audience i Data safety. Zadeklarować brak reklam. Aplikacja nie ma logowania do konta — wkleić
   `app-access-instructions.md`, która opisuje utworzenie lokalnego sejfu, wymagania
   hasła głównego i blokady ekranu oraz testowe dane syntetyczne. Sprawdzić
   `data-safety-worksheet.md` i praktyki SDK względem finalnego AAB; aplikacja nie
   zawiera Google Play Billing.
7. **Policy and programs → Privacy policy:** wkleić
   `https://www.cretorix.eu/securevault-privacy.html`. Potwierdzić, że publiczna strona,
   zasoby aplikacji i odpowiedzi Data Safety opisują te same praktyki.
8. **Store settings / App access and contact details:** wpisać zweryfikowane dane
   wydawcy, kontaktu pomocy i prywatności. Nie używać przykładowych ani prywatnych danych
   testowych.
9. **Production → Countries/regions and release:** ustawić kraje dostępności, przesłać
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
- Ustawić bezpłatną dystrybucję i wycofać dawny `pro_unlock` z aktywnej oferty.
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
