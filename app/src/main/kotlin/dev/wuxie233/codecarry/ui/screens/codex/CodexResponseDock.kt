package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockItem
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockTag

/** The caller consumes IME insets; measure the remaining space instead of subtracting twice. */
@Composable
internal fun CodexResponseDock(
    items: List<ChatResponseDockItem>,
    responseContent: @Composable (ChatResponseDockItem) -> Unit,
    composerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val responseHeight = minOf(280.dp, maxHeight * 0.4f)
        Column {
            if (items.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = responseHeight)
                        .verticalScroll(rememberScrollState()).testTag(ChatResponseDockTag),
                ) {
                    items.forEach { item -> key(item.key) { responseContent(item) } }
                }
            }
            composerContent()
        }
    }
}
