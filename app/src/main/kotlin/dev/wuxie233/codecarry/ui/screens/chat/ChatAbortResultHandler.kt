package dev.wuxie233.codecarry.ui.screens.chat

internal const val ABORT_FAILED_MESSAGE = "Failed to stop session"

internal sealed class AbortOutcome {
    data object Success : AbortOutcome()
    data object Unsuccessful : AbortOutcome()
    data class Failed(val cause: Throwable) : AbortOutcome()
}

internal fun handleAbortResult(
    outcome: AbortOutcome,
    onIdle: () -> Unit,
    onError: (String) -> Unit
) {
    when (outcome) {
        is AbortOutcome.Success -> onIdle()
        is AbortOutcome.Unsuccessful -> onError(ABORT_FAILED_MESSAGE)
        is AbortOutcome.Failed -> {
            val message = outcome.cause.message
            if (message.isNullOrBlank()) {
                onError(ABORT_FAILED_MESSAGE)
            } else {
                onError(message)
            }
        }
    }
}
