# ❓ FAQ

[English](faq.md) · [Français](faq.fr.md) · [Deutsch](faq.de.md) · [Español](faq.es.md) · [Italiano](faq.it.md) · **Nederlands** · [Português](faq.pt.md) · [Polski](faq.pl.md)

## 📱 Apparaten

**Welke tablets werken?**
Elke tablet met Android **8.0** of nieuwer. Dagelijks in gebruik op een Samsung Galaxy Tab A8 en getest op een Raspberry
Pi 4 met Android. Een eenvoudige tablet volstaat: de app is native, zonder browser erachter.

**Amazon Fire-tablets?**
Nog niet getest. Fire OS is gebaseerd op Android, zonder Google-diensten: de app zou moeten werken, het uitknippen van
de avatars gebruikt dan het model in de app (iets minder strakke randen). Reacties zijn welkom.

**Raspberry Pi?**
Ja, met Android erop: zie de [sectie Raspberry Pi](../README.nl.md#-raspberry-pi) van de README. Gebruik een echte
voeding van 5 V / 3 A: een USB-poort van een computer of dock is te zwak, het scherm verliest het signaal en er
verschijnt een melding *Voeding te zwak*.

**Welke APK moet ik downloaden?**
`…-arm64-v8a.apk` voor bijna alle recente tablets en de Raspberry Pi. `…-armeabi-v7a.apk` voor oudere 32-bits tablets.
De universele APK werkt overal, maar is drie keer zo groot.

**Waarom is de app zo groot?**
Vooral door het uitknippen van de avatars op het apparaat (Google ML Kit-modellen). De APK voor de architectuur van uw
tablet is ongeveer 50 MB. De **lichte versie** (`…-lite-universal.apk`, 15 MB, voor alle tablets) heeft geen Google ML
Kit: avatars zijn dan ronde foto's, zonder uitknippen; al het andere is hetzelfde.

## 🏡 Home Assistant

**Moet ik iets installeren in Home Assistant?**
Nee: geen aangepaste kaarten, helpers, sjablonen of automatiseringen. De app leest de entiteiten van uw integraties en
berekent de rest zelf (aanwezigheid, goedkoopste brandstof, weerwaarschuwingen…). Twee dingen zijn optioneel: de tablet
als apparaat in Home Assistant (*mobile_app*, voor zijn sensoren en meldingen) en de spraaksatelliet.

**Moet ik een token aanmaken?**
Nee. De wizard meldt zich aan met uw Home Assistant-account, vanaf uw telefoon (QR-code) of op de tablet, en maakt het
token zelf aan. Een zelfgemaakt token plakken werkt ook.

**Werkt het buitenshuis?**
Ja, via een reverse proxy (Pangolin, Cloudflare Access…): voeg zijn headers toe in *Instellingen › Home Assistant*. De
app waarschuwt als een `http://`-adres buiten het lokale netwerk wordt gebruikt.

**Is mijn token veilig?**
Het blijft in de app, op de tablet. In een geëxporteerde back-up is het versleuteld met een sleutel die aan de tablet
gebonden is: op een andere tablet wordt het token opnieuw gevraagd en moeten de toegangscodes opnieuw gekozen worden.

## 📹 Camera's

**Welke camera's worden ondersteund?**
Elke `camera.*`-entiteit van Home Assistant (momentopname en stream via Home Assistant), of een direct `rtsp://`- /
HLS-adres (go2rtc…). Camera's op batterij worden alleen gewekt wanneer nodig, volgens hun voeding (net, zon, batterij).

**Het volledige scherm blijft zwart of meldt een niet-ondersteund formaat.**
Sommige tablets kunnen 4K H.265-streams niet decoderen. De app probeert dan een softwaredecoder, daarna de lichtere stream
van de kaart, en onthoudt geweigerde formaten (*Instellingen › Weergave* om ze te wissen). De „vloeiende” stream van de
camera kiezen voor het volledige scherm lost het meestal op.

**Waar komt de detectiegeschiedenis vandaan?**
Uit de map waarin uw opnamen staan: Home Assistant-media, een SMB- of NFS-share, of een map op de tablet. Elke
momentopname wordt aan zijn video gekoppeld.

## 🔒 Kioskmodus en updates

**Hoe open ik de instellingen?**
Druk lang op de linkerbovenhoek van het scherm. Als er een mastercode is ingesteld, wordt die eerst gevraagd.

**Hoe vergrendel ik de tablet volledig?**
Maak de app eenmaal *apparaateigenaar* (één `adb`-opdracht, of een QR-code na een reset): zie
[Kioskmodus](../README.nl.md#-kioskmodus). Voor het verwijderen: *Instellingen › Beveiliging › Rechten van
apparaateigenaar intrekken*.

**Hoe werken updates?**
De app controleert de GitHub-releases elke 12 uur en installeert de nieuwe versie. De eerste keer vraagt Android om
HA-MACS toe te staan *onbekende apps te installeren*: daarna gaat de update vanzelf verder. Als apparaateigenaar wordt
niets gevraagd.

## 🐛 Problemen

**De app is gecrasht of iets werkt niet.**
Open *Instellingen › Over › Crashlogboek*: kopieer het (of download het vanaf een telefoon met *Instellingen › Dashboard
› Back-up met de telefoon*) en plak het in een [melding](https://github.com/Arubinu/ha-macs-android/issues/new/choose).
Het toont ook video- en streamfouten, met verborgen adressen. Deel nooit uw token, codes of een back-upbestand.

**De animaties ontbreken of haperen.**
De animaties van Android staan uit op de tablet (toegankelijkheidsinstelling of ontwikkelaarsopties). De app merkt dit
op en laat in *Instellingen › Weergave* zien hoe u het oplost.

## ℹ️ Over

**Is het een officiële Home Assistant-app?**
Nee, het is een onafhankelijk project. Het personage M.A.C.S. is het werk van
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, niet-commercieel gebruik).

**Kan ik helpen?**
Graag: meldingen met het crashlogboek, ideeën, vertalingen (vandaag 8 talen) en tests op andere tablets.
