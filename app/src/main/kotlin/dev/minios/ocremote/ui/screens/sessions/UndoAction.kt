package dev.minios.ocremote.ui.screens.sessions

/**
 * Snackbar event emitted from [SessionListViewModel] after an archive / restore /
 * failure occurs. The screen collects these from `viewModel.undoState` and shows a
 * Snackbar; for [Archive] and [Restore] the snackbar exposes an Undo action that
 * calls the inverse VM method. [Failure] shows a non-actionable snackbar.
 */
internal sealed interface UndoAction {
    val sessionId: String?

    data class Archive(
        override val sessionId: String,
        val title: String,
    ) : UndoAction

    data class Restore(
        override val sessionId: String,
        val title: String,
    ) : UndoAction

    data class Failure(
        val messageResId: Int,
        override val sessionId: String? = null,
    ) : UndoAction
}
