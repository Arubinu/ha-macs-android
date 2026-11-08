package fr.pergens.hamacs.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.R
import fr.pergens.hamacs.ui.components.HaIcon
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Danger = Color(0xFFFF6B6B)

/**
 * Messages d'état par-dessus le tableau de bord : action refusée par Home Assistant (toast, même style que
 * le bandeau du mode édition) et Home Assistant injoignable (pastille discrète en bas au centre).
 */
@Composable
fun BoxScope.StatusOverlays(state: DashState) {
    // Tout est empilé en bas au centre : bandeau du mode édition, pastille « injoignable », puis toast.
    val base = if (state.editMode) 72.dp else 12.dp
    val since by state.ha.unreachableSince.collectAsState()
    // Pastille affichée après 10 s sans connexion, pour ne pas clignoter lors d'une simple reconnexion.
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(since) {
        show = false
        val s = since ?: return@LaunchedEffect
        delay((10_000 - (System.currentTimeMillis() - s)).coerceAtLeast(0))
        show = true
    }
    val unreachable = show && since != null
    // Dernière heure connue : la pastille la garde pendant son fondu de disparition (sinon « 1 janv. 00:00 »).
    var lastSince by remember { mutableStateOf(0L) }
    since?.let { lastSince = it }
    // Sous-tension de l'alimentation : pastille pendant 10 min après la dernière alerte.
    val lowAt by fr.pergens.hamacs.PowerSupply.lastLow.collectAsState()
    val now = fr.pergens.hamacs.ui.cards.rememberNow(30_000)
    val lowPower = lowAt?.let { now - it < 10 * 60_000 } == true
    var lastLow by remember { mutableStateOf(0L) }
    lowAt?.let { lastLow = it }
    val pillsHeight = (if (unreachable) 34.dp else 0.dp) + (if (lowPower) 34.dp else 0.dp)

    // --- Action refusée ------------------------------------------------------------------------------
    var refusal by remember { mutableStateOf<String?>(null) }
    var refusalId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        state.ha.refusals.collect { refusal = it; refusalId++ }
    }
    LaunchedEffect(refusalId) {
        if (refusal != null) { delay(5000); refusal = null }
    }
    val notConnected = stringResource(R.string.ha_not_connected_action)
    AnimatedVisibility(
        refusal != null,
        // Au-dessus du bandeau du mode édition et de la pastille « injoignable » s'ils sont affichés.
        Modifier.align(Alignment.BottomCenter).padding(bottom = base + pillsHeight + if (pillsHeight > 0.dp) 6.dp else 0.dp),
        enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 2 },
        exit = fadeOut(tween(250)),
    ) {
        val shape = RoundedCornerShape(24.dp)
        val text = refusal?.let { if (it == fr.pergens.hamacs.ha.HaClient.NOT_CONNECTED) notConnected else stringResource(R.string.ha_action_refused, it) } ?: ""
        Row(
            Modifier.widthIn(max = 720.dp).clip(shape).background(Color(0xF0202024), shape)
                .border(1.dp, Danger.copy(alpha = 0.7f), shape)
                .heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:alert-circle-outline", size = 20.dp, tint = Danger)
            Text(
                text, Modifier.padding(start = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 14.sp, color = Color.White),
            )
        }
    }

    // --- Home Assistant injoignable ------------------------------------------------------------------
    AnimatedVisibility(
        unreachable,
        Modifier.align(Alignment.BottomCenter).padding(bottom = base),
        enter = fadeIn(tween(400)), exit = fadeOut(tween(400)),
    ) {
        val s = since ?: lastSince
        val t = Instant.ofEpochMilli(s).atZone(ZoneId.systemDefault())
        val label = if (t.toLocalDate() == LocalDate.now()) t.format(DateTimeFormatter.ofPattern("HH:mm"))
        else t.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.clip(shape).background(Color(0xB3141416), shape).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:lan-disconnect", size = 14.dp, tint = Danger.copy(alpha = 0.85f))
            Text(
                stringResource(R.string.ha_unreachable_since, label), Modifier.padding(start = 6.dp),
                style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f)),
            )
        }
    }

    // --- Alimentation trop faible (au-dessus de la pastille « injoignable ») -----------------------------
    AnimatedVisibility(
        lowPower,
        Modifier.align(Alignment.BottomCenter).padding(bottom = base + if (unreachable) 34.dp else 0.dp),
        enter = fadeIn(tween(400)), exit = fadeOut(tween(400)),
    ) {
        val label = Instant.ofEpochMilli(lastLow).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
        val shape = RoundedCornerShape(16.dp)
        Row(
            Modifier.clip(shape).background(Color(0xB3141416), shape).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:flash-alert-outline", size = 14.dp, tint = Color(0xFFFFB74D))
            Text(
                stringResource(R.string.power_low, label), Modifier.padding(start = 6.dp),
                style = TextStyle(fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f)),
            )
        }
    }
}
