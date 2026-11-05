#!/bin/sh
# Aides pour piloter l'app sur l'émulateur (Git Bash ou shell POSIX) : à charger avec `. tools/emulator.sh`.
#   tapt "texte" [n]      touche le n-ième élément (1 par défaut) qui affiche exactement ce texte
#   shot nom              capture d'écran dans $SHOTS/nom.png
#   open_dashboard        relance l'app et ouvre le tableau de bord
#   open_settings         ouvre les réglages (appui long en haut à gauche)
#   edit_mode             active le mode édition (Réglages › Tableau de bord › Modifier les blocs)
#   open_avatar_editor    ouvre l'éditeur d'avatar du premier membre
# Coordonnées : tablette 1920 × 1200 (AVD taba8), interface en français.
export MSYS_NO_PATHCONV=1   # sinon Git Bash transforme /sdcard/… en chemin Windows
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb}"
SHOTS="${SHOTS:-.}"

tapt() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  # Texte passé en hexadécimal : sous Windows, les accents d'un argument arrivent déformés dans Python.
  hex=$(printf %s "$1" | od -An -tx1 | tr -d ' \n')
  b=$("$ADB" shell cat /sdcard/ui.xml | python -I -c "
import sys, re, html
raw = bytes.fromhex(sys.argv[1])
try: t = raw.decode('utf-8')
except UnicodeDecodeError: t = raw.decode('latin-1')   # commande tapée dans une console Windows (cp1252)
want, n = int(sys.argv[2]), 0
sys.stdin.reconfigure(encoding='utf-8')
for m in re.finditer(r'<node [^>]*>', sys.stdin.read()):
    tm = re.search(r' text=\"([^\"]*)\"', m.group(0))
    if tm and html.unescape(tm.group(1)) == t:   # apostrophes encodées (&apos;) dans le XML
        n += 1
        if n == want:
            b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', m.group(0))
            print((int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2)
            break
" "$hex" "${2:-1}")
  if [ -z "$b" ]; then echo "introuvable : $1" >&2; return 1; fi
  "$ADB" shell input tap $b
}

shot() { "$ADB" exec-out screencap -p > "$SHOTS/$1.png"; }

open_dashboard() {
  "$ADB" shell am force-stop fr.arubinu.hadash
  # Lancement explicite : `monkey` ouvre parfois le choix de l'écran d'accueil (l'app se déclare HOME).
  "$ADB" shell am start -n fr.arubinu.hadash/.MainActivity >/dev/null 2>&1; sleep 8
  "$ADB" shell input tap 960 1090; sleep 3          # bouton « Dashboard » de l'accueil
}

visible() { "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "$ADB" shell cat /sdcard/ui.xml | grep -q "$1"; }

# Appui long immobile en (30, 40), dans la zone cachée de 48 dp. Pas au bord : (8, 8) ouvre parfois le volet des
# notifications d'Android. Le premier appui après un changement d'écran est parfois ignoré : on réessaie (en
# refermant le volet au besoin) jusqu'à voir les réglages.
open_settings() {
  for i in 1 2 3; do
    "$ADB" shell input swipe 30 40 30 40 2500; sleep 2
    visible "Les modifications" && return 0
    "$ADB" shell cmd statusbar collapse
  done
  echo "réglages non ouverts" >&2; return 1
}

edit_mode() {
  open_settings
  tapt "Tableau de bord"; sleep 2
  tapt "Modifier les blocs" 2; sleep 2               # 1 = titre de la ligne, 2 = bouton
}

open_avatar_editor() {
  open_dashboard
  "$ADB" shell input tap 966 66; sleep 2            # onglet Membres (son nom n'est affiché que s'il est actif)
  edit_mode
  "$ADB" shell input tap 724 180; sleep 2           # premier avatar
  tapt "Créer ou modifier l'avatar"; sleep 2
}
