# HA Android Dashboard

Dashboard Home Assistant **natif** pour tablettes Android (8.0+), pensé pour remplacer un dashboard Lovelace
affiché dans un navigateur kiosque : même apparence, mais rendu Jetpack Compose (fluide même sur des tablettes
modestes), connexion WebSocket directe à Home Assistant et assistant vocal local.

- Aucune page web pour l'interface : seules exceptions, le personnage **M.A.C.S.** (copie embarquée de la page de
  son intégration HA, servie localement et pilotée nativement : son rendu ne dépend pas de Home Assistant ;
  `"macs": { "source": "ha" }` utilise la page servie par HA) et les pages externes explicitement embarquées
  (ex. radar Windy, dont le mode, le zoom et la position se règlent en mode édition).
- Si Home Assistant refuse une action (ou est injoignable), un message le signale ; une pastille discrète indique
  depuis quand Home Assistant ne répond plus.
- Configuration **modulaire** en JSON (`dashboard.json`) : écrans, colonnes, cartes, thème. Modifiable dans l'app
  (mode édition), et transférable d'une tablette à l'autre par export / import.
- **Assistant vocal « full audio »** : le micro est diffusé à une passerelle qui transmet l'audio brut à un modèle
  audio local (LFM3-Audio via llama-server) et exécute les actions Home Assistant.
- **Assistant de premier démarrage** : connexion, puis adaptation à la maison — pièces tirées des zones (éclairage,
  température, humidité, 3 appareils), membres tirés des personnes (photo, capteurs du téléphone), caméras regroupées
  par appareil et réparties dans des onglets personnalisables (ajout, renommage, icône, suppression), avec le flux de
  la carte (léger : fluide / SD) et celui du plein écran (le plus défini : net / HD) choisis automatiquement et
  modifiables, éléments principaux (météo, alarme, musique, consommation, capteurs de
  M.A.C.S.), radar Windy centré sur la maison, pages sans équivalent masquées, onglets nommés dans la langue de l'app.
  Si l'agencement fourni correspond déjà à la maison, il peut être gardé tel quel. Une sauvegarde exportée peut aussi
  être importée dès la première étape (connexion, réglages et agencement repris d'un coup). Relançable depuis
  Réglages › Tableau de bord ; tout reste modifiable en mode édition (noms et icônes des onglets compris).
- **Mode kiosque** à la manière de Fully Kiosk : impossible de quitter l'app ou d'éteindre la tablette sans le code.
- Interface en **8 langues** : anglais, français, allemand, espagnol, italien, néerlandais, portugais, polonais
  (langue du système ou choisie dans Réglages › À propos), AlertTicker et son éditeur compris. Les états affichés
  dans les alertes utilisent les traductions de Home Assistant.
- **Aucun capteur à créer dans Home Assistant** : tout est calculé par l'app à partir des entités des intégrations.
  - Membres : présence, heure d'arrivée / de départ et historique tirés de la personne (`person.*`), icône de la
    zone tirée des zones de HA ; animations du bandeau facultatives (`private/`, voir plus bas), icônes sinon.
  - Graphiques : bande jour / nuit d'après le soleil (`sun.sun`) ; valeur affichée = la plus basse ou la plus haute
    des courbes avec son nom (`state_from: min | max`, ex. meilleur prix carburant parmi plusieurs stations).
  - M.A.C.S. : température et vent repris de l'entité météo quand aucun capteur n'est choisi.
  - Alertes Météo-France : entités de l'app `sensor.hadash_weather_alert_yellow|orange|red` (nombre de phénomènes,
    attribut `names`) calculées à partir du capteur de vigilance de l'intégration Météo-France.
  - AlertTicker `persistent` : l'heure du déclenchement est mémorisée (`{trigger_time}`) et un déclenchement survenu
    tablette éteinte est retrouvé dans l'historique (24 h) — ex. boîte aux lettres, sans input_text ni automatisation.

## Structure

| Dossier | Contenu |
|---|---|
| `app/` | Application Android (Kotlin, Jetpack Compose) |
| `app/src/main/assets/dashboard.json` | Configuration par défaut embarquée (exemple anonymisé) |
| `private/assets/` | Fichiers personnels non versionnés, prioritaires sur ceux de `app/src/main/assets/` |
| `gateway/` | Passerelle vocale Python (`voice_gateway.py`, Dockerfile) |
| `tools/gen-icons.mjs` | Génère les tables d'icônes MDI / Hue à partir des sources amont |

## Compiler l'application

Prérequis : Android SDK (plateforme 36) et **JDK 17** (Gradle 8.14 ne tourne pas sur le JDK 25 d'Android Studio).

```sh
# local.properties (non versionné)
sdk.dir=/chemin/vers/Android/Sdk
ha.url=http://homeassistant.local:8123
ha.token=<jeton d'accès longue durée>     # valeur par défaut, modifiable dans l'app
voice.url=ws://<hôte-passerelle>:8765/ws

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Fichiers personnels (`private/`)

Le dossier `private/` n'est pas versionné. Au build, `private/assets/` est ajouté aux assets de l'app et **remplace**
les fichiers du même nom : `private/assets/dashboard.json` sert ainsi de configuration par défaut à la place de
l'exemple du dépôt. On peut aussi y mettre des images sous licence non redistribuable, par exemple les animations
du bandeau des membres (`images/icons/earth.gif`, `running.gif`, `battery-charged.gif`, `half-battery.gif`) ;
sans elles, des icônes s'affichent à la place.

Dans l'application, un **appui long sur le coin supérieur gauche** ouvre les réglages : URL et jeton Home Assistant,
URL de la passerelle vocale, couleur, écran toujours allumé, langue, export / import de la configuration.

### Mode kiosque

Réglages › Sécurité › **Mode kiosque** : le tableau de bord reste affiché. Le bouton Retour, ou l'appui long en haut à
gauche, demande le code d'accès puis ouvre un menu : réglages du tableau de bord, quitter le kiosque (il reprend dès le
retour dans l'app, ou après 5 minutes), paramètres Android, redémarrer, éteindre (menu d'alimentation autorisé une
minute).

- **Verrouillage complet** (recommandé) : l'app doit être *propriétaire de l'appareil*, à faire une fois depuis un
  ordinateur, aucun compte (Google, Samsung…) ne devant être connecté sur la tablette pendant la commande :
  `adb shell dpm set-device-owner fr.arubinu.hadash/.kiosk.KioskAdmin`. Les boutons Accueil et Applications
  récentes, le volet des notifications et le menu d'alimentation sont alors bloqués ; l'app devient l'écran d'accueil
  et démarre avec la tablette. Avant de désinstaller l'app : Réglages › Sécurité › **Retirer les droits de
  propriétaire**. Le redémarrage forcé matériel (appui très long sur le bouton marche) reste toujours possible.
- **Verrouillage simple** (sans la commande) : épinglage d'écran Android ; activer « Demander le code avant de retirer
  l'épingle » dans les paramètres Android, et choisir l'app comme écran d'accueil.

## Configuration (`dashboard.json`)

Le fichier embarqué sert de configuration par défaut ; les changements faits dans l'app (mode édition) s'y ajoutent
sur la tablette. Pour la transférer ou la sauvegarder : Réglages › Tableau de bord › **Exporter** / **Importer**
(un seul fichier contenant les réglages, l'agencement et les réglages des blocs — il contient aussi le jeton
d'accès et le code : à garder en lieu sûr).

```jsonc
{
  "start_screen": "home",
  "idle_screen": "home", "idle_seconds": 300,        // retour automatique à l'accueil
  "theme": { "card_radius": 0, "colors": { "background": "#000", "accent": "rgb(140,245,255)" } },
  "macs": { "temperature_sensor": "sensor.temperature_ext", "weather_entity": "weather.maison", "battery": "device" },
  "popups": { "appletv": { "name": "Télécommande", "cards": [ { "type": "remote", "entity": "remote.salon" } ] } },
  "screens": {
    "home":      { "layout": "overlay", "cards": [ { "type": "macs", "position": "fill" }, { "type": "clock", "position": "top_start" } ] },
    "dashboard": { "layout": "columns", "columns": [ { "cards": [ … ] }, { "cards": [ … ] } ] }
  }
}
```

### Types de cartes

| Type | Équivalent Lovelace | Options principales |
|---|---|---|
| `vertical`, `horizontal` | vertical/horizontal-stack | `cards`, `gap`, `weight` |
| `switcher` | state-switch (hash) + embedded-view + menu | `id`, `nav` (`pills`/`rail`/`none`), `default`, `pages[{id,icon,label,cards}]` |
| `room`, `room_grid` | button-card « pièce » | `name`, `icon`, `entity`, `info[{icon,entity,decimals,suffix}]`, `buttons[]`, `columns` |
| `button` | button-card rond | `entity`, `icon`, `color` (`first`/`second`/`third`/css), `icon_on`, `icon_<état>`, `tap_action` |
| `alarm` | alarmo-card | `entity`, `name` |
| `clock_weather` | clock-weather-card | `entity`, `days`, `animated_icons` (en-tête, défaut `true`), `forecast_animated_icons` (défaut `false`) |
| `clock` | clock | `seconds` |
| `macs` | macs-card | `height`/`aspect_ratio`, `tap_action` (défaut : parler) |
| `camera` | hash-timer + picture-entity | `entity`, `battery`, `refresh`, `image_url`, `aspect_ratio` |
| `camera_detections` | camera-detection-slider | `minutes`, `storage`, `cameras[]`, `time_source`, `time_format`, `pairing`… (voir ci-dessous) |
| `members` | avatars + infos membres | `selector`, `members[]`, `avatar_url` |
| `presence_history` | waterfall-history-card | `entity`, `hours`, `segments` |
| `calendar` | calendar-card-pro | `entities`, `days` |
| `power_graph` | plotly-graph (conso + jour/nuit) | `entity`, `hours`, `height` |
| `mini_graph` | mini-graph-card | `entity`, `hours`, `decimals`, `aggregate`, `color` ; ou plusieurs courbes : `entities` (`name`, `color`, `show_graph`, `show_line`, `show_legend`, `y_axis`, `smoothing`), `hours_to_show`, `points_per_hour`, `aggregate_func`, `state_map`, `show` (`fill`, `name`, `state`, `legend`), `fade_edges` |
| `swipe` | simple-swipe-card | `cards`, `enable_auto_swipe`, `auto_swipe_interval`, `swipe_effect` (`slide`, `fade`, `instant`), `show_pagination` ; glissement au doigt et indicateur à points |
| `media_player` | mediocre-media-player-card | `entity` |
| `remote` | télécommande Apple TV | `entity`, `media_player`, `apps` |
| `alert_ticker` (ou `ticker`) | [AlertTicker Card](https://github.com/djdevil/AlertTicker-Card) | mêmes options que la carte Lovelace (voir ci-dessous) |
| `webview` | iframe | `url`, `aspect_ratio` (adresse modifiable en mode édition) |
| `windy` | iframe Windy | `url` (embed.windy.com), `aspect_ratio` ; mode, zoom et position réglables en mode édition |
| `title`, `rule`, `spacer` | markdown / séparateurs | `text` |

Actions (`tap_action` / `hold_action`) : `toggle`, `more-info`, `navigate` (`#page` ou `#popup`), `screen`, `popup`,
`call-service` / `perform-action`, `voice`, `none`. Ajouter `"pin": true` protège l'action par le code d'accès.

Options communes utiles :

| Option | Rôle |
|---|---|
| `access_code` (racine) | code d'accès initial (ensuite modifiable dans Réglages › Sécurité) |
| `radius` | arrondi d'un bloc (défaut : `rounded_radius` du thème pour pièces, caméras, alertes, pages web) |
| `space_after` | écart sous un bloc dans une colonne `"fit": true` |
| `flex`, `min_height` | le bloc occupe l'espace restant d'une colonne `"fit": true` |
| `layer: "front"`, `overflow_bottom` | (M.A.C.S.) au premier plan, débordant vers le bas par-dessus les blocs voisins |
| `always_live`, `live_seconds`, `motion`, `motion_delay` | (caméra) direct permanent, durée du direct après un appui, capteurs de détection |
| `loading_image`, `offline_image`, `error_image` | (caméra) images d'attente, hors ligne et flux indisponible ; par défaut, écrans dessinés et traduits (« Chargement ... », « Caméra hors ligne ») |
| `accent_hue` | (image) image pensée pour l'accent d'origine, recolorée avec la couleur principale |
| `fade_edges` | (`power_graph`, `mini_graph`) bords gauche, droit et bas en fondu |

La **couleur principale** (Réglages › Affichage) remplace l'accent du thème sur tout le tableau de bord : boutons
actifs, contours, graphiques, et M.A.C.S. (yeux, bouche, sourcils, effet batterie compris).

**M.A.C.S.** se règle en mode édition (toucher le personnage), avec les options de l'éditeur de sa carte : entité
d'humeur, satellite Assist (écoute / réflexion, puis content ou perplexe), tristesse quand la batterie est faible,
capteurs de température, de vent et de précipitations (unité automatique ou imposée, plage min / max ramenée à 0–100 ;
plages par défaut de la carte : 5–30 °C, 10–50 mph, 0–10 mm ou 0–100 %), entité météo pour les conditions, batterie
(tablette, capteurs de niveau en % ou V et de charge, intégration M.A.C.S. ou aucune), nombre d'échanges affichés,
luminosité automatique (délai, minimum, maximum, pause des animations) et animations. Sans capteur, les entités de
l'intégration M.A.C.S. (`number.macs_temperature`…) sont utilisées. Clés équivalentes dans `"macs"` de
`dashboard.json` : `mood_entity`, `assist_satellite`, `low_battery_sad`, `temperature_sensor` / `_unit` / `_min` /
`_max` (idem `wind_*`, `precipitation_*`), `weather_entity`, `battery` (`device`, `sensor`, `integration`, `none`),
`battery_sensor`, `battery_unit`, `battery_min`, `battery_max`, `charging_sensor`, `max_turns`,
`auto_brightness_enabled`, `auto_brightness_timeout_minutes`, `auto_brightness_min`, `auto_brightness_max`,
`auto_brightness_pause_animations`, `animations_enabled`.

### Dernières détections

La carte `camera_detections` affiche les captures récentes, depuis le stockage choisi (`storage.type`) :

| `type` | Paramètres | Remarque |
|---|---|---|
| `ha_media` | `path` (`media-source://…`), `url_prefix` (facultatif, ex. `/local/media/Enregistrements`) | pas de date de modification |
| `smb` | `host` (port possible), `share`, `path`, `user` (vide = invité), `password`, `domain` | SMB 2/3 |
| `nfs` | `host`, `export`, `path`, `uid`, `gid` | NFS v3 ; l'export doit accepter les ports non privilégiés (`insecure`) |
| `local` | `uri` (dossier choisi dans l'app), `path` | stockage de la tablette, carte SD, clé USB |
| `entities` | — (chaque caméra a son `entity`, `image.*` ou `camera.*`) | dernière capture de chaque caméra, sans vidéo |

- `cameras` : `[{ "name": "Entrée", "match": "Argus 1", "mode": "prefix" | "suffix" | "contains" | "all", "folder": "" }]`
  — plusieurs caméras peuvent partager un dossier, chacune reconnue par un préfixe ou suffixe de ses fichiers.
- `minutes` : durée d'affichage d'une capture après la détection.
- `time_source` : `name` (date dans le nom, `time_format` vide = détection automatique, sinon motif comme
  `yyyyMMdd_HHmmss`) ou `modified` (date de modification du fichier).
- `pairing` : association capture ↔ vidéo, `auto` (même nom, sinon vidéo de la même caméra démarrée juste avant),
  `name` ou `time` ; `image_affix` / `video_affix` : texte propre aux captures ou aux vidéos, ignoré pour comparer les
  noms (ex. `_snap`). `image_ext`, `video_ext` : extensions reconnues.

Tout se règle en mode édition (toucher la carte), avec un bouton **Tester** qui indique le nombre de fichiers lus, de
captures récentes par caméra et la dernière capture trouvée. Les fichiers SMB / NFS sont téléchargés à la demande dans
le cache (vidé après un jour).

### AlertTicker

Portage natif de l'AlertTicker Card v1.3.19 : les clés de configuration sont celles de la carte Lovelace (une
configuration copiée depuis Home Assistant fonctionne telle quelle, `card_mod` excepté). Pris en charge : les 63 thèmes
et leurs animations, les 13 transitions de cycle, entité unique ou filtre multi-entités (`entity_filter`, `device_class`,
`label_filter`, `area_filter`, exclusions), conditions ET/OU, `on_change`, `trigger_delay`, `auto_dismiss_after`,
`persistent`, `time_range`, regroupement, snooze (menu ou durée fixe), historique, son, synthèse vocale
(`tts.speak` ou `notify`), notifications push, bannière overlay sur les autres écrans, templates `{{ }}` rendus par HA,
mode test, état « tout va bien » : message, horloge (4 styles), météo (8 styles, fond animé selon la condition :
soleil, nuit étoilée avec lune et aurore, nuages, brouillard, vent, pluie, neige, grêle, orage), prévisions sur 7 jours
et alternance météo / prévisions.

Options propres à l'app (équivalent du `card_mod`) : `position`, `margin`, `max_width`, `radius`,
`large_button_size`, `large_button_right`, `content_padding_right`, `counter_top`, `counter_right`.

En mode édition, toucher la carte ouvre son éditeur complet (comme l'éditeur visuel de Lovelace), avec aperçu en direct
et un bouton pour importer la configuration de la carte depuis Home Assistant.

### Mode édition

Réglages › Tableau de bord › **Modifier les blocs** : toucher un bloc ouvre ses réglages au lieu de l'actionner
(« Demander le code d'accès », « Flux permanent » pour les caméras, « Hauteur du graphique » pour les graphiques). Le code
d'accès peut protéger tout bloc qui agit sur une entité : boutons des pièces, caméras, alarme, lecteur multimédia,
télécommande, choix du membre. Ces choix sont enregistrés sur la tablette, par-dessus `dashboard.json`.

La section **Entités** de chaque bloc permet de remplacer les entités qu'il utilise (entité principale, batterie et
capteurs de mouvement d'une caméra, lecteur de la télécommande, informations d'une pièce, personne, capteurs et
calendriers d'un membre, entité météo…), avec recherche et filtre par type ; « Rétablir » revient à `dashboard.json`.
Le remplacement s'applique à tous les blocs qui utilisent la même entité d'origine, et les réglages du bloc (code
d'accès, flux permanent, hauteur) suivent la nouvelle entité.

Exception : un graphique à plusieurs courbes (`mini_graph` avec `entities`) se règle carte par carte, sans toucher
aux autres graphiques qui partagent une entité : titre, valeur affichée en grand, courbes (nom affiché, entité,
ajout, suppression ; couleurs de la carte d'origine) et bande jour/nuit.

Un **carrousel** (`swipe`) ouvre d'abord sa propre fenêtre : liste de ses cartes (ouvrir, monter, descendre,
retirer, ajouter un graphique en choisissant sa valeur principale, les autres réglages reprenant ceux du premier
graphique) et options de défilement (automatique, durée, transition, indicateur à points). Une carte ouverte
depuis le carrousel y ramène quand on la ferme ; le défilement automatique est suspendu en mode édition.

Les **pièces** (`room_grid`) : toucher une pièce (ou l'un de ses boutons) ouvre son éditeur — nom, icône, entité,
2 capteurs au plus (icône, entité, décimales), 3 appareils au plus (entité, icône, icône quand actif et icônes
par état comme `icon_movement`, couleur du cercle actif, action au toucher : allumer/éteindre, détails, aucune ou
ouvrir une popup comme la télécommande, code d'accès), position dans la grille et suppression. Une tuile
« Ajouter une pièce » termine la grille en mode édition.

Les **onglets de caméras** (`switcher` ne contenant que des caméras) : en mode édition, une pilule ✎ au bout de la
barre d'onglets ouvre la liste des onglets (nom, nombre de caméras, onglet par défaut, ordre, suppression, ajout) ;
toucher un onglet ouvre sa fenêtre (icône, nom, onglet par défaut, ses caméras : réglages, ordre, retrait, ajout).
Fermer une fenêtre ouverte depuis une autre ramène à la précédente (caméra → onglet → liste des onglets). Une caméra ajoutée reprend les réglages de la première caméra, et sa
batterie et ses capteurs de mouvement sont trouvés sur le même appareil dans Home Assistant. La batterie et les
capteurs de mouvement d'une caméra peuvent aussi être ajoutés depuis sa fenêtre de réglages.

Les réglages globaux (connexion, affichage, veille, code d'accès…) restent dans la page Réglages.

## Passerelle vocale

```sh
cd gateway
pip install -r requirements.txt
HA_URL=http://homeassistant.local:8123 HA_TOKEN=… LLM_URL=http://llama-server:8081 python voice_gateway.py
# ou : docker compose up -d
```

Fonctionnement par défaut (`MODE=primed`) :

1. la tablette diffuse le micro (PCM 16 kHz) en WebSocket et détecte la fin de parole (500 ms de silence) ;
   dès le début de la phrase, la passerelle réveille le modèle s'il était en veille ;
2. une transcription rapide (~0,35 s) sert à afficher « j'ai entendu » et à reconnaître les **commandes simples**
   (« allume/éteins/ouvre/ferme … », « quelle est la température … »), exécutées immédiatement sans modèle ;
3. sinon, **un seul appel** au modèle audio avec l'audio original + les entités pertinentes et leur état ;
4. la réponse s'affiche dans les bulles du M.A.C.S. et est **prononcée** via `/tts` de llama-server en streaming
   (llama.cpp ≥ 0.8.0 avec LFM3-Audio) ; le visage du M.A.C.S. reflète l'émotion de la réponse.

Mesuré sur une question simple : premier son ~0,5 s après la fin de la phrase.

| Variable | Défaut | Rôle |
|---|---|---|
| `MODE` | `primed` | `primed` (ASR + un appel audio) ou `audio` (audio seul, le modèle cherche via outils) |
| `ENTITY_SOURCE` | `auto` | `assist` (exposition Assist de HA), `all` (domaines autorisés), `auto` |
| `CANDIDATES` | `10` | entités proposées au modèle en mode `primed` |
| `CONFIRM_MODE` | `fast` | `fast` : pas de second appel au modèle après une action réussie |
| `TRANSCRIBE` | `after` | transcription d'affichage en mode `audio` : `after`, `parallel`, `off` |
| `ALLOWED_DOMAINS` | lumières, prises, volets… | domaines que le modèle a le droit de piloter |
| `FAST_INTENTS` | `1` | commandes simples traitées localement, sans appel au modèle |
| `TTS` | `1` | réponse parlée via `/tts` de llama-server |
| `TTS_VOICE` | — | voix prédéfinie du modèle (ex. `female`, `male`) |
| `TTS_VOICES_DIR` | — | dossier d'extraits de référence par émotion (`neutral.wav`, `happy.wav`, `sad.wav`) pour le clonage de voix |

Outils de test :

- `gateway/test_client.py question.wav` simule une tablette : temps de réponse, premier son, audio dans `reponse.wav` ;
- `gateway/test_intents.py "allume la lumière du salon" …` montre ce que ferait la reconnaissance locale, sans rien exécuter.

## Licence des ressources

Le code de l'app et de la passerelle est sous licence [MIT](LICENSE). Les ressources tierces gardent leur licence :

Icônes [Material Design Icons](https://pictogrammers.com/library/mdi/) (Apache 2.0),
[hass-hue-icons](https://github.com/arallsopp/hass-hue-icons) (CC BY-NC-SA 4.0, tracés dans `assets/hue.json`), [Meteocons](https://github.com/basmilius/weather-icons) (MIT), polices Audiowide et
[Carlito](https://github.com/googlefonts/carlito) (OFL), thèmes, traductions et modèles d'alertes de
l'[AlertTicker Card](https://github.com/djdevil/AlertTicker-Card) (MIT), bibliothèques
[smbj](https://github.com/hierynomus/smbj) et [nfs-client-java](https://github.com/EMCECS/nfs-client-java) (Apache 2.0).

Le dossier `app/src/main/assets/macs/` est une copie non modifiée de la page du personnage
[M.A.C.S.](https://github.com/glyndavidson/MACS) de Glyn Davidson, sous licence
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) : usage non commercial uniquement, avec
attribution, et redistribution de ces fichiers sous la même licence. Avec hass-hue-icons, sous la même licence, l'app ne doit donc
pas être vendue avec ces ressources.

Les animations du bandeau des membres viennent de [Flaticon](https://www.flaticon.com/animated-icons) : leur licence
gratuite ne permet pas de les redistribuer telles quelles, elles ne sont donc pas dans le dépôt (voir `private/`).
