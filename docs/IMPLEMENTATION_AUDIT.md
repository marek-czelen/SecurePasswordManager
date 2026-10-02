# Audyt implementacji

**Data przeglądu:** 2026-10-02  
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
| OCR i aparat | OCR na urządzeniu, zdjęcie/galeria, skanowanie wielostronicowe i kalibracja | Skanowanie dokumentów i kalibracja są blokowane przez Pro. |
| Kopia automatyczna | Użytkownik wybiera lokalizację SAF; po udanym zapisie odświeżana jest zaszyfrowana kopia | Nie ma harmonogramu WorkManager ani automatycznej polityki retry. |

## Istotne ograniczenia wydania

1. **Pro nie jest dostępne w wydaniu produkcyjnym.** `PremiumManager` zwraca Pro wyłącznie
   w buildzie debug z przełącznikiem testowym. Billing Google Play i pamięć uprawnienia
   offline nie są zaimplementowane; release zwraca Free.
2. **Lint nie przechodzi.** Ostatni `lintDebug` zgłosił 21 błędów i 81 ostrzeżeń.
   Wśród błędów są użycia eksperymentalnego API CameraX bez opt-in, atrybut `android:tint`
   zamiast `app:tint` oraz brak tłumaczeń niemieckich i francuskich dla nowych tekstów XLSX.
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
między zaszyfrowanym backupem a eksportami plaintext oraz faktyczny stan uprawnień Pro.
Stan walidacji i pozostałe bramki wydania zapisano w [statusie VLT3](VLT3_IMPLEMENTATION_PROGRESS.md)
i [audycie bezpieczeństwa](SECURITY_AUDIT.md).
