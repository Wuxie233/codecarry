package dev.wuxie233.codecarry.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MarkdownMessagePlanningStateTest {
    @Test fun `streaming retains closed prefix and unchanged source plans`() {
        val state = MarkdownMessagePlanningState()
        val source = "# Heading\n\nClosed paragraph.\n\nOpen tail"
        val first = (state.plan(source) as MarkdownStreamingPlanResult.Success).plan
        val next = (state.plan("$source continues") as MarkdownStreamingPlanResult.Success).plan
        assertSame(first.blocks.first(), next.blocks.first())
        assertSame(next, (state.plan("$source continues") as MarkdownStreamingPlanResult.Success).plan)
        assertEquals("$source continues", next.originalSource)
    }

    @Test fun `replaced content reparses without leaking previous message`() {
        val state = MarkdownMessagePlanningState()
        state.plan("# Old\n\nEarlier text")
        val replaced = (state.plan("New message") as MarkdownStreamingPlanResult.Success).plan
        assertEquals("New message", replaced.blocks.joinToString("") { it.source })
    }
}
