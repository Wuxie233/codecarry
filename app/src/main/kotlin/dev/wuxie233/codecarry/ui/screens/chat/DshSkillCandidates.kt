package dev.wuxie233.codecarry.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.dsh.DshSkillEntry
import dev.wuxie233.codecarry.data.dsh.DshCommandDescriptor
import dev.wuxie233.codecarry.ui.components.SelectedSkillChip
import dev.wuxie233.codecarry.ui.components.SkillCandidatePanel
import dev.wuxie233.codecarry.ui.components.SkillCompletionTarget
import dev.wuxie233.codecarry.ui.components.skillCompletionTarget
import dev.wuxie233.codecarry.ui.components.skillMatchRank

internal data class DshSkillTarget(val start: Int, val end: Int, val query: String)

internal fun dshSkillTarget(value: TextFieldValue): DshSkillTarget? = skillCompletionTarget(value)?.let {
    DshSkillTarget(it.range.start, it.range.end, it.query)
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
        else skills.mapNotNull { skill ->
            skillMatchRank(skill.name, skill.description, skill.whenToUse, query)?.let { skill to it }
        }.distinctBy { it.first.name }.sortedBy { it.second }.map { it.first }
}

/** The chip owns the selected skill; consume only the query and keep the surrounding draft intact. */
internal fun consumeDshSkillQuery(
    value: TextFieldValue,
    target: DshSkillTarget,
    skill: DshSkillEntry,
    state: DshSkillCandidateState,
): TextFieldValue? {
    if (dshSkillTarget(value) != target || skill !in state.matching(target.query)) return null
    return SkillCompletionTarget(target.query, TextRange(target.start, target.end)).consume(value)
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

internal val LocalChatConfirmationGate = staticCompositionLocalOf<ChatConfirmationGate> {
    error("ChatConfirmationGate must be provided by the chat owner")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DshSelectedSkillChips(names: List<String>, enabled: Boolean, onRemove: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { name ->
            key(name) {
                SelectedSkillChip(name, enabled,
                    stringResource(R.string.dsh_skill_remove, name), { onRemove(name) },
                    modifier = Modifier.testTag("dsh_skill:$name"),
                    removeModifier = Modifier.testTag("dsh_skill_remove:$name"))
            }
        }
    }
}

/** Uses the Codex composer's inline panel; the ViewModel owns selection, catalog and send state. */
@Composable
internal fun DshSkillCandidates(
    value: TextFieldValue,
    state: DshSkillCandidateState,
    onChange: (TextFieldValue) -> Unit,
    onRetry: () -> Unit,
    onCompact: () -> Unit = {},
    onSelectSkill: (DshSkillEntry) -> Boolean,
    enabled: Boolean = true,
) {
    val gate = LocalChatConfirmationGate.current
    DisposableEffect(gate) { onDispose { gate.release("compact") } }
    val target = dshSkillTarget(value)
    var dismissed by remember(value) { mutableStateOf(false) }
    val currentValue by rememberUpdatedState(value)
    val currentState by rememberUpdatedState(state)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnSelect by rememberUpdatedState(onSelectSkill)
    val visible = state.available && target != null && !dismissed && gate.owner == null
    val acceptingSelection by rememberUpdatedState(visible && enabled)
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
            }, enabled = enabled) { Text(stringResource(R.string.dsh_compact_confirm)) } },
            dismissButton = { TextButton(onClick = { gate.release("compact") }) {
                Text(stringResource(R.string.cancel))
            } },
        )
    }
    BackHandler(visible) { dismissed = true }
    if (!visible) return
    val currentTarget = target ?: return
    val compactAction: (@Composable () -> Unit)? = if (state.matchesCompact(currentTarget.query)) {
        {
            TextButton(onClick = {
                if (acceptingSelection && !dismissed &&
                    dshSkillTarget(currentValue) == currentTarget &&
                    currentState.matchesCompact(currentTarget.query) && gate.acquire("compact")) {
                    dismissed = true
                }
            }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("/compact")
                    Text(stringResource(R.string.dsh_compact_description), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } else null
    SkillCandidatePanel(
        title = stringResource(R.string.dsh_skills_title),
        candidates = state.matching(currentTarget.query),
        key = { "skill:${it.name}" }, name = { it.name }, description = { it.description },
        loading = state.loading, error = if (state.failed) stringResource(R.string.dsh_skills_failed) else null,
        enabled = enabled, emptyText = stringResource(R.string.dsh_skills_no_match),
        retryText = stringResource(R.string.dsh_skills_retry), onRetry = onRetry,
        onSelect = { skill ->
            if (acceptingSelection && !dismissed && gate.owner == null) {
                consumeDshSkillQuery(currentValue, currentTarget, skill, currentState)?.let { consumed ->
                    if (currentOnSelect(skill)) {
                        dismissed = true
                        currentOnChange(consumed)
                    }
                }
            }
        },
        leadingAction = compactAction,
    )
}
