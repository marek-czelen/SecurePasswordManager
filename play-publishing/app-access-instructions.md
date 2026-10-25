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

Po utworzeniu sejfu wszystkie podstawowe funkcje są dostępne bez konta zewnętrznego.
W wersji Free można dodać po jednym pliku i dokumencie. Kolejne pliki i dokumenty,
historia haseł oraz zaawansowany import/eksport są funkcjami Pro.

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
Wersja produkcyjna nie zawiera przełącznika testowego ani obejścia płatności. Jeśli
recenzja wymaga sprawdzenia funkcji Pro, zakup i przywracanie należy zweryfikować w
sklepowym środowisku testowym Google Play przed wysłaniem wersji produkcyjnej.
