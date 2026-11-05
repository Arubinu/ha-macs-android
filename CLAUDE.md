# HA Dash — notes pour Claude

App Android native (Kotlin / Jetpack Compose, `fr.arubinu.hadash`) pour tablette, qui reproduit le
dashboard Lovelace Home Assistant de l'utilisateur sans dépendre de Lovelace ni de capteurs créés dans HA.

## Règles

- Répondre **uniquement en français**.
- **Token HA** : uniquement dans `local.properties` (gitignoré) et dans les prefs de l'app. Ne jamais le committer,
  ni l'écrire dans un fichier suivi. La config exportée contient le token et le code d'accès : ne pas la publier.
- **Ne jamais actionner de vrais appareils** pendant les tests (lumières, volets, scènes, services…).
- **Réglages globaux** → page Réglages. **Réglages propres à un bloc** → mode édition (éditeur du bloc).
- Ne pas exécuter de code externe téléchargé ; le lire / parser comme donnée est OK.
- **Pas de compatibilité ascendante** tant qu'aucune Release GitHub n'est publiée : pas de migrations ni de
  lecture d'anciens formats. On change le format et on met à jour `dashboard.json` directement.
- Viser l'**autonomie vis-à-vis de HA** : ce qui était calculé par des templates, helpers, automatisations ou
  fichiers `/local` doit être refait dans l'app (entités virtuelles, historique, assets embarqués).
- **Dépôt public** (https://github.com/Arubinu/ha-macs-android) : aucune donnée personnelle versionnée. La vraie
  config de l'utilisateur est dans `private/assets/dashboard.json` (gitignoré, prioritaire au build sur l'exemple
  anonymisé `app/src/main/assets/dashboard.json`). Noms de l'exemple : membres alex / sarah / leo / emma, villes
  Valmont, Rochebelle, Bois-Joli, Saint-Florin, Les Tilleuls, Autoroute. Toute modification de structure de la
  config doit être faite dans **les deux** fichiers.
- `private/assets/images/icons/*.gif` : animations Flaticon (non redistribuables), jamais dans le dépôt.
- Licence MIT pour le code ; `assets/macs/` et `assets/hue.json` restent en CC BY-NC-SA 4.0.
- Tester la version publique : déplacer temporairement `private/` hors du projet avant le build.
- **Avatars** (`/local/images/avatars/`) : encore lus depuis HA, l'utilisateur a une demande à venir à ce sujet.

## Environnement (Windows)

- Build : `JAVA_HOME="/c/Program Files/Java/jdk-17" ./gradlew -q assembleDebug` (Bash).
- adb : `$LOCALAPPDATA/Android/Sdk/platform-tools/adb`. Émulateur : AVD `taba8` (`emulator-5554`, 1920×1200).
- **Encodage** : ne jamais mettre de caractères non-ASCII dans un heredoc bash, un `sed` ou un script python
  passé sur stdin (corrompt les fichiers). Utiliser Edit/Write, ou un script python écrit avec Write dans le
  scratchpad et lancé avec `python -I`.
- **Fins de ligne** : tout en **LF** (`.gitattributes`, `core.autocrlf=false` dans le dépôt), sauf `*.bat` en CRLF.
  Écrire les fichiers en LF (`newline='\n'` en python) ; vérifier avec `git ls-files --eol | grep w/crlf`.
- Chaînes : 8 langues (`values`, `-fr`, `-de`, `-es`, `-it`, `-nl`, `-pt`, `-pl`) ; toute nouvelle chaîne
  doit être ajoutée partout.
- Mettre à jour le `README.md` quand une fonctionnalité visible change.

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
  d'arrivée/départ tirée de l'historique ; icônes des zones lues dans `zone.*`.
- **Caméras** : une caméra physique = un appareil ; `entity` = flux de la carte, `fullscreen_entity` = flux
  plein écran. L'assistant permet d'ajouter / renommer / supprimer des onglets.
- **Graphiques** : `state_from: min|max` affiche la courbe la plus basse/haute avec son nom (meilleur prix) ;
  bandes jour/nuit sur `sun.sun`.
- **Alertes `persistent`** : heure de déclenchement mémorisée (`{trigger_time}`), rattrapage sur 24 h
  d'historique à la connexion ; la prise en compte (✕) est propre à chaque tablette.
