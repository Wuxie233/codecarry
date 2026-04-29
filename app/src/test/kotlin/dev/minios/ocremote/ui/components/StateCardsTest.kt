package dev.minios.ocremote.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCardsTest {

    @Test
    fun stateCardComposableSymbols_areExported() {
        val exportedNames = Class
            .forName("dev.minios.ocremote.ui.components.StateCardsKt")
            .declaredMethods
            .map { it.name }
            .toSet()

        assertTrue(exportedNames.contains("LoadingStateCard"))
        assertTrue(exportedNames.contains("EmptyStateCard"))
        assertTrue(exportedNames.contains("ErrorStateCard"))
    }

    @Test
    fun errorStateCard_definesRetryActionText() {
        val source = java.io.File("src/main/kotlin/dev/minios/ocremote/ui/components/StateCards.kt").readText()

        assertTrue(source.contains("Retry"))
    }

    @Test
    fun stateCards_useThemeColorTokensForTitleAndBodyText() {
        val source = java.io.File("src/main/kotlin/dev/minios/ocremote/ui/components/StateCards.kt").readText()

        val titleTextColorUses = Regex(
            """Text\([\s\S]*?text\s*=\s*title,[\s\S]*?color\s*=\s*MaterialTheme\.colorScheme\.onSurface(?!Variant)""",
        ).findAll(source).count()
        val bodyTextColorUses = Regex(
            """Text\([\s\S]*?text\s*=\s*message,[\s\S]*?color\s*=\s*MaterialTheme\.colorScheme\.onSurfaceVariant""",
        ).findAll(source).count()

        assertTrue("Expected title text to use MaterialTheme.colorScheme.onSurface", titleTextColorUses >= 2)
        assertTrue("Expected body text to use MaterialTheme.colorScheme.onSurfaceVariant", bodyTextColorUses >= 2)
    }

    @Test
    fun errorStateCard_retryActionHasMinimumTouchTarget() {
        val source = java.io.File("src/main/kotlin/dev/minios/ocremote/ui/components/StateCards.kt").readText()

        val retryButtonMinimumTarget = Regex(
            """Button\([\s\S]*?onClick\s*=\s*retry,[\s\S]*?defaultMinSize\(\s*minWidth\s*=\s*48\.dp,\s*minHeight\s*=\s*48\.dp\s*\)""",
        )

        assertTrue(retryButtonMinimumTarget.containsMatchIn(source))
    }

    @Test
    fun errorStateCard_retryActionHasContentDescription() {
        val source = java.io.File("src/main/kotlin/dev/minios/ocremote/ui/components/StateCards.kt").readText()

        val retryButtonContentDescription = Regex(
            """Button\([\s\S]*?onClick\s*=\s*retry,[\s\S]*?\.semantics\s*\{\s*contentDescription\s*=\s*"Retry loading state"\s*\}[\s\S]*?\)\s*\{\s*Text\("Retry"\)\s*\}""",
        )
        assertTrue(retryButtonContentDescription.containsMatchIn(source))
    }
}

@Suppress("unused")
@Composable
private fun StateCardsSmokeContent() {
    LoadingStateCard(label = "Loading MCP servers")
    EmptyStateCard(
        title = "No MCP servers",
        message = "Configure one to get started.",
        action = { Text("Configure") },
    )
    ErrorStateCard(
        title = "Could not load MCP servers",
        message = "Try again.",
        onRetry = {},
    )
}
