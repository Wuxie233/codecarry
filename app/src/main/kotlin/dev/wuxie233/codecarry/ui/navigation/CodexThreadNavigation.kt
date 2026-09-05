package dev.wuxie233.codecarry.ui.navigation

import androidx.navigation.NavController

internal fun NavController.openCodexRelatedThread(
    serverId: String,
    currentThreadId: String,
    targetThreadId: String,
) {
    if (targetThreadId.isBlank() || targetThreadId == currentThreadId) return
    // Related threads share a destination, but need independent SavedStateHandle/ViewModel
    // owners. Single-top would reuse the parent's owner with its original thread ID.
    navigate(Screen.CodexChat.createRoute(serverId, targetThreadId))
}
