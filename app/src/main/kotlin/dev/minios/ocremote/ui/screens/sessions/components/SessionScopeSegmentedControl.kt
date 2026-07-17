package dev.minios.ocremote.ui.screens.sessions.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.data.preferences.SessionScope

/**
 * Top-level switch between [SessionScope.INBOX] (active sessions) and
 * [SessionScope.ARCHIVED] (archive vault).
 *
 * - Inbox tab shows the Inbox icon and label.
 * - Archived tab shows the Archive icon and, when [archivedCount] > 0,
 *   appends the count in parentheses. When [archivedCount] == 0 we
 *   omit the count entirely (cleaner empty state).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScopeSegmentedControl(
    currentScope: SessionScope,
    archivedCount: Int,
    onScopeChange: (SessionScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(SessionScope.INBOX, SessionScope.ARCHIVED)

    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        options.forEachIndexed { index, scope ->
            val selected = scope == currentScope
            SegmentedButton(
                selected = selected,
                onClick = { onScopeChange(scope) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    Icon(
                        imageVector = when (scope) {
                            SessionScope.INBOX -> Icons.Default.Inbox
                            SessionScope.ARCHIVED -> Icons.Default.Archive
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = when (scope) {
                                SessionScope.INBOX -> stringResource(R.string.sessions_scope_inbox)
                                SessionScope.ARCHIVED -> if (archivedCount > 0) {
                                    stringResource(R.string.sessions_scope_archived_with_count, archivedCount)
                                } else {
                                    stringResource(R.string.sessions_scope_archived)
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}
