package fr.arubinu.hadash.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arubinu.hadash.App
import fr.arubinu.hadash.R
import fr.arubinu.hadash.config.BlockOverrides
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ha.browseMedia
import fr.arubinu.hadash.detections.DetectionConfig
import fr.arubinu.hadash.detections.DetectionScanner
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter

private val Muted = Color(255, 255, 255, 150)
private val RowBg = Color(255, 255, 255, 10)
private val Danger = Color(0xFFFF6B6B)

/** Champs modifiables de la zone des détections (surcharges `set`). */
private val DET_FIELDS = listOf(
    "storage", "minutes", "time_source", "time_format", "image_ext", "video_ext", "pairing", "image_affix", "video_affix", "cameras",
)

/** Zone des détections [key] : carte effective, carte d'origine et enregistrement d'un champ. */
private class DetData(val key: String, val card: JsonObject, val original: JsonObject) {
    val cfg = DetectionConfig(card)
    val cameras: List<JsonObject> = DetectionConfig.camerasJson(card)
    /** null ou valeur d'origine = surcharge retirée. */
    fun set(field: String, value: JsonElement?) =
        App.instance.config.setBlockField(key, field, value?.takeIf { it != original[field] })
    fun setCameras(list: List<JsonObject>) = set("cameras", JsonArray(list))
    fun setCamera(i: Int, c: JsonObject) = setCameras(cameras.mapIndexed { j, x -> if (j == i) c else x })
}

@Composable
private fun rememberDet(key: String): DetData? {
    val app = App.instance
    val rawDash by app.config.rawDashboard.collectAsState()
    val dash by app.config.dashboard.collectAsState()
    val original = remember(rawDash, key) { BlockOverrides.find(rawDash, key) } ?: return null
    val card = remember(dash, key) { BlockOverrides.find(dash, key) } ?: original
    return remember(card, original) { DetData(key, card, original) }
}

private fun JsonObject.with(k: String, v: String?) = JsonObject(if (v.isNullOrBlank()) this - k else this + (k to JsonPrimitive(v)))

/**
 * Réglages de la zone des détections : durée d'affichage, stockage (médias de Home Assistant, partage SMB ou NFS,
 * dossier de la tablette, entités) avec test, lecture des fichiers (date, extensions, association capture ↔
 * vidéo) et liste des caméras (chacune ouvre sa fenêtre).
 */
@Composable
fun DetectionFields(key: String) {
    val app = App.instance
    val state = LocalDashState.current
    val d = rememberDet(key) ?: return
    val type = d.cfg.type

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // --- Affichage
        DetSection(stringResource(R.string.det_section_display))
        var minutes by remember(d.cfg.minutes) { mutableFloatStateOf(d.cfg.minutes.toFloat()) }
        Column(Modifier.fillMaxWidth().padding(start = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.det_minutes), style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))
                Text(stringResource(R.string.det_minutes_help), style = TextStyle(fontSize = 13.sp, color = Muted))
            }
            Text(durationLabel(minutes.toInt()), style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
        }
        Slider(
            minutes, { minutes = (Math.round(it / 5f) * 5f).coerceAtLeast(5f) }, valueRange = 5f..240f,
            onValueChangeFinished = { d.set("minutes", JsonPrimitive(minutes.toInt())) },
            colors = SliderDefaults.colors(thumbColor = Dash.colors.accent, activeTrackColor = Dash.colors.accent, inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
        )
        }

        // --- Stockage
        DetSection(stringResource(R.string.det_section_storage))
        Box(Modifier.fillMaxWidth().padding(start = 14.dp)) {
        Chips(
            listOf(
                "ha_media" to stringResource(R.string.det_storage_ha),
                "smb" to stringResource(R.string.det_storage_smb),
                "nfs" to stringResource(R.string.det_storage_nfs),
                "local" to stringResource(R.string.det_storage_local),
                "entities" to stringResource(R.string.det_storage_entities),
            ),
            type,
        ) { t ->
            if (t != type) {
                // Réglages déjà saisis pour ce type conservés (champ `saved_<type>`), le reste repart de zéro.
                val saved = (d.card["saved_$t"] as? JsonObject) ?: JsonObject(mapOf("type" to JsonPrimitive(t)))
                d.set("saved_$type", d.cfg.storage)
                d.set("storage", saved)
            }
        }
        }
        StorageFields(d)
        TestButton(d)

        // --- Fichiers
        if (type != "entities") {
            DetSection(stringResource(R.string.det_section_files))
            Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.det_time_source))
            val hasModified = type != "ha_media"
            Chips(
                listOf("name" to stringResource(R.string.det_time_name), "modified" to stringResource(R.string.det_time_modified)),
                if (hasModified) d.cfg.timeSource else "name",
                enabled = { it == "name" || hasModified },
            ) { d.set("time_source", JsonPrimitive(it).takeIf { _ -> it != "name" }) }
            if (!hasModified) Help(stringResource(R.string.det_time_modified_ha))
            if (d.cfg.timeSource != "modified" || !hasModified) {
                DebouncedField(d.card.s("time_format").orEmpty(), stringResource(R.string.det_time_format), stringResource(R.string.det_time_format_hint)) {
                    d.set("time_format", it.takeIf { v -> v.isNotBlank() }?.let { v -> JsonPrimitive(v) })
                }
                Help(stringResource(R.string.det_time_format_help))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DebouncedField(d.card.s("image_ext").orEmpty(), stringResource(R.string.det_image_ext), "jpg, jpeg, png", Modifier.weight(1f)) {
                    d.set("image_ext", it.takeIf { v -> v.isNotBlank() }?.let { v -> JsonPrimitive(v) })
                }
                DebouncedField(d.card.s("video_ext").orEmpty(), stringResource(R.string.det_video_ext), "mp4, mkv, mov", Modifier.weight(1f)) {
                    d.set("video_ext", it.takeIf { v -> v.isNotBlank() }?.let { v -> JsonPrimitive(v) })
                }
            }
            Label(stringResource(R.string.det_pairing))
            Chips(
                listOf(
                    "auto" to stringResource(R.string.det_pairing_auto),
                    "name" to stringResource(R.string.det_pairing_name),
                    "time" to stringResource(R.string.det_pairing_time),
                ),
                d.cfg.pairing,
            ) { d.set("pairing", JsonPrimitive(it).takeIf { _ -> it != "auto" }) }
            if (d.cfg.pairing != "time") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DebouncedField(d.cfg.imageAffix, stringResource(R.string.det_image_affix), "_snap", Modifier.weight(1f)) {
                        d.set("image_affix", it.takeIf { v -> v.isNotEmpty() }?.let { v -> JsonPrimitive(v) })
                    }
                    DebouncedField(d.cfg.videoAffix, stringResource(R.string.det_video_affix), "_rec", Modifier.weight(1f)) {
                        d.set("video_affix", it.takeIf { v -> v.isNotEmpty() }?.let { v -> JsonPrimitive(v) })
                    }
                }
                Help(stringResource(R.string.det_affix_help))
            }
            }
        }

        // --- Caméras
        DetSection(stringResource(R.string.det_section_cameras))
        Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (d.cameras.isEmpty() && type != "entities") Help(stringResource(R.string.det_cameras_none))
        d.cameras.forEachIndexed { i, cam ->
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(RowBg, shape).border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                    .clickable { state.openEditChild(EditTarget.Detections(key), EditTarget.DetectionCamera(key, i)) }
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Dash.colors.menuActiveButtonBackground), contentAlignment = Alignment.Center) {
                    HaIcon(if (type == "entities") entityIcon(cam.s("entity").orEmpty(), App.instance.ha.states.value[cam.s("entity")]) else "mdi:cctv", size = 20.dp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(cam.s("name")?.ifBlank { null } ?: stringResource(R.string.det_camera_unnamed), style = TextStyle(fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Medium))
                    Text(cameraSummary(cam, type), style = TextStyle(fontSize = 13.sp, color = Muted))
                }
                RowIcon("mdi:chevron-up", i > 0) { d.setCameras(d.cameras.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                RowIcon("mdi:chevron-down", i < d.cameras.lastIndex) { d.setCameras(d.cameras.toMutableList().apply { add(i + 1, removeAt(i)) }) }
                RowIcon("mdi:delete-outline", true, Danger) { d.setCameras(d.cameras.filterIndexed { j, _ -> j != i }) }
            }
        }
        AddButton(stringResource(R.string.det_add_camera)) {
            val n = d.cameras.size + 1
            d.setCameras(d.cameras + JsonObject(mapOf(
                "name" to JsonPrimitive(app.getString(R.string.det_camera_new, n)),
                "match" to JsonPrimitive(""), "mode" to JsonPrimitive("prefix"),
            )))
            state.openEditChild(EditTarget.Detections(key), EditTarget.DetectionCamera(key, d.cameras.size))
        }
        }

        val set = app.config.rawBlocks.collectAsState().value[key]?.get("set") as? JsonObject
        if (set != null && set.isNotEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            TextLink(stringResource(R.string.det_reset)) {
                (DET_FIELDS + set.keys.filter { it.startsWith("saved_") }).forEach { app.config.setBlockField(key, it, null) }
            }
        }
    }
}

@Composable
private fun cameraSummary(cam: JsonObject, type: String): String {
    if (type == "entities") return cam.s("entity") ?: stringResource(R.string.det_camera_no_entity)
    val match = cam.s("match").orEmpty()
    val mode = cam.s("mode") ?: "prefix"
    val rule = when {
        mode == "all" || match.isBlank() -> stringResource(R.string.det_match_all)
        mode == "suffix" -> stringResource(R.string.det_match_suffix_short, match)
        mode == "contains" -> stringResource(R.string.det_match_contains_short, match)
        else -> stringResource(R.string.det_match_prefix_short, match)
    }
    val folder = cam.s("folder")?.takeIf { it.isNotBlank() }
    return if (folder != null) "$rule · $folder" else rule
}

@Composable
private fun durationLabel(min: Int): String =
    if (min < 60) stringResource(R.string.det_duration_min, min)
    else if (min % 60 == 0) stringResource(R.string.det_duration_h, min / 60)
    else stringResource(R.string.det_duration_hmin, min / 60, min % 60)

/** Paramètres du stockage choisi, appliqués d'un coup (« Appliquer ») pour ne pas se connecter à chaque frappe. */
@Composable
private fun StorageFields(d: DetData) {
    val ctx = LocalContext.current
    val storage = d.cfg.storage
    val type = d.cfg.type
    val values = remember(storage) { mutableStateMapOf<String, String>().apply { storage.forEach { (k, v) -> (v as? JsonPrimitive)?.content?.let { put(k, it) } } } }
    fun apply() {
        val o = JsonObject(buildMap {
            put("type", JsonPrimitive(type))
            values.filterKeys { it != "type" }.forEach { (k, v) -> if (v.isNotBlank()) put(k, JsonPrimitive(v.trim())) }
        })
        d.set("storage", o)
    }
    val changed = values.filterKeys { it != "type" }.any { (k, v) -> v.trim() != storage.s(k).orEmpty() } ||
        storage.keys.any { it != "type" && values[it].isNullOrBlank() }

    var browse by remember { mutableStateOf<BrowseRequest?>(null) }
    val browseTitle = stringResource(R.string.browse_title)
    val mediaRoot = stringResource(R.string.browse_media_root)

    @Composable
    fun field(k: String, label: String, hint: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), secret: Boolean = false, number: Boolean = false, onBrowse: (() -> Unit)? = null) =
        TextBox(values[k].orEmpty(), { values[k] = it }, label, hint, modifier, secret, number, onBrowse)

    /** Dossier d'un partage SMB / NFS, exploré avec les paramètres saisis (même non appliqués). */
    fun browseShare() {
        val cfg = JsonObject(buildMap {
            put("type", JsonPrimitive(type))
            values.filterKeys { it != "type" && it != "path" }.forEach { (k, v) -> if (v.isNotBlank()) put(k, JsonPrimitive(v.trim())) }
        })
        val storage = fr.arubinu.hadash.detections.DetectionStorage.of(cfg) ?: return
        val rootLabel = values["share"]?.takeIf { it.isNotBlank() } ?: values["export"]?.takeIf { it.isNotBlank() } ?: values["host"].orEmpty()
        browse = BrowseRequest(browseTitle, FolderNode(rootLabel, ""), storageChildren(storage)) { picked ->
            values["path"] = picked
            apply()
        }
    }

    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (type) {
            "ha_media" -> {
                field("path", stringResource(R.string.det_ha_path), "media-source://media_source/local/…", onBrowse = {
                    browse = BrowseRequest(browseTitle, FolderNode(mediaRoot, "media-source://"), { id ->
                        App.instance.ha.browseMedia(id).children.filter { it.canExpand }.map { FolderNode(it.title, it.contentId) }
                    }) { picked ->
                        // Autre dossier : l'ancienne adresse des fichiers ne correspond plus (Home Assistant la fournira).
                        if (picked != storage.s("path")) values["url_prefix"] = ""
                        values["path"] = picked
                        apply()
                    }
                })
                field("url_prefix", stringResource(R.string.det_ha_prefix), "/local/media/…")
                Help(stringResource(R.string.det_ha_help))
            }
            "smb" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field("host", stringResource(R.string.det_host), "192.168.1.10", Modifier.weight(1f))
                    field("share", stringResource(R.string.det_share), "cameras", Modifier.weight(1f))
                }
                field("path", stringResource(R.string.det_folder), stringResource(R.string.det_folder_hint), onBrowse = { browseShare() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field("user", stringResource(R.string.det_user), null, Modifier.weight(1f))
                    field("password", stringResource(R.string.det_password), null, Modifier.weight(1f), secret = true)
                }
                field("domain", stringResource(R.string.det_domain), "WORKGROUP")
                Help(stringResource(R.string.det_smb_help))
            }
            "nfs" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field("host", stringResource(R.string.det_host), "192.168.1.10", Modifier.weight(1f))
                    field("export", stringResource(R.string.det_export), "/volume1/cameras", Modifier.weight(1f))
                }
                field("path", stringResource(R.string.det_folder), stringResource(R.string.det_folder_hint), onBrowse = { browseShare() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field("uid", "UID", "0", Modifier.weight(1f), number = true)
                    field("gid", "GID", "0", Modifier.weight(1f), number = true)
                }
                Help(stringResource(R.string.det_nfs_help))
            }
            "local" -> {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    if (uri != null) {
                        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        values["uri"] = uri.toString()
                        apply()
                    }
                }
                val label = storage.s("uri")?.let { u ->
                    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(u)) }.getOrNull()?.replace("primary:", "")?.ifBlank { "/" }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RowBg).clickable { picker.launch(null) }
                        .heightIn(min = 56.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HaIcon("mdi:folder-outline", size = 22.dp, tint = Dash.colors.accent)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(label ?: stringResource(R.string.det_local_pick), style = TextStyle(fontSize = 15.sp, color = Color.White))
                        if (label != null) Text(stringResource(R.string.det_local_change), style = TextStyle(fontSize = 12.sp, color = Muted))
                    }
                    HaIcon("mdi:chevron-right", size = 22.dp, tint = Muted)
                }
                Help(stringResource(R.string.det_local_help))
            }
            "entities" -> Help(stringResource(R.string.det_entities_help))
        }
        if (type != "local" && type != "entities") {
            DetButton(stringResource(R.string.web_apply), Modifier.fillMaxWidth(), enabled = changed) { apply() }
        }
    }
    browse?.let { r -> FolderBrowser(r.title, r.root, r.children, onDismiss = { browse = null }) { picked -> browse = null; r.onPick(picked) } }
}

/** Analyse immédiate avec les réglages enregistrés : fichiers lus, captures récentes, dernière capture. */
@Composable
private fun TestButton(d: DetData) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var result by remember(d.cfg) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var running by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetButton(stringResource(if (running) R.string.det_testing else R.string.det_test), Modifier.fillMaxWidth(), enabled = !running) {
            running = true
            scope.launch {
                result = runCatching { DetectionScanner.scan(d.cfg) }.fold(
                    onSuccess = { r ->
                        val parts = buildList {
                            if (d.cfg.type != "entities") add(ctx.resources.getQuantityString(R.plurals.det_test_files, r.files, r.files))
                            add(ctx.resources.getQuantityString(R.plurals.det_test_recent, r.detections.size, r.detections.size, durationText(ctx, d.cfg.minutes)))
                        }
                        val detail = r.perCamera.entries.filter { it.key.isNotBlank() }.joinToString { "${it.key} ${it.value}" }
                        val latest = r.latest?.let { ctx.getString(R.string.det_test_latest, it.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))) }
                            ?: if (d.cfg.type != "entities") ctx.getString(R.string.det_test_no_capture) else null
                        (parts.joinToString(" · ") + (if (detail.isNotEmpty()) " ($detail)" else "") + (latest?.let { "\n$it" } ?: "")) to false
                    },
                    onFailure = { e -> ctx.getString(R.string.det_test_error, e.message ?: e.javaClass.simpleName) to true },
                )
                running = false
            }
        }
        result?.let { (text, error) ->
            Text(text, style = TextStyle(fontSize = 13.sp, color = if (error) Danger else Color(0xFF4CD964), lineHeight = 18.sp))
        }
    }
}

private fun durationText(ctx: android.content.Context, min: Int): String =
    if (min < 60) ctx.getString(R.string.det_duration_min, min)
    else if (min % 60 == 0) ctx.getString(R.string.det_duration_h, min / 60)
    else ctx.getString(R.string.det_duration_hmin, min / 60, min % 60)

/** Titre de la fenêtre d'une caméra des détections : son nom. */
@Composable
internal fun detectionCameraTitle(key: String, index: Int): String {
    val d = rememberDet(key) ?: return ""
    return d.cameras.getOrNull(index)?.s("name")?.ifBlank { null } ?: stringResource(R.string.det_camera_unnamed)
}

/**
 * Caméra n° [index] de la zone des détections : nom affiché, puis reconnaissance de ses fichiers (début, fin ou
 * partie du nom, ou tous les fichiers) et sous-dossier ; en mode entités, l'entité de sa capture.
 */
@Composable
fun DetectionCameraFields(key: String, index: Int, onDismiss: () -> Unit) {
    val d = rememberDet(key) ?: return
    val cam = d.cameras.getOrNull(index) ?: return
    val entities = d.cfg.type == "entities"
    var pick by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf<BrowseRequest?>(null) }
    browse?.let { r -> FolderBrowser(r.title, r.root, r.children, onDismiss = { browse = null }) { picked -> browse = null; r.onPick(picked) } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DebouncedField(cam.s("name").orEmpty(), stringResource(R.string.det_camera_name), null) { d.setCamera(index, cam.with("name", it)) }
        if (entities) {
            DetSection(stringResource(R.string.det_camera_entity))
            Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val id = cam.s("entity")
            if (id != null) EntityCard(id, onClick = { pick = true }) else AddButton(stringResource(R.string.det_camera_pick_entity)) { pick = true }
            Help(stringResource(R.string.det_camera_entity_help))
            }
        } else {
            DetSection(stringResource(R.string.det_camera_files))
            Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val mode = cam.s("mode") ?: "prefix"
            Chips(
                listOf(
                    "prefix" to stringResource(R.string.det_match_prefix),
                    "suffix" to stringResource(R.string.det_match_suffix),
                    "contains" to stringResource(R.string.det_match_contains),
                    "all" to stringResource(R.string.det_match_all),
                ),
                mode,
            ) { d.setCamera(index, cam.with("mode", it)) }
            if (mode != "all") {
                DebouncedField(cam.s("match").orEmpty(), stringResource(R.string.det_match_text), "Argus 1") { d.setCamera(index, cam.with("match", it)) }
            }
            val storage = remember(d.cfg.storage) { fr.arubinu.hadash.detections.DetectionStorage.of(d.cfg.storage) }
            val browseTitle = stringResource(R.string.browse_title)
            val rootLabel = stringResource(R.string.browse_storage_root)
            DebouncedField(
                cam.s("folder").orEmpty(), stringResource(R.string.det_camera_folder), stringResource(R.string.det_camera_folder_hint),
                onBrowse = storage?.let { s -> { browse = BrowseRequest(browseTitle, FolderNode(rootLabel, ""), storageChildren(s)) { d.setCamera(index, cam.with("folder", it)) } } },
            ) {
                d.setCamera(index, cam.with("folder", it))
            }
            Help(stringResource(R.string.det_match_help))
            }
        }
        DetButton(stringResource(R.string.det_camera_delete), Modifier.fillMaxWidth().padding(top = 8.dp), color = Danger) {
            d.setCameras(d.cameras.filterIndexed { j, _ -> j != index })
            onDismiss()
        }
    }
    if (pick) EntityPicker("image", onDismiss = { pick = false }) { id ->
        pick = false
        d.setCamera(index, cam.with("entity", id))
    }
}

// --- Éléments communs ------------------------------------------------------------------------------------

@Composable
private fun DetSection(text: String) {
    Text(text, Modifier.padding(top = 10.dp, bottom = 2.dp), style = TextStyle(fontSize = 13.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold))
}

@Composable
private fun Label(text: String) = Text(text, style = TextStyle(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium))

@Composable
private fun Help(text: String) = Text(text, style = TextStyle(fontSize = 12.sp, color = Muted, lineHeight = 16.sp))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, enabled: (String) -> Boolean = { true }, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val sel = id == selected
            val on = enabled(id)
            val shape = RoundedCornerShape(16.dp)
            Text(
                label,
                Modifier.clip(shape).background(if (sel) Dash.colors.accent else Color.Transparent, shape)
                    .border(1.dp, if (sel) Dash.colors.accent else Color.White.copy(alpha = if (on) 0.25f else 0.1f), shape)
                    .then(if (on) Modifier.clickable { onSelect(id) } else Modifier)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = TextStyle(fontSize = 14.sp, color = if (sel) Color.Black else Color.White.copy(alpha = if (on) 1f else 0.35f)),
            )
        }
    }
}

@Composable
private fun TextBox(
    value: String, onChange: (String) -> Unit, label: String, hint: String?, modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false, number: Boolean = false, onBrowse: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value, onChange, modifier, label = { Text(label) }, singleLine = true,
        // Bouton d'exploration : choisir le dossier au lieu de l'écrire.
        trailingIcon = onBrowse?.let { browse -> {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = browse), contentAlignment = Alignment.Center) {
                HaIcon("mdi:folder-search-outline", size = 22.dp, tint = Dash.colors.accent)
            }
        } },
        placeholder = hint?.let { { Text(it, color = Color.White.copy(alpha = 0.3f)) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { secret -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent),
    )
}

/** Explorateur de dossiers demandé par un champ. */
private class BrowseRequest(val title: String, val root: FolderNode, val children: suspend (String) -> List<FolderNode>, val onPick: (String) -> Unit)

/** Sous-dossiers d'un stockage, identifiés par leur chemin relatif. */
private fun storageChildren(storage: fr.arubinu.hadash.detections.DetectionStorage): suspend (String) -> List<FolderNode> = { path ->
    storage.folders(path).map { FolderNode(it, fr.arubinu.hadash.detections.DetectionStorage.join(path, it)) }
}

/** Champ enregistré un instant après la dernière frappe. */
@Composable
private fun DebouncedField(value: String, label: String, hint: String?, modifier: Modifier = Modifier.fillMaxWidth(), onBrowse: (() -> Unit)? = null, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(700)
        onSave(text)
    }
    TextBox(text, { text = it }, label, hint, modifier, onBrowse = onBrowse)
}

@Composable
private fun DetButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Dash.colors.accent, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val c = if (enabled) color else color.copy(alpha = 0.35f)
    Text(
        text, modifier.clip(shape).border(1.dp, c, shape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        style = TextStyle(fontSize = 14.sp, color = c, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
    )
}

@Composable
private fun RowIcon(icon: String, enabled: Boolean, tint: Color = Muted, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { HaIcon(icon, size = 22.dp, tint = if (enabled) tint else Color.White.copy(alpha = 0.15f)) }
}
