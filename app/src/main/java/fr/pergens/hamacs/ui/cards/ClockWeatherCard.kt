package fr.pergens.hamacs.ui.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.FirstBaseline
import fr.pergens.hamacs.config.b
import fr.pergens.hamacs.config.d
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.Card
import fr.pergens.hamacs.config.i
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import androidx.compose.runtime.collectAsState
import fr.pergens.hamacs.ha.Forecast
import fr.pergens.hamacs.ha.subscribeForecast
import fr.pergens.hamacs.ui.LocalDashState
import fr.pergens.hamacs.ui.components.editOutline
import androidx.compose.foundation.clickable
import fr.pergens.hamacs.ui.components.DashCard
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.components.SmilSvgIcon
import fr.pergens.hamacs.ui.entity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val FR: Locale get() = Locale.getDefault()

private val CONDITIONS = mapOf(
    "clear-night" to R.string.cond_clear_night, "cloudy" to R.string.cond_cloudy, "fog" to R.string.cond_fog,
    "hail" to R.string.cond_hail, "lightning" to R.string.cond_lightning, "lightning-rainy" to R.string.cond_lightning_rainy,
    "partlycloudy" to R.string.cond_partlycloudy, "pouring" to R.string.cond_pouring, "rainy" to R.string.cond_rainy,
    "snowy" to R.string.cond_snowy, "snowy-rainy" to R.string.cond_snowy_rainy, "sunny" to R.string.cond_sunny,
    "windy" to R.string.cond_windy, "windy-variant" to R.string.cond_windy_variant, "exceptional" to R.string.cond_exceptional,
)

@Composable
fun conditionLabel(condition: String?): String =
    CONDITIONS[condition]?.let { stringResource(it) } ?: condition.orEmpty()

/**
 * Condition HA → icône Meteocons « line » de clock-weather-card (même correspondance que
 * mapWeatherStateToIconFileName : `clear-night` affiche le soleil en journée, comme la carte).
 */
fun weatherIconName(condition: String?, night: Boolean): String {
    val s = if (night) "night" else "day"
    return when (val c = condition?.lowercase()) {
        "clear-night", "clear", "sunny" -> "clear-$s"
        "partlycloudy" -> "partly-cloudy-$s"
        "cloudy" -> "cloudy"
        "fog" -> "fog-$s"
        "hail" -> "hail"
        "lightning" -> "thunderstorms-$s"
        "lightning-rainy" -> "thunderstorms-$s-rain"
        "pouring" -> "extreme-rain"
        "rainy" -> "rain"
        "snowy" -> "snow"
        "snowy-rainy" -> "sleet"
        "windy", "windy-variant" -> "wind"
        "windy-exceptional" -> "wind-alert"
        "exceptional" -> "weather-alert"
        "sunrise", "sunset" -> c
        else -> "not-available"
    }
}

/** Icône météo ; [animated] : version animée (SMIL) des assets `weather/animated/`. */
@Composable
fun WeatherIcon(condition: String?, night: Boolean, modifier: Modifier, animated: Boolean = false) {
    val name = weatherIconName(condition, night)
    SmilSvgIcon(if (animated) "weather/animated/$name.svg" else "weather/$name.svg", modifier, animated)
}

/** Couleur de barre de température (dégradé froid → chaud de clock-weather-card). */
private val TEMP_STOPS = listOf(
    -10.0 to Color(120, 150, 240), 0.0 to Color(155, 200, 215), 10.0 to Color(120, 210, 170),
    15.0 to Color(150, 225, 140), 20.0 to Color(240, 215, 90), 25.0 to Color(250, 160, 80), 30.0 to Color(240, 90, 80),
)

private fun tempColor(t: Double): Color {
    if (t <= TEMP_STOPS.first().first) return TEMP_STOPS.first().second
    for (i in 1 until TEMP_STOPS.size) {
        val (t1, c1) = TEMP_STOPS[i]
        val (t0, c0) = TEMP_STOPS[i - 1]
        if (t <= t1) return lerp(c0, c1, ((t - t0) / (t1 - t0)).toFloat())
    }
    return TEMP_STOPS.last().second
}

@Composable
fun rememberNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs - System.currentTimeMillis() % periodMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun rememberForecast(entityId: String, type: String): List<Forecast> {
    val ha = LocalDashState.current.ha
    var data by remember(entityId, type) { mutableStateOf<List<Forecast>>(emptyList()) }
    DisposableEffect(entityId, type) {
        val unsub = ha.subscribeForecast(entityId, type) { data = it }
        onDispose { unsub() }
    }
    return data
}

/** Version native de clock-weather-card : grande heure, prévisions horaires et barres journalières. */
@Composable
fun ClockWeatherCard(card: Card, modifier: Modifier) {
    val id = card.s("entity") ?: "weather.forecast_maison"
    val weather = entity(id)
    val sun = entity("sun.sun")
    val hourly = rememberForecast(id, "hourly")
    val daily = rememberForecast(id, "daily")
    val now = rememberNow(1000)
    val zone = ZoneId.systemDefault()
    val night = sun?.state == "below_horizon"
    val temp = weather?.attrDouble("temperature")

    // Pas de marge intérieure en bas : la carte s'arrête au bas de la dernière barre de température.
    val state = LocalDashState.current
    // Mode édition : contour pointillé, un toucher ouvre le choix de l'entité météo.
    val edit = if (state.editMode) Modifier
        .then(Modifier.editOutline(true, androidx.compose.foundation.shape.RoundedCornerShape(fr.pergens.hamacs.ui.theme.Dash.shapes.cardRadius)))
        .clickable { state.editTarget = fr.pergens.hamacs.ui.EditTarget.Block(id) }
    else Modifier
    DashCard(modifier.fillMaxWidth().then(edit)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(weather?.state, night, Modifier.size(card.i("icon_size", 110).dp), animated = card.b("animated_icons", true))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HaIcon("mdi:thermometer", size = 16.dp)
                        Text(
                            "${temp?.roundToInt() ?: "—"}°C",
                            Modifier.padding(start = 2.dp, end = 12.dp),
                            style = TextStyle(fontSize = 16.sp, color = Color.White),
                        )
                        Text(
                            conditionLabel(weather?.state),
                            style = TextStyle(fontSize = 16.sp, color = Color.White),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(now).atZone(zone)),
                        style = TextStyle(fontSize = 64.sp, color = Color.White, lineHeight = 66.sp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HaIcon("mdi:calendar", size = 16.dp)
                        Text(
                            DateTimeFormatter.ofPattern("d MMMM yyyy", FR).format(Instant.ofEpochMilli(now).atZone(zone)),
                            Modifier.padding(start = 4.dp),
                            style = TextStyle(fontSize = 16.sp, color = Color.White),
                        )
                    }
                }
            }
            Divider()
            HourlyRow(hourly, sun?.attrString("next_rising"), sun?.attrString("next_setting"), zone, card.b("forecast_animated_icons"))
            Divider()
            DailyBars(daily.take(card.i("days", 5)), temp, zone, card.b("forecast_animated_icons"))
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Color(255, 255, 255, 30)))
}

private sealed interface HourItem {
    val time: Long
    data class F(val f: Forecast) : HourItem { override val time get() = f.time }
    data class Sun(override val time: Long, val rising: Boolean) : HourItem
}

@Composable
private fun HourlyRow(hourly: List<Forecast>, nextRising: String?, nextSetting: String?, zone: ZoneId, animated: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val count = (maxWidth.value / 52f).toInt().coerceAtLeast(3)
        val items = remember(hourly, nextRising, nextSetting) {
            val sunEvents = listOfNotNull(
                nextRising?.let { runCatching { HourItem.Sun(Instant.parse(it.replace("+00:00", "Z")).toEpochMilli(), true) }.getOrNull() },
                nextSetting?.let { runCatching { HourItem.Sun(Instant.parse(it.replace("+00:00", "Z")).toEpochMilli(), false) }.getOrNull() },
            )
            (hourly.map { HourItem.F(it) } + sunEvents).sortedBy { it.time }
        }
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            items.take(count).forEachIndexed { idx, item ->
                Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val label = if (idx == 0 && item is HourItem.F) stringResource(R.string.weather_now) else fmt.format(Instant.ofEpochMilli(item.time).atZone(zone))
                    Text(label, style = TextStyle(fontSize = 12.sp, color = Color(255, 255, 255, 180)))
                    when (item) {
                        is HourItem.F -> {
                            val h = Instant.ofEpochMilli(item.time).atZone(zone).hour
                            WeatherIcon(item.f.condition, h < 7 || h >= 19, Modifier.size(38.dp), animated)
                            Text(
                                "${item.f.temperature?.roundToInt() ?: "—"}°C",
                                style = TextStyle(fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold),
                            )
                        }
                        is HourItem.Sun -> {
                            WeatherIcon(if (item.rising) "sunrise" else "sunset", false, Modifier.size(38.dp), animated)
                            HaIcon(if (item.rising) "mdi:arrow-up" else "mdi:arrow-down", size = 14.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DailyBars(daily: List<Forecast>, current: Double?, zone: ZoneId, animated: Boolean) {
    if (daily.isEmpty()) return
    val lows = daily.mapNotNull { it.templow ?: it.temperature }
    val highs = daily.mapNotNull { it.temperature }
    val min = (lows.minOrNull() ?: 0.0)
    val max = (highs.maxOrNull() ?: 1.0).coerceAtLeast(min + 1)
    val today = LocalDate.now(zone)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        daily.forEachIndexed { index, f ->
            val date = Instant.ofEpochMilli(f.time).atZone(zone).toLocalDate()
            val label = if (date == today) stringResource(R.string.weather_today) else DateTimeFormatter.ofPattern("EEE", FR).format(date)
            val lo = f.templow ?: f.temperature ?: 0.0
            val hi = f.temperature ?: lo
            // Dernière ligne : sa hauteur s'arrête au dernier pixel de la barre (centrée dans la ligne),
            // pour aligner le bas de la barre sur le bas des blocs voisins.
            val trim = if (index == daily.lastIndex) Modifier.layout { m, c ->
                val p = m.measure(c)
                val cut = ((p.height - BAR_HEIGHT.roundToPx()) / 2).coerceAtLeast(0)
                layout(p.width, p.height - cut) { p.place(0, 0) }
            } else Modifier
            Row(trim, verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(40.dp), style = TextStyle(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold))
                WeatherIcon(f.condition, false, Modifier.size(24.dp), animated)
                Text(
                    "${lo.roundToInt()}°C", Modifier.width(44.dp).padding(start = 4.dp),
                    style = TextStyle(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold),
                )
                TempBar(lo, hi, min, max, if (date == today) current else null, Modifier.weight(1f).height(22.dp))
                Text(
                    "${hi.roundToInt()}°C", Modifier.width(48.dp),
                    style = TextStyle(fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
                )
            }
        }
    }
}

/** Épaisseur des barres de température journalières. */
private val BAR_HEIGHT = 12.dp

@Composable
private fun TempBar(lo: Double, hi: Double, min: Double, max: Double, current: Double?, modifier: Modifier) {
    Canvas(modifier.padding(horizontal = 8.dp)) {
        val h = BAR_HEIGHT.toPx()
        val y = (size.height - h) / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color(255, 255, 255, 20), Offset(0f, y), Size(size.width, h), r)
        val x0 = ((lo - min) / (max - min)).toFloat() * size.width
        val x1 = ((hi - min) / (max - min)).toFloat() * size.width
        drawRoundRect(
            Brush.horizontalGradient(listOf(tempColor(lo), tempColor(hi)), startX = x0, endX = x1),
            Offset(x0, y), Size((x1 - x0).coerceAtLeast(h), h), r,
        )
        current?.let {
            val cx = ((it.coerceIn(min, max) - min) / (max - min)).toFloat() * size.width
            drawCircle(Color.White, radius = size.height / 2, center = Offset(cx, size.height / 2))
            drawCircle(Color(30, 30, 30), radius = size.height / 2 - 2.dp.toPx(), center = Offset(cx, size.height / 2))
        }
    }
}

/**
 * Horloge de l'accueil : HH:mm en grand ; à droite, les secondes alignées sur le HAUT des chiffres et la
 * date sur leur ligne de base. Les deux occupent presque toute la hauteur des chiffres.
 */
@Composable
fun ClockCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val app = fr.pergens.hamacs.App.instance
    val blocks by app.config.blocks.collectAsState()
    val dash by app.config.dashboard.collectAsState()
    val opts = blocks[CLOCK_KEY]
    // Météo sous l'heure : activée par défaut, entité du bloc sinon celle de M.A.C.S.
    val showWeather = opts?.b("weather") ?: card.b("weather", true)
    val weatherId = opts?.s("weather_entity") ?: card.s("weather_entity")
        ?: blocks[fr.pergens.hamacs.macs.MacsOptions.KEY]?.o("config")?.s("weather_entity") ?: dash.o("macs")?.s("weather_entity")
    val edit = if (state.editMode) Modifier
        .editOutline(true, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .clickable { state.editTarget = fr.pergens.hamacs.ui.EditTarget.Clock() }
    else Modifier
    Column(modifier.then(edit)) {
        ClockFace(card, Modifier)
        if (showWeather && weatherId != null) ClockWeatherLine(weatherId, card.d("size", 64.0).toFloat(), Modifier.padding(top = 10.dp))
    }
}

/** Clé des réglages du bloc horloge (mode édition). */
const val CLOCK_KEY = "clock"

/** Ligne météo succincte : icône, température, ciel, mini / maxi du jour. */
@Composable
private fun ClockWeatherLine(id: String, hourSize: Float, modifier: Modifier) {
    val weather = entity(id) ?: return
    val night = entity("sun.sun")?.state == "below_horizon"
    val daily = rememberForecast(id, "daily")
    val zone = ZoneId.systemDefault()
    val today = daily.firstOrNull { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() == LocalDate.now(zone) }
    val temp = weather.attrDouble("temperature")
    val big = (hourSize * 0.42f).sp
    val small = (hourSize * 0.26f).sp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WeatherIcon(weather.state, night, Modifier.size((hourSize * 0.75f).dp), animated = true)
        Text(
            "${temp?.roundToInt() ?: "—"}°", Modifier.padding(start = 6.dp),
            style = TextStyle(fontSize = big, color = Color.White),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(conditionLabel(weather.state), style = TextStyle(fontSize = small, color = Color.White), maxLines = 1)
            if (today != null) {
                val lo = today.templow?.roundToInt()
                val hi = today.temperature?.roundToInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lo != null) {
                        HaIcon("mdi:arrow-down", size = (hourSize * 0.22f).dp, tint = Color(155, 200, 235))
                        Text("$lo°", Modifier.padding(end = 10.dp), style = TextStyle(fontSize = small, color = Color(255, 255, 255, 170)))
                    }
                    if (hi != null) {
                        HaIcon("mdi:arrow-up", size = (hourSize * 0.22f).dp, tint = Color(250, 175, 95))
                        Text("$hi°", style = TextStyle(fontSize = small, color = Color(255, 255, 255, 170)))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockFace(card: Card, modifier: Modifier) {
    val now = rememberNow(1000)
    val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    val hourSize = card.d("size", 64.0).sp
    // Hauteur des chiffres Roboto ≈ 0,71 × taille de police : date ≈ 44 % et secondes ≈ 37 % de l'heure,
    // le reste formant le petit espace entre les deux.
    val dateSize = hourSize * 0.44f
    val secondsSize = hourSize * 0.37f
    val showSeconds = card.s("seconds") != "false"
    val date = DateTimeFormatter.ofPattern("d MMMM yyyy", FR).format(t)
        .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    Layout(
        content = {
            Text(DateTimeFormatter.ofPattern("HH:mm").format(t), style = TextStyle(fontSize = hourSize, color = Color.White))
            Text(if (showSeconds) DateTimeFormatter.ofPattern("ss").format(t) else "", style = TextStyle(fontSize = secondsSize, color = Color(255, 255, 255, 150)))
            Text(date, style = TextStyle(fontSize = dateSize, color = Color.White))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val hour = measurables[0].measure(loose)
        val gap = (hourSize.toPx() * 0.12f).toInt()
        val seconds = measurables[1].measure(loose)
        val dateP = measurables[2].measure(loose)
        val capRatio = 0.711f
        val hourBaseline = hour[FirstBaseline]
        val hourCapTop = hourBaseline - (hourSize.toPx() * capRatio).toInt()
        // Haut des chiffres des secondes = haut des chiffres de l'heure.
        val secondsY = hourCapTop - (seconds[FirstBaseline] - (secondsSize.toPx() * capRatio).toInt())
        // Ligne de base de la date = ligne de base de l'heure.
        val dateY = hourBaseline - dateP[FirstBaseline]
        val top = minOf(0, secondsY, dateY)
        val width = hour.width + gap + maxOf(seconds.width, dateP.width)
        val height = maxOf(hour.height, dateY + dateP.height) - top
        layout(width, height) {
            hour.place(0, -top)
            seconds.place(hour.width + gap, secondsY - top)
            dateP.place(hour.width + gap, dateY - top)
        }
    }
}

internal fun Modifier.ring(color: Color) = this.border(2.dp, color, CircleShape)
