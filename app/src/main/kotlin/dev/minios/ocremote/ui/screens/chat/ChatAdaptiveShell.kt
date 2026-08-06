package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class ChatContextPresentation {
    Replacement,
    Persistent,
}

@Immutable
internal data class ChatAdaptiveLayoutPolicy(
    val widthClass: WindowWidthSizeClass,
    val contextPresentation: ChatContextPresentation,
)

internal fun chatAdaptiveLayoutPolicy(windowSizeClass: WindowSizeClass): ChatAdaptiveLayoutPolicy {
    val widthClass = windowSizeClass.widthSizeClass
    return ChatAdaptiveLayoutPolicy(
        widthClass = widthClass,
        contextPresentation = if (widthClass == WindowWidthSizeClass.Expanded) {
            ChatContextPresentation.Persistent
        } else {
            ChatContextPresentation.Replacement
        },
    )
}

@Composable
internal fun ChatAdaptiveShell(
    windowSizeClass: WindowSizeClass,
    contextVisible: Boolean,
    modifier: Modifier = Modifier,
    primaryContent: @Composable () -> Unit,
    contextContent: @Composable (Modifier) -> Unit,
) {
    val policy = chatAdaptiveLayoutPolicy(windowSizeClass)
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val verticalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(horizontalInsets),
    ) {
        if (contextVisible && policy.contextPresentation == ChatContextPresentation.Replacement) {
            contextContent(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(verticalInsets),
            )
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    primaryContent()
                }

                if (contextVisible) {
                    contextContent(
                        Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .windowInsetsPadding(verticalInsets),
                    )
                }
            }
        }
    }
}
