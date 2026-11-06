package fr.pergens.hamacs.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.o
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.config.d
import kotlinx.serialization.json.JsonObject

/**
 * Jetons du thème « clear-transparent » (card-mod-root) : fond noir, aucun arrondi global,
 * menus en pilules et boutons ronds colorés.
 */
@Immutable
data class DashColors(
    val background: Color = Color.Black,
    val cardBackground: Color = Color.Black,
    val menuBackground: Color = Color(24, 24, 24),
    val menuActiveButton: Color = Color.White,
    val menuActiveButtonBackground: Color = Color(208, 208, 208, 128),
    val menuInactiveButton: Color = Color.White,
    val menuInactiveButtonBackground: Color = Color.Transparent,
    val iconActive: Color = Color(255, 255, 255, 13),
    val iconActiveBackground: Color = Color(140, 245, 255),
    val iconInactiveBackground: Color = Color(255, 255, 255, 13),
    val sensorBorder: Color = Color(255, 255, 255, 13),
    val defaultButtonBackground: Color = Color(255, 255, 255, 13),
    val firstButtonBackground: Color = Color(90, 172, 181),
    val secondButtonBackground: Color = Color(250, 172, 104),
    val thirdButtonBackground: Color = Color(174, 104, 250),
    val accent: Color = Color(140, 245, 255),
    val text: Color = Color.White,
    val textSecondary: Color = Color(255, 255, 255, 179),
    val divider: Color = Color(255, 255, 255, 128),
)

@Immutable
data class DashShapes(
    /** `--ha-card-border-radius: 0px` du thème : aucun arrondi par défaut (moins coûteux à dessiner). */
    val cardRadius: Dp = 0.dp,
    /** `--card-border-radius: 20px` : arrondi forcé sur certaines cartes (pièces, caméras, alertes). */
    val roundedRadius: Dp = 20.dp,
    /** Rayon des menus en pilules (border-radius: 18px). */
    val menuRadius: Dp = 18.dp,
    val menuBorder: Dp = 4.dp,
    val gutter: Dp = 12.dp,
)

@Immutable
data class DashTheme(
    val colors: DashColors = DashColors(),
    val shapes: DashShapes = DashShapes(),
)

val LocalDash = staticCompositionLocalOf { DashTheme() }

val MdiFont = FontFamily(Font(R.font.mdi))
val Audiowide = FontFamily(Font(R.font.audiowide))

object Dash {
    val colors: DashColors @Composable get() = LocalDash.current.colors
    val shapes: DashShapes @Composable get() = LocalDash.current.shapes
}

@Composable
fun DashThemeProvider(theme: DashTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDash provides theme, content = content)
}

/** Parse une couleur CSS : #rgb, #rrggbb, #rrggbbaa, rgb(), rgba(), noms courants. */
fun parseCssColor(value: String?): Color? {
    val v = value?.trim()?.lowercase() ?: return null
    when (v) {
        "black" -> return Color.Black
        "white" -> return Color.White
        "red" -> return Color.Red
        "transparent", "none" -> return Color.Transparent
    }
    if (v.startsWith("#")) {
        val h = v.substring(1)
        return runCatching {
            when (h.length) {
                3 -> Color(
                    h.substring(0, 1).repeat(2).toInt(16),
                    h.substring(1, 2).repeat(2).toInt(16),
                    h.substring(2, 3).repeat(2).toInt(16),
                )
                6 -> Color(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
                8 -> Color(
                    h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16),
                    h.substring(4, 6).toInt(16), h.substring(6, 8).toInt(16),
                )
                else -> null
            }
        }.getOrNull()
    }
    if (v.startsWith("rgb")) {
        val parts = v.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
        if (parts.size < 3) return null
        val r = parts[0].toFloatOrNull() ?: return null
        val g = parts[1].toFloatOrNull() ?: return null
        val b = parts[2].toFloatOrNull() ?: return null
        val a = parts.getOrNull(3)?.toFloatOrNull() ?: 1f
        return Color(r / 255f, g / 255f, b / 255f, a.coerceIn(0f, 1f))
    }
    return null
}

/**
 * Couleur principale choisie dans les Réglages : remplace l'accent du thème partout où il est utilisé
 * (accent, fond des boutons actifs s'il reprenait l'accent). [hex] vide ou invalide : thème inchangé.
 */
fun DashTheme.withAccent(hex: String?): DashTheme {
    val c = parseCssColor(hex)?.copy(alpha = 1f) ?: return this
    val old = colors.accent
    return copy(
        colors = colors.copy(
            accent = c,
            iconActiveBackground = if (colors.iconActiveBackground == old) c else colors.iconActiveBackground,
        ),
    )
}

/** Accent d'origine (#8CF5FF) : référence des décalages de teinte (images, M.A.C.S.). */
val DEFAULT_ACCENT = Color(140, 245, 255)

/**
 * Filtre recolorant une image pensée pour l'accent d'origine : rotation de teinte de l'écart entre
 * [accent] et #8CF5FF, saturation proportionnelle. Null si l'accent est celui d'origine.
 */
fun accentImageFilter(accent: Color): androidx.compose.ui.graphics.ColorFilter? {
    val t = FloatArray(3).also { android.graphics.Color.colorToHSV(accent.toArgb(), it) }
    val r = FloatArray(3).also { android.graphics.Color.colorToHSV(DEFAULT_ACCENT.toArgb(), it) }
    val deg = t[0] - r[0]
    val sat = if (r[1] > 0f) (t[1] / r[1]).coerceIn(0f, 1.5f) else 1f
    if (kotlin.math.abs(deg) < 1f && kotlin.math.abs(sat - 1f) < 0.03f) return null
    // Rotation de teinte (luminance conservée), puis saturation.
    val a = Math.toRadians(deg.toDouble())
    val c = kotlin.math.cos(a).toFloat()
    val s = kotlin.math.sin(a).toFloat()
    val hue = floatArrayOf(
        0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
        0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
        0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
    val m = androidx.compose.ui.graphics.ColorMatrix(hue)
    m.timesAssign(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(sat) })
    return androidx.compose.ui.graphics.ColorFilter.colorMatrix(m)
}

/** Applique les surcharges éventuelles du bloc "theme" de dashboard.json. */
fun themeFromConfig(cfg: JsonObject?): DashTheme {
    val base = DashTheme()
    if (cfg == null) return base
    val c = cfg.o("colors")
    fun col(key: String, def: Color) = parseCssColor(c?.s(key)) ?: def
    val d = base.colors
    return DashTheme(
        colors = DashColors(
            background = col("background", d.background),
            cardBackground = col("card_background", d.cardBackground),
            menuBackground = col("menu_background", d.menuBackground),
            menuActiveButton = col("menu_active_button", d.menuActiveButton),
            menuActiveButtonBackground = col("menu_active_button_background", d.menuActiveButtonBackground),
            menuInactiveButton = col("menu_inactive_button", d.menuInactiveButton),
            menuInactiveButtonBackground = col("menu_inactive_button_background", d.menuInactiveButtonBackground),
            iconActive = col("icon_active", d.iconActive),
            iconActiveBackground = col("icon_active_background", d.iconActiveBackground),
            iconInactiveBackground = col("icon_inactive_background", d.iconInactiveBackground),
            sensorBorder = col("sensor_border", d.sensorBorder),
            defaultButtonBackground = col("default_button_background", d.defaultButtonBackground),
            firstButtonBackground = col("first_button_background", d.firstButtonBackground),
            secondButtonBackground = col("second_button_background", d.secondButtonBackground),
            thirdButtonBackground = col("third_button_background", d.thirdButtonBackground),
            accent = col("accent", d.accent),
            text = col("text", d.text),
            textSecondary = col("text_secondary", d.textSecondary),
            divider = col("divider", d.divider),
        ),
        shapes = DashShapes(
            cardRadius = (cfg.d("card_radius") ?: base.shapes.cardRadius.value.toDouble()).toFloat().dp,
            roundedRadius = (cfg.d("rounded_radius") ?: base.shapes.roundedRadius.value.toDouble()).toFloat().dp,
            menuRadius = (cfg.d("menu_radius") ?: base.shapes.menuRadius.value.toDouble()).toFloat().dp,
            menuBorder = (cfg.d("menu_border") ?: base.shapes.menuBorder.value.toDouble()).toFloat().dp,
            gutter = (cfg.d("gutter") ?: base.shapes.gutter.value.toDouble()).toFloat().dp,
        ),
    )
}

/** Résout les noms de couleurs symboliques utilisés dans la config ("first", "second", "accent"…). */
fun DashColors.named(name: String?, default: Color): Color = when (name) {
    null -> default
    "first" -> firstButtonBackground
    "second" -> secondButtonBackground
    "third" -> thirdButtonBackground
    "default" -> defaultButtonBackground
    "accent" -> accent
    "active" -> iconActiveBackground
    else -> parseCssColor(name) ?: default
}

/**
 * Couleur de configuration d'une carte : noms symboliques (« accent », « first »…) ou CSS. Une couleur égale
 * à l'accent d'origine (#8CF5FF, alpha éventuel conservé) suit la couleur principale choisie.
 */
@Composable
fun configColor(value: String?, default: Color): Color {
    val accent = Dash.colors.accent
    val c = Dash.colors.named(value, default)
    val sameAsDefault = kotlin.math.abs(c.red - DEFAULT_ACCENT.red) < 0.01f &&
        kotlin.math.abs(c.green - DEFAULT_ACCENT.green) < 0.01f && kotlin.math.abs(c.blue - DEFAULT_ACCENT.blue) < 0.01f
    return if (sameAsDefault) accent.copy(alpha = c.alpha) else c
}
