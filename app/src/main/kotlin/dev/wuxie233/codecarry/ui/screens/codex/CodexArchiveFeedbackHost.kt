package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.wuxie233.codecarry.R

/** The ViewModel owns the FIFO; leaving the screen must not consume its head. */
@Composable
internal fun CodexArchiveFeedbackHost(
    feedback: CodexArchiveFeedback?,
    hostState: SnackbarHostState,
    onUndo: (Long) -> Unit,
    onDismiss: (Long) -> Unit,
    onPresent: (Long, Long) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val undo by rememberUpdatedState(onUndo)
    val dismiss by rememberUpdatedState(onDismiss)
    val present by rememberUpdatedState(onPresent)
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = 10_000L, containsIcons = true, containsText = true, containsControls = true,
    ) ?: 10_000L
    val message = feedback?.let { archiveFeedbackMessage(it) }
    val action = if (feedback?.canUndo == true) stringResource(R.string.codex_archive_undo) else null
    LaunchedEffect(feedback?.id, lifecycle, message, action) {
        if (feedback == null || message == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // One VM timer survives navigation and respects accessibility recommendations.
            // Pending feedback starts its window only when it becomes visible.
            present(feedback.id, timeout)
            when (hostState.showSnackbar(message, action, withDismissAction = true, duration = SnackbarDuration.Indefinite)) {
                SnackbarResult.ActionPerformed -> undo(feedback.id)
                SnackbarResult.Dismissed -> dismiss(feedback.id)
            }
        }
    }
    SnackbarHost(hostState)
}

@Composable
private fun archiveFeedbackMessage(feedback: CodexArchiveFeedback): String = buildList {
    if (feedback.archivedCount > 0) add(pluralStringResource(R.plurals.codex_archive_done, feedback.archivedCount, feedback.archivedCount))
    if (feedback.restoredCount > 0) add(pluralStringResource(R.plurals.codex_archive_restored, feedback.restoredCount, feedback.restoredCount))
    if (feedback.failedCount > 0) add(pluralStringResource(
        if (feedback.operation == CodexArchiveOperation.ARCHIVE) R.plurals.codex_archive_failed else R.plurals.codex_archive_restore_failed,
        feedback.failedCount, feedback.failedCount,
    ))
    if (feedback.unknownCount > 0) add(pluralStringResource(R.plurals.codex_archive_unknown, feedback.unknownCount, feedback.unknownCount))
}.joinToString("\n")
