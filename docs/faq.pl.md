# ❓ FAQ

[English](faq.md) · [Français](faq.fr.md) · [Deutsch](faq.de.md) · [Español](faq.es.md) · [Italiano](faq.it.md) · [Nederlands](faq.nl.md) · [Português](faq.pt.md) · **Polski**

## 📱 Urządzenia

**Jakie tablety działają?**
Każdy tablet z Androidem **8.0** lub nowszym. Używana codziennie na Samsungu Galaxy Tab A8 i przetestowana na
Raspberry Pi 4 z Androidem. Wystarczy skromny tablet: aplikacja jest natywna, bez przeglądarki pod spodem.

**Tablety Amazon Fire?**
Jeszcze nieprzetestowane. Fire OS opiera się na Androidzie, bez usług Google: aplikacja powinna działać, a wycinanie
awatarów używa wtedy modelu wbudowanego w aplikację (nieco mniej czyste krawędzie). Opinie mile widziane.

**Raspberry Pi?**
Tak, z zainstalowanym Androidem: zobacz [sekcję Raspberry Pi](../README.pl.md#-raspberry-pi) w README. Użyj
prawdziwego zasilacza 5 V / 3 A: port USB komputera lub stacji dokującej jest za słaby, ekran traci sygnał i pojawia
się komunikat *Zbyt słabe zasilanie*.

**Który plik APK pobrać?**
`…-arm64-v8a.apk` dla prawie wszystkich nowszych tabletów i Raspberry Pi. `…-armeabi-v7a.apk` dla starszych tabletów
32-bitowych. Uniwersalny APK działa wszędzie, ale jest trzy razy większy.

**Dlaczego aplikacja jest tak duża?**
Głównie przez wycinanie awatarów na urządzeniu (modele Google ML Kit). APK dla architektury Twojego tabletu ma około
44 MB.

## 🏡 Home Assistant

**Czy trzeba coś instalować w Home Assistant?**
Nie: żadnych kart niestandardowych, pomocników, szablonów ani automatyzacji. Aplikacja odczytuje encje Twoich integracji
i sama oblicza resztę (obecność, najtańsze paliwo, ostrzeżenia pogodowe…). Dwie rzeczy są opcjonalne: pokazanie tabletu
jako urządzenia w Home Assistant (*mobile_app*, dla jego czujników i powiadomień) oraz satelita głosowy.

**Czy trzeba utworzyć token?**
Nie. Kreator loguje się Twoim kontem Home Assistant, z telefonu (kod QR) lub na tablecie, i sam tworzy token. Wklejenie
własnego tokenu też działa.

**Czy działa poza domem?**
Tak, przez odwrotne proxy (Pangolin, Cloudflare Access…): dodaj jego nagłówki w *Ustawienia › Home Assistant*. Aplikacja
ostrzega, gdy adres `http://` jest używany poza siecią lokalną.

**Czy mój token jest bezpieczny?**
Zostaje w aplikacji, na tablecie. W wyeksportowanej kopii zapasowej jest zaszyfrowany kluczem powiązanym z tabletem: na
innym tablecie token jest pobierany ponownie, a kody dostępu trzeba wybrać od nowa.

## 📹 Kamery

**Jakie kamery są obsługiwane?**
Każda encja `camera.*` z Home Assistant (zdjęcie i strumień przez Home Assistant) albo bezpośredni adres `rtsp://` / HLS
(go2rtc…). Kamery na baterie są wybudzane tylko w razie potrzeby, zależnie od zasilania (sieć, słoneczne, bateria).

**Pełny ekran pozostaje czarny lub zgłasza nieobsługiwany format.**
Niektóre tablety nie dekodują strumieni 4K H.265. Aplikacja próbuje wtedy dekodera programowego, potem lżejszego
strumienia karty, i zapamiętuje odrzucone formaty (*Ustawienia › Wyświetlanie*, aby je wyczyścić). Wybranie „płynnego”
strumienia kamery dla pełnego ekranu zwykle rozwiązuje problem.

**Skąd pochodzi historia wykryć?**
Z folderu, w którym zapisywane są nagrania: media Home Assistant, udział SMB lub NFS albo folder na tablecie. Każde
zdjęcie jest powiązane ze swoim filmem.

## 🔒 Tryb kiosku i aktualizacje

**Jak otworzyć ustawienia?**
Przytrzymaj lewy górny róg ekranu. Jeśli ustawiono kod główny, zostanie najpierw o niego poproszony.

**Jak całkowicie zablokować tablet?**
Raz ustaw aplikację jako *właściciela urządzenia* (jedno polecenie `adb` albo kod QR po zresetowaniu): zobacz
[Tryb kiosku](../README.pl.md#-tryb-kiosku). Przed odinstalowaniem: *Ustawienia › Bezpieczeństwo › Usuń uprawnienia
właściciela*.

**Jak działają aktualizacje?**
Aplikacja co 12 godzin sprawdza wydania (Releases) na GitHubie i instaluje nową wersję. Za pierwszym razem Android prosi
o zezwolenie HA-MACS na *instalowanie nieznanych aplikacji*: potem aktualizacja przebiega sama. Jako właściciel
urządzenia nic nie jest pytane.

## 🐛 Problemy

**Aplikacja uległa awarii lub coś nie działa.**
Otwórz *Ustawienia › Informacje › Dziennik awarii*: skopiuj go (lub pobierz z telefonu przez *Ustawienia › Panel › Kopia
zapasowa telefonem*) i wklej do [zgłoszenia](https://github.com/Arubinu/ha-macs-android/issues/new/choose). Zawiera też
błędy wideo i strumieni, z ukrytymi adresami. Nigdy nie udostępniaj tokenu, kodów ani pliku kopii zapasowej.

**Brakuje animacji lub się zacinają.**
Animacje Androida są wyłączone na tablecie (ustawienie ułatwień dostępu lub opcje programisty). Aplikacja to wykrywa i
pokazuje w *Ustawienia › Wyświetlanie*, jak to naprawić.

## ℹ️ O projekcie

**Czy to oficjalna aplikacja Home Assistant?**
Nie, to niezależny projekt. Postać M.A.C.S. jest dziełem
[Glyna Davidsona](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, użytek niekomercyjny).

**Czy mogę pomóc?**
Chętnie: zgłoszenia z dziennikiem awarii, pomysły, tłumaczenia (dziś 8 języków) i testy na innych tabletach.
