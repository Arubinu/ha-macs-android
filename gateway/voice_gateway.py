#!/usr/bin/env python3
"""
Passerelle vocale « full audio » entre la tablette et un modèle audio local (LFM3-Audio via llama-server).

Tablette ──WebSocket (PCM 16 kHz)──▶ passerelle ──audio brut──▶ llama-server (/v1/chat/completions)
                                         │                              │
                                         ◀── tool calls (call_service…) ┘
                                         └──WebSocket──▶ Home Assistant

- Pas de STT ni de TTS : l'audio part tel quel au modèle, qui répond par du texte et/ou des appels d'outils.
- Une transcription (ASR) est lancée en parallèle, uniquement pour l'affichage (« j'ai entendu… »).
- Les entités proposées au modèle sont celles exposées à Assist dans Home Assistant. Si le catalogue est petit,
  il est placé dans un prompt système stable (mis en cache par llama-server) ; sinon le modèle utilise l'outil
  `search_entities` pour ne pas saturer le contexte.

Protocole tablette (WebSocket /ws) :
  → {"type":"start","id":"…","sample_rate":16000,"format":"pcm_s16le"}  puis trames binaires PCM
  → {"type":"end"} | {"type":"cancel"}
  ← {"type":"heard","id","text"} | {"type":"reply_delta","id","text"} | {"type":"reply","id","text"}
  ← {"type":"audio","id","data":base64 PCM16,"sample_rate"} (si le modèle produit de l'audio)
  ← {"type":"action","id","domain","service","entity_id"} | {"type":"error","id","text"} | {"type":"done","id"}
"""
from __future__ import annotations

import array
import asyncio
import base64
import io
import json
import logging
import os
import re
import time
import unicodedata
import wave
from dataclasses import dataclass, field

import aiohttp
from aiohttp import web

log = logging.getLogger("voice-gateway")


# --------------------------------------------------------------------------------------------------
# Configuration (variables d'environnement)
# --------------------------------------------------------------------------------------------------

@dataclass
class Config:
    ha_url: str = os.environ.get("HA_URL", "http://homeassistant.local:8123").rstrip("/")
    ha_token: str = os.environ.get("HA_TOKEN", "")
    llm_url: str = os.environ.get("LLM_URL", "http://localhost:8081").rstrip("/")
    llm_model: str = os.environ.get("LLM_MODEL", "")
    host: str = os.environ.get("HOST", "0.0.0.0")
    port: int = int(os.environ.get("PORT", "8765"))
    language: str = os.environ.get("LANGUAGE", "fr")
    # Au-delà de ce nombre de caractères, le catalogue n'est plus mis dans le prompt (recherche via outil).
    catalog_max_chars: int = int(os.environ.get("CATALOG_MAX_CHARS", "6000"))
    # "fast" : confirmation immédiate après une action réussie (pas de second appel au modèle).
    confirm_mode: str = os.environ.get("CONFIRM_MODE", "fast")
    max_rounds: int = int(os.environ.get("MAX_ROUNDS", "2"))
    entity_source: str = os.environ.get("ENTITY_SOURCE", "auto")
    # "primed" : ASR rapide pour choisir les entités, puis un seul appel avec l'audio original (défaut).
    # "audio"  : audio seul, le modèle cherche les entités lui-même via les outils (plus d'allers-retours).
    mode: str = os.environ.get("MODE", "primed")
    candidates: int = int(os.environ.get("CANDIDATES", "10"))
    # Commandes simples traitées localement sans appeler le modèle.
    fast_intents: bool = os.environ.get("FAST_INTENTS", "1") != "0"
    # Réponse parlée via /tts de llama-server (LFM3-Audio, llama.cpp ≥ 0.8.0).
    tts: bool = os.environ.get("TTS", "1") != "0"
    tts_voice: str = os.environ.get("TTS_VOICE", "")  # voix prédéfinie (ex. female / male)
    tts_voices_dir: str = os.environ.get("TTS_VOICES_DIR", "")  # neutral.wav, happy.wav, sad.wav… (clonage)
    # Transcription pour l'affichage : "after" (après la réponse), "parallel" ou "off".
    transcribe: str = os.environ.get("TRANSCRIBE", "after")
    allowed_domains: tuple = tuple(os.environ.get(
        "ALLOWED_DOMAINS",
        "light,switch,cover,fan,climate,media_player,scene,script,input_boolean,input_select,lock,vacuum,button,"
        "input_button,alarm_control_panel,humidifier,water_heater,valve,lawn_mower,number,input_number,select,remote",
    ).split(","))


CFG = Config()

# Règles par défaut de Home Assistant (homeassistant/exposed_entities.py) pour les entités sans réglage explicite.
DEFAULT_EXPOSED_DOMAINS = {
    "climate", "cover", "fan", "humidifier", "light", "media_player", "scene", "switch", "todo", "vacuum",
    "water_heater", "lock", "valve",
}
DEFAULT_EXPOSED_DEVICE_CLASSES = {
    ("binary_sensor", dc) for dc in ("door", "garage_door", "lock", "occupancy", "opening", "presence", "window")
} | {("sensor", dc) for dc in ("aqi", "co", "co2", "humidity", "pm10", "pm25", "temperature", "volatile_organic_compounds")}


STATE_FR = {"on": "allumé", "off": "éteint", "open": "ouvert", "closed": "fermé", "home": "à la maison",
            "not_home": "absent", "locked": "verrouillé", "unlocked": "déverrouillé", "playing": "en lecture",
            "paused": "en pause", "idle": "inactif", "unavailable": "indisponible", "unknown": "inconnu"}


def norm(text: str) -> str:
    """Minuscules sans accents ni ponctuation, pour la recherche floue."""
    text = unicodedata.normalize("NFKD", text or "").encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


# --------------------------------------------------------------------------------------------------
# Home Assistant : états, registre, exposition à Assist, appels de service
# --------------------------------------------------------------------------------------------------

@dataclass
class Entity:
    entity_id: str
    name: str
    area: str = ""
    aliases: list = field(default_factory=list)

    @property
    def domain(self) -> str:
        return self.entity_id.split(".", 1)[0]

    def keys(self) -> str:
        return norm(" ".join([self.name, self.area, *self.aliases, self.entity_id.split(".", 1)[1].replace("_", " ")]))


class HomeAssistant:
    def __init__(self, session: aiohttp.ClientSession):
        self.session = session
        self.ws: aiohttp.ClientWebSocketResponse | None = None
        self.states: dict[str, dict] = {}
        self.entities: dict[str, Entity] = {}
        self.catalog_text = ""
        self._id = 0
        self._pending: dict[int, asyncio.Future] = {}
        self._ready = asyncio.Event()

    async def run(self):
        while True:
            try:
                await self._connect()
            except Exception as e:  # noqa: BLE001
                log.warning("Home Assistant: %s, nouvelle tentative dans 5 s", e)
            self._ready.clear()
            await asyncio.sleep(5)

    async def _connect(self):
        async with self.session.ws_connect(f"{CFG.ha_url}/api/websocket", heartbeat=30, max_msg_size=0) as ws:
            self.ws = ws
            await ws.receive_json()  # auth_required
            await ws.send_json({"type": "auth", "access_token": CFG.ha_token})
            auth = await ws.receive_json()
            if auth.get("type") != "auth_ok":
                raise RuntimeError(f"authentification refusée ({auth.get('message')})")
            log.info("Connecté à Home Assistant %s", auth.get("ha_version"))
            reader = asyncio.create_task(self._reader(ws))
            try:
                await self.refresh()
                await self._send({"type": "subscribe_events", "event_type": "state_changed"})
                for ev in ("entity_registry_updated", "area_registry_updated"):
                    await self._send({"type": "subscribe_events", "event_type": ev})
                self._ready.set()
                await reader
            finally:
                reader.cancel()
                for f in self._pending.values():
                    f.cancel()
                self._pending.clear()

    async def _reader(self, ws):
        async for msg in ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            data = json.loads(msg.data)
            for m in data if isinstance(data, list) else [data]:
                if m.get("type") == "result":
                    fut = self._pending.pop(m.get("id"), None)
                    if fut and not fut.done():
                        fut.set_result(m)
                elif m.get("type") == "event":
                    ev = m["event"]
                    if ev.get("event_type") == "state_changed":
                        new = ev["data"].get("new_state")
                        if new:
                            self.states[new["entity_id"]] = new
                        else:
                            self.states.pop(ev["data"]["entity_id"], None)
                    else:
                        asyncio.create_task(self.refresh())

    async def _send(self, payload: dict) -> dict:
        self._id += 1
        payload = {**payload, "id": self._id}
        fut = asyncio.get_running_loop().create_future()
        self._pending[self._id] = fut
        await self.ws.send_json(payload)
        return await asyncio.wait_for(fut, 20)

    async def call(self, payload: dict):
        await asyncio.wait_for(self._ready.wait(), 10)
        res = await self._send(payload)
        if not res.get("success"):
            raise RuntimeError(res.get("error", {}).get("message", "erreur Home Assistant"))
        return res.get("result")

    async def refresh(self):
        """Recharge états, registres et liste des entités exposées à Assist."""
        states = (await self._send({"type": "get_states"}))["result"]
        self.states = {s["entity_id"]: s for s in states}
        ents = (await self._send({"type": "config/entity_registry/list_for_display"})).get("result", {})
        areas = {a["area_id"]: a["name"] for a in (await self._send({"type": "config/area_registry/list"}))["result"]}
        devices = {d["id"]: d.get("area_id") for d in (await self._send({"type": "config/device_registry/list"}))["result"]}
        # Exposition à Assist : réglage explicite par entité, sinon règle par défaut de HA
        # (« exposer les nouvelles entités » + domaines/classes exposés par défaut, hors entités masquées/techniques).
        explicit: dict[str, bool] = {}
        expose_new = True
        try:
            res = await self._send({"type": "homeassistant/expose_entity/list"})
            explicit = {eid: bool(v.get("conversation")) for eid, v in res["result"]["exposed_entities"].items()}
            res = await self._send({"type": "homeassistant/expose_new_entities/get", "assistant": "conversation"})
            expose_new = bool(res.get("result", {}).get("expose_new", True))
        except Exception:  # noqa: BLE001
            log.warning("Réglages d'exposition Assist indisponibles : règles par défaut")

        registry = {e["ei"]: e for e in ents.get("entities", [])}

        def default_rule(eid: str, st: dict, domains) -> bool:
            reg = registry.get(eid, {})
            if reg.get("hb") or reg.get("ec") is not None:
                return False
            domain = eid.split(".", 1)[0]
            dc = st.get("attributes", {}).get("device_class")
            return domain in domains or (domain, dc) in DEFAULT_EXPOSED_DEVICE_CLASSES

        def assist_exposed(eid: str, st: dict) -> bool:
            if eid in explicit:
                return explicit[eid]
            return expose_new and default_rule(eid, st, DEFAULT_EXPOSED_DOMAINS)

        # ENTITY_SOURCE : "assist" (réglages d'exposition HA), "all" (domaines autorisés), "auto" (assist s'il
        # expose réellement quelque chose, sinon all).
        source = CFG.entity_source
        if source == "auto":
            count = sum(1 for eid, st in self.states.items() if assist_exposed(eid, st))
            source = "assist" if count >= 5 else "all"
        if source == "assist":
            is_exposed = assist_exposed
        else:
            def is_exposed(eid: str, st: dict) -> bool:
                return default_rule(eid, st, set(CFG.allowed_domains))
        full_aliases = {}
        try:
            for e in (await self._send({"type": "config/entity_registry/list"}))["result"]:
                if e.get("aliases"):
                    full_aliases[e["entity_id"]] = list(e["aliases"])
        except Exception:  # noqa: BLE001
            pass

        self.entities = {}
        for eid, st in self.states.items():
            if not is_exposed(eid, st):
                continue
            reg = registry.get(eid, {})
            area_id = reg.get("ai") or devices.get(reg.get("di"))
            self.entities[eid] = Entity(
                entity_id=eid,
                name=st.get("attributes", {}).get("friendly_name", eid),
                area=areas.get(area_id, "") if area_id else "",
                aliases=full_aliases.get(eid, []),
            )
        lines = [f"{e.entity_id}|{e.name}|{e.area}" + (f"|{','.join(e.aliases)}" if e.aliases else "")
                 for e in sorted(self.entities.values(), key=lambda x: (x.area, x.entity_id))]
        self.catalog_text = "\n".join(lines)
        log.info("%d entités (source %s, %d caractères de catalogue, %s)", len(self.entities), source,
                 len(self.catalog_text), "dans le prompt" if len(self.catalog_text) <= CFG.catalog_max_chars else "via search_entities")

    def ranked(self, query: str, domains: set | None = None, device_class: str | None = None) -> list[tuple[int, Entity]]:
        """Entités classées par pertinence (mots entiers > préfixes > sous-chaînes, pluriels tolérés)."""
        q = norm(query)
        words = [w[:-1] if len(w) > 4 and w.endswith("s") else w for w in q.split() if len(w) > 1]
        scored = []
        for e in self.entities.values():
            if domains and e.domain not in domains:
                continue
            if device_class and self.states.get(e.entity_id, {}).get("attributes", {}).get("device_class") != device_class:
                continue
            k = f" {e.keys()} "
            score = sum(3 if f" {w} " in k or f" {w}s " in k else (2 if f" {w}" in k else (1 if w in k else 0)) for w in words)
            if q and q in k:
                score += 5
            if score:
                scored.append((score, e))
        scored.sort(key=lambda x: -x[0])
        return scored

    def search(self, query: str, domain: str | None = None, limit: int = 8) -> list[dict]:
        if domain and not any(e.domain == domain for e in self.entities.values()):
            domain = None  # domaine inventé par le modèle : on l'ignore plutôt que de ne rien trouver
        return [self.describe(e.entity_id) for _, e in self.ranked(query, {domain} if domain else None)[:limit]]

    def say_state(self, entity_id: str) -> str:
        """Phrase courte décrivant l'état d'une entité (« Salon : 19,0 °C »)."""
        d = self.describe(entity_id)
        state = str(d.get("state"))
        try:
            value = f"{float(state):.1f}".replace(".", ",")
        except ValueError:
            value = STATE_FR.get(state, state)
        unit = d.get("unit_of_measurement", "")
        return f"{d.get('name') or entity_id} : {value}{(' ' + unit) if unit else ''}"

    def describe(self, entity_id: str) -> dict:
        e = self.entities.get(entity_id)
        st = self.states.get(entity_id, {})
        attrs = st.get("attributes", {})
        out = {"entity_id": entity_id, "name": e.name if e else attrs.get("friendly_name"), "area": e.area if e else "",
               "state": st.get("state")}
        for k in ("unit_of_measurement", "brightness", "current_temperature", "temperature", "media_title", "volume_level"):
            if k in attrs:
                out[k] = attrs[k]
        return out


# --------------------------------------------------------------------------------------------------
# Outils exposés au modèle
# --------------------------------------------------------------------------------------------------

# Schémas volontairement minimalistes : avec une entrée audio, llama-server ne réutilise pas le cache de
# prompt, donc chaque token de définition d'outil est retraité à chaque requête.
def _tool(name: str, desc: str, props: dict, required: list) -> dict:
    return {"type": "function", "function": {"name": name, "description": desc, "parameters": {
        "type": "object", "properties": {k: {"type": t} for k, t in props.items()}, "required": required}}}


TOOLS = [
    _tool("call_service", "Action HA (ex. light turn_on)",
          {"domain": "string", "service": "string", "entity_id": "string", "data": "object"},
          ["domain", "service", "entity_id"]),
    _tool("get_state", "État d'entités", {"entity_id": "string"}, ["entity_id"]),
    _tool("search_entities", "Trouver des entités par nom/pièce", {"query": "string", "domain": "string"}, ["query"]),
    _tool("assist", "Commande texte pour l'assistant HA", {"text": "string"}, ["text"]),
]
# En mode amorcé, les états sont déjà dans le prompt : seuls les outils d'action restent.
PRIMED_TOOLS = [t for t in TOOLS if t["function"]["name"] in ("call_service", "assist")]


RULES = (
    "Tu es l'assistant vocal de la maison. Réponds TOUJOURS en français, en une phrase courte, sans markdown.\n"
    "- Pour agir (allumer, éteindre, ouvrir, régler) : appelle call_service avec un entity_id de la liste "
    "(domain = la partie avant le point, service = turn_on, turn_off, toggle, open_cover, close_cover…).\n"
    "- Pour une question sur un état (température, allumé ou non…) : réponds directement avec l'état donné dans la liste.\n"
    "- Minuteurs, listes, scripts ou demande complexe : appelle assist avec la demande reformulée.\n"
    "- N'invente jamais d'entity_id."
)

STOPWORDS = set("""le la les l un une des de du d au aux a à et ou est sont quel quelle quels quelles qui que quoi
dans sur sous pour par avec sans ce cet cette ces mon ma mes ton ta tes son sa ses notre nos votre vos leur leurs il
elle ils elles on je tu nous vous me te se y en ne pas plus peux peut pouvez veux voudrais stp svp s'il plait merci
allume allumer eteins eteindre ouvre ouvrir ferme fermer mets mettre baisse monte combien fait est-ce""".split())


def system_prompt(ha: HomeAssistant, transcript: str | None = None) -> str:
    if transcript is not None:
        # Mode « amorcé » : seules les entités pertinentes pour la phrase entendue, avec leur état.
        words = [w for w in norm(transcript).split() if w not in STOPWORDS and len(w) > 2]
        rows = ha.search(" ".join(words), limit=CFG.candidates) if words else []
        lines = "\n".join(f"{r['entity_id']}|{r['name']}|{r['area']}|{r['state']}{r.get('unit_of_measurement', '')}" for r in rows)
        return RULES + "\nEntités (entity_id|nom|pièce|état) :\n" + (lines or "(aucune trouvée)")
    if ha.catalog_text and len(ha.catalog_text) <= CFG.catalog_max_chars:
        return RULES + "\nEntités (entity_id|nom|pièce|alias) :\n" + ha.catalog_text
    return RULES + "\nUtilise search_entities pour trouver les entity_id avant d'agir."


async def run_tool(ha: HomeAssistant, name: str, args: dict) -> tuple[str, dict | None]:
    """Exécute un outil ; retourne (résultat JSON pour le modèle, action effectuée éventuelle)."""
    try:
        if name == "search_entities":
            return json.dumps(ha.search(args.get("query", ""), args.get("domain")), ensure_ascii=False), None
        if name == "get_state":
            ids = [i.strip() for i in str(args.get("entity_id", "")).split(",") if i.strip()]
            return json.dumps([ha.describe(i) for i in ids if i in ha.states], ensure_ascii=False), None
        if name == "assist":
            res = await ha.call({"type": "conversation/process", "text": args.get("text", ""), "language": CFG.language})
            speech = res.get("response", {}).get("speech", {}).get("plain", {}).get("speech", "")
            return json.dumps({"response": speech}, ensure_ascii=False), {"assist": speech}
        if name == "call_service":
            ids = [i.strip() for i in str(args.get("entity_id", "")).split(",") if i.strip()]
            unknown = [i for i in ids if i not in ha.entities]
            if unknown or not ids:
                return json.dumps({"error": f"entité inconnue ou non exposée : {', '.join(unknown) or '?'}"}), None
            # Un petit modèle se trompe souvent de domaine : on le déduit de l'entity_id.
            domain = ids[0].split(".", 1)[0]
            if domain in READ_ONLY_DOMAINS:
                # « Action » sur un capteur = question sur son état : réponse directe, sans second appel.
                return json.dumps({"success": True}), {"answer": "; ".join(ha.say_state(i) for i in ids)}
            if domain not in CFG.allowed_domains:
                return json.dumps({"error": f"domaine non autorisé : {domain}"}), None
            service = normalize_service(str(args.get("service", "")), domain)
            if service is None:
                return json.dumps({"error": f"service inconnu : {args.get('service')}"}), None
            data = args.get("data") if isinstance(args.get("data"), dict) else {}
            await ha.call({"type": "call_service", "domain": domain, "service": service,
                           "service_data": data, "target": {"entity_id": ids}})
            return json.dumps({"success": True}), {"domain": domain, "service": service, "entity_id": ids}
        return json.dumps({"error": f"outil inconnu : {name}"}), None
    except Exception as e:  # noqa: BLE001
        return json.dumps({"error": str(e)}, ensure_ascii=False), None


READ_ONLY_DOMAINS = {"sensor", "binary_sensor", "weather", "sun", "person", "device_tracker", "zone", "input_text",
                     "calendar", "event", "image", "camera", "update"}

KNOWN_SERVICES = {
    "turn_on", "turn_off", "toggle", "open_cover", "close_cover", "stop_cover", "set_cover_position", "lock", "unlock",
    "media_play", "media_pause", "media_play_pause", "media_stop", "media_next_track", "media_previous_track",
    "volume_up", "volume_down", "volume_set", "volume_mute", "set_temperature", "set_hvac_mode", "select_option",
    "press", "set_value", "start", "pause", "return_to_base", "alarm_arm_away", "alarm_arm_home", "alarm_disarm",
    "open_valve", "close_valve", "set_percentage", "send_command",
}


def normalize_service(service: str, domain: str) -> str | None:
    s = norm(service).replace(" ", "_")
    if s in KNOWN_SERVICES:
        return s
    if s in ("on", "allumer", "allume", "activate", "open", "ouvrir"):
        return "open_cover" if domain == "cover" else "turn_on"
    if s in ("off", "eteindre", "eteins", "deactivate", "close", "fermer"):
        return "close_cover" if domain == "cover" else "turn_off"
    if domain in ("scene", "script") and s in ("run", "execute", "activer"):
        return "turn_on"
    return None


# --------------------------------------------------------------------------------------------------
# Intentions rapides : les commandes simples sont traitées sans appeler le modèle (~0 ms après l'ASR)
# --------------------------------------------------------------------------------------------------

VERBS_ON = {"allume", "allumer", "allumez", "active", "activer", "activez", "demarre", "lance", "ouvre", "ouvrir", "ouvrez", "monte"}
VERBS_OFF = {"eteins", "eteint", "eteindre", "eteignez", "desactive", "desactiver", "coupe", "couper", "arrete", "ferme",
             "fermer", "fermez", "baisse"}
QUESTION_WORDS = {"quelle", "quel", "quelles", "quels", "combien", "est", "fait"}
# Mots génériques désignant un type d'appareil (filtre de domaine, absents des noms d'entités).
GENERIC = {"lumiere": {"light"}, "lumieres": {"light"}, "lampe": {"light"}, "lampes": {"light"}, "eclairage": {"light"},
           "volet": {"cover"}, "volets": {"cover"}, "store": {"cover"}, "stores": {"cover"}, "prise": {"switch"},
           "ventilateur": {"fan"}, "chauffage": {"climate"}, "television": {"media_player"}, "tele": {"media_player"}}
MEASURES = {"temperature": "temperature", "chaud": "temperature", "froid": "temperature", "humidite": "humidity"}
ALL_WORDS = {"tout", "toutes", "tous"}
CONTROLLABLE = {"light", "switch", "cover", "fan", "climate", "media_player", "input_boolean", "scene", "script"}


def pretty(name: str) -> str:
    return re.sub(r"\s*:\s*", " ", name or "").strip()


MAIN_LIGHT_WORDS = ("plafonnier", "principal", "plafond", "suspension")

_UNITS = ["zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze",
          "treize", "quatorze", "quinze", "seize"]
_TENS = {20: "vingt", 30: "trente", 40: "quarante", 50: "cinquante", 60: "soixante"}


def nombre(n: int) -> str:
    """Entier en toutes lettres (−999…999) : le TTS prononce mal les chiffres et les décimales."""
    if n < 0:
        return "moins " + nombre(-n)
    if n <= 16:
        return _UNITS[n]
    if n < 20:
        return "dix-" + _UNITS[n - 10]
    if n < 70:
        t, u = n // 10 * 10, n % 10
        return _TENS[t] + ("" if u == 0 else (" et un" if u == 1 else "-" + _UNITS[u]))
    if n < 80:
        return "soixante" + (" et onze" if n == 71 else "-" + nombre(n - 60))
    if n < 100:
        return "quatre-vingt" + ("s" if n == 80 else "-" + nombre(n - 80))
    if n < 1000:
        c, r = n // 100, n % 100
        head = "cent" if c == 1 else _UNITS[c] + " cent" + ("s" if r == 0 else "")
        return head + ("" if r == 0 else " " + nombre(r))
    return str(n)


def spoken(text: str) -> str:
    """Texte affiché → texte prononcé : nombres arrondis en lettres, unités en toutes lettres."""
    text = text.replace("°C", " degrés").replace("%", " pour cent").replace(" : ", ", ")
    text = re.sub(r"-?\d+(?:[.,]\d+)?", lambda m: nombre(round(float(m.group().replace(",", ".")))), text)
    return re.sub(r"\s+", " ", text).strip()


def tie_break(ha: HomeAssistant, tied: list[Entity], plural: bool) -> list[Entity]:
    """Départage des entités ex aequo : groupes plutôt que leurs membres, entités rattachées à une pièce,
    et pour « la lumière » au singulier, le plafonnier."""
    def members(e: Entity) -> set:
        m = ha.states.get(e.entity_id, {}).get("attributes", {}).get("entity_id")
        return set(m) if isinstance(m, list) else set()
    covered = set().union(*(members(e) for e in tied)) if tied else set()
    ids = {e.entity_id for e in tied}
    # Groupes sans liste de membres (ex. Zigbee2MQTT) : « x_plafonnier » couvre « x_plafonnier_1 », « _2 »…
    covered |= {i for i in ids if any(i != j and re.fullmatch(re.escape(j) + r"_\d+", i) for j in ids)}
    out = [e for e in tied if e.entity_id not in covered]
    with_area = [e for e in out if e.area]
    out = with_area or out
    if not plural and len(out) > 1:
        main = [e for e in out if any(w in norm(e.name) for w in MAIN_LIGHT_WORDS)]
        out = main or out
    return out


def fast_intent(ha: HomeAssistant, transcript: str) -> tuple[str, list[dict]] | None:
    """Reconnaît « allume/éteins/ouvre/ferme X » et « quelle est la température de X ».
    Retourne (réponse, actions à exécuter) ou None si la phrase doit aller au modèle."""
    words = norm(transcript).split()
    if not words:
        return None
    wset = set(words)
    on, off = wset & VERBS_ON, wset & VERBS_OFF
    domains = set().union(*(GENERIC[w] for w in wset if w in GENERIC)) or None
    rest = [w for w in words if w not in STOPWORDS and w not in VERBS_ON | VERBS_OFF | QUESTION_WORDS
            and w not in GENERIC and w not in MEASURES and w not in ALL_WORDS and len(w) > 2]

    # Question sur une mesure : « quelle est la température du salon ? »
    measure = next((MEASURES[w] for w in words if w in MEASURES), None)
    if measure and not (on or off):
        ranked = ha.ranked(" ".join(rest), {"sensor"}, measure) if rest else []
        if not ranked:
            return None
        tied = tie_break(ha, [e for s, e in ranked if s == ranked[0][0]], plural=True)
        if len(tied) != 1:
            return None
        e = tied[0]
        st = ha.states.get(e.entity_id, {})
        try:
            value = f"{float(st.get('state')):.1f}".replace(".", ",").removesuffix(",0")
        except (TypeError, ValueError):
            return None
        unit = st.get("attributes", {}).get("unit_of_measurement", "")
        what = " d'humidité" if measure == "humidity" else ""
        return f"{e.area or pretty(e.name)} : {value} {unit}{what}.", []

    if bool(on) == bool(off) or not rest:
        return None  # aucun verbe ou verbes contradictoires : au modèle
    ranked = ha.ranked(" ".join(rest), domains or CONTROLLABLE)
    if not ranked or ranked[0][0] < 3:
        return None
    best = ranked[0][0]
    # Pluriel (« les lumières », « tout ») : toute la pièce ; singulier : un seul appareil.
    plural = bool(wset & ALL_WORDS) or any(w in wset for w in ("lumieres", "lampes", "volets", "stores", "les"))
    targets = tie_break(ha, [e for s, e in ranked if s == best], plural)
    if len(targets) > 1 and not plural:
        return None  # plusieurs candidats précis : ambigu, le modèle tranchera
    actions = []
    for e in targets:
        if e.domain == "cover":
            service = "open_cover" if on else "close_cover"
        elif e.domain in ("scene", "script"):
            service = "turn_on"
        else:
            service = "turn_on" if on else "turn_off"
        actions.append({"domain": e.domain, "service": service, "entity_id": [e.entity_id]})
    verb = ("J'ouvre" if on else "Je ferme") if targets[0].domain == "cover" else ("J'allume" if on else "J'éteins")
    if len(targets) == 1:
        what = pretty(targets[0].name)
    else:
        kind = "lumières" if all(e.domain == "light" for e in targets) else "appareils"
        areas = {e.area for e in targets}
        what = f"les {kind}" + (f", {areas.pop()}" if len(areas) == 1 and "" not in areas else "")
    return f"{verb} {what}.", actions


def confirmation(actions: list[dict]) -> str:
    for a in actions:
        if a.get("answer"):
            return a["answer"]
        if a.get("assist"):
            return a["assist"]
    return "C'est fait."


# --------------------------------------------------------------------------------------------------
# Modèle audio (llama-server, API OpenAI)
# --------------------------------------------------------------------------------------------------

_voice_cache: dict[str, str | None] = {}


def voice_reference(emotion: str) -> str | None:
    """Extrait de référence (TTS_VOICES_DIR/<émotion>.wav, sinon neutral.wav) encodé en base64, mis en cache."""
    if not CFG.tts_voices_dir:
        return None
    for name in (emotion, "neutral"):
        if name not in _voice_cache:
            path = os.path.join(CFG.tts_voices_dir, f"{name}.wav")
            _voice_cache[name] = base64.b64encode(open(path, "rb").read()).decode() if os.path.exists(path) else None
        if _voice_cache[name]:
            return _voice_cache[name]
    return None


def pcm_to_wav(pcm: bytes, rate: int) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return buf.getvalue()


class Llm:
    def __init__(self, session: aiohttp.ClientSession):
        self.session = session

    def _body(self, messages, **extra):
        body = {"messages": messages, "cache_prompt": True, "temperature": 0, **extra}
        if CFG.llm_model:
            body["model"] = CFG.llm_model
        return body

    _last_use = 0.0

    async def warm_up(self):
        """llama-server se met en veille quand il est inactif (~1 s de réveil) : on le réveille dès que
        l'utilisateur commence à parler, le délai est ainsi masqué par la durée de la phrase."""
        if time.monotonic() - self._last_use < 60:
            return
        self._last_use = time.monotonic()
        try:
            async with self.session.post(f"{CFG.llm_url}/v1/chat/completions",
                                         json=self._body([{"role": "user", "content": "ok"}], max_tokens=1)) as r:
                await r.read()
        except Exception as e:  # noqa: BLE001
            log.debug("Réveil du modèle : %s", e)

    async def transcribe(self, wav_b64: str) -> str:
        self._last_use = time.monotonic()
        body = self._body([
            {"role": "system", "content": "Perform ASR."},
            {"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": wav_b64, "format": "wav"}}]},
        ], max_tokens=128)
        async with self.session.post(f"{CFG.llm_url}/v1/chat/completions", json=body) as r:
            data = await r.json()
        return data["choices"][0]["message"].get("content", "").strip()

    async def speak(self, text: str, send, emotion: str = "neutral", lang: str | None = None):
        """Synthèse vocale en flux (/tts de llama-server) relayée à la tablette par petits blocs PCM16.
        Le premier son part ~150 ms après l'appel ; un extrait de référence par émotion peut colorer la voix."""
        lang = lang or CFG.language
        body = {"input": spoken(text) if lang == "fr" else text, "lang": lang, "stream": True, "response_format": "pcm"}
        ref = voice_reference(emotion)
        if ref:
            body["speaker_ref_b64"] = ref
        elif CFG.tts_voice:
            body["voice"] = CFG.tts_voice
        rate, pending = 24000, b""
        async with self.session.post(f"{CFG.llm_url}/tts", json=body) as r:
            if r.status != 200:
                log.warning("TTS HTTP %s : %s", r.status, (await r.text())[:200])
                return
            m = re.search(r"rate=(\d+)", r.headers.get("Content-Type", ""))
            rate = int(m.group(1)) if m else rate
            async for chunk in r.content.iter_any():
                pending += chunk
                usable = len(pending) - len(pending) % 4  # float32 little-endian
                if usable < 4800:  # ~50 ms à 24 kHz : évite une avalanche de micro-messages
                    continue
                samples = array.array("f", pending[:usable])
                pending = pending[usable:]
                pcm16 = array.array("h", (max(-32768, min(32767, int(s * 32767))) for s in samples))
                await send("audio", data=base64.b64encode(pcm16.tobytes()).decode(), sample_rate=rate)
            if len(pending) >= 4:
                samples = array.array("f", pending[: len(pending) - len(pending) % 4])
                pcm16 = array.array("h", (max(-32768, min(32767, int(s * 32767))) for s in samples))
                await send("audio", data=base64.b64encode(pcm16.tobytes()).decode(), sample_rate=rate)

    async def stream(self, messages: list, on_text, on_audio, tools: list) -> dict:
        """Requête en streaming ; retourne le message assistant complet (texte + tool_calls)."""
        body = self._body(messages, tools=tools, stream=True, max_tokens=256)
        text, calls = "", {}
        async with self.session.post(f"{CFG.llm_url}/v1/chat/completions", json=body) as r:
            if r.status != 200:
                raise RuntimeError(f"llama-server HTTP {r.status}: {(await r.text())[:200]}")
            async for raw in r.content:
                line = raw.decode().strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    break
                delta = json.loads(payload)["choices"][0].get("delta", {})
                if delta.get("content"):
                    text += delta["content"]
                    await on_text(delta["content"])
                audio = delta.get("audio")
                if isinstance(audio, dict) and audio.get("data"):
                    await on_audio(audio["data"])
                for tc in delta.get("tool_calls") or []:
                    slot = calls.setdefault(tc.get("index", 0), {"id": "", "name": "", "arguments": ""})
                    slot["id"] = tc.get("id") or slot["id"]
                    fn = tc.get("function", {})
                    slot["name"] += fn.get("name") or ""
                    slot["arguments"] += fn.get("arguments") or ""
        msg = {"role": "assistant", "content": text}
        if calls:
            msg["tool_calls"] = [{"id": c["id"] or f"call_{i}", "type": "function",
                                  "function": {"name": c["name"], "arguments": c["arguments"]}}
                                 for i, c in sorted(calls.items())]
        return msg


# --------------------------------------------------------------------------------------------------
# Session vocale (une par tablette connectée)
# --------------------------------------------------------------------------------------------------

async def handle_turn(ws: web.WebSocketResponse, ha: HomeAssistant, llm: Llm, turn_id: str, pcm: bytes, rate: int,
                      lang: str | None = None):
    t0 = time.perf_counter()

    async def send(kind: str, **kw):
        if not ws.closed:
            await ws.send_json({"type": kind, "id": turn_id, **kw})

    wav_b64 = base64.b64encode(pcm_to_wav(pcm, rate)).decode()

    async def transcribe():
        try:
            await send("heard", text=await llm.transcribe(wav_b64))
        except Exception as e:  # noqa: BLE001
            log.warning("Transcription d'affichage impossible : %s", e)

    asr = None
    transcript = None
    if CFG.mode == "primed":
        # Transcription rapide → sélection des entités pertinentes → un seul appel audio bien amorcé.
        try:
            transcript = await llm.transcribe(wav_b64)
            await send("heard", text=transcript)
        except Exception as e:  # noqa: BLE001
            log.warning("ASR impossible, bascule en mode audio pur : %s", e)
    elif CFG.transcribe == "parallel":
        # En parallèle, la transcription d'affichage ralentit la réponse (slots partagés).
        asr = asyncio.create_task(transcribe())

    actions: list[dict] = []
    reply = ""
    path = "llm"
    try:
        # Les intentions rapides sont rédigées en français.
        fast = fast_intent(ha, transcript) if transcript and CFG.fast_intents and (lang or CFG.language) == "fr" else None
        if fast:
            # Commande simple reconnue localement : pas d'appel au modèle.
            path = "fast"
            reply, actions = fast
            await send("reply", text=reply)
            emotion = "happy" if actions else "neutral"
            await send("emotion", emotion=emotion)
            speech = asyncio.create_task(llm.speak(reply, send, emotion, lang)) if CFG.tts else None
            for a in actions:
                await ha.call({"type": "call_service", "domain": a["domain"], "service": a["service"],
                               "service_data": {}, "target": {"entity_id": a["entity_id"]}})
                await send("action", **a)
            if speech:
                await speech
            return
        reply = await run_llm(ha, llm, send, wav_b64, transcript, actions)
        failed = reply.startswith(("Je n'ai pas compris", "Désolé"))
        emotion = "sad" if failed else ("happy" if any("domain" in a for a in actions) else "neutral")
        await send("emotion", emotion=emotion)
        if CFG.tts and reply:
            await llm.speak(reply, send, emotion, lang)
    except Exception as e:  # noqa: BLE001
        log.exception("Échec du tour %s", turn_id)
        await send("error", text=str(e))
    finally:
        if CFG.transcribe == "after" and transcript is None:
            asr = asyncio.create_task(transcribe())
        if asr:
            try:
                await asyncio.wait_for(asr, 5)
            except Exception:  # noqa: BLE001
                pass
        await send("done")
        log.info("Tour %s [%s] : %.0f ms, %d action(s), réponse : %s", turn_id[:8], path,
                 (time.perf_counter() - t0) * 1000, len(actions), reply[:120])


async def run_llm(ha: HomeAssistant, llm: Llm, send, wav_b64: str, transcript: str | None, actions: list) -> str:
    """Appel(s) au modèle audio avec outils ; retourne la réponse finale."""
    messages = [
        {"role": "system", "content": system_prompt(ha, transcript)},
        {"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": wav_b64, "format": "wav"}}]},
    ]
    reply = ""
    for _ in range(CFG.max_rounds):
        msg = await llm.stream(
            messages,
            on_text=lambda t: send("reply_delta", text=t),
            on_audio=lambda b64: send("audio", data=b64, sample_rate=24000),
            tools=PRIMED_TOOLS if transcript is not None else TOOLS,
        )
        reply += msg.get("content") or ""
        calls = msg.get("tool_calls") or []
        if not calls:
            break
        messages.append(msg)
        only_actions = True
        for c in calls:
            name = c["function"]["name"]
            try:
                args = json.loads(c["function"]["arguments"] or "{}")
            except json.JSONDecodeError:
                args = {}
            result, action = await run_tool(ha, name, args)
            log.info("outil %s(%s) → %s", name, args, result[:160])
            if action:
                actions.append(action)
                if "domain" in action:
                    await send("action", **action)
            if name not in ("call_service", "assist") or action is None:
                only_actions = False
            messages.append({"role": "tool", "tool_call_id": c["id"], "content": result})
        if only_actions and CFG.confirm_mode == "fast":
            reply = confirmation(actions)
            await send("reply", text=reply)
            break
    if not reply.strip():
        reply = confirmation(actions) if actions else "Je n'ai pas compris."
        await send("reply", text=reply)
    return reply


async def ws_handler(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=30, max_msg_size=0)
    await ws.prepare(request)
    ha: HomeAssistant = request.app["ha"]
    llm: Llm = request.app["llm"]
    log.info("Tablette connectée : %s", request.remote)
    buf = bytearray()
    turn_id, rate, recording, lang = "", 16000, False, None
    async for msg in ws:
        if msg.type == aiohttp.WSMsgType.BINARY:
            if recording:
                buf.extend(msg.data)
        elif msg.type == aiohttp.WSMsgType.TEXT:
            m = json.loads(msg.data)
            kind = m.get("type")
            if kind == "start":
                buf.clear()
                turn_id, rate, recording = m.get("id", ""), int(m.get("sample_rate", 16000)), True
                lang = m.get("language")
                asyncio.create_task(llm.warm_up())
            elif kind == "cancel":
                buf.clear()
                recording = False
            elif kind == "end" and recording:
                recording = False
                asyncio.create_task(handle_turn(ws, ha, llm, turn_id, bytes(buf), rate, lang))
    log.info("Tablette déconnectée : %s", request.remote)
    return ws


async def health(request: web.Request) -> web.Response:
    ha: HomeAssistant = request.app["ha"]
    return web.json_response({"ha_connected": ha._ready.is_set(), "entities": len(ha.entities),
                              "catalog_in_prompt": len(ha.catalog_text) <= CFG.catalog_max_chars})


async def on_startup(app: web.Application):
    session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60))
    # llama-server ferme la connexion après un flux SSE : pas de keep-alive, sinon la requête suivante
    # échoue sur une connexion morte (ServerDisconnectedError).
    llm_session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60), connector=aiohttp.TCPConnector(force_close=True))
    app["session"] = session
    app["llm_session"] = llm_session
    app["ha"] = HomeAssistant(session)
    app["llm"] = Llm(llm_session)
    app["ha_task"] = asyncio.create_task(app["ha"].run())


async def on_cleanup(app: web.Application):
    app["ha_task"].cancel()
    await app["session"].close()
    await app["llm_session"].close()


def main():
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")
    if not CFG.ha_token:
        raise SystemExit("HA_TOKEN manquant (jeton d'accès longue durée Home Assistant)")
    app = web.Application()
    app.router.add_get("/ws", ws_handler)
    app.router.add_get("/health", health)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    web.run_app(app, host=CFG.host, port=CFG.port)


if __name__ == "__main__":
    main()
