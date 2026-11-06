package fr.pergens.hamacs.detections

import fr.pergens.hamacs.App
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Caméra de la zone des détections. Fichiers : reconnue par [match] selon [mode] (`prefix`, `suffix`, `contains`,
 * `all`) dans le sous-dossier [folder]. Entités : capture de l'entité [entity] (`image.*`, `camera.*`…).
 */
data class DetectionCamera(val name: String, val match: String, val mode: String, val folder: String, val entity: String?)

/** Détection affichée : capture (et vidéo éventuelle), chargées à la demande. */
class Detection(
    val id: String,
    val camera: String,
    val time: LocalDateTime,
    val image: suspend () -> String?,
    val video: (suspend () -> String?)?,
)

/** Réglages de la zone des détections, lus dans la carte. */
class DetectionConfig(card: JsonObject) {
    val storage: JsonObject = card.o("storage") ?: buildJsonObject { put("type", "ha_media") }
    val type: String = storage.s("type") ?: "ha_media"
    val minutes: Int = card.i("minutes", 20)
    /** `name` (date dans le nom du fichier) ou `modified` (date de modification). */
    val timeSource: String = card.s("time_source") ?: "name"
    /** Format de date du nom de fichier (`yyyyMMddHHmmss`…) ; vide = détection automatique. */
    val timeFormat: String = card.s("time_format").orEmpty()
    val imageExts: Set<String> = exts(card.s("image_ext"), "jpg,jpeg,png,webp")
    val videoExts: Set<String> = exts(card.s("video_ext"), "mp4,mkv,mov,avi,m4v,ts")
    /** `auto` (même nom, sinon la plus proche dans le temps), `name` ou `time`. */
    val pairing: String = card.s("pairing") ?: "auto"
    /** Texte propre aux captures / aux vidéos, ignoré pour les associer par leur nom (ex. `_snap`). */
    val imageAffix: String = card.s("image_affix").orEmpty()
    val videoAffix: String = card.s("video_affix").orEmpty()
    val videoWindow: Long = card.i("video_window_seconds", 60).toLong()
    val timeOffsetHours: Int? = card.i("time_offset_hours")
    val cameras: List<DetectionCamera> = when (val c = card["cameras"]) {
        is JsonArray -> c.mapNotNull { it as? JsonObject }.map { o ->
            DetectionCamera(o.s("name").orEmpty(), o.s("match").orEmpty(), o.s("mode") ?: "prefix", o.s("folder").orEmpty(), o.s("entity"))
        }
        else -> emptyList()
    }

    companion object {
        private fun exts(v: String?, default: String) =
            (v?.takeIf { it.isNotBlank() } ?: default).split(',', ' ', ';').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.toSet()

        /** Caméras au format liste (pour l'éditeur), quel que soit le format d'origine. */
        fun camerasJson(card: JsonObject): List<JsonObject> = DetectionConfig(card).cameras.map { c ->
            buildJsonObject {
                put("name", c.name)
                if (c.entity != null) put("entity", c.entity) else {
                    put("match", c.match); put("mode", c.mode)
                    if (c.folder.isNotBlank()) put("folder", c.folder)
                }
            }
        }
    }
}

object DetectionScanner {
    /** Date/heure dans un nom : 2025-11-04_20-30-12, 20251104203012, 20251104T203012… (la dernière trouvée). */
    private val AUTO_DATE = Regex("""(?<!\d)(\d{4})[-_.]?(\d{2})[-_.]?(\d{2})[-_T .]?(\d{2})[-_:.hH]?(\d{2})[-_:.mM]?(\d{2})(?!\d)""")
    /** Horodatage Unix (secondes, éventuellement suivies des millisecondes). */
    private val EPOCH = Regex("""(?<!\d)(1\d{9})(\d{3})?(?!\d)""")

    /** Date tirée du nom [stem], automatiquement ou selon [format] (motif Java : yyyy, MM, dd, HH, mm, ss). */
    fun timeFromName(stem: String, format: String): LocalDateTime? {
        if (format.isNotBlank()) {
            val (regex, formatter) = compiled(format) ?: return null
            val m = regex.findAll(stem).lastOrNull() ?: return null
            return runCatching { LocalDateTime.parse(m.value, formatter) }.getOrNull()
        }
        AUTO_DATE.findAll(stem).toList().asReversed().forEach { m ->
            val g = m.groupValues.drop(1).map { it.toInt() }
            runCatching { return LocalDateTime.of(g[0], g[1], g[2], g[3], g[4], g[5]) }
        }
        EPOCH.findAll(stem).lastOrNull()?.let { m ->
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(m.groupValues[1].toLong()), ZoneId.systemDefault())
        }
        return null
    }

    private val formats = HashMap<String, Pair<Regex, DateTimeFormatter>?>()

    /** Motif de date → expression régulière (chiffres de la bonne longueur) et analyseur tolérant. */
    @Synchronized
    private fun compiled(format: String): Pair<Regex, DateTimeFormatter>? = formats.getOrPut(format) {
        runCatching {
            val re = StringBuilder()
            var i = 0
            while (i < format.length) {
                val c = format[i]
                when {
                    c == '\'' -> {
                        val end = format.indexOf('\'', i + 1).takeIf { it > i } ?: format.length
                        re.append(Regex.escape(format.substring(i + 1, end)))
                        i = end + 1
                        continue
                    }
                    c.isLetter() -> {
                        var j = i
                        while (j < format.length && format[j] == c) j++
                        re.append("\\d{${j - i}}")
                        i = j
                        continue
                    }
                    else -> re.append(Regex.escape(c.toString()))
                }
                i++
            }
            val f = DateTimeFormatterBuilder().appendPattern(format)
                .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                .toFormatter()
            Regex("(?<!\\d)$re(?!\\d)") to f
        }.getOrNull()
    }

    private fun matches(stem: String, cam: DetectionCamera): Boolean {
        val m = cam.match.trim()
        if (cam.mode == "all" || m.isEmpty()) return true
        // Limite de mot quand le texte finit (ou commence) par une lettre/un chiffre : « Argus 1 » ≠ « Argus 10 ».
        return when (cam.mode) {
            "suffix" -> stem.endsWith(m, true) && (!m.first().isLetterOrDigit() || stem.length == m.length || !stem[stem.length - m.length - 1].isLetterOrDigit())
            "contains" -> stem.contains(m, true)
            else -> stem.startsWith(m, true) && (!m.last().isLetterOrDigit() || stem.length == m.length || !stem[m.length].isLetterOrDigit())
        }
    }

    /** Résultat d'une analyse : détections, et nombre de fichiers lus (pour le bouton « Tester »). */
    class Result(val detections: List<Detection>, val files: Int, val perCamera: Map<String, Int>, val captures: Int = detections.size, val latest: LocalDateTime? = detections.firstOrNull()?.time)

    suspend fun scan(cfg: DetectionConfig, windowMinutes: Int = cfg.minutes): Result =
        if (cfg.type == "entities") scanEntities(cfg, windowMinutes) else scanFiles(cfg, windowMinutes)

    /** Entités de Home Assistant : la dernière capture de chaque caméra, si elle est récente. */
    private fun scanEntities(cfg: DetectionConfig, windowMinutes: Int): Result {
        val ha = App.instance.ha
        val now = LocalDateTime.now()
        val limit = now.minusMinutes(windowMinutes.toLong())
        val out = cfg.cameras.mapNotNull { cam ->
            val id = cam.entity ?: return@mapNotNull null
            val st = ha.states.value[id] ?: return@mapNotNull null
            // `image.*` : l'état est la date de la dernière image ; sinon la dernière mise à jour de l'entité.
            val t = runCatching { java.time.OffsetDateTime.parse(st.state).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
                ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(st.lastUpdated), ZoneId.systemDefault())
            if (t.isBefore(limit)) return@mapNotNull null
            val picture = st.attrString("entity_picture") ?: return@mapNotNull null
            Detection("$id@$t", cam.name.ifBlank { st.name }, t, { ha.resolveUrl(picture) }, null)
        }.sortedByDescending { it.time }
        return Result(out, out.size, out.groupingBy { it.camera }.eachCount())
    }

    private suspend fun scanFiles(cfg: DetectionConfig, windowMinutes: Int): Result {
        val storage = DetectionStorage.of(cfg.storage) ?: error(App.instance.getString(fr.pergens.hamacs.R.string.det_err_storage))
        val cams = cfg.cameras.ifEmpty { listOf(DetectionCamera("", "", "all", "", null)) }
        // Un seul parcours par dossier, même s'il contient plusieurs caméras.
        val listing = cams.map { it.folder }.distinct().associateWith { storage.list(it) }
        DetectionStorage.trimCache(App.instance)
        return withContext(Dispatchers.Default) {
            val now = LocalDateTime.now()
            val byModified = cfg.timeSource == "modified" && storage.hasModified
            fun timeOf(f: StoredFile): LocalDateTime? =
                if (byModified) f.modified?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()) }
                else timeFromName(f.stem, cfg.timeFormat)

            class F(val cam: DetectionCamera, val file: StoredFile, val time: LocalDateTime)
            val files = cams.flatMap { cam ->
                listing[cam.folder].orEmpty().filter { matches(it.stem, cam) }.mapNotNull { f -> timeOf(f)?.let { F(cam, f, it) } }
            }
            val images = files.filter { it.file.ext in cfg.imageExts }
            val videos = files.filter { it.file.ext in cfg.videoExts }
            // Horloge des caméras décalée (heure d'été non appliquée…) : si des fichiers sont « dans le futur »,
            // toutes les dates sont corrigées du décalage, arrondi à l'heure.
            val skew = cfg.timeOffsetHours ?: if (byModified) 0 else images.maxOfOrNull { it.time }
                ?.takeIf { it.isAfter(now.plusMinutes(2)) }
                ?.let { Math.round(Duration.between(now, it).toMinutes() / 60.0).toInt() } ?: 0
            val limit = now.minusMinutes(windowMinutes.toLong())
            fun key(f: StoredFile, affix: String) = (if (affix.isBlank()) f.stem else f.stem.replace(affix, "", true)).lowercase()
            val videosByName = videos.groupBy { it.cam.folder + "/" + key(it.file, cfg.videoAffix) }
            val videosByCam = videos.groupBy { it.cam }
            val out = images.mapNotNull { img ->
                val t = img.time.minusHours(skew.toLong())
                if (t.isBefore(limit)) return@mapNotNull null
                val byName = if (cfg.pairing != "time") videosByName[img.cam.folder + "/" + key(img.file, cfg.imageAffix)]?.firstOrNull() else null
                // Vidéo de la même caméra démarrée au plus `video_window_seconds` avant la capture (ou juste après).
                val byTime = if (byName == null && cfg.pairing != "name") videosByCam[img.cam].orEmpty()
                    .filter { v -> Duration.between(v.time, img.time).seconds in -5..cfg.videoWindow }
                    .minByOrNull { v -> kotlin.math.abs(Duration.between(v.time, img.time).seconds) } else null
                val video = byName ?: byTime
                Detection(
                    "${img.file.folder}/${img.file.name}", img.cam.name, t,
                    { storage.open(img.file) },
                    video?.let { v -> { storage.open(v.file) } },
                )
            }.sortedByDescending { it.time }
            Result(out, listing.values.sumOf { it.size }, out.groupingBy { it.camera }.eachCount(), images.size, images.maxOfOrNull { it.time }?.minusHours(skew.toLong()))
        }
    }
}
