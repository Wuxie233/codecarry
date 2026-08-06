package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFollowTailPolicyTest {
    @Test
    fun `new content preserves a manually scrolled-up viewport`() {
        val scrolledUp = ChatFollowTailPolicy.onViewportChanged(
            state = ChatFollowTailState(),
            isAtTail = false,
            isUserScrollInProgress = true,
        )

        val firstUpdate = ChatFollowTailPolicy.onContentChanged(scrolledUp, hasContent = true)
        val longStreamingUpdate = ChatFollowTailPolicy.onContentChanged(firstUpdate.state, hasContent = true)

        assertFalse(firstUpdate.scrollToTail)
        assertFalse(longStreamingUpdate.scrollToTail)
        assertFalse(longStreamingUpdate.state.isFollowing)
        assertTrue(longStreamingUpdate.state.hasNewContent)
        assertTrue(longStreamingUpdate.state.showAffordance)
    }

    @Test
    fun `non-message state changes do not create unread content`() {
        val scrolledUp = ChatFollowTailState(isFollowing = false)

        val transition = ChatFollowTailPolicy.onContentChanged(scrolledUp, hasContent = false)

        assertFalse(transition.scrollToTail)
        assertFalse(transition.state.hasNewContent)
        assertTrue(transition.state.showAffordance)
    }

    @Test
    fun `content continues following while the user remains at the tail`() {
        val transition = ChatFollowTailPolicy.onContentChanged(
            state = ChatFollowTailState(),
            hasContent = true,
        )

        assertTrue(transition.scrollToTail)
        assertTrue(transition.state.isFollowing)
        assertFalse(transition.state.hasNewContent)
    }

    @Test
    fun `new-content affordance returns to tail and resumes following`() {
        val waiting = ChatFollowTailState(isFollowing = false, hasNewContent = true)

        val transition = ChatFollowTailPolicy.onReturnToTail()

        assertTrue(waiting.showAffordance)
        assertTrue(transition.scrollToTail)
        assertTrue(transition.state.isFollowing)
        assertFalse(transition.state.hasNewContent)
        assertFalse(transition.state.showAffordance)
    }

    @Test
    fun `reaching tail manually clears the new-content marker`() {
        val state = ChatFollowTailPolicy.onViewportChanged(
            state = ChatFollowTailState(isFollowing = false, hasNewContent = true),
            isAtTail = true,
            isUserScrollInProgress = false,
        )

        assertTrue(state.isFollowing)
        assertFalse(state.hasNewContent)
    }

    @Test
    fun `layout growth alone does not disable following`() {
        val state = ChatFollowTailPolicy.onViewportChanged(
            state = ChatFollowTailState(),
            isAtTail = false,
            isUserScrollInProgress = false,
        )

        assertTrue(state.isFollowing)
    }
}
