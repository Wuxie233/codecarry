package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.domain.model.Roundtable

internal const val ChatResponseDockTag = "chat-response-dock"
internal const val ChatComposerPrimaryTag = "chat-composer-primary"
internal val ChatResponseDockMaxHeight = 280.dp
internal val ChatComposerPrimaryMinWidth = 160.dp

internal enum class ChatResponseDockKind {
    Retry,
    Roundtable,
    Permission,
    Question,
}

internal data class ChatResponseDockItem(
    val kind: ChatResponseDockKind,
    val ownershipId: String? = null,
) {
    val key: String = "${kind.name.lowercase()}:${ownershipId.orEmpty()}"
}

internal enum class RoundtableDockAction {
    Skip,
    Continue,
}

internal fun buildChatResponseDockItems(
    hasRetry: Boolean,
    roundtableStatus: Roundtable.Status?,
    hasAwaitingSkip: Boolean,
    permissionIds: List<String>,
    questionIds: List<String>,
): List<ChatResponseDockItem> = buildList {
    if (hasRetry) add(ChatResponseDockItem(ChatResponseDockKind.Retry))
    if (
        roundtableStatus == Roundtable.Status.AwaitingCommand || hasAwaitingSkip
    ) {
        add(ChatResponseDockItem(ChatResponseDockKind.Roundtable))
    }
    permissionIds.forEach { add(ChatResponseDockItem(ChatResponseDockKind.Permission, it)) }
    questionIds.forEach { add(ChatResponseDockItem(ChatResponseDockKind.Question, it)) }
}

internal fun roundtableDockActions(
    status: Roundtable.Status?,
    hasAwaitingSkip: Boolean,
): List<RoundtableDockAction> = buildList {
    if (hasAwaitingSkip) add(RoundtableDockAction.Skip)
    if (status == Roundtable.Status.AwaitingCommand) add(RoundtableDockAction.Continue)
}

internal fun Modifier.chatComposerPrimaryWidth(): Modifier =
    widthIn(min = ChatComposerPrimaryMinWidth).testTag(ChatComposerPrimaryTag)

@Composable
internal fun ChatResponseDock(
    items: List<ChatResponseDockItem>,
    responseContent: @Composable (ChatResponseDockItem) -> Unit,
    composerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (items.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ChatResponseDockMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .testTag(ChatResponseDockTag),
            ) {
                items.forEach { item ->
                    key(item.key) {
                        responseContent(item)
                    }
                }
            }
        }
        composerContent()
    }
}
