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
| Wersja | `1.0` / kod wersji `1` | Zwiększać `versionCode` przy każdym kolejnym przesłaniu |
| Produkt Billing | `pro_unlock` | Utworzyć w Play Console jednorazowy produkt o dokładnie takim ID |
| Model płatności | Aplikacja bezpłatna z opcjonalnym zakupem Pro | Wybrać bezpłatną dystrybucję; cenę Pro ustawić jako zakup w aplikacji |
| Kategoria | Nieustawiona | Rekomendacja: Produktywność; potwierdzić właściwą kategorię dostępną w Play |
| Kontakt pomocy | Niepodany | **TODO:** podać monitorowany adres e-mail |
| Wydawca / kontakt prywatności | Pozostały pola zastępcze | **TODO:** uzupełnić w `docs/PRIVACY_POLICY.md` |
| Publiczny URL polityki | Niepodany | **TODO:** opublikować sprawdzoną politykę pod stabilnym adresem HTTPS |
| Podpisywanie | Nie skonfigurowano w repozytorium | Włączyć Play App Signing i przechowywać klucz uploadu poza repozytorium |

Nie przesyłaj wydania produkcyjnego, dopóki nie zostaną ustalone: stały application ID,
tożsamość wydawcy, kontakty, publiczny URL polityki prywatności i treści oferty sklepu.

## Gdzie wpisać poszczególne elementy w Play Console

1. **Create app / App setup:** wpisać nazwę aplikacji, język domyślny, typ aplikacji/gry
   oraz bezpłatną dystrybucję (Pro jest sprzedawane jako zakup w aplikacji). Potwierdzić,
   że stała nazwa pakietu odpowiada `defaultConfig.applicationId`.
2. **Testing → Internal testing:** przesłać podpisany Android App Bundle (`.aab`), dodać
   testerów i opublikować wydanie testów wewnętrznych. Do realistycznych testów Billing
   instalować aplikację ze sklepowego linku dołączenia do testu.
3. **Monetize with Play → Products → One-time products:** utworzyć `pro_unlock`, ustawić
   cenę i kraje dostępności oraz aktywować produkt przed testami zakupu i przywracania.
4. **Grow users → Store presence → Main store listing:** skopiować tytuł, krótki i pełny
   opis z odpowiedniego pliku `store-listing-*.md`. Przesłać
   `assets/app-icon-512.png`, `assets/feature-graphic-1024x500.png` i zrzuty z
   `assets/screenshots/phone/`.
5. **Store listing → tłumaczenia:** dodać polski, angielski, niemiecki i francuski tekst
   oferty, jeśli te języki będą kierowane do odbiorców. Aplikacja ma zasoby UI dla tych
   czterech języków.
6. **Policy and programs → App content:** wypełnić App access, Ads, Content rating,
   Target audience i Data safety. Aplikacja nie ma logowania do konta — instrukcja dla
   recenzenta powinna wyjaśniać, że może utworzyć lokalny, testowy sejf z tymczasowym
   hasłem i syntetycznymi danymi. Zadeklarować brak reklam tylko wtedy, gdy pozostanie
   to prawdą w finalnym wydaniu. Sprawdzić `docs/DATA_SAFETY.md` względem finalnego AAB
   i ujawnień SDK.
7. **Policy and programs → Privacy policy:** wkleić publiczny HTTPS URL sprawdzonej
   polityki `docs/PRIVACY_POLICY.md`. Przed publikacją zastąpić wszystkie pola wydawcy i
   kontaktów.
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
