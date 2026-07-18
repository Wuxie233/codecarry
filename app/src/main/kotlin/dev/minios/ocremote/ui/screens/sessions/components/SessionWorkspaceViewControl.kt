package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.data.preferences.SessionListViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionWorkspaceViewControl(
    currentView: SessionListViewMode,
    activityCount: Int,
    onViewChange: (SessionListViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(SessionListViewMode.ACTIVITY, SessionListViewMode.PROJECTS)
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, view ->
            SegmentedButton(
                selected = currentView == view,
                onClick = { onViewChange(view) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {
                    Icon(
                        imageVector = if (view == SessionListViewMode.PROJECTS) Icons.Default.Folder else Icons.Default.Notifications,
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        when (view) {
                            SessionListViewMode.PROJECTS -> stringResource(R.string.sessions_view_projects)
                            SessionListViewMode.ACTIVITY -> if (activityCount > 0) {
                                stringResource(R.string.sessions_view_activity_count, activityCount)
                            } else {
                                stringResource(R.string.sessions_view_activity)
                            }
                        },
                    )
                },
            )
        }
    }
}
