#!/bin/sh
# Captures d'écran d'une langue pour les README : sh tools/demo/capture.sh <langue>
#   DEMO_WORK : dossier de travail contenant demo_base.tar (données de l'app en mode démo, avatars compris,
#               voir tools/demo/README.md) ; les captures sont écrites dans $DEMO_WORK/shots/<langue>/.
# L'app (version publique) doit être installée sur l'émulateur ; le faux serveur est lancé ici dans la langue voulue.
# Navigation par coordonnées (tablette 1920 × 1200) : les textes changent avec la langue.
L="$1"
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb}"
WORK="${DEMO_WORK:?DEMO_WORK non défini}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$WORK/shots/$L"
mkdir -p "$OUT"

shot() { "$ADB" exec-out screencap -p > "$OUT/$1.png"; }

# État de démo + langue de l'app + config traduite, avec la page centrale $1 affichée d'office.
prepare() {
  "$ADB" shell am force-stop fr.pergens.hamacs
  python "$REPO/tools/demo/translate_dashboard.py" "$L" "$WORK/dash_tmp.json" "$1"
  "$ADB" push "$WORK/demo_base.tar" /data/local/tmp/app.tar >/dev/null
  "$ADB" push "$WORK/dash_tmp.json" /data/local/tmp/dash.json >/dev/null
  "$ADB" shell "chmod 644 /data/local/tmp/app.tar /data/local/tmp/dash.json; run-as fr.pergens.hamacs tar xf /data/local/tmp/app.tar; run-as fr.pergens.hamacs cp /data/local/tmp/dash.json files/dashboard.json"
  "$ADB" shell "run-as fr.pergens.hamacs sed -i 's#</map>#    <string name=\"app_language\">$L</string>\n</map>#' shared_prefs/settings.xml"
  # Lancement explicite (monkey peut ouvrir le choix de l'écran d'accueil).
  "$ADB" shell am start -n fr.pergens.hamacs/.MainActivity >/dev/null
  sleep 12
}

DEMO_LANG="$L" python "$REPO/tools/demo/demo_ha.py" > "$WORK/demo_ha_$L.log" 2>&1 &
SRV=$!
sleep 4

prepare rooms
shot home
"$ADB" shell input tap 960 1090; sleep 6          # bouton « Dashboard »
shot dashboard

prepare members
"$ADB" shell input tap 960 1090; sleep 6
shot members

prepare consumption
"$ADB" shell input tap 960 1090; sleep 5
"$ADB" shell input tap 1590 665; sleep 10         # caméra du haut : flux vidéo (badge DIRECT)
shot cameras-energy

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

"$ADB" shell am force-stop fr.pergens.hamacs
kill $SRV 2>/dev/null
taskkill //F //PID $SRV >/dev/null 2>&1
echo "done $L"
