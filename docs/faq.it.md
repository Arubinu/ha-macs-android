# ❓ FAQ

[English](faq.md) · [Français](faq.fr.md) · [Deutsch](faq.de.md) · [Español](faq.es.md) · **Italiano** · [Nederlands](faq.nl.md) · [Português](faq.pt.md) · [Polski](faq.pl.md)

## 📱 Dispositivi

**Quali tablet funzionano?**
Qualsiasi tablet con Android **8.0** o successivo. Usata ogni giorno su un Samsung Galaxy Tab A8 e provata su un
Raspberry Pi 4 con Android. Basta un tablet modesto: l'app è nativa, senza browser dietro.

**Tablet Amazon Fire?**
Non ancora provati. Fire OS si basa su Android, senza i servizi Google: l'app dovrebbe funzionare, e lo scontorno degli
avatar usa allora il modello incluso nell'app (bordi un po' meno puliti). I riscontri sono benvenuti.

**Raspberry Pi?**
Sì, con Android installato: vedi la [sezione Raspberry Pi](../README.it.md#-raspberry-pi) del README. Usa un vero
alimentatore 5 V / 3 A: una porta USB di computer o di dock è troppo debole, lo schermo perde il segnale e compare
l'indicazione *Alimentazione insufficiente*.

**Quale APK scaricare?**
`…-arm64-v8a.apk` per quasi tutti i tablet recenti e il Raspberry Pi. `…-armeabi-v7a.apk` per i vecchi tablet a 32 bit.
L'APK universale funziona ovunque, ma pesa il triplo.

**Perché l'app è così pesante?**
Soprattutto per lo scontorno degli avatar sul dispositivo (modelli Google ML Kit). L'APK dell'architettura del tuo tablet
pesa circa 50 MB. La **versione leggera** (`…-lite-universal.apk`, 15 MB, per tutti i tablet) non ha Google ML
Kit: gli avatar sono allora foto rotonde, senza scontorno; tutto il resto è uguale.

## 🏡 Home Assistant

**Devo installare qualcosa in Home Assistant?**
No: niente schede personalizzate, aiutanti, template o automazioni. L'app legge le entità delle tue integrazioni e
calcola il resto da sola (presenza, carburante più economico, allerte meteo…). Due cose sono facoltative: mostrare il
tablet come dispositivo in Home Assistant (*mobile_app*, per i suoi sensori e le notifiche) e il satellite vocale.

**Devo creare un token?**
No. La procedura guidata accede con il tuo account Home Assistant, dal telefono (codice QR) o sul tablet, e crea il token
da sola. Funziona anche incollare un token creato da te.

**Funziona fuori casa?**
Sì, tramite un proxy inverso (Pangolin, Cloudflare Access…): aggiungi le sue intestazioni in *Impostazioni › Home
Assistant*. L'app avvisa se un indirizzo `http://` è usato fuori dalla rete locale.

**Il mio token è al sicuro?**
Resta nell'app, sul tablet. In un backup esportato è cifrato con una chiave legata al tablet: su un altro tablet il token
viene richiesto di nuovo e i codici di accesso vanno scelti di nuovo.

## 📹 Telecamere

**Quali telecamere sono supportate?**
Qualsiasi entità `camera.*` di Home Assistant (istantanea e flusso tramite Home Assistant), o un indirizzo `rtsp://` /
HLS diretto (go2rtc…). Le telecamere a batteria vengono svegliate solo quando serve, secondo la loro alimentazione (rete,
solare, batteria).

**Lo schermo intero resta nero o indica un formato non supportato.**
Alcuni tablet non riescono a decodificare i flussi 4K H.265. L'app prova allora un decodificatore software, poi il flusso
più leggero della scheda, e ricorda i formati rifiutati (*Impostazioni › Visualizzazione* per azzerarli). Scegliere il
flusso «fluido» della telecamera per lo schermo intero di solito risolve.

**Da dove viene la cronologia dei rilevamenti?**
Dalla cartella in cui sono salvate le registrazioni: media di Home Assistant, una condivisione SMB o NFS, o una cartella
del tablet. Ogni istantanea è associata al suo video.

## 🔒 Modalità kiosk e aggiornamenti

**Come apro le impostazioni?**
Tieni premuto l'angolo in alto a sinistra dello schermo. Se è impostato un codice master, viene chiesto prima.

**Come blocco completamente il tablet?**
Rendi l'app *proprietaria del dispositivo* una volta (un comando `adb`, o un codice QR dopo un ripristino): vedi
[Modalità kiosk](../README.it.md#-modalità-kiosk). Prima di disinstallare: *Impostazioni › Sicurezza › Rimuovi i diritti
di proprietario*.

**Come funzionano gli aggiornamenti?**
L'app controlla le Release di GitHub ogni 12 ore e installa la nuova versione. La prima volta, Android chiede di
consentire a HA-MACS di *installare app sconosciute*: poi l'aggiornamento prosegue da solo. Come proprietaria del
dispositivo, non viene chiesto nulla.

## 🐛 Problemi

**L'app si è chiusa o qualcosa non funziona.**
Apri *Impostazioni › Informazioni › Registro degli arresti anomali*: copialo (o scaricalo da un telefono con
*Impostazioni › Dashboard › Backup con il telefono*) e incollalo in una
[segnalazione](https://github.com/Arubinu/ha-macs-android/issues/new/choose). Elenca anche gli errori video e dei flussi,
con gli indirizzi nascosti. Non condividere mai il token, i codici o un file di backup.

**Le animazioni mancano o scattano.**
Le animazioni di Android sono disattivate sul tablet (impostazione di accessibilità o opzioni sviluppatore). L'app lo
rileva e mostra come rimediare in *Impostazioni › Visualizzazione*.

## ℹ️ Informazioni

**È un'app ufficiale di Home Assistant?**
No, è un progetto indipendente. Il personaggio M.A.C.S. è opera di
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, uso non commerciale).

**Posso aiutare?**
Volentieri: segnalazioni con il registro, idee, traduzioni (8 lingue oggi) e prove su altri tablet.
