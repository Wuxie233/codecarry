package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R

internal enum class ChatHeaderDensity {
    Compact,
    Expanded,
}

internal data class ChatHeaderLayoutPolicy(
    val density: ChatHeaderDensity,
    val titleMaxCharacters: Int,
    val contextMaxCharacters: Int,
    val showSecondaryActionsInline: Boolean,
    val minimumActionSizeDp: Int = 48,
)

internal fun chatHeaderLayoutPolicy(availableWidthDp: Int): ChatHeaderLayoutPolicy =
    if (availableWidthDp < 600) {
        ChatHeaderLayoutPolicy(
            density = ChatHeaderDensity.Compact,
            titleMaxCharacters = 28,
            contextMaxCharacters = 28,
            showSecondaryActionsInline = false,
        )
    } else {
        ChatHeaderLayoutPolicy(
            density = ChatHeaderDensity.Expanded,
            titleMaxCharacters = 72,
            contextMaxCharacters = 64,
            showSecondaryActionsInline = true,
        )
    }

internal fun truncateChatHeaderTitle(title: String, maxCharacters: Int): String =
    title.truncateEnd(maxCharacters)

internal fun truncateChatHeaderContext(context: String, maxCharacters: Int): String =
    context.truncateMiddle(maxCharacters)

private fun String.truncateEnd(maxCharacters: Int): String {
    if (length <= maxCharacters) return this
    if (maxCharacters <= 0) return ""
    if (maxCharacters == 1) return "…"
    return take(maxCharacters - 1) + "…"
}

private fun String.truncateMiddle(maxCharacters: Int): String {
    if (length <= maxCharacters) return this
    if (maxCharacters <= 0) return ""
    if (maxCharacters == 1) return "…"
    val visibleCharacters = maxCharacters - 1
    val prefixLength = visibleCharacters / 2
    val suffixLength = visibleCharacters - prefixLength
    return take(prefixLength) + "…" + takeLast(suffixLength)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatHeader(
    title: String,
    context: String,
    backendLabel: String,
    statusLabel: String,
    usageSummary: String?,
    canStop: Boolean,
    showSubagents: Boolean,
    runningSubagentCount: Int,
    showTerminal: Boolean,
    showOverflow: Boolean,
    onNavigateBack: () -> Unit,
    onStop: () -> Unit,
    onToggleSubagents: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenOverflow: () -> Unit,
    overflowMenu: @Composable (ChatHeaderDensity) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val policy = chatHeaderLayoutPolicy(maxWidth.value.toInt())
        val displayTitle = truncateChatHeaderTitle(title, policy.titleMaxCharacters)
        val displayContext = truncateChatHeaderContext(context, policy.contextMaxCharacters)
        val metadata = listOfNotNull(
            displayContext.takeIf(String::isNotBlank),
            backendLabel.takeIf(String::isNotBlank),
            statusLabel.takeIf(String::isNotBlank),
            usageSummary?.takeIf(String::isNotBlank),
        ).joinToString(" · ")

        TopAppBar(
            title = {
                Column {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (metadata.isNotBlank()) {
                        Text(
                            text = metadata,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                HeaderAction(onClick = onNavigateBack, policy = policy) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                    )
                }
            },
            actions = {
                if (canStop) {
                    HeaderAction(onClick = onStop, policy = policy) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (showSubagents && policy.showSecondaryActionsInline) {
                    HeaderAction(onClick = onToggleSubagents, policy = policy) {
                        BadgedBox(
                            badge = {
                                if (runningSubagentCount > 0) {
                                    Badge { Text(runningSubagentCount.toString()) }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = stringResource(
                                    R.string.chat_subagents_open_count,
                                    runningSubagentCount,
                                ),
                            )
                        }
                    }
                }
                if (showTerminal && policy.showSecondaryActionsInline) {
                    HeaderAction(onClick = onOpenTerminal, policy = policy) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = stringResource(R.string.tool_terminal),
                        )
                    }
                }
                if (showOverflow) {
                    Box {
                        HeaderAction(onClick = onOpenOverflow, policy = policy) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        overflowMenu(policy.density)
                    }
                }
            },
        )
    }
}

@Composable
internal fun ChatHeaderCompactOverflowActions(
    density: ChatHeaderDensity,
    showSubagents: Boolean,
    runningSubagentCount: Int,
    showTerminal: Boolean,
    onToggleSubagents: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    if (density != ChatHeaderDensity.Compact) return

    if (showSubagents) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_subagents_title)) },
            onClick = onToggleSubagents,
            leadingIcon = {
                BadgedBox(
                    badge = {
                        if (runningSubagentCount > 0) {
                            Badge { Text(runningSubagentCount.toString()) }
                        }
                    },
                ) {
                    Icon(Icons.Default.SmartToy, contentDescription = null)
                }
            },
        )
    }
    if (showTerminal) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tool_terminal)) },
            onClick = onOpenTerminal,
            leadingIcon = {
                Icon(Icons.Default.Terminal, contentDescription = null)
            },
        )
    }
}

@Composable
private fun HeaderAction(
    onClick: () -> Unit,
    policy: ChatHeaderLayoutPolicy,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(policy.minimumActionSizeDp.dp),
    ) {
        content()
    }
}
