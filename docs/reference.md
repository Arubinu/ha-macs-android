# Technical reference

**English** · [Français](reference.fr.md)

> Configuration details for going further. For normal use, the first-launch wizard and edit mode are enough: nothing
> requires editing `dashboard.json` by hand.

## Configuration (`dashboard.json`)

The bundled file is the default configuration; changes made in the app (edit mode) are added on top of it on the
tablet. To transfer or back it up: Settings › Dashboard › **Export** / **Import** (a single file with the settings, the
layout and the block settings; it also contains the access token and the code: keep it somewhere safe).

```jsonc
{
  "start_screen": "home",
  "idle_screen": "home", "idle_seconds": 300,        // automatic return to the home screen
  "theme": { "card_radius": 0, "colors": { "background": "#000", "accent": "rgb(140,245,255)" } },
  "macs": { "temperature_sensor": "sensor.outdoor_temperature", "weather_entity": "weather.home", "battery": "device" },
  "popups": { "appletv": { "name": "Remote", "cards": [ { "type": "remote", "entity": "remote.living_room" } ] } },
  "screens": {
    "home":      { "layout": "overlay", "cards": [ { "type": "macs", "position": "fill" }, { "type": "clock", "position": "top_start" } ] },
    "dashboard": { "layout": "columns", "columns": [ { "cards": [ … ] }, { "cards": [ … ] } ] }
  }
}
```

### Card types

| Type | Lovelace equivalent | Main options |
|---|---|---|
| `vertical`, `horizontal` | vertical/horizontal-stack | `cards`, `gap`, `weight` |
| `switcher` | state-switch (hash) + embedded-view + menu | `id`, `nav` (`pills`/`rail`/`none`), `default`, `pages[{id,icon,label,cards}]` |
| `room`, `room_grid` | "room" button-card | `name`, `icon`, `entity`, `info[{icon,entity,decimals,suffix}]`, `buttons[]`, `columns` |
| `button` | round button-card | `entity`, `icon`, `color` (`first`/`second`/`third`/css), `icon_on`, `icon_<state>`, `tap_action` |
| `alarm` | alarmo-card | `entity`, `name` |
| `clock_weather` | clock-weather-card | `entity`, `days`, `animated_icons` (header, default `true`), `forecast_animated_icons` (default `false`) |
| `clock` | clock | `seconds` |
| `macs` | macs-card | `height`/`aspect_ratio`, `tap_action` (default: talk) |
| `camera` | hash-timer + picture-entity | `entity`, `battery`, `refresh`, `image_url`, `aspect_ratio` |
| `camera_detections` | camera-detection-slider | `minutes`, `storage`, `cameras[]`, `time_source`, `time_format`, `pairing`… (see below) |
| `members` | avatars + member info | `members[]`, `avatar_url` |
| `presence_history` | waterfall-history-card | `entity`, `hours`, `segments` |
| `calendar` | calendar-card-pro | `entities`, `days` |
| `power_graph` | plotly-graph (consumption + day/night) | `entity`, `hours`, `height` |
| `mini_graph` | mini-graph-card | `entity`, `hours`, `decimals`, `aggregate`, `color`; or several lines: `entities` (`name`, `color`, `show_graph`, `show_line`, `show_legend`, `y_axis`, `smoothing`), `hours_to_show`, `points_per_hour`, `aggregate_func`, `state_map`, `show` (`fill`, `name`, `state`, `legend`), `fade_edges` |
| `swipe` | simple-swipe-card | `cards`, `enable_auto_swipe`, `auto_swipe_interval`, `swipe_effect` (`slide`, `fade`, `instant`), `show_pagination`; finger swipe and dot indicator |
| `media_player` | mediocre-media-player-card | `entity`, `players` (other players, chips), `use_art_colors`, `show_volume_step_buttons`, `always_show_power_button`, `favorite_button` |
| `remote` | Apple TV remote | `entity`, `media_player`, `apps` |
| `alert_ticker` (or `ticker`) | [AlertTicker Card](https://github.com/djdevil/AlertTicker-Card) | same options as the Lovelace card (see below) |
| `webview` | iframe | `url`, `aspect_ratio` (address editable in edit mode) |
| `windy` | Windy iframe | `url` (embed.windy.com), `aspect_ratio`; mode, zoom and position adjustable in edit mode |
| `title`, `rule`, `spacer` | markdown / separators | `text` |

Actions (`tap_action` / `hold_action`): `toggle`, `more-info`, `navigate` (`#page` or `#popup`), `screen`, `popup`,
`call-service` / `perform-action`, `voice`, `none`. Adding `"pin": true` protects the action with the access code.

Useful common options:

| Option | Role |
|---|---|
| `access_code` (root) | initial access code (then editable in Settings › Security) |
| *(setting)* `master_code` | master code (empty = none), set in the wizard or Settings › Security: asked before the settings and for the kiosk menu (otherwise the access code). After 3 wrong codes: 30 s wait, doubled at each new mistake (15 min at most) |
| `radius` | corner radius of a block (default: the theme's `rounded_radius` for rooms, cameras, alerts, web pages) |
| `space_after` | gap below a block in a `"fit": true` column |
| `flex`, `min_height` | the block takes the remaining space of a `"fit": true` column |
| `layer: "front"`, `overflow_bottom` | (M.A.C.S.) in front, overflowing downwards over the neighbouring blocks |
| `always_live`, `live_seconds`, `motion`, `motion_delay` | (camera) permanent live view, live duration after a tap, motion sensors |
| `loading_image`, `offline_image`, `error_image` | (camera) loading, offline and unavailable stream images; by default, drawn and translated screens ("Loading…", "Camera offline") |
| `accent_hue` | (image) image designed for the original accent, recoloured with the main colour |
| `fade_edges` | (`power_graph`, `mini_graph`) left, right and bottom edges faded |

The **main colour** (Settings › Display) replaces the theme accent on the whole dashboard: active buttons, outlines,
graphs, and M.A.C.S. (eyes, mouth, eyebrows, battery effect included).

**M.A.C.S.** is set up in edit mode (tap the character), with the options of its card editor: mood entity, Assist
satellite (listening / thinking, then happy or confused), sadness when the battery is low, temperature, wind and
precipitation sensors (automatic or forced unit, min / max range mapped to 0–100; default ranges of the card: 5–30 °C,
10–50 mph, 0–10 mm or 0–100 %), weather entity for the conditions, battery (tablet, level sensors in % or V and charging
sensor, M.A.C.S. integration or none), number of exchanges shown, automatic brightness (delay, minimum, maximum,
animation pause) and animations. Without a sensor, the M.A.C.S. integration entities (`number.macs_temperature`…) are
used. Equivalent keys in `"macs"` of `dashboard.json`: `mood_entity`, `assist_satellite`, `low_battery_sad`,
`temperature_sensor` / `_unit` / `_min` / `_max` (same for `wind_*`, `precipitation_*`), `weather_entity`, `battery`
(`device`, `sensor`, `integration`, `none`), `battery_sensor`, `battery_unit`, `battery_min`, `battery_max`,
`charging_sensor`, `max_turns`, `auto_brightness_enabled`, `auto_brightness_timeout_minutes`, `auto_brightness_min`,
`auto_brightness_max`, `auto_brightness_pause_animations`, `animations_enabled`.

### Latest detections

The `camera_detections` card shows the recent snapshots, from the chosen storage (`storage.type`):

| `type` | Parameters | Note |
|---|---|---|
| `ha_media` | `path` (`media-source://…`), `url_prefix` (optional, e.g. `/local/media/Recordings`) | no modification date |
| `smb` | `host` (port allowed), `share`, `path`, `user` (empty = guest), `password`, `domain` | SMB 2/3 |
| `nfs` | `host`, `export`, `path`, `uid`, `gid` | NFS v3; the export must accept non-privileged ports (`insecure`) |
| `local` | `uri` (folder chosen in the app), `path` | tablet storage, SD card, USB drive |
| `entities` | — (each camera has its `entity`, `image.*` or `camera.*`) | latest snapshot of each camera, without video |

- `cameras`: `[{ "name": "Entrance", "match": "Argus 1", "mode": "prefix" | "suffix" | "contains" | "all", "folder": "" }]`
  — several cameras can share a folder, each recognised by a prefix or suffix of its files.
- `minutes`: how long a snapshot is shown after the detection.
- `time_source`: `name` (date in the file name, empty `time_format` = automatic detection, otherwise a pattern such as
  `yyyyMMdd_HHmmss`) or `modified` (file modification date).
- `pairing`: snapshot ↔ video matching, `auto` (same name, otherwise the video of the same camera that started just
  before), `name` or `time`; `image_affix` / `video_affix`: text specific to snapshots or videos, ignored when comparing
  names (e.g. `_snap`). `image_ext`, `video_ext`: recognised extensions.

Everything is set up in edit mode (tap the card), with a **Test** button that shows the number of files read, of recent
snapshots per camera and the latest snapshot found. SMB / NFS files are downloaded on demand into the cache (cleared
after a day).

A round button at the top right of the card opens the **detection history** (whole year, by period or by day, camera
filter, activity chart, preview); it needs a storage with files (not `entities`).

### AlertTicker

Native port of the AlertTicker Card v1.3.19: the configuration keys are those of the Lovelace card (a configuration
copied from Home Assistant works as is, `card_mod` excepted). Supported: the 63 themes and their animations, the 13 cycle
transitions, single entity or multi-entity filter (`entity_filter`, `device_class`, `label_filter`, `area_filter`,
exclusions), AND/OR conditions, `on_change`, `trigger_delay`, `auto_dismiss_after`, `persistent`, `time_range`,
grouping, snooze (menu or fixed duration), history, sound, text to speech (`tts.speak` or `notify`), push notifications,
overlay banner on the other screens, `{{ }}` templates rendered by HA, test mode, "all clear" state: message, clock (4
styles), weather (8 styles, animated background depending on the condition: sun, starry night with moon and aurora,
clouds, fog, wind, rain, snow, hail, thunderstorm), 7-day forecast and weather / forecast alternation.

App-specific options (equivalent of `card_mod`): `position`, `margin`, `max_width`, `radius`, `large_button_size`,
`large_button_right`, `content_padding_right`, `counter_top`, `counter_right`.

In edit mode, tapping the card opens its full editor (like the Lovelace visual editor), with a live preview. The module
is fully standalone: it does not read any Home Assistant dashboard.

### Edit mode

Settings › Dashboard › **Edit blocks**: tapping a block opens its settings instead of triggering it ("Ask for the access
code", "Always live" for cameras, "Chart height" for graphs). The access code can protect any block that acts on an
entity: room buttons, cameras, alarm, media player, remote, member choice. These choices are saved on the tablet, on top
of `dashboard.json`.

The **Entities** section of each block replaces the entities it uses (main entity, battery and motion sensors of a
camera, player of the remote, room information, person, sensors and calendars of a member, weather entity…), with search
and a filter by type; "Restore" goes back to `dashboard.json`. The replacement applies to all blocks that use the same
original entity, and the block settings (access code, always live, height) follow the new entity.

Exception: a graph with several lines (`mini_graph` with `entities`) is set up card by card, without touching the other
graphs that share an entity: title, value shown large, lines (displayed name, entity, add, remove; colours of the
original card) and day/night band.

A **carousel** (`swipe`) first opens its own window: list of its cards (open, move up, move down, remove, add a graph by
choosing its main value, the other settings taken from the first graph) and scrolling options (automatic, duration,
transition, dot indicator). A card opened from the carousel returns to it when closed; automatic scrolling is paused in
edit mode.

**Rooms** (`room_grid`): tapping a room (or one of its buttons) opens its editor: name, icon, entity, at most 2 sensors
(icon, entity, decimals), at most 3 devices (entity, icon, active icon and per-state icons such as `icon_movement`,
active circle colour, tap action: toggle, details, none or open a popup such as the remote, access code), position in
the grid and removal. An "Add a room" tile ends the grid in edit mode.

**Camera tabs** (`switcher` containing only cameras): in edit mode, a ✎ pill at the end of the tab bar opens the tab list
(name, number of cameras, default tab, order, removal, add); tapping a tab opens its window (icon, name, default tab, its
cameras: settings, order, removal, add). Closing a window opened from another returns to the previous one (camera → tab
→ tab list). An added camera takes the settings of the first camera, and its battery and motion sensors are found on the
same device in Home Assistant. A camera's battery and motion sensors can also be added from its settings window. Outside
edit mode, the camera column shows the first cameras of the tab (tab editor order) that fit, then a "+N" tile that
opens the camera wall. A camera whose *Show on the dashboard* option is off (its editor) only appears in the wall and
is not counted in "+N": if the others fit, there is no tile.

Global settings (connection, display, standby, access code…) stay in the Settings page.

## Notifications from Home Assistant

With *Settings › Home Assistant › Tablet in Home Assistant*, the tablet appears as a device (`mobile_app`) and receives
`notify.mobile_app_<name>` notifications:

| `message` | `data` | Effect |
|---|---|---|
| any text | `title` (optional) | banner at the top of the screen |
| `TTS` | `tts_text` | read aloud |
| `command_screen_on` | — | turns the screen on |
| `command_camera` | `entity` | camera fullscreen |
| `command_macs_mood` | `mood`, `duration` (seconds, default 10) | M.A.C.S. mood for a while: `surprised`, `happy`, `sad`, `confused`, `thinking`, `listening`, `sleeping`, `bored`; `idle` cancels it |

The mood entity of M.A.C.S. (`select.macs_mood` of the M.A.C.S. integration, or any entity whose state is a mood) is
optional: without it, M.A.C.S. stays calm, and the command above needs nothing to be created in Home Assistant.

```yaml
# Doorbell: M.A.C.S. surprised for 10 s
action: notify.mobile_app_wall_tablet
data:
  message: command_macs_mood
  data:
    mood: surprised
    duration: 10
```

## Home Assistant satellite (Wyoming)

Settings › Voice assistant › *Home Assistant satellite (Wyoming)*: the tablet behaves like a voice satellite
(wyoming-satellite, ESPHome). It listens on TCP port **10700** and announces itself over mDNS (`_wyoming._tcp`); Home
Assistant offers it in Settings › Devices & services ("Wyoming Protocol"), otherwise add it with the tablet's address
and this port. The Assist pipeline (speech recognition, agent, text to speech, wake word) is chosen on the device page
in Home Assistant.

- **Tap on M.A.C.S.**: pipeline from listening (`asr` → `tts`), 16 kHz PCM audio until the end of the sentence detected
  by Home Assistant;
- **Wake word** (option): microphone always open, detection done by Home Assistant (openWakeWord…);
- the transcript and the answer appear in the M.A.C.S. bubbles, the speech is played by the tablet.

The gateway below is the other possible system (exclusive): chosen on the same screen.

## Media player (DLNA)

Settings › Home Assistant › *Media player (DLNA)*: the tablet becomes a UPnP MediaRenderer (SSDP, AVTransport,
RenderingControl, ConnectionManager services, events). Home Assistant discovers it ("DLNA Digital Media Renderer") and
creates a `media_player` entity: `tts.speak`, `media_player.play_media`, radios, Music Assistant… The volume is Android's
media volume. A pill shows what is playing with pause and stop (bottom left on the home screen, next to the tab bar
elsewhere). Fixed HTTP port: 49494.

## Raspberry Pi

The app only needs Android: on a Raspberry Pi, install Android, then the `arm64-v8a` APK of the Release.
**Tested on a Pi 4 (8 GB, LineageOS 23.2 / Android 16, 1920×1080 HDMI screen)**: the whole display fits in 1080 px of
height; home screen ≈ 40 frames/s, dashboard ≈ 25 frames/s (CPU, M.A.C.S. included); ≈ 165 MB of memory. Hardware H.264
decoding (`c2.v4l2.avc.decoder`) for the fullscreen and the wall (3 live streams at once without trouble, automatic
estimate: 8); H.265 in software only. Without camera or microphone nothing crashes ("No camera", "Voice gateway
unreachable"). The Pi 5 has not been tried.

**Hardware**

- Raspberry Pi 4 (4 GB of memory or more) or Pi 5, fast microSD card (or SSD), official power supply.
- Power supply: 5.1 V / 3 A (Pi 4) or 5 V / 5 A (Pi 5). The Pi 4 does not negotiate USB Power Delivery: plugged into a
  USB-C dock port (even "PD") or a charger, it only gets the basic 5 V, often too tight with USB webcams. Under-voltage
  (log: `hwmon0: Undervoltage detected!`): HDMI signal lost ("No signal" on the screen), slowdowns. The app reads the
  `rpi_volt` sensor (`/sys/class/hwmon/*/in0_lcrit_alarm`, `PowerSupply.kt`): *Power supply too weak* pill for 10 min
  after the last alert and a note in the log (Settings › About).
- Touchscreen: the official Raspberry Pi display (DSI), an HDMI screen with USB touch, or a Waveshare SPI screen. A DSI
  display is enabled in Android's *Raspberry Pi settings*, with no HDMI screen plugged in. The brightness set by the app
  (dimming, night mode) depends on Android's support of the screen.
- Camera (optional, presence detection and avatars): preferably a USB webcam (UVC); the Pi camera modules (CSI) work,
  with known issues depending on the module.

**Installation**

1. **Android image**: KonstaKANG's "unofficial" LineageOS (Android without Google services), page of the
   [Pi 4](https://konstakang.com/devices/rpi4/) or the [Pi 5](https://konstakang.com/devices/rpi5/). Take the most recent
   LineageOS version (not the "Android TV" ones), then the `lineage-<version>-<date>-UNOFFICIAL-KonstaKANG-rpi4.zip` file
   (or `-rpi5`) of the *Download* section, not the `…-ota.zip` (update from the TWRP recovery). Only the latest version
   has a direct link; previous ones are in the *Previous builds* folder. A SHA-256 checksum is given to check the file.
2. **microSD card**: [Raspberry Pi Imager](https://www.raspberrypi.com/software/) (Windows, macOS, Linux). Choose the Pi
   model, then, in the list of operating systems, the last option *Use custom* and the downloaded `.zip` (Imager reads
   the `.zip` directly; otherwise extract the `.img`). Choose the card and write. Do not use the OS customisation offered
   by Imager (made for Raspberry Pi OS).
3. **First boot**: finish Android's setup (language, Wi-Fi).
4. **The app**: with the Pi's browser, open the
   [latest Release](https://github.com/Arubinu/ha-macs-android/releases/latest), download
   `ha-macs-android-<version>-arm64-v8a.apk` and open it (allow installing unknown apps). Or, from a computer:
   Settings › About tablet › tap *Build number* 7 times, enable ADB debugging in the developer options, then
   `adb install ha-macs-android-<version>-arm64-v8a.apk`.
5. The app's wizard does the rest. For the full kiosk:
   `adb shell dpm set-device-owner fr.pergens.hamacs/.kiosk.KioskAdmin` (Settings › Security › *Prepare the tablet*).

Google services (optional, only useful for a cleaner avatar cut-out): KonstaKANG explains on each version's page how to
add MindTheGapps from the TWRP recovery.

**Differences with a tablet**

- No Google services: the avatar cut-out uses the bundled model (slightly less clean edges).
- USB webcam: Android presents it as an "external" camera when the version allows it. It is chosen in
  Settings › Standby & presence › *Camera* (*Preview* button to check); a camera plugged in or unplugged is detected
  without restarting the app.
- Camera video: the Pi 4 decodes H.264 and H.265 in hardware (according to KonstaKANG); the Pi 5 has no hardware H.264
  decoder (the CPU is usually enough for one or two 1080p streams). The camera wall adapts the number of simultaneous
  live streams to what the device can decode (Settings › Display).

## Voice gateway

```sh
cd gateway
pip install -r requirements.txt
HA_URL=http://homeassistant.local:8123 HA_TOKEN=… LLM_URL=http://llama-server:8081 python voice_gateway.py
# or: docker compose up -d
```

Default behaviour (`MODE=primed`):

1. the tablet streams the microphone (16 kHz PCM) over WebSocket and detects the end of speech (500 ms of silence); as
   soon as the sentence starts, the gateway wakes the model up if it was asleep;
2. a fast transcription (~0.35 s) is used to show "I heard" and to recognise **simple commands** ("turn on/off/open/close
   …", "what is the temperature …"), run immediately without the model;
3. otherwise, **a single call** to the audio model with the original audio + the relevant entities and their state;
4. the answer appears in the M.A.C.S. bubbles and is **spoken** through llama-server's `/tts` in streaming
   (llama.cpp ≥ 0.8.0 with LFM3-Audio); M.A.C.S.'s face reflects the emotion of the answer.

Measured on a simple question: first sound ~0.5 s after the end of the sentence.

| Variable | Default | Role |
|---|---|---|
| `MODE` | `primed` | `primed` (ASR + one audio call) or `audio` (audio only, the model searches through tools) |
| `ENTITY_SOURCE` | `auto` | `assist` (HA's Assist exposure), `all` (allowed domains), `auto` |
| `CANDIDATES` | `10` | entities offered to the model in `primed` mode |
| `CONFIRM_MODE` | `fast` | `fast`: no second model call after a successful action |
| `TRANSCRIBE` | `after` | display transcription in `audio` mode: `after`, `parallel`, `off` |
| `ALLOWED_DOMAINS` | lights, switches, covers… | domains the model is allowed to control |
| `FAST_INTENTS` | `1` | simple commands handled locally, without calling the model |
| `TTS` | `1` | spoken answer through llama-server's `/tts` |
| `TTS_VOICE` | — | preset voice of the model (e.g. `female`, `male`) |
| `TTS_VOICES_DIR` | — | folder of reference clips per emotion (`neutral.wav`, `happy.wav`, `sad.wav`) for voice cloning |

Test tools:

- `gateway/test_client.py question.wav` simulates a tablet: response time, first sound, audio in `reponse.wav`;
- `gateway/test_intents.py "turn on the living room light" …` shows what the local recognition would do, without
  running anything.
