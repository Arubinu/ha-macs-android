# ❓ FAQ

**English** · [Français](faq.fr.md) · [Deutsch](faq.de.md) · [Español](faq.es.md) · [Italiano](faq.it.md) · [Nederlands](faq.nl.md) · [Português](faq.pt.md) · [Polski](faq.pl.md)

## 📱 Devices

**Which tablets work?**
Any tablet with Android **8.0** or later. Tested daily on a Samsung Galaxy Tab A8, and on a Raspberry Pi 4 running
Android. A modest tablet is enough: the app is native, with no browser behind it.

**Amazon Fire tablets?**
Not tested yet. Fire OS is based on Android and has no Google services: the app should run, the avatar cut-out then uses
the model bundled in the app (edges slightly less clean). Feedback is welcome.

**Raspberry Pi?**
Yes, with Android installed on it: see the [Raspberry Pi section](../README.md#-raspberry-pi) of the README. Use a real
5 V / 3 A power supply: a computer or dock USB port is too weak, the screen loses its signal and a *Power supply too
weak* pill appears.

**Which APK should I download?**
`…-arm64-v8a.apk` for almost every recent tablet and the Raspberry Pi. `…-armeabi-v7a.apk` for older 32-bit tablets.
The universal APK works everywhere but is three times larger.

**Why is the app this large?**
Mostly because of the on-device cut-out of the avatars (Google ML Kit models). The APK of your tablet's architecture
is about 44 MB.

## 🏡 Home Assistant

**Do I need to install anything in Home Assistant?**
No: no custom cards, helpers, templates or automations. The app reads your integrations' entities and computes the rest
itself (presence, best fuel price, weather alerts…). Two things are optional: showing the tablet as a device in Home
Assistant (*mobile_app*, for its sensors and notifications) and the voice satellite.

**Do I need to create a token?**
No. The wizard signs in with your Home Assistant account, from your phone (QR code) or on the tablet, and creates the
token itself. Pasting a token you made yourself also works.

**Does it work away from home?**
Yes, through a reverse proxy (Pangolin, Cloudflare Access…): add its headers in *Settings › Home Assistant*. The app
warns you if an `http://` address is used outside your local network.

**Is my token safe?**
It stays in the app on the tablet. In an exported backup it is encrypted with a key tied to the tablet: on another
tablet the token is asked again, and the access codes must be set again.

## 📹 Cameras

**Which cameras are supported?**
Any Home Assistant `camera.*` entity (snapshot and stream through Home Assistant), or a direct `rtsp://` / HLS address
(go2rtc…). Battery cameras are only woken up when needed, according to their power supply (mains, solar, battery).

**The fullscreen stays black or says the format is not supported.**
Some tablets cannot decode 4K H.265 streams. The app then tries a software decoder, then the card's lighter stream, and
remembers formats the tablet refuses (*Settings › Display* to reset). Choosing the "fluent" stream of the camera for
fullscreen usually solves it.

**Where does the detection history come from?**
From the folder where your recordings are stored: Home Assistant media, an SMB or NFS share, or a folder on the tablet.
Each snapshot is matched with its video.

## 🔒 Kiosk mode and updates

**How do I open the settings?**
Long press the top-left corner of the screen. If a master code is set, it is asked first.

**How do I lock the tablet completely?**
Make the app *device owner* once (one `adb` command, or a QR code after a reset): see
[Kiosk mode](../README.md#-kiosk-mode). Before uninstalling: *Settings › Security › Remove device owner rights*.

**How do updates work?**
The app checks the GitHub Releases every 12 hours and installs the new version. The first time, Android asks you to
allow HA-MACS to *install unknown apps*: the update then continues by itself. As device owner, nothing is asked.

## 🐛 Problems

**The app crashed or something does not work.**
Open *Settings › About › Crash log*: copy it (or download it from a phone with *Settings › Dashboard › Backup with the
phone*) and paste it in a [bug report](https://github.com/Arubinu/ha-macs-android/issues/new/choose). It also lists
video and stream errors, with addresses hidden. Never share your token, codes or a backup file.

**The animations are missing or jerky.**
Android's animations are turned off on the tablet (accessibility setting, or developer options). The app detects it
and shows how to fix it in *Settings › Display*.

## ℹ️ About

**Is it an official Home Assistant app?**
No, it is an independent project. The M.A.C.S. character is the work of
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, non-commercial use).

**Can I help?**
Gladly: bug reports with the crash log, ideas, translations (8 languages today) and tests on other tablets.
