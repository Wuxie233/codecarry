package dev.wuxie233.codecarry.ui.screens.chat

internal data class ChatFollowTailState(
    val isFollowing: Boolean = true,
    val hasNewContent: Boolean = false,
) {
    val showAffordance: Boolean
        get() = !isFollowing
}

internal data class ChatFollowTailTransition(
    val state: ChatFollowTailState,
    val scrollToTail: Boolean = false,
)

internal object ChatFollowTailPolicy {
    fun onViewportChanged(
        state: ChatFollowTailState,
        isAtTail: Boolean,
        isUserScrollInProgress: Boolean,
    ): ChatFollowTailState = when {
        isAtTail -> ChatFollowTailState()
        isUserScrollInProgress -> state.copy(isFollowing = false)
        else -> state
    }

    fun onContentChanged(
        state: ChatFollowTailState,
        hasContent: Boolean,
    ): ChatFollowTailTransition = when {
        !hasContent -> ChatFollowTailTransition(state)
        state.isFollowing -> ChatFollowTailTransition(state, scrollToTail = true)
        else -> ChatFollowTailTransition(state.copy(hasNewContent = true))
    }

    fun onManualNavigation(state: ChatFollowTailState): ChatFollowTailState =
        state.copy(isFollowing = false)

    fun onReturnToTail(): ChatFollowTailTransition =
        ChatFollowTailTransition(ChatFollowTailState(), scrollToTail = true)
}
