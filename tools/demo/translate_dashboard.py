"""Config d'exemple traduite pour les captures : python translate_dashboard.py <langue> <sortie.json> [page].

Remplace les textes visibles (noms, libellés, messages) par leur traduction (translations.json) ; « fr » recopie
la config telle quelle. [page] : page affichée d'office au centre (rooms, members, consumption, music) ; les
caméras s'ouvrent sur l'onglet intérieur (la caméra du haut a un flux vidéo).
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
LANGS = ['en', 'de', 'es', 'it', 'nl', 'pt', 'pl']
KEYS = {'label', 'name', 'text', 'message', 'secondary_text', 'title'}

lang, out = sys.argv[1], sys.argv[2]
table = json.loads((HERE / 'translations.json').read_text(encoding='utf-8'))
dashboard = json.loads((HERE.parents[1] / 'app/src/main/assets/dashboard.json').read_text(encoding='utf-8'))


def walk(o, key=None):
    if isinstance(o, dict):
        return {k: walk(v, k) for k, v in o.items()}
    if isinstance(o, list):
        return [walk(v, key) for v in o]
    if isinstance(o, str) and key in KEYS and lang in LANGS and isinstance(table.get(o), list):
        return table[o][LANGS.index(lang)]
    return o


page = sys.argv[3] if len(sys.argv) > 3 else None


def defaults(o):
    if isinstance(o, dict):
        if o.get('type') == 'switcher' and o.get('id') == 'center' and page:
            o['default'] = page
        if o.get('type') == 'switcher' and o.get('id') == 'cameras':
            o['default'] = 'inside-camera'
        for v in o.values():
            defaults(v)
    elif isinstance(o, list):
        for v in o:
            defaults(v)


result = walk(dashboard)
defaults(result)
Path(out).write_text(json.dumps(result, ensure_ascii=False), encoding='utf-8')
