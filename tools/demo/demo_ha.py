"""Faux serveur Home Assistant pour les captures d'écran du README (données fictives).

Lit les entités de la config d'exemple (app/src/main/assets/dashboard.json) et répond à l'API utilisée par l'app :
WebSocket (auth, subscribe_entities, historique, prévisions, registres, flux caméra…) et REST (calendriers, images
des caméras, flux HLS). Toute autre commande reçoit une réponse vide « réussie » ; les actions (call_service) sont
acquittées sans rien piloter.

    pip install aiohttp
    python tools/demo/demo_ha.py            # http://0.0.0.0:8123, jeton quelconque
    Dans l'app (émulateur) : adresse http://10.0.2.2:8123, jeton « demo ».
"""
import json
import math
import random
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from aiohttp import web, WSMsgType

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
DASHBOARD = ROOT / 'app/src/main/assets/dashboard.json'
VERSION = '2026.11.0'
NOW = datetime.now(timezone.utc)


def iso(t: datetime) -> str:
    return t.astimezone(timezone.utc).isoformat()


def ts(t: datetime) -> float:
    return t.timestamp()


# --- États ---------------------------------------------------------------------------------------------------

PEOPLE = {
    'alex': ('Alex', 'home'),
    'sarah': ('Sarah', 'Travail'),
    'leo': ('Léo', 'home'),
    'emma': ('Emma', 'home'),
}


def entity_ids() -> list[str]:
    text = DASHBOARD.read_text(encoding='utf-8')
    ids = set(re.findall(r'"([a-z_]+\.[a-z0-9_]+)"', text))
    domains = {'light', 'switch', 'sensor', 'binary_sensor', 'camera', 'person', 'weather', 'alarm_control_panel',
               'media_player', 'remote', 'calendar', 'select', 'sun', 'number', 'siren', 'update', 'zone'}
    return sorted(i for i in ids if i.split('.')[0] in domains)


def pretty(object_id: str) -> str:
    return object_id.replace('_', ' ').capitalize()


# Lecteur de démonstration : toutes les fonctions (pause, avance, volume, pistes, marche/arrêt, source, aléatoire,
# mode son, bibliothèque, répétition, regroupement, recherche…).
MEDIA_ALL = 1 | 2 | 4 | 8 | 16 | 32 | 128 | 256 | 512 | 1024 | 2048 | 4096 | 16384 | 32768 | 65536 | 131072 | 262144 | 524288 | 4194304

# Bibliothèque : (titre, artiste, classe, couleur de pochette).
TRACKS = [('Clair de lune', 'Claude Debussy', '#3b6ea8'), ('Gymnopédie n° 1', 'Erik Satie', '#a85a3b'),
          ('Le Cygne', 'Camille Saint-Saëns', '#3ba88c'), ('Boléro', 'Maurice Ravel', '#a83b6e'),
          ('La Mer', 'Charles Trenet', '#3b8ca8'), ('Nocturne op. 9 n° 2', 'Frédéric Chopin', '#6e3ba8')]
RADIOS = [('Radio Classique', '#c9a227'), ('FIP', '#d0386b'), ('France Musique', '#2a7fd4'), ('Jazz Radio', '#7a4bd1')]


def media_item(cid, title, cls, color, play=True, expand=False, children=None):
    o = {'title': title, 'media_class': cls, 'media_content_id': cid, 'media_content_type': 'music', 'can_play': play,
         'can_expand': expand, 'thumbnail': f'/demo/cover/{color.lstrip("#")}.svg'}
    if children is not None:
        o['children'] = children
    return o


def browse(cid: str) -> dict:
    tracks = [media_item(f'track:{i}', f'{t} — {a}', 'track', c) for i, (t, a, c) in enumerate(TRACKS)]
    radios = [media_item(f'radio:{n}', n, 'channel', c) for n, c in RADIOS]
    if cid == 'radios':
        return media_item('radios', 'Radios', 'directory', '#c9a227', play=False, expand=True, children=radios)
    if cid == 'favorites':
        return media_item('favorites', 'Favoris', 'playlist', '#d0386b', play=True, expand=True, children=tracks[:3])
    if cid == 'tracks':
        return media_item('tracks', 'Morceaux', 'directory', '#3b6ea8', play=False, expand=True, children=tracks)
    return media_item('', 'Music Assistant', 'directory', '#333333', play=False, expand=True, children=[
        media_item('favorites', 'Favoris', 'playlist', '#d0386b', expand=True),
        media_item('radios', 'Radios', 'directory', '#c9a227', play=False, expand=True),
        media_item('tracks', 'Morceaux', 'directory', '#3b6ea8', play=False, expand=True)])


def build_states() -> dict[str, dict]:
    rnd = random.Random(42)
    st: dict[str, dict] = {}

    def put(eid, state, **attrs):
        attrs.setdefault('friendly_name', pretty(eid.split('.', 1)[1]))
        changed = NOW - timedelta(minutes=rnd.randint(5, 300))
        st[eid] = {'state': str(state), 'attributes': attrs, 'last_changed': changed, 'last_updated': changed}

    for eid in entity_ids():
        dom, obj = eid.split('.', 1)
        if dom == 'light':
            on = rnd.random() < 0.45
            put(eid, 'on' if on else 'off', brightness=rnd.randint(90, 255) if on else None, supported_color_modes=['brightness'])
        elif dom == 'switch':
            put(eid, 'on' if rnd.random() < 0.5 else 'off')
        elif dom == 'binary_sensor':
            put(eid, 'off', device_class='motion' if 'mouvement' in obj or 'humain' in obj else None)
        elif dom == 'camera':
            put(eid, 'idle', entity_picture=f'/api/camera_proxy/{eid}?token=demo', friendly_name=pretty(obj))
        elif dom == 'sensor':
            if 'temperature' in obj:
                put(eid, f'{rnd.uniform(19.5, 23.5):.1f}', unit_of_measurement='°C', device_class='temperature')
            elif 'humidity' in obj:
                put(eid, rnd.randint(42, 58), unit_of_measurement='%', device_class='humidity')
            elif 'charger' in obj or 'battery_state' in obj:
                put(eid, 'none')
            elif 'batter' in obj:  # battery / batterie
                put(eid, rnd.randint(55, 98), unit_of_measurement='%', device_class='battery')
            elif 'steps' in obj:
                put(eid, rnd.randint(2500, 11000), unit_of_measurement='steps')
            elif obj.endswith('_power'):
                put(eid, 420, unit_of_measurement='W', device_class='power')
            elif obj.endswith('_power_a'):
                put(eid, 380, unit_of_measurement='W', device_class='power')
            elif obj.startswith('station_'):
                base = {'gazole': 1.68, 'sp95': 1.79, 'sp98': 1.86}[obj.rsplit('_', 1)[1]]
                put(eid, f'{base + rnd.uniform(-0.05, 0.06):.3f}', unit_of_measurement='€/L')
            elif 'precipitation' in obj:
                put(eid, 20, unit_of_measurement='%')
            elif 'movement' in obj:
                put(eid, 'none')
            elif obj.startswith('hadash_'):
                continue  # entités calculées par l'app
            else:
                put(eid, 0)
        elif dom == 'person':
            name, where = PEOPLE.get(obj, (pretty(obj), 'home'))
            put(eid, where, friendly_name=name)
        elif dom == 'weather':
            put(eid, 'partlycloudy', temperature=14.0, temperature_unit='°C', humidity=62, pressure=1018,
                pressure_unit='hPa', wind_speed=12.0, wind_speed_unit='km/h', wind_bearing=230, friendly_name='Valmont')
        elif dom == 'alarm_control_panel':
            put(eid, 'disarmed', friendly_name='Alarme', code_arm_required=False)
        elif dom == 'media_player':
            if 'appletv' in obj:
                put(eid, 'playing', media_title='Voyage en Patagonie', media_artist='Documentaire', app_name='TV',
                    friendly_name='Apple TV du salon', volume_level=0.4, media_duration=3120, media_position=1410,
                    media_position_updated_at=iso(NOW), entity_picture='/demo/cover/patagonie.svg',
                    supported_features=1 | 2 | 4 | 8 | 16 | 32 | 128 | 256 | 16384 | 2048 | 524288,
                    source_list=['TV', 'Netflix', 'YouTube'], source='TV', group_members=['media_player.salon_appletv'])
            else:
                put(eid, 'playing', friendly_name='Kiosque', media_title='Clair de lune', media_artist='Claude Debussy',
                    media_album_name='Suite bergamasque', media_duration=300, media_position=92,
                    media_position_updated_at=iso(NOW), volume_level=0.35, is_volume_muted=False, shuffle=False,
                    repeat='all', source='Radio Classique', source_list=['Radio Classique', 'FIP', 'Spotify', 'Bluetooth'],
                    sound_mode='Musique', sound_mode_list=['Musique', 'Film', 'Voix'], group_members=[eid],
                    entity_picture='/demo/cover/clair.svg', media_content_id='clair', supported_features=MEDIA_ALL)
        elif dom == 'remote':
            put(eid, 'on', friendly_name='Télécommande')
        elif dom == 'calendar':
            put(eid, 'off')
        elif dom == 'select':
            put(eid, 'happy', options=['happy', 'idle', 'sad', 'thinking', 'listening'])
        elif dom == 'sun':
            put(eid, 'above_horizon', next_rising=iso(NOW + timedelta(hours=15)), next_setting=iso(NOW + timedelta(hours=2)))
        else:
            put(eid, 'unknown')

    # Courrier déposé ce matin (alerte « boîte aux lettres »).
    mail = st.get('binary_sensor.boite_aux_lettres_occupancy')
    if mail:
        mail['state'] = 'on'
        mail['last_changed'] = mail['last_updated'] = NOW.replace(hour=7, minute=58, second=12)
    # Zones (icône du lieu de chaque membre) et vigilance météo (tout vert).
    st['zone.home'] = {'state': '3', 'attributes': {'friendly_name': 'Maison', 'icon': 'mdi:home'}, 'last_changed': NOW, 'last_updated': NOW}
    st['zone.travail'] = {'state': '1', 'attributes': {'friendly_name': 'Travail', 'icon': 'mdi:briefcase'}, 'last_changed': NOW, 'last_updated': NOW}
    st['sensor.valmont_weather_alert'] = {
        'state': 'Vert', 'last_changed': NOW, 'last_updated': NOW,
        'attributes': {'friendly_name': 'Valmont Weather alert', 'Vent violent': 'Vert', 'Orages': 'Vert', 'Pluie-inondation': 'Vert'},
    }
    # Alimentation des caméras (mur de caméras) : 1 et 2 sur secteur (direct permanent), 3 solaire, 4 sur batterie.
    for i, charge in ((1, 'chargecomplete'), (2, 'chargecomplete'), (3, 'charging'), (4, 'discharging')):
        st[f'sensor.argus_{i}_battery_state'] = {'state': charge, 'last_changed': NOW, 'last_updated': NOW,
                                               'attributes': {'friendly_name': f'Argus {i} Battery state'}}
    return st


STATES = build_states()


def compressed(eid: str) -> dict:
    s = STATES[eid]
    return {'s': s['state'], 'a': {k: v for k, v in s['attributes'].items() if v is not None},
            'lc': ts(s['last_changed']), 'lu': ts(s['last_updated'])}


# --- Historique ----------------------------------------------------------------------------------------------

def history(eid: str, start: datetime, end: datetime) -> list[dict]:
    rnd = random.Random(eid)
    rows = []
    if eid.startswith('person.'):
        # Présence : départs et retours réalistes sur la période.
        where = STATES.get(eid, {}).get('state', 'home')
        t, cur = start, 'home'
        while t < end:
            rows.append({'s': cur, 'lu': ts(t)})
            stay = rnd.uniform(1.5, 6) if cur == 'home' else rnd.uniform(1, 4)
            t += timedelta(hours=stay)
            cur = 'not_home' if cur == 'home' else 'home'
        rows.append({'s': where, 'lu': ts(end - timedelta(minutes=rnd.randint(20, 90)))})
        return rows
    if eid == 'sun.sun':
        t = start.replace(minute=0, second=0, microsecond=0)
        last = None
        while t < end:
            local_h = (t.hour + 1) % 24
            s = 'above_horizon' if 7 <= local_h < 17 else 'below_horizon'
            if s != last:
                rows.append({'s': s, 'lu': ts(t)})
                last = s
            t += timedelta(minutes=30)
        return rows
    if eid == 'sensor.argus_3_battery_state':
        # Panneau solaire : charge le jour, décharge la nuit.
        t = start.replace(minute=0, second=0, microsecond=0)
        last = None
        while t < end:
            s = 'charging' if 8 <= (t.hour + 1) % 24 < 17 else 'discharging'
            if s != last:
                rows.append({'s': s, 'lu': ts(t)})
                last = s
            t += timedelta(hours=1)
        return rows
    state = STATES.get(eid, {}).get('state')
    try:
        base = float(state)
    except (TypeError, ValueError):
        # Etat texte : valeur actuelle depuis son dernier changement (capteur oui/non actif : inactif avant).
        changed = STATES.get(eid, {}).get('last_changed', start)
        if eid.startswith('binary_sensor.') and state == 'on' and start < changed < end:
            return [{'s': 'off', 'lu': ts(start)}, {'s': 'on', 'lu': ts(changed)}]
        return [{'s': state or 'unknown', 'lu': ts(max(start, changed))}]
    step = timedelta(minutes=5)
    t = start
    is_power = eid.endswith('_power') or eid.endswith('_power_a')
    is_fuel = eid.startswith('sensor.station_')
    v = base
    while t <= end:
        if is_power:
            h = t.hour + t.minute / 60 + 1
            v = 180 + 260 * max(0, math.sin((h - 6) / 24 * 2 * math.pi)) + (900 if rnd.random() < 0.04 else 0) + rnd.uniform(-40, 40)
            rows.append({'s': f'{v:.0f}', 'lu': ts(t)})
        elif is_fuel:
            if rnd.random() < 0.01:
                v = base + rnd.uniform(-0.04, 0.04)
            rows.append({'s': f'{v:.3f}', 'lu': ts(t)})
            t += step * 11
        else:
            v += rnd.uniform(-0.1, 0.1)
            rows.append({'s': f'{v:.1f}', 'lu': ts(t)})
            t += step * 5
        t += step
    return rows


def statistics(eid: str, start: datetime, end: datetime, period: str) -> list[dict]:
    """Statistiques longue durée (moyenne, min, max par période) tirées de l'historique fictif."""
    step = {'5minute': timedelta(minutes=5), 'hour': timedelta(hours=1), 'day': timedelta(days=1)}.get(period, timedelta(hours=1))
    rows = [(r['lu'], float(r['s'])) for r in history(eid, start, end) if re.match(r'^-?\d+(\.\d+)?$', str(r['s']))]
    out, t = [], start
    while t < end:
        vals = [v for (u, v) in rows if ts(t) <= u < ts(t + step)]
        if vals:
            out.append({'start': int(ts(t) * 1000), 'end': int(ts(t + step) * 1000),
                        'mean': sum(vals) / len(vals), 'min': min(vals), 'max': max(vals)})
        t += step
    return out


# --- Prévisions et calendriers -------------------------------------------------------------------------------

def forecast(kind: str) -> list[dict]:
    rnd = random.Random(kind)
    conds = ['partlycloudy', 'cloudy', 'sunny', 'rainy', 'partlycloudy', 'sunny', 'cloudy']
    out = []
    if kind == 'hourly':
        t = NOW.replace(minute=0, second=0, microsecond=0)
        for i in range(24):
            out.append({'datetime': iso(t + timedelta(hours=i)), 'condition': conds[(i // 4) % len(conds)],
                        'temperature': round(14 - abs(i - 3) * 0.4 + rnd.uniform(-0.5, 0.5), 1),
                        'precipitation': 0.0, 'precipitation_probability': rnd.choice([0, 0, 10, 20])})
    else:
        d = NOW.replace(hour=11, minute=0, second=0, microsecond=0)
        for i in range(7):
            hi = round(rnd.uniform(12, 17), 1)
            out.append({'datetime': iso(d + timedelta(days=i)), 'condition': conds[i % len(conds)],
                        'temperature': hi, 'templow': round(hi - rnd.uniform(5, 8), 1),
                        'precipitation': rnd.choice([0, 0, 1.2, 3.5]), 'precipitation_probability': rnd.choice([0, 10, 40, 70])})
    return out


EVENTS = {
    'calendar.alex_google': [(0, '18:30', '20:00', 'Cours de guitare'), (2, '09:00', '10:00', 'Dentiste'), (5, None, None, 'Week-end à la montagne')],
    'calendar.sarah': [(1, '08:00', '09:00', 'Réunion d\'équipe'), (1, '19:30', '22:00', 'Dîner chez Julie'), (4, '14:30', '15:30', 'Rendez-vous banque')],
    'calendar.leo': [(0, '17:00', '18:30', 'Entraînement de foot'), (3, '13:30', '16:00', 'Sortie scolaire')],
    'calendar.emma': [(2, '10:00', '11:00', 'Piscine'), (6, None, None, 'Anniversaire de Lina')],
    'calendar.emma_google': [],
    'calendar.jours_feries': [(6, None, None, 'Armistice')],
}


LANGS = ['en', 'de', 'es', 'it', 'nl', 'pt', 'pl']
LANG = __import__('os').environ.get('DEMO_LANG', 'fr')
EVENT_NAMES = json.loads((HERE / 'translations.json').read_text(encoding='utf-8'))['events']


def tr_event(summary: str) -> str:
    """Titre d'événement dans la langue des captures (`DEMO_LANG`, français par défaut)."""
    if LANG in LANGS and summary in EVENT_NAMES:
        return EVENT_NAMES[summary][LANGS.index(LANG)]
    return summary


def calendar(eid: str) -> list[dict]:
    today = datetime.now().date()
    out = []
    for day, start, end, summary in EVENTS.get(eid, []):
        summary = tr_event(summary)
        d = today + timedelta(days=day)
        if start is None:
            out.append({'summary': summary, 'start': {'date': d.isoformat()}, 'end': {'date': (d + timedelta(days=1)).isoformat()}})
        else:
            tz = datetime.now().astimezone().tzinfo
            s = datetime.combine(d, datetime.strptime(start, '%H:%M').time(), tz)
            e = datetime.combine(d, datetime.strptime(end, '%H:%M').time(), tz)
            out.append({'summary': summary, 'start': {'dateTime': s.isoformat()}, 'end': {'dateTime': e.isoformat()}})
    return out


# --- Registres (pièces, appareils) ---------------------------------------------------------------------------

AREAS = [('salon', 'Salon', 'mdi:sofa'), ('entree', 'Entrée', 'mdi:door'), ('chambre_alex', 'Chambre Alex', 'mdi:bed'),
         ('chambre_emma', 'Chambre Emma', 'mdi:bed'), ('toilette', 'Toilette', 'mdi:toilet')]


def area_of(eid: str):
    obj = eid.split('.', 1)[1]
    for aid, _, _ in AREAS:
        if obj.startswith(aid) or obj.startswith(aid.replace('entree', 'couloir_entree')):
            return aid
    return None


def device_of(eid: str):
    m = re.match(r'[a-z_]+\.(argus_\d)', eid)
    return m.group(1) if m else None


# --- Serveur -------------------------------------------------------------------------------------------------

async def websocket(request):
    ws = web.WebSocketResponse(max_msg_size=0)
    await ws.prepare(request)
    await ws.send_json({'type': 'auth_required', 'ha_version': VERSION})

    async def result(mid, value=None, success=True):
        await ws.send_json({'id': mid, 'type': 'result', 'success': success, 'result': value} if success else
                           {'id': mid, 'type': 'result', 'success': False, 'error': {'code': 'not_found', 'message': 'demo'}})

    async for msg in ws:
        if msg.type != WSMsgType.TEXT:
            continue
        data = json.loads(msg.data)
        for m in data if isinstance(data, list) else [data]:
            kind, mid = m.get('type'), m.get('id')
            if kind == 'auth':
                await ws.send_json({'type': 'auth_ok', 'ha_version': VERSION})
            elif kind == 'mobile_app/push_notification_channel':
                PUSH.append((ws, mid))
                await result(mid)
            elif kind == 'auth/long_lived_access_token':
                print('jeton longue duree cree :', m.get('client_name'), m.get('lifespan'))
                await result(mid, 'demo-long-lived-token-0123456789')
            elif kind == 'supported_features':
                await result(mid)
            elif kind == 'subscribe_entities':
                SUBSCRIBERS.append((ws, mid))
                await result(mid)
                await ws.send_json({'id': mid, 'type': 'event', 'event': {'a': {e: compressed(e) for e in STATES}}})
            elif kind == 'weather/subscribe_forecast':
                await result(mid)
                await ws.send_json({'id': mid, 'type': 'event', 'event': {'type': m.get('forecast_type'), 'forecast': forecast(m.get('forecast_type', 'daily'))}})
            elif kind == 'history/history_during_period':
                start = datetime.fromisoformat(m['start_time'].replace('Z', '+00:00'))
                end = datetime.fromisoformat(m.get('end_time', iso(NOW)).replace('Z', '+00:00'))
                await result(mid, {e: history(e, start, end) for e in m.get('entity_ids', []) if e in STATES})
            elif kind == 'recorder/statistics_during_period':
                start = datetime.fromisoformat(m['start_time'].replace('Z', '+00:00'))
                end = datetime.fromisoformat(m.get('end_time', iso(NOW)).replace('Z', '+00:00'))
                await result(mid, {e: statistics(e, start, end, m.get('period', '5minute'))
                                   for e in m.get('statistic_ids', []) if e in STATES})
            elif kind == 'config/area_registry/list':
                await result(mid, [{'area_id': a, 'name': n, 'icon': i, 'labels': [], 'aliases': []} for a, n, i in AREAS])
            elif kind == 'config/entity_registry/list':
                await result(mid, [{'entity_id': e, 'area_id': area_of(e), 'device_id': device_of(e), 'platform': 'demo',
                                    'entity_category': None, 'hidden_by': None, 'disabled_by': None, 'labels': []} for e in STATES])
            elif kind == 'config/device_registry/list':
                await result(mid, [{'id': f'argus_{i}', 'name': f'Argus {i}', 'area_id': None, 'labels': []} for i in range(1, 5)])
            elif kind == 'frontend/get_translations':
                await result(mid, {'resources': {}})
            elif kind in ('get_services', 'config/label_registry/list', 'assist_pipeline/pipeline/list'):
                await result(mid, [] if kind != 'get_services' else {})
            elif kind == 'camera/stream':
                cam = re.match(r'camera\.(argus_\d)', m.get('entity_id', ''))
                if cam and (HERE / 'stream' / f'{cam.group(1)}.m3u8').exists():
                    await result(mid, {'url': f'/demo/stream/{cam.group(1)}.m3u8'})
                else:
                    await result(mid, success=False)
            elif kind == 'render_template':
                await result(mid)
                await ws.send_json({'id': mid, 'type': 'event', 'event': {'result': '', 'listeners': {}}})
            elif kind and kind.startswith('lovelace/'):
                await result(mid, success=False)
            elif kind == 'media_player/browse_media':
                await result(mid, browse(m.get('media_content_id', '')))
            elif kind == 'call_service' and m.get('return_response'):
                eid = (m.get('target') or {}).get('entity_id', '')
                if m.get('service') == 'search_media':
                    q = (m.get('service_data') or {}).get('search_query', '').lower()
                    found = [media_item(f'track:{i}', f'{t} — {a}', 'track', c) for i, (t, a, c) in enumerate(TRACKS)
                             if q in t.lower() or q in a.lower()] + [media_item(f'radio:{n}', n, 'channel', c) for n, c in RADIOS if q in n.lower()]
                    await result(mid, {'response': {eid: {'result': found}}})
                elif m.get('service') == 'get_queue' and eid == 'media_player.kiosk_3':
                    nxt = TRACKS[1]
                    await result(mid, {'response': {eid: {'items': 12, 'current_index': 0,
                        'current_item': {'name': 'Clair de lune', 'image': '/demo/cover/3b6ea8.svg',
                                         'media_item': {'name': 'Clair de lune', 'artists': [{'name': 'Claude Debussy'}]}},
                        'next_item': {'name': nxt[0], 'image': f'/demo/cover/{nxt[2].lstrip("#")}.svg',
                                      'media_item': {'name': nxt[0], 'artists': [{'name': nxt[1]}]}}}}})
                else:
                    await result(mid, success=False)
            elif kind == 'call_service' and m.get('domain') == 'media_player':
                await result(mid, {'context': {}})
                await media_service(m)
            elif kind == 'media_source/browse_media':
                await result(mid, {'title': 'Media', 'media_content_id': m.get('media_content_id', ''), 'children': [], 'can_expand': True})
            else:
                await result(mid)  # call_service, subscribe_events, unsubscribe… : acquittés sans effet
    return ws


async def push(eid):
    s = STATES[eid]
    for ws, mid in list(SUBSCRIBERS):
        if ws.closed:
            SUBSCRIBERS.remove((ws, mid))
            continue
        await ws.send_json({'id': mid, 'type': 'event', 'event': {'c': {eid: {'+': {'s': s['state'], 'a': s['attributes']}}}}})


async def media_service(m):
    """Lecteurs de démo : les commandes changent réellement leur état (rien n'est piloté)."""
    eid = (m.get('target') or {}).get('entity_id') or (m.get('service_data') or {}).get('entity_id')
    if eid not in STATES:
        return
    s, a, d, svc = STATES[eid], STATES[eid]['attributes'], m.get('service_data') or {}, m.get('service')
    now = datetime.now(timezone.utc)
    if 'media_position' in a and s['state'] == 'playing' and a.get('media_position_updated_at'):
        elapsed = (now - datetime.fromisoformat(a['media_position_updated_at'])).total_seconds()
        a['media_position'] = min(a.get('media_duration', 0), a['media_position'] + elapsed)
    a['media_position_updated_at'] = iso(now)
    if svc == 'media_play_pause':
        s['state'] = 'paused' if s['state'] == 'playing' else 'playing'
    elif svc in ('media_play', 'turn_on'):
        s['state'] = 'playing'
    elif svc == 'media_pause':
        s['state'] = 'paused'
    elif svc in ('media_stop', 'turn_off'):
        s['state'] = 'off' if svc == 'turn_off' else 'idle'
    elif svc == 'volume_set':
        a['volume_level'] = round(float(d.get('volume_level', 0)), 2)
    elif svc == 'volume_mute':
        a['is_volume_muted'] = bool(d.get('is_volume_muted'))
    elif svc == 'shuffle_set':
        a['shuffle'] = bool(d.get('shuffle'))
    elif svc == 'repeat_set':
        a['repeat'] = d.get('repeat', 'off')
    elif svc == 'select_source':
        a['source'] = d.get('source')
    elif svc == 'select_sound_mode':
        a['sound_mode'] = d.get('sound_mode')
    elif svc == 'media_seek':
        a['media_position'] = float(d.get('seek_position', 0))
    elif svc in ('media_next_track', 'media_previous_track', 'play_media'):
        cur = next((i for i, t in enumerate(TRACKS) if t[0] == a.get('media_title')), 0)
        cid = d.get('media_content_id', '')
        if svc == 'play_media' and cid.startswith('radio:'):
            name = cid[6:]
            color = dict(RADIOS).get(name, '#333333')
            a.update(media_title=name, media_artist='Radio en direct', media_album_name=None, media_duration=None,
                     entity_picture=f'/demo/cover/{color.lstrip("#")}.svg', source=name)
            s['state'] = 'playing'
            await push(eid)
            return
        i = int(cid[6:]) if svc == 'play_media' and cid.startswith('track:') else (cur + (1 if svc == 'media_next_track' else -1)) % len(TRACKS)
        t, art, color = TRACKS[i]
        a.update(media_title=t, media_artist=art, media_album_name='Classiques', media_duration=240, media_position=0,
                 entity_picture=f'/demo/cover/{color.lstrip("#")}.svg')
        s['state'] = 'playing'
    elif svc == 'join':
        for other in d.get('group_members', []):
            if other not in a.setdefault('group_members', [eid]):
                a['group_members'] = a['group_members'] + [other]
    elif svc == 'unjoin':
        for p in STATES.values():
            if eid in p['attributes'].get('group_members', [])[1:]:
                p['attributes']['group_members'] = [x for x in p['attributes']['group_members'] if x != eid]
    await push(eid)
    for other, p in STATES.items():
        if other != eid and other.startswith('media_player.'):
            await push(other)


async def cover(request):
    """Pochette de démonstration (SVG) : dégradé de la couleur demandée et une note de musique."""
    name = request.match_info['name']
    color = {'clair': '3b6ea8', 'patagonie': '5a8a3b'}.get(name, name)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#{color}"/>
<stop offset="1" stop-color="#111"/></linearGradient></defs><rect width="512" height="512" fill="url(#g)"/>
<circle cx="380" cy="130" r="160" fill="#fff" opacity="0.07"/>
<path d="M300 120v190a55 55 0 1 1-30-49V160l-90 22v170a55 55 0 1 1-30-49V160z" fill="#fff" opacity="0.85"/></svg>'''
    return web.Response(text=svg, content_type='image/svg+xml')


async def camera_proxy(request):
    eid = request.match_info['entity'].replace('_clair', '_fluent').replace('_snapshots', '')
    path = HERE / 'cameras' / f'{eid}.jpg'
    if not path.exists():
        m = re.match(r'camera\.(argus_\d)', eid)
        path = HERE / 'cameras' / f'camera.{m.group(1)}_fluent.jpg' if m else path
    return web.FileResponse(path) if path.exists() else web.Response(status=404)


async def calendars(request):
    return web.json_response(calendar(request.match_info['entity']))


# Tests : change l'etat d'une entite et le pousse aux abonnes (ex. sonnette : /demo/set?entity=binary_sensor.x&state=on).
SUBSCRIBERS = []


async def demo_set(request):
    eid, state = request.query.get('entity', ''), request.query.get('state', '')
    if eid not in STATES:
        return web.Response(status=404, text='unknown entity')
    STATES[eid]['state'] = state
    for ws, mid in list(SUBSCRIBERS):
        if ws.closed:
            SUBSCRIBERS.remove((ws, mid))
            continue
        await ws.send_json({'id': mid, 'type': 'event', 'event': {'c': {eid: {'+': {'s': state}}}}})
    return web.Response(text=f'{eid} = {state}')


# Integration mobile_app (simplifiee) : enregistrement, webhook (affiche ce que l'app envoie), canal de notifications.
PUSH = []


async def mobile_register(request):
    print('mobile_app/registrations :', await request.json())
    return web.json_response({'webhook_id': 'demo-webhook', 'secret': None, 'cloudhook_url': None, 'remote_ui_url': None})


async def mobile_webhook(request):
    print('webhook', request.match_info['id'], ':', await request.json())
    return web.json_response({})


async def demo_notify(request):
    """Notification de test : /demo/notify?message=...&title=...&tts_text=...&entity=..."""
    q = request.query
    data = {k: q[k] for k in ('tts_text', 'entity') if k in q}
    for ws, mid in list(PUSH):
        if ws.closed:
            PUSH.remove((ws, mid))
            continue
        await ws.send_json({'id': mid, 'type': 'event', 'event': {'message': q.get('message', ''), 'title': q.get('title'), 'data': data}})
    return web.Response(text=f'{len(PUSH)} abonne(s)')


async def api_root(request):
    return web.json_response({'message': 'API running.'})


# Connexion (flux OAuth2 de HA, simplifie) : page de connexion factice, n'importe quel identifiant est accepte.
LOGIN_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;background:#fafafa;display:flex;justify-content:center;padding:40px}
form{display:flex;flex-direction:column;gap:12px;width:320px}input,button{padding:12px;font-size:16px}</style></head>
<body><form method="post"><h2>Demo Home Assistant</h2><p>Client : %(client)s</p>
<input name="username" placeholder="Username" value="demo"><input name="password" type="password" placeholder="Password" value="demo">
<button id="login" type="submit">Log in</button></form></body></html>"""


def same_origin(a: str, b: str) -> bool:
    from urllib.parse import urlsplit
    x, y = urlsplit(a), urlsplit(b)
    return (x.scheme, x.netloc) == (y.scheme, y.netloc)


async def authorize(request):
    q = request.query
    client, redirect = q.get('client_id', ''), q.get('redirect_uri', '')
    if not same_origin(client, redirect):
        return web.Response(status=400, text='Invalid redirect URI')
    if request.method == 'POST':
        from urllib.parse import urlencode
        raise web.HTTPFound(redirect + '?' + urlencode({'code': 'demo-code', 'state': q.get('state', '')}))
    return web.Response(text=LOGIN_PAGE % {'client': client}, content_type='text/html')


async def auth_token(request):
    form = await request.post()
    print('auth/token :', dict(form))
    if form.get('grant_type') != 'authorization_code' or form.get('code') != 'demo-code':
        return web.json_response({'error': 'invalid_request'}, status=400)
    return web.json_response({'access_token': 'demo-access', 'refresh_token': 'demo-refresh', 'token_type': 'Bearer', 'expires_in': 1800})


async def auth_revoke(request):
    print('auth/revoke :', dict(await request.post()))
    return web.Response(text='')


def app() -> web.Application:
    a = web.Application()
    a.router.add_get('/api/websocket', websocket)
    a.router.add_get('/api/', api_root)
    a.router.add_route('*', '/auth/authorize', authorize)
    a.router.add_post('/auth/token', auth_token)
    a.router.add_post('/auth/revoke', auth_revoke)
    a.router.add_get('/demo/set', demo_set)
    a.router.add_get('/demo/cover/{name}.svg', cover)
    a.router.add_post('/api/mobile_app/registrations', mobile_register)
    a.router.add_post('/api/webhook/{id}', mobile_webhook)
    a.router.add_get('/demo/notify', demo_notify)
    a.router.add_get('/api/camera_proxy/{entity}', camera_proxy)
    a.router.add_get('/api/calendars/{entity}', calendars)
    a.router.add_static('/demo/stream/', HERE / 'stream')
    return a


def ensure_stream():
    """Flux HLS des caméras (badge DIRECT, mur de caméras), générés une fois avec ffmpeg depuis leurs images."""
    import os, shutil, subprocess
    ffmpeg = os.environ.get('DEMO_FFMPEG') or shutil.which('ffmpeg')
    for i in range(1, 5):
        out = HERE / 'stream' / f'argus_{i}.m3u8'
        if out.exists():
            continue
        if not ffmpeg:
            print('ffmpeg introuvable (DEMO_FFMPEG) : pas de flux vidéo (les caméras resteront en image fixe).')
            return
        out.parent.mkdir(exist_ok=True)
        subprocess.run([ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-loop', '1',
                        '-i', str(HERE / 'cameras' / f'camera.argus_{i}_fluent.jpg'), '-t', '20', '-r', '10',
                        '-vf', 'scale=1280:720,format=yuv420p', '-c:v', 'libx264', '-preset', 'veryfast', '-g', '20',
                        '-hls_time', '4', '-hls_list_size', '0', '-hls_playlist_type', 'vod', '-f', 'hls',
                        str(out)], check=False)


if __name__ == '__main__':
    ensure_stream()
    web.run_app(app(), host='0.0.0.0', port=8123)
