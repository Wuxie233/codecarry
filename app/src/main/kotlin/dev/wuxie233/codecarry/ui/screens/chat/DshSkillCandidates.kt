package dev.wuxie233.codecarry.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import dev.wuxie233.codecarry.data.dsh.DshCommandDescriptor

internal data class DshSkillTarget(val start: Int, val end: Int, val query: String)

internal fun dshSkillTarget(value: TextFieldValue): DshSkillTarget? {
    if (value.composition != null || !value.selection.collapsed) return null
    val cursor = value.selection.start
    val text = value.text
    val start = text.lastIndexOf('/', (cursor - 1).coerceAtLeast(0))
    if (start < 0 || start >= cursor || (start > 0 && !text[start - 1].isWhitespace())) return null
    fun Char.isName() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-'
    val query = text.substring(start + 1, cursor)
    if (!query.all { it.isName() }) return null
    var end = cursor
    while (end < text.length && text[end].isName()) end++
    if (end < text.length && !text[end].isWhitespace()) return null
    return DshSkillTarget(start, end, query)
}

data class DshSkillCandidateState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val skills: List<DshSkillEntry> = emptyList(),
    val commands: List<DshCommandDescriptor> = emptyList(),
) {
    fun matchesCompact(query: String): Boolean = available && !loading && !failed &&
        commands.any { it.name == "compact" } && "compact".startsWith(query, ignoreCase = true)

    fun matching(query: String): List<DshSkillEntry> =
        if (!available || loading || failed) emptyList()
        else skills.filter { it.name.startsWith(query, ignoreCase = true) }
}

internal fun insertDshSkill(
    value: TextFieldValue,
    target: DshSkillTarget,
    skill: DshSkillEntry,
    state: DshSkillCandidateState,
): TextFieldValue? {
    if (dshSkillTarget(value) != target || skill !in state.matching(target.query)) return null
    val inserted = "/${skill.name} "
    return TextFieldValue(value.text.replaceRange(target.start, target.end, inserted), TextRange(target.start + inserted.length))
}

/** Shared by compact and message rewind confirmations in one chat. */
@Stable
internal class ChatConfirmationGate {
    var owner: String? by mutableStateOf(null)
        private set
    fun acquire(value: String): Boolean {
        if (owner != null) return false
        owner = value
        return true
    }
    fun release(value: String) { if (owner == value) owner = null }
}

internal val LocalChatConfirmationGate = staticCompositionLocalOf<ChatConfirmationGate?> { null }

/** Presentation only: the ViewModel owns the session catalog and its request lifetime. */
@Composable
internal fun DshSkillCandidates(
    value: TextFieldValue,
    state: DshSkillCandidateState,
    onChange: (TextFieldValue) -> Unit,
    onRetry: () -> Unit,
    onCompact: () -> Unit = {},
) {
    val gate = LocalChatConfirmationGate.current ?: remember { ChatConfirmationGate() }
    DisposableEffect(gate) { onDispose { gate.release("compact") } }
    val target = dshSkillTarget(value)
    var dismissed by remember(value) { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val currentState by rememberUpdatedState(state)
    val currentOnChange by rememberUpdatedState(onChange)
    val visible = state.available && target != null && !dismissed && gate.owner == null
    if (gate.owner == "compact") {
        AlertDialog(
            onDismissRequest = { gate.release("compact") },
            title = { Text(stringResource(R.string.dsh_compact_title)) },
            text = { Text(stringResource(R.string.dsh_compact_body)) },
            confirmButton = { TextButton(onClick = {
                if (gate.owner == "compact") {
                    gate.release("compact")
                    onCompact()
                }
            }) { Text(stringResource(R.string.dsh_compact_confirm)) } },
            dismissButton = { TextButton(onClick = { gate.release("compact") }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }
    val acceptingSelection by rememberUpdatedState(visible)
    BackHandler(visible) { dismissed = true }
    if (!visible || target == null) return
    Box(Modifier.fillMaxWidth()) {
        Popup(
            alignment = Alignment.BottomStart,
            onDismissRequest = { dismissed = true },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        ) {
            Surface(tonalElevation = 4.dp, shadowElevation = 4.dp) {
                Column(Modifier.widthIn(min = 240.dp, max = 400.dp).heightIn(max = 240.dp).padding(8.dp)) {
                    when {
                        state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.dsh_skills_loading), Modifier.padding(8.dp))
                        }
                        state.failed -> {
                            Text(stringResource(R.string.dsh_skills_failed))
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.dsh_skills_retry)) }
                        }
                        state.matching(target.query).isEmpty() && !state.matchesCompact(target.query) -> Text(stringResource(R.string.dsh_skills_no_match))
                        else -> LazyColumn {
                            if (state.matchesCompact(target.query)) item(key = "command:compact") {
                                Column(Modifier.fillMaxWidth().clickable {
                                    if (acceptingSelection && !dismissed &&
                                        dshSkillTarget(currentValue) == target &&
                                        currentState.matchesCompact(target.query) && gate.acquire("compact")) {
                                        dismissed = true
                                    }
                                }.padding(8.dp)) {
                                    Text("/compact", style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.dsh_compact_description), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            items(state.matching(target.query), key = { "skill:${it.name}" }) { skill ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    if (acceptingSelection && !dismissed && gate.owner == null) insertDshSkill(currentValue, target, skill, currentState)?.let {
                                        dismissed = true
                                        currentOnChange(it)
                                    }
                                }.padding(8.dp)) {
                                    Text(skill.name, style = MaterialTheme.typography.titleSmall)
                                    Text(skill.description, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
