package fr.pergens.hamacs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.pergens.hamacs.ui.theme.Dash

/**
 * Markdown simple des notes de version (Releases GitHub) : titres, puces, paragraphes, gras, code et liens (texte seul).
 * Images et commentaires HTML ignorés ; tout ce qui suit `<!-- whats-new:end -->` (fichiers joints…) n'est pas affiché.
 */
object Markdown {
    const val END = "<!-- whats-new:end -->"

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Para(val text: String) : Block
        data object Gap : Block
    }

    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val comment = Regex("""<!--.*?-->""")

    fun parse(md: String): List<Block> {
        val body = md.substringBefore(END).replace("\r", "")
        val out = mutableListOf<Block>()
        body.lines().forEach { raw ->
            val line = comment.replace(image.replace(raw, ""), "").let { link.replace(it, "$1") }.trimEnd()
            val t = line.trim()
            when {
                t.isEmpty() -> if (out.isNotEmpty() && out.last() != Block.Gap) out += Block.Gap
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length
                    t.drop(level).trim().takeIf { it.isNotEmpty() }?.let { out += Block.Heading(level, it) }
                }
                t.startsWith("- ") || t.startsWith("* ") -> out += Block.Bullet(t.drop(2).trim())
                else -> out += Block.Para(t)
            }
        }
        while (out.lastOrNull() == Block.Gap) out.removeAt(out.lastIndex)
        return out
    }

    /** `**gras**` et `` `code` `` ; le reste tel quel. */
    fun inline(text: String, code: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                    val end = text.indexOf("**", i + 2)
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)); append(text.substring(i + 2, end)); pop()
                    i = end + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) > i + 1 -> {
                    val end = text.indexOf('`', i + 1)
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = code)); append(text.substring(i + 1, end)); pop()
                    i = end + 1
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier, fontSize: Int = 14) {
    val muted = Color(0xFFC8C8D0)
    val accent = Dash.colors.accent
    val body = TextStyle(fontSize = fontSize.sp, color = muted, lineHeight = (fontSize + 6).sp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Markdown.parse(md).forEach { b ->
            when (b) {
                is Markdown.Block.Heading -> Text(
                    Markdown.inline(b.text, accent), Modifier.padding(top = 4.dp),
                    style = TextStyle(fontSize = (if (b.level <= 2) fontSize + 4 else fontSize + 2).sp, color = Color.White, fontWeight = FontWeight.Medium),
                )
                is Markdown.Block.Bullet -> Row {
                    Box(Modifier.height((fontSize + 6).dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(accent))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(Markdown.inline(b.text, accent), style = body)
                }
                is Markdown.Block.Para -> Text(Markdown.inline(b.text, accent), style = body)
                Markdown.Block.Gap -> Spacer(Modifier.height(2.dp))
            }
        }
    }
}
