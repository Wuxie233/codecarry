package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderPolicyTest {

    @Test
    fun compactPolicyKeepsControlsTouchSizedAndLimitsText() {
        val policy = chatHeaderLayoutPolicy(359)

        assertEquals(ChatHeaderDensity.Compact, policy.density)
        assertEquals(48, policy.minimumActionSizeDp)
        assertEquals(false, policy.showSecondaryActionsInline)
        assertEquals(28, policy.titleMaxCharacters)
        assertEquals(28, policy.contextMaxCharacters)
    }

    @Test
    fun expandedPolicyRetainsMoreTitleAndContext() {
        val policy = chatHeaderLayoutPolicy(840)

        assertEquals(ChatHeaderDensity.Expanded, policy.density)
        assertEquals(72, policy.titleMaxCharacters)
        assertEquals(64, policy.contextMaxCharacters)
        assertEquals(true, policy.showSecondaryActionsInline)
    }

    @Test
    fun titleTruncationKeepsTheLeadingSessionIdentity() {
        assertEquals(
            "Investigate streaming res…",
            truncateChatHeaderTitle("Investigate streaming response recovery", 26),
        )
    }

    @Test
    fun contextTruncationKeepsBothPathRootAndProjectLeaf() {
        assertEquals(
            "/srv/workspa…emote-android",
            truncateChatHeaderContext("/srv/workspaces/client/apps/oc-remote-android", 26),
        )
    }
}
