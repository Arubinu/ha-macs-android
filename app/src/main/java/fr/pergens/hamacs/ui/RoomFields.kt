package fr.pergens.hamacs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.BlockOverrides
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.IconRegistry
import fr.pergens.hamacs.ui.theme.Dash
import fr.pergens.hamacs.ui.theme.named
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val Muted = Color(255, 255, 255, 150)
private val RowBg = Color(255, 255, 255, 10)
private val Danger = Color(0xFFFF6B6B)

private fun JsonObject.with(k: String, v: JsonElement?) = JsonObject(if (v == null) this - k else this + (k to v))
private fun JsonObject.withStr(k: String, v: String?) = with(k, v?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private const val MAX_SENSORS = 2
private const val MAX_DEVICES = 3

/** Pièces de la grille [gridKey] : liste d'origine (dashboard.json) et liste actuelle (avec les changements). */
private fun roomsOf(gridKey: String): Pair<JsonArray, List<JsonObject>>? {
    val config = App.instance.config
    val original = BlockOverrides.find(config.rawDashboard.value, gridKey)?.get("rooms") as? JsonArray ?: return null
    val current = (BlockOverrides.find(config.sourceDashboard.value, gridKey)?.get("rooms") as? JsonArray ?: original)
        .mapNotNull { it as? JsonObject }
    return original to current
}

private fun saveRooms(gridKey: String, original: JsonArray, list: List<JsonObject>) {
    val arr = JsonArray(list.map { JsonObject(it - "_key") })
    App.instance.config.setBlockField(gridKey, "rooms", arr.takeIf { it != original })
}

/** Ajoute une pièce vide à la grille, puis ouvre son éditeur ([open] reçoit sa position). */
fun addRoom(gridKey: String, open: (Int) -> Unit) {
    val (original, rooms) = roomsOf(gridKey) ?: return
    val room = JsonObject(mapOf(
        "name" to JsonPrimitive(App.instance.getString(R.string.room_new_name)),
        "icon" to JsonPrimitive("mdi:home-outline"),
        "info" to JsonArray(emptyList()), "buttons" to JsonArray(emptyList()),
    ))
    saveRooms(gridKey, original, rooms + room)
    open(rooms.size)
}

/** Icône proposée pour un nouvel appareil, selon le type d'entité. */
private fun defaultIcon(entity: String): String = App.instance.ha.states.value[entity]?.attrString("icon") ?: when (entity.substringBefore('.')) {
    "light" -> "mdi:lightbulb"
    "switch" -> "mdi:power-socket-eu"
    "media_player", "remote" -> "mdi:remote"
    "fan" -> "mdi:fan"
    "cover" -> "mdi:window-shutter"
    "climate" -> "mdi:thermostat"
    "binary_sensor" -> "mdi:motion-sensor"
    else -> "mdi:toggle-switch"
}

/**
 * Éditeur d'une pièce : nom, icône, entité (médaillon allumé quand elle est active), deux capteurs au plus,
 * trois appareils au plus (entité, icônes selon l'état, couleur du cercle actif, action, code d'accès),
 * position dans la grille et suppression. Tout est enregistré immédiatement.
 */
@Composable
fun RoomFields(gridKey: String, index: Int, onClose: () -> Unit) {
    val app = App.instance
    // Recomposition à chaque changement (structure de la grille, réglages des blocs).
    app.config.sourceDashboard.collectAsState().value
    val blocks by app.config.rawBlocks.collectAsState()
    val (original, rooms) = roomsOf(gridKey) ?: return
    val room = rooms.getOrNull(index) ?: return
    fun save(r: JsonObject) = saveRooms(gridKey, original, rooms.mapIndexed { j, x -> if (j == index) r else x })

    var iconFor by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var entityFor by remember { mutableStateOf<Pair<String?, (String) -> Unit>?>(null) }

    // --- Pièce ---------------------------------------------------------------------------------------------
    RoomSection(stringResource(R.string.room_section))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Debounced(room.s("name") ?: "", stringResource(R.string.room_name)) { save(room.withStr("name", it)) }
        IconChoice(stringResource(R.string.room_icon), room.s("icon"), Modifier.fillMaxWidth()) { iconFor = { ic -> save(room.withStr("icon", ic)) } }
        val pickMain = { entityFor = (room.s("entity")?.substringBefore('.') ?: "light") to { id: String -> save(room.withStr("entity", id)) } }
        EntityCard(room.s("entity") ?: "—", label = stringResource(R.string.room_entity), onClick = pickMain) {
            EntityCardAction("mdi:swap-horizontal", tint = Dash.colors.accent, onClick = pickMain)
            if (room.s("entity") != null) EntityCardAction("mdi:close") { save(room.withStr("entity", null)) }
        }
        room.s("entity")?.let { main ->
            val protected = blocks[app.config.resolveKey(main)]?.b("protected") ?: false
            SwitchLine(stringResource(R.string.edit_protect), protected) { app.config.setBlockOption(main, "protected", it.takeIf { v -> v }) }
        }
    }

    // --- Capteurs ------------------------------------------------------------------------------------------
    val info = room.list("info")
    RoomSection(stringResource(R.string.room_sensors, info.size, MAX_SENSORS))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        info.forEachIndexed { i, sensor ->
            fun setSensor(s: JsonObject) = save(room.with("info", JsonArray(info.mapIndexed { j, x -> if (j == i) s else x })))
            val pickSensor = {
                entityFor = "sensor" to { id: String ->
                    // Nouvelle entité : son unité est reprise automatiquement (plus de suffixe imposé).
                    setSensor(JsonObject(sensor - "suffix" - "attribute" + ("entity" to JsonPrimitive(id))))
                }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RowBg).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EntityCard(sensor.s("entity") ?: "—", onClick = pickSensor) {
                    EntityCardAction("mdi:swap-horizontal", tint = Dash.colors.accent, onClick = pickSensor)
                    EntityCardAction("mdi:delete-outline") { save(room.with("info", JsonArray(info.filterIndexed { j, _ -> j != i }))) }
                }
                // Icône affichée dans la pièce, et décimales : auto (valeur brute) ou 0, 1, 2.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(sensor.s("icon"), Color.White.copy(alpha = 0.08f)) { iconFor = { ic -> setSensor(sensor.withStr("icon", ic)) } }
                    Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.room_decimals), style = TextStyle(fontSize = 12.sp, color = Muted))
                        listOf<Int?>(null, 0, 1, 2).forEach { d ->
                            Chip(d?.toString() ?: stringResource(R.string.room_decimals_auto), sensor.i("decimals") == d && (d != null || sensor["decimals"] == null)) {
                                setSensor(sensor.with("decimals", d?.let { JsonPrimitive(it) }))
                            }
                        }
                    }
                }
            }
        }
        if (info.size < MAX_SENSORS) AddButton(stringResource(R.string.room_add_sensor)) {
            entityFor = "sensor" to { id ->
                val unit = app.ha.states.value[id]?.unit
                val icon = app.ha.states.value[id]?.attrString("icon") ?: when {
                    unit?.startsWith("°") == true -> "mdi:thermometer"
                    unit == "%" -> "mdi:water-percent"
                    else -> "mdi:gauge"
                }
                save(room.with("info", JsonArray(info + JsonObject(mapOf("entity" to JsonPrimitive(id), "icon" to JsonPrimitive(icon))))))
            }
        }
    }

    // --- Appareils -----------------------------------------------------------------------------------------
    val devices = room.list("buttons")
    RoomSection(stringResource(R.string.room_devices, devices.size, MAX_DEVICES))
    val state = LocalDashState.current
    // Liste compacte : cercle actif (couleur, icône) et entité ; toucher un appareil ouvre sa fenêtre.
    fun openDevice(i: Int) = state.openEditChild(EditTarget.Room(gridKey, index), EditTarget.Device(gridKey, index, i))
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEachIndexed { i, dev ->
            val colors = Dash.colors
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(colors.named(dev.s("color"), colors.firstButtonBackground)).clickable { openDevice(i) },
                    contentAlignment = Alignment.Center,
                ) { HaIcon(dev.s("icon_on") ?: dev.s("icon"), size = 26.dp, tint = colors.named(dev.s("icon_color"), Color.White)) }
                EntityCard(dev.s("entity") ?: "—", Modifier.weight(1f).padding(start = 10.dp), onClick = { openDevice(i) }) {
                    EntityCardAction("mdi:chevron-up", enabled = i > 0) { save(room.with("buttons", JsonArray(devices.toMutableList().apply { add(i - 1, removeAt(i)) }))) }
                    EntityCardAction("mdi:chevron-down", enabled = i < devices.lastIndex) { save(room.with("buttons", JsonArray(devices.toMutableList().apply { add(i + 1, removeAt(i)) }))) }
                    EntityCardAction("mdi:delete-outline") { save(room.with("buttons", JsonArray(devices.filterIndexed { j, _ -> j != i }))) }
                }
            }
        }
        if (devices.size < MAX_DEVICES) AddButton(stringResource(R.string.room_add_device)) {
            entityFor = null as String? to { id ->
                save(room.with("buttons", JsonArray(devices + JsonObject(mapOf(
                    "entity" to JsonPrimitive(id), "icon" to JsonPrimitive(defaultIcon(id)), "color" to JsonPrimitive("first"),
                )))))
                // Le nouvel appareil s'ouvre directement pour régler ses icônes, sa couleur et son action.
                openDevice(devices.size)
            }
        }
    }

    // --- Position et suppression -------------------------------------------------------------------------
    RoomSection(stringResource(R.string.room_position))
    Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fun move(to: Int) {
            saveRooms(gridKey, original, rooms.toMutableList().apply { add(to, removeAt(index)) })
            state.editTarget = EditTarget.Room(gridKey, to)
        }
        OutlineButton("mdi:chevron-left", stringResource(R.string.room_move_before), enabled = index > 0) { move(index - 1) }
        OutlineButton("mdi:chevron-right", stringResource(R.string.room_move_after), enabled = index < rooms.lastIndex) { move(index + 1) }
        Box(Modifier.weight(1f))
        OutlineButton("mdi:delete-outline", stringResource(R.string.room_delete), color = Danger, enabled = rooms.size > 1) {
            saveRooms(gridKey, original, rooms.filterIndexed { j, _ -> j != index })
            onClose()
        }
    }
    // Pièce de dashboard.json modifiée : retour possible à la grille d'origine.
    if ((blocks[gridKey]?.get("set") as? JsonObject)?.get("rooms") != null) Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterEnd) {
        TextLink(stringResource(R.string.room_reset)) { app.config.setBlockField(gridKey, "rooms", null) }
    }

    iconFor?.let { apply -> IconPicker(onDismiss = { iconFor = null }) { ic -> iconFor = null; apply(ic) } }
    entityFor?.let { (domain, apply) -> EntityPicker(domain, onDismiss = { entityFor = null }) { id -> entityFor = null; apply(id) } }
}

/**
 * Fenêtre d'un appareil n° [deviceIndex] de la pièce n° [roomIndex] de la grille [gridKey] : entité, icônes,
 * couleur du cercle actif, action au toucher, code d'accès et suppression (qui referme la fenêtre).
 */
@Composable
fun DeviceFields(gridKey: String, roomIndex: Int, deviceIndex: Int, onClose: () -> Unit) {
    App.instance.config.sourceDashboard.collectAsState().value
    val blocks by App.instance.config.rawBlocks.collectAsState()
    val (original, rooms) = roomsOf(gridKey) ?: return
    val room = rooms.getOrNull(roomIndex) ?: return
    val devices = room.list("buttons")
    val dev = devices.getOrNull(deviceIndex) ?: return
    fun saveDevices(list: List<JsonObject>) =
        saveRooms(gridKey, original, rooms.mapIndexed { j, x -> if (j == roomIndex) room.with("buttons", JsonArray(list)) else x })
    var iconFor by remember { mutableStateOf<((String?) -> Unit)?>(null) }
    var entityFor by remember { mutableStateOf<((String) -> Unit)?>(null) }
    DeviceCard(
        dev, blocks,
        onChange = { d -> saveDevices(devices.mapIndexed { j, x -> if (j == deviceIndex) d else x }) },
        onDelete = { saveDevices(devices.filterIndexed { j, _ -> j != deviceIndex }); onClose() },
        pickIcon = { apply -> iconFor = apply },
        pickEntity = { apply -> entityFor = apply },
    )
    iconFor?.let { apply -> IconPicker(onDismiss = { iconFor = null }) { ic -> iconFor = null; apply(ic) } }
    entityFor?.let { apply -> EntityPicker(dev.s("entity")?.substringBefore('.'), onDismiss = { entityFor = null }) { id -> entityFor = null; apply(id) } }
}

/** Nom affiché d'un appareil (titre de sa fenêtre). */
internal fun deviceTitle(gridKey: String, roomIndex: Int, deviceIndex: Int): String? {
    val dev = roomsOf(gridKey)?.second?.getOrNull(roomIndex)?.list("buttons")?.getOrNull(deviceIndex) ?: return null
    val id = dev.s("entity") ?: return null
    return App.instance.ha.states.value[id]?.name ?: id
}

/**
 * Appareil d'une pièce : aperçu du cercle actif (couleur et icône), entité, icônes (par défaut, quand actif,
 * et pour un état précis comme `icon_movement`), couleur active, action au toucher, code d'accès, suppression.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(
    dev: JsonObject, blocks: Map<String, JsonObject>, onChange: (JsonObject) -> Unit, onDelete: () -> Unit,
    pickIcon: (((String?) -> Unit)) -> Unit, pickEntity: ((String) -> Unit) -> Unit,
) {
    val app = App.instance
    val colors = Dash.colors
    val activeColor = colors.named(dev.s("color"), colors.firstButtonBackground)
    // Directement dans la fenêtre de l'appareil : pas de cadre autour.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Aperçu : le bouton tel qu'il apparaît quand l'appareil est actif.
            Box(Modifier.size(46.dp).clip(CircleShape).background(activeColor), contentAlignment = Alignment.Center) {
                HaIcon(dev.s("icon_on") ?: dev.s("icon"), size = 26.dp, tint = colors.named(dev.s("icon_color"), Color.White))
            }
            val pickDevice = { pickEntity { id -> onChange(dev.withStr("entity", id)) } }
            EntityCard(dev.s("entity") ?: "—", Modifier.weight(1f).padding(start = 12.dp), onClick = pickDevice) {
                EntityCardAction("mdi:swap-horizontal", tint = colors.accent, onClick = pickDevice)
                EntityCardAction("mdi:delete-outline", onClick = onDelete)
            }
        }
        // Icônes : par défaut (inactif), quand actif, puis celles d'états précis déjà présentes (icon_<état>).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconChoice(stringResource(R.string.room_icon_default), dev.s("icon"), Modifier) { pickIcon { ic -> onChange(dev.withStr("icon", ic)) } }
            IconChoice(stringResource(R.string.room_icon_on), dev.s("icon_on"), Modifier, optional = true) { pickIcon { ic -> onChange(dev.withStr("icon_on", ic)) } }
            dev.keys.filter { it.startsWith("icon_") && it !in setOf("icon_on", "icon_off", "icon_color") }.forEach { k ->
                IconChoice(stringResource(R.string.room_icon_state, k.removePrefix("icon_")), dev.s(k), Modifier, optional = true) {
                    pickIcon { ic -> onChange(dev.withStr(k, ic)) }
                }
            }
        }
        // Couleur du cercle quand l'appareil est actif.
        Text(stringResource(R.string.room_color), style = TextStyle(fontSize = 13.sp, color = Muted))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val choices = listOf("first", "second", "third", "accent", "#E53935", "#FB8C00", "#FDD835", "#43A047", "#1E88E5", "#8E24AA", "#EC407A", "#FFFFFF")
            choices.forEach { c ->
                val col = colors.named(c, Color.Gray)
                val sel = (dev.s("color") ?: "first").equals(c, ignoreCase = true)
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(col)
                        .border(if (sel) 3.dp else 1.dp, if (sel) Color.White else Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable { onChange(dev.withStr("color", c)) },
                )
            }
        }
        // Action au toucher : allumer/éteindre, détails, aucune, ou ouvrir une popup (télécommande…).
        Text(stringResource(R.string.room_action), style = TextStyle(fontSize = 13.sp, color = Muted))
        val popups = app.config.dashboard.collectAsState().value.o("popups")
        val tap = dev.o("tap_action")
        val current = when (tap?.s("action")) {
            null, "toggle" -> "toggle"
            "navigate" -> "popup:" + tap.s("navigation_path")?.removePrefix("#")
            else -> tap.s("action")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.room_action_toggle), current == "toggle") { onChange(dev.with("tap_action", null)) }
            Chip(stringResource(R.string.room_action_details), current == "more-info") { onChange(dev.with("tap_action", JsonObject(mapOf("action" to JsonPrimitive("more-info"))))) }
            Chip(stringResource(R.string.room_action_none), current == "none") { onChange(dev.with("tap_action", JsonObject(mapOf("action" to JsonPrimitive("none"))))) }
            popups?.forEach { (id, p) ->
                val name = (p as? JsonObject)?.s("name") ?: id
                Chip(stringResource(R.string.room_action_popup, name), current == "popup:$id") {
                    onChange(dev.with("tap_action", JsonObject(mapOf("action" to JsonPrimitive("navigate"), "navigation_path" to JsonPrimitive("#$id")))))
                }
            }
        }
        dev.s("entity")?.let { id ->
            val protected = blocks[app.config.resolveKey(id)]?.b("protected") ?: (dev.o("tap_action")?.get("pin") != null)
            SwitchLine(stringResource(R.string.edit_protect), protected) { app.config.setBlockOption(id, "protected", it) }
        }
    }
}

// --- Petits éléments ---------------------------------------------------------------------------------------

@Composable
private fun RoomSection(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
}

@Composable
private fun Debounced(value: String, label: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(600)
        onSave(text.trim())
    }
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent, cursorColor = Dash.colors.accent),
    )
}

/** Icône choisie (aperçu + libellé) ; un toucher ouvre la liste des icônes. */
@Composable
private fun IconChoice(label: String, icon: String?, modifier: Modifier, optional: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(RowBg, shape).clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) HaIcon(icon, size = 26.dp) else HaIcon("mdi:plus", size = 22.dp, tint = Muted)
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, style = TextStyle(fontSize = 13.sp, color = Muted))
            Text(icon ?: if (optional) stringResource(R.string.room_icon_none) else "—", style = TextStyle(fontSize = 13.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EntityChoiceBox(label: String, id: String?, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.clip(shape).background(RowBg, shape).clickable(onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = TextStyle(fontSize = 13.sp, color = Muted))
        if (id != null) EntityName(id) else Text(stringResource(R.string.room_entity_none), style = TextStyle(fontSize = 13.sp, color = Color.White))
    }
}

@Composable
private fun IconBubble(icon: String?, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        HaIcon(icon ?: "mdi:plus", size = 22.dp)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        Modifier.clip(shape).background(if (selected) Dash.colors.accent else Color.Transparent, shape)
            .border(1.dp, if (selected) Dash.colors.accent else Color.White.copy(alpha = 0.25f), shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
        style = TextStyle(fontSize = 13.sp, color = if (selected) Color.Black else Color.White),
    )
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        HaIcon("mdi:lock-outline", size = 18.dp, tint = Muted)
        Text(title, Modifier.weight(1f).padding(start = 8.dp), style = TextStyle(fontSize = 14.sp, color = Color.White))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Dash.colors.accent, checkedThumbColor = Color.Black))
    }
}

@Composable
private fun IconAction(icon: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        HaIcon(icon, size = 22.dp, tint = Muted)
    }
}

@Composable
private fun OutlineButton(icon: String, text: String, color: Color = Dash.colors.accent, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val c = if (enabled) color else Color.White.copy(alpha = 0.2f)
    Row(
        Modifier.clip(shape).border(1.dp, c, shape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaIcon(icon, size = 18.dp, tint = c)
        Text(text, Modifier.padding(start = 6.dp), style = TextStyle(fontSize = 14.sp, color = c, fontWeight = FontWeight.Medium))
    }
}

/** Choix d'une icône (Material Design Icons et Hue) : recherche par nom, grille d'aperçus, « Aucune ». */
@Composable
internal fun IconPicker(onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = remember(query) {
        val q = query.trim().lowercase().replace(' ', '-')
        IconRegistry.allIcons.filter { q.isEmpty() || it.contains(q) }.take(400)
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth(0.55f).fillMaxHeight(0.85f).clip(shape).background(Color(0xF2222222), shape).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.room_pick_icon), Modifier.weight(1f), style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium))
                TextLink(stringResource(R.string.room_icon_none)) { onPick(null) }
                IconAction("mdi:close", onDismiss)
            }
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.room_icon_search)) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent, cursorColor = Dash.colors.accent),
            )
            LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxWidth().weight(1f)) {
                items(list, key = { it }) { ic ->
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(ic) }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        HaIcon(ic, size = 30.dp)
                        Text(
                            ic.substringAfter(':'), Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 10.sp, color = Muted),
                        )
                    }
                }
            }
        }
    }
}
