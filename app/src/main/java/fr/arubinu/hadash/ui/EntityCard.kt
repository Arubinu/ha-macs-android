package fr.arubinu.hadash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arubinu.hadash.ha.EntityState
import fr.arubinu.hadash.ha.Fmt
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.theme.Dash

private val Muted = Color(255, 255, 255, 150)
private val CardBg = Color(255, 255, 255, 10)

/** Icône d'une entité : celle définie dans Home Assistant, sinon selon son type et sa classe. */
internal fun entityIcon(id: String, e: EntityState?): String {
    e?.attrString("icon")?.let { return it }
    val dc = e?.attrString("device_class")
    return when (id.substringBefore('.')) {
        "light" -> "mdi:lightbulb"
        "switch" -> "mdi:power-socket-eu"
        "camera" -> "mdi:cctv"
        "media_player" -> "mdi:speaker"
        "remote" -> "mdi:remote"
        "person" -> "mdi:account"
        "calendar" -> "mdi:calendar"
        "weather" -> "mdi:weather-partly-cloudy"
        "zone" -> "mdi:map-marker-radius"
        "alarm_control_panel" -> "mdi:shield-home"
        "input_select", "select" -> "mdi:form-dropdown"
        "fan" -> "mdi:fan"
        "cover" -> "mdi:window-shutter"
        "climate" -> "mdi:thermostat"
        "lock" -> "mdi:lock"
        "sun" -> "mdi:white-balance-sunny"
        "sensor" -> when (dc) {
            "temperature" -> "mdi:thermometer"
            "humidity" -> "mdi:water-percent"
            "battery" -> "mdi:battery"
            "power", "energy" -> "mdi:flash"
            "monetary" -> "mdi:cash"
            "timestamp" -> "mdi:clock-outline"
            else -> "mdi:eye"
        }
        "binary_sensor" -> when (dc) {
            "motion", "moving" -> "mdi:motion-sensor"
            "occupancy", "presence" -> "mdi:home-account"
            "door", "window", "opening" -> "mdi:door"
            "battery_charging" -> "mdi:battery-charging"
            else -> "mdi:checkbox-blank-circle-outline"
        }
        else -> "mdi:shape-outline"
    }
}

/**
 * Entité affichée dans les fenêtres de réglage : carte arrondie avec l'icône de l'entité (allumée si elle est
 * active), son nom, son identifiant en italique et sa valeur actuelle ; [trailing] reçoit les actions (changer,
 * retirer…). [label] s'affiche au-dessus (rôle de l'entité dans le bloc).
 */
@Composable
internal fun EntityCard(
    id: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    onClick: (() -> Unit)? = null,
    showState: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val e = entity(id.takeIf { it != "—" })
    Column(modifier.fillMaxWidth()) {
        label?.let { Text(it, Modifier.padding(start = 4.dp, bottom = 4.dp), style = TextStyle(fontSize = 13.sp, color = Muted, fontWeight = FontWeight.Medium)) }
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(CardBg, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 52.dp).padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val active = e?.isActive == true
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (active) Dash.colors.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                if (id == "—") HaIcon("mdi:plus", size = 18.dp, tint = Muted)
                else HaIcon(entityIcon(id, e), size = 19.dp, tint = if (active) Dash.colors.accent else Color.White.copy(alpha = 0.8f))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(e?.name ?: id, style = TextStyle(fontSize = 14.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (id != "—") Text(id, style = TextStyle(fontSize = 12.sp, color = Muted, fontStyle = FontStyle.Italic), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (showState && e != null) Text(
                Fmt.state(e), Modifier.widthIn(max = 120.dp).padding(horizontal = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 13.sp, color = Muted),
            )
            trailing()
        }
    }
}

/** Petit bouton rond d'action dans une [EntityCard] (changer, monter, retirer…). */
@Composable
internal fun EntityCardAction(icon: String, enabled: Boolean = true, tint: Color = Muted, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { HaIcon(icon, size = 20.dp, tint = if (enabled) tint else Color.White.copy(alpha = 0.15f)) }
}
