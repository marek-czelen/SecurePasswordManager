# Instrukcje dostępu dla recenzenta Google Play

## Tekst do formularza App access

SecureVault nie wymaga konta, nazwy użytkownika, hasła do serwera ani kodu
jednorazowego. Przy pierwszym uruchomieniu recenzent tworzy lokalny sejf na urządzeniu.
Nie ma gotowych danych logowania ani zdalnego konta demonstracyjnego.

1. Użyj urządzenia testowego z ustawioną blokadą ekranu Androida (PIN, wzór lub hasło).
   Jeśli blokada nie jest ustawiona, skonfiguruj ją w Ustawieniach Androida, a następnie
   wróć do aplikacji.
2. Przy pierwszym uruchomieniu wybierz **Utwórz magazyn** i wpisz nowe hasło główne dwa
   razy. Musi ono mieć 12–24 drukowalne znaki ASCII i nie może składać się wyłącznie z
   cyfr. Przykładowe hasło testowe: `ReviewVault-2026!`. Jest ono przeznaczone wyłącznie
   do świeżego sejfu testowego.
3. Aplikacja wyświetli kod QR odzyskiwania. Zamknij okno, aby kontynuować. Kod QR jest
   sekretem odzyskiwania; nie należy umieszczać go w zrzutach ekranu ani w materiałach
   zgłoszenia.
4. Odblokuj aplikację silną biometrią albo poświadczeniem blokady ekranu urządzenia.
   Jeśli na urządzeniu nie skonfigurowano biometrii, użyj PIN-u, wzoru lub hasła
   urządzenia.
5. W sejfie użyj przycisku **Dodaj** i twórz wyłącznie syntetyczne dane testowe.

Po utworzeniu sejfu wszystkie funkcje są dostępne bez konta zewnętrznego i zakupu,
z limitami Free:

- Jeden plik i jeden dokument w sejfie.
- Do dwóch poprzednich haseł w historii każdego wpisu logowania. Aby zobaczyć historię,
  dodaj wpis, zmień jego hasło, zapisz, a następnie zmień je ponownie.
- Do pięciu wpisów na pojedynczą operację zaawansowanego importu lub eksportu CSV.
  Pozostałe rekordy z większego importu są jawnie pokazane jako pominięte.
- Import można sprawdzić plikiem UTF-8 CSV z kolumnami `name,username,password,url`
  i pięcioma syntetycznymi wierszami. Eksport CSV zapisuje dane jawnie do miejsca
  wybranego przez recenzenta; należy używać wyłącznie danych testowych.
- Pozostałe funkcje aplikacji są dostępne do testowania bez zakupu.

Pro zwiększa limity plików i dokumentów, historię do pięciu poprzednich haseł na wpis
oraz usuwa limit pięciu wpisów na operację zaawansowanego importu/eksportu. Pełne,
zaszyfrowane kopie sejfu i ich odzyskiwanie nie są ograniczone w Free; limit dotyczy
wyłącznie importu/eksportu rekordów haseł, a nie kopii `vault.vlt3`.

## Test autouzupełniania (opcjonalnie)

- Android 14 lub nowszy: w Ustawieniach Androida włącz SecureVault w razie potrzeby w
  sekcji **Dodatkowi dostawcy**, a następnie ustaw go jako **Preferowaną usługę** w
  **Hasła, klucze dostępu i konta**.
- Starszy Android: wyszukaj w Ustawieniach **Usługa autouzupełniania** i wybierz
  SecureVault.
- W Chrome 135 lub nowszym włącz **Ustawienia → Usługi autouzupełniania →
  Autouzupełnianie z innej usługi**. Nazwy i położenie ustawień zależą od wersji Chrome
  i producenta urządzenia.
- Otwórz testową stronę logowania, wybierz podpowiedź SecureVault i uwierzytelnij się.

## Zakup Pro

Pro jest jednorazowym zakupem Google Play o identyfikatorze produktu `pro_unlock`.
Wersja produkcyjna nie zawiera przełącznika testowego ani obejścia płatności. Każdy
rodzaj funkcji można przetestować w podanych limitach Free; zakup Pro zwiększa limity.
Zakup i przywracanie należy zweryfikować w sklepowym środowisku testowym Google Play
przed wysłaniem wersji produkcyjnej.
