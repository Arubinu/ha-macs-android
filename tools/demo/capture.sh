#!/bin/sh
# Captures d'écran d'une langue pour les README : sh tools/demo/capture.sh <langue>
#   DEMO_WORK : dossier de travail contenant demo_base.tar (données de l'app en mode démo, avatars compris,
#               voir tools/demo/README.md) ; les captures sont écrites dans $DEMO_WORK/shots/<langue>/.
# L'app (version publique) doit être installée sur l'émulateur ; le faux serveur est lancé ici dans la langue voulue.
# Navigation par coordonnées (tablette 1920 × 1200) : les textes changent avec la langue.
L="$1"
# ONLY=main|history|avatar : seulement ces captures (main = accueil, tableau de bord, mur, membres, caméras).
want() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb}"
WORK="${DEMO_WORK:?DEMO_WORK non défini}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
# Git Bash : chemins au format C:/… pour le Python de Windows et adb (MSYS_NO_PATHCONV ne convertit plus /c/…).
if command -v cygpath >/dev/null 2>&1; then REPO="$(cygpath -m "$REPO")"; WORK="$(cygpath -m "$WORK")"; fi
OUT="$WORK/shots/$L"
mkdir -p "$OUT"

shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png"; }

# Touche le premier élément dont le texte commence par $1 (ASCII), d'après uiautomator : les libellés changent de
# largeur selon la langue (« Hoy » / « Aujourd'hui » décalent « 7 jours »).
tap_prefix() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  b=$("$ADB" shell cat /sdcard/ui.xml | tr '>' '\n' | grep "text=\"$1" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')
  set -- $b
  [ -n "$4" ] && "$ADB" shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# État de démo + langue de l'app + config traduite, avec la page centrale $1 affichée d'office.
prepare() {
  "$ADB" shell am force-stop fr.pergens.hamacs
  # Autorisations données d'office : leur demande s'afficherait devant l'écran capturé.
  for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do "$ADB" shell pm grant fr.pergens.hamacs android.permission.$p 2>/dev/null; done
  python "$REPO/tools/demo/translate_dashboard.py" "$L" "$WORK/dash_tmp.json" "$1"
  "$ADB" push "$WORK/demo_base.tar" /data/local/tmp/app.tar >/dev/null
  "$ADB" push "$WORK/dash_tmp.json" /data/local/tmp/dash.json >/dev/null
  "$ADB" shell "chmod 644 /data/local/tmp/app.tar /data/local/tmp/dash.json; run-as fr.pergens.hamacs tar xf /data/local/tmp/app.tar; run-as fr.pergens.hamacs rm -f shared_prefs/alert_ticker.xml; run-as fr.pergens.hamacs cp /data/local/tmp/dash.json files/dashboard.json"
  "$ADB" shell "run-as fr.pergens.hamacs sed -i 's#</map>#    <string name=\"app_language\">$L</string>\n</map>#' shared_prefs/settings.xml"
  # Lancement explicite (monkey peut ouvrir le choix de l'écran d'accueil).
  "$ADB" shell am start -n fr.pergens.hamacs/.MainActivity >/dev/null
  sleep 12
}

DEMO_LANG="$L" python "$REPO/tools/demo/demo_ha.py" > "$WORK/demo_ha_$L.log" 2>&1 &
SRV=$!
sleep 4

if want main; then
prepare rooms
shot home
"$ADB" shell input tap 960 1090; sleep 6          # bouton « Dashboard »
shot dashboard

prepare rooms
"$ADB" shell input tap 1835 1115; sleep 20        # bouton du mur de caméras (accueil) : directs des caméras sur secteur
shot camera-wall

prepare members
"$ADB" shell input tap 960 1090; sleep 6
shot members

prepare consumption
"$ADB" shell input tap 960 1090; sleep 5
"$ADB" shell input tap 1590 665; sleep 10         # caméra du haut : flux vidéo (badge DIRECT)
shot cameras-energy
fi

if want history; then
prepare rooms
"$ADB" shell input tap 960 1090; sleep 6
"$ADB" shell input tap 1828 80; sleep 8           # historique des détections (bouton rond de la zone des détections)
tap_prefix "7 "; sleep 3                          # période « 7 jours »
"$ADB" shell input tap 220 890; sleep 3           # 4e capture (2e ligne)
"$ADB" shell input tap 1555 824; sleep 2.5        # « Lire » : lecture sur place, barre d'avancement visible
shot detection-history
# Plein écran zoomé (avec la barre de lecture) : sans texte, une seule image pour toutes les langues
# (docs/screenshots/fullscreen-zoom.jpg). Les commandes se masquent 3 s après la première image : le double toucher
# relance ce délai, la capture suit aussitôt.
if [ "$L" = en ]; then
  "$ADB" shell input tap 1758 824; sleep 2.5      # plein écran de la capture choisie
  "$ADB" shell "input tap 1300 750 & sleep 0.12; input tap 1300 750"; sleep 1   # double toucher : zoom
  shot fullscreen-zoom
fi
fi

if want avatar; then
prepare members
"$ADB" shell input tap 960 1090; sleep 4
for i in 1 2 3; do                                # réglages : appui long en (30, 40)
  "$ADB" shell input swipe 30 40 30 40 2500; sleep 2
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml | grep -q 'text="Home Assistant"' && break
  "$ADB" shell cmd statusbar collapse
done
"$ADB" shell input tap 212 645; sleep 2           # section Tableau de bord
"$ADB" shell input tap 1590 231; sleep 2          # Modifier les blocs
"$ADB" shell input tap 724 180; sleep 2           # premier membre
"$ADB" shell input tap 960 170; sleep 3           # Créer ou modifier l'avatar
"$ADB" shell input tap 1240 390; sleep 4          # Fichier de la tablette
"$ADB" shell input tap 600 757; sleep 16          # première photo, détourage
shot avatar-editor
fi

"$ADB" shell am force-stop fr.pergens.hamacs
kill $SRV 2>/dev/null
taskkill //F //PID $SRV >/dev/null 2>&1
echo "done $L"
