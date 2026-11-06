package fr.pergens.hamacs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.pergens.hamacs.App
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.s
import fr.pergens.hamacs.ui.components.HaIcon
import fr.pergens.hamacs.ui.theme.Dash
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val Muted = Color(0xFFA3A3AD)
private val ErrorColor = Color(0xFFFF6B6B)
private val SuccessColor = Color(0xFF4CD964)

/**
 * Vérifie un code actuel saisi dans un formulaire avec le même compteur d'erreurs que le pavé de code :
 * renvoie le message d'erreur (blocage ou code incorrect), ou null si le code est bon.
 */
fun checkCurrentCode(entered: String, expected: String, wrong: String): String? {
    val config = App.instance.config
    fun lockedMessage(until: Long): String {
        val s = ((until - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(1)
        return App.instance.getString(R.string.pin_locked, "%d:%02d".format(s / 60, s % 60))
    }
    val until = config.pinLockedUntil()
    if (until > 0L) return lockedMessage(until)
    if (entered == expected) { config.pinSucceeded(); return null }
    config.pinFailed()
    val next = config.pinLockedUntil()
    return if (next > 0L) lockedMessage(next) else wrong
}

private val digits = { s: String -> s.filter { it.isDigit() }.take(8) }

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), onDone: (() -> Unit)? = null) {
    // Touche du clavier : "Suivant" passe au champ suivant, "OK" (dernier champ) valide et ferme le clavier.
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value, { onChange(digits(it)) }, modifier, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke(); focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Dash.colors.accent),
    )
}

@Composable
private fun SheetButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val c = Dash.colors.accent
    val focus = LocalFocusManager.current
    Text(
        text,
        Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (primary) (if (enabled) c else c.copy(alpha = 0.3f)) else Color.Transparent, shape)
            .border(1.dp, if (primary) Color.Transparent else c.copy(alpha = if (enabled) 1f else 0.3f), shape)
            .then(if (enabled) Modifier.clickable { focus.clearFocus(); onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (primary) Color.Black else c.copy(alpha = if (enabled) 1f else 0.3f)),
    )
}

/**
 * Code propre à un membre (fiche du membre en mode édition) : le définir, le changer (code actuel demandé) ou le
 * retirer ; sans code propre, c'est le code d'accès qui est demandé.
 */
@Composable
fun MemberCodeEditor(memberId: String) {
    val config = App.instance.config
    var defined by remember(memberId) { mutableStateOf(config.memberCode(memberId) != null) }
    var current by remember(memberId) { mutableStateOf("") }
    var new1 by remember(memberId) { mutableStateOf("") }
    var new2 by remember(memberId) { mutableStateOf("") }
    var message by remember(memberId) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val wrong = stringResource(R.string.access_code_wrong)
    val invalid = stringResource(R.string.access_code_invalid)
    val mismatch = stringResource(R.string.access_code_mismatch)
    val saved = stringResource(R.string.access_code_saved)
    val removed = stringResource(R.string.member_code_removed)
    val currentError = { if (!defined) null else checkCurrentCode(current, config.memberCode(memberId).orEmpty(), wrong)?.also { current = "" } }

    Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (defined) R.string.member_code_defined else R.string.member_code_none),
            style = TextStyle(fontSize = 13.sp, color = Muted),
        )
        val canSave = (!defined || current.isNotEmpty()) && new1.isNotEmpty() && new2.isNotEmpty()
        val save = {
            val error = currentError()
            message = when {
                error != null -> error to true
                new1.length !in 4..8 -> invalid to true
                new1 != new2 -> mismatch to true
                else -> {
                    config.setMemberCode(memberId, new1)
                    defined = true; current = ""; new1 = ""; new2 = ""
                    saved to false
                }
            }
        }
        if (defined) CodeField(current, { current = it; message = null }, stringResource(R.string.access_code_current))
        CodeField(new1, { new1 = it; message = null }, stringResource(R.string.access_code_new))
        CodeField(new2, { new2 = it; message = null }, stringResource(R.string.access_code_confirm), onDone = { if (canSave) save() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetButton(stringResource(R.string.master_code_save), primary = true, enabled = canSave) { save() }
            if (defined) SheetButton(stringResource(R.string.master_code_remove), primary = false, enabled = current.isNotEmpty()) {
                val error = currentError()
                message = if (error != null) error to true else {
                    config.setMemberCode(memberId, null)
                    defined = false; current = ""; new1 = ""; new2 = ""
                    removed to false
                }
            }
            message?.let { (text, isError) -> Text(text, style = TextStyle(fontSize = 13.sp, color = if (isError) ErrorColor else SuccessColor)) }
        }
    }
}

/** Nom affiché d'un membre (personne Home Assistant liée), pour les libellés de codes. */
private fun memberName(id: String): String {
    val app = App.instance
    fun find(e: JsonElement): String? = when (e) {
        is JsonObject -> if (e.s("id") == id && (e["person"] != null || e["name"] != null)) (e.s("name") ?: e.s("person")) else e.values.firstNotNullOfOrNull { find(it) }
        is JsonArray -> e.firstNotNullOfOrNull { find(it) }
        else -> null
    }
    val ref = find(app.config.rawDashboard.value)
    val person = ref?.takeIf { it.startsWith("person.") } ?: "person.$id"
    return ref?.takeIf { !it.startsWith("person.") } ?: app.ha.states.value[person]?.name ?: id.replaceFirstChar { it.uppercase() }
}

@Composable
private fun codeLabel(key: String): String = when {
    key == "access_code" -> stringResource(R.string.access_code)
    key == "master_code" -> stringResource(R.string.master_code)
    else -> stringResource(R.string.member_code_of, memberName(key.removePrefix("member_code:")))
}

/**
 * Après l'import d'une sauvegarde d'une autre tablette, ses codes (chiffrés pour elle) sont illisibles : un nouveau code
 * est demandé pour chaque code différent, sans possibilité de passer (aucun code connu par défaut n'est mis à la place).
 */
@Composable
fun CodeReplaceDialog() {
    val config = App.instance.config
    val groups by config.codesToReplace.collectAsState()
    val group = groups.firstOrNull() ?: return
    var new1 by remember(group) { mutableStateOf("") }
    var new2 by remember(group) { mutableStateOf("") }
    var error by remember(group) { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.access_code_invalid)
    val mismatch = stringResource(R.string.access_code_mismatch)
    val total = remember { mutableStateOf(0) }
    if (groups.size > total.value) total.value = groups.size
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C1C21)).padding(28.dp).width(520.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaIcon("mdi:shield-key-outline", size = 26.dp, tint = Dash.colors.accent)
                Text(
                    stringResource(R.string.codes_replace_title), Modifier.padding(start = 12.dp).weight(1f),
                    style = TextStyle(fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Medium),
                )
                if (total.value > 1) Text("${total.value - groups.size + 1} / ${total.value}", style = TextStyle(fontSize = 14.sp, color = Muted))
            }
            Text(stringResource(R.string.codes_replace_help), style = TextStyle(fontSize = 14.sp, color = Muted, lineHeight = 20.sp))
            // Ce que couvre ce code (codes identiques sur l'autre tablette).
            Text(
                group.map { codeLabel(it) }.joinToString(" · "),
                style = TextStyle(fontSize = 17.sp, color = Color.White, fontWeight = FontWeight.Medium),
            )
            val canSave = new1.isNotEmpty() && new2.isNotEmpty()
            val save = {
                error = when {
                    new1.length !in 4..8 -> invalid
                    new1 != new2 -> mismatch
                    else -> { config.replaceCodes(group, new1); null }
                }
            }
            CodeField(new1, { new1 = it; error = null }, stringResource(R.string.access_code_new))
            CodeField(new2, { new2 = it; error = null }, stringResource(R.string.access_code_confirm), onDone = { if (canSave) save() })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetButton(stringResource(R.string.master_code_save), primary = true, enabled = canSave) { save() }
                error?.let { Text(it, style = TextStyle(fontSize = 13.sp, color = ErrorColor)) }
            }
        }
    }
}
