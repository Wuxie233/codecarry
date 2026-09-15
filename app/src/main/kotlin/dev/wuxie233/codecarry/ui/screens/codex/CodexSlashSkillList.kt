package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexSkill

/** Only a leading slash token is a picker query; ordinary prose and paths stay intact. */
internal fun codexSlashSkillQuery(draft: String): String? =
    draft.takeIf { it.startsWith("/") && it.drop(1).none { char -> char.isWhitespace() || char == '/' } }
        ?.drop(1)

internal fun matchingCodexSkills(skills: List<CodexSkill>, query: String): List<CodexSkill> =
    skills.filter { it.enabled && (it.name.contains(query, ignoreCase = true) ||
        it.description.contains(query, ignoreCase = true) ||
        it.shortDescription?.contains(query, ignoreCase = true) == true) }
        .distinctBy { it.path }

@Composable
internal fun CodexSlashSkillList(
    query: String,
    skills: List<CodexSkill>,
    loading: Boolean,
    error: String?,
    enabled: Boolean,
    onRetry: () -> Unit,
    onSelect: (CodexSkill) -> Unit,
) {
    val matches = matchingCodexSkills(skills, query)
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
            item {
                Text(stringResource(R.string.codex_attachment_skills),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
                    TextButton(onClick = onRetry, enabled = enabled && !loading) {
                        Text(stringResource(R.string.retry))
                    }
                }
                if (!loading && error == null && matches.isEmpty()) {
                    Text(stringResource(R.string.codex_attachment_empty), modifier = Modifier.padding(12.dp))
                }
            }
            items(matches, key = { it.path }) { skill ->
                TextButton(onClick = { onSelect(skill) }, enabled = enabled && !loading,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(skill.name)
                        Text(skill.shortDescription ?: skill.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
