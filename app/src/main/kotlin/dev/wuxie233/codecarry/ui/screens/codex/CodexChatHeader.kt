package dev.wuxie233.codecarry.ui.screens.codex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wuxie233.codecarry.R

/** One navigation row; full workspace and status remain available through the title. */
@Composable
internal fun CodexChatHeader(
    title: String,
    context: String,
    status: String,
    onNavigateBack: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenOverflow: () -> Unit,
    actions: @Composable () -> Unit = {},
    overflowMenu: @Composable () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), Modifier.size(22.dp))
            }
            Column(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpenStatus)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(context.trimEnd('/').substringAfterLast('/').ifBlank { context }, status)
                        .filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            actions()
            Box {
                IconButton(onClick = onOpenOverflow) {
                    Icon(Icons.Default.MoreHoriz, stringResource(R.string.more_options), Modifier.size(22.dp))
                }
                overflowMenu()
            }
        }
    }
}
