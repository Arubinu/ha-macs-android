package fr.arubinu.hadash.ui.cards.alerts

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Thèmes de l'AlertTicker Card (djdevil, MIT), transcrits de son CSS : fond, bordure, halo animé,
 * couleurs du badge et du titre, cadre et animation de l'icône, décors animés.
 */
enum class Category { Critical, Warning, Info, Ok, Style, Timer }

/** Fond : dégradé linéaire (angle CSS en degrés) ou radial. */
sealed interface Bg {
    data class Linear(val angle: Float, val colors: List<Color>) : Bg
    data class Radial(val colors: List<Color>, val cx: Float = 0.5f, val cy: Float = 0.5f) : Bg
    data class Solid(val color: Color) : Bg
}

/** Bordure : épaisseur uniforme, ou barre gauche épaisse + fins côtés. */
data class Border(val width: Float, val color: Color, val left: Float? = null, val sideColor: Color? = null)

/** Halo extérieur (box-shadow) animé entre deux intensités. */
data class Glow(
    val color: Color,
    val blur: Float,
    val color2: Color = color,
    val blur2: Float = blur,
    val period: Int = 0,
    /** Pic bref (orage, volt) au lieu d'une respiration sinusoïdale. */
    val flashAt: Float? = null,
    /** Clignotement franc (batterie) : moitié du temps avec halo, moitié sans. */
    val blink: Boolean = false,
    /** Couleur de bordure animée en même temps que le halo. */
    val border2: Color? = null,
)

enum class IconBoxShape { Circle, Rounded }

data class IconBox(
    val size: Float,
    val shape: IconBoxShape,
    val radius: Float = 0f,
    val bg: List<Color>,
    val border: Color? = null,
    val borderWidth: Float = 1f,
)

/** Animations d'icône (keyframes CSS des thèmes). */
enum class IconAnim {
    None, Flash, FlashFast, Flicker, Spin, SpinFast, Pulse, PulseSoft, Bob, Shake, Swing, SwingV,
    Sway, Scan, Beat, Bounce, Glow, Ping, Blink, Wobble, Zap, Thunder, Collapse, Sparkle, Facet, FlareSpin,
    Tilt, FlickerHolo, FloatSpin,
}

/** Décors animés dessinés derrière le contenu. */
enum class Decor {
    Strobe, LightningFlash, NeonScan, HoloGrid, HoloScan, NuclearPulse, Radar, Confetti, Ecg, Scanlines, Waves,
    MotionLines, Stripes, Bubbles, TempBar, Drain, DoorRay, WindowShimmer, PresencePing, UpdateRing, ShieldScan,
    PowerLines, CyberLines, CyberGlitch, VaporGrid, VaporPulse, Lava, Smoke, WindLines, Leak, CloudPulse,
    SatWaves, TipsGlow, SunRay, PlantBar, LockScan, LightBeam, Notes, Portal, Void, VoltGrid, VoltZap, Nebula,
    PrismFacets, PrismSweep, ArcadeGrid, ArcadeScan, DiamondFacets, DiamondShimmer, Quantum, Rain, StormFlash,
    Snow, FrostShimmer, Aurora, WarnDot,
}

data class AtcTheme(
    val id: String,
    val emoji: String,
    val category: Category,
    /** Couleur d'accent (THEME_META.color). */
    val color: Color,
    val bg: Bg,
    val border: Border? = null,
    /** Rayon propre au thème (null = celui de la carte). */
    val radius: Float? = null,
    val glow: Glow? = null,
    val badge: Color = Color.White,
    val badgeGradient: List<Color>? = null,
    val badgeGlow: Boolean = false,
    val title: Color = Color.White,
    val titleWeight: Int = 600,
    val titleRem: Float = 0.98f,
    val badgeRem: Float = 0.72f,
    val iconRem: Float = 2.2f,
    val iconBox: IconBox? = null,
    val iconAnim: IconAnim = IconAnim.None,
    val iconGlow: Color? = null,
    val decor: List<Decor> = emptyList(),
    val mono: Boolean = false,
    /** Thème clair (minimal) : textes foncés. */
    val light: Boolean = false,
)

private fun c(hex: Long) = Color(hex or 0xFF000000)
private fun rgba(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a * 255).toInt())
private fun lin(angle: Float, vararg colors: Long) = Bg.Linear(angle, colors.map { c(it) })

object AtcThemes {
    private val list = listOf(
        // --- Critique ---
        AtcTheme("emergency", "🚨", Category.Critical, c(0xff5252), lin(135f, 0x1a0000, 0x3d0000),
            Border(2f, c(0xe53935)), glow = Glow(rgba(229, 57, 53, 0.4f), 20f, rgba(229, 57, 53, 0.8f), 45f, 1200),
            badge = c(0xff6b6b), titleRem = 1.05f, iconRem = 2f, iconAnim = IconAnim.Flash),
        AtcTheme("fire", "🔥", Category.Critical, c(0xff6d00), lin(135f, 0x1a0500, 0x2d0a00),
            Border(2f, c(0xff6d00)), glow = Glow(rgba(255, 109, 0, 0.35f), 22f, rgba(255, 109, 0, 0.75f), 42f, 900),
            badge = c(0xff9100), titleRem = 1.05f, iconRem = 2f, iconAnim = IconAnim.Flicker, iconGlow = c(0xff6d00)),
        AtcTheme("alarm", "🔴", Category.Critical, c(0xff1744), Bg.Solid(c(0x0d0000)),
            Border(2f, c(0xff1744)), badge = c(0xff5252), titleRem = 1.05f, iconRem = 2f, iconAnim = IconAnim.FlashFast,
            decor = listOf(Decor.Strobe)),
        AtcTheme("lightning", "🌩️", Category.Critical, c(0xd500f9), Bg.Solid(c(0x050510)),
            Border(2f, c(0x7c4dff)), glow = Glow(rgba(124, 77, 255, 0.3f), 20f), badge = c(0xb388ff), title = c(0xe8eaff),
            titleRem = 1.05f, iconRem = 2f, iconAnim = IconAnim.Glow, iconGlow = c(0x7c4dff), decor = listOf(Decor.LightningFlash)),
        AtcTheme("nuclear", "☢️", Category.Critical, c(0xffd54f), Bg.Radial(listOf(c(0x0f0d00), c(0x060500))),
            Border(2f, c(0xf9a825)), radius = 12f,
            glow = Glow(rgba(249, 168, 37, 0.35f), 22f, rgba(249, 168, 37, 0.7f), 52f, 2500, border2 = c(0xffca28)),
            titleRem = 1.05f, iconAnim = IconAnim.Spin, iconGlow = c(0xf9a825), decor = listOf(Decor.NuclearPulse)),
        AtcTheme("flood", "🌊", Category.Critical, c(0x40c4ff), lin(180f, 0x010b15, 0x012a4a),
            Border(2f, c(0x0096c7)), radius = 12f, glow = Glow(rgba(0, 150, 199, 0.3f), 22f, rgba(0, 150, 199, 0.7f), 55f, 2000),
            badge = c(0x00e5ff), title = c(0xe0f7fa), titleWeight = 700, titleRem = 1.05f, iconAnim = IconAnim.Bob,
            iconGlow = c(0x0096c7), decor = listOf(Decor.Waves)),
        AtcTheme("motion", "👁️", Category.Critical, c(0x00e676), lin(135f, 0x000e00, 0x001a00),
            Border(1f, rgba(0, 255, 65, 0.45f)), radius = 12f, glow = Glow(rgba(0, 255, 65, 0.08f), 18f),
            badge = c(0x00ff41), badgeGlow = true, title = c(0xccffcc), titleWeight = 700, titleRem = 1.05f,
            iconAnim = IconAnim.Scan, iconGlow = rgba(0, 255, 65, 0.8f), decor = listOf(Decor.MotionLines)),
        AtcTheme("intruder", "🚷", Category.Critical, c(0xff1744), Bg.Solid(c(0x0a0000)),
            Border(2f, c(0xd50000)), radius = 12f,
            glow = Glow(rgba(213, 0, 0, 0.3f), 20f, rgba(255, 23, 68, 0.65f), 55f, 650, border2 = c(0xff1744)),
            badge = c(0xff1744), badgeGlow = true, title = c(0xffcdd2), titleWeight = 700, titleRem = 1.05f,
            iconAnim = IconAnim.SpinFast, iconGlow = c(0xff1744), decor = listOf(Decor.Stripes)),
        AtcTheme("toxic", "☠️", Category.Critical, c(0x76ff03), lin(135f, 0x010d00, 0x001a00),
            Border(1f, rgba(57, 255, 20, 0.5f)), radius = 12f, badge = c(0x39ff14), badgeGlow = true, title = c(0xccffcc),
            titleWeight = 700, titleRem = 1.05f, iconAnim = IconAnim.Glow, iconGlow = rgba(57, 255, 20, 0.7f), decor = listOf(Decor.Bubbles)),
        // --- Attention ---
        AtcTheme("warning", "⚠️", Category.Warning, c(0xffab40), lin(135f, 0x1a1000, 0x2d1f00),
            Border(1f, rgba(255, 152, 0, 0.2f), left = 4f, sideColor = c(0xff9800)), badge = c(0xff9800), title = c(0xffe0b2),
            iconRem = 2f, decor = listOf(Decor.WarnDot)),
        AtcTheme("caution", "🟡", Category.Warning, c(0xffc107), Bg.Solid(c(0x1a1400)),
            Border(2f, c(0xffc107)), radius = 10f, badge = c(0xffc107), title = c(0xfff8e1), iconRem = 2f,
            iconGlow = c(0xffc107), decor = listOf(Decor.WarnDot)),
        AtcTheme("radar", "🎯", Category.Warning, c(0x64ffda), Bg.Solid(c(0x120c00)),
            Border(1f, rgba(255, 160, 0, 0.35f)), radius = 12f, badge = c(0xffa000), title = c(0xfff3e0), iconRem = 2f,
            iconAnim = IconAnim.Ping, iconGlow = c(0xffa000), decor = listOf(Decor.Radar)),
        AtcTheme("temperature", "🌡️", Category.Warning, c(0xff6d00), lin(135f, 0x0d0300, 0x1f0800),
            Border(1f, rgba(255, 87, 34, 0.55f)), radius = 12f, glow = Glow(rgba(255, 87, 34, 0.12f), 18f),
            badge = c(0xff8f00), title = c(0xfff3e0), iconAnim = IconAnim.Shake, iconGlow = rgba(255, 87, 34, 0.8f),
            decor = listOf(Decor.TempBar)),
        AtcTheme("battery", "🔋", Category.Warning, c(0xffca28), lin(135f, 0x0d0800, 0x1a1200),
            Border(2f, c(0xff6f00)), radius = 12f,
            glow = Glow(rgba(255, 111, 0, 0.4f), 22f, Color.Transparent, 0f, 1200, blink = true, border2 = rgba(255, 111, 0, 0.25f)),
            badge = c(0xff8f00), title = c(0xfff8e1), iconAnim = IconAnim.Blink, iconGlow = rgba(255, 111, 0, 0.8f),
            decor = listOf(Decor.Drain)),
        AtcTheme("battery3d", "🔋", Category.Warning, c(0x4caf50), lin(135f, 0x0a1210, 0x101c19),
            Border(1f, rgba(255, 255, 255, 0.05f)), radius = 12f, titleRem = 0.95f),
        AtcTheme("door", "🚪", Category.Warning, c(0xffab40), lin(135f, 0x0d0b00, 0x1c1700),
            Border(2f, c(0xffd600)), radius = 12f, glow = Glow(rgba(255, 214, 0, 0.1f), 18f), badge = c(0xffd600),
            title = c(0xfffde7), iconAnim = IconAnim.Swing, iconGlow = rgba(255, 214, 0, 0.8f), decor = listOf(Decor.DoorRay)),
        AtcTheme("window", "🪟", Category.Warning, c(0x80d8ff), lin(135f, 0x001929, 0x002e4d),
            Border(1f, rgba(128, 216, 255, 0.35f)), radius = 12f, glow = Glow(rgba(128, 216, 255, 0.08f), 18f),
            badge = c(0x80d8ff), title = c(0xe1f5fe), iconAnim = IconAnim.SwingV, iconGlow = rgba(128, 216, 255, 0.7f),
            decor = listOf(Decor.WindowShimmer)),
        AtcTheme("smoke", "🌫️", Category.Warning, c(0xb0bec5), lin(135f, 0x0a0a0a, 0x151515),
            Border(1f, rgba(160, 160, 160, 0.4f)), radius = 12f, glow = Glow(rgba(120, 120, 120, 0.08f), 18f),
            badge = c(0x9e9e9e), title = c(0xeeeeee), iconAnim = IconAnim.Wobble, iconGlow = rgba(180, 180, 180, 0.5f),
            decor = listOf(Decor.Smoke)),
        AtcTheme("wind", "💨", Category.Warning, c(0x80d8ff), lin(135f, 0x00080d, 0x001018),
            Border(1f, rgba(100, 200, 255, 0.35f)), radius = 12f, glow = Glow(rgba(100, 200, 255, 0.06f), 18f),
            badge = c(0x64c8ff), title = c(0xe0f4ff), iconAnim = IconAnim.Sway, iconGlow = rgba(100, 200, 255, 0.8f),
            decor = listOf(Decor.WindLines)),
        AtcTheme("leak", "💧", Category.Warning, c(0x40c4ff), lin(135f, 0x000512, 0x000d20),
            Border(1f, rgba(30, 136, 229, 0.45f)), radius = 12f, glow = Glow(rgba(30, 136, 229, 0.08f), 18f),
            badge = c(0x1e88e5), title = c(0xe3f2fd), iconAnim = IconAnim.Bob, iconGlow = rgba(30, 136, 229, 0.8f),
            decor = listOf(Decor.Leak)),
        // --- Information ---
        AtcTheme("info", "ℹ️", Category.Info, c(0x40c4ff), lin(135f, 0x001a2d, 0x002340),
            Border(1f, rgba(41, 182, 246, 0.15f), left = 4f, sideColor = c(0x29b6f6)), badge = c(0x29b6f6), title = c(0xe1f5fe),
            titleWeight = 500, iconBox = IconBox(40f, IconBoxShape.Circle, bg = listOf(rgba(41, 182, 246, 0.15f)), border = rgba(41, 182, 246, 0.3f))),
        AtcTheme("notification", "🔔", Category.Info, c(0x40c4ff), lin(135f, 0x001428, 0x001e3c),
            Border(1f, rgba(33, 150, 243, 0.3f)), radius = 14f, badge = c(0x64b5f6), title = c(0xe3f2fd), titleWeight = 500,
            iconBox = IconBox(46f, IconBoxShape.Rounded, 14f, listOf(c(0x1565c0), c(0x1976d2)))),
        AtcTheme("aurora", "🌌", Category.Info, c(0xea80fc), Bg.Solid(c(0x020c14)),
            Border(1f, rgba(100, 255, 218, 0.3f)), radius = 14f, badge = c(0x64ffda), title = c(0xe0f7fa), titleWeight = 500,
            iconBox = IconBox(42f, IconBoxShape.Circle, bg = listOf(rgba(0, 200, 140, 0.15f)), border = rgba(0, 200, 140, 0.4f)),
            decor = listOf(Decor.Aurora)),
        AtcTheme("hologram", "🔷", Category.Info, c(0x18ffff), Bg.Solid(c(0x000d1a)),
            Border(1f, rgba(0, 200, 255, 0.4f)), radius = 12f, glow = Glow(rgba(0, 200, 255, 0.1f), 22f),
            badge = c(0x00c8ff), badgeGlow = true, title = c(0xb3ecff),
            iconBox = IconBox(44f, IconBoxShape.Rounded, 8f, listOf(rgba(0, 200, 255, 0.1f)), rgba(0, 200, 255, 0.45f)),
            iconAnim = IconAnim.FlickerHolo, iconGlow = rgba(0, 200, 255, 0.5f), decor = listOf(Decor.HoloGrid, Decor.HoloScan)),
        AtcTheme("presence", "🏠", Category.Info, c(0x40c4ff), lin(135f, 0x00071a, 0x000e28),
            Border(1f, rgba(0, 188, 212, 0.4f)), radius = 12f, glow = Glow(rgba(0, 188, 212, 0.08f), 18f),
            badge = c(0x00bcd4), title = c(0xe0f7fa), iconAnim = IconAnim.Blink, iconGlow = rgba(0, 188, 212, 0.7f),
            decor = listOf(Decor.PresencePing)),
        AtcTheme("update", "🔄", Category.Info, c(0x40c4ff), lin(135f, 0x000d1a, 0x001226),
            Border(1f, rgba(0, 229, 255, 0.35f)), radius = 12f, glow = Glow(rgba(0, 229, 255, 0.06f), 18f),
            badge = c(0x00e5ff), title = c(0xe0f7ff), iconAnim = IconAnim.Spin, iconGlow = rgba(0, 229, 255, 0.7f),
            decor = listOf(Decor.UpdateRing)),
        AtcTheme("cloud", "☁️", Category.Info, c(0x82b1ff), lin(135f, 0x080c10, 0x0d1520),
            Border(1f, rgba(144, 164, 174, 0.35f)), radius = 12f,
            glow = Glow(rgba(144, 164, 174, 0.1f), 18f, rgba(144, 164, 174, 0.22f), 32f, 4000),
            badge = c(0x90a4ae), title = c(0xeceff1), iconAnim = IconAnim.Bob, iconGlow = rgba(144, 164, 174, 0.6f),
            decor = listOf(Decor.CloudPulse)),
        AtcTheme("satellite", "📡", Category.Info, c(0x40c4ff), lin(135f, 0x000a14, 0x001020),
            Border(1f, rgba(0, 230, 118, 0.3f)), radius = 12f, glow = Glow(rgba(0, 230, 118, 0.06f), 18f),
            badge = c(0x00e676), title = c(0xe8f5e9), iconAnim = IconAnim.Blink, iconGlow = rgba(0, 230, 118, 0.7f),
            decor = listOf(Decor.SatWaves)),
        AtcTheme("tips", "💡", Category.Info, c(0xffeb3b), lin(135f, 0x0d0900, 0x1a1200),
            Border(1f, rgba(255, 193, 7, 0.35f)), radius = 12f,
            glow = Glow(rgba(255, 193, 7, 0.12f), 18f, rgba(255, 193, 7, 0.28f), 38f, 3000),
            badge = c(0xffc107), title = c(0xfff8e1), iconAnim = IconAnim.Ping, iconGlow = rgba(255, 193, 7, 0.9f),
            decor = listOf(Decor.TipsGlow)),
        AtcTheme("light", "🔆", Category.Info, c(0xffd600), lin(135f, 0x0d0b00, 0x1a1500),
            Border(1f, rgba(255, 214, 0, 0.4f)), radius = 12f,
            glow = Glow(rgba(255, 214, 0, 0.1f), 20f, rgba(255, 214, 0, 0.3f), 48f, 2800),
            badge = c(0xffd600), title = c(0xfffde7), iconAnim = IconAnim.Pulse, iconGlow = rgba(255, 214, 0, 0.6f),
            decor = listOf(Decor.LightBeam)),
        AtcTheme("music", "🎵", Category.Info, c(0xe040fb), lin(135f, 0x1a001a, 0x2e0033),
            Border(1f, rgba(224, 64, 251, 0.4f)), radius = 12f,
            glow = Glow(rgba(224, 64, 251, 0.1f), 20f, rgba(224, 64, 251, 0.35f), 50f, 2400),
            badge = c(0xe040fb), title = c(0xf3e5f5), iconAnim = IconAnim.Beat, iconGlow = c(0xe040fb), decor = listOf(Decor.Notes)),
        // --- OK ---
        AtcTheme("success", "✅", Category.Ok, c(0x69f0ae), lin(135f, 0x001a0a, 0x002d14),
            Border(1f, rgba(76, 175, 80, 0.15f), left = 4f, sideColor = c(0x4caf50)), badge = c(0x4caf50), title = c(0xc8e6c9),
            titleWeight = 500, iconBox = IconBox(40f, IconBoxShape.Circle, bg = listOf(rgba(76, 175, 80, 0.15f)))),
        AtcTheme("check", "🟢", Category.Ok, c(0x69f0ae), lin(135f, 0x001408, 0x003018),
            Border(2f, c(0x00c853)), radius = 12f, glow = Glow(rgba(0, 200, 83, 0.2f), 16f), badge = c(0x00c853), title = c(0xb9f6ca),
            iconBox = IconBox(46f, IconBoxShape.Circle, bg = listOf(rgba(0, 200, 83, 0.15f)), border = rgba(0, 200, 83, 0.4f), borderWidth = 2f),
            iconAnim = IconAnim.Ping),
        AtcTheme("confetti", "🎉", Category.Ok, c(0x69f0ae), lin(135f, 0x001408, 0x003020),
            Border(2f, c(0x69f0ae)), radius = 12f, glow = Glow(rgba(105, 240, 174, 0.2f), 18f), badge = c(0x69f0ae), title = c(0xe8f5e9),
            iconBox = IconBox(44f, IconBoxShape.Circle, bg = listOf(rgba(105, 240, 174, 0.15f))), iconAnim = IconAnim.Bounce,
            decor = listOf(Decor.Confetti)),
        AtcTheme("heartbeat", "💓", Category.Ok, c(0xff4081), lin(135f, 0x001008, 0x001e10),
            Border(1f, rgba(0, 200, 83, 0.35f)), radius = 12f, glow = Glow(rgba(0, 200, 83, 0.12f), 18f),
            iconBox = IconBox(46f, IconBoxShape.Circle, bg = listOf(rgba(0, 200, 83, 0.12f)), border = rgba(0, 200, 83, 0.45f), borderWidth = 2f),
            iconAnim = IconAnim.Beat, decor = listOf(Decor.Ecg)),
        AtcTheme("shield", "🛡️", Category.Ok, c(0x69f0ae), lin(135f, 0x000d00, 0x001a00),
            Border(2f, c(0x00c853)), radius = 12f, glow = Glow(rgba(0, 200, 83, 0.3f), 20f, rgba(0, 200, 83, 0.65f), 48f, 2500),
            badge = c(0x00c853), title = c(0xe8f5e9), iconAnim = IconAnim.PulseSoft, iconGlow = c(0x00c853), decor = listOf(Decor.ShieldScan)),
        AtcTheme("power", "⚡", Category.Ok, c(0xffab40), lin(135f, 0x000a00, 0x001600),
            Border(2f, c(0x76ff03)), radius = 12f,
            glow = Glow(rgba(118, 255, 3, 0.35f), 18f, rgba(118, 255, 3, 0.75f), 45f, 1600, border2 = c(0xb2ff59)),
            badge = c(0x76ff03), title = c(0xf1ffe0), iconAnim = IconAnim.Zap, iconGlow = c(0x76ff03), decor = listOf(Decor.PowerLines)),
        AtcTheme("sunrise", "🌅", Category.Ok, c(0xffd54f), lin(135f, 0x0d0500, 0x180c00),
            Border(1f, rgba(255, 160, 0, 0.4f)), radius = 12f,
            glow = Glow(rgba(255, 160, 0, 0.15f), 18f, rgba(255, 160, 0, 0.35f), 40f, 4000),
            badge = c(0xffa000), title = c(0xfff8e1), iconAnim = IconAnim.Bob, iconGlow = rgba(255, 160, 0, 0.9f), decor = listOf(Decor.SunRay)),
        AtcTheme("plant", "🌱", Category.Ok, c(0x69f0ae), lin(135f, 0x010d00, 0x011800),
            Border(1f, rgba(0, 200, 83, 0.4f)), radius = 12f, glow = Glow(rgba(0, 200, 83, 0.06f), 18f),
            badge = c(0x00c853), title = c(0xe8f5e9), iconAnim = IconAnim.PulseSoft, iconGlow = rgba(0, 200, 83, 0.7f), decor = listOf(Decor.PlantBar)),
        AtcTheme("lock", "🔒", Category.Ok, c(0x69f0ae), lin(135f, 0x00030d, 0x000616),
            Border(2f, c(0x1565c0)), radius = 12f,
            glow = Glow(rgba(21, 101, 192, 0.3f), 18f, rgba(21, 101, 192, 0.6f), 38f, 3000, border2 = c(0x1976d2)),
            badge = c(0x1976d2), title = c(0xe3f2fd), iconAnim = IconAnim.PulseSoft, iconGlow = rgba(21, 101, 192, 0.9f), decor = listOf(Decor.LockScan)),
        // --- 3D spectaculaires ---
        AtcTheme("portal", "🌀", Category.Critical, c(0xff1744), lin(135f, 0x1a0005, 0x2d0010), radius = 12f,
            glow = Glow(rgba(255, 23, 68, 0.35f), 24f, rgba(255, 23, 68, 0.7f), 65f, 2500),
            badge = c(0xff5252), title = c(0xffc1c1), iconAnim = IconAnim.FlareSpin, iconGlow = rgba(255, 23, 68, 0.95f), decor = listOf(Decor.Portal)),
        AtcTheme("void", "⚫", Category.Critical, c(0xd500f9), Bg.Radial(listOf(c(0x1a0033), c(0x08000f)), cx = 0.25f), radius = 12f,
            glow = Glow(rgba(170, 0, 255, 0.25f), 20f, rgba(213, 0, 249, 0.55f), 55f, 4000),
            badge = c(0xd500f9), title = c(0xf0c4ff), iconAnim = IconAnim.Collapse, iconGlow = rgba(213, 0, 249, 0.85f), decor = listOf(Decor.Void)),
        AtcTheme("volt", "⚡", Category.Warning, c(0xf9f926), lin(135f, 0x080800, 0x141400),
            Border(1f, rgba(249, 249, 38, 0.5f)), radius = 12f,
            glow = Glow(rgba(249, 249, 38, 0.18f), 12f, rgba(249, 249, 38, 0.9f), 55f, 1200, flashAt = 0.82f, border2 = c(0xf9f926)),
            badge = c(0xf9f926), title = c(0xfffff0), iconAnim = IconAnim.Thunder, iconGlow = rgba(249, 249, 38, 0.6f),
            decor = listOf(Decor.VoltGrid, Decor.VoltZap)),
        AtcTheme("nebula", "🌌", Category.Warning, c(0xce93d8), lin(135f, 0x0d0019, 0x0a1529), radius = 12f,
            badge = c(0xce93d8), title = c(0xede7f6), iconAnim = IconAnim.Bob, iconGlow = rgba(206, 147, 216, 0.9f), decor = listOf(Decor.Nebula)),
        AtcTheme("prism", "💎", Category.Info, Color.White, lin(135f, 0x0a0a14, 0x14141e),
            Border(1f, rgba(255, 255, 255, 0.18f)), radius = 12f,
            badgeGradient = listOf(c(0xff69b4), c(0xffa500), c(0xffe000), c(0x00e676), c(0x00bfff), c(0xaa44ff)),
            title = c(0xf5f5ff), iconAnim = IconAnim.Sparkle, decor = listOf(Decor.PrismFacets, Decor.PrismSweep)),
        AtcTheme("arcade", "🕹️", Category.Info, c(0x00e5ff), lin(180f, 0x00050d, 0x00101a),
            Border(1f, rgba(0, 229, 255, 0.55f)), radius = 12f, glow = Glow(rgba(0, 229, 255, 0.2f), 20f),
            badge = c(0x00e5ff), title = c(0xb2ebf2), mono = true, iconAnim = IconAnim.Pulse, iconGlow = rgba(0, 229, 255, 0.95f),
            decor = listOf(Decor.ArcadeGrid, Decor.ArcadeScan)),
        AtcTheme("diamond", "💠", Category.Ok, c(0x80deea), lin(135f, 0x00141a, 0x001a24),
            Border(1f, rgba(128, 222, 234, 0.5f)), radius = 12f,
            glow = Glow(rgba(128, 222, 234, 0.2f), 18f, rgba(128, 222, 234, 0.55f), 48f, 3000),
            badge = c(0x80deea), title = c(0xe0f7fa), iconAnim = IconAnim.Facet, iconGlow = rgba(128, 222, 234, 0.7f),
            decor = listOf(Decor.DiamondFacets, Decor.DiamondShimmer)),
        AtcTheme("quantum", "⚛️", Category.Ok, c(0x69f0ae), lin(135f, 0x001a0d, 0x001a19), radius = 12f,
            glow = Glow(rgba(105, 240, 174, 0.18f), 16f, rgba(105, 240, 174, 0.45f), 42f, 3000),
            badge = c(0x69f0ae), title = c(0xc8f7d8), iconAnim = IconAnim.Bob, iconGlow = rgba(105, 240, 174, 0.85f), decor = listOf(Decor.Quantum)),
        // --- Météo ---
        AtcTheme("storm", "⛈️", Category.Warning, c(0x82b0ff), lin(160f, 0x050810, 0x080f1a, 0x0c1628),
            Border(1f, rgba(100, 150, 255, 0.3f)), radius = 12f,
            glow = Glow(rgba(80, 130, 255, 0.12f), 18f, rgba(200, 220, 255, 0.9f), 80f, 3500, flashAt = 0.81f),
            badge = c(0x82b0ff), title = c(0xdce8ff), titleWeight = 700, iconAnim = IconAnim.Thunder, iconGlow = rgba(100, 160, 255, 0.8f),
            decor = listOf(Decor.Rain, Decor.StormFlash)),
        AtcTheme("frost", "❄️", Category.Info, c(0x90caf9), lin(160f, 0x020610, 0x040d1c, 0x06142a),
            Border(1f, rgba(160, 210, 255, 0.25f)), radius = 12f,
            glow = Glow(rgba(140, 200, 255, 0.1f), 16f, rgba(140, 200, 255, 0.25f), 34f, 5000),
            badge = c(0x90caf9), title = c(0xe3f2fd), iconAnim = IconAnim.FloatSpin, iconGlow = rgba(160, 220, 255, 0.85f),
            decor = listOf(Decor.Snow, Decor.FrostShimmer)),
        // --- Style ---
        AtcTheme("ticker", "📰", Category.Style, c(0xea80fc), Bg.Solid(c(0x111111)), Border(1f, c(0x333333))),
        AtcTheme("neon", "⚡", Category.Style, c(0xea80fc), Bg.Solid(c(0x0a0a0f)),
            Border(1f, rgba(0, 255, 255, 0.25f)), glow = Glow(rgba(0, 255, 255, 0.1f), 12f),
            badge = c(0x00ffff), badgeGlow = true, title = c(0xe0e0ff), iconRem = 2f, iconGlow = c(0x00ffff), decor = listOf(Decor.NeonScan)),
        AtcTheme("glass", "🔮", Category.Style, c(0x40c4ff),
            Bg.Linear(135f, listOf(rgba(102, 126, 234, 0.4f), rgba(118, 75, 162, 0.4f), rgba(246, 79, 89, 0.35f))),
            Border(1f, rgba(255, 255, 255, 0.22f)), badge = rgba(255, 255, 255, 0.6f), title = rgba(255, 255, 255, 0.95f),
            iconBox = IconBox(44f, IconBoxShape.Rounded, 12f, listOf(rgba(255, 255, 255, 0.15f)), rgba(255, 255, 255, 0.2f))),
        AtcTheme("matrix", "💻", Category.Style, c(0x00ff41), Bg.Solid(Color.Black),
            Border(1f, c(0x00ff41)), glow = Glow(rgba(0, 255, 65, 0.15f), 15f), badge = c(0x00ff41), title = c(0x00ff41),
            titleWeight = 700, iconRem = 2f, mono = true),
        AtcTheme("minimal", "📋", Category.Style, c(0xcfd8dc), Bg.Solid(c(0xf8f9fa)),
            Border(1f, rgba(0, 0, 0, 0.06f), left = 5f, sideColor = c(0xe53935)), badge = c(0xe53935), title = c(0x212121),
            iconRem = 2f, light = true),
        AtcTheme("retro", "📺", Category.Style, c(0xff6d00), Bg.Solid(c(0x080600)),
            Border(2f, c(0xe65100)), radius = 8f,
            glow = Glow(rgba(230, 81, 0, 0.25f), 18f, rgba(230, 81, 0, 0.5f), 36f, 4000),
            badge = c(0xff8f00), badgeGlow = true, title = c(0xffe0b2), mono = true, iconRem = 2f, iconGlow = c(0xff8f00),
            decor = listOf(Decor.Scanlines)),
        AtcTheme("cyberpunk", "🤖", Category.Style, c(0xff00ff), lin(135f, 0x0a001a, 0x130025),
            Border(1f, c(0x9c27b0)), radius = 12f,
            glow = Glow(rgba(156, 39, 176, 0.45f), 22f, rgba(0, 229, 255, 0.45f), 42f, 2000, border2 = c(0x00e5ff)),
            badge = c(0xe040fb), badgeGlow = true, title = c(0xede0ff), iconAnim = IconAnim.Glow, iconGlow = c(0x9c27b0),
            decor = listOf(Decor.CyberLines, Decor.CyberGlitch)),
        AtcTheme("vapor", "🌸", Category.Style, c(0xff80ab), lin(160f, 0x0a0015, 0x1a0030, 0x0d0025),
            Border(1f, rgba(255, 0, 255, 0.4f)), radius = 12f, badge = c(0xff80ff), badgeGlow = true, title = c(0xf0e0ff),
            iconAnim = IconAnim.Bob, iconGlow = rgba(255, 0, 255, 0.8f), decor = listOf(Decor.VaporPulse, Decor.VaporGrid)),
        AtcTheme("lava", "🌋", Category.Style, c(0xff6d00), Bg.Radial(listOf(c(0x120500), c(0x050000))),
            Border(1f, rgba(255, 87, 34, 0.5f)), radius = 12f, glow = Glow(rgba(255, 87, 34, 0.1f), 22f),
            badge = c(0xff6d00), title = c(0xfff3e0), iconAnim = IconAnim.Glow, iconGlow = rgba(255, 87, 34, 0.8f), decor = listOf(Decor.Lava)),
        // --- Minuteries ---
        AtcTheme("countdown", "⏱️", Category.Timer, c(0x18ffff), Bg.Solid(c(0x1e1e2e)), titleRem = 0.95f, badgeRem = 0.65f, iconRem = 2f),
        AtcTheme("hourglass", "⏳", Category.Timer, c(0x18ffff), Bg.Solid(c(0x1e1e2e)), titleRem = 0.95f, badgeRem = 0.65f, iconRem = 2f),
        AtcTheme("timer_pulse", "💥", Category.Timer, c(0xff4444), Bg.Solid(c(0x1e1e2e)), titleRem = 0.95f, badgeRem = 0.65f, iconRem = 2f),
        AtcTheme("timer_ring", "🔵", Category.Timer, c(0x18ffff), Bg.Solid(c(0x1e1e2e)), titleRem = 0.95f, badgeRem = 0.65f, iconRem = 2f),
    )

    private val byId = list.associateBy { it.id }
    val all: List<AtcTheme> get() = list
    operator fun get(id: String?): AtcTheme = byId[id?.lowercase()] ?: byId.getValue("emergency")
    fun exists(id: String?) = id != null && byId.containsKey(id.lowercase())

    /** Couleur d'accent par catégorie (`_getAccentColor`). */
    fun accentFor(id: String?): Color = when (this[id].category) {
        Category.Critical -> c(0xe53935)
        Category.Warning -> c(0xff9800)
        Category.Info -> c(0x29b6f6)
        Category.Ok -> c(0x4caf50)
        else -> c(0x9e9e9e)
    }
}

/** Dégradé CSS `linear-gradient(<angle>deg, …)` adapté à une zone w×h. */
fun Bg.brush(w: Float, h: Float): Brush = when (this) {
    is Bg.Solid -> Brush.linearGradient(listOf(color, color))
    is Bg.Radial -> Brush.radialGradient(colors, center = Offset(w * cx, h * cy), radius = maxOf(w, h) * 0.6f)
    is Bg.Linear -> {
        // CSS : 0deg = vers le haut, 90deg = vers la droite ; la ligne passe par le centre.
        val rad = (angle - 90f) * PI.toFloat() / 180f
        val dx = cos(rad)
        val dy = sin(rad)
        val half = (kotlin.math.abs(w * dx) + kotlin.math.abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        Brush.linearGradient(colors, Offset(cx - dx * half, cy - dy * half), Offset(cx + dx * half, cy + dy * half))
    }
}
