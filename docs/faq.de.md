# ❓ FAQ

[English](faq.md) · [Français](faq.fr.md) · **Deutsch** · [Español](faq.es.md) · [Italiano](faq.it.md) · [Nederlands](faq.nl.md) · [Português](faq.pt.md) · [Polski](faq.pl.md)

## 📱 Geräte

**Welche Tablets funktionieren?**
Jedes Tablet mit Android **8.0** oder neuer. Täglich im Einsatz auf einem Samsung Galaxy Tab A8 und getestet auf einem
Raspberry Pi 4 mit Android. Ein einfaches Tablet reicht: Die App ist nativ, ohne Browser dahinter.

**Amazon-Fire-Tablets?**
Noch nicht getestet. Fire OS basiert auf Android, ohne Google-Dienste: Die App sollte laufen, das Freistellen der
Avatare nutzt dann das in der App enthaltene Modell (etwas weniger saubere Kanten). Rückmeldungen sind willkommen.

**Raspberry Pi?**
Ja, mit installiertem Android: siehe den [Abschnitt Raspberry Pi](../README.de.md#-raspberry-pi) der README. Verwenden
Sie ein echtes 5-V-/3-A-Netzteil: Ein USB-Anschluss eines Computers oder Docks ist zu schwach, der Bildschirm verliert
das Signal und eine Anzeige *Stromversorgung zu schwach* erscheint.

**Welche APK soll ich herunterladen?**
`…-arm64-v8a.apk` für fast alle aktuellen Tablets und den Raspberry Pi. `…-armeabi-v7a.apk` für ältere 32-Bit-Tablets.
Die universelle APK läuft überall, ist aber dreimal so groß.

**Warum ist die App so groß?**
Vor allem wegen des Freistellens der Avatare auf dem Gerät (Google-ML-Kit-Modelle). Die APK für die Architektur Ihres
Tablets ist etwa 44 MB groß.

## 🏡 Home Assistant

**Muss ich etwas in Home Assistant installieren?**
Nein: keine benutzerdefinierten Karten, Helfer, Templates oder Automationen. Die App liest die Entitäten Ihrer
Integrationen und berechnet den Rest selbst (Anwesenheit, günstigster Kraftstoffpreis, Wetterwarnungen…). Zwei Dinge
sind optional: das Tablet als Gerät in Home Assistant (*mobile_app*, für seine Sensoren und Benachrichtigungen) und
der Sprachsatellit.

**Muss ich ein Token erstellen?**
Nein. Der Assistent meldet sich mit Ihrem Home-Assistant-Konto an, vom Handy (QR-Code) oder auf dem Tablet, und
erstellt das Token selbst. Ein selbst erstelltes Token einzufügen funktioniert auch.

**Funktioniert es auch unterwegs?**
Ja, über einen Reverse-Proxy (Pangolin, Cloudflare Access…): seine Header unter *Einstellungen › Home Assistant*
eintragen. Die App warnt, wenn eine `http://`-Adresse außerhalb des lokalen Netzes verwendet wird.

**Ist mein Token sicher?**
Es bleibt in der App auf dem Tablet. In einer exportierten Sicherung ist es mit einem an das Tablet gebundenen Schlüssel
verschlüsselt: Auf einem anderen Tablet wird das Token erneut abgefragt und die Zugangscodes müssen neu gewählt werden.

## 📹 Kameras

**Welche Kameras werden unterstützt?**
Jede `camera.*`-Entität von Home Assistant (Schnappschuss und Stream über Home Assistant) oder eine direkte `rtsp://`- /
HLS-Adresse (go2rtc…). Akkukameras werden nur bei Bedarf geweckt, je nach Stromversorgung (Netz, Solar, Akku).

**Das Vollbild bleibt schwarz oder meldet ein nicht unterstütztes Format.**
Manche Tablets können 4K-H.265-Streams nicht dekodieren. Die App versucht dann einen Software-Decoder, danach den
leichteren Stream der Karte, und merkt sich abgelehnte Formate (*Einstellungen › Anzeige* zum Zurücksetzen). Den
„flüssigen“ Stream der Kamera für das Vollbild zu wählen, löst das meist.

**Woher kommt der Erkennungsverlauf?**
Aus dem Ordner, in dem Ihre Aufnahmen liegen: Home-Assistant-Medien, eine SMB- oder NFS-Freigabe oder ein Ordner auf
dem Tablet. Jeder Schnappschuss wird seinem Video zugeordnet.

## 🔒 Kioskmodus und Updates

**Wie öffne ich die Einstellungen?**
Lange auf die obere linke Ecke des Bildschirms drücken. Ist ein Mastercode festgelegt, wird er zuerst abgefragt.

**Wie sperre ich das Tablet vollständig?**
Die App einmal zum *Geräteinhaber* machen (ein `adb`-Befehl oder ein QR-Code nach einem Zurücksetzen): siehe
[Kioskmodus](../README.de.md#-kioskmodus). Vor dem Deinstallieren: *Einstellungen › Sicherheit › Geräteinhaber-Rechte
entfernen*.

**Wie funktionieren Updates?**
Die App prüft alle 12 Stunden die GitHub-Releases und installiert die neue Version. Beim ersten Mal bittet Android
darum, HA-MACS das *Installieren unbekannter Apps* zu erlauben: Danach läuft das Update von selbst weiter. Als
Geräteinhaber wird nichts gefragt.

## 🐛 Probleme

**Die App ist abgestürzt oder etwas funktioniert nicht.**
Öffnen Sie *Einstellungen › Über › Absturzprotokoll*: kopieren (oder vom Handy über *Einstellungen › Dashboard ›
Sicherung mit dem Handy* herunterladen) und in einen
[Fehlerbericht](https://github.com/Arubinu/ha-macs-android/issues/new/choose) einfügen. Es listet auch Video- und
Stream-Fehler auf, mit ausgeblendeten Adressen. Teilen Sie niemals Ihr Token, Ihre Codes oder eine Sicherungsdatei.

**Die Animationen fehlen oder ruckeln.**
Die Android-Animationen sind auf dem Tablet ausgeschaltet (Bedienungshilfe oder Entwickleroptionen). Die App erkennt das
und zeigt unter *Einstellungen › Anzeige*, wie man es behebt.

## ℹ️ Über

**Ist das eine offizielle Home-Assistant-App?**
Nein, es ist ein unabhängiges Projekt. Die Figur M.A.C.S. ist das Werk von
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, nicht kommerzielle Nutzung).

**Kann ich helfen?**
Gern: Fehlerberichte mit dem Absturzprotokoll, Ideen, Übersetzungen (heute 8 Sprachen) und Tests auf anderen Tablets.
