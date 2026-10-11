package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexAsyncQuestion

/** A source item's questions form one batch, matching the ordinary question-card grammar. */
@Composable
internal fun CodexAsyncQuestionCard(
    questions: List<CodexAsyncQuestion>,
    enabled: Boolean,
    submitting: Boolean,
    error: String?,
    onAnswer: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (questions.isEmpty()) return
    val key = questions.joinToString("|") { "${it.turnId}:${it.id}" }
    var drafts by rememberSaveable(key) { mutableStateOf(ArrayList(List(questions.size) { "" })) }
    var custom by rememberSaveable(key) { mutableStateOf(ArrayList(List(questions.size) { "" })) }
    val canEdit = enabled && !submitting
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                questions.joinToString("\n") { it.title },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.codex_async_question_title))
            }
        }
    }
    if (!expanded) return
    Dialog(
        onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.codex_async_question_title),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        QuestionForm(questions, canEdit, submitting, error, drafts, custom,
                            onDrafts = { drafts = it }, onCustom = { custom = it }, onAnswer = onAnswer)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionForm(
    questions: List<CodexAsyncQuestion>,
    canEdit: Boolean,
    submitting: Boolean,
    error: String?,
    drafts: ArrayList<String>,
    custom: ArrayList<String>,
    onDrafts: (ArrayList<String>) -> Unit,
    onCustom: (ArrayList<String>) -> Unit,
    onAnswer: (Map<String, String>) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            questions.forEachIndexed { index, question ->
                Text(question.title, style = MaterialTheme.typography.bodyMedium)
                when {
                    question.answer != null -> Text(
                        stringResource(R.string.codex_async_answered, question.answer),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    !question.canAnswer -> Text(
                        stringResource(R.string.codex_async_question_expired),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {
                        question.options.forEach { option ->
                            val selected = drafts[index] == option && custom[index].isEmpty()
                            Surface(
                                onClick = {
                                    onDrafts(ArrayList(drafts).apply { set(index, option) })
                                    onCustom(ArrayList(custom).apply { set(index, "") })
                                    if (questions.size == 1) onAnswer(mapOf(question.id to option))
                                },
                                enabled = canEdit,
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected, onClick = null, enabled = canEdit)
                                    Text(option, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = custom[index],
                            onValueChange = { value ->
                                onCustom(ArrayList(custom).apply { set(index, value) })
                                onDrafts(ArrayList(drafts).apply { set(index, value) })
                            },
                            enabled = canEdit,
                            label = { Text(stringResource(R.string.question_custom_answer)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 4,
                        )
                    }
                }
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val pending = questions.withIndex().filter { it.value.canAnswer }
            if (pending.isNotEmpty() && (questions.size > 1 || questions.single().options.isEmpty() || custom.single().isNotEmpty())) {
                Button(
                    onClick = { onAnswer(pending.associate { it.value.id to drafts[it.index] }) },
                    enabled = canEdit && pending.all { drafts[it.index].isNotBlank() },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(if (submitting) R.string.codex_async_answer_sending else R.string.question_submit))
                }
            }
        }
    }
}
