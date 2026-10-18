package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.ui.text.input.TextFieldValue
import dev.wuxie233.codecarry.ui.components.SkillCompletionTarget
import dev.wuxie233.codecarry.ui.components.skillCompletionTarget

internal typealias CodexSkillCompletionTarget = SkillCompletionTarget

internal fun codexSkillCompletionTarget(value: TextFieldValue): CodexSkillCompletionTarget? =
    skillCompletionTarget(value)
