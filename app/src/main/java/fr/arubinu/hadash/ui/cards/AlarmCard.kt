package fr.arubinu.hadash.ui.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arubinu.hadash.config.Card
import fr.arubinu.hadash.config.s
import fr.arubinu.hadash.ui.LocalDashState
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import fr.arubinu.hadash.R
import fr.arubinu.hadash.ui.PinRequest
import fr.arubinu.hadash.ui.components.DashCard
import fr.arubinu.hadash.ui.components.HaIcon
import fr.arubinu.hadash.ui.components.editOutline
import fr.arubinu.hadash.ui.components.tapHold
import fr.arubinu.hadash.ui.entity
import fr.arubinu.hadash.ui.theme.Dash
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private data class AlarmMode(val state: String, @StringRes val label: Int, val icon: String, val service: String)

private val MODES = listOf(
    AlarmMode("disarmed", R.string.alarm_mode_disarmed, "mdi:shield-off", "alarm_disarm"),
    AlarmMode("armed_away", R.string.alarm_mode_away, "mdi:lock", "alarm_arm_away"),
    AlarmMode("armed_home", R.string.alarm_mode_home, "mdi:home", "alarm_arm_home"),
)

@Composable
private fun stateLabel(s: String?): String = when (s) {
    "disarmed" -> stringResource(R.string.alarm_state_disarmed)
    "armed_away" -> stringResource(R.string.alarm_state_armed_away)
    "armed_home" -> stringResource(R.string.alarm_state_armed_home)
    "armed_night" -> stringResource(R.string.alarm_state_armed_night)
    "arming" -> stringResource(R.string.alarm_state_arming)
    "pending" -> stringResource(R.string.alarm_state_pending)
    "triggered" -> stringResource(R.string.alarm_state_triggered)
    null -> "—"
    else -> s
}

private fun stateIcon(s: String?) = when (s) {
    "disarmed" -> "mdi:shield-off"
    "armed_away" -> "mdi:shield-lock"
    "armed_home" -> "mdi:shield-home"
    "armed_night" -> "mdi:shield-moon"
    "arming", "pending" -> "mdi:shield-sync"
    "triggered" -> "mdi:bell-ring"
    else -> "mdi:shield-outline"
}

/** Version native de l'alarmo-card (sans le menu d'angle, barre d'actions sur fond du menu). */
@Composable
fun AlarmCard(card: Card, modifier: Modifier) {
    val state = LocalDashState.current
    val codeTitle = stringResource(R.string.alarm_code_title)
    val id = card.s("entity") ?: "alarm_control_panel.alarmo"
    val e = entity(id)
    val c = Dash.colors
    val ring by animateColorAsState(
        when (e?.state) {
            "triggered" -> Color(0xFFE53935)
            "disarmed", null -> Color(255, 255, 255, 140)
            else -> c.accent
        }, label = "alarm-ring",
    )

    fun run(mode: AlarmMode) {
        val needsCode = e?.attrString("code_format") != null &&
            (mode.service == "alarm_disarm" || e.attrBool("code_arm_required") != false)
        val call = { code: String? ->
            state.ha.fireService(
                "alarm_control_panel", mode.service,
                data = code?.let { buildJsonObject { put("code", JsonPrimitive(it)) } },
                target = buildJsonObject { put("entity_id", JsonPrimitive(id)) },
            )
        }
        // Code d'accès de la tablette d'abord (si le bloc est protégé), puis code de l'alarme si elle en exige un.
        state.guard(id) {
            if (needsCode) state.pin = PinRequest(codeTitle, null) { call(it) } else call(null)
        }
    }

    DashCard(modifier.fillMaxWidth(), padding = 20.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).border(2.dp, ring, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { HaIcon(stateIcon(e?.state), size = 28.dp, tint = Color(255, 255, 255, 140)) }
                Column(Modifier.padding(start = 22.dp)) {
                    Text(card.s("name") ?: stringResource(R.string.alarm_name), style = TextStyle(fontSize = 26.sp, color = Color.White))
                    Text(stateLabel(e?.state), style = TextStyle(fontSize = 14.sp, color = Color.White))
                }
            }
            val bar = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .padding(top = 20.dp)
                    .fillMaxWidth()
                    .clip(bar)
                    .background(c.menuBackground, bar)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MODES.forEach { mode ->
                    val active = e?.state == mode.state
                    val shape = RoundedCornerShape(10.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .editOutline(state.editMode && !active, shape)
                            .clip(shape)
                            .background(if (active) Color(255, 255, 255, 90) else Color.Transparent, shape)
                            .tapHold({ if (!active) run(mode) })
                            // Marge intérieure : de l'air au-dessus de l'icône et sous le texte. Le cadre touche la
                            // barre avec le même écart (6dp) en haut, en bas et sur les côtés.
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Bloc de la taille de l'icône seule : c'est l'icône qui est centrée, le voyant déborde à côté.
                        Box(Modifier.size(20.dp)) {
                            HaIcon(mode.icon, size = 20.dp)
                            // Indicateur « prêt » (aucun capteur ouvert) comme show_ready_indicator.
                            if (!active && mode.state != "disarmed" && e?.attrList("open_sensors").isNullOrEmpty()) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        // En haut à droite de l'icône, légèrement détaché d'elle.
                                        .offset(x = 8.dp, y = (-3).dp)
                                        .size(7.dp)
                                        .background(Color(0xFF4CAF50), CircleShape)
                                )
                            }
                        }
                        Text(stringResource(mode.label), Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 14.sp, color = Color.White))
                    }
                }
            }
        }
    }
}
