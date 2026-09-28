# VLT3 Implementation Progress

## Status ogólny

- Aktualny etap: 23/23 — końcowa weryfikacja VLT3 i usunięcie VLT2.
- Ukończenie: około 90%.
- Ostatnio zakończone zadanie: VLT2 persistence/kodek/slot usunięte po przełączeniu UI; DEK używa wyłącznie Argon2id+Recovery QR.
- Najbliższe następne zadanie: uruchomić końcowy build/test/lint i zaktualizować dokumentację bezpieczeństwa.
- Kompilacja: przed uproszczeniem modeli VLT2 wszystkie lokalne testy, lint, Android-test compile i assemble przechodziły; końcowy przebieg po zmianach do ponowienia.
- Testy: 30 testów JVM przechodziły; nowe testy tekstu/repo do ponowienia; brak podłączonego urządzenia do testów instrumentacyjnych.
- Znane blokery: brak. W istniejącym worktree znajduje się wcześniejsza, niepowiązana zmiana użytkownika w `docs/FEATURE_MATRIX.md`; pozostaje nietknięta.

## W trakcie

### Etap 23 — końcowa walidacja

- Zakres rozpoczętej pracy: usunięcie starej monolitycznej ścieżki po przełączeniu aplikacji i walidacja pozostałych V3 testów.
- Wykonane podpunkty: V2 repository, envelope, slot, kodek danych, model obrazów i PBKDF2 fallback usunięte; V3 pozostaje jedyną aktywną ścieżką.
- Pozostałe podpunkty: końcowy build/test/lint, aktualizacja dokumentacji, próba testów instrumentacyjnych.
- Wykryte problemy: brak podłączonego urządzenia uniemożliwia uruchomienie testów Keystore/AtomicFile/bitmap.
- Ryzyka: SAF nie gwarantuje atomowego zastąpienia istniejącego dokumentu; backup jest kompletnie zbudowany i sprawdzony lokalnie przed rozpoczęciem kopiowania.
- Następny konkretny krok: uruchomić pełne lokalne zadania Gradle i dopisać dokumentację VLT3.

## Do wykonania

- [x] 1. Niezależnie wersjonowany format headera i kodek.
- [x] 2. Atomowa aktualizacja headera i wrapperów DEK.
- [x] 3. Niezależnie wersjonowany format i szyfrowanie manifestu.
- [x] 4. Magazyn niezmiennych zaszyfrowanych obiektów.
- [x] 5. Kodek bloku tekstowego bez danych binarnych.
- [x] 6. Oddzielne zaszyfrowane obrazy.
- [x] 7. Oddzielne zaszyfrowane miniatury.
- [x] 8. Pliki do 10 MiB jako pojedyncze obiekty.
- [x] 9. Transakcje copy-on-write.
- [x] 10. `manifest.current` jako punkt zatwierdzenia.
- [x] 11. Utrzymywanie `manifest.previous`.
- [x] 12. Odzyskiwanie kompletnego stanu po awarii.
- [x] 13. Wykrywanie brakujących i uszkodzonych obiektów.
- [x] 14. Bezpieczne sprzątanie obiektów nieosiągalnych.
- [x] 15. Strumieniowy eksport/import pojedynczego backupu.
- [x] 16. Podłączenie nowego repozytorium do UI.
- [x] 17. Podłączenie mediów i plików do listy wpisów.
- [x] 18. Weryfikacja wyszukiwania, folderów, tagów, ulubionych i TOTP.
- [x] 19. Weryfikacja biometrii, Recovery QR, zmiany hasła i auto-lock.
- [x] 20. Weryfikacja ręcznego backupu/odzyskiwania dla Free i Pro.
- [~] 21. Testy wydajności, pamięci i odporności na awarie.
- [~] 22. Aktualizacja dokumentacji bezpieczeństwa.
- [~] 23. Usunięcie VLT2 po pełnym przełączeniu i przejściu testów.

## Znane problemy i dług techniczny

- Weryfikacja zachowania Android Keystore i trwałości fsync wymaga testu instrumentacyjnego/fizycznego urządzenia; test JVM tego nie potwierdzi.
- Należy zachować odrębne wersje kontenera, headera, manifestu, bloku tekstowego, obiektu i backupu.
- Limity VLT3: 10 000 wpisów, blok tekstowy 32 MiB, obraz 10 MiB, miniatura 256 KiB, plik 10 MiB, aktywne dane 100 MiB; bez chunkowania.
- Import/eksport musi pozostać dostępny niezależnie od uprawnień Free/Pro.
- Test `VaultHeaderStore` wymaga urządzenia/emulatora; sam fakt kompilacji testu nie weryfikuje zachowania systemu plików Android przy utracie zasilania.
- Manifest V3 ma limit 30 000 zasobów i 8 MiB zakodowanego rozmiaru; należy zweryfikować, czy limit jest spójny z dopuszczalną liczbą załączników przed finalizacją UI.
- Testy Android Keystore/AtomicFile nadal wymagają urządzenia; `connectedDebugAndroidTest` nie mógł uruchomić się, ponieważ nie ma podłączonego urządzenia.
- `connectedDebugAndroidTest` wcześniej zakończył się „No connected devices”; Keystore, fsync i bitmapy nie są runtime-zweryfikowane.
- Końcowa kopia backupu do dowolnego SAF URI nie jest atomowa dla każdego dostawcy; pełny backup jest stagingowany i walidowany lokalnie przed zapisem, a importer odrzuca plik niekompletny.
- Dodawanie plików jest dostępne w Pro zgodnie z wcześniejszą zmianą w `FEATURE_MATRIX.md`; backup i recovery pozostają dostępne dla Free i Pro.

## Rejestr decyzji

- 2026-09-28: VLT3 został przełączony jako jedyny format aktywny; stary plik `vault.vlt` nie jest migrowany i wyświetla jawny komunikat.
- 2026-09-28: każdy nowy szyfrowany obiekt/generacja otrzyma losowe ID 256-bitowe, odrębny klucz HKDF-SHA-256, losowy nonce 96-bitowy i AAD wiążące kontekst.
- 2026-09-28: `manifest.current` będzie punktem zatwierdzenia copy-on-write; stare obiekty pozostaną do czasu bezpiecznego GC.
- 2026-09-28: brak migracji VLT2, synchronizacji, ochrony przed celowym rollbackiem i chunkowania w pierwszej wersji.
- 2026-09-28: header używa niezależnego `headerSchemaVersion`; `AtomicFile` zabezpiecza jego pojedynczą podmianę bez utrzymywania starego wrappera jako aktywnego fallbacku.
- 2026-09-28: manifest przechowuje referencję do bloku tekstowego oraz inwentarz zasobów. SHA-256 ciphertextu jest metadanymi do walidacji kopii bez odszyfrowywania; AES-GCM pozostaje weryfikacją kryptograficzną.
- 2026-09-28: testy JVM magazynu wstrzykują synchronizację katalogu; produkcyjna implementacja używa `Os.fsync` po rename.
- 2026-09-28: miniaturę tworzy `VaultImageProcessor` jako JPG maks. 256 KiB; kod jeszcze nie jest podłączony do ekranu kadrowania.
- 2026-09-28: testy JVM magazynu wstrzykują tylko synchronizację katalogu; produkcyjny magazyn używa `Os.fsync` po rename.
- 2026-09-28: każdy obiekt ma niezależne losowe 256-bitowe ID i generację; klucz HKDF wiąże obie wartości, typ i wersję schematu; ciphertext hash jest weryfikowany przy odczycie/backupie, nie przez eager media scan przy wyświetleniu listy.
- 2026-09-28: KDF VLT3 akceptuje maksymalnie 256 MiB Argon2id, 10 iteracji i 4 wątki, ograniczając zasoby z niezaufanego headera.

### Zakończone zadania

#### Etap 1 — Header VLT3 i wrappery DEK

- Utworzone klasy: `VaultV3Format`, `VaultHeaderV3`, `VaultHeaderCodec`, `VaultV3KeyDerivation`, `VaultHeaderStore`.
- Zmienione klasy: brak; ścieżka VLT2 i UI pozostały nietknięte.
- Dodane testy: `VaultV3HeaderTest` (round-trip, zmiana hasła z tym samym DEK, błędny QR/AAD, nieobsługiwane wersje, separacja kluczy); `VaultHeaderStoreTest` (instrumentacyjny round-trip i podmiana).
- Wynik testów: `testDebugUnitTest` zaliczony; `compileDebugAndroidTestJavaWithJavac`, `lintDebug` i `assembleDebug` zaliczone.
- Implementacja: header ograniczony rozmiarem, oddzielnie wersjonowany; wrapper hasła używa Argon2id, niezależnego QR secret i HKDF; `AtomicFile` zapisuje header.
- Decyzje bezpieczeństwa: nie dodano PIN-verifiera; AAD wiąże wrapper z wersjami, vault ID, KDF, salt, QR ID i generacją wrappera; zmiana hasła tworzy nowy wrapper, nie zmienia DEK ani wrappera sprzętowego.
- Kolejność zakończenia: 1.

#### Etap 3 — Manifest VLT3

- Utworzone klasy: `VaultObjectRef`, `VaultManifest`, `VaultManifestCodec`.
- Zmienione klasy: brak.
- Dodane testy: `VaultManifestTest` (round-trip, złe klucze/vault ID, tampering, duplikaty, limity).
- Wynik testów: `testDebugUnitTest`, `compileDebugAndroidTestJavaWithJavac`, `lintDebug` i `assembleDebug` zaliczone.
- Implementacja: osobne wersjonowanie manifestu, szyfrowanie AES-GCM i AAD z vault ID oraz generacją, katalog referencji z rozmiarem i hashem ciphertextu.
- Decyzje bezpieczeństwa: manifest odrzuca duplikaty ID/generacji i referencje o niezgodnych długościach; tekstowy blok nie jest jeszcze zapisywany.
- Kolejność zakończenia: 2.

#### Etap 4 — Niezmienny magazyn obiektów

- Utworzone klasy: `VaultObjectStore`, `VaultDirectorySync`.
- Zmienione klasy: brak.
- Dodane testy: `VaultObjectStoreTest` (round-trip, ID nieczytelne, różne ID dla nowych generacji, tampering/hash, limity).
- Wynik testów: `testDebugUnitTest`, kompilacja Android testów, `lintDebug` i `assembleDebug` zaliczone.
- Implementacja: obiekt zapisuje się do pliku tymczasowego, fsync i atomowego rename; jego klucz jest wyprowadzany przez HKDF, AAD wiąże typ/ID/generację/właściciela/rozmiar, a manifestowy hash weryfikuje ciphertext.
- Decyzje bezpieczeństwa: obiektów nie nadpisuje się; nowa wersja dostaje losowe ID/generację i nonce; platformowy fsync katalogu odseparowano od logiki testowalnej JVM.
- Kolejność zakończenia: 3.

#### Etap 5 — Kodek bloku tekstowego

- Utworzone klasy: `VaultTextDataCodec`.
- Zmienione klasy: `VaultItem` (stabilne ID wpisu, typ pliku i referencje; pole VLT2 `imageJpeg` zachowane tymczasowo).
- Dodane testy: `VaultTextDataCodecTest` (round-trip wpisów login/image/file, inline-media rejection, duplikaty, wersje, limity i 10 001 wpisów).
- Wynik testów: `testDebugUnitTest`, `compileDebugAndroidTestJavaWithJavac`, `lintDebug` i `assembleDebug` zaliczone.
- Implementacja: niezależna wersja tekstu, padding 4 KiB, limit 32 MiB, maksymalnie 10 000 wpisów; media są wyłącznie referencjami.
- Decyzje bezpieczeństwa: VLT3 odrzuca bajty `imageJpeg`; dekoder waliduje UTF-8, typy, ID, długości i nadmiarowe dane.
- Kolejność zakończenia: 4.
