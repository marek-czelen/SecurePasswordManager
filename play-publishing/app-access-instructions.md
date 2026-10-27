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

Po utworzeniu sejfu można testować funkcje bez konta zewnętrznego. Plan Free pozwala
dodać jeden plik i jeden skan dokumentu, przechowuje do dwóch poprzednich haseł na wpis
i pozwala na pięć wpisów na zaawansowaną operację importu/eksportu. Pełne zaszyfrowane
kopie sejfu nie mają limitu planowego. Aplikacja nie wyświetla reklam.

Jednorazowy zakup Google Play `pro_unlock` aktywuje Pro: usuwa limity planowe liczby
plików/dokumentów i liczby wpisów na operację importu/eksportu, a historię zwiększa do
pięciu poprzednich haseł na wpis. Test zakupu wymaga licencjonowanego konta Google Play;
nie jest wymagany do sprawdzenia limitów Free.

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

## Przywrócenie zakupu Pro

Zakup Pro można przywrócić z Ustawień na tym samym koncie Google Play. Dotychczasowy
zakup usunięcia reklam pod tym samym identyfikatorem `pro_unlock` nadal przyznaje Pro.
