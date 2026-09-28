package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockItem
import dev.wuxie233.codecarry.ui.screens.chat.ChatResponseDockTag

/** Local operation failures stay visible independently of the history scroll position. */
@Composable
internal fun CodexOperationErrorNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var detailsOpen by remember(message) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(stringResource(R.string.chat_failure_operation), style = MaterialTheme.typography.labelMedium)
                Text(message, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { detailsOpen = true }) { Text(stringResource(R.string.chat_failure_details)) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
    if (detailsOpen) AlertDialog(
        onDismissRequest = { detailsOpen = false },
        title = { Text(stringResource(R.string.chat_failure_operation)) },
        text = {
            SelectionContainer {
                Text(message, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            TextButton(onClick = { detailsOpen = false }) { Text(stringResource(R.string.close)) }
        },
    )
}

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
