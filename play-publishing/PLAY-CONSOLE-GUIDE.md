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
| Produkt Billing | `pro_unlock` | Zachować ID; jednorazowy zakup SecureVault Pro |
| Model płatności | Free z limitami ilościowymi i opcjonalny jednorazowy zakup Pro | Potwierdzić cenę i dostępność; poprzedni zakup usunięcia reklam daje Pro |
| Kategoria | Nieustawiona | Rekomendacja: Produktywność; potwierdzić właściwą kategorię dostępną w Play |
| Kontakt pomocy | Niepotwierdzony | **TODO:** podać monitorowany adres e-mail pomocy |
| Kontakt prywatności | `talk@cretorix.eu` | Potwierdzić, że jest monitorowany i aktualny |
| Wydawca / data polityki | Cretorix / 1 października 2023 r. (potwierdzone przez wydawcę) | Upewnić się, że te dane nadal są aktualne |
| Publiczny URL polityki | `https://www.cretorix.eu/securevault-privacy.html` | Działa i jest używany w aplikacji; potwierdzić ten sam URL w Play Console |
| Podpisywanie | Nie skonfigurowano w repozytorium | Włączyć Play App Signing i przechowywać klucz uploadu poza repozytorium |

Nie przesyłaj wydania produkcyjnego, dopóki nie zostaną ustalone: stały application ID,
tożsamość wydawcy, kontakty, publiczny URL polityki prywatności i treści oferty sklepu.

## Model Free/Pro

- Free: jeden plik, jeden skanowany dokument, do dwóch poprzednich haseł na wpis i do
  pięciu wpisów na pojedynczą operację zaawansowanego importu/eksportu.
- Pro: bez limitu planowego liczby plików i dokumentów oraz bez limitu pięciu wpisów na
  operację; historia haseł pozostaje ograniczona technicznie do pięciu wpisów na login.
- Twarde limity VLT3 nadal obowiązują oba plany. Pełny zaszyfrowany backup sejfu nie ma
  limitu planowego.
- Nie zmieniaj identyfikatora produktu `pro_unlock`. Dotychczasowi nabywcy usunięcia
  reklam muszą zachować dostęp Pro.
- Wersja aplikacji nie zawiera reklam ani SDK AdMob/UMP. W formularzu Play zadeklaruj
  brak reklam i sprawdź Data Safety dla finalnych zależności.

## Gdzie wpisać poszczególne elementy w Play Console

1. **Create app / App setup:** wpisać nazwę aplikacji, język domyślny, typ aplikacji/gry
   oraz bezpłatną dystrybucję z opcjonalnym zakupem Pro. Potwierdzić, że stała nazwa
   pakietu odpowiada `defaultConfig.applicationId`.
2. **Testing → Internal testing:** przesłać podpisany Android App Bundle (`.aab`), dodać
   testerów i opublikować wydanie testów wewnętrznych. Do realistycznych testów Billing
   instalować aplikację ze sklepowego linku dołączenia do testu.
3. **Monetize with Play → Products → One-time products:** zachować `pro_unlock` jako
   jednorazowy zakup SecureVault Pro. Zaktualizować nazwę, opis, cenę i kraje dostępności
   w Play Console bez zmiany identyfikatora produktu; sprawdzić zakup/przywracanie jako
   licencjonowany tester.
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
   `data-safety-worksheet.md`, Google Play Billing i praktyki pozostałych SDK względem
   finalnego AAB.
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
