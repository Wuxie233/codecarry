package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.ui.screens.chat.ChatFollowTailPolicy
import dev.wuxie233.codecarry.ui.screens.chat.ChatFollowTailState
import dev.wuxie233.codecarry.ui.screens.chat.ChatTailAffordance
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A single scroll owner distinguishes a user's drag from stream-driven layout changes. */
@Composable
internal fun CodexTimelineViewport(
    contentKey: Any?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onFollowTailChanged: (Boolean) -> Unit = {},
    manualNavigationKey: Any? = null,
    content: LazyListScope.() -> Unit,
) {
    var followState by remember(listState) { mutableStateOf(ChatFollowTailState()) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var userScrollInProgress by remember(listState) { mutableStateOf(false) }
    var lastManualNavigationKey by remember(listState) { mutableStateOf(manualNavigationKey) }
    val scope = rememberCoroutineScope()
    val atTail by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            last == null || (
                last.index == layout.totalItemsCount - 1 &&
                    last.offset + last.size <= layout.viewportEndOffset + 2
                )
        }
    }

    suspend fun scrollToTail() {
        val count = listState.layoutInfo.totalItemsCount
        if (count == 0) return
        listState.scrollToItem(count - 1)
        // A final item may be taller than the entire viewport. Align its bottom,
        // including content padding, rather than stopping at its first line.
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull { it.index == count - 1 }
        if (last != null) {
            val overflow = last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset
            if (overflow > 0) listState.scrollBy(overflow.toFloat())
        }
    }

    // A deliberate disclosure click is navigation too: keep its reading anchor
    // instead of pushing the newly expanded activity above the viewport.
    LaunchedEffect(manualNavigationKey, listState) {
        if (lastManualNavigationKey != manualNavigationKey) {
            lastManualNavigationKey = manualNavigationKey
            followState = ChatFollowTailPolicy.onManualNavigation(followState)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { Triple(dragged, listState.isScrollInProgress, atTail) }.collect { (isDragged, scrolling, isAtTail) ->
            if (isDragged) userScrollInProgress = true
            followState = ChatFollowTailPolicy.onViewportChanged(
                state = followState,
                isAtTail = isAtTail,
                isUserScrollInProgress = userScrollInProgress,
            )
            if (!isDragged && !scrolling) userScrollInProgress = false
        }
    }

    // Read model (issue #30): replies count as presented only while the timeline
    // follows the tail; browsing older history keeps not-yet-seen replies unread.
    LaunchedEffect(listState) {
        snapshotFlow { followState.isFollowing }.collect { following -> onFollowTailChanged(following) }
    }

    LaunchedEffect(contentKey, listState) {
        // Initial composition can precede the first LazyColumn measure.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        // Frame callbacks run before layout; a second frame lets the newly
        // composed items finish measurement before reading their bottom edge.
        withFrameNanos { }
        withFrameNanos { }
        if (dragged || userScrollInProgress) {
            followState = ChatFollowTailPolicy.onManualNavigation(followState)
        }
        val transition = ChatFollowTailPolicy.onContentChanged(followState, hasContent = true)
        followState = transition.state
        if (transition.scrollToTail) scrollToTail()
    }

    // Markdown/WebView/image measurement can finish after the model's content key has
    // stopped changing. Keep the measured tail aligned too; otherwise a correct final
    // reply can briefly appear and then move below the viewport on the later layout.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            CodexTailMeasurement(
                itemCount = layout.totalItemsCount,
                viewportEnd = layout.viewportEndOffset,
                lastIndex = last?.index,
                lastOffset = last?.offset,
                lastSize = last?.size,
                scrolling = listState.isScrollInProgress,
                dragging = dragged,
            )
        }.collect { measured ->
            if (measured.itemCount > 0 && !measured.scrolling && !measured.dragging &&
                !userScrollInProgress && followState.isFollowing && !atTail
            ) {
                scrollToTail()
            }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
        if (!atTail && followState.showAffordance) {
            ChatTailAffordance(
                hasNewContent = followState.hasNewContent,
                onClick = {
                    scope.launch {
                        userScrollInProgress = false
                        followState = ChatFollowTailPolicy.onReturnToTail().state
                        scrollToTail()
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            )
        }
    }
}

private data class CodexTailMeasurement(
    val itemCount: Int,
    val viewportEnd: Int,
    val lastIndex: Int?,
    val lastOffset: Int?,
    val lastSize: Int?,
    val scrolling: Boolean,
    val dragging: Boolean,
)
