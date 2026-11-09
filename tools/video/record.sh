#!/bin/sh
# Séquences filmées de l'app (émulateur, données de démo) pour la vidéo : sh tools/video/record.sh <langue>
#   DEMO_WORK : dossier contenant demo_base.tar (voir tools/demo/README.md). Sortie : tools/video/public/rec/<langue>/.
# Le faux serveur est lancé ici ; M.A.C.S. change d'humeur par /demo/set (entité select.macs_mood).
L="$1"
# ONLY=intro : seulement le lancement (sinon tout).
want() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }
export MSYS_NO_PATHCONV=1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb}"
WORK="${DEMO_WORK:?DEMO_WORK non défini}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
if command -v cygpath >/dev/null 2>&1; then REPO="$(cygpath -m "$REPO")"; WORK="$(cygpath -m "$WORK")"; fi
OUT="$REPO/tools/video/public/rec/$L"
mkdir -p "$OUT"

prepare() {   # même préparation que tools/demo/capture.sh, page centrale $1, lancement si $2 != nolaunch
  "$ADB" shell am force-stop fr.pergens.hamacs
  for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do "$ADB" shell pm grant fr.pergens.hamacs android.permission.$p 2>/dev/null; done
  python "$REPO/tools/demo/translate_dashboard.py" "$L" "$WORK/dash_tmp.json" "$1"
  "$ADB" push "$WORK/demo_base.tar" /data/local/tmp/app.tar >/dev/null
  "$ADB" push "$WORK/dash_tmp.json" /data/local/tmp/dash.json >/dev/null
  "$ADB" shell "chmod 644 /data/local/tmp/app.tar /data/local/tmp/dash.json; run-as fr.pergens.hamacs tar xf /data/local/tmp/app.tar; run-as fr.pergens.hamacs rm -f shared_prefs/alert_ticker.xml; run-as fr.pergens.hamacs cp /data/local/tmp/dash.json files/dashboard.json"
  "$ADB" shell "run-as fr.pergens.hamacs sed -i 's#</map>#    <string name=\"app_language\">$L</string>\n</map>#' shared_prefs/settings.xml"
  [ "$2" = nolaunch ] && return
  "$ADB" shell am start -n fr.pergens.hamacs/.MainActivity >/dev/null
  sleep 12
}

# Filme $2 secondes dans $1.mp4 pendant que les commandes suivantes s'exécutent (fonction rec_end pour attendre).
rec() { "$ADB" shell screenrecord --bit-rate 16000000 --time-limit "$2" "/sdcard/rec_$1.mp4" & REC=$!; REC_NAME=$1; sleep 0.5; }
rec_end() { wait $REC; sleep 1; "$ADB" pull "/sdcard/rec_$REC_NAME.mp4" "$OUT/$REC_NAME.mp4" >/dev/null; "$ADB" shell rm "/sdcard/rec_$REC_NAME.mp4"; }
mood() { curl -s "http://127.0.0.1:8123/demo/set?entity=select.macs_mood&state=$1" >/dev/null; }

DEMO_LANG="$L" python "$REPO/tools/demo/demo_ha.py" > "$WORK/demo_ha_rec_$L.log" 2>&1 &
SRV=$!
sleep 4
"$ADB" reverse tcp:8123 tcp:8123 >/dev/null 2>&1

# 1. Lancement de l'app sur l'accueil : apparition de M.A.C.S.
prepare rooms nolaunch
mood idle
rec home_intro 22
"$ADB" shell am start -n fr.pergens.hamacs/.MainActivity >/dev/null
rec_end

if [ -z "$ONLY" ]; then
# 2. Réactions de M.A.C.S. (surpris, content), puis ses yeux suivent un doigt.
sleep 2
rec home_moods 18
sleep 2; mood surprised
sleep 4; mood happy
sleep 4; mood idle
sleep 1; "$ADB" shell input swipe 300 420 1600 560 2600
sleep 0.4; "$ADB" shell input swipe 1600 560 700 300 2000
rec_end

# 3. Bouton « Dashboard » → tableau de bord (pièces).
rec dashboard 10
"$ADB" shell input tap 960 1090
rec_end

# 4. Membres.
prepare members
"$ADB" shell input tap 960 1090; sleep 5
rec members 8
rec_end

# 5. Énergie.
prepare consumption
"$ADB" shell input tap 960 1090; sleep 5
rec energy 8
rec_end

# 6. Mur de caméras (directs des caméras sur secteur).
prepare rooms
rec wall 14
"$ADB" shell input tap 1835 1115
rec_end

fi

"$ADB" shell am force-stop fr.pergens.hamacs
kill $SRV 2>/dev/null
taskkill //F //PID $SRV >/dev/null 2>&1
ls -la "$OUT"
echo "rec done $L"
