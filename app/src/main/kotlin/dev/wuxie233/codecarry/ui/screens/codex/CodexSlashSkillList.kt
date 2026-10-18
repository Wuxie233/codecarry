package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.data.codex.CodexSkill
import dev.wuxie233.codecarry.ui.components.SkillCandidatePanel
import dev.wuxie233.codecarry.ui.components.skillMatchRank

internal fun matchingCodexSkills(skills: List<CodexSkill>, query: String): List<CodexSkill> =
    skills.asSequence()
        .filter { it.enabled }
        .mapNotNull { skill ->
            skillMatchRank(skill.name, skill.description, skill.shortDescription, query)?.let { skill to it }
        }
        .distinctBy { it.first.path }
        .sortedBy { it.second }
        .map { it.first }
        .toList()

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
    SkillCandidatePanel(
        title = stringResource(R.string.codex_attachment_skills),
        candidates = matchingCodexSkills(skills, query),
        key = { it.path }, name = { it.name }, description = { it.shortDescription ?: it.description },
        loading = loading, error = error, enabled = enabled,
        emptyText = stringResource(R.string.codex_attachment_empty),
        retryText = stringResource(R.string.retry), onRetry = onRetry, onSelect = onSelect,
    )
}
