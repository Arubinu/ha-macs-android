# Référence technique

> Détails de configuration pour aller plus loin. Pour une utilisation normale, l'assistant de premier démarrage et
> le mode édition suffisent : rien n'oblige à modifier `dashboard.json` à la main.

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
| `members` | avatars + infos membres | `members[]`, `avatar_url` |
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
| *(réglage)* `master_code` | code maître (vide = aucun), défini dans l'assistant ou Réglages › Sécurité : demandé avant les réglages et pour le menu du kiosque (à défaut, le code d'accès). Après 3 erreurs de code : attente de 30 s, doublée à chaque nouvelle erreur (15 min au plus) |
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
