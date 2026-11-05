package fr.arubinu.hadash.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arubinu.hadash.ui.theme.Audiowide
import fr.arubinu.hadash.ui.theme.Dash

/** Clic + appui long sans ripple Material (comme `--ha-ripple-*-opacity: 0`). */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tapHold(onTap: (() -> Unit)?, onHold: (() -> Unit)? = null): Modifier =
    if (onTap == null && onHold == null) this
    else this.then(
        Modifier.combinedClickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = { onTap?.invoke() },
            onLongClick = onHold,
        )
    )

/** Contour en pointillés d'accent signalant un bloc réglable, uniquement en mode édition. */
@Composable
fun Modifier.editOutline(editMode: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier {
    if (!editMode) return this
    val color = Dash.colors.accent
    return this.drawWithContent {
        drawContent()
        // Coins toujours arrondis (12dp minimum), même pour un bloc à angles droits ; trait entièrement
        // à l'intérieur du bloc (retrait d'une demi-épaisseur).
        val minR = 12.dp.toPx()
        val half = 1.dp.toPx()
        fun round(r: androidx.compose.ui.geometry.CornerRadius) =
            androidx.compose.ui.geometry.CornerRadius(maxOf(r.x, minR) - half, maxOf(r.y, minR) - half)
        val path = androidx.compose.ui.graphics.Path().apply {
            when (val outline = shape.createOutline(size, layoutDirection, this@drawWithContent)) {
                is androidx.compose.ui.graphics.Outline.Rectangle -> addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(outline.rect.deflate(half), round(androidx.compose.ui.geometry.CornerRadius.Zero)),
                )
                is androidx.compose.ui.graphics.Outline.Rounded -> {
                    val rr = outline.roundRect
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            rr.left + half, rr.top + half, rr.right - half, rr.bottom - half,
                            round(rr.topLeftCornerRadius), round(rr.topRightCornerRadius),
                            round(rr.bottomRightCornerRadius), round(rr.bottomLeftCornerRadius),
                        ),
                    )
                }
                else -> addOutline(outline)
            }
        }
        drawPath(
            path, color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())),
            ),
        )
    }
}

/** Conteneur « ha-card » : fond du thème, rayon global (0 par défaut). */
@Composable
fun DashCard(
    modifier: Modifier = Modifier,
    background: Color = Dash.colors.cardBackground,
    radius: Dp = Dash.shapes.cardRadius,
    padding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(radius) { RoundedCornerShape(radius) }
    Box(
        modifier
            .clip(shape)
            .background(background, shape)
            .padding(padding),
        content = content,
    )
}

/** Titre de section en police Audiowide (les « ### … » markdown du dashboard). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        style = TextStyle(
            fontFamily = Audiowide,
            fontSize = 20.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Normal,
        ),
    )
}

/** Petit séparateur horizontal centré (barre blanche 50 % utilisée entre les blocs). */
@Composable
fun CenterRule(modifier: Modifier = Modifier) {
    // Ligne de 1px, moitié de la largeur (left: 25%; right: 25%; background: rgba(255,255,255,.5)).
    Box(modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth(0.5f)
                .height(1.dp)
                .background(Dash.colors.divider)
        )
    }
}
