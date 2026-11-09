# ❓ FAQ

[English](faq.md) · **Français** · [Deutsch](faq.de.md) · [Español](faq.es.md) · [Italiano](faq.it.md) · [Nederlands](faq.nl.md) · [Português](faq.pt.md) · [Polski](faq.pl.md)

## 📱 Appareils

**Quelles tablettes fonctionnent ?**
Toute tablette sous Android **8.0** ou plus récent. Utilisée tous les jours sur une Samsung Galaxy Tab A8, et testée sur
un Raspberry Pi 4 sous Android. Une tablette modeste suffit : l'app est native, sans navigateur derrière.

**Les tablettes Amazon Fire ?**
Pas encore testées. Fire OS est basé sur Android, sans les services Google : l'app devrait fonctionner, le détourage des
avatars utilise alors le modèle intégré à l'app (contours un peu moins nets). Les retours sont les bienvenus.

**Un Raspberry Pi ?**
Oui, avec Android installé dessus : voir la [section Raspberry Pi](../README.fr.md#-raspberry-pi) du README. Utilisez une
vraie alimentation 5 V / 3 A : un port USB d'ordinateur ou de dock est trop faible, l'écran perd le signal et une
pastille *Alimentation trop faible* apparaît.

**Quel APK télécharger ?**
`…-arm64-v8a.apk` pour presque toutes les tablettes récentes et le Raspberry Pi. `…-armeabi-v7a.apk` pour les anciennes
tablettes 32 bits. L'APK universel marche partout mais pèse trois fois plus.

**Pourquoi l'app est-elle si lourde ?**
Surtout à cause du détourage des avatars sur l'appareil (modèles Google ML Kit). L'APK de l'architecture de votre
tablette fait environ 44 Mo.

## 🏡 Home Assistant

**Faut-il installer quelque chose dans Home Assistant ?**
Non : ni cartes personnalisées, ni entrées (helpers), ni modèles, ni automatisations. L'app lit les entités de vos
intégrations et calcule le reste elle-même (présence, carburant le moins cher, vigilance météo…). Deux choses sont en
option : faire apparaître la tablette comme appareil dans Home Assistant (*mobile_app*, pour ses capteurs et ses
notifications) et le satellite vocal.

**Faut-il créer un jeton ?**
Non. L'assistant se connecte avec votre compte Home Assistant, depuis votre téléphone (QR code) ou sur la tablette, et
crée le jeton lui-même. Coller un jeton que vous avez créé fonctionne aussi.

**Ça marche hors de la maison ?**
Oui, derrière un proxy inverse (Pangolin, Cloudflare Access…) : ajoutez ses en-têtes dans *Réglages › Home Assistant*.
L'app prévient si une adresse `http://` est utilisée hors du réseau local.

**Mon jeton est-il en sécurité ?**
Il reste dans l'app, sur la tablette. Dans une sauvegarde exportée, il est chiffré avec une clé liée à la tablette : sur
une autre tablette, le jeton est redemandé et les codes d'accès doivent être choisis à nouveau.

## 📹 Caméras

**Quelles caméras sont prises en charge ?**
Toute entité `camera.*` de Home Assistant (capture et flux par Home Assistant), ou une adresse `rtsp://` / HLS directe
(go2rtc…). Les caméras sur batterie ne sont réveillées qu'au besoin, selon leur alimentation (secteur, solaire,
batterie).

**Le plein écran reste noir ou indique un format non pris en charge.**
Certaines tablettes ne décodent pas les flux 4K H.265. L'app essaie alors un décodeur logiciel, puis le flux plus léger
de la carte, et retient les formats refusés (*Réglages › Affichage* pour tout réinitialiser). Choisir le flux « fluide »
de la caméra pour le plein écran règle le plus souvent le problème.

**D'où vient l'historique des détections ?**
Du dossier où vos enregistrements sont stockés : médias de Home Assistant, partage SMB ou NFS, ou dossier de la tablette.
Chaque capture est associée à sa vidéo.

## 🔒 Mode kiosque et mises à jour

**Comment ouvrir les réglages ?**
Appui long dans le coin en haut à gauche de l'écran. Si un code maître est défini, il est demandé d'abord.

**Comment verrouiller complètement la tablette ?**
Rendre l'app *propriétaire de l'appareil* une fois (une commande `adb`, ou un QR code après une réinitialisation) : voir
[Mode kiosque](../README.fr.md#-mode-kiosque). Avant de désinstaller : *Réglages › Sécurité › Retirer les droits de
propriétaire*.

**Comment se font les mises à jour ?**
L'app consulte les Releases GitHub toutes les 12 heures et installe la nouvelle version. La première fois, Android demande
d'autoriser HA-MACS à *installer des applications inconnues* : la mise à jour reprend ensuite toute seule. En
propriétaire de l'appareil, rien n'est demandé.

## 🐛 Problèmes

**L'app a planté ou quelque chose ne marche pas.**
Ouvrez *Réglages › À propos › Journal des plantages* : copiez-le (ou téléchargez-le depuis un téléphone avec
*Réglages › Tableau de bord › Sauvegarde avec le téléphone*) et collez-le dans un
[signalement](https://github.com/Arubinu/ha-macs-android/issues/new/choose). Il liste aussi les erreurs de vidéo et de
flux, adresses masquées. Ne partagez jamais votre jeton, vos codes ou un fichier de sauvegarde.

**Les animations manquent ou saccadent.**
Les animations d'Android sont coupées sur la tablette (réglage d'accessibilité, ou options pour les développeurs). L'app
le détecte et montre comment corriger dans *Réglages › Affichage*.

## ℹ️ À propos

**Est-ce une app officielle de Home Assistant ?**
Non, c'est un projet indépendant. Le personnage M.A.C.S. est l'œuvre de
[Glyn Davidson](https://github.com/glyndavidson/MACS) (CC BY-NC-SA 4.0, usage non commercial).

**Puis-je aider ?**
Avec plaisir : signalements avec le journal, idées, traductions (8 langues aujourd'hui) et essais sur d'autres tablettes.
