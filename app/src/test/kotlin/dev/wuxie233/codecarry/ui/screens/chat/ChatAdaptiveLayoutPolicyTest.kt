package dev.wuxie233.codecarry.ui.screens.chat

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class ChatAdaptiveLayoutPolicyTest {

    @Test
    fun `phone width uses replacement context`() {
        val policy = policyForWidth(360)

        assertEquals(WindowWidthSizeClass.Compact, policy.widthClass)
        assertEquals(ChatContextPresentation.Replacement, policy.contextPresentation)
    }

    @Test
    fun `tablet width uses replacement context`() {
        val policy = policyForWidth(700)

        assertEquals(WindowWidthSizeClass.Medium, policy.widthClass)
        assertEquals(ChatContextPresentation.Replacement, policy.contextPresentation)
    }

    @Test
    fun `expanded width uses persistent context`() {
        val policy = policyForWidth(1200)

        assertEquals(WindowWidthSizeClass.Expanded, policy.widthClass)
        assertEquals(ChatContextPresentation.Persistent, policy.contextPresentation)
    }

    private fun policyForWidth(widthDp: Int): ChatAdaptiveLayoutPolicy {
        val windowSizeClass = WindowSizeClass.calculateFromSize(
            DpSize(width = widthDp.dp, height = 800.dp),
        )
        return chatAdaptiveLayoutPolicy(windowSizeClass)
    }
}
