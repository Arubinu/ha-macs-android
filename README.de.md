# 🏠 HA-MACS Android

[English](README.md) · [Français](README.fr.md) · **Deutsch** · [Español](README.es.md) · [Italiano](README.it.md) · [Nederlands](README.nl.md) · [Português](README.pt.md) · [Polski](README.pl.md)

Ein **natives Android-Dashboard für Home Assistant**, gemacht für Wandtablets. Es bringt das Aussehen eines
Lovelace-Kiosk-Dashboards in eine flüssige Jetpack-Compose-App, auch auf einfachen Tablets: ohne Browser, ohne
Custom Cards und ohne Einrichtung in Home Assistant.

<p align="center">
  <img src="docs/screenshots/de/home.jpg" width="49%" alt="">
  <img src="docs/screenshots/de/dashboard.jpg" width="49%" alt="">
</p>
<p align="center">
  <img src="docs/screenshots/de/members.jpg" width="49%" alt="">
  <img src="docs/screenshots/de/cameras-energy.jpg" width="49%" alt="">
</p>
<p align="center">
  <img src="docs/screenshots/de/avatar-editor.jpg" width="66%" alt="">
</p>
<p align="center">📸 <em>Demodaten.</em></p>

## ✨ Funktionen

- 🤖 **M.A.C.S.**, die animierte Assistenzfigur, im Mittelpunkt des Startbildschirms
- 🧙 **Einrichtungsassistent**: Räume, Mitglieder, Kameras und wichtige Elemente werden in Home Assistant erkannt
- 🏠 **Räume**: Licht, Temperatur, Luftfeuchtigkeit und Geräte auf einen Blick
- 👨‍👩‍👧 **Mitglieder**: Anwesenheit (Balken antippen für Ankünfte und Abfahrten), Ankunftszeit, Schritte, Handy-Akku
  und anstehende Termine
- 🧑‍🎨 **Avatare**: Foto mit der Tablet-Kamera, aus einer Datei oder vom Handy (QR-Code), auf dem Gerät freigestellt,
  optionale Filter, Kopf ragt aus einem Kreis in der Farbe des Dashboards
- 📹 **Kameras**: Livebild, eigene Tabs, Vollbild in hoher Auflösung, letzte Erkennungen (HA-Medien, SMB, NFS, lokal)
- 🚨 **Warnungen**: native Umsetzung der [AlertTicker Card](https://github.com/djdevil/AlertTicker-Card), 63 Themes,
  Ton, Sprachausgabe und Benachrichtigungen
- 🌦️ **Wetter und Diagramme**: Vorhersage, Windy-Radar, Stromverbrauch, Spritpreise mit der günstigsten Tankstelle
  (Diagramm antippen: jede Tankstelle, ihr Preis und ihre letzten Änderungen)
- 🎵 **Musik**: Cover und seine Farben, Fortschritt live, Lautstärke, Zufall und Wiederholung, mehrere Player; Cover
  antippen für Mediathek, Suche, Warteschlange, Quelle und Lautsprechergruppen
- 🎙️ **Sprachassistent**: mit M.A.C.S. über dein lokales Gateway oder als **Home-Assistant-Sprachsatellit** sprechen
  (Wyoming, Assist-Pipeline, Aktivierungswort optional); Einstellungen › Sprachassistent
- ✏️ **Bearbeitungsmodus**: Entitäten, Räume, Tabs und Blöcke direkt auf dem Tablet ändern
- 🔒 **Kioskmodus**: ohne Mastercode (oder Zugangscode) lässt sich die App nicht verlassen und das Tablet nicht ausschalten
- 🌍 **8 Sprachen**: Englisch, Französisch, Deutsch, Spanisch, Italienisch, Niederländisch, Portugiesisch, Polnisch

> 💡 **Kein Sensor in Home Assistant anzulegen**: Anwesenheit, Tag/Nacht, Wetterwarnungen, bester Preis… alles wird
> von der App aus den Entitäten deiner Integrationen berechnet.

## 📋 Voraussetzungen

- 📱 Tablet mit **Android 8.0** oder neuer (z. B. Galaxy Tab A8)
- 🏡 Home Assistant vom Tablet aus erreichbar; kein Token nötig: Der Assistent meldet sich mit deinem Konto an,
  *Mit dem Handy* (QR-Code, der Passwortmanager des Handys füllt die Felder aus) oder *Auf dem Tablet*, oder nimmt ein
  *Zugriffstoken*

## 🚀 Installation

Noch gibt es keine fertige APK: Sie wird aus dem Quellcode gebaut.

```sh
# Android SDK (Plattform 36) und JDK 17 erforderlich
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Beim ersten Start findet der **Assistent** Home Assistant im Netzwerk, meldet sich an und passt das Dashboard an dein
Zuhause an. Schon eine Sicherung? Im ersten Schritt **importieren**, fertig.

## ⚙️ Im Alltag

- ⚙️ **Einstellungen**: lange auf die obere linke Ecke drücken (Verbindung, Anzeige, Ruhezustand, Zugangscode,
  Sprache…)
- 🔑 **Mastercode** (optional, im Assistenten oder unter Einstellungen › Sicherheit): wird vor dem Öffnen der
  Einstellungen abgefragt
- ✏️ **Bearbeitungsmodus**: Einstellungen › Dashboard › *Blöcke bearbeiten*, dann einen Block antippen
- 💾 **Sicherung**: Einstellungen › Dashboard › *Exportieren* / *Importieren* / *Mit dem Handy* (QR-Code); Token und
  Codes sind für dieses Tablet verschlüsselt (auf einem anderen werden neue Codes abgefragt), die Datei bleibt aber
  persönlich (Layout, Avatarfotos)
- 🔄 **Updates**: Einstellungen › Über › *Updates*; neue Versionen (und auf Wunsch Vorabversionen) werden automatisch
  installiert, die App öffnet sich nach einem Update oder Absturz von selbst wieder und zeigt einmal die *Neuigkeiten*
- 🌙 **Nachtmodus**: Einstellungen › Ruhezustand & Anwesenheit › *Nachtmodus*; von Sonnenuntergang bis -aufgang (oder
  zu festen Zeiten) wird der Bildschirm früher schwarz und wacht bei Berührung oder Anwesenheit auf
- 🔔 **Türklingel**: im Bearbeitungsmodus zeigt *Bei einem Ereignis öffnen* eine Kamera im Vollbild, wenn eine
  Klingel oder ein Melder auslöst, mit Ton
- 📡 **Tablet in Home Assistant**: Einstellungen › Home Assistant; Sensoren für Akku, Bildschirm und Anwesenheit sowie
  Benachrichtigungen (Banner, Vorlesen, Bildschirm an, Kamera im Vollbild)
- 🔊 **Mediaplayer**: Home Assistant findet das Tablet (DLNA) und kann Sprachausgabe, Radios oder Music Assistant darauf
  abspielen; Einstellungen › Home Assistant
  (Zugriff: Home Assistant und genehmigte Geräte oder das ganze Netzwerk)
- 🌍 **Zugriff über das Internet**: https-Reverse-Proxy (Pangolin, Cloudflare Access…) mit seinen Headern;
  Einstellungen › Home Assistant › *Reverse-Proxy*
- 🧙 **Assistent**: jederzeit erneut startbar unter Einstellungen › Dashboard

## 🔒 Kioskmodus

Einstellungen › Sicherheit › **Kioskmodus**. Für eine vollständige Sperre (Home, letzte Apps, Benachrichtigungen und
Ein/Aus-Menü gesperrt) die App einmalig vom Computer aus zum *Geräteinhaber* machen, ohne angemeldetes Konto auf dem
Tablet:

```sh
adb shell dpm set-device-owner fr.pergens.hamacs/.kiosk.KioskAdmin
```

📷 Ohne Computer: *Tablet vorbereiten* (unten) zeigt einen QR-Code, der nach dem Zurücksetzen auf dem
Begrüßungsbildschirm gescannt wird (6-mal auf dieselbe Stelle tippen). Einfacher: die App als Startbildschirm wählen; sie
kommt mit der Home-Taste und beim Start zurück, aber eine Android-Geste kann das Bildschirmfixieren lösen.

⚠️ Vor dem Deinstallieren: Einstellungen › Sicherheit › *Geräteinhaber-Rechte entfernen*.

✅ Einstellungen › Sicherheit › *Tablet vorbereiten* prüft alles auf einmal: Kiosk, Geräteinhaber oder
Startbildschirm, Mastercode, Neustart nach einem Absturz, Akku und Animationen, jeweils mit der Lösung.

## 🎙️ Sprachassistent (optional)

Ein kleines Python-Gateway (`gateway/`, Docker-fähig) leitet das Mikrofon des Tablets an ein lokales Audiomodell
(LFM3-Audio über llama-server) weiter und führt die Home-Assistant-Aktionen aus. Einfache Befehle („schalte das Licht
im Wohnzimmer ein") werden sofort ohne Modell ausgeführt.

```sh
cd gateway && docker compose up -d
```

## 🛠️ Weiterführendes

- 📖 [Technische Referenz](docs/reference.fr.md) (auf Französisch): `dashboard.json`, Kartentypen, Erkennungen,
  AlertTicker, Bearbeitungsmodus, Sprach-Gateway

## 📄 Lizenz

Der Code steht unter der [MIT-Lizenz](LICENSE). Ressourcen Dritter behalten ihre eigene Lizenz:

- 🤖 [M.A.C.S.](https://github.com/glyndavidson/MACS) von Glyn Davidson und
  [hass-hue-icons](https://github.com/arallsopp/hass-hue-icons): **CC BY-NC-SA 4.0**, nur nicht kommerzielle Nutzung
- 🎨 [Material Design Icons](https://pictogrammers.com/library/mdi/) (Apache 2.0),
  [Meteocons](https://github.com/basmilius/weather-icons) (MIT), Schriften Audiowide und
  [Carlito](https://github.com/googlefonts/carlito) (OFL), Themes und Übersetzungen der
  [AlertTicker Card](https://github.com/djdevil/AlertTicker-Card) (MIT)
- 📚 [smbj](https://github.com/hierynomus/smbj) und [nfs-client-java](https://github.com/EMCECS/nfs-client-java)
  (Apache 2.0)
