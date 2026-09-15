package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal data class CodexSkillCompletionTarget(val query: String, val range: TextRange) {
    fun consume(value: TextFieldValue): TextFieldValue = TextFieldValue(
        text = value.text.removeRange(range.start, range.end),
        selection = TextRange(range.start),
    )
}

/** Resolve only the slash token being edited, never a URL or a multi-segment path. */
internal fun codexSkillCompletionTarget(value: TextFieldValue): CodexSkillCompletionTarget? {
    if (!value.selection.collapsed) return null
    val text = value.text
    val cursor = value.selection.start
    var start = cursor
    while (start > 0 && text[start - 1].isSkillQueryCharacter()) start--
    if (start == 0 || text[start - 1] != '/') return null
    start--
    val previous = text.getOrNull(start - 1)
    // ASCII words and path/URL prefixes are not invocation boundaries. Chinese
    // prose and punctuation may directly precede a skill query without a space.
    if (previous != null && (previous in 'a'..'z' || previous in 'A'..'Z' ||
            previous in '0'..'9' || previous in "_/\\.:~@-")) return null
    var end = cursor
    while (end < text.length && text[end].isSkillQueryCharacter()) end++
    if (text.getOrNull(end) in listOf('/', '\\', '.')) return null
    // Allow an IME composing the query itself; an unrelated composition must
    // not be cancelled by picking a skill.
    value.composition?.let {
        if (it.min < start || it.max > end) return null
    }
    return CodexSkillCompletionTarget(text.substring(start + 1, cursor), TextRange(start, end))
}

private fun Char.isSkillQueryCharacter(): Boolean = isLetterOrDigit() || this in "-_:"
