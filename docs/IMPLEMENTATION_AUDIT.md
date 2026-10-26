# Audyt implementacji

**Data przeglądu:** 2026-10-26
**Zakres:** aplikacja Android, główne moduły Java, konfiguracja Gradle, manifest,
zasoby oraz istniejąca dokumentacja. To przegląd zgodności opisu z kodem, nie niezależny
test penetracyjny ani certyfikacja bezpieczeństwa.

## Co jest zaimplementowane

| Obszar | Stan w kodzie | Uwagi |
|---|---|---|
| Magazyn danych | VLT3 jest jedynym używanym formatem | Starego VLT2 aplikacja nie migruje ani nie otwiera. |
| Szyfrowanie i odblokowanie | DEK, Argon2id, niezależny Recovery QR, AES-GCM i Android Keystore | Odblokowanie biometryczne wymaga mocnej biometrii oraz sprzętowo chronionego klucza; urządzenie bez takiego wsparcia kończy operację błędem. |
| Zapis i odzyskiwanie | Copy-on-write, bieżący i poprzedni manifest, staging kopii | SAF nie gwarantuje atomowej podmiany pliku u każdego dostawcy; przerwana kopia zewnętrzna może być niekompletna. |
| Wpisy i narzędzia | Loginy, foldery, tagi, ulubione, wyszukiwanie, generator, TOTP i lokalna ocena haseł | Dane kondycji haseł są analizowane lokalnie. |
| Obrazy i załączniki | Obrazy JPG i miniatury, wielostronicowe skany JPG oraz pliki jako osobne zaszyfrowane obiekty | Limit obrazu/strony: 10 MiB; pliku: 10 MiB; skan: do 20 stron i 60 MiB; aktywny magazyn: 100 MiB. |
| Import/eksport | CSV, XLSX, Bitwarden JSON, KeePass XML, 1Password 1PUX; eksport CSV i zaszyfrowanej kopii | XLSX może być chroniony hasłem. Import 1Password pomija załączniki. CSV oraz jawny eksport załącznika zawierają plaintext. |
| Autofill | Android Autofill oraz Credential Manager od API 34 | Brak zapisywania nowych haseł. Dopasowanie witryn używa dokładnej nazwy hosta; wybór poświadczenia przekazuje je aplikacji żądającej. |
| OCR i aparat | OCR na urządzeniu, zdjęcie/galeria, skanowanie wielostronicowe i kalibracja | Funkcje dostępne bez zakupu; obowiązują wyłącznie techniczne limity rozmiaru i liczby stron. |
| Kopia automatyczna | Użytkownik wybiera lokalizację SAF; po udanym zapisie odświeżana jest zaszyfrowana kopia | Nie ma harmonogramu WorkManager ani automatycznej polityki retry. |

## Istotne ograniczenia wydania

1. **Reklamy wymagają konfiguracji wydawniczej.** Debug używa oficjalnych testowych ID
   AdMob; build release wymaga `ADMOB_APP_ID` i `ADMOB_UNLOCK_BANNER_ID` w
   właściwościach Gradle użytkownika. Testowe ID są dozwolone wyłącznie dla testów
   wewnętrznych; do produkcji trzeba użyć ID z własnego konta AdMob. `pro_unlock` usuwa
   wyłącznie baner; nie ogranicza funkcji sejfu.
2. **Lint nie przechodzi.** Ostatni `lintDebug` zgłosił 5 błędów i 86 ostrzeżeń:
   atrybut `android:tint` zamiast `app:tint` oraz brak czterech tłumaczeń tekstów XLSX
   na niemiecki i francuski.
3. **Testy urządzeniowe pozostają wymagane.** Testy JVM nie potwierdzają zachowania
   Android Keystore, mocnej biometrii, `AtomicFile`/fsync, aparatu ani dostawców SAF po
   awarii lub zmianie urządzenia.
4. **Publikacja wymaga konfiguracji.** Polityka prywatności ma pola zastępcze, a URL
   osadzony w aplikacji wskazuje `example.com`. Konfiguracja aplikacji nie zawiera jawnej
   konfiguracji podpisywania release; przed dystrybucją trzeba zweryfikować podpis AAB/APK.
5. **Brak migracji VLT2.** Użytkownicy starego formatu muszą wykonać eksport w poprzedniej
   wersji aplikacji przed utworzeniem nowego sejfu.

## Zgodność dokumentacji po audycie

Dokumentację repozytorium i wbudowane komunikaty polityki prywatności uzupełniono o
wielostronicowe skany i ich limity, import chronionych hasłem
arkuszy XLSX, pomijanie załączników 1Password, automatyczne kopie po zapisie, różnicę
między zaszyfrowanym backupem a eksportami plaintext oraz nowy model: wszystkie funkcje
sejfu są bezpłatne, a zakup `pro_unlock` usuwa wyłącznie reklamę. Publiczną politykę
prywatności i deklarację Data Safety trzeba zsynchronizować z wdrożonymi SDK AdMob/UMP.
Stan walidacji i pozostałe bramki wydania zapisano w [statusie VLT3](VLT3_IMPLEMENTATION_PROGRESS.md)
i [audycie bezpieczeństwa](SECURITY_AUDIT.md).
