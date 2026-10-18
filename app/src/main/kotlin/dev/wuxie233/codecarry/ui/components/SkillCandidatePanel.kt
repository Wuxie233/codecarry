package dev.wuxie233.codecarry.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/** Shared search ordering from the Codex composer. Identity and eligibility belong to each backend. */
internal fun skillMatchRank(name: String, description: String, shortDescription: String?, query: String): Int? {
    if (query.isEmpty() || name.equals(query, ignoreCase = true)) return 0
    if (name.startsWith(query, ignoreCase = true)) return 1
    if (name.contains(query, ignoreCase = true)) return 2
    var matched = 0
    for (char in name) {
        if (matched < query.length && char.equals(query[matched], ignoreCase = true)) matched++
    }
    if (matched == query.length) return 3
    if (description.contains(query, ignoreCase = true) || shortDescription?.contains(query, ignoreCase = true) == true) return 4
    return null
}

internal data class SkillCompletionTarget(val query: String, val range: TextRange) {
    fun consume(value: TextFieldValue): TextFieldValue = TextFieldValue(
        text = value.text.removeRange(range.start, range.end),
        selection = TextRange(range.start),
    )
}

/** Resolve the query at the cursor without treating URLs, paths, or unrelated IME text as skills. */
internal fun skillCompletionTarget(value: TextFieldValue): SkillCompletionTarget? {
    if (!value.selection.collapsed) return null
    val text = value.text
    val cursor = value.selection.start
    var start = cursor
    while (start > 0 && text[start - 1].isSkillQueryCharacter()) start--
    if (start == 0 || text[start - 1] != '/') return null
    start--
    val previous = text.getOrNull(start - 1)
    if (previous != null && (previous in 'a'..'z' || previous in 'A'..'Z' ||
            previous in '0'..'9' || previous in "_/\\.:~@-")) return null
    var end = cursor
    while (end < text.length && text[end].isSkillQueryCharacter()) end++
    if (text.getOrNull(end) in listOf('/', '\\', '.')) return null
    value.composition?.let {
        if (it.min < start || it.max > end) return null
    }
    return SkillCompletionTarget(text.substring(start + 1, cursor), TextRange(start, end))
}

private fun Char.isSkillQueryCharacter(): Boolean = isLetterOrDigit() || this in "-_:"

/** The same inline, composer-width panel for both native control surfaces; no transport assumptions. */
@Composable
internal fun <T> SkillCandidatePanel(
    title: String,
    candidates: List<T>,
    key: (T) -> String,
    name: (T) -> String,
    description: (T) -> String,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    emptyText: String,
    retryText: String,
    onRetry: () -> Unit,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    leadingAction: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier.fillMaxWidth().testTag("skill-candidate-panel"),
        shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            item(key = "header") {
                Text(title, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp))
                    TextButton(onClick = onRetry, enabled = enabled && !loading) { Text(retryText) }
                }
                if (!loading && error == null && candidates.isEmpty() && leadingAction == null) {
                    Text(emptyText, modifier = Modifier.padding(12.dp))
                }
            }
            if (leadingAction != null) item(key = "leading-action") { leadingAction() }
            items(candidates, key = key) { candidate ->
                TextButton(onClick = { onSelect(candidate) }, enabled = enabled && !loading,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(name(candidate))
                        Text(description(candidate), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Mirrors the existing Codex non-image attachment chip without importing its wire input type. */
@Composable
internal fun SelectedSkillChip(
    label: String,
    enabled: Boolean,
    removeDescription: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    removeModifier: Modifier = Modifier,
) {
    InputChip(selected = false, enabled = enabled, onClick = onRemove,
        modifier = modifier,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onRemove, enabled = enabled, modifier = removeModifier.size(48.dp)) {
                Icon(Icons.Default.Close, removeDescription)
            }
        })
}
