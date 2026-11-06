package fr.pergens.hamacs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.coroutines.delay

/** Notification envoyée par Home Assistant (`notify.mobile_app_…`) : bandeau en haut, refermé au toucher ou après 15 s. */
@Composable
fun HaBanner(title: String?, message: String, onClose: () -> Unit) {
    LaunchedEffect(title, message) { delay(15_000); onClose() }
    Box(Modifier.fillMaxSize().padding(top = 24.dp), contentAlignment = Alignment.TopCenter) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier.widthIn(max = 900.dp).clip(shape).background(Color(0xF21C1C21), shape)
                .border(1.dp, Dash.colors.accent.copy(alpha = 0.6f), shape).clickable(onClick = onClose)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaIcon("mdi:home-assistant", size = 30.dp, tint = Dash.colors.accent)
            Column(Modifier.padding(start = 16.dp)) {
                title?.let { Text(it, style = TextStyle(fontSize = 15.sp, color = Dash.colors.accent, fontWeight = FontWeight.SemiBold)) }
                Text(message, style = TextStyle(fontSize = 18.sp, color = Color.White, lineHeight = 24.sp))
            }
        }
    }
}
