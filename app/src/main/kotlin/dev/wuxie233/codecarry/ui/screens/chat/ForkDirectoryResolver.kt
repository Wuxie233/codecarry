package dev.wuxie233.codecarry.ui.screens.chat

import dev.wuxie233.codecarry.domain.model.Session

/**
 * Resolves the directory context that should be sent on a fork-session request.
 *
 * The chain mirrors [ChatViewModel.executeCommand]'s fallback so that fork and
 * command execution use the same project-context behaviour:
 *
 *   1. The in-memory [sessionDirectory] populated by `loadSession()`.
 *   2. The directory of the matching session in the reducer snapshot (covers the
 *      case where `loadSession()` failed but SSE has already delivered the session).
 *   3. `null` — explicit, deliberate fallback. Caller is expected to log a warning
 *      and let the server fall back to its own default project context.
 *
 * Pure function — no Android, no coroutines, no side effects. Testable in isolation.
 */
object ForkDirectoryResolver {

    /**
     * @param sessionDirectory current in-memory directory loaded from the source session
     * @param sessionId        id of the source session being forked
     * @param reducerSessions  current snapshot of `eventReducer.sessions.value`
     * @return the directory string to attach to the fork request, or `null` to send no header
     */
    fun resolve(
        sessionDirectory: String?,
        sessionId: String,
        reducerSessions: List<Session>,
    ): String? {
        sessionDirectory?.takeIf { it.isNotBlank() }?.let { return it }
        return reducerSessions
            .firstOrNull { it.id == sessionId }
            ?.directory
            ?.takeIf { it.isNotBlank() }
    }
}
