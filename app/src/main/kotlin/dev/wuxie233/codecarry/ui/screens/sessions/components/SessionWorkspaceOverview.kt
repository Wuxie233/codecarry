package dev.wuxie233.codecarry.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val SessionWorkspaceMaxContentWidth = 960.dp

internal fun sessionWorkspaceContentWidth(availableWidth: Dp): Dp =
    availableWidth.coerceAtMost(SessionWorkspaceMaxContentWidth)

/** Keeps the selected server's recent work and controls in one scannable hierarchy. */
@Composable
internal fun SessionWorkspaceOverview(
    recentWork: (@Composable () -> Unit)?,
    viewControl: @Composable () -> Unit,
    projectControls: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = SessionWorkspaceMaxContentWidth)
                .fillMaxWidth()
                .testTag("session_workspace_overview"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recentWork?.let { content ->
                Box(Modifier.testTag("session_workspace_recent")) {
                    content()
                }
            }
            Box(Modifier.testTag("session_workspace_view_control")) {
                viewControl()
            }
            projectControls?.let { content ->
                Column(
                    modifier = Modifier.testTag("session_workspace_project_controls"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    content()
                }
            }
        }
    }
}
