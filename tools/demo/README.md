# Démo et captures d'écran

`demo_ha.py` est un faux serveur Home Assistant qui sert des données fictives à l'app, pour les captures d'écran
des README sans exposer de vraie maison. Il lit les entités de la config d'exemple
(`app/src/main/assets/dashboard.json`) et invente des valeurs plausibles et stables (historiques, prévisions,
calendriers, caméras, flux vidéo). Les actions envoyées par l'app sont acquittées sans rien piloter.

```sh
pip install aiohttp
python tools/demo/demo_ha.py      # écoute sur le port 8123
```

Dans l'app (émulateur) : adresse `http://10.0.2.2:8123`, jeton quelconque (ex. `demo`).

Pour refaire les captures (une série par langue de l'app) :

1. sauvegarder les données de l'app sur l'émulateur (`adb exec-out run-as fr.arubinu.hadash tar cf - shared_prefs files`) ;
2. compiler la version publique (dossier `private/` déplacé hors du projet), l'installer, `pm clear`, puis écrire
   `shared_prefs/settings.xml` avec `ha_url`, `ha_token`, `setup_done` et `presence_detection` à `false` ;
3. lancer le faux serveur, créer les avatars des membres avec l'éditeur de l'app (photos CC0), puis sauvegarder cet
   état dans `$DEMO_WORK/demo_base.tar` (même commande `tar` qu'en 1) et arrêter le serveur ;
4. `DEMO_WORK=… sh tools/demo/capture.sh <langue>` pour chaque langue (`en fr de es it nl pt pl`) : config
   d'exemple traduite (`translate_dashboard.py`, textes dans `translations.json`), événements du faux serveur
   traduits (`DEMO_LANG`), langue de l'app réglée ;
5. enregistrer en JPEG 1440 px dans `docs/screenshots/<langue>/` ;
6. réinstaller la version personnelle et restaurer les données sauvegardées en 1 (`tar xf`), puis redonner
   l'autorisation caméra (`pm clear` la retire).

## Images

Images des caméras (`cameras/`, recadrées et horodatées) d'après des photos de Wikimedia Commons du domaine public
ou en CC0. Le flux vidéo de la caméra « en direct » (`stream/`, non versionné) est généré au démarrage avec ffmpeg
s'il est installé.

- [Sidewalk Leads to Front Porch with Chair Near Door](https://commons.wikimedia.org/wiki/File:Sidewalk_Leads_to_Front_Porch_with_Chair_Near_Door_-_DPLA_-_71848c65252a2cc89f232fa8f946b63a.jpg) (domaine public)
- [Interior view, first floor, hallway — W. K. Bowman-O'Neal Apartment Building](https://commons.wikimedia.org/wiki/File:Interior_view,_first_floor,_detail,_hallway_looking_north_-_W._K._Bowman-O%27Neal_Apartment_Building,_4313_East_Kellogg_Street,_Wichita,_Sedgwick_County,_KS_HABS_KANS,87-WICH.V,1-35.tif) (domaine public, HABS)
- [Plants on balcony D220722](https://commons.wikimedia.org/wiki/File:Plants_on_balcony_D220722.jpg) (domaine public)
- [Homestead, automobile parked in the driveway](https://commons.wikimedia.org/wiki/File:Homestead,_automobile_parked_in_the_driveway(GN11497).jpg) (CC0)

Avatars des captures, photos CC0 d'Unsplash via Wikimedia Commons (non versionnées) :
[Man vanguard glasses outdoors](https://commons.wikimedia.org/wiki/File:Man_vanguard_glasses_outdoors_(Unsplash).jpg),
[Happy daughter](https://commons.wikimedia.org/wiki/File:Happy_daughter_(Unsplash).jpg),
[Always smile](https://commons.wikimedia.org/wiki/File:Always_smile_(Unsplash).jpg),
[Laughing in Journey t-shirt](https://commons.wikimedia.org/wiki/File:Laughing_in_Journey_t-shirt_(Unsplash).jpg).
