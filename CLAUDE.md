# HA Dash — notes pour Claude

App Android native (Kotlin / Jetpack Compose, `fr.pergens.hamacs`) pour tablette, qui reproduit le
dashboard Lovelace Home Assistant de l'utilisateur sans dépendre de Lovelace ni de capteurs créés dans HA.

## Règles

- Répondre **uniquement en français**.
- **Token HA** : uniquement dans les prefs de l'app. Ne jamais le committer, ni l'écrire dans un fichier suivi, ni le
  compiler dans l'APK (plus de préremplissage depuis `local.properties` : un APK installé ailleurs l'emportait).
  La config exportée contient le token et le code d'accès : ne pas la publier.
- **Ne jamais actionner de vrais appareils** pendant les tests (lumières, volets, scènes, services…).
- **Réglages globaux** → page Réglages. **Réglages propres à un bloc** → mode édition (éditeur du bloc).
- Ne pas exécuter de code externe téléchargé ; le lire / parser comme donnée est OK.
- **Pas de compatibilité ascendante** tant qu'aucune Release GitHub n'est publiée : pas de migrations ni de
  lecture d'anciens formats. On change le format et on met à jour `dashboard.json` directement.
- Viser l'**autonomie vis-à-vis de HA** : ce qui était calculé par des templates, helpers, automatisations ou
  fichiers `/local` doit être refait dans l'app (entités virtuelles, historique, assets embarqués).
- **Dépôt public** (https://github.com/Arubinu/ha-macs-android) : aucune donnée personnelle versionnée. La vraie
  config de l'utilisateur est un export de l'app dans `private/` (gitignoré, ex. `ha-dashboard-2026-11-05.json`),
  restauré par import ; rien de `private/` n'entre dans l'APK. L'APK n'embarque que l'exemple anonymisé
  `app/src/main/assets/dashboard.json`. Noms de l'exemple : membres alex / sarah / leo / emma, villes
  Valmont, Rochebelle, Bois-Joli, Saint-Florin, Les Tilleuls, Autoroute. Toute modification de structure de la
  config doit être faite dans l'exemple (et l'export de `private/` régénéré si besoin).
- Animations du bandeau des membres : dessinées en Compose (`ui/components/MemberAnimations.kt`, couleur d'accent).
  Ne jamais ajouter de ressource sous licence non redistribuable (ex. Flaticon gratuit) dans le dépôt.
- **README en 8 langues** (`README.md` anglais + `README.<lang>.md`) : courts, avec emojis, même plan partout ; toute
  modification se reporte dans les 8. Libellés des menus = textes réels de l'app (`strings.xml` de la langue).
  Le détail technique va dans `docs/reference.fr.md`.
- Licence MIT pour le code ; `assets/macs/` et `assets/hue.json` restent en CC BY-NC-SA 4.0.

## Environnement (Windows)

- Build : `JAVA_HOME="/c/Program Files/Java/jdk-17" ./gradlew -q assembleDebug` (Bash) → un seul APK
  (`app-debug.apk`). `assembleRelease` → un APK par architecture + un universel (`app-<abi>-release.apk`,
  `app-universal-release.apk` ; arm64 ≈ 44 Mo contre 121 Mo, à cause des bibliothèques ML Kit).
- adb : `$LOCALAPPDATA/Android/Sdk/platform-tools/adb`. Émulateur : AVD `taba8` (`emulator-5554`, 1920×1200).
- **Encodage** : ne jamais mettre de caractères non-ASCII dans un heredoc bash, un `sed` ou un script python
  passé sur stdin (corrompt les fichiers). Utiliser Edit/Write, ou un script python écrit avec Write dans le
  scratchpad et lancé avec `python -I`.
- **Fins de ligne** : tout en **LF** (`.gitattributes`, `core.autocrlf=false` dans le dépôt), sauf `*.bat` en CRLF.
  Écrire les fichiers en LF (`newline='\n'` en python) ; vérifier avec `git ls-files --eol | grep w/crlf`.
- Chaînes : 8 langues (`values`, `-fr`, `-de`, `-es`, `-it`, `-nl`, `-pt`, `-pl`) ; toute nouvelle chaîne
  doit être ajoutée partout.
- Mettre à jour les README (8 langues) quand une fonctionnalité visible change.

## Tests sur l'émulateur (erreurs déjà commises, à ne pas refaire)

- Utiliser `tools/emulator.sh` (`. tools/emulator.sh` puis `open_avatar_editor`, `tapt "texte"`, `shot nom`) au lieu
  d'enchaîner des coordonnées à l'aveugle : une seule touche décalée envoie toute la suite ailleurs (paramètres
  Android ouverts par erreur). Prendre une capture après chaque étape incertaine.
- Git Bash : `export MSYS_NO_PATHCONV=1` avant `adb push … /sdcard/…` ou `adb shell run-as …`, sinon le chemin
  devient `C:/Program Files/Git/sdcard/…`.
- `uiautomator dump` encode les apostrophes (`&apos;`) : comparer après `html.unescape`.
- Un même texte peut apparaître deux fois (« Modifier les blocs » = titre + bouton : prendre le 2ᵉ).
- Le nom d'un onglet (« Membres ») n'est affiché que s'il est actif : toucher son icône (966, 66).
- Les réglages s'ouvrent par un appui long **immobile en (30, 40)** (`input swipe 30 40 30 40 2500`), avec nouvel
  essai si besoin (le premier appui après un changement d'écran est parfois ignoré). Ne pas viser le bord (8, 8) :
  ça ouvre parfois le volet des notifications d'Android (`cmd statusbar collapse` pour le refermer). Toujours
  vérifier que les réglages sont ouverts avant d'enchaîner. Ils rouvrent la dernière section consultée.
- Les commandes Bash tapées ici arrivent en cp1252 (Latin-1) : `tools/emulator.sh` passe le texte en hexadécimal et
  accepte UTF-8 ou Latin-1. Avec `MSYS_NO_PATHCONV=1`, écrire les chemins locaux en `C:/…` (pas `/c/…`).
- Le sélecteur de photos : Retour le ferme entièrement (pas seulement son menu). Après un `adb push` d'image, lancer
  `am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/…` pour qu'elle apparaisse.
- Émulateur : pas de caméra frontale (arrière émulée). Le module ML Kit de détourage des services Google met du
  temps à se télécharger la première fois : en attendant, c'est le détourage « selfie » embarqué qui sert
  (contours moins propres). Après une réinstallation propre, il peut falloir l'attendre à nouveau.
- Sauvegarder les prefs (`run-as … cat shared_prefs/settings.xml`) avant un test destructif et les restaurer après.
  Pour tout l'état de l'app : `adb exec-out run-as fr.pergens.hamacs tar cf - shared_prefs files > sauvegarde.tar`,
  restauration par `push` puis `run-as … tar xf`. Après `pm clear`, redonner l'autorisation caméra.
- Lancer l'app avec `am start -n fr.pergens.hamacs/.MainActivity` : `monkey` ouvre parfois le choix de l'écran
  d'accueil (l'app se déclare HOME pour le kiosque).
- Un clavier ouvert fait remonter les fenêtres : ne pas enchaîner des coordonnées après une saisie. Viser les champs
  par leur libellé (`tapt "Nouveau code (4 à 8 chiffres)"`) et passer au suivant / valider avec `input keyevent 66`.
- `ffmpeg` dans Git Bash est un script qui boucle : appeler `/d/Logiciels/ffmpeg/ffmpeg.exe` (vidéo d'animation :
  `adb shell screenrecord --time-limit 3 …`, puis une mosaïque d'images avec `-vf "fps=20,…,tile=8x4"`).
- Relancer l'émulateur (`emulator -avd taba8 -no-snapshot-save`) recharge un ancien instantané : l'app peut y être
  vierge ou l'ancien paquet réapparaître. Réinstaller, restaurer la dernière sauvegarde `tar` et redonner la caméra.
- Une liste qui défile (`verticalScroll`) dans une colonne qui défile déjà doit toujours avoir une hauteur maximale
  finie, même provisoire, sinon Compose plante (c'est arrivé avec le calendrier des membres).
- **Captures des README** : faux serveur Home Assistant `tools/demo/demo_ha.py` (données fictives) et script
  `tools/demo/capture.sh <langue>` ; procédure complète dans `tools/demo/README.md`. Une série par langue dans
  `docs/screenshots/<langue>/`. Ne jamais faire de captures avec les vraies données de l'utilisateur.

## Architecture

- `assets/dashboard.json` : agencement par défaut (écrans, cartes). `config/ConfigRepository.kt` : prefs,
  import/export, surcharges de blocs.
- **Surcharges de blocs** (`config/BlockOverrides.kt`) : `setBlockField` (champ), `setBlockValue` (option),
  `setBlockConfig` (config complète). Une carte enregistrée depuis son éditeur (ex. AlertTicker, clé `alerts`)
  ne suit plus `dashboard.json` : changer `dashboard.json` ne suffit pas pour une config déjà enregistrée.
- `ha/` : client WebSocket (`HaClient`), historique, `VirtualEntities` (entités `sensor.hadash_*` injectées
  dans `ha.states`, ex. `sensor.hadash_weather_alert_{yellow,orange,red}` depuis la vigilance Météo-France).
- `ui/cards/` : cartes (alertes, caméras, graphiques, membres…). `ui/*Fields.kt` : éditeurs du mode édition.
- `setup/` : assistant de premier démarrage (`HomeSetup.scan/apply`, `SetupWizard`). Les champs du plan
  sont des `mutableStateOf` / `mutableStateList` (sinon le strong skipping saute la recomposition).
- Images embarquées : `assets/images/` (URL `file:///android_asset/...`, laissées telles quelles par `resolveUrl`).
- `gateway/` : passerelle vocale Python (Docker), indépendante de l'app.

## Choix fonctionnels à retenir

- **Membres** : sélection locale (prefs `members`), plus d'`input_select` ; présence via `person.*`, heure
  d'arrivée/départ tirée de l'historique ; icônes des zones lues dans `zone.*`. Code d'accès : global
  (`members:main`, changer de membre) ou, s'il est désactivé, par membre (`member:<id>`). Plus de membres que la
  largeur n'en contient (avatar 80 dp) : flèches et défilement par page. Calendrier : défilement propre, fondu en bas.
- **Code maître** (pref `master_code`, vide = aucun) : distinct du code d'accès (famille, blocs protégés). Demandé à
  l'appui long du coin (`DashState.askSettings`) et pour le menu du kiosque (sinon code d'accès). Défini dans
  Réglages › Sécurité (code actuel exigé pour changer/supprimer) ou à la dernière étape de l'assistant.
- **Connexion à HA** (`ha/HaLogin.kt`, `ui/HaLoginUi.kt`, `ha/HaDiscovery.kt`) : flux OAuth2 de HA puis création
  d'un jeton longue durée par WebSocket (`auth/long_lived_access_token`, nom « HA-MACS · modèle · date », session
  temporaire révoquée). *Téléphone* : QR → `net/LocalHttpServer` de la tablette, `client_id` = `http://ip:port/`
  (HA accepte les IP locales), retour sur `/clé/cb` ; la page permet aussi d'envoyer un jeton collé. *Tablette* :
  WebView, `client_id` `https://pergens.fr/hamacs/`, redirection interceptée (jamais chargée), cookies effacés.
  Serveurs trouvés en mDNS (`_home-assistant._tcp`, pas visible depuis l'émulateur). Tests : `demo_ha.py` simule
  `/auth/authorize`, `/auth/token`, `/auth/revoke` et le jeton longue durée (curl + `adb forward` pour le téléphone).
- **Essais de code** : compteur commun (prefs `pin_fails`, `pin_lock_until`, non exportés) pour tous les codes vérifiés
  par l'app (pavé `PinDialog` + champs « Code actuel » des réglages). Dès 3 erreurs : 30 s, doublé à chaque erreur,
  15 min au plus ; conservé au redémarrage, remis à zéro par un bon code. Code de l'alarme non concerné (vérifié par HA).
  En test : sauvegarder/restaurer les prefs pour ne pas laisser de blocage.
- **Membre favori** : option `favorite` sur `members:main` (réglage « Membre favori » d'un membre), appliqué au
  démarrage et au retour sur l'accueil (`DashState.onHome`). Sans favori, un membre protégé (code pour tous ou code du
  membre) n'est pas gardé : premier membre non protégé, sinon aucun (« Touchez un membre… »).
- **Export** : le jeton HA y est chiffré (`config/DeviceSecret.kt`, AES-GCM, clé tirée d'`ANDROID_ID`) ; il ne se
  déchiffre que sur l'appareil d'origine, ailleurs il est vidé et demandé (`importNeedsToken`). Un jeton en clair
  dans un fichier reste accepté. Les codes (`access_code`, `master_code`, `member_code:<id>`, plus le `access_code`
  racine de l'agencement, retiré de l'agencement exporté) sont chiffrés de la même façon ; `code_groups` regroupe
  les codes identiques. Ailleurs, rien n'est écrit pour eux (pas de 0000) : `codes_to_replace` (pref, conservée
  jusqu'au bout) fait afficher `CodeReplaceDialog` (non fermable), un nouveau code par groupe.
- **Sauvegarde par téléphone** (`ui/PhoneTransferUi.kt`) : QR → `LocalHttpServer` ; la page télécharge l'export
  (`/clé/export`), en envoie un (`/clé/import`, même traitement que l'import de fichier : `MainActivity.applyImport`)
  et propose le journal des plantages (`/clé/crashes`). Boutons dans Réglages › Tableau de bord, l'assistant et le journal.
- **Plantages** (`CrashGuard.kt`) : journal `files/crashes.log` (20 entrées, Réglages › À propos), relance par alarme →
  `RestartReceiver` (sauf 3 plantages en 2 min). Android ne laisse rouvrir l'écran que si l'app est propriétaire,
  écran d'accueil ou autorisée à se superposer (ligne « Relance automatique » de Sécurité). Test (débogage) :
  `am start -n fr.pergens.hamacs/.MainActivity --ez debug_crash true` (`am crash` ne passe pas par le gestionnaire).
- **Mises à jour** (`update/Updater.kt`) : Releases GitHub (`/releases`, préversions si « Inclure les préversions »),
  APK de l'ABI sinon universel, `PackageInstaller` (sans confirmation si propriétaire), `UpdatedReceiver` rouvre l'app.
  Version = tag (`-PappVersion`, release.yml), `versionCode` = 10000·maj + 100·min + patch. Test (débogage) : pref
  `update_url` vers un faux JSON servi depuis le PC (`http://10.0.2.2:…`), APK construit avec `-PappVersion=1.0.1` ;
  revenir ensuite avec `adb install -r -d`. Une release ne met à jour qu'une app signée avec la même clé.
- **Mode nuit** (`ui/NightMode.kt`, prefs `night_*`) : début / fin = `sun` (heure de `next_setting` / `next_rising` de
  `sun.sun`, sinon 21:30 / 7:00) ou `HH:MM`. La nuit, après `night_seconds` : écran noir (`MainActivity.nightBlackout`,
  au-dessus de tout, M.A.C.S. masqué, absorbe le premier contact) ou luminosité `night_level`. En test, couper la
  détection de présence : la caméra émulée bouge et relance l'activité sans cesse.
- **Caméra sur événement** (`ui/CameraEvents.kt`) : options du bloc caméra `event_entity`, `event_seconds`, `event_sound`
  (éditeur de la caméra en mode édition). `CameraEventWatcher` compare l'état précédent complet ; `on` pour un
  binary_sensor, tout nouvel état sinon ; caméra protégée non ouverte ; fermeture si personne n'a touché l'écran.
  Test : `demo_ha.py` → `/demo/set?entity=…&state=…` pousse un changement d'état.
- **Tablette dans HA** (`ha/MobileAppLink.kt`) : intégration `mobile_app` (`/api/mobile_app/registrations`, webhook,
  `register_sensor` / `update_sensor_states` toutes les 60 s), notifications par la WebSocket
  (`mobile_app/push_notification_channel`) : texte → bandeau (`ui/HaBanner.kt`), `TTS` + `tts_text`,
  `command_screen_on`, `command_camera` + `entity`. Webhook et identifiant non exportés (`LOCAL_KEYS`). Test :
  `demo_ha.py` simule l'enregistrement et `/demo/notify?message=…`. Ne pas activer sur le vrai HA sans accord
  (crée un appareil).
- **Lecteur DLNA** (`dlna/DlnaRenderer.kt`, `dlna/Scpd.kt`, prefs `dlna`, non exportées) : MediaRenderer UPnP (SSDP sur
  239.255.255.250:1900, verrou multicast ; HTTP fixe port 49494, chemin `/dlna-<uuid>`), AVTransport, RenderingControl
  (volume « médias » d'Android), ConnectionManager, événements `LastChange` envoyés dans l'ordre (file unique). Lecture
  ExoPlayer ; pastille `ui/DlnaUi.kt` : complète en bas à gauche sur l'accueil, compacte à gauche de la barre d'onglets
  la plus haute ailleurs (`DashState.topMenu`, relevée par `PillMenu`), masquée sur les réglages. Test : `adb forward
  tcp:49494`, requêtes SOAP et SUBSCRIBE depuis le PC (rappel `http://10.0.2.2:…`) ; le multicast SSDP ne traverse pas
  l'émulateur.
- **Satellite Wyoming** (`voice/WyomingSatellite.kt`, prefs `wyoming`) : Réglages › Assistant vocal, système *Passerelle*
  ou *Home Assistant* (exclusifs). Serveur TCP 10700, mDNS `_wyoming._tcp`, `describe`/`info`, `ping`/`pong`,
  `run-satellite` ; appui sur le M.A.C.S. → `run-pipeline` (asr→tts) + audio 16 kHz ; mot d'activation → micro continu,
  `run-pipeline` depuis `wake`. Réception : `transcript`, `synthesize`, audio TTS (écrit dans l'AudioTrack sur une file
  unique : écrire en parallèle fait planter le code natif), `played`. Envois réseau jamais sur le fil principal (file
  `writer`). Démarré dans `App` après `link` (nom de l'appareil). Test : `adb forward tcp:10700`, faux HA en Python.
- **Animations coupées** : `ui/SystemAnimations.kt` lit `ANIMATOR_DURATION_SCALE` ; cause : `remove_animations`
  (réglage système Samsung, accessibilité) sinon échelles des options pour les développeurs. Bandeau (tableau de bord
  seulement) et Réglages › Affichage ouvrent `AnimationsSheet` : *Automatiquement* (WRITE_SECURE_SETTINGS, accordable
  uniquement par adb, aucune page d'Android ne la propose ; commande à copier ; ensuite l'app remet les échelles à 1 à
  chaque retour au premier plan) ou *Moi-même* (étapes guidées, écrans ouverts en tâche neuve, via `Kiosk.suspend` en
  kiosque). Ne jamais accorder la permission sur la tablette de l'utilisateur sans son accord.
- **Préparer la tablette** (`ui/Readiness.kt`) : Réglages › Sécurité (en tête) et une fois après l'assistant s'il manque
  quelque chose : kiosque, propriétaire (commande adb) ou écran d'accueil, relance après plantage, batterie
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), animations. Écrans d'Android ouverts via `Kiosk.suspend` si verrouillé.
- **Quoi de neuf** (`update/WhatsNew.kt`, prefs `whats_new`, non exportées) : au premier démarrage d'une nouvelle
  version, ses notes de Release (gardées à l'installation, sinon relues sur GitHub) s'affichent une fois ; aussi dans
  Réglages › À propos › *Nouveautés*. Rendu Markdown simple (`ui/Markdown.kt`) ; dans les notes, tout ce qui suit
  `<!-- whats-new:end -->` (liste des APK) n'est pas affiché dans l'app. Test : pref `update_url` vers un faux JSON et
  `whats_new.xml` avec `last` = version antérieure.
- **Formats vidéo** : décodeur de repli (logiciel) activé dans ExoPlayer ; flux refusés mémorisés (`ui/VideoCompat.kt`,
  prefs `video`) ; Galaxy Tab A8 : pas de décodage matériel 4K H.265 (flux « Clair » Reolink, enregistrements du Hub).
- **Journal** : `CrashGuard.note` (une fois par clé et par démarrage, adresses passées par `redact`) pour les erreurs de
  lecture vidéo (plein écran, direct, captures) et du lecteur DLNA.
- **Détails au toucher** : graphique à plusieurs courbes → `GraphDetailsSheet` (valeur, écart, 3 derniers changements
  sur 7 jours) ; barre de présence → `PresenceDetailsSheet` (périodes des 24 h).
- **Bouton Dashboard** : `assets/images/dashboard-button.webp` (WebP animé, Android 9+), généré par
  `tools/images/make_dashboard_button.py` depuis les sources de l'utilisateur (vague + texte HD centré).
- **Lecteur multimédia** (`ui/cards/MediaPlayer.kt`, réglages `ui/MediaFields.kt`) : d'après la Mediocre Media Player
  Card. Commandes selon `supported_features` (masquées sinon), progression en direct (`media_position_updated_at`),
  curseurs fins maison (`SlimSlider`, pas le Slider Material), couleur de la pochette. Grande fenêtre (toucher la
  pochette ou le titre) : source, mode son, enceintes (`join` / `unjoin`), favori (entité button), bibliothèque
  (`media_player/browse_media`), recherche (`media_player.search_media`) et file (`music_assistant.get_queue`), ces deux
  derniers avec `return_response`. Démo : `demo_ha.py` simule bibliothèque, recherche, file, pochettes SVG
  (`/demo/cover/…`) et fait réellement changer l'état des lecteurs.
- **Codes des membres** : « Code pour ce membre » (sans « Code pour tous ») → code propre `member_code:<id>`
  (`MemberCodeEditor` dans la fiche du membre) ; sans code propre, le code d'accès est demandé.
- **Onglets favoris** : `"favorite"` d'un switcher (étoile dans l'éditeur des onglets, pastille ✎ sous le rail en
  mode édition). Affiché au démarrage et à chaque retour sur l'écran d'accueil (`start_screen`) ; sans favori,
  l'onglet en cours est gardé.
- **Release** : `.github/workflows/release.yml` (Release publiée → `assembleRelease` signé avec la clé des secrets
  `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` → 5 APK joints).
  En local, la clé peut être donnée dans `local.properties` (`release.store.file`…), sinon clé de debug.
- **Avatars** (`ui/avatar/`, `config/AvatarStore.kt`) : éditeur dans les réglages du membre (mode édition). Source :
  caméra de la tablette (CameraX, dans l'app : rien d'externe en kiosque), fichier (sélecteur de photos), téléphone
  (QR code → mini serveur HTTP local, code aléatoire, un seul envoi), photo HA. Détourage ML Kit (services Google,
  sinon « selfie » embarqué), filtres (aucun, doux, affiche, BD), cadrage aux doigts. Image 1024 enregistrée dans
  l'app avec la photo d'origine, le masque et les réglages (l'éditeur rouvre sur l'avatar existant), le tout dans
  l'export. Photo coupée au sommet du crâne : photo ronde automatique (détourage réactivable). Rendu : cercle 836/1024 en bas, liseré 25/1024, fond #181818 → couleur d'accent ;
  détouré = la tête dépasse (clip cercle ∪ moitié haute), photo = rond, noir et blanc si non sélectionné.
  Cadrage par défaut (repères de l'avatar de l'utilisateur) : haut des pixels opaques à 78/1024, tête jusqu'au cou
  sur 42 % de la largeur ; le bas de la photo ne remonte jamais au-dessus du bas du cercle. La détection de visage
  ML Kit ne reconnaît pas les avatars cartoon : elle ne sert qu'aux photos rondes.
- **Caméras** : une caméra physique = un appareil ; `entity` = flux de la carte, `fullscreen_entity` = flux
  plein écran. L'assistant permet d'ajouter / renommer / supprimer des onglets.
- **Graphiques** : `state_from: min|max` affiche la courbe la plus basse/haute avec son nom (meilleur prix) ;
  bandes jour/nuit sur `sun.sun`.
- **Alertes `persistent`** : heure de déclenchement mémorisée (`{trigger_time}`), rattrapage sur 24 h
  d'historique à la connexion ; la prise en compte (✕) est propre à chaque tablette.
