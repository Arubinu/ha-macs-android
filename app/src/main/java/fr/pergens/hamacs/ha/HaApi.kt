package fr.pergens.hamacs.ha

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class Point(val t: Long, val v: Double)
data class StatPoint(val t: Long, val mean: Double?, val min: Double?, val max: Double?)
data class StateSpan(val start: Long, val state: String)

data class Forecast(
    val time: Long,
    val condition: String?,
    val temperature: Double?,
    val templow: Double?,
    val precipitation: Double?,
    val precipitationProbability: Double? = null,
)

data class CalendarEvent(
    val summary: String,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val allDay: Boolean,
    val calendar: String,
)

data class MediaItem(
    val title: String,
    val contentId: String,
    val canExpand: Boolean,
    val mediaClass: String?,
    val thumbnail: String?,
    val children: List<MediaItem>,
)

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

/** Horodatage HA : ISO 8601, ou nombre en secondes (historique) ou millisecondes (statistiques). */
private fun parseTs(el: kotlinx.serialization.json.JsonElement?): Long? {
    val p = el as? JsonPrimitive ?: return null
    p.doubleOrNull?.let { return if (it > 1e11) it.toLong() else (it * 1000).toLong() }
    return p.contentOrNull?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
}

/** Historique numérique (history/history_during_period). */
suspend fun HaClient.history(entityIds: List<String>, hours: Int): Map<String, List<StateSpan>> {
    val now = System.currentTimeMillis()
    val res = call(buildJsonObject {
        put("type", "history/history_during_period")
        put("start_time", iso(now - hours * 3_600_000L))
        put("end_time", iso(now))
        putJsonArray("entity_ids") { entityIds.forEach { add(JsonPrimitive(it)) } }
        put("minimal_response", true)
        put("no_attributes", true)
        put("significant_changes_only", false)
    }, timeoutMs = 30_000).jsonObject
    // Conversion hors du thread principal : l'historique d'un capteur rapide compte des milliers de lignes.
    return withContext(Dispatchers.Default) { res.mapValues { (_, arr) ->
        arr.jsonArray.mapNotNull { row ->
            val o = row.jsonObject
            val t = parseTs(o["lu"]) ?: parseTs(o["lc"]) ?: return@mapNotNull null
            StateSpan(t, o["s"]?.jsonPrimitive?.contentOrNull ?: "")
        }
    } }
}

/** Statistiques long terme (recorder/statistics_during_period). */
suspend fun HaClient.statistics(entityId: String, hours: Int, period: String = "5minute"): List<StatPoint> {
    val now = System.currentTimeMillis()
    val res = call(buildJsonObject {
        put("type", "recorder/statistics_during_period")
        put("start_time", iso(now - hours * 3_600_000L))
        put("end_time", iso(now))
        putJsonArray("statistic_ids") { add(JsonPrimitive(entityId)) }
        put("period", period)
        putJsonArray("types") { listOf("mean", "min", "max").forEach { add(JsonPrimitive(it)) } }
    }, timeoutMs = 30_000).jsonObject
    val rows = res[entityId] as? JsonArray ?: return emptyList()
    return withContext(Dispatchers.Default) { rows.mapNotNull { r ->
        val o = r.jsonObject
        val t = parseTs(o["start"]) ?: return@mapNotNull null
        StatPoint(
            t,
            o["mean"]?.jsonPrimitive?.doubleOrNull,
            o["min"]?.jsonPrimitive?.doubleOrNull,
            o["max"]?.jsonPrimitive?.doubleOrNull,
        )
    } }
}

/** Prévisions météo (abonnement, mis à jour par HA). */
fun HaClient.subscribeForecast(entityId: String, type: String, onUpdate: (List<Forecast>) -> Unit): () -> Unit =
    subscribe(buildJsonObject {
        put("type", "weather/subscribe_forecast")
        put("entity_id", entityId)
        put("forecast_type", type)
    }) { ev ->
        val list = (ev["forecast"] as? JsonArray)?.mapNotNull { f ->
            val o = f.jsonObject
            Forecast(
                time = parseTs(o["datetime"]) ?: return@mapNotNull null,
                condition = o["condition"]?.jsonPrimitive?.contentOrNull,
                temperature = o["temperature"]?.jsonPrimitive?.doubleOrNull,
                templow = o["templow"]?.jsonPrimitive?.doubleOrNull,
                precipitation = o["precipitation"]?.jsonPrimitive?.doubleOrNull,
                precipitationProbability = o["precipitation_probability"]?.jsonPrimitive?.doubleOrNull,
            )
        } ?: emptyList()
        onUpdate(list)
    }

/** URL de flux HLS d'une caméra. */
suspend fun HaClient.cameraStreamUrl(entityId: String): String? {
    val res = call(buildJsonObject {
        put("type", "camera/stream")
        put("entity_id", entityId)
        put("format", "hls")
    }, timeoutMs = 20_000).jsonObject
    return resolveUrl(res["url"]?.jsonPrimitive?.contentOrNull)
}

suspend fun HaClient.browseMedia(contentId: String): MediaItem {
    val res = call(buildJsonObject {
        put("type", "media_source/browse_media")
        put("media_content_id", contentId)
    }, timeoutMs = 20_000).jsonObject
    return parseMedia(res)
}

private fun parseMedia(o: JsonObject): MediaItem = MediaItem(
    title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
    contentId = o["media_content_id"]?.jsonPrimitive?.contentOrNull ?: "",
    canExpand = o["can_expand"]?.jsonPrimitive?.contentOrNull == "true",
    mediaClass = o["media_class"]?.jsonPrimitive?.contentOrNull,
    thumbnail = o["thumbnail"]?.jsonPrimitive?.contentOrNull,
    children = (o["children"] as? JsonArray)?.map { parseMedia(it.jsonObject) } ?: emptyList(),
)

suspend fun HaClient.resolveMedia(contentId: String): String? {
    val res = call(buildJsonObject {
        put("type", "media_source/resolve_media")
        put("media_content_id", contentId)
    }).jsonObject
    return resolveUrl(res["url"]?.jsonPrimitive?.contentOrNull)
}

/** Événements de calendrier via l'API REST. */
suspend fun HaClient.calendarEvents(http: OkHttpClient, entityIds: List<String>, days: Int): List<CalendarEvent> =
    withContext(Dispatchers.IO) {
        val json = Json { ignoreUnknownKeys = true }
        val start = ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0)
        val end = start.plusDays(days.toLong())
        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        entityIds.flatMap { id ->
            runCatching {
                // HttpUrl encode le « + » du fuseau horaire (sinon interprété comme une espace par HA).
                val url = "$baseUrl/api/calendars/$id".toHttpUrl().newBuilder()
                    .addQueryParameter("start", start.format(fmt))
                    .addQueryParameter("end", end.format(fmt))
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use emptyList()
                    json.parseToJsonElement(body).jsonArray.mapNotNull { ev ->
                        val o = ev.jsonObject
                        val s = o["start"]?.jsonObject ?: return@mapNotNull null
                        val e = o["end"]?.jsonObject ?: return@mapNotNull null
                        val allDay = s["date"] != null
                        fun parse(x: JsonObject): ZonedDateTime =
                            x["dateTime"]?.jsonPrimitive?.contentOrNull?.let { ZonedDateTime.parse(it) }
                                ?: java.time.LocalDate.parse(x["date"]!!.jsonPrimitive.content).atStartOfDay(start.zone)
                        CalendarEvent(
                            summary = o["summary"]?.jsonPrimitive?.contentOrNull ?: "",
                            start = parse(s),
                            end = parse(e),
                            allDay = allDay,
                            calendar = id,
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }.sortedBy { it.start }
    }
