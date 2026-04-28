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
